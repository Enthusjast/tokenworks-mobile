package cn.edu.ustc.tokenworks

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
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
    private suspend fun <T> operation(discard: (T) -> Unit = {}, endpoint: String? = null, block: suspend () -> T): T {
        val context = currentCoroutineContext()
        context.ensureActive()
        live()
        try {
            val result = block()
            try { context.ensureActive(); live() } catch (error: Exception) { discard(result); throw error }
            if (mutable.value.phase != DshTransportPhase.READY) mutable.value = DshTransportState(path, DshTransportPhase.READY)
            return result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            context.ensureActive()
            if (isSessionDeletionRefusal(endpoint, error)) { live(); throw error }
            val failure = routeFailure(path, error)
            mutable.value = if (ended.get()) DshTransportState(path, DshTransportPhase.CLOSED) else failure
            if (failure.phase == DshTransportPhase.REVOKED || failure.phase == DshTransportPhase.AUTH_REQUIRED) client.close()
            if (error is IOException) throw RemoteRequestException(checkNotNull(failure.error)).also { it.initCause(error) }
            throw error
        }
    }
    suspend fun probe() = operation { client.probe() }
    override suspend fun native(endpoint: String, args: JsonObject): JsonValue = operation(endpoint = endpoint) { client.native(endpoint, args) }
    override suspend fun openMux(): DshMux = operation(DshMux::close) { client.openMux() }
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

/** Optional feature replies are not carrier failures. Authentication and I/O still fail closed. */
internal fun isSessionDeletionRefusal(endpoint: String?, error: Exception): Boolean {
    if (endpoint != "sessionDeletion/status" && endpoint != "sessionDeletion/delete" || error !is DshFailure) return false
    return when (error.code) {
        "session-deletion/unavailable", "session-deletion/outcome-unknown", "gateway/invocation-unavailable",
        "gateway/definition-unavailable", "gateway/method-unavailable", "gateway/service-unavailable" -> true
        "gateway/unsupported" -> endpoint == "sessionDeletion/status"
        else -> false
    }
}

internal fun routeFailure(path: DshTransportPath, error: Exception): DshTransportState {
    val code = when (error) {
        is DshFailure -> error.code
        is LanPairingException -> error.code
        is RemoteRequestException -> error.code
        is DeviceProofException -> "auth.proof_invalid"
        is IOException -> when {
            error.message in setOf("lan.tls_failed", "auth.proof_invalid", "access.denied", "e2e.unsupported") -> error.message!!
            error.message?.startsWith("e2e/") == true && error !is java.io.EOFException && error !is java.net.SocketTimeoutException -> "e2e.failed"
            else -> "network.unavailable"
        }
        else -> DshRecoveryPolicy.classify(error).code
    }
    val phase = when (code) {
        "rtc.consent_required", "e2e.auth_unconfirmed" -> DshTransportPhase.AUTH_REQUIRED
        "rtc.disabled", "rtc.unsupported", "lan.tls_failed" -> DshTransportPhase.UNSUPPORTED
        "transport/closed" -> DshTransportPhase.CLOSED
        else -> when (DshRecoveryPolicy.classify(code).action) {
            DshRecoveryAction.REVOKED -> DshTransportPhase.REVOKED
            DshRecoveryAction.AUTH_REQUIRED -> DshTransportPhase.AUTH_REQUIRED
            DshRecoveryAction.MANUAL_RETRY -> DshTransportPhase.UNSUPPORTED
            DshRecoveryAction.CANCELLED -> DshTransportPhase.CLOSED
            DshRecoveryAction.BACKOFF -> DshTransportPhase.BACKOFF
        }
    }
    return DshTransportState(path, phase, code)
}

internal fun DshTransportState.allowsFallback(): Boolean = when (error) {
    "network.unavailable", "network.timeout", "wire/disconnected" -> true
    "rtc.disabled", "rtc.unavailable", "rtc.unsupported" -> path == DshTransportPath.P2P || path == DshTransportPath.TURN
    else -> false
}
