package cn.edu.ustc.tokenworks

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch

/** Directory content only; the workspace shell owns navigation and the chat pane. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionsScreen(state: HomeState, viewModel: HomeViewModel, modifier: Modifier = Modifier) {
    val runtime = state.runtimes.firstOrNull { it.id == state.selectedRuntimeId }
    val activeTransport by viewModel.activeTransport.collectAsState()
    val transport = activeTransport?.takeIf { it.runtimeId == state.selectedRuntimeId }
    val discovery = remember(transport) { transport?.let(::DiscoveryClient) }
    val directory = rememberWorkspaceDirectory(transport, runtime?.access, state.lanOnly, !state.backgroundReadOnly)
    val archived = directory.snapshot?.archivedSessionIds.orEmpty()
    val visible = remember(state.sessions, archived) { state.sessions.filterNot { it.id in archived } }
    val byId = remember(state.sessions) { state.sessions.associateBy { it.id } }
    var workspaceOpen by rememberSaveable(state.selectedRuntimeId, state.deviceId) { mutableStateOf(false) }
    var query by remember(state.presentationOwner, state.selectedRuntimeId) { mutableStateOf("") }
    val canSearch = state.lanOnly || runtime?.access?.allows("session/search") == true
    val normalizedQuery = query.trim()
    val searching = normalizedQuery.isNotEmpty() && canSearch
    val results = rememberDiscoveryQuery(discovery.takeIf { searching && !workspaceOpen }, normalizedQuery, debounce = true) {
        checkNotNull(discovery).search(normalizedQuery)
    }
    val snackbar = remember(state.presentationOwner, state.selectedRuntimeId) { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val selectedSession by rememberUpdatedState(state.selectedSessionId)
    val onArchiveChanged: (String, Boolean) -> Unit = { id, archivedNow ->
        if (archivedNow && selectedSession == id) viewModel.closeSession()
        viewModel.refreshSessions()
    }
    val actionError = state.sessionActionError?.let { stringResource(R.string.session_action_error, it) }
    LaunchedEffect(actionError) {
        if (actionError != null) snackbar.showSnackbar(actionError)
    }
    var actionSessionId by rememberSaveable(state.selectedRuntimeId, state.deviceId) { mutableStateOf<String?>(null) }
    var renameSessionId by rememberSaveable(state.selectedRuntimeId, state.deviceId) { mutableStateOf<String?>(null) }
    var titleDraft by rememberSaveable(state.selectedRuntimeId, state.deviceId) { mutableStateOf("") }
    val actionSession = state.sessions.firstOrNull { it.id == actionSessionId }
    val renameSession = state.sessions.firstOrNull { it.id == renameSessionId }
    val pullState = rememberPullToRefreshState()
    val now = System.currentTimeMillis()
    val cutoff = now - 86_400_000L
    var directoryFilter by rememberSaveable(state.selectedRuntimeId, state.deviceId) { mutableStateOf(DirectoryFilter.ALL) }
    val unread: (SessionDirectoryEntry) -> Boolean = { it.id != state.selectedSessionId && viewModel.isUnread(it) }
    val groups = sessionDirectoryGroups(visible, cutoff) { entry ->
        directoryFilter == DirectoryFilter.ALL ||
            directoryFilter == DirectoryFilter.RUNNING && entry.running ||
            directoryFilter == DirectoryFilter.UNREAD && unread(entry)
    }
    val available = state.selectedRuntimeId != null && !state.loadingSessions
    val canCreate = state.lanOnly || runtime?.access?.canCreate() == true

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = TokenWorksMetrics.chatBarHeight)
                    .padding(horizontal = TokenWorksSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        // A sidebar can remain visible while a session is open: leave both levels.
                        if (state.selectedSessionId != null) viewModel.closeSession()
                        viewModel.back()
                    },
                    modifier = Modifier.size(TokenWorksSpacing.minimumTouchTarget),
                ) {
                    Icon(painterResource(R.drawable.ic_back), stringResource(R.string.action_back_computers),
                        Modifier.size(TokenWorksSpacing.iconSize))
                }
                Text(
                    runtime?.hostAlias?.takeIf { it.isNotBlank() } ?: stringResource(R.string.nav_sessions),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(viewModel::refreshSessions, enabled = available,
                    modifier = Modifier.size(TokenWorksSpacing.minimumTouchTarget)) {
                    Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.action_refresh),
                        Modifier.size(TokenWorksSpacing.iconSize))
                }
                IconButton(viewModel::openCreation, enabled = canCreate,
                    modifier = Modifier.size(TokenWorksSpacing.minimumTouchTarget)) {
                    Icon(painterResource(R.drawable.ic_add), stringResource(R.string.action_create_session),
                        Modifier.size(TokenWorksSpacing.iconSize))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = TokenWorksSpacing.md), horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                FilterChip(!workspaceOpen, { workspaceOpen = false }, { Text(stringResource(R.string.nav_sessions)) })
                FilterChip(workspaceOpen, { workspaceOpen = true }, { Text(stringResource(R.string.workspace_directory_title)) })
            }
            if (!workspaceOpen && canSearch) {
                OutlinedTextField(query, { query = it.take(500) },
                    Modifier.fillMaxWidth().padding(horizontal = TokenWorksSpacing.md, vertical = TokenWorksSpacing.xs),
                    label = { Text(stringResource(R.string.discovery_search)) }, singleLine = true, enabled = transport != null)
            }
            if (workspaceOpen) {
                WorkspaceManagementPanel(directory, state.sessions, viewModel::openSession, onArchiveChanged, Modifier.weight(1f))
            } else
            Box(
                Modifier.weight(1f).fillMaxWidth().pullToRefresh(
                    isRefreshing = state.loadingSessions,
                    state = pullState,
                    enabled = state.selectedRuntimeId != null,
                    onRefresh = viewModel::refreshSessions,
                ),
            ) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = TokenWorksSpacing.md, vertical = TokenWorksSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xs),
                ) {
                    if (searching) {
                        if (results.loading) item(key = "search-loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        results.error?.let { error -> item(key = "search-error") {
                            Text(errorText(error), color = MaterialTheme.colorScheme.error)
                        } }
                        val search = results.value
                        val rows = search?.items.orEmpty().mapNotNull { hit -> byId[hit.sessionId]?.let { it to hit.snippet } }.distinctBy { it.first.id }
                        if (search != null && rows.isEmpty()) item(key = "search-empty") {
                            Text(stringResource(R.string.discovery_search_empty))
                        }
                        items(rows, key = { it.first.id }) { (entry, snippet) ->
                            SessionDirectoryRow(entry, state.selectedSessionId == entry.id, unread(entry), available, now,
                                onClick = { viewModel.openSession(entry.id) }, onLongClick = { actionSessionId = entry.id }, snippet = snippet)
                        }
                        if (search?.hasMore == true) item(key = "search-more") {
                            Text(stringResource(R.string.discovery_search_more), style = MaterialTheme.typography.bodySmall)
                        }
                        return@LazyColumn
                    }
                    if (visible.isNotEmpty() && !state.loadingSessions) item(key = "session-status") {
                        DirectoryFilters(directoryFilter, visible.count { it.running }, visible.count(unread), choose = { directoryFilter = it })
                    }
                    if (state.loadingSessions) item(key = "loading") {
                        Column(Modifier.fillMaxWidth().padding(TokenWorksSpacing.md),
                            verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(stringResource(R.string.session_loading), style = MaterialTheme.typography.bodySmall,
                                color = tokenWorksSurfaces().textTertiary)
                        }
                    }
                    if (state.sessionError != null) item(key = "error") {
                        Surface(shape = tokenWorksSquircle(TokenWorksRadius.md),
                            color = MaterialTheme.colorScheme.errorContainer) {
                            Column(Modifier.fillMaxWidth().padding(TokenWorksSpacing.md),
                                verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                                Text(stringResource(R.string.session_directory_error),
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(state.sessionError, style = MaterialTheme.typography.bodySmall)
                                TextButton(viewModel::refreshSessions, enabled = available) {
                                    Text(stringResource(R.string.action_retry))
                                }
                            }
                        }
                    }
                    if (visible.isEmpty() && !state.loadingSessions && state.sessionError == null) {
                        item(key = "empty") {
                            TokenWorksEmptyState(
                                title = stringResource(R.string.session_empty),
                                detail = stringResource(R.string.session_empty_detail),
                                actionLabel = stringResource(R.string.action_create_session),
                                onAction = viewModel::openCreation.takeIf { canCreate },
                                modifier = Modifier.tokenWorksEntrance("sessions-empty"),
                            )
                        }
                    }
                    for ((isRecent, grouped) in groups) {
                        if (grouped.isEmpty()) continue
                        item(key = if (isRecent) "recent" else "earlier") {
                            SessionDirectoryEyebrow(stringResource(if (isRecent) R.string.workspace_drawer_recent else R.string.workspace_drawer_earlier))
                        }
                        items(grouped, key = { it.first.id }, contentType = { "session" }) { (entry, depth) ->
                            SessionDirectoryRow(entry, state.selectedSessionId == entry.id, unread(entry), available, now,
                                onClick = { viewModel.openSession(entry.id) }, onLongClick = { actionSessionId = entry.id }, depth = depth)
                        }
                    }
                }
                if (pullState.distanceFraction > 0f || state.loadingSessions && state.sessions.isNotEmpty()) {
                    PullToRefreshDefaults.Indicator(
                        state = pullState,
                        isRefreshing = state.loadingSessions,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(TokenWorksSpacing.md))
    }

    if (actionSession != null) {
        ModalBottomSheet(
            onDismissRequest = { actionSessionId = null },
            sheetMaxWidth = TokenWorksSpacing.readingWidth,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = TokenWorksSpacing.lg, vertical = TokenWorksSpacing.sm)) {
                Text(actionSession.metadata?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.session_untitled),
                    Modifier.padding(TokenWorksSpacing.md), style = MaterialTheme.typography.titleLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                SessionDirectoryAction(stringResource(R.string.session_rename),
                    enabled = available && actionSession.parentSessionId == null && (state.lanOnly || runtime?.access?.canRename(actionSession.id) == true)) {
                    titleDraft = actionSession.metadata?.title.orEmpty()
                    renameSessionId = actionSession.id
                    actionSessionId = null
                }
                SessionDirectoryAction(stringResource(R.string.session_fork),
                    enabled = available && actionSession.parentSessionId == null && (state.lanOnly || runtime?.access?.canFork(actionSession.id) == true)) {
                    actionSessionId = null
                    if (state.selectedSessionId != actionSession.id) viewModel.openSession(actionSession.id)
                    viewModel.forkSession()
                }
                val alreadyArchived = actionSession.id in archived
                SessionDirectoryAction(stringResource(if (alreadyArchived) R.string.directory_restore else R.string.conversation_archive),
                    enabled = available && !directory.busy && directory.canArchiveSession(actionSession.id, !alreadyArchived)) {
                    actionSessionId = null
                    directory.archiveSession(actionSession.id, archived = !alreadyArchived,
                        onDone = { onArchiveChanged(actionSession.id, !alreadyArchived) },
                        onError = { code -> scope.launch { snackbar.showSnackbar(code) } })
                }
                SessionDirectoryAction(stringResource(R.string.action_cancel)) { actionSessionId = null }
            }
        }
    }
    if (renameSession != null) {
        AlertDialog(
            onDismissRequest = { renameSessionId = null },
            title = { Text(stringResource(R.string.session_rename)) },
            text = {
                OutlinedTextField(
                    value = titleDraft,
                    onValueChange = { titleDraft = it },
                    label = { Text(stringResource(R.string.session_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renameSessionId = null
                        if (state.selectedSessionId != renameSession.id) viewModel.openSession(renameSession.id)
                        viewModel.renameSession(titleDraft.trim())
                    },
                    enabled = titleDraft.isNotBlank() && available && (state.lanOnly || runtime?.access?.canRename(renameSession.id) == true),
                ) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton({ renameSessionId = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** Dates section roots; children retain their parent section even when they were updated later. */
private fun sessionDirectoryGroups(rows: List<SessionDirectoryEntry>, recentCut: Long,
    matches: (SessionDirectoryEntry) -> Boolean): List<Pair<Boolean, List<Pair<SessionDirectoryEntry, Int>>>> {
    val byId = rows.associateBy { it.id }
    val visibleIds = HashSet<String>()
    rows.filter(matches).forEach { row ->
        var current: SessionDirectoryEntry? = row
        while (current != null && visibleIds.add(current.id)) current = byId[current.parentSessionId]
    }
    val included = rows.filter { it.id in visibleIds }
    val children = included.groupBy { it.parentSessionId?.takeIf(visibleIds::contains) }
    val recent = ArrayList<Pair<SessionDirectoryEntry, Int>>()
    val earlier = ArrayList<Pair<SessionDirectoryEntry, Int>>()
    val visited = HashSet<String>()
    val pending = ArrayDeque<Pair<SessionDirectoryEntry, Int>>()
    fun append(root: SessionDirectoryEntry) {
        val group = if (root.updatedAt >= recentCut) recent else earlier
        pending.addLast(root to 0)
        while (pending.isNotEmpty()) {
            val (row, depth) = pending.removeLast()
            if (!visited.add(row.id)) continue
            group += row to depth
            children[row.id]?.asReversed()?.forEach { pending.addLast(it to depth + 1) }
        }
    }
    children[null]?.forEach(::append)
    included.forEach { if (it.id !in visited) append(it) }
    return listOf(true to recent, false to earlier)
}

private enum class DirectoryFilter { ALL, RUNNING, UNREAD }

@Composable
private fun DirectoryFilters(active: DirectoryFilter, running: Int, unread: Int, choose: (DirectoryFilter) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
        FilterChip(active == DirectoryFilter.ALL, { choose(DirectoryFilter.ALL) }, { Text(stringResource(R.string.directory_filter_all)) })
        FilterChip(active == DirectoryFilter.RUNNING, { choose(DirectoryFilter.RUNNING) }, { Text(stringResource(R.string.directory_filter_running, running)) })
        FilterChip(active == DirectoryFilter.UNREAD, { choose(DirectoryFilter.UNREAD) }, { Text(stringResource(R.string.directory_filter_unread, unread)) })
    }
}

@Composable
private fun SessionDirectoryEyebrow(label: String) {
    Text(label, Modifier.padding(horizontal = TokenWorksSpacing.md, vertical = TokenWorksSpacing.sm),
        style = MaterialTheme.typography.labelSmall, color = tokenWorksSurfaces().textTertiary)
}

@Composable
private fun SessionDirectoryRow(
    entry: SessionDirectoryEntry,
    isSelected: Boolean,
    unread: Boolean,
    enabled: Boolean,
    now: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    snippet: String? = null,
    depth: Int = 0,
) {
    val status = listOfNotNull(
        stringResource(R.string.session_running).takeIf { entry.running },
        stringResource(R.string.workspace_unread).takeIf { unread },
        stringResource(R.string.session_unavailable).takeIf { entry.state == "unavailable" },
        stringResource(R.string.directory_subagent).takeIf { entry.parentSessionId != null },
    ).joinToString("，")
    val shape = tokenWorksSquircle(TokenWorksRadius.md)
    Row(
        Modifier.fillMaxWidth().padding(start = TokenWorksSpacing.md * depth.coerceAtMost(3)).tokenWorksPress(enabled).clip(shape)
            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .combinedClickable(enabled = enabled, role = Role.Button,
                onLongClickLabel = stringResource(R.string.session_actions_title),
                onClick = onClick, onLongClick = onLongClick)
            .semantics(mergeDescendants = true) { selected = isSelected; stateDescription = status }
            .heightIn(min = TokenWorksMetrics.chatBarHeight)
            .padding(horizontal = TokenWorksSpacing.md, vertical = TokenWorksSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xxs)) {
            Text(entry.metadata?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.session_untitled),
                style = MaterialTheme.typography.titleSmall,
                color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            (snippet ?: entry.metadata?.workspaceTitle)?.takeIf { it.isNotBlank() }?.let { directory ->
                Text(directory, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else tokenWorksSurfaces().textTertiary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xs)) {
            Text(relativeSessionTime(entry.updatedAt, now), style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else tokenWorksSurfaces().textTertiary)
            if (entry.running || unread) {
                Row(horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xs)) {
                    if (entry.running) Box(Modifier.size(TokenWorksSpacing.sm)
                        .background(tokenWorksSurfaces().success, CircleShape))
                    if (unread) Box(Modifier.size(TokenWorksSpacing.sm)
                        .background(MaterialTheme.colorScheme.primary, CircleShape))
                }
            }
        }
    }
}

@Composable
private fun SessionDirectoryAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
        Box(Modifier.fillMaxWidth()) { Text(label, style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
internal fun relativeSessionTime(updatedAt: Long, now: Long = System.currentTimeMillis()): String {
    if (updatedAt <= 0L) return ""
    val elapsed = (now - updatedAt).coerceAtLeast(0L)
    if (elapsed < 60_000L) return stringResource(R.string.workspace_relative_now)
    if (elapsed < 3_600_000L) return stringResource(R.string.workspace_relative_minutes, elapsed / 60_000L)
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(updatedAt).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    if (date == today.minusDays(1)) return stringResource(R.string.workspace_relative_yesterday)
    if (elapsed < 86_400_000L) return stringResource(R.string.workspace_relative_hours, elapsed / 3_600_000L)
    return stringResource(R.string.workspace_relative_date, date.monthValue, date.dayOfMonth)
}
