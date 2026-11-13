package cn.edu.ustc.tokenworks

import cn.edu.ustc.tokenworks.DshFailure
import cn.edu.ustc.tokenworks.DshLease
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.InetSocketAddress
import java.net.Socket
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.net.ssl.SSLSocket

class RemoteRequestException(val code: String = "network.unavailable") : IllegalStateException(code)

/** No redirects, implicit retries, cookies, logging interceptor or disk response cache. */
class LoginHttp private constructor(private val client: OkHttpClient) {
    suspend fun downloadAppRelease(release: AppRelease, downloadOrigins: Set<String>, directory: java.io.File, current: () -> Unit = {}, progress: (Long) -> Unit = {}): PrivateApkCandidate {
        currentCoroutineContext().ensureActive(); current()
        val candidate = PrivateApkCandidate(directory, release)
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion { if (it != null) candidate.close() }
        try {
            withContext(Dispatchers.IO) {
                val lifetime = currentCoroutineContext()
                val check = { lifetime.ensureActive(); current() }
                downloadAppBytes(client, candidate, downloadOrigins, check, progress); candidate.seal(check)
            }
            currentCoroutineContext().ensureActive(); current()
            return candidate
        } catch (error: Exception) { candidate.close(); cancellation?.dispose(); throw error }
    }

    suspend fun discovery(config: LoginConfiguration): OidcDiscovery {
        val url = config.issuer.trimEnd('/') + "/.well-known/openid-configuration"
        return OidcDiscovery.parse(get(url), config)
    }
    suspend fun keys(discovery: OidcDiscovery): IssuerKeys = IssuerKeys.parse(get(discovery.jwksUri))
    fun tokenEndpoint(discovery: OidcDiscovery, signer: DeviceProofSigner): TokenEndpoint = TokenEndpoint(this, discovery, signer)
    fun resource(config: LoginConfiguration, session: LoginSession, signer: DeviceProofSigner): RemoteApi {
        if (session.issuer != config.issuer || session.clientId != config.clientId || session.deploymentFingerprint != config.fingerprint || session.proofKeyThumbprint != signer.publicKey.thumbprint) throw RemoteRequestException("auth.identity_mismatch")
        return RemoteApi(this, config, session, signer)
    }



    private suspend fun get(url: String): ByteArray = checked(execute(Request.Builder().url(url).header("Accept", "application/json").header("Cache-Control", "no-store").build())).bytes

    internal suspend fun execute(request: Request): HttpReply {
        if (request.url.scheme != "https" || request.url.username.isNotEmpty() || request.url.password.isNotEmpty()) throw RemoteRequestException("network.insecure_endpoint")
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(RemoteRequestException())
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val reply = response.use {
                            val body = it.body ?: throw RemoteRequestException("network.invalid_response")
                            if (body.contentLength() > JsonDocument.MAX_BYTES) throw RemoteRequestException("network.response_too_large")
                            val source = body.source()
                            source.request(JsonDocument.MAX_BYTES.toLong() + 1)
                            if (source.buffer.size > JsonDocument.MAX_BYTES) throw RemoteRequestException("network.response_too_large")
                            val type = body.contentType()
                            if (type?.type != "application" || (type.subtype != "json" && !type.subtype.endsWith("+json")) || type.charset(Charsets.UTF_8) != Charsets.UTF_8) throw RemoteRequestException("network.invalid_response")
                            HttpReply(it.code, it.headers, source.buffer.readByteArray())
                        }
                        if (continuation.isActive) continuation.resume(reply)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(if (error is RemoteRequestException) error else RemoteRequestException("network.invalid_response"))
                    }
                }
            })
        }
    }

    internal fun webSocket(request: Request, listener: WebSocketListener): WebSocket = client.newWebSocket(request, listener)

    internal fun checked(reply: HttpReply): HttpReply {
        if (reply.status !in 200..299) {
            val code = runCatching { JsonDocument.parse(reply.bytes).root.obj("error").string("code") }.getOrNull()
            // Only protocol codes reach presentation; untrusted error bodies/messages do not.
            throw RemoteRequestException(if (code in setOf("auth.expired", "auth.proof_invalid", "access.denied", "access.revoked", "device.invalid", "device.registration_conflict", "internal",
                    "approval.conflict", "approval.expired", "approval.assurance_required", "rate.limited", "protocol.invalid", "file.request_conflict", "file.quota_exceeded",
                    "push.invalid", "push.conflict", "push.expired", "push.unavailable", "file.state_changed", "file.scanner_unavailable", "file.unavailable", "file.staging_required", "file.already_bound")) code!! else "network.request_failed")
        }
        return reply
    }

    companion object {
        fun create(): LoginHttp = LoginHttp(secure(OkHttpClient.Builder()).build())
        internal fun forTests(client: OkHttpClient): LoginHttp = LoginHttp(secure(client.newBuilder()).build())
        private fun secure(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).cache(null)
            .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
    }
}

internal class HttpReply(val status: Int, val headers: Headers, val bytes: ByteArray) {
    fun nonce(): String? {
        val nonces = headers.values("DPoP-Nonce")
        if (nonces.size > 1) throw RemoteRequestException("auth.proof_invalid")
        return nonces.singleOrNull()?.let { DeviceProofs.nonce(it) }
    }
}

class TokenEndpoint internal constructor(private val http: LoginHttp, private val discovery: OidcDiscovery, private val signer: DeviceProofSigner) {
    private var nonce: String? = null
    private val mutex = Mutex()
    suspend fun exchange(exchange: CodeExchange, now: () -> Long = System::currentTimeMillis): ByteArray = mutex.withLock {
        if (exchange.login.discovery !== discovery || exchange.login.proofKeyThumbprint != signer.publicKey.thumbprint) throw RemoteRequestException("auth.identity_mismatch")
        request(exchange.form(), now)
    }

    suspend fun refresh(previous: LoginSession, now: () -> Long = System::currentTimeMillis): ByteArray = mutex.withLock {
        if (previous.deploymentFingerprint != discovery.configuration.fingerprint || previous.proofKeyThumbprint != signer.publicKey.thumbprint) throw RemoteRequestException("auth.identity_mismatch")
        if (!discovery.refreshAllowed) throw RemoteRequestException("auth.reauthentication_required")
        val refreshToken = previous.consumeRefreshTokenForRequest()
        request(mapOf("grant_type" to "refresh_token", "client_id" to discovery.configuration.clientId, "resource" to discovery.configuration.resource, "refresh_token" to refreshToken), now)
    }

    private suspend fun request(parameters: Map<String, String>, now: () -> Long): ByteArray {
        val url = discovery.tokenEndpoint.toHttpUrl()
        val form = FormBody.Builder().apply { parameters.forEach { (name, value) -> add(name, value) } }.build()
        repeat(2) { attempt ->
            val proof = DeviceProofs.dpop(signer, "POST", url.toString(), now(), nonce)
            nonce = null
            val reply = http.execute(Request.Builder().url(url).post(form).header("DPoP", proof.compact).header("Accept", "application/json").header("Cache-Control", "no-store").build())
            nonce = reply.nonce()
            val challenge = reply.status == 400 && runCatching { JsonDocument.parse(reply.bytes).root.string("error") == "use_dpop_nonce" }.getOrDefault(false)
            if (attempt == 0 && challenge && nonce != null) return@repeat
            if (reply.status !in 200..299) {
                val rejected = runCatching { JsonDocument.parse(reply.bytes).root.string("error") }.getOrNull()
                throw RemoteRequestException(if (reply.status == 400 && rejected in setOf("invalid_grant", "unsupported_grant_type")) "auth.reauthentication_required" else "auth.token_exchange_failed")
            }
            return reply.bytes
        }
        throw RemoteRequestException("auth.token_exchange_failed")
    }
}

class RemoteApi internal constructor(private val http: LoginHttp, private val config: LoginConfiguration, private val session: LoginSession, private val signer: DeviceProofSigner) {
    private val fileOwner = Any()
    private var nonce: String? = null
    private var mappedUserId: String? = null
    private val mutex = Mutex()

    /** Current identity can be queried before registering a device and obtains a fresh nonce. */
    suspend fun me(): ServiceAccountIdentity = mutex.withLock { identity() }

    private suspend fun identity(): ServiceAccountIdentity {
        val data = JsonDocument.parse(read("/v1/me")).root
        data.exactKeys("ok", "data")
        if (data["ok"] != JsonBoolean(true)) throw RemoteRequestException("network.invalid_response")
        val account = ServiceAccountIdentity.parse(data.obj("data"), session.issuer, session.subject, session.clientId, session.scopes, config.organizationId)
        if (mappedUserId != null && account.userId != mappedUserId) throw RemoteRequestException("auth.identity_mismatch")
        mappedUserId = account.userId
        return account
    }

    suspend fun runtimes(): JsonObject = mutex.withLock { JsonDocument.parse(read("/v1/runtimes")).root }
    suspend fun hosts(): JsonObject = mutex.withLock { JsonDocument.parse(read("/v1/hosts")).root }
    suspend fun appRelease(packageName: String, installedVersion: Long, androidApi: Long, downloadOrigins: Set<String>, current: () -> Unit = {}): AppReleaseSelection = mutex.withLock {
        if (installedVersion !in 1..MAX_SAFE_INTEGER || androidApi !in 26..100) throw ProtocolException()
        val path = "/v1/app/releases/latest?organization_id=${config.organizationId}&version_code=$installedVersion&protocol_version=$APP_UPDATE_PROTOCOL&android_api=$androidApi"
        val root = JsonDocument.parse(request("GET", path, beforeSend = current)).root
        current()
        root.exactKeys("ok", "data"); if (root["ok"] != JsonBoolean(true)) throw ProtocolException()
        AppReleaseSelection.parse(root.obj("data"), packageName, installedVersion, androidApi, downloadOrigins)
    }

    fun hostBinding(owner: CoroutineScope, resumeId: String? = null, onAuthorityFailure: (String) -> Unit = {}, onReady: () -> Unit = {}): HostBindingFlow =
        HostBindingFlow(this, owner, resumeId, onAuthorityFailure, onReady)

    suspend fun bindingStatus(id: String): HostBindingDetails = mutex.withLock {
        bindingDetails(read("/v1/enrollments/${Contract.uuid(id)}"), id)
    }
    suspend fun prepareHostBinding(id: String, code: String): HostBindingRequest = mutex.withLock {
        Contract.uuid(id); Contract.nonce(code)
        HostBindingRequest(this, bindingDetails(request("POST", "/v1/enrollments/$id/preview", jsonObject("code" to code.json())), id), code)
    }
    private fun bindingDetails(bytes: ByteArray, id: String): HostBindingDetails {
        val root = JsonDocument.parse(bytes).root
        root.exactKeys("ok", "data")
        if (root["ok"] != JsonBoolean(true)) throw RemoteRequestException("network.invalid_response")
        return HostBindingDetails.parse(root.obj("data"), config.organizationId, id)
    }
    internal suspend fun confirmReviewedBinding(reviewed: HostBindingDetails, code: String, current: () -> Unit, onDispatch: () -> Unit): HostBindingDetails = mutex.withLock {
        val prefix = "/v1/enrollments/${reviewed.id}"
        current()
        val fresh = bindingDetails(request("POST", "$prefix/preview", jsonObject("code" to code.json()), current), reviewed.id)
        if (fresh.reviewHash != reviewed.reviewHash) throw HostBindingException("binding.details_changed")
        current()
        if (fresh.confirmedByMe) return@withLock fresh
        if (fresh.userConfirmed) throw HostBindingException("binding.confirmed_elsewhere")
        if (!fresh.canConfirm()) throw HostBindingException(if (fresh.expired()) "binding.expired" else "binding.unavailable")
        val root = JsonDocument.parse(request("POST", "$prefix/confirm", jsonObject("code" to code.json())) { current(); onDispatch() }).root
        root.exactKeys("ok", "data")
        if (root["ok"] != JsonBoolean(true)) throw RemoteRequestException("network.invalid_response")
        val result = root.obj("data")
        result.exactKeys("enrollment_id", "host_id", "runtime_id", "state", "expires_at", "host_confirmed", "user_confirmed")
        val user = (result["user_confirmed"] as? JsonBoolean)?.value ?: throw ProtocolException()
        val host = (result["host_confirmed"] as? JsonBoolean)?.value ?: throw ProtocolException()
        if (result.string("enrollment_id") != fresh.id || result.string("host_id") != fresh.hostId || result.string("runtime_id") != fresh.runtimeId || relayTime(result.string("expires_at")) != fresh.expiresAt ||
            !user || result.string("state") != if (host) "approved" else "user_confirmed") throw ProtocolException()
        current()
        val accepted = bindingDetails(request("GET", prefix, beforeSend = current), reviewed.id)
        if (!accepted.confirmedByMe || accepted.reviewHash != reviewed.reviewHash) throw HostBindingException("binding.details_changed")
        accepted
    }


    /** Reconciliation only: never creates or revives a device and never replays a write. */
    suspend fun currentDevice(approvalKey: PublicProofKey): RegisteredDevice? = mutex.withLock {
        identity()
        val root = JsonDocument.parse(read("/v1/devices/current")).root
        root.exactKeys("ok", "data")
        if (root["ok"] != JsonBoolean(true)) throw RemoteRequestException("network.invalid_response")
        val data = root.obj("data")
        if (data.string("state") == "unregistered") {
            data.exactKeys("state")
            return@withLock null
        }
        data.exactKeys("state", "device")
        val state = data.string("state")
        if (state !in setOf("active", "revoked")) throw RemoteRequestException("network.invalid_response")
        val device = data.obj("device"); device.exactKeys("device_id", "key_version", "approval_key_jkt", "proof_key_jkt")
        if (device.string("approval_key_jkt") != approvalKey.thumbprint || device.string("proof_key_jkt") != signer.publicKey.thumbprint) throw RemoteRequestException("auth.identity_mismatch")
        val id = Contract.uuid(device.string("device_id"))
        val version = device.integer("key_version", 1, 9_007_199_254_740_991)
        if (state == "revoked" || version != 1L) throw RemoteRequestException("device.invalid")
        RegisteredDevice(id, version)
    }

    suspend fun prepareRegistration(approvalKey: PublicProofKey, name: String): RegistrationRequest = mutex.withLock {
        // A successful /me request obtains a nonce before asking for user auth.
        identity()
        val jti = DeviceProofs.randomId()
        val prepared = PreparedDeviceRegistration.prepare(session.issuer, session.subject, name, approvalKey, signer.publicKey, jti, System.currentTimeMillis())
        val reserved = nonce; nonce = null
        RegistrationRequest(prepared, approvalKey, name, jti, reserved)
    }

    inner class RegistrationRequest internal constructor(
        val signatureRequest: PreparedDeviceRegistration, private val approvalKey: PublicProofKey, private val name: String, private val jti: String, private val reservedNonce: String?,
    ) {
        private val once = java.util.concurrent.atomic.AtomicBoolean()
        suspend fun submit(signed: DeviceRegistrationProof): RegisteredDevice = mutex.withLock {
            if (!once.compareAndSet(false, true) || signed.dpopJti != jti) throw RemoteRequestException("auth.proof_invalid")
            val access = session.accessTokenForRequest(System.currentTimeMillis())
            val url = (config.remoteOrigin + "/v1/devices").toHttpUrl()
            val proof = DeviceProofs.dpop(signer, "POST", url.toString(), System.currentTimeMillis(), reservedNonce, access, jti)
            val body = JsonDocument.of(jsonObject("name" to name.json(), "approval_public_jwk" to approvalKey.document.root,
                "approval_proof" to signed.compact.json(), "proof_public_jwk" to signer.publicKey.document.root)).canonicalBytes().toRequestBody("application/json".toMediaType())
            val reply = http.execute(Request.Builder().url(url).post(body).header("Authorization", "DPoP $access").header("DPoP", proof.compact).header("Accept", "application/json").build())
            nonce = reply.nonce()
            // No automatic retry: a new DPoP jti needs a new approval-key proof
            // and explicit local authentication. Transport loss is ambiguous.
            val result = JsonDocument.parse(http.checked(reply).bytes).root
            result.exactKeys("ok", "data")
            if (result["ok"] != JsonBoolean(true)) throw RemoteRequestException("network.invalid_response")
            val data = result.obj("data"); data.exactKeys("device_id", "key_version")
            RegisteredDevice(Contract.uuid(data.string("device_id")), data.integer("key_version", 1, 1))
        }
        fun cancel() { once.set(true); signatureRequest.cancel() }
    }

    private suspend fun read(path: String): ByteArray = request("GET", path)

    private suspend fun request(method: String, path: String, body: JsonObject? = null, beforeSend: () -> Unit = {}): ByteArray {
        val url = (config.remoteOrigin + path).toHttpUrl()
        repeat(2) { attempt ->
            currentCoroutineContext().ensureActive()
            val access = session.accessTokenForRequest(System.currentTimeMillis())
            val proof = DeviceProofs.dpop(signer, method, url.toString(), System.currentTimeMillis(), nonce, access)
            nonce = null
            val builder = Request.Builder().url(url).header("Authorization", "DPoP $access").header("DPoP", proof.compact).header("Accept", "application/json").header("Cache-Control", "no-store")
            if (body != null) builder.post(JsonDocument.of(body).canonicalBytes().toRequestBody("application/json".toMediaType()))
            currentCoroutineContext().ensureActive(); beforeSend()
            val reply = http.execute(builder.build())
            nonce = reply.nonce()
            val challenge = reply.status == 401 && reply.headers.values("WWW-Authenticate").any { it == "DPoP error=\"use_dpop_nonce\"" }
            if (attempt == 0 && challenge && nonce != null) return@repeat
            return http.checked(reply).bytes
        }
        throw RemoteRequestException("auth.proof_invalid")
    }

    /** Same DPoP shape as request(), plus the Relay routing headers. */
    private suspend fun nativeRequest(method: String, path: String, body: JsonObject?, extra: List<Pair<String, String>>): ByteArray {
        val url = (config.remoteOrigin + path).toHttpUrl()
        repeat(2) { attempt ->
            currentCoroutineContext().ensureActive()
            val access = session.accessTokenForRequest(System.currentTimeMillis())
            val proof = DeviceProofs.dpop(signer, method, url.toString(), System.currentTimeMillis(), nonce, access)
            nonce = null
            val builder = Request.Builder().url(url).header("Authorization", "DPoP $access").header("DPoP", proof.compact)
                .header("Accept", "application/json").header("Cache-Control", "no-store")
            extra.forEach { (name, value) -> builder.header(name, value) }
            if (body != null) builder.post(JsonDocument.of(body).canonicalBytes().toRequestBody("application/json".toMediaType()))
            val reply = http.execute(builder.build())
            nonce = reply.nonce()
            val challenge = reply.status == 401 && reply.headers.values("WWW-Authenticate").any { it == "DPoP error=\"use_dpop_nonce\"" }
            if (attempt == 0 && challenge && nonce != null) return@repeat
            return http.checked(reply).bytes
        }
        throw RemoteRequestException("auth.proof_invalid")
    }

    // ---- Native dsh transport (POST /api + /api/remote.mux) ----

    private fun adopt(headers: Headers) {
        headers.values("DPoP-Nonce").singleOrNull()?.let { nonce = it }
    }

    suspend fun nativeLease(runtimeId: String): DshLease {
        Contract.uuid(runtimeId)
        val path = "/v2/runtimes/$runtimeId/leases"
        val root = JsonDocument.parse(mutex.withLock { request("POST", path, jsonObject()) }).root
        root.exactKeys("ok", "data")
        if (root["ok"] != JsonBoolean(true)) throw RemoteRequestException("network.invalid_response")
        val data = root.obj("data")
        data.exactKeys("access_token", "token_type", "expires_in", "lease_id")
        if (data.string("token_type") != "Bearer" || data.integer("expires_in", 1, 3600) != 300L) throw RemoteRequestException("lease.invalid")
        val token = data.string("access_token")
        if (!token.matches(Regex("^[A-Za-z0-9_-]{43}$"))) throw RemoteRequestException("lease.invalid")
        Contract.uuid(data.string("lease_id"))
        return DshLease(token, data.string("lease_id"), System.currentTimeMillis() + 300_000)
    }

    /** One unary native call. Returns the raw dsh value; absence stays absent. */
    suspend fun native(endpoint: String, args: JsonObject, runtimeId: String, lease: DshLease): JsonValue {
        if (!endpoint.matches(Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]*/[A-Za-z0-9_.:-]+"))) throw RemoteRequestException("protocol.invalid")
        Contract.uuid(runtimeId)
        val rpcId = java.util.UUID.randomUUID().toString()
        val body = jsonObject(
            "type" to JsonString("client-request"),
            "rpcId" to JsonString(rpcId),
            "method" to JsonString(endpoint),
            "payload" to jsonObject("args" to args),
        )
        val reply = mutex.withLock {
            nativeRequest("POST", "/api/$endpoint", body, routing(runtimeId, lease))
        }
        val root = JsonDocument.parse(reply).root
        if (root.fields.keys != setOf("type", "rpcId", "result") || root.string("type") != "server-response" || root.string("rpcId") != rpcId) throw RemoteRequestException("network.invalid_response")
        val result = root.obj("result")
        if (result.fields.keys !in setOf(setOf("ok", "value"), setOf("ok", "error"), setOf("ok"))) throw RemoteRequestException("network.invalid_response")
        return when (result["ok"]) {
            JsonBoolean(true) -> result.fields["value"] ?: JsonNull
            JsonBoolean(false) -> {
                val error = result.obj("error")
                val code = runCatching { error.string("code") }.getOrDefault("gateway/internal")
                throw DshFailure(if (code.matches(Regex("^[a-z0-9][a-z0-9/._-]{0,63}$"))) code else "gateway/internal")
            }
            else -> throw RemoteRequestException("network.invalid_response")
        }
    }

    private fun routing(runtimeId: String, lease: DshLease) = listOf(
        "X-TokenWorks-Runtime" to runtimeId, "X-TokenWorks-Lease" to lease.token,
    )

    /** One exact-route GET under the runtime lease; binary bodies pass through verbatim. */
    suspend fun fileBytes(runtimeId: String, lease: DshLease, path: String, maxBytes: Int): ByteArray {
        Contract.uuid(runtimeId)
        if (!path.startsWith("/api/") || path.length > 4096) throw RemoteRequestException("protocol.invalid")
        val reply = mutex.withLock { nativeRequest("GET", path, null, routing(runtimeId, lease)) }
        if (reply.size > maxBytes) throw RemoteRequestException("network.response_too_large")
        return reply
    }

    /** Opens the native mux for this runtime. The caller keeps it for its lifetime. */
    suspend fun openMux(runtimeId: String, lease: DshLease): cn.edu.ustc.tokenworks.DshMux {
        Contract.uuid(runtimeId)
        val url = config.remoteOrigin + "/api/remote.mux"
        val httpsUrl = url.toHttpUrl()
        // A GET rotates the DPoP nonce through the normal JSON path so the
        // upgrade proof carries a server-issued nonce. 404 here is expected.
        runCatching { mutex.withLock { nativeRequest("GET", "/api/remote.mux", null, routing(runtimeId, lease)) } }
        repeat(2) { attempt ->
            val access = session.accessTokenForRequest(System.currentTimeMillis())
            val proof = DeviceProofs.dpop(signer, "GET", url, System.currentTimeMillis(), nonce, access)
            nonce = null
            val request = Request.Builder().url(url.replaceFirst("https://", "wss://"))
                .header("Authorization", "DPoP $access").header("DPoP", proof.compact)
                .header("X-TokenWorks-Runtime", runtimeId).header("X-TokenWorks-Lease", lease.token)
                .build()
            val opened = CompletableDeferred<cn.edu.ustc.tokenworks.DshMux>()
            var socket: WebSocket? = null
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    adopt(response.headers)
                    opened.complete(cn.edu.ustc.tokenworks.DshMux(
                        { text -> webSocket.send(text) },
                        { code, reason -> webSocket.close(code, reason) },
                    ))
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (opened.isCompleted) opened.getCompleted().receive(text) else opened.completeExceptionally(RemoteRequestException("network.invalid_response"))
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (opened.isCompleted) opened.getCompleted().fail()
                    webSocket.close(1000, null)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    response?.let { adopt(it.headers) }
                    if (!opened.isCompleted) opened.completeExceptionally(RemoteRequestException(if (response?.code == 401) "auth.proof_invalid" else "network.unavailable"))
                }
            }
            socket = http.webSocket(request, listener)
            try {
                return withTimeout(15_000) { opened.await() }
            } catch (error: Exception) {
                socket?.cancel()
                val retry = attempt == 0 && error is RemoteRequestException && error.code == "auth.proof_invalid" && nonce != null
                if (!retry) throw error
            }
        }
        throw RemoteRequestException("auth.proof_invalid")
    }
}


class RegisteredDevice(val deviceId: String, val keyVersion: Long)
