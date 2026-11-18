package cn.edu.ustc.tokenworks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** Directory content only; the workspace shell owns navigation and the chat pane. */
@Composable
internal fun SessionsScreen(state: HomeState, viewModel: HomeViewModel, modifier: Modifier = Modifier) {
    key(state.presentationOwner?.fingerprint, state.selectedRuntimeId) { SessionDirectoryScreen(state, viewModel, modifier) }
}

private enum class DirectoryPage { ACTIVE, ARCHIVED, WORKSPACES }
private enum class DirectoryFilter { ALL, RUNNING, UNREAD }
private data class ArchiveConfirmation(val ids: Set<String>, val archive: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionDirectoryScreen(state: HomeState, model: HomeViewModel, modifier: Modifier) {
    val owner = state.presentationOwner?.fingerprint
    val runtimeId = state.selectedRuntimeId
    val runtime = state.runtimeRow(runtimeId)
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val started = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    var deletionLifecycleVersion by remember(lifecycle) { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) deletionLifecycleVersion++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val deletionEpoch = deletionLifecycleVersion
    val haptic = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val activeRoute by model.activeTransport.collectAsState()
    val route = activeRoute?.takeIf { owner != null && runtimeId == it.runtimeId }
    val routeState = route?.state?.collectAsState()?.value
    val generation = routeState?.generation
    val ready = routeState?.phase == DshTransportPhase.READY
    val directory = rememberWorkspaceDirectory(route, runtime?.access, state.lanOnly, started && !state.backgroundReadOnly)
    val access = SessionDirectoryAccess(state.lanOnly, runtime?.access)
    val expires = runtime?.access?.expiresAt.takeUnless { state.lanOnly }
    var expired by remember(expires) { mutableStateOf(expires != null && expires <= System.currentTimeMillis()) }
    LaunchedEffect(expires) {
        if (expires != null && !expired) { delay((expires - System.currentTimeMillis()).coerceAtLeast(0)); expired = true }
    }
    fun current(write: Boolean = false): HomeState? {
        val live = model.state.value
        val transport = route?.state?.value ?: return null
        return live.takeIf {
            owner != null && runtimeId != null && live.phase == HomePhase.READY && live.presentationOwner?.fingerprint == owner &&
                live.selectedRuntimeId == runtimeId && model.activeTransport.value === route && live.lanOnly == state.lanOnly &&
                live.runtimeRow(runtimeId)?.access === runtime?.access && (expires == null || expires > System.currentTimeMillis()) &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && transport.generation == generation &&
                transport.phase == DshTransportPhase.READY && (!write || !live.backgroundReadOnly)
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val pinStore = remember(context, owner, runtimeId) {
        if (owner != null && runtimeId != null) SessionDirectoryPins(context.applicationContext, owner, runtimeId) else null
    }
    var pins by remember(pinStore) { mutableStateOf(pinStore?.read().orEmpty()) }
    var page by rememberSaveable { mutableStateOf(DirectoryPage.ACTIVE) }
    var filter by rememberSaveable { mutableStateOf(DirectoryFilter.ALL) }
    // Never persist query text, snapshots or snippets in saved instance state.
    var query by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var renameId by remember(route, generation, runtime?.access) { mutableStateOf<String?>(null) }
    var title by remember(renameId) { mutableStateOf(state.sessions.firstOrNull { it.id == renameId }?.metadata?.title.orEmpty()) }
    var confirmation by remember(route, generation, runtime?.access) { mutableStateOf<ArchiveConfirmation?>(null) }
    var mutating by remember { mutableStateOf(false) }
    var refreshVersion by remember { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf(false) }
    var deletionRefreshPending by remember { mutableStateOf(false) }
    fun refresh() { directory.retry(); refreshVersion++; model.refreshSessions() }
    fun sameDirectory(): HomeState? = model.state.value.takeIf {
        owner != null && runtimeId != null && it.phase == HomePhase.READY && it.presentationOwner?.fingerprint == owner &&
            it.selectedRuntimeId == runtimeId && it.lanOnly == state.lanOnly
    }
    fun mayRefreshDeletion(): Boolean = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
        sameDirectory()?.let { !it.backgroundReadOnly && !it.refreshing } == true
    // Reconcile with session/list even after a failed transport generation; never infer rows from an ACK.
    LaunchedEffect(deletionRefreshPending, started, state.backgroundReadOnly, state.refreshing, state.phase) {
        if (deletionRefreshPending && mayRefreshDeletion()) {
            refresh()
            deletionRefreshPending = false
        }
    }
    fun clearSelection() { selecting = false; selection = emptySet() }
    fun switch(next: DirectoryPage) { clearSelection(); query = ""; filter = DirectoryFilter.ALL; page = next; keyboard?.hide() }
    fun select(id: String) { if (!mutating) { selecting = true; selection = if (id in selection) selection - id else selection + id } }
    BackHandler(selecting && !mutating) { clearSelection() }

    val snapshot = directory.snapshot
    val archivedIds = remember(snapshot) { snapshot?.archivedSessionIds?.toHashSet().orEmpty() }
    val archivedPage = page == DirectoryPage.ARCHIVED
    val archiveLoading = snapshot == null && directory.loading
    val archiveUnavailable = archivedPage && snapshot == null && !archiveLoading
    val visible = remember(state.sessions, archivedIds, expired, runtime?.access, state.lanOnly, archivedPage, archiveLoading, routeState?.phase) {
        if (expired || archiveLoading || routeState?.phase in setOf(DshTransportPhase.REVOKED, DshTransportPhase.AUTH_REQUIRED,
                DshTransportPhase.CLOSED)) emptyList()
        else {
            val readable = state.sessions.filter { access.read("session/list", it.id) && access.read("session/follow", it.id) }
            val membership = directoryArchivedRows(readable, archivedIds)
            readable.filter { (it.id in membership) == archivedPage }
        }
    }
    LaunchedEffect(visible, started, expired, generation) {
        if (!started || expired) { clearSelection(); renameId = null; confirmation = null }
        else selection = selection.intersect(visible.mapTo(HashSet()) { it.id })
    }
    fun mayArchive(id: String, archive: Boolean): Boolean {
        val row = current(write = true)?.sessions?.firstOrNull { it.id == id } ?: return false
        val baseline = directory.snapshot ?: return false
        val endpoint = if (archive) "workspace/archiveSession" else "workspace/unarchiveSession"
        return !directory.busy && row.parentSessionId == null && (id in baseline.archivedSessionIds) != archive &&
            (!archive || !row.running) && access.write(endpoint, id) && directory.canArchiveSession(id, archive)
    }
    fun mayChange(endpoint: String, id: String): Boolean {
        val row = current(write = true)?.sessions?.firstOrNull { it.id == id } ?: return false
        return page == DirectoryPage.ACTIVE && row.parentSessionId == null && row.state != "unavailable" &&
            id !in directory.snapshot?.archivedSessionIds.orEmpty() && access.write(endpoint, id)
    }
    fun askArchive(ids: Set<String>, archive: Boolean) {
        if (!mutating && ids.isNotEmpty() && ids.all { mayArchive(it, archive) }) {
            keyboard?.hide(); confirmation = ArchiveConfirmation(ids, archive)
        }
    }
    fun runAction(task: suspend () -> String?) {
        if (mutating) return
        mutating = true
        scope.launch {
            val message = try { task() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { context.getString(R.string.session_action_error, homeErrorCode(error)) }
            finally { mutating = false }
            if (message != null) snackbar.showSnackbar(message)
        }
    }

    fun deletionCurrent(): Boolean = deletionEpoch == deletionLifecycleVersion && page == DirectoryPage.ARCHIVED &&
        selecting && !deletionRefreshPending && current(write = true)?.let { !it.loadingSessions && it.sessionError == null } == true
    fun mayDelete(id: String): Boolean {
        val row = model.state.value.sessions.firstOrNull { it.id == id } ?: return false
        val baseline = directory.snapshot ?: return false
        return id in selection && !directory.busy && directory.problem == null &&
            directoryDeletionEligible(row, baseline.archivedSessionIds, access)
    }
    val deletionTargets = selection.takeIf {
        !mutating && ready && started && !expired && !state.backgroundReadOnly && !state.loadingSessions && state.sessionError == null &&
            it.isDirectoryDeletionBatch() && deletionCurrent() && it.all(::mayDelete)
    }
    val hostAllowsDeletion = remember(route, generation, runtime?.access, deletionTargets, lifecycle, deletionEpoch, refreshVersion) {
        mutableStateOf(false)
    }
    LaunchedEffect(hostAllowsDeletion) {
        val ids = deletionTargets ?: return@LaunchedEffect
        val sentRoute = route ?: return@LaunchedEffect
        delay(220) // Coalesce taps while building the selection.
        hostAllowsDeletion.value = probeDirectoryDeletion(ids,
            { !mutating && deletionCurrent() && selection == ids }, ::mayDelete, sentRoute::native)
    }
    var deleteConfirmation by remember(hostAllowsDeletion) { mutableStateOf(false) }
    fun mayConfirmDeletion(ids: Set<String>): Boolean = !mutating && hostAllowsDeletion.value &&
        deletionCurrent() && selection == ids && ids.all(::mayDelete)
    fun deleteConfirmed(ids: Set<String>) {
        val sentRoute = route ?: return
        if (!mayConfirmDeletion(ids)) return
        deleteConfirmation = false
        deleting = true
        runAction {
            try {
                if (!deletionCurrent() || selection != ids) return@runAction null
                val outcome = sentRoute.connect().use { connection ->
                    runDirectoryDeletion(ids, { deletionCurrent() && selection == ids }, ::mayDelete, connection::native)
                }
                val live = sameDirectory() ?: return@runAction null
                selection = selection - outcome.confirmed
                if (selection.isEmpty()) clearSelection()
                if (live.selectedSessionId in outcome.confirmed) model.closeSession()
                if (deletionEpoch != deletionLifecycleVersion || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@runAction null
                if (outcome.confirmed.size == ids.size) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                context.getString(if (outcome.uncertain) R.string.directory_delete_uncertain_result else R.string.directory_delete_result,
                    outcome.confirmed.size, ids.size - outcome.confirmed.size)
            } finally {
                // Run the read even if navigating away cancelled this composition’s batch.
                if (mayRefreshDeletion()) refresh() else deletionRefreshPending = true
                deleting = false
            }
        }
    }

    val needle = query.trim()
    val searching = needle.isNotEmpty()
    val discovery = remember(route) { route?.let(::DiscoveryClient) }
    val searchKey = if (searching && page != DirectoryPage.WORKSPACES && !archiveUnavailable && !archiveLoading &&
        ready && !expired && started && access.read("session/search")) Triple(discovery, generation, runtime?.access to refreshVersion) else null
    val found = rememberDiscoveryQuery(searchKey, needle, debounce = true) {
        if (current() == null || !access.read("session/search")) throw DshFailure("access.denied")
        checkNotNull(discovery).search(needle).also { if (current() == null || !access.read("session/search")) throw DshFailure("access.denied") }
    }
    val matches = remember(visible, needle, found.value, runtime?.access, expired) {
        if (!searching) emptyMap() else sessionDirectorySearch(visible, needle, found.value) { access.read("session/search", it) }
    }
    val unread = remember(visible, state.selectedSessionId, state.reader) {
        visible.filter { it.id != state.selectedSessionId && model.isUnread(it) }.mapTo(HashSet()) { it.id }
    }
    val previewSource = remember(route, generation, runtime?.access, ready, started, expired, state.lanOnly, refreshVersion) {
        route?.takeIf { ready && started && !expired }?.let {
            SessionDirectoryPreviewSource(it, isCurrent = { current() != null }, mayRead = { id -> access.read("session/page", id) })
        }
    }
    val now = System.currentTimeMillis()
    val groups = remember(visible, archivedPage, pins, searching, matches, filter, unread) {
        sessionDirectoryGroups(visible, now - 86_400_000L, if (archivedPage) emptySet() else pins) { row ->
            (!searching || row.id in matches) && (archivedPage || filter == DirectoryFilter.ALL ||
                filter == DirectoryFilter.RUNNING && row.running || filter == DirectoryFilter.UNREAD && row.id in unread)
        }
    }
    val hasRows = groups.values.any { it.isNotEmpty() }
    val loading = (state.loadingSessions && visible.isEmpty()) || archiveLoading
    val canCreate = !expired && runtimeId != null && !state.backgroundReadOnly && (state.lanOnly || runtime?.access?.canCreate() == true)

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = TokenWorksMetrics.chatBarHeight).padding(horizontal = TokenWorksSpacing.xs),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = !mutating, onClick = {
                    if (selecting) clearSelection() else { if (state.selectedSessionId != null) model.closeSession(); model.back() }
                }) { Icon(painterResource(R.drawable.ic_back), stringResource(if (selecting) R.string.directory_cancel_selection else R.string.action_back_computers)) }
                Text(runtime?.hostAlias?.takeIf { it.isNotBlank() } ?: stringResource(R.string.nav_sessions),
                    Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(::refresh, enabled = runtimeId != null && !state.loadingSessions && !mutating) {
                    Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.action_refresh))
                }
                IconButton(model::openCreation, enabled = canCreate && !selecting && !mutating) {
                    Icon(painterResource(R.drawable.ic_add), stringResource(R.string.action_create_session))
                }
            }
            DirectoryChips(Modifier.padding(horizontal = TokenWorksSpacing.md)) {
                DirectoryChip(page == DirectoryPage.ACTIVE, stringResource(R.string.nav_sessions), !mutating) { switch(DirectoryPage.ACTIVE) }
                DirectoryChip(archivedPage, stringResource(R.string.directory_archived_tab), !mutating) { switch(DirectoryPage.ARCHIVED) }
                DirectoryChip(page == DirectoryPage.WORKSPACES, stringResource(R.string.workspace_directory_title), !mutating) { switch(DirectoryPage.WORKSPACES) }
            }
            if (page == DirectoryPage.WORKSPACES) {
                WorkspaceManagementPanel(directory, state.sessions, model::openSession, { id, archived ->
                    if (archived && current(write = true)?.selectedSessionId == id) model.closeSession()
                    refresh()
                }, Modifier.weight(1f))
            } else {
                val pull = rememberPullToRefreshState()
                Box(Modifier.weight(1f).fillMaxWidth().pullToRefresh(isRefreshing = state.loadingSessions, state = pull,
                    enabled = runtimeId != null && !mutating, onRefresh = ::refresh)) {
                    LazyColumn(Modifier.fillMaxSize().semantics { collectionInfo = CollectionInfo(groups.values.sumOf { it.size }, 1) },
                        contentPadding = PaddingValues(horizontal = TokenWorksSpacing.md, vertical = TokenWorksSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xs)) {
                        item("directory:search") {
                            OutlinedTextField(query, { query = it.take(500); clearSelection() }, Modifier.fillMaxWidth(),
                                enabled = !mutating, singleLine = true, label = { Text(stringResource(R.string.session_directory_search_label)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                                trailingIcon = if (query.isEmpty()) null else ({
                                    IconButton({ query = ""; clearSelection() }, enabled = !mutating) {
                                        Icon(painterResource(R.drawable.ic_directory_clear), stringResource(R.string.directory_clear_search))
                                    }
                                }))
                        }
                        if (visible.isNotEmpty() && !archivedPage) item("directory:filters") {
                            DirectoryChips {
                                DirectoryChip(filter == DirectoryFilter.ALL, stringResource(R.string.directory_filter_all), !mutating) { filter = DirectoryFilter.ALL; clearSelection() }
                                DirectoryChip(filter == DirectoryFilter.RUNNING, stringResource(R.string.directory_filter_running, visible.count { it.running }), !mutating) { filter = DirectoryFilter.RUNNING; clearSelection() }
                                DirectoryChip(filter == DirectoryFilter.UNREAD, stringResource(R.string.directory_filter_unread, unread.size), !mutating) { filter = DirectoryFilter.UNREAD; clearSelection() }
                            }
                        }
                        if (hasRows && !selecting) item("directory:select") {
                            TextButton({ selecting = true; keyboard?.hide() }, enabled = !mutating) { Text(stringResource(R.string.directory_select)) }
                        }
                        if (!archivedPage && snapshot == null && !loading && runtimeId != null) item("directory:archive-unknown") {
                            DirectoryNotice(stringResource(R.string.directory_archive_unknown))
                        }
                        state.sessionError?.let { error -> item("directory:error") {
                            DirectoryNotice(stringResource(R.string.session_directory_error) + " · " + errorText(error), retry = ::refresh)
                        } }
                        if (loading) item("directory:loading") { DirectorySkeleton(stringResource(R.string.session_loading)) }
                        if (archiveUnavailable) item("directory:archive-unavailable") {
                            DirectoryEmpty(stringResource(R.string.directory_archive_unavailable), stringResource(R.string.directory_archive_unknown),
                                action = ::refresh, actionLabel = stringResource(R.string.action_retry))
                        }
                        if (found.loading) item("directory:search-loading") { DirectorySkeleton(stringResource(R.string.directory_search_loading), rows = 2) }
                        found.error?.let { error -> item("directory:search-error") {
                            DirectoryNotice(stringResource(R.string.directory_content_search_failed) + " · " + errorText(error), retry = ::refresh)
                        } }
                        if (searching && searchKey == null && !loading && !archiveUnavailable) item("directory:metadata-only") {
                            DirectoryNotice(stringResource(R.string.directory_metadata_search_only))
                        }
                        if (!hasRows && !loading && !archiveUnavailable && !found.loading && state.sessionError == null) item("directory:empty") {
                            when {
                                searching -> DirectoryEmpty(stringResource(R.string.discovery_search_empty), stringResource(R.string.session_directory_search_empty_detail))
                                filter != DirectoryFilter.ALL -> DirectoryEmpty(stringResource(R.string.directory_filter_empty),
                                    action = { filter = DirectoryFilter.ALL }, actionLabel = stringResource(R.string.directory_filter_all))
                                archivedPage -> DirectoryEmpty(stringResource(R.string.directory_archived_empty), stringResource(R.string.directory_archived_detail))
                                else -> DirectoryEmpty(stringResource(R.string.session_empty), stringResource(R.string.directory_active_empty_detail),
                                    action = model::openCreation.takeIf { canCreate }, actionLabel = stringResource(R.string.action_create_session))
                            }
                        }
                        if (found.value?.hasMore == true && matches.values.any { it != null }) item("directory:search-refine") {
                            DirectoryNotice(stringResource(R.string.directory_search_refine))
                        }
                        var sectionOffset = 0
                        for ((section, rows) in groups) {
                            if (rows.isEmpty()) continue
                            item("directory:section:$section") {
                                val label = when (section) {
                                    DirectorySection.PINNED -> R.string.directory_pinned_section
                                    DirectorySection.RECENT -> R.string.workspace_drawer_recent
                                    DirectorySection.EARLIER -> R.string.workspace_drawer_earlier
                                }
                                Text(stringResource(label), Modifier.padding(TokenWorksSpacing.sm).semantics { heading() },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                            }
                            val offset = sectionOffset
                            sectionOffset += rows.size
                            itemsIndexed(rows, key = { _, item -> "directory:row:${item.entry.id}" }, contentType = { _, _ -> "directory:row" }) { index, (entry, depth) ->
                                val id = entry.id
                                val interactive = ready && started && !expired && !mutating && !state.backgroundReadOnly
                                val preview = rememberDirectoryPreview(previewSource?.takeIf { access.read("session/page", id) }, entry)
                                SessionDirectoryRow(entry, depth, now, state.selectedSessionId == id, id in unread, archivedPage, id in pins && !archivedPage,
                                    selecting, id in selection, if (selecting) !mutating else interactive, preview, matches[id],
                                    open = {
                                        if (selecting) select(id)
                                        else if (access.read("session/follow", id) && current(write = true)?.sessions?.any { it.id == id } == true) {
                                            keyboard?.hide(); haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); model.openSession(id)
                                        }
                                    }, select = { keyboard?.hide(); select(id) },
                                    pin = if (!archivedPage && pinStore != null && interactive) ({
                                        if (current()?.sessions?.any { it.id == id } == true) pins = pinStore.toggle(id)
                                    }) else null,
                                    rename = if (interactive && mayChange("session/rename", id)) ({ keyboard?.hide(); renameId = id }) else null,
                                    fork = if (interactive && mayChange("session/fork", id)) ({
                                        val sentRoute = route
                                        if (sentRoute != null) runAction {
                                            sentRoute.connect().use { connection ->
                                                if (!mayChange("session/fork", id)) throw DshFailure("access.denied")
                                                val method = DshMethods.SessionFork
                                                val args = method.input.encode(DshSessionForkInput(DshSessionForkInputRequest(id))) as JsonObject
                                                val result = method.output.decode(connection.native(method.endpoint, args))
                                                if (current(write = true) != null) { model.openSession(result.sessionId); refresh() }
                                            }
                                            null
                                        }
                                    }) else null,
                                    archive = if (interactive && mayArchive(id, !archivedPage)) ({ askArchive(setOf(id), !archivedPage) }) else null,
                                    collectionIndex = offset + index)
                            }
                        }
                    }
                    if (pull.distanceFraction > 0f || state.loadingSessions && visible.isNotEmpty()) {
                        PullToRefreshDefaults.Indicator(state = pull, isRefreshing = state.loadingSessions, modifier = Modifier.align(Alignment.TopCenter))
                    }
                }
                if (selecting) DirectorySelectionBar(selection.size, mutating,
                    selection.isNotEmpty() && selection.all { mayArchive(it, !archivedPage) }, !archivedPage,
                    cancel = ::clearSelection, submit = { askArchive(selection, !archivedPage) }, deleting = deleting,
                    delete = deletionTargets?.takeIf { hostAllowsDeletion.value }?.let { ids ->
                        { if (mayConfirmDeletion(ids)) { keyboard?.hide(); deleteConfirmation = true } }
                    })
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(TokenWorksSpacing.md))
    }

    confirmation?.let { target ->
        val operation = stringResource(if (target.archive) R.string.directory_archive_action else R.string.directory_restore)
        AlertDialog(onDismissRequest = { confirmation = null },
            title = { Text(stringResource(R.string.directory_archive_confirm_title, operation, target.ids.size)) },
            text = { Text(stringResource(if (target.archive) R.string.directory_archive_explanation else R.string.directory_restore_explanation)) },
            confirmButton = { TextButton({
                val sentRoute = route ?: return@TextButton
                if (mutating || !target.ids.all { mayArchive(it, target.archive) }) return@TextButton
                confirmation = null
                runAction {
                    val outcome = sentRoute.connect().use { connection ->
                        runDirectoryArchive(target.ids, { current(write = true) != null }, { mayArchive(it, target.archive) }) { id ->
                            sendDirectoryArchive(connection, id, target.archive)
                        }
                    }
                    selection = selection - outcome.completed
                    if (selection.isEmpty()) clearSelection()
                    current(write = true)?.let { live ->
                        if (target.archive && live.selectedSessionId in outcome.completed) model.closeSession()
                        refresh()
                    }
                    val missing = target.ids.size - outcome.completed.size
                    if (missing == 0) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    val error = outcome.unconfirmed.values.firstOrNull()?.let { " · ${context.getString(R.string.session_action_error, it)}" }.orEmpty()
                    context.getString(R.string.directory_archive_result, outcome.completed.size, missing) + error
                }
            }, enabled = !mutating && target.ids.all { mayArchive(it, target.archive) }) { Text(operation) } },
            dismissButton = { TextButton({ confirmation = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
    val deleteIds = deletionTargets
    if (deleteConfirmation && deleteIds != null && hostAllowsDeletion.value) {
        DirectoryDeleteConfirmation(deleteIds.size, dismiss = { deleteConfirmation = false }, confirm = { deleteConfirmed(deleteIds) })
    }
    renameId?.let { id ->
        AlertDialog(onDismissRequest = { renameId = null }, title = { Text(stringResource(R.string.session_rename)) },
            text = { OutlinedTextField(title, { title = it.take(500) }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.session_title_label)) }, singleLine = true) },
            confirmButton = { TextButton({
                val sentRoute = route ?: return@TextButton
                val next = title.trim()
                if (!mayChange("session/rename", id) || mutating || next.isEmpty()) return@TextButton
                renameId = null
                runAction {
                    sentRoute.connect().use { connection ->
                        if (!mayChange("session/rename", id)) throw DshFailure("access.denied")
                        val method = DshMethods.SessionRename
                        val args = method.input.encode(DshSessionRenameInput(DshSessionRenameInputRequest(id, next))) as JsonObject
                        method.output.decode(connection.native(method.endpoint, args))
                    }
                    if (current(write = true) != null) refresh()
                    null
                }
            }, enabled = !mutating && title.isNotBlank() && mayChange("session/rename", id)) { Text(stringResource(R.string.action_confirm)) } },
            dismissButton = { TextButton({ renameId = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
}

@Composable
private fun DirectoryChips(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) { content() }
}

@Composable
private fun DirectoryChip(selected: Boolean, label: String, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(selected, onClick, { Text(label) }, modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget), enabled = enabled)
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

