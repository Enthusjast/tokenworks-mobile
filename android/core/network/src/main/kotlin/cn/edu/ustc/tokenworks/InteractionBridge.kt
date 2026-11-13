package cn.edu.ustc.tokenworks

import kotlinx.coroutines.flow.StateFlow

/** One pending interaction surfaced from the Host's `$events` waterfall. */
sealed class PendingInteraction {
    abstract val eventId: String
    abstract val agentId: String

    data class Approval(
        override val eventId: String, override val agentId: String,
        val toolName: String, val callId: String?, val reason: String?,
    ) : PendingInteraction()

    data class Question(
        override val eventId: String, override val agentId: String, val callId: String?,
        val timed: Boolean, val questions: List<QuestionCard>,
    ) : PendingInteraction()

    data class QuestionCard(val id: String, val question: String, val detail: String?, val header: String?,
        val options: List<String>, val multiSelect: Boolean)
}

/**
 * v2 approval/question bridge (IMPROVE_PLAN §6.4): one `$events` mux stream per
 * runtime; `user-questions/request` and `approval/request` waterfalls become UI
 * state; answers go through `userQuestions.answer` and `$events/result`.
 */
interface InteractionBridge : AutoCloseable {
    val interactions: StateFlow<List<PendingInteraction>>
    val errors: StateFlow<String?>

    /** Approve one tool call for this attempt only. */
    suspend fun approve(eventId: String)

    /** Reject one approval or question with an explanation. */
    suspend fun reject(eventId: String, message: String)

    /** Submit structured question answers keyed by question id. */
    suspend fun submitAnswers(eventId: String, answers: List<AnsweredQuestion>)

    data class AnsweredQuestion(val id: String, val selected: List<String>, val custom: String?)
}
