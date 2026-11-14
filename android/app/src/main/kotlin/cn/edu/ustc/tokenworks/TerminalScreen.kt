package cn.edu.ustc.tokenworks

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * N-2: terminal surface. Monospace output view plus an input line; lifecycle
 * handled by TerminalWatcher.
 */
@Composable
internal fun TerminalScreen(watcher: TerminalWatcher, modifier: Modifier = Modifier) {
    val screen by watcher.screen.collectAsState()
    val attached by watcher.attached.collectAsState()
    val terminals by watcher.terminals.collectAsState()
    val error by watcher.error.collectAsState()
    var input by remember { mutableStateOf("") }
    Column(modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Button({ watcher.open("mobile-" + System.currentTimeMillis()) }, enabled = attached == null) { Text("新建终端") }
            attached?.let { info -> OutlinedButton({ watcher.closeTerminal(info.id) }) { Text("关闭 ${info.title}") } }
            OutlinedButton({ watcher.refresh() }) { Text("刷新") }
            if (terminals.isNotEmpty()) Text("${terminals.size} 个终端", style = MaterialTheme.typography.labelMedium)
        }
        error?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }
        SelectionContainer {
            Text(screen.ifEmpty { if (attached == null) "未连接终端" else "等待输出…" },
                Modifier.fillMaxWidth().heightIn(min = 200.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(input, { input = it }, modifier = Modifier.weight(1f), label = { Text("命令") },
                singleLine = true, enabled = attached?.running == true)
            Button({ if (input.isNotBlank()) { watcher.send(input.ifEndsWithNewline()); input = "" } },
                enabled = attached?.running == true) { Text("发送") }
        }
        val attachedInfo = attached
        if (attachedInfo != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(80 to 24, 100 to 32, 120 to 40).forEach { (c, r) ->
                OutlinedButton({ watcher.resize(c, r) }) { Text("${c}×${r}") }
            }
        }
    }
}

private fun String.ifEndsWithNewline(): String = if (endsWith("\n")) this else "$this\n"
