package cn.edu.ustc.tokenworks

import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Drives the real HTTP path: RemoteApi.native / nativeLease against a TLS mock Relay. */
class NativeTransportTest {
    private val now = System.currentTimeMillis()
    private val runtimeId = "44444444-4444-4444-8444-444444444444"
    private val key = ECKeyGenerator(Curve.P_256).generate()
    // The proof key must carry exactly kty/crv/x/y; the issuer JWKS key needs a kid.
    private val issuerKey = ECKeyGenerator(Curve.P_256).keyID("issuer-key").generate()
    private val signer = object : DeviceProofSigner {
        override val publicKey = PublicProofKey(JsonDocument.parse(key.toPublicJWK().toJSONString()).root)
        override fun signJose(signingInput: ByteArray): ByteArray =
            ECDSASigner(key).sign(JWSHeader(JWSAlgorithm.ES256), signingInput).decode()
    }

    private lateinit var server: MockWebServer
    private lateinit var http: LoginHttp
    private lateinit var config: LoginConfiguration
    private lateinit var session: LoginSession
    private lateinit var api: RemoteApi

    @Before fun start() {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost").build()
        val certificates = HandshakeCertificates.Builder().heldCertificate(certificate)
            .addTrustedCertificate(certificate.certificate).build()
        val client = okhttp3.OkHttpClient.Builder().sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager).build()
        server = MockWebServer()
        server.useHttps(certificates.sslSocketFactory(), false)
        server.start()
        config = LoginConfiguration.parse("""{
          "issuer":"https://id.example.edu", "client_id":"mobile", "redirect_uri":"cn.edu.ustc.tokenworks:/oauth2redirect",
          "resource":"urn:tokenworks-mobile", "audience":"urn:tokenworks-mobile", "remote_origin":"https://${server.hostName}:${server.port}",
          "organization_id":"11111111-1111-4111-8111-111111111111", "scopes":["openid","remote.access"],
          "endpoint_origins":["https://id.example.edu"], "signing_algorithms":["ES256"]
        }""".toByteArray())
        // A session that passes resource(): follow RefreshTest's proven claim shape.
        val login = PendingLogin(discovery(config), signer.publicKey.thumbprint, now)
        val at = now / 1000
        fun common(t: Long) = jsonObject("iss" to config.issuer.json(), "sub" to "opaque-subject".json(),
            "iat" to t.json(), "exp" to (t + 300).json())
        val access = jwt(common(at).with(
            "aud" to config.audience.json(), "client_id" to config.clientId.json(),
            "jti" to "fresh-access-token".json(), "scope" to "remote.access".json(),
            "cnf" to jsonObject("jkt" to login.proofKeyThumbprint.json())), "at+jwt")
        val idToken = jwt(common(at).with(
            "aud" to JsonArray(listOf(config.clientId.json(), "trusted-extra-audience".json())),
            "azp" to config.clientId.json(), "auth_time" to JsonNumber((at - 10).toDouble()), "nonce" to login.nonce.json()), "JWT")
        val response = jsonObject(
            "token_type" to "DPoP".json(), "expires_in" to 300L.json(),
            "access_token" to access.json(), "id_token" to idToken.json(),
            "refresh_token" to "original-refresh-secret".json(),
        )
        session = TokenVerifier.authorizationCodeResponse(JsonDocument.of(response).canonicalBytes(),
            login.acceptCallback(config.redirectUri + "?state=${login.state}&code=opaque-test-code", now),
            issuerKeys(), now)
        http = LoginHttp.forTests(client)
        api = http.resource(config, session, signer)
    }

    @After fun stop() {
        server.shutdown()
    }

    private fun issuerKeys() = IssuerKeys.parse(JsonDocument.of(jsonObject(
        "keys" to JsonArray(listOf(JsonDocument.parse(issuerKey.toPublicJWK().toJSONString()).root)))).canonicalBytes())
    private fun jwt(claims: JsonObject, type: String) = JWSObject(
        JWSHeader.Builder(JWSAlgorithm.ES256).keyID(issuerKey.keyID).type(JOSEObjectType(type)).build(),
        Payload(JsonDocument.of(claims).canonicalText())).apply { sign(ECDSASigner(issuerKey)) }.serialize()

    private fun discovery(config: LoginConfiguration) = OidcDiscovery.parse("""{
      "issuer":"${config.issuer}", "authorization_endpoint":"${config.issuer}/authorize",
      "token_endpoint":"${config.issuer}/token", "jwks_uri":"${config.issuer}/jwks",
      "response_types_supported":["code"], "id_token_signing_alg_values_supported":["ES256"]
    }""".toByteArray(), config)
    private fun leaseBody(token: String, leaseId: String) = jsonObject(
        "ok" to JsonBoolean(true), "data" to jsonObject(
            "access_token" to token.json(), "token_type" to "Bearer".json(),
            "expires_in" to 300L.json(), "lease_id" to leaseId.json(),
        ),
    )
    /**
     * A dispatcher answering every native POST with the rpcId it carries. `script`
     * yields one outcome per request: a value payload or a dsh error code.
     */
    private fun echoDispatcher(script: List<(JsonObject) -> String>): okhttp3.mockwebserver.Dispatcher {
        val queue = java.util.concurrent.ConcurrentLinkedDeque(script)
        return object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                // RecordedRequest.body is one-shot; clone it so the dispatcher's parse
                // doesn't consume what the test assertion later reads.
                val bytes = request.body.clone().readByteArray()
                val requestRoot = runCatching { json(bytes) }.getOrElse { JsonObject(emptyMap()) }
                // Lease POSTs get a valid lease body; native calls consume the script.
                val body = if (request.path?.startsWith("/api/") == true)
                    queue.poll()?.invoke(requestRoot) ?: "{}"
                else JsonDocument.of(leaseBody("A".repeat(43), "55555555-5555-4555-8555-555555555555")).canonicalText()
                return MockResponse().setBody(body).addHeader("Content-Type: application/json")
            }
        }
    }
    private fun valueReply(value: JsonValue): (JsonObject) -> String = { request ->
        JsonDocument.of(jsonObject("type" to "server-response".json(), "rpcId" to request.fields["rpcId"]!!,
            "result" to jsonObject("ok" to JsonBoolean(true), "value" to value))).canonicalText()
    }
    private fun errorReply(code: String): (JsonObject) -> String = { request ->
        JsonDocument.of(jsonObject("type" to "server-response".json(), "rpcId" to request.fields["rpcId"]!!,
            "result" to jsonObject("ok" to JsonBoolean(false), "error" to jsonObject("code" to code.json())))).canonicalText()
    }
    private fun json(bytes: ByteArray) = JsonDocument.parse(bytes).root

    @Test fun nativeLeaseParsesAServerIssuedSecret() = runTest {
        val token = "A".repeat(43)
        server.enqueue(MockResponse().setBody(JsonDocument.of(leaseBody(token, "55555555-5555-4555-8555-555555555555")).canonicalText())
            .addHeader("Content-Type: application/json").addHeader("DPoP-Nonce: ${"B".repeat(32)}"))
        val lease = api.nativeLease(runtimeId)
        assertEquals(token, lease.token)
        assertEquals("55555555-5555-4555-8555-555555555555", lease.id)
        assertTrue(lease.usable())
        val request = server.takeRequest()
        assertEquals("/v2/runtimes/$runtimeId/leases", request.path)
        assertEquals("POST", request.method)
        assertTrue(request.getHeader("Authorization")!!.startsWith("DPoP "))
        assertNotNull(request.getHeader("DPoP"))
    }

    @Test fun nativeLeaseRejectsMalformedLeaseResponses() = runTest {
        val bad = listOf(
            leaseBody("short", "55555555-5555-4555-8555-555555555555"),
            leaseBody("A".repeat(43), "not-a-uuid"),
            leaseBody("A".repeat(43), "55555555-5555-4555-8555-555555555555").with("data" to
                (leaseBody("A".repeat(43), "55555555-5555-4555-8555-555555555555").obj("data").with("expires_in" to 301L.json()))),
            leaseBody("A".repeat(43), "55555555-5555-4555-8555-555555555555").with("data" to
                (leaseBody("A".repeat(43), "55555555-5555-4555-8555-555555555555").obj("data").with("token_type" to "DPoP".json()))),
            jsonObject("ok" to JsonBoolean(false), "data" to JsonNull),
        )
        for (body in bad) {
            server.enqueue(MockResponse().setBody(JsonDocument.of(body).canonicalText()).addHeader("Content-Type: application/json"))
            try { kotlinx.coroutines.runBlocking { api.nativeLease(runtimeId) }; fail("malformed lease accepted") }
            catch (expected: Exception) { assertTrue("unexpected: $expected", expected is RemoteRequestException || expected is ProtocolException) }
        }
        assertEquals(bad.size, server.requestCount)
    }

    @Test fun nativeCallReturnsServerValueAndVerifiesEnvelope() = runTest {
        server.dispatcher = echoDispatcher(listOf(valueReply(jsonObject("items" to JsonArray(emptyList())))))
        val issued = api.nativeLease(runtimeId)
        val value = api.native("session/list", jsonObject(), runtimeId, issued)
        assertTrue(value is JsonObject)
        // Recorded order: lease POST then native POST.
        val leaseRequest = server.takeRequest()
        assertEquals("/v2/runtimes/$runtimeId/leases", leaseRequest.path)
        // Find the native POST among recorded requests (the lease POST may retry for a nonce).
        var nativeRequest: okhttp3.mockwebserver.RecordedRequest? = null
        repeat(4) {
            val recorded = server.takeRequest(1000, java.util.concurrent.TimeUnit.MILLISECONDS) ?: return@repeat
            println("RECORDED: " + recorded.method + " " + recorded.path + " body=" + recorded.body.readByteArray().toString(Charsets.UTF_8).take(80))
            if (recorded.path == "/api/session/list" && nativeRequest == null) nativeRequest = recorded
        }
        nativeRequest!!
        assertEquals(issued.token, nativeRequest!!.getHeader("X-TokenWorks-Lease"))
        assertEquals(runtimeId, nativeRequest!!.getHeader("X-TokenWorks-Runtime"))
    }
    private fun leaseReply(token: String = "A".repeat(43), id: String = "55555555-5555-4555-8555-555555555555"): (JsonObject) -> String = { _ ->
        JsonDocument.of(leaseBody(token, id)).canonicalText()
    }

    @Test fun nativeSurveysServerErrorsAsDshFailureCodes() = runTest {
        val lease = DshLease("A".repeat(43), "55555555-5555-4555-8555-555555555555", System.currentTimeMillis() + 300_000)
        server.dispatcher = echoDispatcher(listOf(errorReply("lease.expired")))
        try { kotlinx.coroutines.runBlocking { api.native("session/list", jsonObject(), runtimeId, lease) }; fail("server error accepted") }
        catch (expected: DshFailure) { assertEquals("lease.expired", expected.code) }
    }

    @Test fun nativeRejectsMismatchedOrAlienEnvelopes() = runTest {
        val lease = DshLease("A".repeat(43), "55555555-5555-4555-8555-555555555555", System.currentTimeMillis() + 300_000)
        val envelopes = listOf(
            jsonObject("type" to "server-response".json(), "rpcId" to "other".json(), "result" to jsonObject("ok" to JsonBoolean(true))),
            jsonObject("type" to "server-response".json(), "result" to jsonObject("ok" to JsonBoolean(true))),
            jsonObject("type" to "event".json(), "rpcId" to "ignored".json(), "result" to jsonObject("ok" to JsonBoolean(true))),
            jsonObject("type" to "server-response".json(), "rpcId" to "ignored".json(), "result" to jsonObject("ok" to JsonBoolean(true), "extra" to 1L.json())),
            jsonObject("type" to "server-response".json(), "rpcId" to "ignored".json(), "result" to jsonObject("ok" to "yes".json())),
        )
        for (envelope in envelopes) {
            server.enqueue(MockResponse().setBody(JsonDocument.of(envelope).canonicalText()).addHeader("Content-Type: application/json"))
            assertThrows(RemoteRequestException::class.java) { kotlinx.coroutines.runBlocking { api.native("session/list", jsonObject(), runtimeId, lease) } }
        }
        assertEquals(envelopes.size, server.requestCount)
    }

    @Test fun nativeRejectsInvalidEndpointShapesBeforeSending() = runTest {
        val lease = DshLease("A".repeat(43), "55555555-5555-4555-8555-555555555555", System.currentTimeMillis() + 300_000)
        for (endpoint in listOf("session", "/session/list", "session/", "session/../admin", "has space/list")) {
            assertThrows(RemoteRequestException::class.java) { kotlinx.coroutines.runBlocking { api.native(endpoint, jsonObject(), runtimeId, lease) } }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun leaseFailureCodesInvalidateCachedSecretsForRetries() = runTest {
        val client = DshSessionClient(api)
        try {
            server.dispatcher = echoDispatcher(listOf(
                valueReply(jsonObject("items" to JsonArray(emptyList()))),
                errorReply("lease.expired"),
                valueReply(jsonObject("items" to JsonArray(emptyList())))))
            client.sessions(runtimeId)
            // The second call's server error must evict the cached secret...
            assertThrows(DshFailure::class.java) { kotlinx.coroutines.runBlocking { client.sessions(runtimeId) } }
            // ...so the third call mints a fresh lease and succeeds.
            client.sessions(runtimeId)
        } finally { client.close() }
    }

    @Test fun transportLossStaysAmbiguousAndNeverBecomesProtocolFailure() = runTest {
        val lease = DshLease("A".repeat(43), "55555555-5555-4555-8555-555555555555", System.currentTimeMillis() + 300_000)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val error = assertThrows(RemoteRequestException::class.java) { kotlinx.coroutines.runBlocking { api.native("session/list", jsonObject(), runtimeId, lease) } }
        assertEquals("network.unavailable", error.code)
    }
}
