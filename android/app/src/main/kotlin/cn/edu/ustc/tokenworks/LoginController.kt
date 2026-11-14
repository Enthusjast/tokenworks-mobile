package cn.edu.ustc.tokenworks

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import java.io.FileNotFoundException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class HomePhase { LOADING, UNCONFIGURED, INVALID_CONFIGURATION, SIGNED_OUT, RECOVERING, RECOVERY_ERROR, REAUTHENTICATE, RENEWING, CLEARING, STARTING, BROWSER, EXCHANGING, ENROLL, PREPARING, AUTHENTICATING, REGISTERING, RECONCILING, REGISTRATION_UNKNOWN, READY }
class BrowserLaunch(val id: Long, val uri: Uri)
class BiometricLaunch(val id: Long, val operation: AuthenticatedKeyOperation<*>, val title: String = "确认登记此设备", val subtitle: String = "将这部手机的独立密钥登记到当前账号")
class DocumentPickLaunch(val id: String, val purpose: DocumentPickPurpose = DocumentPickPurpose.FILE, val output: Uri? = null)
class ExportSaveLaunch(val id: String, val name: String) { override fun toString() = "ExportSaveLaunch" }
enum class ApprovalUiStage { IDLE, PREPARING, AUTHENTICATING, SENDING }
data class RuntimeRow(val id: String, val hostId: String, val version: String, val hostAlias: String, val access: RuntimeDirectoryEntry? = null)
data class HomeState(
    val phase: HomePhase = HomePhase.LOADING, val issuer: String = "", val error: String? = null,
    val subject: String = "", val deviceId: String = "", val runtimes: List<RuntimeRow> = emptyList(),
    val presentationOwner: CommandOwner? = null,
    val browser: BrowserLaunch? = null, val biometric: BiometricLaunch? = null, val authenticationId: Long? = null,
    val securitySetupRequired: Boolean = false, val refreshing: Boolean = false, val deviceName: String? = null,
    val selectedRuntimeId: String? = null, val selectedSessionId: String? = null,
    val sessions: List<SessionDirectoryEntry> = emptyList(), val loadingSessions: Boolean = false, val sessionError: String? = null,
    val reader: SessionReadState? = null,
    val modelCatalog: DshModelCatalog? = null, val modelSelectionOpen: Boolean = false, val modelBusy: Boolean = false, val modelError: String? = null,
    val sessionActionError: String? = null, val submitting: Boolean = false,
    val appUpdates: AppUpdateUiState? = null, val appUpdateSettings: Boolean = false,
    val binding: HostBindingState? = null, val pendingBindingId: String? = null,
    val creation: SessionCreationState? = null,
    val composer: MessageComposerState? = null,
    val interactions: List<PendingInteraction> = emptyList(),
    val interactionError: String? = null,
    val terminal: TerminalWatcher? = null,
    val terminals: List<TerminalInfo> = emptyList(),
    /** First agent id seen in follow records; terminal/attach requires it. */
    val agentId: String? = null,
)

internal fun HomeState.runtimeRow(id: String?): RuntimeRow? = runtimes.firstOrNull { it.id == id }

/** Creation is one native call; `CREATING` only covers the round trip. */
enum class SessionCreationPhase { READY, CREATING, FAILED }
data class SessionCreationState(val phase: SessionCreationPhase = SessionCreationPhase.READY, val error: String? = null)


internal class LoginEnvironment(
    val configuration: () -> ByteArray,
    val storageSlot: String = "active",
    val ledgerName: String = "pending_device_keys",
    val now: () -> Long = System::currentTimeMillis,
    val refreshLeadMillis: Long = 30_000,
    val minimumValidityMillis: Long = 60_000,
    val appUpdateConfiguration: (() -> ByteArray)? = null,
    val appUpdateDiagnosticFailure: ((Exception) -> Unit)? = null,
)

@Singleton
class LoginController internal constructor(private val context: Context, private val http: LoginHttp, private val environment: LoginEnvironment, private val installations: AppInstallations = AppInstallations(context)) : AutoCloseable {
    @Inject internal constructor(@ApplicationContext context: Context, http: LoginHttp, installations: AppInstallations) : this(context, http, LoginEnvironment({ context.assets.open("deployment.json").use { it.readBytes() } }), installations)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(HomeState())
    private val drafts = ConversationDrafts()
    val state: StateFlow<HomeState> = mutable.asStateFlow()
    private val updateLaunches = Channel<AppUpdateLaunch>(4)
    val appUpdateEvents = updateLaunches.receiveAsFlow()
    @Volatile private var generation = 0L
    private val persistence = AccountPersistence(context, environment.storageSlot, environment.ledgerName) { it == generation && scope.isActive }
    private var configuration: LoginConfiguration? = null
    private var nextAuthenticationId = 0L
    private var job: Job? = null
    private var deadline: Job? = null
    private var reference: ScopeReference? = null
    private var owner: DeviceKeyStore? = null
    private var proof: DeviceKeyHandle? = null
    private var approval: DeviceKeyHandle? = null
    private var checkpoint: AccountCheckpoint? = null
    private var pending: PendingLogin? = null
    private var session: LoginSession? = null
    private var api: RemoteApi? = null
    private var registration: RemoteApi.RegistrationRequest? = null
    private var operation: AuthenticatedKeyOperation<DeviceRegistrationProof>? = null
    private var cleanupPending = false
    private var foreground = false
    private var navigationGeneration = 0L
    private var navigationJob: Job? = null
    private var readerJob: Job? = null
    private var reader: DshSessionReader? = null
    private var readMarks: SessionReadMarks? = null
    private var watcher: InteractionWatcher? = null
    private var terminal: TerminalWatcher? = null
    private val outbox = PromptOutbox()
    private var watcherJob: Job? = null
    private var appUpdateFlow: AppUpdateFlow? = null
    private var appUpdateCollector: Job? = null
    private var appUpdateEventCollector: Job? = null
    private var desired: Pair<String, String?>? = null
    private var knownSelection = false
    private var commandCollector: Job? = null
    private var changesCollector: Job? = null
    private var commandJob: Job? = null
    private var binding: HostBindingFlow? = null
    private var bindingCollector: Job? = null
    private var bindingGeneration = 0L
    private var pendingBindingId: String? = null
    private var resumeBindingView = false
    private enum class Source { BROWSER, CACHE, REFRESH }

    init {
        work {
            try { configuration = withContext(Dispatchers.IO) { LoginConfiguration.parse(environment.configuration()) } }
            catch (_: FileNotFoundException) { mutable.value = HomeState(HomePhase.UNCONFIGURED); return@work }
            catch (_: Exception) { mutable.value = HomeState(HomePhase.INVALID_CONFIGURATION); return@work }
            restore(generation)
        }
    }

    private fun work(block: suspend CoroutineScope.() -> Unit) {
        // Assign before execution: an immediate failure can start cleanup as another task.
        val next = scope.launch(start = CoroutineStart.LAZY, block = block)
        job = next
        next.start()
    }

    private fun current(stamp: Long) { if (generation != stamp || !scope.isActive) throw CancellationException() }
    private fun now() = environment.now()

    /** Capture returned owners inside NonCancellable so cancellation cannot drop an unclosed key owner. */
    private suspend fun open(stamp: Long, action: suspend () -> OpenAccount?): Boolean {
        var opened: OpenAccount? = null
        try {
            withContext(NonCancellable) { opened = action() }
            current(stamp)
            val result = opened ?: return false
            owner?.close()
            reference = result.reference; owner = result.owner; proof = result.proof; approval = result.approval
            checkpoint = result.checkpoint
            opened = null
            return true
        } finally { opened?.close() }
    }

    private suspend fun persist(next: AccountCheckpoint, stamp: Long) {
        persistence.save(next, stamp)
        current(stamp)
        checkpoint = next
    }
    private suspend fun <T> beginKeyOperation(stamp: Long, begin: () -> AuthenticatedKeyOperation<T>): AuthenticatedKeyOperation<T> {
        var opened: AuthenticatedKeyOperation<T>? = null
        try {
            withContext(NonCancellable + Dispatchers.Default) { opened = begin() }
            current(stamp)
            return opened!!.also { opened = null }
        } finally { opened?.close() }
    }

    private suspend fun restore(stamp: Long) {
        val config = configuration ?: return
        mutable.value = HomeState(HomePhase.RECOVERING, config.issuer)
        try {
            if (!open(stamp) { persistence.load(config, stamp, now()) }) {
                mutable.value = HomeState(HomePhase.SIGNED_OUT, config.issuer)
                return
            }
            val saved = checkpoint!!
            if (saved.refresh != RefreshCheckpoint.READY) { showReauthentication("login.reauthentication"); return }
            val discovery = http.discovery(config)
            val verified = try { TokenVerifier.revalidateStored(saved.session, config, http.keys(discovery), now()) }
            catch (error: LoginException) {
                if (saved.session.hasRefreshToken()) {
                    if (renewCredential(stamp, discovery)) install(session!!, Source.REFRESH, stamp)
                    return
                }
                requireReauthentication(stamp, "login.reauthentication")
                return
            }
            install(verified, Source.CACHE, stamp)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { handle(error, stamp) }
    }

    fun login() {
        val config = configuration ?: return
        if (mutable.value.phase == HomePhase.RECOVERY_ERROR) {
            if (cleanupPending) { clearAccount(null); return }
            val stamp = fence(closeKeys = true)
            checkpoint = null
            work { restore(stamp) }
            return
        }
        if (mutable.value.phase !in setOf(HomePhase.SIGNED_OUT, HomePhase.REAUTHENTICATE)) return
        val saved = checkpoint
        val forceAuthentication = mutable.value.error == "approval.assurance_required"
        val stamp = fence(closeKeys = false)
        mutable.value = HomeState(HomePhase.STARTING, config.issuer)
        work {
            try {
                if (saved != null) {
                    // A cancelled/restarted browser reauthentication must not resume an old rotating grant.
                    persist(saved.requireReauthentication(now()), stamp)
                    if (proof == null) open(stamp) { persistence.load(config, stamp, now()) }
                }
                val discovery = http.discovery(config)
                if (saved == null) {
                    val local = ScopeReference(UUID.randomUUID().toString(), config.organizationId)
                    open(stamp) { persistence.createProof(local, stamp) }
                }
                current(stamp)
                val attempt = PendingLogin(discovery, proof!!.thumbprint, now())
                pending = attempt
                val browserRequest = AuthorizationRequest.Builder(
                    AuthorizationServiceConfiguration(discovery.authorizationEndpoint.toUri(), discovery.tokenEndpoint.toUri()),
                    config.clientId, ResponseTypeValues.CODE, config.redirectUri.toUri(),
                ).setScopes(config.scopes).setState(attempt.state).setNonce(attempt.nonce)
                    .setCodeVerifier(attempt.verifierForAuthorizationRequest(), attempt.codeChallenge, "S256")
                    .setAdditionalParameters(mapOf("resource" to config.resource, "dpop_jkt" to proof!!.thumbprint))
                    .apply { if (forceAuthentication) setPrompt("login") }.build()
                mutable.value = HomeState(HomePhase.BROWSER, config.issuer, browser = BrowserLaunch(stamp, browserRequest.toUri()))
                deadline = scope.launch { delay(600_000); if (generation == stamp && pending != null) browserFailed("login.expired") }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (generation == stamp) browserFailed(code(error)) }
        }
    }

    fun browserOpened(id: Long) { if (mutable.value.browser?.id == id) mutable.value = mutable.value.copy(browser = null) }
    fun browserUnavailable() { if (pending != null) browserFailed("login.browser_unavailable") }

    fun callback(uri: String) {
        val attempt = pending ?: return
        val stamp = generation
        val exchange = try { attempt.acceptCallback(uri, now()) }
        catch (error: LoginException) {
            if (error.failure in setOf(LoginFailure.INVALID_RESPONSE, LoginFailure.IDENTITY)) mutable.value = mutable.value.copy(error = "login.invalid_callback")
            else browserFailed(code(error))
            return
        }
        pending = null; deadline?.cancel()
        mutable.value = mutable.value.copy(phase = HomePhase.EXCHANGING, browser = null, error = null)
        work {
            try {
                val signer = proof!!.proofSigner()
                val raw = http.tokenEndpoint(attempt.discovery, signer).exchange(exchange)
                val verified = TokenVerifier.authorizationCodeResponse(raw, exchange, http.keys(attempt.discovery), now())
                current(stamp)
                val old = checkpoint
                if (old != null && verified.subject != old.session.subject) { browserFailed("login.account_mismatch"); return@work }
                install(verified, Source.BROWSER, stamp)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (generation == stamp) {
                    if (checkpoint != null && session != null) handle(error, stamp)
                    else if (authorityLost(error)) clearAccount(code(error)) else browserFailed(code(error))
                }
            }
        }
    }

    private suspend fun install(verified: LoginSession, source: Source, stamp: Long) {
        val config = configuration!!
        val resource = http.resource(config, verified, proof!!.proofSigner())
        val identity = resource.me()
        current(stamp)
        val saved = checkpoint
        if (saved != null && identity.userId != saved.userId) throw RemoteRequestException("auth.identity_mismatch")
        val next = when (source) {
            Source.BROWSER -> saved?.authorized(verified, identity.userId, now()) ?: AccountCheckpoint.create(reference!!.account, config, identity.userId, verified, now())
            Source.CACHE -> saved!!.revalidated(verified, identity.userId, now())
            Source.REFRESH -> saved!!
        }
        session = verified; api = resource
        try { persist(next, stamp) }
        catch (error: VaultException) {
            // A new phone may still need the user's secure-lock setup. Never POST enrollment until saved.
            if (saved == null && source == Source.BROWSER && error.failure == VaultFailure.LOCK_REQUIRED) {
                checkpoint = next
                showEnrollment(code(error), setup = true)
                scheduleRenewal(stamp, verified)
                return
            }
            throw error
        }
        reconcileAccount(stamp)
    }

    private suspend fun reconcileAccount(stamp: Long) {
        current(stamp)
        val saved = checkpoint!!
        val key = approval
        if (key != null) {
            val device = api!!.currentDevice(PublicProofKey(key.publicJwk))
            current(stamp)
            if (device != null) {
                persist(saved.deviceAccepted(device.deviceId, device.keyVersion, now()), stamp)
                val accepted = checkpoint!!
                val presentationOwner = CommandOwner(accepted.accountId, accepted.organizationId, configuration!!.fingerprint, accepted.userId, accepted.deviceId!!)
                readMarks = SessionReadMarks(context, presentationOwner.fingerprint)
                mutable.value = HomeState(HomePhase.READY, session!!.issuer, subject = session!!.subject, deviceId = device.deviceId, presentationOwner = presentationOwner)
                scheduleRenewal(stamp, session!!)
                fetchDirectory(stamp)
                return
            }
        }
        persist(saved.enrollmentNotFound(now()), stamp)
        showEnrollment()
        scheduleRenewal(stamp, session!!)
    }

    private fun showEnrollment(error: String? = null, setup: Boolean = false) {
        mutable.value = HomeState(HomePhase.ENROLL, configuration!!.issuer, error, subject = checkpoint!!.session.subject,
            securitySetupRequired = setup, deviceName = checkpoint!!.deviceName)
    }

    fun register(name: String) {
        if (mutable.value.phase == HomePhase.REGISTRATION_UNKNOWN) {
            val stamp = generation
            mutable.value = mutable.value.copy(phase = HomePhase.RECONCILING, error = null)
            work { try { reconcileAccount(stamp) } catch (error: CancellationException) { throw error } catch (error: Exception) { registrationUnknown(error, stamp) } }
            return
        }
        if (mutable.value.phase != HomePhase.ENROLL) return
        if (session!!.accessExpiresAt - now() <= environment.minimumValidityMillis) { renew(); return }
        val stamp = generation
        mutable.value = mutable.value.copy(phase = HomePhase.PREPARING, error = null, securitySetupRequired = false)
        work {
            try {
                // The credential record exists before any enrollment effect. Reuse an interrupted local key creation.
                persist(checkpoint!!, stamp)
                val local = approval ?: persistence.approval(owner!!, reference!!, stamp)
                current(stamp); approval = local
                persist(checkpoint!!.withApprovalKey(local.thumbprint, now()), stamp)
                val existing = api!!.currentDevice(PublicProofKey(local.publicJwk))
                current(stamp)
                if (existing != null) { reconcileAccount(stamp); return@work }
                if (checkpoint!!.enrollment != EnrollmentCheckpoint.PENDING) persist(checkpoint!!.enrollmentNotFound(now()), stamp)
                val actualName = checkpoint!!.deviceName ?: name.trim()
                val request = api!!.prepareRegistration(PublicProofKey(local.publicJwk), actualName)
                current(stamp)
                registration = request
                operation = beginKeyOperation(stamp) { local.beginRegistration(request.signatureRequest) }
                current(stamp)
                val id = ++nextAuthenticationId
                mutable.value = mutable.value.copy(phase = HomePhase.AUTHENTICATING, authenticationId = id, biometric = BiometricLaunch(id, operation!!), deviceName = actualName)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (generation == stamp) {
                    discardRegistration()
                    if (authorityLost(error)) clearAccount(code(error))
                    else showEnrollment(code(error), setup = error is DeviceCryptoException && error.failure in setOf(DeviceCryptoFailure.LOCK_REQUIRED, DeviceCryptoFailure.BIOMETRIC_REQUIRED) || error is VaultException && error.failure == VaultFailure.LOCK_REQUIRED)
                }
            }
        }
    }

    fun promptOpened(id: Long) { if (mutable.value.biometric?.id == id) mutable.value = mutable.value.copy(biometric = null) }
    fun authenticated(id: Long, result: BiometricPrompt.AuthenticationResult) {
        if (mutable.value.authenticationId != id) return
        val active = operation ?: return
        val request = registration ?: return
        if (runCatching { result.cryptoObject?.signature === active.cryptoObject.signature }.getOrDefault(false).not()) return
        val stamp = generation
        operation = null; registration = null
        val name = mutable.value.deviceName!!
        mutable.value = mutable.value.copy(phase = HomePhase.REGISTERING, biometric = null, authenticationId = null)
        work {
            var posted = false
            try {
                val signed = withContext(Dispatchers.Default) { active.finish(result) }
                current(stamp)
                persist(checkpoint!!.beginEnrollment(name, now()), stamp)
                current(stamp); posted = true
                val device = request.submit(signed)
                current(stamp)
                persist(checkpoint!!.deviceAccepted(device.deviceId, device.keyVersion, now()), stamp)
                reconcileAccount(stamp)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (generation == stamp) {
                    if (authorityLost(error)) clearAccount(code(error))
                    else if (posted) {
                        mutable.value = mutable.value.copy(phase = HomePhase.RECONCILING)
                        try { reconcileAccount(stamp) } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { registrationUnknown(failure, stamp) }
                    } else showEnrollment(code(error))
                }
            } finally { active.close(); request.cancel() }
        }
    }

    private fun registrationUnknown(error: Exception, stamp: Long) {
        if (generation != stamp) return
        if (authorityLost(error)) clearAccount(code(error))
        else mutable.value = HomeState(HomePhase.REGISTRATION_UNKNOWN, configuration!!.issuer, "device.registration_unknown", subject = checkpoint!!.session.subject, deviceName = checkpoint!!.deviceName)
    }

    fun authenticationCancelled(id: Long) {
        if (mutable.value.authenticationId != id) return
        discardRegistration()
        if (mutable.value.phase == HomePhase.AUTHENTICATING) showEnrollment("login.authentication_cancelled")
    }
    private fun discardRegistration() { operation?.close(); registration?.cancel(); operation = null; registration = null }

    private fun scheduleRenewal(stamp: Long, verified: LoginSession) {
        deadline?.cancel()
        deadline = scope.launch {
            delay((verified.accessExpiresAt - now() - environment.refreshLeadMillis).coerceAtLeast(1000))
            if (generation == stamp && session === verified) renew()
        }
    }
    private fun renew() {
        if (checkpoint == null || session == null) return
        val stamp = fence(closeKeys = false, preserveSelection = true)
        mutable.value = HomeState(HomePhase.RENEWING, configuration!!.issuer, subject = checkpoint!!.session.subject)
        work {
            try { if (renewCredential(stamp)) install(session!!, Source.REFRESH, stamp) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { handle(error, stamp) }
        }
    }

    private suspend fun renewCredential(stamp: Long, known: OidcDiscovery? = null): Boolean {
        val config = configuration!!
        val saved = checkpoint!!
        if (saved.refresh != RefreshCheckpoint.READY || !saved.session.hasRefreshToken()) { requireReauthentication(stamp, "login.reauthentication"); return false }
        val discovery = known ?: http.discovery(config)
        persist(saved.beginRefresh(now()), stamp)
        val refreshing = checkpoint!!
        try {
            val requested = now()
            val raw = http.tokenEndpoint(discovery, proof!!.proofSigner()).refresh(refreshing.session)
            val verified = TokenVerifier.refreshResponse(raw, refreshing.session, config, http.keys(discovery), requested, now())
            current(stamp)
            persist(refreshing.refreshed(verified, now()), stamp)
            session = verified
            return true
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            current(stamp)
            // The durable marker already prevents replay if this follow-up write also fails.
            runCatching { persist(refreshing.requireReauthentication(now()), stamp) }
            showReauthentication("login.reauthentication")
            return false
        }
    }

    private suspend fun requireReauthentication(stamp: Long, error: String) {
        checkpoint?.let { persist(it.requireReauthentication(now()), stamp) }
        current(stamp); showReauthentication(error)
    }
    private fun showReauthentication(error: String?) {
        retireSession(true)
        retireBinding(false)
        deadline?.cancel(); pending?.cancel(); pending = null; discardRegistration()
        session = null; api = null
        mutable.value = HomeState(HomePhase.REAUTHENTICATE, configuration!!.issuer, error, subject = checkpoint?.session?.subject ?: "")
    }
    private fun browserFailed(error: String) {
        if (checkpoint == null) clearAccount(error)
        else { fence(closeKeys = false); showReauthentication(error) }
    }

    fun refresh() {
        if (mutable.value.phase != HomePhase.READY || mutable.value.refreshing) return
        if (session!!.accessExpiresAt - now() <= environment.minimumValidityMillis) { renew(); return }
        val stamp = generation
        work { try { fetchDirectory(stamp) } catch (error: CancellationException) { throw error } catch (error: Exception) { handle(error, stamp) } }
    }
    private suspend fun fetchDirectory(stamp: Long) {
        mutable.value = mutable.value.copy(refreshing = true)
        try {
            val resource = api!!
            val identity = resource.me()
            current(stamp)
            if (identity.userId != checkpoint!!.userId) throw RemoteRequestException("auth.identity_mismatch")
            val hosts = resource.hosts()
            current(stamp)
            if (hosts["ok"] != JsonBoolean(true)) throw RemoteRequestException("network.invalid_response")
            val names = hosts.obj("data").array("items").associate { item ->
                val host = item as? JsonObject ?: throw RemoteRequestException("network.invalid_response")
                val alias = Contract.boundedText(host.string("alias"), 640)
                if (alias.codePointCount(0, alias.length) > 160) throw RemoteRequestException("network.invalid_response")
                Contract.uuid(host.string("id")) to alias
            }
            val response = resource.runtimes()
            current(stamp)
            if (response["ok"] != JsonBoolean(true)) throw RemoteRequestException("network.invalid_response")
            val items = response.obj("data").array("items").mapNotNull { item ->
                val row = item as? JsonObject ?: throw RemoteRequestException("network.invalid_response")
                val access = RuntimeDirectoryEntry.parse(row)
                if (access.organizationId != null && access.organizationId != configuration!!.organizationId) return@mapNotNull null
                RuntimeRow(access.id, access.hostId, access.dshVersion, names[access.hostId] ?: "已授权的电脑", access)
            }
            val visible = items.mapTo(HashSet()) { it.id }
            for (old in mutable.value.runtimes) {
                val next = items.firstOrNull { it.id == old.id }?.access
                if (old.id !in visible || next == null || next.grantVersion != old.access?.grantVersion || next.role != old.access?.role) drafts.revokeRuntime(old.id)
            }
            val selected = mutable.value.selectedRuntimeId?.takeIf { it in visible }
            mutable.value = mutable.value.copy(runtimes = items, error = null, selectedRuntimeId = selected, pendingBindingId = pendingBindingId)
            if (selected == null) {
                retireSession(true)
                mutable.value = mutable.value.copy(selectedSessionId = null, sessions = emptyList(), sessionError = null, loadingSessions = false)
            } else if (foreground) {
                loadSessions(selected, stamp)
                val resumed = desired?.takeIf { it.first == selected }?.second
                if (resumed != null) follow(selected, resumed, stamp, reopen = true)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            current(stamp)
            if (authorityLost(error)) requireReauthentication(stamp, code(error)) else mutable.value = mutable.value.copy(error = code(error))
        } finally {
            if (generation == stamp) {
                mutable.value = mutable.value.copy(refreshing = false)
                resumeBindingOrDirectory()
                attachAppUpdates()
            }
        }
    }

    private fun resumeBindingOrDirectory() {
        if (!foreground || mutable.value.phase != HomePhase.READY || binding != null || mutable.value.appUpdateSettings) return
        if (resumeBindingView) { resumeBindingView = false; pendingBindingId?.let(::openBinding) }
    }

    /** Directory rows for one runtime; a failing call keeps the previous rows and reports the error. */
    private suspend fun loadSessions(runtime: String, stamp: Long) {
        val client = DshSessionClient(api!!)
        try {
            mutable.value = mutable.value.copy(loadingSessions = true, sessionError = null)
            val page = client.sessions(runtime)
            current(stamp)
            val access = mutable.value.runtimes.firstOrNull { it.id == runtime }?.access
            val rows = page.items.filter { access == null || it.viewScope(access, now()) }
            mutable.value = mutable.value.copy(sessions = rows, loadingSessions = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            current(stamp)
            mutable.value = mutable.value.copy(loadingSessions = false, sessionError = code(error))
        } finally {
            client.close()
        }
    }

    /** Local-only convenience mark; it never implies server-side read state. */
    fun isUnread(entry: SessionDirectoryEntry): Boolean = readMarks?.isUnread(entry.id, entry.updatedAt) == true

    fun refreshSessions() {
        val runtime = mutable.value.selectedRuntimeId ?: return
        if (!foreground || mutable.value.refreshing) return
        val stamp = generation
        navigation { try { loadSessions(runtime, stamp) } catch (error: CancellationException) { throw error } catch (error: Exception) { if (generation == stamp) mutable.value = mutable.value.copy(sessionError = code(error)) } }
    }

    private fun navigation(block: suspend CoroutineScope.() -> Unit) {
        val task = scope.launch(start = CoroutineStart.LAZY, block = block); navigationJob = task; task.start()
    }

    /** Selecting a runtime replaces any followed session; the reader is torn down first. */
    fun openRuntime(id: String) {
        if (!foreground || mutable.value.phase != HomePhase.READY || binding != null) return
        val row = mutable.value.runtimes.firstOrNull { it.id == id } ?: return
        if (mutable.value.selectedRuntimeId == id && mutable.value.sessions.isNotEmpty()) return
        desired = id to null
        retireSession(false)
        mutable.value = mutable.value.copy(selectedRuntimeId = id, selectedSessionId = null, sessions = emptyList(), sessionError = null, loadingSessions = true)
        val stamp = generation
        navigation { try { loadSessions(id, stamp) } catch (error: CancellationException) { throw error } catch (error: Exception) { if (generation == stamp) mutable.value = mutable.value.copy(loadingSessions = false, sessionError = code(error)) } }
    }

    /** Opens one session with one reader; a second call for the same session is a no-op. */
    fun openSession(id: String) {
        val runtime = mutable.value.selectedRuntimeId ?: return
        desired = runtime to id
        follow(runtime, id, generation, reopen = reader?.sessionId != id)
    }

    fun closeSession() {
        desired = mutable.value.selectedRuntimeId?.let { it to null }
        retireSession(false)
        mutable.value = mutable.value.copy(selectedSessionId = null)
    }

    private fun retireSession(clearDesired: Boolean) {
        readerJob?.cancel(); readerJob = null
        reader?.close(); reader = null
        if (clearDesired) desired = null
        retireWatcher()
        terminal?.close(); terminal = null
        mutable.value = mutable.value.copy(reader = null, loadingSessions = false, submitting = false, modelSelectionOpen = false, modelBusy = false, composer = null)
    }

    /** F-04: one $events watcher per runtime, started with the first session open. */
    private fun ensureWatcher(runtime: String) {
        if (watcher != null) return
        val next = InteractionWatcher(api!!, runtime, scope)
        watcher = next
        watcherJob = scope.launch {
            next.interactions.collect { if (watcher === next) mutable.value = mutable.value.copy(interactions = it, interactionError = next.errors.value) }
        }
        // N-2: terminal watcher rides the same runtime lifecycle.
        val terminals = TerminalWatcher(api!!, runtime, scope)
        terminal = terminals
        scope.launch { terminals.terminals.collect { if (terminal === terminals) mutable.value = mutable.value.copy(terminals = it) } }

    }

    /** The session's agent identity as projected by follow records; empty until known. */
    private fun agentId(): String = mutable.value.agentId ?: ""

    private fun retireWatcher() {
        watcherJob?.cancel(); watcherJob = null
        watcher?.close(); watcher = null
        mutable.value = mutable.value.copy(interactions = emptyList(), interactionError = null)
    }

    private fun follow(runtime: String, session: String, stamp: Long, reopen: Boolean) {
        if (reopen) {
            ensureWatcher(runtime)
            readerJob?.cancel(); readerJob = null
            reader?.close(); reader = null
            mutable.value = mutable.value.copy(selectedRuntimeId = runtime, selectedSessionId = session, sessionError = null, sessionActionError = null, reader = null, composer = draftResource(runtime, session)?.let { drafts.open(it) })
            val next = DshSessionReader(api!!, runtime, session, scope)
            reader = next
            readerJob = scope.launch {
                next.state.collect { state ->
                    if (generation != stamp || reader !== next) return@collect
                    mutable.value = mutable.value.copy(reader = state)
                    if (state.phase == SessionReadPhase.READY) mutable.value.sessions.firstOrNull { it.id == session }?.let { readMarks?.mark(session, it.updatedAt) }
                }
            }
        } else {
            mutable.value = mutable.value.copy(selectedRuntimeId = runtime, selectedSessionId = session, composer = draftResource(runtime, session)?.let { drafts.open(it) })
        }
    }

    private fun draftResource(runtime: String, session: String): ConversationResource? {
        val owner = mutable.value.presentationOwner ?: return null
        val host = mutable.value.runtimes.firstOrNull { it.id == runtime }?.hostId ?: return null
        return ConversationResource(owner, host, runtime, session)
    }

    /** Draft text stays in foreground memory only; it is never persisted. */
    fun editDraft(value: TextFieldValue) {
        val owner = mutable.value.presentationOwner ?: return
        val runtime = mutable.value.selectedRuntimeId ?: return
        val session = mutable.value.selectedSessionId ?: return
        val host = mutable.value.runtimes.firstOrNull { it.id == runtime }?.hostId ?: return
        val resource = ConversationResource(owner, host, runtime, session)
        val currentDraft = mutable.value.composer ?: drafts.open(resource)
        drafts.edit(currentDraft.copy(value = value))?.let { mutable.value = mutable.value.copy(composer = it) }
    }

    /** Sends the composer draft once; the text is restored if the call fails. */
    fun sendDraft() {
        val draft = mutable.value.composer ?: return
        val text = draft.value.text
        if (text.isBlank() || mutable.value.submitting) return
        prompt(text)
        mutable.value = mutable.value.copy(composer = drafts.saved(draft) ?: draft.copy(value = TextFieldValue()))
    }

    private fun restoreDraft(text: String) {
        val draft = mutable.value.composer ?: return
        val merged = text + draft.value.text
        mutable.value = mutable.value.copy(composer = draft.copy(value = TextFieldValue(merged, TextRange(merged.length))))
    }

    fun loadOlder() {
        val current = reader ?: return
        if (current.state.value.loadingOlder) return
        navigation { try { current.loadOlder() } catch (error: CancellationException) { throw error } catch (_: Exception) { } }
    }

    fun prompt(text: String, mode: String = "queue") {
        val current = reader ?: return
        val body = text.trim()
        if (body.isEmpty() || mutable.value.submitting) return
        val stamp = generation
        // F-05: the requestId is minted once; a retry of a pending entry reuses
        // it, so dsh can never see the retry as a new message.
        val entry = mutable.value.composer?.value?.let { outbox.pending().firstOrNull { p -> p.text == body } }
            ?: outbox.submit(current.sessionId, body, mode)
        mutable.value = mutable.value.copy(submitting = true, sessionActionError = null)
        navigation {
            try {
                current(stamp)
                current.prompt(body, entry.requestId, entry.mode)
                current(stamp)
                outbox.settle(entry.requestId)
                mutable.value = mutable.value.copy(submitting = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == stamp) {
                    mutable.value = mutable.value.copy(submitting = false, sessionActionError = code(error))
                    restoreDraft(body)
                }
            }
        }
    }

    fun cancelSession() = sessionAction { it.cancel() }

    /** F-04: approve/reject/answer one pending interaction. */
    fun approveInteraction(eventId: String) {
        val current = watcher ?: return
        navigation { try { current.approve(eventId) } catch (e: CancellationException) { throw e } catch (_: Exception) { } }
    }
    fun rejectInteraction(eventId: String, message: String = "在手机上拒绝") {
        val current = watcher ?: return
        navigation { try { current.reject(eventId, message) } catch (e: CancellationException) { throw e } catch (_: Exception) { } }
    }
    fun submitAnswers(eventId: String, answers: List<InteractionBridge.AnsweredQuestion>) {
        val current = watcher ?: return
        navigation { try { current.submitAnswers(eventId, answers) } catch (e: CancellationException) { throw e } catch (_: Exception) { } }
    }

    fun renameSession(title: String) {
        val next = title.trim()
        if (next.isEmpty()) return
        sessionAction { it.rename(next) }
    }

    fun archiveSession() = sessionAction { it.archive() }

    fun forkSession() {
        val runtime = mutable.value.selectedRuntimeId
        sessionAction { reader ->
            val fork = reader.fork()
            mutable.value = mutable.value.copy(selectedSessionId = fork)
            val stamp = generation
            if (runtime != null) loadSessions(runtime, stamp)
            follow(runtime ?: return@sessionAction, fork, stamp, reopen = true)
        }
    }

    private fun sessionAction(block: suspend (DshSessionReader) -> Unit) {
        val current = reader ?: return
        val stamp = generation
        mutable.value = mutable.value.copy(sessionActionError = null)
        navigation {
            try {
                current(stamp)
                block(current)
                current(stamp)
                mutable.value.selectedRuntimeId?.let { loadSessions(it, stamp) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == stamp) mutable.value = mutable.value.copy(sessionActionError = code(error))
            }
        }
    }

    fun openModelSelection() {
        val runtime = mutable.value.selectedRuntimeId ?: return
        mutable.value = mutable.value.copy(modelSelectionOpen = true, modelBusy = true, modelError = null)
        val stamp = generation
        navigation {
            val client = DshSessionClient(api!!)
            try {
                val catalog = client.modelCatalog(runtime)
                current(stamp)
                mutable.value = mutable.value.copy(modelCatalog = catalog, modelBusy = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == stamp) mutable.value = mutable.value.copy(modelBusy = false, modelError = code(error))
            } finally {
                client.close()
            }
        }
    }

    fun closeModelSelection() { mutable.value = mutable.value.copy(modelSelectionOpen = false, modelError = null) }

    fun selectModel(option: DshModelOption, reasoningEffort: String? = null) {
        closeModelSelection()
        sessionAction { it.selectModel(option.provider, option.model, reasoningEffort) }
    }

    fun openCreation() { mutable.value = mutable.value.copy(creation = SessionCreationState()) }
    fun closeCreation() { mutable.value = mutable.value.copy(creation = null) }

    /** Creates a session and follows it. Omitted fields leave dsh defaults in place. */
    fun createSession(workspaceId: String? = null, agentPreset: String? = null) {
        val runtime = mutable.value.selectedRuntimeId ?: return
        if (mutable.value.submitting) return
        val stamp = generation
        mutable.value = mutable.value.copy(submitting = true, creation = SessionCreationState(SessionCreationPhase.CREATING))
        navigation {
            val client = DshSessionClient(api!!)
            try {
                val created = client.createSession(runtime, workspaceId?.takeIf { it.isNotBlank() }?.trim(), null, agentPreset?.takeIf { it.isNotBlank() }?.trim())
                current(stamp)
                loadSessions(runtime, stamp)
                mutable.value = mutable.value.copy(submitting = false, creation = null)
                follow(runtime, created, stamp, reopen = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == stamp) mutable.value = mutable.value.copy(submitting = false, creation = SessionCreationState(SessionCreationPhase.FAILED, code(error)))
            } finally {
                client.close()
            }
        }
    }

    /** Back moves one level: model sheet, creation, chat, then the runtime list. */
    fun back() {
        val current = mutable.value
        when {
            current.modelSelectionOpen -> closeModelSelection()
            current.creation != null -> closeCreation()
            current.selectedSessionId != null -> closeSession()
            current.selectedRuntimeId != null -> { retireSession(true); mutable.value = mutable.value.copy(selectedRuntimeId = null, selectedSessionId = null, sessions = emptyList(), sessionError = null) }
        }
    }

    fun foreground(active: Boolean) {
        if (foreground == active) return
        foreground = active
        binding?.foreground(active)
        if (!active) {
            drafts.clear(null)
            readerJob?.cancel(); readerJob = null
            reader?.close(); reader = null
            mutable.value = mutable.value.copy(reader = null, loadingSessions = false, submitting = false, binding = binding?.state?.value)
        } else if (mutable.value.phase == HomePhase.READY) {
            if (mutable.value.appUpdateSettings) { attachAppUpdates(); return }
            if (binding == null) {
                if (resumeBindingView) { resumeBindingView = false; pendingBindingId?.let(::openBinding) }
                else desired?.let { (runtime, session) ->
                    mutable.value = mutable.value.copy(selectedRuntimeId = runtime, selectedSessionId = session)
                    if (session != null) follow(runtime, session, generation, reopen = true) else openRuntime(runtime)
                }
            }
        }
    }
    private fun retireAppUpdates(keepPage: Boolean = false) {
        appUpdateCollector?.cancel(); appUpdateCollector = null
        appUpdateEventCollector?.cancel(); appUpdateEventCollector = null
        appUpdateFlow?.close(); appUpdateFlow = null
        mutable.value = mutable.value.copy(appUpdates = null, appUpdateSettings = keepPage && mutable.value.appUpdateSettings)
    }
    fun openAppUpdates() {
        if (!foreground || mutable.value.phase != HomePhase.READY) return
        if (session!!.accessExpiresAt - now() <= environment.minimumValidityMillis) { renew(); return }
        closeBinding(); resumeBindingView = false; retireSession(false)
        mutable.value = mutable.value.copy(appUpdateSettings = true, selectedRuntimeId = null, selectedSessionId = null,
            sessions = emptyList(), reader = null, loadingSessions = false, sessionError = null)
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
        mutable.value = mutable.value.copy(appUpdates = opened.state.value)
        appUpdateCollector = scope.launch {
            opened.state.collect { value ->
                if (generation == stamp && navigationGeneration == view && appUpdateFlow === opened && foreground && mutable.value.phase == HomePhase.READY)
                    mutable.value = mutable.value.copy(appUpdates = value)
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

    private fun fence(closeKeys: Boolean, preserveSelection: Boolean = false): Long {
        drafts.clear()
        retireSession(!preserveSelection)
        retireBinding(preserveSelection)
        generation++; job?.cancel(); deadline?.cancel(); pending?.cancel(); pending = null; discardRegistration()
        retireWatcher()
        if (closeKeys) { owner?.close(); owner = null; proof = null; approval = null; api = null; session = null; readMarks = null }
        mutable.value = mutable.value.copy(presentationOwner = null)
        return generation
    }
    fun cancel() { clearAccount(null) }
    private fun clearAccount(error: String?) {
        val old = reference
        val stamp = fence(closeKeys = true)
        cleanupPending = true
        mutable.value = HomeState(HomePhase.CLEARING, configuration?.issuer ?: "")
        work {
            try {
                persistence.clear(stamp, old)
                current(stamp)
                cleanupPending = false; checkpoint = null; reference = null
                mutable.value = HomeState(HomePhase.SIGNED_OUT, configuration?.issuer ?: "", error)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (generation == stamp) mutable.value = HomeState(HomePhase.RECOVERY_ERROR, configuration?.issuer ?: "", "account.cleanup_failed") }
        }
    }
    private fun handle(error: Exception, stamp: Long) {
        if (generation != stamp) return
        deadline?.cancel()
        retireSession(true)
        retireBinding(false)
        if (authorityLost(error)) clearAccount(code(error))
        else { session = null; api = null; discardRegistration(); mutable.value = HomeState(HomePhase.RECOVERY_ERROR, configuration?.issuer ?: "", code(error)) }
    }
    private fun authorityLost(error: Exception): Boolean =
        error is LoginException && error.failure == LoginFailure.IDENTITY ||
            error is RemoteRequestException && error.code in setOf("auth.identity_mismatch", "access.organization_missing", "access.denied", "access.revoked", "device.invalid")
    private fun retireBinding(keepResume: Boolean) {
        bindingGeneration++; bindingCollector?.cancel(); bindingCollector = null
        resumeBindingView = keepResume && binding != null && binding?.resumeId != null
        if (keepResume) binding?.resumeId?.let { pendingBindingId = it } else pendingBindingId = null
        binding?.close(); binding = null
    }
    fun openBinding(resumeId: String? = pendingBindingId) {
        if (!foreground || mutable.value.phase != HomePhase.READY) return
        if (session!!.accessExpiresAt - now() <= environment.minimumValidityMillis) {
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
        mutable.value = mutable.value.copy(binding = opened.state.value, pendingBindingId = pendingBindingId,
            selectedRuntimeId = null, selectedSessionId = null, sessions = emptyList(), reader = null, loadingSessions = false, sessionError = null)
        bindingCollector = scope.launch {
            opened.state.collect { next ->
                if (generation != stamp || bindingGeneration != view || binding !== opened || mutable.value.phase != HomePhase.READY) return@collect
                pendingBindingId = if (next.phase in setOf(HostBindingPhase.READY, HostBindingPhase.UNAVAILABLE) || next.phase == HostBindingPhase.ENTER && next.id.isEmpty()) null else opened.resumeId
                mutable.value = mutable.value.copy(binding = next, pendingBindingId = pendingBindingId)
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
        mutable.value = mutable.value.copy(binding = null, pendingBindingId = resume)
    }
    private fun code(error: Exception): String = when (error) {
        is CommandDataException -> error.code
        is LoginException -> "login.${error.failure.name.lowercase()}"
        is RemoteRequestException -> error.code
        is DeviceCryptoException -> "device.${error.failure.name.lowercase()}"
        is DeviceProofException -> "auth.proof_invalid"
        is VaultException -> "vault.${error.failure.name.lowercase()}"
        else -> "network.unavailable"
    }
    /** Process/owner disposal preserves durable credentials; explicit local exit uses cancel(). */
    override fun close() { fence(closeKeys = true); updateLaunches.close(); scope.cancel() }
}

@HiltViewModel
class HomeViewModel @Inject constructor(private val login: LoginController) : ViewModel() {
    fun approveInteraction(eventId: String) = login.approveInteraction(eventId)
    fun rejectInteraction(eventId: String, message: String) = login.rejectInteraction(eventId, message)
    fun submitAnswers(eventId: String, answers: List<InteractionBridge.AnsweredQuestion>) = login.submitAnswers(eventId, answers)
    val state = login.state
    val appUpdateEvents = login.appUpdateEvents
    fun openAppUpdates() = login.openAppUpdates()
    fun closeAppUpdates() = login.closeAppUpdates()
    fun checkAppUpdates(view: String) = login.checkAppUpdates(view)
    fun downloadAppUpdate(view: String, publication: String?) = login.downloadAppUpdate(view, publication)
    fun installAppUpdate(view: String, attempt: String?, publication: String?) = login.installAppUpdate(view, attempt, publication)
    fun requestAppInstallPermission(view: String) = login.requestAppInstallPermission(view)
    fun cleanupAppUpdate(view: String, context: String) = login.cleanupAppUpdate(view, context)
    fun cancelAppUpdateDownload(view: String) = login.cancelAppUpdateDownload(view)
    fun consumeAppUpdateLaunch(event: AppUpdateLaunch) = login.consumeAppUpdateLaunch(event)
    fun appUpdateLaunchFailed(event: AppUpdateLaunch) = login.appUpdateLaunchFailed(event)
    fun login() = login.login()
    fun register(name: String) = login.register(name)
    fun cancelAccount() = login.cancel()
    fun refresh() = login.refresh()
    fun foreground(active: Boolean) = login.foreground(active)
    fun openRuntime(id: String) = login.openRuntime(id)
    fun openSession(id: String) = login.openSession(id)
    fun closeSession() = login.closeSession()
    fun openCreation() = login.openCreation()
    fun closeCreation() = login.closeCreation()
    fun createSession(workspaceId: String?, agentPreset: String?) = login.createSession(workspaceId, agentPreset)
    fun loadOlder() = login.loadOlder()
    fun refreshSessions() = login.refreshSessions()
    fun isUnread(entry: SessionDirectoryEntry) = login.isUnread(entry)
    fun editDraft(value: TextFieldValue) = login.editDraft(value)
    fun sendDraft() = login.sendDraft()
    fun back() = login.back()
    fun openModelSelection() = login.openModelSelection()
    fun closeModelSelection() = login.closeModelSelection()
    fun selectModel(option: DshModelOption, reasoningEffort: String? = null) = login.selectModel(option, reasoningEffort)
    fun cancelSession() = login.cancelSession()
    fun renameSession(title: String) = login.renameSession(title)
    fun archiveSession() = login.archiveSession()
    fun forkSession() = login.forkSession()
    fun openBinding() = login.openBinding()
    fun previewBinding(id: String, code: String) = login.previewBinding(id, code)
    fun confirmBinding() = login.confirmBinding()
    fun queryBinding(id: String) = login.queryBinding(id)
    fun refreshBinding() = login.refreshBinding()
    fun enterAnotherBinding() = login.enterAnotherBinding()
    fun closeBinding() = login.closeBinding()
    fun browserOpened(id: Long) = login.browserOpened(id)
    fun browserUnavailable() = login.browserUnavailable()
    fun promptOpened(id: Long) = login.promptOpened(id)
    fun authenticated(id: Long, result: BiometricPrompt.AuthenticationResult) = login.authenticated(id, result)
    fun authenticationCancelled(id: Long) = login.authenticationCancelled(id)
}
