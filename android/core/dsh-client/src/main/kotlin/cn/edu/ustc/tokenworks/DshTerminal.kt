package cn.edu.ustc.tokenworks

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/** Host-owned process metadata; detaching an attachment never closes this process. */
data class TerminalInfo(
    val id: String, val title: String, val shellPath: String, val cwd: String,
    val cols: Int, val rows: Int, val state: String, val exitCode: Int?, val error: String?,
) {
    val running get() = state == "running"
}

data class TerminalEnvironment(
    val cwd: String, val maxInputBytes: Int, val maxBufferedBytes: Int,
    val maxCols: Int, val maxRows: Int, val scrollback: Int,
) {
    fun accepts(cols: Int, rows: Int) = cols in 2..maxCols && rows in 1..maxRows
}

data class TerminalInputWindow(val maxBytes: Int, val frameOverheadBytes: Int)

sealed interface TerminalFrame {
    data class Snapshot(val sequence: Long, val screen: String, val info: TerminalInfo,
        val writable: Boolean, val inputWindow: TerminalInputWindow? = null) : TerminalFrame
    data class Output(val sequence: Long, val data: String) : TerminalFrame
    data class State(val info: TerminalInfo, val writable: Boolean) : TerminalFrame
    data class Ack(val sequence: Long, val refusal: String? = null) : TerminalFrame
}

/** A single-consumer native attachment. Input is never replayed across attachments. */
class DshTerminal(
    private val stream: DshStream,
    initial: TerminalInfo,
    private val environment: TerminalEnvironment,
) : AutoCloseable {
    private data class Command(val sequence: Long, val value: JsonObject, val bytes: Int, val inputBytes: Int)
    private val lock = Any()
    private var closed = false
    private var baseline = false
    private var outputSequence = 0L
    private var inputSequence = 0L
    private var acknowledged = 0L
    private var window: TerminalInputWindow? = null
    private val queued = ArrayDeque<Command>()
    private val pending = ArrayDeque<Command>()
    private var retainedBytes = 0L
    private var inFlightBytes = 0L
    private var retainedInputBytes = 0L
    private val mutableInfo = MutableStateFlow(initial)
    val info: StateFlow<TerminalInfo> = mutableInfo.asStateFlow()
    private val mutableWritable = MutableStateFlow(false)
    val writable: StateFlow<Boolean> = mutableWritable.asStateFlow()
    private val mutableIssue = MutableStateFlow<String?>(null)
    val issue: StateFlow<String?> = mutableIssue.asStateFlow()

    /** Reads the actual mux stream directly: no second inbox or reader job. */
    suspend fun receive(): TerminalFrame? {
        while (true) {
            if (synchronized(lock) { closed }) return null
            val message = stream.next() ?: return null
            val frame = when (message) {
                is DshMuxFrame.End -> return null
                is DshMuxFrame.Error -> throw DshFailure(message.error.code)
                is DshMuxFrame.Item -> decode((message.value as? DshOptional.Value)?.value as? JsonObject ?: throw ProtocolException())
            }
            synchronized(lock) {
                if (closed) return null
                when (frame) {
                    is TerminalFrame.Snapshot -> {
                        if (baseline || frame.info.id != info.value.id || frame.screen.toByteArray(Charsets.UTF_8).size > OUTPUT_BYTES) throw ProtocolException()
                        baseline = true
                        outputSequence = frame.sequence
                        window = frame.inputWindow
                        mutableInfo.value = frame.info
                        mutableWritable.value = frame.writable && frame.info.running
                    }
                    is TerminalFrame.Output -> {
                        if (!baseline) throw ProtocolException()
                        if (frame.sequence <= outputSequence) return@synchronized
                        if (frame.sequence != outputSequence + 1) throw DshFailure("terminal.output_gap")
                        if (frame.data.toByteArray(Charsets.UTF_8).size > OUTPUT_BYTES) throw ProtocolException()
                        outputSequence = frame.sequence
                        return frame
                    }
                    is TerminalFrame.State -> {
                        if (!baseline || frame.info.id != info.value.id) throw ProtocolException()
                        mutableInfo.value = frame.info
                        mutableWritable.value = frame.writable && frame.info.running
                    }
                    is TerminalFrame.Ack -> {
                        if (!baseline || frame.sequence != acknowledged + 1 || pending.firstOrNull()?.sequence != frame.sequence) throw ProtocolException()
                        val accepted = pending.removeFirst()
                        acknowledged = frame.sequence
                        retainedBytes -= accepted.bytes
                        retainedInputBytes -= accepted.inputBytes
                        inFlightBytes -= accepted.bytes
                        if (frame.refusal != null) {
                            mutableWritable.value = false
                            mutableIssue.value = "terminal.${frame.refusal}"
                        }
                        flush()
                    }
                }
                if (frame !is TerminalFrame.Output) return frame
            }
        }
    }

    fun write(data: String): Boolean = synchronized(lock) {
        val bytes = data.toByteArray(Charsets.UTF_8).size
        if (!canWrite() || data.isEmpty() || retainedInputBytes + bytes > environment.maxInputBytes) return false
        enqueue(jsonObject("type" to "write".json(), "sequence" to (inputSequence + 1).json(), "data" to data.json()), bytes)
    }

    fun resize(cols: Int, rows: Int): Boolean = synchronized(lock) {
        if (!canWrite() || !environment.accepts(cols, rows)) return false
        enqueue(jsonObject("type" to "resize".json(), "sequence" to (inputSequence + 1).json(),
            "cols" to cols.toLong().json(), "rows" to rows.toLong().json()))
    }

    private fun canWrite() = !closed && baseline && writable.value && info.value.running

    private fun enqueue(value: JsonObject, inputBytes: Int = 0): Boolean {
        // The native window counts the compact JSON command plus the advertised carrier envelope.
        val bytes = JsonDocument.of(value).canonicalBytes().size.toLong() + (window?.frameOverheadBytes ?: 0)
        if (bytes > (window?.maxBytes ?: Int.MAX_VALUE) || retainedBytes + bytes > minOf(environment.maxBufferedBytes, OUTPUT_BYTES)) {
            mutableIssue.value = "terminal.inputFull"
            close()
            return false
        }
        queued.addLast(Command(++inputSequence, value, bytes.toInt(), inputBytes))
        retainedBytes += bytes
        retainedInputBytes += inputBytes
        return try { flush(); true } catch (error: DshFailure) {
            mutableIssue.value = error.code
            close()
            false
        }
    }

    private fun flush() {
        while (queued.isNotEmpty() && writable.value) {
            val command = queued.first()
            if (inFlightBytes + command.bytes > (window?.maxBytes ?: Int.MAX_VALUE)) return
            queued.removeFirst()
            pending.addLast(command)
            inFlightBytes += command.bytes
            stream.send(DshOptional.Value(command.value))
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            mutableWritable.value = false
            queued.clear()
            pending.clear()
            retainedBytes = 0
            inFlightBytes = 0
            retainedInputBytes = 0
            stream.close()
        }
    }

    companion object {
        const val OUTPUT_BYTES = 4 * 1024 * 1024

        fun decode(value: JsonObject): TerminalFrame {
            DshMethods.TerminalAttach.output.decode(value)
            return when (value.string("type")) {
            "snapshot" -> TerminalFrame.Snapshot(value.integer("sequence", 0), value.string("screen"), decodeInfo(value.obj("info")),
                value.boolean("writable"), (value.fields["inputWindow"] as? JsonObject)?.let {
                    TerminalInputWindow(it.integer("maxBytes", 1, Int.MAX_VALUE.toLong()).toInt(), it.integer("frameOverheadBytes", 0, Int.MAX_VALUE.toLong()).toInt())
                })
            "output" -> TerminalFrame.Output(value.integer("sequence", 1), value.string("data"))
            "state" -> TerminalFrame.State(decodeInfo(value.obj("info")), value.boolean("writable"))
            "ack" -> TerminalFrame.Ack(value.integer("sequence", 1), (value.fields["refusal"] as? JsonString)?.value?.also {
                if (it !in setOf("read-only", "not-running")) throw ProtocolException()
            })
            else -> throw ProtocolException()
        }
        }

        fun decodeInfo(info: JsonObject): TerminalInfo = TerminalInfo(
            info.string("id"), info.string("title"), info.obj("shell").string("path"), info.string("cwd"),
            info.integer("cols", 2, Int.MAX_VALUE.toLong()).toInt(), info.integer("rows", 1, Int.MAX_VALUE.toLong()).toInt(),
            info.string("state").also { if (it !in setOf("running", "exited", "failed")) throw ProtocolException() },
            (info.fields["exitCode"] as? JsonNumber)?.value?.toInt(), (info.fields["error"] as? JsonString)?.value,
        )

        private fun JsonObject.boolean(name: String) = (fields[name] as? JsonBoolean)?.value ?: throw ProtocolException()
    }
}
