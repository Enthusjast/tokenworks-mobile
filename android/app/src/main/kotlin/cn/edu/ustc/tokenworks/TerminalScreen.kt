package cn.edu.ustc.tokenworks

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Native terminal controls use only the session-owned coordinator. */
@Composable
internal fun TerminalScreen(watcher: TerminalWatcher, modifier: Modifier = Modifier) {
    val state by watcher.state.collectAsState()
    var input by remember(watcher) { mutableStateOf("") }
    var closing by remember(watcher) { mutableStateOf<TerminalInfo?>(null) }
    val actions = rememberCoroutineScope()
    val scroll = rememberScrollState()
    LaunchedEffect(watcher) { watcher.refresh() }
    LaunchedEffect(state.screen) { scroll.scrollTo(scroll.maxValue) }
    fun submit() {
        if (watcher.send(input + "\r")) input = ""
    }
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(TokenWorksSpacing.md),
        verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
            Button(onClick = { actions.launch { watcher.open() } }, enabled = !state.busy && state.terminals.isEmpty(),
                modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text("新建终端") }
            OutlinedButton(onClick = { actions.launch { watcher.refresh() } }, enabled = !state.busy,
                modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text("刷新列表") }
            if (state.attached != null) OutlinedButton(onClick = watcher::detach, enabled = !state.busy,
                modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text("断开附件") }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.terminals.forEach { info ->
            Surface(Modifier.fillMaxWidth(), shape = tokenWorksSquircle(TokenWorksRadius.md),
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.padding(horizontal = TokenWorksSpacing.md, vertical = TokenWorksSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
                    Column(Modifier.weight(1f)) {
                        Text(info.title, style = MaterialTheme.typography.titleSmall)
                        Text("${info.state} · ${info.cols}×${info.rows}" + (info.exitCode?.let { " · exit $it" } ?: ""),
                            style = MaterialTheme.typography.labelMedium, color = tokenWorksSurfaces().textTertiary)
                    }
                    OutlinedButton(onClick = { actions.launch { watcher.attach(info.id) } }, enabled = !state.busy,
                        modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                        Text(if (state.attached?.id == info.id) "重新连接" else "连接")
                    }
                    TextButton(onClick = { closing = info }, enabled = !state.busy,
                        modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text("关闭进程") }
                }
            }
        }
        if (state.reconnecting) TokenWorksStatusNotice("连接中断，正在恢复屏幕；未确认输入不会重发。", tone = TokenWorksNoticeTone.WARNING)
        else if (state.attached != null && !state.writable) TokenWorksStatusNotice(
            if (state.attached?.running == true) "只读或等待终端快照" else "进程已结束，可查看屏幕或关闭终端",
            tone = TokenWorksNoticeTone.INFO)
        state.error?.let { TokenWorksStatusNotice(it, tone = TokenWorksNoticeTone.ERROR) }
        Surface(Modifier.fillMaxWidth(), shape = tokenWorksSquircle(TokenWorksRadius.md),
            color = tokenWorksSurfaces().codeWellBackground, border = BorderStroke(1.dp, tokenWorksSurfaces().codeWellBorder)) {
            SelectionContainer {
                Text(state.screen.ifEmpty { if (state.attached == null) "选择已有终端，或新建一个 shell。" else "等待输出…" },
                    Modifier.fillMaxWidth().padding(TokenWorksSpacing.md)
                        .heightIn(min = 200.dp, max = 360.dp).verticalScroll(scroll).horizontalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 20.sp),
                    color = tokenWorksSurfaces().codeWellForeground)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), label = { Text("命令 / 输入") },
                singleLine = true, enabled = state.writable, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }))
            Button(onClick = { submit() }, enabled = state.writable,
                modifier = Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) { Text("发送") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
            listOf("Ctrl-C" to "\u0003", "Ctrl-D" to "\u0004", "Tab" to "\t", "Esc" to "\u001b", "退格" to "\u007f",
                "上" to "\u001b[A", "下" to "\u001b[B", "左" to "\u001b[D", "右" to "\u001b[C").forEach { (label, value) ->
                OutlinedButton(onClick = { watcher.send(value) }, enabled = state.writable) { Text(label) }
            }
        }
        if (state.attached != null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
            listOf(80 to 24, 100 to 32, 120 to 40).forEach { (cols, rows) ->
                OutlinedButton(onClick = { watcher.resize(cols, rows) }, enabled = state.writable && state.environment?.accepts(cols, rows) == true) {
                    Text("${cols}×${rows}")
                }
            }
        }
        Text("断开只取消当前附件，不终止 shell。", style = MaterialTheme.typography.labelSmall, color = tokenWorksSurfaces().textTertiary)
    }
    closing?.let { info ->
        AlertDialog(onDismissRequest = { closing = null }, title = { Text("关闭终端进程？") },
            text = { Text("${info.title} 中运行的 shell 和子进程将被终止。") },
            confirmButton = { TextButton(onClick = { closing = null; actions.launch { watcher.closeTerminal(info.id) } }) { Text("关闭进程") } },
            dismissButton = { TextButton(onClick = { closing = null }) { Text("取消") } })
    }
}
