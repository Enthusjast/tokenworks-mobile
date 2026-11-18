package cn.edu.ustc.tokenworks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The controller owns the draft, submission and cancellation; this row only renders their state. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatComposer(
    state: HomeState,
    onEditDraft: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier,
    onSetMode: (String) -> Unit = {},
    onAttach: (() -> Unit)? = null,
    onImage: (() -> Unit)? = null,
    onVoice: (() -> Unit)? = null,
    onShortcuts: (() -> Unit)? = null,
    onQueue: (() -> Unit)? = null,
    selectedModel: String? = null,
    modelsEnabled: Boolean = true,
    quoteRevision: Int = 0,
    completion: @Composable (TextFieldValue) -> Unit = {},
) {
    val composer = state.composer
    val fileState = state.files?.state?.collectAsState()?.value
    val attachmentCount = (fileState?.uploaded?.size ?: 0) + (fileState?.images?.size ?: 0)
    val ready = state.reader?.let { !it.readOnly } == true
    val editable = ready && composer != null
    val running = state.reader?.running == true
    val emptyValue = remember { TextFieldValue() }
    val value = composer?.value ?: emptyValue
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(quoteRevision) {
        if (quoteRevision != 0 && editable) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    var editingStarted by remember(composer?.resource) { mutableStateOf(false) }
    val surfaces = tokenWorksSurfaces()
    val bodyScale = LocalTokenWorksReadingStyle.current.bodyScale
    val inputStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 15.sp * bodyScale,
        lineHeight = 15.sp * 1.45f * bodyScale,
        color = if (editable) MaterialTheme.colorScheme.onSurface else surfaces.textTertiary,
    )
    val inputMaxHeight = with(LocalDensity.current) { inputStyle.lineHeight.toDp() * 8 } + TokenWorksSpacing.sm * 2
    val inputLabel = stringResource(R.string.chat_input)
    val placeholder = stringResource(
        when {
            editable -> R.string.chat_compose_placeholder
            state.reader?.phase == SessionReadPhase.CONNECTING -> R.string.conversation_connecting
            state.reader?.phase == SessionReadPhase.SYNCING -> R.string.conversation_syncing
            state.reader?.phase == SessionReadPhase.BACKOFF -> R.string.conversation_reconnecting
            else -> R.string.session_unavailable
        },
    )
    val actionLabel = stringResource(
        when {
            state.submitting -> R.string.chat_sending
            running && composer?.mode == "steer" -> R.string.chat_send_steer
            running -> R.string.chat_send_queue
            else -> R.string.chat_send
        },
    )

    var modeMenuOpen by remember { mutableStateOf(false) }
    val modeLabel = stringResource(if (composer?.mode == "steer") R.string.chat_send_steer else R.string.chat_send_queue)
    val modeMenuLabel = stringResource(R.string.chat_delivery_mode_title)
    Surface(
        modifier = modifier.fillMaxWidth().imePadding().testTag("message-composer")
            .padding(horizontal = TokenWorksSpacing.sm, vertical = TokenWorksSpacing.xs),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = tokenWorksSquircle(TokenWorksRadius.xl),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = TokenWorksSpacing.xs)) {
        if (editable && editingStarted) completion(value)
        if (value.text.isNotBlank()) Text(stringResource(R.string.chat_audit_draft_memory),
            Modifier.padding(horizontal = TokenWorksSpacing.sm, vertical = TokenWorksSpacing.xs),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = TokenWorksSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xxs)) {
            onAttach?.let { action -> IconButton(action, enabled = editable, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_attach), stringResource(R.string.chat_audit_attach))
            } }
            onImage?.let { action -> IconButton(action, enabled = editable, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_chat_image), stringResource(R.string.composer_add_image))
            } }
            onVoice?.let { action -> IconButton(action, enabled = editable, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_chat_mic), stringResource(R.string.chat_audit_voice))
            } }
            onShortcuts?.let { action ->
                val referenceLabel = stringResource(R.string.discovery_add)
                TextButton(action, enabled = editable,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = referenceLabel },
                    contentPadding = PaddingValues(horizontal = TokenWorksSpacing.sm)) {
                    Text(stringResource(R.string.chat_audit_shortcuts), style = MaterialTheme.typography.labelMedium)
                }
            }
            onQueue?.let { action -> TextButton(action, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(pluralStringResource(R.plurals.chat_audit_pending, state.pendingPrompts.size, state.pendingPrompts.size),
                    style = MaterialTheme.typography.labelMedium)
            } }
            TextButton(onOpenModels, enabled = ready && modelsEnabled,
                modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = TokenWorksSpacing.sm)) {
                Text(selectedModel?.let { stringResource(R.string.chat_audit_selected_model, it) }
                    ?: stringResource(R.string.workspace_model), style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                TextButton(
                    onClick = { modeMenuOpen = true },
                    enabled = editable && !state.submitting,
                    modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)
                        .semantics { contentDescription = modeMenuLabel; stateDescription = modeLabel },
                    contentPadding = PaddingValues(horizontal = TokenWorksSpacing.xs),
                ) { Text(modeLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                DropdownMenu(expanded = modeMenuOpen, onDismissRequest = { modeMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_send_queue), style = MaterialTheme.typography.bodyMedium) },
                        trailingIcon = { if (composer?.mode != "steer") Icon(painterResource(R.drawable.ic_chat_mode_selected), null) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { modeMenuOpen = false; if (composer?.mode != "queue") onSetMode("queue") },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_send_steer), style = MaterialTheme.typography.bodyMedium) },
                        trailingIcon = { if (composer?.mode == "steer") Icon(painterResource(R.drawable.ic_chat_mode_selected), null) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { modeMenuOpen = false; if (composer?.mode != "steer") onSetMode("steer") },
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = TokenWorksMetrics.composerBarHeight).padding(TokenWorksSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xs),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                Modifier.weight(1f)
                    .heightIn(min = TokenWorksMetrics.composerKey, max = inputMaxHeight)
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onEditDraft,
                    enabled = editable,
                    textStyle = inputStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = TokenWorksSpacing.xs, vertical = TokenWorksSpacing.sm)
                        .onFocusChanged { if (it.isFocused) editingStarted = true }
                        .focusRequester(focusRequester)
                        .testTag("composer-input").semantics { contentDescription = inputLabel },
                    decorationBox = { innerTextField ->
                        Box {
                            if (value.text.isEmpty()) Text(if (attachmentCount > 0)
                                pluralStringResource(R.plurals.chat_audit_attachment_count, attachmentCount, attachmentCount) else placeholder,
                                style = inputStyle, color = surfaces.textTertiary)
                            innerTextField()
                        }
                    },
                )
            }
            if (running) IconButton(onCancel, enabled = ready && !state.submitting, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_stop), stringResource(R.string.workspace_stop_task), tint = MaterialTheme.colorScheme.error)
            }
            FilledIconButton(
                onClick = onSend,
                enabled = !state.submitting && editable && (value.text.isNotBlank() || attachmentCount > 0),
                modifier = Modifier.sizeIn(
                    minWidth = TokenWorksSpacing.minimumTouchTarget,
                    minHeight = TokenWorksSpacing.minimumTouchTarget,
                ).size(TokenWorksMetrics.composerKey)
                    .semantics {
                        contentDescription = actionLabel
                        if (state.submitting) liveRegion = LiveRegionMode.Polite
                    },
            ) {
                if (state.submitting) CircularProgressIndicator(
                    modifier = Modifier.size(TokenWorksSpacing.iconSize),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = TokenWorksSpacing.xxs,
                ) else Icon(
                    painterResource(R.drawable.ic_send),
                    contentDescription = null,
                    modifier = Modifier.size(TokenWorksSpacing.iconSize),
                )
            }
        }
        }
    }
}
