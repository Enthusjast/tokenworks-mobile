package cn.edu.ustc.tokenworks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.os.ConfigurationCompat
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay

/** Replay never re-executes prompts, links, tool calls or interactions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConversationReplay(capture: ConversationCapture, onDismiss: () -> Unit) {
    var index by remember(capture) { mutableIntStateOf(0) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
    val date = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }
    var playing by remember(capture) { mutableStateOf(false) }
    val item = capture.items[index]
    val atEnd = index == capture.items.lastIndex
    LaunchedEffect(playing, index, capture, lifecycle) {
        if (lifecycle != Lifecycle.State.RESUMED) playing = false
        if (lifecycle == Lifecycle.State.RESUMED && playing && !atEnd) {
            val gap = capture.items[index + 1].time?.let { next -> item.time?.let { next - it } }
            delay(gap?.coerceIn(1000L, 6000L) ?: 2500L)
            index++
        } else if (atEnd) playing = false
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.chat_audit_replay)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                if (capture.partial) Text(stringResource(R.string.chat_audit_replay_partial), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.chat_audit_replay_position, index + 1, capture.items.size),
                    modifier = Modifier.semantics { if (!playing) liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelMedium)
                key(item.id) {
                    SelectionContainer {
                        Column(Modifier.fillMaxWidth().heightIn(max = TokenWorksMetrics.panelIdealMin)
                            .verticalScroll(rememberScrollState()).padding(vertical = TokenWorksSpacing.sm),
                            verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                            item.time?.let { Text(date.format(Date(it)), style = MaterialTheme.typography.labelSmall) }
                            Text(stringResource(conversationRoleLabel(item.role)), style = MaterialTheme.typography.titleSmall)
                            for (block in item.blocks) {
                                if (block.name.isNotBlank()) Text(block.name, style = MaterialTheme.typography.labelMedium)
                                if (block.text.isNotEmpty()) Text(block.text)
                                else if (block.media != null && block.name.isBlank()) Text(stringResource(R.string.chat_audit_image))
                            }
                        }
                    }
                }
                FlowRow {
                    TextButton({ playing = false; index-- }, enabled = index > 0) { Text(stringResource(R.string.chat_audit_previous)) }
                    TextButton({
                        if (atEnd) { index = 0; playing = false } else playing = !playing
                    }, enabled = capture.items.size > 1) {
                        Text(stringResource(if (atEnd) R.string.chat_audit_restart else if (playing) R.string.chat_audit_pause else R.string.chat_audit_play))
                    }
                    TextButton({ playing = false; index++ }, enabled = !atEnd) { Text(stringResource(R.string.chat_audit_next)) }
                }
            }
        }, confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_close)) } })
}
