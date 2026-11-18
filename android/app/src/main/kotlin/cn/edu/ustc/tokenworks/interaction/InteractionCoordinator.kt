package cn.edu.ustc.tokenworks

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select

/** Controller-facing lifecycle: route replacement immediately retires every old action key. */
class InteractionCoordinator(private val owner: CoroutineScope) : AutoCloseable {
    data class State(
        val interactions: List<PendingInteraction> = emptyList(),
        val error: String? = null,
        val phase: InteractionWatcher.Phase = InteractionWatcher.Phase.CLOSED,
        val generation: Long? = null,
    )
    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable.asStateFlow()
    private var watcher: InteractionWatcher? = null
    private var collection: Job? = null
    private var projections: Job? = null
    private var selectedSession: String? = null
    private val authenticating = MutableStateFlow<String?>(null)

    fun start(transport: DshTransport, sessionId: String? = null) {
        stop()
        val next = InteractionWatcher(transport, owner, sessionId = sessionId)
        watcher = next
        selectedSession = sessionId
        collection = owner.launch {
            combine(next.interactions, next.errors, next.phase, authenticating) { interactions, error, phase, verifying ->
                val visible = if (verifying == null) interactions else interactions.map {
                    if (it is PendingInteraction.Approval && it.eventId == verifying && !it.attempted)
                        it.copy(busy = true, verifyingBiometric = true) else it
                }
                State(visible, error, phase, next.connectionGeneration)
            }.collect { if (watcher === next) mutable.value = it }
        }
        projections = owner.launch {
            state.map { it.generation }.distinctUntilChanged().collectLatest { generation ->
                if (generation == null) return@collectLatest
                val feed = SessionProjectionFeed(transport, this) { agentId, value ->
                    if (watcher === next && (sessionId == null || sessionId == agentId)) next.updateUserQuestions(generation, agentId, value)
                }
                try {
                    feed.error.collect { error ->
                        if (error != null && watcher === next) mutable.value = mutable.value.copy(error = error)
                    }
                } finally { feed.close() }
            }
        }
    }

    fun stop() {
        projections?.cancel(); projections = null
        collection?.cancel(); collection = null
        watcher?.close(); watcher = null
        selectedSession = null
        authenticating.value = null
        mutable.value = State()
    }

    internal suspend fun approve(key: String, sessionId: String, verify: suspend () -> Unit, isCurrent: () -> Boolean = { true }) =
        action(key, sessionId) { current ->
            val pending = current.requirePendingApproval(key)
            val generation = current.connectionGeneration ?: throw DshFailure("interaction.stale")
            if (!isCurrent() || !authenticating.compareAndSet(null, key)) throw DshFailure("interaction.stale")
            try {
                coroutineScope {
                    val retired = async(start = CoroutineStart.UNDISPATCHED) {
                        current.interactions.first { items -> items.none { it.eventId == key && it.agentId == sessionId } }
                    }
                    val verification = async { withTimeoutOrNull(30_000) { verify(); true } ?: false }
                    try {
                        val verified = select<Boolean> {
                            retired.onAwait { throw DshFailure("interaction.stale") }
                            verification.onAwait { it }
                        }
                        if (!verified) throw DshFailure("interaction.biometric_cancelled")
                    } finally { retired.cancel(); verification.cancel() }
                }
                if (watcher !== current || current.connectionGeneration != generation || !isCurrent() ||
                    current.requirePendingApproval(key) != pending
                ) throw DshFailure("interaction.stale")
                current.approve(key)
            } finally { authenticating.compareAndSet(key, null) }
        }

    suspend fun reject(key: String, message: String = "device_declined", sessionId: String? = null) =
        action(key, sessionId) { it.reject(key, message) }
    suspend fun answer(key: String, answers: List<InteractionBridge.AnsweredQuestion>, sessionId: String? = null) =
        action(key, sessionId) { it.submitAnswers(key, answers) }

    fun updateUserQuestions(generation: Long, agentId: String, projection: JsonObject) {
        if (selectedSession == null || selectedSession == agentId) watcher?.updateUserQuestions(generation, agentId, projection)
    }

    private suspend fun action(key: String, sessionId: String?, block: suspend (InteractionWatcher) -> Unit) {
        val current = watcher ?: throw DshFailure("interaction.stale")
        val scopedSession = selectedSession ?: sessionId
        try {
            if ((sessionId != null && selectedSession != null && selectedSession != sessionId) ||
                current.interactions.value.none { it.eventId == key && (scopedSession == null || it.agentId == scopedSession) } ||
                authenticating.value == key
            ) throw DshFailure("interaction.stale")
            mutable.update { it.copy(error = null) }
            block(current)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (watcher === current) mutable.update { it.copy(error = (error as? DshFailure)?.code ?: "interaction.result_unconfirmed") }
            throw error
        }
    }

    override fun close() = stop()
}
