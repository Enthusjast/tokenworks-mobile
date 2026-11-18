package cn.edu.ustc.tokenworks

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class DshWorkspace(
    val id: String,
    val path: String,
    val title: String,
    val sessionIds: List<String>,
)

data class DshWorkspaceSnapshot(
    val workspaces: List<DshWorkspace>,
    val archivedSessionIds: List<String>,
    val pinnedSessionIds: List<String>,
)

/** The follow stream is authoritative; mutation responses are never folded into the directory. */
class DshWorkspaceClient(private val transport: DshTransport) {
    /** Owns one subscription generation. Recollect to reconnect and obtain a fresh baseline. */
    fun follow(): Flow<DshWorkspaceSnapshot> = flow {
        val method = DshMethods.WorkspaceFollow
        val mux = transport.openMux()
        try {
            val args = method.input.encode(emptyMap<String, JsonValue>())
            mux.open(method.endpoint, jsonObject("args" to args)).use { stream ->
                var snapshot: DshWorkspaceSnapshot? = null
                while (true) {
                    when (val frame = stream.next()) {
                        is DshMuxFrame.Item -> {
                            val item = (frame.value as? DshOptional.Value)?.value ?: throw ProtocolException()
                            snapshot = reduceWorkspace(snapshot, method.output.decode(item))
                            emit(snapshot)
                        }
                        is DshMuxFrame.Error -> throw DshFailure(frame.error.code)
                        is DshMuxFrame.End, null -> throw DshFailure("network.workspace_stream_ended")
                    }
                }
            }
        } finally {
            mux.close()
        }
    }

    suspend fun create(path: String) = call(
        DshMethods.WorkspaceCreate, DshWorkspaceCreateInput(DshWorkspaceCreateInputRequest(path)),
    )

    suspend fun rename(workspaceId: String, title: String) = call(
        DshMethods.WorkspaceRename, DshWorkspaceRenameInput(DshWorkspaceRenameInputRequest(workspaceId, title)),
    )

    suspend fun delete(workspaceId: String) = call(
        DshMethods.WorkspaceDelete, DshWorkspaceDeleteInput(DshWorkspaceDeleteInputRequest(workspaceId)),
    )

    suspend fun insertBefore(workspaceId: String, beforeWorkspaceId: String? = null) = call(
        DshMethods.WorkspaceInsertBefore,
        DshWorkspaceInsertBeforeInput(DshWorkspaceInsertBeforeInputRequest(workspaceId, beforeWorkspaceId.optional())),
    )

    suspend fun insertSessionBefore(workspaceId: String, sessionId: String, beforeSessionId: String? = null) = call(
        DshMethods.WorkspaceInsertSessionBefore,
        DshWorkspaceInsertSessionBeforeInput(
            DshWorkspaceInsertSessionBeforeInputRequest(workspaceId, sessionId, beforeSessionId.optional()),
        ),
    )

    suspend fun pinSession(sessionId: String) = call(
        DshMethods.WorkspacePinSession, DshWorkspacePinSessionInput(DshWorkspacePinSessionInputRequest(sessionId)),
    )

    suspend fun unpinSession(sessionId: String) = call(
        DshMethods.WorkspaceUnpinSession, DshWorkspaceUnpinSessionInput(DshWorkspaceUnpinSessionInputRequest(sessionId)),
    )

    suspend fun archiveSession(sessionId: String, stopActivity: Boolean? = null) = call(
        DshMethods.WorkspaceArchiveSession,
        DshWorkspaceArchiveSessionInput(DshWorkspaceArchiveSessionInputRequest(sessionId, stopActivity.optional())),
    )

    suspend fun unarchiveSession(sessionId: String) = call(
        DshMethods.WorkspaceUnarchiveSession,
        DshWorkspaceUnarchiveSessionInput(DshWorkspaceUnarchiveSessionInputRequest(sessionId)),
    )

    private suspend fun <I, O> call(method: DshMethod<I, O>, input: I): O =
        method.output.decode(transport.native(method.endpoint, method.input.encode(input) as JsonObject))
}

internal fun reduceWorkspace(previous: DshWorkspaceSnapshot?, change: DshWorkspaceFollowResult): DshWorkspaceSnapshot {
    if ((previous == null) != (change is DshWorkspaceFollowResult.Case1)) throw ProtocolException()
    return when (change) {
        is DshWorkspaceFollowResult.Case1 -> change.value.value.let { baseline ->
            DshWorkspaceSnapshot(
                baseline.items.map { DshWorkspace(it.workspaceId, it.path, it.title, it.sessionIds) },
                baseline.archivedSessionIds,
                baseline.pinnedSessionIds,
            )
        }
        is DshWorkspaceFollowResult.Case2 -> change.value.value.let { replacement ->
            DshWorkspaceSnapshot(
                replacement.items.map { DshWorkspace(it.workspaceId, it.path, it.title, it.sessionIds) },
                replacement.archivedSessionIds,
                replacement.pinnedSessionIds,
            )
        }
        is DshWorkspaceFollowResult.Case3 -> {
            val prior = checkNotNull(previous)
            val item = change.value.workspace.let { DshWorkspace(it.workspaceId, it.path, it.title, it.sessionIds) }
            val position = prior.workspaces.indexOfFirst { it.id == item.id }
            val rows = if (position == -1) prior.workspaces + item else prior.workspaces.toMutableList().apply {
                this[position] = item
            }
            prior.copy(workspaces = rows)
        }
        is DshWorkspaceFollowResult.Case4 -> checkNotNull(previous).let { prior ->
            prior.copy(workspaces = prior.workspaces.filterNot { it.id == change.value.workspaceId })
        }
        is DshWorkspaceFollowResult.Case5 -> checkNotNull(previous).let { prior ->
            val rows = prior.workspaces.associateBy(DshWorkspace::id)
            prior.copy(workspaces = change.value.workspaceIds.mapNotNull(rows::get))
        }
        is DshWorkspaceFollowResult.Case6 -> checkNotNull(previous).copy(archivedSessionIds = change.value.archivedSessionIds)
        is DshWorkspaceFollowResult.Case7 -> checkNotNull(previous).copy(pinnedSessionIds = change.value.pinnedSessionIds)
    }
}

private fun <T : Any> T?.optional(): DshOptional<T> = if (this == null) DshOptional.Missing else DshOptional.Value(this)
