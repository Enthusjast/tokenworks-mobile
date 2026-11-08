package cn.edu.ustc.tokenworks

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
@Composable
private fun readingBodyStyle(style: TextStyle = MaterialTheme.typography.bodyLarge): TextStyle {
    val scale = LocalTokenWorksReadingStyle.current.bodyScale
    return style.copy(fontSize = style.fontSize * scale, lineHeight = style.lineHeight * scale)
}

private fun safeWebTarget(value: String): Boolean = runCatching {
    val uri = Uri.parse(value)
    uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && value.none { it.isISOControl() }
}.getOrDefault(false)

private data class PreparedMessage(val document: MessageMarkdownDocument, val links: Map<MessageTextRange, String>, val excessLinks: Boolean)

/** One formatting generation per continuous append stream, scoped to this composition identity. */
private class MessageFormattingContinuity(var source: String, var generation: Int = 0) {
    fun update(next: String) {
        if (next == source) return
        if (!next.startsWith(source)) generation++
        source = next
    }
}

private fun MessageMarkdownDocument.forEachSpan(action: (MessageInline) -> Unit) {
    for (block in blocks) when (block) {
        is MessageMarkdownBlock.Paragraph -> block.spans.forEach(action)
        is MessageMarkdownBlock.Heading -> block.spans.forEach(action)
        is MessageMarkdownBlock.ListItem -> block.spans.forEach(action)
        is MessageMarkdownBlock.Quote -> block.spans.forEach(action)
        is MessageMarkdownBlock.Table -> {
            block.headers.forEach { it.forEach(action) }
            block.rows.forEach { row -> row.forEach { it.forEach(action) } }
        }
        else -> Unit
    }
}

/** Presentation only. No HTML, remote resources, automatic launches or generated intents. */
@Composable
internal fun RichMessageText(text: String, initial: Int = 16 * 1024, interactive: Boolean = true) {
    val continuity = remember { MessageFormattingContinuity(text) }
    continuity.update(text)
    var limit by remember { mutableIntStateOf(initial.coerceAtLeast(1)) }
    var raw by remember(continuity.generation) { mutableStateOf(false) }
    var requestedLink by remember(text, interactive) { mutableStateOf<String?>(null) }
    var linkFailed by remember(interactive) { mutableStateOf(false) }
    val end = minOf(limit, text.length).let { if (it in 1 until text.length && text[it - 1].isHighSurrogate() && text[it].isLowSurrogate()) it - 1 else it }
    var prepared by remember(continuity.generation) { mutableStateOf<PreparedMessage?>(null) }
    LaunchedEffect(text, end, continuity.generation) {
        prepared = withContext(Dispatchers.Default) {
            val document = parseMessageMarkdown(text, end)
            val links = linkedMapOf<MessageTextRange, String>()
            var excess = false
            document.forEachSpan { span -> span.link?.let { range ->
                if (range !in links && links.size <= 128) {
                    val target = range.text(text)
                    if (safeWebTarget(target)) {
                        if (links.size < 128) links[range] = target else excess = true
                    }
                }
            } }
            PreparedMessage(document, links, excess)
        }
    }
    // An append may retain the single last formatted projection until the new one is ready.
    // Replacement/shortening starts a new generation and cannot retain old links or content.
    val current = prepared?.takeIf { (it.document.source === text || text.startsWith(it.document.source)) && it.document.visibleEnd <= end }
    val uriHandler = LocalUriHandler.current
    Column(Modifier.then(if (interactive) Modifier else Modifier.clearAndSetSemantics { }),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (current == null || raw) {
            MessageSelection(interactive) { Text(text.substring(0, end), style = readingBodyStyle()) }
        } else for (block in current.document.blocks) key(block.range.start) {
            when (block) {
                is MessageMarkdownBlock.Paragraph -> MessageInlineText(current, block.spans, interactive = interactive, onLink = { requestedLink = it })
                is MessageMarkdownBlock.Plain -> MessageSelection(interactive) { Text(block.range.text(current.document.source), style = readingBodyStyle()) }
                is MessageMarkdownBlock.Heading -> MessageInlineText(current, block.spans,
                    Modifier.semantics { heading() }, when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }, interactive = interactive, onLink = { requestedLink = it })
                is MessageMarkdownBlock.ListItem -> Row(Modifier.padding(start = (block.depth * 12).dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(block.marker, style = readingBodyStyle())
                    MessageInlineText(current, block.spans, Modifier.weight(1f), interactive = interactive, onLink = { requestedLink = it })
                }
                is MessageMarkdownBlock.Quote -> {
                    val color = MaterialTheme.colorScheme.outlineVariant
                    MessageInlineText(current, block.spans, Modifier.drawBehind {
                        drawLine(color, Offset.Zero, Offset(0f, size.height), 2.dp.toPx())
                    }.padding(start = 12.dp), interactive = interactive, onLink = { requestedLink = it })
                }
                is MessageMarkdownBlock.Code -> MessageCode(current.document.source, block,
                    current.document.visibleEnd < current.document.source.length, text, end, interactive)
                is MessageMarkdownBlock.Table -> MessageTable(current, block, interactive) { requestedLink = it }
            }
        }
        if (current?.excessLinks == true && !raw) Text(stringResource(R.string.chat_link_limit), style = MaterialTheme.typography.bodySmall)
        if (interactive && current?.document?.formatted == true) TextButton({ raw = !raw }, Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(if (raw) R.string.chat_formatted else R.string.chat_raw))
        }
        if (interactive && end < text.length) TextButton({ limit = (limit.toLong() + 16 * 1024).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }, Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.chat_show_more))
        }
    }
    if (interactive) requestedLink?.let { target ->
        AlertDialog(onDismissRequest = { requestedLink = null }, title = { Text(stringResource(R.string.chat_link_title)) },
            text = { SelectionContainer { Text(target) } },
            confirmButton = { TextButton({ requestedLink = null; linkFailed = !safeWebTarget(target) || runCatching { uriHandler.openUri(target) }.isFailure }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.chat_link_open)) } },
            dismissButton = { TextButton({ requestedLink = null }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.chat_link_cancel)) } })
    }
    if (interactive && linkFailed) AlertDialog(onDismissRequest = { linkFailed = false }, title = { Text(stringResource(R.string.chat_link_failed)) },
        confirmButton = { TextButton({ linkFailed = false }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.chat_link_cancel)) } })
}

@Composable
private fun MessageSelection(interactive: Boolean, content: @Composable () -> Unit) {
    if (interactive) SelectionContainer(content = content) else content()
}

@Composable
private fun MessageInlineText(message: PreparedMessage, spans: List<MessageInline>, modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge, interactive: Boolean, onLink: (String) -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    val latestLink by rememberUpdatedState(onLink)
    val reading = LocalTokenWorksReadingStyle.current
    val formatted = remember(message, spans, color, reading.codeScale, style.fontSize, interactive) {
        buildAnnotatedString {
            spans.forEach { span ->
                val start = length
                append(message.document.source, span.range.start, span.range.end)
                if (span.style and MESSAGE_BOLD != 0) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), start, length)
                if (span.style and MESSAGE_ITALIC != 0) addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
                if (span.style and MESSAGE_CODE != 0) addStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = style.fontSize * reading.codeScale), start, length)
                message.links[span.link]?.let { target ->
                    if (interactive) addLink(LinkAnnotation.Clickable(target,
                        TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline)),
                        LinkInteractionListener { latestLink(target) }), start, length)
                    else addStyle(SpanStyle(color = color, textDecoration = TextDecoration.Underline), start, length)
                }
            }
        }
    }
    val actions = remember(message, spans, context, interactive) {
        if (!interactive) emptyList() else spans.mapNotNull { message.links[it.link] }.distinct().map { target ->
            CustomAccessibilityAction(context.getString(R.string.chat_link_accessibility, target)) { latestLink(target); true }
        }
    }
    MessageSelection(interactive) {
        Text(formatted, modifier.then(if (interactive) Modifier.semantics { customActions = actions } else Modifier), style = readingBodyStyle(style))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessageCode(source: String, block: MessageMarkdownBlock.Code, truncated: Boolean,
    latestSource: String, latestEnd: Int, interactive: Boolean) {
    val clipboard = LocalClipboardManager.current
    val reading = LocalTokenWorksReadingStyle.current
    val surfaces = tokenWorksSurfaces()
    val content = remember(source, block.content) { block.content.text(source) }
    var copied by remember(content) { mutableStateOf(false) }
    var copyFailed by remember(content) { mutableStateOf(false) }
    val currentSource by rememberUpdatedState(latestSource)
    val currentEnd by rememberUpdatedState(latestEnd)
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
    val complete = block.closed || !truncated
    Surface(
        color = surfaces.codeWellBackground,
        shape = tokenWorksSquircle(TokenWorksRadius.md),
        border = BorderStroke(1.dp, surfaces.codeWellBorder),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().background(surfaces.codeWellHeader)
                    .padding(horizontal = TokenWorksSpacing.md, vertical = TokenWorksSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(block.language?.text(source) ?: stringResource(R.string.chat_code), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall, color = surfaces.codeWellHeaderForeground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (interactive) TextButton({
                    copied = false
                    try {
                        val currentBlock = if (currentSource == source) block else
                            parseMessageMarkdown(currentSource, currentEnd).blocks.firstOrNull {
                                it is MessageMarkdownBlock.Code && it.range.start == block.range.start
                            } as? MessageMarkdownBlock.Code
                        if (currentBlock != null && (currentBlock.closed || currentEnd == currentSource.length)) {
                            clipboard.setText(AnnotatedString(currentBlock.content.text(currentSource)))
                            copyFailed = false
                            copied = true
                        } else copyFailed = true
                    }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { copyFailed = true }
                }, enabled = complete && latestSource == source,
                    colors = ButtonDefaults.textButtonColors(contentColor = surfaces.codeWellHeaderForeground),
                    modifier = Modifier.heightIn(min = 48.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text(stringResource(if (copied) R.string.chat_copied else if (block.closed) R.string.chat_copy_code else R.string.chat_copy_visible_code),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            Box(Modifier.fillMaxWidth().padding(TokenWorksSpacing.md)) {
                MessageSelection(interactive) { Text(content,
                    if (reading.wrapCode) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState(), enabled = interactive),
                    color = surfaces.codeWellForeground,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 13.sp * reading.codeScale,
                        lineHeight = 20.sp * reading.codeScale,
                    ),
                    softWrap = reading.wrapCode) }
            }
            if (!complete) Text(stringResource(R.string.chat_code_incomplete),
                modifier = Modifier.padding(horizontal = TokenWorksSpacing.md),
                style = MaterialTheme.typography.bodySmall, color = surfaces.codeWellHeaderForeground)
            if (copyFailed) Text(stringResource(R.string.chat_copy_failed),
                modifier = Modifier.padding(horizontal = TokenWorksSpacing.md).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun MessageTable(message: PreparedMessage, block: MessageMarkdownBlock.Table, interactive: Boolean, onLink: (String) -> Unit) {
    if (block.headers.isEmpty()) {
        MessageSelection(interactive) { Text(block.range.text(message.document.source), style = readingBodyStyle()) }
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellWidth = if (constraints.hasBoundedWidth) (maxWidth / block.headers.size).coerceIn(120.dp, 180.dp) else 180.dp
        Box(Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.horizontalScroll(rememberScrollState(), enabled = interactive)) {
                MessageTableRow(message, block.headers, true, cellWidth, 0, interactive, onLink)
                block.rows.forEachIndexed { index, row -> MessageTableRow(message, row, false, cellWidth, index + 1, interactive, onLink) }
            }
        }
    }
}

@Composable
private fun MessageTableRow(message: PreparedMessage, cells: List<List<MessageInline>>, header: Boolean,
    cellWidth: Dp, rowIndex: Int, interactive: Boolean, onLink: (String) -> Unit) {
    val border = MaterialTheme.colorScheme.outlineVariant
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(Modifier.height(IntrinsicSize.Min).drawWithContent {
        drawContent()
        if (rowIndex > 0) drawLine(border, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx())
    }) {
        cells.forEachIndexed { column, spans ->
            Surface(Modifier.width(cellWidth).fillMaxHeight().testTag("message-table-cell-$rowIndex-$column").drawWithContent {
                drawContent()
                if (column > 0) {
                    val x = if (rtl) size.width else 0f
                    drawLine(border, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                }
            }, color = if (header) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface) {
                MessageInlineText(message, spans, Modifier.fillMaxWidth().padding(12.dp),
                    if (header) MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold) else MaterialTheme.typography.bodyLarge, interactive, onLink)
            }
        }
    }
}
