package cn.edu.ustc.tokenworks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException

/** Actions use only the current display text; a quote is an editable local draft, not a server relation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConversationMessage(item: ConversationItem, onQuote: ((String) -> Unit)?, onMessageAction: ((String) -> Unit)? = null,
    onPreviewMedia: ((NativeImageReference) -> Unit)? = null, interactive: Boolean = true,
    onPreviewGallery: ((List<NativeImageReference>, Int) -> Unit)? = null,
    onPreviewFile: ((FilesPreviewLink) -> Unit)? = null,
    usage: UsageCounts? = null,
    onSelectText: ((String) -> Unit)? = null) {
    val currentItem by rememberUpdatedState(item)
    val currentOnQuote by rememberUpdatedState(onQuote)
    val currentOnMessageAction by rememberUpdatedState(onMessageAction)
    val currentOnSelectText by rememberUpdatedState(onSelectText)
    val currentOnPreviewMedia by rememberUpdatedState(onPreviewMedia)
    val currentOnPreviewGallery by rememberUpdatedState(onPreviewGallery)
    val gallery = remember(item) { item.blocks.mapNotNull { it.media } }
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
    val timestamp = remember(item.time, locale) {
        item.time?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(it)) }
    }
    val hasText = item.blocks.any { it.text.isNotBlank() }
    fun originalText() = currentItem.blocks.joinToString("\n\n") { it.text }
    val clipboard = LocalClipboardManager.current
    var menuExpanded by remember(item.id, interactive) { mutableStateOf(false) }
    var copyState by remember(item.id, interactive) { mutableStateOf<Int?>(null) }
    val roleLabel = stringResource(when (item.role) {
        ConversationRole.USER -> R.string.conversation_message_role_user
        ConversationRole.ASSISTANT -> R.string.conversation_message_role_assistant
        ConversationRole.TOOL -> R.string.conversation_message_role_tool
        ConversationRole.NOTICE -> R.string.conversation_message_role_notice
    })
    val moreLabel = stringResource(R.string.conversation_message_more_description)
    val user = item.role == ConversationRole.USER
    val copy: () -> Unit = {
        try {
            clipboard.setText(AnnotatedString(originalText()))
            copyState = R.string.conversation_message_copied
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            copyState = R.string.conversation_message_copy_failed
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().then(if (interactive) Modifier else Modifier.clearAndSetSemantics { })) {
        // A prompt never spans a wall of text, and long answers stop at the reading measure.
        val width = if (user) Modifier.widthIn(max = minOf(TokenWorksSpacing.readingWidth, maxWidth * 0.82f))
        else Modifier.widthIn(max = TokenWorksSpacing.readingWidth).fillMaxWidth()
        val messageModifier = width.testTag("conversation-message:${item.id}").then(if (interactive) Modifier
            .semantics {
                isTraversalGroup = true
                onLongClick(label = moreLabel) { menuExpanded = true; true }
            }
            .conversationMessageLongPress(item.id) { menuExpanded = true }
            else Modifier.clearAndSetSemantics { })
        val content: @Composable () -> Unit = {
            Column(Modifier.padding(horizontal = if (user) TokenWorksSpacing.lg else 0.dp, vertical = TokenWorksSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.inTurn)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                    Text(roleLabel, style = MaterialTheme.typography.labelMedium,
                        color = if (user) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary)
                    timestamp?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
                if (item.live && item.blocks.all { it.text.isEmpty() && it.media == null && it.name.isEmpty() && it.callId.isEmpty() }) Text(
                    stringResource(R.string.conversation_message_preparing), style = MaterialTheme.typography.bodyLarge)
                for ((index, block) in item.blocks.withIndex()) key(item.id, index) {
                    when (block.kind) {
                        BlockKind.TEXT -> RichMessageText(block.text, interactive = interactive, onQuoteCode = onQuote,
                            onPreviewFile = onPreviewFile)
                        BlockKind.REASONING -> ConversationMessageDetail(item.id, index, block, reasoning = true,
                            forceExpanded = item.hasError, interactive = interactive)
                        BlockKind.TOOL -> ConversationMessageDetail(item.id, index, block, reasoning = false,
                            forceExpanded = item.hasError, interactive = interactive)
                        BlockKind.IMAGE -> {
                            val reference = block.media!!
                            val name = reference.name ?: stringResource(R.string.chat_audit_image)
                            val label = stringResource(R.string.media_message_preview, name)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.chat_audit_media_dimensions, name, reference.width, reference.height, reference.bytes),
                                    style = MaterialTheme.typography.bodyMedium)
                                if (interactive && (onPreviewGallery != null || onPreviewMedia != null)) TextButton({
                                    if (currentItem.persisted && !currentItem.live) {
                                        val images = currentItem.blocks.mapNotNull { it.media }
                                        val at = images.indexOfFirst { it === reference }
                                        if (at >= 0) currentOnPreviewGallery?.invoke(images, at) ?: currentOnPreviewMedia?.invoke(reference)
                                    }
                                }, enabled = item.persisted && !item.live && gallery.isNotEmpty(),
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
                if (item.hasError) Text(stringResource(if (item.role == ConversationRole.TOOL)
                    R.string.conversation_message_tool_error else R.string.conversation_message_reply_error),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (usage != null && item.role == ConversationRole.ASSISTANT) {
                    val number = remember(locale) { NumberFormat.getIntegerInstance(locale) }
                    Text(stringResource(R.string.chat_audit_tokens, number.format(usage.input), number.format(usage.output)),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (usage.cacheRead > 0 || usage.cacheWrite > 0) Text(
                        stringResource(R.string.chat_audit_cache_tokens, number.format(usage.cacheRead), number.format(usage.cacheWrite)),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (interactive) Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(copy, Modifier.heightIn(min = 48.dp), enabled = item.blocks.any { it.text.isNotEmpty() },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                        Text(stringResource(R.string.conversation_message_copy_short), style = MaterialTheme.typography.labelSmall)
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
                                    currentOnQuote?.invoke(originalText())
                                })
                            if (onSelectText != null) DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_audit_select_text)) }, modifier = Modifier.heightIn(min = 48.dp),
                                enabled = hasText, onClick = {
                                    menuExpanded = false
                                    if (currentItem.blocks.any { it.text.isNotBlank() }) currentOnSelectText?.invoke(currentItem.id)
                                })
                            if (onMessageAction != null && item.persisted && !item.live &&
                                (item.role == ConversationRole.USER || item.role == ConversationRole.ASSISTANT)) DropdownMenuItem(
                                text = { Text(stringResource(R.string.media_message_action)) }, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                                    menuExpanded = false
                                    if (currentItem.persisted && !currentItem.live &&
                                        (currentItem.role == ConversationRole.USER || currentItem.role == ConversationRole.ASSISTANT))
                                        currentOnMessageAction?.invoke(currentItem.id)
                                })
                        }
                    }
                }
                copyState?.let { notice ->
                    Text(stringResource(notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (notice == R.string.conversation_message_copy_failed) MaterialTheme.colorScheme.error
                        else tokenWorksSurfaces().success,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
            if (user) Surface(messageModifier, color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = tokenWorksSquircle(TokenWorksRadius.lg), content = content)
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
    val tone = if (reasoning) null else toolTone(block.name)
    val titleStyle = if (reasoning) MaterialTheme.typography.labelLarge
    else MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace)
    Column(verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
        if (interactive && !forceExpanded) TextButton({ expanded = !expanded }, Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics { stateDescription = expansion }) {
            ToolToneMark(tone)
            Text(title, Modifier.weight(1f), style = titleStyle)
            Icon(painterResource(R.drawable.ic_chevron), contentDescription = null,
                modifier = Modifier.size(TokenWorksSpacing.iconSize).rotate(if (open) 90f else 0f))
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
            ToolToneMark(tone)
            Text(title, style = titleStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(visible = open) {
            Column(verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                if (!reasoning && block.callId.isNotEmpty()) Text(
                    stringResource(R.string.conversation_message_call_id, block.callId), style = MaterialTheme.typography.bodySmall)
                if (block.text.isNotEmpty()) RichMessageText(block.text, if (reasoning) 16 * 1024 else 4 * 1024,
                    interactive = interactive)
            }
        }
    }
}

/** Display-only grouping of tool names; it never implies execution status or success. */
internal enum class ToolTone { TERMINAL, EDIT, READ, SEARCH, WEB, AGENT, PLAN, NEUTRAL }

internal fun toolTone(name: String): ToolTone = when (name.lowercase().trim()) {
    "bash", "pwsh", "shell", "terminal" -> ToolTone.TERMINAL
    "write", "edit", "str_replace_editor", "apply_patch", "multiedit" -> ToolTone.EDIT
    "read", "read_image", "view" -> ToolTone.READ
    "grep", "glob", "search" -> ToolTone.SEARCH
    "web_fetch", "web_search", "fetch" -> ToolTone.WEB
    "subagent", "subagent_fork", "send_message", "workflow" -> ToolTone.AGENT
    "todo_write" -> ToolTone.PLAN
    else -> ToolTone.NEUTRAL
}

@Composable
private fun ToolToneMark(tone: ToolTone?) {
    when (tone) {
        null -> Spacer(Modifier.width(TokenWorksSpacing.xxs))
        else -> {
            val surfaces = tokenWorksSurfaces()
            val (container, mark) = when (tone) {
                ToolTone.TERMINAL -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
                ToolTone.AGENT -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
                ToolTone.EDIT, ToolTone.WEB -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.tertiary
                ToolTone.READ, ToolTone.SEARCH -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.secondary
                ToolTone.PLAN -> surfaces.infoContainer to surfaces.info
                ToolTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.outline
            }
            Box(
                Modifier.size(20.dp).background(container, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(8.dp).background(mark, CircleShape))
            }
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
