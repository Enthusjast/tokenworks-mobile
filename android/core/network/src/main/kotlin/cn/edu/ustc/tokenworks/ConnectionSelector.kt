package cn.edu.ustc.tokenworks

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream

/** One binding, ordered availability-only candidates. A dispatched mutation is never replayed. */
class ConnectionSelector(
    lan: DshLanTransport?, relay: EncryptedDshTransport, installationId: String,
    rtcCandidates: List<EncryptedDshTransport> = emptyList(),
) : DshTransport {
    override val runtimeId = relay.runtimeId
    private val candidates: List<EncryptedDshTransport> = listOfNotNull(lan) + rtcCandidates + relay
    private val guard = Any()
    private val selecting = Mutex()
    @Volatile private var ended = false
    @Volatile private var selected: EncryptedDshTransport? = null
    @Volatile private var generation = 0L
    private var next = 0
    private val mutable = MutableStateFlow(DshTransportState(candidates.first().state.value.path, DshTransportPhase.CONNECTING))
    override val state: StateFlow<DshTransportState> = mutable.asStateFlow()

    init {
        lanIdentity(installationId)
        if (rtcCandidates.size > 2 || rtcCandidates.map { it.state.value.path }.distinct().size != rtcCandidates.size ||
            rtcCandidates.any { it.state.value.path !in setOf(DshTransportPath.P2P, DshTransportPath.TURN) } ||
            candidates.any { it.runtimeId != runtimeId || it.installationId != installationId || it.bindingIdentity != relay.bindingIdentity }) {
            candidates.forEach(DshTransport::close)
            throw LanPairingException("lan.identity_mismatch")
        }
    }
    private fun live() { if (ended) throw DshFailure("transport/closed") }
    private fun available(error: Exception): Boolean = when (error) {
        is LanPairingException -> error.code == "network.unavailable"
        is RemoteRequestException -> error.code in setOf("network.unavailable", "rtc.disabled", "rtc.unavailable")
        is DshFailure -> error.code == "wire/disconnected"
        else -> false
    }
    private suspend fun selection(): EncryptedDshTransport = selecting.withLock {
        live()
        selected?.let { return@withLock it }
        while (next < candidates.size) {
            val candidate = candidates[next]
            try { candidate.probeIdentity() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!available(error) || next == candidates.lastIndex) throw error
                candidate.close()
                synchronized(guard) { live(); next++; generation++ }
                continue
            }
            synchronized(guard) {
                live(); selected = candidate; generation++
                mutable.value = candidate.state.value.copy(generation = generation)
            }
            return@withLock candidate
        }
        throw RemoteRequestException("network.unavailable")
    }
    /** Retire only; opening a future candidate must never obscure an ambiguous mutation result. */
    private suspend fun retire(route: EncryptedDshTransport, error: Exception): Boolean = selecting.withLock {
        if (!available(error)) return@withLock false
        synchronized(guard) {
            if (ended) return@withLock false
            if (selected !== route) return@withLock next < candidates.size
            selected = null; next++; generation++
            mutable.value = DshTransportState(candidates.getOrNull(next)?.state?.value?.path ?: route.state.value.path,
                DshTransportPhase.CONNECTING, "transport.route_changed", generation)
        }
        route.close()
        next < candidates.size
    }
    private suspend fun <T> dispatch(route: EncryptedDshTransport, block: suspend (DshTransport) -> T): T {
        live()
        try {
            return block(route).also {
                synchronized(guard) {
                    live()
                    if (selected !== route) throw DshFailure("transport/changed")
                    mutable.value = route.state.value.copy(generation = generation)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            synchronized(guard) { if (!ended && selected === route) mutable.value = route.state.value.copy(generation = generation) }
            throw error
        }
    }
    private suspend fun <T> operation(retryRead: Boolean, block: suspend (DshTransport) -> T): T {
        while (true) {
            val route = selection()
            try { return dispatch(route, block) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (!retire(route, error) || !retryRead) throw error }
        }
    }
    override suspend fun native(endpoint: String, args: JsonObject): JsonValue =
        operation(endpoint in READ_ONLY_UNARY) { it.native(endpoint, args) }
    override suspend fun openMux(): DshMux = operation(true) { it.openMux() }
    override suspend fun connect(): DshConnection {
        val route = selection()
        val stamp = synchronized(guard) { live(); generation }
        val owned = route.connect()
        return object : DshConnection {
            private fun current() { if (ended || selected !== route || generation != stamp) throw DshFailure("transport/changed") }
            override suspend fun native(endpoint: String, args: JsonObject): JsonValue {
                current()
                try { return owned.native(endpoint, args).also { current() } }
                catch (error: Exception) { retire(route, error); throw error }
            }
            override suspend fun openMux(): DshMux {
                current()
                try { return owned.openMux().also { mux -> try { current() } catch (error: Exception) { mux.close(); throw error } } }
                catch (error: Exception) { retire(route, error); throw error }
            }
            override fun close() = owned.close()
        }
    }
    override suspend fun download(path: String, sink: OutputStream, maxBytes: Long, range: DshByteRange?): DshDownload {
        var sinkTouched = false
        val counted = object : OutputStream() {
            override fun write(value: Int) { sinkTouched = true; sink.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { if (length > 0) sinkTouched = true; sink.write(bytes, offset, length) }
            override fun flush() = sink.flush()
        }
        while (true) {
            val route = selection()
            try { return dispatch(route) { it.download(path, counted, maxBytes, range) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (!retire(route, error) || sinkTouched) throw error }
        }
    }
    override suspend fun upload(path: String, mediaType: String, source: InputStream, sizeBytes: Long): JsonValue =
        operation(false) { it.upload(path, mediaType, source, sizeBytes) }
    override fun close() {
        synchronized(guard) {
            if (ended) return
            ended = true; mutable.value = mutable.value.copy(phase = DshTransportPhase.CLOSED)
        }
        candidates.forEach(DshTransport::close)
    }
    companion object {
        internal val READ_ONLY_UNARY = setOf("session/list", "session/search", "session/page", "session/modelCatalog",
            "session/attachment", "terminal/list", "terminal/environment", "terminal/shells", "session/projections")
    }
}
