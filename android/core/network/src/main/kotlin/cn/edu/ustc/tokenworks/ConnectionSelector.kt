package cn.edu.ustc.tokenworks

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream

/** One binding, ordered availability-only candidates. A dispatched mutation is never replayed. */
class ConnectionSelector(
    candidates: List<EncryptedDshTransport>,
    installationId: String,
    private val onFailure: (DshTransportState) -> Unit = {},
) : DshTransport {
    private val candidates = candidates.sortedBy { it.state.value.path }
    private val first = requireNotNull(this.candidates.firstOrNull())
    override val runtimeId = first.runtimeId
    private class Selection(val route: EncryptedDshTransport, val generation: Long)
    private val guard = Any()
    private val selecting = Mutex()
    private var ended = false
    private var selected: Selection? = null
    private var blocked: Exception? = null
    private var generation = 0L
    private var next = 0
    private val mutable = MutableStateFlow(DshTransportState(first.state.value.path, DshTransportPhase.CONNECTING))
    override val state: StateFlow<DshTransportState> = mutable.asStateFlow()

    init {
        try {
            lanIdentity(installationId)
            if (this.candidates.distinctBy { it.state.value.path }.size != this.candidates.size ||
                this.candidates.any { it.runtimeId != runtimeId || it.installationId != installationId || it.bindingIdentity != first.bindingIdentity })
                throw LanPairingException("lan.identity_mismatch")
        } catch (error: Exception) {
            this.candidates.forEach(DshTransport::close)
            throw error
        }
    }
    // Called with guard held; only the identity probe itself needs suspension/serialization.
    private fun live() { if (ended) throw DshFailure("transport/closed") }
    private fun current(pick: Selection) {
        live()
        blocked?.let { throw it }
        if (selected !== pick) throw DshFailure("transport/changed")
    }
    private fun advance() {
        next++
        mutable.value = DshTransportState(candidates[next].state.value.path, DshTransportPhase.CONNECTING,
            "transport.route_changed", ++generation)
    }
    private fun report(failure: DshTransportState) {
        val diagnostic = failure.copy(generation = generation)
        mutable.value = diagnostic
        onFailure(diagnostic)
    }
    private suspend fun selection(): Selection {
        currentCoroutineContext().ensureActive()
        synchronized(guard) {
            live()
            blocked?.let { throw it }
            selected?.let { return it }
        }
        return selecting.withLock { selectCandidate() }
    }
    private suspend fun selectCandidate(): Selection {
        val context = currentCoroutineContext()
        while (true) {
            context.ensureActive()
            val candidate = synchronized(guard) {
                live()
                blocked?.let { throw it }
                selected?.let { return it }
                if (mutable.value.phase != DshTransportPhase.CONNECTING)
                    mutable.value = DshTransportState(candidates[next].state.value.path, DshTransportPhase.CONNECTING, generation = generation)
                candidates[next]
            }
            try { candidate.probeIdentity() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                val failure = routeFailure(candidate.state.value.path, error)
                val advanced = synchronized(guard) {
                    context.ensureActive()
                    live()
                    blocked?.let { throw it }
                    report(failure)
                    if (!failure.allowsFallback()) blocked = error
                    if (blocked != null || next == candidates.lastIndex) false
                    else { advance(); true }
                }
                if (!advanced) throw error
                candidate.close()
                continue
            }
            return synchronized(guard) {
                context.ensureActive()
                live()
                blocked?.let { throw it }
                Selection(candidate, ++generation).also {
                    selected = it
                    mutable.value = candidate.state.value.copy(generation = it.generation)
                }
            }
        }
    }
    /** Retire only; opening a future candidate must never obscure an ambiguous mutation result. */
    private suspend fun failed(pick: Selection, error: Exception): Boolean {
        if (error is CancellationException) throw error
        val context = currentCoroutineContext()
        val failure = routeFailure(pick.route.state.value.path, error)
        val fallback = failure.allowsFallback()
        val advanced = synchronized(guard) {
            context.ensureActive()
            if (ended || blocked != null) return false
            if (failure.phase in AUTHORITY_PHASES || failure.error == "lan.tls_failed" || failure.error?.startsWith("e2e.") == true) {
                report(failure)
                blocked = error
                return false
            }
            if (selected !== pick) return (fallback || failure.error == "transport/changed") && candidates[next] !== pick.route
            report(failure)
            if (!fallback) {
                return false
            }
            selected = null
            if (next == candidates.lastIndex) false else { advance(); true }
        }
        if (advanced) pick.route.close()
        return advanced
    }
    private suspend fun <T> dispatch(pick: Selection, discard: (T) -> Unit, block: suspend () -> T): T {
        val context = currentCoroutineContext()
        context.ensureActive()
        synchronized(guard) { current(pick) }
        val result = block()
        try {
            synchronized(guard) {
                context.ensureActive()
                current(pick)
                val latest = pick.route.state.value
                val published = mutable.value
                if (published.phase != latest.phase || published.error != latest.error)
                    mutable.value = latest.copy(generation = pick.generation)
            }
            return result
        } catch (error: Exception) {
            discard(result)
            throw error
        }
    }
    private suspend fun <T> operation(retryRead: Boolean, discard: (T) -> Unit = {}, block: suspend (Selection) -> T): T {
        while (true) {
            val pick = selection()
            try { return dispatch(pick, discard) { block(pick) } }
            catch (error: Exception) { if (!failed(pick, error) || !retryRead) throw error }
        }
    }
    override suspend fun native(endpoint: String, args: JsonObject): JsonValue =
        operation(endpoint in READ_ONLY_UNARY) { it.route.native(endpoint, args) }
    override suspend fun openMux(): DshMux = operation(true, DshMux::close) { it.route.openMux() }
    override suspend fun connect(): DshConnection = operation(true, DshConnection::close) { pick ->
        val owned = pick.route.connect()
        object : DshConnection {
            override suspend fun native(endpoint: String, args: JsonObject): JsonValue {
                try { return dispatch(pick, {}) { owned.native(endpoint, args) } }
                catch (error: Exception) { failed(pick, error); throw error }
            }
            override suspend fun openMux(): DshMux {
                try { return dispatch(pick, DshMux::close) { owned.openMux() } }
                catch (error: Exception) { failed(pick, error); throw error }
            }
            override fun close() = owned.close()
        }
    }
    override suspend fun download(path: String, sink: OutputStream, maxBytes: Long, range: DshByteRange?): DshDownload {
        var sinkTouched = false
        var sinkFailed = false
        val counted = object : OutputStream() {
            private inline fun toSink(write: () -> Unit) {
                sinkFailed = true
                write()
                sinkFailed = false
            }
            override fun write(value: Int) { sinkTouched = true; toSink { sink.write(value) } }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (length > 0) sinkTouched = true
                toSink { sink.write(bytes, offset, length) }
            }
            override fun flush() = toSink { sink.flush() }
        }
        while (true) {
            val pick = selection()
            try { return dispatch(pick, {}) { pick.route.download(path, counted, maxBytes, range) } }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (sinkFailed || !failed(pick, error) || sinkTouched) throw error
            }
        }
    }
    override suspend fun upload(path: String, mediaType: String, source: InputStream, sizeBytes: Long): JsonValue =
        operation(false) { it.route.upload(path, mediaType, source, sizeBytes) }
    override fun close() {
        synchronized(guard) {
            if (ended) return
            ended = true; mutable.value = mutable.value.copy(phase = DshTransportPhase.CLOSED)
        }
        candidates.forEach(DshTransport::close)
    }
    companion object {
        private val AUTHORITY_PHASES = setOf(DshTransportPhase.AUTH_REQUIRED, DshTransportPhase.REVOKED)
        internal val READ_ONLY_UNARY = setOf("session/list", "session/search", "session/page", "session/modelCatalog",
            "session/attachment", "terminal/list", "terminal/environment", "terminal/shells", "session/projections")
    }
}
