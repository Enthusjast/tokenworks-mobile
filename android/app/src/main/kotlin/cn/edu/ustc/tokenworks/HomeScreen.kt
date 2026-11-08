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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadyHomeRoute(state: HomeState, model: HomeViewModel, viewport: WorkspaceViewport, onCancel: () -> Unit) {
    // Only presentation state survives child pages. Leaving READY disposes this scope.
    val navigation = remember { HomeNavigation() }
    // Positions are scoped to this READY identity and contain only stable message keys and offsets.
    val readingPositions = remember { LinkedHashMap<Triple<String?, String, String>, ChatReadingPosition>() }
    var appearanceSettings by remember(state.presentationOwner?.fingerprint) { mutableStateOf(false) }
    val bindingState = state.binding
    val shellVisible = !appearanceSettings && !state.appUpdateSettings && !state.notificationSettings &&
        state.reviewingApprovalId == null && bindingState == null && state.creation == null && state.selectedSessionId == null
    val chromeVisible = !appearanceSettings && !state.appUpdateSettings && !state.notificationSettings &&
        state.reviewingApprovalId == null && bindingState == null && state.creation == null
    val layout = workspaceLayout(
        viewport = viewport,
        hasSession = state.selectedSessionId != null,
        panelRequested = navigation.chatDetail != null || state.modelSelectionOpen || state.changesOpen || state.messageActionsOpen,
        navigationExpanded = navigation.navigationExpanded,
    ).let {
        if (state.knownTarget != null) it.copy(leftPane = WorkspaceLeftPane.NONE, navigationWidth = 0.dp) else it
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val back: () -> Unit = {
        if (imeVisible) keyboard?.hide()
        else if (navigation.chatDetail != null) navigation.closeChatDetail()
        else model.back()
    }
    LaunchedEffect(state.selectedRuntimeId, state.selectedSessionId, state.reader?.phase, state.knownTarget) {
        navigation.recordVisited(state)
    }

    // Controller-driven entries (including creation results) share the same directory position.
    // A known-session notification remains on its exact-resource path, outside this shell.
    LaunchedEffect(state.selectedRuntimeId, state.creation, state.notificationSettings, state.appUpdateSettings) {
        if (state.notificationSettings || state.appUpdateSettings) {
            navigation.destination = HomeDestination.SETTINGS
        } else if (state.creation == null && state.knownTarget == null) {
            state.runtimes.firstOrNull { it.id == state.selectedRuntimeId }?.let {
                navigation.selectRuntime(it.id)
                navigation.destination = HomeDestination.SESSIONS
            }
        }
    }
    LaunchedEffect(shellVisible, navigation.destination, navigation.directoryRuntimeId, state.runtimes,
        state.selectedRuntimeId, state.refreshing, state.sessionError) {
        if (shellVisible && navigation.destination == HomeDestination.SESSIONS && state.selectedRuntimeId == null &&
            !state.refreshing && state.sessionError == null) {
            val eligible = state.runtimes.filter { it.access?.organizationId != null && it.access.canList() }
            val initial = if (navigation.directoryRuntimeId == null) eligible.firstOrNull()
                else eligible.firstOrNull { it.id == navigation.directoryRuntimeId }
            initial?.let {
                navigation.selectRuntime(it.id)
                model.openRuntime(it.id)
            }
        }
    }
    val closeChild: () -> Unit = {
        if (model.state.value.messageActionsOpen) model.dismissMessageActions()
        if (model.state.value.changesOpen) model.closeChanges()
        if (model.state.value.modelSelectionOpen) model.closeModelSelection()
        navigation.closeChatDetail()
    }
    val selectDestination: (HomeDestination) -> Unit = { destination ->
        navigation.closePresentationPanels()
        if (state.knownTarget != null) model.back()
        else {
            closeChild()
            if (destination != navigation.destination || destination != HomeDestination.SESSIONS) {
                // The shortcut follows the same child -> chat -> directory retirement as Back.
                if (model.state.value.selectedSessionId != null) model.back()
                if (model.state.value.selectedRuntimeId != null) model.back()
                navigation.directoryFromComputer = false
                navigation.destination = destination
            }
        }
    }
    val backToComputers: () -> Unit = { selectDestination(HomeDestination.COMPUTERS) }
    val openNavigation: () -> Unit = {
        when (layout.leftPane) {
            WorkspaceLeftPane.SIDEBAR -> navigation.navigationExpanded = false
            WorkspaceLeftPane.RAIL -> {
                closeChild()
                navigation.navigationExpanded = true
            }
            WorkspaceLeftPane.NONE -> navigation.drawerOpen = true
        }
    }
    val selectRuntime: (String) -> Unit = { id ->
        navigation.closePresentationPanels()
        navigation.directoryFromComputer = navigation.destination == HomeDestination.COMPUTERS
        navigation.destination = HomeDestination.SESSIONS
        navigation.selectRuntime(id)
        if (id != model.state.value.selectedRuntimeId) model.openRuntime(id)
    }
    val selectSession: (String) -> Unit = { id ->
        navigation.drawerOpen = false
        if (id != model.state.value.selectedSessionId) {
            navigation.closePresentationPanels()
            model.openSession(id)
        }
    }
    val sourceOwner = state.presentationOwner?.fingerprint
    val sourceHost = state.runtimeRow(state.selectedRuntimeId)?.hostId
    val readingResource = remember(sourceHost, state.selectedRuntimeId, state.selectedSessionId) {
        state.selectedRuntimeId?.let { runtimeId -> state.selectedSessionId?.let { sessionId -> Triple(sourceHost, runtimeId, sessionId) } }
    }
    val changeDetail: (ChatDetailTarget?) -> Unit = { target ->
        val current = model.state.value
        if (current.presentationOwner?.fingerprint == sourceOwner &&
            current.selectedRuntimeId == state.selectedRuntimeId && current.selectedSessionId == state.selectedSessionId &&
            current.runtimeRow(current.selectedRuntimeId)?.hostId == sourceHost) navigation.setChatDetail(current, target)
    }
    SideEffect {
        navigation.reconcileChatDetail(state)
        if (!chromeVisible) navigation.closePresentationPanels()
        if (state.modelSelectionOpen || state.changesOpen || state.messageActionsOpen || state.reviewingApprovalId != null) {
            readingResource?.let { readingPositions[it] }?.retainExpandedSources(state.reader?.window?.items.orEmpty())
        }
    }

    val hasRoute = appearanceSettings && state.presentationOwner != null || state.appUpdateSettings || state.notificationSettings ||
        state.reviewingApprovalId != null || bindingState != null || state.creation != null ||
        state.changesOpen && state.changes != null || state.modelSelectionOpen || state.messageActionsOpen && state.messageActions != null || state.selectedSessionId != null
    val backdropPosition = readingResource?.let { readingPositions[it] }
    val routeContent: (@Composable () -> Unit)? = if (!hasRoute) null else { {
    if (appearanceSettings && state.presentationOwner != null) {
        BackHandler { appearanceSettings = false }
        AppearanceSettingsScreen(state.presentationOwner) { appearanceSettings = false }
    } else if (state.appUpdateSettings) {
        BackHandler { model.closeAppUpdates() }
        val update = state.appUpdates ?: AppUpdateUiState()
        AppUpdatesScreen(update, { model.checkAppUpdates(update.viewId) },
            { model.downloadAppUpdate(update.viewId, update.release?.document?.sha256()) },
            { model.installAppUpdate(update.viewId, update.attemptId, update.release?.document?.sha256()) },
            { model.requestAppInstallPermission(update.viewId) }, { token -> model.cleanupAppUpdate(update.viewId, token) },
            { model.cancelAppUpdateDownload(update.viewId) }, model::closeAppUpdates)
    } else if (state.notificationSettings) {
        BackHandler { model.closeNotificationSettings() }
        val notifications = state.notifications ?: NotificationUiState()
        NotificationsScreen(notifications.copy(note = state.notificationNote ?: notifications.note), model::refreshNotifications, model::enableNotifications, model::disableNotifications, model::renewNotifications,
            { model.notificationInbox(false) }, { model.notificationInbox(true) }, model::openNotification, model::closeNotificationSettings)
    } else if (state.reviewingApprovalId != null) {
        BackHandler { model.closeApprovalReview() }
        ApprovalReviewScreen(state, model::closeApprovalReview, model::reviewApproval, model::decideApproval, model::sendOriginalApproval,
            model::queryApprovals, model::discardUnsentApproval, model::refreshApprovals, model::reauthenticateApproval)
    } else if (bindingState != null) {
        BackHandler(onBack = { if (imeVisible) keyboard?.hide() else model.closeBinding() })
        HostBindingScreen(bindingState, state.subject, model::previewBinding, model::queryBinding, model::confirmBinding, model::refreshBinding, model::enterAnotherBinding,
            { if (imeVisible) keyboard?.hide() else model.closeBinding() })
    } else if (state.creation != null) {
        BackHandler(onBack = back)
        SessionCreationScreen(state, model::createSession, model::refreshCreationOptions, model::queryCreation, model::sendPendingCreation, model::openCreatedSession, back)
    } else if (state.messageActionsOpen && state.messageActions != null) {
        val actions = state.messageActions
        val original = actions.originalRequestId?.let { id -> state.outbox?.records?.firstOrNull { it.intent.requestId == id } }
        val presentation = when {
            layout.size == WorkspaceSize.EXPANDED -> TaskPresentation.SIDE_PANEL
            viewport.heightDp >= 480 -> TaskPresentation.SHEET
            else -> TaskPresentation.PAGE
        }
        val dismiss: () -> Unit = { if (imeVisible) keyboard?.hide() else model.dismissMessageActions() }
        AdaptiveSessionPanel(layout, presentation, dismiss,
            backdrop = { ReadOnlySessionBackdrop(state, backdropPosition) }) {
            key(presentation) {
                SessionMessageActionPreview(actions.copy(error = state.sessionActionError ?: actions.error), original,
                    fork = { actions.preview?.anchor?.let(model::forkMessage) },
                    share = { actions.preview?.anchor?.let(model::shareMessage) },
                    queryOriginal = model::queryFork, openChild = model::openForkChild, dismiss = dismiss, embedded = true)
            }
        }
    } else if (state.changesOpen && state.changes != null) {
        val changes = state.changes
        val uiState = remember(sourceOwner, state.selectedRuntimeId, state.selectedSessionId, changes.contextId) { ChangesPanelUiState() }
        val patchUiState = remember(sourceOwner, state.selectedRuntimeId, state.selectedSessionId, changes.contextId,
            changes.patch?.fileId, changes.patch?.version) { ChangePatchUiState() }
        val presentation = when (layout.size) {
            WorkspaceSize.COMPACT -> TaskPresentation.PAGE
            WorkspaceSize.MEDIUM -> TaskPresentation.SHEET
            WorkspaceSize.EXPANDED -> TaskPresentation.SIDE_PANEL
        }
        val dismiss: () -> Unit = { if (imeVisible) keyboard?.hide() else model.closeChanges() }
        AdaptiveSessionPanel(layout, presentation, dismiss,
            backdrop = { ReadOnlySessionBackdrop(state, backdropPosition) }) {
            key(presentation) {
                SessionChangesScreen(changes.copy(error = state.sessionActionError ?: changes.error),
                    model::loadChanges, model::loadNextChanges, model::reloadChanges, model::loadChangePatch,
                    model::loadNextChangePatch, model::copyChangePatch, dismiss,
                    uiState = uiState, embedded = true, patchUiState = patchUiState)
            }
        }
    } else if (state.modelSelectionOpen) {
        val catalog = requireNotNull(state.modelCatalog)
        val uiState = remember(sourceOwner, state.selectedRuntimeId, state.selectedSessionId, catalog.contextId) { ModelPanelUiState() }
        val original = remember(state.outbox?.records) {
            state.outbox?.records.orEmpty().filter { it.intent.kind == CommandKind.MODEL_SELECT }
                .maxWithOrNull(compareBy({ if (it.remote != RemoteCommandState.SETTLED && it.remote != RemoteCommandState.REJECTED) 1 else 0 }, { it.createdAt }, { it.intent.requestId }))
        }
        val presentation = when {
            layout.size == WorkspaceSize.EXPANDED -> TaskPresentation.SIDE_PANEL
            viewport.heightDp >= 480 -> TaskPresentation.SHEET
            else -> TaskPresentation.PAGE
        }
        val dismiss: () -> Unit = { if (imeVisible) keyboard?.hide() else model.closeModelSelection() }
        AdaptiveSessionPanel(layout, presentation, dismiss,
            backdrop = { ReadOnlySessionBackdrop(state, backdropPosition) }) {
            key(presentation) {
                SessionModelsScreen(catalog, original, state.modelBusy,
                    state.modelError ?: state.outbox?.error, model::loadModels, model::loadNextModels, model::reloadModels,
                    model::selectModel, model::queryModelSelection, model::sendPending, model::discardUnsentModelSelection,
                    dismiss, uiState = uiState, embedded = true)
            }
        }
    } else if (state.selectedSessionId != null) {
        key(state.selectedRuntimeId, state.selectedSessionId) {
            val position = remember(readingResource) {
                if (readingResource == null) ChatReadingPosition()
                else readingPositions.remove(readingResource)?.also { readingPositions[readingResource] = it } ?: ChatReadingPosition().also {
                    if (readingPositions.size >= 16) readingPositions.remove(readingPositions.keys.first())
                    readingPositions[readingResource] = it
                }
            }
            SessionsScreen(state, back, model::refreshSessions, model::loadOlder, model::retryRead,
                model::submitMessage, model::sendPending, model::queryCommands, model::cancelSession, model::answerQuestion, model::claimQuestion, model::releaseQuestion, model::refreshQuestions,
                model::reviewApproval, model::queryApprovals, model::archiveSession,
                model::pickDocument, model::documentPicked, model::toggleUpload, model::queryUpload, model::removeUpload, model::cancelUpload,
                model::browseExports, model::requestExport, model::queryExports, model::cancelExport, model::removeExport,
                model::chooseExportDestination, model::exportDestinationPicked, model::cancelExportSave, readingPosition = position,
                onComposerChange = model::editComposer, onModels = model::openModelSelection,
                onChanges = model::openChanges, onMessageAction = model::previewMessage,
                onPickImage = { model.pickImage() }, onPickCamera = model::pickCamera, onPreviewMedia = model::previewImage,
                onDismissImagePreview = model::dismissImagePreview, onCancelImage = model::cancelImage,
                onReselectImage = { model.pickImage(it) }, onResolveFailedInput = model::resolveFailedInput,
                layout = layout, detailTarget = navigation.chatDetail, onDetailsChange = changeDetail,
                onOpenNavigation = openNavigation,
                onOpenHosts = { if (state.knownTarget == null) navigation.hostSwitcherOpen = true else navigation.openChatDetail(state, ChatDetailSection.INFO) },
                onCreateSession = { state.selectedRuntimeId?.let(model::openCreation) })
        }
    }
    } }
    BackHandler(enabled = shellVisible && (navigation.destination != HomeDestination.SESSIONS || navigation.directoryFromComputer)) {
        if (imeVisible) keyboard?.hide()
        else if (navigation.directoryFromComputer) backToComputers()
        else selectDestination(HomeDestination.SESSIONS)
    }
    TokenWorksAppShell(state, navigation, selectDestination,
        onRuntime = selectRuntime, onSession = selectSession,
        onRefresh = model::refresh, onRefreshSessions = model::refreshSessions,
        onCreate = { id -> navigation.closePresentationPanels(); model.openCreation(id) },
        onBinding = { navigation.closePresentationPanels(); model.openBinding() },
        onNotifications = { navigation.closePresentationPanels(); model.openNotificationSettings() },
        onAppUpdates = { navigation.closePresentationPanels(); model.openAppUpdates() },
        onAppearance = { navigation.closePresentationPanels(); if (state.presentationOwner != null) appearanceSettings = true },
        onExit = onCancel, onBackToComputers = backToComputers,
        layout = layout, chromeVisible = chromeVisible, onOpenNavigation = openNavigation, content = routeContent)
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
