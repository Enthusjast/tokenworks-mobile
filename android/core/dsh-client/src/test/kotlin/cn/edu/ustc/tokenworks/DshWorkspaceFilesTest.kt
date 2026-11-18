package cn.edu.ustc.tokenworks

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class DshWorkspaceFilesTest {
    private val version = "a".repeat(64)
    private val chunk = DshWorkspaceFiles.CHUNK_BYTES

    @Test fun listsRelativeSourcesAndSavesExactBoundedChunksIncludingEmptyFile() = runBlocking {
        val data = ByteArray(chunk * 2 + 7) { (it % 251).toByte() }
        val transport = WorkspaceTransport(data)
        val client = DshWorkspaceFiles(transport, "session-a")
        val root = client.list(emptyList())
        assertEquals(listOf("reports"), root.entries.single().parts)
        val file = client.list(root.entries.single().parts).entries.single() as WorkspaceExportFile
        assertEquals(listOf("reports", "result %.bin"), file.parts)
        val target = Destination()
        val progress = mutableListOf<Long>()
        client.save(file, target, progress::add)
        assertEquals(listOf(0L to chunk, chunk.toLong() to chunk, (chunk * 2L) to 7), transport.requests)
        assertEquals(listOf(chunk.toLong(), chunk * 2L, data.size.toLong()), progress)
        assertArrayEquals(data, target.output.toByteArray())
        assertTrue(target.committed)
        assertFalse(target.aborted)

        val emptyTransport = WorkspaceTransport(ByteArray(0))
        val emptyTarget = Destination()
        DshWorkspaceFiles(emptyTransport, "session-a").save(file.copy(sizeBytes = 0), emptyTarget)
        assertTrue(emptyTarget.committed)
        assertEquals(listOf(0L to 1), emptyTransport.requests)
        assertEquals(0, emptyTarget.output.size())
    }

    @Test fun cancellationOnSecondChunkDeletesThePartialDestination() = runBlocking {
        val parked = CompletableDeferred<Unit>()
        val transport = WorkspaceTransport(ByteArray(chunk * 2)) { offset ->
            if (offset != 0L) { parked.complete(Unit); awaitCancellation() }
        }
        val target = Destination()
        val client = DshWorkspaceFiles(transport, "session-a")
        val file = client.list(listOf("reports")).entries.single() as WorkspaceExportFile
        val transfer = launch { client.save(file, target) }
        withTimeout(3_000) { parked.await() }
        assertEquals(chunk, target.output.size())
        transfer.cancelAndJoin()
        assertTrue(target.aborted)
        assertFalse(target.committed)
        assertEquals(0, target.output.size())
    }

    @Test fun rejectsUntrustedPathsAndMalformedChunksWithoutPublication() = runBlocking {
        val transport = WorkspaceTransport(ByteArray(chunk + 1), invalidEof = true)
        val client = DshWorkspaceFiles(transport, "session-a")
        for (parts in listOf(listOf(".."), listOf("/home"), listOf("a\\b"))) {
            expectFailure<ProtocolException> { client.list(parts) }
        }
        val target = Destination()
        val file = client.list(listOf("reports")).entries.single() as WorkspaceExportFile
        expectFailure<RemoteRequestException> { client.save(file, target) }
        assertTrue(target.aborted)
        assertFalse(target.committed)
    }

    private suspend inline fun <reified T : Exception> expectFailure(crossinline block: suspend () -> Unit) {
        try { block(); fail("Expected ${T::class.simpleName}") }
        catch (error: Exception) { if (error !is T) throw error }
    }

    private inner class WorkspaceTransport(
        private val data: ByteArray,
        private val invalidEof: Boolean = false,
        private val awaiting: suspend (Long) -> Unit = {},
    ) : DshTransport {
        override val runtimeId = "fixture"
        override val state = MutableStateFlow(DshTransportState(DshTransportPath.LAN, DshTransportPhase.READY))
        val requests = mutableListOf<Pair<Long, Int>>()
        override suspend fun native(endpoint: String, args: JsonObject): JsonValue {
            assertEquals("session-a", args.string("sessionId"))
            if (endpoint == "workspaceExports/list") {
                args.exactKeys("sessionId", "parts")
                val entries = if (args.array("parts").isEmpty()) {
                    jsonObject("name" to "reports".json(), "kind" to "directory".json())
                } else {
                    assertEquals(listOf("reports".json()), args.array("parts"))
                    jsonObject("name" to "result %.bin".json(), "kind" to "file".json(), "size" to data.size.toLong().json(), "version" to version.json())
                }
                return jsonObject("entries" to JsonArray(listOf(entries)), "truncated" to JsonBoolean(false))
            }
            assertEquals("workspaceExports/read", endpoint)
            args.exactKeys("sessionId", "request")
            val request = args.obj("request")
            assertEquals(listOf("reports".json(), "result %.bin".json()), request.array("parts"))
            assertEquals(data.size.toLong(), request.integer("size", 0))
            assertEquals(version, request.string("version"))
            val offset = request.integer("offset", 0)
            val count = request.integer("length", 1, chunk.toLong()).toInt()
            requests.add(offset to count)
            awaiting(offset)
            val end = (offset + count).coerceAtMost(data.size.toLong()).toInt()
            return jsonObject("offset" to offset.json(),
                "data" to Base64.getEncoder().encodeToString(data.copyOfRange(offset.toInt(), end)).json(),
                "eof" to JsonBoolean(invalidEof || end == data.size))
        }
        override suspend fun openMux(): DshMux = error("not a workspace transfer")
        override suspend fun download(path: String, sink: OutputStream, maxBytes: Long, range: DshByteRange?): DshDownload = error("workspace must not use absolute file routes")
        override suspend fun upload(path: String, mediaType: String, source: InputStream, sizeBytes: Long): JsonValue = error("read-only workspace")
        override fun close() {}
    }

    private class Destination : FileExportTarget {
        val output = ByteArrayOutputStream()
        var committed = false
        var aborted = false
        override fun open(): OutputStream = output
        override fun commit() { committed = true }
        override fun abort() { aborted = true; output.reset() }
    }
}
