package cn.edu.ustc.tokenworks

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID

/** Opt-in isolated real Host + JNI + SCTP proof. Supply a fresh private fixture for each path. */
@RunWith(AndroidJUnit4::class)
class RtcNativeCarrierTest {
    @Test fun realNativeUnaryMuxFileApprovalAndOwnerFence() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        val input = arguments.getString("rtcHostFixture")
        assumeTrue("explicit isolated RTC Host fixture required", input != null)
        require(input == "rtc-host-fixture.json")
        val path = when (arguments.getString("rtcRoute")) {
            "p2p" -> DshTransportPath.P2P
            "turn" -> DshTransportPath.TURN
            else -> error("rtcRoute must explicitly select p2p or turn")
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.filesDir, input!!)
        require(source.isFile && source.length() in 1..65_536)
        val fixture = JsonDocument.parse(source.readBytes()).root
        check(source.delete())
        assertEquals(DshMethods.VERSION, fixture.string("dshVersion"))
        val descriptor = LanPairingDescriptor.parse(JsonDocument.of(fixture.obj("pairing")).canonicalText())
        val ca = CertificateFactory.getInstance("X.509").generateCertificate(fixture.string("operatorCaPem").byteInputStream()) as X509Certificate
        ca.checkValidity(); require(ca.basicConstraints >= 0)
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(ca).build()
        val http = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        val connections = LanConnectionFactory.withOperatorClient(http)
        val keys = ECKeyGenerator(Curve.P_256).generate()
        val signer = object : DeviceProofSigner {
            override val publicKey = PublicProofKey(JsonDocument.parse(keys.toPublicJWK().toJSONString()).root)
            override fun signJose(signingInput: ByteArray) = ECDSASigner(keys).sign(JWSHeader(JWSAlgorithm.ES256), signingInput).decode()
        }
        val ownerDocument = fixture.obj("owner")
        val owner = LocalDataOwner(ownerDocument.string("account_id"), ownerDocument.string("organization_id"), ownerDocument.string("deployment_sha256"),
            ownerDocument.string("user_id"), ownerDocument.string("device_id"))
        val store = AndroidLanCredentialStore(context, owner, signer.publicKey.thumbprint)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var active = true
        val current = { if (!active) throw CancellationException("owner epoch retired") }
        val pairing = PairingCoordinator(store, signer, scope, connections, current)
        val routes = AndroidRtcRoutes(context, pairing, { owner }, { null }, current)
        val staging = File(context.cacheDir, "rtc-proof-${UUID.randomUUID()}").apply { mkdirs() }
        var transport: EncryptedDshTransport? = null
        var replacement: EncryptedDshTransport? = null
        try {
            val intent = store.intent(descriptor, "Android native RTC proof", signer, null)
            withContext(Dispatchers.IO) { store.begin(intent); store.promote(connections.pair(signer, intent)) }
            pairing.restore()
            withTimeout(10_000) { pairing.state.first { !it.busy && it.devices.any { device -> device.runtimeId == descriptor.runtimeId } } }
            routes.setConsent(descriptor.runtimeId, false)
            assertFalse(routes.consent(descriptor.runtimeId))
            assertTrue(runCatching { routes.transport(descriptor.runtimeId, path) }.exceptionOrNull() is RemoteRequestException)
            assertTrue(routes.selectedCandidates(descriptor.runtimeId).isEmpty())
            routes.setConsent(descriptor.runtimeId, true)
            val route = routes.transport(descriptor.runtimeId, path).also { transport = it }
            withTimeout(40_000) { route.probeIdentity() }
            assertEquals(descriptor.installationId, route.installationId)
            assertEquals(path, route.state.value.path)
            assertEquals(DshTransportPhase.READY, route.state.value.phase)
            val selected = routes.selectedCandidates(descriptor.runtimeId)
            assertTrue("actual selected candidates required", selected.isNotEmpty())
            assertTrue(selected.all { it.path == path && it.protocol == "udp" && (if (path == DshTransportPath.TURN) it.localType == "relay" else it.localType != "relay" && it.remoteType != "relay") })
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("rtc_selected_candidates", selected.joinToString { "${it.path}:${it.localType}/${it.remoteType}:${it.protocol}" })
            })
            val sessionId = fixture.string("sessionId")
            assertTrue(DshSessionClient(route).sessions().items.any { it.id == sessionId })
            route.connect().use { connection ->
                val mux = connection.openMux()
                try {
                    mux.open(DSH_EVENTS_ENDPOINT, jsonObject()).use {
                        assertTrue((connection.native("session/list", DshMethods.SessionList.input.encode(DshSessionListInput(DshSessionListInputRequest())) as JsonObject) as JsonObject).array("items").isNotEmpty())
                    }
                } finally { mux.close() }
            }
            val files = DshFilesClient(route, staging)
            val image = fixture.obj("image")
            val bytes = Base64.getDecoder().decode(image.string("data"))
            val uploaded = files.upload(sessionId, image.string("name"), bytes.size.toLong(), { bytes.inputStream() })
            assertEquals("sha256:${image.string("sha256")}", uploaded.attachmentId)
            files.download(image.string("uploadedPath")).use { assertArrayEquals(bytes, it.file.readBytes()) }
            val large = fixture.obj("rtc").obj("largeFile")
            assertEquals(400003L, large.integer("bytes"))
            files.download(large.string("path")).use { staged ->
                assertEquals(large.string("sha256"), staged.sha256)
                assertArrayEquals(ByteArray(400003) { ((it * 37 + 11) and 255).toByte() }, staged.file.readBytes())
            }
            val reader = DshSessionReader(route, sessionId, scope)
            val interactions = InteractionCoordinator(scope)
            val terminal = TerminalWatcher(route, sessionId, scope)
            try {
                withTimeout(20_000) { reader.state.first { it.phase == SessionReadPhase.READY } }
                interactions.start(route, sessionId)
                withTimeout(20_000) { interactions.state.first { it.phase == InteractionWatcher.Phase.ONLINE } }
                terminal.open()
                withTimeout(20_000) { terminal.state.first { it.writable } }
                reader.prompt("REMOTE_APPROVAL_ALLOW", UUID.randomUUID().toString())
                val pending = withTimeout(20_000) { interactions.state.first { state -> state.interactions.any { it is PendingInteraction.Approval } } }
                    .interactions.filterIsInstance<PendingInteraction.Approval>().single()
                assertEquals(SessionReadPhase.READY, reader.state.value.phase)
                assertEquals(InteractionWatcher.Phase.ONLINE, interactions.state.value.phase)
                assertTrue(terminal.state.value.writable)
                files.download(large.string("path")).use { assertEquals(large.string("sha256"), it.sha256) }
                // This fixture exercises the native carrier after verification, not the device prompt.
                interactions.approve(pending.eventId, sessionId, verify = { assertTrue(terminal.state.value.writable) })
                withTimeout(20_000) { reader.state.first { !it.running } }
                files.download(fixture.obj("artifacts").string("workspace") + "/approval-allow.txt").use {
                    assertEquals("authorized", it.file.readText(Charsets.UTF_8))
                }
                terminal.closeTerminal()
            } finally { terminal.close(); interactions.close(); reader.close() }
            routes.setConsent(descriptor.runtimeId, false)
            assertTrue(routes.selectedCandidates(descriptor.runtimeId).isEmpty())
            assertNotNull(runCatching { route.probeIdentity() }.exceptionOrNull())
            routes.setConsent(descriptor.runtimeId, true)
            val refreshed = routes.transport(descriptor.runtimeId, path).also { replacement = it }
            withTimeout(40_000) { refreshed.probeIdentity() }
            assertNotNull(runCatching { route.probeIdentity() }.exceptionOrNull())
            active = false
            routes.close(); pairing.close()
            assertNotNull(runCatching { refreshed.native("session/list", jsonObject()) }.exceptionOrNull())
        } finally {
            replacement?.close(); transport?.close(); routes.close(); pairing.close(); scope.cancel()
            withContext(NonCancellable + Dispatchers.IO) { store.clear() }
            staging.deleteRecursively(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
        }
    }
}
