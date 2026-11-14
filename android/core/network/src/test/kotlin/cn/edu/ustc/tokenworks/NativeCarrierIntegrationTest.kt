package cn.edu.ustc.tokenworks

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real installed dsh NativeGateway + protected Host pairing + production Node/JNI Noise, no native API mock. */
class NativeCarrierIntegrationTest {
    @Test fun realNativeHttpMuxBinaryPtyAndDeviceKeyRotation() = runBlocking {
        val repository = File(System.getProperty("noise.repository"))
        val process = ProcessBuilder(System.getProperty("noise.node", "node"), "--import", "tsx", "tools/dev/native-test-host.ts")
            .directory(repository).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val reader = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var transport: DshLanTransport? = null
        var client: OkHttpClient? = null
        var staging: File? = null
        var stage = "fixture-start"
        try {
            val fixtureFile = reader.submit<File> {
                val stdout = process.inputStream.bufferedReader()
                while (true) {
                    val line = stdout.readLine() ?: throw IllegalStateException("Native fixture exited before private descriptor")
                    if (line.startsWith("NATIVE_TEST_FIXTURE=")) return@submit File(line.substringAfter('='))
                }
                @Suppress("UNREACHABLE_CODE") error("fixture unavailable")
            }.get(90, TimeUnit.SECONDS)
            val fixture = JsonDocument.parse(fixtureFile.readBytes()).root
            assertEquals(DshMethods.VERSION, fixture.string("dshVersion"))
            val descriptor = LanPairingDescriptor.parse(JsonDocument.of(fixture.obj("pairing")).canonicalText())
            val ca = CertificateFactory.getInstance("X.509").generateCertificate(fixture.string("operatorCaPem").byteInputStream()) as X509Certificate
            ca.checkValidity(); assertTrue(ca.basicConstraints >= 0)
            val trust = HandshakeCertificates.Builder().addTrustedCertificate(ca).build()
            client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
            val factory = LanConnectionFactory.withOperatorClient(client)
            val proofKey = ECKeyGenerator(Curve.P_256).generate()
            val signer = object : DeviceProofSigner {
                override val publicKey = PublicProofKey(JsonDocument.parse(proofKey.toPublicJWK().toJSONString()).root)
                override fun signJose(signingInput: ByteArray) = ECDSASigner(proofKey).sign(JWSHeader(JWSAlgorithm.ES256), signingInput).decode()
            }
            val ownerDocument = fixture.obj("owner")
            val owner = LocalDataOwner(ownerDocument.string("account_id"), ownerDocument.string("organization_id"), ownerDocument.string("deployment_sha256"),
                ownerDocument.string("user_id"), ownerDocument.string("device_id"))
            val intent = LanPairingIntent.create(descriptor, "JVM encrypted native proof", owner, signer)
            // Serialization exercises the complete private precommit format, not a different token/key allocation.
            val recovered = LanPairingIntent.parse(JsonDocument.parse(JsonDocument.of(intent.document()).canonicalBytes()).root)
            assertEquals(intent.credentials.bindingSha256, recovered.credentials.bindingSha256)
            stage = "signed-pair-and-read-reconcile"
            val credentials = factory.pair(signer, intent)
            factory.reconcile(signer, recovered.credentials)
            assertNotEquals(owner.deviceId, credentials.pairId)
            assertNull(credentials.manifest.fields["relay"])
            stage = "noise-ready"
            val route = factory.transport(credentials, signer).also { transport = it }
            route.probeIdentity()
            assertEquals(DshTransportPhase.READY, route.state.value.phase)
            val sessionId = fixture.string("sessionId")
            val listArgs = DshMethods.SessionList.input.encode(DshSessionListInput(DshSessionListInputRequest())) as JsonObject
            stage = "native-unary"
            assertTrue(DshSessionClient(route).sessions().items.any { it.id == sessionId })
            stage = "independent-native-mux-and-unary"
            route.connect().use { connection ->
                val mux = connection.openMux()
                try {
                    val events = mux.open(DSH_EVENTS_ENDPOINT, jsonObject())
                    val sessions = connection.native("session/list", listArgs) as JsonObject
                    assertTrue(sessions.array("items").isNotEmpty())
                    events.close()
                } finally { mux.close() }
            }
            staging = java.nio.file.Files.createTempDirectory("noise-native-jvm-").toFile()
            val files = DshFilesClient(route, staging)
            val image = fixture.obj("image")
            val imageBytes = Base64.getDecoder().decode(image.string("data"))
            stage = "native-binary-upload"
            val uploaded = files.upload(sessionId, image.string("name"), imageBytes.size.toLong(), { imageBytes.inputStream() })
            assertEquals("sha256:${image.string("sha256")}", uploaded.attachmentId)
            stage = "native-binary-image-download"
            files.download(image.string("uploadedPath")).use { file ->
                assertArrayEquals(imageBytes, file.file.readBytes()); assertEquals(image.string("sha256"), file.sha256)
            }
            val large = ByteArray(400_003).also { java.security.SecureRandom().nextBytes(it) }
            val largeFile = File(fixture.obj("artifacts").string("workspace"), "encrypted-${UUID.randomUUID()}.bin")
            largeFile.writeBytes(large)
            stage = "native-multi-record-binary-download"
            files.download(largeFile.absolutePath).use { file -> assertArrayEquals(large, file.file.readBytes()) }
            stage = "native-pty"
            TerminalWatcher(route, sessionId, scope).use { terminal ->
                terminal.open()
                withTimeout(20_000) { terminal.state.first { it.writable } }
                val canary = "noise-pty-${UUID.randomUUID()}-原样🙂"
                assertTrue(terminal.send("printf '%s\\n' '$canary'\n"))
                withTimeout(20_000) { terminal.state.first { state -> state.screen.lineSequence().any { it.trim() == canary } } }
                terminal.closeTerminal()
            }
            val rotation = NoiseDeviceRotationIntent.create(credentials)
            val persistedRotation = NoiseDeviceRotationIntent.parse(JsonDocument.parse(JsonDocument.of(rotation.document()).canonicalBytes()).root)
            stage = "old-authenticated-device-rotation"
            factory.rotate(signer, rotation)
            stage = "new-key-read-reconcile"
            factory.reconcileRotation(signer, persistedRotation)
            val oldFailure = runCatching { route.native("session/list", listArgs) }.exceptionOrNull()
            assertNotNull("retired key cannot reuse its old native pool", oldFailure)
            stage = "new-key-native-unary-and-revoke"
            factory.transport(rotation.replacement, signer).use { replacement ->
                replacement.probeIdentity()
                assertTrue(DshSessionClient(replacement).sessions().items.any { it.id == sessionId })
                replacement.revoke()
                assertEquals(DshTransportPhase.CLOSED, replacement.state.value.phase)
            }
            stage = "revoked-key-refusal"
            factory.transport(rotation.replacement, signer).use { revoked ->
                assertNotNull(runCatching { revoked.probeIdentity() }.exceptionOrNull())
                assertTrue(revoked.state.value.phase in setOf(DshTransportPhase.AUTH_REQUIRED, DshTransportPhase.REVOKED))
            }
        } catch (error: Throwable) {
            throw AssertionError("Real native proof failed at $stage", error)
        } finally {
            transport?.close(); scope.cancel(); staging?.deleteRecursively()
            client?.connectionPool?.evictAll(); client?.dispatcher?.executorService?.shutdown()
            runCatching { process.outputStream.bufferedWriter().apply { write("stop\n"); flush() } }
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
            reader.shutdownNow()
        }
    }
}
