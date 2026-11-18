package cn.edu.ustc.tokenworks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.floor

private enum class ShareOperation(val label: Int) {
    READING(R.string.session_share_reading), CHOOSING(R.string.session_share_choosing),
    SAVING(R.string.session_share_saving), PUBLISHING(R.string.session_share_publishing),
}

private class SharedLink(val url: String, val expiresAt: Instant)
private class DocumentSelection {
    val result = CompletableDeferred<Uri?>()
    var uri: Uri? = null
        private set
    fun finish(uri: Uri?) { this.uri = uri; result.complete(uri) }
}
private data class ShareNotice(val text: Int, val tone: TokenWorksNoticeTone)

@Composable
internal fun SessionShareDialog(
    sessionId: String,
    title: String,
    transport: DshTransport?,
    api: RemoteApi?,
    subagent: DshSubagentAddress? = null,
    onDismiss: () -> Unit,
) = key(sessionId, subagent) {
    val address = remember(sessionId, subagent) { sessionHistoryAddress(sessionId, subagent) }
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    val currentApi by rememberUpdatedState(api)
    var operation by remember { mutableStateOf<ShareOperation?>(null) }
    var task by remember { mutableStateOf<Job?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<ShareNotice?>(null) }
    var selection by remember { mutableStateOf<DocumentSelection?>(null) }
    var link by remember { mutableStateOf<SharedLink?>(null) }

    fun error(text: Int) { notice = ShareNotice(text, TokenWorksNoticeTone.ERROR) }
    fun success(text: Int) { notice = ShareNotice(text, TokenWorksNoticeTone.SUCCESS) }
    fun currentRoute(transport: DshTransport) {
        when (transport.state.value.phase) {
            DshTransportPhase.AUTH_REQUIRED, DshTransportPhase.REVOKED, DshTransportPhase.CLOSED -> throw DshFailure("access.denied")
            else -> Unit
        }
    }
    fun received(uri: Uri?) {
        val pending = selection
        if (pending != null) pending.finish(uri)
        else if (uri != null) scope.launch {
            if (!withContext(NonCancellable + Dispatchers.IO) { removeDocument(resolver, uri) }) error(R.string.session_share_cleanup_failed)
        }
    }
    val jsonl = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(SessionExportFormat.JSONL.mime), ::received)
    val markdown = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(SessionExportFormat.MARKDOWN.mime), ::received)

    fun startExport(format: SessionExportFormat) {
        if (operation != null) return
        val source = transport ?: return
        notice = null
        operation = ShareOperation.READING
        task = scope.launch {
            var staged: StagedSessionHistory? = null
            var destination: DocumentSelection? = null
            var saved = false
            try {
                withContext(Dispatchers.IO) {
                    val copy = if (format == SessionExportFormat.MARKDOWN) SessionHistoryMarkdownCopy.from(context.resources) else null
                    val snapshot = StagedSessionHistory(context.cacheDir, copy)
                    staged = snapshot
                    source.connect().use { snapshot.collect(it, address, title, format) }
                }
                currentRoute(source)
                operation = ShareOperation.CHOOSING
                val pending = DocumentSelection()
                destination = pending
                selection = pending
                val filename = "tokenworks-session.${format.extension}"
                when (format) {
                    SessionExportFormat.JSONL -> jsonl.launch(filename)
                    SessionExportFormat.MARKDOWN -> markdown.launch(filename)
                }
                val target = pending.result.await() ?: return@launch
                operation = ShareOperation.SAVING
                withContext(Dispatchers.IO) {
                    val output = resolver.openOutputStream(target, "wt") ?: throw IOException("document unavailable")
                    // The exact cut is staged. SAF backgrounds the app and may legitimately close the Host route.
                    output.use { checkNotNull(staged).writeTo(it); ensureActive() }
                    saved = true
                }
                success(R.string.session_share_saved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error(if (operation == ShareOperation.READING) R.string.session_share_read_failed else R.string.session_share_save_failed)
            } finally {
                if (selection === destination) selection = null
                val unfinished = destination?.uri?.takeUnless { saved }
                withContext(NonCancellable + Dispatchers.IO) {
                    if (unfinished != null && !removeDocument(resolver, unfinished)) {
                        withContext(Dispatchers.Main.immediate) { error(R.string.session_share_cleanup_failed) }
                    }
                    staged?.close()
                }
                operation = null
                task = null
            }
        }
    }

    fun publish() {
        if (operation != null || currentApi == null) return
        val source = transport ?: return
        confirming = false
        notice = null
        operation = ShareOperation.READING
        task = scope.launch {
            try {
                val sealed = withContext(Dispatchers.IO) {
                    StagedSessionHistory(context.cacheDir, SessionHistoryMarkdownCopy.from(context.resources)).use { snapshot ->
                        source.connect().use {
                            snapshot.collect(it, address, title, SessionExportFormat.MARKDOWN, SessionSharePublication.MAX_BYTES.toLong())
                        }
                        snapshot.seal()
                    }
                }
                currentRoute(source)
                operation = ShareOperation.PUBLISHING
                val published = (currentApi ?: throw RemoteRequestException("auth.expired")).createSessionShare(sealed.ciphertext)
                currentRoute(source)
                link = SharedLink("${published.readerUrl}#${sealed.fragment}", published.expiresAt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SessionShareSizeException) {
                error(R.string.session_share_too_large)
            } catch (failure: Exception) {
                error(when {
                    operation == ShareOperation.READING -> R.string.session_share_read_failed
                    (failure as? RemoteRequestException)?.code == "rate.limited" -> R.string.session_share_rate_limited
                    else -> R.string.session_share_publish_failed
                })
            } finally {
                operation = null
                task = null
            }
        }
    }

    val shared = link
    LaunchedEffect(shared) {
        if (shared != null) {
            delay(Duration.between(Instant.now(), shared.expiresAt).toMillis().coerceAtLeast(0))
            link = null
            notice = ShareNotice(R.string.session_share_expired, TokenWorksNoticeTone.INFO)
        }
    }
    val active = operation
    val dismiss = { if (confirming) confirming = false else if (active == null) onDismiss() }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(if (confirming) R.string.session_share_confirm_title else R.string.session_share_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                if (confirming) {
                    TokenWorksStatusNotice(stringResource(R.string.session_share_confirm_detail), tone = TokenWorksNoticeTone.WARNING)
                } else {
                    if (shared == null) {
                        Text(stringResource(R.string.session_share_scope), style = MaterialTheme.typography.bodyMedium)
                        if (active == null && transport == null) {
                            TokenWorksStatusNotice(stringResource(R.string.session_share_wait_route), tone = TokenWorksNoticeTone.INFO)
                        } else if (active == null) {
                            ShareAction(R.string.session_export_jsonl) { startExport(SessionExportFormat.JSONL) }
                            ShareAction(R.string.session_export_markdown) { startExport(SessionExportFormat.MARKDOWN) }
                            if (api != null) ShareAction(R.string.session_share_create) { confirming = true }
                            else Text(stringResource(R.string.session_share_need_account), style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text(stringResource(R.string.session_share_ready))
                        val expires = remember(shared) {
                            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(shared.expiresAt)
                        }
                        Text(stringResource(R.string.session_share_expires, expires))
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { ShareQr(shared.url) }
                        ShareAction(R.string.session_share_copy) {
                            if (Instant.now() < shared.expiresAt) {
                                val clip = ClipData.newPlainText(context.getString(R.string.session_share_title), shared.url)
                                clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                if (clipboard == null) error(R.string.session_share_copy_failed)
                                else { clipboard.setPrimaryClip(clip); success(R.string.session_share_copied) }
                            }
                        }
                        ShareAction(R.string.session_share_send) {
                            if (Instant.now() < shared.expiresAt) {
                                val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shared.url)
                                try { context.startActivity(Intent.createChooser(intent, null)) }
                                catch (_: android.content.ActivityNotFoundException) { error(R.string.session_share_send_failed) }
                            }
                        }
                    }
                    if (active != null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        TokenWorksStatusNotice(stringResource(active.label), tone = TokenWorksNoticeTone.INFO)
                    }
                    notice?.let { TokenWorksStatusNotice(stringResource(it.text), tone = it.tone) }
                }
            }
        },
        confirmButton = {
            when {
                confirming -> Button(onClick = ::publish, enabled = api != null && transport != null,
                    modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                    Text(stringResource(R.string.session_share_confirm))
                }
                active == null -> TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                    Text(stringResource(R.string.session_share_close))
                }
                else -> TextButton(onClick = { task?.cancel() }, enabled = active != ShareOperation.CHOOSING,
                    modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text(stringResource(R.string.session_share_cancel_operation)) }
            }
        },
        dismissButton = {
            if (confirming) TextButton(onClick = { confirming = false }, modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                Text(stringResource(R.string.session_share_cancel))
            }
        },
    )
}

@Composable
private fun ShareAction(label: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
        Text(stringResource(label))
    }
}

@Composable
private fun ShareQr(url: String) {
    val bits = remember(url) { QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 0, 0) }
    val description = stringResource(R.string.session_share_qr_description)
    Canvas(Modifier.widthIn(max = 232.dp).fillMaxWidth().aspectRatio(1f).semantics {
        role = Role.Image
        contentDescription = description
    }) {
        val module = floor(size.minDimension / bits.width)
        val left = floor((size.width - bits.width * module) / 2)
        val top = floor((size.height - bits.height * module) / 2)
        drawRect(Color.White)
        for (row in 0 until bits.height) {
            var column = 0
            while (column < bits.width) {
                if (!bits[column, row]) { column++; continue }
                val start = column
                while (column < bits.width && bits[column, row]) column++
                drawRect(Color.Black, Offset(left + start * module, top + row * module), Size((column - start) * module, module))
            }
        }
    }
}

private fun removeDocument(resolver: ContentResolver, uri: Uri): Boolean =
    runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
