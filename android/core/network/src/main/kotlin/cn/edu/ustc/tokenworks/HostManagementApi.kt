package cn.edu.ustc.tokenworks

import java.net.URLEncoder

/** Metadata is not a runtime grant. Every page and mutation uses the existing DPoP resource boundary. */
data class ManagementPage<T>(val items: List<T>, val nextCursor: String?)
data class ManagedDevice(val id: String, val name: String, val state: String, val current: Boolean)
data class ManagedInstallation(val id: String, val hostId: String, val alias: String, val state: String, val owner: Boolean)
data class ManagedProfile(val id: String, val state: String)
data class ManagedGrant(val userId: String, val role: String, val state: String, val version: Long)
sealed interface ManagementTarget {
    data class Device(val id: String) : ManagementTarget
    data class Installation(val id: String) : ManagementTarget
    data class Profile(val installationId: String, val runtimeId: String) : ManagementTarget
    data class Grant(val runtimeId: String, val userId: String) : ManagementTarget
}

class HostManagementApi internal constructor(private val request: suspend (String, String) -> JsonObject) {
    constructor(api: RemoteApi) : this({ method, path -> api.resourceJson(method, path) })
    private fun data(root: JsonObject): JsonObject {
        root.exactKeys("ok", "data")
        if (root["ok"] != JsonBoolean(true)) throw ProtocolException()
        return root.obj("data")
    }
    private suspend fun <T> page(path: String, cursor: String?, parse: (JsonObject) -> T): ManagementPage<T> {
        if (cursor != null && (cursor.isEmpty() || cursor.length > 1024)) throw ProtocolException()
        val query = "?limit=50" + (cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        val body = data(request("GET", path + query))
        if (path.startsWith("/v1/installations/") && path.endsWith("/runtimes") && body.string("installation_id") != path.split('/')[3]) throw ProtocolException()
        if (path.startsWith("/v1/runtimes/") && path.endsWith("/grants") && body.string("runtime_id") != path.split('/')[3]) throw ProtocolException()
        val rows = body.array("items")
        if (rows.size > 50) throw ProtocolException()
        val next = when (val value = body["next_cursor"]) {
            JsonNull -> null
            is JsonString -> value.value.also { if (it.isEmpty() || it.length > 1024 || it == cursor) throw ProtocolException() }
            else -> throw ProtocolException()
        }
        return ManagementPage(rows.map { parse(it as? JsonObject ?: throw ProtocolException()) }, next)
    }
    suspend fun runtimeDirectory(cursor: String? = null) = page("/v1/runtime-directory", cursor, RuntimeDirectoryEntry::parse)
    suspend fun hostDirectory(cursor: String? = null) = page("/v1/host-directory", cursor) {
        Contract.uuid(it.string("id")) to Contract.boundedText(it.string("alias"), 640)
    }
    suspend fun devices(cursor: String? = null) = page("/v1/devices", cursor) {
        ManagedDevice(Contract.uuid(it.string("device_id")), Contract.boundedText(it.string("name"), 640),
            Contract.boundedText(it.string("state"), 64), (it["is_current"] as? JsonBoolean)?.value ?: throw ProtocolException())
    }
    suspend fun installations(cursor: String? = null) = page("/v1/installations", cursor) {
        ManagedInstallation(Contract.uuid(it.string("installation_id")), Contract.uuid(it.string("host_id")),
            Contract.boundedText(it.string("host_alias"), 640), Contract.boundedText(it.string("state"), 64),
            (it["is_owner"] as? JsonBoolean)?.value ?: throw ProtocolException())
    }
    suspend fun profiles(installationId: String, cursor: String? = null) = page("/v1/installations/${Contract.uuid(installationId)}/runtimes", cursor) {
        ManagedProfile(Contract.uuid(it.string("runtime_id")), Contract.boundedText(it.string("state"), 64))
    }
    suspend fun grants(runtimeId: String, cursor: String? = null) = page("/v1/runtimes/${Contract.uuid(runtimeId)}/grants", cursor) {
        ManagedGrant(Contract.uuid(it.string("user_id")), Contract.boundedText(it.string("role"), 64),
            Contract.boundedText(it.string("state"), 64), it.integer("grant_version", 1))
    }
    suspend fun revoke(target: ManagementTarget) {
        val path = when (target) {
            is ManagementTarget.Device -> "/v1/devices/${Contract.uuid(target.id)}"
            is ManagementTarget.Installation -> "/v1/installations/${Contract.uuid(target.id)}"
            is ManagementTarget.Profile -> "/v1/installations/${Contract.uuid(target.installationId)}/runtimes/${Contract.uuid(target.runtimeId)}"
            is ManagementTarget.Grant -> "/v1/runtimes/${Contract.uuid(target.runtimeId)}/grants/${Contract.uuid(target.userId)}"
        }
        if (data(request("DELETE", path))["revoked"] != JsonBoolean(true)) throw ProtocolException()
    }
}
