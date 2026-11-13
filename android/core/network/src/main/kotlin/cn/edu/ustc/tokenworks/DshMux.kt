package cn.edu.ustc.tokenworks

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Short-lived Relay lease; the secret is only ever sent to Relay endpoints. */
class DshLease(val token: String, val id: String, val expiresAt: Long) {
    fun usable(now: Long = System.currentTimeMillis()) = now + 30_000 < expiresAt
    override fun toString() = "DshLease($id)"
}

class DshFailure(val code: String) : IllegalStateException(code)

/**
 * One native WebSocket carrying logical streams. Framing only: the caller owns
 * the socket and hands text messages in. OkHttp's send is thread-safe.
 */
class DshMux internal constructor(private val writer: (String) -> Boolean, private val closer: (Int, String?) -> Unit,
    /** F-06: per-stream frame budget; consumption slower than arrival cannot grow memory. */
    private val streamCapacity: Int = 256) {
    private val ended = AtomicBoolean()
    private val nextId = AtomicLong(1)
    private val streams = ConcurrentHashMap<String, Channel<DshMuxFrame>>()

    fun closed() = ended.get()

    internal fun receive(text: String) {
        val frame = runCatching { DshStreams.decode(JsonDocument.parse(text).root) }.getOrNull() ?: return
        val target = streams[frame.streamId] ?: return
        if (frame !is DshMuxFrame.Item) { streams.remove(frame.streamId); target.trySend(frame); return }
        // F-06: a bounded stream admits the frame unless full. Assistant stream
        // chunks are replaceable - the oldest buffered chunk yields to the newest.
        // Durable frames (snapshot/event/checkpoint/page) are never dropped: the
        // caller blocks, applying backpressure to the socket read loop.
        if (target.trySend(frame).isSuccess) return
        if (replaceable(frame)) { target.tryReceive(); target.trySend(frame) }
        else kotlinx.coroutines.runBlocking { target.send(frame) }
    }

    /** Assistant stream frames can be collapsed to the newest without losing facts. */
    private fun replaceable(frame: DshMuxFrame.Item): Boolean {
        val payload = (frame.value as? DshOptional.Value)?.value as? JsonObject ?: return false
        return payload.fields["type"] == JsonString("assistant-stream")
    }
    internal fun fail() {
        ended.set(true)
        streams.values.forEach { it.close() }
        streams.clear()
    }

    private fun send(value: JsonObject) {
        if (ended.get()) throw DshFailure("native mux closed")
        if (!writer(JsonDocument.of(value).canonicalText())) throw DshFailure("native mux closed")
    }

    fun open(endpoint: String, payload: JsonValue): DshStream {
        val streamId = nextId.getAndIncrement().toString()
        val channel = Channel<DshMuxFrame>(streamCapacity)
        streams[streamId] = channel
        try {
            send(DshStreams.encode(DshMuxRequest.Open(streamId, endpoint, payload)))
            return DshStream(this, streamId, channel)
        } catch (error: Exception) {
            streams.remove(streamId)
            throw error
        }
    }

    internal fun cancel(streamId: String) {
        if (streams.remove(streamId) != null) runCatching { send(DshStreams.encode(DshMuxRequest.Cancel(streamId))) }
    }
    internal fun item(streamId: String, value: DshOptional<JsonValue>) = send(DshStreams.encode(DshMuxRequest.Item(streamId, value)))
    internal fun endUplink(streamId: String) = runCatching { send(DshStreams.encode(DshMuxRequest.End(streamId))) }

    fun close() {
        if (ended.compareAndSet(false, true)) {
            streams.values.forEach { it.close() }
            streams.clear()
            closer(1000, null)
        }
    }
}

/** One logical native stream opened on a mux. */
class DshStream internal constructor(private val mux: DshMux, val id: String, private val frames: Channel<DshMuxFrame>) : AutoCloseable {
    suspend fun next(): DshMuxFrame? = frames.receiveCatching().getOrNull()
    fun send(item: DshOptional<JsonValue>) = mux.item(id, item)
    fun end() = mux.endUplink(id)
    override fun close() = mux.cancel(id)
}
