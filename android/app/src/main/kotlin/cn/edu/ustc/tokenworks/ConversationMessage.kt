package cn.edu.ustc.tokenworks

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.enthusjast.tokenworks.protocol.VisibleMediaReference
import io.github.enthusjast.tokenworks.session.BlockKind
import io.github.enthusjast.tokenworks.session.ConversationItem
import io.github.enthusjast.tokenworks.session.ConversationRole
import io.github.enthusjast.tokenworks.session.DisplayBlock
import kotlinx.coroutines.CancellationException

/** Actions use only the current display text; a quote is an editable local draft, not a server relation. */
@Composable
internal fun ConversationMessage(item: ConversationItem, onQuote: ((String) -> Unit)?, onMessageAction: ((String) -> Unit)? = null,
    onPreviewMedia: ((VisibleMediaReference) -> Unit)? = null, interactive: Boolean = true) {
    val currentItem by rememberUpdatedState(item)
    val currentOnQuote by rememberUpdatedState(onQuote)
    val currentOnMessageAction by rememberUpdatedState(onMessageAction)
    val currentOnPreviewMedia by rememberUpdatedState(onPreviewMedia)
    val clipboard = LocalClipboardManager.current
    var menuExpanded by remember(item.id, interactive) { mutableStateOf(false) }
    var copyFailed by remember(item.id, interactive) { mutableStateOf(false) }
    val roleLabel = stringResource(when (item.role) {
        ConversationRole.USER -> R.string.conversation_message_role_user
        ConversationRole.ASSISTANT -> R.string.conversation_message_role_assistant
        ConversationRole.TOOL -> R.string.conversation_message_role_tool
        ConversationRole.NOTICE -> R.string.conversation_message_role_notice
    })
    val moreLabel = stringResource(R.string.conversation_message_more_description)
    val user = item.role == ConversationRole.USER
    val copy: () -> Unit = {
        copyFailed = false
        try {
            clipboard.setText(AnnotatedString(currentItem.blocks.joinToString("\n\n") { it.text }))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            copyFailed = true
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().then(if (interactive) Modifier else Modifier.clearAndSetSemantics { })) {
        val width = if (user) Modifier.widthIn(max = minOf(640.dp, maxWidth * 0.88f)) else Modifier.fillMaxWidth()
        val messageModifier = width.testTag("conversation-message:${item.id}").then(if (interactive) Modifier
            .semantics {
                isTraversalGroup = true
                contentDescription = roleLabel
                onLongClick(label = moreLabel) { menuExpanded = true; true }
            }
            .conversationMessageLongPress(item.id) { menuExpanded = true }
            else Modifier.clearAndSetSemantics { })
        val content: @Composable () -> Unit = {
            Column(Modifier.padding(horizontal = if (user) 12.dp else 0.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.hasError) Text(stringResource(if (item.role == ConversationRole.TOOL)
                    R.string.conversation_message_tool_error else R.string.conversation_message_reply_error),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                if (item.live && item.blocks.all { it.text.isEmpty() && it.media == null && it.name.isEmpty() && it.callId.isEmpty() }) Text(
                    stringResource(R.string.conversation_message_preparing), style = MaterialTheme.typography.bodyLarge)
                for ((index, block) in item.blocks.withIndex()) key(item.id, index) {
                    when (block.kind) {
                        BlockKind.TEXT -> RichMessageText(block.text, interactive = interactive)
                        BlockKind.REASONING -> ConversationMessageDetail(item.id, index, block, reasoning = true,
                            forceExpanded = item.hasError, interactive = interactive)
                        BlockKind.TOOL -> ConversationMessageDetail(item.id, index, block, reasoning = false,
                            forceExpanded = item.hasError, interactive = interactive)
                        BlockKind.IMAGE -> {
                            val reference = block.media!!
                            val label = stringResource(R.string.media_message_preview, reference.manifest.name)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(reference.manifest.name, style = MaterialTheme.typography.bodyMedium)
                                Text(fileSizeText(reference.manifest.sizeBytes), style = MaterialTheme.typography.bodySmall)
                                if (interactive) TextButton({
                                    if (currentItem.persisted && !currentItem.live && currentItem.blocks.any { it.media === reference }) currentOnPreviewMedia?.invoke(reference)
                                }, enabled = item.persisted && !item.live && onPreviewMedia != null,
                                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = label }) { Text(label) }
                            }
                        }
                        BlockKind.OTHER -> {
                            if (block.text.isNotEmpty()) RichMessageText(block.text, interactive = interactive)
                            else Text(stringResource(R.string.conversation_message_other),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (item.live) Text(stringResource(R.string.conversation_message_receiving),
                    style = MaterialTheme.typography.labelMedium)
                if (item.interrupted) Text(stringResource(R.string.conversation_message_interrupted),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                if (!item.persisted && !item.live) Text(stringResource(R.string.conversation_message_unconfirmed),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (interactive) Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(copy, Modifier.heightIn(min = 48.dp), enabled = item.blocks.any { it.text.isNotEmpty() },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                        Text(stringResource(R.string.conversation_message_copy_short))
                    }
                    Box {
                        IconButton({ menuExpanded = true }, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                            Icon(painterResource(R.drawable.ic_more), moreLabel, Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.conversation_message_copy)) },
                                modifier = Modifier.heightIn(min = 48.dp), enabled = item.blocks.any { it.text.isNotEmpty() },
                                onClick = { menuExpanded = false; copy() })
                            if (onQuote != null) DropdownMenuItem(
                                text = { Text(stringResource(R.string.conversation_message_quote)) },
                                modifier = Modifier.heightIn(min = 48.dp), enabled = item.blocks.any { it.text.isNotEmpty() }, onClick = {
                                    menuExpanded = false
                                    currentOnQuote?.invoke(currentItem.blocks.joinToString("\n\n") { it.text })
                                })
                            if (onMessageAction != null && item.persisted && !item.live && (item.role == ConversationRole.USER || item.role == ConversationRole.ASSISTANT)) DropdownMenuItem(
                                text = { Text(stringResource(R.string.media_message_action)) }, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                                    menuExpanded = false
                                    if (currentItem.persisted && !currentItem.live && (currentItem.role == ConversationRole.USER || currentItem.role == ConversationRole.ASSISTANT)) currentOnMessageAction?.invoke(currentItem.id)
                                })
                        }
                    }
                }
                if (copyFailed) Text(stringResource(R.string.conversation_message_copy_failed),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
            if (user) Surface(messageModifier, color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.medium, content = content)
            else Column(messageModifier) { content() }
        }
    }
}

/** A lightweight disclosure, never an inferred tool execution status or duration. */
@Composable
private fun ConversationMessageDetail(itemId: String, index: Int, block: DisplayBlock, reasoning: Boolean,
    forceExpanded: Boolean, interactive: Boolean) {
    var expanded by remember(itemId, index) { mutableStateOf(false) }
    val open = forceExpanded || expanded
    val title = if (reasoning) stringResource(R.string.conversation_message_reasoning)
        else block.name.ifEmpty { stringResource(R.string.conversation_message_tool) }
    val expansion = stringResource(if (open) R.string.conversation_message_expanded else R.string.conversation_message_collapsed)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (interactive && !forceExpanded) TextButton({ expanded = !expanded }, Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics { stateDescription = expansion }) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Icon(painterResource(R.drawable.ic_chevron), contentDescription = null,
                modifier = Modifier.size(20.dp).rotate(if (open) 90f else 0f))
        } else Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (open) {
            if (!reasoning && block.callId.isNotEmpty()) Text(
                stringResource(R.string.conversation_message_call_id, block.callId), style = MaterialTheme.typography.bodySmall)
            if (block.text.isNotEmpty()) RichMessageText(block.text, if (reasoning) 16 * 1024 else 4 * 1024,
                interactive = interactive)
        }
    }
}

/** Observe before child text selection without turning ordinary message taps into actions. */
private fun Modifier.conversationMessageLongPress(itemId: String, onLongPress: () -> Unit): Modifier = pointerInput(itemId) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val cancelled = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var finished = false
            while (!finished) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pointer = event.changes.firstOrNull { it.id == down.id }
                finished = pointer == null || !pointer.pressed ||
                    (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                    event.changes.count { it.pressed } > 1
            }
            true
        }
        if (cancelled == null) {
            onLongPress()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
}
