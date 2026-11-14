package cn.edu.ustc.tokenworks

import android.content.Context
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class SessionCoordinator(
    private val context: Context, private val scope: CoroutineScope,
    private val mutable: MutableStateFlow<HomeState>, private val account: AccountCoordinator,
    private val routes: RuntimeRoutes,
) {
    private val drafts = ConversationDrafts()
    private val transcripts = EncryptedTranscriptCache(context)
    var foreground = false
        private set
    var backgroundHold = false
        private set
    var onAuthorityLost: () -> Unit = {}
    var notificationReferenceExpiry: Long? = null
    private fun writable() = foreground && !mutable.value.backgroundReadOnly && mutable.value.phase == HomePhase.READY
    fun holdBackground(active: Boolean) {
        backgroundHold = active && mutable.value.phase == HomePhase.READY && mutable.value.selectedSessionId != null
        mutable.update { it.copy(backgroundReadOnly = !foreground && backgroundHold) }
        if (!backgroundHold && !foreground) { retireSession(false); routes.close() }
    }
    val navigationVersion: Long get() = navigationGeneration
    private val generation: Long get() = account.generation
    private fun current(stamp: Long) = account.current(stamp)
    private fun now() = account.now()
    private var navigationGeneration = 0L
    private var navigationJob: Job? = null
    private var readerJob: Job? = null
    private var reader: DshSessionReader? = null
    val canHoldBackground: Boolean get() = reader?.let { it.sessionId == mutable.value.selectedSessionId &&
        it.state.value.phase in setOf(SessionReadPhase.CONNECTING, SessionReadPhase.SYNCING, SessionReadPhase.READY, SessionReadPhase.BACKOFF) } == true
    private var readMarks: SessionReadMarks? = null
    private val interactions = InteractionCoordinator(scope)
    private var terminal: TerminalWatcher? = null
    private var files: FilesCoordinator? = null
    private var fileSession: String? = null
    private val outbox = OutboxCoordinator(scope) { EncryptedOutboxStore(context).open() }
    private var watcherJob: Job? = null
    private var desired: Pair<String, String?>? = null
    init {
        outbox.load()
        scope.launch {
            outbox.state.collect { delivery ->
                val route = commandRoute()
                mutable.update { home -> home.copy(pendingPrompts = delivery.entries.filter { it.route == route },
                    submitting = delivery.sending != null || home.creation?.phase == SessionCreationPhase.CREATING, outboxError = delivery.error) }
            }
        }
    }
    fun acceptedOwner(owner: CommandOwner) { readMarks = SessionReadMarks(context, owner.fingerprint) }
    fun fence(closeKeys: Boolean, preserveSelection: Boolean) {
        backgroundHold = false
        onAuthorityLost()
        drafts.clear()
        retireSession(!preserveSelection)
        routes.close()
        if (closeKeys) readMarks = null
    }
    suspend fun clearOwner(ownerId: String) {
        outbox.clearOwner(ownerId)
        transcripts.clearOwner(ownerId)
    }
    suspend fun revokeRuntime(row: RuntimeRow) {
        drafts.revokeRuntime(row.id)
        if (mutable.value.selectedRuntimeId == row.id) { retireSession(false); routes.close() }
        mutable.value.presentationOwner?.fingerprint?.let {
            outbox.revokeRuntime(it, row.hostId, row.id)
            transcripts.clearRuntime(it, row.hostId, row.id)
        }
    }
    suspend fun directoryChanged(selected: String?, stamp: Long) {
        if (selected == null) {
            retireSession(true)
            mutable.update { home -> home.copy(selectedSessionId = null, sessions = emptyList(), sessionError = null, loadingSessions = false) }
        } else if (foreground) {
            loadSessions(selected, stamp)
            val resumed = desired?.takeIf { it.first == selected }?.second
            if (resumed != null) follow(selected, resumed, stamp, reopen = true)
        }
    }
    private fun route(runtime: String): DshTransport = routes.route(runtime)
    /** Directory rows for one runtime; a failing call keeps the previous rows and reports the error. */
    private suspend fun loadSessions(runtime: String, stamp: Long) {
        val client = DshSessionClient(route(runtime))
        try {
            mutable.update { home -> home.copy(loadingSessions = true, sessionError = null) }
            val page = client.sessions()
            current(stamp)
            if (mutable.value.selectedRuntimeId != runtime) return
            val access = mutable.value.runtimes.firstOrNull { it.id == runtime }?.access
            val rows = page.items.filter { access == null || it.viewScope(access, now()) }
            mutable.update { home -> home.copy(sessions = rows, loadingSessions = false) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            current(stamp)
            mutable.update { home -> home.copy(loadingSessions = false, sessionError = homeErrorCode(error)) }
        }
    }

    /** Local-only convenience mark; it never implies server-side read state. */
    fun isUnread(entry: SessionDirectoryEntry): Boolean = readMarks?.isUnread(entry.id, entry.updatedAt) == true

    fun refreshSessions() {
        val runtime = mutable.value.selectedRuntimeId ?: return
        if (!foreground || mutable.value.refreshing) return
        val stamp = generation
        navigation { try { loadSessions(runtime, stamp) } catch (error: CancellationException) { throw error } catch (error: Exception) { if (generation == stamp) mutable.update { home -> home.copy(sessionError = homeErrorCode(error)) } } }
    }

    private fun navigation(block: suspend CoroutineScope.() -> Unit) {
        val task = scope.launch(start = CoroutineStart.LAZY, block = block); navigationJob = task; task.start()
    }

    /** Selecting a runtime replaces any followed session; the reader is torn down first. */
    fun openRuntime(id: String) {
        if (!writable()) return
        if (!foreground || mutable.value.phase != HomePhase.READY || mutable.value.binding != null || mutable.value.pairingOpen) return
        val row = mutable.value.runtimes.firstOrNull { it.id == id } ?: return
        if (mutable.value.selectedRuntimeId == id && mutable.value.sessions.isNotEmpty()) return
        desired = id to null
        retireSession(false)
        mutable.update { home -> home.copy(selectedRuntimeId = id, selectedSessionId = null, sessions = emptyList(), sessionError = null, loadingSessions = true) }
        val stamp = generation
        navigation { try { loadSessions(id, stamp) } catch (error: CancellationException) { throw error } catch (error: Exception) { if (generation == stamp) mutable.update { home -> home.copy(loadingSessions = false, sessionError = homeErrorCode(error)) } } }
    }

    /** Opens one session with one reader; a second call for the same session is a no-op. */
    fun openSession(id: String) {
        if (!writable()) return
        val runtime = mutable.value.selectedRuntimeId ?: return
        desired = runtime to id
        follow(runtime, id, generation, reopen = reader?.sessionId != id)
    }

    fun closeSession() {
        desired = mutable.value.selectedRuntimeId?.let { it to null }
        retireSession(false)
        mutable.update { home -> home.copy(selectedSessionId = null) }
    }

    fun retireSession(clearDesired: Boolean) {
        navigationGeneration++
        navigationJob?.cancel(); navigationJob = null
        readerJob?.cancel(); readerJob = null
        reader?.close(); reader = null
        outbox.cancelSending()
        files?.close(); files = null
        fileSession = null
        if (clearDesired) desired = null
        retireWatcher()
        terminal?.close(); terminal = null
        mutable.update { home -> home.copy(reader = null, terminal = null, files = null, pendingPrompts = emptyList(), loadingSessions = false, submitting = false, modelSelectionOpen = false, modelBusy = false, composer = null) }
    }

    private fun ensureWatcher(runtime: String, sessionId: String) {
        val connection = route(runtime)
        interactions.start(connection)
        watcherJob?.cancel()
        watcherJob = scope.launch {
            interactions.state.collect { mutable.update { home -> home.copy(interactions = it.interactions, interactionError = it.error) } }
        }
        terminal?.close()
        terminal = TerminalWatcher(connection, sessionId, scope)
        if (fileSession != sessionId || files == null) {
            files?.close()
            files = FilesCoordinator(connection, java.io.File(context.noBackupFilesDir, "file-preview"), scope, sessionId)
            fileSession = sessionId
        }
        mutable.update { home -> home.copy(terminal = terminal, files = files) }
    }

    private fun retireWatcher() {
        watcherJob?.cancel(); watcherJob = null
        interactions.stop()
        mutable.update { home -> home.copy(interactions = emptyList(), interactionError = null) }
    }

    private fun follow(runtime: String, session: String, stamp: Long, reopen: Boolean) {
        if (reopen) {
            outbox.cancelSending()
            ensureWatcher(runtime, session)
            readerJob?.cancel(); readerJob = null
            reader?.close(); reader = null
            mutable.update { home -> home.copy(selectedRuntimeId = runtime, selectedSessionId = session, sessionError = null, sessionActionError = null, reader = null, composer = draftResource(runtime, session)?.let { drafts.open(it) }) }
            val next = DshSessionReader(route(runtime), session, scope, cache = commandRoute()?.let { transcripts.forRoute(it) })
            reader = next
            mutable.update { home -> home.copy(pendingPrompts = outbox.state.value.entries.filter { it.route == commandRoute() }) }
            readerJob = scope.launch {
                next.state.collect { state ->
                    if (generation != stamp || reader !== next) return@collect
                    mutable.update { home -> home.copy(reader = state) }
                    if (backgroundHold && state.phase in setOf(SessionReadPhase.AUTH_REQUIRED, SessionReadPhase.REVOKED, SessionReadPhase.ERROR, SessionReadPhase.CLOSED)) onAuthorityLost()
                    if (state.phase == SessionReadPhase.READY) mutable.value.sessions.firstOrNull { it.id == session }?.let { readMarks?.mark(session, it.updatedAt) }
                }
            }
        } else {
            mutable.update { home -> home.copy(selectedRuntimeId = runtime, selectedSessionId = session, composer = draftResource(runtime, session)?.let { drafts.open(it) }) }
        }
    }

    private fun draftResource(runtime: String, session: String): ConversationResource? {
        val owner = mutable.value.presentationOwner ?: return null
        val host = mutable.value.runtimes.firstOrNull { it.id == runtime }?.hostId ?: return null
        return ConversationResource(owner, host, runtime, session)
    }

    /** Draft text stays in foreground memory only; it is never persisted. */
    fun editDraft(value: TextFieldValue) {
        if (!writable()) return
        val owner = mutable.value.presentationOwner ?: return
        val runtime = mutable.value.selectedRuntimeId ?: return
        val session = mutable.value.selectedSessionId ?: return
        val host = mutable.value.runtimes.firstOrNull { it.id == runtime }?.hostId ?: return
        val resource = ConversationResource(owner, host, runtime, session)
        val currentDraft = mutable.value.composer ?: drafts.open(resource)
        drafts.edit(currentDraft.copy(value = value))?.let { mutable.update { home -> home.copy(composer = it) } }
    }

    /** Clear a draft only after the durable intent commits, never before disk persistence. */
    fun sendDraft() {
        if (!writable()) return
        val draft = mutable.value.composer ?: return
        val reader = reader ?: return
        val route = commandRoute() ?: return
        val filesOwner = files
        val receipts = filesOwner?.state?.value?.uploaded.orEmpty().filter { it.sessionId == reader.sessionId }.map { it.receiptId }
        val capturedImages = filesOwner?.imagesFor(reader.sessionId).orEmpty().toList()
        if ((draft.value.text.isBlank() && receipts.isEmpty() && capturedImages.isEmpty()) || mutable.value.submitting) return
        outbox.submit(route, draft.value.text, draft.mode, reader, receipts, capturedImages) {
            receipts.forEach { filesOwner?.removeUpload(it) }
            filesOwner?.consumeImages(capturedImages)
            val cleared = drafts.saved(draft)
            if (mutable.value.composer?.resource == draft.resource && cleared != null)
                mutable.update { home -> home.copy(composer = cleared) }
        }
    }

    private fun commandRoute(): DshCommandRoute? {
        val resource = mutable.value.composer?.resource ?: return null
        return DshCommandRoute(resource.owner.fingerprint, resource.hostId, resource.runtimeId, resource.sessionId)
    }

    fun retryPrompt(requestId: String) {
        if (!writable()) return
        val route = commandRoute() ?: return
        val current = reader ?: return
        outbox.retry(requestId, route, current)
    }
    fun abandonPrompt(requestId: String) { commandRoute()?.let { outbox.abandon(requestId, it) } }

    fun loadOlder() {
        val current = reader ?: return
        if (current.state.value.loadingOlder) return
        navigation { try { current.loadOlder() } catch (error: CancellationException) { throw error } catch (_: Exception) { } }
    }

    fun prompt(text: String, mode: String = "queue") {
        if (!writable()) return
        val reader = reader ?: return
        val route = commandRoute() ?: return
        if (text.isBlank() || mutable.value.submitting) return
        outbox.submit(route, text, mode, reader) { }
    }

    fun cancelSession() = sessionAction { it.cancel() }

    fun approveInteraction(eventId: String) = interactionAction { interactions.approve(eventId) }
    fun rejectInteraction(eventId: String, message: String = "在手机上拒绝") = interactionAction { interactions.reject(eventId, message) }
    fun submitAnswers(eventId: String, answers: List<InteractionBridge.AnsweredQuestion>) = interactionAction { interactions.answer(eventId, answers) }
    private fun interactionAction(block: suspend () -> Unit) {
        if (!writable()) return
        navigation {
            try { if (writable()) block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { home -> home.copy(interactionError = homeErrorCode(error)) } }
        }
    }

    fun renameSession(title: String) {
        val next = title.trim()
        if (next.isEmpty()) return
        sessionAction { it.rename(next) }
    }

    fun archiveSession() = sessionAction { it.archive() }

    fun forkSession() {
        val runtime = mutable.value.selectedRuntimeId
        sessionAction { reader ->
            val fork = reader.fork()
            mutable.update { home -> home.copy(selectedSessionId = fork) }
            val stamp = generation
            if (runtime != null) loadSessions(runtime, stamp)
            follow(runtime ?: return@sessionAction, fork, stamp, reopen = true)
        }
    }

    private fun sessionAction(block: suspend (DshSessionReader) -> Unit) {
        if (!writable()) return
        val current = reader ?: return
        val stamp = generation
        mutable.update { home -> home.copy(sessionActionError = null) }
        navigation {
            try {
                current(stamp)
                if (!writable()) return@navigation
                block(current)
                current(stamp)
                mutable.value.selectedRuntimeId?.let { loadSessions(it, stamp) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == stamp) mutable.update { home -> home.copy(sessionActionError = homeErrorCode(error)) }
            }
        }
    }

    fun openModelSelection() {
        val runtime = mutable.value.selectedRuntimeId ?: return
        mutable.update { home -> home.copy(modelSelectionOpen = true, modelBusy = true, modelError = null) }
        val stamp = generation
        navigation {
            val client = DshSessionClient(route(runtime))
            try {
                val catalog = client.modelCatalog()
                current(stamp)
                mutable.update { home -> home.copy(modelCatalog = catalog, modelBusy = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == stamp) mutable.update { home -> home.copy(modelBusy = false, modelError = homeErrorCode(error)) }
            }
        }
    }

    fun closeModelSelection() { mutable.update { home -> home.copy(modelSelectionOpen = false, modelError = null) } }

    fun selectModel(option: DshModelOption, reasoningEffort: String? = null) {
        closeModelSelection()
        sessionAction { it.selectModel(option.provider, option.model, reasoningEffort) }
    }

    fun openCreation() { mutable.update { home -> home.copy(creation = SessionCreationState()) } }
    fun closeCreation() { mutable.update { home -> home.copy(creation = null) } }

    /** Creates a session and follows it. Omitted fields leave dsh defaults in place. */
    fun createSession(workspaceId: String? = null, agentPreset: String? = null) {
        if (!writable()) return
        val runtime = mutable.value.selectedRuntimeId ?: return
        if (mutable.value.submitting) return
        val stamp = generation
        mutable.update { home -> home.copy(submitting = true, creation = SessionCreationState(SessionCreationPhase.CREATING)) }
        navigation {
            val client = DshSessionClient(route(runtime))
            try {
                val created = client.createSession(workspaceId?.takeIf { it.isNotBlank() }?.trim(), null, agentPreset?.takeIf { it.isNotBlank() }?.trim())
                current(stamp)
                loadSessions(runtime, stamp)
                mutable.update { home -> home.copy(submitting = false, creation = null) }
                follow(runtime, created, stamp, reopen = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == stamp) mutable.update { home -> home.copy(submitting = false, creation = SessionCreationState(SessionCreationPhase.FAILED, homeErrorCode(error))) }
            }
        }
    }

    /** Back moves one level: model sheet, creation, chat, then the runtime list. */
    fun back() {
        val current = mutable.value
        when {
            current.modelSelectionOpen -> closeModelSelection()
            current.creation != null -> closeCreation()
            current.selectedSessionId != null -> closeSession()
            current.selectedRuntimeId != null -> { retireSession(true); mutable.update { home -> home.copy(selectedRuntimeId = null, selectedSessionId = null, sessions = emptyList(), sessionError = null) } }
        }
    }
    fun foreground(active: Boolean) {
        if (foreground == active) return
        foreground = active
        mutable.update { it.copy(backgroundReadOnly = !active && backgroundHold) }
        if (!active) {
            val picker = files?.state?.value?.pickerActive == true
            drafts.clear(mutable.value.composer.takeIf { picker })
            outbox.cancelSending()
            navigationJob?.cancel(); navigationJob = null
            terminal?.close(); terminal = null
            if (!picker) { files?.clearImages(); files?.dismiss(); mutable.update { it.copy(composer = null, terminal = null) } }
            if (!backgroundHold) {
                retireWatcher()
                readerJob?.cancel(); readerJob = null
                reader?.close(); reader = null
                routes.close()
                mutable.update { it.copy(reader = null, loadingSessions = false, submitting = false) }
            } else mutable.update { it.copy(loadingSessions = false, submitting = false) }
        }
    }
    fun resumeSelection() {
        desired?.let { (runtime, session) ->
            mutable.update { home -> home.copy(selectedRuntimeId = runtime, selectedSessionId = session) }
            if (session != null) follow(runtime, session, generation, reopen = true) else openRuntime(runtime)
        }
    }

    /** Called only after HostCoordinator freshly establishes Relay authority for this owner. */
    suspend fun openReferencedSession(runtimeId: String, sessionId: String): Boolean {
        if (!foreground || mutable.value.phase != HomePhase.READY || mutable.value.lanOnly) return false
        val owner = account.localOwner() ?: return false
        if (mutable.value.presentationOwner?.fingerprint != owner.fingerprint || mutable.value.runtimes.none { it.id == runtimeId && it.access != null }) return false
        val stamp = generation
        val page = DshSessionClient(route(runtimeId)).sessions()
        current(stamp)
        if (!foreground || account.localOwner()?.fingerprint != owner.fingerprint || mutable.value.presentationOwner?.fingerprint != owner.fingerprint) return false
        val access = mutable.value.runtimes.firstOrNull { it.id == runtimeId }?.access ?: return false
        val rows = page.items.filter { it.viewScope(access, now()) }
        if (notificationReferenceExpiry?.let { now() >= it } == true) return false
        if (rows.none { it.id == sessionId }) return false
        retireSession(false)
        desired = runtimeId to sessionId
        mutable.update { it.copy(selectedRuntimeId = runtimeId, selectedSessionId = sessionId, sessions = rows, sessionError = null) }
        follow(runtimeId, sessionId, stamp, reopen = true)
        return true
    }
}
