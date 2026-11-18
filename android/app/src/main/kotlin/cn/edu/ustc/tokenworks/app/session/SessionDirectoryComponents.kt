package cn.edu.ustc.tokenworks

import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionDirectoryRow(
    entry: SessionDirectoryEntry, depth: Int, now: Long, opened: Boolean, unread: Boolean, archived: Boolean, pinned: Boolean,
    selecting: Boolean, checked: Boolean, enabled: Boolean, preview: DirectoryPreview?, snippet: String?,
    open: () -> Unit, select: () -> Unit, pin: (() -> Unit)?, rename: (() -> Unit)?, fork: (() -> Unit)?, archive: (() -> Unit)?,
    collectionIndex: Int? = null,
) {
    val title = entry.metadata?.title?.takeIf(String::isNotBlank) ?: stringResource(R.string.session_untitled)
    val selectLabel = stringResource(R.string.directory_select)
    val renameLabel = stringResource(R.string.session_rename)
    val archiveLabel = stringResource(if (archived) R.string.directory_restore else R.string.directory_archive_action)
    val pinLabel = stringResource(if (pinned) R.string.directory_unpin else R.string.directory_pin)
    val forkLabel = stringResource(R.string.session_fork)
    val haptic = LocalHapticFeedback.current
    val shape = tokenWorksSquircle(TokenWorksRadius.md)
    val modifier = Modifier.fillMaxWidth().padding(start = TokenWorksSpacing.sm * depth.coerceAtMost(3)).clip(shape)
    val body: @Composable () -> Unit = {
        var menu by remember { mutableStateOf(false) }
        val status = buildList {
            if (pinned) add(stringResource(R.string.directory_pinned_status))
            if (archived) add(stringResource(R.string.directory_archived_tab))
            if (entry.running && preview?.turn == null) add(stringResource(R.string.session_running))
            if (unread) add(stringResource(R.string.workspace_unread))
            if (entry.state == "unavailable") add(stringResource(R.string.session_unavailable))
            if (entry.parentSessionId != null) add(stringResource(R.string.directory_subagent))
        }
        val turn = preview?.turn?.takeIf { entry.running }
        val runLabel = if (turn?.step != null) stringResource(R.string.directory_run_step, turn.turn, turn.step)
            else turn?.let { stringResource(R.string.directory_run_turn, it.turn) }
        val chosen = if (selecting) checked else opened
        val colors = MaterialTheme.colorScheme
        val foreground = if (chosen) colors.onSecondaryContainer else colors.onSurface
        val supporting = if (chosen) colors.onSecondaryContainer else colors.onSurfaceVariant
        Row(Modifier.fillMaxWidth().background(if (chosen) colors.secondaryContainer else colors.surface),
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f)
                .combinedClickable(enabled = enabled, role = if (selecting) Role.Checkbox else Role.Button,
                    onLongClickLabel = selectLabel, onLongClick = if (selecting) null else select, onClick = open)
                .semantics(mergeDescendants = true) {
                    collectionIndex?.let { collectionItemInfo = CollectionItemInfo(it, 1, 0, 1) }
                    if (selecting) toggleableState = ToggleableState(checked) else selected = opened
                    if (enabled && !selecting) customActions = buildList {
                        add(CustomAccessibilityAction(selectLabel) { select(); true })
                        if (pin != null) add(CustomAccessibilityAction(pinLabel) { pin(); true })
                        if (rename != null) add(CustomAccessibilityAction(renameLabel) { rename(); true })
                        if (fork != null) add(CustomAccessibilityAction(forkLabel) { fork(); true })
                        if (archive != null) add(CustomAccessibilityAction(archiveLabel) { archive(); true })
                    }
                }
                .heightIn(min = TokenWorksSpacing.minimumTouchTarget)
                .padding(start = TokenWorksSpacing.md, end = TokenWorksSpacing.sm, top = TokenWorksSpacing.sm, bottom = TokenWorksSpacing.sm),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                if (selecting) Checkbox(checked, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xxs)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = foreground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    entry.metadata?.workspaceTitle?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = supporting, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val line = snippet ?: preview?.text ?: if (preview?.hasMessage == true) stringResource(R.string.directory_media_preview) else null
                    if (line != null) Text(line, style = MaterialTheme.typography.bodySmall, color = supporting, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (runLabel != null) Text(runLabel, style = MaterialTheme.typography.labelMedium, color = foreground)
                    if (status.isNotEmpty()) Text(status.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = foreground)
                    val time = relativeSessionTime(entry.updatedAt, now)
                    val tokens = entry.usageSnapshot?.counts?.total?.let { total ->
                        val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
                        stringResource(R.string.directory_token_total, remember(locale, total) { NumberFormat.getIntegerInstance(locale).format(total) })
                    }
                    val metadata = listOfNotNull(time.takeIf(String::isNotBlank), tokens).joinToString(" · ")
                    if (metadata.isNotBlank()) Text(metadata, style = MaterialTheme.typography.bodySmall, color = supporting)
                }
            }
            if (!selecting) Box {
                IconButton({ menu = true }, enabled = enabled, modifier = Modifier.size(TokenWorksSpacing.minimumTouchTarget)) {
                    Icon(painterResource(R.drawable.ic_more), stringResource(R.string.directory_row_actions, title), tint = supporting)
                }
                DropdownMenu(menu && enabled, onDismissRequest = { menu = false }) {
                    fun invoke(action: () -> Unit) { menu = false; action() }
                    DropdownMenuItem(text = { Text(selectLabel) }, onClick = { invoke(select) })
                    if (pin != null) DropdownMenuItem(text = { Text(pinLabel) }, onClick = { invoke(pin) })
                    if (rename != null) DropdownMenuItem(text = { Text(renameLabel) }, onClick = { invoke(rename) })
                    if (fork != null) DropdownMenuItem(text = { Text(forkLabel) }, onClick = { invoke(fork) })
                    if (archive != null) DropdownMenuItem(text = { Text(archiveLabel) }, onClick = { invoke(archive) })
                }
            }
        }
    }
    val swipeArchive = archive.takeIf { enabled && !selecting && !archived }
    val swipeRename = rename.takeIf { enabled && !selecting }
    if (swipeArchive == null && swipeRename == null) {
        Box(modifier) { body() }
    } else {
        val swipe = rememberSwipeToDismissBoxState(positionalThreshold = { distance -> distance * 0.44f })
        val scope = rememberCoroutineScope()
        SwipeToDismissBox(
            state = swipe, modifier = modifier,
            enableDismissFromStartToEnd = swipeArchive != null, enableDismissFromEndToStart = swipeRename != null,
            backgroundContent = {
                val direction = swipe.dismissDirection
                if (direction != SwipeToDismissBoxValue.Settled) {
                    val archiving = direction == SwipeToDismissBoxValue.StartToEnd
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = TokenWorksSpacing.lg).clearAndSetSemantics {},
                        contentAlignment = if (archiving) Alignment.CenterStart else Alignment.CenterEnd) {
                        Text(if (archiving) archiveLabel else renameLabel, color = MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.labelLarge)
                    }
                }
            },
            onDismiss = { direction ->
                val action = when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> swipeArchive
                    SwipeToDismissBoxValue.EndToStart -> swipeRename
                    SwipeToDismissBoxValue.Settled -> null
                }
                // Settle the row in place; swipe only opens the same confirmed action as the menu.
                scope.launch { swipe.snapTo(SwipeToDismissBoxValue.Settled) }
                if (action != null) { haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate); action() }
            },
        ) { body() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DirectorySelectionBar(
    count: Int, busy: Boolean, canSubmit: Boolean, archive: Boolean, cancel: () -> Unit, submit: () -> Unit,
    deleting: Boolean, delete: (() -> Unit)?,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = TokenWorksSpacing.md, vertical = TokenWorksSpacing.sm)) {
            Text(when {
                deleting -> stringResource(R.string.directory_delete_working)
                busy -> stringResource(R.string.directory_archive_working)
                else -> stringResource(R.string.directory_selection_count, count)
            },
                Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                TextButton(cancel, enabled = !busy, modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                    Text(stringResource(R.string.directory_cancel_selection))
                }
                FilledTonalButton(submit, enabled = canSubmit && !busy, modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                    Text(stringResource(if (archive) R.string.directory_archive_action else R.string.directory_restore))
                }
                if (delete != null) TextButton(delete, enabled = !busy,
                    modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.directory_delete_permanently))
                }
            }
            if (count > 0 && !canSubmit && !busy) Text(stringResource(R.string.directory_selection_blocked), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun DirectoryDeleteConfirmation(count: Int, dismiss: () -> Unit, confirm: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(pluralStringResource(R.plurals.directory_delete_confirm_title, count, count)) },
        text = { Text(stringResource(R.string.directory_delete_explanation), Modifier.verticalScroll(rememberScrollState())) },
        dismissButton = {
            TextButton(dismiss, modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        confirmButton = {
            Button(confirm, modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget),
                colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError)) {
                Text(stringResource(R.string.directory_delete_permanently))
            }
        },
    )
}

@Composable
internal fun DirectoryNotice(label: String, retry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(TokenWorksSpacing.sm)) {
        Text(label, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (retry != null) TextButton(retry, modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

@Composable
internal fun DirectorySkeleton(label: String, rows: Int = 4) {
    val view = LocalView.current
    val motion = remember(view) { Settings.Global.getFloat(view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
    val slide = if (motion) rememberInfiniteTransition(label = "directory skeleton").animateFloat(
        initialValue = -0.4f, targetValue = 1.4f,
        animationSpec = infiniteRepeatable(tween(1450, easing = LinearEasing)), label = "skeleton highlight",
    ) else null
    val surface = MaterialTheme.colorScheme.surfaceContainerHighest
    val highlight = MaterialTheme.colorScheme.surfaceContainerLow
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        repeat(rows) {
            Column(Modifier.fillMaxWidth().clip(tokenWorksSquircle(TokenWorksRadius.md))
                .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(TokenWorksSpacing.md).clearAndSetSemantics {},
                verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                for (fraction in listOf(0.54f, 0.77f, 0.34f)) {
                    Box(Modifier.fillMaxWidth(fraction).height(10.dp).clip(tokenWorksSquircle(TokenWorksRadius.sm)).drawBehind {
                        val phase = slide?.value
                        if (phase == null) drawRect(surface)
                        else {
                            val center = phase * size.width
                            drawRect(Brush.linearGradient(listOf(surface, highlight, surface),
                                start = Offset(center - size.width * .30f, 0f), end = Offset(center + size.width * .30f, 0f)))
                        }
                    })
                }
            }
        }
    }
}

@Composable
internal fun DirectoryEmpty(title: String, detail: String? = null, action: (() -> Unit)? = null, actionLabel: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = TokenWorksSpacing.lg), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = tokenWorksSquircle(TokenWorksRadius.lg)) {
            Icon(painterResource(R.drawable.ic_directory_empty), null, Modifier.padding(14.dp).size(58.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        TokenWorksEmptyState(title = title, detail = detail, actionLabel = actionLabel, onAction = action)
    }
}
