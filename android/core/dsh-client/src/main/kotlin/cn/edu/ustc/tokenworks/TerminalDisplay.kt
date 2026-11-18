package cn.edu.ustc.tokenworks

/** Colors are either a palette index (0–255), an RGB value flagged with [RGB], or default. */
data class TerminalStyle(
    val foreground: Int = DEFAULT,
    val background: Int = DEFAULT,
    val attributes: Int = 0,
) {
    fun has(attribute: Int) = attributes and attribute != 0

    companion object {
        const val DEFAULT = -1
        const val RGB = 1 shl 24
        const val BOLD = 1
        const val FAINT = 2
        const val ITALIC = 4
        const val UNDERLINE = 8
        const val INVERSE = 16
        const val HIDDEN = 32
        const val STRIKE = 64
        internal val Plain = TerminalStyle()
    }
}

data class TerminalCell(val text: String, val columns: Int, val style: TerminalStyle)

class TerminalLine internal constructor(
    val id: Long,
    val cells: List<TerminalCell?>,
    /** The next row continues this line at this column (zero means hard row boundary). */
    private val wrapAt: Int,
) {
    val wraps get() = wrapAt > 0
    val text: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val line = buildString {
            for (index in 0 until (if (wraps) wrapAt else cells.size)) {
                val cell = cells[index]
                when {
                    cell == null -> append(' ')
                    cell.columns == 0 -> Unit
                    cell.style.has(TerminalStyle.HIDDEN) -> repeat(cell.columns) { append(' ') }
                    else -> append(cell.text)
                }
            }
        }
        if (wraps) line else line.trimEnd(' ')
    }

    /** Converts a UTF-16 text offset to a display cell. End offsets exclude that cell. */
    fun columnAt(offset: Int, end: Boolean = false): Int {
        var index = 0
        for ((column, cell) in cells.withIndex()) {
            if (cell?.columns == 0) continue
            val length = when {
                cell == null -> 1
                cell.style.has(TerminalStyle.HIDDEN) -> cell.columns
                else -> cell.text.length
            }
            if (offset < index + length) return column + if (end && offset > index) (cell?.columns ?: 1) else 0
            index += length
        }
        return if (wraps) wrapAt else cells.size
    }
}

enum class TerminalCursorShape { BLOCK, UNDERLINE, BAR }
data class TerminalCursor(val lineId: Long, val column: Int, val shape: TerminalCursorShape)

data class TerminalKeyModes(
    val applicationCursor: Boolean = false,
    val applicationKeypad: Boolean = false,
    val bracketedPaste: Boolean = false,
    val newline: Boolean = false,
)

data class TerminalDisplay internal constructor(
    val cols: Int,
    val rows: Int,
    val lines: List<TerminalLine>,
    val alternate: Boolean,
    val cursor: TerminalCursor?,
    val keyModes: TerminalKeyModes,
    val reverseVideo: Boolean,
) {
    val text: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        lines.joinToString("\n") { it.text }.trimEnd('\n')
    }

    fun transcript(): String = buildString {
        lines.forEachIndexed { index, line ->
            append(line.text)
            if (!line.wraps && index != lines.lastIndex) append('\n')
        }
    }.trimEnd('\n')
}

data class TerminalSearchRange(val lineId: Long, val start: Int, val end: Int)
data class TerminalSearchMatch(val lineIndex: Int, val ranges: List<TerminalSearchRange>)

/** Literal case-insensitive search across displayed rows, including soft wraps but not hard newlines. */
fun TerminalDisplay.search(query: String): List<TerminalSearchMatch> {
    if (query.isEmpty() || query.length > 256) return emptyList()
    val matches = mutableListOf<TerminalSearchMatch>()
    var first = 0
    val logical = StringBuilder()
    val offsets = mutableListOf<Int>()
    for ((index, line) in lines.withIndex()) {
        offsets.add(logical.length)
        logical.append(line.text)
        if (line.wraps && index != lines.lastIndex) continue
        var start = logical.indexOf(query, ignoreCase = true)
        var row = 0
        while (start >= 0 && matches.size < 2000) {
            val end = start + query.length
            while (row < offsets.lastIndex && offsets[row + 1] <= start) row++
            var part = row
            val ranges = mutableListOf<TerminalSearchRange>()
            while (part < offsets.size && offsets[part] < end) {
                val physical = lines[first + part]
                val from = maxOf(0, start - offsets[part])
                val until = minOf(physical.text.length, end - offsets[part])
                if (from < until) ranges.add(TerminalSearchRange(physical.id, physical.columnAt(from), physical.columnAt(until, end = true)))
                part++
            }
            matches.add(TerminalSearchMatch(first + row, ranges))
            start = logical.indexOf(query, end, ignoreCase = true)
        }
        if (matches.size == 2000) break
        first = index + 1
        logical.setLength(0)
        offsets.clear()
    }
    return matches
}

enum class TerminalKey {
    UP, DOWN, LEFT, RIGHT, HOME, END, INSERT, DELETE, PAGE_UP, PAGE_DOWN,
    ESCAPE, BACKSPACE, ENTER, TAB, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    KP0, KP1, KP2, KP3, KP4, KP5, KP6, KP7, KP8, KP9, KP_DOT, KP_DIVIDE, KP_MULTIPLY, KP_MINUS, KP_PLUS, KP_ENTER,
}

data class TerminalModifiers(val shift: Boolean = false, val alt: Boolean = false, val ctrl: Boolean = false) {
    internal val parameter get() = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
}

fun TerminalKeyModes.encode(key: TerminalKey, modifiers: TerminalModifiers = TerminalModifiers()): String {
    val parameter = modifiers.parameter
    val esc = "\u001b"
    val csi = "$esc["
    val ss3 = "${esc}O"
    fun directional(final: Char, application: Boolean) = when {
        parameter > 1 -> "${csi}1;$parameter$final"
        application -> "$ss3$final"
        else -> "$csi$final"
    }
    fun numbered(code: Int) = if (parameter > 1) "$csi$code;$parameter~" else "$csi$code~"
    fun simple(value: String) = if (modifiers.alt) esc + value else value
    return when (key) {
        TerminalKey.UP, TerminalKey.DOWN, TerminalKey.RIGHT, TerminalKey.LEFT ->
            directional("ABDC"[key.ordinal], applicationCursor)
        TerminalKey.HOME -> directional('H', applicationCursor)
        TerminalKey.END -> directional('F', applicationCursor)
        TerminalKey.INSERT -> numbered(2)
        TerminalKey.DELETE -> numbered(3)
        TerminalKey.PAGE_UP -> numbered(5)
        TerminalKey.PAGE_DOWN -> numbered(6)
        TerminalKey.ESCAPE -> simple(esc)
        TerminalKey.BACKSPACE -> simple(if (modifiers.ctrl) "\b" else "\u007f")
        TerminalKey.ENTER -> simple(if (newline) "\r\n" else "\r")
        TerminalKey.TAB -> when {
            modifiers.shift && parameter == 2 -> "${csi}Z"
            parameter != 1 -> "${csi}1;${parameter}I"
            else -> "\t"
        }
        in TerminalKey.F1..TerminalKey.F4 -> directional('P' + (key.ordinal - TerminalKey.F1.ordinal), true)
        in TerminalKey.F5..TerminalKey.F12 -> numbered(functionCodes[key.ordinal - TerminalKey.F5.ordinal])
        else -> {
            val index = key.ordinal - TerminalKey.KP0.ordinal
            if (applicationKeypad) directional("pqrstuvwxyonjmkM"[index], true)
            else simple("0123456789./*-+\r"[index].toString())
        }
    }
}

private val functionCodes = intArrayOf(15, 17, 18, 19, 20, 21, 23, 24)

fun terminalTypedText(text: String, modifiers: TerminalModifiers): String? {
    if (text.isEmpty()) return null
    val value = if (modifiers.ctrl) {
        val char = text.singleOrNull()?.uppercaseChar() ?: return null
        when (char) {
            in '@'..'_' -> (char.code and 31).toChar().toString()
            ' ', '2' -> "\u0000"
            '3' -> "\u001b"
            '4' -> "\u001c"
            '5' -> "\u001d"
            '6' -> "\u001e"
            '7', '/' -> "\u001f"
            '8', '?' -> "\u007f"
            else -> return null
        }
    } else text
    return if (modifiers.alt) "\u001b$value" else value
}
