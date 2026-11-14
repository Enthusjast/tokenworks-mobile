package cn.edu.ustc.tokenworks

/** Intrinsic native display metadata only. The hash is a locator, never download authority.
 * Every preview must ask the currently selected session's Host via session/attachment again.
 */
class NativeImageReference(
    val attachmentId: String,
    val mediaType: String,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val name: String? = null,
) {
    init {
        if (name != null) requireText(name)
        if (!attachmentId.matches(Regex("sha256:[0-9a-f]{64}")) || mediaType !in MEDIA_TYPES ||
            bytes !in 1..MAX_BYTES || width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION ||
            width.toLong() * height > MAX_PIXELS) throw ProtocolException()
        if (name != null && (name.isBlank() || name.toByteArray(Charsets.UTF_8).size > 512 ||
                name == "." || name == ".." || name.any { it.isISOControl() || it in "/\\\u202a\u202b\u202c\u202d\u202e\u2066\u2067\u2068\u2069" })) throw ProtocolException()
    }
    fun json(): JsonObject = jsonObject("attachmentId" to attachmentId.json(), "mediaType" to mediaType.json(),
        "bytes" to bytes.json(), "width" to width.toLong().json(), "height" to height.toLong().json()).let {
        if (name == null) it else it.with("name" to name.json())
    }
    fun same(other: NativeImageReference) = attachmentId == other.attachmentId && mediaType == other.mediaType &&
        bytes == other.bytes && width == other.width && height == other.height && name == other.name
    override fun toString() = "NativeImageReference"
    companion object {
        const val MAX_BYTES = 20L * 1024 * 1024
        const val MAX_DIMENSION = 16_384
        const val MAX_PIXELS = 32_000_000L
        val MEDIA_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
        fun parse(root: JsonObject): NativeImageReference {
            SessionJson.fields(root, setOf("attachmentId", "mediaType", "bytes", "width", "height"), setOf("name"))
            return NativeImageReference(root.string("attachmentId"), root.string("mediaType"), root.integer("bytes", 1, MAX_BYTES),
                root.integer("width", 1, MAX_DIMENSION.toLong()).toInt(), root.integer("height", 1, MAX_DIMENSION.toLong()).toInt(),
                if ("name" in root.fields) root.string("name") else null)
        }
    }
}
