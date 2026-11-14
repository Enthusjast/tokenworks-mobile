package cn.edu.ustc.tokenworks

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FilePreviewTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun previewableTypesCoverTextImagesAndPdfOnly() {
        for (type in listOf("text/plain", "text/x-kotlin", "application/json", "image/png", "image/jpeg", "application/pdf"))
            assertTrue(type, FilePreview.previewable(type))
        for (type in listOf("application/octet-stream", "application/zip", "video/mp4", "application/x-msdownload", "text/html", "image/svg+xml", ""))
            assertFalse(type, FilePreview.previewable(type))
    }

    @Test fun fileLargerThanInlinePreviewBudgetRemainsDownloadableAndByteExact(): Unit = runBlocking {
        val bytes = FilePreview.MAX_PREVIEW_BYTES + 1024 * 1024
        val transport = ZeroFileTransport(bytes)
        val staged = DshFilesClient(transport, directory.root).download("/work/large.bin")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val chunk = ByteArray(32 * 1024)
            repeat(bytes / chunk.size) { digest.update(chunk) }
            assertEquals(digest.digest().joinToString("") { "%02x".format(it) }, staged.sha256)
            assertEquals(bytes.toLong(), staged.file.length())
            assertEquals("application/octet-stream", staged.mediaType)
            assertNull(FilePreview.decodeText(staged.file))
        } finally { staged.close() }
    }

    @Test fun oversizedInlineFetchFailsWithoutLeavingAPartialFile(): Unit = runBlocking {
        val transport = ZeroFileTransport(FilePreview.MAX_PREVIEW_BYTES + 1)
        try {
            FilePreview(transport, directory.root).fetch("/work/large.bin")
            fail("expected preview budget failure")
        } catch (error: RemoteRequestException) { assertEquals("files.too_large", error.code) }
        assertTrue(directory.root.listFiles().orEmpty().isEmpty())
    }

    private class ZeroFileTransport(private val size: Int) : DshTransport {
        override val runtimeId = "public-file-runtime"
        override val state = MutableStateFlow(DshTransportState(DshTransportPath.RELAY, DshTransportPhase.READY))
        override suspend fun download(path: String, sink: OutputStream, maxBytes: Long, range: DshByteRange?): DshDownload {
            val chunk = ByteArray(32 * 1024)
            var left = size
            while (left > 0) {
                val count = minOf(left, chunk.size)
                sink.write(chunk, 0, count); left -= count
            }
            return DshDownload("application/octet-stream", size.toLong(), null, null)
        }
        override suspend fun native(endpoint: String, args: JsonObject): JsonValue = error("not a file read")
        override suspend fun openMux(): DshMux = error("not a file read")
        override suspend fun upload(path: String, mediaType: String, source: InputStream, sizeBytes: Long): JsonValue = error("not a file read")
        override fun close() {}
    }
}
