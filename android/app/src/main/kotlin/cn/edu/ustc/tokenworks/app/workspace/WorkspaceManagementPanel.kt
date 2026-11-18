package cn.edu.ustc.tokenworks

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow

@Composable
internal fun WorkspaceManagementPanel(
    directory: WorkspaceDirectoryState,
    sessions: List<SessionDirectoryEntry>,
    onOpenSession: (String) -> Unit,
    onArchiveChanged: (sessionId: String, archived: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) = key(directory) {
    var archiveOpen by rememberSaveable { mutableStateOf(false) }
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    var prompt by remember { mutableStateOf<WorkspacePrompt?>(null) }
    val archiveChanged by rememberUpdatedState(onArchiveChanged)
    val snapshot = directory.snapshot
    val layout = remember(snapshot, sessions, directory) {
        snapshot?.let { WorkspaceLayout(it, sessions.filter { row -> directory.canReadSessionSummary(row.id) }) }
    }
    BackHandler(enabled = archiveOpen) { archiveOpen = false }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = TokenWorksMetrics.chatBarHeight).padding(horizontal = TokenWorksSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (archiveOpen) {
                IconButton(onClick = { archiveOpen = false }, modifier = Modifier.size(TokenWorksSpacing.minimumTouchTarget)) {
                    Icon(painterResource(R.drawable.ic_back), stringResource(R.string.workspace_directory_back),
                        Modifier.size(TokenWorksSpacing.iconSize))
                }
            }
            Text(
                stringResource(if (archiveOpen) R.string.workspace_directory_archived else R.string.workspace_directory_title),
                Modifier.weight(1f).padding(horizontal = TokenWorksSpacing.sm).semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            if (!archiveOpen && snapshot != null) {
                TextButton(onClick = { archiveOpen = true }, modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                    Text(stringResource(R.string.workspace_directory_archived))
                }
                if (directory.canWrite(WorkspaceAction.CREATE)) {
                    IconButton(
                        onClick = { prompt = WorkspacePrompt.Create },
                        enabled = !directory.busy,
                        modifier = Modifier.size(TokenWorksSpacing.minimumTouchTarget),
                    ) {
                        Icon(painterResource(R.drawable.ic_add), stringResource(R.string.workspace_directory_create),
                            Modifier.size(TokenWorksSpacing.iconSize))
                    }
                }
            }
        }
        if (directory.loading || directory.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        key(archiveOpen) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = TokenWorksSpacing.md, vertical = TokenWorksSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xs),
            ) {
                if (directory.loading || directory.problem != null || directory.busy || directory.mutationError != null) {
                    item(key = "workspace-status") { WorkspaceDirectoryStatus(directory) }
                }
                if (layout == null || snapshot == null) return@LazyColumn
                if (archiveOpen) {
                    if (layout.archived.isEmpty()) item(key = "archive-empty") {
                        TokenWorksEmptyState(stringResource(R.string.workspace_directory_empty_archive))
                    }
                    items(layout.archived, key = { "archived:$it" }) { id ->
                        val title = workspaceSessionTitle(layout.sessions[id], id)
                        WorkspaceDirectoryRow(
                            title = title,
                            actionLabel = stringResource(R.string.workspace_directory_session_actions, title),
                            actions = if (directory.canArchiveSession(id, archived = false)) listOf(
                                WorkspaceMenuItem(R.string.workspace_directory_restore) {
                                    directory.archiveSession(id, archived = false, onDone = { archiveChanged(id, false) })
                                },
                            ) else emptyList(),
                            actionsEnabled = !directory.busy,
                        )
                    }
                    return@LazyColumn
                }
                if (layout.pinned.isEmpty() && layout.ungrouped.isEmpty() && layout.sections.isEmpty()) item(key = "workspace-empty") {
                    TokenWorksEmptyState(stringResource(R.string.workspace_directory_empty))
                }
                if (layout.pinned.isNotEmpty()) {
                    item(key = "pinned-heading") { WorkspaceHeading(R.string.workspace_directory_pinned) }
                    items(layout.pinned, key = { "pinned:$it" }) { id ->
                        WorkspaceSessionRow(id, layout.sessions[id], directory, onOpenSession,
                            onArchive = { prompt = it }, pinned = true)
                    }
                }
                layout.sections.forEachIndexed { index, section ->
                    val workspace = section.workspace
                    val expanded = workspace.id !in collapsed
                    item(key = "workspace:${workspace.id}") {
                        val actions = buildList {
                            if (directory.canWrite(WorkspaceAction.RENAME, workspaceId = workspace.id)) {
                                add(WorkspaceMenuItem(R.string.workspace_directory_rename) { prompt = WorkspacePrompt.Rename(workspace) })
                            }
                            if (directory.canWrite(WorkspaceAction.MOVE_WORKSPACE, workspaceId = workspace.id)) {
                                snapshot.workspaces.getOrNull(index - 1)?.let { previous ->
                                    add(WorkspaceMenuItem(R.string.workspace_directory_move_up) {
                                        directory.mutate(WorkspaceCommand.MoveWorkspace(workspace.id, previous.id))
                                    })
                                }
                                if (index < snapshot.workspaces.lastIndex) add(WorkspaceMenuItem(R.string.workspace_directory_move_down) {
                                    directory.mutate(WorkspaceCommand.MoveWorkspace(workspace.id, snapshot.workspaces.getOrNull(index + 2)?.id))
                                })
                            }
                            if (directory.canWrite(WorkspaceAction.DELETE, workspaceId = workspace.id)) {
                                add(WorkspaceMenuItem(R.string.workspace_directory_delete) { prompt = WorkspacePrompt.Delete(workspace) })
                            }
                        }
                        WorkspaceDirectoryRow(
                            title = workspace.title,
                            supporting = workspace.path,
                            actionLabel = stringResource(R.string.workspace_directory_workspace_actions, workspace.title),
                            actions = actions,
                            actionsEnabled = !directory.busy,
                            onClick = { collapsed = if (expanded) collapsed + workspace.id else collapsed - workspace.id },
                            expansion = expanded,
                        )
                    }
                    if (expanded) {
                        if (section.sessions.isEmpty()) item(key = "workspace-empty:${workspace.id}") {
                            Text(stringResource(R.string.workspace_directory_no_sessions),
                                Modifier.padding(start = TokenWorksSpacing.xxl, top = TokenWorksSpacing.sm, bottom = TokenWorksSpacing.sm),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        itemsIndexed(section.sessions, key = { _, row -> "workspace-session:${workspace.id}:${row.value}" }) { position, row ->
                            val id = row.value
                            WorkspaceSessionRow(
                                id, layout.sessions[id], directory, onOpenSession, onArchive = { prompt = it },
                                workspaceId = workspace.id,
                                above = section.sessions.getOrNull(position - 1)?.value,
                                below = section.sessions.getOrNull(position + 1),
                                workspaceSessionIds = workspace.sessionIds,
                            )
                        }
                    }
                }
                if (layout.ungrouped.isNotEmpty()) {
                    item(key = "ungrouped-heading") { WorkspaceHeading(R.string.workspace_directory_ungrouped) }
                    items(layout.ungrouped, key = { "ungrouped:${it.id}" }) { row ->
                        WorkspaceSessionRow(row.id, row, directory, onOpenSession, onArchive = { prompt = it })
                    }
                }
            }
        }
    }
    prompt?.let { current ->
        key(current) {
            WorkspaceManagementDialog(current, directory, onDismiss = { prompt = null },
                onArchived = { id -> archiveChanged(id, true) })
        }
    }
}

@Composable
private fun WorkspaceSessionRow(
    id: String,
    metadata: SessionDirectoryEntry?,
    directory: WorkspaceDirectoryState,
    onOpen: (String) -> Unit,
    onArchive: (WorkspacePrompt.Archive) -> Unit,
    pinned: Boolean = false,
    workspaceId: String? = null,
    above: String? = null,
    below: IndexedValue<String>? = null,
    workspaceSessionIds: List<String> = emptyList(),
) {
    val title = workspaceSessionTitle(metadata, id)
    val pinAction = if (pinned) WorkspaceAction.UNPIN else WorkspaceAction.PIN
    val actions = buildList {
        if (directory.canWrite(pinAction, sessionId = id)) {
            add(WorkspaceMenuItem(if (pinned) R.string.workspace_directory_unpin else R.string.workspace_directory_pin) {
                directory.mutate(if (pinned) WorkspaceCommand.Unpin(id) else WorkspaceCommand.Pin(id))
            })
        }
        if (workspaceId != null && directory.canWrite(WorkspaceAction.MOVE_SESSION, workspaceId, id)) {
            if (above != null) add(WorkspaceMenuItem(R.string.workspace_directory_move_up) {
                directory.mutate(WorkspaceCommand.MoveSession(workspaceId, id, above))
            })
            if (below != null) add(WorkspaceMenuItem(R.string.workspace_directory_move_down) {
                directory.mutate(WorkspaceCommand.MoveSession(workspaceId, id, workspaceSessionIds.getOrNull(below.index + 1)))
            })
        }
        if (directory.canArchiveSession(id)) add(WorkspaceMenuItem(R.string.conversation_archive) {
            onArchive(WorkspacePrompt.Archive(id, title, stopActivity = metadata?.running == true))
        })
    }
    WorkspaceDirectoryRow(
        title = title,
        supporting = if (metadata?.running == true) stringResource(R.string.session_running) else null,
        actionLabel = stringResource(R.string.workspace_directory_session_actions, title),
        actions = actions,
        actionsEnabled = !directory.busy,
        modifier = if (workspaceId == null) Modifier else Modifier.padding(start = TokenWorksSpacing.lg),
        onClick = if (directory.canOpen(id)) ({ onOpen(id) }) else null,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceDirectoryRow(
    title: String,
    actionLabel: String,
    actions: List<WorkspaceMenuItem>,
    actionsEnabled: Boolean,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    onClick: (() -> Unit)? = null,
    expansion: Boolean? = null,
) {
    val hasActions = actions.isNotEmpty()
    var menuOpen by remember(hasActions, actionsEnabled) { mutableStateOf(false) }
    val expandLabel = expansion?.let { stringResource(if (it) R.string.detail_expanded else R.string.detail_collapsed) }
    val toggleLabel = expansion?.let { stringResource(if (it) R.string.workspace_directory_collapse else R.string.workspace_directory_expand) }
    val openMenu: (() -> Unit)? = if (hasActions && actionsEnabled) ({ menuOpen = true }) else null
    val click = onClick ?: openMenu
    Surface(
        modifier.fillMaxWidth(),
        shape = tokenWorksSquircle(TokenWorksRadius.sm),
        color = if (expansion == null) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).heightIn(min = TokenWorksSpacing.minimumTouchTarget)
                    .then(if (click == null) Modifier else Modifier.combinedClickable(
                        onClickLabel = toggleLabel,
                        role = Role.Button,
                        onClick = click,
                        onLongClickLabel = actionLabel.takeIf { openMenu != null },
                        onLongClick = openMenu,
                    ))
                    .semantics { if (expandLabel != null) stateDescription = expandLabel }
                    .padding(horizontal = TokenWorksSpacing.sm, vertical = TokenWorksSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xs),
            ) {
                if (expansion != null) Icon(painterResource(R.drawable.ic_chevron), null,
                    Modifier.size(TokenWorksSpacing.iconSize).rotate(if (expansion) 90f else 0f))
                Column(Modifier.weight(1f)) {
                    Text(title, style = if (expansion == null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (hasActions) Box {
                IconButton(onClick = { menuOpen = true }, enabled = actionsEnabled,
                    modifier = Modifier.size(TokenWorksSpacing.minimumTouchTarget)) {
                    Icon(painterResource(R.drawable.ic_more), actionLabel, Modifier.size(TokenWorksSpacing.iconSize))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    actions.forEach { action ->
                        DropdownMenuItem(text = { Text(stringResource(action.label)) }, onClick = {
                            menuOpen = false
                            action.run()
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceHeading(@StringRes label: Int) {
    Text(stringResource(label), Modifier.padding(horizontal = TokenWorksSpacing.sm, vertical = TokenWorksSpacing.xs)
        .semantics { heading() }, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun WorkspaceDirectoryStatus(directory: WorkspaceDirectoryState) {
    Column(Modifier.fillMaxWidth().padding(TokenWorksSpacing.sm), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xs)) {
        val problem = directory.problem
        val message = when {
            directory.busy -> stringResource(R.string.workspace_directory_saving)
            directory.loading && problem != null -> stringResource(R.string.workspace_directory_reconnecting)
            directory.loading -> stringResource(R.string.workspace_directory_loading)
            problem == "workspace.route_unavailable" -> stringResource(R.string.workspace_directory_unavailable)
            problem == "access.denied" || problem == "access.revoked" || problem == "remote-access/denied" ->
                stringResource(R.string.workspace_directory_denied)
            problem != null -> stringResource(R.string.workspace_directory_read_error, problem)
            else -> null
        }
        if (message != null) Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (directory.canRetry) TextButton(directory::retry) { Text(stringResource(R.string.action_retry)) }
        directory.mutationError?.let { code ->
            Text(workspaceMutationError(code), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(directory::dismissMutationError) { Text(stringResource(R.string.action_close)) }
        }
    }
}

@Composable
private fun workspaceSessionTitle(row: SessionDirectoryEntry?, id: String): String =
    row?.metadata?.title?.takeIf(String::isNotBlank) ?: stringResource(R.string.session_short_title, id.takeLast(8))

private data class WorkspaceSection(val workspace: DshWorkspace, val sessions: List<IndexedValue<String>>)

private class WorkspaceLayout(snapshot: DshWorkspaceSnapshot, sessionRows: List<SessionDirectoryEntry>) {
    val sessions = sessionRows.associateBy(SessionDirectoryEntry::id)
    val archived = snapshot.archivedSessionIds
    private val archivedIds = archived.toHashSet()
    val pinned = snapshot.pinnedSessionIds.filterNot(archivedIds::contains)
    private val pinnedIds = pinned.toHashSet()
    private val grouped = HashSet<String>()
    val sections = snapshot.workspaces.map { workspace ->
        WorkspaceSection(workspace, workspace.sessionIds.withIndex().filter { (_, id) ->
            id !in archivedIds && id !in pinnedIds
        }).also { grouped.addAll(workspace.sessionIds) }
    }
    val ungrouped = sessionRows.filter { it.id !in archivedIds && it.id !in pinnedIds && it.id !in grouped }
}
