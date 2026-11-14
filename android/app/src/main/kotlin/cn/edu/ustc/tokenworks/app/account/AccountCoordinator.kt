package cn.edu.ustc.tokenworks

import android.content.Context
import androidx.biometric.BiometricPrompt
import androidx.core.net.toUri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import java.io.FileNotFoundException
import java.util.UUID

internal interface AccountFeatures {
    fun fence(closeKeys: Boolean, preserveSelection: Boolean)
    fun unavailable()
    suspend fun ready(owner: CommandOwner, stamp: Long)
    suspend fun clearOwner(ownerId: String, lanStore: AndroidLanCredentialStore?)
}

internal class AccountCoordinator(
    private val context: Context, private val http: LoginHttp, private val environment: LoginEnvironment,
    private val scope: CoroutineScope, private val mutable: MutableStateFlow<HomeState>,
) : AutoCloseable {
    @Volatile var generation = 0L
        private set
    private val persistence = AccountPersistence(context, environment.storageSlot, environment.ledgerName) { it == generation && scope.isActive }
    var configuration: LoginConfiguration? = null
        private set
    private var nextAuthenticationId = 0L
    private var job: Job? = null
    private var deadline: Job? = null
    private var reference: ScopeReference? = null
    private var owner: DeviceKeyStore? = null
    private var proof: DeviceKeyHandle? = null
    private var approval: DeviceKeyHandle? = null
    var checkpoint: AccountCheckpoint? = null
        private set
    private var pending: PendingLogin? = null
    var session: LoginSession? = null
        private set
    var api: RemoteApi? = null
        private set
    private var registration: RemoteApi.RegistrationRequest? = null
    private var operation: AuthenticatedKeyOperation<DeviceRegistrationProof>? = null
    private var cleanupPending = false
    private lateinit var features: AccountFeatures
    private enum class Source { BROWSER, CACHE, REFRESH }

    fun start(features: AccountFeatures) {
        this.features = features
        work {
            try { configuration = withContext(Dispatchers.IO) { LoginConfiguration.parse(environment.configuration()) } }
            catch (_: FileNotFoundException) { mutable.value = HomeState(HomePhase.UNCONFIGURED); return@work }
            catch (_: Exception) { mutable.value = HomeState(HomePhase.INVALID_CONFIGURATION); return@work }
            restore(generation)
        }
    }

    fun work(block: suspend CoroutineScope.() -> Unit) {
        // Assign before execution: an immediate failure can start cleanup as another task.
        val next = scope.launch(start = CoroutineStart.LAZY, block = block)
        job = next
        next.start()
    }

    fun current(stamp: Long) { if (generation != stamp || !scope.isActive) throw CancellationException() }
    fun now() = environment.now()

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
        if (mutable.value.phase !in setOf(HomePhase.SIGNED_OUT, HomePhase.REAUTHENTICATE) && !mutable.value.lanOnly) return
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
            catch (error: Exception) { if (generation == stamp) browserFailed(homeErrorCode(error)) }
        }
    }

    fun browserOpened(id: Long) { if (mutable.value.browser?.id == id) mutable.update { home -> home.copy(browser = null) } }
    fun browserUnavailable() { if (pending != null) browserFailed("login.browser_unavailable") }

    fun callback(uri: String) {
        val attempt = pending ?: return
        val stamp = generation
        val exchange = try { attempt.acceptCallback(uri, now()) }
        catch (error: LoginException) {
            if (error.failure in setOf(LoginFailure.INVALID_RESPONSE, LoginFailure.IDENTITY)) mutable.update { home -> home.copy(error = "login.invalid_callback") }
            else browserFailed(homeErrorCode(error))
            return
        }
        pending = null; deadline?.cancel()
        mutable.update { home -> home.copy(phase = HomePhase.EXCHANGING, browser = null, error = null) }
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
                    else if (authorityLost(error)) clearAccount(homeErrorCode(error)) else browserFailed(homeErrorCode(error))
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
                showEnrollment(homeErrorCode(error), setup = true)
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
                mutable.value = HomeState(HomePhase.READY, session!!.issuer, subject = session!!.subject, deviceId = device.deviceId, presentationOwner = presentationOwner)
                scheduleRenewal(stamp, session!!)
                features.ready(presentationOwner, stamp)
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
            mutable.update { home -> home.copy(phase = HomePhase.RECONCILING, error = null) }
            work { try { reconcileAccount(stamp) } catch (error: CancellationException) { throw error } catch (error: Exception) { registrationUnknown(error, stamp) } }
            return
        }
        if (mutable.value.phase != HomePhase.ENROLL) return
        if (session!!.accessExpiresAt - now() <= environment.minimumValidityMillis) { renew(); return }
        val stamp = generation
        mutable.update { home -> home.copy(phase = HomePhase.PREPARING, error = null, securitySetupRequired = false) }
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
                mutable.update { home -> home.copy(phase = HomePhase.AUTHENTICATING, authenticationId = id, biometric = BiometricLaunch(id, operation!!), deviceName = actualName) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (generation == stamp) {
                    discardRegistration()
                    if (authorityLost(error)) clearAccount(homeErrorCode(error))
                    else showEnrollment(homeErrorCode(error), setup = error is DeviceCryptoException && error.failure in setOf(DeviceCryptoFailure.LOCK_REQUIRED, DeviceCryptoFailure.BIOMETRIC_REQUIRED) || error is VaultException && error.failure == VaultFailure.LOCK_REQUIRED)
                }
            }
        }
    }

    fun promptOpened(id: Long) { if (mutable.value.biometric?.id == id) mutable.update { home -> home.copy(biometric = null) } }
    fun authenticated(id: Long, result: BiometricPrompt.AuthenticationResult) {
        if (mutable.value.authenticationId != id) return
        val active = operation ?: return
        val request = registration ?: return
        if (runCatching { result.cryptoObject?.signature === active.cryptoObject.signature }.getOrDefault(false).not()) return
        val stamp = generation
        operation = null; registration = null
        val name = mutable.value.deviceName!!
        mutable.update { home -> home.copy(phase = HomePhase.REGISTERING, biometric = null, authenticationId = null) }
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
                    if (authorityLost(error)) clearAccount(homeErrorCode(error))
                    else if (posted) {
                        mutable.update { home -> home.copy(phase = HomePhase.RECONCILING) }
                        try { reconcileAccount(stamp) } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { registrationUnknown(failure, stamp) }
                    } else showEnrollment(homeErrorCode(error))
                }
            } finally { active.close(); request.cancel() }
        }
    }

    private fun registrationUnknown(error: Exception, stamp: Long) {
        if (generation != stamp) return
        if (authorityLost(error)) clearAccount(homeErrorCode(error))
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
    fun renew() {
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

    suspend fun requireReauthentication(stamp: Long, error: String) {
        checkpoint?.let { persist(it.requireReauthentication(now()), stamp) }
        current(stamp); showReauthentication(error)
    }
    private fun showReauthentication(error: String?) {
        features.unavailable()
        deadline?.cancel(); pending?.cancel(); pending = null; discardRegistration()
        session = null; api = null
        mutable.value = HomeState(HomePhase.REAUTHENTICATE, configuration!!.issuer, error, subject = checkpoint?.session?.subject ?: "", canUseLanOnly = localOwner() != null)
    }
    private fun browserFailed(error: String) {
        if (checkpoint == null) clearAccount(error)
        else { fence(closeKeys = false); showReauthentication(error) }
    }

    private fun fence(closeKeys: Boolean, preserveSelection: Boolean = false): Long {
        features.fence(closeKeys, preserveSelection)
        generation++; job?.cancel(); deadline?.cancel(); pending?.cancel(); pending = null; discardRegistration()
        if (closeKeys) { owner?.close(); owner = null; proof = null; approval = null; api = null; session = null }
        mutable.update { home -> home.copy(presentationOwner = null) }
        return generation
    }
    fun requireFreshCredential(): Boolean {
        if (mutable.value.lanOnly) return false
        val credential = session ?: return false
        if (credential.accessExpiresAt - now() <= environment.minimumValidityMillis) { renew(); return false }
        return true
    }
    fun proofSigner(): DeviceProofSigner = proof!!.proofSigner()
    fun localOwner(): LocalDataOwner? = dataOwner().takeIf { proof != null && owner != null }
    private fun dataOwner(): LocalDataOwner? {
        val saved = checkpoint ?: return null
        val device = saved.deviceId ?: return null
        val config = configuration ?: return null
        return LocalDataOwner(saved.accountId, saved.organizationId, config.fingerprint, saved.userId, device)
    }
    fun enterLanOnly() {
        if (mutable.value.phase !in setOf(HomePhase.RECOVERY_ERROR, HomePhase.REAUTHENTICATE)) return
        val local = localOwner() ?: return
        val stamp = fence(closeKeys = false)
        session = null; api = null
        mutable.value = HomeState(HomePhase.READY, configuration!!.issuer, subject = checkpoint!!.session.subject,
            deviceId = local.deviceId, presentationOwner = CommandOwner(local.accountId, local.organizationId, local.deploymentHash, local.userId, local.deviceId), lanOnly = true)
        work { features.ready(mutable.value.presentationOwner!!, stamp) }
    }
    fun cancel() {
        if (mutable.value.pairing.blocksNavigation()) {
            mutable.update { it.copy(error = "lan.pending_authorization") }
            return
        }
        val store = lanStore()
        if (store == null) { clearAccount(null); return }
        val stamp = generation
        work {
            try {
                val unresolved = withContext(Dispatchers.IO) { store.loadPending() != null }
                current(stamp)
                if (unresolved) mutable.update { it.copy(error = "lan.pending_authorization") }
                else clearAccount(null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == stamp) mutable.update { it.copy(error = homeErrorCode(error)) }
            }
        }
    }
    private fun lanStore(): AndroidLanCredentialStore? = dataOwner()?.let {
        AndroidLanCredentialStore(context, it, checkpoint!!.session.proofKeyThumbprint)
    }
    private fun clearAccount(error: String?) {
        val old = reference
        val oldOwner = mutable.value.presentationOwner?.fingerprint ?: dataOwner()?.fingerprint
        val lanStore = lanStore()
        val stamp = fence(closeKeys = true)
        cleanupPending = true
        mutable.value = HomeState(HomePhase.CLEARING, configuration?.issuer ?: "")
        work {
            try {
                val unresolvedLan = if (lanStore == null) false else try {
                    withContext(Dispatchers.IO) { lanStore.loadPending() != null }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { true }
                oldOwner?.let { features.clearOwner(it, lanStore) }
                persistence.clear(stamp, old)
                current(stamp)
                cleanupPending = false; checkpoint = null; reference = null
                mutable.value = HomeState(HomePhase.SIGNED_OUT, configuration?.issuer ?: "",
                    if (unresolvedLan) "lan.pending_removed_with_account" else error)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (generation == stamp) mutable.value = HomeState(HomePhase.RECOVERY_ERROR, configuration?.issuer ?: "", "account.cleanup_failed") }
        }
    }
    fun handle(error: Exception, stamp: Long) {
        if (generation != stamp) return
        deadline?.cancel()
        features.unavailable()
        if (authorityLost(error)) clearAccount(homeErrorCode(error))
        else { session = null; api = null; discardRegistration(); mutable.value = HomeState(HomePhase.RECOVERY_ERROR, configuration?.issuer ?: "", homeErrorCode(error), canUseLanOnly = localOwner() != null) }
    }
    fun authorityLost(error: Exception): Boolean =
        error is LoginException && error.failure == LoginFailure.IDENTITY ||
            error is RemoteRequestException && error.code in setOf("auth.identity_mismatch", "access.organization_missing", "access.denied", "access.revoked", "device.invalid")
    /** Process/owner disposal preserves durable credentials; explicit local exit uses cancel(). */
    override fun close() { fence(closeKeys = true) }

}
