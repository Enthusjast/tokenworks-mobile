package cn.edu.ustc.tokenworks

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Durable delivery intents are separate from foreground drafts. Recovery never sends a command. */
internal class OutboxCoordinator(private val owner: CoroutineScope, private val open: suspend () -> DshCommandOutbox) {
    data class State(val entries: List<PromptOutboxEntry> = emptyList(), val sending: String? = null, val error: String? = null)
    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable.asStateFlow()
    private val mutex = Mutex()
    private var commands: DshCommandOutbox? = null
    private var sending: Job? = null
    private suspend fun storage(): DshCommandOutbox = mutex.withLock {
        commands ?: open().also { opened ->
            commands = opened
            owner.launch { opened.snapshot.collect { entries -> mutable.update { it.copy(entries = entries) } } }
        }
    }
    fun load() { owner.launch { try { storage() } catch (error: Exception) { if (error is CancellationException) throw error; failure(error) } } }

    fun submit(route: DshCommandRoute, text: String, mode: String, reader: DshSessionReader, receipts: List<String> = emptyList(),
        images: List<NativeImagePart> = emptyList(), accepted: () -> Unit) = send {
        val entry = storage().prepare(route, text, mode, receipts, images)
        accepted()
        deliver(entry, reader)
    }
    fun retry(requestId: String, route: DshCommandRoute, reader: DshSessionReader) = send {
        storage().retry(requestId, route)?.let { deliver(it, reader) }
    }
    fun revisePending(requestId: String, route: DshCommandRoute, text: String? = null, mode: String? = null) { owner.launch {
        try { storage().editPendingText(requestId, route, text, mode) }
        catch (error: Exception) { if (error is CancellationException) throw error; failure(error) }
    } }
    fun abandon(requestId: String, route: DshCommandRoute) { owner.launch {
        try { storage().abandon(requestId, route) }
        catch (error: Exception) { if (error is CancellationException) throw error; failure(error) }
    } }
    suspend fun clearOwner(ownerId: String) { storage().clearOwner(ownerId) }
    suspend fun revokeRuntime(ownerId: String, hostId: String, runtimeId: String) { storage().revokeRuntime(ownerId, hostId, runtimeId) }
    fun cancelSending() { sending?.cancel() }

    private fun send(block: suspend () -> Unit) {
        if (sending?.isCompleted == false) return
        val job = owner.launch(start = CoroutineStart.LAZY) {
            mutable.update { it.copy(error = null, sending = "preparing") }
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { failure(error) }
            finally { mutable.update { it.copy(sending = null) } }
        }
        sending = job
        job.start()
    }
    private suspend fun deliver(entry: PromptOutboxEntry, reader: DshSessionReader) {
        require(entry.route.sessionId == reader.sessionId)
        val store = storage()
        val sendingEntry = store.markSending(entry.requestId, entry.route) ?: return
        mutable.update { it.copy(sending = entry.requestId) }
        try {
            reader.prompt(sendingEntry.text, sendingEntry.requestId, sendingEntry.mode, sendingEntry.receipts, sendingEntry.images)
            store.settle(entry.requestId, entry.route)
        } catch (error: Exception) {
            withContext(NonCancellable) {
                if (DshRecoveryPolicy.classify(error).refused) store.markRefused(entry.requestId, entry.route)
                else store.markUnknown(entry.requestId, entry.route)
            }
            throw error
        }
    }
    private fun failure(error: Exception) { mutable.update { it.copy(error = when (error) {
        is OutboxException -> "outbox.${error.failure.name.lowercase()}"
        else -> DshRecoveryPolicy.classify(error).code
    }) } }
}
