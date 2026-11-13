package cn.edu.ustc.tokenworks

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One Host-side terminal as advertised by `terminal/create` / `terminal/list`. */
data class TerminalInfo(
    val id: String, val title: String, val shellPath: String, val cwd: String,
    val cols: Int, val rows: Int, val state: String, val exitCode: Int?, val error: String?,
) {
    val running get() = state == "running"
}

/** One attach frame: a full snapshot, incremental output, or a state transition. */
sealed class TerminalFrame {
    abstract val sequence: Long

    data class Snapshot(override val sequence: Long, val screen: String, val info: TerminalInfo, val writable: Boolean) : TerminalFrame()
    data class Output(override val sequence: Long, val data: String) : TerminalFrame()
    data class State(override val sequence: Long, val info: TerminalInfo) : TerminalFrame()
}

/** Uplink frames: bounded `write` chunks and `resize`. */
sealed class TerminalInput {
    abstract val sequence: Long

    data class Write(override val sequence: Long, val data: String) : TerminalInput()
    data class Resize(override val sequence: Long, val cols: Int, val rows: Int) : TerminalInput()
}

/** P1-7: one attached terminal on a native mux stream. */
class DshTerminal(
    private val stream: DshStream,
    initial: TerminalInfo,
    private val maxInputBytes: Int = 4096,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val sequence = AtomicLong(initialSequence(initial))
    private val mutable = MutableStateFlow(initial)
    val info: StateFlow<TerminalInfo> = mutable.asStateFlow()
    private val frames = Channel<TerminalFrame>(Channel.BUFFERED)

    /** Consumed once by the caller's render loop; the stream ends on `exited`. */
    suspend fun receive(): TerminalFrame? {
        if (closed.get()) return null
        val frame = frames.receiveCatching().getOrNull() ?: return null
        sequence.updateAndGet { current -> maxOf(current, frame.sequence + 1) }
        when (frame) {
            is TerminalFrame.State -> mutable.value = frame.info
            is TerminalFrame.Snapshot -> mutable.value = frame.info
            is TerminalFrame.Output -> Unit
        }
        return frame
    }

    fun write(data: String): Boolean {
        if (closed.get()) return false
        val encoded = data.toByteArray(Charsets.UTF_8)
        if (encoded.size > maxInputBytes) return false
        return try { stream.send(DshOptional.Value(jsonObject(
            "type" to "write".json(), "sequence" to sequence.get().json(), "data" to data.json(),
        ))); true } catch (_: DshFailure) { false }
    }

    fun resize(cols: Int, rows: Int): Boolean {
        if (closed.get() || cols !in 2..512 || rows !in 2..512) return false
        return try { stream.send(DshOptional.Value(jsonObject(
            "type" to "resize".json(), "sequence" to sequence.get().json(), "cols" to cols.toLong().json(), "rows" to rows.toLong().json(),
        ))); true } catch (_: DshFailure) { false }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stream.close()
        frames.close()
    }

    companion object {
        /** Snapshot frames carry the authoritative sequence; `initial` seeds from the info. */
        internal fun initialSequence(info: TerminalInfo): Long = 0L

        /** Decode one downlink mux item into a frame; unknown shapes are protocol errors. */
        fun decode(value: JsonObject): TerminalFrame {
            val type = value.string("type")
            val sequence = value.integer("sequence", 0)
            return when (type) {
                "snapshot" -> {
                    val info = value.obj("info")
                    TerminalFrame.Snapshot(sequence, value.string("screen"), decodeInfo(info), value.fields["writable"] == JsonBoolean(true))
                }
                "output" -> TerminalFrame.Output(sequence, value.string("data"))
                "state" -> TerminalFrame.State(sequence, decodeInfo(value.obj("info")))
                else -> throw ProtocolException()
            }
        }

        private fun decodeInfo(info: JsonObject): TerminalInfo = TerminalInfo(
            info.string("id"), info.string("title"), info.obj("shell").string("path"), info.string("cwd"),
            info.integer("cols", 2, 512).toInt(), info.integer("rows", 2, 512).toInt(),
            info.string("state"), (info.fields["exitCode"] as? JsonNumber)?.value?.toInt(), (info.fields["error"] as? JsonString)?.value,
        )
    }
}
