package cn.edu.ustc.tokenworks

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect

/** Live waterfalls and explicit session projections are distinct answer routes. */
class NativeInteractionBridge(
    private val events: DshEvents,
    private val mux: DshMux,
    private val post: suspend (endpoint: String, args: JsonObject) -> JsonValue,
    owner: CoroutineScope,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sessionId: String? = null,
) : InteractionBridge {
    private val lock = Any()
    private val scope = CoroutineScope(owner.coroutineContext + SupervisorJob(owner.coroutineContext[Job]))
    private val mutable = MutableStateFlow<List<PendingInteraction>>(emptyList())
    override val interactions: StateFlow<List<PendingInteraction>> = mutable.asStateFlow()
    private val mutableErrors = MutableStateFlow<String?>(null)
    override val errors: StateFlow<String?> = mutableErrors.asStateFlow()
    private var live = emptyList<DshWaterfall>()
    private val continued = linkedMapOf<String, PendingInteraction.Question>()
    private val directSends = mutableMapOf<String, Job>()
    private val waits = mutableMapOf<String, Job>()
    private val waitStreams = mutableMapOf<String, DshStream>()
    private val deadlines = mutableMapOf<String, Long>()
    private val initialRemaining = mutableMapOf<String, Long>()
    private var closed = false

    init {
        scope.launch { events.pending.collect { values -> synchronized(lock) {
            live = if (sessionId == null) values else values.filter { it.agentId == sessionId }
            val keys = live.map { it.key }.toSet()
            waits.keys.filter { it !in keys }.forEach { waits.remove(it)?.cancel() }
            deadlines.keys.retainAll(keys)
            initialRemaining.keys.retainAll(keys)
            publish()
        } } }
        scope.launch { while (isActive) { delay(100); synchronized(lock) { publish() } } }
    }

    private fun decodeCards(request: JsonObject): List<PendingInteraction.QuestionCard> {
        val cards = request.array("questions").map { value ->
            val card = value as? JsonObject ?: throw ProtocolException()
            val options = (card.fields["options"] as? JsonArray)?.values?.map { option ->
                val item = option as? JsonObject ?: throw ProtocolException()
                PendingInteraction.QuestionOption(item.string("label"), item.text("description"))
            }.orEmpty()
            if (options.any { it.label.isBlank() } || options.map { it.label }.distinct().size != options.size) throw ProtocolException()
            val intent = (card.fields["intent"] as? JsonObject)?.let {
                PendingInteraction.QuestionIntent(it.string("kind"), it.string("approve"))
            }
            PendingInteraction.QuestionCard(card.string("id"), card.string("question"), card.text("detail"),
                card.text("header"), options, card.fields["multiSelect"] == JsonBoolean(true), intent)
        }
        if (cards.isEmpty() || cards.any { it.id.isBlank() } || cards.map { it.id }.distinct().size != cards.size) throw ProtocolException()
        return cards
    }

    private fun publish() {
        if (closed) return
        val decoded = live.mapNotNull { waterfall ->
            try {
                when (waterfall.event) {
                    "approval/request" -> PendingInteraction.Approval(waterfall.key, waterfall.agentId,
                        waterfall.request.string("toolName"), waterfall.request.text("callId"), waterfall.request.text("reason"),
                        waterfall.busy, waterfall.attempted, waterfall.error)
                    "user-questions/request" -> {
                        // A waterfall can only represent OPEN, never a continued projection.
                        if (waterfall.request.text("state")?.let { it != "open" } == true) throw ProtocolException()
                        val wait = waterfall.request.fields["wait"] as? JsonObject
                        val timed = wait?.fields?.get("timed") == JsonBoolean(true)
                        val callId = wait?.text("callId")
                        val cards = decodeCards(waterfall.request)
                        if (timed && callId == null) throw ProtocolException()
                        if (timed && waterfall.key !in waits && waterfall.key !in deadlines) attach(waterfall, callId!!)
                        val remaining = deadlines[waterfall.key]?.let { (it - nowMillis()).coerceAtLeast(0) }
                        if (remaining == 0L) { events.expire(waterfall.key); null }
                        else PendingInteraction.Question(waterfall.key, waterfall.agentId, callId, timed, cards,
                            remainingMs = remaining, busy = waterfall.busy, attempted = waterfall.attempted, error = waterfall.error,
                            initialRemainingMs = initialRemaining[waterfall.key])
                    }
                    else -> null
                }
            } catch (_: Exception) { mutableErrors.value = "interaction.invalid"; null }
        }
        val continuedCalls = continued.values.map { it.agentId to it.callId }.toSet()
        mutable.value = decoded.filterNot { it is PendingInteraction.Question && (it.agentId to it.callId) in continuedCalls } + continued.values
    }

    private fun attach(waterfall: DshWaterfall, callId: String) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val started = nowMillis()
            val stream = mux.open("userQuestions/attachWait", jsonObject("args" to jsonObject(
                "agentId" to waterfall.agentId.json(), "callId" to callId.json())))
            val admitted = synchronized(lock) {
                if (closed) false else { waitStreams[waterfall.key] = stream; true }
            }
            if (!admitted) { stream.close(); return@launch }
            try {
                while (true) {
                    when (val frame = stream.next() ?: break) {
                        is DshMuxFrame.Item -> {
                            val value = (frame.value as? DshOptional.Value)?.value ?: throw ProtocolException()
                            val remaining = DshUserQuestionsAttachWaitResult.codec.decode(value).remainingMs
                            if (!remaining.isFinite() || remaining < 0 || remaining > Long.MAX_VALUE.toDouble() / 2) throw ProtocolException()
                            synchronized(lock) {
                                if (events.pending.value.none { it.key == waterfall.key }) return@launch
                                initialRemaining.putIfAbsent(waterfall.key, remaining.toLong())
                                val deadline = started + remaining.toLong()
                                deadlines[waterfall.key] = minOf(deadlines[waterfall.key] ?: deadline, deadline)
                                publish()
                            }
                        }
                        is DshMuxFrame.Error -> throw DshFailure("interaction.wait_failed")
                        is DshMuxFrame.End -> break
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableErrors.value = "interaction.wait_failed" }
            finally {
                synchronized(lock) {
                    if (waitStreams[waterfall.key] === stream) {
                        waitStreams.remove(waterfall.key)
                        // An unconsumed closed claim can no longer authorize an answer. If the
                        // result is already in flight, let the existing acknowledgement settle.
                        if (events.pending.value.any { it.key == waterfall.key && !it.attempted }) events.expire(waterfall.key)
                    }
                }
                stream.close()
            }
        }
        waits[waterfall.key] = job
        job.start()
    }

    /** Full native values.userQuestions projection for one agent, not a waterfall payload. */
    fun updateUserQuestions(agentId: String, projection: JsonObject) = synchronized(lock) {
        if (closed || (sessionId != null && agentId != sessionId)) return@synchronized
        try {
            val next = projection.array("active").mapNotNull { value ->
                val item = value as? JsonObject ?: throw ProtocolException()
                if (item.string("state") != "continued") return@mapNotNull null
                val callId = item.string("callId")
                val key = "${events.generation}:projection:$agentId:$callId"
                val old = continued[key]
                PendingInteraction.Question(key, agentId, callId, true, decodeCards(item),
                    PendingInteraction.QuestionPhase.CONTINUED, busy = old?.busy ?: false,
                    attempted = old?.attempted ?: false, error = old?.error)
            }
            val keys = next.map { it.eventId }.toSet()
            continued.values.filter { it.agentId == agentId && it.eventId !in keys }.forEach {
                continued.remove(it.eventId)
                directSends.remove(it.eventId)?.cancel(CancellationException("interaction.stale"))
            }
            next.forEach { question ->
                live.filter { it.agentId == agentId && (it.request.fields["wait"] as? JsonObject)?.text("callId") == question.callId }
                    .forEach { events.expire(it.key) }
                continued[question.eventId] = question
            }
            publish()
        } catch (_: Exception) { mutableErrors.value = "interaction.invalid" }
    }

    private fun find(key: String): PendingInteraction = synchronized(lock) {
        publish()
        mutable.value.firstOrNull { it.eventId == key } ?: throw DshFailure("interaction.stale")
    }

    private suspend fun result(key: String, value: JsonValue) {
        try { events.answer(key, DshEventOutcome.Result(DshOptional.Value(value))) }
        catch (error: Exception) { mutableErrors.value = "interaction.result_unconfirmed"; throw error }
    }

    internal fun requirePendingApproval(eventId: String): PendingInteraction.Approval {
        // The event list is the authority, not a delayed UI projection.
        val active = events.pending.value.firstOrNull {
            it.key == eventId && it.event == "approval/request" && (sessionId == null || it.agentId == sessionId)
        }
            ?: throw DshFailure("interaction.stale")
        if (active.attempted || active.busy) throw DshFailure("interaction.already_attempted")
        return PendingInteraction.Approval(active.key, active.agentId, active.request.string("toolName"),
            active.request.text("callId"), active.request.text("reason"))
    }

    override suspend fun approve(eventId: String) {
        if (find(eventId) !is PendingInteraction.Approval) throw DshFailure("interaction.invalid_action")
        result(eventId, "allowed-once".json())
    }

    override suspend fun reject(eventId: String, message: String) {
        val current = find(eventId)
        if (current is PendingInteraction.Question) {
            if (current.phase == PendingInteraction.QuestionPhase.CONTINUED) throw DshFailure("interaction.already_continued")
            if (current.timed && current.remainingMs == null) throw DshFailure("interaction.wait_failed")
            try { events.answer(eventId, DshEventOutcome.Next) }
            catch (error: Exception) { mutableErrors.value = "interaction.result_unconfirmed"; throw error }
        } else result(eventId, "rejected".json())
    }

    override suspend fun submitAnswers(eventId: String, answers: List<InteractionBridge.AnsweredQuestion>) {
        val current = find(eventId) as? PendingInteraction.Question ?: throw DshFailure("interaction.stale")
        if (current.phase == PendingInteraction.QuestionPhase.OPEN && current.timed && current.remainingMs == null)
            throw DshFailure("interaction.wait_failed")
        try { validateQuestionAnswers(current, answers) }
        catch (error: DshFailure) { mutableErrors.value = error.code; throw error }
        val payload = jsonObject("answers" to JsonArray(answers.map { item -> jsonObject(
            "id" to item.id.json(), "selected" to JsonArray(item.selected.map(String::json)),
            *(item.custom?.trim()?.takeIf { it.isNotEmpty() }?.let { arrayOf("custom" to it.json()) } ?: emptyArray())) }))
        if (current.phase == PendingInteraction.QuestionPhase.OPEN) { result(eventId, payload); return }
        coroutineScope {
            val send = synchronized(lock) {
                val question = continued[eventId] ?: throw DshFailure("interaction.stale")
                if (question.attempted) throw DshFailure("interaction.already_attempted")
                val job = async(start = CoroutineStart.LAZY) {
                    val accepted = post("userQuestions/answer", jsonObject("agentId" to current.agentId.json(),
                        "callId" to current.callId!!.json(), "answer" to payload))
                    if (accepted != JsonBoolean(true)) throw DshFailure("interaction.answer_not_accepted")
                }
                continued[eventId] = question.copy(busy = true, attempted = true)
                directSends[eventId] = job
                publish()
                job
            }
            try {
                send.start(); send.await()
                synchronized(lock) {
                    val question = continued[eventId] ?: throw DshFailure("interaction.stale")
                    // Retain a consumed tombstone until the projection removes this call.
                    continued[eventId] = question.copy(busy = false)
                    publish()
                }
            } catch (error: Exception) {
                synchronized(lock) {
                    continued[eventId]?.let { continued[eventId] = it.copy(busy = false, error = "interaction.result_unconfirmed") }
                    mutableErrors.value = "interaction.result_unconfirmed"
                    publish()
                }
                throw error
            } finally { synchronized(lock) { directSends.remove(eventId) } }
        }
    }

    override fun close() {
        val streams: List<DshStream>
        val jobs: List<Job>
        synchronized(lock) {
            if (closed) return
            closed = true
            streams = waitStreams.values.toList(); waitStreams.clear()
            jobs = directSends.values.toList() + waits.values
            directSends.clear(); waits.clear(); deadlines.clear(); initialRemaining.clear(); continued.clear(); live = emptyList()
            mutable.value = emptyList()
        }
        // Cancel the actual native claim synchronously, before the owner closes its mux.
        // Coroutine cancellation alone may run finally only after the carrier is gone.
        streams.forEach(DshStream::close)
        jobs.forEach { it.cancel() }
        scope.cancel()
        events.close()
    }

    private fun JsonObject.text(key: String) = (fields[key] as? JsonString)?.value
}
