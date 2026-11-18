package cn.edu.ustc.tokenworks

import androidx.annotation.DrawableRes
import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
    onSessionOpened: () -> Unit = onCloseNavigation,
) {
    var previousSession by remember { mutableStateOf(state.selectedSessionId) }
    LaunchedEffect(state.selectedSessionId) {
        if (state.selectedSessionId != previousSession) {
            previousSession = state.selectedSessionId
            onSessionOpened()
        }
    }
    val unread = state.sessions.count { it.state != "archived" && viewModel.isUnread(it) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = TokenWorksSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                BadgedBox(badge = { WorkspaceUnreadBadge(unread) }) {
                    Text(stringResource(R.string.workspace_title), style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = TokenWorksSpacing.sm))
                }
            }
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
internal fun WorkspaceRail(onNavigation: () -> Unit, onHosts: () -> Unit, onSettings: () -> Unit, unread: Int = 0) {
    Column(Modifier.fillMaxSize().padding(TokenWorksMetrics.edge), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TokenWorksMetrics.gap)) {
        RailKey(R.drawable.ic_menu, R.string.workspace_rail_sessions, onNavigation, unread)
        RailKey(R.drawable.ic_computers, R.string.workspace_switch_host, onHosts)
        Spacer(Modifier.weight(1f))
        RailKey(R.drawable.ic_settings, R.string.nav_settings, onSettings)
    }
}

@Composable
private fun RailKey(@DrawableRes icon: Int, @StringRes description: Int, onClick: () -> Unit, unread: Int = 0) {
    FilledTonalIconButton(onClick, Modifier.size(TokenWorksMetrics.key).tokenWorksPress()) {
        BadgedBox(badge = { WorkspaceUnreadBadge(unread) }) { ShellIcon(icon, description) }
    }
}

@Composable
private fun WorkspaceUnreadBadge(unread: Int) {
    if (unread > 0) {
        val label = pluralStringResource(R.plurals.workspace_unread_sessions, unread, unread)
        Badge(Modifier.clearAndSetSemantics { contentDescription = label; liveRegion = LiveRegionMode.Polite }) {
            Text(if (unread > 99) stringResource(R.string.workspace_unread_overflow) else unread.toString())
        }
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
                    stringResource(R.string.settings_lan_notice),
                    modifier = Modifier.padding(vertical = TokenWorksSpacing.sm),
                    tone = TokenWorksNoticeTone.WARNING,
                )
                TextButton(viewModel::login, Modifier.padding(bottom = TokenWorksSpacing.sm)) { Text(stringResource(R.string.settings_relay_sign_in)) }
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
                    RuntimePickerAction(stringResource(R.string.settings_secure_pairing), viewModel::openPairing)
                    RuntimeDivider()
                    RuntimePickerAction(stringResource(R.string.settings_manage_devices), viewModel::openManagement)
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
internal fun WorkspaceSettings(
    state: HomeState, onAppearance: () -> Unit, onAppUpdates: () -> Unit, onExit: () -> Unit,
    onBack: () -> Unit, onManagement: () -> Unit, onNotifications: () -> Unit, onPairing: () -> Unit, onUsage: () -> Unit,
    onNotificationCenter: (() -> Unit)? = null,
    verifyExit: (((Boolean) -> Unit) -> Unit)? = null,
) {
    val owner = state.presentationOwner?.fingerprint
    val currentOwner by rememberUpdatedState(owner)
    val currentExit by rememberUpdatedState(onExit)
    val verify = verifyExit ?: key(owner) { rememberSignOutVerification() }
    var confirmExit by remember(owner) { mutableStateOf(false) }
    var verifying by remember(owner) { mutableStateOf(false) }
    var verificationFailed by remember(owner) { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val term = query.trim()
    val context = LocalContext.current
    var copyResult by remember(owner) { mutableStateOf<Int?>(null) }
    val device = state.deviceName ?: stringResource(R.string.settings_device_registered)
    val account = listOf(device, state.subject, state.issuer, state.deviceId).filter(String::isNotBlank)
    val version = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)
    val exitLabel = stringResource(R.string.action_exit_account)
    val copyLabel = stringResource(R.string.settings_copy_account)
    val groups = listOf(
        SettingsGroup(R.string.settings_account, emptyList(), listOf(copyLabel, exitLabel) + account),
        SettingsGroup(R.string.settings_group_security, listOf(
            Setting(R.string.settings_manage_devices, R.string.settings_manage_devices_detail, onManagement),
            Setting(R.string.settings_secure_pairing, R.string.settings_secure_pairing_detail, onPairing),
        )),
        SettingsGroup(R.string.settings_group_notifications, buildList {
            onNotificationCenter?.let { add(Setting(R.string.settings_notification_center, R.string.settings_notification_center_detail, it)) }
            add(Setting(R.string.settings_notification_preferences, R.string.settings_notification_preferences_detail, onNotifications))
        }),
        SettingsGroup(R.string.settings_group_appearance, listOf(
            Setting(R.string.directory_appearance_entry, R.string.directory_appearance_detail, onAppearance, owner != null),
        )),
        SettingsGroup(R.string.settings_group_about, buildList {
            add(Setting(R.string.usage_title, R.string.usage_settings_detail, onUsage))
            if (!state.lanOnly) add(Setting(R.string.settings_updates, R.string.settings_updates_detail, onAppUpdates))
        }, listOf(version)),
    )
    val filtered = groups.mapNotNull { group ->
        val title = context.getString(group.title)
        val matchTitle = title.contains(term, ignoreCase = true)
        val rows = group.rows.filter { row -> matchTitle ||
            context.getString(row.title).contains(term, ignoreCase = true) || context.getString(row.detail).contains(term, ignoreCase = true) }
        val showExtra = group.extra.isNotEmpty() && (matchTitle || group.extra.any { it.contains(term, ignoreCase = true) })
        if (rows.isEmpty() && !showExtra) null else Triple(group.title, rows, showExtra)
    }
    Scaffold(topBar = {
        TokenWorksTopBar(stringResource(R.string.nav_settings), navigationIcon = {
            IconButton(onBack, Modifier.size(TokenWorksSpacing.minimumTouchTarget)) { ShellIcon(R.drawable.ic_back, R.string.action_close) }
        })
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).imePadding(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = TokenWorksSpacing.readingWidth).fillMaxSize(), contentPadding = PaddingValues(TokenWorksSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md)) {
                item("search") {
                    OutlinedTextField(query, { query = it.take(120) }, Modifier.fillMaxWidth().testTag("settings-search"),
                        label = { Text(stringResource(R.string.settings_search)) }, leadingIcon = { ShellIcon(R.drawable.ic_search) }, singleLine = true)
                }
                if (filtered.isEmpty()) item("no-matches") {
                    Text(stringResource(R.string.settings_search_empty), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                items(filtered, key = { it.first }) { (title, rows, showExtra) ->
                    SettingsSection(stringResource(title), term.isNotEmpty()) {
                        rows.forEachIndexed { index, setting ->
                            if (index > 0) RuntimeDivider()
                            SettingsRow(stringResource(setting.title), stringResource(setting.detail), setting.open, setting.enabled)
                        }
                        if (showExtra && title == R.string.settings_account) {
                            Column(Modifier.padding(horizontal = TokenWorksSpacing.lg, vertical = TokenWorksSpacing.md)) {
                                SelectionContainer {
                                    Column(verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xs)) {
                                        account.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                    }
                                }
                                TextButton({
                                    copyResult = try {
                                        val clipboard = checkNotNull(context.getSystemService(ClipboardManager::class.java))
                                        val clip = ClipData.newPlainText(copyLabel, account.joinToString("\n"))
                                        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                                        clipboard.setPrimaryClip(clip)
                                        R.string.settings_account_copied
                                    } catch (_: Exception) { R.string.settings_copy_failed }
                                }) { Text(copyLabel) }
                                copyResult?.let { Text(stringResource(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                                TextButton({ verificationFailed = false; confirmExit = true }, enabled = owner != null && !verifying,
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(exitLabel) }
                                if (verificationFailed) TokenWorksStatusNotice(stringResource(R.string.settings_verify_exit_failed), isError = true)
                            }
                        }
                        if (showExtra && title == R.string.settings_group_about) {
                            if (rows.isNotEmpty()) RuntimeDivider()
                            Text(version, Modifier.padding(TokenWorksSpacing.lg), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    if (confirmExit && owner != null) AlertDialog(onDismissRequest = { confirmExit = false },
        title = { Text(stringResource(R.string.settings_exit_title)) }, text = { Text(stringResource(R.string.settings_exit_detail)) },
        confirmButton = { TextButton({
            confirmExit = false
            verifying = true
            verify { matched ->
                verifying = false
                if (currentOwner == owner) {
                    if (matched) currentExit() else verificationFailed = true
                }
            }
        }) { Text(exitLabel) } },
        dismissButton = { TextButton({ confirmExit = false }) { Text(stringResource(R.string.action_cancel)) } })
}

private data class Setting(@param:StringRes val title: Int, @param:StringRes val detail: Int,
    val open: () -> Unit, val enabled: Boolean = true)

private data class SettingsGroup(@param:StringRes val title: Int, val rows: List<Setting>, val extra: List<String> = emptyList())

@Composable
private fun SettingsSection(title: String, searching: Boolean, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val open = searching || expanded
    val status = stringResource(if (open) R.string.settings_group_expanded else R.string.settings_group_collapsed)
    Surface(shape = tokenWorksSquircle(TokenWorksRadius.lg), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget)
                .semantics { heading(); stateDescription = status }
                .then(if (searching) Modifier else Modifier.clickable(role = Role.Button) { expanded = !expanded })
                .padding(horizontal = TokenWorksSpacing.lg, vertical = TokenWorksSpacing.sm),
                verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (!searching) Box(Modifier.rotate(if (open) 90f else 0f)) { ShellIcon(R.drawable.ic_chevron) }
            }
            AnimatedVisibility(open) {
                Column { RuntimeDivider(); content() }
            }
        }
    }
}

@Composable
private fun SettingsRow(title: String, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = TokenWorksSpacing.minimumTouchTarget)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
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
