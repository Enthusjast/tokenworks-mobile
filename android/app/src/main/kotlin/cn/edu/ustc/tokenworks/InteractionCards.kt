package cn.edu.ustc.tokenworks

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async

@Composable
internal fun InteractionCards(
    interactions: List<PendingInteraction>, onApprove: (String, suspend () -> Unit) -> Unit,
    onReject: (String) -> Unit, onAnswers: (String, List<InteractionBridge.AnsweredQuestion>) -> Unit,
    modifier: Modifier = Modifier, error: String? = null,
    decisions: List<InteractionDecision> = emptyList(), sessionId: String? = null,
) {
    val history = remember(decisions, sessionId) { decisions.filter { sessionId != null && it.sessionId == sessionId } }
    if (interactions.isEmpty() && error == null && history.isEmpty()) return
    var selectedKey by remember(sessionId) { mutableStateOf<String?>(null) }
    val current = interactions.firstOrNull { it.eventId == selectedKey } ?: interactions.firstOrNull()
    val index = interactions.indexOf(current).coerceAtLeast(0)
    val view = LocalView.current
    val approvals = interactions.filterIsInstance<PendingInteraction.Approval>().map { it.eventId }.toSet()
    val announced = remember(sessionId) { mutableSetOf<String>() }
    LaunchedEffect(view, approvals) {
        announced.retainAll(approvals)
        if (approvals.any { it !in announced }) view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        announced.addAll(approvals)
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        error?.let { InteractionError(it) }
        if (interactions.size > 1) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton({ selectedKey = interactions[index - 1].eventId }, enabled = index > 0) { Text(stringResource(R.string.interaction_previous)) }
            Text(stringResource(R.string.interaction_position, index + 1, interactions.size))
            TextButton({ selectedKey = interactions[index + 1].eventId }, enabled = index + 1 < interactions.size) { Text(stringResource(R.string.interaction_next)) }
        }
        current?.let { interaction -> key(interaction.eventId) {
            when (interaction) {
                is PendingInteraction.Approval -> ApprovalCard(interaction, onApprove, onReject)
                is PendingInteraction.Question -> QuestionCard(interaction, onAnswers, onReject)
            }
        } }
        if (history.isNotEmpty()) key(sessionId) { DecisionHistory(history) }
    }
}

@Composable
private fun InteractionError(code: String) {
    val text = when (code) {
        "interaction.answers.required" -> R.string.interaction_error_required
        "interaction.answers.option", "interaction.answers.cardinality" -> R.string.interaction_error_options
        "interaction.result_unconfirmed", "interaction.answer_not_accepted" -> R.string.interaction_error_unconfirmed
        "interaction.stale", "interaction.already_continued", "interaction.already_attempted" -> R.string.interaction_error_stale
        "interaction.biometric_unavailable" -> R.string.interaction_biometric_unavailable
        "interaction.biometric_cancelled" -> R.string.interaction_biometric_cancelled
        else -> R.string.interaction_error_generic
    }
    Text(stringResource(text), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun InteractionStatus(interaction: PendingInteraction) {
    when {
        interaction.busy -> Row(horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(stringResource(if (interaction is PendingInteraction.Approval && interaction.verifyingBiometric)
                R.string.interaction_biometric_in_progress else R.string.interaction_status_sending),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        interaction.error != null -> InteractionError(interaction.error!!)
        interaction.attempted -> TokenWorksStatusNotice(
            stringResource(R.string.interaction_status_waiting),
            tone = TokenWorksNoticeTone.INFO,
        )
    }
}

@Composable
private fun ApprovalCard(
    interaction: PendingInteraction.Approval,
    onApprove: (String, suspend () -> Unit) -> Unit,
    onReject: (String) -> Unit,
) {
    val context = LocalContext.current
    val cardScope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var pressedKey by remember { mutableStateOf<Key?>(null) }
    var focused by remember { mutableStateOf(false) }
    val enabled = !interaction.busy && !interaction.attempted
    val allow = {
        onApprove(interaction.eventId) {
            val verification = cardScope.async { runCatching { verifyStrongApproval(context) } }
            try { verification.await().getOrThrow() } finally { verification.cancel() }
        }
    }
    val reject = { onReject(interaction.eventId) }
    val tool = stringResource(approvalTool(interaction.toolName)?.label ?: R.string.interaction_tool_other)
    Surface(
        Modifier.fillMaxWidth().tokenWorksEntrance(interaction.eventId),
        shape = tokenWorksSquircle(TokenWorksRadius.lg),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(TokenWorksSpacing.lg), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
            Column(Modifier.fillMaxWidth().testTag("approval-shortcuts")
                .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                    tokenWorksSquircle(TokenWorksRadius.sm))
                .onFocusChanged { state -> focused = state.isFocused; if (!focused) pressedKey = null }
                .onKeyEvent { event ->
                    if (!enabled || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed || event.isShiftPressed)
                        return@onKeyEvent false
                    val key = event.key
                    if (key != Key.Enter && key != Key.NumPadEnter && key != Key.Escape) return@onKeyEvent false
                    when (event.type) {
                        KeyEventType.KeyDown -> { pressedKey = key; true }
                        KeyEventType.KeyUp -> {
                            val matches = pressedKey == key && !event.nativeKeyEvent.isCanceled
                            pressedKey = null
                            if (matches) { if (key == Key.Escape) reject() else allow() }
                            matches
                        }
                        else -> false
                    }
                }.focusable(enabled).padding(TokenWorksSpacing.xs)) {
                Text(stringResource(R.string.interaction_approval), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() })
                Text(tool, style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.interaction_keyboard_help), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.interaction_biometric_required), style = MaterialTheme.typography.bodyMedium)
            val disclosure = stringResource(if (expanded) R.string.interaction_hide_details else R.string.interaction_show_details)
            TextButton({ expanded = !expanded }, Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)
                .semantics { stateDescription = disclosure }) { Text(disclosure) }
            if (expanded) Text(stringResource(R.string.interaction_detail_policy), style = MaterialTheme.typography.bodyMedium)
            InteractionStatus(interaction)
            Row(horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md), modifier = Modifier.fillMaxWidth()) {
                Button(allow, enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                    Text(stringResource(R.string.interaction_allow))
                }
                OutlinedButton(reject, enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                    Text(stringResource(R.string.interaction_reject))
                }
            }
        }
    }
}

@Composable
private fun DecisionHistory(decisions: List<InteractionDecision>) {
    var expanded by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), shape = tokenWorksSquircle(TokenWorksRadius.lg),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = TokenWorksSpacing.md, vertical = TokenWorksSpacing.sm)) {
            TextButton({ expanded = !expanded }, Modifier.heightIn(min = TokenWorksSpacing.minimumTouchTarget)) {
                Text(stringResource(R.string.interaction_history, decisions.size))
            }
            if (expanded) {
                Text(stringResource(R.string.interaction_history_limit), style = MaterialTheme.typography.bodySmall)
                Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(vertical = TokenWorksSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.xs)) {
                    decisions.forEach { decision -> key(decision.sequence) {
                        val outcome = when (decision.outcome) {
                            InteractionDecision.Outcome.ALLOWED -> R.string.interaction_history_allowed
                            InteractionDecision.Outcome.REJECTED -> R.string.interaction_history_rejected
                            InteractionDecision.Outcome.CANCELLED -> R.string.interaction_history_cancelled
                            InteractionDecision.Outcome.UNAVAILABLE -> R.string.interaction_history_unavailable
                        }
                        Text(stringResource(R.string.interaction_history_row,
                            stringResource(decision.tool?.label ?: R.string.interaction_tool_other), stringResource(outcome)),
                            style = MaterialTheme.typography.bodyMedium)
                    } }
                }
            }
        }
    }
}

@Composable
private fun QuestionCountdown(interaction: PendingInteraction.Question) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TokenWorksSpacing.sm)) {
        val remaining = interaction.remainingMs
        val initial = interaction.initialRemainingMs
        val indicator = Modifier.size(24.dp).clearAndSetSemantics { }
        if (remaining != null && initial != null && initial > 0) {
            CircularProgressIndicator(progress = { (remaining.toDouble() / initial).coerceIn(0.0, 1.0).toFloat() },
                modifier = indicator, strokeWidth = 3.dp)
        } else CircularProgressIndicator(indicator, strokeWidth = 3.dp)
        val text = if (remaining == null) stringResource(R.string.interaction_question_waiting) else {
            val seconds = remainingSeconds(remaining)
            pluralStringResource(R.plurals.interaction_question_remaining, seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), seconds)
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
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
    val enabled = !interaction.busy && !interaction.attempted && (!interaction.timed ||
        interaction.phase == PendingInteraction.QuestionPhase.CONTINUED || (interaction.remainingMs ?: 0) > 0)
    val answers = interaction.questions.map { card -> InteractionBridge.AnsweredQuestion(
        card.id, selections[card.id].orEmpty().toList(), customs[card.id]?.trim()?.ifEmpty { null }) }
    val valid = runCatching { validateQuestionAnswers(interaction, answers) }.isSuccess
    Surface(
        Modifier.fillMaxWidth(),
        shape = tokenWorksSquircle(TokenWorksRadius.lg),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(TokenWorksSpacing.lg), verticalArrangement = Arrangement.spacedBy(TokenWorksSpacing.md)) {
            Text(stringResource(R.string.interaction_question_title), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            if (interaction.phase == PendingInteraction.QuestionPhase.CONTINUED) {
                Text(stringResource(R.string.interaction_question_continued), style = MaterialTheme.typography.labelLarge)
            } else if (interaction.timed) {
                QuestionCountdown(interaction)
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
                            label = { Text(when {
                                card.options.isEmpty() -> stringResource(R.string.interaction_question_answer, card.question)
                                card.multiSelect -> stringResource(R.string.interaction_question_other_multiple)
                                else -> stringResource(R.string.interaction_question_other_single)
                            }) })
                    }
                } }
            }
            InteractionStatus(interaction)
            if (!valid && enabled) Text(stringResource(R.string.interaction_question_complete), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button({ onAnswers(interaction.eventId, answers) }, enabled = enabled && valid,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.interaction_question_submit)) }
                if (interaction.phase == PendingInteraction.QuestionPhase.OPEN) {
                    OutlinedButton({ onReject(interaction.eventId) }, enabled = enabled,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.interaction_question_defer)) }
                }
            }
        }
    }
}
