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
    AlertDialog(onDismissRequest = dismiss, title = { Text("语音转文字") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("识别结果仅填入草稿，不会自动发送。应用不保存、上传或转发音频；麦克风只在你开始识别后启用。")
            if (coordinator.onDeviceAvailable) Text("当前使用设备上的本机识别服务，不会自动切换到联网服务。")
            else {
                Text("此设备没有可用的本机识别服务。系统语音服务可能将音频发送给其提供商联网处理；优先离线不能保证离线，这不受应用控制。")
                if (coordinator.systemAvailable) Row(
                    Modifier.fillMaxWidth().toggleable(
                        value = systemAllowed,
                        enabled = !state.listening && !state.awaitingPermission,
                        role = Role.Checkbox,
                        onValueChange = { systemAllowed = it },
                    ),
                ) {
                    Checkbox(systemAllowed, null, enabled = !state.listening && !state.awaitingPermission)
                    Text("允许本次使用系统语音服务", Modifier.padding(top = 12.dp))
                } else Text("没有可用的系统语音服务；仍可使用键盘输入。")
            }
            if (state.listening) Text(
                if (state.finishing) "正在等待识别结果…离开应用会立即取消。" else "正在聆听…离开应用会立即取消。",
                color = MaterialTheme.colorScheme.primary,
            )
            if (state.awaitingPermission) Text("正在等待麦克风权限请求结束；关闭或离开应用后不会开始识别。")
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(state.text, coordinator::editText, Modifier.fillMaxWidth().heightIn(min = 120.dp),
                label = { Text("识别文字（可修改）") }, enabled = !state.listening && !state.awaitingPermission)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.listening) OutlinedButton(coordinator::stop, enabled = !state.finishing) { Text("结束聆听") }
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
                    Text("开始识别")
                }
                TextButton({ systemAllowed = false; coordinator.cancel() }) { Text("清空") }
            }
        }
    }, confirmButton = {
        TextButton({
            val current = coordinator.state.value
            if (!current.listening && !current.awaitingPermission && current.text.isNotBlank()) {
                coordinator.close()
                onInsert(current.text)
            }
        }, enabled = !state.listening && !state.awaitingPermission && state.text.isNotBlank()) { Text("插入草稿") }
    }, dismissButton = { TextButton(dismiss) { Text("取消") } })
}
