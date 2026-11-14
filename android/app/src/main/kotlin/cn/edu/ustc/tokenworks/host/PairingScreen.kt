package cn.edu.ustc.tokenworks

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Manual JSON and actual document import. No camera permission or simulated QR scanning. */
@Composable
fun PairingScreen(
    state: PairingState, onImport: (String) -> Unit, onPair: (String) -> Unit, onRetrySave: () -> Unit,
    onRevoke: (String) -> Unit, onRevokePending: () -> Unit, onForget: (String) -> Unit, onBack: () -> Unit,
    onRotateDeviceKey: (String) -> Unit, onReconcileRotation: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The descriptor includes a one-time secret: do not persist it with rememberSaveable.
    var text by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf(android.os.Build.MODEL.take(80)) }
    var checked by remember(state.preview) { mutableStateOf(false) }
    var importError by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf<PairedLanDevice?>(null) }
    var confirmRevoke by remember { mutableStateOf<PairedLanDevice?>(null) }
    var confirmRotation by remember { mutableStateOf<PairedLanDevice?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            importError = false
            try {
                val imported = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > 16_384) throw IllegalArgumentException()
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray().toString(Charsets.UTF_8)
                    } ?: throw IllegalArgumentException()
                }
                onImport(imported)
                text = ""
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { importError = true }
            finally { importing = false }
        }
    }
    val busy = state.busy || importing
    Scaffold(topBar = {
        TokenWorksTopBar(title = "端到端主机配对", subtitle = "核对 TLS 与 Noise 主机身份",
            contentMaxWidth = TokenWorksSpacing.readingWidth,
            navigationIcon = { TextButton(onClick = onBack, enabled = !busy && state.pendingDevice == null && state.rotatingRuntimeId == null,
                modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text("返回") } })
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).imePadding().verticalScroll(rememberScrollState())
            .widthIn(max = TokenWorksSpacing.readingWidth).padding(TokenWorksSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md)) {
            Text("从主机导出的描述符配对", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text("仅导入由主机操作员当面提供的完整 JSON 或二维码内容。应用先验证正常 CA、主机名和 TLS pin，再将配对授权绑定到 Noise 公钥、完整账户归属与本设备签名密钥。")
            Text("主机轮换 Noise 密钥会撤销旧配对，必须重新核对操作员提供的新描述符。不会从中继公告或旧连接自动迁移主机公钥。")
            if (state.pendingDevice == null && state.rotatingRuntimeId == null) {
                OutlinedTextField(value = text, onValueChange = { if (it.length <= 16_384) text = it },
                    label = { Text("主机配对 JSON") }, minLines = 4, maxLines = 8, enabled = !busy,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                    Button(onClick = { onImport(text); text = ""; importError = false }, enabled = !busy && text.isNotBlank(),
                        modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text("读取并核对") }
                    OutlinedButton(onClick = { picker.launch(arrayOf("application/json", "text/plain")) }, enabled = !busy,
                        modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text("导入 JSON 文件") }
                }
            }
            state.preview?.let { preview ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(TokenWorksSpacing.md), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                        Text("核对主机身份", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                        SelectionContainer { Text("HTTPS：${preview.origin}\n运行时：${preview.runtimeId}\n安装标识：${preview.installationId}\nTLS pin：${preview.tlsPin}\nNoise 公钥：${preview.hostStaticPublicKey}\n主机密钥版本：${preview.hostKeyEpoch}", fontFamily = FontFamily.Monospace) }
                        Text("有效期至：${java.time.Instant.ofEpochMilli(preview.expiresAt)}")
                        Row(Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget)
                            .toggleable(value = checked, enabled = !busy, role = Role.Checkbox, onValueChange = { checked = it })) {
                            Checkbox(checked, onCheckedChange = null, enabled = !busy)
                            Text("我已与主机操作员提供的地址、运行时、安装标识、TLS pin、Noise 公钥与密钥版本逐项核对。", Modifier.weight(1f))
                        }
                    }
                }
                OutlinedTextField(deviceName, { if (it.length <= 80) deviceName = it }, label = { Text("本设备名称") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Button(onClick = { onPair(deviceName) }, enabled = checked && deviceName.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text("验证并安全配对") }
            }
            if (state.phase == PairingPhase.SAVE_REQUIRED || state.phase == PairingPhase.UNKNOWN) {
                Text(if (state.rotatingRuntimeId != null)
                    "设备密钥轮换结果待核对：新密钥已加密保存，主机可能已经停用旧密钥。只会用已保存的新密钥查询，不会重发轮换或退回旧密钥。"
                    else if (state.phase == PairingPhase.SAVE_REQUIRED)
                    "主机已接受配对，但凭据尚未完成安全保存。恢复信息已加密保留，关闭页面或重启不会丢失。"
                    else "配对结果未知：主机可能已登记此设备。恢复凭据已加密保存；核对只会发送签名身份查询，绝不会重发配对写入。",
                    color = MaterialTheme.colorScheme.error)
                state.pendingDevice?.let { device ->
                    SelectionContainer { Text("待核对主机：${device.origin}\n运行时：${device.runtimeId}\n安装标识：${device.installationId}") }
                    Button(onClick = onRetrySave, enabled = !busy) {
                        Text(if (state.phase == PairingPhase.UNKNOWN) "核对主机并安全保存" else "重试安全保存")
                    }
                    OutlinedButton(onClick = { confirmRevoke = device }, enabled = !busy) { Text("在主机上撤销待定配对") }
                    TextButton(onClick = { confirmForget = device }, enabled = !busy) { Text("仅忘记本地恢复信息") }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
            if (importError) Text("无法读取文件。请选择不超过 16 KB 的 UTF-8 JSON 文件。", color = MaterialTheme.colorScheme.error)
            state.error?.let { Text(pairingError(it), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            if (state.devices.isNotEmpty()) Text("已安全保存的主机", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            state.devices.forEach { device ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(TokenWorksSpacing.md), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                        Text(device.origin, style = MaterialTheme.typography.titleSmall)
                        SelectionContainer { Text("运行时：${device.runtimeId}\n安装标识：${device.installationId}\n主机密钥版本：${device.hostKeyEpoch}\n设备密钥版本：${device.deviceKeyEpoch}", fontFamily = FontFamily.Monospace) }
                        Text("局域网与中继只在同一账户、配对、主机公钥和密钥版本全部一致时切换。仅暂时不可达允许加密只读回退；写操作不会自动重放。独立局域网授权不会产生中继访问权。")
                        if (state.rotatingRuntimeId == device.runtimeId) {
                            Button(onClick = { onReconcileRotation(device.runtimeId) }, enabled = !busy,
                                modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text("核对新设备密钥并保存") }
                        } else {
                            OutlinedButton(onClick = { confirmRotation = device }, enabled = !busy && state.rotatingRuntimeId == null && state.pendingDevice == null,
                                modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text("轮换此设备密钥") }
                        }
                        OutlinedButton(onClick = { confirmRevoke = device }, enabled = !busy) { Text("在主机上撤销") }
                        TextButton(onClick = { confirmForget = device }, enabled = !busy) { Text("仅从此设备移除") }
                    }
                }
            }
        }
    }
    confirmRotation?.let { device ->
        AlertDialog(onDismissRequest = { confirmRotation = null }, title = { Text("轮换此主机的设备密钥？") },
            text = { Text("应用会先加密保存新设备密钥和恢复记录，再通过旧密钥认证的控制连接提交一次轮换。旧原生连接将终止；丢失回复时只核对新密钥，不重发写入或回退。主机公钥不会改变。") },
            confirmButton = { TextButton(onClick = { confirmRotation = null; onRotateDeviceKey(device.runtimeId) }) { Text("保存并轮换") } },
            dismissButton = { TextButton(onClick = { confirmRotation = null }) { Text("取消") } })
    }
    confirmForget?.let { device ->
        AlertDialog(onDismissRequest = { confirmForget = null }, title = { Text("仅移除本地凭据？") },
            text = { Text("这不会撤销主机上的授权；即使配对结果未知，主机也可能已经登记此设备。移除后将无法使用此凭据核对或撤销，请在主机设置中检查并移除可能留下的授权。") },
            confirmButton = { TextButton(onClick = { confirmForget = null; onForget(device.runtimeId) }) { Text("仅本地移除") } },
            dismissButton = { TextButton(onClick = { confirmForget = null }) { Text("取消") } })
    }
    confirmRevoke?.let { device ->
        AlertDialog(onDismissRequest = { confirmRevoke = null }, title = { Text("撤销此主机配对？") },
            text = { Text("主机会停止此配对设备的局域网、中继及其他加密原生连接。确认主机已持久撤销后才删除本地凭据；这不等同于撤销中继账户或租约权限。") },
            confirmButton = { TextButton(onClick = { confirmRevoke = null; if (device == state.pendingDevice) onRevokePending() else onRevoke(device.runtimeId) }) { Text("撤销配对") } },
            dismissButton = { TextButton(onClick = { confirmRevoke = null }) { Text("取消") } })
    }
}

private fun pairingError(code: String): String = when (code) {
    "lan.tls_failed" -> "TLS 验证失败。请核对主机名、管理员 CA 和公钥指纹；未向该连接发送配对码或凭据。"
    "lan.identity_mismatch" -> "主机身份不匹配。已停止连接，不会切换到其他身份。"
    "e2e.repair_required" -> "主机密钥或旧配对版本不再匹配。请让操作员撤销旧配对，并重新核对其提供的新主机描述符；不会自动接受新公钥。"
    "e2e.unsupported" -> "当前主机、加密协议版本或本机原生加密库不受支持。请升级后重新配对；不会回退到明文接口。"
    "e2e.rotation_pending" -> "设备密钥轮换尚待核对，已暂停原生访问。请核对已保存的新密钥，或由主机操作员处理撤销。"
    "e2e.binding_mismatch", "e2e.owner_mismatch", "e2e.pin_mismatch", "e2e.failed" -> "加密身份或账户绑定验证失败。已停止连接，不会切换身份或改用明文。"
    "e2e.relay_unbound" -> "此配对只具有独立局域网授权，没有绑定中继设备身份。请经操作员明确授权后重新配对。"
    "auth.proof_invalid", "access.denied" -> "主机或中继拒绝此凭证。恢复记录仍保留；不会以另一条路径绕过撤权。"
    "lan.auth_required", "lan.revoked" -> "主机拒绝了此设备的授权。不会自动使用中继绕过，请核对或重新配对。"
    "lan.code_expired" -> "配对码已过期，请从主机重新导入描述符。"
    "lan.already_paired" -> "此运行时已有保存的配对。请先撤销或明确移除旧配对。"
    "lan.restore_required" -> "请先完成安全凭据恢复，再开始配对。"
    "lan.pending_authorization" -> "仍有待核对的主机授权。请核对保存、在主机撤销，或明确选择仅忘记本地恢复信息。"
    "lan.storage_full" -> "已保存的主机数量已达上限，请先移除不再使用的配对。"
    "network.unavailable" -> "无法连接主机。请检查同一局域网、地址和主机是否在线；写操作不会自动重试。"
    else -> if (code.startsWith("lan.vault_")) "安全存储不可用。请确认已设置安全锁屏且设备处于解锁状态，再重试。"
        else "配对未完成。请核对 JSON、设备名称和主机状态后重试。"
}
