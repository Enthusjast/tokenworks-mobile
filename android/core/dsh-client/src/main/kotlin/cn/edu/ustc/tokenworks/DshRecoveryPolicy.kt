package cn.edu.ustc.tokenworks

import java.io.IOException
import kotlinx.coroutines.CancellationException

enum class DshRecoveryAction { AUTH_REQUIRED, REVOKED, MANUAL_RETRY, BACKOFF, CANCELLED }
data class DshRecoveryDecision(val action: DshRecoveryAction, val code: String, val refused: Boolean = false)

/** Classifies failures only; BACKOFF permits reconnecting reads, never replaying commands. */
object DshRecoveryPolicy {
    private val revoked = setOf(
        "access.revoked", "access.denied", "device.invalid", "device.registration_conflict",
        "access/revoked", "access/denied", "device/invalid", "device/registration_conflict",
        "gateway/interactions-forbidden", "gateway/forbidden", "gateway/peer-disposed", "remote-access/denied", "lan.revoked",
    )
    private val protocol = setOf(
        "gateway/protocol", "gateway/arguments-invalid", "gateway/input-invalid", "gateway/result-invalid",
        "gateway/signature-invalid", "gateway/binding-invalid", "gateway/provider-mismatch",
        "gateway/ambiguous-endpoint", "gateway/definition-unavailable", "gateway/invocation-unavailable",
        "gateway/method-unavailable", "gateway/uplink-overflow",
    )
    private val preExecution = setOf(
        "gateway/arguments-invalid", "gateway/input-invalid", "gateway/signature-invalid", "gateway/binding-invalid",
        "gateway/provider-mismatch", "gateway/ambiguous-endpoint", "gateway/definition-unavailable",
        "gateway/invocation-unavailable", "gateway/method-unavailable", "gateway/interactions-forbidden",
    )

    fun classify(error: Throwable): DshRecoveryDecision = when (error) {
        is CancellationException -> DshRecoveryDecision(DshRecoveryAction.CANCELLED, "request.cancelled")
        is RemoteRequestException -> classify(error.code)
        is DshFailure -> classify(error.code)
        is ProtocolException, is SessionDataException -> DshRecoveryDecision(DshRecoveryAction.MANUAL_RETRY, "protocol.unsupported")
        is IOException -> DshRecoveryDecision(DshRecoveryAction.BACKOFF, "network.unavailable")
        else -> DshRecoveryDecision(DshRecoveryAction.MANUAL_RETRY, "request.failed")
    }

    fun classify(code: String): DshRecoveryDecision {
        val auth = code.startsWith("auth.") || code.startsWith("auth/") || code.startsWith("lease.") || code.startsWith("lease/")
        val action = when {
            code in revoked -> DshRecoveryAction.REVOKED
            auth || code in setOf("gateway/unauthorized", "lan.auth_required") -> DshRecoveryAction.AUTH_REQUIRED
            code.startsWith("protocol.") || code.startsWith("protocol/") || code in protocol ||
                code == "network.invalid_response" || code.startsWith("session.") && code != "session.stream_ended" -> DshRecoveryAction.MANUAL_RETRY
            code.startsWith("network.") || code.startsWith("network/") || code.startsWith("relay.") ||
                code in setOf("wire/disconnected", "rtc.unavailable", "session.stream_ended", "native mux closed", "native events stream ended") ||
                code in setOf("gateway/internal", "gateway/cancelled", "gateway/service-unavailable", "gateway/context-unavailable", "gateway/lookup-unavailable") -> DshRecoveryAction.BACKOFF
            else -> DshRecoveryAction.MANUAL_RETRY
        }
        // A disposed peer or malformed result may occur after the command executed.
        val refused = auth || code in preExecution || code in revoked && code != "gateway/peer-disposed"
        return DshRecoveryDecision(action, code, refused)
    }

    fun backoffMillis(attempt: Int): Long = minOf(30_000L, 1_000L shl attempt.coerceIn(0, 5))
}
