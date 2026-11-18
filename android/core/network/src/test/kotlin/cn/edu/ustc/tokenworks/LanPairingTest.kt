package cn.edu.ustc.tokenworks

import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class LanPairingTest {
    private val runtime = "44444444-4444-4444-8444-444444444444"
    private val installation = "55555555-5555-4555-8555-555555555555"
    private val device = "66666666-6666-4666-8666-666666666666"
    private val token = Contract.base64url(ByteArray(32) { 7 })
    private val code = "12345678"
    private val key = ECKeyGenerator(Curve.P_256).generate()
    private val owner = LocalDataOwner(device, installation, "a".repeat(64), runtime, device)
    private lateinit var hostKeys: E2eStaticKeyPair
    private lateinit var defaultCredentials: LanCredentials
    private var registeredManifest: JsonObject? = null
    private val signatures = AtomicInteger()
    private val signer = object : DeviceProofSigner {
        override val publicKey = PublicProofKey(JsonDocument.parse(key.toPublicJWK().toJSONString()).root)
        override fun signJose(signingInput: ByteArray): ByteArray {
            signatures.incrementAndGet()
            return ECDSASigner(key).sign(com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.ES256), signingInput).decode()
        }
    }
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var certificate: HeldCertificate
    private lateinit var origin: String
    private lateinit var pin: String
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var identityStatus = 200
    private var disconnectApi = false
    private var disconnectProbe = false
    private var identityInstallation: String? = null
    private var pairingInstallation: String? = null
    private var truncatedDownload = false
    private var disconnectPair = false
    private var pairGate: java.util.concurrent.CountDownLatch? = null
    private val serverSockets = CopyOnWriteArrayList<WebSocket>()
    private var carrierNonceChallenge = false
    private val carrierNonce = "outer-admission-nonce"
    private var outerAttack = 0

    @Before fun setup() {
        hostKeys = E2eSession.generateStatic()
        val ca = HeldCertificate.Builder().commonName("operator-ca").certificateAuthority(1).build()
        certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").signedBy(ca).build()
        val serverKeys = HandshakeCertificates.Builder().heldCertificate(certificate, ca.certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(ca.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        pin = CertificatePinner.pin(certificate.certificate)
        server = MockWebServer()
        server.useHttps(serverKeys.sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                return when {
                    request.path == "/pair/tls" -> if (disconnectProbe) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else MockResponse().setResponseCode(204)
                    request.path == "/carrier" -> when {
                        carrierNonceChallenge && (proof(request).fields["nonce"] as? JsonString)?.value != carrierNonce ->
                            MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "DPoP error=\"use_dpop_nonce\"").setHeader("DPoP-Nonce", carrierNonce)
                        outerAttack in 1..2 -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                serverSockets.add(webSocket)
                                if (outerAttack == 1) webSocket.send(okio.ByteString.of(*ByteArray(65538))) else webSocket.send("forbidden outer text")
                            }
                            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, "") }
                        })
                        disconnectProbe -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        identityStatus != 200 -> MockResponse().setResponseCode(identityStatus)
                        else -> MockResponse().withWebSocketUpgrade(NoiseTlsTestBridge(hostKeys, { defaultCredentials }, {
                            (client.sslSocketFactory.createSocket("localhost", server.port) as javax.net.ssl.SSLSocket).also { socket ->
                                socket.sslParameters = socket.sslParameters.also { it.endpointIdentificationAlgorithm = "HTTPS" }; socket.startHandshake()
                            }
                        }, { identityInstallation ?: installation }, purpose = request.getHeader("X-TokenWorks-Carrier-Purpose") ?: "native")).also { response ->
                            if (outerAttack == 3) repeat(20) { index -> response.setHeader("X-Bounded-$index", "x".repeat(1800)) }
                        }
                    }
                    request.path == "/pair" && request.method == "DELETE" -> json(jsonObject("ok" to JsonBoolean(true)))
                    request.path == "/pair" -> {
                        val body = JsonDocument.parse(request.body.clone().readByteArray()).root
                        registeredManifest = body.obj("manifest")
                        val reply = json(defaultCredentials.publicDocument().with("installationId" to (pairingInstallation ?: installation).json(),
                            "deviceId" to body.string("deviceId").json(), "token" to body.string("token").json(),
                            "manifest" to body.obj("manifest"), "bindingSha256" to JsonDocument.of(body.obj("manifest")).sha256().json()))
                        pairGate?.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        if (disconnectPair) reply.setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        else reply
                    }
                    request.path == "/pair/identity" -> if (identityStatus != 200) MockResponse().setResponseCode(identityStatus) else {
                        val manifest = registeredManifest ?: defaultCredentials.manifest
                        json(jsonObject("runtimeId" to runtime.json(), "installationId" to (identityInstallation ?: installation).json(),
                            "manifest" to manifest, "bindingSha256" to JsonDocument.of(manifest).sha256().json()))
                    }
                    request.path == "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { serverSockets.add(webSocket) }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val frame = WireJson.parse(text)
                            webSocket.send(WireJson.encode(jsonObject("type" to "end".json(), "streamId" to frame.string("streamId").json())))
                        }
                    })
                    request.path?.startsWith("/api/session/downloadFileBinary?") == true -> if (truncatedDownload)
                        MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("a")
                            .setHeader("Content-Length", "20").setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
                        else MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("exact binary")
                    request.path?.startsWith("/api/session/uploadFileBinary?") == true -> if (disconnectApi)
                        MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else json(jsonObject("uploaded" to JsonBoolean(true)))
                    request.path?.startsWith("/api/") == true -> {
                        if (disconnectApi) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        else {
                            val body = SessionJson.parse(request.body.readByteArray())
                            json(jsonObject("type" to "server-response".json(), "rpcId" to body.string("rpcId").json(),
                                "result" to jsonObject("ok" to JsonBoolean(true), "value" to jsonObject("native" to JsonBoolean(true)))))
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        origin = server.url("/").toString().removeSuffix("/")
        val allocated = LanPairingIntent.create(descriptor(), "Android", owner, signer).credentials
        val manifest = allocated.manifest.with("pairId" to device.json(), "tokenSha256" to noiseTokenHash(token).json())
        defaultCredentials = LanCredentials.parse(allocated.document().with("deviceId" to device.json(), "token" to token.json(),
            "manifest" to manifest, "bindingSha256" to JsonDocument.of(manifest).sha256().json()))
    }

    @After fun cleanup() { pairGate?.countDown(); serverSockets.forEach { it.close(1000, "fixture complete") }; server.shutdown() }
    private fun json(root: JsonObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(JsonDocument.of(root).canonicalText())
    private fun credentialDocument() = defaultCredentials.document()
    private fun descriptor(pinValue: String = pin, originValue: String = origin) = LanPairingDescriptor.parse(JsonDocument.of(jsonObject(
        "version" to 2L.json(), "origin" to originValue.json(), "runtimeId" to runtime.json(), "installationId" to installation.json(),
        "tlsPin" to pinValue.json(), "code" to code.json(), "expiresAt" to (System.currentTimeMillis() + 60_000).json(),
        "carrierVersion" to 1L.json(), "suite" to E2eSession.SUITE.json(), "hostStaticPublicKey" to Contract.base64url(hostKeys.publicKey).json(),
        "hostKeyEpoch" to 1L.json(), "minimumEncrypted" to JsonBoolean(true))).canonicalText())
    private fun transport() = DshLanTransport(LanCredentials.parse(credentialDocument()), signer, client)
    private fun proof(request: RecordedRequest): JsonObject {
        val jws = JWSObject.parse(request.getHeader("DPoP"))
        assertTrue(jws.verify(ECDSAVerifier(key.toPublicJWK())))
        return JsonDocument.parse(jws.payload.toString()).root
    }
    private suspend fun failure(expected: String, block: suspend () -> Unit) {
        try { block(); fail("expected $expected") } catch (error: Exception) {
            assertEquals(expected, when (error) { is LanPairingException -> error.code; is RemoteRequestException -> error.code; is DshFailure -> error.code; else -> throw error })
        }
    }

    @Test fun operatorCaTlsPreflightPrecedesAnyCodeOrSignedProof() = runBlocking {
        val intent = LanPairingIntent.create(descriptor(), "Android", owner, signer)
        val accepted = LanPairingClient(signer, client).pair(intent)
        assertEquals(intent.credentials.pairId, accepted.pairId)
        assertEquals(intent.credentials.token, accepted.token)
        assertEquals(runtime, accepted.runtimeId)
        assertEquals(listOf("/pair/tls", "/pair"), requests.map { it.path })
        val preflight = requests.first()
        assertNull(preflight.getHeader("Authorization")); assertNull(preflight.getHeader("DPoP"))
        assertEquals(0L, preflight.bodySize)
        val paired = requests.last()
        val body = JsonDocument.parse(paired.body.readByteArray()).root
        body.exactKeys("code", "deviceName", "deviceId", "token", "manifest")
        assertEquals(code, body.string("code")); assertEquals("Android", body.string("deviceName"))
        assertEquals(intent.credentials.pairId, body.string("deviceId")); assertEquals(intent.credentials.token, body.string("token"))
        val claims = proof(paired)
        assertEquals("POST", claims.string("htm")); assertEquals("$origin/pair", claims.string("htu"))
        assertEquals("$code.${intent.credentials.bindingSha256}", claims.string("nonce"))
        assertEquals(Contract.base64url(MessageDigest.getInstance("SHA-256").digest(intent.credentials.token.toByteArray())), claims.string("ath"))
        assertEquals(1, signatures.get())
    }

    @Test fun wrongPinNeverSignsOrSendsPairCode() = runBlocking {
        val wrong = "sha256/" + java.util.Base64.getEncoder().encodeToString(ByteArray(32))
        failure("lan.tls_failed") { LanPairingClient(signer, client).pair(LanPairingIntent.create(descriptor(wrong), "Android", owner, signer)) }
        assertEquals(0, signatures.get())
        assertFalse(requests.any { it.path == "/pair" || it.getHeader("Authorization") != null || it.bodySize > 0 })
    }

    @Test fun pinIsNotAReplacementForNormalCaAndHostnameValidation() = runBlocking {
        failure("lan.tls_failed") { LanPairingClient(signer).pair(LanPairingIntent.create(descriptor(), "Android", owner, signer)) }
        // A caller's permissive hostname override is not honored by LAN admission.
        val permissiveHostname = client.newBuilder().hostnameVerifier { _, _ -> true }.build()
        failure("lan.tls_failed") { LanPairingClient(signer, permissiveHostname).pair(LanPairingIntent.create(descriptor(originValue = origin.replace("localhost", "127.0.0.1")), "Android", owner, signer)) }
        assertEquals(0, signatures.get())
    }

    @Test fun authenticatedIdentityUnaryFilesMuxAndRevokeUseExactNativeRoutesAndFreshDpop() = runBlocking {
        transport().use { lan ->
            val value = lan.native("session/list", jsonObject()) as JsonObject
            assertEquals(JsonBoolean(true), value["native"])
            val sink = ByteArrayOutputStream()
            lan.download("/api/session/downloadFileBinary?sessionId=s&name=a", sink, 100)
            assertEquals("exact binary", sink.toString("UTF-8"))
            lan.upload("/api/session/uploadFileBinary?sessionId=s&name=a", "text/plain", ByteArrayInputStream("file".toByteArray()), 4)
            val mux = lan.openMux()
            val stream = mux.open("session/follow", jsonObject("args" to jsonObject()))
            assertTrue(stream.next() is DshMuxFrame.End)
            stream.close(); mux.close()
            lan.revoke()
        }
        assertTrue(requests.any { it.path == "/api/remote.mux" })
        assertTrue(requests.any { it.path == "/pair" && it.method == "DELETE" })
        val authenticated = requests.filter { it.path == "/carrier" }
        val seen = mutableSetOf<String>()
        for (request in authenticated) {
            assertEquals("DPoP $token", request.getHeader("Authorization"))
            assertEquals(device, request.getHeader("X-Tokenworks-Device-Id"))
            assertNull(request.getHeader("X-TokenWorks-Lease"))
            val claims = proof(request)
            assertEquals(request.method, claims.string("htm"))
            assertEquals(origin + request.path!!.substringBefore('?'), claims.string("htu"))
            assertEquals(Contract.base64url(MessageDigest.getInstance("SHA-256").digest(token.toByteArray())), claims.string("ath"))
            assertTrue(seen.add(claims.string("jti")))
            assertTrue(kotlin.math.abs(claims.integer("iat", 0) * 1000 - System.currentTimeMillis()) < 60_000)
        }
    }

    @Test fun selectorNeverReplaysAnAmbiguousWriteButAllowsNewExplicitRelayCommands() = runBlocking {
        disconnectApi = true
        val relay = RecordingRelay(runtime)
        ConnectionSelector(listOf(transport(), relay), installation).use { selector ->
            failure("network.unavailable") { selector.native("session/prompt", jsonObject()) }
            assertEquals(0, relay.calls.get())
            assertEquals(1, requests.count { it.path == "/api/session/prompt" })
            assertEquals("relay", (selector.native("session/prompt", jsonObject()) as JsonString).value)
            assertEquals(1, relay.calls.get())
            selector.upload("/api/session/uploadFileBinary?sessionId=s&name=a", "text/plain", ByteArrayInputStream(byteArrayOf(1)), 1)
            assertEquals(1, relay.uploads.get())
            assertEquals(1, requests.count { it.path == "/api/session/prompt" })
        }
        val uploadRelay = RecordingRelay(runtime)
        ConnectionSelector(listOf(transport(), uploadRelay), installation).use { selector ->
            failure("network.unavailable") { selector.upload("/api/session/uploadFileBinary?sessionId=s&name=a", "text/plain", ByteArrayInputStream(byteArrayOf(1)), 1) }
            assertEquals(0, uploadRelay.uploads.get())
            selector.upload("/api/session/uploadFileBinary?sessionId=s&name=a", "text/plain", ByteArrayInputStream(byteArrayOf(1)), 1)
            assertEquals(1, uploadRelay.uploads.get())
        }
    }

    @Test fun selectorMayRetryReviewedReadOnceOnSelectedRelay() = runBlocking {
        disconnectApi = true
        val relay = RecordingRelay(runtime)
        ConnectionSelector(listOf(transport(), relay), installation).use { selector ->
            assertEquals("relay", (selector.native("session/list", jsonObject()) as JsonString).value)
            assertEquals(1, relay.calls.get())
            assertEquals(1, requests.count { it.path == "/api/session/list" })
        }
    }

    @Test fun boundConnectionRejectsStaleAnswersWithoutSendingThemToEitherPeer() = runBlocking {
        val relay = RecordingRelay(runtime)
        ConnectionSelector(listOf(transport(), relay), installation).use { selector ->
            val bound = selector.connect()
            val mux = bound.openMux()
            val events = mux.open(DSH_EVENTS_ENDPOINT, jsonObject())
            disconnectApi = true
            failure("network.unavailable") { bound.native("session/prompt", jsonObject()) }
            val before = requests.size
            val result = jsonObject("result" to DshStreams.result(DshEventResult("client", "event", DshEventOutcome.Next)))
            try { bound.native(DSH_EVENTS_RESULT_ENDPOINT, result); fail("stale connection accepted") }
            catch (error: DshFailure) { assertEquals("transport/changed", error.code) }
            assertEquals(before, requests.size)
            assertEquals(0, relay.calls.get())
            assertEquals("relay", (selector.connect().native(DSH_EVENTS_RESULT_ENDPOINT, result) as JsonString).value)
            assertEquals(1, relay.calls.get())
            events.close(); mux.close(); bound.close()
        }
    }

    @Test fun selectorRejectsIdentityAuthAndPinFailuresWithoutSilentFallback() = runBlocking {
        val relay = RecordingRelay(runtime)
        assertThrows(LanPairingException::class.java) { ConnectionSelector(listOf(transport(), relay), "77777777-7777-4777-8777-777777777777") }
        identityStatus = 401
        ConnectionSelector(listOf(transport(), relay), installation).use { selector ->
            failure("auth.proof_invalid") { selector.native("session/list", jsonObject()) }
        }
        identityStatus = 200
        identityInstallation = "77777777-7777-4777-8777-777777777777"
        ConnectionSelector(listOf(transport(), relay), installation).use { selector ->
            failure("e2e.repair_required") { selector.native("session/list", jsonObject()) }
        }
        val wrongPin = credentialDocument().with("tlsPin" to ("sha256/" + java.util.Base64.getEncoder().encodeToString(ByteArray(32))).json())
        ConnectionSelector(listOf(DshLanTransport(LanCredentials.parse(wrongPin), signer, client), relay), installation).use { selector ->
            failure("lan.tls_failed") { selector.native("session/list", jsonObject()) }
        }
        assertEquals(0, relay.calls.get())
    }

    @Test fun pairResponseCannotReplaceTheReviewedInstallation() = runBlocking {
        pairingInstallation = "77777777-7777-4777-8777-777777777777"
        failure("lan.identity_mismatch") { LanPairingClient(signer, client).pair(LanPairingIntent.create(descriptor(), "Android", owner, signer)) }
        assertEquals(1, requests.count { it.path == "/pair" })
    }

    @Test fun lostPairReplyReconcilesPersistedCapabilityWithoutRepeatingWrite() = runBlocking {
        val original = LanPairingIntent.create(descriptor(), "Android", owner, signer)
        val restored = LanPairingIntent.parse(JsonDocument.parse(JsonDocument.of(original.document()).canonicalBytes()).root)
        disconnectPair = true
        failure("network.unavailable") { LanPairingClient(signer, client).pair(original) }
        LanPairingClient(signer, client).reconcile(restored.credentials)
        failure("lan.pending_authorization") { LanPairingClient(signer, client).pair(original) }
        failure("lan.pending_authorization") { LanPairingClient(signer, client).pair(restored) }
        assertEquals(1, requests.count { it.path == "/pair" })
        val identity = requests.last { it.path == "/pair/identity" }
        assertEquals("DPoP ${original.credentials.token}", identity.getHeader("Authorization"))
        assertEquals(original.credentials.pairId, identity.getHeader("X-TokenWorks-Device-Id"))
        assertEquals("GET", proof(identity).string("htm"))
    }

    @Test fun cancelledPairKeepsSerializableCapabilityAndOnlyQueriesIdentityOnRecovery() = runBlocking {
        val intent = LanPairingIntent.create(descriptor(), "Android", owner, signer)
        pairGate = java.util.concurrent.CountDownLatch(1)
        val pairing = launch { LanPairingClient(signer, client).pair(intent) }
        withTimeout(5000) { while (requests.none { it.path == "/pair" }) delay(10) }
        pairing.cancelAndJoin()
        pairGate!!.countDown()
        val restored = LanPairingIntent.parse(JsonDocument.parse(JsonDocument.of(intent.document()).canonicalBytes()).root)
        LanPairingClient(signer, client).reconcile(restored.credentials)
        assertEquals(1, requests.count { it.path == "/pair" })
        identityInstallation = "77777777-7777-4777-8777-777777777777"
        failure("e2e.repair_required") { LanPairingClient(signer, client).reconcile(restored.credentials) }
        identityStatus = 401
        failure("lan.auth_required") { LanPairingClient(signer, client).reconcile(restored.credentials) }
        assertEquals(intent.credentials.token, restored.credentials.token)
    }

    @Test fun failedDownloadNeverAppendsRelayBytesToAPartiallyWrittenSink() = runBlocking {
        truncatedDownload = true
        val relay = RecordingRelay(runtime)
        ConnectionSelector(listOf(transport(), relay), installation).use { selector ->
            val sink = ByteArrayOutputStream()
            try {
                selector.download("/api/session/downloadFileBinary?sessionId=s&name=a", sink, 100)
                fail("expected truncated response failure")
            } catch (_: RemoteRequestException) {
                assertEquals(0, relay.downloads.get())
                assertFalse(sink.toString("UTF-8").contains("relay"))
            }
        }
    }

    @Test fun selectedRelayMuxSupportsNativeEventsTerminalAndUplink() = runBlocking {
        disconnectProbe = true
        val relay = RecordingRelay(runtime)
        ConnectionSelector(listOf(transport(), relay), installation).use { selector ->
            val mux = selector.openMux()
            val follow = mux.open("session/follow", jsonObject("args" to jsonObject()))
            val terminal = mux.open("terminal/create", jsonObject())
            val events = mux.open(DSH_EVENTS_ENDPOINT, jsonObject())
            terminal.send(DshOptional.Value(jsonObject("input" to "a".json())))
            events.send(DshOptional.Value(jsonObject("answer" to "accepted".json())))
            assertEquals(5, relay.frames.size)
            assertEquals(listOf("session/follow", "terminal/create", DSH_EVENTS_ENDPOINT), relay.frames.take(3).map { WireJson.parse(it).string("endpoint") })
            assertEquals("item", WireJson.parse(relay.frames[3]).string("type"))
            assertEquals("item", WireJson.parse(relay.frames[4]).string("type"))
            follow.end(); follow.close(); terminal.close(); events.close(); mux.close()
        }
    }

    @Test fun outerNonceChallengeRebuildsProofOnceBeforeAnyNativeDispatch() = runBlocking {
        carrierNonceChallenge = true
        transport().use { lan -> assertEquals(JsonBoolean(true), (lan.native("session/list", jsonObject()) as JsonObject)["native"]) }
        val attempts = requests.filter { it.path == "/carrier" }
        assertEquals(2, attempts.size)
        assertNull(proof(attempts[0]).fields["nonce"])
        assertEquals(carrierNonce, proof(attempts[1]).string("nonce"))
        assertNotEquals(proof(attempts[0]).string("jti"), proof(attempts[1]).string("jti"))
        assertTrue(attempts.all { it.handshake != null })
        assertEquals(1, requests.count { it.path == "/api/session/list" })
    }

    @Test fun outerUnauthorizedWithoutNonceDoesNotRetryOrExecuteNativeHttp() = runBlocking {
        identityStatus = 401
        transport().use { lan -> failure("auth.proof_invalid") { lan.native("session/list", jsonObject()) } }
        assertEquals(1, requests.count { it.path == "/carrier" })
        assertFalse(requests.any { it.path?.startsWith("/api/") == true })
    }

    @Test fun oversizedBinaryTextAndAggregateUpgradeHeadersFailBeforeNativeDispatch() = runBlocking {
        for (attack in 1..3) {
            outerAttack = attack
            transport().use { lan -> failure("e2e.failed") { lan.native("session/list", jsonObject()) } }
        }
        assertEquals(3, requests.count { it.path == "/carrier" })
        assertFalse(requests.any { it.path?.startsWith("/api/") == true })
    }

    @Test fun outerUsesConfiguredHttpConnectProxyAndKeepsDpopInsideVerifiedTls() = runBlocking {
        CarrierConnectProxy("localhost", server.port).use { proxy ->
            val configured = client.newBuilder().proxy(java.net.Proxy(java.net.Proxy.Type.HTTP,
                java.net.InetSocketAddress("127.0.0.1", proxy.port))).build()
            DshLanTransport(defaultCredentials, signer, configured).use { lan ->
                assertEquals(JsonBoolean(true), (lan.native("session/list", jsonObject()) as JsonObject)["native"])
            }
            assertEquals(1, proxy.requests.size)
            assertEquals("localhost:${server.port}", proxy.requests.single().target)
            assertNull(proxy.requests.single().headers["Authorization"])
            assertNull(proxy.requests.single().headers["DPoP"])
            assertTrue(proxy.forwardedBytes.get() > 0)
        }
    }

    @Test fun outerHonorsConfiguredProxyAuthenticatorWithoutNativeReplay() = runBlocking {
        CarrierConnectProxy("localhost", server.port, requireAuthentication = true).use { proxy ->
            val authenticating = client.newBuilder().proxy(java.net.Proxy(java.net.Proxy.Type.HTTP,
                java.net.InetSocketAddress("127.0.0.1", proxy.port))).proxyAuthenticator { _, response ->
                assertEquals(407, response.code)
                response.request.newBuilder().header("Proxy-Authorization", "Basic cHJveHk6c2VjcmV0").build()
            }.build()
            DshLanTransport(defaultCredentials, signer, authenticating).use { lan ->
                assertEquals(JsonBoolean(true), (lan.native("session/list", jsonObject()) as JsonObject)["native"])
            }
            assertEquals(2, proxy.requests.size)
            assertNull(proxy.requests.first().headers["Proxy-Authorization"])
            assertEquals("Basic cHJveHk6c2VjcmV0", proxy.requests.last().headers["Proxy-Authorization"])
            assertTrue(proxy.requests.all { it.headers["DPoP"] == null && it.headers["Authorization"] == null })
            assertEquals(1, requests.count { it.path == "/api/session/list" })
        }
    }

    private inner class RecordingRelay(override val runtimeId: String) : EncryptedDshTransport {
        override val installationId: String get() = installation
        override val bindingIdentity: String get() = defaultCredentials.bindingSha256
        override suspend fun probeIdentity() = Unit
        override val state = MutableStateFlow(DshTransportState(DshTransportPath.RELAY, DshTransportPhase.READY))
        val calls = AtomicInteger()
        val uploads = AtomicInteger()
        val downloads = AtomicInteger()
        val frames = mutableListOf<String>()
        override suspend fun native(endpoint: String, args: JsonObject): JsonValue { calls.incrementAndGet(); return "relay".json() }
        override suspend fun openMux() = DshMux({ frames.add(it); true }, { _, _ -> })
        override suspend fun download(path: String, sink: OutputStream, maxBytes: Long, range: DshByteRange?): DshDownload {
            downloads.incrementAndGet()
            sink.write("relay".toByteArray()); return DshDownload("text/plain", 5, null, null)
        }
        override suspend fun upload(path: String, mediaType: String, source: InputStream, sizeBytes: Long): JsonValue { uploads.incrementAndGet(); return JsonNull }
        override fun close() = Unit
    }
}
