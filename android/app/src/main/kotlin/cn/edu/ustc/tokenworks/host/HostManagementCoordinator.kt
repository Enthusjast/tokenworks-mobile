package cn.edu.ustc.tokenworks

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** View generations fence pagination, probes and mutation replies independently of account generations. */
internal class HostManagementCoordinator(
    private val scope: CoroutineScope,
    private val authorized: suspend () -> HostManagementApi,
    private val pendingPairing: suspend () -> Boolean,
    private val revokeLocal: suspend (ManagementTarget) -> Unit,
    private val refreshDirectory: suspend () -> Unit,
    private val connectPath: (String, HostConnectionPath) -> Unit,
    private val probePath: (String, HostConnectionPath) -> DshTransport,
    private val changeRtcConsent: (String, Boolean) -> Unit,
    private val readRtcConsent: (String) -> Boolean,
    private val recordProbeFailure: (HostResourceIdentity, HostConnectionPath) -> Unit = { _, _ -> },
) {
    private val mutable = MutableStateFlow(HostManagementState())
    val state: StateFlow<HostManagementState> = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private var uncertain: ManagementTarget? = null
    private val pageCursors = mutableMapOf<String, MutableSet<String>>()
    private fun acceptCursor(key: String, cursor: String?, next: String?) {
        val seen = if (cursor == null) mutableSetOf<String>().also { pageCursors[key] = it } else pageCursors.getOrPut(key) { mutableSetOf() }
        if (cursor != null) seen += cursor
        if (next != null && next in seen) throw ProtocolException()
    }
    fun fence() { generation++; job?.cancel(); job = null; uncertain = null; pageCursors.clear(); mutable.value = HostManagementState() }
    fun open(relay: Boolean) {
        val pending = uncertain; fence(); uncertain = pending
        mutable.value = HostManagementState(open = true, relayManagementAvailable = relay, mutationUnknown = pending != null)
        if (relay) refresh()
    }
    fun close() { val pending = uncertain; fence(); uncertain = pending }
    fun directory(hosts: List<ManagedHostGroup>, selected: String?, transport: DshTransportState?,
        failures: Map<HostResourceIdentity, Map<HostConnectionPath, Long>> = emptyMap(), rtcSupported: Boolean = false) {
        val old = mutable.value.hosts.flatMap { it.runtimes }.associateBy { it.identity }
        mutable.update { it.copy(hosts = hosts.map { group -> group.copy(runtimes = group.runtimes.map { row ->
            row.copy(status = old[row.identity]?.status.orEmpty(), connectionFailures = failures[row.identity].orEmpty())
        }) }, selectedRuntimeId = selected, transport = transport,
            rtcSupported = rtcSupported,
            rtcConsents = hosts.flatMap { it.runtimes }.associate { it.identity.runtimeId to readRtcConsent(it.identity.runtimeId) }) }
    }
    fun setRtcConsent(runtime: String, enabled: Boolean) {
        if (!state.value.open || state.value.busy) return
        val row = state.value.hosts.flatMap { it.runtimes }.singleOrNull { it.identity.runtimeId == runtime && HostConnectionPath.P2P in it.paths } ?: return
        if (!row.identity.relayAuthorized && state.value.relayManagementAvailable) return
        try {
            changeRtcConsent(runtime, enabled)
            mutable.update { it.copy(rtcConsents = it.rtcConsents + (runtime to readRtcConsent(runtime)), notice = if (enabled) "已允许此所有者的点对点与 TURN 路径；下次连接重新检测。" else "已停止此运行配置的点对点与 TURN 连接。") }
        } catch (error: Exception) { mutable.update { it.copy(error = homeErrorCode(error)) } }
    }
    private fun launch(relay: Boolean = true, action: suspend () -> Unit) {
        if (!state.value.open || state.value.busy || (relay && !state.value.relayManagementAvailable)) return
        val view = generation
        mutable.update { it.copy(busy = true, error = null, notice = null) }
        job = scope.launch {
            try { action(); ensureActive(); if (generation != view) throw CancellationException() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (generation == view) mutable.update { it.copy(error = homeErrorCode(error)) } }
            finally { if (generation == view) mutable.update { it.copy(busy = false) } }
        }
    }
    fun refresh() = launch {
        val api = authorized()
        val devices = api.devices(); currentCoroutineContext().ensureActive()
        val installations = api.installations(); currentCoroutineContext().ensureActive()
        refreshDirectory(); currentCoroutineContext().ensureActive()
        val pending = uncertain
        val reconciled = pending == null || reconcile(api, pending)
        currentCoroutineContext().ensureActive()
        if (reconciled) uncertain = null
        acceptCursor("devices", null, devices.nextCursor); acceptCursor("installations", null, installations.nextCursor)
        mutable.update { it.copy(devices = devices.items, devicesCursor = devices.nextCursor,
            installations = installations.items, installationsCursor = installations.nextCursor,
            profiles = emptyList(), profileInstallation = null, profilesCursor = null,
            grants = emptyList(), grantRuntime = null, grantsCursor = null, mutationUnknown = !reconciled,
            notice = if (pending != null && reconciled) "已重新读取目标的服务端状态；未自动重试撤销。" else null) }
    }
    private suspend fun <T> findState(load: suspend (String?) -> ManagementPage<T>, match: (T) -> String?): Boolean {
        var cursor: String? = null
        val seen = HashSet<String>()
        do {
            val page = load(cursor); currentCoroutineContext().ensureActive()
            val target = page.items.firstNotNullOfOrNull(match)
            if (target != null) return target in setOf("active", "pending", "revoked", "expired", "inactive")
            cursor = page.nextCursor
            if (cursor != null && !seen.add(cursor)) throw ProtocolException()
        } while (cursor != null)
        return false
    }
    private suspend fun reconcile(api: HostManagementApi, target: ManagementTarget): Boolean = when (target) {
        is ManagementTarget.Device -> findState(api::devices) { it.state.takeIf { _ -> it.id == target.id } }
        is ManagementTarget.Installation -> findState(api::installations) { it.state.takeIf { _ -> it.id == target.id } }
        is ManagementTarget.Profile -> findState({ api.profiles(target.installationId, it) }) { it.state.takeIf { _ -> it.id == target.runtimeId } }
        is ManagementTarget.Grant -> findState({ api.grants(target.runtimeId, it) }) { it.state.takeIf { _ -> it.userId == target.userId } }
    }
    fun devices() = launch {
        val cursor = state.value.devicesCursor ?: return@launch
        val page = authorized().devices(cursor); currentCoroutineContext().ensureActive()
        acceptCursor("devices", cursor, page.nextCursor)
        mutable.update { it.copy(devices = (it.devices + page.items).distinctBy { row -> row.id }, devicesCursor = page.nextCursor) }
    }
    fun installations() = launch {
        val cursor = state.value.installationsCursor ?: return@launch
        val page = authorized().installations(cursor); currentCoroutineContext().ensureActive()
        acceptCursor("installations", cursor, page.nextCursor)
        mutable.update { it.copy(installations = (it.installations + page.items).distinctBy { row -> row.id }, installationsCursor = page.nextCursor) }
    }
    fun profiles(id: String) = launch {
        val same = state.value.profileInstallation == id
        val cursor = if (same) state.value.profilesCursor ?: return@launch else null
        val page = authorized().profiles(id, cursor); currentCoroutineContext().ensureActive()
        acceptCursor("profiles:$id", cursor, page.nextCursor)
        mutable.update { it.copy(profileInstallation = id, profiles = ((if (same) it.profiles else emptyList()) + page.items).distinctBy { row -> row.id }, profilesCursor = page.nextCursor) }
    }
    fun grants(id: String) = launch {
        val same = state.value.grantRuntime == id
        val cursor = if (same) state.value.grantsCursor ?: return@launch else null
        val page = authorized().grants(id, cursor); currentCoroutineContext().ensureActive()
        acceptCursor("grants:$id", cursor, page.nextCursor)
        mutable.update { it.copy(grantRuntime = id, grants = ((if (same) it.grants else emptyList()) + page.items).distinctBy { row -> row.userId }, grantsCursor = page.nextCursor) }
    }
    fun requestRevoke(target: ManagementTarget) {
        if (state.value.open && !state.value.busy && !state.value.mutationUnknown && state.value.relayManagementAvailable)
            mutable.update { it.copy(confirmation = target) }
    }
    fun cancelRevoke() { if (!state.value.busy) mutable.update { it.copy(confirmation = null) } }
    fun confirmRevoke() = launch {
        val target = state.value.confirmation ?: return@launch
        if (state.value.mutationUnknown) return@launch
        if (pendingPairing()) throw RemoteRequestException("lan.pending_authorization")
        val api = authorized()
        // Retire local runtime capabilities before dispatch; ambiguous writes cannot revive an outbox or draft.
        if (target !is ManagementTarget.Device) revokeLocal(target)
        currentCoroutineContext().ensureActive()
        // Do not offer retry after a dispatched DELETE with an ambiguous response.
        uncertain = target
        mutable.update { it.copy(confirmation = null, mutationUnknown = true) }
        api.revoke(target)
        currentCoroutineContext().ensureActive()
        uncertain = null
        if (target is ManagementTarget.Device) revokeLocal(target)
        currentCoroutineContext().ensureActive()
        mutable.update { it.copy(mutationUnknown = false, notice = "撤销已确认；已重新核对授权与本地状态。") }
        refreshDirectory()
        val devices = api.devices(); currentCoroutineContext().ensureActive()
        val installations = api.installations(); currentCoroutineContext().ensureActive()
        acceptCursor("devices", null, devices.nextCursor); acceptCursor("installations", null, installations.nextCursor)
        mutable.update { it.copy(devices = devices.items, devicesCursor = devices.nextCursor,
            installations = installations.items, installationsCursor = installations.nextCursor,
            profiles = emptyList(), profileInstallation = null, profilesCursor = null,
            grants = emptyList(), grantRuntime = null, grantsCursor = null) }
    }
    fun probe(runtime: String, path: HostConnectionPath) = launch(relay = false) {
        val row = state.value.hosts.flatMap { it.runtimes }.singleOrNull { it.identity.runtimeId == runtime && path in it.paths } ?: return@launch
        if (!row.identity.relayAuthorized && state.value.relayManagementAvailable) throw RemoteRequestException("lan.separate_authorization")
        if (path == HostConnectionPath.AUTO) return@launch
        status(row.identity, path, HostPathStatus(HostReachability.CHECKING))
        var route: DshTransport? = null
        try {
            withTimeout(if (path in setOf(HostConnectionPath.P2P, HostConnectionPath.TURN)) 40_000 else 4_000) {
                if (row.identity.relayAuthorized) authorized()
                route = probePath(runtime, path)
                DshSessionClient(route!!).sessions()
            }
            currentCoroutineContext().ensureActive()
            status(row.identity, path, HostPathStatus(HostReachability.REACHABLE))
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            recordProbeFailure(row.identity, path)
            status(row.identity, path, HostPathStatus(HostReachability.UNREACHABLE, "network.timeout"))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val code = (error as? LanPairingException)?.code ?: homeErrorCode(error)
            val authority = when (route?.state?.value?.phase) {
                DshTransportPhase.AUTH_REQUIRED, DshTransportPhase.REVOKED -> true
                else -> when (DshRecoveryPolicy.classify(code).action) {
                    DshRecoveryAction.AUTH_REQUIRED, DshRecoveryAction.REVOKED -> true
                    else -> false
                }
            }
            recordProbeFailure(row.identity, path)
            status(row.identity, path, HostPathStatus(if (authority) HostReachability.AUTH_REQUIRED else HostReachability.UNREACHABLE, code))
        } finally { route?.close() }
    }
    private fun status(key: HostResourceIdentity, path: HostConnectionPath, status: HostPathStatus) {
        mutable.update { it.copy(hosts = it.hosts.map { group -> group.copy(runtimes = group.runtimes.map { row ->
            if (row.identity == key) row.copy(status = row.status + (path to status)) else row
        }) }) }
    }
    fun connect(runtime: String, path: HostConnectionPath) {
        if (!state.value.open || state.value.busy) return
        val row = state.value.hosts.flatMap { it.runtimes }.singleOrNull { it.identity.runtimeId == runtime && path in it.paths } ?: return
        if (!row.identity.relayAuthorized && state.value.relayManagementAvailable) { mutable.update { it.copy(error = "lan.separate_authorization") }; return }
        try { connectPath(runtime, path); close() } catch (error: Exception) { mutable.update { it.copy(error = homeErrorCode(error)) } }
    }
}
