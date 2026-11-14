package cn.edu.ustc.tokenworks

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.OutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Private, disk-backed file. The owning preview/coordinator must close it. */
class StagedFile internal constructor(
    val file: File,
    val name: String,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: String,
    val etag: String? = null,
    val contentRange: String? = null,
) : AutoCloseable {
    override fun close() { file.delete() }
    override fun toString(): String = "StagedFile($mediaType, ${sizeBytes}B)"
}

class FilePreview(private val transport: DshTransport, private val stagingDirectory: File) {
    companion object {
        const val MAX_PREVIEW_BYTES = 20 * 1024 * 1024
        const val MAX_TEXT_BYTES = 256 * 1024
        val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
        fun previewable(mediaType: String): Boolean = mediaType in IMAGE_TYPES || mediaType == "application/pdf" ||
            (mediaType.startsWith("text/") && mediaType !in setOf("text/html", "text/xml")) || mediaType == "application/json"

        fun exactPath(path: String): String {
            if (!path.startsWith('/') || path.length > 4096 || path.any { it < ' ' || it == '\u007f' } ||
                path.contains('\\') || path.split('/').any { it == "." || it == ".." }) throw RemoteRequestException("files.invalid_path")
            return "/api/file?path=${encode(path)}"
        }
        internal fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        fun displayName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\')
            .filter { it >= ' ' && it != '\u007f' && it !in "\u202a\u202b\u202c\u202d\u202e\u2066\u2067\u2068\u2069" }
            .take(160).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "attachment"

        /** Header MIME is untrusted too: raster/PDF require magic; text requires valid bounded UTF-8. */
        internal fun verifiedMime(file: File, declared: String): String {
            val head = ByteArray(16)
            val count = file.inputStream().use { it.read(head) }
            fun starts(vararg bytes: Int) = count >= bytes.size && bytes.indices.all { (head[it].toInt() and 255) == bytes[it] }
            if (starts(137, 80, 78, 71, 13, 10, 26, 10)) return "image/png"
            if (starts(255, 216, 255)) return "image/jpeg"
            if (count >= 6 && String(head, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")) return "image/gif"
            if (count >= 12 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WEBP") return "image/webp"
            if (count >= 5 && String(head, 0, 5, Charsets.US_ASCII) == "%PDF-") return "application/pdf"
            val mime = declared.substringBefore(';').trim().lowercase()
            if (mime in IMAGE_TYPES || mime in setOf("application/pdf", "image/svg+xml", "text/html", "application/xhtml+xml", "text/xml", "application/xml")) return "application/octet-stream"
            if (file.length() <= MAX_TEXT_BYTES && (mime.startsWith("text/") || mime == "application/json")) {
                val text = decodeText(file)
                if (text != null && !text.trimStart().startsWith('<') && !text.contains('\u0000')) return mime
            }
            return "application/octet-stream"
        }
        fun decodeText(file: File): String? {
            if (file.length() > MAX_TEXT_BYTES) return null
            return runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(file.readBytes())).toString() }.getOrNull()
        }
    }

    suspend fun fetch(path: String, maxBytes: Long = MAX_PREVIEW_BYTES.toLong(), range: DshByteRange? = null,
                      onProgress: (Long) -> Unit = {}): StagedFile {
        val route = exactPath(path)
        require(maxBytes in 1..DshFilesClient.MAX_UPLOAD_BYTES)
        return stage(displayName(path), maxBytes, onProgress) { sink -> transport.download(route, sink, maxBytes, range) }
    }

    internal suspend fun stage(name: String, maxBytes: Long, onProgress: (Long) -> Unit = {},
                               receive: suspend (OutputStream) -> DshDownload): StagedFile {
        stagingDirectory.mkdirs()
        val file = File.createTempFile("dsh-file-", ".private", stagingDirectory)
        try {
            val context = currentCoroutineContext()
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            val metadata = file.outputStream().use { raw ->
                val bounded = object : OutputStream() {
                    override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        context.ensureActive()
                        if (length < 0 || count > maxBytes - length) throw RemoteRequestException("files.too_large")
                        raw.write(bytes, offset, length); digest.update(bytes, offset, length)
                        count += length; onProgress(count)
                    }
                    override fun flush() = raw.flush()
                }
                receive(bounded)
            }
            context.ensureActive()
            if (metadata.sizeBytes != count) throw RemoteRequestException("files.size_mismatch")
            val mime = verifiedMime(file, metadata.mediaType)
            return StagedFile(file, displayName(name), mime, count, digest.digest().joinToString("") { "%02x".format(it) }, metadata.etag, metadata.contentRange)
        } catch (error: Throwable) { file.delete(); throw error }
    }
}
