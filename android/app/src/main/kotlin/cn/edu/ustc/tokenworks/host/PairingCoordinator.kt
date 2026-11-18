package cn.edu.ustc.tokenworks

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** State contains reviewed public facts only, never a pairing code, token, or descriptor JSON. */
enum class PairingPhase { ENTRY, REVIEW, PAIRING, READY, REVOKING, SAVE_REQUIRED, UNKNOWN }
data class PairingPreview(val origin: String, val runtimeId: String, val installationId: String, val tlsPin: String, val expiresAt: Long,
    val hostStaticPublicKey: String, val hostKeyEpoch: Long)
data class PairedLanDevice(val runtimeId: String, val installationId: String, val origin: String, val pairId: String,
    val hostKeyEpoch: Long, val deviceKeyEpoch: Long, val relayBound: Boolean = false)
data class PairingState(
    val phase: PairingPhase = PairingPhase.ENTRY, val preview: PairingPreview? = null,
    val devices: List<PairedLanDevice> = emptyList(), val busy: Boolean = false, val error: String? = null,
    val pendingDevice: PairedLanDevice? = null,
    val rotatingRuntimeId: String? = null,
)

/** The account generation fences UI/network work, never erases its durable pending capability. */
class PairingCoordinator(
    private val store: AndroidLanCredentialStore, private val signer: DeviceProofSigner, owner: CoroutineScope,
    private val connections: LanConnectionFactory = LanConnectionFactory.create(), private val current: () -> Unit = {},
    private val relayIssuer: String? = null,
) : AutoCloseable {
    private val scope = CoroutineScope(owner.coroutineContext + SupervisorJob(owner.coroutineContext[Job]))
    private val closed = AtomicBoolean()
    private val lock = Any()
    private val credentials = linkedMapOf<String, LanCredentials>()
    private val transports = mutableSetOf<DshTransport>()
    private val controls = mutableMapOf<PairingControlClient, String>()
    private var descriptor: LanPairingDescriptor? = null
    private var pending: LanPairingIntent? = null
    private var rotation: NoiseDeviceRotationIntent? = null
    private var accepted = false
    private var loaded = false
    private val mutable = MutableStateFlow(PairingState())
    val state: StateFlow<PairingState> = mutable.asStateFlow()

    private fun live() { if (closed.get()) throw CancellationException(); current() }
    private fun publicDevice(value: LanCredentials) = PairedLanDevice(value.runtimeId, value.installationId, value.origin, value.pairId,
        value.hostKeyEpoch, value.deviceKeyEpoch, relayBound = value.manifest.fields["relay"] is JsonObject)
    private fun devices(): List<PairedLanDevice> = synchronized(lock) { credentials.values.map(::publicDevice) }
    private fun pendingPhase() = if (accepted) PairingPhase.SAVE_REQUIRED else PairingPhase.UNKNOWN
    private fun code(error: Exception): String = when (error) {
        is LanPairingException -> error.code
        is DshFailure -> error.code
        is RemoteRequestException -> error.code
        is VaultException -> "lan.vault_${error.failure.name.lowercase()}"
        else -> "lan.invalid_descriptor"
    }
    private fun start(block: suspend () -> Unit) {
        live()
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null)
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!closed.get()) mutable.value = mutable.value.copy(
                    phase = if (rotation != null) PairingPhase.UNKNOWN else if (pending != null) pendingPhase() else if (descriptor != null) PairingPhase.REVIEW else PairingPhase.READY,
                    pendingDevice = pending?.credentials?.let(::publicDevice), error = code(error))
            }
            finally { if (!closed.get()) mutable.value = mutable.value.copy(busy = false) }
        }
    }

    fun restore() {
        if (loaded) return
        start {
            val saved = withContext(Dispatchers.IO) { live(); store.restore() }
            live()
            synchronized(lock) { credentials.clear(); saved.credentials.forEach { credentials[it.runtimeId] = it } }
            pending = saved.pending
            rotation = saved.rotation
            loaded = true
            mutable.value = PairingState(if (pending != null || rotation != null) PairingPhase.UNKNOWN else if (saved.credentials.isEmpty()) PairingPhase.ENTRY else PairingPhase.READY,
                devices = devices(), busy = true, pendingDevice = pending?.credentials?.let(::publicDevice), rotatingRuntimeId = rotation?.previous?.runtimeId)
            if (pending != null) reconcileAndSave()
            if (rotation != null) reconcileRotationAndSave()
        }
    }

    /** A descriptor is private and cannot overwrite an unresolved durable intent. */
    fun importDescriptor(text: String) {
        live()
        if (mutable.value.busy || pending != null || rotation != null) return
        try {
            val parsed = LanPairingDescriptor.parse(text)
            descriptor = parsed
            mutable.value = PairingState(PairingPhase.REVIEW, PairingPreview(parsed.origin, parsed.runtimeId, parsed.installationId,
                parsed.tlsPin, parsed.expiresAt, parsed.hostStaticPublicKey, parsed.hostKeyEpoch), devices())
        } catch (error: Exception) {
            descriptor = null
            mutable.value = PairingState(devices = devices(), error = code(error))
        }
    }

    fun pair(deviceName: String) {
        val reviewed = descriptor ?: return
        if (!loaded) { mutable.value = mutable.value.copy(error = "lan.restore_required"); return }
        if (pending != null || rotation != null) return
        start {
            val intent = withContext(Dispatchers.IO) { live(); store.intent(reviewed, deviceName, signer, relayIssuer) }
            // Even a cancellation at the IO return boundary leaves a recoverable encrypted intent.
            withContext(NonCancellable + Dispatchers.IO) { store.begin(intent, ::live) }
            pending = intent
            descriptor = null
            live()
            mutable.value = mutable.value.copy(phase = PairingPhase.PAIRING, preview = null, pendingDevice = publicDevice(intent.credentials))
            connections.pair(signer, intent)
            accepted = true
            mutable.value = mutable.value.copy(phase = PairingPhase.SAVE_REQUIRED)
            savePending()
        }
    }

    private suspend fun reconcileAndSave() {
        val intent = pending ?: return
        live()
        connections.reconcile(signer, intent.credentials)
        live()
        accepted = true
        mutable.value = mutable.value.copy(phase = PairingPhase.SAVE_REQUIRED)
        savePending()
    }

    /** Promotion and descriptor erasure are one vault commit, independent of a closing UI generation. */
    private suspend fun savePending() {
        val intent = pending ?: return
        withContext(NonCancellable + Dispatchers.IO) { store.promote(intent.credentials) }
        live()
        synchronized(lock) { credentials[intent.credentials.runtimeId] = intent.credentials }
        pending = null
        accepted = false
        mutable.value = PairingState(PairingPhase.READY, devices = devices(), busy = true)
    }

    fun retrySave() { if (pending != null) start { if (accepted) savePending() else reconcileAndSave() } }

    fun rotateDeviceKey(runtimeId: String) {
        if (pending != null || rotation != null) return
        start {
            val previous = synchronized(lock) { credentials[runtimeId] } ?: return@start
            val intent = withContext(Dispatchers.IO) { live(); NoiseDeviceRotationIntent.create(previous) }
            withContext(NonCancellable + Dispatchers.IO) { store.beginRotation(intent, ::live) }
            rotation = intent
            synchronized(lock) { retireTransports(runtimeId) }
            mutable.value = mutable.value.copy(phase = PairingPhase.PAIRING, rotatingRuntimeId = runtimeId)
            live()
            // This control write is dispatched once. All restart/cancel/lost-ACK paths read the new key.
            connections.rotate(signer, intent)
            reconcileRotationAndSave()
        }
    }
    fun reconcileRotation(runtimeId: String) {
        if (rotation?.previous?.runtimeId == runtimeId) start { reconcileRotationAndSave() }
    }
    private suspend fun reconcileRotationAndSave() {
        val intent = rotation ?: return
        live(); connections.reconcileRotation(signer, intent); live()
        withContext(NonCancellable + Dispatchers.IO) { store.promoteRotation(intent) }
        live()
        synchronized(lock) { retireTransports(intent.previous.runtimeId); credentials[intent.replacement.runtimeId] = intent.replacement }
        rotation = null
        mutable.value = PairingState(PairingPhase.READY, devices = devices(), busy = true)
    }
    private fun retireTransports(runtimeId: String) {
        val retired = transports.filter { it.runtimeId == runtimeId }
        controls.filterValues { it == runtimeId }.keys.toList().forEach { it.close(); controls.remove(it) }
        retired.forEach(DshTransport::close); transports.removeAll(retired.toSet())
    }

    fun revoke(runtimeId: String) {
        start {
            val value = rotation?.replacement?.takeIf { it.runtimeId == runtimeId } ?: pending?.credentials?.takeIf { it.runtimeId == runtimeId }
                ?: synchronized(lock) { credentials[runtimeId] } ?: return@start
            mutable.value = mutable.value.copy(phase = PairingPhase.REVOKING)
            connections.transport(value, signer).use { it.revoke() }
            // Only an acknowledged DELETE permits automatic local removal. An ambiguous DELETE stays recoverable.
            withContext(NonCancellable + Dispatchers.IO) { store.remove(runtimeId) }
            live()
            removeMemory(runtimeId)
            mutable.value = PairingState(PairingPhase.READY, devices = devices(), busy = true)
        }
    }
    fun revokePending() { pending?.let { revoke(it.credentials.runtimeId) } }

    /** Caller explicitly confirms that this does not revoke any possible Host authorization. */
    fun forget(runtimeId: String) {
        start {
            withContext(NonCancellable + Dispatchers.IO) { live(); store.remove(runtimeId) }
            live()
            removeMemory(runtimeId)
            mutable.value = PairingState(PairingPhase.READY, devices = devices(), busy = true)
        }
    }
    private fun removeMemory(runtimeId: String) {
        synchronized(lock) {
            credentials.remove(runtimeId)
            retireTransports(runtimeId)
        }
        if (pending?.credentials?.runtimeId == runtimeId) { pending = null; accepted = false }
        if (rotation?.previous?.runtimeId == runtimeId) rotation = null
    }
    fun transport(runtimeId: String, api: RemoteApi?): DshLanTransport? = synchronized(lock) {
        live()
        if (rotation?.previous?.runtimeId == runtimeId) throw LanPairingException("e2e.rotation_pending")
        transports.removeAll { it.state.value.phase == DshTransportPhase.CLOSED }
        credentials[runtimeId]?.let { value -> connections.transport(value, signer, api).also { transports.add(it) } }
    }
    fun relayTransport(api: RemoteApi, runtimeId: String): DshRelayTransport = synchronized(lock) {
        live()
        if (rotation?.previous?.runtimeId == runtimeId) throw LanPairingException("e2e.rotation_pending")
        val value = credentials[runtimeId] ?: throw LanPairingException("e2e.repair_required")
        connections.relayTransport(api, value).also { transports.add(it) }
    }
    fun rtcTransport(runtimeId: String, path: DshTransportPath, outer: CarrierOuterDialer,
        api: RemoteApi?): DshRtcTransport = synchronized(lock) {
        live()
        if (rotation?.previous?.runtimeId == runtimeId) throw LanPairingException("e2e.rotation_pending")
        val value = credentials[runtimeId] ?: throw LanPairingException("e2e.repair_required")
        transports.removeAll { it.state.value.phase == DshTransportPhase.CLOSED }
        connections.rtcTransport(value, signer, path, outer, api).also { transports.add(it) }
    }
    fun pairingControl(runtimeId: String, api: RemoteApi?): PairingControlClient = synchronized(lock) {
        live()
        controls.keys.removeAll { it.closed }
        if (rotation?.previous?.runtimeId == runtimeId) throw LanPairingException("e2e.rotation_pending")
        val value = credentials[runtimeId] ?: throw LanPairingException("e2e.repair_required")
        connections.pairingControl(value, signer, api).also { controls[it] = runtimeId }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        synchronized(lock) { transports.forEach(DshTransport::close); transports.clear(); credentials.clear() }
        synchronized(lock) { controls.keys.toList().forEach(AutoCloseable::close); controls.clear() }
        descriptor = null
        // Do not clear pending, or its vault record. A new account generation reconciles by signed GET.
        mutable.value = PairingState()
    }
}
