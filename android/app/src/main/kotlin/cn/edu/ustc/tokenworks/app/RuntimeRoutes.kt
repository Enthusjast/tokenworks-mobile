package cn.edu.ustc.tokenworks

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Owns exactly one route/Peer generation for all native clients of the selected runtime. */
internal class RuntimeRoutes(
    private val account: AccountCoordinator,
    private val home: MutableStateFlow<HomeState>,
) : AutoCloseable {
    var pairing: PairingCoordinator? = null
    var rtc: RtcRouteProvider? = null
    var onFailure: (HostResourceIdentity, DshTransportState) -> Unit = { _, _ -> }
    private var transport: DshTransport? = null
    private var transportOwner: HostResourceIdentity? = null
    private var accountGeneration = -1L
    private var collector: Job? = null
    private val observing = CoroutineScope(Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow<DshTransportState?>(null)
    val state: StateFlow<DshTransportState?> = mutable.asStateFlow()
    private val active = MutableStateFlow<DshTransport?>(null)
    val activeTransport: StateFlow<DshTransport?> = active.asStateFlow()
    private val choices = mutableMapOf<HostResourceIdentity, HostConnectionPath>()
    private var choiceGeneration = -1L

    private fun identity(runtime: String): HostResourceIdentity {
        if (choiceGeneration != account.generation) { choices.clear(); choiceGeneration = account.generation }
        val row = home.value.runtimeRow(runtime) ?: throw RemoteRequestException("access.denied")
        val installation = if (home.value.lanOnly) pairing?.state?.value?.devices?.firstOrNull { it.runtimeId == runtime }?.installationId else row.access?.installationId
        return HostResourceIdentity(row.hostId, installation, runtime, !home.value.lanOnly)
    }
    private fun paired(key: HostResourceIdentity) = key.installationId != null && pairing?.state?.value?.devices?.any {
        it.runtimeId == key.runtimeId && it.installationId == key.installationId && it.relayBound == key.relayAuthorized
    } == true
    fun rtcConsent(runtime: String): Boolean = rtc?.consent(runtime) == true
    fun setRtcConsent(runtime: String, enabled: Boolean) {
        val key = identity(runtime)
        if (!paired(key)) throw RemoteRequestException("lan.not_paired")
        val provider = rtc ?: throw RemoteRequestException("rtc.unavailable")
        provider.setConsent(runtime, enabled)
        if (!enabled && choices[key] in setOf(HostConnectionPath.P2P, HostConnectionPath.TURN)) choices.remove(key)
        close()
    }
    fun select(runtime: String, path: HostConnectionPath) {
        val key = identity(runtime)
        validatePath(key, path)
        choices[key] = path
        close()
    }
    private fun validatePath(key: HostResourceIdentity, path: HostConnectionPath) {
        if (!key.relayAuthorized && path == HostConnectionPath.RELAY) throw RemoteRequestException("access.denied")
        if (path in setOf(HostConnectionPath.LAN, HostConnectionPath.P2P, HostConnectionPath.TURN) && !paired(key)) throw RemoteRequestException("lan.not_paired")
        if (path in setOf(HostConnectionPath.P2P, HostConnectionPath.TURN) && !rtcConsent(key.runtimeId)) throw RemoteRequestException("rtc.consent_required")
        if (path in setOf(HostConnectionPath.P2P, HostConnectionPath.TURN) && rtc?.supported != true) throw RemoteRequestException("rtc.unsupported")
    }
    private fun create(key: HostResourceIdentity, path: HostConnectionPath, recordFailures: Boolean): DshTransport {
        validatePath(key, path)
        val installationId = key.installationId ?: throw RemoteRequestException("lan.identity_mismatch")
        val api = if (key.relayAuthorized) account.api ?: throw RemoteRequestException("auth.expired") else null
        val candidates = ArrayList<EncryptedDshTransport>(4)
        fun add(route: EncryptedDshTransport) {
            candidates.add(route)
            if (route.installationId != installationId) throw RemoteRequestException("lan.identity_mismatch")
        }
        try {
            if (path == HostConnectionPath.LAN || path == HostConnectionPath.AUTO && paired(key)) {
                add(pairing?.transport(key.runtimeId, api) ?: throw RemoteRequestException("lan.not_paired"))
            }
            val provider = rtc
            val autoRtc = path == HostConnectionPath.AUTO && paired(key) && rtcConsent(key.runtimeId) && provider?.supported == true
            if (path == HostConnectionPath.P2P || autoRtc) add(provider!!.transport(key.runtimeId, DshTransportPath.P2P))
            if (path == HostConnectionPath.TURN || autoRtc) add(provider!!.transport(key.runtimeId, DshTransportPath.TURN))
            if (path == HostConnectionPath.RELAY || path == HostConnectionPath.AUTO && key.relayAuthorized) {
                add(pairing?.relayTransport(checkNotNull(api), key.runtimeId) ?: throw RemoteRequestException("e2e.repair_required"))
            }
            if (candidates.isEmpty()) throw RemoteRequestException("lan.not_paired")
            return ConnectionSelector(candidates, installationId) { failed -> if (recordFailures) onFailure(key, failed) }
        } catch (error: Exception) {
            candidates.forEach(DshTransport::close)
            throw error
        }
    }
    fun route(runtime: String): DshTransport {
        val key = identity(runtime)
        transport?.takeIf { transportOwner == key && accountGeneration == account.generation }?.let { return it }
        close()
        val next = create(key, choices[key] ?: HostConnectionPath.AUTO, recordFailures = true)
        transport = next; transportOwner = key; accountGeneration = account.generation
        mutable.value = next.state.value
        active.value = next
        collector = observing.launch { next.state.collect { if (transport === next) mutable.value = it } }
        return next
    }
    /** A bounded caller-owned probe never changes selected transport, session, drafts, or outbox. */
    fun probe(runtime: String, path: HostConnectionPath): DshTransport {
        if (path == HostConnectionPath.AUTO) throw RemoteRequestException("protocol.invalid")
        return create(identity(runtime), path, recordFailures = false)
    }
    fun forget(runtime: String) {
        choices.keys.removeAll { it.runtimeId == runtime }
        if (transport?.runtimeId == runtime) close()
    }
    override fun close() { collector?.cancel(); collector = null; transport?.close(); transport = null; transportOwner = null; mutable.value = null; active.value = null }
}
