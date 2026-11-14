package cn.edu.ustc.tokenworks

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun InteractionCards(
    interactions: List<PendingInteraction>, onApprove: (String) -> Unit,
    onReject: (String) -> Unit, onAnswers: (String, List<InteractionBridge.AnsweredQuestion>) -> Unit,
    modifier: Modifier = Modifier, error: String? = null,
) {
    if (interactions.isEmpty() && error == null) return
    var selectedKey by remember { mutableStateOf<String?>(null) }
    val current = interactions.firstOrNull { it.eventId == selectedKey } ?: interactions.firstOrNull()
    val index = interactions.indexOf(current).coerceAtLeast(0)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        error?.let { InteractionError(it) }
        if (interactions.size > 1) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton({ selectedKey = interactions[index - 1].eventId }, enabled = index > 0) { Text("上一个") }
            Text("${index + 1} / ${interactions.size} 个待处理请求")
            TextButton({ selectedKey = interactions[index + 1].eventId }, enabled = index + 1 < interactions.size) { Text("下一个") }
        }
        current?.let { interaction -> key(interaction.eventId) {
            Text("会话：${interaction.agentId}", style = MaterialTheme.typography.labelMedium)
            when (interaction) {
                is PendingInteraction.Approval -> ApprovalCard(interaction, onApprove, onReject)
                is PendingInteraction.Question -> QuestionCard(interaction, onAnswers, onReject)
            }
        } }
    }
}

@Composable
private fun InteractionError(code: String) {
    val text = when (code) {
        "interaction.answers.required" -> "请回答所有问题。"
        "interaction.answers.option", "interaction.answers.cardinality" -> "请检查选项；单选题只能选择一项或填写其他答案。"
        "interaction.result_unconfirmed", "interaction.answer_not_accepted" -> "未确认主机收到结果。为避免重复操作，此请求不会自动重试，请等待主机更新。"
        "interaction.stale", "interaction.already_continued" -> "此请求已失效，请等待主机更新。"
        else -> "交互暂不可用，请检查连接并等待主机更新。"
    }
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun InteractionStatus(interaction: PendingInteraction) {
    when {
        interaction.busy -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("正在提交，等待主机确认…", style = MaterialTheme.typography.bodyMedium)
        }
        interaction.error != null -> InteractionError(interaction.error!!)
        interaction.attempted -> Text("已提交，等待主机更新", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ApprovalCard(interaction: PendingInteraction.Approval, onApprove: (String) -> Unit, onReject: (String) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("工具请求批准", style = MaterialTheme.typography.titleMedium)
            // Only the public tool label/reason is shown, never raw tool arguments or credentials.
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(interaction.toolName, style = MaterialTheme.typography.bodyLarge)
                interaction.reason?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            InteractionStatus(interaction)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button({ onApprove(interaction.eventId) }, enabled = !interaction.attempted,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("允许一次") }
                OutlinedButton({ onReject(interaction.eventId) }, enabled = !interaction.attempted,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("拒绝") }
            }
        }
    }
}

@Composable
private fun QuestionCard(
    interaction: PendingInteraction.Question,
    onAnswers: (String, List<InteractionBridge.AnsweredQuestion>) -> Unit,
    onReject: (String) -> Unit,
) {
    val selections = remember(interaction.eventId) { mutableStateMapOf<String, Set<String>>() }
    val customs = remember(interaction.eventId) { mutableStateMapOf<String, String>() }
    val enabled = !interaction.attempted && (!interaction.timed || interaction.phase == PendingInteraction.QuestionPhase.CONTINUED || interaction.remainingMs != null)
    val answers = interaction.questions.map { card -> InteractionBridge.AnsweredQuestion(
        card.id, selections[card.id].orEmpty().toList(), customs[card.id]?.trim()?.ifEmpty { null }) }
    val valid = runCatching { validateQuestionAnswers(interaction, answers) }.isSuccess
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("需要你的回答", style = MaterialTheme.typography.titleMedium)
            if (interaction.phase == PendingInteraction.QuestionPhase.CONTINUED) {
                Text("主机已继续执行，回答将作为新消息排队", style = MaterialTheme.typography.labelLarge)
            } else if (interaction.timed) {
                Text(interaction.remainingMs?.let { "剩余 ${(it + 999) / 1_000} 秒" } ?: "正在获取主机剩余时间…",
                    style = MaterialTheme.typography.labelLarge)
            }
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                interaction.questions.forEach { card -> key(card.id) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        card.header?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                        Text(card.question, style = MaterialTheme.typography.bodyLarge)
                        card.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        card.intent?.let { Text("${it.kind} · ${it.approve}", style = MaterialTheme.typography.labelLarge) }
                        card.options.forEach { option ->
                            val selected = option.label in selections[card.id].orEmpty()
                            val change: (Boolean) -> Unit = { checked ->
                                val current = selections[card.id].orEmpty()
                                selections[card.id] = if (card.multiSelect) {
                                    if (checked) current + option.label else current - option.label
                                } else setOf(option.label)
                                if (!card.multiSelect) customs.remove(card.id)
                            }
                            val action = if (card.multiSelect) Modifier.toggleable(selected, enabled = enabled,
                                role = Role.Checkbox, onValueChange = change)
                            else Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = { change(true) })
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).then(action).padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (card.multiSelect) Checkbox(selected, null, enabled = enabled)
                                else RadioButton(selected, null, enabled = enabled)
                                Column(Modifier.weight(1f)) {
                                    Text(option.label, style = MaterialTheme.typography.bodyMedium)
                                    option.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                        OutlinedTextField(customs[card.id].orEmpty(), { text ->
                            customs[card.id] = text
                            if (!card.multiSelect && text.isNotBlank()) selections.remove(card.id)
                        }, enabled = enabled, modifier = Modifier.fillMaxWidth(), minLines = 1,
                            label = { Text(if (card.options.isEmpty()) "${card.question} · 回答" else "其他答案（与单选选项互斥）") })
                    }
                } }
            }
            InteractionStatus(interaction)
            if (!valid && !interaction.attempted) Text("请完成全部问题后提交", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button({ onAnswers(interaction.eventId, answers) }, enabled = enabled && valid,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("提交") }
                if (interaction.phase == PendingInteraction.QuestionPhase.OPEN) {
                    OutlinedButton({ onReject(interaction.eventId) }, enabled = !interaction.attempted,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("不在此回答") }
                }
            }
        }
    }
}
