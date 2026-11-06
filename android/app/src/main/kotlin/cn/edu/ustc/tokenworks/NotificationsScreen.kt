package cn.edu.ustc.tokenworks

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.enthusjast.tokenworks.ui.TokenWorksSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun NotificationsScreen(state: NotificationUiState, refresh: () -> Unit, enable: () -> Unit, disable: () -> Unit, renew: () -> Unit,
    inbox: () -> Unit, more: () -> Unit, open: (String, String?) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    var details by remember { mutableStateOf(false) }
    WorkspacePanelFrame(stringResource(R.string.settings_notifications), back, embedded = false,
        backLabel = stringResource(R.string.flow_notifications_back)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = TokenWorksSpacing.chatMaxWidth).fillMaxWidth(),
                contentPadding = PaddingValues(TokenWorksSpacing.page),
                verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.large)) {
                item("channel") {
                    Column(verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.small)) {
                        Text(stringResource(R.string.flow_notifications_registration), style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() })
                        Text(stringResource(when (state.phase) {
                            NotificationPhase.LOADING -> R.string.workspace_notifications_loading
                            NotificationPhase.UNCONFIGURED -> R.string.workspace_notifications_unconfigured
                            NotificationPhase.UNAVAILABLE -> R.string.workspace_notifications_unavailable
                            NotificationPhase.DISABLED -> R.string.workspace_notifications_disabled
                            NotificationPhase.WAITING -> R.string.workspace_notifications_waiting
                            NotificationPhase.ACTIVE -> R.string.workspace_notifications_active
                            NotificationPhase.ERROR -> R.string.workspace_notifications_error
                        }), style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        Text(stringResource(R.string.flow_notifications_permission,
                            stringResource(if (state.allowed) R.string.flow_notifications_allowed else R.string.flow_notifications_not_allowed)),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        state.error?.let { Text(notificationErrorText(it), color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                        if (!state.allowed) OutlinedButton({
                            if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.workspace_notifications_allow)) }
                        if (!state.allowed && Build.VERSION.SDK_INT >= 33) TextButton({
                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.workspace_notifications_system_settings)) }
                        if (state.phase != NotificationPhase.ACTIVE && state.canEnable) Button(enable, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(if (state.phase == NotificationPhase.WAITING) R.string.workspace_notifications_continue else R.string.workspace_notifications_enable))
                        }
                        if (state.phase == NotificationPhase.ACTIVE) OutlinedButton(renew, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.workspace_notifications_renew)) }
                        TextButton(refresh, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.workspace_notifications_refresh))
                        }
                        val expansion = stringResource(if (details) R.string.detail_expanded else R.string.detail_collapsed)
                        TextButton({ details = !details }, Modifier.heightIn(min = 48.dp).semantics { stateDescription = expansion }) {
                            Text(stringResource(if (details) R.string.workspace_panel_hide_details else R.string.workspace_panel_details))
                        }
                        if (details) {
                            Text(stringResource(R.string.workspace_notifications_privacy), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.flow_notifications_foreground), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.workspace_notifications_delivery_limits), style = MaterialTheme.typography.bodySmall)
                            state.expiresAt?.let { expires ->
                                val expiry = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(expires))
                                Text(stringResource(R.string.flow_notifications_expiry, expiry), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (state.phase in setOf(NotificationPhase.WAITING, NotificationPhase.ACTIVE, NotificationPhase.ERROR)) {
                            TextButton(disable, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.workspace_notifications_disable)) }
                        }
                    }
                }
                item("reference-scope") {
                    HorizontalDivider()
                    Text(stringResource(R.string.flow_notifications_references), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = TokenWorksSpacing.medium).semantics { heading() })
                    Text(stringResource(R.string.flow_notifications_reference_scope), style = MaterialTheme.typography.bodySmall)
                    if (state.phase == NotificationPhase.ACTIVE) OutlinedButton(inbox, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.workspace_notifications_inbox))
                    }
                    if (state.items.isEmpty()) Text(stringResource(R.string.flow_notifications_no_references),
                        style = MaterialTheme.typography.bodyMedium)
                }
                state.note?.let { note -> item("note") { Text(note, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } }
                items(state.items, key = { it.id }) { reference ->
                    Column(verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.small)) {
                        Text(notificationReferenceText(reference), style = MaterialTheme.typography.bodyLarge)
                        OutlinedButton({ open(reference.bindingId, reference.id) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.workspace_notifications_open))
                        }
                        HorizontalDivider()
                    }
                }
                if (state.cursor != null) item("more") {
                    OutlinedButton(more, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.workspace_notifications_more)) }
                }
            }
        }
    }
}

@Composable
internal fun notificationErrorText(code: String): String = stringResource(when (code) {
    "push.not_configured" -> R.string.workspace_notifications_error_configuration
    "push.services_unavailable" -> R.string.workspace_notifications_error_services
    "push.permission_required" -> R.string.workspace_notifications_error_permission
    "push.conflict" -> R.string.workspace_notifications_error_conflict
    "push.expired" -> R.string.workspace_notifications_error_expired
    "push.cleanup_failed", "push.cleanup_required" -> R.string.workspace_notifications_error_cleanup
    "push.storage_unavailable" -> R.string.workspace_notifications_error_storage
    "push.stale", "access.denied", "auth.identity_mismatch" -> R.string.workspace_notifications_error_identity
    else -> R.string.workspace_notifications_error_generic
})
