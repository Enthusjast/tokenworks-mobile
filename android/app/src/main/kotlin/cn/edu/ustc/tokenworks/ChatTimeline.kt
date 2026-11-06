package cn.edu.ustc.tokenworks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import io.github.enthusjast.tokenworks.protocol.VisibleMediaReference
import io.github.enthusjast.tokenworks.session.BlockKind
import io.github.enthusjast.tokenworks.session.ConversationItem
import io.github.enthusjast.tokenworks.session.ConversationRole

internal sealed interface ChatTimelineRow {
    val key: String

    data class Message(val item: ConversationItem) : ChatTimelineRow {
        override val key: String get() = item.id
    }

    data class ProcessGroup(val items: List<ConversationItem>) : ChatTimelineRow {
        override val key: String = "process:${items.first().id}"
    }
}

internal data class ChatTimelineProjection(
    val rows: List<ChatTimelineRow>,
    val sourceToGroup: Map<String, String>,
)

/** Only adjacent, nonempty process-only messages may lose their individual collapsed rows. */
internal fun projectChatTimeline(items: List<ConversationItem>): ChatTimelineProjection {
    val rows = ArrayList<ChatTimelineRow>()
    val sourceToGroup = LinkedHashMap<String, String>()
    var group: MutableList<ConversationItem>? = null
    fun flush() {
        val pending = group ?: return
        val row = ChatTimelineRow.ProcessGroup(pending)
        rows += row
        pending.forEach { sourceToGroup[it.id] = row.key }
        group = null
    }
    for (item in items) {
        if (item.isProcessOnly()) {
            val pending = group ?: ArrayList<ConversationItem>().also { group = it }
            pending += item
        } else {
            flush()
            rows += ChatTimelineRow.Message(item)
        }
    }
    flush()
    return ChatTimelineProjection(rows, sourceToGroup)
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

private fun ChatTimelineRow.ProcessGroup.isExpanded(expandedSourceIds: Set<String>): Boolean =
    items.any { it.id in expandedSourceIds }

/** Keys are in exactly the renderer's order, excluding the caller's leading/trailing slots. */
internal fun ChatTimelineProjection.renderKeys(expandedSourceIds: Set<String>): List<String> = buildList {
    for (row in rows) {
        add(row.key)
        if (row is ChatTimelineRow.ProcessGroup && row.isExpanded(expandedSourceIds)) {
            row.items.forEach { add(it.id) }
        }
    }
}

/** Saved positions remain source IDs; collapsed sources resolve to their current process header. */
internal fun ChatTimelineProjection.renderKeyForSource(sourceId: String, expandedSourceIds: Set<String>): String? {
    val groupKey = sourceToGroup[sourceId]
    if (groupKey != null) {
        val group = rows.first { it.key == groupKey } as ChatTimelineRow.ProcessGroup
        return if (group.isExpanded(expandedSourceIds)) sourceId else groupKey
    }
    return sourceId.takeIf { id -> rows.any { it is ChatTimelineRow.Message && it.item.id == id } }
}

/** A header has no business identity; it anchors to the first real source, never a synthetic ID. */
internal fun ChatTimelineProjection.sourceIdForRenderKey(renderKey: String): String? {
    for (row in rows) when (row) {
        is ChatTimelineRow.Message -> if (row.key == renderKey) return row.item.id
        is ChatTimelineRow.ProcessGroup -> {
            if (row.key == renderKey) return row.items.first().id
            if (row.items.any { it.id == renderKey }) return renderKey
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
        modifier = modifier.then(background),
        state = listState,
        userScrollEnabled = interactive,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leadingContent()
        for (row in projection.rows) when (row) {
            is ChatTimelineRow.Message -> item(key = row.key, contentType = "message") {
                ConversationMessage(row.item, if (interactive) onQuote else null,
                    if (interactive) onMessageAction else null, if (interactive) onPreviewMedia else null,
                    interactive = interactive)
            }
            is ChatTimelineRow.ProcessGroup -> {
                val expanded = row.isExpanded(expandedSourceIds)
                item(key = row.key, contentType = "process") {
                    ProcessHeader(row, expanded, interactive) {
                        onProcessExpansion(row.items.map { it.id }, !expanded)
                    }
                }
                if (expanded) items(row.items, key = { it.id }, contentType = { "message" }) { item ->
                    ConversationMessage(item, if (interactive) onQuote else null,
                        if (interactive) onMessageAction else null, if (interactive) onPreviewMedia else null,
                        interactive = interactive)
                }
            }
        }
        trailingContent()
    }
}

@Composable
private fun ProcessHeader(row: ChatTimelineRow.ProcessGroup, expanded: Boolean, interactive: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.conversation_process_count, row.items.size)
    val expansion = stringResource(if (expanded) R.string.conversation_message_expanded else R.string.conversation_message_collapsed)
    val latestLive = row.items.lastOrNull { it.live }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (interactive) TextButton(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .testTag(row.key).semantics { stateDescription = expansion }) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(painterResource(R.drawable.ic_chevron), contentDescription = null,
                modifier = Modifier.rotate(if (expanded) 90f else 0f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
