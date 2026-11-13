package cn.edu.ustc.tokenworks

/**
 * P1-7 file preview transport: the `/api/file` exact route streams bytes
 * verbatim through the tunnel; the phone caps what it will buffer for a
 * preview and never writes to the Host filesystem.
 */
class FilePreview {
    companion object {
        /** Preview budget: a larger file stays downloadable but not previewable. */
        const val MAX_PREVIEW_BYTES = 20 * 1024 * 1024

        /** Text and image kinds render inline; everything else only offers download. */
        fun previewable(mediaType: String): Boolean = mediaType.startsWith("text/") || mediaType == "application/json" ||
            mediaType.startsWith("image/") || mediaType == "application/pdf"
    }

    /** Fetches at most [maxBytes] of one file through the authenticated tunnel. */
    suspend fun fetch(api: RemoteApi, runtimeId: String, lease: DshLease, path: String, maxBytes: Int = MAX_PREVIEW_BYTES): ByteArray {
        if (path.isEmpty() || path.length > 4096 || path.any { it < ' ' || it >= '' }) throw RemoteRequestException("protocol.invalid")
        val encoded = java.net.URLEncoder.encode(path, "UTF-8")
        return api.fileBytes(runtimeId, lease, "/api/file?path=$encoded", maxBytes)
    }
}
