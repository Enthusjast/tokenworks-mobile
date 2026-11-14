package cn.edu.ustc.tokenworks

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WireCodecTest {
    private val spec = JsonDocument.parse(File(System.getProperty("contracts.root"), "golden/wire-fragment.json").readText()).root
    private val native = "{\"type\":\"item\",\"streamId\":\"1\",\"value\":\"" + spec.string("text").repeat(spec.integer("repeat").toInt()) + "\"}"
    private fun fragments() = WireCodec().encode(native).toList()

    @Test fun sharedCasesPreserveExactBytesAndRejectOrderReplayAndIntegrityFailures() {
        val frames = fragments()
        assertEquals(spec.integer("count").toInt(), frames.size)
        frames.forEachIndexed { index, text ->
            val frame = JsonDocument.parse(text).root
            assertEquals("1", frame.string("id")); assertEquals(index.toLong(), frame.integer("index", 0))
            assertEquals(spec.integer("bytes"), frame.integer("bytes")); assertEquals(spec.string("sha256"), frame.string("sha256"))
            assertTrue(text.toByteArray().size <= WireBudget.FRAME_BYTES)
        }
        for (value in spec.array("cases")) {
            val scenario = value as JsonObject
            val decoder = WireCodec()
            var deliveries = 0
            val decode = {
                for (step in scenario.array("indices")) {
                    var frame = frames[(step as JsonNumber).value.toInt()]
                    (scenario.fields["checksum"] as? JsonString)?.let { checksum ->
                        frame = JsonDocument.of(JsonDocument.parse(frame).root.with("sha256" to checksum)).canonicalText()
                    }
                    decoder.decode(frame)?.let { assertEquals(native, it); deliveries++ }
                }
            }
            try {
                val expected = (scenario.fields["error"] as? JsonString)?.value
                if (expected == null) { decode(); assertEquals(scenario.integer("deliveries").toInt(), deliveries) }
                else assertEquals(scenario.string("name"), expected, assertThrows(ProtocolException::class.java) { decode() }.message)
                assertEquals(0, decoder.bufferedBytes)
            } finally { decoder.close() }
        }
    }

    @Test fun sharedTimeoutAndPartialDisconnectAreTerminalAndReconnectStartsFresh() {
        val frames = fragments()
        val expired = CountDownLatch(1)
        val decoder = WireCodec(timeoutMs = spec.integer("timeoutMs"), timeout = { expired.countDown() })
        decoder.decode(frames.first())
        assertEquals(spec.integer("bytes").toInt(), decoder.bufferedBytes)
        assertTrue(expired.await(2, TimeUnit.SECONDS)); assertEquals(0, decoder.bufferedBytes)
        assertEquals("wire/closed", assertThrows(ProtocolException::class.java) { decoder.decode(frames.first()) }.message)
        val disconnected = WireCodec()
        frames.take(spec.integer("partialDisconnectAfter").toInt()).forEach(disconnected::decode)
        disconnected.close(); disconnected.close()
        assertEquals(0, disconnected.bufferedBytes)
        assertEquals("wire/closed", assertThrows(ProtocolException::class.java) { disconnected.decode(frames[1]) }.message)
        val reconnect = WireCodec()
        assertEquals(listOf(native), frames.mapNotNull(reconnect::decode)); reconnect.close()
    }

    @Test fun exactFrameAndLogicalLimitsIncludeLargeAsciiStringsAndSmallDenseNativeJson() {
        for (size in listOf(WireBudget.FRAME_BYTES, WireBudget.FRAME_BYTES + 1, WireBudget.LOGICAL_BYTES)) {
            val prefix = "{\"type\":\"item\",\"streamId\":\"1\",\"value\":\""
            val text = prefix + "x".repeat(size - prefix.length - 2) + "\"}"
            val encoder = WireCodec(); val decoder = WireCodec()
            val frames = encoder.encode(text).toList()
            assertEquals(size <= WireBudget.FRAME_BYTES, frames.size == 1)
            assertTrue(frames.all { it.toByteArray().size <= WireBudget.FRAME_BYTES })
            assertEquals(listOf(text), frames.mapNotNull(decoder::decode))
            assertEquals(text.length - prefix.length - 2, WireJson.parse(text).string("value").length)
            encoder.close(); decoder.close()
        }
        assertEquals("wire/logical-limit", assertThrows(ProtocolException::class.java) {
            WireCodec().encode("x".repeat(WireBudget.LOGICAL_BYTES + 1))
        }.message)
        val dense = "{\"type\":\"item\",\"streamId\":\"1\",\"value\":[" + List(20_000) { "0" }.joinToString(",") + "]}"
        assertEquals(dense, WireCodec().decode(dense))
        val value = "x".repeat(200_000)
        assertEquals(value, WireJson.parse(WireJson.encode(jsonObject("value" to value.json()))).string("value"))
        assertThrows(ProtocolException::class.java) { WireJson.encode(jsonObject("value" to "\uD800".json())) }
    }

    @Test fun monotonicIdsInterruptionAndInvalidBase64NeverRetainPartialMemory() {
        val encoder = WireCodec()
        val frames = encoder.encode(native).toList()
        assertEquals("2", JsonDocument.parse(encoder.encode(native).first()).root.string("id"))
        val invalid = JsonDocument.of(JsonDocument.parse(frames.first()).root.with("data" to "%%%".json())).canonicalText()
        for (sequence in listOf(listOf(invalid), listOf(frames.first(), "{\"type\":\"end\",\"streamId\":\"1\"}"))) {
            val decoder = WireCodec()
            assertThrows(ProtocolException::class.java) { sequence.forEach(decoder::decode) }
            assertEquals(0, decoder.bufferedBytes)
        }
    }
}
