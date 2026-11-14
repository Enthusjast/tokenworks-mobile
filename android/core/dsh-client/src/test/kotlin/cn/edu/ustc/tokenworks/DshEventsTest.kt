package cn.edu.ustc.tokenworks

import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

internal class InteractionTestTransport : DshTransport {
    override val runtimeId = "runtime"
    override val state = MutableStateFlow(DshTransportState(DshTransportPath.RELAY, DshTransportPhase.READY))
    val muxes = mutableListOf<DshMux>()
    val frames = mutableListOf<JsonObject>()
    val posts = mutableListOf<Pair<String, JsonObject>>()
    var result: suspend () -> JsonValue = { JsonBoolean(true) }
    override suspend fun native(endpoint: String, args: JsonObject): JsonValue { posts += endpoint to args; return result() }
    override suspend fun openMux(): DshMux = DshMux({ frames += JsonDocument.parse(it).root; true }, { _, _ -> }).also { muxes += it }
    override suspend fun download(path: String, sink: OutputStream, maxBytes: Long, range: DshByteRange?): DshDownload = error("unused")
    override suspend fun upload(path: String, mediaType: String, source: InputStream, sizeBytes: Long): JsonValue = error("unused")
    override fun close() { muxes.forEach { it.close() } }
}

internal fun assertNativeJson(expected: JsonObject, actual: JsonObject) =
    assertEquals(JsonDocument.of(expected).canonicalText(), JsonDocument.of(actual).canonicalText())

internal fun DshMux.event(value: JsonObject, streamId: String = "1") = receive(JsonDocument.of(jsonObject(
    "type" to "item".json(), "streamId" to streamId.json(), "value" to value)).canonicalText())
internal fun DshMux.ready(client: String = "client") = event(jsonObject("type" to "ready".json(),
    "clientId" to client.json(), "host" to jsonObject("home" to "/private".json())))
internal fun DshMux.waterfall(id: String = "event", event: String = "approval/request", request: JsonObject = jsonObject("toolName" to "read".json())) =
    event(jsonObject("type" to "waterfall".json(), "event" to event.json(), "eventId" to id.json(),
        "agentId" to "agent".json(), "request" to request))
internal fun DshMux.cancelEvent(id: String = "event") = event(jsonObject("type" to "cancel".json(), "eventId" to id.json()))
internal fun DshMux.eof() = receive("{\"type\":\"end\",\"streamId\":\"1\"}")

@OptIn(ExperimentalCoroutinesApi::class)
class DshEventsTest {
    @Test fun readyWaterfallUsesActualResultTransportAndWaitsForConfirmation() = runTest {
        val transport = InteractionTestTransport()
        val confirmation = CompletableDeferred<JsonValue>()
        transport.result = { confirmation.await() }
        val watcher = InteractionWatcher(transport, backgroundScope)
        try {
            runCurrent(); val mux = transport.muxes.single(); mux.ready(); mux.waterfall(); runCurrent()
            assertEquals(InteractionWatcher.Phase.ONLINE, watcher.phase.value)
            val key = watcher.interactions.value.single().eventId
            val first = backgroundScope.async { watcher.approve(key) }
            runCurrent()
            assertTrue(watcher.interactions.value.single().busy)
            assertEquals(1, transport.posts.size)
            val (endpoint, args) = transport.posts.single()
            assertEquals(DSH_EVENTS_RESULT_ENDPOINT, endpoint)
            assertNativeJson(jsonObject("clientId" to "client".json(), "eventId" to "event".json(),
                "outcome" to jsonObject("kind" to "result".json(), "value" to "allowed-once".json())), args)
            assertEquals("interaction.already_attempted", failure { watcher.approve(key) })
            confirmation.complete(JsonBoolean(true)); runCurrent(); first.await()
            assertTrue(watcher.interactions.value.isEmpty())
            mux.waterfall(); runCurrent()
            assertTrue(watcher.interactions.value.isEmpty())
        } finally { watcher.close() }
    }

    @Test fun cancellationDuringSendClearsImmediatelyAndRefusesStaleAnswer() = runTest {
        val transport = InteractionTestTransport()
        val waiting = CompletableDeferred<JsonValue>()
        transport.result = { waiting.await() }
        val watcher = InteractionWatcher(transport, backgroundScope)
        try {
            runCurrent(); val mux = transport.muxes.single(); mux.ready(); mux.waterfall(); runCurrent()
            val key = watcher.interactions.value.single().eventId
            val send = backgroundScope.async { runCatching { watcher.approve(key) } }
            runCurrent(); mux.cancelEvent(); runCurrent()
            assertTrue(watcher.interactions.value.isEmpty())
            assertTrue(send.await().isFailure)
            assertEquals("interaction.stale", failure { watcher.approve(key) })
            assertEquals(1, transport.posts.size)
        } finally { watcher.close() }
    }

    @Test fun ambiguousFailureCannotReplayAndNativeRejectionIsAResult() = runTest {
        val transport = InteractionTestTransport()
        transport.result = { throw DshFailure("network.lost") }
        val watcher = InteractionWatcher(transport, backgroundScope)
        try {
            runCurrent(); val mux = transport.muxes.single(); mux.ready(); mux.waterfall(); runCurrent()
            val key = watcher.interactions.value.single().eventId
            assertEquals("network.lost", failure { watcher.reject(key, "not sent as a fabricated exception") })
            runCurrent()
            assertEquals("interaction.result_unconfirmed", watcher.interactions.value.single().error)
            assertEquals("interaction.already_attempted", failure { watcher.approve(key) })
            assertEquals("rejected", transport.posts.single().second.obj("outcome").string("value"))
            assertEquals(1, transport.posts.size)
        } finally { watcher.close() }
    }

    @Test fun eofClearsAndReconnectsWithoutAcceptingOldGeneration() = runTest {
        val transport = InteractionTestTransport()
        val watcher = InteractionWatcher(transport, backgroundScope)
        try {
            runCurrent(); transport.muxes.single().apply { ready("first"); waterfall() }; runCurrent()
            val old = watcher.interactions.value.single().eventId
            transport.muxes.single().eof(); runCurrent()
            assertTrue(watcher.interactions.value.isEmpty())
            advanceTimeBy(1_000); runCurrent()
            assertEquals(2, transport.muxes.size)
            transport.muxes.last().apply { ready("second"); waterfall() }; runCurrent()
            assertNotEquals(old, watcher.interactions.value.single().eventId)
            assertEquals("interaction.stale", failure { watcher.approve(old) })
            watcher.approve(watcher.interactions.value.single().eventId)
            assertEquals("second", transport.posts.single().second.string("clientId"))
        } finally { watcher.close() }
    }

    @Test fun revokedIsTerminalEvenWhileAResultIsSuspended() = runTest {
        val transport = InteractionTestTransport()
        transport.result = { awaitCancellation() }
        val watcher = InteractionWatcher(transport, backgroundScope)
        runCurrent(); transport.muxes.single().apply { ready(); waterfall() }; runCurrent()
        val send = backgroundScope.async { runCatching { watcher.approve(watcher.interactions.value.single().eventId) } }
        runCurrent()
        transport.state.value = transport.state.value.copy(phase = DshTransportPhase.REVOKED)
        runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertEquals(InteractionWatcher.Phase.CLOSED, watcher.phase.value)
        assertTrue(watcher.interactions.value.isEmpty())
        assertTrue(send.await().isFailure)
        assertEquals(1, transport.muxes.size)
    }

    @Test fun quietReadyTimeoutSuspendsRatherThanBlockingAndCanReconnect() = runTest {
        val transport = InteractionTestTransport()
        val watcher = InteractionWatcher(transport, backgroundScope, readyTimeoutMillis = 100)
        try {
            runCurrent(); assertEquals(InteractionWatcher.Phase.CONNECTING, watcher.phase.value)
            advanceTimeBy(100); runCurrent()
            assertEquals("native.events.ready_timeout", watcher.errors.value)
            assertTrue(transport.muxes.single().closed())
            advanceTimeBy(1_000); runCurrent()
            transport.muxes.last().ready(); runCurrent()
            assertEquals(InteractionWatcher.Phase.ONLINE, watcher.phase.value)
        } finally { watcher.close() }
    }

    private suspend fun failure(block: suspend () -> Unit): String = try { block(); fail("expected DshFailure"); "" }
        catch (error: DshFailure) { error.code }
}
