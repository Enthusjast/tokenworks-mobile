package cn.edu.ustc.tokenworks

import java.util.ArrayDeque

/** Stateful UTF-8 terminal (the transport has already decoded Unicode). Owned by one attachment reader. */
internal class TerminalEmulator(cols: Int, rows: Int, private val scrollback: Int) {
    private enum class Parse { TEXT, ESC, ESC_INTERMEDIATE, CSI, IGNORE_CSI, OSC, STRING, OSC_ESC, STRING_ESC }
    private data class Position(val x: Int, val y: Int, val style: TerminalStyle, val origin: Boolean, val wrap: Boolean,
        val special: BooleanArray, val g1: Boolean)
    private inner class Row(val id: Long = ++lineId) {
        val cells: Array<TerminalCell?> get() = storage
        private var storage: Array<TerminalCell?> = arrayOfNulls(width)
        private var cached: TerminalLine? = null
        private var shared = false
        var wrapAt = 0
            set(value) { if (field != value) { field = value; cached = null } }
        fun line(): TerminalLine = cached ?: TerminalLine(id, storage.asList(), wrapAt).also {
            shared = true
            cached = it
        }
        fun edit(): Array<TerminalCell?> {
            if (shared) { storage = storage.copyOf(); shared = false }
            cached = null
            return storage
        }
        fun resize() {
            storage = storage.copyOf(width)
            shared = false
            cached = null
            wrapAt = minOf(wrapAt, width)
            repairWide(storage)
        }
    }
    private inner class Buffer {
        val lines = MutableList(height) { Row() }
        var x = 0
        var y = 0
        var top = 0
        var bottom = height - 1
        var pendingWrap = false
        var saved: Position? = null
    }

    private var width = cols.coerceIn(2, MAX_COLUMNS)
    private var height = rows.coerceIn(1, MAX_ROWS)
    private var lineId = 0L
    private val history = ArrayDeque<Row>()
    private var primary = Buffer()
    private var alternate = Buffer()
    private var buffer = primary
    private var altSave: Position? = null
    private var style = TerminalStyle.Plain
    private var showCursor = true
    private var cursorShape = TerminalCursorShape.BLOCK
    private var modes = TerminalKeyModes()
    private var reverse = false
    private var origin = false
    private var autowrap = true
    private var insert = false
    private var special = BooleanArray(4)
    private var charset = 0
    private var tabs = BooleanArray(MAX_COLUMNS) { it != 0 && it % 8 == 0 }
    private var parse = Parse.TEXT
    private val params = StringBuilder()
    private var intermediate = ' '
    private var highSurrogate: Char? = null
    private var lastPrinted = 0
    private var graphemeColumn = -1

    fun display(): TerminalDisplay {
        val visible = buffer.lines
        return TerminalDisplay(width, height, buildList((if (buffer === primary) history.size else 0) + visible.size) {
            if (buffer === primary) for (row in history) add(row.line())
            for (row in visible) add(row.line())
        }, buffer === alternate,
            if (showCursor) TerminalCursor(visible[buffer.y].id, buffer.x, cursorShape) else null, modes, reverse)
    }

    fun resize(cols: Int, rows: Int) {
        val w = cols.coerceIn(2, MAX_COLUMNS)
        val h = rows.coerceIn(1, MAX_ROWS)
        if (w == width && h == height) return
        val oldWidth = width
        width = w
        if (oldWidth != w) reflowPrimary(h)
        for (screen in listOf(primary, alternate)) {
            if (screen === alternate && oldWidth != w) screen.lines.forEach { it.resize() }
            // Keep the cursor on-screen. If a shrink takes rows above it, they become scrollback.
            while (screen.lines.size > h) {
                if (screen.y >= h) {
                    val removed = screen.lines.removeAt(0)
                    if (screen === primary) history.addLast(removed)
                    screen.y--
                } else screen.lines.removeAt(screen.lines.lastIndex)
            }
            while (screen.lines.size < h) {
                if (screen === primary && history.isNotEmpty()) { screen.lines.add(0, history.removeLast()); screen.y++ }
                else screen.lines.add(Row())
            }
            screen.x = screen.x.coerceIn(0, w - 1)
            screen.pendingWrap = screen.pendingWrap && screen.x == w - 1
            screen.top = 0
            screen.bottom = h - 1
        }
        height = h
        // 1049 restores the primary cursor, including its new position after an alternate-screen resize.
        altSave = altSave?.copy(x = primary.x, y = primary.y, wrap = primary.pendingWrap)
        trimHistory()
        graphemeColumn = -1
    }

    /** Rewrap logical lines and carry the primary cursor through the same cell mapping. */
    private fun reflowPrimary(newHeight: Int) {
        val old = history.toList() + primary.lines
        val cursor = history.size + primary.y
        val retained = ArrayDeque<Row>()
        val retainBeforeCursor = historyLimit(newHeight) + newHeight
        var produced = 0
        var newRow = -1
        var newX = 0
        var newPending = false
        fun add(id: Long?): Row {
            val row = if (id != null) Row(id) else Row()
            retained.addLast(row)
            produced++
            if (newRow < 0 && retained.size > retainBeforeCursor) retained.removeFirst()
            return row
        }
        var start = 0
        reflow@ while (start < old.size) {
            var end = start
            while (end < old.lastIndex && old[end].wrapAt > 0) end++
            val groupStart = produced
            if (newRow >= 0 && produced >= newRow + newHeight) break
            var target = add(old[start].id)
            var x = 0
            for (index in start..end) {
                val source = old[index]
                val cells = source.cells
                val effective = source.wrapAt.takeIf { it > 0 } ?: (cells.indexOfLast { it != null } + 1)
                val cursorLimit = if (index == cursor) primary.x + (if (primary.pendingWrap) 1 else 0) else -1
                val count = maxOf(effective, cursorLimit)
                var col = 0
                while (col < count) {
                    val cell = cells[col]
                    val size = cell?.columns ?: 1
                    if (size == 0) { col++; continue }
                    if (x + size > width) {
                        if (newRow >= 0 && produced >= newRow + newHeight) break@reflow
                        target.wrapAt = x
                        val oldRow = start + produced - groupStart
                        target = add(if (oldRow <= end) old[oldRow].id else null)
                        x = 0
                    }
                    if (index == cursor && cursorLimit in col until col + size) {
                        newRow = produced - 1
                        newX = x + cursorLimit - col
                    }
                    target.cells[x] = cell
                    if (size == 2) target.cells[x + 1] = Continuation
                    x += size
                    col += size
                }
                if (index == cursor && cursorLimit == count) {
                    newRow = produced - 1
                    newX = minOf(x, width - 1)
                    newPending = x == width && autowrap
                }
            }
            start = end + 1
        }
        val first = minOf(newRow, maxOf(0, produced - newHeight))
        history.clear()
        primary.lines.clear()
        var index = produced - retained.size
        for (row in retained) {
            if (index < first) history.addLast(row)
            else if (index < first + newHeight) primary.lines.add(row)
            index++
        }
        primary.y = newRow - first
        primary.x = newX
        primary.pendingWrap = newPending
    }

    fun feed(data: String) {
        for (char in data) {
            val high = highSurrogate
            if (high != null) {
                highSurrogate = null
                if (Character.isLowSurrogate(char)) { consume(Character.toCodePoint(high, char)); continue }
                consume(0xfffd)
            }
            if (Character.isHighSurrogate(char)) highSurrogate = char
            else consume(if (Character.isLowSurrogate(char)) 0xfffd else char.code)
        }
    }

    private fun consume(code: Int) {
        when (parse) {
            Parse.OSC, Parse.STRING, Parse.OSC_ESC, Parse.STRING_ESC -> {
                val osc = parse == Parse.OSC || parse == Parse.OSC_ESC
                val escape = parse == Parse.OSC_ESC || parse == Parse.STRING_ESC
                parse = when {
                    code == 0x9c || code == 0x18 || code == 0x1a || (osc && code == 7) || (escape && code == '\\'.code) -> Parse.TEXT
                    code == 27 -> if (osc) Parse.OSC_ESC else Parse.STRING_ESC
                    else -> if (osc) Parse.OSC else Parse.STRING
                }
                return
            }
            else -> Unit
        }
        if (code == 27) { parse = Parse.ESC; return }
        if (code == 0x18 || code == 0x1a) { parse = Parse.TEXT; return }
        if (code < 32 || code in 127..159) { control(code); return }
        when (parse) {
            Parse.TEXT -> print(code)
            Parse.ESC -> {
                parse = Parse.TEXT
                when (code.toChar()) {
                    '[' -> startCsi()
                    ']' -> parse = Parse.OSC
                    'P', 'X', '^', '_' -> parse = Parse.STRING
                    '7' -> buffer.saved = position()
                    '8' -> restore(buffer.saved)
                    'D' -> index()
                    'E' -> { move(x = 0); index() }
                    'M' -> reverseIndex()
                    'H' -> tabs[buffer.x] = true
                    '=' -> modes = modes.copy(applicationKeypad = true)
                    '>' -> modes = modes.copy(applicationKeypad = false)
                    'n', 'o' -> charset = code - 'n'.code + 2
                    'c' -> reset()
                    in ' '..'/' -> { intermediate = code.toChar(); parse = Parse.ESC_INTERMEDIATE }
                }
            }
            Parse.ESC_INTERMEDIATE -> if (code >= 0x30) {
                val slot = "()*+".indexOf(intermediate)
                if (slot >= 0) special[slot] = code == '0'.code
                parse = Parse.TEXT
            }
            Parse.CSI, Parse.IGNORE_CSI -> when (code) {
                in 0x40..0x7e -> {
                    val valid = parse == Parse.CSI
                    parse = Parse.TEXT
                    if (valid) csi(code.toChar(), params.toString())
                }
                in 0x20..0x3f -> if (parse == Parse.CSI) {
                    if (params.length < 256) params.append(code.toChar()) else parse = Parse.IGNORE_CSI
                }
                else -> parse = Parse.IGNORE_CSI
            }
            else -> Unit
        }
    }

    private fun startCsi() { parse = Parse.CSI; params.setLength(0) }

    private fun control(code: Int) {
        when (code) {
            8 -> move(x = buffer.x - 1)
            9 -> tab(1)
            10, 11, 12, 0x84 -> { if (modes.newline) move(x = 0); index() }
            13 -> move(x = 0)
            14 -> charset = 1
            15 -> charset = 0
            0x85 -> { move(x = 0); index() }
            0x88 -> tabs[buffer.x] = true
            0x8d -> reverseIndex()
            0x90, 0x98, 0x9e, 0x9f -> parse = Parse.STRING
            0x9b -> startCsi()
            0x9d -> parse = Parse.OSC
        }
    }

    private fun position() = Position(buffer.x, buffer.y, style, origin, buffer.pendingWrap, special.copyOf(), charset == 1)
    private fun restore(saved: Position?) {
        origin = saved?.origin ?: false
        style = saved?.style ?: TerminalStyle.Plain
        special = saved?.special?.copyOf() ?: BooleanArray(4)
        charset = if (saved?.g1 == true) 1 else 0
        move(saved?.x ?: 0, saved?.y ?: 0)
        buffer.pendingWrap = saved?.wrap == true && buffer.x == width - 1
    }

    private fun reset() {
        history.clear()
        primary = Buffer(); alternate = Buffer(); buffer = primary; altSave = null
        tabs = BooleanArray(MAX_COLUMNS) { it != 0 && it % 8 == 0 }
        style = TerminalStyle.Plain; modes = TerminalKeyModes(); charset = 0; special.fill(false)
        showCursor = true; cursorShape = TerminalCursorShape.BLOCK; reverse = false
        origin = false; autowrap = true; insert = false; graphemeColumn = -1; lastPrinted = 0
    }

    private fun move(x: Int = buffer.x, y: Int = buffer.y) {
        buffer.x = x.coerceIn(0, width - 1); buffer.y = y.coerceIn(0, height - 1)
        buffer.pendingWrap = false; graphemeColumn = -1
    }

    private fun tab(count: Int, backwards: Boolean = false) {
        val step = if (backwards) -1 else 1
        var x = buffer.x
        repeat(minOf(width, count)) {
            x += step
            while (x in 1 until width - 1 && !tabs[x]) x += step
            x = x.coerceIn(0, width - 1)
        }
        move(x = x)
    }

    private fun erasedCell(): TerminalCell? {
        val background = if (style.has(TerminalStyle.INVERSE)) style.foreground else style.background
        return if (background == TerminalStyle.DEFAULT) null else TerminalCell(" ", 1, TerminalStyle(background = background))
    }
    private fun blank() = Row().also { row -> erasedCell()?.let { row.cells.fill(it) } }
    private fun historyLimit(rows: Int = height) = minOf(scrollback.coerceIn(0, 4096), maxOf(0, (262_144 / width) - rows))
    private fun trimHistory() {
        val limit = historyLimit()
        while (history.size > limit) history.removeFirst()
    }

    private fun scrollUp(top: Int, bottom: Int, count: Int) {
        repeat(minOf(count, bottom - top + 1)) {
            val row = buffer.lines.removeAt(top)
            if (buffer === primary && top == 0 && bottom == height - 1) { history.addLast(row); trimHistory() }
            buffer.lines.add(bottom, blank())
        }
    }
    private fun scrollDown(top: Int, bottom: Int, count: Int) {
        repeat(minOf(count, bottom - top + 1)) {
            buffer.lines.removeAt(bottom)
            buffer.lines.add(top, blank())
        }
    }
    private fun index() {
        if (buffer.y == buffer.bottom) scrollUp(buffer.top, buffer.bottom, 1) else buffer.y = minOf(height - 1, buffer.y + 1)
        buffer.pendingWrap = false; graphemeColumn = -1
    }
    private fun reverseIndex() {
        if (buffer.y == buffer.top) scrollDown(buffer.top, buffer.bottom, 1) else buffer.y = maxOf(0, buffer.y - 1)
        buffer.pendingWrap = false; graphemeColumn = -1
    }

    private fun clearCluster(cells: Array<TerminalCell?>, column: Int, fill: TerminalCell?) {
        when (cells[column]?.columns) {
            0 -> if (column > 0) cells[column - 1] = fill
            2 -> if (column + 1 < cells.size) cells[column + 1] = fill
        }
        cells[column] = fill
    }
    private fun repairWide(cells: Array<TerminalCell?>) {
        for (x in cells.indices) {
            if (cells[x]?.columns == 2 && cells.getOrNull(x + 1)?.columns != 0) cells[x] = null
            if (cells[x]?.columns == 0 && cells.getOrNull(x - 1)?.columns != 2) cells[x] = null
        }
    }
    private fun erase(row: Int, from: Int, until: Int) {
        if (from >= until) return
        val line = buffer.lines[row]
        val cells = line.edit()
        val fill = erasedCell()
        clearCluster(cells, from, fill); clearCluster(cells, until - 1, fill)
        cells.fill(fill, from, until)
        if (until == width) line.wrapAt = 0
    }
    private fun eraseDisplay(kind: Int) {
        when (kind) {
            0 -> { erase(buffer.y, buffer.x, width); for (row in buffer.y + 1 until height) erase(row, 0, width) }
            1 -> { for (row in 0 until buffer.y) erase(row, 0, width); erase(buffer.y, 0, buffer.x + 1) }
            2 -> for (row in 0 until height) erase(row, 0, width)
            3 -> if (buffer === primary) history.clear()
        }
    }
    private fun shiftCharacters(n: Int, right: Boolean) {
        val cells = buffer.lines[buffer.y].edit()
        val x = buffer.x
        val end = minOf(width, x + n)
        val count = end - x
        val fill = erasedCell()
        if (right) {
            if (cells[x]?.columns == 0) clearCluster(cells, x, fill)
            cells.copyInto(cells, end, x, width - count)
            cells.fill(fill, x, end)
        } else {
            if (cells[x]?.columns == 0) clearCluster(cells, x, fill)
            if (end < width && cells[end]?.columns == 0) clearCluster(cells, end, fill)
            cells.copyInto(cells, x, end, width)
            cells.fill(fill, width - count, width)
        }
        repairWide(cells)
    }

    private fun print(code: Int) {
        val cp = if (special[charset] && code in 0x60..0x7e) LineDrawing.codePointAt(code - 0x60) else code
        val size = cellWidth(cp)
        val previous = buffer.lines[buffer.y].cells.getOrNull(graphemeColumn)
        val join = previous != null && (size == 0 || previous.text.endsWith('\u200d') ||
            (isRegionalIndicator(cp) && previous.text.codePointCount(0, previous.text.length) == 1 && isRegionalIndicator(previous.text.codePointAt(0))))
        if (join) {
            if (previous!!.text.length + Character.charCount(cp) <= 32) {
                buffer.lines[buffer.y].edit()[graphemeColumn] = previous.copy(text = previous.text + String(Character.toChars(cp)))
            }
            return
        }
        if (size == 0) return
        if (buffer.pendingWrap || buffer.x + size > width) {
            if (autowrap) {
                buffer.lines[buffer.y].wrapAt = if (buffer.pendingWrap) width else buffer.x
                index()
                buffer.x = 0
            }
            else if (size == 2) return
        }
        if (insert) shiftCharacters(size, right = true)
        val cells = buffer.lines[buffer.y].edit()
        val x = buffer.x
        clearCluster(cells, x, null)
        if (size == 2) clearCluster(cells, x + 1, null)
        val text = if (cp in 32..126) ASCII[cp - 32] else String(Character.toChars(cp))
        cells[x] = if (style == TerminalStyle.Plain && cp in 32..126) ASCII_CELLS[cp - 32] else TerminalCell(text, size, style)
        if (size == 2) cells[x + 1] = Continuation
        buffer.x = minOf(width - 1, x + size)
        buffer.pendingWrap = autowrap && x + size == width
        graphemeColumn = x
        lastPrinted = code
    }

    private fun csi(final: Char, raw: String) {
        val marker = raw.firstOrNull()?.takeIf { it in '<'..'?' }
        val body = if (marker == null) raw else raw.substring(1)
        val parameterPart = body.takeWhile { it in '0'..';' }
        val suffix = body.substring(parameterPart.length)
        if (marker == null && suffix == " " && final == 'q') {
            cursorShape = when (parameterPart.toIntOrNull()) { 3, 4 -> TerminalCursorShape.UNDERLINE; 5, 6 -> TerminalCursorShape.BAR; else -> TerminalCursorShape.BLOCK }
            return
        }
        if (suffix.isNotEmpty() || marker != null && marker != '?') return
        if (marker == null && final == 'm') { sgr(parameterPart); return }
        val args = parameterPart.split(';').map { it.toIntOrNull()?.coerceIn(0, 65535) ?: 0 }
        fun n(index: Int = 0, default: Int = 1) = args.getOrElse(index) { 0 }.takeIf { it != 0 } ?: default
        if (final == 'h' || final == 'l') { for (mode in args) setMode(marker == '?', mode, final == 'h'); return }
        if (marker != null && final !in "JK") return
        val x = buffer.x
        val y = buffer.y
        val top = if (origin || y in buffer.top..buffer.bottom) buffer.top else 0
        val bottom = if (origin || y in buffer.top..buffer.bottom) buffer.bottom else height - 1
        fun absoluteY(row: Int) = if (origin) (row - 1 + buffer.top).coerceAtMost(buffer.bottom) else row - 1
        when (final) {
            'A' -> move(y = maxOf(top, y - n()))
            'B', 'e' -> move(y = minOf(bottom, y + n()))
            'C', 'a' -> move(x = x + n())
            'D' -> move(x = x - n())
            'E' -> move(0, minOf(bottom, y + n()))
            'F' -> move(0, maxOf(top, y - n()))
            'G', '`' -> move(x = n() - 1)
            'H', 'f' -> move(n(1) - 1, absoluteY(n()))
            'd' -> move(y = absoluteY(n()))
            'I', 'Z' -> tab(n(), backwards = final == 'Z')
            'J' -> { eraseDisplay(args[0]); move() }
            'K' -> {
                when (args[0]) { 0 -> erase(y, x, width); 1 -> erase(y, 0, x + 1); 2 -> erase(y, 0, width) }
                move()
            }
            'X' -> { erase(y, x, minOf(width, x + n())); move() }
            '@', 'P' -> { shiftCharacters(n(), right = final == '@'); move() }
            'L', 'M' -> if (y in buffer.top..buffer.bottom) {
                if (final == 'L') scrollDown(y, buffer.bottom, n()) else scrollUp(y, buffer.bottom, n())
                move(x = 0)
            }
            'S' -> { scrollUp(buffer.top, buffer.bottom, n()); move() }
            'T' -> { scrollDown(buffer.top, buffer.bottom, n()); move() }
            'b' -> if (lastPrinted != 0) repeat(minOf(width * height, n())) { print(lastPrinted) }
            'r' -> {
                val from = n() - 1
                val until = minOf(height, n(1, height)) - 1
                if (from < until || (height == 1 && from == 0 && until == 0)) {
                    buffer.top = from; buffer.bottom = until; move(0, if (origin) from else 0)
                }
            }
            'g' -> when (args[0]) { 0 -> tabs[x] = false; 3 -> tabs.fill(false) }
            's' -> if (parameterPart.isEmpty()) buffer.saved = position()
            'u' -> restore(buffer.saved)
        }
    }

    private fun setMode(private: Boolean, mode: Int, enabled: Boolean) {
        if (!private) {
            when (mode) { 4 -> insert = enabled; 20 -> modes = modes.copy(newline = enabled) }
            return
        }
        when (mode) {
            1 -> modes = modes.copy(applicationCursor = enabled)
            5 -> reverse = enabled
            6 -> { origin = enabled; move(0, if (origin) buffer.top else 0) }
            7 -> { autowrap = enabled; if (!enabled) buffer.pendingWrap = false }
            25 -> showCursor = enabled
            47, 1047, 1049 -> {
                if (enabled && buffer === primary) {
                    if (mode == 1049) altSave = position()
                    if (mode != 47) alternate = Buffer()
                    buffer = alternate
                    graphemeColumn = -1
                } else if (!enabled && buffer === alternate) {
                    buffer = primary
                    if (mode == 1049) restore(altSave)
                    altSave = null
                    if (mode != 47) alternate = Buffer()
                    graphemeColumn = -1
                }
            }
            66 -> modes = modes.copy(applicationKeypad = enabled)
            1048 -> if (enabled) buffer.saved = position() else restore(buffer.saved)
            2004 -> modes = modes.copy(bracketedPaste = enabled)
        }
    }

    private fun sgr(raw: String) {
        val parts = raw.split(';')
        var attributes = style.attributes
        var fg = style.foreground
        var bg = style.background
        var i = 0
        while (i < parts.size) {
            val chunks = parts[i++].split(':')
            val code = if (chunks[0].isEmpty()) 0 else chunks[0].toIntOrNull() ?: continue
            when (code) {
                0 -> { attributes = 0; fg = TerminalStyle.DEFAULT; bg = TerminalStyle.DEFAULT }
                1 -> attributes = attributes or TerminalStyle.BOLD
                2 -> attributes = attributes or TerminalStyle.FAINT
                3 -> attributes = attributes or TerminalStyle.ITALIC
                4, 21 -> attributes = if (chunks.getOrNull(1) == "0") attributes and TerminalStyle.UNDERLINE.inv() else attributes or TerminalStyle.UNDERLINE
                7 -> attributes = attributes or TerminalStyle.INVERSE
                8 -> attributes = attributes or TerminalStyle.HIDDEN
                9 -> attributes = attributes or TerminalStyle.STRIKE
                22 -> attributes = attributes and (TerminalStyle.BOLD or TerminalStyle.FAINT).inv()
                23 -> attributes = attributes and TerminalStyle.ITALIC.inv()
                24 -> attributes = attributes and TerminalStyle.UNDERLINE.inv()
                27 -> attributes = attributes and TerminalStyle.INVERSE.inv()
                28 -> attributes = attributes and TerminalStyle.HIDDEN.inv()
                29 -> attributes = attributes and TerminalStyle.STRIKE.inv()
                in 30..37 -> fg = code - 30
                in 40..47 -> bg = code - 40
                in 90..97 -> fg = code - 82
                in 100..107 -> bg = code - 92
                38, 48, 58 -> {
                    val format = (chunks.getOrNull(1) ?: parts.getOrNull(i++))?.toIntOrNull()
                    val count = when (format) { 2 -> 3; 5 -> 1; else -> 0 }
                    val components = if (chunks.size > 1) chunks.drop(if (format == 2 && chunks.size >= 6) 3 else 2)
                        else List(count) { parts.getOrNull(i++) ?: "" }
                    fun component(index: Int) = components.getOrNull(index)?.toIntOrNull()?.takeIf { it in 0..255 }
                    val color = when (format) {
                        5 -> component(0)
                        2 -> {
                            val r = component(0); val g = component(1); val b = component(2)
                            if (r != null && g != null && b != null) TerminalStyle.RGB or (r shl 16) or (g shl 8) or b else null
                        }
                        else -> null
                    }
                    if (color != null) when (code) { 38 -> fg = color; 48 -> bg = color }
                }
                39 -> fg = TerminalStyle.DEFAULT
                49 -> bg = TerminalStyle.DEFAULT
            }
        }
        style = TerminalStyle(fg, bg, attributes)
    }

    companion object {
        const val MAX_COLUMNS = 1024
        const val MAX_ROWS = 512
        private val Continuation = TerminalCell("", 0, TerminalStyle.Plain)
        private val ASCII = Array(95) { (it + 32).toChar().toString() }
        private val ASCII_CELLS = Array(95) { TerminalCell(ASCII[it], 1, TerminalStyle.Plain) }
        private const val LineDrawing = "◆▒␉␌␍␊°±␤␋┘┐┌└┼⎺⎻─⎼⎽├┤┴┬│≤≥π≠£·"

        private fun isRegionalIndicator(cp: Int) = cp in 0x1f1e6..0x1f1ff
        // East Asian ambiguous characters remain single-width. Combining marks and joiners occupy no cell.
        private fun cellWidth(cp: Int): Int {
            if (cp < 0x0300) return 1
            if (cp in 0x1f3fb..0x1f3ff || cp in 0x1160..0x11ff) return 0
            val type = Character.getType(cp)
            if (type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.FORMAT.toInt()) return 0
            return if (cp in 0x1100..0x115f || cp in 0x231a..0x231b || cp in 0x2e80..0xa4cf && cp != 0x303f ||
                cp in 0xac00..0xd7a3 || cp in 0xf900..0xfaff || cp in 0xfe10..0xfe19 || cp in 0xfe30..0xfe6f ||
                cp in 0xff01..0xff60 || cp in 0xffe0..0xffe6 || cp in 0x16fe0..0x18dff || cp in 0x1b000..0x1b2ff ||
                cp in 0x1f300..0x1faff || isRegionalIndicator(cp) || cp in 0x20000..0x3fffd) 2 else 1
        }
    }
}
