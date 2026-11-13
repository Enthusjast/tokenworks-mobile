package cn.edu.ustc.tokenworks

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** The canonical writer records every frame so tests assert the exact wire shape. */
private class RecordingWriter(var accept: Boolean = true) {
    val frames = mutableListOf<String>()
    fun write(text: String): Boolean {
        if (!accept) return false
        frames.add(text)
        return true
    }
}

private fun muxFrame(type: String, streamId: String, build: (MutableMap<String, JsonValue>) -> Unit = {}): String {
    val fields = linkedMapOf<String, JsonValue>("type" to type.json(), "streamId" to streamId.json())
    build(fields)
    return JsonDocument.of(JsonObject(fields)).canonicalText()
}

class DshMuxTest {
    private val writer = RecordingWriter()
    private val closings = mutableListOf<Pair<Int, String?>>()
    private val mux = DshMux(writer::write) { code, reason -> closings.add(code to reason) }

    private fun itemFrame(streamId: String, value: JsonValue) =
        muxFrame("item", streamId) { it["value"] = value }
    private fun endFrame(streamId: String) = muxFrame("end", streamId)
    private fun openRequest(streamId: String, endpoint: String = "session/follow") = jsonObject(
        "type" to "open".json(), "streamId" to streamId.json(),
        "endpoint" to endpoint.json(), "payload" to jsonObject("sessionId" to "66666666-6666-4666-8666-666666666666".json()),
    )

    @Test fun openWritesFramedRequestAndStreamSendsItems() = runTest {
        val stream = mux.open("session/follow", jsonObject("sessionId" to "66666666-6666-4666-8666-666666666666".json()))
        assertEquals("1", stream.id)
        assertEquals(listOf(JsonDocument.of(openRequest("1")).canonicalText()), writer.frames)
        stream.send(DshOptional.Value("hello".json()))
        assertEquals(2, writer.frames.size)
        val item = JsonDocument.parse(writer.frames[1]).root
        assertEquals("item", item.string("type"))
        assertEquals("1", item.string("streamId"))
        assertEquals("hello", (item.fields["value"] as JsonString).value)
        stream.end()
        assertEquals("end", JsonDocument.parse(writer.frames[2]).root.string("type"))
    }

    @Test fun downlinkFramesRouteToTheirStreamUntilTerminal() = runTest {
        val stream = mux.open("session/follow", jsonObject())
        mux.receive(itemFrame("1", "chunk".json()))
        assertEquals(DshMuxFrame.Item("1", DshOptional.Value("chunk".json())), withTimeout(1000) { stream.next() })
        mux.receive(endFrame("1"))
        assertEquals(DshMuxFrame.End("1"), withTimeout(1000) { stream.next() })
        // A terminal frame deregisters the stream; later frames are dropped silently.
        mux.receive(itemFrame("1", "late".json()))
        assertThrows(TimeoutCancellationException::class.java) { kotlinx.coroutines.runBlocking { withTimeout(150) { stream.next() } } }
    }

    @Test fun unknownOrMalformedFramesAreDroppedWithoutThrowing() = runTest {
        mux.receive("{not json")
        mux.receive(muxFrame("item", "404") { it["value"] = "x".json() })
        mux.receive(muxFrame("nonsense", "1"))
        assertFalse(mux.closed())
    }

    @Test fun closeSignalsSocketAndDrainsAllStreams() = runTest {
        val first = mux.open("session/follow", jsonObject())
        val second = mux.open("session/follow", jsonObject())
        mux.close()
        assertEquals(listOf(1000 to null), closings)
        assertTrue(mux.closed())
        assertNull(withTimeout(1000) { first.next() })
        assertNull(withTimeout(1000) { second.next() })
    }

    @Test fun failingWriterFailsOpenAndRejectsLaterSends() = runTest {
        writer.accept = false
        try { mux.open("session/follow", jsonObject()); fail("open succeeded on a broken socket") }
        catch (expected: DshFailure) { assertEquals("native mux closed", expected.code) }
        // The mux itself stays open: only close()/fail() end it; a healthy writer
        // continues to work afterwards.
        writer.accept = true
        val stream = mux.open("session/follow", jsonObject())
        assertEquals("2", stream.id)
    }

    @Test fun cancelRemovesStreamAndSendsCancelFrame() = runTest {
        val stream = mux.open("session/follow", jsonObject())
        stream.close()
        assertEquals(2, writer.frames.size)
        assertEquals("cancel", JsonDocument.parse(writer.frames[1]).root.string("type"))
        // The deregistered stream drains nothing; a later frame is dropped silently.
        mux.receive(itemFrame("1", "late".json()))
        assertThrows(TimeoutCancellationException::class.java) { kotlinx.coroutines.runBlocking { withTimeout(150) { stream.next() } } }
        // A second cancel must not resend anything.
        stream.close()
        assertEquals(2, writer.frames.size)
    }

    @Test fun streamIdsIncreaseMonotonically() = runTest {
        val first = mux.open("a", jsonObject())
        val second = mux.open("b", jsonObject())
        assertEquals("1", first.id)
        assertEquals("2", second.id)
    }
}

class DshLeaseTest {
    @Test fun leaseIsUnusableInsideTheThirtySecondMargin() {
        val now = 1_000_000L
        val fresh = DshLease("token", "lease", now + 31_000)
        assertTrue(fresh.usable(now))
        val marginal = DshLease("token", "lease", now + 30_000)
        assertFalse(marginal.usable(now))
        val expired = DshLease("token", "lease", now - 1)
        assertFalse(expired.usable(now))
    }
}
