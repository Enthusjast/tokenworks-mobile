package cn.edu.ustc.tokenworks

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

internal fun PairingState?.blocksNavigation(): Boolean = this != null &&
    (busy || phase == PairingPhase.SAVE_REQUIRED || phase == PairingPhase.UNKNOWN)

internal class HostCoordinator(
    private val context: Context, private val http: LoginHttp, private val environment: LoginEnvironment,
    private val installations: AppInstallations, private val scope: CoroutineScope,
    private val mutable: MutableStateFlow<HomeState>, private val account: AccountCoordinator,
    private val sessions: SessionCoordinator, private val routes: RuntimeRoutes,
) : AccountFeatures {
    private val generation: Long get() = account.generation
    private val navigationGeneration: Long get() = sessions.navigationVersion
    private val foreground: Boolean get() = sessions.foreground
    private val session: LoginSession? get() = account.session
    private val checkpoint: AccountCheckpoint? get() = account.checkpoint
    private val configuration: LoginConfiguration? get() = account.configuration
    private val api: RemoteApi? get() = account.api
    private fun current(stamp: Long) = account.current(stamp)
    private fun now() = account.now()
    private fun renew() = account.renew()
    private fun retireSession(clearDesired: Boolean) = sessions.retireSession(clearDesired)
    private val updateLaunches = Channel<AppUpdateLaunch>(4)
    val appUpdateEvents = updateLaunches.receiveAsFlow()
    private var appUpdateFlow: AppUpdateFlow? = null
    private var appUpdateCollector: Job? = null
    private var appUpdateEventCollector: Job? = null
    private var binding: HostBindingFlow? = null
    private var bindingCollector: Job? = null
    private var bindingGeneration = 0L
    private var pendingBindingId: String? = null
    private var resumeBindingView = false
    private var pairing: PairingCoordinator? = null
    private var pairingStore: AndroidLanCredentialStore? = null
    private var pairingStoreOwnerId: String? = null
    private var pairingCollector: Job? = null
    private var rtcRoutes: AndroidRtcRoutes? = null
    private val managementFlow = HostManagementCoordinator(scope, ::managementApi,
        pendingPairing = { pairingBlocked() || withContext(Dispatchers.IO) { pairingStore?.loadPending() != null } },
        revokeLocal = ::managementRevoked, refreshDirectory = { fetchDirectory(generation) },
        connectPath = { runtime, path ->
            if (!foreground || mutable.value.phase != HomePhase.READY || pairingBlocked()) throw RemoteRequestException("lan.pending_authorization")
            if (!mutable.value.lanOnly && !account.requireFreshCredential()) throw RemoteRequestException("auth.expired")
            closeBinding(); retireAppUpdates(); leavePairing(); sessions.retireSession(false)
            routes.select(runtime, path)
            mutable.update { it.copy(selectedRuntimeId = null, selectedSessionId = null, sessions = emptyList()) }
            sessions.openRuntime(runtime)
        }, probePath = routes::probe, changeRtcConsent = routes::setRtcConsent, readRtcConsent = routes::rtcConsent)
    val management: StateFlow<HostManagementState> = managementFlow.state
    init {
        scope.launch {
            val directory = mutable.map { Triple(it.runtimes, it.pairing?.devices.orEmpty(), it.lanOnly) }.distinctUntilChanged()
                .map { (runtimes, devices, lanOnly) -> groupHostDirectory(runtimes, devices, lanOnly) }
            combine(directory, mutable.map { it.selectedRuntimeId }.distinctUntilChanged(), routes.state) { hosts, selected, transport ->
                Triple(hosts, selected, transport)
            }.collect { (hosts, selected, transport) -> managementFlow.directory(hosts, selected, transport) }
        }
    }
    private suspend fun managementApi(): HostManagementApi {
        if (mutable.value.phase != HomePhase.READY || mutable.value.lanOnly || !account.requireFreshCredential()) throw RemoteRequestException("auth.expired")
        val stamp = generation; val owner = account.localOwner() ?: throw RemoteRequestException("auth.expired")
        val resource = api ?: throw RemoteRequestException("auth.expired")
        val identity = resource.me(); current(stamp)
        if (account.localOwner()?.fingerprint != owner.fingerprint || identity.userId != owner.userId || owner.organizationId !in identity.memberships)
            throw RemoteRequestException("auth.identity_mismatch")
        return HostManagementApi(resource)
    }
    private suspend fun managementRevoked(target: ManagementTarget) {
        if (target is ManagementTarget.Device) {
            if (target.id == checkpoint?.deviceId) { account.cancel(); return }
            return
        }
        val affected = mutable.value.runtimes.filter { row -> when (target) {
            is ManagementTarget.Installation -> row.access?.installationId == target.id
            is ManagementTarget.Profile -> row.id == target.runtimeId && row.access?.installationId == target.installationId
            is ManagementTarget.Grant -> row.id == target.runtimeId
            else -> false
        } }
        affected.forEach { sessions.revokeRuntime(it); routes.forget(it.id) }
        if (target !is ManagementTarget.Grant || target.userId == checkpoint?.userId) {
            val removed = affected.mapTo(HashSet()) { it.id }
            mutable.update { it.copy(runtimes = it.runtimes.filterNot { row -> row.id in removed }, selectedRuntimeId = it.selectedRuntimeId?.takeUnless { id -> id in removed }) }
        }
    }
    fun openManagement() {
        if (!foreground || mutable.value.phase != HomePhase.READY || !leavePairing()) return
        if (!mutable.value.lanOnly && !account.requireFreshCredential()) return
        closeBinding(); retireAppUpdates(); sessions.retireSession(false)
        mutable.update { it.copy(selectedSessionId = null, sessions = emptyList(), sessionError = null) }
        managementFlow.open(!mutable.value.lanOnly)
        val home = mutable.value
        managementFlow.directory(groupHostDirectory(home.runtimes, home.pairing?.devices.orEmpty(), home.lanOnly), home.selectedRuntimeId, routes.state.value)
    }
    fun closeManagement() = managementFlow.close()
    fun refreshManagement() = managementFlow.refresh()
    fun loadManagementDevices() = managementFlow.devices()
    fun loadManagementInstallations() = managementFlow.installations()
    fun loadManagementProfiles(installationId: String) = managementFlow.profiles(installationId)
    fun loadManagementGrants(runtimeId: String) = managementFlow.grants(runtimeId)
    fun requestManagementRevoke(target: ManagementTarget) = managementFlow.requestRevoke(target)
    fun cancelManagementRevoke() = managementFlow.cancelRevoke()
    fun confirmManagementRevoke() = managementFlow.confirmRevoke()
    fun probeHostPath(runtimeId: String, path: HostConnectionPath) = managementFlow.probe(runtimeId, path)
    fun selectHostPath(runtimeId: String, path: HostConnectionPath) = managementFlow.connect(runtimeId, path)
    fun setRtcConsent(runtimeId: String, enabled: Boolean) = managementFlow.setRtcConsent(runtimeId, enabled)
    suspend fun resolveNotificationReference(runtimeId: String, sessionId: String, ownerFingerprint: String): Boolean {
        if (!foreground || mutable.value.phase != HomePhase.READY || mutable.value.lanOnly || pairingBlocked()) return false
        if (account.localOwner()?.fingerprint != ownerFingerprint || !account.requireFreshCredential()) return false
        val stamp = generation
        return try {
            fetchDirectory(stamp); current(stamp)
            if (mutable.value.error != null || account.localOwner()?.fingerprint != ownerFingerprint || mutable.value.lanOnly) return false
            val access = mutable.value.runtimeRow(runtimeId)?.access ?: return false
            if (!access.canFollow(sessionId, now()) || access.organizationId != checkpoint?.organizationId) return false
            closeManagement(); closeBinding(); retireAppUpdates(); if (!leavePairing()) return false
            sessions.openReferencedSession(runtimeId, sessionId)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    override fun fence(closeKeys: Boolean, preserveSelection: Boolean) {
        managementFlow.fence()
        sessions.fence(closeKeys, preserveSelection)
        retireBinding(preserveSelection)
        retireAppUpdates(preserveSelection)
        retirePairing()
    }
    override fun unavailable() {
        managementFlow.fence()
        sessions.retireSession(true)
        retireBinding(false)
        retireAppUpdates()
        routes.close()
        retirePairing()
    }
    override suspend fun ready(owner: CommandOwner, stamp: Long) {
        sessions.acceptedOwner(owner)
        attachPairing(stamp)
        if (!mutable.value.lanOnly) fetchDirectory(stamp)
    }
    override suspend fun clearOwner(ownerId: String, lanStore: AndroidLanCredentialStore?) {
        sessions.clearOwner(ownerId)
        val store = lanStore ?: pairingStore.takeIf { pairingStoreOwnerId == ownerId }
        withContext(NonCancellable + Dispatchers.IO) { store?.clear() }
        pairingStore = null; pairingStoreOwnerId = null
    }
    fun refresh() {
        if (mutable.value.phase != HomePhase.READY || mutable.value.refreshing) return
        if (!account.requireFreshCredential()) return
        val stamp = generation
        account.work { try { fetchDirectory(stamp) } catch (error: CancellationException) { throw error } catch (error: Exception) { account.handle(error, stamp) } }
    }
    private suspend fun fetchDirectory(stamp: Long) {
        mutable.update { home -> home.copy(refreshing = true) }
        try {
            val resource = api!!
            val identity = resource.me()
            current(stamp)
            if (identity.userId != checkpoint!!.userId) throw RemoteRequestException("auth.identity_mismatch")
            val directory = HostManagementApi(resource)
            val names = linkedMapOf<String, String>()
            val hostCursors = HashSet<String>()
            var cursor: String? = null
            do {
                val page = directory.hostDirectory(cursor); current(stamp)
                page.items.forEach { (id, alias) -> names[id] = alias }
                cursor = page.nextCursor
                if (cursor != null && !hostCursors.add(cursor)) throw ProtocolException()
            } while (cursor != null)
            val items = mutableListOf<RuntimeRow>()
            val runtimeCursors = HashSet<String>()
            cursor = null
            do {
                val page = directory.runtimeDirectory(cursor); current(stamp)
                for (access in page.items) {
                    if (access.organizationId != null && access.organizationId != configuration!!.organizationId) continue
                    if (items.any { it.id == access.id }) throw ProtocolException()
                    items += RuntimeRow(access.id, access.hostId, access.dshVersion, names[access.hostId] ?: "已授权的电脑", access)
                }
                cursor = page.nextCursor
                if (cursor != null && !runtimeCursors.add(cursor)) throw ProtocolException()
            } while (cursor != null)
            val visible = items.mapTo(HashSet()) { it.id }
            for (old in mutable.value.runtimes) {
                val next = items.firstOrNull { it.id == old.id }?.access
                if (old.id !in visible || next == null || next.hostId != old.hostId || next.installationId != old.access?.installationId ||
                    next.grantVersion != old.access?.grantVersion || next.role != old.access?.role || next.bindings != old.access?.bindings || next.expiresAt != old.access?.expiresAt) {
                    sessions.revokeRuntime(old); routes.forget(old.id)
                }
            }
            current(stamp)
            val oldSelection = mutable.value.runtimeRow(mutable.value.selectedRuntimeId)?.let { HostResourceIdentity(it.hostId, it.access?.installationId, it.id, true) }
            val selected = retainHostSelection(oldSelection, items.map { HostResourceIdentity(it.hostId, it.access?.installationId, it.id, true) })?.runtimeId
            mutable.update { home -> home.copy(runtimes = items, error = null, selectedRuntimeId = selected, pendingBindingId = pendingBindingId) }
            if (!management.value.open) sessions.directoryChanged(selected, stamp)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            current(stamp)
            if (account.authorityLost(error)) account.requireReauthentication(stamp, homeErrorCode(error)) else mutable.update { home -> home.copy(error = homeErrorCode(error)) }
        } finally {
            if (generation == stamp) {
                mutable.update { home -> home.copy(refreshing = false) }
                resumeBindingOrDirectory()
                attachAppUpdates()
            }
        }
    }

    private fun resumeBindingOrDirectory() {
        if (!foreground || mutable.value.phase != HomePhase.READY || binding != null || mutable.value.appUpdateSettings || mutable.value.pairingOpen) return
        if (resumeBindingView) { resumeBindingView = false; pendingBindingId?.let(::openBinding) }
    }

    fun foreground(active: Boolean) {
        if (foreground == active) return
        binding?.foreground(active)
        sessions.foreground(active)
        if (active && mutable.value.phase == HomePhase.READY) {
            if (mutable.value.appUpdateSettings) { attachAppUpdates(); return }
            if (binding == null && !mutable.value.pairingOpen) {
                if (resumeBindingView) { resumeBindingView = false; pendingBindingId?.let(::openBinding) }
                else sessions.resumeSelection()
            }
        }
        mutable.update { home -> home.copy(binding = binding?.state?.value) }
    }
    private fun retireAppUpdates(keepPage: Boolean = false) {
        appUpdateCollector?.cancel(); appUpdateCollector = null
        appUpdateEventCollector?.cancel(); appUpdateEventCollector = null
        appUpdateFlow?.close(); appUpdateFlow = null
        mutable.update { home -> home.copy(appUpdates = null, appUpdateSettings = keepPage && home.appUpdateSettings) }
    }
    fun openAppUpdates() {
        if (!foreground || mutable.value.phase != HomePhase.READY || !leavePairing()) return
        if (!account.requireFreshCredential()) return
        closeBinding(); resumeBindingView = false; retireSession(false)
        mutable.update { home -> home.copy(appUpdateSettings = true, selectedRuntimeId = null, selectedSessionId = null,
            sessions = emptyList(), reader = null, loadingSessions = false, sessionError = null) }
        attachAppUpdates()
    }
    private fun attachAppUpdates() {
        if (!foreground || mutable.value.phase != HomePhase.READY || !mutable.value.appUpdateSettings || appUpdateFlow != null) return
        val resource = api ?: return; val saved = checkpoint ?: return; val config = configuration ?: return; val device = saved.deviceId ?: return
        val actor = LocalDataOwner(saved.accountId, saved.organizationId, config.fingerprint, saved.userId, device)
        val stamp = generation; val view = navigationGeneration
        fun viewCurrent() {
            current(stamp)
            if (!foreground || view != navigationGeneration || mutable.value.phase != HomePhase.READY || !mutable.value.appUpdateSettings ||
                checkpoint?.accountId != actor.accountId || checkpoint?.deviceId != actor.deviceId) throw CancellationException()
            if ((session?.accessExpiresAt ?: 0) <= now()) throw RemoteRequestException("auth.expired")
        }
        val opened = AppUpdateFlow(actor, { loadAppUpdateConfiguration(context, actor, environment.appUpdateConfiguration) },
            AndroidAppUpdateAccess(context, resource, http, installations, environment.storageSlot), scope, ::viewCurrent,
            authorityFailure = { code ->
                if (generation == stamp && navigationGeneration == view && mutable.value.phase == HomePhase.READY) {
                    if (code in setOf("auth.expired", "auth.proof_invalid", "auth.reauthentication_required")) renew() else refresh()
                }
            }, diagnosticFailure = environment.appUpdateDiagnosticFailure)
        appUpdateFlow = opened
        mutable.update { home -> home.copy(appUpdates = opened.state.value) }
        appUpdateCollector = scope.launch {
            opened.state.collect { value ->
                if (generation == stamp && navigationGeneration == view && appUpdateFlow === opened && foreground && mutable.value.phase == HomePhase.READY)
                    mutable.update { home -> home.copy(appUpdates = value) }
            }
        }
        appUpdateEventCollector = scope.launch {
            opened.events.collect { event ->
                if (generation == stamp && navigationGeneration == view && appUpdateFlow === opened && foreground && mutable.value.phase == HomePhase.READY && !updateLaunches.trySend(event).isSuccess)
                    opened.launchFailed(event)
            }
        }
    }
    fun closeAppUpdates() = retireAppUpdates()
    private fun currentAppUpdate(view: String?): AppUpdateFlow? = appUpdateFlow?.takeIf {
        view != null && foreground && mutable.value.phase == HomePhase.READY && mutable.value.appUpdateSettings && it.state.value.viewId == view
    }
    fun checkAppUpdates(view: String? = mutable.value.appUpdates?.viewId) = currentAppUpdate(view)?.checkLatest()
    fun downloadAppUpdate(view: String? = mutable.value.appUpdates?.viewId, publication: String? = mutable.value.appUpdates?.release?.document?.sha256()) = currentAppUpdate(view)?.download(publication)
    fun installAppUpdate(view: String? = mutable.value.appUpdates?.viewId, attempt: String? = mutable.value.appUpdates?.attemptId,
        publication: String? = mutable.value.appUpdates?.release?.document?.sha256()) = currentAppUpdate(view)?.install(attempt, publication)
    fun requestAppInstallPermission(view: String? = mutable.value.appUpdates?.viewId) = currentAppUpdate(view)?.requestPermission()
    fun cleanupAppUpdate(view: String? = mutable.value.appUpdates?.viewId, context: String? = mutable.value.appUpdates?.cleanupToken) = currentAppUpdate(view)?.cleanup(context)
    fun cancelAppUpdateDownload(view: String? = mutable.value.appUpdates?.viewId) = currentAppUpdate(view)?.cancelDownload()
    fun consumeAppUpdateLaunch(event: AppUpdateLaunch): (() -> android.content.Intent)? {
        if (!foreground || mutable.value.phase != HomePhase.READY || !mutable.value.appUpdateSettings) return null
        if ((session?.accessExpiresAt ?: 0) <= now()) { renew(); return null }
        return appUpdateFlow?.consume(event)
    }
    fun appUpdateLaunchFailed(event: AppUpdateLaunch) = appUpdateFlow?.launchFailed(event)
    private fun retireBinding(keepResume: Boolean) {
        bindingGeneration++; bindingCollector?.cancel(); bindingCollector = null
        resumeBindingView = keepResume && binding != null && binding?.resumeId != null
        if (keepResume) binding?.resumeId?.let { pendingBindingId = it } else pendingBindingId = null
        binding?.close(); binding = null
    }
    fun openBinding(resumeId: String? = pendingBindingId) {
        if (!foreground || mutable.value.phase != HomePhase.READY || !leavePairing()) return
        if (mutable.value.lanOnly) return
        if ((session?.accessExpiresAt ?: 0) - now() <= environment.minimumValidityMillis) {
            pendingBindingId = resumeId; renew(); resumeBindingView = resumeId != null; return
        }
        retireSession(false); retireBinding(false)
        val stamp = generation; val view = bindingGeneration
        pendingBindingId = resumeId
        val opened = api!!.hostBinding(scope, resumeId, onAuthorityFailure = { code ->
            if (generation == stamp && bindingGeneration == view && mutable.value.phase == HomePhase.READY) {
                if (code in setOf("auth.expired", "auth.proof_invalid")) renew() else refresh()
            }
        }, onReady = {
            if (generation == stamp && bindingGeneration == view && mutable.value.phase == HomePhase.READY) refresh()
        })
        binding = opened
        mutable.update { home -> home.copy(binding = opened.state.value, pendingBindingId = pendingBindingId,
            selectedRuntimeId = null, selectedSessionId = null, sessions = emptyList(), reader = null, loadingSessions = false, sessionError = null) }
        bindingCollector = scope.launch {
            opened.state.collect { next ->
                if (generation != stamp || bindingGeneration != view || binding !== opened || mutable.value.phase != HomePhase.READY) return@collect
                pendingBindingId = if (next.phase in setOf(HostBindingPhase.READY, HostBindingPhase.UNAVAILABLE) || next.phase == HostBindingPhase.ENTER && next.id.isEmpty()) null else opened.resumeId
                mutable.update { home -> home.copy(binding = next, pendingBindingId = pendingBindingId) }
            }
        }
        opened.foreground(true)
    }
    fun previewBinding(id: String, code: String) = binding?.preview(id, code)
    fun confirmBinding() = binding?.confirm()
    fun queryBinding(id: String) = binding?.lookup(id)
    fun refreshBinding() = binding?.lookup()
    fun enterAnotherBinding() = binding?.enterAnother()
    fun closeBinding() {
        val opened = binding ?: return
        val resume = opened.resumeId.takeIf { opened.state.value.phase !in setOf(HostBindingPhase.READY, HostBindingPhase.UNAVAILABLE) }
        retireBinding(false); pendingBindingId = resume
        mutable.update { home -> home.copy(binding = null, pendingBindingId = resume) }
    }
    private fun attachPairing(stamp: Long) {
        val local = account.localOwner() ?: return
        val signer = account.proofSigner()
        val store = AndroidLanCredentialStore(context, local, signer.publicKey.thumbprint)
        pairingStore = store; pairingStoreOwnerId = local.fingerprint
        // The pairing scope names the trusted Relay resource, not the OIDC identity provider.
        val opened = PairingCoordinator(store, signer, scope, current = { current(stamp) },
            relayIssuer = configuration?.remoteOrigin?.takeUnless { mutable.value.lanOnly })
        pairing = opened
        routes.pairing = opened
        val rtc = AndroidRtcRoutes(context, opened, owner = { account.localOwner()?.takeIf { it.fingerprint == local.fingerprint } }, api = { api }, current = {
            current(stamp)
            if (pairing !== opened) throw CancellationException()
        })
        rtcRoutes = rtc
        routes.rtc = rtc
        pairingCollector = scope.launch {
            opened.state.collect { next ->
                if (generation != stamp || pairing !== opened) return@collect
                mutable.update { home -> home.copy(pairing = next) }
                if (mutable.value.lanOnly) {
                    val items = next.devices.map { RuntimeRow(it.runtimeId, "lan:${it.installationId}", "", "已配对的局域网电脑") }
                    val removed = mutable.value.runtimes.filter { old -> items.none { it.id == old.id } }
                    removed.forEach { sessions.revokeRuntime(it) }
                    current(stamp)
                    val selected = mutable.value.selectedRuntimeId?.takeIf { id -> items.any { it.id == id } }
                    mutable.update { home -> home.copy(runtimes = items, selectedRuntimeId = selected) }
                    if (removed.isNotEmpty()) sessions.directoryChanged(selected, stamp)
                }
            }
        }
        opened.restore()
    }
    private fun retirePairing() {
        pairingCollector?.cancel(); pairingCollector = null
        routes.rtc = null
        rtcRoutes?.close(); rtcRoutes = null
        routes.pairing = null
        pairing?.close(); pairing = null
        mutable.update { home -> home.copy(pairing = null, pairingOpen = false) }
    }
    private fun pairingBlocked(): Boolean = pairing?.state?.value.blocksNavigation()
    private fun leavePairing(): Boolean {
        if (pairingBlocked()) return false
        mutable.update { home -> home.copy(pairingOpen = false) }
        return true
    }
    fun openPairing() {
        if (!foreground || mutable.value.phase != HomePhase.READY || pairing == null || pairingBlocked()) return
        closeBinding(); retireAppUpdates(); retireSession(false)
        mutable.update { home -> home.copy(pairingOpen = true, selectedRuntimeId = null, selectedSessionId = null,
            sessions = emptyList(), sessionError = null) }
    }
    fun closePairing() { leavePairing() }
    fun importPairingDescriptor(text: String) { if (mutable.value.pairingOpen) pairing?.importDescriptor(text) }
    fun pairLan(deviceName: String) { if (mutable.value.pairingOpen) pairing?.pair(deviceName) }
    fun retryPairingSave() = pairing?.retrySave()
    fun revokePendingPairing() = pairing?.revokePending()
    fun revokeLan(runtimeId: String) { retireLanRuntime(runtimeId); pairing?.revoke(runtimeId) }
    fun forgetLan(runtimeId: String) { retireLanRuntime(runtimeId); pairing?.forget(runtimeId) }
    fun rotateDeviceKey(runtimeId: String) {
        if (!mutable.value.pairingOpen) return
        retireLanRuntime(runtimeId)
        pairing?.rotateDeviceKey(runtimeId)
    }
    fun reconcileDeviceRotation(runtimeId: String) {
        if (mutable.value.pairingOpen) pairing?.reconcileRotation(runtimeId)
    }
    private fun retireLanRuntime(runtimeId: String) {
        if (mutable.value.selectedRuntimeId == runtimeId) { sessions.retireSession(true); routes.close() }
    }
    fun back() {
        if (management.value.open) { closeManagement(); return }
        if (mutable.value.pairingOpen) { closePairing(); return }
        sessions.back()
    }
    fun close() { updateLaunches.close() }
}
