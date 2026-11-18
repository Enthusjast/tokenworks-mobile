package cn.edu.ustc.tokenworks

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Native grants are typed. An ID on a workspace grant is not a session grant. */
internal class SessionDirectoryAccess(private val lanOwner: Boolean, private val grant: RuntimeDirectoryEntry?) {
    fun read(endpoint: String, id: String? = null): Boolean = lanOwner || granted(endpoint, id, listing = id == null)

    fun write(endpoint: String, id: String): Boolean = lanOwner ||
        (grant?.role in setOf("owner", "operator") && granted(endpoint, id, listing = false))

    fun mayDelete(id: String): Boolean = (lanOwner || grant?.role == "owner") &&
        read("session/follow", id) && write("workspace/unarchiveSession", id)

    private fun granted(endpoint: String, id: String?, listing: Boolean): Boolean {
        val activeGrant = grant ?: return false
        if (activeGrant.expiresAt?.let { it <= System.currentTimeMillis() } == true) return false
        val action = DshProductAccess.actions[endpoint]?.takeIf { it.selected && (!listing || it.globalCall) } ?: return false
        return activeGrant.bindings.any { (resource, actions) ->
            endpoint in actions && resource.kind in action.resources && when (resource.kind) {
                "global" -> resource.id == null
                "session" -> listing || resource.id == null || resource.id == id
                else -> false
            }
        }
    }
}

/** Inherited archive visibility is not native archive membership for a deletion target. */
internal fun directoryDeletionEligible(row: SessionDirectoryEntry, archivedIds: List<String>, access: SessionDirectoryAccess): Boolean =
    row.parentSessionId == null && !row.running && row.id in archivedIds && access.mayDelete(row.id)

/** Local metadata and native content results share the same authorized set of session IDs. */
internal fun sessionDirectorySearch(
    rows: List<SessionDirectoryEntry>,
    query: String,
    results: DshSessionSearchResult?,
    mayShowContent: (String) -> Boolean,
): Map<String, String?> {
    val byId = rows.associateBy { it.id }
    val matches = linkedMapOf<String, String?>()
    for (hit in results?.items.orEmpty()) {
        if (hit.sessionId in byId && mayShowContent(hit.sessionId)) {
            matches.putIfAbsent(hit.sessionId, directoryLine(hit.snippet))
        }
    }
    for (row in rows) {
        val metadata = row.metadata
        if (metadata?.title?.contains(query, ignoreCase = true) == true ||
            metadata?.workspaceTitle?.contains(query, ignoreCase = true) == true) matches.putIfAbsent(row.id, null)
    }
    return matches
}

/** The native archive gate follows a durable subagent lineage, but never fork ancestry. */
internal fun directoryArchivedRows(rows: List<SessionDirectoryEntry>, nativeArchived: Set<String>): Set<String> {
    if (nativeArchived.isEmpty()) return emptySet()
    val rowById = rows.associateBy { it.id }
    val membership = HashMap<String, Boolean>()
    for (row in rows) {
        if (row.id in membership) continue
        val path = linkedSetOf<String>()
        var id: String? = row.id
        while (id != null && id !in nativeArchived && id !in membership && path.add(id)) id = rowById[id]?.parentSessionId
        val archived = id != null && (id in nativeArchived || membership[id] == true)
        path.forEach { membership[it] = archived }
        membership[row.id] = archived
    }
    return membership.filterValues { it }.keys
}

internal enum class DirectorySection { PINNED, RECENT, EARLIER }
internal data class DirectoryPlacement(val entry: SessionDirectoryEntry, val depth: Int)

/** Keep ancestors of filtered matches for context. A pin promotes its subtree, not its old parent. */
internal fun sessionDirectoryGroups(
    rows: List<SessionDirectoryEntry>, recentCut: Long, pins: Set<String>, matches: (SessionDirectoryEntry) -> Boolean,
): Map<DirectorySection, List<DirectoryPlacement>> {
    val byId = rows.associateBy { it.id }
    val visible = HashSet<String>()
    for (row in rows) {
        if (!matches(row)) continue
        var current: SessionDirectoryEntry? = row
        while (current != null && visible.add(current.id) && current.id !in pins) current = byId[current.parentSessionId]
    }
    val included = rows.filter { it.id in visible }.sortedByDescending { it.updatedAt }
    val children = included.groupBy { row -> row.parentSessionId?.takeIf { it in visible && row.id !in pins } }
    val grouped = DirectorySection.entries.associateWith { ArrayList<DirectoryPlacement>() }
    val visited = HashSet<String>()
    val pending = ArrayDeque<DirectoryPlacement>()
    fun append(root: SessionDirectoryEntry) {
        val section = when {
            root.id in pins -> DirectorySection.PINNED
            root.updatedAt >= recentCut -> DirectorySection.RECENT
            else -> DirectorySection.EARLIER
        }
        val target = grouped.getValue(section)
        pending.addLast(DirectoryPlacement(root, 0))
        while (pending.isNotEmpty()) {
            val item = pending.removeLast()
            if (!visited.add(item.entry.id)) continue
            target += item
            val descendants = children[item.entry.id].orEmpty()
            for (index in descendants.indices.reversed()) pending.addLast(DirectoryPlacement(descendants[index], item.depth + 1))
        }
    }
    children[null]?.forEach(::append)
    included.filterNot { it.id in visited }.forEach(::append)
    return grouped
}

internal data class DirectoryArchiveOutcome(val completed: Set<String>, val blocked: Set<String>, val unconfirmed: Map<String, String>)

/** Sequential, non-atomic and never retried: re-check the fence immediately before every send. */
internal suspend fun runDirectoryArchive(
    ids: Set<String>,
    isCurrent: () -> Boolean,
    maySend: (String) -> Boolean,
    send: suspend (String) -> Unit,
): DirectoryArchiveOutcome {
    val completed = linkedSetOf<String>()
    val blocked = linkedSetOf<String>()
    val unconfirmed = linkedMapOf<String, String>()
    for (id in ids) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) break
        if (!maySend(id)) {
            blocked += id
            continue
        }
        try {
            send(id)
            if (!isCurrent()) break
            completed += id
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!isCurrent()) break
            unconfirmed[id] = homeErrorCode(error)
            // The only recoverable per-row refusals are explicit native rejections before writing.
            if (error !is DshFailure || error.code !in setOf("workspace/session-active", "session/not-found")) break
        }
    }
    return DirectoryArchiveOutcome(completed, blocked, unconfirmed)
}

/** An acknowledgement is not the directory source: the UI still waits for workspace/follow. */
internal suspend fun sendDirectoryArchive(connection: DshConnection, id: String, archive: Boolean) {
    val ids = if (archive) {
        val method = DshMethods.WorkspaceArchiveSession
        val request = DshWorkspaceArchiveSessionInput(DshWorkspaceArchiveSessionInputRequest(id, DshOptional.Value(false)))
        method.output.decode(connection.native(method.endpoint, method.input.encode(request) as JsonObject)).archivedSessionIds
    } else {
        val method = DshMethods.WorkspaceUnarchiveSession
        val request = DshWorkspaceUnarchiveSessionInput(DshWorkspaceUnarchiveSessionInputRequest(id))
        method.output.decode(connection.native(method.endpoint, method.input.encode(request) as JsonObject)).archivedSessionIds
    }
    if ((id in ids) != archive) throw ProtocolException()
}

internal fun directoryLine(raw: String): String? = raw.lineSequence().firstNotNullOfOrNull { line ->
    val bounded = line.trim().take(240).filterNot {
        it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' || it in '\u2028'..'\u2029'
    }.trim()
    bounded.takeIf(String::isNotEmpty)?.let { if (line.trim().length <= 240) it else it.take(239).trimEnd().trimEnd { char -> char.isHighSurrogate() } + "…" }
}
