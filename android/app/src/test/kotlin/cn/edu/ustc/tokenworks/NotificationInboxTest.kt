package cn.edu.ustc.tokenworks

import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class NotificationInboxTest {
    private val binding = UUID.randomUUID().toString()
    private val ownerId = UUID.randomUUID().toString()
    private val owner = LocalDataOwner(ownerId, ownerId, "a".repeat(64), ownerId, ownerId)
    private val now = 1_000L

    private fun reference(kind: String, expiry: Long) = PushReference.parse(mapOf(
        "v" to "1", "binding_id" to binding, "notification_id" to UUID.randomUUID().toString(),
        "account_id" to owner.accountId, "organization_id" to owner.organizationId, "deployment_sha256" to owner.deploymentHash,
        "user_id" to owner.userId, "device_id" to owner.deviceId, "owner_fingerprint" to owner.fingerprint,
        "runtime_id" to ownerId, "session_id" to "synthetic-sensitive-routing", "kind" to kind,
        "expires_at" to Instant.ofEpochMilli(expiry).toString(),
    ), now)

    @Test fun inboxProjectsOnlyLocalKeysAndKindsAndRechecksBindingAndExpiry() {
        val inbox = NotificationInbox(capacity = 2)
        val first = reference("interaction", now + 1)
        val second = reference("completed", now + 1_000)
        inbox.accept(first); inbox.accept(first); inbox.accept(second)
        val projected = inbox.projection()
        assertEquals(listOf(NotificationCenterState.Kind.COMPLETED, NotificationCenterState.Kind.INTERACTION), projected.map { it.kind })
        assertTrue(projected.none { it.id == first.notificationId || it.id == second.notificationId })
        assertFalse(projected.toString().contains("synthetic-sensitive-routing"))
        assertSame(second, inbox.resolve(projected.first().id))
        inbox.removeInvalid(owner, binding, now + 1)
        assertEquals(listOf(projected.first()), inbox.projection())
        assertNull(inbox.resolve(projected.last().id))
        inbox.removeInvalid(owner, "rotated", now + 2)
        assertTrue(inbox.projection().isEmpty())
    }

    @Test fun boundedInboxNeverReusesEvictedOrClearedRowIds() {
        val inbox = NotificationInbox(capacity = 1)
        inbox.accept(reference("interaction", now + 1_000))
        val first = inbox.projection().single().id
        inbox.accept(reference("completed", now + 1_000))
        val second = inbox.projection().single().id
        assertNull(inbox.resolve(first)); assertNotEquals(first, second)
        inbox.clear()
        inbox.accept(reference("interaction", now + 1_000))
        assertNull(inbox.resolve(second)); assertNotEquals(second, inbox.projection().single().id)
    }
}
