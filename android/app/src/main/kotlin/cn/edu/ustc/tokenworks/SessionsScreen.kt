package cn.edu.ustc.tokenworks

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import io.github.enthusjast.tokenworks.network.*
import io.github.enthusjast.tokenworks.session.*
import io.github.enthusjast.tokenworks.command.*
import io.github.enthusjast.tokenworks.protocol.*
import io.github.enthusjast.tokenworks.ui.TokenWorksSpacing
import io.github.enthusjast.tokenworks.ui.tokenWorksSurfaces
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

private fun questionAnchor(handle: QuestionHandle) = "question:${handle.kind}:${handle.questionId}:${handle.epoch}:${handle.subscriptionId}:${handle.syncId}:${handle.definition}"
private fun approvalAnchor(approval: PresentedApproval) = "approval:${approval.status.id}:${approval.handle?.reviewId.orEmpty()}"

private data class ChatScrollObservation(val scrolling: Boolean, val lastKey: Any?, val atHistory: Boolean,
    val firstIndex: Int, val offset: Int)

private fun captureChatPosition(position: ChatReadingPosition, projection: ChatTimelineProjection,
    expanded: Set<String>, listState: LazyListState) {
    fun source(key: String): String? = projection.sourceIdForRenderKey(key) ?: key.takeIf { it.startsWith("process:") }
        ?.removePrefix("process:")?.takeIf { projection.renderKeyForSource(it, expanded) != null }
    val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { source(it.key as? String ?: "") != null } ?: return
    val renderKey = visible.key as String
    val savedSource = position.key?.takeIf { projection.renderKeyForSource(it, expanded) == renderKey }
    position.key = savedSource ?: source(renderKey)
    position.offset = (-visible.offset).coerceAtLeast(0)
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SessionsScreen(state: HomeState, onBack: () -> Unit, onRefresh: () -> Unit, onOlder: () -> Unit, onRetry: () -> Unit,
    onSubmit: (MessageSubmission) -> Unit = {}, onSendPending: (String) -> Unit = {}, onQueryCommands: () -> Unit = {}, onCancelSession: (String) -> Unit = {},
    onAnswer: (QuestionHandle,QuestionAnswers)->Unit={_,_->},onClaim: (QuestionHandle)->Unit={},onRelease: (QuestionHandle)->Unit={},onRefreshQuestions: ()->Unit={},
    onApproval: (String) -> Unit = {}, onApprovalQuery: () -> Unit = {}, onArchive: (String, Boolean, Boolean) -> Unit = { _, _, _ -> },
    onPickFile: (String?) -> Unit = {}, onPickedFile: (String, Uri?) -> Unit = { _, _ -> }, onToggleFile: (String) -> Unit = {},
    onQueryFile: (String) -> Unit = {}, onRemoveFile: (String) -> Unit = {}, onCancelFile: () -> Unit = {},
    onBrowseExports: (ExportSource?, Boolean) -> Unit = { _, _ -> }, onExport: (ExportSource) -> Unit = {}, onQueryExports: () -> Unit = {},
    onCancelExport: (String) -> Unit = {}, onRemoveExport: (String) -> Unit = {}, onSaveExport: (String) -> Unit = {}, onExportDestination: (String, Uri?) -> Unit = { _, _ -> }, onCancelExportSave: () -> Unit = {},
    readingPosition: ChatReadingPosition? = null, onComposerChange: (MessageComposerState) -> Unit = {}, onModels: (() -> Unit)? = null,
    onChanges: (() -> Unit)? = null, onMessageAction: ((String) -> Unit)? = null,
    onPickImage: () -> Unit = {}, onPickCamera: () -> Unit = {}, onPreviewMedia: ((VisibleMediaReference) -> Unit)? = null,
    onDismissImagePreview: () -> Unit = {}, onCancelImage: () -> Unit = {}, onReselectImage: (String) -> Unit = {},
    onResolveFailedInput: (MessageComposerState, Boolean) -> Unit = { _, _ -> },
    layout: WorkspaceLayoutSpec, detailTarget: ChatDetailTarget?, onDetailsChange: (ChatDetailTarget?) -> Unit,
    onOpenNavigation: () -> Unit, onOpenHosts: () -> Unit, onCreateSession: () -> Unit) {
    val runtime = state.runtimeRow(state.selectedRuntimeId)
    val opened = state.selectedSessionId ?: return
    val archived = state.sessions.firstOrNull { it.id == opened }?.state == "archived"
    val canCompose = !archived && state.outbox != null && runtime?.access?.role in setOf("operator", "owner") && runtime?.access?.scope?.actions?.containsAll(setOf("message.submit", "command.status")) == true
    val details = detailTarget?.takeIf { it.matches(state) }
    val showDetails = details != null
    val detailSection = details?.section ?: ChatDetailSection.INFO
    val detailRequestId = details?.requestId
    var cancelConfirmEpoch by remember(opened) { mutableStateOf<String?>(null) }
    var taskMenu by remember(opened) { mutableStateOf(false) }
    var fullEditor by remember(state.composer?.resource) { mutableStateOf(false) }
    var attachmentMenuOpen by remember(state.composer?.resource) { mutableStateOf(false) }
    var attachmentHelp by remember(state.composer?.resource) { mutableStateOf(false) }
    var dismissEditor by remember(state.composer?.resource) { mutableStateOf<() -> Unit>({ fullEditor = false }) }
    var quoteTooLarge by remember(state.composer?.resource) { mutableStateOf(false) }
    var pendingMenu by remember(opened) { mutableStateOf(false) }
    var requestedAnchor by remember(opened) { mutableStateOf<String?>(null) }
    var targetGone by remember(opened) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val leaveDetails = { onDetailsChange(null) }
    val returnFromChat: () -> Unit = { if (imeVisible) keyboard?.hide() else if (showDetails) leaveDetails() else onBack() }
    BackHandler { returnFromChat() }
    val chatListState = rememberLazyListState()
    val detailsListState = rememberLazyListState()
    val read = state.reader ?: SessionReadState(SessionReadPhase.CONNECTING)
    val transcript = read.window
    val position = remember(readingPosition, state.presentationOwner?.fingerprint, state.selectedRuntimeId, opened) {
        readingPosition ?: ChatReadingPosition()
    }
    val projection = remember(transcript?.items, transcript?.taskRunning) {
        projectChatTimeline(transcript?.items.orEmpty(), transcript?.taskRunning == true)
    }
    val appContext = LocalContext.current.applicationContext
    val marks = remember(state.appearanceScope().ownerKey) {
        state.appearanceScope().ownerKey?.let { SessionReadMarks(appContext, it) }
    }
    val latestPersisted by rememberUpdatedState(transcript?.persistedThrough ?: 0L)
    LaunchedEffect(opened, marks, latestPersisted) {
        if (latestPersisted > 0) marks?.mark(opened, latestPersisted)
    }
    DisposableEffect(opened, marks) { onDispose { if (latestPersisted > 0) marks?.mark(opened, latestPersisted) } }
    var expandedSourceIds by position::expandedSourceIds
    val renderedKeys = remember(projection, expandedSourceIds) { projection.renderKeys(expandedSourceIds) }
    var restoringReading by remember(opened) { mutableStateOf(false) }
    var returningToLatest by remember(opened) { mutableStateOf(false) }
    LaunchedEffect(projection) { position.retainExpandedSources(transcript?.items.orEmpty()) }
    var initialized by remember(opened) { mutableStateOf(false) }
    var followLatest by remember(opened) { mutableStateOf(position.following) }
    var newMessages by remember(opened) { mutableStateOf(false) }
    var lastMessageKey by remember(opened) { mutableStateOf<String?>(null) }
    var lastMessageLength by remember { mutableIntStateOf(-1) }
    var requestedBoundary by remember(opened) { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    val focusedQuestion = read.questions.items.firstOrNull { it.handle == state.notificationQuestion }
    val visibleQuestions = read.questions.items.takeIf { read.phase == SessionReadPhase.READY && !archived }.orEmpty()
    val pendingQuestions = visibleQuestions.filter { it.answerable }
    val approvalView = state.approvals?.takeIf {
        !archived && runtime?.access?.role == "owner" && runtime.access.scope?.actions?.containsAll(setOf("session.read", "approval.decide")) == true
    }
    val pendingApprovals = approvalView?.view?.items.orEmpty().filter { it.canReview || it.handle != null || it.remainingMillis > 0 && it.status.state in setOf(ApprovalRemoteState.PENDING, ApprovalRemoteState.DECISION_RECEIVED) }
    val pendingOriginals = state.outbox?.records.orEmpty().filter { it.remote !in setOf(RemoteCommandState.SETTLED, RemoteCommandState.REJECTED, RemoteCommandState.AGENT_QUEUED) }
    val chatKeys = buildList {
        if (read.phase != SessionReadPhase.READY || read.cacheError != null) add("connection")
        if (state.notificationNote != null) add("notification-note")
        if (focusedQuestion != null && visibleQuestions.any { it.handle == focusedQuestion.handle }) add(questionAnchor(focusedQuestion.handle))
        if (approvalView?.error != null) add("approval-error")
        pendingApprovals.forEach { add(approvalAnchor(it)) }
        visibleQuestions.filter { it.handle != focusedQuestion?.handle }.forEach { add(questionAnchor(it.handle)) }
        if (read.questions.error != null && read.phase == SessionReadPhase.READY && !archived) add("question-error")
        if (transcript != null) {
            if (transcript.hasMore && read.phase == SessionReadPhase.READY) add("older")
            if (transcript.checkpoint != "persisted") add("persistence")
            if (transcript.items.isEmpty() && read.phase == SessionReadPhase.READY) add("empty-session")
        }
    }
    LaunchedEffect(requestedAnchor, chatKeys, showDetails) {
        val anchor = requestedAnchor ?: return@LaunchedEffect
        val index = chatKeys.indexOf(anchor)
        if (showDetails || index < 0) targetGone = true else chatListState.scrollToItem(index)
        requestedAnchor = null
    }
    val cancelEpoch = read.runtimeEpoch
    val canCancel = !archived && read.phase == SessionReadPhase.READY && transcript?.taskRunning == true && cancelEpoch != null &&
        runtime?.access?.let { grant ->
            grant.canRead() && grant.role in setOf("operator", "owner") &&
                grant.scope?.actions?.containsAll(setOf("session.cancel", "command.status")) == true
        } == true && state.outbox?.connected == true && !state.submitting
    val pendingCancel = cancelEpoch?.let { epoch ->
        state.outbox?.records?.firstOrNull {
            it.intent.runtimeId == state.selectedRuntimeId && it.intent.sessionId == opened && it.intent.isCurrentCancel(epoch) &&
                it.remote !in setOf(RemoteCommandState.SETTLED, RemoteCommandState.REJECTED)
        }
    }
    val summary = state.outbox?.let { selectChatDeliveryRecord(it.records, state.selectedRuntimeId.orEmpty(), opened) }
    val footerError = state.commandError ?: state.outbox?.error
    val composerShowsStop = canCompose && !fullEditor && state.composer?.value?.text?.isBlank() == true &&
        state.uploads?.selected.isNullOrEmpty() && canCancel && pendingCancel == null
    val footerCanCancel = canCancel && pendingCancel == null && !composerShowsStop
    val hasOperationFooter = summary != null || footerError != null || footerCanCancel || pendingCancel != null
    val openDetails: (ChatDetailSection, String?) -> Unit = { section, request ->
        if (!showDetails) captureChatPosition(position, projection, expandedSourceIds, chatListState)
        val owner = state.presentationOwner
        if (owner != null && runtime != null) {
            val target = ChatDetailTarget(owner.fingerprint, runtime.hostId, runtime.id, opened, section, request)
            if (target.matches(state)) onDetailsChange(target)
        }
        scope.launch { detailsListState.scrollToItem(0) }
    }
    val onlineForAttachments = read.phase == SessionReadPhase.READY && state.outbox?.connected == true
    val filesAllowed = canCompose && onlineForAttachments && state.outbox?.canAttachFiles == true && state.uploads != null
    val imagesAllowed = canCompose && onlineForAttachments && state.outbox?.canAttachImages == true && state.images != null
    val attachmentBusy = state.uploads?.busy == true
    val imageBusy = attachmentBusy || state.images?.preparing == true
    val fileReason = stringResource(if (attachmentBusy) R.string.composer_attachment_busy else R.string.workspace_attachment_file_unavailable)
    val imageReason = stringResource(if (imageBusy) R.string.composer_attachment_busy else R.string.workspace_attachment_image_unavailable)
    val switchEditor: () -> Unit = { attachmentMenuOpen = false; fullEditor = !fullEditor }
    val attachmentPanel: @Composable () -> Unit = {
        if (attachmentMenuOpen) ModalBottomSheet(
            onDismissRequest = { attachmentMenuOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            sheetMaxWidth = TokenWorksSpacing.readingWidth,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = TokenWorksSpacing.lg)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = TokenWorksSpacing.lg, end = TokenWorksSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.workspace_attachment_panel_title), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge)
                    TextButton({ attachmentMenuOpen = false }, Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                        Text(stringResource(R.string.action_close))
                    }
                }
                AttachmentAction(stringResource(R.string.workspace_attach_file),
                    detail = if (!filesAllowed || attachmentBusy) fileReason else null,
                    icon = R.drawable.ic_attach, enabled = filesAllowed && !attachmentBusy) {
                    attachmentMenuOpen = false; onPickFile(null)
                }
                AttachmentAction(stringResource(R.string.workspace_attach_image),
                    detail = if (!imagesAllowed || imageBusy) imageReason else null,
                    icon = R.drawable.ic_attach, enabled = imagesAllowed && !imageBusy) {
                    attachmentMenuOpen = false; onPickImage()
                }
                AttachmentAction(stringResource(R.string.workspace_attach_camera),
                    detail = if (!imagesAllowed || imageBusy) imageReason else null,
                    icon = R.drawable.ic_attach, enabled = imagesAllowed && !imageBusy) {
                    attachmentMenuOpen = false; onPickCamera()
                }
                HorizontalDivider()
                AttachmentAction(stringResource(if (fullEditor) R.string.chat_editor_compact else R.string.chat_editor_expand),
                    icon = R.drawable.ic_menu) { switchEditor() }
                AttachmentAction(stringResource(R.string.chat_view_attachments), icon = R.drawable.ic_attach) {
                    attachmentMenuOpen = false; fullEditor = false; openDetails(ChatDetailSection.FILES, null)
                }
                if (!filesAllowed || !imagesAllowed || attachmentBusy || imageBusy) {
                    AttachmentAction(stringResource(R.string.composer_attachment_help), icon = R.drawable.ic_info) {
                        attachmentMenuOpen = false; attachmentHelp = true
                    }
                }
                if (canCancel && pendingCancel == null) {
                    AttachmentAction(stringResource(R.string.workspace_stop_task), icon = R.drawable.ic_stop) {
                        attachmentMenuOpen = false; cancelConfirmEpoch = cancelEpoch
                    }
                }
                if (pendingCancel != null) {
                    AttachmentAction(stringResource(R.string.chat_view_cancel_original), icon = R.drawable.ic_info) {
                        attachmentMenuOpen = false; fullEditor = false
                        openDetails(ChatDetailSection.COMMANDS, pendingCancel.intent.requestId)
                    }
                }
            }
        }
    }
    LaunchedEffect(canCancel, cancelEpoch, pendingCancel?.intent?.requestId) {
        if (!canCancel || pendingCancel != null || cancelConfirmEpoch != null && cancelConfirmEpoch != cancelEpoch) cancelConfirmEpoch = null
    }
    val currentTranscript by rememberUpdatedState(transcript)
    val currentProjection by rememberUpdatedState(projection)
    val currentExpanded by rememberUpdatedState(expandedSourceIds)
    val currentRead by rememberUpdatedState(read)
    val capturePosition: () -> Unit = { captureChatPosition(position, currentProjection, currentExpanded, chatListState) }
    val currentCapture by rememberUpdatedState(capturePosition)
    DisposableEffect(opened, chatListState) {
        onDispose { currentCapture(); position.following = followLatest }
    }
    val requestOlder: () -> Unit = { capturePosition(); onOlder() }
    val currentOnOlder by rememberUpdatedState(requestOlder)
    LaunchedEffect(chatListState, opened, showDetails) {
        if (!showDetails) {
            var userScrolling = false
            snapshotFlow { ChatScrollObservation(chatListState.isScrollInProgress,
                chatListState.layoutInfo.visibleItemsInfo.lastOrNull()?.key,
                chatListState.layoutInfo.visibleItemsInfo.any { it.key == "older" },
                chatListState.firstVisibleItemIndex, chatListState.firstVisibleItemScrollOffset) }
                .collect { view ->
                    if (restoringReading || returningToLatest) userScrolling = false
                    else if (view.scrolling) {
                        userScrolling = true
                        followLatest = view.lastKey == "conversation-end"
                        if (followLatest) newMessages = false
                        currentCapture()
                        val snapshot = currentTranscript
                        val boundary = snapshot?.records?.firstOrNull()?.seq
                        if (initialized && view.atHistory && boundary != null && boundary != requestedBoundary && snapshot.hasMore &&
                            currentRead.phase == SessionReadPhase.READY && !currentRead.loadingOlder && currentRead.pageError == null) {
                            requestedBoundary = boundary
                            currentOnOlder()
                        }
                        position.following = followLatest
                        initialized = true
                    } else if (userScrolling) {
                        currentCapture()
                        position.following = followLatest
                        userScrolling = false
                    }
                }
        }
    }
    // Only structural changes restore anchors. Streaming text cannot cancel this job every 50ms.
    LaunchedEffect(renderedKeys, chatKeys, showDetails) {
        if (showDetails || followLatest) return@LaunchedEffect
        snapshotFlow { chatListState.isScrollInProgress }.first { !it }
        if (followLatest) return@LaunchedEffect
        val savedSource = position.key
        val restoreKey = savedSource?.let { projection.renderKeyForSource(it, expandedSourceIds) }
        val sourceIndex = restoreKey?.let(renderedKeys::indexOf) ?: -1
        if (sourceIndex < 0) {
            position.key = null
            initialized = true
            return@LaunchedEffect
        }
        restoringReading = true
        try {
            withFrameNanos { }
            chatListState.scrollToItem(chatKeys.size + sourceIndex, if (restoreKey == savedSource) position.offset else 0)
            withFrameNanos { }
        } finally { restoringReading = false }
        initialized = true
    }
    LaunchedEffect(chatListState, opened, showDetails) {
        if (!showDetails) snapshotFlow { currentTranscript }.collect { snapshot ->
            if (snapshot == null) return@collect
            val tail = snapshot.items.lastOrNull()
            val length = tail?.blocks?.sumOf { it.text.length } ?: -1
            val changed = lastMessageKey != null && (tail?.id != lastMessageKey || tail?.live == true && length != lastMessageLength)
            if (initialized && !followLatest && changed) newMessages = true
            if (followLatest && !returningToLatest && !chatListState.isScrollInProgress) {
                withFrameNanos { }
                if (followLatest && !returningToLatest && !chatListState.isScrollInProgress) {
                    restoringReading = true
                    try {
                        val end = chatListState.layoutInfo.totalItemsCount - 1
                        if (end >= 0) chatListState.scrollToItem(end)
                        withFrameNanos { }
                    } catch (cancelled: CancellationException) {
                        if (!currentCoroutineContext().isActive) throw cancelled
                        // User input may cancel a scroll mutation without cancelling this observer.
                        followLatest = false
                        position.following = false
                    } finally { restoringReading = false }
                    initialized = true
                    if (followLatest) { position.following = true; newMessages = false }
                }
            }
            lastMessageKey = tail?.id
            lastMessageLength = length
        }
    }
    MediaPickerEffect(state.documentPicker, onPickedFile)
    state.images?.let { MessageMediaPreview(it, onDismissImagePreview) }
    state.exportSavePicker?.let { pending -> key(pending.id) {
        var launched by rememberSaveable { mutableStateOf(false) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> onExportDestination(pending.id, uri) }
        LaunchedEffect(pending.id) { if (!launched) { launched = true; picker.launch(exportSuggestedName(pending.name)) } }
    } }
    val connectionStatus = stringResource(when (read.phase) {
        SessionReadPhase.CONNECTING -> R.string.conversation_connecting
        SessionReadPhase.SYNCING -> R.string.conversation_syncing
        SessionReadPhase.READY -> R.string.conversation_live
        SessionReadPhase.BACKOFF -> R.string.conversation_reconnecting
        SessionReadPhase.OFFLINE -> R.string.conversation_offline
        SessionReadPhase.AUTH_REQUIRED -> R.string.conversation_auth_required
        SessionReadPhase.REVOKED -> R.string.conversation_revoked
        SessionReadPhase.ERROR -> R.string.conversation_read_error
        SessionReadPhase.CLOSED -> R.string.conversation_paused
    })
    val directorySession = state.knownTarget?.session?.takeIf { it.id == opened } ?: state.sessions.firstOrNull { it.id == opened }
    val sessionTitle = directorySession?.displayMetadata(runtime?.access)?.title
        ?: stringResource(R.string.chat_title, state.sessionIdentifier(opened))
    val leadingContent: LazyListScope.() -> Unit = {
            if (read.phase != SessionReadPhase.READY || read.cacheError != null) item("connection") {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(connectionStatus, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        if (read.window != null && read.phase != SessionReadPhase.READY) Text(stringResource(R.string.conversation_history_cached), style = MaterialTheme.typography.bodySmall)
                        if (read.cacheError != null) Text(stringResource(R.string.conversation_history_cache_error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        if (read.phase == SessionReadPhase.OFFLINE && read.window == null) Text(stringResource(R.string.conversation_history_requires_host))
                        if (read.error in setOf("protocol.snapshot_too_large", "client.history_limit")) Text(stringResource(R.string.conversation_history_too_large))
                        if (read.phase in setOf(SessionReadPhase.ERROR, SessionReadPhase.OFFLINE, SessionReadPhase.BACKOFF)) {
                            OutlinedButton(onRetry, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.conversation_history_retry)) }
                        }
                    }
                }
            }
            state.notificationNote?.let { note -> item("notification-note") { Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            if (!showDetails && read.phase == SessionReadPhase.READY && !archived && focusedQuestion != null) item(questionAnchor(focusedQuestion.handle)) {
                QuestionCard(focusedQuestion, read.questions.canAnswer, state.submitting, state.outbox?.records ?: emptyList(), onAnswer, onClaim, onRelease, onRefreshQuestions, onQueryCommands)
            }

            fun LazyListScope.archiveGroup() {
                if (!showDetails || detailSection != ChatDetailSection.INFO || !(archived || runtime?.access?.scope?.actions?.contains("session.archive") == true)) return
                item("archive-session") {
                    key(opened) {
                        SessionArchiveActions(archived, read.runtimeEpoch, read.phase == SessionReadPhase.READY && state.outbox?.canArchive == true && !state.submitting,
                            runtime?.access?.role in setOf("operator", "owner") && runtime?.access?.scope?.actions?.containsAll(setOf("session.archive", "command.status")) == true,
                            onArchive, onRefresh)
                    }
                }
            }
            fun LazyListScope.approvalGroup() {
                val approvals = state.approvals?.takeIf { !archived && runtime?.access?.role == "owner" && runtime.access.scope?.actions?.containsAll(setOf("session.read", "approval.decide")) == true } ?: return
                approvals.error?.let { code -> item("approval-error") { Text(approvalErrorText(code), color = MaterialTheme.colorScheme.error) } }
                val pending = approvals.view.items.filter { it.canReview || it.handle != null || it.remainingMillis > 0 && it.status.state in setOf(ApprovalRemoteState.PENDING, ApprovalRemoteState.DECISION_RECEIVED) }
                if (!showDetails) items(pending, key = { approvalAnchor(it) }) { view ->
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.conversation_approval_pending), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        Text(stringResource(R.string.conversation_approval_summary_notice))
                        view.context?.let { context ->
                            Text(stringResource(R.string.conversation_tool, approvalVisibleText(context.root.string("tool"))), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.conversation_workspace, context.root.obj("workspace").string("workspace_id")), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.conversation_approval_resource_count, context.root.array("resources").size), style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton({ onApproval(view.status.id) }, enabled = approvals.connected && approvals.contentReady, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.conversation_approval_review)) }
                    } }
                }
                if (showDetails && approvals.records.isNotEmpty()) item("approval-records-heading") {
                    Text(stringResource(R.string.conversation_approval_records), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    TextButton(onApprovalQuery, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.conversation_approval_query)) }
                }
                if (showDetails) items(approvals.records, key = { "approval-original:${it.intent.requestId}" }) { row ->
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(if (row.intent.decision.choice == ApprovalChoice.ALLOW_ONCE) R.string.conversation_original_allow else R.string.conversation_original_reject), style = MaterialTheme.typography.titleSmall)
                        Text(approvalRecordLabel(row))
                        OutlinedButton({ onApproval(row.intent.approvalId) }, enabled = approvals.connected, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.conversation_approval_original)) }
                    } }
                }
            }
            fun LazyListScope.uploadsGroup() {
                if (!showDetails || detailSection != ChatDetailSection.FILES) return
                val files = state.uploads?.takeIf { !archived && canCompose }
                if (files == null) {
                    if (detailSection == ChatDetailSection.FILES) item("uploads-unavailable") {
                        Text(stringResource(R.string.chat_attachments_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    return
                }
                item("uploads") {
                    UploadsPanel(files.copy(rows = files.rows.filter { it.stored.intent.image == null }), state.outbox?.canAttachFiles == true && read.phase == SessionReadPhase.READY,
                        onPickFile, onToggleFile, onQueryFile, onRemoveFile, onCancelFile)
                }
            }
            fun LazyListScope.imagesGroup() {
                if (!showDetails || detailSection != ChatDetailSection.IMAGES) return
                state.images?.let { imageState -> item("images") {
                    SessionImagesPanel(imageState, state.uploads,
                        canCompose && state.outbox?.canAttachImages == true && read.phase == SessionReadPhase.READY,
                        onPickImage, onPickCamera, onToggleFile, onQueryFile, onRemoveFile, onCancelImage, onDismissImagePreview, onReselectImage)
                } }
            }
            fun LazyListScope.exportsGroup() {
                if (!showDetails || detailSection != ChatDetailSection.FILES) return
                state.exports?.let { exports -> item("exports") { ExportsPanel(exports, onBrowseExports, onExport, onQueryExports, onCancelExport, onRemoveExport, onSaveExport, state.exportSaving || state.exportSavePicker != null) } }
            }
            fun LazyListScope.exportStatusGroup() {
                if (!showDetails || detailSection != ChatDetailSection.FILES || !(state.exportSaving || state.exportSaved || state.exportSaveError != null)) return
                item("export-save-status") {
                    if (state.exportSaving) {
                        Text(stringResource(R.string.conversation_export_progress, state.exportBytes), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        TextButton(onCancelExportSave, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.conversation_export_cancel)) }
                    }
                    if (state.exportSaved) Text(stringResource(R.string.conversation_export_verified), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    state.exportSaveError?.let { Text(exportErrorText(it), color = MaterialTheme.colorScheme.error) }
                }
            }
            fun LazyListScope.commandGroup() {
                state.commandError?.let { error -> item("command-error") { Text(commandErrorText(error), color = MaterialTheme.colorScheme.error) } }
                val outbox = state.outbox ?: return
                outbox.error?.let { error -> item("outbox-error") { Text(commandErrorText(error), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } }
                if (!showDetails || detailSection != ChatDetailSection.COMMANDS) return
                val target = detailRequestId.takeIf { detailSection == ChatDetailSection.COMMANDS }
                val ordered = outbox.records.sortedWith(
                    compareBy<PendingCommand> { if (target != null && it.intent.requestId == target) 0 else 1 }
                        .thenByDescending { it.createdAt }.thenByDescending { it.intent.requestId })
                if (target != null && ordered.none { it.intent.requestId == target }) item("original-unavailable") { Text(stringResource(R.string.chat_target_gone), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                if (ordered.isEmpty()) item("outbox-empty") { Text(stringResource(R.string.chat_no_delivery_records), style = MaterialTheme.typography.bodySmall) }
                else item("outbox-heading") {
                    Text(stringResource(R.string.conversation_request_records), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.conversation_request_meaning), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onQueryCommands, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.conversation_request_query)) }
                }
                items(ordered, key = { "command:${it.intent.requestId}" }) { record ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(commandLabel(record)), style = MaterialTheme.typography.titleSmall)
                            if (record.intent.kind == CommandKind.MESSAGE && record.remote == RemoteCommandState.SETTLED) {
                                Text(stringResource(R.string.chat_delivery_settled_note), style = MaterialTheme.typography.bodySmall)
                            }
                            SelectionContainer { Text(stringResource(R.string.chat_original_request, record.intent.requestId), style = MaterialTheme.typography.bodySmall) }
                            Text(stringResource(when {
                                record.attempts == 0L -> R.string.chat_local_only
                                record.remote == RemoteCommandState.RECEIVED -> R.string.chat_relay_only
                                record.remote == RemoteCommandState.HOST_ACCEPTED -> R.string.chat_host_saved
                                record.everAgentQueued -> R.string.chat_queued_not_finished
                                else -> R.string.chat_original_reconcile
                            }), style = MaterialTheme.typography.bodySmall)
                            if (needsDeliveryReconciliation(record) && record.remote != RemoteCommandState.OUTCOME_UNKNOWN) {
                                Text(stringResource(R.string.chat_original_reconcile), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            if (record.intent.kind == CommandKind.ARCHIVE) {
                                val archiveIntent = (record.intent.payload["archived"] as JsonBoolean).value
                                Text(stringResource(if (archiveIntent) R.string.conversation_archive else R.string.conversation_restore))
                                if ((record.intent.payload["stop_activity"] as JsonBoolean).value) Text(stringResource(R.string.conversation_archive_stop_note), style = MaterialTheme.typography.bodySmall)
                            }
                            if (!record.everAgentQueued) record.intent.text()?.let { RichMessageText(it, 512) }
                            if (record.intent.fileIds().isNotEmpty()) Text(stringResource(R.string.conversation_request_attachment_count, record.intent.fileIds().size), style = MaterialTheme.typography.bodySmall)
                            if(record.intent.kind.question) SubmittedQuestionAnswer(record.intent)
                            record.intent.modelSelection()?.let { selection ->
                                SelectionContainer { Text("${selection.providerId}\n${selection.modelId}" +
                                    (selection.reasoningEffortId?.let { "\n$it" } ?: "")) }
                                Text(stringResource(R.string.models_checkpoint_scope), style = MaterialTheme.typography.bodySmall)
                            }
                            val questionCurrent=!record.intent.kind.question || read.questions.items.any {it.batch.id==record.intent.payload.string("question_id") && it.batch.definition==record.intent.questionDefinition && it.handle.epoch==record.intent.epoch && it.answerable}
                            val actionAllowed = (!archived || record.intent.kind == CommandKind.ARCHIVE) && (record.intent.epoch == null || record.intent.epoch == read.runtimeEpoch)
                            if (record.delivery == CommandDelivery.PENDING && record.attempts == 0L && questionCurrent) {
                                OutlinedButton({ onSendPending(record.intent.requestId) }, enabled = outbox.connected && actionAllowed, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(if (record.intent.kind == CommandKind.MESSAGE) R.string.conversation_send_original_message else R.string.conversation_send_original_command)) }
                            }
                            if ((record.remote == RemoteCommandState.RECEIVED || record.remote == null && record.intent.fileIds().isNotEmpty()) && !record.everHostSaved && record.attempts > 0 && questionCurrent) {
                                var retry by remember { mutableStateOf(false) }
                                OutlinedButton({ retry = true }, enabled = outbox.connected && actionAllowed && (record.intent.fileIds().isEmpty() || outbox.canAttachFiles), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.conversation_request_retry)) }
                                if (retry) AlertDialog(onDismissRequest = { retry = false }, title = { Text(stringResource(R.string.conversation_request_retry_title)) },
                                    text = { Text(stringResource(R.string.conversation_request_retry_notice)) },
                                    confirmButton = { TextButton({ retry = false; onSendPending(record.intent.requestId) }) { Text(stringResource(R.string.conversation_request_retry_confirm)) } },
                                    dismissButton = { TextButton({ retry = false }) { Text(stringResource(R.string.action_cancel)) } })
                            }
                            record.error?.let { Text(commandErrorText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            fun LazyListScope.cancelGroup() {
                if (!showDetails || detailSection != ChatDetailSection.INFO || !(canCancel || pendingCancel != null)) return
                item("cancel-task") {
                    if (pendingCancel != null) {
                        Text(stringResource(R.string.chat_cancel_pending), style = MaterialTheme.typography.titleSmall)
                        SelectionContainer { Text(stringResource(R.string.chat_original_request, pendingCancel.intent.requestId), style = MaterialTheme.typography.bodySmall) }
                    }
                    if (canCancel && cancelEpoch != null) {
                        OutlinedButton({ cancelConfirmEpoch = cancelEpoch }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.workspace_stop_task)) }
                    }
                }
            }

            if (showDetails) {
                when (detailSection) {
                    ChatDetailSection.INFO -> {
                        item("session-info") {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                directorySession?.displayMetadata(runtime?.access)?.let { metadata ->
                                    metadata.title?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.titleMedium) } }
                                    metadata.workspaceTitle?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodyMedium) } }
                                    if (metadata.titleSource == "cached") Text(stringResource(R.string.directory_cached_name_note), style = MaterialTheme.typography.bodySmall)
                                }
                                SelectionContainer { Text(stringResource(R.string.conversation_resource_identity, opened, runtime?.hostId.orEmpty(), state.selectedRuntimeId.orEmpty())) }
                                directorySession?.let { session ->
                                    Text(stringResource(R.string.conversation_directory_status, session.state))
                                    SelectionContainer { Text(stringResource(R.string.conversation_workspace, session.workspaceId ?: stringResource(R.string.value_unknown))) }
                                    Text(stringResource(R.string.conversation_directory_checkpoint, session.lastPersisted), style = MaterialTheme.typography.bodySmall)
                                    Text(stringResource(R.string.conversation_directory_updated, java.time.Instant.ofEpochMilli(session.updatedAt).toString()), style = MaterialTheme.typography.bodySmall)
                                } ?: Text(stringResource(R.string.conversation_directory_missing), style = MaterialTheme.typography.bodySmall)
                                TextButton(onRefresh, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.conversation_directory_refresh)) }
                                TextButton({ openDetails(ChatDetailSection.FILES, null) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.chat_files)) }
                                TextButton({ openDetails(ChatDetailSection.COMMANDS, null) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.chat_original_records)) }
                            }
                        }
                        archiveGroup()
                        cancelGroup()
                    }
                    ChatDetailSection.FILES -> { uploadsGroup(); exportsGroup(); exportStatusGroup() }
                    ChatDetailSection.IMAGES -> imagesGroup()
                    ChatDetailSection.COMMANDS -> { commandGroup(); approvalGroup() }
                }
            } else {
                approvalGroup()
                if (read.phase == SessionReadPhase.READY && !archived) {
                    items(visibleQuestions.filter { it.handle != focusedQuestion?.handle },key={questionAnchor(it.handle)}) {question->
                        QuestionCard(question,read.questions.canAnswer,state.submitting,state.outbox?.records ?: emptyList(),onAnswer,onClaim,onRelease,onRefreshQuestions,onQueryCommands)
                    }
                    read.questions.error?.let {code->item("question-error") {Text(commandErrorText(code),color=MaterialTheme.colorScheme.error)}}
                }
            }
            if (!showDetails && transcript != null) {
                if (transcript.hasMore && read.phase == SessionReadPhase.READY) item("older") {
                    OutlinedButton(requestOlder, enabled = !read.loadingOlder && read.pageError != "client.history_limit", modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(if (read.loadingOlder) R.string.conversation_history_loading else R.string.conversation_history_older)) }
                    read.pageError?.let { Text(stringResource(if (it == "client.history_limit") R.string.conversation_history_limit else R.string.conversation_history_error), color = MaterialTheme.colorScheme.error) }
                }
                if (transcript.checkpoint != "persisted") item("persistence") { Text(stringResource(R.string.conversation_history_unconfirmed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (transcript.items.isEmpty() && read.phase == SessionReadPhase.READY) item("empty-session") { Text(stringResource(R.string.conversation_empty)) }
            }
    }
    if (showDetails) {
        val presentation = when (layout.size) {
            WorkspaceSize.COMPACT -> TaskPresentation.PAGE
            WorkspaceSize.MEDIUM -> TaskPresentation.SHEET
            WorkspaceSize.EXPANDED -> TaskPresentation.SIDE_PANEL
        }
        LaunchedEffect(details?.section, details?.requestId) { detailsListState.scrollToItem(0) }
        AdaptiveSessionPanel(layout, presentation, returnFromChat,
            backdrop = { ReadOnlySessionBackdrop(state, position) }) {
            key(presentation) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(returnFromChat, Modifier.size(48.dp)) {
                            Icon(painterResource(R.drawable.ic_back), stringResource(R.string.chat_messages))
                        }
                        Text(stringResource(when (detailSection) {
                            ChatDetailSection.INFO -> R.string.chat_details
                            ChatDetailSection.FILES -> R.string.chat_files
                            ChatDetailSection.IMAGES -> R.string.session_actions_images
                            ChatDetailSection.COMMANDS -> R.string.chat_original_records
                        }), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("chat-details"),
                        state = detailsListState, contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp), content = leadingContent)
                }
            }
        }
    } else {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        topBar = {
            Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
                contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = TokenWorksSpacing.readingWidth).fillMaxWidth()) {
                    SessionHeader(sessionTitle,
                        stringResource(R.string.workspace_host_status, runtime?.hostAlias ?: stringResource(R.string.chat_authorized_computer), connectionStatus),
                        connection = when (read.phase) {
                            SessionReadPhase.READY -> HeaderConnection.LIVE
                            SessionReadPhase.CONNECTING, SessionReadPhase.SYNCING -> HeaderConnection.PENDING
                            SessionReadPhase.BACKOFF, SessionReadPhase.OFFLINE, SessionReadPhase.CLOSED -> HeaderConnection.DEGRADED
                            SessionReadPhase.AUTH_REQUIRED, SessionReadPhase.REVOKED, SessionReadPhase.ERROR -> HeaderConnection.ERROR
                        },
                        modelsEnabled = onModels != null && state.modelCatalog != null,
                        createEnabled = runtime?.access?.creationScope() != null,
                        onNavigation = onOpenNavigation, onHosts = onOpenHosts,
                        onModels = { capturePosition(); onModels?.invoke() }, onCreate = onCreateSession, onMore = { capturePosition(); taskMenu = true })
                    Box(Modifier.align(Alignment.BottomEnd).width(48.dp)) {
                        DropdownMenu(taskMenu, { taskMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.chat_details)) }, onClick = { taskMenu = false; openDetails(ChatDetailSection.INFO, null) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.chat_files)) }, onClick = { taskMenu = false; openDetails(ChatDetailSection.FILES, null) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.chat_original_records)) }, onClick = { taskMenu = false; openDetails(ChatDetailSection.COMMANDS, null) })
                            if (onChanges != null && state.changes != null) DropdownMenuItem(text = { Text(stringResource(R.string.session_actions_changes)) },
                                onClick = { taskMenu = false; onChanges() })
                            if (state.images != null) DropdownMenuItem(text = { Text(stringResource(R.string.session_actions_images)) },
                                onClick = { taskMenu = false; openDetails(ChatDetailSection.IMAGES, null) })
                        }
                    }
                }
            }
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            ChatTranscript(projection, chatListState, expandedSourceIds, interactive = true,
                leadingContent = leadingContent,
                trailingContent = {
                    if (transcript?.taskRunning == true && read.phase == SessionReadPhase.READY) item("running") {
                        Text(stringResource(R.string.conversation_running), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item("conversation-end") { Spacer(Modifier.height(1.dp)) }
                },
                onProcessExpansion = { ids, expand ->
                    if (!expand || position.key !in ids) capturePosition()
                    followLatest = false
                    position.following = false
                    expandedSourceIds = if (expand) expandedSourceIds + ids else expandedSourceIds - ids.toSet()
                },
                onQuote = state.composer?.takeIf { canCompose }?.let { draft -> { quote ->
                    val quoted = draft.withLocalQuote(quote)
                    if (quoted.value.text.length <= 65_536) onComposerChange(quoted) else quoteTooLarge = true
                } },
                onMessageAction = onMessageAction.takeIf { state.messageActions?.canPreview == true },
                onPreviewMedia = onPreviewMedia.takeIf { read.phase == SessionReadPhase.READY && runtime?.access?.scope?.actions?.contains("file.download") == true },
                modifier = Modifier.widthIn(max = TokenWorksSpacing.readingWidth).fillMaxWidth().weight(1f).testTag("chat-list"))
            Column(Modifier.widthIn(max = TokenWorksSpacing.readingWidth).fillMaxWidth()) {
                // Measure the composer first; long status prose may scroll, never cover Send.
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                if (initialized && (!followLatest || newMessages)) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.widthIn(max = TokenWorksSpacing.readingWidth).fillMaxWidth().padding(horizontal = 16.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    followLatest = true
                                    position.following = true
                                    newMessages = false
                                    scope.launch {
                                        returningToLatest = true
                                        try { chatListState.animateScrollToItem((chatListState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) }
                                        finally {
                                            returningToLatest = false
                                            followLatest = chatListState.layoutInfo.visibleItemsInfo.lastOrNull()?.key == "conversation-end"
                                            position.following = followLatest
                                        }
                                    }
                                },
                                modifier = Modifier.align(Alignment.CenterEnd).heightIn(min = 48.dp),
                            ) { Text(stringResource(if (newMessages) R.string.chat_new_messages else R.string.chat_latest)) }
                        }
                    }
                }
                if (pendingQuestions.isNotEmpty() || pendingApprovals.isNotEmpty() || pendingOriginals.isNotEmpty()) Box {
                    TextButton({ pendingMenu = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.workspace_pending, pendingQuestions.size + pendingApprovals.size + pendingOriginals.size))
                    }
                    DropdownMenu(pendingMenu, { pendingMenu = false }) {
                        pendingQuestions.forEach { question ->
                            DropdownMenuItem(text = { Text(stringResource(R.string.conversation_pending_question, stringResource(if (question.handle.kind == QuestionKind.BLOCKING) R.string.conversation_question_blocking else R.string.conversation_question_timed), question.batch.prompts.first().question), maxLines = 2, overflow = TextOverflow.Ellipsis) }, onClick = {
                                pendingMenu = false; followLatest = false; targetGone = false; requestedAnchor = questionAnchor(question.handle)
                            })
                        }
                        pendingApprovals.forEach { approval ->
                            DropdownMenuItem(text = { Text(stringResource(R.string.conversation_pending_approval, approval.status.id)) }, onClick = {
                                pendingMenu = false; followLatest = false; targetGone = false; requestedAnchor = approvalAnchor(approval)
                            })
                        }
                        pendingOriginals.forEach { original ->
                            DropdownMenuItem(text = { Text(stringResource(R.string.conversation_pending_original, original.intent.requestId)) }, onClick = { pendingMenu = false; openDetails(ChatDetailSection.COMMANDS, original.intent.requestId) })
                        }
                    }
                }
                if (targetGone) Text(stringResource(R.string.chat_target_gone), modifier = Modifier.padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite })
                if (hasOperationFooter) ChatOperationFooter(summary, footerError, footerCanCancel, pendingCancel,
                    onViewDelivery = { openDetails(ChatDetailSection.COMMANDS, summary?.intent?.requestId) },
                    onViewCancel = { openDetails(ChatDetailSection.COMMANDS, pendingCancel?.intent?.requestId) },
                    onCancel = { if (canCancel && pendingCancel == null && cancelEpoch != null) cancelConfirmEpoch = cancelEpoch })
                }
                if (canCompose && !fullEditor) state.composer?.let { draft -> SessionComposer(state, onSubmit, draft, onComposerChange,
                    onAttach = { attachmentMenuOpen = true }, onRemove = onToggleFile,
                    onViewAttachments = { attachmentMenuOpen = false; openDetails(ChatDetailSection.FILES, null) }, onExpand = switchEditor,
                    onResolveFailure = onResolveFailedInput, canCancel = canCancel && pendingCancel == null,
                    onCancel = { if (canCancel && pendingCancel == null) cancelConfirmEpoch = cancelEpoch }) }
            }
        }
    }
    }
    if (fullEditor && canCompose) state.composer?.let { draft ->
        Dialog(onDismissRequest = { dismissEditor() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            val editorKeyboard = LocalSoftwareKeyboardController.current
            val editorImeVisible = WindowInsets.isImeVisible
            SideEffect { dismissEditor = { if (editorImeVisible) editorKeyboard?.hide() else { attachmentMenuOpen = false; fullEditor = false } } }
            BackHandler { dismissEditor() }
            Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                SessionComposer(state, onSubmit, draft, onComposerChange, { attachmentMenuOpen = true }, onToggleFile,
                    { attachmentMenuOpen = false; fullEditor = false; openDetails(ChatDetailSection.FILES, null) },
                    expanded = true, onExpand = switchEditor, onResolveFailure = onResolveFailedInput,
                    canCancel = canCancel && pendingCancel == null,
                    onCancel = { if (canCancel && pendingCancel == null) cancelConfirmEpoch = cancelEpoch })
            }
        }
    }
    attachmentPanel()
    if (attachmentHelp) AlertDialog(onDismissRequest = { attachmentHelp = false },
        title = { Text(stringResource(R.string.composer_attachment_help)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!filesAllowed || attachmentBusy) Text(fileReason)
            if (!imagesAllowed || imageBusy) Text(imageReason)
        } }, confirmButton = { TextButton({ attachmentHelp = false }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_close)) } })
    if (quoteTooLarge) AlertDialog(onDismissRequest = { quoteTooLarge = false }, title = { Text(stringResource(R.string.chat_quote_too_large)) },
        confirmButton = { TextButton({ quoteTooLarge = false }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_close)) } })
    val confirmEpoch = cancelConfirmEpoch
    if (confirmEpoch != null && canCancel && pendingCancel == null && confirmEpoch == cancelEpoch) AlertDialog(
        onDismissRequest = { cancelConfirmEpoch = null }, title = { Text(stringResource(R.string.conversation_cancel_title)) },
        text = { Text(stringResource(R.string.conversation_cancel_detail)) },
        confirmButton = { TextButton({
            val epoch = cancelEpoch
            cancelConfirmEpoch = null
            if (epoch != null && epoch == confirmEpoch && canCancel && pendingCancel == null) onCancelSession(epoch)
        }) { Text(stringResource(R.string.conversation_cancel_confirm)) } },
        dismissButton = { TextButton({ cancelConfirmEpoch = null }) { Text(stringResource(R.string.conversation_cancel_keep)) } })
}

/** The background receives only the current accepted window; it owns no platform or network effects. */
@Composable
internal fun ReadOnlySessionBackdrop(state: HomeState, position: ChatReadingPosition?) {
    val window = state.reader?.window
    val projection = remember(window?.items) { projectChatTimeline(window?.items.orEmpty(), running = false) }
    val expanded = position?.expandedSourceIds.orEmpty()
    val keys = remember(projection, expanded) { projection.renderKeys(expanded) }
    val anchor = position?.key?.let { projection.renderKeyForSource(it, expanded) }
    val initialIndex = if (position?.following != false) (keys.size - 1).coerceAtLeast(0) else keys.indexOf(anchor).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex,
        initialFirstVisibleItemScrollOffset = if (anchor == position?.key) position?.offset ?: 0 else 0)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
            ChatTranscript(projection, listState, expanded, interactive = false,
                leadingContent = {}, trailingContent = {}, onProcessExpansion = { _, _ -> },
                onQuote = null, onMessageAction = null, onPreviewMedia = null,
                modifier = Modifier.widthIn(max = TokenWorksSpacing.readingWidth).fillMaxSize())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatOperationFooter(summary: PendingCommand?, error: String?, canCancel: Boolean, pendingCancel: PendingCommand?,
    onViewDelivery: () -> Unit, onViewCancel: () -> Unit, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val sameCancel = summary != null && summary.intent.requestId == pendingCancel?.intent?.requestId
            if (summary != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (sameCancel) R.string.chat_cancel_pending else commandLabel(summary)),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                val label = stringResource(if (sameCancel) R.string.chat_view_cancel_original else R.string.chat_view_delivery)
                TextButton(if (sameCancel) onViewCancel else onViewDelivery,
                    Modifier.heightIn(min = 48.dp).semantics { contentDescription = label }) {
                    Text(stringResource(R.string.workspace_delivery_details))
                }
            }
            if (error != null) Text(commandErrorText(error), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (pendingCancel != null && !sameCancel) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.chat_cancel_pending), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                val label = stringResource(R.string.chat_view_cancel_original)
                TextButton(onViewCancel, Modifier.heightIn(min = 48.dp).semantics { contentDescription = label }) {
                    Text(stringResource(R.string.workspace_delivery_details))
                }
            }
            if (canCancel) TextButton(onCancel, Modifier.heightIn(min = 48.dp)) {
                Icon(painterResource(R.drawable.ic_stop), null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.workspace_stop_task))
            }
        }
    }
}


@Composable
private fun commandErrorText(code: String): String {
    if (code.startsWith("file.")) return fileErrorText(code)
    return stringResource(when (code) {
    "data.locked", "data.lock_required" -> R.string.composer_error_data_locked
    "data.key_missing", "data.key_corrupt", "data.owner_mismatch", "data.corrupt", "data.unavailable" -> R.string.composer_error_data_key_missing
    "data.outbox_limit" -> R.string.composer_error_data_outbox_limit
    "command.busy" -> R.string.composer_error_command_busy
    "command.too_large", "command.invalid" -> R.string.composer_error_command_too_large
    "command.reconcile_required", "command.delivery_unconfirmed", "command.status_unavailable" -> R.string.composer_error_command_reconcile_required
    "runtime.changed" -> R.string.composer_error_runtime_changed
    "session.active" -> R.string.composer_error_session_active
    "session.archived" -> R.string.composer_error_session_archived
    "access.denied" -> R.string.composer_error_access_denied
    "question.stale","question.resync_required","question.ended","interaction.stale" -> R.string.composer_error_question_stale
    "question.invalid_answer" -> R.string.composer_error_question_invalid_answer
    "question.reconcile_required" -> R.string.composer_error_question_reconcile_required
    "question.claim_limit","rate.limited" -> R.string.composer_error_question_claim_limit
    "command.cancel_unconfirmed" -> R.string.composer_error_command_cancel_unconfirmed
        else -> R.string.composer_error_operation
    })
}

/** A sheet row for one attachment action; the reason text explains a disabled row without color. */
@Composable
private fun AttachmentAction(
    title: String,
    @androidx.annotation.DrawableRes icon: Int,
    detail: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyMedium) },
        supportingContent = detail?.let { text -> { Text(text, style = MaterialTheme.typography.bodySmall, color = tokenWorksSurfaces().textTertiary) } },
        leadingContent = { Icon(painterResource(icon), null, Modifier.size(TokenWorksSpacing.iconSize)) },
        modifier = Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (enabled) Modifier else Modifier.semantics { disabled() }),
    )
}
