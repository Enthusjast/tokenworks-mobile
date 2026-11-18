package cn.edu.ustc.tokenworks

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

internal data class WorkspaceMenuItem(@StringRes val label: Int, val run: () -> Unit)

internal sealed class WorkspacePrompt(
    val action: WorkspaceAction,
    val workspaceId: String? = null,
    val sessionId: String? = null,
) {
    data object Create : WorkspacePrompt(WorkspaceAction.CREATE)
    data class Rename(val workspace: DshWorkspace) : WorkspacePrompt(WorkspaceAction.RENAME, workspace.id)
    data class Delete(val workspace: DshWorkspace) : WorkspacePrompt(WorkspaceAction.DELETE, workspace.id)
    data class Archive(val id: String, val title: String, val stopActivity: Boolean) :
        WorkspacePrompt(WorkspaceAction.ARCHIVE, sessionId = id)
}

@Composable
internal fun WorkspaceManagementDialog(
    prompt: WorkspacePrompt,
    directory: WorkspaceDirectoryState,
    onDismiss: () -> Unit,
    onArchived: (String) -> Unit,
) {
    var input by remember { mutableStateOf((prompt as? WorkspacePrompt.Rename)?.workspace?.title.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var stopActivity by remember { mutableStateOf((prompt as? WorkspacePrompt.Archive)?.stopActivity == true) }
    val editing = prompt is WorkspacePrompt.Create || prompt is WorkspacePrompt.Rename
    val title = when (prompt) {
        WorkspacePrompt.Create -> R.string.workspace_directory_create
        is WorkspacePrompt.Rename -> R.string.workspace_directory_rename
        is WorkspacePrompt.Delete -> R.string.workspace_directory_delete
        is WorkspacePrompt.Archive -> R.string.conversation_archive
    }
    val confirm = if (prompt is WorkspacePrompt.Archive && stopActivity) R.string.workspace_directory_archive_stop else title
    AlertDialog(
        onDismissRequest = { if (!directory.busy) onDismiss() },
        title = { Text(stringResource(title)) },
        text = {
            Column {
                if (editing) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it; error = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(if (prompt == WorkspacePrompt.Create) R.string.workspace_directory_path else R.string.workspace_directory_name)) },
                        supportingText = {
                            when {
                                error != null -> WorkspaceDialogError(checkNotNull(error))
                                prompt == WorkspacePrompt.Create -> Text(stringResource(R.string.workspace_directory_path_help))
                            }
                        },
                        enabled = !directory.busy,
                        isError = error != null,
                        singleLine = true,
                    )
                } else {
                    when (prompt) {
                        is WorkspacePrompt.Delete -> Text(stringResource(R.string.workspace_directory_delete_detail, prompt.workspace.title))
                        is WorkspacePrompt.Archive -> Text(stringResource(
                            if (stopActivity) R.string.workspace_directory_archive_active else R.string.workspace_directory_archive_detail,
                            prompt.title,
                        ))
                        else -> Unit
                    }
                    error?.let { WorkspaceDialogError(it) }
                }
                if (directory.busy) Text(stringResource(R.string.workspace_directory_saving),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            if (directory.canWrite(prompt.action, prompt.workspaceId, prompt.sessionId)) {
                TextButton(
                    enabled = !directory.busy && (!editing || input.isNotBlank()),
                    onClick = {
                        val command = when (prompt) {
                            WorkspacePrompt.Create -> WorkspaceCommand.Create(input)
                            is WorkspacePrompt.Rename -> WorkspaceCommand.Rename(prompt.workspace.id, input.trim())
                            is WorkspacePrompt.Delete -> WorkspaceCommand.Delete(prompt.workspace.id)
                            is WorkspacePrompt.Archive -> WorkspaceCommand.Archive(prompt.id, stopActivity)
                        }
                        error = null
                        directory.mutate(command, done = {
                            onDismiss()
                            if (prompt is WorkspacePrompt.Archive) onArchived(prompt.id)
                        }, failed = { code ->
                            if (prompt is WorkspacePrompt.Archive && !stopActivity && code == "workspace/session-active") {
                                stopActivity = true
                            } else error = code
                        })
                    },
                ) { Text(stringResource(confirm)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !directory.busy) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun WorkspaceDialogError(code: String) {
    Text(workspaceMutationError(code), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
internal fun workspaceMutationError(code: String): String = when (code) {
    "workspace/invalid-path" -> stringResource(R.string.workspace_directory_invalid_path)
    "workspace/name-conflict" -> stringResource(R.string.workspace_directory_name_conflict)
    else -> stringResource(R.string.workspace_directory_write_error, code)
}
