package cn.edu.ustc.tokenworks

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Calendar
import java.text.DateFormat
import java.util.Date

internal enum class HomeDestination(@get:StringRes val label: Int, @get:DrawableRes val icon: Int) {
    SESSIONS(R.string.nav_sessions, R.drawable.ic_sessions),
    COMPUTERS(R.string.nav_computers, R.drawable.ic_computers),
    SETTINGS(R.string.nav_settings, R.drawable.ic_settings),
}

internal enum class DirectorySessionFilter(@get:StringRes val label: Int) {
    ALL(R.string.directory_filter_all),
    ACTIVE(R.string.directory_filter_active),
    ARCHIVED(R.string.directory_filter_archived),
}

/** Presentation state only. No credentials, drafts, approval handles or restored authority. */
@Stable
internal class HomeNavigation {
    var destination by mutableStateOf(HomeDestination.SESSIONS)
    var directoryRuntimeId by mutableStateOf<String?>(null)
    var directoryFromComputer by mutableStateOf(false)
    var directoryPosition by mutableStateOf(LazyListState())
        private set
    var directoryQuery by mutableStateOf("")
    var directorySessionFilter by mutableStateOf(DirectorySessionFilter.ALL)
    var directoryWorkspaceId by mutableStateOf<String?>(null)
    var drawerOpen by mutableStateOf(false)
    var hostSwitcherOpen by mutableStateOf(false)
    var navigationExpanded by mutableStateOf(true)

    /** Bumped by the rail's search key; the directory consumes it once by focusing its search field. */
    var directorySearchRequest by mutableStateOf(0)
        private set
    private var directorySearchServed = 0

    fun requestDirectorySearch() {
        directorySearchRequest++
    }

    fun consumeDirectorySearch(): Boolean {
        if (directorySearchServed == directorySearchRequest) return false
        directorySearchServed = directorySearchRequest
        return true
    }
    var chatDetail by mutableStateOf<ChatDetailTarget?>(null)
        private set

    fun openChatDetail(state: HomeState, section: ChatDetailSection, requestId: String? = null) {
        val owner = state.presentationOwner ?: return
        val runtime = state.runtimeRow(state.selectedRuntimeId) ?: return
        val session = state.selectedSessionId ?: return
        val target = ChatDetailTarget(owner.fingerprint, runtime.hostId, runtime.id, session, section, requestId)
        if (target.matches(state)) chatDetail = target
    }

    fun setChatDetail(state: HomeState, target: ChatDetailTarget?) {
        if (target == null || target.matches(state)) chatDetail = target
    }

    fun closeChatDetail() { chatDetail = null }

    fun reconcileChatDetail(state: HomeState) {
        if (chatDetail?.matches(state) == false) chatDetail = null
        if (state.phase != HomePhase.READY) { drawerOpen = false; hostSwitcherOpen = false }
    }

    fun closePresentationPanels() {
        drawerOpen = false
        hostSwitcherOpen = false
        chatDetail = null
    }

    private var directoryIdentity: DirectoryIdentity? = null
    private var visited by mutableStateOf<List<VisitedSession>>(emptyList())

    fun selectRuntime(id: String) {
        if (directoryRuntimeId != id) {
            resetDirectoryFilters()
            directoryIdentity = null
        }
        directoryRuntimeId = id
    }

    fun resetDirectoryFilters() {
        directoryPosition = LazyListState()
        directoryQuery = ""
        directorySessionFilter = DirectorySessionFilter.ALL
        directoryWorkspaceId = null
    }

    /** Retire presentation hints, never fetch resources or restore authority. */
    fun reconcileDirectory(state: HomeState) {
        if (state.phase != HomePhase.READY || state.knownTarget != null) {
            if (directoryIdentity != null || directoryQuery.isNotEmpty() ||
                directorySessionFilter != DirectorySessionFilter.ALL || directoryWorkspaceId != null) resetDirectoryFilters()
            directoryIdentity = null
            visited = emptyList()
            return
        }
        val row = state.runtimes.firstOrNull { it.id == directoryRuntimeId }
        val identity = row?.takeIf { it.access?.organizationId != null && it.access.canList() }?.let {
            DirectoryIdentity(state.issuer, state.subject, state.deviceId, state.presentationOwner?.fingerprint,
                it.id, it.hostId, it.access!!.organizationId!!)
        }
        if (identity == null) {
            if (!state.refreshing) {
                if (directoryIdentity != null || directoryQuery.isNotEmpty() ||
                    directorySessionFilter != DirectorySessionFilter.ALL || directoryWorkspaceId != null) resetDirectoryFilters()
                directoryIdentity = null
                visited = visited.filter { it.runtimeId != directoryRuntimeId }
            }
            return
        }
        if (directoryIdentity != null && directoryIdentity != identity) {
            resetDirectoryFilters()
            visited = emptyList()
        }
        directoryIdentity = identity
        val runtime = state.directoryRuntime(directoryRuntimeId) ?: return
        if (state.refreshing || state.loadingSessions || state.sessionError != null || state.error != null) return
        val rows = state.directoryRows(runtime.id)
        if (directoryWorkspaceId != null && rows.none { it.workspaceId == directoryWorkspaceId }) {
            directoryWorkspaceId = null
            directoryPosition = LazyListState()
        }
        visited = visited.filter { entry ->
            entry.runtimeId != runtime.id || rows.any { it.id == entry.sessionId && it.viewScope(runtime.access!!) != null }
        }
    }

    /** IDs are navigation hints, never cached rows or restored read authority. */
    fun recordVisited(state: HomeState) {
        if (state.reader?.phase != SessionReadPhase.READY) return
        val runtime = state.directoryRuntime(directoryRuntimeId) ?: return
        val grant = runtime.access ?: return
        val session = state.directoryRows(directoryRuntimeId).firstOrNull {
            it.id == state.selectedSessionId && it.viewScope(grant) != null
        } ?: return
        val entry = VisitedSession(state.issuer, state.subject, state.deviceId,
            runtime.id, runtime.hostId, grant.organizationId!!, session.id)
        if (visited.firstOrNull() != entry) visited = buildList(5) {
            add(entry)
            for (previous in visited) if (previous != entry && size < 5) add(previous)
        }
    }

    fun recentSession(
        state: HomeState,
        rows: List<SessionDirectoryEntry> = state.directoryRows(directoryRuntimeId),
    ): SessionDirectoryEntry? {
        val runtime = state.directoryRuntime(directoryRuntimeId) ?: return null
        val grant = runtime.access ?: return null
        return visited.firstNotNullOfOrNull { entry ->
            if (entry.issuer != state.issuer || entry.subject != state.subject || entry.deviceId != state.deviceId ||
                entry.runtimeId != runtime.id || entry.hostId != runtime.hostId ||
                entry.organizationId != grant.organizationId) null
            else rows.firstOrNull { it.id == entry.sessionId && it.viewScope(grant) != null }
        }
    }
}

/** A presentation target never survives a different accepted identity or a retired reader. */
internal fun ChatDetailTarget.matches(state: HomeState): Boolean =
    state.phase == HomePhase.READY && state.presentationOwner?.fingerprint == ownerKey &&
        state.selectedRuntimeId == runtimeId && state.selectedSessionId == sessionId &&
        state.runtimeRow(runtimeId)?.hostId == hostId && state.reader != null &&
        state.reader.phase != SessionReadPhase.CLOSED && state.reader.phase != SessionReadPhase.REVOKED


private data class VisitedSession(
    val issuer: String, val subject: String, val deviceId: String,
    val runtimeId: String, val hostId: String, val organizationId: String, val sessionId: String,
)

private data class DirectoryIdentity(
    val issuer: String, val subject: String, val deviceId: String, val ownerFingerprint: String?,
    val runtimeId: String, val hostId: String, val organizationId: String,
)

private fun HomeState.directoryRuntime(id: String?): RuntimeRow? =
    runtimes.firstOrNull { phase == HomePhase.READY && knownTarget == null &&
        it.id == id && it.id == selectedRuntimeId && it.access?.organizationId != null && it.access.canList() }

/** Only the current acquired directory; apply resource containment before searching or revisiting. */
private fun HomeState.directoryRows(id: String?, retainDuringLoad: Boolean = false): List<SessionDirectoryEntry> {
    val runtime = directoryRuntime(id) ?: return emptyList()
    if (refreshing || loadingSessions && !retainDuringLoad || sessionError != null || error != null) return emptyList()
    val scope = runtime.access?.scope ?: return emptyList()
    return sessions.filter { it.id in scope.sessions || it.workspaceId?.let(scope.workspaces::contains) == true }
}

private inline fun <T> stableLabel(id: String, rows: List<T>, idOf: (T) -> String): String {
    val collides = rows.any {
        val other = idOf(it)
        other != id && other.regionMatches(0, id, 0, 8) &&
            other.regionMatches(other.length - 4, id, id.length - 4, 4)
    }
    return if (collides) id else "${id.take(8)}…${id.takeLast(4)}"
}

private fun runtimeLabel(id: String, runtimes: List<RuntimeRow>): String = stableLabel(id, runtimes) { it.id }

@Composable
private fun directoryWorkspaceLabel(id: String, rows: List<SessionDirectoryEntry>, titles: Map<String, String?>): String {
    val identifier = stableLabel(id, rows) { it.workspaceId.orEmpty() }
    val title = titles[id]
    return if (title == null) identifier else stringResource(R.string.directory_named_identifier, title, identifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TokenWorksAppShell(
    state: HomeState,
    navigation: HomeNavigation,
    onDestination: (HomeDestination) -> Unit,
    onRuntime: (String) -> Unit,
    onSession: (String) -> Unit,
    onRefresh: () -> Unit,
    onRefreshSessions: () -> Unit,
    onCreate: (String) -> Unit,
    onBinding: () -> Unit,
    onNotifications: () -> Unit,
    onAppearance: () -> Unit,
    onAppUpdates: () -> Unit,
    onExit: () -> Unit,
    onBackToComputers: () -> Unit,
    layout: WorkspaceLayoutSpec,
    chromeVisible: Boolean,
    onOpenNavigation: () -> Unit,
    content: (@Composable () -> Unit)? = null,
) {
    val destination = navigation.destination
    val runtime = state.directoryRuntime(navigation.directoryRuntimeId)
    val appContext = LocalContext.current.applicationContext
    val ownerKey = state.appearanceScope().ownerKey
    val readMarks = remember(ownerKey) { ownerKey?.let { SessionReadMarks(appContext, it) } }
    SideEffect {
        navigation.reconcileDirectory(state)
        navigation.reconcileChatDetail(state)
    }
    val directoryInNavigation = chromeVisible && destination == HomeDestination.SESSIONS &&
        state.knownTarget == null && (
            layout.leftPane == WorkspaceLeftPane.SIDEBAR || layout.leftPane == WorkspaceLeftPane.NONE && navigation.drawerOpen
        )
    WorkspaceScaffold(layout, navigation.drawerOpen, { navigation.drawerOpen = it }, chromeVisible,
        railContent = {
            WorkspaceRail(state, navigation, onDestination, onCreate)
        },
        navigationContent = {
            WorkspaceNavigation(state, navigation, layout, onDestination, onRuntime, onSession, onCreate,
                onBinding, onNotifications, onOpenNavigation, readMarks)
        }) {
        if (content != null) content()
        else Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            topBar = {
                TokenWorksTopBar(stringResource(destination.label), navigationIcon = {
                    IconButton(onOpenNavigation, Modifier.size(TokenWorksSpacing.minimumTouchTarget)) {
                        ShellIcon(R.drawable.ic_menu, R.string.workspace_open_navigation)
                    }
                }, actions = {
                    if (destination == HomeDestination.SESSIONS && navigation.directoryFromComputer) {
                        IconButton(onBackToComputers, Modifier.size(TokenWorksSpacing.minimumTouchTarget)) {
                            ShellIcon(R.drawable.ic_back, R.string.action_back_computers)
                        }
                    }
                    if (destination != HomeDestination.SETTINGS) IconButton(
                        if (destination == HomeDestination.SESSIONS && runtime != null) onRefreshSessions else onRefresh,
                        enabled = !state.refreshing && !state.loadingSessions,
                        modifier = Modifier.size(TokenWorksSpacing.minimumTouchTarget),
                    ) { ShellIcon(R.drawable.ic_refresh, R.string.action_refresh) }
                    if (destination == HomeDestination.SESSIONS && runtime?.access?.creationScope() != null) {
                        IconButton({ onCreate(runtime.id) }, Modifier.size(TokenWorksSpacing.minimumTouchTarget)) {
                            ShellIcon(R.drawable.ic_add, R.string.action_create_session)
                        }
                    }
                })
            },
        ) { insets ->
            Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
                when (destination) {
                    HomeDestination.SESSIONS -> if (directoryInNavigation) WorkspaceEmptySelection()
                        else SessionDirectoryScreen(state, navigation, onRuntime, onSession, onBinding, readMarks = readMarks)
                    HomeDestination.COMPUTERS -> ComputersScreen(state, onRuntime, onCreate, onBinding)
                    HomeDestination.SETTINGS -> SettingsScreen(state, onNotifications, onAppearance, onAppUpdates, onExit)
                }
            }
        }
    }
    if (navigation.hostSwitcherOpen && chromeVisible && state.knownTarget == null) {
        ModalBottomSheet(onDismissRequest = { navigation.hostSwitcherOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().heightIn(max = 720.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.workspace_switch_host), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton({ navigation.hostSwitcherOpen = false }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_close)) }
                }
                ComputersScreen(state, onRuntime = { id ->
                    navigation.hostSwitcherOpen = false
                    if (id != state.selectedRuntimeId) onRuntime(id)
                }, onCreate = { id -> navigation.hostSwitcherOpen = false; onCreate(id) },
                    onBinding = { navigation.hostSwitcherOpen = false; onBinding() })
            }
        }
    }
}

@Composable
private fun WorkspaceEmptySelection() {
    Column(Modifier.widthIn(max = TokenWorksSpacing.readingWidth).fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.workspace_choose_session), style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.workspace_choose_session_detail), style = MaterialTheme.typography.bodyMedium,
            color = tokenWorksSurfaces().textTertiary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkspaceNavigation(state: HomeState, navigation: HomeNavigation, layout: WorkspaceLayoutSpec,
    onDestination: (HomeDestination) -> Unit, onRuntime: (String) -> Unit, onSession: (String) -> Unit,
    onCreate: (String) -> Unit, onBinding: () -> Unit, onNotifications: () -> Unit, onOpenNavigation: () -> Unit,
    readMarks: SessionReadMarks?) {
    val runtime = state.directoryRuntime(navigation.directoryRuntimeId)
    val inSidebar = layout.leftPane == WorkspaceLeftPane.SIDEBAR
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compactChrome = maxHeight < 480.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.workspace_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    maxLines = if (compactChrome) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                if (compactChrome && runtime?.access?.creationScope() != null) IconButton({ onCreate(runtime.id) }, Modifier.size(48.dp)) {
                    ShellIcon(R.drawable.ic_add, R.string.action_create_session)
                }
                IconButton({ if (inSidebar) navigation.navigationExpanded = false else navigation.drawerOpen = false }, Modifier.size(48.dp)) {
                    ShellIcon(if (inSidebar) R.drawable.ic_menu else R.drawable.ic_back,
                        if (inSidebar) R.string.workspace_collapse_navigation else R.string.workspace_close_navigation)
                }
            }
            if (state.knownTarget != null) {
                TextButton({ navigation.drawerOpen = false; onDestination(HomeDestination.SESSIONS) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.workspace_back_reminders))
                }
            } else {
                if (!compactChrome && runtime?.access?.creationScope() != null) FilledTonalButton({ onCreate(runtime.id) },
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp)) {
                    ShellIcon(R.drawable.ic_add)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_create_session))
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (navigation.destination == HomeDestination.SESSIONS) SessionDirectoryScreen(state, navigation, onRuntime, onSession, onBinding, inNavigation = true, readMarks = readMarks)
                    else TextButton({ onDestination(HomeDestination.SESSIONS) }, Modifier.padding(16.dp).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.workspace_return_sessions))
                    }
                }
                HorizontalDivider()
                if (compactChrome) {
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val hostLabel = stringResource(R.string.workspace_host_status, runtime?.hostAlias ?: stringResource(R.string.session_select_computer), stringResource(R.string.workspace_switch_host))
                        IconButton({ navigation.hostSwitcherOpen = true }, Modifier.size(48.dp)) {
                            Icon(painterResource(R.drawable.ic_computers), hostLabel, Modifier.size(20.dp))
                        }
                        HomeDestination.entries.forEach { destination -> if (destination != HomeDestination.SESSIONS) {
                            IconButton({ onDestination(destination) }, Modifier.size(48.dp).semantics { selected = navigation.destination == destination }) {
                                ShellIcon(destination.icon, destination.label)
                            }
                        } }
                        val reminders = stringResource(R.string.settings_notifications)
                        TextButton({ navigation.drawerOpen = false; onNotifications() }, Modifier.heightIn(min = 48.dp)
                            .semantics { contentDescription = reminders }) { Text(stringResource(R.string.workspace_nav_reminders), style = MaterialTheme.typography.bodySmall) }
                    }
                } else {
                    TextButton({ navigation.hostSwitcherOpen = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp)) {
                        ShellIcon(R.drawable.ic_computers)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                            Text(runtime?.hostAlias ?: stringResource(R.string.session_select_computer), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(if (state.selectedSessionId != null && state.reader?.phase == SessionReadPhase.READY)
                                R.string.workspace_live_connection else R.string.workspace_presence_unknown), style = MaterialTheme.typography.bodySmall)
                        }
                        ShellIcon(R.drawable.ic_chevron)
                    }
                    HomeDestination.entries.forEach { destination -> if (destination != HomeDestination.SESSIONS) {
                        TextButton({ onDestination(destination) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .semantics { selected = navigation.destination == destination }) {
                            ShellIcon(destination.icon)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(destination.label), Modifier.weight(1f))
                        }
                    } }
                    val reminders = stringResource(R.string.settings_notifications)
                    TextButton({ navigation.drawerOpen = false; onNotifications() }, Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .semantics { contentDescription = reminders }) {
                        Text(stringResource(R.string.settings_notifications))
                    }
                }
            }
        }
    }
}

/**
 * The 68 dp collapsed column: one key per top-level action instead of a squeezed copy of the
 * sidebar. Nothing here fetches or mutates session resources; it only moves presentation state.
 */
@Composable
private fun WorkspaceRail(state: HomeState, navigation: HomeNavigation,
    onDestination: (HomeDestination) -> Unit, onCreate: (String) -> Unit) {
    val runtime = state.directoryRuntime(navigation.directoryRuntimeId)
    Column(
        Modifier.fillMaxHeight().fillMaxWidth().padding(TokenWorksMetrics.edge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TokenWorksMetrics.gap),
    ) {
        RailKey(R.drawable.ic_menu, R.string.workspace_rail_sessions, selected = false) {
            navigation.drawerOpen = true
        }
        RailKey(R.drawable.ic_search, R.string.workspace_rail_search, selected = false) {
            navigation.requestDirectorySearch()
            navigation.drawerOpen = true
        }
        RailKey(R.drawable.ic_computers, R.string.workspace_switch_host, selected = navigation.hostSwitcherOpen) {
            navigation.hostSwitcherOpen = true
        }
        Spacer(Modifier.weight(1f))
        if (state.knownTarget == null && runtime?.access?.creationScope() != null) {
            RailKey(R.drawable.ic_add, R.string.action_create_session, selected = false) { onCreate(runtime.id) }
        }
        RailKey(R.drawable.ic_settings, R.string.nav_settings, selected = navigation.destination == HomeDestination.SETTINGS) {
            onDestination(HomeDestination.SETTINGS)
        }
    }
}

@Composable
private fun RailKey(@DrawableRes icon: Int, @StringRes label: Int, selected: Boolean, onClick: () -> Unit) {
    val description = stringResource(label)
    Box(
        Modifier.size(TokenWorksMetrics.key)
            .tokenWorksPress()
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(TokenWorksSpacing.iconSize),
            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ShellIcon(@DrawableRes icon: Int, @StringRes description: Int? = null) {
    Icon(painterResource(icon), description?.let { stringResource(it) }, Modifier.size(24.dp))
}

@OptIn(ExperimentalLayoutApi::class)
/** One ordered directory row: a section label or a session entry. */
private sealed interface DirectoryRow {
    val key: String
    val type: String

    data class Eyebrow(@get:StringRes val text: Int, override val key: String) : DirectoryRow {
        override val type: String get() = "eyebrow"
    }

    data class Session(val entry: SessionDirectoryEntry) : DirectoryRow {
        override val key: String get() = entry.id
        override val type: String get() = "session"
    }
}

@Composable
private fun SessionDirectoryScreen(state: HomeState, navigation: HomeNavigation, onRuntime: (String) -> Unit, onSession: (String) -> Unit, onBinding: () -> Unit, inNavigation: Boolean = false, readMarks: SessionReadMarks? = null) {
    val listable = state.runtimes.filter { it.access?.organizationId != null && it.access.canList() }
    val runtime = state.directoryRuntime(navigation.directoryRuntimeId)
    val staleSelection = navigation.directoryRuntimeId != null && runtime == null
    val rows = state.directoryRows(navigation.directoryRuntimeId, retainDuringLoad = true)
    val workspaces = rows.mapNotNull { it.workspaceId }.distinct().sorted()
    val workspaceTitles = remember(rows, runtime?.access) { directoryWorkspaceTitles(rows, runtime?.access) }
    val query = navigation.directoryQuery.trim()
    val matches = rows.filter { session ->
        (navigation.directoryWorkspaceId == null || session.workspaceId == navigation.directoryWorkspaceId) &&
            when (navigation.directorySessionFilter) {
                DirectorySessionFilter.ALL -> true
                DirectorySessionFilter.ACTIVE -> session.state != "archived"
                DirectorySessionFilter.ARCHIVED -> session.state == "archived"
            } && (query.isEmpty() || session.id.contains(query, ignoreCase = true) ||
                session.workspaceId?.contains(query, ignoreCase = true) == true ||
                session.displayMetadata(runtime?.access)?.let { display ->
                    display.title?.contains(query, ignoreCase = true) == true || display.workspaceTitle?.contains(query, ignoreCase = true) == true
                } == true)
    }
    val recent = if (query.isEmpty()) navigation.recentSession(state, matches) else null
    var choosingComputer by remember { mutableStateOf(false) }
    var explainingScope by remember { mutableStateOf(false) }
    var choosingWorkspace by remember(navigation.directoryRuntimeId) { mutableStateOf(false) }
    val searchAvailable = runtime != null && !state.refreshing && !state.loadingSessions && state.sessionError == null && state.error == null
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(navigation.directorySearchRequest, searchAvailable) {
        if (searchAvailable && navigation.consumeDirectorySearch()) searchFocus.requestFocus()
    }
    LaunchedEffect(runtime?.id, state.refreshing, state.loadingSessions, state.sessionError, state.error) {
        if (runtime == null || state.refreshing || state.loadingSessions || state.sessionError != null || state.error != null) {
            focusManager.clearFocus()
            keyboard?.hide()
            choosingWorkspace = false
            if (state.sessionError != null) navigation.directoryPosition.scrollToItem(0)
        }
    }
        val dayAgo = remember(rows) { System.currentTimeMillis() - 24L * 60 * 60 * 1000 }
        val fresh = matches.filter { it.updatedAt >= dayAgo }
        val older = matches.filter { it.updatedAt < dayAgo }
        val grouped: List<DirectoryRow> = buildList {
            when {
                navigation.directorySessionFilter == DirectorySessionFilter.ARCHIVED ->
                    if (matches.isNotEmpty()) {
                        add(DirectoryRow.Eyebrow(R.string.directory_filter_archived, "eyebrow-archived"))
                        matches.forEach { add(DirectoryRow.Session(it)) }
                    }
                fresh.isEmpty() && older.isNotEmpty() -> {
                    add(DirectoryRow.Eyebrow(R.string.workspace_drawer_earlier, "eyebrow-earlier"))
                    older.forEach { add(DirectoryRow.Session(it)) }
                }
                else -> {
                    if (fresh.isNotEmpty()) {
                        add(DirectoryRow.Eyebrow(R.string.workspace_drawer_recent, "eyebrow-recent"))
                        fresh.forEach { add(DirectoryRow.Session(it)) }
                    }
                    if (older.isNotEmpty()) {
                        add(DirectoryRow.Eyebrow(R.string.workspace_drawer_earlier, "eyebrow-earlier"))
                        older.forEach { add(DirectoryRow.Session(it)) }
                    }
                }
            }
        }
    LazyColumn(
        modifier = Modifier.widthIn(max = 800.dp).fillMaxSize().imePadding().testTag("session-directory"),
        state = navigation.directoryPosition,
        contentPadding = PaddingValues(TokenWorksSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm),
    ) {
        item("scope") {
            Column(verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                OutlinedTextField(
                    value = navigation.directoryQuery,
                    onValueChange = { navigation.directoryQuery = it },
                    enabled = searchAvailable,
                    placeholder = { Text(stringResource(R.string.directory_search_label),
                        style = MaterialTheme.typography.bodyMedium, color = tokenWorksSurfaces().textTertiary) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = tokenWorksSquircle(TokenWorksRadius.xl),
                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocus).testTag("directory-search"),
                )
                if (!inNavigation) OutlinedButton({ choosingComputer = true }, enabled = listable.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("runtime-filter")) {
                    Text(runtime?.hostAlias ?: stringResource(R.string.session_select_computer), Modifier.weight(1f))
                    ShellIcon(R.drawable.ic_chevron)
                }
                OutlinedButton({ choosingWorkspace = true }, enabled = searchAvailable && workspaces.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("directory-workspace-filter")) {
                    Text(navigation.directoryWorkspaceId?.let { id ->
                        stringResource(R.string.session_workspace, directoryWorkspaceLabel(id, rows, workspaceTitles))
                    } ?: stringResource(R.string.directory_all_workspaces), Modifier.weight(1f))
                    ShellIcon(R.drawable.ic_chevron)
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DirectorySessionFilter.entries.forEach { filter ->
                        FilterChip(navigation.directorySessionFilter == filter,
                            { navigation.directorySessionFilter = filter }, enabled = searchAvailable,
                            label = { Text(stringResource(filter.label)) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("directory-status-${filter.name}"))
                    }
                }
                if (searchAvailable && (navigation.directorySessionFilter != DirectorySessionFilter.ALL ||
                        navigation.directoryWorkspaceId != null || navigation.directoryQuery.isNotEmpty())) {
                    TextButton({ navigation.resetDirectoryFilters() }, Modifier.heightIn(min = 48.dp).testTag("directory-reset-filters")) {
                        Text(stringResource(R.string.directory_reset_filters))
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.directory_scope_short), Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton({ explainingScope = true }, Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.directory_scope_help))
                    }
                }
            }
        }
        if (state.refreshing) item("loading-computers") { DirectoryProgress(R.string.directory_loading) }
        if (state.loadingSessions && runtime != null) item("loading-sessions") { DirectoryProgress(R.string.session_loading) }
        state.error?.let { item("account-error") { TokenWorksStatusNotice(errorText(it), isError = true) } }
        if (staleSelection && !state.refreshing && state.sessionError == null) item("stale-scope") {
            TokenWorksStatusNotice(stringResource(R.string.session_scope_changed), isError = true)
        }
        if (runtime == null && listable.isNotEmpty() && !staleSelection && !state.refreshing) item("choose-scope") {
            TokenWorksStatusNotice(stringResource(R.string.session_choose_scope))
        }
        state.sessionError?.let { error -> item("directory-error") {
            TokenWorksStatusNotice(stringResource(if (error == "access.denied") R.string.session_directory_denied else R.string.session_directory_error), isError = true)
        } }
        if (!state.refreshing && state.error == null && listable.isEmpty()) item("no-computers") {
            EmptyDirectory(R.string.session_no_computer, R.string.session_no_computer_detail)
            TextButton(onBinding, Modifier.heightIn(min = 48.dp)) { Text(stringResource(if (state.pendingBindingId == null) R.string.action_bind_computer else R.string.action_review_binding)) }
        }
        if (runtime != null && (searchAvailable || state.loadingSessions)) {
            if (searchAvailable && rows.isEmpty()) item("empty") { TokenWorksStatusNotice(stringResource(R.string.session_empty)) }
            else if (searchAvailable && matches.isEmpty()) item("no-matches") {
                EmptyDirectory(R.string.directory_search_empty, R.string.directory_search_empty_detail)
            }
            if (recent != null && searchAvailable) item("recent") {
                OutlinedButton(
                    onClick = {
                        navigation.recentSession(state)?.takeIf { it.id == recent.id }?.let { onSession(it.id) }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("directory-recent"),
                ) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                        Text(stringResource(R.string.directory_recent))
                        val title = recent.displayMetadata(runtime.access)?.title
                        if (title != null) Text(title, style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.session_short_title, stableLabel(recent.id, rows) { it.id }),
                            style = if (title == null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall)
                    }
                    ShellIcon(R.drawable.ic_chevron)
                }
            }
            items(grouped, key = { it.key }, contentType = { it.type }) { row ->
                when (row) {
                    is DirectoryRow.Eyebrow -> DrawerEyebrow(stringResource(row.text))
                    is DirectoryRow.Session -> {
                        val session = row.entry
                        val readable = runtime.access?.let(session::viewScope) != null
                        val unread = readMarks != null && session.id != state.selectedSessionId &&
                            remember(session.id, session.lastPersisted, state.selectedSessionId) {
                                readMarks.isUnread(session.id, session.lastPersisted)
                            }
                        SessionListItem(session, stableLabel(session.id, rows) { it.id },
                            session.workspaceId?.let { workspaceTitles[it] ?: stableLabel(it, rows) { entry -> entry.workspaceId.orEmpty() } },
                            readable, session.displayMetadata(runtime.access), state.loadingSessions,
                            isSelected = state.selectedSessionId == session.id, unread = unread) {
                            if (!state.loadingSessions && !state.refreshing && state.directoryRows(navigation.directoryRuntimeId).any { it.id == session.id } &&
                                readable) onSession(session.id)
                        }
                    }
                }
            }
        }
    }
    if (explainingScope) AlertDialog(
        onDismissRequest = { explainingScope = false },
        title = { Text(stringResource(R.string.directory_scope_help)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.session_scope))
                Text(stringResource(R.string.directory_search_scope))
                Text(stringResource(R.string.directory_filter_scope))
                Text(stringResource(R.string.directory_recent_scope))
            }
        },
        confirmButton = { TextButton({ explainingScope = false }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_close)) } },
    )
    if (choosingWorkspace && searchAvailable) AlertDialog(
        onDismissRequest = { choosingWorkspace = false },
        title = { Text(stringResource(R.string.directory_choose_workspace)) },
        text = {
            LazyColumn {
                item("all") {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.directory_all_workspaces)) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("directory-workspace-all").clickable(role = Role.Button) {
                            navigation.directoryWorkspaceId = null
                            choosingWorkspace = false
                        },
                    )
                }
                items(workspaces, key = { it }) { id ->
                    ListItem(
                        headlineContent = {
                            val title = workspaceTitles[id]
                            Text(stringResource(R.string.session_workspace,
                                if (title == null) id else stringResource(R.string.directory_named_identifier, title, id)))
                        },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("directory-workspace-$id").clickable(role = Role.Button) {
                            navigation.directoryWorkspaceId = id
                            choosingWorkspace = false
                        },
                    )
                }
            }
        },
        confirmButton = { TextButton({ choosingWorkspace = false }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_close)) } },
    )
    if (choosingComputer) AlertDialog(
        onDismissRequest = { choosingComputer = false },
        title = { Text(stringResource(R.string.session_select_computer)) },
        text = {
            LazyColumn {
                items(listable, key = { it.id }) { row ->
                    ListItem(
                        headlineContent = { Text(row.hostAlias) },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.computer_runtime, row.id))
                                Text(stringResource(R.string.directory_organization, row.access?.organizationId.orEmpty()))
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("runtime-filter-${row.id}").clickable(role = Role.Button) {
                            choosingComputer = false
                            if (row.access?.canList() == true) onRuntime(row.id)
                        },
                    )
                }
            }
        },
        confirmButton = { TextButton({ choosingComputer = false }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun SessionListItem(session: SessionDirectoryEntry, label: String, workspaceLabel: String?, canRead: Boolean,
    display: DirectoryDisplayMetadata?, refreshing: Boolean, isSelected: Boolean, unread: Boolean, onClick: () -> Unit) {
    val title = display?.title ?: stringResource(R.string.session_short_title, label)
    val stateLabel = stringResource(when (session.state) {
        "running" -> R.string.session_running
        "archived" -> R.string.session_archived
        "unavailable" -> R.string.session_unavailable
        else -> R.string.session_available
    })
    val time = relativeSessionTime(session.updatedAt)
    // While a refresh keeps the last directory, or the row is directory-only, say so instead of
    // silently ignoring taps.
    val readableHint = when {
        refreshing -> stringResource(R.string.session_actions_directory_retained)
        !canRead -> stringResource(R.string.session_directory_only)
        else -> null
    }
    val secondary = listOfNotNull(
        workspaceLabel,
        time.ifEmpty { null },
        stateLabel.takeIf { session.state == "archived" || session.state == "unavailable" },
        readableHint,
    ).joinToString(" · ")
    val description = if (unread) stateLabel + " · " + stringResource(R.string.workspace_unread) else stateLabel
    val selectedBar = MaterialTheme.colorScheme.primary
    Surface(
        shape = tokenWorksSquircle(TokenWorksRadius.md),
        color = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .tokenWorksPress(enabled = canRead && !refreshing)
                .drawBehind {
                    if (isSelected) drawRect(selectedBar, size = Size(3.dp.toPx(), size.height))
                }
                .clickable(enabled = canRead && !refreshing, role = Role.Button, onClick = onClick)
                .semantics { selected = isSelected; stateDescription = description }
                .padding(start = TokenWorksSpacing.md, end = TokenWorksSpacing.sm, top = TokenWorksSpacing.sm, bottom = TokenWorksSpacing.sm)
                .testTag("session-${session.id}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xxs)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    secondary,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokenWorksSurfaces().textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                session.state == "running" -> Box(
                    Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                )
                unread -> {
                    val unreadLabel = stringResource(R.string.workspace_unread)
                    Box(
                        Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f), CircleShape)
                            .semantics { contentDescription = unreadLabel },
                    )
                }
                else -> Unit
            }
        }
    }
}

/** Small uppercase-looking section label; it groups directory rows without adding a second list. */
@Composable
private fun DrawerEyebrow(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(
            start = TokenWorksSpacing.md, end = TokenWorksSpacing.md,
            top = TokenWorksSpacing.md, bottom = TokenWorksSpacing.xs,
        ),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
        color = tokenWorksSurfaces().textTertiary,
    )
}

/** Relative time for directory rows; UTC-independent, bounded to three forms. */
@Composable
internal fun relativeSessionTime(updatedAt: Long, now: Long = System.currentTimeMillis()): String {
    if (updatedAt <= 0) return ""
    val minutes = ((now - updatedAt).coerceAtLeast(0)) / 60_000
    if (minutes < 1) return stringResource(R.string.workspace_relative_now)
    if (minutes < 60) return stringResource(R.string.workspace_relative_minutes, minutes.toInt())
    if (minutes < 24 * 60) return stringResource(R.string.workspace_relative_hours, (minutes / 60).toInt())
    val calendar = Calendar.getInstance().apply { timeInMillis = updatedAt }
    return stringResource(
        R.string.workspace_relative_date,
        calendar.get(Calendar.MONTH) + 1,
        calendar.get(Calendar.DAY_OF_MONTH),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComputersScreen(state: HomeState, onRuntime: (String) -> Unit, onCreate: (String) -> Unit, onBinding: () -> Unit) {
    var detailId by remember(state.issuer, state.subject, state.deviceId, state.presentationOwner?.fingerprint) { mutableStateOf<String?>(null) }
    LaunchedEffect(state.runtimes) {
        if (state.runtimes.none { it.id == detailId }) detailId = null
    }
    LazyColumn(Modifier.widthIn(max = 800.dp).fillMaxSize().testTag("computers-directory"), contentPadding = PaddingValues(TokenWorksSpacing.lg), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md)) {
        item("bind") {
            OutlinedButton(onBinding, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(if (state.pendingBindingId == null) R.string.action_bind_computer else R.string.action_review_binding))
            }
            Text(stringResource(R.string.computer_scope), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.refreshing) item("loading") { DirectoryProgress(R.string.directory_loading) }
        state.error?.let { item("error") { TokenWorksStatusNotice(errorText(it), isError = true) } }
        if (!state.refreshing && state.error == null && state.runtimes.isEmpty()) item("empty") { EmptyDirectory(R.string.computer_empty, R.string.computer_empty_detail) }
        items(state.runtimes, key = { it.id }) { runtime ->
            val canList = runtime.access?.organizationId != null && runtime.access.canList()
            val live = runtime.id == state.selectedRuntimeId && state.selectedSessionId != null && state.reader?.phase == SessionReadPhase.READY
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ListItem(
                    headlineContent = { Text(runtime.hostAlias, style = MaterialTheme.typography.titleSmall) },
                    supportingContent = { Column {
                        Text(stringResource(if (live) R.string.workspace_live_connection else R.string.computer_presence_unknown), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.computer_runtime, runtimeLabel(runtime.id, state.runtimes)), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(if (canList) R.string.action_open_sessions else R.string.computer_no_directory), style = MaterialTheme.typography.bodySmall)
                    } },
                    trailingContent = { if (canList) ShellIcon(R.drawable.ic_chevron) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("computer-open-${runtime.id}")
                        .then(if (canList) Modifier.clickable(role = Role.Button) { if (runtime.access?.canList() == true) onRuntime(runtime.id) }
                            else Modifier.semantics { disabled() }),
                )
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (runtime.access?.creationScope() != null) TextButton({ if (runtime.access.creationScope() != null) onCreate(runtime.id) }, Modifier.heightIn(min = 48.dp).testTag("computer-create-${runtime.id}")) { Text(stringResource(R.string.action_create_session)) }
                    TextButton({ detailId = runtime.id }, Modifier.heightIn(min = 48.dp).testTag("computer-details-${runtime.id}")) { Text(stringResource(R.string.action_computer_details)) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    state.runtimes.firstOrNull { it.id == detailId }?.let { runtime ->
        AlertDialog(
            onDismissRequest = { detailId = null },
            title = { Text(stringResource(R.string.action_computer_details)) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md)) {
                    item { DetailField(R.string.computer_name, runtime.hostAlias) }
                    item { DetailField(R.string.computer_host_id, runtime.hostId) }
                    item { DetailField(R.string.computer_runtime_id, runtime.id) }
                    item { DetailField(R.string.computer_organization, runtime.access?.organizationId ?: stringResource(R.string.value_unknown)) }
                    item { DetailField(R.string.computer_version, runtime.version.ifEmpty { stringResource(R.string.value_unknown) }) }
                    item { DetailField(R.string.computer_role, stringResource(when (runtime.access?.role) { "owner" -> R.string.role_owner; "operator" -> R.string.role_operator; "viewer" -> R.string.role_viewer; else -> R.string.value_unknown })) }
                }
            },
            confirmButton = { TextButton({ detailId = null }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

@Composable
private fun SettingsScreen(state: HomeState, onNotifications: () -> Unit, onAppearance: () -> Unit, onAppUpdates: () -> Unit, onExit: () -> Unit) {
    var confirmExit by remember(state.issuer, state.subject, state.deviceId, state.presentationOwner?.fingerprint) { mutableStateOf(false) }
    var accountDetails by remember(state.issuer, state.subject, state.deviceId, state.presentationOwner?.fingerprint) { mutableStateOf(false) }
    LazyColumn(Modifier.widthIn(max = 800.dp).fillMaxSize().testTag("settings-list"), contentPadding = PaddingValues(TokenWorksSpacing.lg), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.lg)) {
        item("account") {
            Text(stringResource(R.string.settings_account), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        }
        item("account-details") {
            SettingsItem(R.string.directory_account_details, state.deviceName ?: stringResource(R.string.settings_device_registered), { accountDetails = true })
        }
        item("appearance-heading") { SettingsHeading(R.string.directory_settings_appearance) }
        item("appearance") {
            SettingsItem(
                R.string.directory_appearance_entry,
                stringResource(if (state.presentationOwner != null) R.string.directory_appearance_detail else R.string.directory_appearance_unavailable),
                { if (state.presentationOwner != null) onAppearance() },
                enabled = state.presentationOwner != null,
                modifier = Modifier.testTag("settings-appearance"),
            )
        }
        item("reminders-heading") { SettingsHeading(R.string.directory_settings_reminders) }
        item("notifications") { SettingsItem(R.string.settings_notifications, stringResource(R.string.settings_notifications_detail), onNotifications) }
        item("updates-heading") { SettingsHeading(R.string.directory_settings_updates_about) }
        item("updates") { SettingsItem(R.string.settings_updates, stringResource(R.string.settings_updates_detail), onAppUpdates) }
        item("about") {
            Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
        }
        item("exit") {
            HorizontalDivider()
            TextButton({ confirmExit = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.action_exit_account)) }
        }
    }
    if (accountDetails) AlertDialog(
        onDismissRequest = { accountDetails = false },
        title = { Text(stringResource(R.string.directory_account_details)) },
        text = {
            LazyColumn(Modifier.testTag("account-detail-list"), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                item("subject") { DetailField(R.string.settings_subject, state.subject) }
                item("issuer") { DetailField(R.string.settings_account_source, state.issuer) }
                item("device-name") { DetailField(R.string.settings_device, state.deviceName ?: stringResource(R.string.settings_device_registered)) }
                item("device-id") { DetailField(R.string.settings_device_id, state.deviceId) }
                state.presentationOwner?.let { owner ->
                    item("account-id") { DetailField(R.string.directory_account_id, owner.accountId) }
                    item("organization") { DetailField(R.string.computer_organization, owner.organizationId) }
                    item("user-id") { DetailField(R.string.directory_user_id, owner.userId) }
                    item("deployment") { DetailField(R.string.directory_deployment_hash, owner.deploymentHash) }
                }
            }
        },
        confirmButton = { TextButton({ accountDetails = false }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_close)) } },
    )
    if (confirmExit) AlertDialog(
        onDismissRequest = { confirmExit = false },
        title = { Text(stringResource(R.string.settings_exit_title)) },
        text = { Text(stringResource(R.string.settings_exit_detail), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton({ confirmExit = false; onExit() }, Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.action_exit_account)) } },
        dismissButton = { TextButton({ confirmExit = false }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun SettingsItem(@StringRes title: Int, description: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(description) },
        trailingContent = { if (enabled) ShellIcon(R.drawable.ic_chevron) },
        modifier = modifier.then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier.semantics { disabled() }).heightIn(min = 64.dp),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

@Composable
private fun SettingsHeading(@StringRes title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun DetailField(@StringRes label: Int, value: String) {
    Column(Modifier.padding(vertical = TokenWorksSpacing.xs), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xs)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun DirectoryProgress(@StringRes text: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = TokenWorksSpacing.sm), horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        TokenWorksStatusNotice(stringResource(text), Modifier.weight(1f))
    }
}

@Composable
private fun EmptyDirectory(@StringRes title: Int, @StringRes detail: Int) {
    Column(Modifier.padding(vertical = TokenWorksSpacing.lg), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(stringResource(detail), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Collision checks use only the directory this identity currently contains. */
internal fun HomeState.sessionIdentifier(id: String): String = stableLabel(id, directoryRows(selectedRuntimeId)) { it.id }
