package cn.edu.ustc.tokenworks

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HostBindingScreen(state: HostBindingState, subject: String, onPreview: (String, String) -> Unit, onQuery: (String) -> Unit,
    onConfirm: () -> Unit, onRefresh: () -> Unit, onEnter: () -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = {
        cn.edu.ustc.tokenworks.TokenWorksTopBar(
            title = stringResource(R.string.flow_binding_title),
            subtitle = stringResource(R.string.flow_binding_account, subject),
            contentMaxWidth = TokenWorksSpacing.readingWidth,
            navigationIcon = { IconButton(onBack, Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                Icon(painterResource(R.drawable.ic_back), stringResource(R.string.flow_binding_back))
            } },
        )
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = TokenWorksSpacing.readingWidth).fillMaxWidth().verticalScroll(rememberScrollState()).padding(cn.edu.ustc.tokenworks.TokenWorksSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.lg)) {
                BindingProgress(state.phase)
                if (state.phase == HostBindingPhase.ENTER) key(state.formRevision) {
                    Text(stringResource(R.string.conversation_binding_request), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    BindingEntry(state.id, state.error, onPreview, onQuery)
                } else {
                    BindingStatus(state)
                    if (state.phase == HostBindingPhase.REVIEW) state.details?.let { BindingFacts(it) }
                    when (state.phase) {
                        HostBindingPhase.REVIEW -> {
                            val details = state.details!!
                            HorizontalDivider()
                            Text(stringResource(R.string.conversation_binding_both_sides), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                            BindingFact(stringResource(R.string.flow_binding_phone), stringResource(if (details.confirmedByMe) R.string.flow_binding_confirmed else R.string.flow_binding_unconfirmed))
                            BindingFact(stringResource(R.string.flow_binding_host), stringResource(if (details.hostConfirmed) R.string.flow_binding_confirmed else R.string.flow_binding_unconfirmed))
                            Text(stringResource(R.string.conversation_binding_authorization), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                            var checked by remember(details.reviewHash) { mutableStateOf(false) }
                            Text(stringResource(R.string.conversation_binding_owner_notice))
                            Row(Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget).toggleable(checked, role = Role.Checkbox, onValueChange = { checked = it }),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md)) {
                                Checkbox(checked, onCheckedChange = null)
                                Text(stringResource(R.string.conversation_binding_consent), Modifier.weight(1f))
                            }
                            Button(onConfirm, enabled = checked && details.canConfirm(), modifier = Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text(stringResource(R.string.binding_confirm_computer)) }
                        }
                        HostBindingPhase.UNKNOWN, HostBindingPhase.WAITING_HOST, HostBindingPhase.WAITING_ACCESS, HostBindingPhase.ERROR -> {
                            if (state.id.isNotEmpty()) OutlinedButton(onRefresh, Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text(stringResource(R.string.binding_check_status)) }
                            TextButton(onEnter, Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text(stringResource(R.string.binding_reenter)) }
                        }
                        HostBindingPhase.UNAVAILABLE -> OutlinedButton(onEnter, Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text(stringResource(R.string.binding_another)) }
                        HostBindingPhase.READY -> Button(onBack, Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text(stringResource(R.string.binding_show_computers)) }
                        else -> Unit
                    }
                    if (state.phase != HostBindingPhase.REVIEW) state.details?.let { BindingFacts(it) }
                }
            }
        }
    }
}

@Composable
private fun BindingProgress(phase: HostBindingPhase) {
    val stages = listOf(R.string.flow_binding_step_input, R.string.flow_binding_step_review,
        R.string.flow_binding_step_wait, R.string.flow_binding_step_result)
    val step = when (phase) {
        HostBindingPhase.ENTER -> 0
        HostBindingPhase.LOADING, HostBindingPhase.REVIEW -> 1
        HostBindingPhase.CONFIRMING, HostBindingPhase.WAITING_HOST, HostBindingPhase.WAITING_ACCESS -> 2
        else -> 3
    }
    val current = stringResource(stages[step])
    Text(current, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    val progress = stringResource(R.string.binding_step_progress, step + 1, stages.size)
    Row(Modifier.fillMaxWidth().clearAndSetSemantics { stateDescription = progress },
        horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xs)) {
        stages.forEachIndexed { index, _ ->
            Surface(Modifier.weight(1f).height(4.dp), shape = tokenWorksSquircle(2.dp),
                color = if (index <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant) {}
        }
    }
}

@Composable
private fun BindingEntry(initialId: String, error: String?, onPreview: (String, String) -> Unit, onQuery: (String) -> Unit) {
    // This capability must never be registered with saved-state or process recovery.
    var id by remember { mutableStateOf(initialId) }
    var code by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var idTouched by remember { mutableStateOf(false) }
    var codeTouched by remember { mutableStateOf(false) }
    val idValid = runCatching { Contract.uuid(id.trim().lowercase()) }.isSuccess
    val codeValid = runCatching { Contract.nonce(code.trim()) }.isSuccess
    Text(stringResource(R.string.flow_binding_input_help))
    PairingQrButton { raw ->
        val payload = HostBindingQr.parse(raw)
        if (payload == null) false else {
            id = payload.id
            code = payload.code
            visible = false
            idTouched = false
            codeTouched = false
            true
        }
    }
    OutlinedTextField(id, { if (it.length <= 64) id = it }, singleLine = true, label = { Text(stringResource(R.string.binding_request_number)) },
        supportingText = { Text(if (idTouched && !idValid) stringResource(R.string.binding_request_invalid) else stringResource(R.string.binding_request_hint)) }, isError = idTouched && !idValid,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused && id.isNotEmpty()) idTouched = true })
    OutlinedTextField(code, { if (it.length <= 64) code = it }, singleLine = true, label = { Text(stringResource(R.string.binding_code_label)) },
        supportingText = { Text(if (codeTouched && !codeValid) stringResource(R.string.binding_code_invalid) else stringResource(R.string.binding_code_hint)) }, isError = codeTouched && !codeValid,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
        trailingIcon = { TextButton({ visible = !visible }, Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text(if (visible) stringResource(R.string.binding_hide_code) else stringResource(R.string.binding_show_code)) } },
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused && code.isNotEmpty()) codeTouched = true })
    error?.let { cn.edu.ustc.tokenworks.TokenWorksStatusNotice(bindingError(it), isError = true) }
    Button({ onPreview(id, code) }, enabled = idValid && codeValid, modifier = Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text(stringResource(R.string.binding_review_computer)) }
    Text(stringResource(R.string.binding_check_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton({ onQuery(id) }, enabled = idValid, modifier = Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text(stringResource(R.string.binding_query_confirmed)) }
}

@Composable
private fun BindingFacts(details: HostBindingDetails) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(details.reviewHash) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.lg)) {
            HorizontalDivider()
            Text(stringResource(R.string.conversation_binding_verify), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            BindingFact(stringResource(R.string.binding_organization), details.organizationName)
            BindingFact(stringResource(R.string.binding_computer_name), details.hostAlias)
            BindingFact(stringResource(R.string.binding_runtime), details.profile)
            if (details.dshVersion.isNotEmpty()) BindingFact(stringResource(R.string.binding_dsh_version), details.dshVersion)
            Text(stringResource(R.string.binding_key_fingerprint), style = MaterialTheme.typography.labelLarge)
            SelectionContainer {
                Text(details.keyThumbprint.chunked(8).joinToString(" "), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr))
            }
            OutlinedButton({
                clipboard.setText(AnnotatedString(details.keyThumbprint))
                copied = true
            }, Modifier.fillMaxWidth().heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                Text(stringResource(R.string.flow_binding_copy_fingerprint))
            }
            if (copied) Text(stringResource(R.string.flow_binding_fingerprint_copied), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Text(stringResource(R.string.binding_verify_full), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BindingFact(stringResource(R.string.binding_workspace_label), if (details.workspaces.isEmpty()) stringResource(R.string.binding_no_workspaces) else details.workspaces.joinToString("\n") { it.reference })
            BindingFact(stringResource(R.string.binding_public_number), details.id)
            val expiry = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(details.expiresAt))
            Text(stringResource(R.string.binding_expires_at, expiry), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
        }
}
@Composable private fun BindingFact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge) }
    }
}
@Composable private fun BindingStatus(state: HostBindingState) {
    val busy = state.phase in setOf(HostBindingPhase.LOADING, HostBindingPhase.CONFIRMING, HostBindingPhase.CHECKING)
    val title = when (state.phase) {
        HostBindingPhase.LOADING -> stringResource(R.string.binding_checking_computer)
        HostBindingPhase.REVIEW -> stringResource(R.string.binding_review_request)
        HostBindingPhase.CONFIRMING -> stringResource(R.string.binding_confirming)
        HostBindingPhase.CHECKING -> stringResource(R.string.binding_checking_result)
        HostBindingPhase.UNKNOWN -> stringResource(R.string.binding_result_unknown)
        HostBindingPhase.WAITING_HOST -> stringResource(R.string.binding_wait_computer)
        HostBindingPhase.WAITING_ACCESS -> stringResource(R.string.binding_wait_access)
        HostBindingPhase.READY -> stringResource(R.string.binding_ready)
        HostBindingPhase.UNAVAILABLE -> stringResource(R.string.binding_unavailable)
        HostBindingPhase.ERROR -> stringResource(R.string.binding_check_error)
        HostBindingPhase.PAUSED, HostBindingPhase.CLOSED -> stringResource(R.string.binding_check_paused)
        HostBindingPhase.ENTER -> stringResource(R.string.binding_enter_details)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md)) {
                if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = when (state.phase) {
                        HostBindingPhase.READY -> tokenWorksSurfaces().success
                        HostBindingPhase.ERROR, HostBindingPhase.UNAVAILABLE -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    })
            }
            when (state.phase) {
                HostBindingPhase.UNKNOWN -> Text(stringResource(R.string.binding_result_unknown_detail))
                HostBindingPhase.WAITING_HOST -> Text(stringResource(R.string.binding_wait_computer_detail))
                HostBindingPhase.WAITING_ACCESS -> Text(stringResource(R.string.binding_wait_access_detail))
                HostBindingPhase.READY -> Text(stringResource(R.string.binding_ready_detail))
                else -> Unit
            }
            if (state.id.isNotEmpty()) BindingFact(stringResource(R.string.flow_binding_public_id), state.id)
            if (state.phase != HostBindingPhase.REVIEW) {
                if (state.details != null) Text(stringResource(R.string.flow_binding_snapshot), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.conversation_binding_both_sides), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                BindingFact(stringResource(R.string.flow_binding_phone), stringResource(when (state.details?.confirmedByMe) {
                    true -> R.string.flow_binding_confirmed
                    false -> R.string.flow_binding_unconfirmed
                    null -> R.string.flow_binding_unverified
                }))
                BindingFact(stringResource(R.string.flow_binding_host), stringResource(when (state.details?.hostConfirmed) {
                    true -> R.string.flow_binding_confirmed
                    false -> R.string.flow_binding_unconfirmed
                    null -> R.string.flow_binding_unverified
                }))
                HorizontalDivider()
                Text(stringResource(R.string.conversation_binding_authorization), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                BindingFact(stringResource(R.string.flow_binding_access), stringResource(when (state.phase) {
                    HostBindingPhase.READY -> R.string.flow_binding_access_ready
                    HostBindingPhase.WAITING_ACCESS -> R.string.flow_binding_access_missing
                    else -> R.string.flow_binding_unverified
                }))
            }
            state.error?.let { cn.edu.ustc.tokenworks.TokenWorksStatusNotice(bindingError(it), isError = true) }
        }
}
@Composable
private fun bindingError(code: String) = when (code) {
    "binding.input_invalid" -> stringResource(R.string.binding_error_input)
    "binding.review_cleared" -> stringResource(R.string.binding_error_review_cleared)
    "binding.code_required" -> stringResource(R.string.binding_error_code_required)
    "binding.expired" -> stringResource(R.string.binding_error_expired)
    "binding.confirmed_elsewhere" -> stringResource(R.string.binding_error_elsewhere)
    "binding.organization_mismatch" -> stringResource(R.string.binding_error_organization)
    "binding.details_changed", "binding.identity_mismatch" -> stringResource(R.string.binding_error_changed)
    "binding.protocol_unsupported" -> stringResource(R.string.binding_error_unsupported)
    "binding.unavailable" -> stringResource(R.string.binding_error_unavailable)
    "access.denied", "access.revoked" -> stringResource(R.string.binding_error_access)
    "protocol.invalid" -> stringResource(R.string.binding_error_protocol)
    else -> stringResource(R.string.binding_error_generic)
}
