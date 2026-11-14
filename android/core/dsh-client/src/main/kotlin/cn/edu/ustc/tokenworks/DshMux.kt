package cn.edu.ustc.tokenworks

import kotlinx.coroutines.channels.Channel
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class DshFailure(val code: String) : IllegalStateException(code)

/** A bounded, lossless native mux. A slow consumer explicitly fails the carrier;
 * the socket listener never blocks and never creates per-frame coroutines. */
class DshMux(private val writer: (String) -> Boolean, private val closer: (Int, String?) -> Unit,
    private val streamCapacity: Int = WireBudget.QUEUE_COUNT,
    private val streamByteCapacity: Int = WireBudget.QUEUE_BYTES,
    private val muxCapacity: Int = WireBudget.QUEUE_COUNT,
    private val muxByteCapacity: Int = WireBudget.QUEUE_BYTES,
    private val queuedWriteBytes: () -> Long = { 0L }, private val writeTimeoutMs: Long = 30_000) {
    internal class Inbox {
        data class Item(val frame: DshMuxFrame, val bytes: Int)
        val queue = ArrayDeque<Item>()
        val wake = Channel<Unit>(Channel.CONFLATED)
        var bytes = 0
        var terminal: DshMuxFrame? = null
        var terminalBytes = 0
        var uplinkEnded = false
        var finished = false
    }
    private val lock = Any()
    private var ended = false
    private var readOnlyEndpoints: Set<String>? = null
    private var nextId = 1L
    private val streams = linkedMapOf<String, Inbox>()
    private val draining = linkedSetOf<Inbox>()
    private var queuedCount = 0
    private var queuedBytes = 0
    private data class Write(var bytes: Long, val deadline: Long)
    private val writes = ArrayDeque<Write>()
    private var writeTimer: ScheduledFuture<*>? = null
    private var writeBytes = 0L

    private fun writeBacklog(watch: Boolean = true): Long {
        val actual = queuedWriteBytes().coerceAtLeast(0L)
        var drained = (writeBytes - actual).coerceAtLeast(0L)
        while (drained > 0 && writes.isNotEmpty()) {
            val first = writes.removeFirst()
            val used = minOf(first.bytes, drained)
            drained -= used; writeBytes -= used
            if (used < first.bytes) { first.bytes -= used; writes.addFirst(first) }
        }
        if (writes.isEmpty()) { writeTimer?.cancel(false); writeTimer = null }
        else if (watch) watchWrites()
        return actual
    }

    private fun watchWrites() {
        if (writeTimer != null || ended || writes.isEmpty()) return
        writeTimer = deadlines.schedule({
            synchronized(lock) {
                writeTimer = null
                if (!ended) {
                    writeBacklog(watch = false)
                    if (writes.isNotEmpty() && writes.first.deadline <= System.nanoTime()) protocolFailure("wire/slow-peer")
                    else watchWrites()
                }
            }
        }, maxOf(1L, writes.first.deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
    }
    private val codec: WireCodec = WireCodec(timeout = { protocolFailure("wire/fragment-timeout") })

    init { require(streamCapacity > 0 && streamByteCapacity > 0 && muxCapacity > 0 && muxByteCapacity > 0 && writeTimeoutMs > 0) }
    fun closed() = synchronized(lock) { ended }

    fun receive(text: String) = synchronized(lock) {
        if (ended) return@synchronized
        try {
            val decoded = codec.decode(text)
            val backlog = writeBacklog()
            if (queuedBytes + codec.bufferedBytes + backlog > muxByteCapacity ||
                (codec.bufferedBytes > 0 && queuedCount + writes.size >= muxCapacity)) {
                protocolFailure("wire/slow-peer")
                return@synchronized
            }
            if (decoded == null) return@synchronized
            val frame = DshStreams.decode(WireJson.parse(decoded))
            val target = streams[frame.streamId] ?: return@synchronized
            val bytes = decoded.toByteArray(Charsets.UTF_8).size
            if (frame !is DshMuxFrame.Item) {
                if (target.bytes + bytes > streamByteCapacity || queuedCount + writes.size >= muxCapacity ||
                    queuedBytes + bytes + backlog > muxByteCapacity) {
                    protocolFailure("wire/slow-peer")
                    return@synchronized
                }
                target.terminalBytes = bytes; queuedBytes += bytes; queuedCount++
                streams.remove(frame.streamId)
                target.terminal = frame
                target.finished = true
                target.wake.trySend(Unit)
                return@synchronized
            }
            if (target.queue.size >= streamCapacity || target.bytes + bytes > streamByteCapacity ||
                queuedCount + writes.size >= muxCapacity || queuedBytes + bytes + backlog > muxByteCapacity) {
                protocolFailure("wire/slow-peer")
                return@synchronized
            }
            target.queue.addLast(Inbox.Item(frame, bytes))
            target.bytes += bytes; queuedBytes += bytes; queuedCount++
            target.wake.trySend(Unit)
        } catch (error: Exception) {
            val code = error.message?.takeIf { it.startsWith("wire/") } ?: "wire/invalid-native-frame"
            protocolFailure(code)
        }
    }

    private fun protocolFailure(code: String): Unit = synchronized(lock) {
        if (ended) return@synchronized
        ended = true
        codec.close()
        writeTimer?.cancel(false); writeTimer = null; writes.clear(); writeBytes = 0
        for ((id, inbox) in streams) {
            inbox.terminal = DshMuxFrame.Error(id, DshRemoteFailure(code, code, jsonObject()))
            inbox.finished = true
            inbox.wake.trySend(Unit)
        }
        streams.clear()
        closer(if (code == "wire/slow-peer") 1013 else 1008, code)
    }

    /** Socket callbacks pass only a stable code, never an exception/body/token. */
    fun fail(code: String = "wire/disconnected") = synchronized(lock) {
        if (ended) return@synchronized
        val safeCode = code.takeIf { it.matches(Regex("wire/[a-z-]{1,48}")) } ?: "wire/disconnected"
        ended = true; codec.close()
        writeTimer?.cancel(false); writeTimer = null; writes.clear(); writeBytes = 0
        for ((id, inbox) in streams) {
            inbox.terminal = DshMuxFrame.Error(id, DshRemoteFailure(safeCode, safeCode, jsonObject()))
            inbox.finished = true; inbox.wake.trySend(Unit)
        }
        streams.clear()
    }

    private fun send(value: JsonObject) {
        if (ended) throw DshFailure("native mux closed")
        val text = try { WireJson.encode(value) } catch (error: ProtocolException) {
            throw DshFailure(error.message?.takeIf { it.startsWith("wire/") } ?: "wire/invalid-native-frame")
        }
        val size = text.toByteArray(Charsets.UTF_8).size
        val wireBytes = if (size <= WireBudget.FRAME_BYTES) size else
            4 * ((size + 2) / 3) + ((size + WireBudget.CHUNK_BYTES - 1) / WireBudget.CHUNK_BYTES) * 512
        if (writeBacklog() + queuedBytes + codec.bufferedBytes + wireBytes > muxByteCapacity ||
            writes.size + queuedCount + (if (codec.bufferedBytes > 0) 1 else 0) >= muxCapacity) {
            protocolFailure("wire/slow-peer")
            throw DshFailure("wire/slow-peer")
        }
        var sentBytes = 0L
        for (frame in codec.encode(text)) {
            if (!try { writer(frame) } catch (_: Exception) { false }) {
                protocolFailure("wire/write-failed")
                throw DshFailure("native mux closed")
            }
            sentBytes += frame.toByteArray(Charsets.UTF_8).size
        }
        writes.addLast(Write(sentBytes, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(writeTimeoutMs))); writeBytes += sentBytes
        writeBacklog()
    }

    /** Restriction is monotonic and must be applied before exposing a newly opened carrier. */
    fun restrictEndpoints(allowedEndpoints: Set<String>): DshMux = synchronized(lock) {
        if (ended) throw DshFailure("native mux closed")
        check(streams.isEmpty() && draining.isEmpty()) { "mux restriction requires an unused carrier" }
        readOnlyEndpoints = readOnlyEndpoints?.intersect(allowedEndpoints) ?: allowedEndpoints.toSet()
        this
    }

    fun open(endpoint: String, payload: JsonValue): DshStream = synchronized(lock) {
        if (ended) throw DshFailure("native mux closed")
        if (readOnlyEndpoints?.contains(endpoint) == false) throw DshFailure("connection/read-only-fallback")
        if (draining.size >= 128) throw DshFailure("wire/stream-limit")
        val id = (nextId++).toString()
        val inbox = Inbox()
        streams[id] = inbox; draining.add(inbox)
        try {
            send(DshStreams.encode(DshMuxRequest.Open(id, endpoint, payload)))
            DshStream(this, id, inbox)
        } catch (error: Exception) {
            streams.remove(id); draining.remove(inbox); inbox.finished = true; inbox.wake.close()
            throw error
        }
    }

    internal suspend fun next(inbox: Inbox): DshMuxFrame? {
        while (true) {
            synchronized(lock) {
                if (inbox.queue.isNotEmpty()) {
                    val item = inbox.queue.removeFirst()
                    inbox.bytes -= item.bytes; queuedBytes -= item.bytes; queuedCount--
                    return item.frame
                }
                inbox.terminal?.let {
                    if (inbox.terminalBytes > 0) { queuedBytes -= inbox.terminalBytes; queuedCount--; inbox.terminalBytes = 0 }
                    inbox.terminal = null
                    return it
                }
                if (inbox.finished) { draining.remove(inbox); inbox.wake.close(); return null }
            }
            inbox.wake.receiveCatching()
        }
    }

    internal fun cancel(streamId: String, inbox: Inbox) = synchronized(lock) {
        val active = streams.remove(streamId) != null
        queuedBytes -= inbox.bytes + inbox.terminalBytes
        queuedCount -= inbox.queue.size + if (inbox.terminalBytes > 0) 1 else 0
        inbox.queue.clear(); inbox.bytes = 0; inbox.terminalBytes = 0; inbox.terminal = null; inbox.finished = true
        draining.remove(inbox); inbox.wake.close()
        if (active && !ended) runCatching { send(DshStreams.encode(DshMuxRequest.Cancel(streamId))) }
    }
    internal fun item(streamId: String, value: DshOptional<JsonValue>) = synchronized(lock) {
        if (readOnlyEndpoints != null) throw DshFailure("connection/read-only-fallback")
        val inbox = streams[streamId] ?: throw DshFailure("wire/stream-closed")
        if (inbox.uplinkEnded) throw DshFailure("wire/uplink-ended")
        send(DshStreams.encode(DshMuxRequest.Item(streamId, value)))
    }
    internal fun endUplink(streamId: String) = synchronized(lock) {
        val inbox = streams[streamId] ?: return@synchronized
        if (!inbox.uplinkEnded) { send(DshStreams.encode(DshMuxRequest.End(streamId))); inbox.uplinkEnded = true }
    }

    fun close() = synchronized(lock) {
        if (!ended) {
            ended = true; codec.close()
            writeTimer?.cancel(false); writeTimer = null; writes.clear(); writeBytes = 0
            streams.values.forEach { it.finished = true; it.wake.trySend(Unit) }
            streams.clear(); closer(1000, null)
        }
    }

    companion object {
        private val deadlines = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "native-mux-deadlines").apply { isDaemon = true } }
    }
}

/** One logical native stream opened on a mux. Terminal frames drain before null. */
class DshStream internal constructor(private val mux: DshMux, val id: String, private val inbox: DshMux.Inbox) : AutoCloseable {
    suspend fun next(): DshMuxFrame? = mux.next(inbox)
    fun send(item: DshOptional<JsonValue>) = mux.item(id, item)
    fun end() = mux.endUplink(id)
    override fun close() = mux.cancel(id, inbox)
}
