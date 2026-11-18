package cn.edu.ustc.tokenworks

import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SessionUsageProjectionTest {
    @Test fun durableMessageUsesExplicitCountsUnlessOnlyCompactStreamHasThem() {
        val message = jsonObject("content" to JsonArray(emptyList()), "source" to jsonObject(
            "kind" to "model".json(), "provider" to "native".json(), "model" to "fixture".json(),
            "replayState" to "must-not-enter-display-projection".json(),
        ))
        val stream = JsonArray(listOf(sampleChunk(1), sampleChunk(4)))
        val data = jsonObject("turn" to 1L.json(), "step" to 2L.json(), "stream" to stream, "message" to message)
        val explicit = record("assistant/message", data.with("usage" to sample(6)))
        val fallback = record("assistant/message", data)
        val attempt = record("assistant/attempt", JsonObject(data.fields - "message"))
        assertEquals(SessionUsageEvent.Sample(1, 2, UsageCounts(6, 3, 2, 1), UsageModel("native", "fixture")), explicit.record.usage)
        assertEquals(SessionUsageEvent.Sample(1, 2, UsageCounts(4, 3, 2, 1), UsageModel("native", "fixture")), fallback.record.usage)
        assertEquals(SessionUsageEvent.Sample(1, 2, UsageCounts(4, 3, 2, 1), null), attempt.record.usage)
        assertFalse(SessionJson.encode(explicit.record.raw).toString(Charsets.UTF_8).contains("must-not-enter"))
        assertEquals(explicit.record.usage, DshTranscriptRecord.parse(explicit.json()).record.usage)
    }

    @Test fun controlChangesArrayCarriesCumulativeReplacementWithSequence() = runBlocking {
        lateinit var mux: DshMux
        val transport = object : DshTransport {
            override val runtimeId = "runtime"
            override val state = MutableStateFlow(DshTransportState(DshTransportPath.RELAY, DshTransportPhase.READY))
            override suspend fun openMux(): DshMux = DshMux({ text ->
                val frame = WireJson.parse(text)
                if (frame.string("type") == "open") {
                    val id = frame.string("streamId")
                    fun send(value: JsonObject) = mux.receive(WireJson.encode(jsonObject(
                        "type" to "item".json(), "streamId" to id.json(), "value" to value,
                    )))
                    send(jsonObject("type" to "baseline".json(), "value" to jsonObject("projections" to jsonObject("session" to jsonObject(
                        "asOfSeq" to 5L.json(), "values" to jsonObject("tokenUsage" to projection(10)),
                    )))))
                    fun event(seq: Long, input: Long) = jsonObject("type" to "projection".json(), "sessionId" to "session".json(),
                        "seq" to seq.json(), "changes" to JsonArray(listOf(jsonObject("key" to "tokenUsage".json(), "val" to projection(input)))))
                    send(event(4, 4))
                    send(event(6, 16))
                }
                true
            }, { _, _ -> }).also { mux = it }
            override suspend fun native(endpoint: String, args: JsonObject): JsonValue = error("unused")
            override suspend fun download(path: String, sink: OutputStream, maxBytes: Long, range: DshByteRange?): DshDownload = error("unused")
            override suspend fun upload(path: String, mediaType: String, source: InputStream, sizeBytes: Long): JsonValue = error("unused")
            override fun close() = Unit
        }
        val received = Channel<Pair<String, UsageSnapshot>>(Channel.UNLIMITED)
        val feed = SessionProjectionFeed(transport, this, tokenUsage = { id, value -> received.trySend(id to value) })
        try {
            withTimeout(3_000) {
                assertEquals("session" to UsageSnapshot(5, UsageCounts(10, 3, 2, 1)), received.receive())
                assertEquals("session" to UsageSnapshot(6, UsageCounts(16, 3, 2, 1)), received.receive())
            }
        } finally { feed.close() }
    }

    private fun sample(input: Long) = jsonObject("inputTokens" to input.json(), "outputTokens" to 3L.json(),
        "cacheReadTokens" to 2L.json(), "cacheWriteTokens" to 1L.json(), "reasoningTokens" to 2L.json(),
        "totalTokens" to (input + 6).json())
    private fun sampleChunk(input: Long) = jsonObject("type" to "chunk".json(), "time" to 100L.json(),
        "chunk" to jsonObject("type" to "usage".json(), "usage" to sample(input)))
    private fun projection(input: Long) = jsonObject("uncachedInputTokens" to input.json(), "outputTokens" to 3L.json(),
        "cacheReadTokens" to 2L.json(), "cacheWriteTokens" to 1L.json())
    private fun record(type: String, data: JsonObject) = DshTranscriptRecord.fromNative(jsonObject(
        "seq" to 7L.json(), "time" to 101L.json(), "type" to type.json(), "data" to data,
    ))
}
