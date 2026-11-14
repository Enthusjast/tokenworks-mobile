package cn.edu.ustc.tokenworks

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Installed native Gateway + JNI/Node Noise; authority facts are explicitly test-only, not PG/Go. */
class NativeAuthorityIntegrationTest {
    @Test fun boundLanPreservesRestrictedAuthorityAndFencesRevocation() = runBlocking {
        val process = ProcessBuilder(System.getProperty("noise.node", "node"), "--import", "tsx", "tools/dev/native-authority-test-host.ts")
            .directory(File(System.getProperty("noise.repository"))).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val reader = Executors.newSingleThreadExecutor()
        val stdout = process.inputStream.bufferedReader()
        var client: OkHttpClient? = null
        try {
            val fixtureFile = reader.submit<File> {
                while (true) {
                    val line = stdout.readLine() ?: error("Native authority fixture exited before readiness")
                    if (line.startsWith("NATIVE_AUTHORITY_FIXTURE=")) return@submit File(line.substringAfter('='))
                }
                @Suppress("UNREACHABLE_CODE") error("fixture unavailable")
            }.get(90, TimeUnit.SECONDS)
            val fixture = JsonDocument.parse(fixtureFile.readBytes()).root
            val certificate = CertificateFactory.getInstance("X.509").generateCertificate(fixture.string("operatorCaPem").byteInputStream()) as X509Certificate
            val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate).build()
            val http = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build().also { client = it }
            val key = ECKeyGenerator(Curve.P_256).generate()
            val signer = object : DeviceProofSigner {
                override val publicKey = PublicProofKey(JsonDocument.parse(key.toPublicJWK().toJSONString()).root)
                override fun signJose(signingInput: ByteArray) = ECDSASigner(key).sign(JWSHeader(JWSAlgorithm.ES256), signingInput).decode()
            }
            val ownerData = fixture.obj("owner")
            val owner = LocalDataOwner(ownerData.string("account_id"), ownerData.string("organization_id"), ownerData.string("deployment_sha256"), ownerData.string("user_id"), ownerData.string("device_id"))
            val descriptor = LanPairingDescriptor.parse(JsonDocument.of(fixture.obj("pairing")).canonicalText())
            val credentials = LanConnectionFactory.withOperatorClient(http).pair(signer,
                LanPairingIntent.create(descriptor, "JNI account-bound authority proof", owner, signer, fixture.obj("relay")))
            val session = fixture.string("sessionId")
            val forbidden = fixture.string("forbiddenSessionId")
            fun lease(name: String): DshLease = fixture.obj("leases").obj(name).let {
                DshLease(it.string("token"), it.string("id"), it.integer("expiresAt", 1, MAX_SAFE_INTEGER))
            }
            fun route(name: String) = EncryptedRoute(credentials.runtimeId, DshTransportPath.LAN,
                NativeHttpClient(LanCarrierDialer(credentials, signer, http, lease(name))))
            fun select(target: String) = jsonObject("request" to jsonObject("sessionId" to target.json(), "provider" to "dsh-m0-test".json(), "model" to "offline-alternative".json()))
            suspend fun denied(route: DshTransport, endpoint: String, args: JsonObject) {
                val failure = runCatching { route.native(endpoint, args) }.exceptionOrNull()
                assertTrue("Expected real native denial, got $failure", failure is DshFailure)
                assertEquals("remote-access/denied", (failure as DshFailure).code)
            }
            for (name in listOf("wrongUser", "wrongDevice", "wrongRuntime", "wrongInstallation", "wrongOrganization", "expired", "revoked")) {
                route(name).use { candidate ->
                    assertNotNull("$name authority must fail before native READY", runCatching { withTimeout(15_000) { candidate.probe() } }.exceptionOrNull())
                }
            }
            route("operator").use { operator ->
                operator.probe()
                val mux = operator.openMux()
                try {
                    val follow = mux.open("session/follow", jsonObject("args" to jsonObject("request" to jsonObject("address" to jsonObject("kind" to "session".json(), "sessionId" to session.json())))))
                    try {
                        val first = withTimeout(10_000) { follow.next() }
                        assertTrue("Authorized native follow must yield an item: $first", first is DshMuxFrame.Item)
                        assertTrue((operator.native("terminal/list", jsonObject("sessionId" to session.json())) as JsonArray).values.isEmpty())
                    } finally { follow.close() }
                } finally { mux.close() }
                denied(operator, "terminal/list", jsonObject("sessionId" to forbidden.json()))
            }
            route("owner").use { ownerRoute ->
                ownerRoute.probe()
                assertTrue(DshSessionClient(ownerRoute).sessions().items.any { it.id == session })
            }
            route("operator").use { operator ->
                operator.probe()
                operator.native("session/selectModel", select(session))
                denied(operator, "session/selectModel", select(forbidden))
            }
            route("viewer").use { viewer ->
                viewer.probe()
                denied(viewer, "session/selectModel", select(session))
            }
            suspend fun fenced(name: String, command: String, acknowledgement: String) {
                route(name).use { selected ->
                    selected.probe()
                    val mux = selected.openMux()
                    try {
                        val follow = mux.open("session/follow", jsonObject("args" to jsonObject("request" to jsonObject("address" to jsonObject("kind" to "session".json(), "sessionId" to session.json())))))
                        try {
                            val first = withTimeout(10_000) { follow.next() }
                            assertTrue("Authorized native follow must yield an item: $first", first is DshMuxFrame.Item)
                            process.outputStream.bufferedWriter().apply { write("$command\n"); flush() }
                            val acknowledged = reader.submit<Boolean> {
                                while (true) {
                                    val line = stdout.readLine() ?: return@submit false
                                    if (line == acknowledgement) return@submit true
                                }
                                @Suppress("UNREACHABLE_CODE") false
                            }.get(10, TimeUnit.SECONDS)
                            assertTrue(acknowledged)
                            withTimeout(10_000) { while (!mux.closed()) delay(10) }
                            assertTrue("Changed authority closes its existing native mux", mux.closed())
                            assertNotNull(runCatching { selected.native("session/selectModel", select(session)) }.exceptionOrNull())
                        } finally { follow.close() }
                    } finally { mux.close() }
                }
            }
            fenced("changed", "change-grant", "NATIVE_AUTHORITY_CHANGED")
            fenced("viewer", "revoke-viewer", "NATIVE_AUTHORITY_REVOKED")
            // Bound credentials remain independently usable for local revoke/recovery, never native.
            LanConnectionFactory.withOperatorClient(http).transport(credentials, signer).use { it.revoke() }
        } finally {
            client?.connectionPool?.evictAll(); client?.dispatcher?.executorService?.shutdown()
            runCatching { process.outputStream.bufferedWriter().apply { write("stop\n"); flush() } }
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
            reader.shutdownNow()
        }
    }
}
