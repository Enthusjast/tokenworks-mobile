package cn.edu.ustc.tokenworks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private fun HostConnectionPath.label() = when (this) {
    HostConnectionPath.AUTO -> "同身份自动路径（未同意时跳过点对点与 TURN）"
    HostConnectionPath.LAN -> "局域网"
    HostConnectionPath.P2P -> "点对点（不经过 TURN）"
    HostConnectionPath.TURN -> "TURN 加密转发（仅中继候选）"
    HostConnectionPath.RELAY -> "加密中继"
}
private fun HostReachability.label() = when (this) {
    HostReachability.UNKNOWN -> "未检测"; HostReachability.CHECKING -> "正在检测"; HostReachability.REACHABLE -> "本次原生请求可达"
    HostReachability.UNREACHABLE -> "本次检测不可达"; HostReachability.AUTH_REQUIRED -> "需重新授权"
}
private fun managementStateLabel(value: String) = when (value) {
    "active" -> "有效"; "pending" -> "待批准"; "revoked" -> "已撤销"; "expired" -> "已过期"; "inactive" -> "已停用"; else -> "未知状态（$value）"
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
        TokenWorksTopBar(title = "电脑与设备管理", navigationIcon = {
            IconButton(onBack, Modifier.size(TokenWorksSpacing.minimumTouchTarget)) { ShellIcon(R.drawable.ic_back, R.string.action_back_computers) }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(TokenWorksSpacing.lg), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md)) {
            item {
                Text("连接只在相同安装身份与运行配置内回退，不会自动切换电脑。草稿和待发送操作不随路径迁移。", style = MaterialTheme.typography.bodyMedium)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = TokenWorksSpacing.sm))
                state.error?.let { TokenWorksStatusNotice("操作失败：$it", tone = TokenWorksNoticeTone.ERROR) }
                state.notice?.let { TokenWorksStatusNotice(it) }
                if (state.mutationUnknown) TokenWorksStatusNotice("撤销结果未知。请刷新核对服务端状态，不会自动重试撤销。",
                    tone = TokenWorksNoticeTone.WARNING)
                if (state.relayManagementAvailable) TextButton(onClick = onRefresh, enabled = !state.busy) { Text("刷新并核对授权") }
                else TokenWorksStatusNotice("当前为仅局域网模式；设备、安装和授权管理需要有效的账号连接。", tone = TokenWorksNoticeTone.INFO)
                state.transport?.let { Text("当前连接：${it.path} · ${it.phase}${it.error?.let { error -> " · $error" }.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall, color = tokenWorksSurfaces().textTertiary) }
            }
            item { Text("电脑与运行配置", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) }
            if (state.hosts.isEmpty()) item { TokenWorksEmptyState(title = "没有可用的授权电脑。") }
            state.hosts.forEach { group ->
                item(key = "host:${group.hostId}") {
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(group.alias, style = MaterialTheme.typography.titleMedium)
                        Text("电脑身份：${group.hostId}", style = MaterialTheme.typography.bodySmall)
                        group.runtimes.forEach { row ->
                            Text("运行配置：${row.identity.runtimeId}")
                            Text("安装身份：${row.identity.installationId ?: "服务端未提供，禁止合并路径"}", style = MaterialTheme.typography.bodySmall)
                            val available = row.identity.relayAuthorized || !state.relayManagementAvailable
                            if (!available) Text("独立局域网授权未与中继身份合并；请在仅局域网模式使用。")
                            val rtcConsent = state.rtcConsents[row.identity.runtimeId] == true
                            if (HostConnectionPath.P2P in row.paths) {
                                Text("允许为此所有者和运行配置建立点对点 / TURN 连接。声明的 STUN / TURN 服务会看到网络地址及流量元数据，但看不到原生请求内容；不会申请摄像头或麦克风。", style = MaterialTheme.typography.bodySmall)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("允许点对点和 TURN", Modifier.weight(1f).padding(top = 12.dp))
                                    Switch(checked = rtcConsent, onCheckedChange = { onRtcConsent(row.identity.runtimeId, it) }, enabled = !state.busy && available,
                                        modifier = Modifier.semantics { contentDescription = "允许此运行配置的点对点和 TURN" })
                                }
                            }
                            row.paths.forEach { path ->
                                val status = row.status[path] ?: HostPathStatus()
                                val consentAllowed = path !in setOf(HostConnectionPath.P2P, HostConnectionPath.TURN) || rtcConsent
                                Text("${path.label()} · ${if (path == HostConnectionPath.AUTO) "仅在同身份内回退" else status.reachability.label()}")
                                status.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (path != HostConnectionPath.AUTO) OutlinedButton(onClick = { onProbe(row.identity.runtimeId, path) }, enabled = !state.busy && available && consentAllowed,
                                        modifier = Modifier.semantics { contentDescription = "检测 ${group.alias} 的运行配置 ${row.identity.runtimeId}：${path.label()}" }) { Text("检测") }
                                    Button(onClick = { onConnect(row.identity.runtimeId, path) }, enabled = !state.busy && available && consentAllowed,
                                        modifier = Modifier.semantics { contentDescription = "连接 ${group.alias} 的运行配置 ${row.identity.runtimeId}：${path.label()}" }) { Text("连接") }
                                }
                            }
                            if (row.identity.relayAuthorized && state.relayManagementAvailable)
                                TextButton(onClick = { onGrants(row.identity.runtimeId) }, enabled = !state.busy) { Text("查看运行授权") }
                        }
                    } }
                }
            }
            if (state.relayManagementAvailable) {
                item { Text("账号设备", style = MaterialTheme.typography.titleLarge) }
                state.devices.forEach { device -> item(key = "device:${device.id}") {
                    ManagementRecord("${device.name}${if (device.current) "（当前手机）" else ""}", device.id, device.state,
                        !state.busy && !state.mutationUnknown && device.state == "active", { onRevoke(ManagementTarget.Device(device.id)) })
                } }
                if (state.devicesCursor != null) item { TextButton(onClick = onLoadDevices, enabled = !state.busy) { Text("加载更多设备") } }
                item { Text("安装", style = MaterialTheme.typography.titleLarge) }
                state.installations.forEach { installation -> item(key = "installation:${installation.id}") {
                    Column {
                        ManagementRecord(installation.alias, installation.id, installation.state, !state.busy && !state.mutationUnknown && installation.state in setOf("active", "pending"), { onRevoke(ManagementTarget.Installation(installation.id)) })
                        TextButton(onClick = { onProfiles(installation.id) }, enabled = !state.busy) { Text("查看此安装的运行配置") }
                    }
                } }
                if (state.installationsCursor != null) item { TextButton(onClick = onLoadInstallations, enabled = !state.busy) { Text("加载更多安装") } }
                state.profileInstallation?.let { installation ->
                    item { Text("安装运行配置：$installation", style = MaterialTheme.typography.titleMedium) }
                    state.profiles.forEach { profile -> item(key = "profile:${profile.id}") {
                        ManagementRecord("运行配置", profile.id, profile.state, !state.busy && !state.mutationUnknown && profile.state in setOf("active", "pending"), { onRevoke(ManagementTarget.Profile(installation, profile.id)) })
                    } }
                    if (state.profilesCursor != null) item { TextButton(onClick = { onProfiles(installation) }, enabled = !state.busy) { Text("加载更多运行配置") } }
                }
                state.grantRuntime?.let { runtime ->
                    item { Text("运行授权：$runtime", style = MaterialTheme.typography.titleMedium) }
                    state.grants.forEach { grant -> item(key = "grant:$runtime:${grant.userId}") {
                        ManagementRecord("${grant.role} · 版本 ${grant.version}", grant.userId, grant.state, !state.busy && !state.mutationUnknown && grant.state == "active", { onRevoke(ManagementTarget.Grant(runtime, grant.userId)) })
                    } }
                    if (state.grantsCursor != null) item { TextButton(onClick = { onGrants(runtime) }, enabled = !state.busy) { Text("加载更多授权") } }
                }
            }
        }
    }
    state.confirmation?.let { target ->
        val detail = when (target) {
            is ManagementTarget.Device -> "设备 ${target.id}。撤销当前手机将清除本地账号数据并退出登录。"
            is ManagementTarget.Installation -> "安装 ${target.id} 及其运行配置。"
            is ManagementTarget.Profile -> "安装 ${target.installationId} 的运行配置 ${target.runtimeId}。"
            is ManagementTarget.Grant -> "运行配置 ${target.runtimeId} 中用户 ${target.userId} 的授权。"
        }
        AlertDialog(onDismissRequest = onCancelRevoke, title = { Text("确认撤销？") }, text = { Text("$detail\n已撤销的权限不能通过缓存恢复。相关缓存、草稿和待发送操作将被清理；未解决的局域网配对会阻止撤销。") },
            confirmButton = { TextButton(onClick = onConfirmRevoke, enabled = !state.busy) { Text("确认撤销") } },
            dismissButton = { TextButton(onClick = onCancelRevoke, enabled = !state.busy) { Text("取消") } })
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
                modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text("撤销") }
        }
    }
}
