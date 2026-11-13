package cn.edu.ustc.tokenworks

import org.junit.Assert.*
import org.junit.Test

class SessionPhaseTest {
    private fun phase(code: String) = DshSessionReader.phaseFor(code)

    @Test fun revokedCodesAreTerminal() {
        for (code in listOf("access.revoked", "access.denied", "device.invalid", "device.registration_conflict"))
            assertEquals(code, SessionReadPhase.REVOKED, phase(code))
    }

    @Test fun authAndLeaseCodesRequireReauthentication() {
        for (code in listOf("auth.expired", "auth.proof_invalid", "auth.required", "lease.expired", "lease.unknown"))
            assertEquals(code, SessionReadPhase.AUTH_REQUIRED, phase(code))
    }

    @Test fun protocolAndSessionCodesAreManualRetryErrors() {
        for (code in listOf("protocol.unsupported", "session.not_found", "session.stream_ended-ended"))
            assertEquals(code, SessionReadPhase.ERROR, phase(code))
        // The generic stream-end code retries: the Host may come back.
        assertEquals(SessionReadPhase.BACKOFF, phase("session.stream_ended"))
    }

    @Test fun transportAndUnknownCodesBackOff() {
        for (code in listOf("relay.disconnected", "network.unavailable", "gateway/internal", "anything"))
            assertEquals(code, SessionReadPhase.BACKOFF, phase(code))
    }
}
