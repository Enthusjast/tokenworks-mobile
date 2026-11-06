package cn.edu.ustc.tokenworks

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
internal fun AppUpdatesScreen(state: AppUpdateUiState, check: () -> Unit, download: () -> Unit, install: () -> Unit,
    permission: () -> Unit, cleanup: (String) -> Unit, cancelDownload: () -> Unit, back: () -> Unit) {
    var confirmCleanup by remember(state.viewId) { mutableStateOf<String?>(null) }
    LaunchedEffect(state.cleanupToken, state.canCleanup) { if (!state.canCleanup || confirmCleanup != state.cleanupToken) confirmCleanup = null }
    var showReleaseDetails by remember(state.viewId, state.release) { mutableStateOf(false) }
    val enabled = !state.busy && !state.launchPending
    Scaffold(topBar = {
        io.github.enthusjast.tokenworks.ui.TokenWorksTopBar(
            title = stringResource(R.string.settings_updates),
            subtitle = if (state.installedVersion == null) stringResource(R.string.flow_update_version_unknown, BuildConfig.VERSION_NAME) else stringResource(R.string.flow_update_current_version, BuildConfig.VERSION_NAME, state.installedVersion.toString()),
            contentMaxWidth = 720.dp,
            navigationIcon = { IconButton(back, Modifier.heightIn(min = 48.dp)) {
                Icon(painterResource(R.drawable.ic_back), stringResource(R.string.flow_update_back))
            } },
        )
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxWidth(), contentPadding = PaddingValues(io.github.enthusjast.tokenworks.ui.TokenWorksSpacing.lg), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item("stage") {
                    Text(stringResource(when (state.phase) {
                        AppUpdatePhase.AVAILABLE, AppUpdatePhase.DOWNLOADING, AppUpdatePhase.VERIFYING, AppUpdatePhase.REPREPARE -> R.string.flow_update_step_prepare
                        AppUpdatePhase.PREPARED, AppUpdatePhase.WAITING_USER -> R.string.flow_update_step_install
                        AppUpdatePhase.UNKNOWN, AppUpdatePhase.INSTALLED, AppUpdatePhase.FAILED, AppUpdatePhase.BLOCKED -> R.string.flow_update_step_reconcile
                        else -> R.string.flow_update_step_check
                    }), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                }
                item("status") {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(updatePhaseTitle(state.phase), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            Text(updatePhaseDescription(state.phase), style = MaterialTheme.typography.bodyMedium)
                            if (state.currentSupported == false) Text(stringResource(R.string.workspace_update_unsupported), color = MaterialTheme.colorScheme.error)
                            if (state.phase in setOf(AppUpdatePhase.DOWNLOADING, AppUpdatePhase.VERIFYING, AppUpdatePhase.PREPARED, AppUpdatePhase.WAITING_USER, AppUpdatePhase.UNKNOWN)) Text(stringResource(R.string.flow_update_not_installed), style = MaterialTheme.typography.bodySmall)
                            if (state.phase == AppUpdatePhase.DOWNLOADING && state.release != null) {
                                val total = state.release.sizeBytes
                                LinearProgressIndicator(progress = { (state.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                Text(stringResource(R.string.workspace_update_download_progress, updateSize(state.downloadedBytes), updateSize(total)), style = MaterialTheme.typography.bodySmall)
                            } else if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            when (state.phase) {
                                AppUpdatePhase.AVAILABLE -> Button(if (state.canInstall) download else permission, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                    Text(if (state.canInstall) stringResource(R.string.workspace_update_download_verify) else stringResource(R.string.workspace_update_allow_install))
                                }
                                AppUpdatePhase.PREPARED, AppUpdatePhase.WAITING_USER -> Button(if (state.canInstall) install else permission, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                    Text(if (!state.canInstall) stringResource(R.string.workspace_update_allow_install) else if (state.phase == AppUpdatePhase.PREPARED) stringResource(R.string.workspace_update_open_installer) else stringResource(R.string.workspace_update_continue_install))
                                }
                                AppUpdatePhase.IDLE, AppUpdatePhase.CURRENT, AppUpdatePhase.INSTALLED, AppUpdatePhase.FAILED, AppUpdatePhase.ERROR, AppUpdatePhase.REPREPARE, AppUpdatePhase.UNKNOWN -> Button(check, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                    Text(if (state.phase == AppUpdatePhase.UNKNOWN) stringResource(R.string.workspace_update_reconcile) else if (state.phase in setOf(AppUpdatePhase.ERROR, AppUpdatePhase.FAILED, AppUpdatePhase.REPREPARE)) stringResource(R.string.workspace_update_recheck) else stringResource(R.string.workspace_update_check))
                                }
                                else -> Unit
                            }
                            if (state.phase in setOf(AppUpdatePhase.DOWNLOADING, AppUpdatePhase.VERIFYING)) OutlinedButton(cancelDownload, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.workspace_update_cancel_download)) }
                            if (state.phase in setOf(AppUpdatePhase.AVAILABLE, AppUpdatePhase.PREPARED, AppUpdatePhase.WAITING_USER)) TextButton(check, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(if (state.phase == AppUpdatePhase.AVAILABLE) stringResource(R.string.workspace_update_recheck) else stringResource(R.string.workspace_update_reconcile))
                            }
                            if (state.canCleanup && state.cleanupToken != null) TextButton({ confirmCleanup = state.cleanupToken }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.workspace_update_abandon)) }
                            state.error?.let { Text(appUpdateErrorText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                        }
                }
                state.release?.let { release -> item("release") {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            HorizontalDivider()
                            Text(stringResource(R.string.workspace_update_release_version, release.versionName, release.versionCode.toString()), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                            Text(stringResource(R.string.workspace_update_download_size, updateSize(release.sizeBytes)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val expansion = stringResource(if (showReleaseDetails) R.string.detail_expanded else R.string.detail_collapsed)
                            TextButton({ showReleaseDetails = !showReleaseDetails }, Modifier.heightIn(min = 48.dp).semantics { stateDescription = expansion }) {
                                Text(stringResource(if (showReleaseDetails) R.string.workspace_panel_hide_release else R.string.workspace_panel_release))
                            }
                            if (showReleaseDetails) SelectionContainer {
                                Text(release.releaseNotes.ifBlank { stringResource(R.string.flow_update_no_notes) })
                            }
                        }
                } }
            }
        }
    }
    val confirmedContext = confirmCleanup
    if (confirmedContext != null && confirmedContext == state.cleanupToken && state.canCleanup) AlertDialog(onDismissRequest = { confirmCleanup = null }, title = { Text(stringResource(R.string.workspace_update_abandon_title)) },
        text = { Text(stringResource(R.string.workspace_update_abandon_detail)) },
        confirmButton = { TextButton({ confirmCleanup = null; cleanup(confirmedContext) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.workspace_update_abandon_confirm)) } },
        dismissButton = { TextButton({ confirmCleanup = null }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.workspace_update_keep)) } })
}

private fun updateSize(bytes: Long) = String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
@Composable
private fun updatePhaseTitle(phase: AppUpdatePhase) = when (phase) {
    AppUpdatePhase.LOADING -> stringResource(R.string.workspace_update_phase_loading)
    AppUpdatePhase.UNCONFIGURED -> stringResource(R.string.workspace_update_phase_unconfigured)
    AppUpdatePhase.IDLE -> stringResource(R.string.workspace_update_phase_idle)
    AppUpdatePhase.CHECKING -> stringResource(R.string.workspace_update_phase_checking)
    AppUpdatePhase.CURRENT -> stringResource(R.string.workspace_update_phase_current)
    AppUpdatePhase.AVAILABLE -> stringResource(R.string.workspace_update_phase_available)
    AppUpdatePhase.DOWNLOADING -> stringResource(R.string.workspace_update_phase_downloading)
    AppUpdatePhase.VERIFYING -> stringResource(R.string.workspace_update_phase_verifying)
    AppUpdatePhase.PREPARED -> stringResource(R.string.workspace_update_phase_prepared)
    AppUpdatePhase.REPREPARE -> stringResource(R.string.workspace_update_phase_reprepare)
    AppUpdatePhase.WAITING_USER -> stringResource(R.string.workspace_update_phase_waiting)
    AppUpdatePhase.UNKNOWN -> stringResource(R.string.workspace_update_phase_unknown)
    AppUpdatePhase.INSTALLED -> stringResource(R.string.workspace_update_phase_installed)
    AppUpdatePhase.FAILED -> stringResource(R.string.workspace_update_phase_failed)
    AppUpdatePhase.BLOCKED -> stringResource(R.string.workspace_update_phase_blocked)
    AppUpdatePhase.ERROR -> stringResource(R.string.workspace_update_phase_error)
}
@Composable
private fun updatePhaseDescription(phase: AppUpdatePhase) = when (phase) {
    AppUpdatePhase.UNCONFIGURED -> stringResource(R.string.workspace_update_detail_unconfigured)
    AppUpdatePhase.AVAILABLE -> stringResource(R.string.workspace_update_detail_available)
    AppUpdatePhase.PREPARED, AppUpdatePhase.WAITING_USER -> stringResource(R.string.workspace_update_detail_prepared)
    AppUpdatePhase.REPREPARE -> stringResource(R.string.workspace_update_detail_reprepare)
    AppUpdatePhase.UNKNOWN -> stringResource(R.string.workspace_update_detail_unknown)
    AppUpdatePhase.INSTALLED -> stringResource(R.string.workspace_update_detail_installed)
    AppUpdatePhase.BLOCKED -> stringResource(R.string.workspace_update_detail_blocked)
    AppUpdatePhase.DOWNLOADING, AppUpdatePhase.VERIFYING -> stringResource(R.string.workspace_update_detail_downloading)
    AppUpdatePhase.FAILED -> stringResource(R.string.workspace_update_detail_failed)
    AppUpdatePhase.ERROR -> stringResource(R.string.workspace_update_detail_error)
    else -> stringResource(R.string.workspace_update_detail_default)
}
@Composable
internal fun appUpdateErrorText(code: String) = when (code) {
    "update.configuration", "update.not_configured" -> stringResource(R.string.workspace_update_error_configuration)
    "update.publication_changed" -> stringResource(R.string.workspace_update_error_publication)
    "update.install_permission_required" -> stringResource(R.string.workspace_update_error_permission)
    "update.installed_changed" -> stringResource(R.string.workspace_update_error_installed)
    "update.installation_changed" -> stringResource(R.string.workspace_update_error_pending)
    "update.prepare_again", "update.state_changed" -> stringResource(R.string.workspace_update_error_reprepare)
    "update.apk_invalid", "update.integrity_mismatch", "update.identity_mismatch", "update.incompatible" -> stringResource(R.string.workspace_update_error_integrity)
    "update.other_context" -> stringResource(R.string.workspace_update_error_owner)
    "update.storage_unavailable", "update.installation_unknown", "update.pending_installation" -> stringResource(R.string.workspace_update_error_unknown)
    "update.system_ui_unavailable" -> stringResource(R.string.workspace_update_error_system)
    "auth.expired", "auth.proof_invalid", "auth.reauthentication_required", "access.denied", "access.revoked", "device.invalid" -> stringResource(R.string.workspace_update_error_auth)
    else -> stringResource(R.string.workspace_update_error_generic)
}
