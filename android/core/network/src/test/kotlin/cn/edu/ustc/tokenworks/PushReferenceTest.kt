package cn.edu.ustc.tokenworks

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class PushReferenceTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val other = "22222222-2222-4222-8222-222222222222"
    private val now = 1_800_000_000_000L
    private val owner = LocalDataOwner(id, id, "a".repeat(64), id, id)
    private fun data() = mapOf("v" to "1", "binding_id" to id, "notification_id" to other, "account_id" to id,
        "organization_id" to id, "deployment_sha256" to owner.deploymentHash, "user_id" to id, "device_id" to id,
        "owner_fingerprint" to owner.fingerprint, "runtime_id" to id, "session_id" to "opaque-native-session",
        "kind" to "interaction", "expires_at" to Instant.ofEpochMilli(now + 60_000).toString())

    @Test fun exactReferenceRoundTripsWithoutContentAndHasNoAuthority() {
        val ref = PushReference.parse(data(), now)
        assertEquals(data(), ref.data())
        assertEquals("PushReference", ref.toString())
        assertTrue(ref.matches(owner, id, now))
        assertFalse(ref.matches(owner, other, now))
        assertFalse(ref.matches(owner, null, now))
    }
    @Test fun everyForeignOwnerDimensionRejectsTheReference() {
        val ref = PushReference.parse(data(), now)
        val foreign = listOf(LocalDataOwner(other, id, owner.deploymentHash, id, id), LocalDataOwner(id, other, owner.deploymentHash, id, id),
            LocalDataOwner(id, id, "b".repeat(64), id, id), LocalDataOwner(id, id, owner.deploymentHash, other, id), LocalDataOwner(id, id, owner.deploymentHash, id, other))
        foreign.forEach { assertFalse(ref.matches(it, id, now)) }
        for (key in listOf("account_id", "organization_id", "user_id", "device_id")) {
            assertThrows(Exception::class.java) { PushReference.parse(data() + (key to other), now) }
        }
    }
    @Test fun expiryIsCheckedAtReceiveAndAgainAtClick() {
        val ref = PushReference.parse(data(), now)
        assertFalse(ref.matches(owner, id, now + 60_000))
        for (expiry in listOf(now, now - 1, now + 900_001)) {
            assertThrows(Exception::class.java) { PushReference.parse(data() + ("expires_at" to Instant.ofEpochMilli(expiry).toString()), now) }
        }
    }
    @Test fun malformedOrContentBearingPayloadNeverReachesNotificationRendering() {
        val invalid = listOf(data() - "binding_id", data() + ("body" to "FORBIDDEN"), data() + ("title" to "FORBIDDEN"),
            data() + ("tool_args" to "FORBIDDEN"), data() + ("kind" to "approved"), data() + ("runtime_id" to "foreign-runtime"),
            data() + ("session_id" to ""), data() + ("session_id" to "a\nb"), data() + ("session_id" to "a".repeat(257)),
            data() + ("expires_at" to "invalid"), data() + ("v" to "2"), data() + ("owner_fingerprint" to "b".repeat(64)))
        invalid.forEach { assertThrows(Exception::class.java) { PushReference.parse(it, now) } }
    }
}
