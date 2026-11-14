package cn.edu.ustc.tokenworks

import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** One in-flight carrier message, strictly contiguous and monotonically identified. */
class WireCodec(private val timeoutMs: Long = 30_000, private val timeout: () -> Unit = {}) {
    private data class Pending(val id: String, val count: Int, val bytes: Int, val checksum: String,
        val payload: ByteArray = ByteArray(bytes), var index: Int = 0, var size: Int = 0)
    private var outgoingId = 0L
    private var incomingId = 0L
    private var pending: Pending? = null
    private var timer: ScheduledFuture<*>? = null
    private var closed = false
    val bufferedBytes: Int @Synchronized get() = pending?.bytes ?: 0

    init { require(timeoutMs > 0) }

    @Synchronized private fun clear() { timer?.cancel(false); timer = null; pending = null }
    @Synchronized fun close() { closed = true; clear() }

    @Synchronized fun encode(text: String): Sequence<String> {
        if (closed) throw ProtocolException("wire/closed")
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > WireBudget.LOGICAL_BYTES) throw ProtocolException("wire/logical-limit")
        if (bytes.size <= WireBudget.FRAME_BYTES) return sequenceOf(text)
        val id = (++outgoingId).toString()
        val count = (bytes.size + WireBudget.CHUNK_BYTES - 1) / WireBudget.CHUNK_BYTES
        val checksum = digest(bytes)
        return (0 until count).asSequence().map { index ->
            JsonDocument.of(jsonObject("type" to "wire.fragment".json(), "id" to id.json(),
                "index" to index.toLong().json(), "count" to count.toLong().json(), "bytes" to bytes.size.toLong().json(),
                "sha256" to checksum.json(), "data" to Base64.getEncoder().encodeToString(
                    bytes.copyOfRange(index * WireBudget.CHUNK_BYTES, minOf(bytes.size, (index + 1) * WireBudget.CHUNK_BYTES))).json())).canonicalText()
        }
    }

    @Synchronized fun decode(text: String): String? {
        try {
            if (closed) throw ProtocolException("wire/closed")
            if (text.toByteArray(Charsets.UTF_8).size > WireBudget.FRAME_BYTES) throw ProtocolException("wire/frame-limit")
            val frame = WireJson.parse(text)
            if (frame.string("type") != "wire.fragment") {
                if (pending != null) throw ProtocolException("wire/interrupted")
                return text
            }
            frame.exactKeys("type", "id", "index", "count", "bytes", "sha256", "data")
            val id = frame.string("id")
            val bytes = frame.integer("bytes", WireBudget.FRAME_BYTES + 1L, WireBudget.LOGICAL_BYTES.toLong()).toInt()
            val count = frame.integer("count", 3, 32).toInt()
            val index = frame.integer("index", 0, count - 1L).toInt()
            val checksum = frame.string("sha256")
            val data = frame.string("data")
            if (count != (bytes + WireBudget.CHUNK_BYTES - 1) / WireBudget.CHUNK_BYTES ||
                !checksum.matches(Regex("[a-f0-9]{64}")) || data.length > 4 * ((WireBudget.CHUNK_BYTES + 2) / 3)) throw ProtocolException("wire/invalid-fragment")
            if (pending == null) {
                if (index != 0 || id != (incomingId + 1).toString()) throw ProtocolException("wire/fragment-order")
                val assembly = Pending(id, count, bytes, checksum)
                pending = assembly
                timer = scheduler.schedule({
                    val expired = synchronized(this) { if (pending !== assembly) false else { close(); true } }
                    if (expired) timeout()
                }, timeoutMs, TimeUnit.MILLISECONDS)
            }
            val state = pending!!
            if (state.id != id || state.count != count || state.bytes != bytes || state.checksum != checksum || state.index != index) throw ProtocolException("wire/fragment-order")
            val part = Base64.getDecoder().decode(data)
            if (Base64.getEncoder().encodeToString(part) != data || part.size != minOf(WireBudget.CHUNK_BYTES, bytes - index * WireBudget.CHUNK_BYTES)) throw ProtocolException("wire/invalid-fragment")
            part.copyInto(state.payload, state.size); state.size += part.size; state.index++
            if (state.index != count) return null
            val payload = state.payload
            if (digest(payload) != checksum) throw ProtocolException("wire/checksum")
            val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload)).toString()
            if (WireJson.parse(decoded).string("type") == "wire.fragment") throw ProtocolException("wire/nested-fragment")
            incomingId++; clear()
            return decoded
        } catch (error: Exception) {
            close()
            throw if (error is ProtocolException) error else ProtocolException("wire/invalid-frame")
        }
    }

    companion object {
        private fun digest(bytes: ByteArray): String {
            val alphabet = "0123456789abcdef"
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return buildString(64) { for (byte in digest) { val value = byte.toInt() and 255; append(alphabet[value ushr 4]); append(alphabet[value and 15]) } }
        }
        private val scheduler = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "native-wire-deadlines").apply { isDaemon = true } }
    }
}
