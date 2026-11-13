package cn.edu.ustc.tokenworks

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** One pending waterfall the caller must answer or reject; cancellation retires it. */
sealed interface DshWaterfall {
    val event: String
    val eventId: String
    val agentId: String
    val request: JsonObject

    data class Open(override val event: String, override val eventId: String,
        override val agentId: String, override val request: JsonObject) : DshWaterfall
    data class Cancelled(override val event: String, override val eventId: String,
        override val agentId: String, override val request: JsonObject) : DshWaterfall
}

/**
 * One `$events` stream on a native mux. The caller feeds mux frames in from the
 * socket; answers go through the caller-supplied `$events/result` transport so
 * policy (lease, signing) stays with the connection owner.
 */
class DshEvents internal constructor(
    private val stream: DshStream,
    private val readyTimeoutMillis: Long,
    private val answer: (DshEventResult) -> Unit,
    private val onBroken: (String) -> Unit = {},
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val pending = java.util.concurrent.ConcurrentHashMap<String, DshWaterfall>()
    private val mutable = java.util.concurrent.ConcurrentLinkedDeque<DshWaterfall>()
    private val ready = java.util.concurrent.CountDownLatch(1)
    @Volatile private var clientId: String? = null
    @Volatile private var broken: String? = null

    init {
        thread(name = "dsh-events-pump", isDaemon = true) { pump() }
        if (!ready.await(readyTimeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            close()
            throw DshFailure(broken ?: "native events not ready")
        }
    }

    private fun pump() {
        try {
            while (!closed.get()) {
                when (val frame = kotlinx.coroutines.runBlocking { stream.next() } ?: return) {
                    is DshMuxFrame.Item -> {
                        val value = (frame.value as? DshOptional.Value)?.value as? JsonObject ?: continue
                        val event = runCatching { DshStreams.event(value) }.getOrNull() ?: continue
                        when (event) {
                            is DshEventFrame.Ready -> { clientId = event.clientId; ready.countDown() }
                            is DshEventFrame.Waterfall -> {
                                pending[event.eventId] = DshWaterfall.Open(event.event, event.eventId, event.agentId, event.request)
                                mutable.addLast(pending[event.eventId]!!); publish()
                            }
                            is DshEventFrame.Cancel -> {
                                val removed = pending.remove(event.eventId)
                                if (removed != null) {
                                    mutable.remove(removed)
                                    mutable.addLast(DshWaterfall.Cancelled(removed.event, removed.eventId, removed.agentId, removed.request))
                                    publish()
                                }
                            }
                            is DshEventFrame.Emit -> Unit
                        }
                    }
                    is DshMuxFrame.Error -> { fail(frame.error.code); return }
                    is DshMuxFrame.End -> { fail("native events stream ended"); return }
                }
            }
        } catch (_: InterruptedException) {
            // close() interrupted the pump; nothing to clean up beyond what close did.
        } catch (error: Exception) {
            fail(error.message ?: "native events pump failed")
        }
    }

    private fun fail(code: String) {
        broken = code
        ready.countDown()
        pending.clear(); publish()
        onBroken(code)
    }

    private fun publish() { mutable.removeIf { it is DshWaterfall.Open && !pending.containsKey(it.eventId) } }

    /** `$events` ready clientId; non-null after construction succeeded. */
    fun clientIdOrNull(): String? = clientId

    /** Test/diagnostic view of the raw pending map. */
    internal fun pendingEvents(): Set<String> = pending.keys

    /** Open waterfalls awaiting an answer, newest last. */
    fun open(): List<DshWaterfall.Open> = pending.values.mapNotNull { it as? DshWaterfall.Open }

    /** Cancellations not yet observed by the UI; each is delivered once. */
    fun cancellations(): List<DshWaterfall.Cancelled> {
        val seen = mutableListOf<DshWaterfall.Cancelled>()
        while (true) {
            val head = mutable.peekFirst() ?: break
            if (head is DshWaterfall.Cancelled) { mutable.removeFirst(); seen.add(head) } else break
        }
        return seen
    }

    fun answer(eventId: String, outcome: DshEventOutcome) {
        val current = pending[eventId] ?: throw DshFailure("native events unknown waterfall")
        val id = clientId ?: throw DshFailure("native events not ready")
        answer(DshEventResult(id, eventId, outcome))
        pending.remove(eventId)
        mutable.remove(current)
    }

    fun reject(eventId: String, name: String, message: String, code: String? = null) =
        answer(eventId, DshEventOutcome.Rejected(name, message, code))

    fun next(eventId: String) = answer(eventId, DshEventOutcome.Next)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ready.countDown()
        pending.clear()
        stream.close()
        onBroken("native events closed")
    }
}
