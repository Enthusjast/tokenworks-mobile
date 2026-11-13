package cn.edu.ustc.tokenworks

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NativeInteractionBridgeTest {
    private class RecordingWriter(var accept: Boolean = true) {
        val frames = mutableListOf<String>()
        fun write(text: String): Boolean { if (!accept) return false; frames.add(text); return true }
    }
    private val posted = mutableListOf<Pair<String, JsonObject>>()
    private val answered = mutableListOf<DshEventResult>()
    private val writer = RecordingWriter()
    private val mux = DshMux(writer::write) { _, _ -> }
    private val stream = mux.open(DSH_EVENTS_ENDPOINT, jsonObject())
    private val posts = mutableListOf<Pair<String, JsonObject>>()

    private fun itemFrame(value: JsonObject) = JsonDocument.of(jsonObject(
        "type" to "item".json(), "streamId" to stream.id.json(), "value" to value,
    )).canonicalText()
    private fun readyFrame() = itemFrame(jsonObject(
        "type" to "ready".json(), "clientId" to "11111111-1111-4111-8111-111111111111".json(),
        "host" to jsonObject("home" to "/home/me".json()),
    ))
    private fun approvalFrame(eventId: String = "22222222-2222-4222-8222-222222222222") = itemFrame(jsonObject(
        "type" to "waterfall".json(), "event" to "approval/request".json(), "eventId" to eventId.json(),
        "agentId" to "33333333-3333-4333-8333-333333333333".json(),
        "request" to jsonObject("toolName" to "bash".json(), "callId" to "call-1".json(), "reason" to "run tests".json()),
    ))
    private fun questionFrame(eventId: String = "44444444-4444-4444-8444-444444444444") = itemFrame(jsonObject(
        "type" to "waterfall".json(), "event" to "user-questions/request".json(), "eventId" to eventId.json(),
        "agentId" to "33333333-3333-4333-8333-333333333333".json(),
        "request" to jsonObject(
            "callId" to "call-9".json(),
            "wait" to jsonObject("callId" to "call-9".json(), "timed" to JsonBoolean(true)),
            "questions" to JsonArray(listOf(jsonObject(
                "id" to "q1".json(), "question" to "Pick one".json(), "detail" to "detail".json(),
                "options" to JsonArray(listOf(jsonObject("label" to "Yes".json()), jsonObject("label" to "No".json()))),
                "multiSelect" to JsonBoolean(false),
            ))),
        ),
    ))

    private suspend fun bridge(refresh: Boolean = true): NativeInteractionBridge {
        mux.receive(readyFrame())
        val bridge = NativeInteractionBridge("55555555-5555-4555-8555-555555555555",
            DshEvents(stream, 5000, { answered.add(it) }), { endpoint, args -> posts.add(endpoint to args) })
        if (refresh) bridge.refresh()
        return bridge
    }

    private fun bridgeAwait(condition: () -> Boolean, timeoutMillis: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("condition not met in time")
            Thread.sleep(10)
        }
    }

    @Test fun approvalWaterfallBecomesApprovableInteraction() = runTest {
        val bridge = bridge()
        try {
            mux.receive(approvalFrame())
            bridgeAwait(condition = { bridge.refresh(); bridge.interactions.value.isNotEmpty() })
            val approval = bridge.interactions.value.filterIsInstance<PendingInteraction.Approval>().single()
            assertEquals("bash", approval.toolName)
            assertEquals("call-1", approval.callId)
            assertEquals("run tests", approval.reason)
            bridge.approve(approval.eventId)
            assertEquals("allowed-once", (DshStreams.result(answered.single()).obj("outcome").fields["value"] as JsonString).value)
            assertTrue(bridge.interactions.value.isEmpty())
            assertTrue(posts.isEmpty()) // approvals do not touch the answer endpoint
        } finally { bridge.close() }
    }

    @Test fun questionWaterfallPostsStructuredAnswerAndClosesWaterfall() = runTest {
        val bridge = bridge()
        try {
            mux.receive(questionFrame())
            bridgeAwait(condition = { bridge.refresh(); bridge.interactions.value.isNotEmpty() })
            val question = bridge.interactions.value.filterIsInstance<PendingInteraction.Question>().single()
            assertTrue(question.timed)
            assertEquals(listOf("Yes", "No"), question.questions.single().options)
            bridge.submitAnswers(question.eventId, listOf(InteractionBridge.AnsweredQuestion("q1", listOf("Yes"), null)))
            val (endpoint, args) = posts.single()
            assertEquals("userQuestions/answer", endpoint)
            assertEquals("33333333-3333-4333-8333-333333333333", args.string("agentId"))
            assertEquals("call-9", args.string("callId"))
            assertEquals("q1", args.obj("answer").array("answers").single().let { (it as JsonObject).string("id") })
            assertEquals("11111111-1111-4111-8111-111111111111", answered.single().clientId)
            assertTrue(bridge.interactions.value.isEmpty())
        } finally { bridge.close() }
    }

    @Test fun unknownShapesSurfaceAsErrorsWithoutKillingTheBridge() = runTest {
        val bridge = bridge()
        try {
            mux.receive(itemFrame(jsonObject(
                "type" to "waterfall".json(), "event" to "user-questions/request".json(),
                "eventId" to "66666666-6666-4666-8666-666666666666".json(),
                "agentId" to "33333333-3333-4333-8333-333333333333".json(),
                "request" to jsonObject("unexpected" to JsonBoolean(true)),
            )))
            bridgeAwait(condition = { bridge.refresh(); bridge.errors.value != null })
            assertTrue(bridge.interactions.value.isEmpty())
            // A well-formed frame afterwards still decodes.
            mux.receive(approvalFrame())
            bridgeAwait(condition = { bridge.refresh(); bridge.interactions.value.size == 1 })
            assertEquals(1, bridge.interactions.value.size)
        } finally { bridge.close() }
    }

    @Test fun answeredOrCancelledWaterfallsLeaveTheListOnNextRefresh() = runTest {
        val bridge = bridge()
        try {
            mux.receive(approvalFrame())
            bridgeAwait(condition = { bridge.refresh(); bridge.interactions.value.size == 1 })
            assertEquals(1, bridge.interactions.value.size)
            mux.receive(itemFrame(jsonObject("type" to "cancel".json(), "eventId" to "22222222-2222-4222-8222-222222222222".json())))
            bridgeAwait(condition = { bridge.refresh(); bridge.interactions.value.isEmpty() })
            assertTrue(bridge.interactions.value.isEmpty())
        } finally { bridge.close() }
    }
}
