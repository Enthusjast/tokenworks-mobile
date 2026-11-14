package cn.edu.ustc.tokenworks

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeImagePromptOutboxTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun nativeReaderSendsTheExactDurableImageRequestAgainOnlyAfterExplicitRetry() = runBlocking {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val store = EncryptedFileOutboxStore(File(temporary.root, "images.bin"), OutboxKeyProvider { key })
        val outbox = DshCommandOutbox.open(store)
        val prepared = outbox.prepare(transcriptRoute, "text 原样", "steer", listOf("opaque receipt"),
            listOf(nativeOutboxImage(NativeImagePart.MAX_BATCH_ENCODED_BYTES, "原样.png")))
        assertEquals(prepared, store.load().entries.single())
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = ImagePromptTransport()
        val reader = DshSessionReader(transport, transcriptRoute.sessionId, owner)
        try {
            transport.baseline()
            withTimeout(5000) { reader.state.first { it.phase == SessionReadPhase.READY } }
            outbox.markSending(prepared.requestId, prepared.route)
            transport.loseResponse = true
            try {
                reader.prompt(prepared.text, prepared.requestId, prepared.mode, prepared.receipts, prepared.images)
                fail("lost response must not settle the durable intent")
            } catch (_: IOException) { }
            val first = withTimeout(5000) { transport.prompts.receive() }
            val request = DshMethods.SessionPrompt.input.decode(first).request
            assertEquals(prepared.requestId, request.requestId)
            assertEquals(prepared.sessionId, request.sessionId)
            assertEquals(prepared.mode, request.mode)
            val content = first.obj("request").array("content")
            assertEquals(listOf("text", "file", "image"), content.map { (it as JsonObject).string("type") })
            assertEquals(prepared.images.single().data,
                DshSessionPromptInputRequestContentItemCase2.codec.decode(content.last()).data)

            val recovered = DshCommandOutbox.open(store)
            assertEquals(PromptDeliveryState.OUTCOME_UNKNOWN, recovered.pending(prepared.route).single().state)
            assertTrue(transport.prompts.tryReceive().isFailure)
            val retry = recovered.retry(prepared.requestId, prepared.route)!!
            assertEquals(prepared, retry)
            recovered.markSending(retry.requestId, retry.route)
            reader.prompt(retry.text, retry.requestId, retry.mode, retry.receipts, retry.images)
            val second = withTimeout(5000) { transport.prompts.receive() }
            assertEquals(WireJson.encode(first), WireJson.encode(second))
            recovered.settle(retry.requestId, retry.route)
            assertTrue(DshCommandOutbox.open(store).snapshot.value.isEmpty())

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { reader.prompt("", prepared.requestId, images = List(5) { nativeOutboxImage() }) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { reader.prompt("x".repeat(DshCommandOutbox.MAX_TEXT_BYTES + 1), prepared.requestId, images = prepared.images) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { reader.prompt("", prepared.requestId, images = prepared.images + nativeOutboxImage()) }
            }
            assertTrue(transport.prompts.tryReceive().isFailure)
        } finally {
            reader.close()
            owner.cancel()
        }
    }

    private class ImagePromptTransport : DshTransport {
        override val runtimeId = transcriptRuntime
        override val state = MutableStateFlow(DshTransportState(DshTransportPath.RELAY, DshTransportPhase.READY))
        private val opening = CompletableDeferred<Pair<DshMux, String>>()
        val prompts = Channel<JsonObject>(Channel.UNLIMITED)
        var loseResponse = false

        override suspend fun openMux(): DshMux {
            lateinit var mux: DshMux
            mux = DshMux({ text ->
                val frame = WireJson.parse(text)
                if (frame.string("type") == "open") opening.complete(mux to frame.string("streamId"))
                true
            }, { _, _ -> })
            return mux
        }

        suspend fun baseline() {
            val (mux, streamId) = withTimeout(5000) { opening.await() }
            val snapshot = jsonObject("type" to "snapshot".json(),
                "header" to transcriptIdentity.json().with("cwd" to "/isolated".json(), "isSeeded" to JsonBoolean(false), "agentPreset" to "fixture".json()),
                "cursor" to 0L.json(), "hasMore" to JsonBoolean(false),
                "records" to JsonArray(listOf(jsonObject("type" to "event".json(), "event" to transcriptEvent(0)))),
                "projections" to jsonObject("asOfSeq" to 0L.json(), "values" to jsonObject()),
                "assistantStream" to jsonObject("revision" to 0L.json()))
            mux.receive(WireJson.encode(jsonObject("type" to "item".json(), "streamId" to streamId.json(), "value" to snapshot)))
        }

        override suspend fun native(endpoint: String, args: JsonObject): JsonValue {
            check(endpoint == "session/prompt")
            prompts.send(args)
            if (loseResponse) {
                loseResponse = false
                throw IOException("simulated lost response")
            }
            return JsonNull
        }
        override suspend fun download(path: String, sink: OutputStream, maxBytes: Long, range: DshByteRange?): DshDownload = error("unexpected download")
        override suspend fun upload(path: String, mediaType: String, source: InputStream, sizeBytes: Long): JsonValue = error("unexpected upload")
        override fun close() { }
    }
}
