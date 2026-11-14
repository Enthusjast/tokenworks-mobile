package cn.edu.ustc.tokenworks

import org.junit.Assert.*
import org.junit.Test

class DshLeaseTest {
    @Test fun leaseUsesThirtySecondSafetyMargin() {
        assertTrue(DshLease("token", "lease", 1_031_000).usable(1_000_000))
        assertFalse(DshLease("token", "lease", 1_030_000).usable(1_000_000))
    }
}
