package cn.edu.ustc.tokenworks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private fun HostConnectionPath.labelRes() = when (this) {
    HostConnectionPath.AUTO -> R.string.host_management_path_auto
    HostConnectionPath.LAN -> R.string.host_management_path_lan
    HostConnectionPath.P2P -> R.string.host_management_path_p2p
    HostConnectionPath.TURN -> R.string.host_management_path_turn
    HostConnectionPath.RELAY -> R.string.host_management_path_relay
}
private fun HostReachability.labelRes() = when (this) {
    HostReachability.UNKNOWN -> R.string.host_management_reachability_unknown
    HostReachability.CHECKING -> R.string.host_management_reachability_checking
    HostReachability.REACHABLE -> R.string.host_management_reachability_reachable
    HostReachability.UNREACHABLE -> R.string.host_management_reachability_unreachable
    HostReachability.AUTH_REQUIRED -> R.string.host_management_reachability_auth_required
}
private fun HostManagementNotice.labelRes() = when (this) {
    HostManagementNotice.RTC_ALLOWED -> R.string.host_management_rtc_allowed
    HostManagementNotice.RTC_STOPPED -> R.string.host_management_rtc_stopped
    HostManagementNotice.REVOCATION_RECHECKED -> R.string.host_management_revocation_rechecked
    HostManagementNotice.REVOCATION_CONFIRMED -> R.string.host_management_revocation_confirmed
}
@Composable
private fun managementStateLabel(value: String) = when (value) {
    "active" -> stringResource(R.string.host_management_state_active)
    "pending" -> stringResource(R.string.host_management_state_pending)
    "revoked" -> stringResource(R.string.host_management_state_revoked)
    "expired" -> stringResource(R.string.host_management_state_expired)
    "inactive" -> stringResource(R.string.host_management_state_inactive)
    else -> stringResource(R.string.host_management_state_unknown, value)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostManagementScreen(
    state: HostManagementState, onBack: () -> Unit, onRefresh: () -> Unit,
    onLoadDevices: () -> Unit, onLoadInstallations: () -> Unit, onProfiles: (String) -> Unit,
    onGrants: (String) -> Unit, onRevoke: (ManagementTarget) -> Unit, onCancelRevoke: () -> Unit,
    onConfirmRevoke: () -> Unit, onProbe: (String, HostConnectionPath) -> Unit, onConnect: (String, HostConnectionPath) -> Unit,
    onRtcConsent: (String, Boolean) -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TokenWorksTopBar(title = stringResource(R.string.host_management_title), navigationIcon = {
            IconButton(onBack, Modifier.size(TokenWorksSpacing.minimumTouchTarget)) { ShellIcon(R.drawable.ic_back, R.string.host_management_back) }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(TokenWorksSpacing.lg), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md)) {
            item {
                Text(stringResource(R.string.host_management_intro), style = MaterialTheme.typography.bodyMedium)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = TokenWorksSpacing.sm))
                state.error?.let { TokenWorksStatusNotice(stringResource(R.string.host_management_error, it), tone = TokenWorksNoticeTone.ERROR) }
                state.notice?.let { TokenWorksStatusNotice(stringResource(it.labelRes())) }
                if (state.mutationUnknown) TokenWorksStatusNotice(stringResource(R.string.host_management_revocation_unknown),
                    tone = TokenWorksNoticeTone.WARNING)
                if (state.relayManagementAvailable) TextButton(onClick = onRefresh, enabled = !state.busy) { Text(stringResource(R.string.host_management_refresh)) }
                else TokenWorksStatusNotice(stringResource(R.string.host_management_lan_only), tone = TokenWorksNoticeTone.INFO)
                state.transport?.let {
                    val error = it.error
                    val label = if (error == null) stringResource(R.string.host_management_transport, it.path, it.phase)
                    else stringResource(R.string.host_management_transport_error, it.path, it.phase, error)
                    Text(label, style = MaterialTheme.typography.bodySmall, color = tokenWorksSurfaces().textTertiary)
                }
            }
            item { Text(stringResource(R.string.host_management_hosts), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) }
            if (state.hosts.isEmpty()) item { TokenWorksEmptyState(title = stringResource(R.string.host_management_empty)) }
            state.hosts.forEach { group ->
                item(key = "host:${group.hostId}") {
                    Surface(Modifier.fillMaxWidth(), shape = tokenWorksSquircle(TokenWorksRadius.lg),
                        color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(TokenWorksSpacing.lg), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                        val alias = group.alias ?: stringResource(R.string.host_management_lan_host)
                        Text(alias, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        Text(stringResource(R.string.host_management_host_identity, group.hostId), style = MaterialTheme.typography.bodySmall, color = tokenWorksSurfaces().textTertiary)
                        group.runtimes.forEach { row ->
                            Text(stringResource(R.string.host_management_runtime_identity, row.identity.runtimeId))
                            val installation = row.identity.installationId ?: stringResource(R.string.host_management_installation_unknown)
                            Text(stringResource(R.string.host_management_installation_identity, installation), style = MaterialTheme.typography.bodySmall)
                            val available = row.identity.relayAuthorized || !state.relayManagementAvailable
                            if (!available) Text(stringResource(R.string.host_management_lan_separate))
                            val rtcConsent = state.rtcConsents[row.identity.runtimeId] == true
                            val rtcEnabled = rtcConsent && state.rtcSupported
                            if (HostConnectionPath.P2P in row.paths) {
                                Text(stringResource(R.string.host_management_rtc_description), style = MaterialTheme.typography.bodySmall)
                                if (!state.rtcSupported) Text(stringResource(R.string.host_management_rtc_unsupported), style = MaterialTheme.typography.bodySmall)
                                val consentDescription = stringResource(R.string.host_management_rtc_consent_description)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(stringResource(R.string.host_management_rtc_consent), Modifier.weight(1f).padding(top = 12.dp))
                                    Switch(checked = rtcConsent, onCheckedChange = { onRtcConsent(row.identity.runtimeId, it) },
                                        enabled = !state.busy && available && (state.rtcSupported || rtcConsent),
                                        modifier = Modifier.semantics { contentDescription = consentDescription })
                                }
                            }
                            row.paths.forEach { path ->
                                val status = row.status[path] ?: HostPathStatus()
                                val consentAllowed = path !in setOf(HostConnectionPath.P2P, HostConnectionPath.TURN) || rtcEnabled
                                val auto = path == HostConnectionPath.AUTO
                                val label = stringResource(path.labelRes())
                                val statusRes = if (auto) R.string.host_management_path_auto_status else status.reachability.labelRes()
                                Text(stringResource(R.string.host_management_path_status, label, stringResource(statusRes)))
                                if (auto) {
                                    val order = listOfNotNull(stringResource(R.string.host_management_path_lan),
                                        if (rtcEnabled) stringResource(R.string.host_management_path_p2p_short) else null,
                                        if (rtcEnabled) stringResource(R.string.host_management_path_turn_short) else null,
                                        if (HostConnectionPath.RELAY in row.paths) stringResource(R.string.host_management_path_relay) else null)
                                    Text(order.joinToString(" → "), style = MaterialTheme.typography.bodySmall)
                                } else row.connectionFailures[path]?.let { count ->
                                    Text(stringResource(R.string.host_management_connection_failures, count), style = MaterialTheme.typography.bodySmall)
                                }
                                status.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (!auto) {
                                        val description = stringResource(R.string.host_management_probe_description, alias, row.identity.runtimeId, label)
                                        OutlinedButton(onClick = { onProbe(row.identity.runtimeId, path) }, enabled = !state.busy && available && consentAllowed,
                                            modifier = Modifier.semantics { contentDescription = description }) { Text(stringResource(R.string.host_management_probe)) }
                                    }
                                    val connectDescriptionRes = if (auto) R.string.host_management_connect_description else R.string.host_management_connect_fixed_description
                                    val connectDescription = stringResource(connectDescriptionRes, alias, row.identity.runtimeId, label)
                                    Button(onClick = { onConnect(row.identity.runtimeId, path) }, enabled = !state.busy && available && consentAllowed,
                                        modifier = Modifier.semantics { contentDescription = connectDescription }) {
                                        Text(stringResource(if (auto) R.string.host_management_connect else R.string.host_management_connect_fixed))
                                    }
                                }
                            }
                            if (row.identity.relayAuthorized && state.relayManagementAvailable)
                                TextButton(onClick = { onGrants(row.identity.runtimeId) }, enabled = !state.busy) { Text(stringResource(R.string.host_management_view_grants)) }
                        }
                    } }
                }
            }
            if (state.relayManagementAvailable) {
                item { Text(stringResource(R.string.host_management_devices), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) }
                state.devices.forEach { device -> item(key = "device:${device.id}") {
                    val name = if (device.current) stringResource(R.string.host_management_current_device, device.name) else device.name
                    ManagementRecord(name, device.id, device.state,
                        !state.busy && !state.mutationUnknown && device.state == "active", { onRevoke(ManagementTarget.Device(device.id)) })
                } }
                if (state.devicesCursor != null) item { TextButton(onClick = onLoadDevices, enabled = !state.busy) { Text(stringResource(R.string.host_management_load_devices)) } }
                item { Text(stringResource(R.string.host_management_installations), style = MaterialTheme.typography.titleLarge) }
                state.installations.forEach { installation -> item(key = "installation:${installation.id}") {
                    Column {
                        ManagementRecord(installation.alias, installation.id, installation.state, !state.busy && !state.mutationUnknown && installation.state in setOf("active", "pending"), { onRevoke(ManagementTarget.Installation(installation.id)) })
                        TextButton(onClick = { onProfiles(installation.id) }, enabled = !state.busy) { Text(stringResource(R.string.host_management_view_profiles)) }
                    }
                } }
                if (state.installationsCursor != null) item { TextButton(onClick = onLoadInstallations, enabled = !state.busy) { Text(stringResource(R.string.host_management_load_installations)) } }
                state.profileInstallation?.let { installation ->
                    item { Text(stringResource(R.string.host_management_installation_profiles, installation), style = MaterialTheme.typography.titleMedium) }
                    state.profiles.forEach { profile -> item(key = "profile:${profile.id}") {
                        ManagementRecord(stringResource(R.string.host_management_runtime), profile.id, profile.state, !state.busy && !state.mutationUnknown && profile.state in setOf("active", "pending"), { onRevoke(ManagementTarget.Profile(installation, profile.id)) })
                    } }
                    if (state.profilesCursor != null) item { TextButton(onClick = { onProfiles(installation) }, enabled = !state.busy) { Text(stringResource(R.string.host_management_load_profiles)) } }
                }
                state.grantRuntime?.let { runtime ->
                    item { Text(stringResource(R.string.host_management_runtime_grants, runtime), style = MaterialTheme.typography.titleMedium) }
                    state.grants.forEach { grant -> item(key = "grant:$runtime:${grant.userId}") {
                        ManagementRecord(stringResource(R.string.host_management_grant_role_version, grant.role, grant.version), grant.userId, grant.state, !state.busy && !state.mutationUnknown && grant.state == "active", { onRevoke(ManagementTarget.Grant(runtime, grant.userId)) })
                    } }
                    if (state.grantsCursor != null) item { TextButton(onClick = { onGrants(runtime) }, enabled = !state.busy) { Text(stringResource(R.string.host_management_load_grants)) } }
                }
            }
        }
    }
    state.confirmation?.let { target ->
        val detail = when (target) {
            is ManagementTarget.Device -> stringResource(R.string.host_management_revoke_device, target.id)
            is ManagementTarget.Installation -> stringResource(R.string.host_management_revoke_installation, target.id)
            is ManagementTarget.Profile -> stringResource(R.string.host_management_revoke_profile, target.installationId, target.runtimeId)
            is ManagementTarget.Grant -> stringResource(R.string.host_management_revoke_grant, target.runtimeId, target.userId)
        }
        AlertDialog(onDismissRequest = onCancelRevoke, title = { Text(stringResource(R.string.host_management_revoke_title)) },
            text = { Text(stringResource(R.string.host_management_revoke_detail, detail)) },
            confirmButton = { TextButton(onClick = onConfirmRevoke, enabled = !state.busy) { Text(stringResource(R.string.host_management_revoke_confirm)) } },
            dismissButton = { TextButton(onClick = onCancelRevoke, enabled = !state.busy) { Text(stringResource(R.string.host_management_cancel)) } })
    }
}

@Composable
private fun ManagementRecord(title: String, id: String, state: String, canRevoke: Boolean, onRevoke: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = tokenWorksSquircle(TokenWorksRadius.md),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(horizontal = TokenWorksSpacing.lg, vertical = TokenWorksSpacing.md),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xxs)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(id, style = MaterialTheme.typography.bodySmall, color = tokenWorksSurfaces().textTertiary)
                Text(managementStateLabel(state), style = MaterialTheme.typography.labelMedium,
                    color = when (state) {
                        "active" -> tokenWorksSurfaces().success
                        "revoked", "expired", "inactive" -> tokenWorksSurfaces().textTertiary
                        "pending" -> tokenWorksSurfaces().warning
                        else -> tokenWorksSurfaces().textTertiary
                    })
            }
            TextButton(onClick = onRevoke, enabled = canRevoke,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text(stringResource(R.string.host_management_revoke)) }
        }
    }
}
