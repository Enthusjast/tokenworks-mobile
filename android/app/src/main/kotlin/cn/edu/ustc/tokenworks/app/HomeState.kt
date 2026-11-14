package cn.edu.ustc.tokenworks

import android.net.Uri

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
    val notify: NotifyUiState = NotifyUiState(), val notifySettingsOpen: Boolean = false,
    val backgroundReadOnly: Boolean = false,
    val binding: HostBindingState? = null, val pendingBindingId: String? = null,
    val creation: SessionCreationState? = null,
    val composer: MessageComposerState? = null,
    val interactions: List<PendingInteraction> = emptyList(),
    val interactionError: String? = null,
    val terminal: TerminalWatcher? = null,
    val files: FilesCoordinator? = null,
    val pendingPrompts: List<PromptOutboxEntry> = emptyList(),
    val outboxError: String? = null,
    val lanOnly: Boolean = false, val canUseLanOnly: Boolean = false,
    val pairing: PairingState? = null, val pairingOpen: Boolean = false,
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

internal fun homeErrorCode(error: Exception): String = when (error) {
        is DshFailure -> error.code
        is OutboxException -> "outbox.${error.failure.name.lowercase()}"
        is CommandDataException -> error.code
        is LoginException -> "login.${error.failure.name.lowercase()}"
        is RemoteRequestException -> error.code
        is DeviceCryptoException -> "device.${error.failure.name.lowercase()}"
        is DeviceProofException -> "auth.proof_invalid"
        is VaultException -> "vault.${error.failure.name.lowercase()}"
        else -> "network.unavailable"
    }
