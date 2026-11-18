package cn.edu.ustc.tokenworks

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun SessionArchiveActions(archived: Boolean, epoch: String?, enabled: Boolean, authority: Boolean, onArchive: (String, Boolean, Boolean) -> Unit, onRefresh: () -> Unit) {
    var confirm by remember(archived, epoch) { mutableStateOf(false) }
    var stopActivity by remember(archived, epoch) { mutableStateOf(false) }
    LaunchedEffect(enabled, authority) {
        if (!enabled || !authority) { confirm = false; stopActivity = false }
    }
    Surface(Modifier.fillMaxWidth(), shape = tokenWorksSquircle(TokenWorksRadius.lg),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(TokenWorksSpacing.lg), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md)) {
            Text(
                stringResource(if (archived) R.string.session_archive_archived_title else R.string.session_archive_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(if (archived) R.string.session_archive_archived_description else R.string.session_archive_description),
                style = MaterialTheme.typography.bodySmall,
                color = tokenWorksSurfaces().textTertiary,
            )
            if (authority) OutlinedButton({ stopActivity = false; confirm = true }, enabled = enabled && epoch != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                Text(stringResource(if (archived) R.string.conversation_restore else R.string.conversation_archive))
            }
            TextButton(onRefresh, modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                Text(stringResource(R.string.session_archive_refresh))
            }
        }
    }
    if (confirm && enabled && authority && epoch != null) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(if (archived) R.string.session_archive_restore_prompt else R.string.session_archive_prompt)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (archived) R.string.session_archive_restore_detail else R.string.session_archive_detail))
                if (!archived) {
                    Row(Modifier.fillMaxWidth().toggleable(stopActivity, role = Role.Checkbox, onValueChange = { stopActivity = it }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(stopActivity, null)
                        Text(stringResource(R.string.session_archive_request_stop))
                    }
                    Text(stringResource(R.string.session_archive_stop_detail), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton({ confirm = false; onArchive(epoch, !archived, !archived && stopActivity) }) {
                Text(stringResource(if (archived) R.string.session_archive_confirm_restore else R.string.session_archive_confirm))
            }
        },
        dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}
