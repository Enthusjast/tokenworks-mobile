package cn.edu.ustc.tokenworks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ChatOutboxSheet(
    entries: List<PromptOutboxEntry>, busy: Boolean, writable: Boolean, actions: SessionContentActions,
    hostQueue: (@Composable () -> Unit)? = null, onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetMaxWidth = TokenWorksSpacing.readingWidth) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 580.dp).padding(horizontal = TokenWorksSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
            item {
                Text(stringResource(R.string.chat_audit_queue), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.chat_audit_queue_explanation), style = MaterialTheme.typography.bodyMedium)
                if (entries.isEmpty()) Text(stringResource(R.string.chat_audit_queue_empty), style = MaterialTheme.typography.bodyMedium)
            }
            if (hostQueue != null) item("host-queue") { hostQueue() }
            items(entries, key = { it.requestId }) { entry ->
                val status = when (entry.state) {
                    PromptDeliveryState.PENDING -> R.string.chat_outbox_state_pending
                    PromptDeliveryState.SENDING -> R.string.chat_outbox_state_sending
                    PromptDeliveryState.OUTCOME_UNKNOWN -> R.string.chat_outbox_state_unknown
                    PromptDeliveryState.REFUSED -> R.string.chat_outbox_state_refused
                }
                Column {
                    if (entry.text.isNotBlank()) Text(entry.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (entry.receipts.isNotEmpty() || entry.images.isNotEmpty())
                        Text(stringResource(R.string.chat_outbox_attachments, entry.receipts.size, entry.images.size))
                    Text(stringResource(status), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (entry.state != PromptDeliveryState.SENDING) FlowRow {
                        TextButton({ actions.retryPrompt(entry.requestId) }, enabled = !busy && writable) {
                            Text(stringResource(R.string.chat_outbox_retry))
                        }
                        if (entry.canEdit && actions.editPendingPrompt != null) TextButton({
                            text = entry.text; editing = entry.requestId
                        }, enabled = !busy && writable) { Text(stringResource(R.string.chat_audit_edit_pending)) }
                        if (entry.canEdit && entry.mode != "steer") TextButton({ actions.steerUnsent(entry.requestId) },
                            enabled = !busy && writable) { Text(stringResource(R.string.chat_outbox_steer)) }
                        TextButton({ removing = entry.requestId }, enabled = !busy) { Text(stringResource(R.string.chat_outbox_remove)) }
                    }
                }
            }
        }
    }
    val editable = entries.firstOrNull { it.requestId == editing && it.canEdit && writable }
    if (editable != null) AlertDialog(onDismissRequest = { editing = null },
        title = { Text(stringResource(R.string.chat_audit_edit_pending)) },
        text = { OutlinedTextField(text, { if (it.length <= MAX_DRAFT_CHARACTERS) text = it },
            label = { Text(stringResource(R.string.chat_input)) }, minLines = 2, maxLines = 6) },
        confirmButton = { TextButton({ actions.editPendingPrompt?.invoke(editable.requestId, text); editing = null },
            enabled = !busy && text != editable.text && (text.isNotBlank() || editable.receipts.isNotEmpty() || editable.images.isNotEmpty())) {
            Text(stringResource(R.string.chat_audit_save))
        } }, dismissButton = { TextButton({ editing = null }) { Text(stringResource(R.string.action_cancel)) } })
    val removable = entries.firstOrNull { it.requestId == removing && it.state != PromptDeliveryState.SENDING }
    if (removable != null) AlertDialog(onDismissRequest = { removing = null },
        title = { Text(stringResource(R.string.chat_outbox_remove)) }, text = { Text(stringResource(R.string.chat_audit_remove_pending)) },
        confirmButton = { TextButton({ actions.abandonPrompt(removable.requestId); removing = null }, enabled = !busy) {
            Text(stringResource(R.string.chat_outbox_remove))
        } }, dismissButton = { TextButton({ removing = null }) { Text(stringResource(R.string.action_cancel)) } })
}
