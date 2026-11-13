package cn.edu.ustc.tokenworks

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow

/** Workspace chrome owns presentation only; directory actions belong to the view model. */
@Composable
internal fun TokenWorksAppShell(
    state: HomeState,
    viewModel: HomeViewModel,
    onHosts: () -> Unit,
    onSettings: () -> Unit,
    onCloseNavigation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var previousSession by remember { mutableStateOf(state.selectedSessionId) }
    LaunchedEffect(state.selectedSessionId) {
        if (state.selectedSessionId != previousSession) {
            previousSession = state.selectedSessionId
            onCloseNavigation()
        }
    }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = TokenWorksSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.workspace_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onCloseNavigation, Modifier.size(TokenWorksSpacing.minimumTouchTarget)) {
                ShellIcon(R.drawable.ic_back, R.string.workspace_close_navigation)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.selectedRuntimeId != null) SessionsScreen(state, viewModel)
            else RuntimePicker(state, viewModel)
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = TokenWorksSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onHosts, Modifier.weight(1f).heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                ShellIcon(R.drawable.ic_computers)
                Spacer(Modifier.width(TokenWorksSpacing.sm))
                Text(state.runtimeRow(state.selectedRuntimeId)?.hostAlias ?: stringResource(R.string.session_select_computer),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onSettings, Modifier.size(TokenWorksSpacing.minimumTouchTarget)) {
                ShellIcon(R.drawable.ic_settings, R.string.nav_settings)
            }
        }
    }
}

@Composable
internal fun WorkspaceRail(onNavigation: () -> Unit, onHosts: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(TokenWorksMetrics.edge), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TokenWorksMetrics.gap)) {
        RailKey(R.drawable.ic_menu, R.string.workspace_rail_sessions, onNavigation)
        RailKey(R.drawable.ic_computers, R.string.workspace_switch_host, onHosts)
        Spacer(Modifier.weight(1f))
        RailKey(R.drawable.ic_settings, R.string.nav_settings, onSettings)
    }
}

@Composable
private fun RailKey(@DrawableRes icon: Int, @StringRes description: Int, onClick: () -> Unit) {
    FilledTonalIconButton(onClick, Modifier.size(TokenWorksMetrics.key).tokenWorksPress()) {
        ShellIcon(icon, description)
    }
}

@Composable
internal fun RuntimePicker(state: HomeState, viewModel: HomeViewModel, modifier: Modifier = Modifier,
    onRuntime: (String) -> Unit = viewModel::openRuntime) {
    LazyColumn(modifier.fillMaxSize().testTag("computers-directory"), contentPadding = PaddingValues(TokenWorksSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md)) {
        item("actions") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.nav_computers), style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f).semantics { heading() })
                IconButton(viewModel::refresh, enabled = !state.refreshing,
                    modifier = Modifier.size(TokenWorksSpacing.minimumTouchTarget)) { ShellIcon(R.drawable.ic_refresh, R.string.action_refresh) }
            }
            OutlinedButton(viewModel::openBinding, Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                Text(stringResource(if (state.pendingBindingId == null) R.string.action_bind_computer else R.string.action_review_binding))
            }
            Text(stringResource(R.string.computer_scope), style = MaterialTheme.typography.bodySmall,
                color = tokenWorksSurfaces().textTertiary)
        }
        if (state.refreshing) item("loading") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md)) {
                CircularProgressIndicator(Modifier.size(TokenWorksSpacing.xxl))
                Text(stringResource(R.string.directory_loading), style = MaterialTheme.typography.bodyMedium)
            }
        }
        state.error?.let { code -> item("error") { TokenWorksStatusNotice(errorText(code), isError = true) } }
        if (!state.refreshing && state.runtimes.isEmpty()) item("empty") {
            Column(verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                Text(stringResource(R.string.computer_empty), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.computer_empty_detail), style = MaterialTheme.typography.bodyMedium,
                    color = tokenWorksSurfaces().textTertiary)
            }
        }
        items(state.runtimes, key = { it.id }) { runtime ->
            val available = runtime.access?.canList() == true
            Surface(onClick = { onRuntime(runtime.id) }, enabled = available && !state.refreshing,
                modifier = Modifier.fillMaxWidth().tokenWorksPress(available).testTag("computer-open-${runtime.id}"),
                shape = tokenWorksSquircle(TokenWorksRadius.md), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(TokenWorksSpacing.lg), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xs)) {
                    Text(runtime.hostAlias, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.computer_runtime, runtime.id), style = MaterialTheme.typography.bodySmall,
                        color = tokenWorksSurfaces().textTertiary)
                    Text(stringResource(R.string.computer_version) + " · " + runtime.version.ifEmpty { stringResource(R.string.value_unknown) },
                        style = MaterialTheme.typography.bodySmall, color = tokenWorksSurfaces().textTertiary)
                    Text(stringResource(if (available) R.string.action_open_sessions else R.string.computer_no_directory),
                        style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.computer_presence_unknown), style = MaterialTheme.typography.bodySmall,
                        color = tokenWorksSurfaces().textTertiary)
                }
            }
        }
    }
}

@Composable
internal fun WorkspaceSettings(state: HomeState, onAppearance: () -> Unit, onAppUpdates: () -> Unit, onExit: () -> Unit, onBack: () -> Unit) {
    var confirmExit by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TokenWorksTopBar(stringResource(R.string.nav_settings), navigationIcon = {
            IconButton(onBack, Modifier.size(TokenWorksSpacing.minimumTouchTarget)) { ShellIcon(R.drawable.ic_back, R.string.action_close) }
        })
    }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(TokenWorksSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md)) {
            item("account") {
                Text(stringResource(R.string.settings_account), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() })
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xs)) {
                        Text(state.deviceName ?: stringResource(R.string.settings_device_registered), style = MaterialTheme.typography.bodyMedium)
                        Text(state.subject, style = MaterialTheme.typography.bodySmall)
                        Text(state.issuer, style = MaterialTheme.typography.bodySmall)
                        Text(state.deviceId, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item("appearance") {
                SettingsItem(R.string.directory_appearance_entry, R.string.directory_appearance_detail,
                    onAppearance, enabled = state.presentationOwner != null)
            }
            item("updates") { SettingsItem(R.string.settings_updates, R.string.settings_updates_detail, onAppUpdates) }
            item("version") { Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall) }
            item("exit") {
                TextButton({ confirmExit = true }, Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.action_exit_account))
                }
            }
        }
    }
    if (confirmExit) AlertDialog(onDismissRequest = { confirmExit = false },
        title = { Text(stringResource(R.string.settings_exit_title)) }, text = { Text(stringResource(R.string.settings_exit_detail)) },
        confirmButton = { TextButton({ confirmExit = false; onExit() }) { Text(stringResource(R.string.action_exit_account)) } },
        dismissButton = { TextButton({ confirmExit = false }) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
private fun SettingsItem(@StringRes title: Int, @StringRes description: Int, onClick: () -> Unit, enabled: Boolean = true) {
    Surface(onClick = onClick, enabled = enabled, shape = tokenWorksSquircle(TokenWorksRadius.lg),
        color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        ListItem(headlineContent = { Text(stringResource(title)) }, supportingContent = { Text(stringResource(description)) },
            trailingContent = { ShellIcon(R.drawable.ic_chevron) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget))
    }
}

@Composable
internal fun ShellIcon(@DrawableRes icon: Int, @StringRes description: Int? = null) {
    Icon(painterResource(icon), description?.let { stringResource(it) }, Modifier.size(TokenWorksSpacing.iconSize))
}
