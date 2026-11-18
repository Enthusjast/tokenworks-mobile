package cn.edu.ustc.tokenworks

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt

internal class TerminalViewportScroll {
    val main = LazyListState()
    val alternate = LazyListState()
    val horizontal = ScrollState(0)
    var following by mutableStateOf(true)
}

@Composable
internal fun TerminalViewport(
    display: TerminalDisplay,
    height: Dp,
    searching: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    scroll: TerminalViewportScroll,
    onFit: (Int, Int) -> Unit,
) {
    val surfaces = tokenWorksSurfaces()
    val theme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val px = with(density) { 13.sp.toPx() }
    val painter = remember(px) { TerminalRowPainter(px) }
    val rowHeight = with(density) { painter.rowHeight.toDp() }
    val gridWidth = with(density) { (display.cols * painter.cellWidth).toDp() }
    val list = if (display.alternate) scroll.alternate else scroll.main
    val pan = scroll.horizontal
    val searchInput by rememberUpdatedState(display to query)
    val searchResults by produceState<Pair<String, List<TerminalSearchMatch>>?>(null, searching, query, display.alternate) {
        value = null
        if (!searching) return@produceState
        snapshotFlow { searchInput }.collect { (source, term) ->
            value = term to if (term.isEmpty()) emptyList() else withContext(Dispatchers.Default) { source.search(term) }
        }
    }
    val matches = searchResults?.takeIf { searching && it.first == query }?.second.orEmpty()
    var index by remember(query, searching) { mutableIntStateOf(0) }
    val currentIndex = index.coerceAtMost(matches.lastIndex)
    val active = matches.getOrNull(currentIndex)
    val highlights = remember(matches) { matches.flatMap { it.ranges }.groupBy { it.lineId } }
    val actions = rememberCoroutineScope()

    fun jump(match: TerminalSearchMatch) {
        val range = match.ranges.firstOrNull() ?: return
        // Result indices can move while new output is arriving. Locate the live row by its stable id.
        val row = display.lines.indexOfFirst { it.id == range.lineId }
        if (row < 0) return
        scroll.following = false
        actions.launch {
            list.scrollToItem(row)
            val start = (range.start * painter.cellWidth).toInt()
            val end = (range.end * painter.cellWidth).roundToInt()
            if (start < pan.value || end > pan.value + pan.viewportSize) pan.scrollTo(start.coerceAtMost(pan.maxValue))
        }
    }
    fun step(direction: Int) {
        if (matches.isEmpty()) return
        index = (currentIndex + direction + matches.size) % matches.size
        jump(matches[index])
    }

    val liveRow = remember(display) {
        maxOf(display.lines.indexOfLast { it.id == display.cursor?.lineId },
            display.lines.indexOfLast { it.text.isNotEmpty() }).coerceAtLeast(0)
    }
    LaunchedEffect(display.alternate, display.lines.getOrNull(liveRow)?.id, scroll.following, searching, height, rowHeight) {
        if (!display.alternate && scroll.following && !searching && display.lines.isNotEmpty()) {
            val visibleRows = ((height - TokenWorksSpacing.sm * 2) / rowHeight).toInt().coerceAtLeast(1)
            val first = list.firstVisibleItemIndex
            if (liveRow !in first until first + visibleRows) list.scrollToItem(maxOf(0, liveRow - visibleRows + 1))
        }
    }
    LaunchedEffect(list, searching, display.alternate) {
        if (!searching && !display.alternate) {
            list.interactionSource.interactions.collect { if (it is DragInteraction.Start) scroll.following = false }
        }
    }
    val firstMatch = matches.firstOrNull()?.ranges?.firstOrNull()?.lineId
    // Jump once when a new query has produced a result; streaming output does not steal the user's position.
    var jumped by remember(searching, query) { mutableStateOf(false) }
    LaunchedEffect(firstMatch, searching, query) {
        if (!jumped && firstMatch != null && searching) {
            matches.firstOrNull()?.let(::jump)
            jumped = true
        }
    }

    if (searching) {
        OutlinedTextField(query, { onQuery(it.take(256)) }, Modifier.fillMaxWidth().testTag("terminal-output-search"), singleLine = true,
            label = { Text(stringResource(R.string.terminal_search_label)) })
        TerminalActionRow {
            val status = when {
                query.isEmpty() -> stringResource(R.string.terminal_search_hint)
                searchResults?.first != query -> stringResource(R.string.terminal_search_in_progress)
                matches.isEmpty() -> stringResource(R.string.terminal_no_matches)
                matches.size >= 2000 -> stringResource(R.string.terminal_match_limit, currentIndex + 1)
                else -> stringResource(R.string.terminal_match_count, currentIndex + 1, matches.size)
            }
            Text(status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelMedium)
            TextButton({ step(-1) }, TerminalTouch, enabled = matches.isNotEmpty()) { Text(stringResource(R.string.terminal_previous_match)) }
            TextButton({ step(1) }, TerminalTouch, enabled = matches.isNotEmpty()) { Text(stringResource(R.string.terminal_next_match)) }
        }
    } else if (!scroll.following && !display.alternate) {
        TextButton({ scroll.following = true }, TerminalTouch) { Text(stringResource(R.string.terminal_latest_output)) }
    }

    Surface(Modifier.fillMaxWidth().height(height), shape = tokenWorksSquircle(TokenWorksRadius.md),
        color = if (display.reverseVideo) surfaces.codeWellForeground else surfaces.codeWellBackground,
        border = BorderStroke(1.dp, surfaces.codeWellBorder)) {
        CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(TokenWorksSpacing.sm)) {
                val contentWidth = maxOf(gridWidth, maxWidth)
                val visibleWidth = with(density) { maxWidth.toPx() }
                val visibleHeight = with(density) { maxHeight.toPx() }
                LaunchedEffect(visibleWidth, visibleHeight, painter) {
                    onFit((visibleWidth / painter.cellWidth).toInt(), (visibleHeight / painter.rowHeight).toInt())
                }
                val colors = remember(surfaces.codeWellForeground, surfaces.codeWellBackground, theme, display.reverseVideo) {
                    TerminalPalette(surfaces.codeWellForeground, surfaces.codeWellBackground, theme.primary,
                        theme.primaryContainer, theme.tertiaryContainer, display.reverseVideo)
                }
                Box(Modifier.fillMaxSize().horizontalScroll(pan)) {
                    LazyColumn(Modifier.width(contentWidth).fillMaxHeight(), state = list) {
                        items(display.lines, key = TerminalLine::id) { line ->
                            val cursor = display.cursor?.takeIf { it.lineId == line.id }
                            val ranges = highlights[line.id].orEmpty()
                            val current = active?.ranges?.firstOrNull { it.lineId == line.id }
                            Canvas(Modifier.width(contentWidth).height(rowHeight).semantics {
                                if (line.text.isNotEmpty()) text = AnnotatedString(line.text)
                            }) {
                                drawIntoCanvas { painter.draw(it.nativeCanvas, line, cursor, colors, ranges, current) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private class TerminalPalette(fg: Color, bg: Color, cursor: Color, result: Color, active: Color, reverse: Boolean) {
    val ink = (if (reverse) bg else fg).toArgb()
    val paper = (if (reverse) fg else bg).toArgb()
    val cursor = cursor.toArgb()
    val result = result.copy(alpha = .68f).toArgb()
    val active = active.copy(alpha = .90f).toArgb()

    fun resolve(index: Int, foreground: Boolean): Int = when {
        index == TerminalStyle.DEFAULT -> if (foreground) ink else paper
        index >= TerminalStyle.RGB -> 0xff000000.toInt() or (index and 0xffffff)
        else -> palette[index]
    }

    companion object {
        // Standard xterm palette (terminal data, not app chrome colors).
        private val palette = IntArray(256) { index ->
            val rgb = when (index) {
                in 0..15 -> intArrayOf(0x000000, 0xcd0000, 0x00cd00, 0xcdcd00, 0x0000ee, 0xcd00cd, 0x00cdcd, 0xe5e5e5,
                    0x7f7f7f, 0xff0000, 0x00ff00, 0xffff00, 0x5c5cff, 0xff00ff, 0x00ffff, 0xffffff)[index]
                in 16..231 -> {
                    val cube = index - 16
                    fun channel(level: Int) = if (level == 0) 0 else 55 + 40 * level
                    (channel(cube / 36) shl 16) or (channel(cube / 6 % 6) shl 8) or channel(cube % 6)
                }
                else -> (8 + (index - 232) * 10) * 0x010101
            }
            0xff000000.toInt() or rgb
        }
    }
}

private class TerminalRowPainter(px: Float) {
    private val faces = Array(4) { Typeface.create(Typeface.MONOSPACE, it) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = px; typeface = faces[0] }
    val cellWidth = ceil(paint.measureText("M"))
    private val metrics = paint.fontMetrics
    val rowHeight = ceil(maxOf(metrics.bottom - metrics.top, px * 1.34f))
    private val baseline = (rowHeight - (metrics.ascent + metrics.descent)) / 2

    fun draw(canvas: android.graphics.Canvas, line: TerminalLine, cursor: TerminalCursor?, palette: TerminalPalette,
        ranges: List<TerminalSearchRange>, active: TerminalSearchRange?) {
        fun rect(from: Int, until: Int, color: Int) {
            paint.color = color
            canvas.drawRect(from * cellWidth, 0f, until * cellWidth, rowHeight, paint)
        }
        line.cells.forEachIndexed { col, cell ->
            if (cell != null && cell.columns > 0) {
                val inverse = cell.style.has(TerminalStyle.INVERSE)
                val bg = palette.resolve(if (inverse) cell.style.foreground else cell.style.background, inverse)
                if (bg != palette.paper) rect(col, col + cell.columns, bg)
            }
        }
        ranges.forEach { rect(it.start, it.end, if (it == active) palette.active else palette.result) }
        line.cells.forEachIndexed { col, cell ->
            if (cell == null || cell.columns == 0 || cell.text == " " || cell.style.has(TerminalStyle.HIDDEN)) return@forEachIndexed
            val style = cell.style
            val inverse = style.has(TerminalStyle.INVERSE)
            paint.typeface = faces[(if (style.has(TerminalStyle.BOLD)) 1 else 0) + (if (style.has(TerminalStyle.ITALIC)) 2 else 0)]
            paint.color = palette.resolve(if (inverse) style.background else style.foreground, !inverse)
            if (style.has(TerminalStyle.FAINT)) paint.alpha = 140
            paint.isUnderlineText = style.has(TerminalStyle.UNDERLINE)
            paint.isStrikeThruText = style.has(TerminalStyle.STRIKE)
            canvas.drawText(cell.text, col * cellWidth, baseline, paint)
        }
        if (cursor != null) {
            paint.color = palette.cursor
            val left = cursor.column * cellWidth
            val right = left + cellWidth
            val stroke = maxOf(1f, rowHeight / 11)
            when (cursor.shape) {
                TerminalCursorShape.BAR -> canvas.drawRect(left, 0f, left + stroke, rowHeight, paint)
                TerminalCursorShape.UNDERLINE -> canvas.drawRect(left, rowHeight - stroke, right, rowHeight, paint)
                TerminalCursorShape.BLOCK -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = stroke
                    canvas.drawRect(left + stroke / 2, stroke / 2, right - stroke / 2, rowHeight - stroke / 2, paint)
                    paint.style = Paint.Style.FILL
                }
            }
        }
    }
}
