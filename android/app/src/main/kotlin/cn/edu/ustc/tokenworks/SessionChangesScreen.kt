package cn.edu.ustc.tokenworks

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.enthusjast.tokenworks.network.SessionChangesPhase
import io.github.enthusjast.tokenworks.network.SessionChangesState
import io.github.enthusjast.tokenworks.protocol.SessionChangeFile
import io.github.enthusjast.tokenworks.ui.TokenWorksSpacing

@Stable
internal class ChangesPanelUiState {
    var showSource by mutableStateOf(false)
    var wrapCode by mutableStateOf(true)
    var expandedFileIds by mutableStateOf<Set<String>>(emptySet())
    val listState = LazyListState()
}

/** All lazy code lines share both the offset and measured minimum width. */
@Stable
internal class ChangePatchUiState {
    val horizontal = ScrollState(0)
    var contentWidth by mutableStateOf(0.dp)
}

/** Read-only configured-workspace snapshots; display paths never become executable selectors. */
@Composable
internal fun SessionChangesScreen(
    state: SessionChangesState,
    loadInitial: () -> Unit,
    loadNext: () -> Unit,
    reload: () -> Unit,
    loadPatch: (String) -> Unit,
    loadNextPatch: () -> Unit,
    copyComplete: () -> Unit,
    back: () -> Unit,
    uiState: ChangesPanelUiState,
    embedded: Boolean = false,
    patchUiState: ChangePatchUiState = remember(state.contextId, state.patch?.fileId, state.patch?.version) { ChangePatchUiState() },
) {
    val available = state.phase == SessionChangesPhase.READY || state.phase == SessionChangesPhase.EMPTY
    val patch = state.patch
    val exactPatchText = patch?.takeUnless { it.partial }?.patchUtf8
    val patchLines = remember(state.contextId, exactPatchText) { exactPatchText?.splitToSequence('\n')?.toList().orEmpty() }
    val noFinalNewline = remember(patch) { patch?.hunks?.any { hunk -> hunk.lines.any { it.kind == "no_final_newline" } } == true }
    var showSource by uiState::showSource
    var wrapCode by uiState::wrapCode
    LaunchedEffect(state.files) {
        if (uiState.expandedFileIds.isNotEmpty()) {
            val currentIds = state.files.mapTo(HashSet()) { it.fileId }
            if (uiState.expandedFileIds.any { it !in currentIds }) uiState.expandedFileIds = uiState.expandedFileIds.filterTo(HashSet()) { it in currentIds }
        }
    }
    WorkspacePanelFrame(stringResource(R.string.changes_fork_changes_title), back, embedded,
        subtitle = stringResource(R.string.changes_fork_changes_subtitle)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = TokenWorksSpacing.chatMaxWidth).fillMaxSize().testTag("changes-list"), state = uiState.listState,
                contentPadding = PaddingValues(TokenWorksSpacing.page),
                verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.medium)) {
                item("scope") {
                    val expansion = stringResource(if (showSource) R.string.detail_expanded else R.string.detail_collapsed)
                    TextButton({ showSource = !showSource }, Modifier.heightIn(min = 48.dp).semantics { stateDescription = expansion }) {
                        Text(stringResource(if (showSource) R.string.workspace_panel_hide_source else R.string.workspace_panel_source))
                    }
                    if (showSource) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.changes_fork_scope), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.changes_fork_excluded), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.changes_fork_read_only), style = MaterialTheme.typography.bodySmall)
                    }
                }
                item("status") {
                    Text(stringResource(changesPhaseText(state.phase)),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    if (state.phase == SessionChangesPhase.IDLE) OutlinedButton(loadInitial, enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.changes_fork_load))
                    }
                    if (state.phase in setOf(SessionChangesPhase.READY, SessionChangesPhase.EMPTY,
                            SessionChangesPhase.STALE, SessionChangesPhase.ERROR)) {
                        TextButton(reload, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.changes_fork_reload))
                        }
                    }
                }
                state.error?.let { code -> item("error") {
                    Text(changesForkErrorText(code), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                } }
                state.manifest?.let { manifest -> item("manifest") {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.changes_fork_snapshot), style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.semantics { heading() })
                            SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.changes_fork_base, manifest.baseOid), style = MaterialTheme.typography.bodySmall)
                                Text(stringResource(R.string.changes_fork_version, manifest.version), style = MaterialTheme.typography.bodySmall)
                                if (showSource) {
                                    Text(stringResource(R.string.changes_fork_manifest, manifest.manifestSha256), style = MaterialTheme.typography.bodySmall)
                                    Text(stringResource(R.string.changes_fork_snapshot_id, manifest.snapshotId), style = MaterialTheme.typography.bodySmall)
                                }
                            } }
                            if (showSource) Text(stringResource(R.string.changes_fork_manifest_scope), style = MaterialTheme.typography.bodySmall)
                    }
                } }
                if (state.files.isNotEmpty()) item("files-heading") {
                    Text(stringResource(R.string.changes_fork_files, state.files.size), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() })
                    Text(stringResource(if (state.hasMore) R.string.changes_fork_files_partial else R.string.changes_fork_files_complete),
                        style = MaterialTheme.typography.bodySmall)
                }
                items(state.files, key = { it.fileId }) { file ->
                    ChangeFileCard(file, patch?.fileId == file.fileId, available && !state.loading,
                        details = file.fileId in uiState.expandedFileIds,
                        onDetails = { expanded -> uiState.expandedFileIds = if (expanded) uiState.expandedFileIds + file.fileId else uiState.expandedFileIds - file.fileId }) { loadPatch(file.fileId) }
                }
                if (state.hasMore) item("files-next") {
                    OutlinedButton(loadNext, enabled = available && !state.loading,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.changes_fork_files_next))
                    }
                }
                if (patch != null) {
                    item("patch-heading") {
                        HorizontalDivider()
                        Text(stringResource(R.string.changes_fork_patch_title), style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() })
                        val file = state.files.firstOrNull { it.fileId == patch.fileId }
                        Text(file?.newPath?.display ?: file?.oldPath?.display ?: patch.fileId,
                            style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.changes_fork_version, patch.version), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(if (state.canCopyComplete) R.string.changes_fork_patch_complete else R.string.changes_fork_patch_partial),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.canCopyComplete) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                        FilterChip(wrapCode, { wrapCode = !wrapCode },
                            label = { Text(stringResource(R.string.workspace_panel_wrap_code)) }, modifier = Modifier.heightIn(min = 48.dp))
                        Text(stringResource(R.string.changes_fork_selection_partial), style = MaterialTheme.typography.bodySmall)
                        if (patch.partial) Text(stringResource(R.string.changes_fork_structured_fragments), style = MaterialTheme.typography.bodySmall)
                        if (noFinalNewline) Text(stringResource(R.string.changes_fork_no_final_newline), style = MaterialTheme.typography.bodyMedium)
                        if (patch.kind != "text") Text(changeKindText(patch.kind), style = MaterialTheme.typography.bodyMedium)
                        if (patch.patchUtf8 == null) Text(stringResource(R.string.changes_fork_patch_unavailable),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    if (patch.partial) {
                        patch.hunks.forEachIndexed { hunkIndex, hunk ->
                            item("patch-hunk:$hunkIndex") {
                                ChangeCodeLine("@@ -${hunk.oldStart},${hunk.oldCount} +${hunk.newStart},${hunk.newCount} @@",
                                    "hunk:$hunkIndex", wrapCode, patchUiState)
                            }
                            items(hunk.lines.size, key = { "patch-hunk:$hunkIndex:$it" }) { index ->
                                val line = hunk.lines[index]
                                val text = line.text
                                ChangeCodeLine(if (text == null) stringResource(R.string.changes_fork_line_unavailable) else when (line.kind) {
                                    "add" -> "+$text"
                                    "delete" -> "-$text"
                                    "context" -> " $text"
                                    else -> text
                                }, "hunk:$hunkIndex:$index", wrapCode, patchUiState)
                            }
                        }
                    } else {
                        items(patchLines.size, key = { "patch-line:$it" }) { index ->
                            ChangeCodeLine(patchLines[index], "line:$index", wrapCode, patchUiState)
                        }
                    }
                    if (state.patchHasMore) item("patch-next") {
                        OutlinedButton(loadNextPatch, enabled = available && !state.loading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.changes_fork_patch_next))
                        }
                    }
                    item("patch-copy") {
                        Button(copyComplete, enabled = available && !state.loading && state.canCopyComplete,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.changes_fork_copy_complete))
                        }
                        Text(stringResource(R.string.changes_fork_copy_proof), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangeCodeLine(text: String, lineKey: String, wrap: Boolean, state: ChangePatchUiState) {
    val density = LocalDensity.current
    val lineModifier = if (wrap) Modifier.fillMaxWidth() else Modifier.horizontalScroll(state.horizontal).widthIn(min = state.contentWidth)
    SelectionContainer {
        Text(text, lineModifier.testTag("changes-code:$lineKey"),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), softWrap = wrap,
            onTextLayout = { result ->
                if (!wrap) {
                    val width = with(density) { result.size.width.toDp() }
                    if (width > state.contentWidth) state.contentWidth = width
                }
            })
    }
}

@Composable
private fun ChangeFileCard(file: SessionChangeFile, selected: Boolean, enabled: Boolean,
    details: Boolean, onDetails: (Boolean) -> Unit, open: () -> Unit) {
    Surface(Modifier.fillMaxWidth().semantics { this.selected = selected },
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(vertical = 12.dp, horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(changeStatusText(file.status), style = MaterialTheme.typography.titleSmall)
            file.oldPath?.let { Text(stringResource(R.string.changes_fork_old_path, it.display)) }
            file.newPath?.let { Text(stringResource(R.string.changes_fork_new_path, it.display)) }
            val expansion = stringResource(if (details) R.string.detail_expanded else R.string.detail_collapsed)
            TextButton({ onDetails(!details) }, Modifier.heightIn(min = 48.dp).semantics { stateDescription = expansion }) {
                Text(stringResource(if (details) R.string.workspace_panel_hide_details else R.string.workspace_panel_details))
            }
            if (details) Text(stringResource(R.string.changes_fork_special_path), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(changeKindText(file.kind), style = MaterialTheme.typography.bodyMedium)
            if (details) {
                Text(stringResource(R.string.changes_fork_modes, file.oldMode, file.newMode), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.changes_fork_patch_bytes, file.patchBytes), style = MaterialTheme.typography.bodySmall)
            }
            if (!file.complete) Text(stringResource(R.string.changes_fork_file_partial), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(open, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(if (selected) R.string.changes_fork_patch_reopen else R.string.changes_fork_patch_open))
            }
        }
    }
}

private fun changesPhaseText(phase: SessionChangesPhase): Int = when (phase) {
    SessionChangesPhase.UNAVAILABLE -> R.string.changes_fork_unavailable
    SessionChangesPhase.SNAPSHOT_REQUIRED -> R.string.changes_fork_snapshot_required
    SessionChangesPhase.IDLE -> R.string.changes_fork_idle
    SessionChangesPhase.LOADING -> R.string.changes_fork_loading
    SessionChangesPhase.READY -> R.string.changes_fork_ready
    SessionChangesPhase.EMPTY -> R.string.changes_fork_empty
    SessionChangesPhase.STALE -> R.string.changes_fork_stale
    SessionChangesPhase.OFFLINE -> R.string.changes_fork_offline
    SessionChangesPhase.DENIED -> R.string.changes_fork_denied
    SessionChangesPhase.ERROR -> R.string.changes_fork_error
    SessionChangesPhase.CLOSED -> R.string.changes_fork_closed
}

@Composable
private fun changeStatusText(status: String): String = stringResource(when (status) {
    "added" -> R.string.changes_fork_added
    "modified" -> R.string.changes_fork_modified
    "deleted" -> R.string.changes_fork_deleted
    "renamed" -> R.string.changes_fork_renamed
    "type_changed" -> R.string.changes_fork_type_changed
    else -> R.string.changes_fork_file_changed
})

@Composable
private fun changeKindText(kind: String): String = stringResource(when (kind) {
    "binary" -> R.string.changes_fork_binary
    "symlink" -> R.string.changes_fork_symlink
    "gitlink" -> R.string.changes_fork_gitlink
    else -> R.string.changes_fork_text
})

@Composable
internal fun changesForkErrorText(code: String): String = stringResource(when (code) {
    "access.denied", "access.revoked", "device.invalid", "auth.identity_mismatch" -> R.string.changes_fork_denied
    "runtime.offline", "client.not_ready" -> R.string.changes_fork_offline
    "session.changes.stale", "changes.stale", "message.stale", "message.preview_stale", "session.message.stale",
    "protocol.resync_required", "runtime.changed" -> R.string.changes_fork_stale
    "command.reconcile_required", "command.delivery_unconfirmed" -> R.string.changes_fork_original_unknown
    else -> R.string.changes_fork_action_failed
})
