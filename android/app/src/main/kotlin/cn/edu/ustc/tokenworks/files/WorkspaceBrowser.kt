package cn.edu.ustc.tokenworks

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

@Composable
internal fun WorkspaceBrowser(state: FilesUiState, browse: (List<String>) -> Unit, add: (WorkspaceExportFile) -> Unit,
                              cancel: () -> Unit, onBack: () -> Unit) {
    val listing = state.workspaceListing
    val parts = listing?.parts.orEmpty()
    val enabled = !state.busy && !state.pickerActive
    val scroll = rememberLazyListState()
    LaunchedEffect(parts) { scroll.scrollToItem(0) }
    LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(TokenWorksSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md)) {
        item {
            Text(if (parts.isEmpty()) stringResource(R.string.workspace_files_root)
                else stringResource(R.string.workspace_files_relative_directory, parts.joinToString("/")),
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.workspace_files_read_only), style = MaterialTheme.typography.bodyMedium)
        }
        item { WorkspaceFileStatus(state, cancel) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                if (parts.isNotEmpty()) OutlinedButton({ browse(parts.dropLast(1)) }, enabled = enabled,
                    modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                    Text(stringResource(R.string.workspace_files_parent))
                }
                TextButton({ browse(parts) }, enabled = enabled, modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                    Text(stringResource(R.string.workspace_files_refresh))
                }
            }
        }
        if (listing?.truncated == true) item {
            TokenWorksStatusNotice(stringResource(R.string.workspace_files_truncated), tone = TokenWorksNoticeTone.INFO)
        }
        if (listing != null && listing.entries.isEmpty()) item { Text(stringResource(R.string.workspace_files_list_empty)) }
        items(listing?.entries.orEmpty(), key = { it.name }) { entry ->
            when (entry) {
                is WorkspaceFileDirectory -> OutlinedButton({ browse(entry.parts) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                    Text(stringResource(R.string.workspace_files_open_directory, entry.name))
                }
                is WorkspaceExportFile -> WorkspaceFileCard(entry.name) {
                    val alreadyAdded = state.workspaceExports.any { it.file == entry }
                    val tooLarge = entry.sizeBytes > DshWorkspaceFiles.MAX_FILE_BYTES
                    Text(stringResource(R.string.workspace_files_size, fileSize(entry.sizeBytes)), style = MaterialTheme.typography.bodyMedium)
                    when {
                        tooLarge -> Text(stringResource(R.string.workspace_files_too_large, fileSize(DshWorkspaceFiles.MAX_FILE_BYTES)))
                        alreadyAdded -> Text(stringResource(R.string.workspace_files_added))
                        else -> {
                            val label = stringResource(R.string.workspace_files_add_named, entry.name)
                            TextButton({ add(entry) }, enabled = enabled,
                                modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget).semantics { contentDescription = label }) {
                                Text(stringResource(R.string.workspace_files_add))
                            }
                        }
                    }
                }
            }
        }
        item { WorkspaceFilesBack(onBack) }
    }
}

@Composable
internal fun ExportsScreen(state: FilesUiState, chooseDestination: (WorkspaceExportFile) -> Unit,
                           remove: (WorkspaceExportFile) -> Unit, cancel: () -> Unit, onBack: () -> Unit) {
    val enabled = !state.busy && !state.pickerActive
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(TokenWorksSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md)) {
        item {
            Text(stringResource(R.string.workspace_files_export_list), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.workspace_files_export_hint), style = MaterialTheme.typography.bodyMedium)
        }
        item { WorkspaceFileStatus(state, cancel) }
        if (state.workspaceExports.isEmpty()) item { Text(stringResource(R.string.workspace_files_export_empty)) }
        items(state.workspaceExports, key = { it.file.parts.joinToString("/") }) { item ->
            val file = item.file
            WorkspaceFileCard(file.parts.joinToString("/")) {
                Text(stringResource(R.string.workspace_files_size, fileSize(file.sizeBytes)), style = MaterialTheme.typography.bodyMedium)
                if (item.saved) Text(stringResource(R.string.workspace_files_export_saved), style = MaterialTheme.typography.bodyMedium)
                val saveLabel = stringResource(R.string.workspace_files_export_save_named, file.name)
                Button({ chooseDestination(file) }, enabled = enabled,
                    modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget).semantics { contentDescription = saveLabel }) {
                    Text(stringResource(R.string.workspace_files_export_save))
                }
                val removeLabel = stringResource(R.string.workspace_files_export_remove_named, file.name)
                TextButton({ remove(file) }, enabled = enabled,
                    modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget).semantics { contentDescription = removeLabel }) {
                    Text(stringResource(R.string.workspace_files_export_remove))
                }
            }
        }
        item { WorkspaceFilesBack(onBack) }
    }
}

@Composable
private fun WorkspaceFileCard(title: String, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = tokenWorksSquircle(TokenWorksRadius.md), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(TokenWorksSpacing.md), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun WorkspaceFilesBack(onBack: () -> Unit) {
    TextButton(onBack, Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text(stringResource(R.string.workspace_files_back)) }
}

@Composable
private fun WorkspaceFileStatus(state: FilesUiState, cancel: () -> Unit) {
    when (state.operation) {
        FilesOperation.BROWSE -> {
            TokenWorksStatusNotice(stringResource(R.string.workspace_files_loading), tone = TokenWorksNoticeTone.INFO)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(cancel, Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text(stringResource(R.string.workspace_files_cancel_loading)) }
        }
        FilesOperation.EXPORT -> {
            val total = state.totalBytes ?: 0L
            Text(stringResource(R.string.workspace_files_export_progress, fileSize(state.progressBytes), fileSize(total)))
            val progress = if (total > 0) (state.progressBytes.toFloat() / total).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            TextButton(cancel, Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text(stringResource(R.string.workspace_files_export_cancel)) }
        }
        else -> Unit
    }
    state.error?.let { TokenWorksStatusNotice(workspaceFileError(it), tone = TokenWorksNoticeTone.ERROR) }
    if (state.exported) TokenWorksStatusNotice(stringResource(R.string.workspace_files_export_success), tone = TokenWorksNoticeTone.SUCCESS)
    if (state.exportCancelled) TokenWorksStatusNotice(stringResource(R.string.workspace_files_export_cancelled), tone = TokenWorksNoticeTone.INFO)
}

@Composable
private fun workspaceFileError(code: String): String = when (code) {
    "remote-access/denied", "gateway/peer-disposed", "access.revoked", "access/revoked" -> stringResource(R.string.workspace_files_denied)
    "workspace-export/unavailable", "gateway/lookup-not-found" -> stringResource(R.string.workspace_files_unavailable)
    "workspace-export/unsupported", "gateway/invocation-unavailable", "gateway/definition-unavailable" -> stringResource(R.string.workspace_files_unsupported)
    "workspace-export/source-changed" -> stringResource(R.string.workspace_files_source_changed)
    "files.export_list_full" -> stringResource(R.string.workspace_files_export_list_full)
    "files.integrity_mismatch" -> stringResource(R.string.workspace_files_integrity_error)
    "files.destination_unavailable" -> stringResource(R.string.workspace_files_destination_unavailable)
    "files.destination_cleanup_failed" -> stringResource(R.string.workspace_files_cleanup_error)
    else -> stringResource(R.string.workspace_files_generic_error, code)
}

@Composable private fun fileSize(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)
