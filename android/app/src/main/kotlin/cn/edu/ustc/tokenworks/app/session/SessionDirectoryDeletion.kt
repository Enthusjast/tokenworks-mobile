package cn.edu.ustc.tokenworks

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val DIRECTORY_DELETION_LIMIT = 16
private const val STATUS = "sessionDeletion/status"
private const val DELETE = "sessionDeletion/delete"
private const val UNAVAILABLE = "session-deletion/unavailable"

internal fun Set<String>.isDirectoryDeletionBatch(): Boolean = size in 1..DIRECTORY_DELETION_LIMIT

private fun sessionArgs(id: String) = jsonObject("sessionId" to id.json())

private suspend fun deletionAvailable(id: String, native: suspend (String, JsonObject) -> JsonValue): Boolean {
    val response = native(STATUS, sessionArgs(id)) as? JsonObject ?: throw ProtocolException()
    if (response.fields.size != 1) throw ProtocolException()
    return (response.fields["available"] as? JsonBoolean)?.value ?: throw ProtocolException()
}

/** Probe only the exact current selection, never an unselected sample or a truncated batch. */
internal suspend fun probeDirectoryDeletion(
    ids: Set<String>,
    isCurrent: () -> Boolean,
    mayProbe: (String) -> Boolean,
    native: suspend (String, JsonObject) -> JsonValue,
): Boolean {
    if (!ids.isDirectoryDeletionBatch()) return false
    try {
        for (id in ids) {
            currentCoroutineContext().ensureActive()
            if (!isCurrent() || !mayProbe(id) || !deletionAvailable(id, native)) return false
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return false
    }
    return isCurrent() && ids.all(mayProbe)
}

internal data class DirectoryDeletionOutcome(val confirmed: Set<String>, val uncertain: Boolean = false)

/** A fresh status and a live local check must precede each write. Only a strict acknowledgement counts. */
internal suspend fun runDirectoryDeletion(
    ids: Set<String>,
    isCurrent: () -> Boolean,
    mayDelete: (String) -> Boolean,
    native: suspend (String, JsonObject) -> JsonValue,
): DirectoryDeletionOutcome {
    val confirmed = linkedSetOf<String>()
    if (!ids.isDirectoryDeletionBatch()) return DirectoryDeletionOutcome(confirmed)
    for (id in ids) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) break
        if (!mayDelete(id)) continue
        val available = try {
            deletionAvailable(id, native)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            break // No write for this target. The batch cannot establish fresh host eligibility.
        }
        if (!available) continue
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) break
        if (!mayDelete(id)) continue
        try {
            val response = native(DELETE, sessionArgs(id)) as? JsonObject ?: throw ProtocolException()
            if (response.fields.size != 1 || response.fields["deleted"] != JsonBoolean(true)) throw ProtocolException()
            confirmed += id
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // This one native code guarantees the write did not start. All other failures are ambiguous.
            if (error !is DshFailure || error.code != UNAVAILABLE) return DirectoryDeletionOutcome(confirmed, uncertain = true)
        }
    }
    return DirectoryDeletionOutcome(confirmed)
}
