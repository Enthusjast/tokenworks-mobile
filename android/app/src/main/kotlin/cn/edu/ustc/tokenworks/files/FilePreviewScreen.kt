package cn.edu.ustc.tokenworks

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Native-only rendering: no WebView, JavaScript, external URI, or filename-based decoder. */
@Composable
fun FilePreviewScreen(file: StagedFile, modifier: Modifier = Modifier) {
    val resource = remember(file) { PreviewBitmap() }
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    var text by remember(file) { mutableStateOf<String?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var loading by remember(file) { mutableStateOf(true) }
    DisposableEffect(resource) { onDispose { resource.close() } }
    LaunchedEffect(file) {
        try {
            withContext(Dispatchers.IO) {
                when {
                    file.mediaType in FilePreview.IMAGE_TYPES -> resource.decodeImage(file)
                    file.mediaType == "application/pdf" -> resource.decodePdf(file)
                    FilePreview.previewable(file.mediaType) -> Unit
                }
            }
            bitmap = resource.get()
            if (file.mediaType !in FilePreview.IMAGE_TYPES && file.mediaType != "application/pdf" && FilePreview.previewable(file.mediaType)) {
                text = withContext(Dispatchers.IO) { FilePreview.decodeText(file.file) }
                if (text == null) error = "文本过大或编码不受支持，请保存后查看。"
            }
        } catch (cancelled: CancellationException) { resource.close(); throw cancelled
        } catch (_: Exception) { error = "无法安全预览此文件，仍可保存原文件。"
        } finally { loading = false }
    }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (loading) { CircularProgressIndicator(); Text("正在生成安全预览…") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = "文件预览：${file.name}", modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) }
        if (file.mediaType == "application/pdf" && bitmap != null) Text("PDF 第 1 页 · 预览已限制分辨率，保存可查看完整文件。", style = MaterialTheme.typography.bodySmall)
        text?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodyMedium) } }
        if (!loading && !FilePreview.previewable(file.mediaType)) Text("此类型不在应用内执行或预览。请保存到你选择的位置。")
    }
}

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
        require(width > 0 && height > 0 && width.toLong() * height <= 100_000_000L)
        var sample = 1
        while ((width.toLong() / sample) * (height.toLong() / sample) > 2_000_000L) sample *= 2
        options.inJustDecodeBounds = false; options.inSampleSize = sample; options.inPreferredConfig = Bitmap.Config.ARGB_8888
        val result = BitmapFactory.decodeFile(file.file.path, options) ?: error("image.invalid")
        if (result.allocationByteCount > 8_000_000) { result.recycle(); error("image.budget") }
        publish(result)
    }
    fun decodePdf(file: StagedFile) {
        ParcelFileDescriptor.open(file.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { pdf ->
                require(pdf.pageCount > 0)
                pdf.openPage(0).use { page ->
                    require(page.width > 0 && page.height > 0)
                    val scale = minOf(1.0, 1400.0 / maxOf(page.width, page.height))
                    val width = maxOf(1, (page.width * scale).toInt()); val height = maxOf(1, (page.height * scale).toInt())
                    require(width.toLong() * height <= 2_000_000L)
                    val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try { result.eraseColor(Color.WHITE); page.render(result, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        publish(result)
                    } catch (error: Throwable) { result.recycle(); throw error }
                }
            }
        }
    }
    @Synchronized override fun close() { closed = true; bitmap?.recycle(); bitmap = null }
}
