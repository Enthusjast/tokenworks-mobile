package cn.edu.ustc.tokenworks

import android.content.Context
import androidx.biometric.BiometricPrompt
import androidx.compose.ui.text.input.TextFieldValue
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/** Process-owned composition root. Feature coordinators atomically update one shared home state. */
@Singleton
class LoginController internal constructor(
    context: Context, http: LoginHttp, environment: LoginEnvironment,
    installations: AppInstallations = AppInstallations(context),
) : AutoCloseable {
    @Inject internal constructor(@ApplicationContext context: Context, http: LoginHttp, installations: AppInstallations) :
        this(context, http, LoginEnvironment({ context.assets.open("deployment.json").use { it.readBytes() } }), installations)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(HomeState())
    private val account = AccountCoordinator(context, http, environment, scope, mutable)
    private val routes = RuntimeRoutes(account, mutable)
    private val sessions = SessionCoordinator(context, scope, mutable, account, routes)
    private val hosts = HostCoordinator(context, http, environment, installations, scope, mutable, account, sessions, routes)
    private val notifications = NotifyCoordinator(context, scope, mutable, account, sessions, hosts::resolveNotificationReference, hosts::refresh)
    val state: StateFlow<HomeState> = mutable.asStateFlow()
    internal val activeTransport = routes.activeTransport
    internal val shareApi get() = account.api
    internal val usage = sessions.usage.state
    val appUpdateEvents = hosts.appUpdateEvents
    val management = hosts.management
    fun openManagement() = hosts.openManagement()
    fun closeManagement() = hosts.closeManagement()
    fun refreshManagement() = hosts.refreshManagement()
    fun loadManagementDevices() = hosts.loadManagementDevices()
    fun loadManagementInstallations() = hosts.loadManagementInstallations()
    fun loadManagementProfiles(installationId: String) = hosts.loadManagementProfiles(installationId)
    fun loadManagementGrants(runtimeId: String) = hosts.loadManagementGrants(runtimeId)
    fun requestManagementRevoke(target: ManagementTarget) = hosts.requestManagementRevoke(target)
    fun cancelManagementRevoke() = hosts.cancelManagementRevoke()
    fun confirmManagementRevoke() = hosts.confirmManagementRevoke()
    fun probeHostPath(runtimeId: String, path: HostConnectionPath) = hosts.probeHostPath(runtimeId, path)
    fun selectHostPath(runtimeId: String, path: HostConnectionPath) = hosts.selectHostPath(runtimeId, path)
    fun setRtcConsent(runtimeId: String, enabled: Boolean) = hosts.setRtcConsent(runtimeId, enabled)
    init { sessions.onAuthorityLost = notifications::authorityLost; account.start(hosts) }

    fun openNotifySettings() = notifications.openSettings()
    fun closeNotifySettings() = notifications.closeSettings()
    fun setNotificationsEnabled(enabled: Boolean) = notifications.setEnabled(enabled)
    fun setBackgroundConnectionEnabled(enabled: Boolean) = notifications.setBackground(enabled)
    fun refreshNotificationPermission() = notifications.permissionChanged()
    internal fun receiveNotification(data: Map<String, String>) = notifications.receive(data)
    internal fun rotateNotificationToken(token: String) = notifications.rotateToken(token)
    internal fun openNotificationReference(reference: PushReference) = notifications.openReference(reference)
    internal fun canStartBackgroundConnection() = notifications.canStartService()
    internal fun backgroundServiceStarted() = notifications.serviceStarted()
    internal fun backgroundServiceStopped() = notifications.serviceStopped()
    internal fun stopBackgroundConnection() = notifications.stopBackground()
    fun approveInteraction(eventId: String) = sessions.approveInteraction(eventId)
    fun retryPrompt(requestId: String) = sessions.retryPrompt(requestId)
    fun abandonPrompt(requestId: String) = sessions.abandonPrompt(requestId)
    fun rejectInteraction(eventId: String, message: String) = sessions.rejectInteraction(eventId, message)
    fun submitAnswers(eventId: String, answers: List<InteractionBridge.AnsweredQuestion>) = sessions.submitAnswers(eventId, answers)
    fun openAppUpdates() = hosts.openAppUpdates()
    fun closeAppUpdates() = hosts.closeAppUpdates()
    fun checkAppUpdates(view: String) = hosts.checkAppUpdates(view)
    fun downloadAppUpdate(view: String, publication: String?) = hosts.downloadAppUpdate(view, publication)
    fun installAppUpdate(view: String, attempt: String?, publication: String?) = hosts.installAppUpdate(view, attempt, publication)
    fun requestAppInstallPermission(view: String) = hosts.requestAppInstallPermission(view)
    fun cleanupAppUpdate(view: String, context: String) = hosts.cleanupAppUpdate(view, context)
    fun cancelAppUpdateDownload(view: String) = hosts.cancelAppUpdateDownload(view)
    fun consumeAppUpdateLaunch(event: AppUpdateLaunch) = hosts.consumeAppUpdateLaunch(event)
    fun appUpdateLaunchFailed(event: AppUpdateLaunch) = hosts.appUpdateLaunchFailed(event)
    fun login() = account.login()
    fun register(name: String) = account.register(name)
    fun cancel() { notifications.stopBackground(); account.cancel() }
    fun refresh() = hosts.refresh()
    fun foreground(active: Boolean) = hosts.foreground(active)
    fun openRuntime(id: String) = sessions.openRuntime(id)
    fun openSession(id: String) = sessions.openSession(id)
    fun closeSession() = sessions.closeSession()
    fun openCreation() = sessions.openCreation()
    fun closeCreation() = sessions.closeCreation()
    fun createSession(workspaceId: String?, agentPreset: String?) = sessions.createSession(workspaceId, agentPreset)
    fun loadOlder() = sessions.loadOlder()
    fun refreshSessions() = sessions.refreshSessions()
    fun isUnread(entry: SessionDirectoryEntry) = sessions.isUnread(entry)
    fun editDraft(value: TextFieldValue) = sessions.editDraft(value)
    fun sendDraft() = sessions.sendDraft()
    fun back() = hosts.back()
    fun openModelSelection() = sessions.openModelSelection()
    fun closeModelSelection() = sessions.closeModelSelection()
    fun selectModel(option: DshModelOption, reasoningEffort: String? = null) = sessions.selectModel(option, reasoningEffort)
    fun cancelSession() = sessions.cancelSession()
    fun renameSession(title: String) = sessions.renameSession(title)
    fun archiveSession() = sessions.archiveSession()
    fun forkSession() = sessions.forkSession()
    fun openBinding() = hosts.openBinding()
    fun previewBinding(id: String, code: String) = hosts.previewBinding(id, code)
    fun confirmBinding() = hosts.confirmBinding()
    fun queryBinding(id: String) = hosts.queryBinding(id)
    fun refreshBinding() = hosts.refreshBinding()
    fun enterAnotherBinding() = hosts.enterAnotherBinding()
    fun closeBinding() = hosts.closeBinding()
    fun browserOpened(id: Long) = account.browserOpened(id)
    fun browserUnavailable() = account.browserUnavailable()
    fun promptOpened(id: Long) = account.promptOpened(id)
    fun authenticated(id: Long, result: BiometricPrompt.AuthenticationResult) = account.authenticated(id, result)
    fun authenticationCancelled(id: Long) = account.authenticationCancelled(id)
    fun callback(uri: String) = account.callback(uri)
    fun prompt(text: String, mode: String = "queue") = sessions.prompt(text, mode)
    fun enterLanOnly() = account.enterLanOnly()
    fun openPairing() = hosts.openPairing()
    fun closePairing() = hosts.closePairing()
    fun importPairingDescriptor(text: String) = hosts.importPairingDescriptor(text)
    fun pairLan(deviceName: String) = hosts.pairLan(deviceName)
    fun retryPairingSave() = hosts.retryPairingSave()
    fun revokeLan(runtimeId: String) = hosts.revokeLan(runtimeId)
    fun revokePendingPairing() = hosts.revokePendingPairing()
    fun forgetLan(runtimeId: String) = hosts.forgetLan(runtimeId)
    fun rotateDeviceKey(runtimeId: String) = hosts.rotateDeviceKey(runtimeId)
    fun reconcileDeviceRotation(runtimeId: String) = hosts.reconcileDeviceRotation(runtimeId)
    override fun close() { notifications.close(); account.close(); hosts.close(); scope.cancel() }
}
