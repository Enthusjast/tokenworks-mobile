package cn.edu.ustc.tokenworks

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeAttachmentTest {
    @get:Rule val directory = TemporaryFolder()
    private val data = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1sAAAAASUVORK5CYII="
    private val bytes = Base64.getDecoder().decode(data)
    private val id = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun response() = jsonObject("attachment" to NativeImageReference(id, "image/png", bytes.size.toLong(), 1, 1, "native.png").json(), "data" to data.json())
    private class AttachmentTransport(var result: JsonObject) : DshTransport {
        override val runtimeId = "native-image-runtime"
        override val state = MutableStateFlow(DshTransportState(DshTransportPath.RELAY, DshTransportPhase.READY))
        val requests = mutableListOf<JsonObject>()
        var denied = false
        override suspend fun native(endpoint: String, args: JsonObject): JsonValue {
            assertEquals("session/attachment", endpoint)
            requests.add(args)
            if (denied) throw RemoteRequestException("session.attachment_not_found")
            return result
        }
        override suspend fun openMux(): DshMux = error("not an attachment read")
        override suspend fun download(path: String, sink: OutputStream, maxBytes: Long, range: DshByteRange?): DshDownload = error("not an attachment read")
        override suspend fun upload(path: String, mediaType: String, source: InputStream, sizeBytes: Long): JsonValue = error("not an attachment read")
        override fun close() {}
    }

    @Test fun everyPreviewUsesCurrentSessionHostReachabilityInsteadOfCachedHashAuthority() = runBlocking {
        val transport = AttachmentTransport(response())
        val client = DshFilesClient(transport, directory.root)
        val staged = client.attachment("current-session", id)
        try {
            assertArrayEquals(bytes, staged.file.readBytes())
            assertEquals("image/png", staged.mediaType)
            assertEquals(bytes.size.toLong(), staged.sizeBytes)
        } finally { staged.close() }
        transport.denied = true
        try { client.attachment("other-session", id); fail("Host must authorize each session") }
        catch (error: RemoteRequestException) { assertEquals("session.attachment_not_found", error.code) }
        assertEquals(2, transport.requests.size)
        transport.requests.forEach { it.exactKeys("request"); it.obj("request").exactKeys("sessionId", "attachmentId") }
        assertEquals("current-session", transport.requests[0].obj("request").string("sessionId"))
        assertEquals("other-session", transport.requests[1].obj("request").string("sessionId"))
        assertTrue(directory.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun mismatchedHashMimeSizeAndInvalidBase64FailWithoutLeavingPreviewFiles() = runBlocking {
        val root = response()
        val ref = root.obj("attachment")
        val invalid = listOf(root.with("attachment" to ref.with("attachmentId" to "sha256:${"b".repeat(64)}".json())),
            root.with("attachment" to ref.with("mediaType" to "image/jpeg".json())),
            root.with("attachment" to ref.with("bytes" to (bytes.size + 1L).json())),
            root.with("data" to ("!" + data.drop(1)).json()), root.with("data" to data.dropLast(1).json()),
            root.with("attachment" to ref.with("width" to 0L.json())),
            root.with("data" to Base64.getEncoder().encodeToString(bytes.clone().also { it[it.lastIndex] = (it.last() + 1).toByte() }).json()))
        val transport = AttachmentTransport(root)
        val client = DshFilesClient(transport, directory.root)
        for (bad in invalid) {
            transport.result = bad
            try { client.attachment("current-session", id); fail("must reject malformed native attachment") }
            catch (expected: Exception) { assertTrue(expected is ProtocolException || expected is RemoteRequestException) }
            assertTrue(directory.root.listFiles().orEmpty().isEmpty())
        }
    }
}
