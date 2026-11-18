package cn.edu.ustc.tokenworks

import java.util.Base64
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

sealed interface WorkspaceFileEntry {
    val parts: List<String>
    val name: String get() = parts.last()
}

data class WorkspaceFileDirectory(override val parts: List<String>) : WorkspaceFileEntry

data class WorkspaceExportFile(
    override val parts: List<String>,
    val version: String,
    val sizeBytes: Long,
) : WorkspaceFileEntry

data class WorkspaceFileListing(val parts: List<String>, val entries: List<WorkspaceFileEntry>, val truncated: Boolean)

/** No absolute paths, public file URLs, or legacy export jobs are used by this client. */
class DshWorkspaceFiles(private val transport: DshTransport, private val sessionId: String) {
    init { require(sessionId.isNotBlank() && sessionId.length <= 2048) }

    suspend fun list(parts: List<String>): WorkspaceFileListing {
        val path = parts.toList()
        val result = transport.native("workspaceExports/list", jsonObject(
            "sessionId" to sessionId.json(), "parts" to partsJson(path),
        )) as? JsonObject ?: throw ProtocolException()
        result.exactKeys("entries", "truncated")
        val entries = result.array("entries")
        if (entries.size > MAX_ENTRIES || path.size == MAX_DEPTH && entries.isNotEmpty()) throw ProtocolException()
        val names = HashSet<String>(entries.size)
        val decoded = entries.map { value ->
            val entry = value as? JsonObject ?: throw ProtocolException()
            val name = entry.string("name")
            if (!validPart(name) || !names.add(name)) throw ProtocolException()
            when (entry.string("kind")) {
                "directory" -> {
                    entry.exactKeys("kind", "name")
                    WorkspaceFileDirectory(path + name)
                }
                "file" -> {
                    entry.exactKeys("kind", "name", "size", "version")
                    WorkspaceExportFile(path + name, readVersion(entry), entry.integer("size", 0))
                }
                else -> throw ProtocolException()
            }
        }
        return WorkspaceFileListing(path, decoded, boolean(result, "truncated"))
    }

    /** Same pinned file grant as Save, but stage only an explicitly selected bounded file. */
    suspend fun preview(file: WorkspaceExportFile, stagingDirectory: File, onProgress: (Long) -> Unit = {}): StagedFile {
        val limit = FilePreview.MAX_PREVIEW_BYTES.toLong()
        if (file.sizeBytes !in 0..limit) throw RemoteRequestException("files.too_large")
        return FilePreview(transport, stagingDirectory).stage(file.name, limit, onProgress) { sink ->
            save(file, object : FileExportTarget {
                override fun open(): OutputStream = sink
                override fun commit() = Unit // FilePreview verifies and owns the private file.
                override fun abort() = Unit
            })
            val declared = when (file.name.substringAfterLast('.', "").lowercase()) {
                "json" -> "application/json"
                "txt", "md", "log", "csv", "tsv", "kt", "java", "js", "ts", "py", "go", "rs", "sh", "yaml", "yml", "toml" -> "text/plain"
                else -> "application/octet-stream"
            }
            DshDownload(declared, file.sizeBytes, null, null)
        }
    }

    /** One bounded response at a time on a pinned authenticated route; no in-memory whole file. */
    suspend fun save(file: WorkspaceExportFile, target: FileExportTarget, onProgress: (Long) -> Unit = {}) {
        try {
            val context = currentCoroutineContext()
            context.ensureActive()
            if (file.sizeBytes !in 0..MAX_FILE_BYTES) throw RemoteRequestException("files.too_large")
            if (file.parts.isEmpty() || !VERSION.matches(file.version)) throw ProtocolException()
            val path = partsJson(file.parts)
            transport.connect().use { connection ->
                context.ensureActive()
                target.open().use { sink ->
                    var offset = 0L
                    do {
                        context.ensureActive()
                        val expected = minOf(CHUNK_BYTES.toLong(), file.sizeBytes - offset).toInt()
                        val result = connection.native("workspaceExports/read", jsonObject(
                            "sessionId" to sessionId.json(), "request" to jsonObject(
                                "parts" to path, "version" to file.version.json(), "size" to file.sizeBytes.json(),
                                "offset" to offset.json(), "length" to maxOf(1, expected).toLong().json(),
                            ),
                        )) as? JsonObject ?: throw ProtocolException()
                        context.ensureActive()
                        result.exactKeys("offset", "data", "eof")
                        val end = offset + expected
                        if (result.integer("offset", 0, MAX_FILE_BYTES) != offset || boolean(result, "eof") != (end == file.sizeBytes)) {
                            throw RemoteRequestException("files.integrity_mismatch")
                        }
                        val data = result.string("data")
                        if (data.length != (expected + 2) / 3 * 4) throw RemoteRequestException("files.integrity_mismatch")
                        val bytes = try { Base64.getDecoder().decode(data) }
                        catch (_: IllegalArgumentException) { throw RemoteRequestException("files.integrity_mismatch") }
                        if (bytes.size != expected) throw RemoteRequestException("files.integrity_mismatch")
                        if (bytes.isNotEmpty()) sink.write(bytes)
                        offset = end
                        onProgress(offset)
                    } while (offset < file.sizeBytes)
                    sink.flush()
                }
            }
            context.ensureActive()
            target.commit()
        } catch (error: Throwable) {
            try { target.abort() } catch (abort: Exception) {
                throw RemoteRequestException("files.destination_cleanup_failed").apply { addSuppressed(error); addSuppressed(abort) }
            }
            throw error
        }
    }

    companion object {
        const val MAX_FILE_BYTES = 200_000_000L
        internal const val CHUNK_BYTES = 256 * 1024
        private const val MAX_DEPTH = 32
        private const val MAX_ENTRIES = 512
        private val VERSION = Regex("[a-f0-9]{64}")
        private val SEGMENT = Regex("[^/\\\\\\u0000-\\u001f\\u007f-\\u009f\\u202a-\\u202e\\u2066-\\u2069\\ufffd]+")

        private fun boolean(value: JsonObject, key: String) = (value[key] as? JsonBoolean)?.value ?: throw ProtocolException()
        private fun readVersion(entry: JsonObject) = entry.string("version").also { if (!VERSION.matches(it)) throw ProtocolException() }
        private fun validPart(part: String): Boolean = part.length in 1..255 && part != "." && part != ".." &&
            SEGMENT.matches(part) && part.toByteArray(Charsets.UTF_8).let { it.size <= 255 && it.toString(Charsets.UTF_8) == part }
        private fun partsJson(parts: List<String>): JsonArray {
            if (parts.size > MAX_DEPTH || !parts.all(::validPart)) throw ProtocolException()
            return JsonArray(parts.map { it.json() })
        }
    }
}
