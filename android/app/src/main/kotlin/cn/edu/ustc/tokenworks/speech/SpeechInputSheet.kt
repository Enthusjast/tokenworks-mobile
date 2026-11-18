package cn.edu.ustc.tokenworks

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun SpeechInputSheet(
    onInsert: (String) -> Unit,
    onDismiss: () -> Unit,
    speechInputCoordinator: SpeechInputCoordinator? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val coordinator = remember(context, lifecycle, speechInputCoordinator) {
        speechInputCoordinator ?: SpeechInputCoordinator(context)
    }
    val state by coordinator.state.collectAsState()
    var systemAllowed by remember(coordinator) { mutableStateOf(false) }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        coordinator.setForeground(
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
        )
        coordinator.onPermissionResult(granted)
    }
    DisposableEffect(coordinator, lifecycle) {
        coordinator.setForeground(
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
        )
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> coordinator.setForeground(true, resumed = false)
                Lifecycle.Event.ON_RESUME -> coordinator.setForeground(true)
                Lifecycle.Event.ON_PAUSE -> coordinator.setForeground(true, resumed = false)
                Lifecycle.Event.ON_STOP -> {
                    systemAllowed = false
                    coordinator.setForeground(false)
                }
                Lifecycle.Event.ON_DESTROY -> coordinator.close()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            coordinator.close()
        }
    }
    val dismiss = {
        coordinator.close()
        onDismiss()
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.speech_input_title)) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.speech_input_privacy))
            if (coordinator.onDeviceAvailable) Text(stringResource(R.string.speech_input_on_device_notice))
            else {
                Text(stringResource(R.string.speech_input_system_notice))
                if (coordinator.systemAvailable) Row(
                    Modifier.fillMaxWidth().toggleable(
                        value = systemAllowed,
                        enabled = !state.listening && !state.awaitingPermission,
                        role = Role.Checkbox,
                        onValueChange = { systemAllowed = it },
                    ),
                ) {
                    Checkbox(systemAllowed, null, enabled = !state.listening && !state.awaitingPermission)
                    Text(stringResource(R.string.speech_input_system_consent), Modifier.padding(top = 12.dp))
                } else Text(stringResource(R.string.speech_input_system_unavailable))
            }
            if (state.listening) Text(
                stringResource(if (state.finishing) R.string.speech_input_finishing else R.string.speech_input_listening),
                color = MaterialTheme.colorScheme.primary,
            )
            if (state.awaitingPermission) Text(stringResource(R.string.speech_input_awaiting_permission))
            state.error?.let { error ->
                val message = when (error) {
                    is SpeechInputError.Message -> stringResource(error.resourceId)
                    is SpeechInputError.Formatted -> stringResource(error.resourceId, error.argument)
                }
                Text(message, color = MaterialTheme.colorScheme.error)
            }
            OutlinedTextField(state.text, coordinator::editText, Modifier.fillMaxWidth().heightIn(min = 120.dp),
                label = { Text(stringResource(R.string.speech_input_text_label)) }, enabled = !state.listening && !state.awaitingPermission)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.listening) OutlinedButton(coordinator::stop, enabled = !state.finishing) { Text(stringResource(R.string.speech_input_stop)) }
                else OutlinedButton({
                    val consent = systemAllowed
                    systemAllowed = false
                    // Check again at the action boundary, not only when the observer last ran.
                    coordinator.setForeground(
                        lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
                        resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                    )
                    coordinator.requestStart(
                        hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                        allowSystemProvider = consent,
                        requestPermission = { microphone.launch(Manifest.permission.RECORD_AUDIO) },
                    )
                }, enabled = !state.awaitingPermission && (coordinator.onDeviceAvailable || (coordinator.systemAvailable && systemAllowed))) {
                    Text(stringResource(R.string.speech_input_start))
                }
                TextButton({ systemAllowed = false; coordinator.cancel() }) { Text(stringResource(R.string.speech_input_clear)) }
            }
        }
    }, confirmButton = {
        TextButton({
            val current = coordinator.state.value
            if (!current.listening && !current.awaitingPermission && current.text.isNotBlank()) {
                coordinator.close()
                onInsert(current.text)
            }
        }, enabled = !state.listening && !state.awaitingPermission && state.text.isNotBlank()) { Text(stringResource(R.string.speech_input_insert)) }
    }, dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.speech_input_cancel)) } })
}
