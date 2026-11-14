package cn.edu.ustc.tokenworks

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

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

    fun start(transport: DshTransport) {
        stop()
        val next = InteractionWatcher(transport, owner)
        watcher = next
        collection = owner.launch {
            combine(next.interactions, next.errors, next.phase) { interactions, error, phase ->
                State(interactions, error, phase, next.connectionGeneration)
            }.collect { if (watcher === next) mutable.value = it }
        }
        projections = owner.launch {
            state.map { it.generation }.distinctUntilChanged().collectLatest { generation ->
                if (generation == null) return@collectLatest
                val feed = SessionProjectionFeed(transport, this) { sessionId, value ->
                    if (watcher === next) next.updateUserQuestions(generation, sessionId, value)
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
        mutable.value = State()
    }

    suspend fun approve(key: String) = action { it.approve(key) }
    suspend fun reject(key: String, message: String = "device_declined") = action { it.reject(key, message) }
    suspend fun answer(key: String, answers: List<InteractionBridge.AnsweredQuestion>) = action { it.submitAnswers(key, answers) }

    fun updateUserQuestions(generation: Long, agentId: String, projection: JsonObject) {
        watcher?.updateUserQuestions(generation, agentId, projection)
    }

    private suspend fun action(block: suspend (InteractionWatcher) -> Unit) {
        val current = watcher ?: throw DshFailure("interaction.stale")
        try { block(current) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (watcher === current) mutable.value = mutable.value.copy(error = (error as? DshFailure)?.code ?: "interaction.result_unconfirmed")
            throw error
        }
    }

    override fun close() = stop()
}
