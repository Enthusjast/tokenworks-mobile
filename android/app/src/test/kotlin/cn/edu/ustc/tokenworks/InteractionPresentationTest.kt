package cn.edu.ustc.tokenworks

import org.junit.Assert.*
import org.junit.Test

class InteractionPresentationTest {
    private fun record(seq: Long, type: String, vararg fields: Pair<String, JsonValue>) = SessionRecord.parse(jsonObject(
        "seq" to seq.json(), "time" to 1_000L.json(), "type" to type.json(), "data" to jsonObject(*fields),
    ))

    @Test fun historyRequiresLivePairedNativeOutcomeAndNeverProjectsArbitraryToolText() {
        val window = SessionWindow("request").apply {
            accept(SessionContent("session.snapshot", "request", "sync", jsonObject(
                "cursor" to 5L.json(), "has_more" to JsonBoolean(false), "assistant" to jsonObject("revision" to 0L.json()),
                "records" to JsonArray(listOf(
                    record(0, "approval/decided", "id" to "missing-ask".json(), "outcome" to "allowed-once".json()).raw,
                    record(1, "approval/asked", "id" to "A".json(), "toolName" to "arbitrary-private-name".json(), "reason" to "secret".json()).raw,
                    record(2, "approval/decided", "id" to "A".json(), "outcome" to "rejected".json()).raw,
                    record(3, "approval/decided", "id" to "A".json(), "outcome" to "allowed-once".json()).raw,
                    record(4, "approval/asked", "id" to "B".json(), "toolName" to "bash".json()).raw,
                    record(5, "approval/decided", "id" to "B".json(), "outcome" to "allowed-once".json()).raw,
                )),
            )))
        }.snapshot()
        val state = SessionReadState(SessionReadPhase.READY, window)
        val history = interactionDecisions("current-session", state)
        assertEquals(listOf(5L, 2L), history.map { it.sequence })
        assertEquals(listOf(InteractionDecision.Outcome.ALLOWED, InteractionDecision.Outcome.REJECTED), history.map { it.outcome })
        assertEquals(listOf(ApprovalTool.SHELL, null), history.map { it.tool })
        assertTrue(history.all { it.sessionId == "current-session" })
        assertTrue(interactionDecisions("current-session", state.copy(stale = true)).isEmpty())
        assertTrue(interactionDecisions("current-session", state.copy(phase = SessionReadPhase.BACKOFF)).isEmpty())
    }

    @Test fun countdownRoundsPositivePartialSecondsUpWithoutOverflow() {
        assertEquals(listOf(0L, 1L, 1L, 2L, 1 + (Long.MAX_VALUE - 1) / 1_000),
            listOf(0L, 1L, 1_000L, 1_001L, Long.MAX_VALUE).map(::remainingSeconds))
    }
}
