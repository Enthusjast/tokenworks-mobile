package cn.edu.ustc.tokenworks

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** A lease belongs to one immutable native client/pool generation on every physical path. */
internal class NativeRouteTransport(
    private val credentials: LanCredentials, private val path: DshTransportPath, private val api: RemoteApi?,
    private val dialer: (DshLease?) -> CarrierDialer,
) : EncryptedDshTransport {
    override val runtimeId: String get() = credentials.runtimeId
    override val installationId: String get() = credentials.installationId
    override val bindingIdentity: String get() = credentials.bindingSha256
    private val ended = AtomicBoolean()
    private val mutex = Mutex()
    private val gate = Any()
    private var lease: DshLease? = null
    @Volatile private var route: EncryptedRoute? = null
    private val mutable = MutableStateFlow(DshTransportState(path, DshTransportPhase.CONNECTING))
    override val state: StateFlow<DshTransportState> = mutable.asStateFlow()
    private fun live() { if (ended.get()) throw DshFailure("transport/closed") }
    private suspend fun current(): EncryptedRoute = mutex.withLock {
        live()
        val previous = route
        if (previous != null && !previous.retired && (lease == null || lease!!.usable())) return@withLock previous
        previous?.close(); route = null; lease = null
        val token = if (credentials.manifest.fields["relay"] is JsonObject)
            (api ?: throw DshFailure("e2e.relay_authority_required")).boundNativeLease(credentials) else null
        live()
        lateinit var created: EncryptedRoute
        val generationDialer = dialer(token)
        val client = NativeHttpClient(CarrierDialer { purpose ->
            live()
            if (route !== created || token != null && !token.usable()) throw DshFailure("transport/changed")
            generationDialer.open(purpose)
        })
        created = EncryptedRoute(runtimeId, path, client) {
            !ended.get() && route === created && (token == null || token.usable())
        }
        synchronized(gate) {
            if (ended.get()) { created.close(); throw DshFailure("transport/closed") }
            lease = token; route = created
        }
        created
    }
    private suspend fun <T> operation(block: suspend (EncryptedRoute) -> T): T {
        val selected = current()
        try { return block(selected).also { live() } }
        finally { if (!ended.get() && route === selected) mutable.value = selected.state.value }
    }
    override suspend fun native(endpoint: String, args: JsonObject) = operation { it.native(endpoint, args) }
    override suspend fun openMux() = operation { it.openMux() }
    override suspend fun probeIdentity() = operation { it.probe() }
    override suspend fun connect() = operation { it.connect() }
    override suspend fun download(path: String, sink: OutputStream, maxBytes: Long, range: DshByteRange?) = operation { it.download(path, sink, maxBytes, range) }
    override suspend fun upload(path: String, mediaType: String, source: InputStream, sizeBytes: Long) = operation { it.upload(path, mediaType, source, sizeBytes) }
    override fun close() = synchronized(gate) {
        if (ended.compareAndSet(false, true)) {
            route?.close(); route = null
            mutable.value = DshTransportState(path, DshTransportPhase.CLOSED)
        }
    }
}

class DshRelayTransport private constructor(private val route: NativeRouteTransport) : EncryptedDshTransport by route {
    constructor(api: RemoteApi, credentials: LanCredentials) : this(NativeRouteTransport(credentials, DshTransportPath.RELAY, api,
        { lease -> CarrierDialer { purpose -> api.openCarrier(credentials.runtimeId, lease ?: throw DshFailure("e2e.relay_unbound"), credentials, purpose) } }))
}
