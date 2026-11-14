package cn.edu.ustc.tokenworks

import okhttp3.OkHttpClient
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** LAN changes physical pools only between explicit calls; an owned connection never moves to a new Peer. */
class DshLanTransport(
    private val credentials: LanCredentials, private val signer: DeviceProofSigner,
    operatorClient: OkHttpClient = OkHttpClient(), api: RemoteApi? = null,
) : EncryptedDshTransport {
    override val runtimeId: String get() = credentials.runtimeId
    override val installationId: String get() = credentials.installationId
    override val bindingIdentity: String get() = credentials.bindingSha256
    private val dialer = LanCarrierDialer(credentials, signer, operatorClient)
    private val gate = Any()
    private val ended = AtomicBoolean()
    private val controls = mutableSetOf<NativeHttpClient>()
    private val selected = NativeRouteTransport(credentials, DshTransportPath.LAN, api) { lease ->
        LanCarrierDialer(credentials, signer, operatorClient, lease)
    }
    override val state = selected.state
    private suspend fun <T> control(block: suspend (NativeHttpClient) -> T): T {
        val client = synchronized(gate) {
            if (ended.get()) throw DshFailure("transport/closed")
            NativeHttpClient(dialer).also { controls.add(it) }
        }
        try { return block(client) }
        finally { synchronized(gate) { controls.remove(client) }; client.close() }
    }
    override suspend fun probeIdentity() = selected.probeIdentity()
    suspend fun revoke() {
        val result = control { client -> client.control("DELETE", "/pair", proof = { proof("DELETE", "/pair") }) }
        if (result["ok"] != JsonBoolean(true)) throw LanPairingException("lan.invalid_response")
        close()
    }
    internal suspend fun rotate(intent: NoiseDeviceRotationIntent) {
        if (intent.previous.bindingSha256 != credentials.bindingSha256) throw LanPairingException("lan.identity_mismatch")
        control { client ->
            client.control("PUT", "/pair/noise-key", intent.request()) { socket ->
                val bound = intent.request().with("handshakeHash" to Contract.base64url(socket.handshakeHash).json(),
                    "oldDeviceStaticPublicKey" to credentials.deviceStaticPublicKey.json())
                proof("PUT", "/pair/noise-key", JsonDocument.of(bound).sha256())
            }
        }
    }
    internal suspend fun reconcileEncrypted() {
        val identity = control { client -> client.control("GET", "/pair/identity", proof = { proof("GET", "/pair/identity") }) }
        credentials.verifyIdentity(identity)
    }
    private fun proof(method: String, path: String, nonce: String? = null): String = DeviceProofs.dpop(signer, method, credentials.origin + path,
        System.currentTimeMillis(), nonce = nonce, accessToken = credentials.token).compact
    override suspend fun native(endpoint: String, args: JsonObject) = selected.native(endpoint, args)
    override suspend fun openMux() = selected.openMux()
    override suspend fun connect() = selected.connect()
    override suspend fun download(path: String, sink: OutputStream, maxBytes: Long, range: DshByteRange?) = selected.download(path, sink, maxBytes, range)
    override suspend fun upload(path: String, mediaType: String, source: InputStream, sizeBytes: Long) = selected.upload(path, mediaType, source, sizeBytes)
    override fun close() = synchronized(gate) {
        if (ended.compareAndSet(false, true)) {
            selected.close(); controls.forEach(NativeHttpClient::close); controls.clear()
        }
    }
}
