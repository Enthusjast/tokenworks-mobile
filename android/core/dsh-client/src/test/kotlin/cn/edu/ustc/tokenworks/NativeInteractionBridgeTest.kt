package cn.edu.ustc.tokenworks

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeInteractionBridgeTest {
    private fun question(timed: Boolean = false) = jsonObject(
        "questions" to JsonArray(listOf(jsonObject("id" to "q".json(), "question" to "Pick one".json(),
            "header" to "Decision".json(), "detail" to "A public explanation".json(),
            "options" to JsonArray(listOf(jsonObject("label" to "yes".json(), "description" to "Proceed once".json()),
                jsonObject("label" to "no".json()))),
            "intent" to jsonObject("kind" to "plan".json(), "approve" to "Proceed".json())))),
        "wait" to jsonObject("callId" to "call".json(), "timed" to JsonBoolean(timed)),
    )
    private fun answers(selected: List<String> = listOf("yes"), custom: String? = null) =
        listOf(InteractionBridge.AnsweredQuestion("q", selected, custom))
    private fun projection(state: String = "continued") = jsonObject("active" to JsonArray(listOf(
        jsonObject("callId" to "call".json(), "state" to state.json(), "questions" to question().fields.getValue("questions")))),
        "settled" to JsonArray(emptyList()))

    @Test fun openQuestionsReturnExactStructuredAnswersWithoutDirectEndpoint() = runTest {
        val transport = InteractionTestTransport()
        val watcher = InteractionWatcher(transport, backgroundScope)
        try {
            runCurrent(); transport.muxes.single().apply { ready(); waterfall(event = "user-questions/request", request = question()) }; runCurrent()
            val current = watcher.interactions.value.single() as PendingInteraction.Question
            assertEquals("Proceed once", current.questions.single().options.first().description)
            assertEquals("plan", current.questions.single().intent?.kind)
            watcher.submitAnswers(current.eventId, answers())
            runCurrent()
            assertEquals(listOf(DSH_EVENTS_RESULT_ENDPOINT), transport.posts.map { it.first })
            assertNativeJson(jsonObject("answers" to JsonArray(listOf(jsonObject("id" to "q".json(),
                "selected" to JsonArray(listOf("yes".json())))))), transport.posts.single().second.obj("outcome").obj("value"))
            assertTrue(watcher.interactions.value.isEmpty())
        } finally { watcher.close() }
    }

    @Test fun decliningAnOpenQuestionDelegatesRatherThanClaimingAnInvalidAnswer() = runTest {
        val transport = InteractionTestTransport()
        val watcher = InteractionWatcher(transport, backgroundScope)
        try {
            runCurrent(); transport.muxes.single().apply { ready(); waterfall(event = "user-questions/request", request = question()) }; runCurrent()
            watcher.reject(watcher.interactions.value.single().eventId, "not on this device")
            assertNativeJson(jsonObject("kind" to "next".json()), transport.posts.single().second.obj("outcome"))
            runCurrent()
            assertTrue(watcher.interactions.value.isEmpty())
        } finally { watcher.close() }
    }

    @Test fun requiredKnownOptionsAndSingleSelectionAreValidatedBeforeSending() = runTest {
        val transport = InteractionTestTransport()
        val watcher = InteractionWatcher(transport, backgroundScope)
        try {
            runCurrent(); transport.muxes.single().apply { ready(); waterfall(event = "user-questions/request", request = question()) }; runCurrent()
            val key = watcher.interactions.value.single().eventId
            val bad = listOf(emptyList(), listOf(InteractionBridge.AnsweredQuestion("unknown", listOf("yes"), null)),
                answers(emptyList()), answers(listOf("unknown")), answers(listOf("yes", "no")),
                answers(listOf("yes", "yes")), answers(listOf("yes"), "custom"), answers() + answers())
            bad.forEach { value -> assertTrue(runCatching { watcher.submitAnswers(key, value) }.exceptionOrNull() is DshFailure) }
            assertTrue(transport.posts.isEmpty())
            watcher.submitAnswers(key, answers(emptyList(), "custom"))
            assertEquals("custom", ((transport.posts.single().second.obj("outcome").obj("value").array("answers").single()) as JsonObject).string("custom"))
        } finally { watcher.close() }
    }

    @Test fun multipleSelectionAndFreeTextQuestionValidation() {
        val multi = PendingInteraction.Question("key", "agent", null, false, listOf(
            PendingInteraction.QuestionCard("q", "Pick", null, null,
                listOf(PendingInteraction.QuestionOption("yes"), PendingInteraction.QuestionOption("no")), true)))
        validateQuestionAnswers(multi, answers(listOf("yes", "no"), "extra"))
        val free = multi.copy(questions = multi.questions.map { it.copy(options = emptyList(), multiSelect = false) })
        validateQuestionAnswers(free, answers(emptyList(), "a reply"))
        assertTrue(runCatching { validateQuestionAnswers(free, answers(listOf("yes"))) }.isFailure)
    }

    @Test fun continuedMustComeFromProjectionAndUsesOnlyDirectEndpoint() = runTest {
        val transport = InteractionTestTransport()
        val watcher = InteractionWatcher(transport, backgroundScope)
        try {
            runCurrent(); transport.muxes.single().ready(); runCurrent()
            val generation = watcher.connectionGeneration!!
            watcher.updateUserQuestions(generation, "agent", projection())
            runCurrent()
            val current = watcher.interactions.value.single() as PendingInteraction.Question
            assertEquals(PendingInteraction.QuestionPhase.CONTINUED, current.phase)
            watcher.submitAnswers(current.eventId, answers())
            assertEquals(listOf("userQuestions/answer"), transport.posts.map { it.first })
            val answer = jsonObject("id" to "q".json(), "selected" to JsonArray(listOf("yes".json())))
            assertNativeJson(jsonObject("agentId" to "agent".json(), "callId" to "call".json(),
                "answer" to jsonObject("answers" to JsonArray(listOf(answer)))), transport.posts.single().second)
            watcher.updateUserQuestions(generation, "agent", projection())
            assertTrue(runCatching { watcher.submitAnswers(current.eventId, answers()) }.isFailure)
            assertEquals(1, transport.posts.size)
            watcher.updateUserQuestions(generation, "agent", jsonObject("active" to JsonArray(emptyList())))
            runCurrent(); assertTrue(watcher.interactions.value.isEmpty())
            watcher.updateUserQuestions(generation - 1, "agent", projection())
            runCurrent(); assertTrue(watcher.interactions.value.isEmpty())
        } finally { watcher.close() }
    }

    @Test fun continuedProjectionCancelsInFlightResultAndOldWaterfallIsNeverResent() = runTest {
        val transport = InteractionTestTransport()
        transport.result = { awaitCancellation() }
        val watcher = InteractionWatcher(transport, backgroundScope)
        try {
            runCurrent(); transport.muxes.single().apply { ready(); waterfall(event = "user-questions/request", request = question()) }; runCurrent()
            val old = watcher.interactions.value.single().eventId
            val send = backgroundScope.async { runCatching { watcher.submitAnswers(old, answers()) } }
            runCurrent(); watcher.updateUserQuestions(watcher.connectionGeneration!!, "agent", projection()); runCurrent()
            assertTrue(send.await().isFailure)
            assertEquals(PendingInteraction.QuestionPhase.CONTINUED, (watcher.interactions.value.single() as PendingInteraction.Question).phase)
            assertTrue(runCatching { watcher.submitAnswers(old, answers()) }.isFailure)
            assertEquals(1, transport.posts.size)
        } finally { watcher.close() }
    }

    @Test fun fabricatedContinuedWaterfallIsInvalidAndDoesNotOfferAnAnswer() = runTest {
        val transport = InteractionTestTransport()
        val watcher = InteractionWatcher(transport, backgroundScope)
        try {
            runCurrent(); transport.muxes.single().apply {
                ready(); waterfall(event = "user-questions/request", request = JsonObject(question().fields + ("state" to "continued".json())))
            }; runCurrent()
            assertEquals("interaction.invalid", watcher.errors.value)
            assertTrue(watcher.interactions.value.isEmpty())
            assertTrue(transport.posts.isEmpty())
        } finally { watcher.close() }
    }

    @Test fun timedAttachUsesNativeRemainingTimeNeverExtendsAndExpiryIsNotContinued() = runTest {
        val sent = mutableListOf<JsonObject>()
        val mux = DshMux({ sent += JsonDocument.parse(it).root; true }, { _, _ -> })
        val events = DshEvents(mux.open(DSH_EVENTS_ENDPOINT, jsonObject()), backgroundScope, answer = {})
        val posts = mutableListOf<String>()
        val bridge = NativeInteractionBridge(events, mux, { endpoint, _ -> posts += endpoint; JsonBoolean(true) },
            backgroundScope, nowMillis = { testScheduler.currentTime })
        try {
            mux.ready(); mux.waterfall(event = "user-questions/request", request = question(timed = true)); runCurrent()
            val attach = sent.single { it.fields["endpoint"] == "userQuestions/attachWait".json() }
            assertNativeJson(jsonObject("args" to jsonObject("agentId" to "agent".json(), "callId" to "call".json())), attach.obj("payload"))
            mux.event(jsonObject("remainingMs" to 1_000L.json()), attach.string("streamId")); runCurrent()
            val key = bridge.interactions.value.single().eventId
            assertEquals(1_000L, (bridge.interactions.value.single() as PendingInteraction.Question).remainingMs)
            advanceTimeBy(400); runCurrent()
            assertEquals(600L, (bridge.interactions.value.single() as PendingInteraction.Question).remainingMs)
            mux.event(jsonObject("remainingMs" to 8_000L.json()), attach.string("streamId")); runCurrent()
            assertEquals(600L, (bridge.interactions.value.single() as PendingInteraction.Question).remainingMs)
            advanceTimeBy(600); runCurrent()
            assertTrue(bridge.interactions.value.isEmpty())
            assertTrue(runCatching { bridge.submitAnswers(key, answers()) }.isFailure)
            assertTrue(posts.isEmpty())
            assertEquals(1, sent.count { it.fields["endpoint"] == "userQuestions/attachWait".json() })
        } finally { bridge.close(); mux.close() }
    }
}
