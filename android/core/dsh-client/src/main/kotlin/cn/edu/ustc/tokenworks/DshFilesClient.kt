package cn.edu.ustc.tokenworks

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

/** A picker-owned transaction: abort MUST delete the created document, even after close fails. */
interface FileExportTarget {
    fun open(): OutputStream
    fun commit()
    fun abort()
}

/** Opaque receipt stays bound to its upload session; never treated as a path or URL. */
data class UploadedAttachment(val sessionId: String, val receiptId: String, val attachmentId: String, val name: String, val sizeBytes: Long) {
    fun promptPart(): JsonObject = jsonObject("type" to "file".json(), "receiptId" to receiptId.json())
}

/** Produces the native session/prompt args only; sending and stable request identity belong to the caller. */
fun filePromptArguments(sessionId: String, requestId: String, text: String, attachments: List<UploadedAttachment>, mode: String = "queue",
                        images: List<NativeImagePart> = emptyList()): JsonObject {
    require(sessionId.isNotBlank() && requestId.isNotBlank() && mode in setOf("queue", "steer"))
    val savedAttachments = attachments.toList()
    val savedImages = images.toList()
    require(savedAttachments.all { it.sessionId == sessionId })
    validateNativePrompt(text, savedAttachments.map { it.receiptId }, savedImages)
    val content = buildList<JsonValue> {
        if (text.isNotEmpty()) add(jsonObject("type" to "text".json(), "text" to text.json()))
        savedAttachments.forEach { add(it.promptPart()) }
        savedImages.forEach { add(it.promptPart()) }
    }
    return jsonObject("request" to jsonObject("sessionId" to sessionId.json(), "requestId" to requestId.json(),
        "mode" to mode.json(), "content" to JsonArray(content)))
}

class DshFilesClient(private val transport: DshTransport, stagingDirectory: File) {
    private val preview = FilePreview(transport, stagingDirectory)
    companion object { const val MAX_UPLOAD_BYTES = 200_000_000L }

    suspend fun download(path: String, onProgress: (Long) -> Unit = {}): StagedFile =
        preview.fetch(path, maxBytes = MAX_UPLOAD_BYTES, onProgress = onProgress)

    suspend fun attachment(sessionId: String, attachment: NativeImageReference, onProgress: (Long) -> Unit = {}): StagedFile {
        require(sessionId.isNotBlank())
        val args = jsonObject("request" to jsonObject("sessionId" to sessionId.json(), "attachment" to attachment.json()))
        DshMethods.SessionAttachment.input.decode(args)
        val response = transport.native("session/attachment", args) as? JsonObject ?: throw ProtocolException()
        DshSessionAttachmentResult.codec.decode(response)
        val ref = NativeImageReference.parse(JsonObject(response.obj("attachment").fields.filterKeys {
            it in setOf("attachmentId", "mediaType", "bytes", "width", "height", "name")
        }))
        if (!ref.same(attachment)) throw ProtocolException()
        val mime = ref.mediaType
        val bytes = ref.bytes
        val data = response.string("data")
        val expectedEncoded = ((bytes + 2) / 3) * 4
        if (data.length.toLong() != expectedEncoded) throw ProtocolException()
        val padding = ((3 - bytes % 3) % 3).toInt()
        val encodedLength = data.length - padding
        fun sextet(char: Char): Int = when (char) {
            in 'A'..'Z' -> char - 'A'
            in 'a'..'z' -> char - 'a' + 26
            in '0'..'9' -> char - '0' + 52
            '+' -> 62
            '/' -> 63
            else -> throw ProtocolException()
        }
        for (index in 0 until encodedLength) sextet(data[index])
        for (index in encodedLength until data.length) if (data[index] != '=') throw ProtocolException()
        if (padding > 0 && sextet(data[encodedLength - 1]) and (if (padding == 2) 15 else 3) != 0) throw ProtocolException()
        val name = ref.name ?: "attachment"
        val staged = preview.stage(name, FilePreview.MAX_PREVIEW_BYTES.toLong(), onProgress) { sink ->
            // Decode as a stream, not an additional full-sized decoded allocation.
            Base64.getDecoder().wrap(object : InputStream() {
                private var position = 0
                override fun read(): Int = if (position == data.length) -1 else data[position++].code
                override fun read(target: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    if (position == data.length) return -1
                    val count = minOf(length, data.length - position)
                    for (index in 0 until count) target[offset + index] = data[position++].code.toByte()
                    return count
                }
            }).use { source -> copy(source, sink) }
            DshDownload(mime, bytes, null, null)
        }
        if (staged.mediaType != mime || "sha256:${staged.sha256}" != attachment.attachmentId) {
            staged.close(); throw RemoteRequestException("files.integrity_mismatch")
        }
        return staged
    }

    suspend fun upload(sessionId: String, name: String, sizeBytes: Long?, source: () -> InputStream,
                       onProgress: (Long) -> Unit = {}): UploadedAttachment {
        require(sessionId.isNotBlank() && sessionId.length <= 4096)
        if (sizeBytes != null && sizeBytes !in 0..MAX_UPLOAD_BYTES) throw RemoteRequestException("files.too_large")
        val staged = preview.stage(name, MAX_UPLOAD_BYTES, onProgress) { sink ->
            val count = source().use { copy(it, sink) }
            if (sizeBytes != null && count != sizeBytes) throw RemoteRequestException("files.size_mismatch")
            DshDownload("application/octet-stream", count, null, null)
        }
        try {
            val context = currentCoroutineContext()
            var sent = 0L
            val result = staged.file.inputStream().use { raw ->
                val checked = object : java.io.FilterInputStream(raw) {
                    override fun read(): Int { context.ensureActive(); return super.read().also { if (it >= 0) onProgress(++sent) } }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        context.ensureActive(); return `in`.read(bytes, offset, length).also { if (it > 0) { sent += it; onProgress(sent) } }
                    }
                }
                transport.upload("/api/session/uploadFileBinary?sessionId=${FilePreview.encode(sessionId)}&name=${FilePreview.encode(staged.name)}",
                    "application/octet-stream", checked, staged.sizeBytes)
            } as? JsonObject ?: throw ProtocolException()
            context.ensureActive()
            val ok = result["ok"] as? JsonBoolean ?: throw ProtocolException()
            if (!ok.value) throw RemoteRequestException(result.obj("error").string("code"))
            val value = result.obj("value")
            val receipt = value.string("receiptId")
            if (receipt.isBlank() || receipt.length > 4096 || receipt.any { it < ' ' }) throw ProtocolException()
            val file = value.obj("file")
            val id = file.string("attachmentId")
            if (id != "sha256:${staged.sha256}" || file.integer("bytes", 0, MAX_UPLOAD_BYTES) != staged.sizeBytes) throw RemoteRequestException("files.integrity_mismatch")
            val returnedName = file.string("name")
            if (returnedName != FilePreview.displayName(returnedName)) throw ProtocolException()
            return UploadedAttachment(sessionId, receipt, id, returnedName, staged.sizeBytes)
        } finally { staged.close() }
    }

    suspend fun export(file: StagedFile, target: FileExportTarget, onProgress: (Long) -> Unit = {}) {
        try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var count = 0L
            val context = currentCoroutineContext()
            context.ensureActive()
            target.open().use { sink ->
                file.file.inputStream().use { raw ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        context.ensureActive()
                        val read = raw.read(buffer)
                        if (read < 0) break
                        if (count > file.sizeBytes - read) throw RemoteRequestException("files.size_mismatch")
                        digest.update(buffer, 0, read); sink.write(buffer, 0, read); count += read; onProgress(count)
                    }
                }
                if (count != file.sizeBytes || digest.digest().joinToString("") { "%02x".format(it) } != file.sha256) throw RemoteRequestException("files.integrity_mismatch")
                sink.flush()
            }
            context.ensureActive(); target.commit()
        } catch (error: Throwable) {
            try { target.abort() } catch (abort: Exception) {
                throw RemoteRequestException("files.destination_cleanup_failed").apply { addSuppressed(error); addSuppressed(abort) }
            }
            throw error
        }
    }

    private suspend fun copy(source: InputStream, sink: OutputStream): Long {
        val context = currentCoroutineContext()
        val buffer = ByteArray(32 * 1024)
        var copied = 0L
        while (true) {
            context.ensureActive()
            val count = source.read(buffer)
            if (count < 0) return copied
            sink.write(buffer, 0, count)
            copied += count
        }
    }
}
