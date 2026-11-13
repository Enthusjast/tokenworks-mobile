package cn.edu.ustc.tokenworks

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * v2 approval/question bridge (IMPROVE_PLAN §6.4): the runtime's single `$events`
 * DshEvents instance becomes typed UI state; answers go back through
 * `userQuestions/answer` (questions) and the DshEvents result transport (both).
 *
 * The waterfall request payload is untrusted: unknown shapes are reported as an
 * error instead of throwing into the Host connection.
 */
class NativeInteractionBridge(
    private val runtimeId: String,
    private val events: DshEvents,
    /** `POST /api/<endpoint>` through the caller's lease; endpoint excludes the /api prefix. */
    private val post: suspend (endpoint: String, args: JsonObject) -> Unit,
) : InteractionBridge {
    private val mutable = MutableStateFlow(emptyList<PendingInteraction>())
    override val interactions: StateFlow<List<PendingInteraction>> = mutable.asStateFlow()
    private val mutableErrors = MutableStateFlow<String?>(null)
    override val errors: StateFlow<String?> = mutableErrors.asStateFlow()
    private val handled = MutableStateFlow(emptySet<String>())

    /** Called by the connection owner whenever the events stream delivered frames. */
    fun refresh() {
        val open = mutableListOf<PendingInteraction>()
        val stale = handled.value.toMutableSet()
        for (waterfall in events.open()) {
            val interaction = decode(waterfall) ?: continue
            stale.remove(waterfall.eventId)
            open.add(interaction)
        }
        // Waterfalls answered or cancelled elsewhere drop out of the list.
        stale.forEach(handled.value::minus)
        handled.value = open.map { it.eventId }.toSet()
        mutable.value = open
    }

    private fun decode(waterfall: DshWaterfall.Open): PendingInteraction? = try {
        when (waterfall.event) {
            "approval/request" -> decodeApproval(waterfall)
            "user-questions/request" -> decodeQuestion(waterfall)
            else -> null
        }
    } catch (error: Exception) {
        mutableErrors.value = "interaction.invalid"
        null
    }

    private fun decodeApproval(waterfall: DshWaterfall.Open): PendingInteraction.Approval {
        val request = waterfall.request
        val toolName = request.string("toolName")
        return PendingInteraction.Approval(waterfall.eventId, waterfall.agentId, toolName,
            (request.fields["callId"] as? JsonString)?.value, (request.fields["reason"] as? JsonString)?.value)
    }

    private fun decodeQuestion(waterfall: DshWaterfall.Open): PendingInteraction.Question {
        val request = waterfall.request
        val questions = request.array("questions").map { value ->
            val card = value as? JsonObject ?: throw ProtocolException()
            val options = (card.fields["options"] as? JsonArray)?.values?.mapNotNull { option ->
                (option as? JsonObject)?.string("label")
            } ?: emptyList()
            PendingInteraction.QuestionCard(card.string("id"), card.string("question"),
                (card.fields["detail"] as? JsonString)?.value, (card.fields["header"] as? JsonString)?.value,
                options, card.fields["multiSelect"] == JsonBoolean(true))
        }
        if (questions.isEmpty()) throw ProtocolException()
        val wait = request.fields["wait"] as? JsonObject
        return PendingInteraction.Question(waterfall.eventId, waterfall.agentId,
            (wait?.fields?.get("callId") as? JsonString)?.value, wait?.fields?.get("timed") == JsonBoolean(true), questions)
    }

    private suspend fun result(eventId: String, outcome: DshEventOutcome) {
        events.answer(eventId, outcome)
        handled.value = handled.value - eventId
        mutable.value = mutable.value.filterNot { it.eventId == eventId }
    }

    override suspend fun approve(eventId: String) = result(eventId, DshEventOutcome.Result(DshOptional.Value("allowed-once".json())))

    override suspend fun reject(eventId: String, message: String) {
        // Rejected waterfalls need the caller-declared reason; approval listeners
        // surface it as the decision reason, question listeners as a rejection.
        result(eventId, DshEventOutcome.Rejected("device_declined", message))
    }

    override suspend fun submitAnswers(eventId: String, answers: List<InteractionBridge.AnsweredQuestion>) {
        val interaction = mutable.value.filterIsInstance<PendingInteraction.Question>().firstOrNull { it.eventId == eventId }
            ?: throw DshFailure("native events unknown waterfall")
        // The native answer endpoint carries the structured payload keyed by the
        // waterfall's agent/call identity; the result closes the waterfall itself.
        val callId = interaction.callId ?: throw DshFailure("question.no_call")
        val args = jsonObject(
            "agentId" to interaction.agentId.json(), "callId" to callId.json(),
            "answer" to jsonObject("answers" to JsonArray(answers.map { item ->
                jsonObject("id" to item.id.json(), "selected" to JsonArray(item.selected.map(String::json)),
                    *(item.custom?.let { arrayOf("custom" to it.json()) } ?: emptyArray()))
            })),
        )
        post("userQuestions/answer", args)
        result(eventId, DshEventOutcome.Result(DshOptional.Missing))
    }

    override fun close() = events.close()
}
