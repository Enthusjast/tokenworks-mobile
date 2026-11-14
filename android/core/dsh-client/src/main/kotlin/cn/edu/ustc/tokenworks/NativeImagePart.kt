package cn.edu.ustc.tokenworks

/** An already-prepared, immutable native image. Base64 is never decoded or recompressed on retry. */
data class NativeImagePart(val mediaType: String, val data: String, val name: String? = null) {
    companion object {
        const val MAX_IMAGES = 4
        const val MAX_BATCH_ENCODED_BYTES = 1024 * 1024
        const val MAX_NAME_BYTES = 512
        private val mediaTypes = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

        internal fun validateBatch(images: List<NativeImagePart>) {
            require(images.size <= MAX_IMAGES && images.sumOf { it.encodedBytes.toLong() } <= MAX_BATCH_ENCODED_BYTES) {
                "prompt image budget exceeded"
            }
        }

        private fun sextet(char: Char): Int = when (char) {
            in 'A'..'Z' -> char.code - 'A'.code
            in 'a'..'z' -> char.code - 'a'.code + 26
            in '0'..'9' -> char.code - '0'.code + 52
            '+' -> 62
            '/' -> 63
            else -> -1
        }

        private fun canonicalBase64(value: String): Boolean {
            if (value.isEmpty() || value.length % 4 != 0) return false
            val padding = if (value.endsWith("==")) 2 else if (value.endsWith("=")) 1 else 0
            val end = value.length - padding
            for (index in 0 until end) if (sextet(value[index]) < 0) return false
            // Reject non-zero unused bits, not just invalid characters or misplaced padding.
            return when (padding) {
                2 -> (sextet(value[end - 1]) and 15) == 0
                1 -> (sextet(value[end - 1]) and 3) == 0
                else -> true
            }
        }
    }

    val encodedBytes: Int get() = data.length

    init {
        require(mediaType in mediaTypes) { "image media type invalid" }
        require(data.length <= MAX_BATCH_ENCODED_BYTES && canonicalBase64(data)) { "image base64 invalid" }
        if (name != null) {
            requireText(name)
            require(name.isNotBlank() && name != "." && name != ".." &&
                name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES && name.none {
                    it.isISOControl() || it == '/' || it == '\\' ||
                        it in "\u202a\u202b\u202c\u202d\u202e\u2066\u2067\u2068\u2069"
                }) { "image name invalid" }
        }
    }

    fun promptPart(): JsonObject = DshSessionPromptInputRequestContentItemCase2.codec.encode(
        DshSessionPromptInputRequestContentItemCase2("image", mediaType, data,
            name?.let { DshOptional.Value(it) } ?: DshOptional.Missing)
    ) as JsonObject

    override fun toString(): String = "NativeImagePart(mediaType=$mediaType, encodedBytes=$encodedBytes)"
}

/** Text/receipts and base64 have separate finite budgets at every prompt entry point. */
internal fun validateNativePrompt(text: String, receipts: List<String>, images: List<NativeImagePart>): Int {
    requireText(text)
    require(receipts.size <= 16 && receipts.distinct().size == receipts.size) { "prompt receipts invalid" }
    receipts.forEach {
        requireText(it)
        require(it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= 4096) { "prompt receipt invalid" }
    }
    val textBytes = text.toByteArray(Charsets.UTF_8).size + receipts.sumOf { it.toByteArray(Charsets.UTF_8).size }
    require((text.isNotBlank() || receipts.isNotEmpty() || images.isNotEmpty()) && textBytes <= DshCommandOutbox.MAX_TEXT_BYTES) {
        "prompt size invalid"
    }
    NativeImagePart.validateBatch(images)
    return textBytes
}
