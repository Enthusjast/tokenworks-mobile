package cn.edu.ustc.tokenworks

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class DshRecoveryPolicyTest {
    @Test fun actualRemoteExceptionsAndNativeSlashCodesAreTerminalForAuthorityFailures() {
        listOf("auth.required", "auth.proof_invalid", "lease.expired", "lease.unknown", "auth/required", "lease/expired").forEach { code ->
            listOf(RemoteRequestException(code), DshFailure(code)).forEach { error ->
                val decision = DshRecoveryPolicy.classify(error)
                assertEquals(DshRecoveryAction.AUTH_REQUIRED, decision.action)
                assertTrue(decision.refused)
                assertEquals(code, decision.code)
            }
        }
        listOf("access.revoked", "device.invalid", "remote-access/denied", "gateway/interactions-forbidden").forEach { code ->
            assertEquals(DshRecoveryAction.REVOKED, DshRecoveryPolicy.classify(DshFailure(code)).action)
            assertTrue(DshRecoveryPolicy.classify(DshFailure(code)).refused)
        }
        assertEquals(DshRecoveryAction.REVOKED, DshRecoveryPolicy.classify(DshFailure("gateway/peer-disposed")).action)
        assertFalse(DshRecoveryPolicy.classify(DshFailure("gateway/peer-disposed")).refused)
    }

    @Test fun protocolFailuresRequireManualActionAndResultFailuresKeepUnknownOutcomes() {
        listOf("protocol.unsupported", "gateway/protocol", "gateway/signature-invalid", "gateway/result-invalid", "network.invalid_response").forEach { code ->
            assertEquals(DshRecoveryAction.MANUAL_RETRY, DshRecoveryPolicy.classify(DshFailure(code)).action)
        }
        assertEquals(DshRecoveryAction.MANUAL_RETRY, DshRecoveryPolicy.classify(ProtocolException()).action)
        assertEquals(DshRecoveryAction.MANUAL_RETRY, DshRecoveryPolicy.classify(SessionDataException()).action)
        assertTrue(DshRecoveryPolicy.classify(DshFailure("gateway/input-invalid")).refused)
        assertFalse(DshRecoveryPolicy.classify(DshFailure("gateway/result-invalid")).refused)
        assertFalse(DshRecoveryPolicy.classify(DshFailure("gateway/protocol")).refused)
    }

    @Test fun networkBackoffNeverClaimsCommandWasRefused() {
        listOf(RemoteRequestException(), DshFailure("gateway/internal"), DshFailure("native mux closed"), IOException("offline")).forEach { error ->
            val decision = DshRecoveryPolicy.classify(error)
            assertEquals(DshRecoveryAction.BACKOFF, decision.action)
            assertFalse(decision.refused)
        }
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L), (0..6).map(DshRecoveryPolicy::backoffMillis))
        assertEquals(1000L, DshRecoveryPolicy.backoffMillis(-1))
        assertEquals(30000L, DshRecoveryPolicy.backoffMillis(Int.MAX_VALUE))
    }

    @Test fun cancellationAndUnknownFailuresDoNotTriggerReconnectLoops() {
        assertEquals(DshRecoveryAction.CANCELLED, DshRecoveryPolicy.classify(CancellationException()).action)
        assertEquals(DshRecoveryAction.MANUAL_RETRY, DshRecoveryPolicy.classify(IllegalStateException("private detail")).action)
        assertEquals("request.failed", DshRecoveryPolicy.classify(IllegalStateException("private detail")).code)
        assertEquals(DshRecoveryAction.MANUAL_RETRY, DshRecoveryPolicy.classify(DshFailure("unknown/future-code")).action)
    }
}
