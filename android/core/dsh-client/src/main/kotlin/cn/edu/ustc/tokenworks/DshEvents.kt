package cn.edu.ustc.tokenworks

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The key binds UI actions to the original stream generation, client and event. */
data class DshWaterfall(
    val key: String, val clientId: String, val generation: Long,
    val event: String, val eventId: String, val agentId: String, val request: JsonObject,
    val attempted: Boolean = false, val busy: Boolean = false, val error: String? = null,
)

/** Coroutine-owned `$events` subscription. An attempted result is never replayed. */
class DshEvents internal constructor(
    private val stream: DshStream,
    owner: CoroutineScope,
    private val answer: suspend (DshEventResult) -> Unit,
    private val readyTimeoutMillis: Long = 10_000,
) : AutoCloseable {
    val generation = generations.incrementAndGet()
    private val lock = Any()
    private val scope = CoroutineScope(owner.coroutineContext + SupervisorJob(owner.coroutineContext[Job]))
    private val mutable = MutableStateFlow<List<DshWaterfall>>(emptyList())
    val pending: StateFlow<List<DshWaterfall>> = mutable.asStateFlow()
    private val ready = CompletableDeferred<String>()
    private val ended = CompletableDeferred<String>()
    private val sends = mutableMapOf<String, Deferred<Unit>>()
    private val seen = mutableSetOf<String>()
    private var clientId: String? = null
    private var closed = false

    init { scope.launch { pump() } }

    suspend fun awaitReady(): String = try {
        withTimeout(readyTimeoutMillis) { ready.await() }
    } catch (error: TimeoutCancellationException) {
        finish("native.events.ready_timeout")
        throw DshFailure("native.events.ready_timeout")
    }

    suspend fun awaitClosed(): String = ended.await()

    private suspend fun pump() {
        try {
            while (true) {
                when (val frame = stream.next() ?: break) {
                    is DshMuxFrame.Item -> {
                        val value = (frame.value as? DshOptional.Value)?.value as? JsonObject ?: throw ProtocolException()
                        when (val event = DshStreams.event(value)) {
                            is DshEventFrame.Ready -> synchronized(lock) {
                                if (closed || clientId != null) throw ProtocolException()
                                clientId = event.clientId
                                ready.complete(event.clientId)
                            }
                            is DshEventFrame.Waterfall -> synchronized(lock) {
                                val client = clientId ?: throw ProtocolException()
                                if (!closed && seen.add(event.eventId)) mutable.value = mutable.value + DshWaterfall(
                                    "$generation:$client:${event.eventId}", client, generation,
                                    event.event, event.eventId, event.agentId, event.request)
                            }
                            is DshEventFrame.Cancel -> synchronized(lock) {
                                mutable.value.filter { it.eventId == event.eventId }.forEach { retire(it.key) }
                            }
                            is DshEventFrame.Emit -> Unit
                        }
                    }
                    is DshMuxFrame.Error -> { finish(frame.error.code); return }
                    is DshMuxFrame.End -> break
                }
            }
            finish("native.events.eof")
        } catch (cancelled: CancellationException) {
            finish("native.events.closed")
        } catch (_: Exception) {
            finish("native.events.invalid")
        }
    }

    suspend fun answer(key: String, outcome: DshEventOutcome) = coroutineScope {
        val send = synchronized(lock) {
            val current = mutable.value.firstOrNull { it.key == key } ?: throw DshFailure("interaction.stale")
            if (closed || current.attempted) throw DshFailure("interaction.already_attempted")
            val job = async(start = CoroutineStart.LAZY) {
                answer(DshEventResult(current.clientId, current.eventId, outcome))
            }
            sends[key] = job
            mutable.value = mutable.value.map { if (it.key == key) it.copy(attempted = true, busy = true) else it }
            job
        }
        try {
            send.start()
            send.await()
            synchronized(lock) {
                if (mutable.value.none { it.key == key }) throw DshFailure("interaction.stale")
                mutable.value = mutable.value.filterNot { it.key == key }
            }
        } catch (error: Exception) {
            synchronized(lock) {
                mutable.value = mutable.value.map {
                    if (it.key == key) it.copy(busy = false, error = "interaction.result_unconfirmed") else it
                }
            }
            throw error
        } finally { synchronized(lock) { sends.remove(key) } }
    }

    fun expire(key: String) = synchronized(lock) { retire(key) }

    private fun retire(key: String) {
        mutable.value = mutable.value.filterNot { it.key == key }
        sends.remove(key)?.cancel(CancellationException("interaction.stale"))
    }

    private fun finish(code: String) = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        clientId = null
        mutable.value = emptyList()
        sends.values.forEach { it.cancel(CancellationException("interaction.stale")) }
        sends.clear()
        if (!ready.isCompleted) ready.completeExceptionally(DshFailure(code))
        ended.complete(code)
        stream.close()
        scope.cancel()
    }

    override fun close() { finish("native.events.closed") }

    private companion object { val generations = AtomicLong() }
}
