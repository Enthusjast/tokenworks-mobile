package cn.edu.ustc.tokenworks

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Immutable key/pool/Peer generation shared by unary, files and independent mux sockets. */
internal class EncryptedRoute(
    override val runtimeId: String, private val path: DshTransportPath,
    private val client: NativeHttpClient, private val valid: () -> Boolean = { true },
) : DshTransport {
    private val ended = AtomicBoolean()
    private val mutable = MutableStateFlow(DshTransportState(path, DshTransportPhase.CONNECTING))
    override val state: StateFlow<DshTransportState> = mutable.asStateFlow()
    private fun live() { if (ended.get() || !valid()) throw DshFailure("transport/changed"); client.live() }
    val retired: Boolean get() = ended.get() || client.retired
    private suspend fun <T> operation(block: suspend () -> T): T {
        live()
        try { return block().also { live(); mutable.value = DshTransportState(path, DshTransportPhase.READY) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val code = when (error) { is DshFailure -> error.code; is LanPairingException -> error.code; is RemoteRequestException -> error.code
                is java.io.IOException -> when {
                    error.message in setOf("lan.tls_failed", "auth.proof_invalid", "access.denied", "e2e.unsupported") -> error.message!!
                    error.message?.startsWith("e2e/") == true && error !is java.io.EOFException && error !is java.net.SocketTimeoutException -> "e2e.failed"
                    else -> "network.unavailable"
                }
                else -> "e2e.failed" }
            val phase = when {
                ended.get() -> DshTransportPhase.CLOSED
                code in setOf("access.denied", "lan.revoked") -> DshTransportPhase.REVOKED
                code in setOf("auth.proof_invalid", "lan.auth_required") -> DshTransportPhase.AUTH_REQUIRED
                code.startsWith("e2e.") || code == "lan.tls_failed" -> DshTransportPhase.UNSUPPORTED
                else -> when (DshRecoveryPolicy.classify(error).action) {
                    DshRecoveryAction.REVOKED -> DshTransportPhase.REVOKED
                    DshRecoveryAction.AUTH_REQUIRED -> DshTransportPhase.AUTH_REQUIRED
                    DshRecoveryAction.MANUAL_RETRY -> DshTransportPhase.UNSUPPORTED
                    DshRecoveryAction.CANCELLED -> DshTransportPhase.CLOSED
                    DshRecoveryAction.BACKOFF -> DshTransportPhase.BACKOFF
                }
            }
            mutable.value = DshTransportState(path, phase, code)
            if (phase == DshTransportPhase.REVOKED || phase == DshTransportPhase.AUTH_REQUIRED) client.close()
            if (error is java.io.IOException) throw RemoteRequestException(code).also { it.initCause(error) }
            throw error
        }
    }
    suspend fun probe() = operation { client.probe() }
    override suspend fun native(endpoint: String, args: JsonObject): JsonValue = operation { client.native(endpoint, args) }
    override suspend fun openMux(): DshMux = operation { client.openMux() }
    override suspend fun download(path: String, sink: OutputStream, maxBytes: Long, range: DshByteRange?): DshDownload = operation { client.download(path, sink, maxBytes, range) }
    override suspend fun upload(path: String, mediaType: String, source: InputStream, sizeBytes: Long): JsonValue = operation { client.upload(path, mediaType, source, sizeBytes) }
    override suspend fun connect(): DshConnection {
        probe()
        val closed = AtomicBoolean()
        val muxes = ConcurrentHashMap.newKeySet<DshMux>()
        return object : DshConnection {
            private fun current() { if (closed.get()) throw DshFailure("transport/changed"); live() }
            override suspend fun native(endpoint: String, args: JsonObject): JsonValue { current(); return this@EncryptedRoute.native(endpoint, args).also { current() } }
            override suspend fun openMux(): DshMux {
                current()
                return this@EncryptedRoute.openMux().also { mux ->
                    muxes.removeAll { it.closed() }; muxes.add(mux)
                    try { current() } catch (error: Exception) { mux.close(); throw error }
                }
            }
            override fun close() { if (closed.compareAndSet(false, true)) { muxes.forEach(DshMux::close); muxes.clear() } }
        }
    }
    override fun close() { if (ended.compareAndSet(false, true)) { client.close(); mutable.value = DshTransportState(path, DshTransportPhase.CLOSED) } }
}
