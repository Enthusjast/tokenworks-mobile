package cn.edu.ustc.tokenworks

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * F-04: one long-lived `$events` watcher per runtime. Opens its own mux,
 * feeds the bridge, and reconnects with backoff while alive. The UI observes
 * [interactions]; answers go through the bridge.
 */
class InteractionWatcher(
    private val api: RemoteApi,
    private val runtimeId: String,
    owner: CoroutineScope,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val scope = CoroutineScope(SupervisorJob(owner.coroutineContext[Job]) + Dispatchers.Default)
    private val mutable = MutableStateFlow<List<PendingInteraction>>(emptyList())
    val interactions: StateFlow<List<PendingInteraction>> = mutable.asStateFlow()
    private val mutableErrors = MutableStateFlow<String?>(null)
    val errors: StateFlow<String?> = mutableErrors.asStateFlow()
    private val mutablePhase = MutableStateFlow(Phase.CONNECTING)
    val phase: StateFlow<Phase> = mutablePhase.asStateFlow()

    enum class Phase { CONNECTING, ONLINE, BACKOFF, CLOSED }

    private var mux: DshMux? = null
    private var events: DshEvents? = null
    private var bridge: NativeInteractionBridge? = null

    init {
        scope.launch { loop() }
    }

    private suspend fun loop() {
        var attempt = 0
        while (!closed.get() && scope.isActive) {
            try {
                mutablePhase.value = if (attempt == 0) Phase.CONNECTING else Phase.BACKOFF
                val socket = api.openMux(runtimeId, api.nativeLease(runtimeId))
                mux = socket
                val stream = socket.open(DSH_EVENTS_ENDPOINT, jsonObject("args" to jsonObject()))
                val watcher = this
                val events = DshEvents(stream, readyTimeoutMillis = 10_000, answer = { },
                    onBroken = { watcher.fail(it) })
                this.events = events
                val bridge = NativeInteractionBridge(runtimeId, events, { endpoint, args ->
                    api.native(endpoint, args, runtimeId, api.nativeLease(runtimeId))
                })
                this.bridge = bridge
                mutablePhase.value = Phase.ONLINE
                attempt = 0
                while (!closed.get() && events.clientIdOrNull() != null) {
                    bridge.refresh()
                    mutable.value = bridge.interactions.value
                    mutableErrors.value = bridge.errors.value
                    delay(150)
                }
                if (!closed.get()) throw DshFailure("native events stream ended")
            } catch (cancelled: CancellationException) {
                return
            } catch (error: Exception) {
                fail(if (error is DshFailure) error.code else "relay.disconnected")
                delay(minOf(30_000L, 1_000L shl minOf(attempt++, 5)))
            } finally {
                runCatching { events?.close() }; events = null; bridge = null
                runCatching { mux?.close() }; mux = null
            }
        }
    }

    private fun fail(code: String) {
        if (closed.get()) return
        mutableErrors.value = code
        mutablePhase.value = Phase.BACKOFF
    }

    suspend fun approve(eventId: String) = bridge?.approve(eventId) ?: throw DshFailure("native events not ready")
    suspend fun reject(eventId: String, message: String) = bridge?.reject(eventId, message) ?: throw DshFailure("native events not ready")
    suspend fun submitAnswers(eventId: String, answers: List<InteractionBridge.AnsweredQuestion>) =
        bridge?.submitAnswers(eventId, answers) ?: throw DshFailure("native events not ready")

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        mutablePhase.value = Phase.CLOSED
        runCatching { events?.close() }
        runCatching { mux?.close() }
        scope.cancel()
    }
}
