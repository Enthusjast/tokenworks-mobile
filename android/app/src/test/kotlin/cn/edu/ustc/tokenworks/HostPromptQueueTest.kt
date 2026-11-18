package cn.edu.ustc.tokenworks

import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostPromptQueueTest {
    private val id = "61324820-22c9-4b65-8d23-c224627eb377"
    private fun queued(text: String, withFile: Boolean = false) = jsonObject(
        "id" to id.json(), "role" to "user".json(), "source" to jsonObject("kind" to "user".json(), "rpcId" to "original-request".json()),
        "content" to JsonArray(buildList {
            add(jsonObject("type" to "text".json(), "text" to text.json()))
            if (withFile) add(jsonObject("type" to "file".json(), "attachment" to jsonObject("name" to "notes.txt".json())))
        }),
    )

    @Test fun refreshesBeforeChangingAndOnlySendsAuthoritativeItemId() = runBlocking {
        val route = InboxTransport(queued("Draft A"))
        val queue = HostPromptQueue(route, "scoped-session")
        val before = queue.load().single()
        route.item = queued("Draft B")
        assertFalse(queue.change(before, "Mine") { true })
        assertTrue(route.writes.isEmpty())
        val current = queue.load().single()
        assertFalse(queue.change(current, "Mine") { false })
        assertTrue(queue.change(current, "Mine") { true })
        val write = route.writes.single().obj("request")
        assertEquals("scoped-session", write.string("sessionId"))
        assertEquals(id, write.string("itemId"))
        assertEquals("Mine", (write.obj("action").array("content").single() as JsonObject).string("text"))
    }

    @Test fun attachmentMessagesCanBeRemovedButNotReplacedWithText() = runBlocking {
        val route = InboxTransport(queued("Draft with attachment", withFile = true))
        val queue = HostPromptQueue(route, "scoped-session")
        val item = queue.load().single()
        assertFalse(item.textOnly)
        assertFalse(queue.change(item, "Lose attachment") { true })
        assertTrue(route.writes.isEmpty())
        assertTrue(queue.change(item, null) { true })
        assertEquals("remove", route.writes.single().obj("request").obj("action").string("kind"))
    }

    private class InboxTransport(var item: JsonObject) : DshTransport {
        override val runtimeId = "scoped-runtime"
        override val state = MutableStateFlow(DshTransportState(DshTransportPath.LAN, DshTransportPhase.READY))
        val writes = mutableListOf<JsonObject>()
        override suspend fun native(endpoint: String, args: JsonObject): JsonValue = when (endpoint) {
            "session/projections" -> jsonObject("kind" to "sequenced".json(), "asOfSeq" to 5L.json(), "values" to jsonObject(
                "inbox" to jsonObject("next-turn" to JsonArray(listOf(item)), "next-step" to JsonArray(emptyList())),
            ))
            "session/updateQueue" -> { writes += args; jsonObject("accepted" to JsonBoolean(true)) }
            else -> error("Unexpected method")
        }
        override suspend fun openMux(): DshMux = error("No stream expected")
        override suspend fun download(path: String, sink: OutputStream, maxBytes: Long, range: DshByteRange?): DshDownload = error("No download expected")
        override suspend fun upload(path: String, mediaType: String, source: InputStream, sizeBytes: Long): JsonValue = error("No upload expected")
        override fun close() = Unit
    }
}
