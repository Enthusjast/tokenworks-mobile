package cn.edu.ustc.tokenworks

import androidx.test.platform.app.InstrumentationRegistry
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.RSAKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID

/** Opt-in production Android -> authenticated Go opaque Relay -> real installed NativeGateway.
 * The one-use private fixture contains only freshly generated test keys, never a user account.
 * Its Go repository supplies explicit isolated authorization facts, not PostgreSQL persistence.
 */
class GoRelayNativeScenarioTest {
    @Test fun actualRelayHttpMuxAndMultiRecordBinary() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("goRelayFixture") == "go-relay-fixture.json")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.filesDir, "go-relay-fixture.json")
        check(input.isFile && input.length() in 1..65536)
        val fixture = JsonDocument.parse(input.readBytes()).root
        check(input.delete())
        val config = LoginConfiguration.parse(JsonDocument.of(fixture.obj("configuration")).canonicalBytes())
        val proofKey = ECKey.parse(JsonDocument.of(fixture.obj("phonePrivateJwk")).canonicalText())
        val signer = object : DeviceProofSigner {
            override val publicKey = PublicProofKey(JsonDocument.parse(proofKey.toPublicJWK().toJSONString()).root)
            override fun signJose(signingInput: ByteArray) = ECDSASigner(proofKey).sign(JWSHeader(JWSAlgorithm.ES256), signingInput).decode()
        }
        val discovery = OidcDiscovery.parse(JsonDocument.of(jsonObject(
            "issuer" to config.issuer.json(), "authorization_endpoint" to "${config.issuer}/authorize".json(),
            "token_endpoint" to "${config.issuer}/token".json(), "jwks_uri" to "${config.issuer}/jwks".json(),
            "response_types_supported" to JsonArray(listOf("code".json())),
            "id_token_signing_alg_values_supported" to JsonArray(listOf("RS256".json())))).canonicalBytes(), config)
        val now = System.currentTimeMillis()
        val login = PendingLogin(discovery, signer.publicKey.thumbprint, now)
        val issuerKey = RSAKey.parse(JsonDocument.of(fixture.obj("issuerPrivateJwk")).canonicalText())
        val idToken = JWSObject(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("isolated").type(JOSEObjectType.JWT).build(),
            Payload(JsonDocument.of(jsonObject("iss" to config.issuer.json(), "sub" to "isolated-user".json(),
                "aud" to config.clientId.json(), "iat" to (now / 1000).json(), "exp" to (now / 1000 + 180).json(),
                "nonce" to login.nonce.json())).canonicalText())).apply { sign(RSASSASigner(issuerKey)) }.serialize()
        val tokens = jsonObject("token_type" to "DPoP".json(), "expires_in" to 180L.json(),
            "access_token" to fixture.string("accessToken").json(), "id_token" to idToken.json())
        val session = TokenVerifier.authorizationCodeResponse(JsonDocument.of(tokens).canonicalBytes(),
            login.acceptCallback("${config.redirectUri}?state=${login.state}&code=isolated-code", now),
            IssuerKeys.parse(JsonDocument.of(fixture.obj("jwks")).canonicalBytes()), now)
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(fixture.string("operatorCaPem").byteInputStream()) as X509Certificate
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate).build()
        val operatorClient = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        val http = LoginHttp.create(operatorClient)
        val api = http.resource(config, session, signer)
        val descriptor = LanPairingDescriptor.parse(JsonDocument.of(fixture.obj("pairing")).canonicalText())
        val owner = LocalDataOwner(fixture.string("accountId"), config.organizationId, config.fingerprint,
            fixture.string("userId"), fixture.string("deviceId"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val directory = File(context.cacheDir, "go-relay-${UUID.randomUUID()}").apply { mkdirs() }
        val factory = LanConnectionFactory.withOperatorClient(operatorClient)
        val intent = LanPairingIntent.create(descriptor, "Actual API26 Go Relay proof", owner, signer,
            jsonObject("issuer" to config.remoteOrigin.json(), "organizationId" to owner.organizationId.json(),
                "deploymentSha256" to owner.deploymentHash.json(), "userId" to owner.userId.json(), "deviceId" to owner.deviceId.json()))
        val credentials = factory.pair(signer, intent)
        val transport = factory.relayTransport(api, credentials)
        try {
            transport.probeIdentity()
            assertEquals(DshTransportPath.RELAY, transport.state.value.path)
            assertEquals(DshTransportPhase.READY, transport.state.value.phase)
            val sessions = DshSessionClient(transport).sessions()
            assertTrue(sessions.items.any { it.id == fixture.string("sessionId") })
            val reader = DshSessionReader(transport, fixture.string("sessionId"), scope)
            try {
                withTimeout(20000) { reader.state.first { it.phase == SessionReadPhase.READY } }
                // Keep native mux open while an independent real HTTP/file carrier is active.
                DshFilesClient(transport, directory).download(fixture.string("filePath")).use { downloaded ->
                    assertEquals(fixture.integer("fileBytes", 1, 2_000_000), downloaded.file.length())
                    val digest = MessageDigest.getInstance("SHA-256").digest(downloaded.file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
                    assertEquals(fixture.string("fileSha256"), digest)
                }
                assertEquals(SessionReadPhase.READY, reader.state.value.phase)
            } finally { reader.close() }
        } finally {
            transport.close(); scope.cancel(); directory.deleteRecursively()
            operatorClient.connectionPool.evictAll(); operatorClient.dispatcher.executorService.shutdown()
        }
    }
}
