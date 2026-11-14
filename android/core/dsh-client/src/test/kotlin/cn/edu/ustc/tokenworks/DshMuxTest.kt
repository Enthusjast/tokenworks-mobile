package cn.edu.ustc.tokenworks

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private class RecordingWriter(var accept: Boolean = true) {
    val frames = mutableListOf<String>()
    fun write(text: String): Boolean { if (!accept) return false; frames.add(text); return true }
}
private fun muxFrame(type: String, streamId: String, value: JsonValue? = null): String =
    WireJson.encode(if (value == null) jsonObject("type" to type.json(), "streamId" to streamId.json())
    else jsonObject("type" to type.json(), "streamId" to streamId.json(), "value" to value))

class DshMuxTest {
    private val writer = RecordingWriter()
    private val closings = mutableListOf<Pair<Int, String?>>()
    private val mux = DshMux(writer::write, { code, reason -> closings.add(code to reason) })

    @Test fun openWritesStandardNativeFramesAndUplink() = runTest {
        val stream = mux.open("session/follow", jsonObject())
        assertEquals("1", stream.id)
        assertEquals("open", JsonDocument.parse(writer.frames.single()).root.string("type"))
        stream.send(DshOptional.Value("hello".json())); stream.end()
        assertEquals(listOf("open", "item", "end"), writer.frames.map { JsonDocument.parse(it).root.string("type") })
        assertEquals("hello", JsonDocument.parse(writer.frames[1]).root.string("value"))
    }

    @Test fun delayedConsumerRetainsMixedDurableAndEverySequentialDeltaAndEnd() = runTest {
        val bounded = DshMux(writer::write, { _, _ -> }, streamCapacity = 4)
        val stream = bounded.open("session/follow", jsonObject())
        val values = listOf(jsonObject("type" to "snapshot".json()),
            jsonObject("type" to "assistant-stream".json(), "index" to 0L.json()),
            jsonObject("type" to "event".json()),
            jsonObject("type" to "assistant-stream".json(), "index" to 1L.json()))
        values.forEach { bounded.receive(muxFrame("item", "1", it)) }
        bounded.receive(muxFrame("end", "1"))
        for (value in values) {
            val frame = withTimeout(1000) { stream.next() } as DshMuxFrame.Item
            assertEquals(WireJson.encode(value), WireJson.encode((frame.value as DshOptional.Value).value as JsonObject))
        }
        assertEquals(DshMuxFrame.End("1"), stream.next()); assertNull(stream.next())
    }

    @Test fun fullQueuePreservesRemoteErrorThenCompletes() = runTest {
        val bounded = DshMux(writer::write, { _, _ -> }, streamCapacity = 1)
        val stream = bounded.open("x", jsonObject())
        bounded.receive(muxFrame("item", "1", "kept".json()))
        bounded.receive("""{"type":"error","streamId":"1","error":{"code":"denied","message":"denied","details":{}}}""")
        assertTrue(stream.next() is DshMuxFrame.Item)
        assertEquals("denied", (stream.next() as DshMuxFrame.Error).error.code)
        assertNull(stream.next())
    }

    @Test fun slowPeerNeverBlocksProducerOrSilentlyDropsOldFrames() = runTest {
        val bounded = DshMux(writer::write, { code, reason -> closings.add(code to reason) }, streamCapacity = 2)
        val stream = bounded.open("x", jsonObject())
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit { repeat(300) { bounded.receive(muxFrame("item", "1", it.toLong().json())) } }.get(2, TimeUnit.SECONDS)
            assertTrue(bounded.closed()); bounded.close()
            assertEquals(1013 to "wire/slow-peer", closings.single())
            repeat(2) { assertEquals(DshMuxFrame.Item("1", DshOptional.Value(it.toLong().json())), stream.next()) }
            assertEquals("wire/slow-peer", (stream.next() as DshMuxFrame.Error).error.code)
            assertNull(stream.next())
        } finally { executor.shutdownNow() }
    }

    @Test fun aggregateCountAndByteBudgetsIncludeOtherStreams() = runTest {
        val countBounded = DshMux(writer::write, { _, _ -> }, muxCapacity = 2)
        val a = countBounded.open("a", jsonObject()); val b = countBounded.open("b", jsonObject())
        countBounded.receive(muxFrame("item", "1", "a".json())); countBounded.receive(muxFrame("item", "2", "b".json()))
        countBounded.receive(muxFrame("item", "1", "overflow".json()))
        assertTrue(countBounded.closed())
        assertTrue(a.next() is DshMuxFrame.Item); assertTrue(b.next() is DshMuxFrame.Item)
        assertEquals("wire/slow-peer", (a.next() as DshMuxFrame.Error).error.code)
        val byteBounded = DshMux(writer::write, { _, _ -> }, muxByteCapacity = 200)
        val stream = byteBounded.open("x", jsonObject())
        byteBounded.receive(muxFrame("item", "1", "a".repeat(100).json()))
        byteBounded.receive(muxFrame("item", "1", "b".repeat(100).json()))
        assertTrue(byteBounded.closed()); assertTrue(stream.next() is DshMuxFrame.Item)
        assertEquals("wire/slow-peer", (stream.next() as DshMuxFrame.Error).error.code)
    }

    @Test fun closeAndCancelWakePendingReadersIdempotently() = runTest {
        val first = mux.open("a", jsonObject()); val second = mux.open("b", jsonObject())
        val waitingFirst = async(start = CoroutineStart.UNDISPATCHED) { first.next() }
        val waitingSecond = async(start = CoroutineStart.UNDISPATCHED) { second.next() }
        first.close(); first.close(); mux.close(); mux.close()
        assertNull(withTimeout(1000) { waitingFirst.await() }); assertNull(withTimeout(1000) { waitingSecond.await() })
        assertEquals(listOf("open", "open", "cancel"), writer.frames.map { JsonDocument.parse(it).root.string("type") })
        assertEquals(listOf(1000 to null), closings)
    }

    @Test fun socketFailureDrainsThenReportsSafeCodeAndWakesReader() = runTest {
        val stream = mux.open("x", jsonObject())
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { stream.next() }
        mux.fail("secret body token")
        assertEquals("wire/disconnected", (waiting.await() as DshMuxFrame.Error).error.code)
        assertNull(stream.next())
    }

    @Test fun malformedInputIsExplicitAndLateKnownStreamFramesAreHarmless() = runTest {
        val stream = mux.open("x", jsonObject())
        mux.receive("{not json")
        assertTrue(mux.closed()); assertEquals("wire/invalid-frame", (stream.next() as DshMuxFrame.Error).error.code)
        assertNull(stream.next())
        val healthy = DshMux(writer::write, { _, _ -> })
        val ended = healthy.open("x", jsonObject()); healthy.receive(muxFrame("end", "1"))
        healthy.receive(muxFrame("item", "1", "late".json()))
        assertEquals(DshMuxFrame.End("1"), ended.next()); assertNull(ended.next()); assertFalse(healthy.closed())
    }

    @Test fun failingWriterEndsMuxAndQueuedSocketPressureIsExplicit() {
        writer.accept = false
        assertEquals("native mux closed", assertThrows(DshFailure::class.java) { mux.open("x", jsonObject()) }.code)
        assertTrue(mux.closed())
        val pressure = DshMux({ true }, { _, _ -> }, queuedWriteBytes = { WireBudget.QUEUE_BYTES.toLong() })
        assertEquals("wire/slow-peer", assertThrows(DshFailure::class.java) { pressure.open("x", jsonObject()) }.code)
        assertTrue(pressure.closed())
    }

    @Test fun largeNonAsciiReassemblesBeforeMuxAndUplinkUsesSameCarrier() = runTest {
        val text = "你🙂".repeat(30_000)
        val stream = mux.open("x", jsonObject())
        val native = muxFrame("item", "1", text.json())
        WireCodec().encode(native).forEach(mux::receive)
        assertEquals(text, (((stream.next() as DshMuxFrame.Item).value as DshOptional.Value).value as JsonString).value)
        stream.send(DshOptional.Value(text.json()))
        val decoder = WireCodec()
        val decoded = writer.frames.drop(1).mapNotNull(decoder::decode).single()
        assertEquals(text, WireJson.parse(decoded).string("value"))
        mux.close(); assertNull(stream.next())
    }

    @Test fun oversizedLocalPayloadFailsWithoutPoisoningHealthyMux() {
        val error = assertThrows(DshFailure::class.java) { mux.open("x", jsonObject("text" to "x".repeat(3 * 1024 * 1024).json())) }
        assertEquals("wire/logical-limit", error.code); assertFalse(mux.closed()); assertTrue(writer.frames.isEmpty())
        assertEquals("2", mux.open("x", jsonObject()).id)
    }

    @Test fun uplinkEndIsIdempotentAndRejectsFurtherItems() {
        val stream = mux.open("x", jsonObject())
        stream.end(); stream.end()
        assertEquals(listOf("open", "end"), writer.frames.map { WireJson.parse(it).string("type") })
        assertEquals("wire/uplink-ended", assertThrows(DshFailure::class.java) { stream.send(DshOptional.Missing) }.code)
    }

    @Test fun throwingWriterFailsAllReadersAndPartialDisconnectNeverLeaksIntoReconnect() = runTest {
        var failWrite = false
        val broken = DshMux({ if (failWrite) throw IllegalStateException("private") else true }, { _, _ -> })
        val stream = broken.open("x", jsonObject())
        failWrite = true
        assertThrows(DshFailure::class.java) { stream.send(DshOptional.Missing) }
        assertTrue(broken.closed())
        assertEquals("wire/write-failed", (stream.next() as DshMuxFrame.Error).error.code)
        val native = muxFrame("item", "1", "你🙂".repeat(30_000).json())
        val fragments = WireCodec().encode(native).toList()
        val partial = mux.open("x", jsonObject())
        mux.receive(fragments.first()); mux.fail()
        assertEquals("wire/disconnected", (partial.next() as DshMuxFrame.Error).error.code)
        assertNull(partial.next())
        val fresh = DshMux({ true }, { _, _ -> })
        val resumed = fresh.open("x", jsonObject())
        fragments.forEach(fresh::receive)
        assertEquals("你🙂".repeat(30_000), (((resumed.next() as DshMuxFrame.Item).value as DshOptional.Value).value as JsonString).value)
        resumed.close(); fresh.close()
    }

    @Test fun terminalPayloadsShareAggregateBudgetAndReleaseOnCancellation() = runTest {
        val bounded = DshMux({ true }, { _, _ -> }, muxByteCapacity = 300)
        val first = bounded.open("x", jsonObject())
        bounded.receive("""{"type":"error","streamId":"1","error":{"code":"remote","message":"${"x".repeat(120)}","details":{}}}""")
        first.close()
        val second = bounded.open("x", jsonObject())
        bounded.receive(muxFrame("item", second.id, "x".repeat(120).json()))
        assertFalse(bounded.closed())
        assertTrue(second.next() is DshMuxFrame.Item)
        second.close(); bounded.close()
    }

    @Test fun outboundCountBudgetReconcilesDrainedBytesAndStalledWritesExpire() = runTest {
        var queued = 0L
        val bounded = DshMux({ queued += it.toByteArray().size; true }, { _, _ -> }, muxCapacity = 3,
            queuedWriteBytes = { queued })
        val stream = bounded.open("x", jsonObject())
        stream.send(DshOptional.Missing)
        queued = 0
        stream.send(DshOptional.Missing)
        stream.send(DshOptional.Missing)
        stream.send(DshOptional.Missing)
        assertEquals("wire/slow-peer", assertThrows(DshFailure::class.java) { stream.send(DshOptional.Missing) }.code)
        assertEquals("wire/slow-peer", (stream.next() as DshMuxFrame.Error).error.code)
        val stopped = CountDownLatch(1)
        var stalledBytes = 0L
        val stalled = DshMux({ stalledBytes += it.toByteArray().size; true }, { code, reason ->
            assertEquals(1013, code); assertEquals("wire/slow-peer", reason); stopped.countDown()
        }, queuedWriteBytes = { stalledBytes }, writeTimeoutMs = 20)
        val waiting = stalled.open("x", jsonObject())
        assertTrue(stopped.await(2, TimeUnit.SECONDS)); assertTrue(stalled.closed())
        assertEquals("wire/slow-peer", (waiting.next() as DshMuxFrame.Error).error.code)
        bounded.close(); stalled.close()
    }
}
