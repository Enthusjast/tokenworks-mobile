package cn.edu.ustc.tokenworks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingNavigationTest {
    @Test fun uncommittedOrUnknownHostAuthorizationCannotBeDismissed() {
        assertTrue(PairingState(phase = PairingPhase.SAVE_REQUIRED).blocksNavigation())
        assertTrue(PairingState(phase = PairingPhase.UNKNOWN).blocksNavigation())
        for (phase in PairingPhase.entries) {
            assertTrue(PairingState(phase = phase, busy = true).blocksNavigation())
        }
    }

    @Test fun reviewedOrDurablySavedPairingCanLeaveWithoutPretendingToRevoke() {
        assertFalse((null as PairingState?).blocksNavigation())
        assertFalse(PairingState(phase = PairingPhase.ENTRY).blocksNavigation())
        assertFalse(PairingState(phase = PairingPhase.REVIEW).blocksNavigation())
        assertFalse(PairingState(phase = PairingPhase.READY).blocksNavigation())
    }
}
