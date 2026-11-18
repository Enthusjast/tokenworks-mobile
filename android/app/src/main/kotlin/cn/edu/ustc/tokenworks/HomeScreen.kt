package cn.edu.ustc.tokenworks

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeRoute(model: HomeViewModel, onSecuritySettings: () -> Unit,
    viewport: WorkspaceViewport = rememberWorkspaceViewport(), onCancel: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val management by model.management.collectAsStateWithLifecycle()
    if (state.phase == HomePhase.READY) {
        key(model, state.presentationOwner?.fingerprint) {
            ReadyHomeRoute(state, management, model, viewport, onCancel)
        }
    } else {
        HomeScreen(state, model::login, model::register, onSecuritySettings, onCancel, onLanOnly = model::enterLanOnly)
    }
}

private enum class ChatPicker { FILE, IMAGE }

@Composable
private fun ReadyHomeRoute(state: HomeState, management: HostManagementState, model: HomeViewModel,
    viewport: WorkspaceViewport, onCancel: () -> Unit) {
    var navigationExpanded by rememberSaveable { mutableStateOf(true) }
    var appearanceSettings by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var usageOpen by remember { mutableStateOf(false) }
    var hostsOpen by remember { mutableStateOf(false) }
    var terminalOpen by remember(state.selectedRuntimeId, state.selectedSessionId) { mutableStateOf(false) }
    var filesOpen by remember(state.selectedRuntimeId, state.selectedSessionId) { mutableStateOf(false) }
    var chatPicker by remember(state.files, state.selectedRuntimeId, state.selectedSessionId) { mutableStateOf<ChatPicker?>(null) }
    var speechOpen by remember(state.selectedRuntimeId, state.selectedSessionId) { mutableStateOf(false) }
    val activeTransport by model.activeTransport.collectAsState()
    val usage by model.usage.collectAsStateWithLifecycle()
    val selectedRuntime = state.runtimeRow(state.selectedRuntimeId)
    val sessionTransport = activeTransport?.takeIf { it.runtimeId == state.selectedRuntimeId }
    val selectedModel = rememberNextChatModel(sessionTransport.takeIf {
        state.reader?.readOnly == false && (state.lanOnly || state.selectedSessionId?.let { session ->
            selectedRuntime?.access?.allows("session/projections", session) == true
        } == true)
    }, state.selectedSessionId, state.modelSelectionRevision)
    val terminalWatcher = state.terminal
    val positions = remember { LinkedHashMap<Pair<String, String>, ChatReadingPosition>() }
    val snackbar = remember { SnackbarHostState() }
    val speechScope = rememberCoroutineScope()
    val speechOverflow = stringResource(R.string.chat_audit_text_size_rejected)
    val actionError = state.sessionActionError?.let { stringResource(R.string.session_action_error, it) }
    val homeError = state.error?.let { errorText(it) }
    LaunchedEffect(actionError) { actionError?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(homeError) { homeError?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(state.outboxError) { state.outboxError?.let { snackbar.showSnackbar(it) } }
    val layout = workspaceLayout(viewport, state.selectedSessionId != null, state.modelSelectionOpen, navigationExpanded)
    val overlay = appearanceSettings || settingsOpen || usageOpen || state.appUpdateSettings || state.binding != null || state.creation != null || terminalOpen || filesOpen || speechOpen || state.pairingOpen || management.open || state.notifySettingsOpen || state.notifyCenter.open
    val rejectReason = stringResource(R.string.chat_audit_rejected_phone)
    BackHandler { model.back() }
    Box(Modifier.fillMaxSize()) {
        WorkspaceScaffold(
            layout = layout,
            chromeVisible = !overlay,
            railContent = { openNavigation -> WorkspaceRail(openNavigation, { hostsOpen = true }, { settingsOpen = true },
                unread = state.sessions.count { it.state != "archived" && model.isUnread(it) }) },
            navigationContent = { closeNavigation ->
                TokenWorksAppShell(state, model, onHosts = { closeNavigation(); hostsOpen = true },
                    onSettings = { closeNavigation(); settingsOpen = true },
                    onCloseNavigation = { if (layout.leftPane == WorkspaceLeftPane.SIDEBAR) navigationExpanded = false else closeNavigation() },
                    onSessionOpened = closeNavigation)
            },
        ) { toggleNavigation ->
            val openNavigation: () -> Unit = {
                if (layout.leftPane == WorkspaceLeftPane.SIDEBAR) navigationExpanded = false else toggleNavigation()
            }
            when {
                management.open -> Box(Modifier.fillMaxSize().tokenWorksEntrance("host-management")) {
                    HostManagementScreen(management, model::closeManagement, model::refreshManagement,
                        model::loadManagementDevices, model::loadManagementInstallations, model::loadManagementProfiles,
                        model::loadManagementGrants, model::requestManagementRevoke, model::cancelManagementRevoke,
                        model::confirmManagementRevoke, model::probeHostPath, model::selectHostPath, model::setRtcConsent)
                }
                state.pairingOpen -> {
                    BackHandler { model.closePairing() }
                    val pairing = state.pairing
                    Box(Modifier.fillMaxSize().tokenWorksEntrance("lan-pairing")) {
                        if (pairing == null) CircularProgressIndicator()
                        else PairingScreen(pairing, model::importPairingDescriptor, model::pairLan, model::retryPairingSave,
                            model::revokeLan, model::revokePendingPairing, model::forgetLan, model::closePairing,
                            model::rotateDeviceKey, model::reconcileDeviceRotation)
                    }
                }
                state.notifyCenter.open -> {
                    BackHandler { model.closeNotificationCenter() }
                    Box(Modifier.fillMaxSize().tokenWorksEntrance("notification-center")) {
                        NotificationCenterScreen(state.notifyCenter, onClose = model::closeNotificationCenter,
                            onOpen = { id -> settingsOpen = false; model.openNotificationCenterEntry(id) })
                    }
                }
                state.notifySettingsOpen -> {
                    BackHandler { model.closeNotifySettings() }
                    Box(Modifier.fillMaxSize().tokenWorksEntrance("notification-settings")) {
                        NotifySettingsScreen(state.notify, model::setNotificationsEnabled, model::setBackgroundConnectionEnabled, model::closeNotifySettings)
                    }
                }
                appearanceSettings && state.presentationOwner != null -> {
                    BackHandler { appearanceSettings = false }
                    Box(Modifier.fillMaxSize().tokenWorksEntrance("appearance-settings")) {
                        AppearanceSettingsScreen(state.presentationOwner) { appearanceSettings = false }
                    }
                }
                terminalOpen -> {
                    BackHandler { terminalOpen = false }
                    terminalWatcher?.let { TerminalScreen(it) }
                }
                filesOpen && state.files != null && state.selectedSessionId != null -> {
                    key(state.files, state.selectedSessionId) {
                        val images = remember(state.reader?.items, state.reader?.readOnly) {
                            sentImageGallery(state.reader?.takeUnless { it.readOnly }?.items.orEmpty())
                        }
                        FilesScreen(state.files, state.selectedSessionId, sentImages = images,
                            pickAttachmentOnOpen = chatPicker == ChatPicker.FILE,
                            onAttachmentPickerRequestConsumed = { chatPicker = null },
                            pickImageOnOpen = chatPicker == ChatPicker.IMAGE, onImagePickerRequestConsumed = { chatPicker = null }) {
                            chatPicker = null; filesOpen = false
                        }
                    }
                }
                state.appUpdateSettings -> {
                    BackHandler { model.closeAppUpdates() }
                    val update = state.appUpdates ?: AppUpdateUiState()
                    Box(Modifier.fillMaxSize().tokenWorksEntrance("app-update-settings")) {
                        AppUpdatesScreen(update, { model.checkAppUpdates(update.viewId) },
                            { model.downloadAppUpdate(update.viewId, update.release?.document?.sha256()) },
                            { model.installAppUpdate(update.viewId, update.attemptId, update.release?.document?.sha256()) },
                            { model.requestAppInstallPermission(update.viewId) }, { token -> model.cleanupAppUpdate(update.viewId, token) },
                            { model.cancelAppUpdateDownload(update.viewId) }, model::closeAppUpdates)
                    }
                }
                state.binding != null -> {
                    BackHandler { model.closeBinding() }
                    HostBindingScreen(state.binding, state.subject, model::previewBinding, model::queryBinding, model::confirmBinding,
                        model::refreshBinding, model::enterAnotherBinding, model::closeBinding)
                }
                state.creation != null -> SessionCreationScreen(state, model)
                usageOpen -> {
                    BackHandler { usageOpen = false }
                    Box(Modifier.fillMaxSize().tokenWorksEntrance("usage-settings")) {
                        UsageScreen(usage) { usageOpen = false }
                    }
                }
                settingsOpen -> {
                    BackHandler { settingsOpen = false }
                    WorkspaceSettings(state, onAppearance = { appearanceSettings = true }, onAppUpdates = model::openAppUpdates,
                        onExit = onCancel, onBack = { settingsOpen = false }, onManagement = model::openManagement,
                        onNotifications = model::openNotifySettings, onPairing = model::openPairing, onUsage = { usageOpen = true },
                        onNotificationCenter = model::openNotificationCenter)
                }
                state.selectedSessionId != null -> key(state.presentationOwner?.fingerprint, state.selectedRuntimeId, state.selectedSessionId) {
                    val resource = state.selectedRuntimeId.orEmpty() to state.selectedSessionId
                    val position = remember(resource) {
                        positions[resource] ?: ChatReadingPosition().also {
                            if (positions.size >= 16) positions.remove(positions.keys.first())
                            positions[resource] = it
                        }
                    }
                    val sessionId = state.selectedSessionId
                    val files = state.files
                    val filePreviewAllowed = state.reader?.readOnly == false && files != null
                    NativeSessionContent(state, position, SessionContentActions(model, { terminalOpen = true },
                        { filesOpen = true }, { speechOpen = true },
                        pickAttachment = { chatPicker = ChatPicker.FILE; filesOpen = true },
                        pickImage = { chatPicker = ChatPicker.IMAGE; filesOpen = true },
                        previewGallery = if (filePreviewAllowed) ({ images, at ->
                            if (files?.openImageGallery(sessionId, images, at) == true) filesOpen = true
                        }) else null,
                        previewFile = if (filePreviewAllowed) ({ link ->
                            if (files?.openLinkedFile(sessionId, link) == true) filesOpen = true
                        }) else null), openNavigation, { hostsOpen = true }, transport = sessionTransport,
                        shareApi = model.shareApi.takeUnless { state.lanOnly }, selectedModel = selectedModel, usage = usage,
                        interactionContent = {
                            if (!overlay && (state.interactions.isNotEmpty() || state.interactionError != null ||
                                    state.interactionDecisions.any { it.sessionId == sessionId })) {
                                InteractionCards(state.interactions, model::approveInteraction,
                                    { model.rejectInteraction(it, rejectReason) }, model::submitAnswers,
                                    modifier = Modifier.widthIn(max = TokenWorksSpacing.readingWidth).fillMaxWidth()
                                        .heightIn(max = (viewport.heightDp / 3).dp).verticalScroll(rememberScrollState())
                                        .padding(horizontal = TokenWorksSpacing.md),
                                    error = state.interactionError, decisions = state.interactionDecisions, sessionId = sessionId)
                            }
                        })
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
            SessionModelsScreen(state, model, currentKey = selectedModel?.let { "${it.provider}/${it.model}" })
        }
    }
    LaunchedEffect(state.reader?.readOnly) { if (state.reader?.readOnly != false) speechOpen = false }
    if (speechOpen && state.reader?.readOnly == false) key(state.presentationOwner?.fingerprint, state.selectedRuntimeId, state.selectedSessionId) {
        SpeechInputSheet(onInsert = { text ->
            state.composer?.let { draft ->
                val combined = draft.value.insertReference(text)
                if (combined.text.length <= MAX_DRAFT_CHARACTERS) model.editDraft(combined)
                else speechScope.launch { snackbar.showSnackbar(speechOverflow) }
            }
            speechOpen = false
        }, onDismiss = { speechOpen = false })
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
    val openTerminal: () -> Unit = {},
    val openFiles: () -> Unit = {},
    val retryPrompt: (String) -> Unit = {},
    val abandonPrompt: (String) -> Unit = {},
    val openSpeech: () -> Unit = {},
    val chooseSendMode: (String) -> Unit = {},
    val steerUnsent: (String) -> Unit = {},
    val previewImage: ((NativeImageReference) -> Unit)? = null,
    val previewGallery: ((List<NativeImageReference>, Int) -> Unit)? = null,
    val previewFile: ((FilesPreviewLink) -> Unit)? = null,
    val editPendingPrompt: ((String, String) -> Unit)? = null,
    val pickAttachment: (() -> Unit)? = null,
    val pickImage: (() -> Unit)? = null,
) {
    constructor(model: HomeViewModel, openTerminal: () -> Unit, openFiles: () -> Unit, openSpeech: () -> Unit,
        previewGallery: ((List<NativeImageReference>, Int) -> Unit)?, previewFile: ((FilesPreviewLink) -> Unit)?,
        pickAttachment: (() -> Unit)? = null, pickImage: (() -> Unit)? = null) :
        this(editDraft = model::editDraft, send = model::sendDraft, cancel = model::cancelSession,
            openModels = model::openModelSelection, openCreation = model::openCreation, loadOlder = model::loadOlder,
            fork = model::forkSession, archive = model::archiveSession, rename = model::renameSession,
            openTerminal = openTerminal, openFiles = openFiles, retryPrompt = model::retryPrompt, abandonPrompt = model::abandonPrompt,
            openSpeech = openSpeech, chooseSendMode = model::chooseDraftDelivery, steerUnsent = model::steerUnsentPrompt,
            previewGallery = previewGallery, previewFile = previewFile, editPendingPrompt = model::editPendingPrompt,
            pickAttachment = pickAttachment, pickImage = pickImage)

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeSessionContent(state: HomeState, position: ChatReadingPosition, actions: SessionContentActions,
    onNavigation: () -> Unit, onHosts: () -> Unit, transport: DshTransport? = null, shareApi: RemoteApi? = null,
    selectedModel: UsageModel? = null, usage: UsageState? = null, interactionContent: @Composable () -> Unit = {}) {
    val sessionId = requireNotNull(state.selectedSessionId)
    val entry = state.sessions.firstOrNull { it.id == sessionId }
    val runtime = state.runtimeRow(state.selectedRuntimeId)
    val read = state.reader ?: SessionReadState(SessionReadPhase.CONNECTING)
    val child = entry?.parentSessionId?.let { DshSubagentAddress(it, entry.subagentMode) }
    val discovery = remember(transport) { transport?.let(::DiscoveryClient) }
    val canSkills = state.lanOnly || runtime?.access?.allows("skills/list", sessionId) == true
    val canFiles = state.lanOnly || runtime?.access?.allows("fileReferences/list", sessionId) == true
    val canInsert = discovery != null && state.composer != null && !read.readOnly && (canSkills || canFiles)
    val canReadShare = state.lanOnly || runtime?.access?.allows("session/page", sessionId) == true
    val shareAuthorityLost = !canReadShare || read.phase in setOf(SessionReadPhase.AUTH_REQUIRED, SessionReadPhase.REVOKED)
    val canShare = transport != null && !read.readOnly && canReadShare
    val canReadHostQueue = transport != null && child == null && !read.readOnly &&
        (state.lanOnly || runtime?.access?.allows("session/projections", sessionId) == true)
    var referencesOpen by remember { mutableStateOf(false) }
    var shareOpen by rememberSaveable { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var selectedMessageId by remember { mutableStateOf<String?>(null) }
    var outboxOpen by remember { mutableStateOf(false) }
    var replay by remember { mutableStateOf<ConversationCapture?>(null) }
    var exportConfirmation by remember { mutableStateOf(false) }
    var awaitingDestination by remember { mutableStateOf<ConversationCapture?>(null) }
    var writingPdf by remember { mutableStateOf(false) }
    var exportNotice by remember { mutableStateOf<Int?>(null) }
    var draftNotice by remember { mutableStateOf(false) }
    var quoteRevision by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authorityLost = read.phase == SessionReadPhase.AUTH_REQUIRED || read.phase == SessionReadPhase.REVOKED
    val latestAuthorityLost by rememberUpdatedState(authorityLost)
    val sessionTitle = entry?.metadata?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.session_untitled)
    val capture = remember(sessionTitle, read.window, authorityLost) { if (authorityLost) null else captureConversation(sessionTitle, read) }
    val filename = stringResource(R.string.chat_audit_export_filename)
    var pdfJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val snapshot = awaitingDestination
        awaitingDestination = null
        if (uri != null && snapshot != null && !latestAuthorityLost) {
            writingPdf = true
            pdfJob = scope.launch {
                try {
                    withContext(Dispatchers.IO) { writeConversationPdf(context.applicationContext, uri, snapshot) }
                    exportNotice = R.string.chat_audit_export_success
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { exportNotice = R.string.chat_audit_export_failed }
                finally { writingPdf = false }
            }
        }
    }
    LaunchedEffect(authorityLost) {
        if (authorityLost) {
            replay = null; exportConfirmation = false; awaitingDestination = null; pdfJob?.cancel(); outboxOpen = false
        }
    }
    val modelsEnabled = (state.lanOnly || runtime?.access?.canReadModelCatalog() == true) && !state.modelBusy && !read.readOnly
    val modelLabel = selectedModel?.let { state.modelCatalog?.find("${it.provider}/${it.model}")?.name ?: it.model }
    val labels = if (authorityLost) ChatUsageLabels(null, null) else chatUsageLabels(entry, usage)
    val headerPhase = stableHeaderPhase(sessionId, read.phase)
    val connectionLabel = stringResource(when (headerPhase) {
        SessionReadPhase.CONNECTING -> R.string.conversation_connecting
        SessionReadPhase.SYNCING -> R.string.conversation_syncing
        SessionReadPhase.READY -> R.string.conversation_live
        SessionReadPhase.BACKOFF -> R.string.conversation_reconnecting
        SessionReadPhase.AUTH_REQUIRED -> R.string.conversation_auth_required
        SessionReadPhase.REVOKED -> R.string.conversation_revoked
        SessionReadPhase.ERROR -> R.string.conversation_read_error
        SessionReadPhase.CLOSED -> R.string.conversation_paused
    })
    val editable = state.composer != null && !read.readOnly
    val updateDraft: (TextFieldValue?) -> Unit = { value ->
        if (editable) {
            draftNotice = value == null || value.text.length > MAX_DRAFT_CHARACTERS
            if (!draftNotice && value != null) actions.editDraft(value)
        }
    }
    val quote: ((String) -> Unit)? = if (editable) ({ text ->
        val value = state.composer?.value?.appendChatQuote(text)
        updateDraft(value)
        if (value != null) quoteRevision++
    }) else null
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        SessionHeader(sessionTitle,
            connectionLabel, when (headerPhase) {
                SessionReadPhase.READY -> HeaderConnection.LIVE
                SessionReadPhase.CONNECTING, SessionReadPhase.SYNCING -> HeaderConnection.PENDING
                SessionReadPhase.BACKOFF, SessionReadPhase.CLOSED -> HeaderConnection.DEGRADED
                else -> HeaderConnection.ERROR
            }, modelsEnabled = modelsEnabled,
            createEnabled = (state.lanOnly || runtime?.access?.canCreate() == true) && !read.readOnly,
            onNavigation = onNavigation, onHosts = onHosts, onModels = actions.openModels,
            onCreate = actions.openCreation, onMore = { moreOpen = true }, workspaceName = entry?.metadata?.workspaceTitle,
            usageLabel = labels.tokens, costLabel = labels.cost, pendingCount = state.pendingPrompts.size,
            modelName = modelLabel,
            approvalCount = state.interactions.count { it is PendingInteraction.Approval },
            questionCount = state.interactions.count { it is PendingInteraction.Question })
        if (read.stale) TokenWorksStatusNotice(
            stringResource(R.string.chat_audit_cached_notice),
            modifier = Modifier.fillMaxWidth().padding(horizontal = TokenWorksSpacing.md, vertical = TokenWorksSpacing.xs),
            tone = TokenWorksNoticeTone.WARNING,
        )
        NativeChatTimeline(read, position, actions.loadOlder, quote, { selectedMessageId = it },
            Modifier.widthIn(max = TokenWorksSpacing.readingWidth).fillMaxWidth().weight(1f),
            onPreviewMedia = actions.previewImage.takeIf { !read.readOnly },
            onSuggestion = if (editable) ({ prompt -> updateDraft(state.composer?.value?.insertReference(prompt)) }) else null,
            onPreviewGallery = actions.previewGallery.takeUnless { read.readOnly },
            onPreviewFile = actions.previewFile.takeUnless { read.readOnly })
        val status = when {
            writingPdf -> R.string.chat_audit_export_progress
            exportNotice != null -> exportNotice
            draftNotice -> R.string.chat_audit_text_size_rejected
            else -> null
        }
        if (status != null) Text(stringResource(status),
            Modifier.padding(horizontal = TokenWorksSpacing.md).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        interactionContent()
        ChatComposer(state, { updateDraft(it) }, actions.send, actions.cancel, actions.openModels,
            Modifier.widthIn(max = TokenWorksSpacing.readingWidth).fillMaxWidth(),
            onSetMode = actions.chooseSendMode,
            onAttach = (actions.pickAttachment ?: actions.openFiles).takeIf { state.files != null },
            onImage = actions.pickImage.takeIf { state.files != null },
            onVoice = actions.openSpeech.takeIf { state.composer != null },
            onShortcuts = if (canInsert) ({ referencesOpen = true }) else null,
            onQueue = if (!authorityLost && (canReadHostQueue || state.composer != null || state.pendingPrompts.isNotEmpty())) ({ outboxOpen = true }) else null,
            selectedModel = modelLabel, modelsEnabled = modelsEnabled,
            quoteRevision = quoteRevision,
            completion = { value ->
                if (canInsert && discovery != null) ChatReferenceCompletions(discovery, sessionId, value, canSkills, canFiles) { token, reference ->
                    val latest = state.composer?.value
                    if (latest != null && activeChatReference(latest) == token) updateDraft(latest.acceptChatReference(token, reference))
                }
            })
    }
    if (moreOpen) ModalBottomSheet(
        onDismissRequest = { moreOpen = false },
        sheetMaxWidth = TokenWorksSpacing.readingWidth,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = TokenWorksSpacing.lg, vertical = TokenWorksSpacing.sm)) {
            Text(stringResource(R.string.session_actions_title), Modifier.padding(TokenWorksSpacing.md),
                style = MaterialTheme.typography.titleLarge)
            RuntimePickerAction(stringResource(R.string.chat_audit_terminal), { moreOpen = false; actions.openTerminal() },
                enabled = state.terminal != null && !read.readOnly)
            RuntimePickerAction(stringResource(R.string.chat_audit_files), { moreOpen = false; actions.openFiles() },
                enabled = state.files != null && !read.readOnly)
            RuntimePickerAction(stringResource(R.string.chat_audit_voice), { moreOpen = false; actions.openSpeech() }, enabled = editable)
            RuntimePickerAction(stringResource(R.string.chat_audit_replay), { moreOpen = false; replay = capture }, enabled = capture != null)
            RuntimePickerAction(stringResource(R.string.chat_audit_export_pdf), {
                moreOpen = false; exportNotice = null; exportConfirmation = true
            }, enabled = capture != null && !writingPdf && awaitingDestination == null)
            RuntimePickerAction(stringResource(R.string.session_share_action), { moreOpen = false; shareOpen = true }, enabled = canShare)
            RuntimePickerAction(stringResource(R.string.session_rename), {
                moreOpen = false; title = entry?.metadata?.title.orEmpty(); renameOpen = true
            }, enabled = child == null && (state.lanOnly || runtime?.access?.canRename(sessionId) == true) && !state.submitting && !read.readOnly)
            RuntimePickerAction(stringResource(R.string.session_fork), { moreOpen = false; actions.fork() },
                enabled = child == null && (state.lanOnly || runtime?.access?.canFork(sessionId) == true) && !state.submitting && !read.readOnly)
            RuntimePickerAction(stringResource(R.string.conversation_archive), { moreOpen = false; actions.archive() },
                enabled = child == null && (state.lanOnly || runtime?.access?.allows("workspace/archiveSession", sessionId, entry?.workspaceId) == true) && !state.submitting && !read.readOnly)
            Spacer(Modifier.heightIn(min = TokenWorksSpacing.sm))
        }
    }
    if (referencesOpen && canInsert && discovery != null) {
        PromptReferencesSheet(discovery, sessionId, canSkills, canFiles,
            onPick = { reference -> updateDraft(state.composer?.value?.insertReference(reference)) },
            onDismiss = { referencesOpen = false })
    }
    if (outboxOpen && !authorityLost) ChatOutboxSheet(state.pendingPrompts, state.submitting, editable, actions,
        hostQueue = if (canReadHostQueue && transport != null) ({
            HostQueueSection(transport, sessionId, editable &&
                (state.lanOnly || runtime?.access?.allows("session/updateQueue", sessionId) == true))
        }) else null) { outboxOpen = false }
    if (!authorityLost) replay?.let { ConversationReplay(it) { replay = null } }
    if (exportConfirmation && capture != null && !authorityLost) AlertDialog(
        onDismissRequest = { exportConfirmation = false },
        title = { Text(stringResource(R.string.chat_audit_export_pdf)) },
        text = if (capture.partial) ({ Text(stringResource(R.string.chat_audit_export_partial)) }) else null,
        confirmButton = { TextButton({
            exportConfirmation = false
            awaitingDestination = capture
            try { saveDocument.launch(filename) }
            catch (_: Exception) { awaitingDestination = null; exportNotice = R.string.chat_audit_export_failed }
        }) { Text(stringResource(R.string.chat_audit_save)) } },
        dismissButton = { TextButton({ exportConfirmation = false }) { Text(stringResource(R.string.action_cancel)) } })
    if (shareOpen && !shareAuthorityLost) {
        SessionShareDialog(sessionId, entry?.metadata?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.session_untitled),
            transport.takeIf { canShare }, shareApi, child) { shareOpen = false }
    }
    LaunchedEffect(shareAuthorityLost) { if (shareAuthorityLost) shareOpen = false }
    if (renameOpen) AlertDialog(onDismissRequest = { renameOpen = false }, title = { Text(stringResource(R.string.session_rename)) },
        text = { OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.session_title_label)) }, singleLine = true) },
        confirmButton = { TextButton({ renameOpen = false; actions.rename(title.trim()) }, enabled = title.isNotBlank() && !state.submitting && !read.readOnly) {
            Text(stringResource(R.string.action_confirm))
        } }, dismissButton = { TextButton({ renameOpen = false }) { Text(stringResource(R.string.action_cancel)) } })
    val message = read.items.firstOrNull { it.id == selectedMessageId }
    if (message != null) {
        val text = remember(message) { message.blocks.joinToString("\n\n") { it.text } }
        AlertDialog(onDismissRequest = { selectedMessageId = null }, title = { Text(stringResource(R.string.chat_audit_select_text)) },
            text = { SelectionContainer { Text(text, Modifier.heightIn(max = TokenWorksMetrics.panelIdealMin).verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton({ selectedMessageId = null; quote?.invoke(text) }, enabled = quote != null && text.isNotBlank()) {
                Text(stringResource(R.string.conversation_message_quote))
            } }, dismissButton = { TextButton({ selectedMessageId = null }) { Text(stringResource(R.string.action_close)) } })
    }
}

@Composable
internal fun NativeChatTimeline(read: SessionReadState, position: ChatReadingPosition, onLoadOlder: () -> Unit,
    onQuote: ((String) -> Unit)?, onMessageAction: (String) -> Unit, modifier: Modifier = Modifier,
    onPreviewMedia: ((NativeImageReference) -> Unit)? = null,
    onSuggestion: ((String) -> Unit)? = null,
    onPreviewGallery: ((List<NativeImageReference>, Int) -> Unit)? = null,
    onPreviewFile: ((FilesPreviewLink) -> Unit)? = null) {
    val projection = remember(read.items, read.running) { projectChatTimeline(read.items, read.running) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var following by remember(position) { mutableStateOf(position.following) }
    var jumping by remember(position) { mutableStateOf(false) }
    var previousAnchorKey by remember(position) { mutableStateOf<String?>(null) }
    val newest = remember(read.items) { read.items.maxOfOrNull { it.seq } ?: -1L }
    val latestNewest by rememberUpdatedState(newest)
    val unseen = remember(read.items, following) { if (following) 0 else read.items.count { it.seq > position.seenThrough &&
        (it.role == ConversationRole.ASSISTANT || it.role == ConversationRole.USER) } }
    val messageUsage = remember(read.window) { transcriptMessageUsage(read.window) }
    val scrollListener = remember(position, listState) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && consumed.y != 0f && !jumping) {
                    position.following = !listState.canScrollForward
                    following = position.following
                    if (following) position.seenThrough = latestNewest
                }
                return Offset.Zero
            }
        }
    }
    val keys = remember(projection, position.expandedSourceIds) { listOf("history") + projection.renderKeys(position.expandedSourceIds) + "read-status" }
    val latestProjection by rememberUpdatedState(projection)
    var restored by remember(position) { mutableStateOf(false) }
    LaunchedEffect(keys, read.items.lastOrNull(), read.window != null) {
        if (read.window == null) return@LaunchedEffect
        if (position.seenThrough < 0 || following) position.seenThrough = newest
        position.retainExpandedSources(read.items)
        val source = position.key?.let { projection.renderKeyForSource(it, position.expandedSourceIds) }
        val recoveringAnchor = source != null && previousAnchorKey?.let { it != source && it !in keys } == true
        if (!listState.isScrollInProgress && !jumping) {
            if (position.following || (source != null && (!restored || recoveringAnchor))) {
                jumping = true
                try {
                    if (position.following) listState.scrollToItem(keys.lastIndex)
                    else source?.let { listState.scrollToItem(keys.indexOf(it).coerceAtLeast(0), position.offset) }
                } finally { jumping = false }
            }
        }
        restored = true
    }
    LaunchedEffect(listState, position) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val first = layout.visibleItemsInfo.firstOrNull { latestProjection.sourceIdForRenderKey(it.key.toString()) != null }
            Triple(first, listState.canScrollForward, listState.isScrollInProgress)
        }.collect { (first, canScrollForward, scrolling) ->
            if (!restored || jumping) return@collect
            if (scrolling && canScrollForward) {
                position.following = false
                following = false
            }
            first?.let {
                previousAnchorKey = it.key.toString()
                position.key = latestProjection.sourceIdForRenderKey(it.key.toString())
                position.offset = (-it.offset).coerceAtLeast(0)
            }
            if (!canScrollForward && !following && listState.layoutInfo.totalItemsCount > 0) {
                position.following = true
                position.seenThrough = latestNewest
                following = true
            }
        }
    }
    val scope = rememberCoroutineScope()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
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
        },
        trailingContent = {
            item("read-status") {
                val failure = read.error
                when {
                    failure != null -> TokenWorksStatusNotice(errorText(failure), isError = true)
                    read.phase == SessionReadPhase.CONNECTING || read.phase == SessionReadPhase.SYNCING -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    read.items.isEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TokenWorksEmptyState(
                            title = stringResource(R.string.conversation_empty),
                            modifier = Modifier.tokenWorksEntrance("chat-empty"),
                        )
                        if (!read.readOnly && onSuggestion != null) SessionSuggestions(
                            onPick = onSuggestion,
                            modifier = Modifier.widthIn(max = TokenWorksSpacing.readingWidth),
                        )
                    }
                }
            }
        }, onProcessExpansion = { ids, expanded ->
            position.expandedSourceIds = if (expanded) position.expandedSourceIds + ids else position.expandedSourceIds - ids.toSet()
        }, onQuote = onQuote.takeUnless { read.readOnly }, onMessageAction = null,
        onSelectText = onMessageAction,
        onPreviewMedia = onPreviewMedia.takeUnless { read.readOnly },
        modifier = Modifier.fillMaxWidth().weight(1f).nestedScroll(scrollListener),
        onPreviewGallery = onPreviewGallery.takeUnless { read.readOnly },
        onPreviewFile = onPreviewFile.takeUnless { read.readOnly }, messageUsage = messageUsage)
    androidx.compose.animation.AnimatedVisibility(
        visible = restored && !following && listState.canScrollForward,
        enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut(),
    ) {
        FilledTonalButton(
            onClick = {
                if (!jumping) {
                    jumping = true
                    scope.launch {
                        try { listState.scrollToItem(keys.lastIndex) }
                        finally {
                            jumping = false
                            position.following = !listState.canScrollForward
                            following = position.following
                            if (following) position.seenThrough = latestNewest
                        }
                    }
                }
            },
            modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
            Icon(painterResource(R.drawable.ic_chat_latest), null, Modifier.size(TokenWorksSpacing.iconSize))
            Spacer(Modifier.width(TokenWorksSpacing.xs))
            Text(stringResource(R.string.conversation_jump_latest), style = MaterialTheme.typography.labelMedium)
            if (unseen > 0) {
                Spacer(Modifier.width(TokenWorksSpacing.sm))
                Text(pluralStringResource(R.plurals.chat_audit_new_messages, unseen, unseen),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(state: HomeState, onLogin: () -> Unit, onRegister: (String) -> Unit, onSecuritySettings: () -> Unit,
    onCancel: () -> Unit, onLanOnly: () -> Unit = {}) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    val activeStep = if (enrolling) 1 else 0
                    for (step in 0..2) {
                        val active = step == activeStep
                        Surface(
                            color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            shape = tokenWorksSquircle(TokenWorksRadius.xl),
                        ) {
                            Text(stringResource(when (step) { 0 -> R.string.workspace_step_account; 1 -> R.string.workspace_step_device; else -> R.string.workspace_step_computer }),
                                Modifier.padding(horizontal = TokenWorksSpacing.md, vertical = TokenWorksSpacing.sm)
                                    .semantics { if (active) stateDescription = currentStep },
                                style = if (active) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall)
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
                        else -> Column(Modifier.fillMaxWidth().tokenWorksEntrance("onboarding-progress").semantics { liveRegion = LiveRegionMode.Polite },
                            verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    }
                    if (state.canUseLanOnly && state.phase in setOf(HomePhase.RECOVERY_ERROR, HomePhase.REAUTHENTICATE)) {
                        OutlinedButton(onLanOnly, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.chat_home_lan_entry)) }
                        Text(stringResource(R.string.chat_home_lan_entry_detail), style = MaterialTheme.typography.bodySmall)
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
                        TokenWorksStatusNotice(errorText(code),
                            modifier = Modifier.tokenWorksEntrance("onboarding-error-$code"),
                            tone = TokenWorksNoticeTone.ERROR)
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
internal fun errorText(code: String): String = when (code) {
    "lan.pending_authorization" -> stringResource(R.string.chat_home_lan_pending_authorization)
    "lan.pending_removed_with_account" -> stringResource(R.string.chat_home_lan_removed_with_account)
    "transport/changed", "transport.route_changed" -> stringResource(R.string.chat_home_transport_changed)
    else -> stringResource(when (code) {
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
}

/** Empty-session starters: tapping one fills the draft, it never sends on its own. */
@Composable
internal fun SessionSuggestions(onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val suggestions = listOf(
        stringResource(R.string.chat_suggest_review),
        stringResource(R.string.chat_suggest_tests),
        stringResource(R.string.chat_suggest_explain),
    )
    Column(modifier.fillMaxWidth().padding(horizontal = TokenWorksSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
        suggestions.forEach { prompt ->
            Surface(
                onClick = { onPick(prompt) },
                modifier = Modifier.fillMaxWidth(),
                shape = tokenWorksSquircle(TokenWorksRadius.md),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Text(prompt, Modifier.padding(horizontal = TokenWorksSpacing.md, vertical = TokenWorksSpacing.md),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
