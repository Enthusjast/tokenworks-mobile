package cn.edu.ustc.tokenworks

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** No HTML, WebView, external URI, or filename-based decoder. Render one bounded page at a time. */
@Composable
fun FilePreviewScreen(file: StagedFile, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    var page by remember(file) { mutableIntStateOf(0) }
    var pages by remember(file) { mutableIntStateOf(0) }
    var truncated by remember(file) { mutableStateOf(false) }
    val resource = remember(file, page) { PreviewBitmap() }
    var bitmap by remember(resource) { mutableStateOf<Bitmap?>(null) }
    var text by remember(resource) { mutableStateOf<String?>(null) }
    var status by remember(resource) { mutableStateOf<Int?>(null) }
    var loading by remember(resource) { mutableStateOf(true) }
    DisposableEffect(resource) { onDispose { resource.close() } }
    LaunchedEffect(resource) {
        try {
            val result = withContext(Dispatchers.IO) {
                previewDecoder.withLock {
                    when {
                        file.sizeBytes > FilePreview.MAX_PREVIEW_BYTES -> PreviewInfo(status = R.string.files_audit_large_preview)
                        file.mediaType in FilePreview.IMAGE_TYPES -> {
                            resource.decodeImage(file)
                            PreviewInfo()
                        }
                        file.mediaType == "application/pdf" -> resource.decodePdf(context, file, page)
                        FilePreview.previewable(file.mediaType) -> {
                            val plain = FilePreview.decodeText(file.file) ?: error("Invalid preview text")
                            val end = if (plain.getOrNull(MAX_DISPLAY_TEXT - 1)?.isHighSurrogate() == true) MAX_DISPLAY_TEXT - 1 else MAX_DISPLAY_TEXT
                            PreviewInfo(text = plain.take(end), status = R.string.files_audit_text_truncated.takeIf { plain.length > end })
                        }
                        else -> PreviewInfo(status = R.string.files_audit_unsupported_preview)
                    }
                }
            }
            status = result.status
            text = result.text
            pages = result.pages
            truncated = result.truncated
            bitmap = resource.get()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { status = R.string.files_audit_preview_failed }
        finally { loading = false }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.files_audit_rendering))
        }
        if (pages > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton({ page-- }, enabled = !loading && page > 0, modifier = Modifier.weight(1f).heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                    Text(stringResource(R.string.files_audit_previous_page))
                }
                TextButton({ page++ }, enabled = !loading && page + 1 < pages, modifier = Modifier.weight(1f).heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                    Text(stringResource(R.string.files_audit_next_page))
                }
            }
        }
        if (pages > 0) Text(stringResource(R.string.files_audit_pdf_position, page + 1, pages), style = MaterialTheme.typography.labelLarge)
        if (truncated) Text(stringResource(R.string.files_audit_pdf_truncated, pages), style = MaterialTheme.typography.bodySmall)
        status?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
        bitmap?.let {
            val description = if (pages > 0) stringResource(R.string.files_audit_pdf_description, file.name, page + 1)
                else stringResource(R.string.files_audit_image_description, file.name)
            Image(it.asImageBitmap(), contentDescription = description, modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp))
        }
        text?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodyMedium) } }
    }
}

private const val MAX_DISPLAY_TEXT = 32_768
private val previewDecoder = Mutex()
private data class PreviewInfo(val text: String? = null, val status: Int? = null, val pages: Int = 0, val truncated: Boolean = false)

/** Caps decoded pixel memory independently of the encoded 20 MiB limit. */
private class PreviewBitmap : AutoCloseable {
    private var bitmap: Bitmap? = null
    private var closed = false
    @Synchronized fun get(): Bitmap? = bitmap
    @Synchronized private fun publish(value: Bitmap) { if (closed) value.recycle() else { bitmap?.recycle(); bitmap = value } }
    fun decodeImage(file: StagedFile) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.file.path, options)
        val width = options.outWidth; val height = options.outHeight
        require(width in 1..32_768 && height in 1..32_768 && width.toLong() * height <= 100_000_000L)
        var sample = 1
        while (((width.toLong() + sample - 1) / sample) * ((height.toLong() + sample - 1) / sample) > 2_000_000L ||
            (maxOf(width, height) + sample - 1) / sample > 2048) sample *= 2
        options.inJustDecodeBounds = false; options.inSampleSize = sample; options.inPreferredConfig = Bitmap.Config.ARGB_8888
        val result = BitmapFactory.decodeFile(file.file.path, options) ?: error("image.invalid")
        if (result.allocationByteCount > 8_000_000) { result.recycle(); error("image.budget") }
        publish(result)
    }
    suspend fun decodePdf(context: Context, file: StagedFile, index: Int): PreviewInfo {
        val page = renderPdfPreviewPage(context, file.file, index)
        publish(page.bitmap)
        currentCoroutineContext().ensureActive()
        return PreviewInfo(pages = page.pages, truncated = page.truncated)
    }
    @Synchronized override fun close() { closed = true; bitmap?.recycle(); bitmap = null }
}
