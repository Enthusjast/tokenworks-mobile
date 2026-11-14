package cn.edu.ustc.tokenworks

import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Native HTTP regression scripts run behind a real TLS+JNI Noise splice, never a public /api client. */
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
    private lateinit var credentials: LanCredentials
    private lateinit var native: DshTransport
    private lateinit var operatorClient: okhttp3.OkHttpClient
    private val replies = java.util.concurrent.LinkedBlockingQueue<MockResponse>()
    private var scripted: okhttp3.mockwebserver.Dispatcher? = null
    private fun enqueue(response: MockResponse) { replies.put(response) }
    private val auths = java.util.concurrent.LinkedBlockingQueue<JsonObject>()

    @Before fun start() {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost").build()
        val certificates = HandshakeCertificates.Builder().heldCertificate(certificate)
            .addTrustedCertificate(certificate.certificate).build()
        val client = okhttp3.OkHttpClient.Builder().sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager).build()
        operatorClient = client
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
        http = LoginHttp.create(client)
        api = http.resource(config, session, signer)
        val hostKeys = E2eSession.generateStatic()
        val installation = "55555555-5555-4555-8555-555555555555"
        val owner = LocalDataOwner(config.organizationId, config.organizationId, config.fingerprint, runtimeId, "66666666-6666-4666-8666-666666666666")
        val descriptor = LanPairingDescriptor.parse(JsonDocument.of(jsonObject("version" to 2L.json(), "origin" to server.url("/").toString().removeSuffix("/").json(),
            "runtimeId" to runtimeId.json(), "installationId" to installation.json(), "tlsPin" to okhttp3.CertificatePinner.pin(certificate.certificate).json(),
            "code" to "12345678".json(), "expiresAt" to (System.currentTimeMillis() + 60000).json(), "carrierVersion" to 1L.json(),
            "suite" to E2eSession.SUITE.json(), "hostStaticPublicKey" to Contract.base64url(hostKeys.publicKey).json(), "hostKeyEpoch" to 1L.json(), "minimumEncrypted" to JsonBoolean(true))).canonicalText())
        credentials = LanPairingIntent.create(descriptor, "JVM", owner, signer, jsonObject("issuer" to config.remoteOrigin.json(), "organizationId" to owner.organizationId.json(),
            "deploymentSha256" to owner.deploymentHash.json(), "userId" to owner.userId.json(), "deviceId" to owner.deviceId.json())).credentials
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                if (request.path in setOf("/v2/runtimes/$runtimeId/carrier", "/carrier")) return MockResponse().withWebSocketUpgrade(NoiseTlsTestBridge(hostKeys, { credentials }, {
                    (operatorClient.sslSocketFactory.createSocket("localhost", server.port) as javax.net.ssl.SSLSocket).also { socket ->
                        socket.sslParameters = socket.sslParameters.also { it.endpointIdentificationAlgorithm = "HTTPS" }; socket.startHandshake()
                    }
                }, routeKind = if (request.path == "/carrier") "lan" else "relay",
                    purpose = request.getHeader("X-TokenWorks-Carrier-Purpose") ?: "native", onAuth = { auths.put(it) }))
                return scripted?.dispatch(request) ?: replies.take()
            }
        }
        val lease = DshLease("A".repeat(43), installation, System.currentTimeMillis() + 300_000)
        native = EncryptedRoute(runtimeId, DshTransportPath.RELAY, NativeHttpClient(CarrierDialer { purpose -> api.openCarrier(runtimeId, lease, credentials, purpose) }))
    }

    @After fun stop() {
        native.close()
        operatorClient.connectionPool.evictAll(); operatorClient.dispatcher.executorService.shutdown()
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

    @Test fun nativeLeaseParsesAServerIssuedSecret() = runBlocking {
        val token = "A".repeat(43)
        enqueue(MockResponse().setBody(JsonDocument.of(leaseBody(token, "55555555-5555-4555-8555-555555555555")).canonicalText())
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

    @Test fun nativeLeaseRejectsMalformedLeaseResponses() = runBlocking {
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
            enqueue(MockResponse().setBody(JsonDocument.of(body).canonicalText()).addHeader("Content-Type: application/json"))
            try { kotlinx.coroutines.runBlocking { api.nativeLease(runtimeId) }; fail("malformed lease accepted") }
            catch (expected: Exception) { assertTrue("unexpected: $expected", expected is RemoteRequestException || expected is ProtocolException) }
        }
        assertEquals(bad.size, server.requestCount)
    }

    @Test fun nativeCallReturnsServerValueAndVerifiesEnvelope() = runBlocking {
        scripted = echoDispatcher(listOf(valueReply(jsonObject("items" to JsonArray(emptyList())))))
        val issued = api.nativeLease(runtimeId)
        val value = native.native("session/list", jsonObject())
        assertTrue(value is JsonObject)
        // Recorded order: lease POST then native POST.
        val leaseRequest = server.takeRequest()
        assertEquals("/v2/runtimes/$runtimeId/leases", leaseRequest.path)
        // Find the native POST among recorded requests (the lease POST may retry for a nonce).
        var nativeRequest: okhttp3.mockwebserver.RecordedRequest? = null
        repeat(4) {
            val recorded = server.takeRequest(1000, java.util.concurrent.TimeUnit.MILLISECONDS) ?: return@repeat
            if (recorded.path == "/api/session/list" && nativeRequest == null) nativeRequest = recorded
        }
        nativeRequest!!
        assertNull(nativeRequest!!.getHeader("X-TokenWorks-Lease"))
        assertNull(nativeRequest!!.getHeader("X-TokenWorks-Runtime"))
        val authLease = auths.take().obj("relayLease")
        assertEquals(issued.id, authLease.string("leaseId"))
        assertEquals(issued.token, authLease.string("token"))
    }
    private fun leaseReply(token: String = "A".repeat(43), id: String = "55555555-5555-4555-8555-555555555555"): (JsonObject) -> String = { _ ->
        JsonDocument.of(leaseBody(token, id)).canonicalText()
    }

    @Test fun nativeSurveysServerErrorsAsDshFailureCodes() = runBlocking {
        val lease = DshLease("A".repeat(43), "55555555-5555-4555-8555-555555555555", System.currentTimeMillis() + 300_000)
        scripted = echoDispatcher(listOf(errorReply("lease.expired")))
        try { kotlinx.coroutines.runBlocking { native.native("session/list", jsonObject()) }; fail("server error accepted") }
        catch (expected: DshFailure) { assertEquals("lease.expired", expected.code) }
    }

    @Test fun nativeRejectsMismatchedOrAlienEnvelopes() = runBlocking {
        val lease = DshLease("A".repeat(43), "55555555-5555-4555-8555-555555555555", System.currentTimeMillis() + 300_000)
        val envelopes = listOf(
            jsonObject("type" to "server-response".json(), "rpcId" to "other".json(), "result" to jsonObject("ok" to JsonBoolean(true))),
            jsonObject("type" to "server-response".json(), "result" to jsonObject("ok" to JsonBoolean(true))),
            jsonObject("type" to "event".json(), "rpcId" to "ignored".json(), "result" to jsonObject("ok" to JsonBoolean(true))),
            jsonObject("type" to "server-response".json(), "rpcId" to "ignored".json(), "result" to jsonObject("ok" to JsonBoolean(true), "extra" to 1L.json())),
            jsonObject("type" to "server-response".json(), "rpcId" to "ignored".json(), "result" to jsonObject("ok" to "yes".json())),
        )
        for (envelope in envelopes) {
            enqueue(MockResponse().setBody(JsonDocument.of(envelope).canonicalText()).addHeader("Content-Type: application/json"))
            assertThrows(DshFailure::class.java) { kotlinx.coroutines.runBlocking { native.native("session/list", jsonObject()) } }
        }
        assertEquals(envelopes.size * 2, server.requestCount)
    }

    @Test fun nativeRejectsInvalidEndpointShapesBeforeSending() = runBlocking {
        val lease = DshLease("A".repeat(43), "55555555-5555-4555-8555-555555555555", System.currentTimeMillis() + 300_000)
        for (endpoint in listOf("session", "/session/list", "session/", "session/../admin", "has space/list")) {
            assertThrows(DshFailure::class.java) { kotlinx.coroutines.runBlocking { native.native(endpoint, jsonObject()) } }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun leaseFailureEndsTheRouteWithoutImplicitCommandReplay() = runBlocking {
        val transport = DshRelayTransport(api, credentials)
        val client = DshSessionClient(transport)
        try {
            scripted = echoDispatcher(listOf(
                valueReply(jsonObject("items" to JsonArray(emptyList()))),
                errorReply("lease.expired")))
            client.sessions()
            val before = server.requestCount
            assertThrows(DshFailure::class.java) { kotlinx.coroutines.runBlocking { client.sessions() } }
            assertEquals(DshTransportPhase.AUTH_REQUIRED, transport.state.value.phase)
            assertEquals(before + 1, server.requestCount)
        } finally { transport.close() }
    }

    @Test fun boundLanReusesOneActualLeaseAcrossItsNativePool() = runBlocking {
        scripted = echoDispatcher(List(2) { valueReply(jsonObject("items" to JsonArray(emptyList()))) })
        LanConnectionFactory.withOperatorClient(operatorClient).transport(credentials, signer, api).use { route ->
            repeat(2) { assertTrue(route.native("session/list", jsonObject()) is JsonObject) }
            val auth = auths.take()
            assertEquals("A".repeat(43), auth.obj("relayLease").string("token"))
            assertEquals("55555555-5555-4555-8555-555555555555", auth.obj("relayLease").string("leaseId"))
            assertTrue(auths.isEmpty())
            val paths = List(server.requestCount) { server.takeRequest().path }
            assertEquals(1, paths.count { it == "/v2/runtimes/$runtimeId/leases" })
            assertEquals(1, paths.count { it == "/carrier" })
            assertEquals(2, paths.count { it == "/api/session/list" })
        }
    }

    @Test fun boundLanAndRtcWithoutAuthorityFailBeforeOpeningAnyCarrier() = runBlocking {
        val factory = LanConnectionFactory.withOperatorClient(operatorClient)
        val outer = CarrierOuterDialer { _, _ -> error("bound RTC cannot dial without authority") }
        val routes = listOf(factory.transport(credentials, signer),
            factory.encryptedTransport(credentials, signer, "p2p", DshTransportPath.P2P, outer, null))
        routes.forEach { route ->
            route.use {
                val error = runCatching { route.probeIdentity() }.exceptionOrNull()
                assertTrue(error is DshFailure)
                assertEquals("e2e.relay_authority_required", (error as DshFailure).code)
            }
        }
        assertEquals("e2e.relay_authority_required", assertThrows(DshFailure::class.java) {
            factory.pairingControl(credentials, signer, null)
        }.code)
        assertEquals(0, server.requestCount)
    }

    @Test fun transportLossStaysAmbiguousAndNeverBecomesProtocolFailure() = runBlocking {
        val lease = DshLease("A".repeat(43), "55555555-5555-4555-8555-555555555555", System.currentTimeMillis() + 300_000)
        enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val error = assertThrows(RemoteRequestException::class.java) { kotlinx.coroutines.runBlocking { native.native("session/list", jsonObject()) } }
        assertEquals("network.unavailable", error.code)
    }
}
