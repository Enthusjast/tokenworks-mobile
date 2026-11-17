package cn.edu.ustc.tokenworks

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
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
                IconButton(viewModel::refresh, enabled = !state.refreshing && !state.lanOnly,
                    modifier = Modifier.size(TokenWorksSpacing.minimumTouchTarget)) { ShellIcon(R.drawable.ic_refresh, R.string.action_refresh) }
            }
            if (state.lanOnly) {
                TokenWorksStatusNotice(
                    "仅局域网模式：使用独立配对权限；中转账号尚未重新验证。",
                    modifier = Modifier.padding(vertical = TokenWorksSpacing.sm),
                    tone = TokenWorksNoticeTone.WARNING,
                )
                TextButton(viewModel::login, Modifier.padding(bottom = TokenWorksSpacing.sm)) { Text("重新登录中转账号") }
            }
            Surface(
                shape = tokenWorksSquircle(TokenWorksRadius.lg),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    RuntimePickerAction(stringResource(if (state.pendingBindingId == null) R.string.action_bind_computer else R.string.action_review_binding),
                        viewModel::openBinding, enabled = !state.lanOnly)
                    RuntimeDivider()
                    RuntimePickerAction("局域网安全配对", viewModel::openPairing)
                    RuntimeDivider()
                    RuntimePickerAction("电脑与设备管理", viewModel::openManagement)
                }
            }
            Text(stringResource(R.string.computer_scope), style = MaterialTheme.typography.bodySmall,
                color = tokenWorksSurfaces().textTertiary,
                modifier = Modifier.padding(top = TokenWorksSpacing.sm))
        }
        if (state.refreshing) item("loading") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md)) {
                CircularProgressIndicator(Modifier.size(TokenWorksSpacing.xxl))
                Text(stringResource(R.string.directory_loading), style = MaterialTheme.typography.bodyMedium)
            }
        }
        state.error?.let { code -> item("error") { TokenWorksStatusNotice(errorText(code), isError = true) } }
        if (!state.refreshing && state.runtimes.isEmpty()) item("empty") {
            TokenWorksEmptyState(
                title = stringResource(R.string.computer_empty),
                detail = stringResource(R.string.computer_empty_detail),
                modifier = Modifier.tokenWorksEntrance("computers-empty"),
            )
        }
        items(state.runtimes, key = { it.id }) { runtime ->
            val available = state.lanOnly || runtime.access?.canList() == true
            Surface(onClick = { onRuntime(runtime.id) }, enabled = available && !state.refreshing,
                modifier = Modifier.fillMaxWidth().tokenWorksPress(available).testTag("computer-open-${runtime.id}"),
                shape = tokenWorksSquircle(TokenWorksRadius.md), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = TokenWorksSpacing.lg, vertical = TokenWorksSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xxs)) {
                        Text(runtime.hostAlias, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.computer_runtime, runtime.id) + " · " +
                            stringResource(R.string.computer_version) + " " + runtime.version.ifEmpty { stringResource(R.string.value_unknown) },
                            style = MaterialTheme.typography.bodySmall, color = tokenWorksSurfaces().textTertiary,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(stringResource(if (available) R.string.action_open_sessions else R.string.computer_no_directory),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    ShellIcon(R.drawable.ic_chevron, R.string.action_open_sessions)
                }
            }
        }
    }
}

@Composable
internal fun WorkspaceSettings(state: HomeState, onAppearance: () -> Unit, onAppUpdates: () -> Unit, onExit: () -> Unit,
    onBack: () -> Unit, onManagement: () -> Unit, onNotifications: () -> Unit, onPairing: () -> Unit) {
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
                Surface(
                    shape = tokenWorksSquircle(TokenWorksRadius.lg),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SelectionContainer {
                        Column(Modifier.fillMaxWidth().padding(horizontal = TokenWorksSpacing.lg, vertical = TokenWorksSpacing.md),
                            verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xs)) {
                            Text(state.deviceName ?: stringResource(R.string.settings_device_registered), style = MaterialTheme.typography.bodyMedium)
                            Text(state.subject, style = MaterialTheme.typography.bodySmall,
                                color = tokenWorksSurfaces().textTertiary)
                            Text(state.issuer, style = MaterialTheme.typography.bodySmall,
                                color = tokenWorksSurfaces().textTertiary)
                            Text(state.deviceId, style = MaterialTheme.typography.bodySmall,
                                color = tokenWorksSurfaces().textTertiary)
                        }
                    }
                }
            }
            item("appearance") {
                Surface(
                    shape = tokenWorksSquircle(TokenWorksRadius.lg),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        SettingsRow(stringResource(R.string.directory_appearance_entry),
                            stringResource(R.string.directory_appearance_detail),
                            onAppearance, enabled = state.presentationOwner != null)
                        RuntimeDivider()
                        SettingsRow("电脑与设备管理", "绑定设备、安装记录与授权一览", onManagement)
                        RuntimeDivider()
                        SettingsRow("通知与后台连接", "送达提醒与后台保持连接", onNotifications)
                        RuntimeDivider()
                        SettingsRow("局域网安全配对", "与同一网络下的电脑配对", onPairing)
                        if (!state.lanOnly) {
                            RuntimeDivider()
                            SettingsRow(stringResource(R.string.settings_updates),
                                stringResource(R.string.settings_updates_detail), onAppUpdates)
                        }
                    }
                }
            }
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
private fun SettingsRow(title: String, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = TokenWorksSpacing.minimumTouchTarget)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = TokenWorksSpacing.lg, vertical = TokenWorksSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xxs)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = tokenWorksSurfaces().textTertiary)
        }
        ShellIcon(R.drawable.ic_chevron)
    }
}

@Composable
internal fun ShellIcon(@DrawableRes icon: Int, @StringRes description: Int? = null) {
    Icon(painterResource(icon), description?.let { stringResource(it) }, Modifier.size(TokenWorksSpacing.iconSize))
}

/** One row inside a grouped settings/actions card; rows, not buttons, carry secondary actions. */
@Composable
internal fun RuntimePickerAction(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = TokenWorksSpacing.minimumTouchTarget)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = TokenWorksSpacing.lg, vertical = TokenWorksSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun RuntimeDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(horizontal = TokenWorksSpacing.lg))
}
