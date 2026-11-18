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
import androidx.compose.ui.res.stringResource
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

/** All descriptor sources feed the same explicit host identity review. */
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
        TokenWorksTopBar(title = stringResource(R.string.pairing_title), subtitle = stringResource(R.string.pairing_subtitle),
            contentMaxWidth = TokenWorksSpacing.readingWidth,
            navigationIcon = { TextButton(onClick = onBack, enabled = !busy && state.pendingDevice == null && state.rotatingRuntimeId == null,
                modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text(stringResource(R.string.pairing_back)) } })
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).imePadding().verticalScroll(rememberScrollState())
            .widthIn(max = TokenWorksSpacing.readingWidth).padding(TokenWorksSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md)) {
            Text(stringResource(R.string.pairing_import_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.pairing_import_help))
            Text(stringResource(R.string.pairing_host_rotation_warning))
            if (state.pendingDevice == null && state.rotatingRuntimeId == null) {
                PairingQrButton(enabled = !busy) { payload ->
                    onImport(payload)
                    text = ""
                    importError = false
                    true
                }
                OutlinedTextField(value = text, onValueChange = { if (it.length <= 16_384) text = it },
                    label = { Text(stringResource(R.string.pairing_json_label)) }, minLines = 4, maxLines = 8, enabled = !busy,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                    Button(onClick = { onImport(text); text = ""; importError = false }, enabled = !busy && text.isNotBlank(),
                        modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text(stringResource(R.string.pairing_load_review)) }
                    OutlinedButton(onClick = { picker.launch(arrayOf("application/json", "text/plain")) }, enabled = !busy,
                        modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text(stringResource(R.string.pairing_open_json)) }
                }
            }
            state.preview?.let { preview ->
                Surface(Modifier.fillMaxWidth(), shape = tokenWorksSquircle(TokenWorksRadius.lg),
                    color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(TokenWorksSpacing.md), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                        Text(stringResource(R.string.pairing_review_identity), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                        SelectionContainer { Text(stringResource(R.string.pairing_review_fields, preview.origin, preview.runtimeId, preview.installationId, preview.tlsPin, preview.hostStaticPublicKey, preview.hostKeyEpoch), fontFamily = FontFamily.Monospace) }
                        Text(stringResource(R.string.pairing_expires, java.time.Instant.ofEpochMilli(preview.expiresAt)))
                        Row(Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget)
                            .toggleable(value = checked, enabled = !busy, role = Role.Checkbox, onValueChange = { checked = it })) {
                            Checkbox(checked, onCheckedChange = null, enabled = !busy)
                            Text(stringResource(R.string.pairing_verify_checked), Modifier.weight(1f))
                        }
                    }
                }
                OutlinedTextField(deviceName, { if (it.length <= 80) deviceName = it }, label = { Text(stringResource(R.string.pairing_device_name)) },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Button(onClick = { onPair(deviceName) }, enabled = checked && deviceName.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text(stringResource(R.string.pairing_verify_submit)) }
            }
            if (state.phase == PairingPhase.SAVE_REQUIRED || state.phase == PairingPhase.UNKNOWN) {
                TokenWorksStatusNotice(
                    if (state.rotatingRuntimeId != null)
                        stringResource(R.string.pairing_rotation_pending)
                    else if (state.phase == PairingPhase.SAVE_REQUIRED)
                        stringResource(R.string.pairing_save_pending)
                    else
                        stringResource(R.string.pairing_result_pending),
                    tone = TokenWorksNoticeTone.WARNING,
                )
                state.pendingDevice?.let { device ->
                    SelectionContainer { Text(stringResource(R.string.pairing_pending_fields, device.origin, device.runtimeId, device.installationId)) }
                    Button(onClick = onRetrySave, enabled = !busy) {
                        Text(if (state.phase == PairingPhase.UNKNOWN) stringResource(R.string.pairing_verify_and_save) else stringResource(R.string.pairing_retry_save))
                    }
                    OutlinedButton(onClick = { confirmRevoke = device }, enabled = !busy) { Text(stringResource(R.string.pairing_revoke_pending)) }
                    TextButton(onClick = { confirmForget = device }, enabled = !busy) { Text(stringResource(R.string.pairing_forget_recovery)) }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
            if (importError) TokenWorksStatusNotice(stringResource(R.string.pairing_import_failed),
                tone = TokenWorksNoticeTone.ERROR)
            state.error?.let { TokenWorksStatusNotice(pairingError(it),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                tone = TokenWorksNoticeTone.ERROR) }
            if (state.devices.isNotEmpty()) Text(stringResource(R.string.pairing_saved_hosts), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            state.devices.forEach { device ->
                Surface(Modifier.fillMaxWidth(), shape = tokenWorksSquircle(TokenWorksRadius.lg),
                    color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(TokenWorksSpacing.md), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                        Text(device.origin, style = MaterialTheme.typography.titleSmall)
                        SelectionContainer { Text(stringResource(R.string.pairing_saved_fields, device.runtimeId, device.installationId, device.hostKeyEpoch, device.deviceKeyEpoch), fontFamily = FontFamily.Monospace) }
                        Text(stringResource(R.string.pairing_transport_scope))
                        if (state.rotatingRuntimeId == device.runtimeId) {
                            Button(onClick = { onReconcileRotation(device.runtimeId) }, enabled = !busy,
                                modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text(stringResource(R.string.pairing_reconcile_rotation)) }
                        } else {
                            OutlinedButton(onClick = { confirmRotation = device }, enabled = !busy && state.rotatingRuntimeId == null && state.pendingDevice == null,
                                modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text(stringResource(R.string.pairing_rotate_key)) }
                        }
                        OutlinedButton(onClick = { confirmRevoke = device }, enabled = !busy) { Text(stringResource(R.string.pairing_host_revoke)) }
                        TextButton(onClick = { confirmForget = device }, enabled = !busy) { Text(stringResource(R.string.pairing_local_remove)) }
                    }
                }
            }
        }
    }
    confirmRotation?.let { device ->
        AlertDialog(onDismissRequest = { confirmRotation = null }, title = { Text(stringResource(R.string.pairing_rotate_title)) },
            text = { Text(stringResource(R.string.pairing_rotate_detail)) },
            confirmButton = { TextButton(onClick = { confirmRotation = null; onRotateDeviceKey(device.runtimeId) }) { Text(stringResource(R.string.pairing_rotate_save)) } },
            dismissButton = { TextButton(onClick = { confirmRotation = null }) { Text(stringResource(R.string.pairing_cancel)) } })
    }
    confirmForget?.let { device ->
        AlertDialog(onDismissRequest = { confirmForget = null }, title = { Text(stringResource(R.string.pairing_forget_title)) },
            text = { Text(stringResource(R.string.pairing_forget_detail)) },
            confirmButton = { TextButton(onClick = { confirmForget = null; onForget(device.runtimeId) }) { Text(stringResource(R.string.pairing_forget_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmForget = null }) { Text(stringResource(R.string.pairing_cancel)) } })
    }
    confirmRevoke?.let { device ->
        AlertDialog(onDismissRequest = { confirmRevoke = null }, title = { Text(stringResource(R.string.pairing_revoke_title)) },
            text = { Text(stringResource(R.string.pairing_revoke_detail)) },
            confirmButton = { TextButton(onClick = { confirmRevoke = null; if (device == state.pendingDevice) onRevokePending() else onRevoke(device.runtimeId) }) { Text(stringResource(R.string.pairing_revoke_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmRevoke = null }) { Text(stringResource(R.string.pairing_cancel)) } })
    }
}

@Composable
private fun pairingError(code: String): String = when (code) {
    "lan.tls_failed" -> stringResource(R.string.pairing_error_tls)
    "lan.identity_mismatch" -> stringResource(R.string.pairing_error_identity)
    "e2e.repair_required" -> stringResource(R.string.pairing_error_repair)
    "e2e.unsupported" -> stringResource(R.string.pairing_error_unsupported)
    "e2e.rotation_pending" -> stringResource(R.string.pairing_error_rotation)
    "e2e.binding_mismatch", "e2e.owner_mismatch", "e2e.pin_mismatch", "e2e.failed" -> stringResource(R.string.pairing_error_binding)
    "e2e.relay_unbound" -> stringResource(R.string.pairing_error_relay)
    "auth.proof_invalid", "access.denied" -> stringResource(R.string.pairing_error_denied)
    "lan.auth_required", "lan.revoked" -> stringResource(R.string.pairing_error_revoked)
    "lan.code_expired" -> stringResource(R.string.pairing_error_expired)
    "lan.already_paired" -> stringResource(R.string.pairing_error_existing)
    "lan.restore_required" -> stringResource(R.string.pairing_error_restore)
    "lan.pending_authorization" -> stringResource(R.string.pairing_error_pending)
    "lan.storage_full" -> stringResource(R.string.pairing_error_full)
    "network.unavailable" -> stringResource(R.string.pairing_error_network)
    else -> if (code.startsWith("lan.vault_")) stringResource(R.string.pairing_error_vault)
        else stringResource(R.string.pairing_error_default)
}
