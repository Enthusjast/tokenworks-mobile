package cn.edu.ustc.tokenworks

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** One route-bound native events subscription; terminal authorization failures never reconnect. */
class InteractionWatcher(
    private val transport: DshTransport,
    owner: CoroutineScope,
    private val readyTimeoutMillis: Long = 10_000,
    private val sessionId: String? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val scope = CoroutineScope(owner.coroutineContext + SupervisorJob(owner.coroutineContext[Job]))
    private val mutable = MutableStateFlow<List<PendingInteraction>>(emptyList())
    val interactions: StateFlow<List<PendingInteraction>> = mutable.asStateFlow()
    private val mutableErrors = MutableStateFlow<String?>(null)
    val errors: StateFlow<String?> = mutableErrors.asStateFlow()
    private val mutablePhase = MutableStateFlow(Phase.CONNECTING)
    val phase: StateFlow<Phase> = mutablePhase.asStateFlow()
    enum class Phase { CONNECTING, ONLINE, BACKOFF, CLOSED }
    @Volatile private var bridge: NativeInteractionBridge? = null
    @Volatile private var mux: DshMux? = null
    @Volatile private var events: DshEvents? = null
    @Volatile var connectionGeneration: Long? = null
        private set

    init {
        scope.launch {
            transport.state.collect { state ->
                if (state.phase in terminalPhases) {
                    mutableErrors.value = state.error ?: "interaction.transport_unavailable"
                    close()
                }
            }
        }
        scope.launch { loop() }
    }

    private suspend fun loop() {
        var attempt = 0
        while (!closed.get() && scope.isActive) {
            var connection: DshConnection? = null
            try {
                mutablePhase.value = if (attempt == 0) Phase.CONNECTING else Phase.BACKOFF
                val bound = transport.connect()
                connection = bound
                val socket = bound.openMux()
                mux = socket
                val subscription = DshEvents(socket.open(DSH_EVENTS_ENDPOINT, jsonObject("args" to jsonObject())), scope,
                    // native() already validates the RPC envelope. This endpoint has no success value.
                    answer = { result -> bound.native(DSH_EVENTS_RESULT_ENDPOINT, DshStreams.result(result)); Unit },
                    readyTimeoutMillis = readyTimeoutMillis)
                events = subscription
                subscription.awaitReady()
                val current = NativeInteractionBridge(subscription, socket, bound::native, scope, sessionId = sessionId)
                bridge = current
                connectionGeneration = subscription.generation
                mutablePhase.value = Phase.ONLINE
                mutableErrors.value = null
                attempt = 0
                coroutineScope {
                    launch { current.interactions.collect { mutable.value = it } }
                    launch { current.errors.collect { if (it != null) mutableErrors.value = it } }
                    val code = subscription.awaitClosed()
                    mutable.value = emptyList()
                    throw DshFailure(code)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableErrors.value = (error as? DshFailure)?.code ?: "interaction.disconnected"
                mutablePhase.value = Phase.BACKOFF
            } finally {
                connectionGeneration = null
                bridge?.close(); bridge = null
                events?.close(); events = null
                mux?.close(); mux = null
                connection?.close()
                mutable.value = emptyList()
            }
            if (!closed.get()) delay(minOf(30_000L, 1_000L shl minOf(attempt++, 5)))
        }
    }

    fun requirePendingApproval(eventId: String): PendingInteraction.Approval = current().requirePendingApproval(eventId)

    suspend fun approve(eventId: String) = current().approve(eventId)
    suspend fun reject(eventId: String, message: String) = current().reject(eventId, message)
    suspend fun submitAnswers(eventId: String, answers: List<InteractionBridge.AnsweredQuestion>) = current().submitAnswers(eventId, answers)

    /** Caller must pass the generation captured when its native projection subscription opened. */
    fun updateUserQuestions(generation: Long, agentId: String, projection: JsonObject) {
        if (generation == connectionGeneration && !closed.get()) bridge?.updateUserQuestions(agentId, projection)
    }

    private fun current(): NativeInteractionBridge {
        if (closed.get() || transport.state.value.phase in terminalPhases) throw DshFailure("interaction.stale")
        return bridge ?: throw DshFailure("native.events.not_ready")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        connectionGeneration = null
        mutablePhase.value = Phase.CLOSED
        bridge?.close(); events?.close(); mux?.close()
        mutable.value = emptyList()
        scope.cancel()
    }

    private companion object {
        val terminalPhases = setOf(DshTransportPhase.REVOKED, DshTransportPhase.AUTH_REQUIRED,
            DshTransportPhase.UNSUPPORTED, DshTransportPhase.CLOSED)
    }
}
