package cn.edu.ustc.tokenworks

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * F-04: approval and question cards rendered from the `$events` interaction
 * state; answers go back through the watcher's bridge.
 */
@Composable
internal fun InteractionCards(interactions: List<PendingInteraction>, onApprove: (String) -> Unit,
    onReject: (String) -> Unit, onAnswers: (String, List<InteractionBridge.AnsweredQuestion>) -> Unit,
    modifier: Modifier = Modifier) {
    if (interactions.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (interaction in interactions) {
            when (interaction) {
                is PendingInteraction.Approval -> ApprovalCard(interaction, onApprove, onReject)
                is PendingInteraction.Question -> QuestionCard(interaction, onAnswers, onReject)
            }
        }
    }
}

@Composable
private fun ApprovalCard(interaction: PendingInteraction.Approval, onApprove: (String) -> Unit, onReject: (String) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("工具请求批准", style = MaterialTheme.typography.titleMedium)
            Text(interaction.toolName, style = MaterialTheme.typography.bodyLarge)
            interaction.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button({ onApprove(interaction.eventId) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("允许一次") }
                OutlinedButton({ onReject(interaction.eventId) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("拒绝") }
            }
        }
    }
}

@Composable
private fun QuestionCard(interaction: PendingInteraction.Question, onAnswers: (String, List<InteractionBridge.AnsweredQuestion>) -> Unit,
    onReject: (String) -> Unit) {
    val selections = remember(interaction.eventId) { mutableStateMapOf<String, MutableList<String>>() }
    val customs = remember(interaction.eventId) { mutableStateMapOf<String, String>() }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (interaction.timed && interaction.phase == PendingInteraction.QuestionPhase.CONTINUED) {
                Text("已超时，回答将作为新消息排队", style = MaterialTheme.typography.labelMedium)
            }
            for (card in interaction.questions) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    card.header?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                    Text(card.question, style = MaterialTheme.typography.bodyLarge)
                    card.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (card.options.isEmpty()) {
                        var text by remember(card.id) { mutableStateOf(customs[card.id] ?: "") }
                        OutlinedTextField(text, { text = it; customs[card.id] = it },
                            modifier = Modifier.fillMaxWidth(), label = { Text("自由输入") }, minLines = 1)
                    } else {
                        card.options.forEach { option ->
                            val selected = card.id in selections && option in selections[card.id]!!
                            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                                when {
                                    card.multiSelect -> Checkbox(checked = selected, onCheckedChange = { checked ->
                                        val list = selections.getOrPut(card.id) { mutableListOf() }
                                        if (checked) list.add(option) else list.remove(option)
                                    })
                                    else -> RadioButton(selected = selected, onClick = { selections[card.id] = mutableListOf(option) })
                                }
                                Text(option, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        var free by remember(card.id) { mutableStateOf(customs[card.id] ?: "") }
                        OutlinedTextField(free, { free = it; customs[card.id] = it },
                            modifier = Modifier.fillMaxWidth(), label = { Text("其他（可选）") }, minLines = 1)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                val complete = interaction.questions.all { card ->
                    val picked = selections[card.id].orEmpty()
                    val custom = customs[card.id]?.trim().orEmpty()
                    if (card.options.isEmpty()) custom.isNotEmpty() else picked.isNotEmpty() || custom.isNotEmpty()
                }
                Button({ onAnswers(interaction.eventId, interaction.questions.map { card ->
                    InteractionBridge.AnsweredQuestion(card.id, selections[card.id].orEmpty(), customs[card.id]?.trim()?.ifEmpty { null })
                }) }, enabled = complete, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("提交") }
                OutlinedButton({ onReject(interaction.eventId) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("拒绝") }
            }
        }
    }
}
