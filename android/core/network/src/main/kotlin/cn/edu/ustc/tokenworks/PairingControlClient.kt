package cn.edu.ustc.tokenworks

import java.util.concurrent.atomic.AtomicBoolean
/** Pairing management only. This client cannot execute a native API or native mux request. */
class PairingControlClient internal constructor(
    private val credentials: LanCredentials, private val signer: DeviceProofSigner, private val client: NativeHttpClient,
) : AutoCloseable {
    private val ended = AtomicBoolean()
    val closed: Boolean get() = ended.get()
    suspend fun request(method: String, path: String, body: JsonObject? = null): JsonObject {
        if (closed) throw DshFailure("transport/changed")
        require(method in setOf("GET", "POST", "DELETE") && path.matches(Regex("^/pair/rtc/[A-Za-z0-9/_-]{1,160}$")))
        return client.control(method, path, body) {
            DeviceProofs.dpop(signer, method, credentials.origin + path, System.currentTimeMillis(), accessToken = credentials.token).compact
        }
    }
    override fun close() { if (ended.compareAndSet(false, true)) client.close() }
}
