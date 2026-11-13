package cn.edu.ustc.tokenworks

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DshEventsTest {
    private class RecordingWriter(var accept: Boolean = true) {
        val frames = mutableListOf<String>()
        fun write(text: String): Boolean {
            if (!accept) return false
            frames.add(text); return true
        }
    }
    private fun itemFrame(value: JsonObject) = JsonDocument.of(jsonObject(
        "type" to "item".json(), "streamId" to "1".json(), "value" to value,
    )).canonicalText()
    private fun readyFrame() = itemFrame(jsonObject(
        "type" to "ready".json(), "clientId" to "11111111-1111-4111-8111-111111111111".json(),
        "host" to jsonObject("home" to "/home/me".json()),
    ))
    private fun waterfallFrame(eventId: String = "22222222-2222-4222-8222-222222222222", event: String = "user-questions/request") =
        itemFrame(jsonObject(
            "type" to "waterfall".json(), "event" to event.json(), "eventId" to eventId.json(),
            "agentId" to "33333333-3333-4333-8333-333333333333".json(),
            "request" to jsonObject("prompt" to "Pick one".json()),
        ))
    private fun cancelFrame(eventId: String) = itemFrame(jsonObject("type" to "cancel".json(), "eventId" to eventId.json()))
    private fun errorValue(code: String) = jsonObject(
        "type" to "error".json(), "streamId" to "1".json(),
        "error" to jsonObject("code" to code.json(), "message" to "broken".json(), "details" to JsonNull),
    )

    @Test fun readyThenWaterfallBecomesAnswerableAndAnswerRemovesIt() = runTest {
        val writer = RecordingWriter()
        val mux = DshMux(writer::write) { _, _ -> }
        val stream = mux.open(DSH_EVENTS_ENDPOINT, jsonObject())
        mux.receive(readyFrame())
        val sent = mutableListOf<DshEventResult>()
        val events = DshEvents(stream, readyTimeoutMillis = 5000, answer = { sent.add(it) })
        try {
            assertEquals("11111111-1111-4111-8111-111111111111", events.clientIdOrNull())
            mux.receive(waterfallFrame())
            eventsAwait(condition = { events.open().isNotEmpty() })
            val open = events.open().single()
            assertEquals("user-questions/request", open.event)
            assertEquals("Pick one", (open.request.fields["prompt"] as JsonString).value)
            events.answer(open.eventId, DshEventOutcome.Result(DshOptional.Value("allowed-once".json())))
            eventsAwait(condition = { events.open().isEmpty() })
            assertEquals(1, sent.size)
            val outcome = JsonDocument.of(DshStreams.result(sent.single())).root.obj("outcome")
            assertEquals("result", outcome.string("kind"))
            assertEquals("allowed-once", (outcome.fields["value"] as JsonString).value)
        } finally { events.close() }
    }

    @Test fun cancelMovesTheWaterfallToCancellationsOnce() = runTest {
        val writer = RecordingWriter()
        val mux = DshMux(writer::write) { _, _ -> }
        val stream = mux.open(DSH_EVENTS_ENDPOINT, jsonObject())
        mux.receive(readyFrame())
        val sent = mutableListOf<DshEventResult>()
        val events = DshEvents(stream, 5000, { sent.add(it) })
        try {
            mux.receive(waterfallFrame())
            eventsAwait(condition = { events.open().isNotEmpty() })
            mux.receive(cancelFrame("22222222-2222-4222-8222-222222222222"))
            eventsAwait(condition = { events.open().isEmpty() })
            assertEquals(1, events.cancellations().size)
            assertEquals(0, events.cancellations().size)
            assertTrue(sent.isEmpty())
        } finally { events.close() }
    }

    @Test fun errorFrameBreaksConstructionWithTheServerError() = runTest {
        val writer = RecordingWriter()
        val mux = DshMux(writer::write) { _, _ -> }
        val stream = mux.open(DSH_EVENTS_ENDPOINT, jsonObject())
        assertThrows(DshFailure::class.java) {
            DshEvents(stream, 5000, {}, { })
        }
    }

    @Test fun unknownWaterfallRefusesAnswersAndReadyTimeoutFails() = runTest {
        val writer = RecordingWriter()
        val mux = DshMux(writer::write) { _, _ -> }
        val stream = mux.open(DSH_EVENTS_ENDPOINT, jsonObject())
        mux.receive(readyFrame())
        val events = DshEvents(stream, 5000, {})
        try {
            assertThrows(DshFailure::class.java) {
                events.answer("99999999-9999-4999-8999-999999999999", DshEventOutcome.Next)
            }
        } finally { events.close() }
        // A mux that never sends ready fails construction within the timeout.
        val silent = RecordingWriter()
        val quietMux = DshMux(silent::write) { _, _ -> }
        val quiet = quietMux.open(DSH_EVENTS_ENDPOINT, jsonObject())
        assertThrows(DshFailure::class.java) { DshEvents(quiet, 150, {}) }
    }

    @Test fun rejectEncodesTheRejectedOutcome() = runTest {
        val writer = RecordingWriter()
        val mux = DshMux(writer::write) { _, _ -> }
        val stream = mux.open(DSH_EVENTS_ENDPOINT, jsonObject())
        mux.receive(readyFrame())
        val sent = mutableListOf<DshEventResult>()
        val events = DshEvents(stream, 5000, { sent.add(it) })
        try {
            mux.receive(waterfallFrame(event = "approval/request"))
            eventsAwait(condition = { events.open().isNotEmpty() })
            events.reject("22222222-2222-4222-8222-222222222222", "device_declined", "user rejected")
            assertEquals(1, sent.size)
            val outcome = JsonDocument.of(DshStreams.result(sent.single())).root.obj("outcome")
            assertEquals("rejected", outcome.string("kind"))
            assertEquals("device_declined", outcome.obj("error").string("name"))
        } finally { events.close() }
    }

    private fun eventsAwait(condition: () -> Boolean, timeoutMillis: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("condition not met in time")
            Thread.sleep(10)
        }
    }
}
