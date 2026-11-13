package cn.edu.ustc.tokenworks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal sealed interface ChatTimelineRow {
    val key: String

    /** Which turn the row belongs to; drives the 8 dp / 24 dp vertical rhythm. */
    val turn: Long?

    data class Message(val item: ConversationItem, override val turn: Long?) : ChatTimelineRow {
        override val key: String get() = item.id
    }

    /** Three or more adjacent process events that stay individually readable when expanded. */
    data class ToolGroup(val items: List<ConversationItem>, val live: Boolean, override val turn: Long?) : ChatTimelineRow {
        override val key: String = "g${items.first().seqKey()}"
    }

    /** A whole finished turn folded behind one header, with its final answer kept outside. */
    data class TurnProcess(val items: List<ConversationItem>, val live: Boolean, override val turn: Long?) : ChatTimelineRow {
        override val key: String = "tp${items.first().seqKey()}"
    }

    data class TurnFootnote(val ofTurn: Long, val durationMillis: Long?, val steps: Int, val toolCalls: Int) : ChatTimelineRow {
        override val key: String get() = "ft$ofTurn"
        override val turn: Long get() = ofTurn
    }
}

internal data class ChatTimelineProjection(
    val rows: List<ChatTimelineRow>,
    val sourceToGroup: Map<String, String>,
)

private fun ConversationItem.seqKey(): String = seq.takeIf { it >= 0 }?.toString() ?: id

/**
 * Turn-aware row projection. A finished turn collapses its process (tools and pure reasoning) into
 * one header, keeps the final answer visible, and closes with a footnote; anything still streaming,
 * interrupted or errored stays fully expanded. Short process runs never become a header.
 */
internal fun projectChatTimeline(items: List<ConversationItem>, running: Boolean = false): ChatTimelineProjection {
    val rows = ArrayList<ChatTimelineRow>()
    val sourceToGroup = LinkedHashMap<String, String>()
    fun addGroup(row: ChatTimelineRow, members: List<ConversationItem>) {
        rows += row
        members.forEach { sourceToGroup[it.id] = row.key }
    }
    val segments = turnSegments(items)
    segments.forEachIndexed { index, segment ->
        val turn = segment.turn
        val segmentItems = segment.items
        val last = index == segments.lastIndex
        val answerIndex = segmentItems.indexOfLast { item ->
            item.role == ConversationRole.ASSISTANT && item.blocks.any { it.kind == BlockKind.TEXT && it.text.isNotBlank() }
        }
        val processItems = segmentItems.filter { it.isProcessOnly() }
        val processStaysBeforeAnswer = segmentItems.withIndex().all { (position, item) ->
            !item.isProcessOnly() || position < answerIndex
        }
        val foldable = !(running && last) && segmentItems.none { it.interrupted || it.hasError } &&
            segmentItems.all { it.seq >= 0 } && answerIndex > 0 && processItems.isNotEmpty() && processStaysBeforeAnswer
        if (foldable) {
            segmentItems.take(answerIndex).filterNot { it.isProcessOnly() }.forEach {
                rows += ChatTimelineRow.Message(it, turn)
            }
            addGroup(ChatTimelineRow.TurnProcess(processItems, live = false, turn = turn), processItems)
            rows += ChatTimelineRow.Message(segmentItems[answerIndex], turn)
            val times = segmentItems.mapNotNull { it.time }
            val duration = times.takeIf { it.size >= 2 && it.last() >= it.first() }?.let { it.last() - it.first() }
            val steps = segmentItems.mapNotNull { it.step }.distinct().size
            val tools = segmentItems.count { it.role == ConversationRole.TOOL }
            if (duration != null || steps > 0 || tools > 0) {
                rows += ChatTimelineRow.TurnFootnote(turn, duration, steps, tools)
            }
            return@forEachIndexed
        }
        var run = ArrayList<ConversationItem>()
        fun flushRun() {
            if (run.isEmpty()) return
            if (run.size >= 3) {
                addGroup(ChatTimelineRow.ToolGroup(run, live = running && last && run.any { it.live }, turn), run)
            } else {
                run.forEach { rows += ChatTimelineRow.Message(it, turn) }
            }
            run = ArrayList()
        }
        for (item in segmentItems) {
            if (item.isProcessOnly()) run += item
            else {
                flushRun()
                rows += ChatTimelineRow.Message(item, turn)
            }
        }
        flushRun()
    }
    return ChatTimelineProjection(rows, sourceToGroup)
}

/**
 * Consecutive items sharing a turn. User messages carry no turn on the wire, so they take the turn
 * of the following positioned event, which keeps a prompt with the answer it produced.
 */
private class TurnSegment(val turn: Long, val items: List<ConversationItem>)

private fun turnSegments(items: List<ConversationItem>): List<TurnSegment> {
    if (items.isEmpty()) return emptyList()
    val resolved = arrayOfNulls<Long>(items.size)
    var following: Long? = null
    for (index in items.indices.reversed()) {
        val turn = items[index].turn
        if (turn != null) following = turn
        resolved[index] = turn ?: following
    }
    var previous: Long? = null
    for (index in items.indices) {
        val value = resolved[index] ?: previous ?: 0L
        resolved[index] = value
        previous = value
    }
    val segments = ArrayList<TurnSegment>()
    var start = 0
    for (index in 1..items.size) {
        if (index == items.size || resolved[index] != resolved[start]) {
            segments += TurnSegment(resolved[start] ?: 0L, items.subList(start, index))
            start = index
        }
    }
    return segments
}

private fun ConversationItem.isProcessOnly(): Boolean {
    if (interrupted || hasError || blocks.isEmpty()) return false
    val allowed = when (role) {
        ConversationRole.TOOL -> blocks.all { it.kind == BlockKind.TEXT || it.kind == BlockKind.REASONING || it.kind == BlockKind.TOOL }
        ConversationRole.ASSISTANT -> blocks.all { it.kind == BlockKind.REASONING || it.kind == BlockKind.TOOL }
        else -> false
    }
    return allowed && blocks.any {
        it.text.isNotEmpty() || it.kind == BlockKind.TOOL && (it.name.isNotEmpty() || it.callId.isNotEmpty())
    }
}

private fun ChatTimelineRow.members(): List<ConversationItem>? = when (this) {
    is ChatTimelineRow.ToolGroup -> items
    is ChatTimelineRow.TurnProcess -> items
    is ChatTimelineRow.Message, is ChatTimelineRow.TurnFootnote -> null
}

private fun ChatTimelineRow.isExpanded(expandedSourceIds: Set<String>): Boolean =
    members()?.any { it.id in expandedSourceIds } == true

/** Keys are in exactly the renderer's order, excluding the caller's leading/trailing slots. */
internal fun ChatTimelineProjection.renderKeys(expandedSourceIds: Set<String>): List<String> = buildList {
    for (row in rows) {
        add(row.key)
        if (row.isExpanded(expandedSourceIds)) row.members()?.forEach { add(it.id) }
    }
}

/** Saved positions remain source IDs; collapsed sources resolve to their current group header. */
internal fun ChatTimelineProjection.renderKeyForSource(sourceId: String, expandedSourceIds: Set<String>): String? {
    val groupKey = sourceToGroup[sourceId] ?: return sourceId.takeIf { id ->
        rows.any { it is ChatTimelineRow.Message && it.item.id == id }
    }
    val group = rows.first { it.key == groupKey }
    return if (group.isExpanded(expandedSourceIds)) sourceId else groupKey
}

/** A header has no business identity; it anchors to the first real source, never a synthetic ID. */
internal fun ChatTimelineProjection.sourceIdForRenderKey(renderKey: String): String? {
    for (row in rows) {
        when (row) {
            is ChatTimelineRow.Message -> if (row.key == renderKey) return row.item.id
            is ChatTimelineRow.ToolGroup, is ChatTimelineRow.TurnProcess -> {
                val members = row.members().orEmpty()
                if (row.key == renderKey) return members.firstOrNull()?.id
                if (members.any { it.id == renderKey }) return renderKey
            }
            is ChatTimelineRow.TurnFootnote -> Unit
        }
    }
    return null
}

/** Rendering only. The active screen owns reading, expansion, paging and all business effects. */
@Composable
internal fun ChatTranscript(
    projection: ChatTimelineProjection,
    listState: LazyListState,
    expandedSourceIds: Set<String>,
    interactive: Boolean,
    leadingContent: LazyListScope.() -> Unit,
    trailingContent: LazyListScope.() -> Unit,
    onProcessExpansion: (List<String>, Boolean) -> Unit,
    onQuote: ((String) -> Unit)?,
    onMessageAction: ((String) -> Unit)?,
    onPreviewMedia: ((VisibleMediaReference) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val background = if (interactive) Modifier else Modifier.clearAndSetSemantics { }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
    LazyColumn(
        modifier = modifier.then(background).testTag("chat-list"),
        state = listState,
        userScrollEnabled = interactive,
        contentPadding = PaddingValues(horizontal = TokenWorksSpacing.gutter, vertical = TokenWorksSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.inTurn),
    ) {
        leadingContent()
        var previousTurn: Long? = null
        for (row in projection.rows) {
            val turnBreak = previousTurn != null && row.turn != null && row.turn != previousTurn
            previousTurn = row.turn ?: previousTurn
            val gap = if (turnBreak) Modifier.padding(top = TokenWorksSpacing.betweenTurns - TokenWorksSpacing.inTurn) else Modifier
            when (row) {
                is ChatTimelineRow.Message -> item(key = row.key, contentType = "message") {
                    Box(gap) {
                        ConversationMessage(row.item, if (interactive) onQuote else null,
                            if (interactive) onMessageAction else null, if (interactive) onPreviewMedia else null,
                            interactive = interactive)
                    }
                }
                is ChatTimelineRow.ToolGroup, is ChatTimelineRow.TurnProcess -> {
                    val expanded = row.isExpanded(expandedSourceIds)
                    item(key = row.key, contentType = "process") {
                        ProcessHeader(row, expanded, interactive, gap) {
                            onProcessExpansion(row.members().orEmpty().map { it.id }, !expanded)
                        }
                    }
                    if (expanded) items(row.members().orEmpty(), key = { it.id }, contentType = { "message" }) { item ->
                        ConversationMessage(item, if (interactive) onQuote else null,
                            if (interactive) onMessageAction else null, if (interactive) onPreviewMedia else null,
                            interactive = interactive)
                    }
                }
                is ChatTimelineRow.TurnFootnote -> item(key = row.key, contentType = "footnote") {
                    TurnFootnoteRow(row, gap)
                }
            }
        }
        trailingContent()
    }
}

@Composable
private fun ProcessHeader(
    row: ChatTimelineRow,
    expanded: Boolean,
    interactive: Boolean,
    gap: Modifier,
    onClick: () -> Unit,
) {
    val count = row.members().orEmpty().size
    val label = stringResource(R.string.workspace_process_summary, count)
    val expansion = stringResource(if (expanded) R.string.conversation_message_expanded else R.string.conversation_message_collapsed)
    val latestLive = (row as? ChatTimelineRow.ToolGroup)?.let { group -> group.items.lastOrNull { it.live } }
    Column(gap.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xs)) {
        if (interactive) TextButton(onClick, Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget)
            .testTag(row.key).semantics { stateDescription = expansion }) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(painterResource(R.drawable.ic_chevron), contentDescription = null,
                modifier = Modifier.rotate(if (expanded) 90f else 0f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Text(label, Modifier.padding(vertical = TokenWorksSpacing.md),
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!expanded && latestLive != null) {
            val preview = remember(latestLive) {
                // This is a bounded plain-text glance, not a second Markdown projection/cache.
                buildString {
                    for (block in latestLive.blocks) {
                        val text = block.text.ifEmpty { if (block.kind == BlockKind.TOOL) block.name else "" }
                        if (text.isEmpty()) continue
                        if (isNotEmpty()) append('\n')
                        append(text, 0, minOf(text.length, 1024 - length))
                        if (length >= 1024) break
                    }
                }
            }
            Text(preview.ifEmpty { stringResource(R.string.conversation_message_receiving) },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TurnFootnoteRow(row: ChatTimelineRow.TurnFootnote, gap: Modifier) {
    val duration = row.durationMillis?.let { formatDuration(it) }
    val text = if (duration != null) {
        stringResource(R.string.workspace_turn_footnote, duration, row.steps, row.toolCalls)
    } else {
        stringResource(R.string.workspace_turn_footnote_brief, row.steps, row.toolCalls)
    }
    Text(
        text = text,
        modifier = gap.fillMaxWidth(),
        style = MaterialTheme.typography.labelSmall,
        color = tokenWorksSurfaces().textTertiary,
    )
}

@Composable
private fun formatDuration(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    if (seconds < 60) return stringResource(R.string.workspace_duration_seconds, seconds)
    val minutes = seconds / 60
    val rest = seconds % 60
    if (minutes < 60) return stringResource(R.string.workspace_duration_minutes, minutes, rest)
    return stringResource(R.string.workspace_duration_hours, minutes / 60, minutes % 60)
}
