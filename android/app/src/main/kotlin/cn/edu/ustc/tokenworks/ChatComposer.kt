package cn.edu.ustc.tokenworks

// Shell contract: ChatComposer(state, onEditDraft: (TextFieldValue) -> Unit, onSend: () -> Unit,
// onCancel: () -> Unit, onOpenModels: () -> Unit, modifier: Modifier = Modifier).

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/** The controller owns the draft, submission and cancellation; this row only renders their state. */
@Composable
internal fun ChatComposer(
    state: HomeState,
    onEditDraft: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val composer = state.composer
    val ready = state.reader?.phase == SessionReadPhase.READY
    val editable = ready && composer != null
    val running = state.reader?.running == true
    val emptyValue = remember { TextFieldValue() }
    val value = composer?.value ?: emptyValue
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
            editable -> R.string.chat_input
            state.reader?.phase == SessionReadPhase.CONNECTING -> R.string.conversation_connecting
            state.reader?.phase == SessionReadPhase.SYNCING -> R.string.conversation_syncing
            state.reader?.phase == SessionReadPhase.BACKOFF -> R.string.conversation_reconnecting
            else -> R.string.session_unavailable
        },
    )
    val actionLabel = stringResource(
        when {
            state.submitting -> R.string.chat_sending
            running -> R.string.workspace_stop_task
            else -> R.string.chat_send
        },
    )

    Surface(
        modifier = modifier.fillMaxWidth().imePadding().testTag("message-composer")
            .padding(horizontal = TokenWorksSpacing.sm, vertical = TokenWorksSpacing.xs),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = tokenWorksSquircle(TokenWorksRadius.xl),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = TokenWorksMetrics.composerBarHeight).padding(TokenWorksSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xs),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextButton(
                onClick = onOpenModels,
                enabled = ready,
                modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget),
                contentPadding = PaddingValues(horizontal = TokenWorksSpacing.xs),
            ) {
                // The display projection does not expose a current model; do not infer one from the catalog.
                Text(stringResource(R.string.workspace_model), style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
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
                        .testTag("composer-input").semantics { contentDescription = inputLabel },
                    decorationBox = { innerTextField ->
                        Box {
                            if (value.text.isEmpty()) Text(placeholder, style = inputStyle, color = surfaces.textTertiary)
                            innerTextField()
                        }
                    },
                )
            }
            FilledIconButton(
                onClick = if (running) onCancel else onSend,
                enabled = !state.submitting && if (running) ready else editable && value.text.isNotBlank(),
                modifier = Modifier.sizeIn(
                    minWidth = TokenWorksSpacing.minimumTouchTarget,
                    minHeight = TokenWorksSpacing.minimumTouchTarget,
                ).size(TokenWorksMetrics.composerKey).semantics {
                    contentDescription = actionLabel
                    if (state.submitting) liveRegion = LiveRegionMode.Polite
                },
                colors = if (running) IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) else IconButtonDefaults.filledIconButtonColors(),
            ) {
                if (state.submitting) CircularProgressIndicator(
                    modifier = Modifier.size(TokenWorksSpacing.iconSize),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = TokenWorksSpacing.xxs,
                ) else Icon(
                    painterResource(if (running) R.drawable.ic_stop else R.drawable.ic_send),
                    contentDescription = null,
                    modifier = Modifier.size(TokenWorksSpacing.iconSize),
                )
            }
        }
    }
}
