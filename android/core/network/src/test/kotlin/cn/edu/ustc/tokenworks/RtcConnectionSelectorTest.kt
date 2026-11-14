package cn.edu.ustc.tokenworks

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
        var callFailure: Exception? = null
        var partial = false
        override suspend fun probeIdentity() { probes++; probeFailure?.let { throw it }; state.value = state.value.copy(phase = DshTransportPhase.READY) }
        override suspend fun native(endpoint: String, args: JsonObject): JsonValue { calls++; callFailure?.let { throw it }; return jsonObject() }
        override suspend fun openMux(): DshMux = error("not part of this test")
        override suspend fun download(path: String, sink: OutputStream, maxBytes: Long, range: DshByteRange?): DshDownload {
            calls++; if (partial) sink.write(42); callFailure?.let { throw it }; return DshDownload("application/octet-stream", 0, null, null)
        }
        override suspend fun upload(path: String, mediaType: String, source: InputStream, sizeBytes: Long): JsonValue = native("upload", jsonObject())
        override fun close() { closed = true; state.value = state.value.copy(phase = DshTransportPhase.CLOSED) }
    }
    @Test fun unavailableCandidatesAdvanceInExactOrder() = runBlocking {
        val p2p = Candidate(DshTransportPath.P2P).apply { probeFailure = RemoteRequestException("network.unavailable") }
        val turn = Candidate(DshTransportPath.TURN).apply { probeFailure = RemoteRequestException("network.unavailable") }
        val relay = Candidate(DshTransportPath.RELAY)
        ConnectionSelector(null, relay, installation, listOf(p2p, turn)).use { selector ->
            selector.native("session/list", jsonObject())
            assertEquals(1, p2p.probes); assertEquals(1, turn.probes); assertEquals(1, relay.probes)
            assertTrue(p2p.closed); assertTrue(turn.closed)
            assertEquals(DshTransportPath.RELAY, selector.state.value.path)
        }
    }
    @Test fun identityPinAuthorizationAndCapabilityAreTerminal() = runBlocking {
        for (code in listOf("e2e.repair_required", "e2e.unsupported", "e2e.owner_mismatch", "e2e.relay_authority_required", "lan.tls_failed", "access.denied", "lan.revoked")) {
            val p2p = Candidate(DshTransportPath.P2P).apply { probeFailure = RemoteRequestException(code) }
            val relay = Candidate(DshTransportPath.RELAY)
            ConnectionSelector(null, relay, installation, listOf(p2p)).use { selector ->
                assertNotNull(runCatching { selector.native("session/list", jsonObject()) }.exceptionOrNull())
                assertEquals(0, relay.probes); assertEquals(0, relay.calls)
            }
        }
        val bad = Candidate(DshTransportPath.P2P, "other-binding")
        val relay = Candidate(DshTransportPath.RELAY)
        assertTrue(runCatching { ConnectionSelector(null, relay, installation, listOf(bad)) }.exceptionOrNull() is LanPairingException)
        assertTrue(bad.closed); assertTrue(relay.closed)
    }
    @Test fun ambiguousMutationIsNeverReplayedButFutureIntentCanAdvance() = runBlocking {
        val p2p = Candidate(DshTransportPath.P2P).apply { callFailure = RemoteRequestException("network.unavailable") }
        val relay = Candidate(DshTransportPath.RELAY)
        ConnectionSelector(null, relay, installation, listOf(p2p)).use { selector ->
            assertNotNull(runCatching { selector.native("session/prompt", jsonObject()) }.exceptionOrNull())
            assertEquals(1, p2p.calls); assertEquals(0, relay.probes); assertEquals(0, relay.calls)
            selector.native("session/prompt", jsonObject())
            assertEquals(1, relay.calls)
        }
    }
    @Test fun readRetriesButPartialDownloadNeverAppendsFromAnotherCarrier() = runBlocking {
        val p2p = Candidate(DshTransportPath.P2P).apply { callFailure = RemoteRequestException("network.unavailable") }
        val relay = Candidate(DshTransportPath.RELAY)
        ConnectionSelector(null, relay, installation, listOf(p2p)).use { it.native("session/list", jsonObject()) }
        assertEquals(1, p2p.calls); assertEquals(1, relay.calls)
        val broken = Candidate(DshTransportPath.P2P).apply { partial = true; callFailure = RemoteRequestException("network.unavailable") }
        val other = Candidate(DshTransportPath.RELAY)
        ConnectionSelector(null, other, installation, listOf(broken)).use { selector ->
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
        ConnectionSelector(null, relay, installation, listOf(p2p)).use { selector ->
            assertNotNull(runCatching { selector.download("file", sink, 64) }.exceptionOrNull())
            assertArrayEquals(byteArrayOf(42), bytes.toByteArray())
            assertEquals(0, relay.calls)
        }
    }
}
