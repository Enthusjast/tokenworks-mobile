package cn.edu.ustc.tokenworks

import kotlinx.coroutines.flow.StateFlow

/** eventId is an opaque action key, bound to a connection generation and client. */
sealed class PendingInteraction {
    abstract val eventId: String
    abstract val agentId: String
    abstract val busy: Boolean
    abstract val attempted: Boolean
    abstract val error: String?

    data class Approval(
        override val eventId: String, override val agentId: String,
        val toolName: String, val callId: String?, val reason: String?,
        override val busy: Boolean = false, override val attempted: Boolean = false,
        override val error: String? = null,
    ) : PendingInteraction()

    data class Question(
        override val eventId: String, override val agentId: String, val callId: String?,
        val timed: Boolean, val questions: List<QuestionCard>,
        val phase: QuestionPhase = QuestionPhase.OPEN,
        val remainingMs: Long? = null,
        override val busy: Boolean = false, override val attempted: Boolean = false,
        override val error: String? = null,
    ) : PendingInteraction()

    enum class QuestionPhase { OPEN, CONTINUED }
    data class QuestionOption(val label: String, val description: String? = null)
    data class QuestionIntent(val kind: String, val approve: String)
    data class QuestionCard(
        val id: String, val question: String, val detail: String?, val header: String?,
        val options: List<QuestionOption>, val multiSelect: Boolean, val intent: QuestionIntent? = null,
    )
}

interface InteractionBridge : AutoCloseable {
    val interactions: StateFlow<List<PendingInteraction>>
    val errors: StateFlow<String?>
    suspend fun approve(eventId: String)
    suspend fun reject(eventId: String, message: String)
    suspend fun submitAnswers(eventId: String, answers: List<AnsweredQuestion>)
    data class AnsweredQuestion(val id: String, val selected: List<String>, val custom: String?)
}

/** Native authority still validates answers; this prevents malformed local submissions. */
fun validateQuestionAnswers(question: PendingInteraction.Question, answers: List<InteractionBridge.AnsweredQuestion>) {
    val cards = question.questions.associateBy { it.id }
    if (answers.size != cards.size || answers.map { it.id }.toSet() != cards.keys) throw DshFailure("interaction.answers.required")
    for (answer in answers) {
        val card = cards.getValue(answer.id)
        val known = card.options.map { it.label }.toSet()
        if (answer.selected.distinct().size != answer.selected.size || answer.selected.any { it !in known })
            throw DshFailure("interaction.answers.option")
        val custom = !answer.custom.isNullOrBlank()
        if (answer.selected.isEmpty() && !custom) throw DshFailure("interaction.answers.required")
        if (!card.multiSelect && answer.selected.size + (if (custom) 1 else 0) > 1)
            throw DshFailure("interaction.answers.cardinality")
    }
}
