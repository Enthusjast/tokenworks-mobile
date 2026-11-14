package cn.edu.ustc.tokenworks

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** No provider credentials, session content, or reference identities are exposed to settings UI. */
data class NotifyUiState(
    val configured: Boolean = false, val enabled: Boolean = false, val permissionGranted: Boolean = false,
    val registered: Boolean = false, val backgroundEnabled: Boolean = false, val backgroundActive: Boolean = false,
    val canHoldConnection: Boolean = false, val busy: Boolean = false, val error: String? = null,
)

internal class NotifyCoordinator(
    private val context: Context, private val scope: CoroutineScope, private val home: MutableStateFlow<HomeState>,
    private val account: AccountCoordinator, private val sessions: SessionCoordinator,
    private val resolve: suspend (String, String, String) -> Boolean, private val refreshAuthority: () -> Unit,
) : AutoCloseable {
    private val preferences = context.getSharedPreferences("notification-consent", Context.MODE_PRIVATE)
    private val firebase = FirebasePushClient(context)
    private var ownerId: String? = null
    private var ownerGeneration = -1L
    private var configured: PushDeployment? = null
    private var registrationJob: Job? = null
    private var watchJob: Job? = null
    private var referenceJob: Job? = null
    private var serviceSelection: Pair<String?, String?>? = null
    private var desiredBackground = false
    private var serviceActive = false
    private var serviceGeneration = -1L
    private var binding: String? = null
    private val seen = LinkedHashSet<String>()
    init {
        scope.launch {
            home.map { Triple(it.phase, it.presentationOwner?.fingerprint, it.selectedRuntimeId to it.selectedSessionId) to (account.generation to it.reader?.phase) }.distinctUntilChanged().collect {
                val owner = activeOwner()
                if (owner?.fingerprint != ownerId || ownerGeneration != account.generation) {
                    authorityLost()
                    ownerId = owner?.fingerprint
                    ownerGeneration = account.generation
                    configured = owner?.let { actor -> runCatching { context.assets.open("push-deployment.json").use { PushDeployment.parse(it.readBytes(), actor.deploymentHash, context.packageName) } }.getOrNull() }
                    binding = ownerId?.let { preferences.getString("binding:$it", null) }
                    if (owner != null && optedIn(owner) && notificationsPermitted(context) && configured != null) register()
                }
                if (connectionOwner() == null || home.value.selectedSessionId == null ||
                    (desiredBackground && (!sessions.canHoldBackground || serviceSelection != (home.value.selectedRuntimeId to home.value.selectedSessionId)))) stopBackground()
                publish()
            }
        }
    }
    private fun activeOwner(): LocalDataOwner? = account.localOwner()?.takeIf {
        home.value.phase == HomePhase.READY && !home.value.lanOnly && account.api != null &&
            home.value.presentationOwner?.fingerprint == it.fingerprint && (account.session?.accessExpiresAt ?: 0) > account.now()
    }
    private fun connectionOwner(): LocalDataOwner? = account.localOwner()?.takeIf {
        home.value.phase == HomePhase.READY && home.value.presentationOwner?.fingerprint == it.fingerprint &&
            (home.value.lanOnly || activeOwner() != null)
    }
    private fun optedIn(owner: LocalDataOwner) = preferences.getBoolean("consent:${owner.fingerprint}", false)
    private fun publish(error: String? = home.value.notify.error, busy: Boolean = registrationJob?.isActive == true) {
        val owner = activeOwner()
        val selected = sessions.canHoldBackground
        val next = NotifyUiState(configured != null, owner?.let(::optedIn) == true, notificationsPermitted(context),
            binding != null && owner != null, desiredBackground, serviceActive, connectionOwner() != null && selected && sessions.foreground, busy, error)
        if (next != home.value.notify) home.update { it.copy(notify = next) }
    }
    fun openSettings() { home.update { it.copy(notifySettingsOpen = true) }; permissionChanged() }
    fun closeSettings() { home.update { it.copy(notifySettingsOpen = false) } }
    fun permissionChanged() {
        if (!notificationsPermitted(context)) { stopBackground(); firebase.disable() }
        else if (activeOwner()?.let(::optedIn) == true && binding == null) register()
        publish()
    }
    fun setEnabled(enabled: Boolean) {
        val owner = activeOwner() ?: run { publish("push.relay_identity_required", false); return }
        if (enabled) {
            if (configured == null) { publish("push.not_configured", false); return }
            if (!notificationsPermitted(context)) { publish("push.permission_required", false); return }
            if (!preferences.edit().putBoolean("consent:${owner.fingerprint}", true).commit()) { publish("push.storage_unavailable", false); return }
            register()
        } else {
            registrationJob?.cancel(); registrationJob = null
            preferences.edit().putBoolean("consent:${owner.fingerprint}", false).commit()
            firebase.disable()
            val old = binding
            binding = null
            preferences.edit().remove("binding:${owner.fingerprint}").commit()
            cancelReferences()
            publish(null, false)
            if (old != null) scope.launch {
                try { PushRegistrationApi(account.api ?: throw RemoteRequestException("auth.expired")).revoke(old) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { publish("push.revocation_unconfirmed", false) }
            }
        }
    }
    private fun register(token: String? = null) {
        registrationJob?.cancel()
        val owner = activeOwner() ?: return
        val configuration = configured ?: return
        if (!optedIn(owner) || !notificationsPermitted(context)) return
        val resource = account.api ?: return
        val stamp = account.generation
        registrationJob = scope.launch(start = CoroutineStart.LAZY) {
            publish(null, true)
            try {
                val value = token ?: firebase.token(configuration)
                account.current(stamp)
                if (activeOwner()?.fingerprint != owner.fingerprint || !optedIn(owner) || !notificationsPermitted(context)) return@launch
                val issued = PushRegistrationApi(resource).register(owner, value)
                account.current(stamp)
                if (activeOwner()?.fingerprint != owner.fingerprint || !optedIn(owner)) {
                    withContext(NonCancellable) { runCatching { PushRegistrationApi(resource).revoke(issued) } }
                    return@launch
                }
                if (!preferences.edit().putString("binding:${owner.fingerprint}", issued).commit()) {
                    PushRegistrationApi(resource).revoke(issued)
                    throw IllegalStateException()
                }
                binding = issued
                publish(null, false)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { publish("push.registration_unavailable", false) }
        }.also { it.start() }
    }
    fun rotateToken(token: String) { if (activeOwner()?.let(::optedIn) == true) register(token) }
    fun receive(data: Map<String, String>) {
        val reference = runCatching { PushReference.parse(data, account.now()) }.getOrNull() ?: return
        scope.launch {
            val owner = withTimeoutOrNull(10_000) { home.first { it.phase == HomePhase.READY }; activeOwner() } ?: return@launch
            if (!optedIn(owner) || !reference.matches(owner, binding, account.now()) || !seen.add(reference.notificationId)) return@launch
            while (seen.size > 128) seen.remove(seen.first())
            showReferenceNotification(context, reference)
        }
    }
    fun openReference(reference: PushReference) {
        // This method is called only by an explicit MainActivity notification PendingIntent.
        referenceJob?.cancel()
        referenceJob = scope.launch {
            val owner = withTimeoutOrNull(10_000) { home.first { it.phase == HomePhase.READY }; activeOwner() }
            if (owner == null || !optedIn(owner) || !reference.matches(owner, binding, account.now())) { publish("push.reference_rejected", false); return@launch }
            sessions.notificationReferenceExpiry = reference.expiresAt
            try {
                if (!resolve(reference.runtimeId, reference.sessionId, owner.fingerprint)) publish("push.reference_rejected", false)
                else publish(null, false)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { publish("push.reference_rejected", false) }
            finally { if (sessions.notificationReferenceExpiry == reference.expiresAt) sessions.notificationReferenceExpiry = null }
        }
    }
    fun setBackground(enabled: Boolean) {
        if (!enabled) { stopBackground(); return }
        if (connectionOwner() == null || !sessions.foreground || !sessions.canHoldBackground || !notificationsPermitted(context)) {
            publish("push.background_requires_session_permission", false); return
        }
        desiredBackground = true; serviceGeneration = account.generation
        serviceSelection = home.value.selectedRuntimeId to home.value.selectedSessionId
        try { ContextCompat.startForegroundService(context, Intent(context, SelectedConnectionService::class.java)) }
        catch (_: Exception) { stopBackground(); publish("push.background_unavailable", false); return }
        publish(null)
    }
    fun canStartService() = desiredBackground && sessions.foreground && serviceGeneration == account.generation && connectionOwner() != null && notificationsPermitted(context) && sessions.canHoldBackground && serviceSelection == (home.value.selectedRuntimeId to home.value.selectedSessionId)
    fun serviceStarted() {
        if (!canStartService()) { stopBackground(); return }
        serviceActive = true; sessions.holdBackground(true)
        watchJob?.cancel()
        watchJob = scope.launch {
            while (isActive) {
                delay(15_000)
                if (connectionOwner() == null || serviceGeneration != account.generation || !notificationsPermitted(context) || home.value.selectedSessionId == null) { stopBackground(); break }
                if (!home.value.lanOnly) refreshAuthority()
            }
        }
        publish(null)
    }
    fun serviceStopped() { serviceActive = false; desiredBackground = false; watchJob?.cancel(); watchJob = null; sessions.holdBackground(false); publish() }
    fun stopBackground() {
        desiredBackground = false; serviceActive = false
        serviceSelection = null
        watchJob?.cancel(); watchJob = null
        sessions.holdBackground(false)
        context.stopService(Intent(context, SelectedConnectionService::class.java))
        publish()
    }
    private fun cancelReferences() {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.activeNotifications.filter { it.id != CONNECTION_ID }.forEach { manager.cancel(it.tag, it.id) }
        seen.clear()
    }
    fun authorityLost() {
        registrationJob?.cancel(); registrationJob = null
        referenceJob?.cancel(); referenceJob = null
        firebase.disable(); binding = null; configured = null
        stopBackground(); cancelReferences()
    }
    override fun close() { authorityLost() }
}
