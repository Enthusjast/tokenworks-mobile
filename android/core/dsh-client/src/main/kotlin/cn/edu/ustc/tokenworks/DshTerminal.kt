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

/**
 * Bounded plain-text VT surface: interprets cursor/erase/scroll controls, ignores
 * styling and terminal-side requests. It never exposes OSC payloads or escapes.
 */
internal class TerminalTextScreen(cols: Int, rows: Int, private val scrollback: Int) {
    private var cols = cols.coerceIn(2, 1024)
    private var rows = rows.coerceIn(1, 512)
    private var lines = mutableListOf<CharArray>()
    private var alternate: MutableList<CharArray>? = null
    private var savedMain: MutableList<CharArray>? = null
    private var x = 0
    private var y = 0
    private var savedX = 0
    private var savedY = 0
    private var top = 0
    private var bottom = this.rows - 1
    private var mode = 0
    private val control = StringBuilder()
    private var stringEscape = false

    init { reset() }

    fun reset() {
        lines = MutableList(rows) { blank() }
        alternate = null
        savedMain = null
        x = 0; y = 0; savedX = 0; savedY = 0
        top = 0; bottom = rows - 1; mode = 0
        control.setLength(0)
        stringEscape = false
    }

    fun resize(cols: Int, rows: Int) {
        val newCols = cols.coerceIn(2, 1024)
        val newRows = rows.coerceIn(1, 512)
        if (newCols == this.cols && newRows == this.rows) return
        this.cols = newCols
        this.rows = newRows
        lines = lines.mapTo(mutableListOf()) { old ->
            blank().also { old.copyInto(it, endIndex = minOf(old.size, newCols)) }
        }
        while (lines.size < newRows) lines.add(blank())
        savedMain = null
        alternate = null
        x = x.coerceIn(0, newCols - 1); y = y.coerceIn(0, newRows - 1)
        top = 0; bottom = newRows - 1
        trim()
    }

    fun feed(data: String) {
        data.forEach { char ->
            when (mode) {
                1 -> when (char) {
                    '[' -> { mode = 2; control.setLength(0) }
                    ']', 'P', '^', '_' -> { mode = 3; stringEscape = false }
                    '7' -> { savedX = x; savedY = y; mode = 0 }
                    '8' -> { x = savedX.coerceIn(0, cols - 1); y = savedY.coerceIn(0, rows - 1); mode = 0 }
                    'D' -> { newline(); mode = 0 }
                    'M' -> { if (y == top) { val start = base(); lines.add(start + top, blank()); lines.removeAt(start + bottom + 1) } else y = maxOf(top, y - 1); mode = 0 }
                    'E' -> { x = 0; newline(); mode = 0 }
                    'c' -> reset()
                    '(', ')', '*', '+' -> mode = 4 // consume the character-set selector
                    else -> mode = 0
                }
                2 -> {
                    if (char in '@'..'~') { csi(char); mode = 0 }
                    else if (control.length < 128) control.append(char)
                    else { control.setLength(0); mode = 3 }
                }
                3 -> {
                    if (char == '\u0007' || (stringEscape && char == '\\')) { mode = 0; stringEscape = false }
                    else stringEscape = char == '\u001b'
                }
                4 -> mode = 0
                else -> when (char) {
                    '\u001b' -> mode = 1
                    '\r' -> x = 0
                    '\n', '\u000b', '\u000c' -> newline()
                    '\b' -> x = maxOf(0, x - 1)
                    '\t' -> x = minOf(cols - 1, (x / 8 + 1) * 8)
                    else -> if (char >= ' ' && char != '\u007f' && char !in '\u0080'..'\u009f') {
                        if (x >= cols) { x = 0; newline() }
                        lines[base() + y][x++] = char
                    }
                }
            }
        }
    }

    fun text(): String = buildString {
        lines.forEachIndexed { index, line ->
            var end = line.size
            while (end > 0 && line[end - 1] == ' ') end--
            append(line, 0, end)
            if (index < lines.lastIndex) append('\n')
        }
    }.trimEnd('\n')

    private fun blank() = CharArray(cols) { ' ' }
    private fun base() = maxOf(0, lines.size - rows)
    private fun trim() {
        // Two screens together use at most 4 MiB at the maximum UTF-8 cell width.
        val limit = maxOf(rows, minOf(rows + scrollback.coerceAtMost(4096), 524288 / cols))
        while (lines.size > limit) lines.removeAt(0)
    }
    private fun newline() {
        if (y == bottom) {
            if (top == 0 && bottom == rows - 1 && savedMain == null) { lines.add(blank()); trim() }
            else { val start = base(); lines.removeAt(start + top); lines.add(start + bottom, blank()) }
        } else y = minOf(rows - 1, y + 1)
    }
    private fun csi(final: Char) {
        val raw = control.toString()
        val private = raw.startsWith('?')
        val args = raw.removePrefix("?").split(';').map { it.toIntOrNull() ?: 0 }
        fun arg(index: Int = 0, default: Int = 1) = (args.getOrNull(index) ?: 0).let { if (it == 0) default else it }.coerceIn(0, 100000)
        fun erase(row: Int, from: Int, until: Int) {
            lines[base() + row].fill(' ', from.coerceIn(0, cols), until.coerceIn(0, cols))
        }
        when (final) {
            'A' -> y = maxOf(0, y - arg())
            'B', 'e' -> y = minOf(rows - 1, y + arg())
            'C', 'a' -> x = minOf(cols - 1, x + arg())
            'D' -> x = maxOf(0, x - arg())
            'E' -> { y = minOf(rows - 1, y + arg()); x = 0 }
            'F' -> { y = maxOf(0, y - arg()); x = 0 }
            'G', '`' -> x = (arg() - 1).coerceIn(0, cols - 1)
            'd' -> y = (arg() - 1).coerceIn(0, rows - 1)
            'H', 'f' -> { y = (arg() - 1).coerceIn(0, rows - 1); x = (arg(1) - 1).coerceIn(0, cols - 1) }
            'J' -> when (args[0]) {
                0 -> { erase(y, x, cols); for (row in y + 1 until rows) erase(row, 0, cols) }
                1 -> { for (row in 0 until y) erase(row, 0, cols); erase(y, 0, x + 1) }
                2, 3 -> { for (row in 0 until rows) erase(row, 0, cols); if (args[0] == 3) while (lines.size > rows) lines.removeAt(0) }
            }
            'K' -> when (args[0]) { 0 -> erase(y, x, cols); 1 -> erase(y, 0, x + 1); 2 -> erase(y, 0, cols) }
            'X' -> erase(y, x, minOf(cols, x + arg()))
            'P' -> {
                val row = lines[base() + y]; val start = x.coerceAtMost(cols - 1); val count = minOf(arg(), cols - start)
                row.copyInto(row, start, start + count, cols); row.fill(' ', cols - count, cols)
            }
            '@' -> {
                val row = lines[base() + y]; val start = x.coerceAtMost(cols - 1); val count = minOf(arg(), cols - start)
                row.copyInto(row, start + count, start, cols - count); row.fill(' ', start, start + count)
            }
            'L' -> if (y in top..bottom) repeat(minOf(arg(), bottom - y + 1)) { val start = base(); lines.add(start + y, blank()); lines.removeAt(start + bottom + 1) }
            'M' -> if (y in top..bottom) repeat(minOf(arg(), bottom - y + 1)) { val start = base(); lines.removeAt(start + y); lines.add(start + bottom, blank()) }
            'S' -> repeat(minOf(arg(), rows)) { val previous = y; y = bottom; newline(); y = previous }
            'T' -> repeat(minOf(arg(), rows)) { val start = base(); lines.add(start + top, blank()); lines.removeAt(start + bottom + 1) }
            'r' -> { top = (arg() - 1).coerceIn(0, rows - 1); bottom = (arg(1, rows) - 1).coerceIn(top, rows - 1); x = 0; y = top }
            's' -> { savedX = x; savedY = y }
            'u' -> { x = savedX.coerceIn(0, cols - 1); y = savedY.coerceIn(0, rows - 1) }
            'h', 'l' -> if (private && args.any { it == 1049 || it == 47 || it == 1047 }) {
                if (final == 'h' && savedMain == null) {
                    savedMain = lines; alternate = MutableList(rows) { blank() }; lines = alternate!!
                    savedX = x; savedY = y; x = 0; y = 0
                } else if (final == 'l' && savedMain != null) {
                    lines = savedMain!!; savedMain = null; alternate = null
                    x = savedX.coerceIn(0, cols - 1); y = savedY.coerceIn(0, rows - 1)
                }
            }
            // SGR, cursor visibility, mouse and device queries are deliberately not rendered.
        }
    }
}
