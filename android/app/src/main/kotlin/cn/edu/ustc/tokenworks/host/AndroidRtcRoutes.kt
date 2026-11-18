package cn.edu.ustc.tokenworks

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

internal interface RtcRouteProvider : AutoCloseable {
    val supported: Boolean
    fun consent(runtime: String): Boolean
    fun setConsent(runtime: String, enabled: Boolean)
    fun transport(runtime: String, path: DshTransportPath): EncryptedDshTransport
}

/** This lifecycle/consent owner has no org.webrtc field, parameter, initialization, or eager factory. */
internal class AndroidRtcRoutes(
    context: Context,
    private val pairing: PairingCoordinator,
    private val owner: () -> LocalDataOwner?,
    private val api: () -> RemoteApi?,
    private val current: () -> Unit,
) : RtcRouteProvider {
    private val application = context.applicationContext
    private val preferences = application.getSharedPreferences("rtc-owner-consent-v1", Context.MODE_PRIVATE)
    private val boundOwner = owner()?.fingerprint
    private val guard = Any()
    private var closed = false
    private var engine: RtcNativeOwner? = null
    private val consentFlags = mutableMapOf<String, AtomicBoolean>()
    private val transports = mutableSetOf<OwnedTransport>()
    private inner class OwnedTransport(private val delegate: EncryptedDshTransport) : EncryptedDshTransport by delegate {
        private val ended = AtomicBoolean()
        override fun close() {
            if (!ended.compareAndSet(false, true)) return
            try { delegate.close() } finally { synchronized(guard) { transports.remove(this) } }
        }
    }
    override val supported: Boolean by lazy { RtcPlatformAvailability.available(application.classLoader) }
    private fun consentKey(runtime: String): String {
        current(); Contract.uuid(runtime)
        val identity = owner() ?: throw RemoteRequestException("rtc.owner_required")
        if (identity.fingerprint != boundOwner) throw RemoteRequestException("rtc.owner_changed")
        return "${identity.fingerprint}:$runtime"
    }
    override fun consent(runtime: String): Boolean = synchronized(guard) {
        if (closed) return@synchronized false
        val key = consentKey(runtime)
        consentFlags[runtime]?.get() ?: preferences.getBoolean(key, false)
    }
    override fun setConsent(runtime: String, enabled: Boolean) {
        var active: RtcNativeOwner? = null
        var retiring: List<OwnedTransport> = emptyList()
        try {
            synchronized(guard) {
                if (closed) throw RemoteRequestException("transport.closed")
                val key = consentKey(runtime)
                if (enabled && !supported) throw RemoteRequestException("rtc.unsupported")
                active = engine
                if (!enabled) {
                    consentFlags.getOrPut(runtime) { AtomicBoolean(false) }.set(false)
                    retiring = transports.filter { it.runtimeId == runtime }
                }
                if (!preferences.edit().putBoolean(key, enabled).commit()) throw RemoteRequestException("storage.unavailable")
                if (enabled && consentFlags[runtime]?.get() != true) consentFlags[runtime] = AtomicBoolean(true)
            }
        } finally {
            if (!enabled) try { retiring.forEach(OwnedTransport::close) } finally { active?.closeRuntime(runtime) }
        }
    }
    override fun transport(runtime: String, path: DshTransportPath): EncryptedDshTransport = synchronized(guard) {
        require(path == DshTransportPath.P2P || path == DshTransportPath.TURN)
        if (!consent(runtime)) throw RemoteRequestException("rtc.consent_required")
        if (!supported) throw RemoteRequestException("rtc.unsupported")
        val grant = consentFlags.getOrPut(runtime) { AtomicBoolean(true) }
        fun requireConsent() {
            current()
            if (!grant.get()) throw RemoteRequestException("rtc.consent_required")
        }
        val outer = CarrierOuterDialer { receiver, purpose ->
            val active = synchronized(guard) {
                requireConsent()
                if (closed) throw RemoteRequestException("rtc.consent_required")
                engine ?: AndroidRtcEngine(application, boundOwner ?: throw RemoteRequestException("rtc.owner_required"),
                    current, { target -> pairing.pairingControl(target, api()) }).also { engine = it }
            }
            active.open(runtime, path, ::requireConsent, receiver, purpose)
        }
        transports.removeAll { it.state.value.phase == DshTransportPhase.CLOSED }
        OwnedTransport(pairing.rtcTransport(runtime, path, outer, api())).also { transports.add(it) }
    }
    fun selectedCandidates(runtime: String): List<RtcCandidateFacts> = synchronized(guard) { engine?.selectedCandidates(runtime).orEmpty() }
    override fun close() {
        var retiringTransports: List<OwnedTransport> = emptyList()
        val retiring = synchronized(guard) {
            if (closed) return
            consentFlags.values.forEach { it.set(false) }
            retiringTransports = transports.toList()
            closed = true; engine.also { engine = null }
        }
        try { retiringTransports.forEach(OwnedTransport::close) } finally { retiring?.close() }
    }
}

internal interface RtcNativeOwner : AutoCloseable {
    suspend fun open(runtime: String, path: DshTransportPath, current: () -> Unit, receiver: CarrierReceiver, purpose: String): CarrierOuterSocket
    fun closeRuntime(runtime: String)
    fun selectedCandidates(runtime: String): List<RtcCandidateFacts>
}

internal object RtcPlatformAvailability {
    fun available(loader: ClassLoader): Boolean = try {
        Class.forName("org.webrtc.PeerConnectionFactory", false, loader)
        Class.forName("org.webrtc.DataChannel", false, loader)
        true
    } catch (_: LinkageError) { false } catch (_: ClassNotFoundException) { false }
}
