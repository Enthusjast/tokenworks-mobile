package cn.edu.ustc.tokenworks

import java.time.Instant

/** References are hints only; opening one must re-authorize against the current directory. */
class PushReference private constructor(
    val bindingId: String, val notificationId: String, val owner: LocalDataOwner,
    val runtimeId: String, val sessionId: String, val kind: String, val expiresAt: Long,
) {
    override fun toString() = "PushReference"
    fun matches(owner: LocalDataOwner, binding: String?, now: Long): Boolean =
        this.owner.fingerprint == owner.fingerprint && bindingId == binding && expiresAt > now && expiresAt <= now + 900_000
    fun data(): Map<String, String> = mapOf("v" to "1", "binding_id" to bindingId, "notification_id" to notificationId,
        "account_id" to owner.accountId, "organization_id" to owner.organizationId, "deployment_sha256" to owner.deploymentHash,
        "user_id" to owner.userId, "device_id" to owner.deviceId, "owner_fingerprint" to owner.fingerprint,
        "runtime_id" to runtimeId, "session_id" to sessionId, "kind" to kind, "expires_at" to Instant.ofEpochMilli(expiresAt).toString())
    companion object {
        private val keys = setOf("v", "binding_id", "notification_id", "account_id", "organization_id", "deployment_sha256", "user_id", "device_id", "owner_fingerprint", "runtime_id", "session_id", "kind", "expires_at")
        fun parse(data: Map<String, String>, now: Long): PushReference {
            if (data.keys != keys || data.values.any { it.length > 512 } || data["v"] != "1") throw ProtocolException()
            fun field(key: String) = data.getValue(key)
            val owner = LocalDataOwner(field("account_id"), field("organization_id"), field("deployment_sha256"), field("user_id"), field("device_id"))
            if (owner.fingerprint != field("owner_fingerprint")) throw ProtocolException()
            val session = field("session_id")
            if (session.isEmpty() || session.length > 256 || session.any { it.code < 33 || it.code == 127 }) throw ProtocolException()
            val expiry = try { Instant.parse(field("expires_at")).toEpochMilli() } catch (_: Exception) { throw ProtocolException() }
            if (expiry <= now || expiry > now + 900_000 || field("kind") !in setOf("interaction", "completed")) throw ProtocolException()
            return PushReference(Contract.uuid(field("binding_id")), Contract.uuid(field("notification_id")), owner,
                Contract.uuid(field("runtime_id")), session, field("kind"), expiry)
        }
    }
}

/** Uses the same resource DPoP owner as all other authenticated resource operations. */
class PushRegistrationApi(private val api: RemoteApi) {
    suspend fun register(owner: LocalDataOwner, token: String): String {
        if (token.length !in 16..4096 || token.any { it.code < 33 || it.code > 126 }) throw ProtocolException()
        val root = api.resourceJson("POST", "/v1/push/registrations", jsonObject("provider" to "fcm".json(), "token" to token.json(),
            "account_id" to owner.accountId.json(), "organization_id" to owner.organizationId.json(),
            "deployment_sha256" to owner.deploymentHash.json(), "owner_fingerprint" to owner.fingerprint.json()))
        root.exactKeys("ok", "data")
        if (root["ok"] != JsonBoolean(true)) throw RemoteRequestException("push.registration_failed")
        val data = root.obj("data"); data.exactKeys("binding_id")
        return Contract.uuid(data.string("binding_id"))
    }
    suspend fun revoke(bindingId: String) {
        val root = api.resourceJson("DELETE", "/v1/push/registrations/${Contract.uuid(bindingId)}")
        root.exactKeys("ok", "data")
        if (root["ok"] != JsonBoolean(true)) throw RemoteRequestException("push.revocation_failed")
    }
}
