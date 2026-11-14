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
    private var transport: DshTransport? = null
    private var transportOwner: HostResourceIdentity? = null
    private var accountGeneration = -1L
    private var collector: Job? = null
    private val observing = CoroutineScope(Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow<DshTransportState?>(null)
    val state: StateFlow<DshTransportState?> = mutable.asStateFlow()
    private val choices = mutableMapOf<HostResourceIdentity, HostConnectionPath>()
    private var choiceGeneration = -1L

    private fun identity(runtime: String): HostResourceIdentity {
        if (choiceGeneration != account.generation) { choices.clear(); choiceGeneration = account.generation }
        val row = home.value.runtimeRow(runtime) ?: throw RemoteRequestException("access.denied")
        val installation = if (home.value.lanOnly) pairing?.state?.value?.devices?.firstOrNull { it.runtimeId == runtime }?.installationId else row.access?.installationId
        return HostResourceIdentity(row.hostId, installation, runtime, !home.value.lanOnly)
    }
    private fun paired(key: HostResourceIdentity) = key.installationId != null && pairing?.state?.value?.devices?.any {
        it.runtimeId == key.runtimeId && it.installationId == key.installationId
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
    private fun create(key: HostResourceIdentity, path: HostConnectionPath): DshTransport {
        validatePath(key, path)
        val allocated = mutableListOf<DshTransport>()
        fun <T : EncryptedDshTransport> same(route: T): T {
            allocated.add(route)
            if (route.installationId != key.installationId) throw RemoteRequestException("lan.identity_mismatch")
            return route
        }
        try {
            if (path == HostConnectionPath.P2P || path == HostConnectionPath.TURN) return same(
                rtc!!.transport(key.runtimeId, if (path == HostConnectionPath.P2P) DshTransportPath.P2P else DshTransportPath.TURN))
            val lan = if (paired(key) && path != HostConnectionPath.RELAY) pairing?.transport(key.runtimeId, account.api)?.let(::same) else null
            if (path == HostConnectionPath.LAN) return lan ?: throw RemoteRequestException("lan.not_paired")
            val relay = if (key.relayAuthorized) {
                val api = account.api ?: throw RemoteRequestException("auth.expired")
                same(pairing?.relayTransport(api, key.runtimeId) ?: throw RemoteRequestException("e2e.repair_required"))
            } else null
            if (path == HostConnectionPath.RELAY) return relay ?: throw RemoteRequestException("access.denied")
            val candidates = if (paired(key) && rtcConsent(key.runtimeId) && rtc?.supported == true) listOf(
                same(rtc!!.transport(key.runtimeId, DshTransportPath.P2P)),
                same(rtc!!.transport(key.runtimeId, DshTransportPath.TURN))) else emptyList()
            if (relay != null) return ConnectionSelector(lan, relay, key.installationId ?: throw RemoteRequestException("lan.identity_mismatch"), candidates)
            if (candidates.isNotEmpty()) return ConnectionSelector(lan, candidates.last(), key.installationId!!, candidates.dropLast(1))
            return lan ?: throw RemoteRequestException("lan.not_paired")
        } catch (error: Exception) { allocated.forEach(DshTransport::close); throw error }
    }
    fun route(runtime: String): DshTransport {
        val key = identity(runtime)
        transport?.takeIf { transportOwner == key && accountGeneration == account.generation }?.let { return it }
        close()
        val next = create(key, choices[key] ?: if (key.relayAuthorized || rtcConsent(runtime)) HostConnectionPath.AUTO else HostConnectionPath.LAN)
        transport = next; transportOwner = key; accountGeneration = account.generation
        mutable.value = next.state.value
        collector = observing.launch { next.state.collect { if (transport === next) mutable.value = it } }
        return next
    }
    /** A bounded caller-owned probe never changes selected transport, session, drafts, or outbox. */
    fun probe(runtime: String, path: HostConnectionPath): DshTransport {
        if (path == HostConnectionPath.AUTO) throw RemoteRequestException("protocol.invalid")
        return create(identity(runtime), path)
    }
    fun forget(runtime: String) {
        choices.keys.removeAll { it.runtimeId == runtime }
        if (transport?.runtimeId == runtime) close()
    }
    override fun close() { collector?.cancel(); collector = null; transport?.close(); transport = null; transportOwner = null; mutable.value = null }
}
