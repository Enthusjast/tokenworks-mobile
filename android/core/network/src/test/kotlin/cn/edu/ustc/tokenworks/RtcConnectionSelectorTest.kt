package cn.edu.ustc.tokenworks

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class RtcConnectionSelectorTest {
    private val runtime = "11111111-1111-4111-8111-111111111111"
    private val installation = "22222222-2222-4222-8222-222222222222"
    private inner class Candidate(path: DshTransportPath, override val bindingIdentity: String = "same-binding") : EncryptedDshTransport {
        override val runtimeId = runtime
        override val installationId = installation
        override val state = MutableStateFlow(DshTransportState(path, DshTransportPhase.CONNECTING))
        var probes = 0; var calls = 0; var closed = false
        var probeFailure: Exception? = null
        var onProbe: suspend () -> Unit = {}
        var onConnect: suspend () -> DshConnection = { DshRouteConnection(this) }
        var callFailure: Exception? = null
        var partial = false
        override suspend fun probeIdentity() {
            probes++
            onProbe()
            probeFailure?.let { throw it }
            state.value = state.value.copy(phase = DshTransportPhase.READY)
        }
        override suspend fun connect(): DshConnection = onConnect()
        override suspend fun native(endpoint: String, args: JsonObject): JsonValue { calls++; callFailure?.let { throw it }; return jsonObject() }
        override suspend fun openMux(): DshMux = error("not part of this test")
        override suspend fun download(path: String, sink: OutputStream, maxBytes: Long, range: DshByteRange?): DshDownload {
            calls++; if (partial) sink.write(42); callFailure?.let { throw it }; return DshDownload("application/octet-stream", 0, null, null)
        }
        override suspend fun upload(path: String, mediaType: String, source: InputStream, sizeBytes: Long): JsonValue = native("upload", jsonObject())
        override fun close() { closed = true; state.value = state.value.copy(phase = DshTransportPhase.CLOSED) }
    }
    @Test fun unavailableCandidatesAdvanceInExactOrder() = runBlocking {
        val order = mutableListOf<DshTransportPath>()
        fun candidate(path: DshTransportPath, failure: Exception? = null) = Candidate(path).apply {
            probeFailure = failure
            onProbe = { order += path }
        }
        val lan = candidate(DshTransportPath.LAN, RemoteRequestException("network.unavailable"))
        val p2p = candidate(DshTransportPath.P2P, DshFailure("rtc.disabled"))
        val turn = candidate(DshTransportPath.TURN, DshFailure("rtc.unavailable"))
        val relay = candidate(DshTransportPath.RELAY)
        val failures = mutableListOf<DshTransportState>()
        ConnectionSelector(listOf(turn, relay, lan, p2p), installation) { failures += it }.use { selector ->
            selector.native("session/list", jsonObject())
            assertEquals(DshTransportPath.entries, order)
            assertEquals(DshTransportPath.entries.dropLast(1), failures.map { it.path })
            assertEquals(listOf("network.unavailable", "rtc.disabled", "rtc.unavailable"), failures.map { it.error })
            assertTrue(lan.closed); assertTrue(p2p.closed); assertTrue(turn.closed)
            assertEquals(DshTransportPath.RELAY, selector.state.value.path)
        }
    }
    @Test fun identityPinAuthorizationAndCapabilityAreTerminal() = runBlocking {
        for (code in listOf("e2e.repair_required", "e2e.unsupported", "e2e.owner_mismatch", "e2e.relay_authority_required",
            "e2e.auth_unconfirmed", "lan.tls_failed", "access.denied", "lan.revoked", "rtc.consent_required", "gateway/unauthorized")) {
            val p2p = Candidate(DshTransportPath.P2P).apply { probeFailure = RemoteRequestException(code) }
            val relay = Candidate(DshTransportPath.RELAY)
            ConnectionSelector(listOf(p2p, relay), installation).use { selector ->
                assertNotNull(runCatching { selector.native("session/list", jsonObject()) }.exceptionOrNull())
                assertEquals(0, relay.probes); assertEquals(0, relay.calls)
            }
        }
        val bad = Candidate(DshTransportPath.P2P, "other-binding")
        val relay = Candidate(DshTransportPath.RELAY)
        assertTrue(runCatching { ConnectionSelector(listOf(bad, relay), installation) }.exceptionOrNull() is LanPairingException)
        assertTrue(bad.closed); assertTrue(relay.closed)
    }
    @Test fun ambiguousMutationIsNeverReplayedButFutureIntentCanAdvance() = runBlocking {
        val p2p = Candidate(DshTransportPath.P2P).apply { callFailure = RemoteRequestException("network.unavailable") }
        val relay = Candidate(DshTransportPath.RELAY)
        ConnectionSelector(listOf(p2p, relay), installation).use { selector ->
            assertNotNull(runCatching { selector.native("session/prompt", jsonObject()) }.exceptionOrNull())
            assertEquals(1, p2p.calls); assertEquals(0, relay.probes); assertEquals(0, relay.calls)
            selector.native("session/prompt", jsonObject())
            assertEquals(1, relay.calls)
        }
    }
    @Test fun readRetriesButPartialDownloadNeverAppendsFromAnotherCarrier() = runBlocking {
        val p2p = Candidate(DshTransportPath.P2P).apply { callFailure = RemoteRequestException("network.unavailable") }
        val relay = Candidate(DshTransportPath.RELAY)
        ConnectionSelector(listOf(p2p, relay), installation).use { it.native("session/list", jsonObject()) }
        assertEquals(1, p2p.calls); assertEquals(1, relay.calls)
        val broken = Candidate(DshTransportPath.P2P).apply { partial = true; callFailure = RemoteRequestException("network.unavailable") }
        val other = Candidate(DshTransportPath.RELAY)
        ConnectionSelector(listOf(broken, other), installation).use { selector ->
            val sink = ByteArrayOutputStream()
            assertNotNull(runCatching { selector.download("file", sink, 64) }.exceptionOrNull())
            assertArrayEquals(byteArrayOf(42), sink.toByteArray()); assertEquals(0, other.calls)
        }
    }
    @Test fun sinkFailureAfterPartialWriteNeverRetriesIntoDirtySink() = runBlocking {
        val p2p = Candidate(DshTransportPath.P2P).apply { partial = true }
        val relay = Candidate(DshTransportPath.RELAY)
        val bytes = ByteArrayOutputStream()
        val sink = object : OutputStream() {
            override fun write(value: Int) { bytes.write(value); throw RemoteRequestException("network.unavailable") }
        }
        ConnectionSelector(listOf(p2p, relay), installation).use { selector ->
            assertNotNull(runCatching { selector.download("file", sink, 64) }.exceptionOrNull())
            assertArrayEquals(byteArrayOf(42), bytes.toByteArray())
            assertEquals(0, relay.calls)
        }
    }

    @Test fun cancelledProbeNeitherAdvancesNorIncrementsFailures() = runBlocking {
        val p2p = Candidate(DshTransportPath.P2P)
        val relay = Candidate(DshTransportPath.RELAY)
        p2p.onProbe = {
            currentCoroutineContext().cancel()
            throw RemoteRequestException("network.unavailable")
        }
        val failures = mutableListOf<DshTransportState>()
        ConnectionSelector(listOf(p2p, relay), installation) { failures += it }.use { selector ->
            val waiting = async { selector.native("session/list", jsonObject()) }
            assertTrue(runCatching { waiting.await() }.exceptionOrNull() is CancellationException)
            assertFalse(p2p.closed)
            assertEquals(0, relay.probes)
            assertTrue(failures.isEmpty())
            p2p.onProbe = {}
            selector.native("session/list", jsonObject())
            assertEquals(2, p2p.probes)
        }
    }

    @Test fun cancelledConnectionResultIsClosedAndDoesNotCauseFallback() = runBlocking {
        val p2p = Candidate(DshTransportPath.P2P)
        val relay = Candidate(DshTransportPath.RELAY)
        var closed = false
        p2p.onConnect = {
            currentCoroutineContext().cancel()
            object : DshConnection by DshRouteConnection(p2p) {
                override fun close() { closed = true }
            }
        }
        ConnectionSelector(listOf(p2p, relay), installation).use { selector ->
            val connecting = async { selector.connect() }
            assertTrue(runCatching { connecting.await() }.exceptionOrNull() is CancellationException)
            assertTrue(closed)
            assertEquals(0, relay.probes)
        }
    }

    @Test fun availabilityDuringConnectCanFallBackButAuthorizationAfterConnectIsTerminal() = runBlocking {
        val p2p = Candidate(DshTransportPath.P2P)
        val turn = Candidate(DshTransportPath.TURN)
        val relay = Candidate(DshTransportPath.RELAY)
        p2p.onConnect = { throw DshFailure("wire/disconnected") }
        val rejected = DshFailure("gateway/forbidden")
        ConnectionSelector(listOf(relay, p2p, turn), installation).use { selector ->
            selector.connect().use { owned ->
                assertTrue(p2p.closed)
                assertEquals(DshTransportPath.TURN, selector.state.value.path)
                turn.callFailure = rejected
                assertSame(rejected, runCatching { owned.native("session/list", jsonObject()) }.exceptionOrNull())
            }
            turn.callFailure = RemoteRequestException("network.unavailable")
            assertSame(rejected, runCatching { selector.native("session/list", jsonObject()) }.exceptionOrNull())
            assertEquals(DshTransportPhase.REVOKED, selector.state.value.phase)
            assertEquals(0, relay.probes)
        }
    }

    @Test fun pinnedRouteSurvivesAnAvailabilityFailureWithoutChangingPath() = runBlocking {
        val turn = Candidate(DshTransportPath.TURN).apply { callFailure = DshFailure("rtc.unavailable") }
        ConnectionSelector(listOf(turn), installation).use { selector ->
            assertNotNull(runCatching { selector.native("session/list", jsonObject()) }.exceptionOrNull())
            assertEquals(DshTransportPhase.BACKOFF, selector.state.value.phase)
            turn.callFailure = null
            selector.native("session/list", jsonObject())
            assertEquals(2, turn.probes)
            assertEquals(DshTransportPath.TURN, selector.state.value.path)
            assertFalse(turn.closed)
        }
    }
}
