package cn.edu.ustc.tokenworks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

@Composable
internal fun rememberWorkspaceDirectory(
    transport: DshTransport?,
    access: RuntimeDirectoryEntry?,
    lanOwner: Boolean,
    writable: Boolean,
): WorkspaceDirectoryState {
    val selectedAccess = access?.takeIf { it.id == transport?.runtimeId }
    return key(transport, selectedAccess?.id, selectedAccess?.grantVersion, selectedAccess?.role, selectedAccess?.bindings, lanOwner) {
        val scope = rememberCoroutineScope()
        val expiresAt = selectedAccess?.expiresAt.takeUnless { lanOwner }
        var expired by remember(expiresAt) { mutableStateOf(expiresAt != null && expiresAt <= System.currentTimeMillis()) }
        LaunchedEffect(expiresAt) {
            if (expiresAt != null && !expired) {
                delay((expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
                expired = true
            }
        }
        val permissions = rememberUpdatedState(WorkspacePermissions(selectedAccess, lanOwner, writable, expired))
        val route = transport?.state?.collectAsState()?.value
        val routeState = rememberUpdatedState(route)
        val directory = remember { WorkspaceDirectoryState(transport, scope, permissions, routeState) }
        LaunchedEffect(directory, directory.canFollow, directory.retryToken, route?.generation) {
            directory.follow(route?.generation)
        }
        directory
    }
}

internal class WorkspaceDirectoryState(
    private val transport: DshTransport?,
    private val scope: CoroutineScope,
    private val permissions: State<WorkspacePermissions>,
    private val route: State<DshTransportState?>,
) {
    private val client = transport?.let(::DshWorkspaceClient)
    private var latest by mutableStateOf<DshWorkspaceSnapshot?>(null)
    private var snapshotGeneration by mutableStateOf<Long?>(null)
    private var followError by mutableStateOf<String?>(null)
    private var reconnecting by mutableStateOf(false)
    var retryToken by mutableIntStateOf(0)
        private set
    var busy by mutableStateOf(false)
        private set
    var mutationError by mutableStateOf<String?>(null)
        private set

    private val routeAvailable get() = transport != null && !isTerminal(route.value)
    val canFollow get() = routeAvailable && permissions.value.canFollow()
    val snapshot get() = latest.takeIf { canFollow && snapshotGeneration == route.value?.generation }
    val problem: String? get() = when {
        transport == null -> "workspace.route_unavailable"
        isTerminal(route.value) -> route.value?.error ?: "workspace.route_unavailable"
        !canFollow -> "access.denied"
        else -> followError
    }
    val loading get() = canFollow && (snapshot == null && followError == null || reconnecting)
    val canRetry get() = canFollow && followError != null && !reconnecting

    fun retry() { if (canRetry) retryToken++ }

    suspend fun follow(generation: Long?) {
        latest = null
        followError = null
        reconnecting = false
        if (!canFollow || client == null) return
        var attempt = 0
        while (true) {
            try {
                client.follow().flowOn(Dispatchers.Default).collect {
                    snapshotGeneration = generation
                    latest = it
                    followError = null
                    reconnecting = false
                    attempt = 0
                }
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val decision = DshRecoveryPolicy.classify(error)
                followError = decision.code
                reconnecting = decision.action == DshRecoveryAction.BACKOFF
                if (!reconnecting) {
                    latest = null
                    return
                }
                delay(DshRecoveryPolicy.backoffMillis(attempt++))
            }
        }
    }

    fun canOpen(sessionId: String): Boolean = canFollow && permissions.value.canOpen(sessionId)

    fun canReadSessionSummary(sessionId: String): Boolean = permissions.value.canReadSessionSummary(sessionId)

    fun canWrite(action: WorkspaceAction, workspaceId: String? = null, sessionId: String? = null): Boolean =
        snapshot != null && followError == null && permissions.value.canWrite(action, workspaceId, sessionId)

    fun canArchiveSession(id: String, archived: Boolean = true): Boolean =
        canWrite(if (archived) WorkspaceAction.ARCHIVE else WorkspaceAction.RESTORE, sessionId = id)

    fun archiveSession(id: String, archived: Boolean, onDone: () -> Unit, onError: ((String) -> Unit)? = null) {
        mutate(if (archived) WorkspaceCommand.Archive(id, stopActivity = false) else WorkspaceCommand.Restore(id), onDone, onError)
    }

    fun mutate(command: WorkspaceCommand, done: () -> Unit = {}, failed: ((String) -> Unit)? = null) {
        if (client == null || busy || !canDispatch(command)) return
        busy = true
        mutationError = null
        scope.launch {
            try {
                if (!canDispatch(command)) return@launch
                command.send(client)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val code = homeErrorCode(error)
                if (failed != null) failed(code) else mutationError = code
                return@launch
            } finally {
                busy = false
            }
            done()
        }
    }

    fun dismissMutationError() { mutationError = null }

    private fun canDispatch(command: WorkspaceCommand): Boolean {
        val current = transport?.state?.value ?: return false
        return !isTerminal(current) && current.generation == snapshotGeneration &&
            canWrite(command.action, command.workspaceId, command.sessionId)
    }

    private fun isTerminal(state: DshTransportState?): Boolean = when (state?.phase) {
        DshTransportPhase.AUTH_REQUIRED, DshTransportPhase.REVOKED, DshTransportPhase.CLOSED -> true
        else -> false
    }
}

/** A native call must have a matching grant for every resource it touches, not any one ID. */
internal data class WorkspacePermissions(
    val access: RuntimeDirectoryEntry?,
    val lanOwner: Boolean,
    val writable: Boolean,
    val expired: Boolean = false,
) {
    private fun liveAccess(): RuntimeDirectoryEntry? = access?.takeUnless {
        expired || it.expiresAt?.let { expiry -> expiry <= System.currentTimeMillis() } == true
    }

    fun canFollow(): Boolean = lanOwner || liveAccess()?.bindings?.any {
        // Kind-wide read grants are projected onto the native global call; exact IDs are not.
        it.resource.id == null && DshProductAccess.allowsResource("workspace/follow", it.resource.kind) &&
            "workspace/follow" in it.actions
    } == true

    fun canOpen(sessionId: String): Boolean = writable && (lanOwner || liveAccess()?.let {
        it.has("session/follow", "session", sessionId)
    } == true)

    fun canReadSessionSummary(sessionId: String): Boolean = lanOwner || liveAccess()?.let {
        it.has("session/list", "session", sessionId)
    } == true

    fun canWrite(action: WorkspaceAction, workspaceId: String? = null, sessionId: String? = null): Boolean {
        if (!writable) return false
        if (lanOwner) return true
        val grant = liveAccess() ?: return false
        if (grant.role != "owner" && grant.role != "operator") return false
        return when (action) {
            WorkspaceAction.CREATE -> grant.has(action.endpoint, "global", null)
            WorkspaceAction.RENAME, WorkspaceAction.DELETE, WorkspaceAction.MOVE_WORKSPACE ->
                workspaceId != null && grant.has(action.endpoint, "workspace", workspaceId)
            WorkspaceAction.MOVE_SESSION -> workspaceId != null && sessionId != null &&
                grant.has(action.endpoint, "workspace", workspaceId) && grant.has(action.endpoint, "session", sessionId)
            WorkspaceAction.PIN, WorkspaceAction.UNPIN, WorkspaceAction.ARCHIVE, WorkspaceAction.RESTORE ->
                sessionId != null && grant.has(action.endpoint, "session", sessionId)
        }
    }

    private fun RuntimeDirectoryEntry.has(action: String, kind: String, id: String?) = bindings.any {
        it.resource.kind == kind && (it.resource.id == null || it.resource.id == id) && action in it.actions
    }
}

internal enum class WorkspaceAction(val endpoint: String) {
    CREATE("workspace/create"),
    RENAME("workspace/rename"),
    DELETE("workspace/delete"),
    MOVE_WORKSPACE("workspace/insertBefore"),
    MOVE_SESSION("workspace/insertSessionBefore"),
    PIN("workspace/pinSession"),
    UNPIN("workspace/unpinSession"),
    ARCHIVE("workspace/archiveSession"),
    RESTORE("workspace/unarchiveSession"),
}

internal sealed class WorkspaceCommand(
    val action: WorkspaceAction,
    val workspaceId: String? = null,
    val sessionId: String? = null,
) {
    data class Create(val path: String) : WorkspaceCommand(WorkspaceAction.CREATE)
    data class Rename(val id: String, val title: String) : WorkspaceCommand(WorkspaceAction.RENAME, workspaceId = id)
    data class Delete(val id: String) : WorkspaceCommand(WorkspaceAction.DELETE, workspaceId = id)
    data class MoveWorkspace(val id: String, val before: String?) : WorkspaceCommand(WorkspaceAction.MOVE_WORKSPACE, workspaceId = id)
    data class MoveSession(val workspace: String, val id: String, val before: String?) :
        WorkspaceCommand(WorkspaceAction.MOVE_SESSION, workspaceId = workspace, sessionId = id)
    data class Pin(val id: String) : WorkspaceCommand(WorkspaceAction.PIN, sessionId = id)
    data class Unpin(val id: String) : WorkspaceCommand(WorkspaceAction.UNPIN, sessionId = id)
    data class Archive(val id: String, val stopActivity: Boolean) : WorkspaceCommand(WorkspaceAction.ARCHIVE, sessionId = id)
    data class Restore(val id: String) : WorkspaceCommand(WorkspaceAction.RESTORE, sessionId = id)

    suspend fun send(client: DshWorkspaceClient) {
        when (this) {
            is Create -> client.create(path)
            is Rename -> client.rename(id, title)
            is Delete -> client.delete(id)
            is MoveWorkspace -> client.insertBefore(id, before)
            is MoveSession -> client.insertSessionBefore(workspace, id, before)
            is Pin -> client.pinSession(id)
            is Unpin -> client.unpinSession(id)
            is Archive -> client.archiveSession(id, true.takeIf { stopActivity })
            is Restore -> client.unarchiveSession(id)
        }
    }
}
