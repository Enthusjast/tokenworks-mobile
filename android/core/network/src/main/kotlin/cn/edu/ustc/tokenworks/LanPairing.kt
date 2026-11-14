package cn.edu.ustc.tokenworks

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CertificatePinner
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.util.Base64
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Stable, presentation-safe codes only; never include an operator response or a credential. */
class LanPairingException(val code: String) : IllegalStateException(code)

class LanPairingDescriptor private constructor(
    val origin: String, val runtimeId: String, val installationId: String, val tlsPin: String,
    internal val code: String, val expiresAt: Long, val hostStaticPublicKey: String, val hostKeyEpoch: Long,
) {
    fun document(): JsonObject = jsonObject("version" to 2L.json(), "origin" to origin.json(), "runtimeId" to runtimeId.json(),
        "installationId" to installationId.json(), "tlsPin" to tlsPin.json(), "code" to code.json(), "expiresAt" to expiresAt.json(),
        "carrierVersion" to 1L.json(), "suite" to E2eSession.SUITE.json(), "hostStaticPublicKey" to hostStaticPublicKey.json(),
        "hostKeyEpoch" to hostKeyEpoch.json(), "minimumEncrypted" to JsonBoolean(true))
    companion object {
        fun parse(text: String, now: Long = System.currentTimeMillis()): LanPairingDescriptor {
            val root = JsonDocument.parse(text).root
            if (root["version"] != 2L.json()) throw LanPairingException("e2e.repair_required")
            root.exactKeys("version", "origin", "runtimeId", "installationId", "tlsPin", "code", "expiresAt",
                "carrierVersion", "suite", "hostStaticPublicKey", "hostKeyEpoch", "minimumEncrypted")
            root.integer("carrierVersion", 1, 1)
            if (root.string("suite") != E2eSession.SUITE || root["minimumEncrypted"] != JsonBoolean(true))
                throw LanPairingException("e2e.unsupported")
            val expires = root.integer("expiresAt", 1, MAX_SAFE_INTEGER)
            if (expires <= now) throw LanPairingException("lan.code_expired")
            return LanPairingDescriptor(lanOrigin(root.string("origin")), lanIdentity(root.string("runtimeId")),
                lanIdentity(root.string("installationId")), lanPin(root.string("tlsPin")),
                root.string("code").also { if (it.length != 8 || it.any { c -> c !in '0'..'9' }) throw ProtocolException() },
                expires, noisePublic(root.string("hostStaticPublicKey")), root.integer("hostKeyEpoch", 1, MAX_SAFE_INTEGER))
        }
    }
}

/** Secret material exists only in the owner/proof-scoped encrypted vault, never presentation state. */
class LanCredentials private constructor(
    val pairId: String, internal val token: String, val runtimeId: String, val installationId: String,
    val origin: String, val tlsPin: String, val manifest: JsonObject, private val privateKey: ByteArray,
) {
    val hostStaticPublicKey: String get() = manifest.string("hostStaticPublicKey")
    val hostKeyEpoch: Long get() = manifest.integer("hostKeyEpoch", 1, MAX_SAFE_INTEGER)
    val deviceStaticPublicKey: String get() = manifest.string("deviceStaticPublicKey")
    val deviceKeyEpoch: Long get() = manifest.integer("deviceKeyEpoch", 1, MAX_SAFE_INTEGER)
    val bindingSha256: String get() = JsonDocument.of(manifest).sha256()
    val ownerSha256: String get() = JsonDocument.of(manifest.obj("owner")).sha256()
    internal fun localPrivate(): ByteArray = privateKey.copyOf()
    fun document(): JsonObject = publicDocument().with("deviceStaticPrivateKey" to Contract.base64url(privateKey).json())
    internal fun publicDocument(): JsonObject = jsonObject("deviceId" to pairId.json(), "token" to token.json(),
        "runtimeId" to runtimeId.json(), "installationId" to installationId.json(), "origin" to origin.json(), "tlsPin" to tlsPin.json(),
        "manifest" to manifest, "bindingSha256" to bindingSha256.json())
    internal fun verifyPublic(root: JsonObject) {
        if (JsonDocument.of(root).canonicalText() != JsonDocument.of(publicDocument()).canonicalText())
            throw LanPairingException("lan.identity_mismatch")
    }
    internal fun verifyIdentity(root: JsonObject) {
        root.exactKeys("runtimeId", "installationId", "manifest", "bindingSha256")
        if (root.string("runtimeId") != runtimeId || root.string("installationId") != installationId ||
            root.string("bindingSha256") != bindingSha256 || JsonDocument.of(root.obj("manifest")).sha256() != bindingSha256)
            throw LanPairingException("e2e.repair_required")
    }
    companion object {
        internal fun allocate(descriptor: LanPairingDescriptor, owner: LocalDataOwner, signer: DeviceProofSigner, relay: JsonObject?): LanCredentials {
            val keys = try { E2eSession.generateStatic() } catch (_: UnsupportedOperationException) { throw LanPairingException("e2e.unsupported") }
            try {
                val pairId = UUID.randomUUID().toString()
                val token = Contract.base64url(ByteArray(32).also { SecureRandom().nextBytes(it) })
                var manifest = jsonObject("v" to 2L.json(), "runtimeId" to descriptor.runtimeId.json(), "installationId" to descriptor.installationId.json(),
                    "pairId" to pairId.json(), "tokenSha256" to noiseTokenHash(token).json(), "proofJkt" to signer.publicKey.thumbprint.json(),
                    "deviceStaticPublicKey" to Contract.base64url(keys.publicKey).json(), "deviceKeyEpoch" to 1L.json(),
                    "hostStaticPublicKey" to descriptor.hostStaticPublicKey.json(), "hostKeyEpoch" to descriptor.hostKeyEpoch.json(), "owner" to owner.root)
                relay?.let { manifest = manifest.with("relay" to it) }
                return parse(jsonObject("deviceId" to pairId.json(), "token" to token.json(), "runtimeId" to descriptor.runtimeId.json(),
                    "installationId" to descriptor.installationId.json(), "origin" to descriptor.origin.json(), "tlsPin" to descriptor.tlsPin.json(),
                    "manifest" to manifest, "bindingSha256" to JsonDocument.of(manifest).sha256().json(),
                    "deviceStaticPrivateKey" to Contract.base64url(keys.privateKey).json()))
            } finally { keys.privateKey.fill(0) }
        }
        fun parse(root: JsonObject): LanCredentials {
            if (root.fields["manifest"] == null) throw LanPairingException("e2e.repair_required")
            root.exactKeys("deviceId", "token", "runtimeId", "installationId", "origin", "tlsPin", "manifest", "bindingSha256", "deviceStaticPrivateKey")
            val manifest = root.obj("manifest")
            manifest.exactKeys(*(listOf("v", "runtimeId", "installationId", "pairId", "tokenSha256", "proofJkt", "deviceStaticPublicKey",
                "deviceKeyEpoch", "hostStaticPublicKey", "hostKeyEpoch", "owner") + if (manifest.fields["relay"] != null) listOf("relay") else emptyList()).toTypedArray())
            manifest.integer("v", 2, 2)
            val token = Contract.nonce(root.string("token"))
            if (Contract.base64url(token).size != 32 || manifest.string("tokenSha256") != noiseTokenHash(token)) throw ProtocolException()
            val pairId = Contract.uuid(root.string("deviceId"))
            if (manifest.string("pairId") != pairId || manifest.string("runtimeId") != root.string("runtimeId") ||
                manifest.string("installationId") != root.string("installationId") || JsonDocument.of(manifest).sha256() != root.string("bindingSha256")) throw ProtocolException()
            noisePublic(manifest.string("deviceStaticPublicKey")); noisePublic(manifest.string("hostStaticPublicKey"))
            manifest.integer("deviceKeyEpoch", 1, MAX_SAFE_INTEGER); manifest.integer("hostKeyEpoch", 1, MAX_SAFE_INTEGER)
            Contract.nonce(manifest.string("proofJkt"))
            val owner = manifest.obj("owner")
            owner.exactKeys("account_id", "organization_id", "deployment_sha256", "user_id", "device_id")
            LocalDataOwner(owner.string("account_id"), owner.string("organization_id"), owner.string("deployment_sha256"), owner.string("user_id"), owner.string("device_id"))
            manifest.fields["relay"]?.let { value ->
                val relay = value as? JsonObject ?: throw ProtocolException()
                relay.exactKeys("issuer", "organizationId", "deploymentSha256", "userId", "deviceId")
                Contract.boundedText(relay.string("issuer"), 2048); Contract.uuid(relay.string("organizationId")); Contract.digest(relay.string("deploymentSha256"))
                Contract.uuid(relay.string("userId")); Contract.uuid(relay.string("deviceId"))
                if (relay.string("organizationId") != owner.string("organization_id") || relay.string("deploymentSha256") != owner.string("deployment_sha256") ||
                    relay.string("userId") != owner.string("user_id") || relay.string("deviceId") != owner.string("device_id")) throw ProtocolException()
            }
            return LanCredentials(pairId, token, lanIdentity(root.string("runtimeId")), lanIdentity(root.string("installationId")),
                lanOrigin(root.string("origin")), lanPin(root.string("tlsPin")), manifest, Contract.base64url(noisePublic(root.string("deviceStaticPrivateKey"))))
        }
    }
}

/** Precommit is mandatory before the single POST. Recovered intents are read-reconciled only. */
class LanPairingIntent private constructor(val credentials: LanCredentials, val descriptor: LanPairingDescriptor, val deviceName: String, private val fresh: Boolean) {
    fun document(): JsonObject = jsonObject("version" to 2L.json(), "credentials" to credentials.document(), "descriptor" to descriptor.document(), "deviceName" to deviceName.json())
    private val dispatched = java.util.concurrent.atomic.AtomicBoolean()
    internal fun dispatchOnce() { if (!fresh || !dispatched.compareAndSet(false, true)) throw LanPairingException("lan.pending_authorization") }
    companion object {
        private fun validateName(name: String) { Contract.boundedText(name, 320); if (name.length > 80 || name.isBlank() || name != name.trim()) throw ProtocolException() }
        fun create(descriptor: LanPairingDescriptor, deviceName: String, owner: LocalDataOwner, signer: DeviceProofSigner, relay: JsonObject? = null): LanPairingIntent {
            validateName(deviceName)
            return LanPairingIntent(LanCredentials.allocate(descriptor, owner, signer, relay), descriptor, deviceName, true)
        }
        fun parse(root: JsonObject): LanPairingIntent {
            root.exactKeys("version", "credentials", "descriptor", "deviceName"); root.integer("version", 2, 2)
            val credentials = LanCredentials.parse(root.obj("credentials"))
            val descriptor = LanPairingDescriptor.parse(JsonDocument.of(root.obj("descriptor")).canonicalText(), now = 0)
            val deviceName = root.string("deviceName").also(::validateName)
            if (credentials.runtimeId != descriptor.runtimeId || credentials.installationId != descriptor.installationId || credentials.origin != descriptor.origin ||
                credentials.tlsPin != descriptor.tlsPin || credentials.hostStaticPublicKey != descriptor.hostStaticPublicKey || credentials.hostKeyEpoch != descriptor.hostKeyEpoch) throw ProtocolException()
            return LanPairingIntent(credentials, descriptor, deviceName, false)
        }
    }
}

class LanPairingClient(private val signer: DeviceProofSigner, private val operatorClient: OkHttpClient = OkHttpClient()) {
    suspend fun pair(intent: LanPairingIntent): LanCredentials {
        intent.dispatchOnce()
        val descriptor = intent.descriptor
        val credentials = intent.credentials
        if (credentials.manifest.string("proofJkt") != signer.publicKey.thumbprint) throw LanPairingException("lan.identity_mismatch")
        val http = LanHttp(descriptor.origin, descriptor.tlsPin, operatorClient)
        http.preflight()
        if (descriptor.expiresAt <= System.currentTimeMillis()) throw LanPairingException("lan.code_expired")
        val url = http.url("/pair")
        val proof = DeviceProofs.dpop(signer, "POST", url.toString(), System.currentTimeMillis(), accessToken = credentials.token,
            nonce = "${descriptor.code}.${credentials.bindingSha256}")
        val request = Request.Builder().url(url).post(JsonDocument.of(jsonObject("code" to descriptor.code.json(), "deviceName" to intent.deviceName.json(),
            "deviceId" to credentials.pairId.json(), "token" to credentials.token.json(), "manifest" to credentials.manifest)).canonicalBytes().toRequestBody(LAN_JSON))
            .header("DPoP", proof.compact).header("Accept", "application/json").header("Cache-Control", "no-store").build()
        credentials.verifyPublic(JsonDocument.parse(http.execute(request)).root)
        return credentials
    }
    suspend fun reconcile(credentials: LanCredentials) {
        val http = LanHttp(credentials.origin, credentials.tlsPin, operatorClient)
        http.preflight()
        val url = http.url("/pair/identity")
        val proof = DeviceProofs.dpop(signer, "GET", url.toString(), System.currentTimeMillis(), accessToken = credentials.token)
        val request = Request.Builder().url(url).get().header("DPoP", proof.compact).header("Authorization", "DPoP ${credentials.token}")
            .header("X-TokenWorks-Device-Id", credentials.pairId).header("Accept", "application/json").header("Cache-Control", "no-store").build()
        credentials.verifyIdentity(JsonDocument.parse(http.execute(request)).root)
    }
}

internal fun noisePublic(value: String): String = value.also {
    if (it.length != 43 || Contract.base64url(it).size != 32 || Contract.base64url(Contract.base64url(it)) != it) throw ProtocolException()
}
internal fun noiseTokenHash(token: String): String = Contract.base64url(java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))

internal val LAN_JSON = "application/json".toMediaType()
internal fun lanIdentity(value: String): String = Contract.boundedText(value, 256).also {
    if (it.any { c -> c !in '!'..'~' }) throw ProtocolException()
}
internal fun lanOrigin(value: String): String {
    val url = try { value.toHttpUrl() } catch (_: Exception) { throw ProtocolException() }
    if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
        url.encodedPath != "/" || url.encodedQuery != null || url.encodedFragment != null ||
        value != url.toString().removeSuffix("/")) throw ProtocolException()
    return value
}
internal fun lanPin(value: String): String = value.also {
    if (!it.startsWith("sha256/")) throw ProtocolException()
    val raw = try { Base64.getDecoder().decode(it.removePrefix("sha256/")) } catch (_: Exception) { throw ProtocolException() }
    if (raw.size != 32 || "sha256/" + Base64.getEncoder().encodeToString(raw) != it) throw ProtocolException()
}

/** The public TLS probe carries no secrets. Every later connection independently enforces the pin. */
internal class LanHttp(private val origin: String, pin: String, operatorClient: OkHttpClient) {
    private val base = origin.toHttpUrl()
    private val pinner = CertificatePinner.Builder().add(base.host, pin).build()
    // Use OkHttp's normal hostname verifier, even when a supplied client overrides it.
    private val hostnameVerifier = OkHttpClient().hostnameVerifier
    val client: OkHttpClient = operatorClient.newBuilder().apply {
        interceptors().clear(); networkInterceptors().clear()
    }.hostnameVerifier(hostnameVerifier).certificatePinner(pinner)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .cache(null).cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

    fun url(path: String): HttpUrl {
        if (!path.startsWith('/') || path.startsWith("//") || path.length > 8192 || '#' in path ||
            path.any { it.code < 32 || it.code == 127 }) throw ProtocolException()
        val target = (origin + path).toHttpUrl()
        if (target.scheme != base.scheme || target.host != base.host || target.port != base.port ||
            target.username.isNotEmpty() || target.password.isNotEmpty()) throw ProtocolException()
        return target
    }

    suspend fun preflight() {
        execute(Request.Builder().url(url("/pair/tls")).get().header("Cache-Control", "no-store").build(), emptyAllowed = true)
    }

    suspend fun execute(request: Request, maxBytes: Int = JsonDocument.MAX_BYTES, emptyAllowed: Boolean = false): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(LanPairingException(
                        if (error is SSLException) "lan.tls_failed" else "network.unavailable"))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bytes = response.use {
                            when (it.code) {
                                401 -> throw LanPairingException("lan.auth_required")
                                403, 410 -> throw LanPairingException("lan.revoked")
                            }
                            if (it.code !in 200..299) throw LanPairingException("lan.request_failed")
                            if (emptyAllowed && it.code == 204) return@use ByteArray(0)
                            val body = it.body ?: throw LanPairingException("lan.invalid_response")
                            val type = body.contentType()
                            if (type?.type != "application" || (type.subtype != "json" && !type.subtype.endsWith("+json")) ||
                                type.charset(Charsets.UTF_8) != Charsets.UTF_8) throw LanPairingException("lan.invalid_response")
                            if (body.contentLength() > maxBytes) throw LanPairingException("lan.invalid_response")
                            val source = body.source()
                            source.request(maxBytes.toLong() + 1)
                            if (source.buffer.size > maxBytes) throw LanPairingException("lan.invalid_response")
                            source.buffer.readByteArray()
                        }
                        if (continuation.isActive) continuation.resume(bytes)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (error is LanPairingException) error else LanPairingException("lan.invalid_response"))
                    }
                }
            })
        }
}
