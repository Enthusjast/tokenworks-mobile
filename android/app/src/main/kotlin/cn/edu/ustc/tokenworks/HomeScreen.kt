package cn.edu.ustc.tokenworks

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
internal fun HomeRoute(model: HomeViewModel, onSecuritySettings: () -> Unit,
    viewport: WorkspaceViewport = rememberWorkspaceViewport(), onCancel: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    if (state.phase == HomePhase.READY) {
        key(model, state.presentationOwner?.fingerprint) {
            ReadyHomeRoute(state, model, viewport, onCancel)
        }
    } else {
        HomeScreen(state, model::login, model::register, onSecuritySettings, onCancel)
    }
}

@Composable
private fun ReadyHomeRoute(state: HomeState, model: HomeViewModel, viewport: WorkspaceViewport, onCancel: () -> Unit) {
    var navigationExpanded by remember { mutableStateOf(true) }
    var appearanceSettings by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var hostsOpen by remember { mutableStateOf(false) }
    var terminalOpen by remember { mutableStateOf(false) }
    val terminalWatcher = state.terminal
    val positions = remember { LinkedHashMap<Pair<String, String>, ChatReadingPosition>() }
    val snackbar = remember { SnackbarHostState() }
    val actionError = state.sessionActionError?.let { stringResource(R.string.session_action_error, it) }
    val homeError = state.error?.let { errorText(it) }
    LaunchedEffect(actionError) { actionError?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(homeError) { homeError?.let { snackbar.showSnackbar(it) } }
    val layout = workspaceLayout(viewport, state.selectedSessionId != null, state.modelSelectionOpen, navigationExpanded)
    val overlay = appearanceSettings || settingsOpen || state.appUpdateSettings || state.binding != null || state.creation != null || terminalOpen
    BackHandler { model.back() }
    Box(Modifier.fillMaxSize()) {
        WorkspaceScaffold(
            layout = layout,
            chromeVisible = !overlay,
            railContent = { openNavigation -> WorkspaceRail(openNavigation, { hostsOpen = true }, { settingsOpen = true }) },
            navigationContent = { closeNavigation ->
                TokenWorksAppShell(state, model, onHosts = { closeNavigation(); hostsOpen = true },
                    onSettings = { closeNavigation(); settingsOpen = true },
                    onCloseNavigation = { if (layout.leftPane == WorkspaceLeftPane.SIDEBAR) navigationExpanded = false else closeNavigation() })
            },
        ) { toggleNavigation ->
            val openNavigation: () -> Unit = {
                if (layout.leftPane == WorkspaceLeftPane.SIDEBAR) navigationExpanded = false else toggleNavigation()
            }
            when {
                appearanceSettings && state.presentationOwner != null -> {
                    BackHandler { appearanceSettings = false }
                    AppearanceSettingsScreen(state.presentationOwner) { appearanceSettings = false }
                }
                terminalOpen -> {
                    BackHandler { terminalOpen = false }
                    terminalWatcher?.let { TerminalScreen(it) }
                }
                state.appUpdateSettings -> {
                    BackHandler { model.closeAppUpdates() }
                    val update = state.appUpdates ?: AppUpdateUiState()
                    AppUpdatesScreen(update, { model.checkAppUpdates(update.viewId) },
                        { model.downloadAppUpdate(update.viewId, update.release?.document?.sha256()) },
                        { model.installAppUpdate(update.viewId, update.attemptId, update.release?.document?.sha256()) },
                        { model.requestAppInstallPermission(update.viewId) }, { token -> model.cleanupAppUpdate(update.viewId, token) },
                        { model.cancelAppUpdateDownload(update.viewId) }, model::closeAppUpdates)
                }
                state.binding != null -> {
                    BackHandler { model.closeBinding() }
                    HostBindingScreen(state.binding, state.subject, model::previewBinding, model::queryBinding, model::confirmBinding,
                        model::refreshBinding, model::enterAnotherBinding, model::closeBinding)
                }
                state.creation != null -> SessionCreationScreen(state, model)
                settingsOpen -> {
                    BackHandler { settingsOpen = false }
                    WorkspaceSettings(state, onAppearance = { appearanceSettings = true }, onAppUpdates = model::openAppUpdates,
                        onExit = onCancel, onBack = { settingsOpen = false })
                }
                state.selectedSessionId != null -> key(state.selectedRuntimeId, state.selectedSessionId) {
                    val resource = state.selectedRuntimeId.orEmpty() to state.selectedSessionId
                    val position = remember(resource) {
                        positions[resource] ?: ChatReadingPosition().also {
                            if (positions.size >= 16) positions.remove(positions.keys.first())
                            positions[resource] = it
                        }
                    }
                    NativeSessionContent(state, position, SessionContentActions(model), openNavigation, { hostsOpen = true })
                }
                else -> Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    TokenWorksTopBar(stringResource(R.string.nav_sessions), navigationIcon = {
                        IconButton(openNavigation, Modifier.size(TokenWorksSpacing.minimumTouchTarget)) {
                            ShellIcon(R.drawable.ic_menu, R.string.workspace_open_navigation)
                        }
                    }, actions = {
                        IconButton({ settingsOpen = true }, Modifier.size(TokenWorksSpacing.minimumTouchTarget)) {
                            ShellIcon(R.drawable.ic_settings, R.string.nav_settings)
                        }
                    })
                    Column(Modifier.fillMaxWidth().padding(TokenWorksSpacing.xxl), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md)) {
                        Text(stringResource(R.string.workspace_start_session), style = MaterialTheme.typography.displaySmall,
                            modifier = Modifier.semantics { heading() })
                        Text(stringResource(R.string.workspace_choose_session_detail), style = MaterialTheme.typography.bodyMedium,
                            color = tokenWorksSurfaces().textTertiary)
                        Button(openNavigation, Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                            Text(stringResource(if (state.selectedRuntimeId == null) R.string.session_select_computer else R.string.action_open_sessions))
                        }
                    }
                }
            }
        }
        // F-04: pending approvals and questions float above everything.
        if (state.interactions.isNotEmpty()) {
            InteractionCards(
                interactions = state.interactions,
                onApprove = model::approveInteraction,
                onReject = { model.rejectInteraction(it, "在手机上拒绝") },
                onAnswers = model::submitAnswers,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(TokenWorksSpacing.md))
    }
    if (hostsOpen && !overlay) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { hostsOpen = false }) {
            Surface(shape = tokenWorksSquircle(TokenWorksRadius.xl), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().heightIn(max = viewport.heightDp.dp)) {
                    TextButton({ hostsOpen = false }, Modifier.align(Alignment.End).heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                        Text(stringResource(R.string.action_close))
                    }
                    RuntimePicker(state, model, Modifier.weight(1f, fill = false), onRuntime = { hostsOpen = false; model.openRuntime(it) })
                }
            }
        }
    }
    if (state.modelSelectionOpen && !overlay) {
        AdaptiveSessionPanel(layout, if (layout.panelShown) TaskPresentation.SIDE_PANEL else TaskPresentation.SHEET,
            model::closeModelSelection, backdrop = {}) {
            SessionModelsScreen(state, model, currentKey = null)
        }
    }
}

/** Every effect the chat surface can raise, so the production screen can be hosted without a controller. */
internal class SessionContentActions(
    val editDraft: (TextFieldValue) -> Unit = {},
    val send: () -> Unit = {},
    val cancel: () -> Unit = {},
    val openModels: () -> Unit = {},
    val openCreation: () -> Unit = {},
    val loadOlder: () -> Unit = {},
    val fork: () -> Unit = {},
    val archive: () -> Unit = {},
    val rename: (String) -> Unit = {},
) {
    constructor(model: HomeViewModel) : this(model::editDraft, model::sendDraft, model::cancelSession,
        model::openModelSelection, model::openCreation, model::loadOlder, model::forkSession,
        model::archiveSession, model::renameSession)

}

@Composable
internal fun NativeSessionContent(state: HomeState, position: ChatReadingPosition, actions: SessionContentActions,
    onNavigation: () -> Unit, onHosts: () -> Unit) {
    val sessionId = requireNotNull(state.selectedSessionId)
    val entry = state.sessions.firstOrNull { it.id == sessionId }
    val runtime = state.runtimeRow(state.selectedRuntimeId)
    val read = state.reader ?: SessionReadState(SessionReadPhase.CONNECTING)
    var moreOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var selectedMessageId by remember { mutableStateOf<String?>(null) }
    val connectionLabel = stringResource(when (read.phase) {
        SessionReadPhase.CONNECTING -> R.string.conversation_connecting
        SessionReadPhase.SYNCING -> R.string.conversation_syncing
        SessionReadPhase.READY -> R.string.conversation_live
        SessionReadPhase.BACKOFF -> R.string.conversation_reconnecting
        SessionReadPhase.AUTH_REQUIRED -> R.string.conversation_auth_required
        SessionReadPhase.REVOKED -> R.string.conversation_revoked
        SessionReadPhase.ERROR -> R.string.conversation_read_error
        SessionReadPhase.CLOSED -> R.string.conversation_paused
    })
    val quote: (String) -> Unit = { text ->
        val current = state.composer?.value?.text.orEmpty()
        val combined = buildString {
            append(current)
            if (isNotEmpty()) append("\n\n")
            append(text.lineSequence().joinToString("\n") { "> $it" })
            append('\n')
        }
        actions.editDraft(TextFieldValue(combined, TextRange(combined.length)))
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        SessionHeader(entry?.metadata?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.session_untitled),
            connectionLabel, when (read.phase) {
                SessionReadPhase.READY -> HeaderConnection.LIVE
                SessionReadPhase.CONNECTING, SessionReadPhase.SYNCING -> HeaderConnection.PENDING
                SessionReadPhase.BACKOFF, SessionReadPhase.CLOSED -> HeaderConnection.DEGRADED
                else -> HeaderConnection.ERROR
            }, modelsEnabled = runtime?.access?.canReadModelCatalog() == true && !state.modelBusy,
            createEnabled = runtime?.access?.canCreate() == true,
            onNavigation = onNavigation, onHosts = onHosts, onModels = actions.openModels,
            onCreate = actions.openCreation, onMore = { moreOpen = true })
        NativeChatTimeline(read, position, actions.loadOlder, quote, { selectedMessageId = it },
            Modifier.widthIn(max = TokenWorksSpacing.readingWidth).fillMaxWidth().weight(1f))
        ChatComposer(state, actions.editDraft, actions.send, actions.cancel, actions.openModels,
            Modifier.widthIn(max = TokenWorksSpacing.readingWidth).fillMaxWidth())
    }
    if (moreOpen) AlertDialog(onDismissRequest = { moreOpen = false }, title = { Text(stringResource(R.string.session_actions_title)) },
        text = {
            Column {
                TextButton({ moreOpen = false; title = entry?.metadata?.title.orEmpty(); renameOpen = true },
                    enabled = runtime?.access?.canRename(sessionId) == true && !state.submitting) { Text(stringResource(R.string.session_rename)) }
                TextButton({ moreOpen = false; actions.fork() }, enabled = runtime?.access?.canFork(sessionId) == true && !state.submitting) {
                    Text(stringResource(R.string.session_fork))
                }
                TextButton({ moreOpen = false; actions.archive() },
                    enabled = runtime?.access?.allows("workspace/archiveSession", sessionId, entry?.workspaceId) == true && !state.submitting) {
                    Text(stringResource(R.string.conversation_archive))
                }
            }
        }, confirmButton = { TextButton({ moreOpen = false }) { Text(stringResource(R.string.action_close)) } })
    if (renameOpen) AlertDialog(onDismissRequest = { renameOpen = false }, title = { Text(stringResource(R.string.session_rename)) },
        text = { OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.session_title_label)) }, singleLine = true) },
        confirmButton = { TextButton({ renameOpen = false; actions.rename(title.trim()) }, enabled = title.isNotBlank() && !state.submitting) {
            Text(stringResource(R.string.action_confirm))
        } }, dismissButton = { TextButton({ renameOpen = false }) { Text(stringResource(R.string.action_cancel)) } })
    val message = read.items.firstOrNull { it.id == selectedMessageId }
    if (message != null) {
        val text = remember(message) { message.blocks.joinToString("\n\n") { it.text } }
        AlertDialog(onDismissRequest = { selectedMessageId = null }, title = { Text(stringResource(R.string.conversation_message_more_description)) },
            text = { SelectionContainer { Text(text, Modifier.heightIn(max = TokenWorksMetrics.panelIdealMin).verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton({ selectedMessageId = null; quote(text) }, enabled = state.composer != null && text.isNotBlank()) {
                Text(stringResource(R.string.conversation_message_quote))
            } }, dismissButton = { TextButton({ selectedMessageId = null }) { Text(stringResource(R.string.action_close)) } })
    }
}

@Composable
internal fun NativeChatTimeline(read: SessionReadState, position: ChatReadingPosition, onLoadOlder: () -> Unit,
    onQuote: (String) -> Unit, onMessageAction: (String) -> Unit, modifier: Modifier = Modifier) {
    val projection = remember(read.items, read.running) { projectChatTimeline(read.items, read.running) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var showJump by remember { mutableStateOf(false) }
    val keys = remember(projection, position.expandedSourceIds) { listOf("history") + projection.renderKeys(position.expandedSourceIds) + "read-status" }
    val latestProjection by rememberUpdatedState(projection)
    var restored by remember(position) { mutableStateOf(false) }
    LaunchedEffect(keys, read.items.lastOrNull(), read.window != null) {
        if (read.window == null) return@LaunchedEffect
        position.retainExpandedSources(read.items)
        val source = position.key?.let { projection.renderKeyForSource(it, position.expandedSourceIds) }
        if (position.following) {
            if (!listState.isScrollInProgress) listState.scrollToItem(keys.lastIndex)
        } else if (source != null && !listState.isScrollInProgress) {
            listState.scrollToItem(keys.indexOf(source).coerceAtLeast(0), position.offset)
        }
        restored = true
    }
    LaunchedEffect(listState, position) {
        snapshotFlow { Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.isScrollInProgress) }.collect { (_, _, scrolling) ->
            if (!restored) return@collect
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull { latestProjection.sourceIdForRenderKey(it.key.toString()) != null }
            first?.let {
                position.key = latestProjection.sourceIdForRenderKey(it.key.toString())
                position.offset = (-it.offset).coerceAtLeast(0)
            }
            if (scrolling) position.following = !listState.canScrollForward
            showJump = !position.following
        }
    }
    val scope = rememberCoroutineScope()
    Box(modifier) {
    ChatTranscript(projection, listState, position.expandedSourceIds, interactive = true,
        leadingContent = {
            item("history") {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (read.window?.hasMore == true || read.pageError != null) TextButton(onLoadOlder,
                        enabled = !read.loadingOlder && read.phase == SessionReadPhase.READY) {
                        Text(stringResource(if (read.loadingOlder) R.string.conversation_history_loading else R.string.conversation_history_older))
                    }
                    if (read.pageError != null) TokenWorksStatusNotice(stringResource(R.string.conversation_history_error), isError = true)
                }
            }
        }, trailingContent = {
            item("read-status") {
                val failure = read.error
                when {
                    failure != null -> TokenWorksStatusNotice(errorText(failure), isError = true)
                    read.phase == SessionReadPhase.CONNECTING || read.phase == SessionReadPhase.SYNCING -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    read.items.isEmpty() -> Text(stringResource(R.string.conversation_empty), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }, onProcessExpansion = { ids, expanded ->
            position.expandedSourceIds = if (expanded) position.expandedSourceIds + ids else position.expandedSourceIds - ids.toSet()
        }, onQuote = onQuote, onMessageAction = onMessageAction, onPreviewMedia = null, modifier = Modifier.fillMaxSize())
    if (showJump && !position.following) FilledTonalButton(
        onClick = {
            position.following = true; showJump = false
            scope.launch { listState.animateScrollToItem(keys.lastIndex) }
        },
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = TokenWorksSpacing.md)) {
        Text(stringResource(R.string.conversation_jump_latest), style = MaterialTheme.typography.labelMedium)
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(state: HomeState, onLogin: () -> Unit, onRegister: (String) -> Unit, onSecuritySettings: () -> Unit, onCancel: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("${Build.MANUFACTURER} ${Build.MODEL}".trim()) }
    val displayName = state.deviceName ?: name
    val nameValid = displayName.trim().isNotEmpty() && displayName.trim().toByteArray(Charsets.UTF_8).size <= 160 && displayName.none { it < ' ' || it == '\u007f' }
    var showSource by remember(state.issuer, state.subject, state.deviceId) { mutableStateOf(false) }
    val enrolling = when (state.phase) {
        HomePhase.ENROLL, HomePhase.PREPARING, HomePhase.AUTHENTICATING, HomePhase.REGISTERING,
        HomePhase.RECONCILING, HomePhase.REGISTRATION_UNKNOWN -> true
        else -> false
    }
    val currentStep = stringResource(R.string.workspace_current_step)
    Scaffold { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(painterResource(R.drawable.ic_tokenworks), null, Modifier.size(40.dp))
                    Column {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                        Text(stringResource(R.string.workspace_account_tagline), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (step in 0..2) {
                        val active = step == if (enrolling) 1 else 0
                        Surface(color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            shape = MaterialTheme.shapes.extraLarge) {
                            Text(stringResource(when (step) { 0 -> R.string.workspace_step_account; 1 -> R.string.workspace_step_device; else -> R.string.workspace_step_computer }),
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp).semantics { if (active) stateDescription = currentStep },
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().testTag("onboarding-${state.phase.name}"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val title = when (state.phase) {
                        HomePhase.UNCONFIGURED -> R.string.workspace_account_unconfigured
                        HomePhase.INVALID_CONFIGURATION -> R.string.workspace_account_invalid
                        else -> if (enrolling) R.string.workspace_device_title else R.string.workspace_account_title
                    }
                    Text(stringResource(title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    when (state.phase) {
                        HomePhase.READY -> error("READY requires task-focused navigation")
                        HomePhase.UNCONFIGURED -> Text(stringResource(R.string.workspace_account_configuration_help))
                        HomePhase.INVALID_CONFIGURATION -> Text(stringResource(R.string.workspace_account_invalid_help))
                        HomePhase.SIGNED_OUT -> {
                            Text(stringResource(R.string.workspace_account_browser_description))
                            Button(onLogin, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboarding-login")) {
                                Text(stringResource(R.string.workspace_account_browser_login))
                            }
                        }
                        HomePhase.REAUTHENTICATE -> {
                            Text(stringResource(R.string.workspace_account_reauthenticate_description))
                            Button(onLogin, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboarding-reauthenticate")) {
                                Text(stringResource(R.string.workspace_account_reauthenticate))
                            }
                        }
                        HomePhase.RECOVERY_ERROR -> {
                            Text(stringResource(if (state.error == "account.cleanup_failed") R.string.workspace_recovery_cleanup_description else R.string.workspace_recovery_failed))
                            Button(onLogin, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboarding-retry")) {
                                Text(stringResource(if (state.error == "account.cleanup_failed") R.string.workspace_recovery_cleanup_retry else R.string.workspace_recovery_retry))
                            }
                        }
                        HomePhase.ENROLL -> {
                            Text(stringResource(R.string.workspace_account_registered))
                            OutlinedTextField(displayName, { name = it }, label = { Text(stringResource(R.string.workspace_device_name)) },
                                supportingText = { Text(stringResource(if (state.deviceName != null) R.string.workspace_device_original_name
                                    else if (nameValid) R.string.workspace_device_name_hint else R.string.workspace_device_name_error)) },
                                enabled = state.deviceName == null, isError = !nameValid, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Button({ onRegister(displayName) }, enabled = nameValid,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboarding-register")) {
                                Text(stringResource(R.string.workspace_device_register))
                            }
                            if (state.securitySetupRequired) OutlinedButton(onSecuritySettings, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.workspace_security_settings))
                            }
                        }
                        HomePhase.REGISTRATION_UNKNOWN -> {
                            Text(stringResource(R.string.workspace_device_unknown_description))
                            Button({ onRegister(displayName) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboarding-query-registration")) {
                                Text(stringResource(R.string.workspace_device_query))
                            }
                        }
                        else -> Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Text(stringResource(when (state.phase) {
                                HomePhase.LOADING -> R.string.workspace_account_loading
                                HomePhase.RECOVERING -> R.string.workspace_account_recovering
                                HomePhase.RENEWING -> R.string.workspace_account_renewing
                                HomePhase.CLEARING -> R.string.workspace_account_clearing
                                HomePhase.STARTING -> R.string.workspace_account_starting
                                HomePhase.BROWSER -> R.string.workspace_account_browser_waiting
                                HomePhase.EXCHANGING -> R.string.workspace_account_exchanging
                                HomePhase.PREPARING -> R.string.workspace_device_preparing
                                HomePhase.AUTHENTICATING -> R.string.workspace_device_authenticating
                                HomePhase.REGISTERING -> R.string.workspace_device_registering
                                HomePhase.RECONCILING -> R.string.workspace_device_reconciling
                                else -> error("Unexpected authentication progress phase: ${state.phase}")
                            }), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    if (state.issuer.isNotEmpty() || state.subject.isNotEmpty() || state.deviceId.isNotEmpty()) {
                        TextButton({ showSource = !showSource }, Modifier.heightIn(min = 48.dp).testTag("onboarding-source")) {
                            Text(stringResource(if (showSource) R.string.onboarding_source_close else R.string.onboarding_source_open))
                        }
                        if (showSource) SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (state.issuer.isNotEmpty()) Text(state.issuer, style = MaterialTheme.typography.bodySmall)
                                if (state.subject.isNotEmpty()) Text(state.subject, style = MaterialTheme.typography.bodySmall)
                                if (state.deviceId.isNotEmpty()) Text(state.deviceId, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    state.error?.let { code ->
                        cn.edu.ustc.tokenworks.TokenWorksStatusNotice(errorText(code), isError = true)
                        if (code == "vault.lock_required" && state.phase != HomePhase.ENROLL) {
                            OutlinedButton(onSecuritySettings, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.workspace_security_settings)) }
                        }
                    }
                    val canLeave = when (state.phase) {
                        HomePhase.LOADING, HomePhase.CLEARING, HomePhase.UNCONFIGURED, HomePhase.INVALID_CONFIGURATION, HomePhase.SIGNED_OUT -> false
                        else -> true
                    }
                    if (canLeave) TextButton(onCancel, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onboarding-cancel")) {
                        Text(stringResource(when (state.phase) {
                            HomePhase.REAUTHENTICATE -> R.string.workspace_account_exit
                            HomePhase.RECOVERY_ERROR -> R.string.workspace_account_clear
                            else -> R.string.workspace_account_cancel
                        }))
                    }
                }
                Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
internal fun errorText(code: String): String = stringResource(when (code) {
    "login.browser_unavailable" -> R.string.workspace_error_browser
    "login.invalid_callback" -> R.string.workspace_error_callback
    "login.expired", "auth.expired", "login.identity" -> R.string.workspace_error_expired
    "login.cancelled", "login.authentication_cancelled" -> R.string.workspace_error_cancelled
    "login.reauthentication", "auth.reauthentication_required" -> R.string.workspace_error_reauthentication
    "login.account_mismatch" -> R.string.workspace_error_account_mismatch
    "auth.identity_mismatch" -> R.string.workspace_error_identity_mismatch
    "login.configuration" -> R.string.workspace_error_configuration
    "vault.lock_required" -> R.string.workspace_error_lock_required
    "vault.locked" -> R.string.workspace_error_locked
    "vault.key_missing", "vault.corrupt", "vault.unavailable", "vault.conflict" -> R.string.workspace_error_vault
    "device.lock_required", "device.biometric_required" -> R.string.workspace_error_biometric
    "device.key_invalidated", "device.key_missing" -> R.string.workspace_error_key_invalidated
    "access.denied", "access.revoked", "device.invalid" -> R.string.workspace_error_access
    "access.organization_missing" -> R.string.workspace_error_organization
    "auth.proof_invalid" -> R.string.workspace_error_proof
    "device.registration_not_found" -> R.string.workspace_error_registration_missing
    "device.registration_unknown" -> R.string.workspace_error_registration_unknown
    "device.registration_conflict" -> R.string.workspace_error_registration_conflict
    else -> R.string.workspace_error_operation
})
