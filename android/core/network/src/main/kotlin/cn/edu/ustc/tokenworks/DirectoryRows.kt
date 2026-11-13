package cn.edu.ustc.tokenworks

import java.util.Collections

/**
 * One native dsh remoteAccess grant: the resource the actions apply to plus the
 * endpoint names themselves. `global` covers every resource of that namespace.
 */
data class GrantBinding(val resource: NativeResource, val actions: Set<String>) {
    companion object {
        fun parse(root: JsonObject): GrantBinding {
            val actions = root.array("actions").map { value ->
                val action = value as? JsonString ?: throw ProtocolException()
                Contract.boundedText(action.value, 128)
            }.toSet()
            if (actions.isEmpty()) throw ProtocolException()
            return GrantBinding(NativeResource.parse(root.obj("resource")), Collections.unmodifiableSet(actions))
        }
    }
}

data class NativeResource(val kind: String, val id: String?) {
    companion object {
        fun parse(root: JsonObject): NativeResource {
            val kind = root.string("kind")
            if (kind !in setOf("global", "session", "workspace")) throw ProtocolException()
            val id = when (val value = root.fields["id"]) {
                null, JsonNull -> null
                is JsonString -> Contract.boundedText(value.value, 512)
                else -> throw ProtocolException()
            }
            // A global grant covers every namespace and carries no id; a session
            // or workspace grant without an id covers every resource of that kind.
            if (kind == "global" && id != null) throw ProtocolException()
            return NativeResource(kind, id)
        }
    }
}

/** One runtime the account may reach, with this user's native grants on it. */
class RuntimeDirectoryEntry private constructor(
    val id: String, val hostId: String, val organizationId: String?, val dshVersion: String,
    val applicationVersion: String?, val pluginVersion: String?, val lastSeenAt: Long?,
    val grantVersion: Long?, val role: String?, val expiresAt: Long?, val bindings: List<GrantBinding>,
) {
    /**
     * A call is granted when an unexpired binding names its endpoint and applies to
     * every resource the call touches. A binding without a resource id covers every
     * resource of its kind; session-scoped grants also cover calls that carry no
     * resource (the list itself is filtered by dsh).
     */
    fun allows(endpoint: String, vararg resources: String?, now: Long = System.currentTimeMillis()): Boolean {
        if (expiresAt != null && expiresAt <= now) return false
        val scoped = resources.filterNotNull()
        return bindings.any { binding ->
            binding.actions.contains(endpoint) && when (binding.resource.kind) {
                "global" -> true
                "session", "workspace" -> binding.resource.id == null || scoped.isEmpty() || scoped.any { it == binding.resource.id }
                else -> false
            }
        }
    }

    fun canList(now: Long = System.currentTimeMillis()) = allows("session/list", now = now)
    fun canCreate(now: Long = System.currentTimeMillis()) = allows("session/create", now = now)
    fun canFollow(sessionId: String, now: Long = System.currentTimeMillis()) = allows("session/follow", sessionId, now = now)
    fun canPrompt(sessionId: String, now: Long = System.currentTimeMillis()) = allows("session/prompt", sessionId, now = now)
    fun canCancel(sessionId: String, now: Long = System.currentTimeMillis()) = allows("session/cancel", sessionId, now = now)
    fun canRename(sessionId: String, now: Long = System.currentTimeMillis()) = allows("session/rename", sessionId, now = now)
    fun canFork(sessionId: String, now: Long = System.currentTimeMillis()) = allows("session/fork", sessionId, now = now)
    fun canArchive(sessionId: String, now: Long = System.currentTimeMillis()) = allows("workspace/archiveSession", sessionId, now = now)
    fun canSelectModel(sessionId: String, now: Long = System.currentTimeMillis()) = allows("session/selectModel", sessionId, now = now)
    fun canReadModelCatalog(now: Long = System.currentTimeMillis()) = allows("session/modelCatalog", now = now)

    companion object {
        fun parse(root: JsonObject): RuntimeDirectoryEntry {
            val organization = if ("organization_id" in root.fields) Contract.uuid(root.string("organization_id")) else null
            val version = if ("dsh_version" in root.fields) root.string("dsh_version").also { if (it.isNotEmpty()) Contract.boundedText(it, 128) } else ""
            val access = if ("access" in root.fields) root.obj("access") else null
            if (access == null) return RuntimeDirectoryEntry(
                Contract.uuid(root.string("id")), Contract.uuid(root.string("host_id")), organization, version,
                text(root, "application_version"), text(root, "plugin_version"), time(root, "last_seen_at"), null, null, null, emptyList(),
            )
            val bindings = access.array("bindings").map { GrantBinding.parse(it as JsonObject) }
            if (bindings.size > 512) throw RemoteRequestException("network.invalid_response")
            return RuntimeDirectoryEntry(
                Contract.uuid(root.string("id")), Contract.uuid(root.string("host_id")), organization, version,
                text(root, "application_version"), text(root, "plugin_version"), time(root, "last_seen_at"),
                if ("grant_version" in access.fields) access.integer("grant_version", 0) else null,
                if ("role" in access.fields) Contract.boundedText(access.string("role"), 64) else null,
                time(access, "expires_at"), Collections.unmodifiableList(bindings),
            )
        }

        private fun text(root: JsonObject, key: String): String? = when (val value = root.fields[key]) {
            null, JsonNull -> null
            is JsonString -> Contract.boundedText(value.value, 256)
            else -> throw RemoteRequestException("network.invalid_response")
        }

        private fun time(root: JsonObject, key: String): Long? = when (val value = root.fields[key]) {
            null, JsonNull -> null
            is JsonString -> relayTime(value.value)
            else -> throw RemoteRequestException("network.invalid_response")
        }
    }
}

/**
 * One row of the native session directory. Titles are untrusted display hints;
 * the id is the dsh session id and is never rewritten.
 */
class SessionDirectoryEntry private constructor(
    val id: String, val workspaceId: String?, val state: String, val lastPersisted: Long, val updatedAt: Long,
    val metadata: DirectoryDisplayMetadata?,
) {
    val running get() = state == "running"
    val blank get() = metadata?.blank == true

    /** Grants this row needs to be opened for reading; a missing grant hides it. */
    fun viewScope(runtime: RuntimeDirectoryEntry, now: Long = System.currentTimeMillis()): Boolean =
        runtime.canFollow(id, now)

    override fun toString() = "SessionDirectoryEntry($state)"

    companion object {
        /** Built from one `session/list` item; unknown projection fields are ignored. */
        fun from(item: DshSessionListResultItemsItem): SessionDirectoryEntry {
            if (!item.agentAvailable) return SessionDirectoryEntry(
                item.sessionId, null, "unavailable", -1, item.updatedAt.toDirectoryTime(), null,
            )
            val values = (item.projections as? DshOptional.Value)?.value?.values
            val workingDirectory = (values?.workingDirectory as? DshOptional.Value)?.value
            val title = (values?.title as? DshOptional.Value)?.value
            val kind = (item.projections as? DshOptional.Value)?.value?.kind
            val asOf = (item.projections as? DshOptional.Value)?.value?.asOfSeq?.toDirectoryTime()
            val metadata = if (title == null && workingDirectory == null) null
            else DirectoryDisplayMetadata.native(title, workingDirectory, kind, asOf, item.blank)
            return SessionDirectoryEntry(
                item.sessionId, workingDirectory, if (item.running) "running" else "available",
                -1, item.updatedAt.toDirectoryTime(), metadata,
            )
        }

        private fun Double.toDirectoryTime(): Long = if (!isFinite() || isNaN()) -1L else toLong()
    }
}

/** A session pinned to the runtime that serves it. */
class SessionTarget(val runtime: RuntimeDirectoryEntry, val session: SessionDirectoryEntry) {
    override fun toString() = "SessionTarget(${session.state})"
}
