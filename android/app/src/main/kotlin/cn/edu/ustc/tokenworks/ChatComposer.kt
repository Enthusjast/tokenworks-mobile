package cn.edu.ustc.tokenworks

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.enthusjast.tokenworks.command.CommandIntent
import io.github.enthusjast.tokenworks.network.SessionReadPhase
import io.github.enthusjast.tokenworks.network.UploadPhase
import io.github.enthusjast.tokenworks.network.UploadView
import io.github.enthusjast.tokenworks.ui.TokenWorksSpacing

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SessionComposer(
    state: HomeState, onSubmit: (MessageSubmission) -> Unit, draft: MessageComposerState,
    onEdit: (MessageComposerState) -> Unit, onAttach: () -> Unit, onRemove: (String) -> Unit,
    onViewAttachments: () -> Unit, expanded: Boolean = false, onExpand: () -> Unit,
    onResolveFailure: (MessageComposerState, Boolean) -> Unit,
    canCancel: Boolean = false, onCancel: () -> Unit = {}, attachmentMenu: @Composable () -> Unit = {},
) {
    val text = draft.value.text
    val mode = draft.mode
    val placeholder = "11111111-1111-4111-8111-111111111111"
    val selected = state.uploads?.let { uploads ->
        uploads.selected.mapNotNull { id -> uploads.rows.firstOrNull { it.stored.intent.requestId == id } }
    } ?: emptyList()
    val attachments = selected.mapNotNull { it.stored.fileId }
    val attachmentsReady = selected.size == (state.uploads?.selected?.size ?: 0) && selected.all { it.ready() }
    val textValid = runCatching { CommandIntent.message(placeholder, placeholder, placeholder, mode, text, attachments) }.isSuccess
    val valid = (text.isNotBlank() || attachments.isNotEmpty()) && attachmentsReady && textValid
    val online = state.outbox?.connected == true && state.reader?.phase == SessionReadPhase.READY
    val showStop = online && canCancel && text.isBlank() && state.uploads?.selected.isNullOrEmpty() && !state.submitting
    var modeMenu by remember(draft.resource) { mutableStateOf(false) }
    var attachmentDetailId by remember(draft.resource) { mutableStateOf<String?>(null) }
    var rejectedInput by remember(draft.resource) { mutableStateOf(false) }
    val action = stringResource(
        if (state.submitting) R.string.chat_saving else if (showStop) R.string.workspace_stop_task
        else if (!online) R.string.chat_save_pending else if (mode == "queue") R.string.chat_send_queue else R.string.chat_send_steer,
    )
    val actionEnabled = showStop || valid && !rejectedInput && !state.submitting

    BoxWithConstraints(
        Modifier.testTag("message-composer").fillMaxWidth()
            .then(if (expanded) Modifier.fillMaxHeight() else Modifier)
            .padding(horizontal = TokenWorksSpacing.small, vertical = TokenWorksSpacing.extraSmall),
    ) {
        val trayMaxHeight = maxHeight / 3
        Column(
            Modifier.fillMaxWidth().then(if (expanded) Modifier.fillMaxHeight() else Modifier),
            verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.extraSmall),
        ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = trayMaxHeight).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.extraSmall),
        ) {
            state.failedInput?.takeIf { it.resource == draft.resource }?.let { original ->
                FailedMessageInput(original, !state.submitting, onResolveFailure)
            }
            if (selected.isNotEmpty()) {
                Text(stringResource(R.string.chat_attachment_count, attachments.size), style = MaterialTheme.typography.bodySmall)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.small),
                ) {
                    selected.forEach { upload ->
                        key(upload.stored.intent.requestId) {
                            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                                Column(Modifier.widthIn(max = 240.dp).padding(TokenWorksSpacing.small)) {
                                    val name = upload.stored.intent.manifest.name
                                    Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(fileSizeText(upload.stored.intent.manifest.sizeBytes), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(composerUploadPhase(upload), style = MaterialTheme.typography.bodySmall,
                                        color = if (upload.phase == UploadPhase.REJECTED || upload.phase == UploadPhase.ERROR || upload.phase == UploadPhase.EXPIRED)
                                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row {
                                        val removeName = stringResource(R.string.chat_remove_selection, name)
                                        TextButton({ onRemove(upload.stored.intent.requestId) },
                                            Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget).semantics { contentDescription = removeName },
                                            contentPadding = PaddingValues(horizontal = TokenWorksSpacing.small)) {
                                            Text(stringResource(R.string.chat_remove_attachment))
                                        }
                                        val detailName = stringResource(R.string.chat_attachment_detail_action, name)
                                        TextButton({ attachmentDetailId = upload.stored.intent.requestId },
                                            Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget).semantics { contentDescription = detailName },
                                            contentPadding = PaddingValues(horizontal = TokenWorksSpacing.small)) {
                                            Text(stringResource(R.string.chat_attachment_detail))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                TextButton(onViewAttachments, Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                    Text(stringResource(R.string.chat_view_attachments))
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth().then(if (expanded) Modifier.weight(1f) else Modifier),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(TokenWorksSpacing.pillRadius),
        ) {
            Row(
                Modifier.fillMaxWidth().then(if (expanded) Modifier.fillMaxHeight() else Modifier)
                    .padding(TokenWorksSpacing.extraSmall),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box {
                    IconButton(onAttach, Modifier.size(TokenWorksSpacing.minimumTouchTarget)) {
                        Icon(painterResource(R.drawable.ic_add), stringResource(R.string.workspace_attachment_menu),
                            Modifier.size(TokenWorksSpacing.iconSize))
                    }
                    attachmentMenu()
                }
                TextField(
                    value = draft.value,
                    onValueChange = { value ->
                        if (value.text.length > 65_536) rejectedInput = true
                        else { rejectedInput = false; onEdit(draft.copy(value = value)) }
                    },
                    label = { Text(stringResource(R.string.chat_input)) },
                    minLines = 1,
                    maxLines = if (expanded) Int.MAX_VALUE else 5,
                    isError = rejectedInput || text.isNotBlank() && !textValid,
                    modifier = Modifier.weight(1f).then(if (expanded) Modifier.fillMaxHeight() else Modifier),
                    shape = RoundedCornerShape(TokenWorksSpacing.pillRadius),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        errorContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent,
                    ),
                )
                if (online) FilledIconButton(
                    onClick = { if (showStop) onCancel() else onSubmit(MessageSubmission(draft, online)) },
                    enabled = actionEnabled,
                    modifier = Modifier.size(TokenWorksSpacing.minimumTouchTarget).semantics { contentDescription = action },
                ) {
                    Icon(painterResource(if (showStop) R.drawable.ic_stop else R.drawable.ic_send), null,
                        Modifier.size(TokenWorksSpacing.iconSize))
                }
            }
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.small),
            verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.extraSmall),
        ) {
            Box {
                TextButton({ modeMenu = true }, Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                    Text(stringResource(if (mode == "queue") R.string.chat_queue else R.string.chat_steer),
                        style = MaterialTheme.typography.labelMedium)
                }
                DropdownMenu(modeMenu, { modeMenu = false }) {
                    DropdownMenuItem(text = { Column {
                        Text(stringResource(R.string.chat_queue))
                        Text(stringResource(R.string.chat_queue_desc), style = MaterialTheme.typography.bodySmall)
                    } }, onClick = { modeMenu = false; onEdit(draft.copy(mode = "queue")) })
                    DropdownMenuItem(text = { Column {
                        Text(stringResource(R.string.chat_steer))
                        Text(stringResource(R.string.chat_steer_desc), style = MaterialTheme.typography.bodySmall)
                    } }, onClick = { modeMenu = false; onEdit(draft.copy(mode = "steer")) })
                }
            }
            if (expanded) TextButton(onExpand, Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                Text(stringResource(R.string.chat_editor_compact))
            }
            if (!online) Button(
                { onSubmit(MessageSubmission(draft, online)) },
                enabled = actionEnabled,
                modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget).semantics { contentDescription = action },
            ) { Text(action) }
        }
        if (state.submitting) Row(
            Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.small),
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.chat_saving_request), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (!attachmentsReady) Text(stringResource(R.string.chat_attachment_not_ready), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        if (rejectedInput) {
            val rejection = stringResource(R.string.chat_input_rejected)
            Text(rejection, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { error(rejection); liveRegion = LiveRegionMode.Polite })
        } else if (text.isNotBlank() && !textValid) Text(stringResource(R.string.chat_invalid),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (!online) Text(stringResource(R.string.chat_offline), style = MaterialTheme.typography.bodySmall)
    }
    }
    selected.firstOrNull { it.stored.intent.requestId == attachmentDetailId }?.let { upload ->
        AlertDialog(
            onDismissRequest = { attachmentDetailId = null },
            title = { Text(stringResource(R.string.chat_attachment_detail)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.small)) {
                SelectionContainer { Text(upload.stored.intent.manifest.name) }
                Text(fileSizeText(upload.stored.intent.manifest.sizeBytes), style = MaterialTheme.typography.bodySmall)
                Text(composerUploadPhase(upload), style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton({ attachmentDetailId = null }, Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                Text(stringResource(R.string.action_close))
            } },
        )
    }
}

@Composable
private fun composerUploadPhase(upload: UploadView): String = when (upload.phase) {
    UploadPhase.NEEDS_SOURCE -> stringResource(R.string.file_ux_resume_needed)
    UploadPhase.REGISTERING -> stringResource(R.string.file_ux_registering)
    UploadPhase.UPLOADING -> stringResource(R.string.file_ux_uploading, fileSizeText(upload.bytes), fileSizeText(upload.stored.intent.manifest.sizeBytes))
    UploadPhase.SCANNING -> stringResource(R.string.file_ux_scanning)
    UploadPhase.WAITING -> stringResource(R.string.file_ux_waiting)
    UploadPhase.READY -> stringResource(if (upload.ready()) R.string.file_ux_ready else R.string.file_ux_selection_stale)
    UploadPhase.BOUND -> stringResource(R.string.file_ux_bound)
    UploadPhase.REJECTED -> stringResource(R.string.file_ux_rejected)
    UploadPhase.EXPIRED -> stringResource(R.string.file_ux_expired)
    UploadPhase.ERROR -> stringResource(R.string.file_ux_unknown)
}
