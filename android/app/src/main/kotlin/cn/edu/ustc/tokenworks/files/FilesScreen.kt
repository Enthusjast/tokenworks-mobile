package cn.edu.ustc.tokenworks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.OutputStream

@Composable
internal fun FilesScreen(coordinator: FilesCoordinator, sessionId: String, onBack: () -> Unit) {
    val state by coordinator.state.collectAsState()
    val context = LocalContext.current
    var pane by rememberSaveable(sessionId) { mutableStateOf(FilesPane.ATTACHMENTS) }
    var pickerError by remember(coordinator) { mutableStateOf(false) }
    val saveWorkspaceFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        coordinator.finishWorkspaceSavePicker(uri?.let { DocumentExportTarget(context, it) })
    }
    val leave = {
        coordinator.cancel()
        onBack()
    }
    BackHandler {
        val parts = state.workspaceListing?.parts.orEmpty()
        if (pane == FilesPane.WORKSPACE && parts.isNotEmpty() && !state.busy) coordinator.browseWorkspace(parts.dropLast(1))
        else leave()
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        TabRow(selectedTabIndex = pane.ordinal) {
            FilesPane.entries.forEach { item ->
                Tab(selected = pane == item, enabled = !state.pickerActive, onClick = {
                    pane = item
                    if (item == FilesPane.WORKSPACE && state.workspaceListing == null) coordinator.browseWorkspace()
                }, modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget),
                    text = { Text(stringResource(item.label)) })
            }
        }
        if (pane == FilesPane.EXPORTS && pickerError) Box(Modifier.padding(horizontal = TokenWorksSpacing.lg)) {
            TokenWorksStatusNotice(stringResource(R.string.workspace_files_picker_unavailable), tone = TokenWorksNoticeTone.ERROR)
        }
        when (pane) {
            FilesPane.ATTACHMENTS -> FileAttachmentsPanel(coordinator, sessionId, leave)
            FilesPane.WORKSPACE -> WorkspaceBrowser(state, coordinator::browseWorkspace, coordinator::addWorkspaceExport, coordinator::cancel, leave)
            FilesPane.EXPORTS -> ExportsScreen(state, { file ->
                pickerError = false
                if (coordinator.startWorkspaceSavePicker(file)) {
                    try { saveWorkspaceFile.launch(FilePreview.displayName(file.name)) }
                    catch (_: Exception) { coordinator.finishWorkspaceSavePicker(null); pickerError = true }
                }
            }, coordinator::removeWorkspaceExport, coordinator::cancel, leave)
        }
    }
}

private enum class FilesPane(val label: Int) {
    ATTACHMENTS(R.string.workspace_files_attachments),
    WORKSPACE(R.string.workspace_files_browser),
    EXPORTS(R.string.workspace_files_exports),
}

@Composable
private fun FileAttachmentsPanel(coordinator: FilesCoordinator, sessionId: String, onBack: () -> Unit) {
    val state by coordinator.state.collectAsState()
    val context = LocalContext.current
    val actions = rememberCoroutineScope()
    var path by remember(coordinator) { mutableStateOf("") }
    var platformError by remember(coordinator) { mutableStateOf<String?>(null) }
    val currentSession by rememberUpdatedState(sessionId)
    var imageSelectionSession by remember(coordinator) { mutableStateOf<String?>(null) }
    val selectImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        coordinator.pickerFinished()
        val selectedFor = imageSelectionSession
        imageSelectionSession = null
        if (uri != null && selectedFor != null && selectedFor == currentSession) actions.launch {
            try {
                val name = withContext(Dispatchers.IO) {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (!cursor.moveToFirst()) throw RemoteRequestException("files.document_unavailable")
                        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (column >= 0) cursor.getString(column) ?: "image" else "image"
                    } ?: throw RemoteRequestException("files.document_unavailable")
                }
                if (selectedFor == currentSession) coordinator.selectImage(selectedFor, name) {
                    context.contentResolver.openInputStream(uri) ?: throw RemoteRequestException("files.document_unavailable")
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { platformError = "无法读取所选图片，请检查文件提供程序的访问权限。" }
        }
    }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        coordinator.pickerFinished()
        if (uri != null) actions.launch {
            try {
                val metadata = withContext(Dispatchers.IO) {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (!cursor.moveToFirst()) throw RemoteRequestException("files.document_unavailable")
                        val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                        (if (name >= 0) cursor.getString(name) else "attachment") to
                            (if (size >= 0 && !cursor.isNull(size)) cursor.getLong(size) else null)
                    } ?: throw RemoteRequestException("files.document_unavailable")
                }
                coordinator.upload(sessionId, metadata.first, metadata.second) {
                    context.contentResolver.openInputStream(uri) ?: throw RemoteRequestException("files.document_unavailable")
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { platformError = "无法读取所选文件，请检查文件提供程序的访问权限。" }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        coordinator.pickerFinished()
        if (uri != null) coordinator.export(DocumentExportTarget(context, uri))
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(TokenWorksSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md)) {
        Text(stringResource(R.string.workspace_files_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text("图片供模型识图；普通文件上传仅准备文件附件。发送均由会话中的发送按钮确认。预览不会执行 HTML 或脚本。", style = MaterialTheme.typography.bodyMedium)
        if (state.busy) TokenWorksStatusNotice(
            if (state.operation == FilesOperation.PREPARE_IMAGE) "正在手机上处理图片，尚未发送…" else "正在传输… ${state.progressBytes} 字节" + (state.totalBytes?.let { " / $it" } ?: ""),
            tone = TokenWorksNoticeTone.INFO,
        )
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(coordinator::cancel, Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text("取消传输") }
        }
        (platformError ?: state.error)?.let { TokenWorksStatusNotice(it, tone = TokenWorksNoticeTone.ERROR) }
        if (state.exported) TokenWorksStatusNotice("文件已保存到所选位置。", tone = TokenWorksNoticeTone.SUCCESS)
        Surface(Modifier.fillMaxWidth(), shape = tokenWorksSquircle(TokenWorksRadius.lg),
            color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(TokenWorksSpacing.md), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                Text("读取电脑上的文件", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                OutlinedTextField(path, { path = it }, Modifier.fillMaxWidth(), label = { Text("电脑上的绝对文件路径") }, singleLine = true,
                    supportingText = { Text("例如 /home/user/project/result.png；访问仍受主机授权限制。") })
                Row(horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                    Button({ coordinator.load(path) }, enabled = !state.busy && path.startsWith('/')) { Text("读取文件") }
                    OutlinedButton({
                        platformError = null; coordinator.pickerStarted()
                        try { upload.launch(arrayOf("*/*")) }
                        catch (_: Exception) { coordinator.pickerFinished(); platformError = "系统文件选择器不可用。" }
                    }, enabled = !state.busy && state.uploaded.size < 16) { Text("上传附件") }
                }
            }
        }
        Surface(Modifier.fillMaxWidth(), shape = tokenWorksSquircle(TokenWorksRadius.lg),
            color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(TokenWorksSpacing.md), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                Text("图片供模型识图", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                OutlinedButton({
                    platformError = null; imageSelectionSession = sessionId; coordinator.pickerStarted()
                    try { selectImage.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/gif")) }
                    catch (_: Exception) { imageSelectionSession = null; coordinator.pickerFinished(); platformError = "系统图片选择器不可用。" }
                }, enabled = !state.busy && state.images.size < NativeImagePart.MAX_IMAGES) { Text("选择图片供模型识图…") }
                Text("最多 4 张；每张生成不超过 256 KiB 编码的发送副本，总计不超过 1 MiB。会校正方向、缩小或压缩并移除元数据，原文件不变；不支持动画。",
                    style = MaterialTheme.typography.bodySmall, color = tokenWorksSurfaces().textTertiary)
            }
        }
        state.images.forEach { image ->
            Surface(Modifier.fillMaxWidth(), shape = tokenWorksSquircle(TokenWorksRadius.md),
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.padding(horizontal = TokenWorksSpacing.md, vertical = TokenWorksSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xxs)) {
                        Text("${image.part.name ?: "图片"} · ${image.width} × ${image.height} · 待发送", style = MaterialTheme.typography.bodyMedium)
                        if (image.notice.isNotBlank()) Text(image.notice, style = MaterialTheme.typography.bodySmall,
                            color = tokenWorksSurfaces().textTertiary)
                    }
                    TextButton({ coordinator.removeImage(image.part) }, enabled = !state.busy,
                        modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text("移除") }
                }
            }
        }
        state.uploaded.forEach { file ->
            Surface(Modifier.fillMaxWidth(), shape = tokenWorksSquircle(TokenWorksRadius.md),
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.padding(horizontal = TokenWorksSpacing.md, vertical = TokenWorksSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                    Text("${file.name} · ${file.sizeBytes} 字节 · 待发送", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton({ coordinator.removeUpload(file.receiptId) }, enabled = !state.busy,
                        modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text("移除") }
                }
            }
        }
        state.preview?.let { file ->
            Surface(Modifier.fillMaxWidth(), shape = tokenWorksSquircle(TokenWorksRadius.lg),
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(TokenWorksSpacing.md), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                    Text(file.name, style = MaterialTheme.typography.titleMedium)
                    Text("${file.mediaType} · ${file.sizeBytes} 字节", style = MaterialTheme.typography.labelMedium,
                        color = tokenWorksSurfaces().textTertiary)
                    FilePreviewContent(file)
                    Button({
                        coordinator.pickerStarted()
                        try { export.launch(file.name) }
                        catch (_: Exception) { coordinator.pickerFinished(); platformError = "系统保存选择器不可用。" }
                    }, enabled = !state.busy, modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text("保存到手机…") }
                }
            }
        }
        TextButton(onBack, Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text("返回会话") }
    }
}

@Composable
private fun FilePreviewContent(file: StagedFile) {
    var page by remember(file) { mutableIntStateOf(0) }
    val rendered by produceState<RenderedFile?>(null, file, page) {
        value = withContext(Dispatchers.IO) {
            try {
                when {
                    file.sizeBytes > FilePreview.MAX_PREVIEW_BYTES -> RenderedFile(message = "文件较大，仅提供保存，不在应用内解码。")
                    file.mediaType in FilePreview.IMAGE_TYPES -> {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.file.path, bounds)
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw RemoteRequestException("files.invalid_image")
                        var sample = 1
                        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                        val bitmap = BitmapFactory.decodeFile(file.file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                            ?: throw RemoteRequestException("files.invalid_image")
                        RenderedFile(bitmap = bitmap)
                    }
                    file.mediaType == "application/pdf" -> ParcelFileDescriptor.open(file.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        PdfRenderer(descriptor).use { renderer ->
                            renderer.openPage(page.coerceIn(0, renderer.pageCount - 1)).use { source ->
                                val scale = minOf(1000.0 / source.width.coerceAtLeast(1), 1440.0 / source.height.coerceAtLeast(1))
                                val bitmap = Bitmap.createBitmap((source.width * scale).toInt().coerceAtLeast(1),
                                    (source.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                try { source.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                                catch (error: Throwable) { bitmap.recycle(); throw error }
                                RenderedFile(bitmap = bitmap, pages = renderer.pageCount)
                            }
                        }
                    }
                    else -> RenderedFile(message = FilePreview.decodeText(file.file) ?: "此文件类型不支持内嵌预览；可保存后使用其他应用打开。")
                }
            } catch (_: Exception) { RenderedFile(message = "无法安全解码此文件；可保存原文件。") }
        }
    }
    val current = rendered
    DisposableEffect(current) { onDispose { current?.bitmap?.recycle() } }
    if (current == null) CircularProgressIndicator()
    current?.message?.let { SelectionContainer { Text(it) } }
    current?.bitmap?.let { Image(it.asImageBitmap(), file.name, Modifier.fillMaxWidth().heightIn(max = 480.dp)) }
    if ((current?.pages ?: 0) > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton({ page-- }, enabled = page > 0) { Text("上一页") }
        Text("${page + 1} / ${current?.pages}", Modifier.padding(top = 12.dp))
        TextButton({ page++ }, enabled = page + 1 < (current?.pages ?: 0)) { Text("下一页") }
    }
}

private data class RenderedFile(val bitmap: Bitmap? = null, val message: String? = null, val pages: Int = 0)

/** Android's document provider publishes on close; abort deletes the newly created destination. */
private class DocumentExportTarget(context: Context, private val uri: Uri) : FileExportTarget {
    private val resolver = context.contentResolver
    private var opened = false
    private var committed = false
    override fun open(): OutputStream {
        check(!opened && !committed)
        opened = true
        return resolver.openOutputStream(uri, "wt") ?: throw RemoteRequestException("files.destination_unavailable")
    }
    override fun commit() { check(opened && !committed); committed = true }
    override fun abort() {
        if (!DocumentsContract.deleteDocument(resolver, uri)) throw RemoteRequestException("files.destination_cleanup_failed")
    }
}
