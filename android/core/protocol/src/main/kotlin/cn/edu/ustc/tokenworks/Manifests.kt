package cn.edu.ustc.tokenworks

const val MAX_FILE_BYTES = 200_000_000L
const val MAX_EXPORT_BYTES = 2_000_000_000L

/** Shared leaf-name and digest rules for upload/download manifests. */
class FileManifest(val name: String, val sizeBytes: Long, val sha256: String) {
    init {
        if (name.isBlank() || name == "." || name == ".." || name.toByteArray(Charsets.UTF_8).size > 512 ||
            name.any { it == '/' || it == '\\' || Character.isISOControl(it) } || sizeBytes !in 0..MAX_FILE_BYTES) throw ProtocolException()
        Contract.digest(sha256)
        JsonDocument.of(json()) // Reject unpaired surrogates independently of transport decoding.
    }
    fun json() = jsonObject("name" to name.json(), "size_bytes" to sizeBytes.json(), "sha256" to sha256.json())
    fun same(other: FileManifest) = name == other.name && sizeBytes == other.sizeBytes && sha256 == other.sha256
    override fun toString() = "FileManifest"
    companion object {
        fun parse(root: JsonObject): FileManifest {
            root.exactKeys("name", "size_bytes", "sha256")
            return FileManifest(root.string("name"), root.integer("size_bytes", 0, MAX_FILE_BYTES), root.string("sha256"))
        }
    }
}

/** Visible attachment provenance only; neither a download grant nor a device path. */
class ExportManifest(val name: String, val sizeBytes: Long, val sha256: String) {
    init {
        FileManifest(name, 0, sha256) // Shared leaf-name and digest rules, without widening uploads.
        if (sizeBytes !in 0..MAX_EXPORT_BYTES) throw ProtocolException()
    }
    fun json() = jsonObject("name" to name.json(), "size_bytes" to sizeBytes.json(), "sha256" to sha256.json())
    fun same(other: ExportManifest) = name == other.name && sizeBytes == other.sizeBytes && sha256 == other.sha256
    override fun toString() = "ExportManifest"
    companion object {
        fun parse(root: JsonObject): ExportManifest {
            root.exactKeys("name", "size_bytes", "sha256")
            return ExportManifest(root.string("name"), root.integer("size_bytes", 0, MAX_EXPORT_BYTES), root.string("sha256"))
        }
    }
}
