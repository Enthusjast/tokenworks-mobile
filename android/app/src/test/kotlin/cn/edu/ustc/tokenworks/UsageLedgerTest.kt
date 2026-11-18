package cn.edu.ustc.tokenworks

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class UsageLedgerTest {
    private val model = UsageModel("provider", "model-a")
    private val dayOne = Instant.parse("2026-06-01T23:59:00Z").toEpochMilli()
    private val dayTwo = Instant.parse("2026-06-02T00:02:00Z").toEpochMilli()

    @Test fun snapshotReplacesAndUnseenUsageIsNotAssignedToLatestDayOrModel() {
        val ledger = UsageLedger()
        val detail = history(sample(9, dayTwo, 10, 4))
        assertTrue(ledger.replace(UsageSnapshot(9, UsageCounts(100, 40))))
        assertTrue(ledger.observe(detail))
        assertFalse(ledger.observe(history(sample(9, dayTwo, 10, 4))))
        assertFalse(ledger.replace(UsageSnapshot(9, UsageCounts(100, 40))))
        assertFalse(ledger.replace(UsageSnapshot(8, UsageCounts(90, 36))))
        val summary = ledger.summarize("session", "Session", ZoneOffset.UTC)!!
        assertEquals(UsageCounts(100, 40), summary.counts)
        assertEquals(listOf(
            UsageBucket(model, LocalDate.of(2026, 6, 2), UsageCounts(10, 4)),
            UsageBucket(null, null, UsageCounts(90, 36)),
        ), summary.buckets)
    }

    @Test fun sameSettlementSlotReplacesAcrossCutButRetryStartsNewBillableSlot() {
        val ledger = UsageLedger()
        assertTrue(ledger.replace(UsageSnapshot(4, UsageCounts(10, 5))))
        val initial = sample(4, dayOne, 10, 5)
        val correction = sample(5, dayOne, 9, 3)
        val retry = record(6, dayOne, "cursor", jsonObject(), SessionUsageEvent.project("llm/retry-started", position()))
        val retried = sample(7, dayTwo, 7, 2)
        assertTrue(ledger.observe(history(initial, correction, retry, retried)))
        val summary = ledger.summarize("session", null, ZoneOffset.UTC)!!
        assertEquals(UsageCounts(16, 5), summary.counts)
        assertEquals(listOf(
            UsageBucket(model, LocalDate.of(2026, 6, 1), UsageCounts(9, 3)),
            UsageBucket(model, LocalDate.of(2026, 6, 2), UsageCounts(7, 2)),
        ), summary.buckets)
        assertTrue(ledger.replace(UsageSnapshot(7, UsageCounts(16, 5))))
        assertEquals(summary, ledger.summarize("session", null, ZoneOffset.UTC))
    }

    @Test fun localRatePricesEveryDisjointBucket() {
        val rate = UsageRate(BigDecimal("2"), BigDecimal("3"), BigDecimal("4"), BigDecimal("5"))
        assertEquals(0, BigDecimal("0.000040").compareTo(rate.cost(UsageCounts(1, 2, 3, 4))))
        assertNull(UsageRate.parseAmount("1e3"))
        assertNull(UsageRate.parseAmount("-1"))
    }

    private fun position() = jsonObject("turn" to 1L.json(), "step" to 1L.json())
    private fun sample(seq: Long, time: Long, input: Long, output: Long): JsonObject {
        val message = jsonObject("content" to JsonArray(emptyList()))
        val source = jsonObject("kind" to "model".json(), "provider" to model.provider.json(), "model" to model.model.json())
        val data = position().with("message" to message.with("source" to source),
            "usage" to jsonObject("inputTokens" to input.json(), "outputTokens" to output.json()))
        return record(seq, time, "assistant/message", position().with("message" to message), SessionUsageEvent.project("assistant/message", data))
    }

    private fun record(seq: Long, time: Long, type: String, data: JsonObject, usage: JsonObject?) = jsonObject(
        "seq" to seq.json(), "time" to time.json(), "type" to type.json(), "data" to data,
    ).let { if (usage == null) it else it.with("usage" to usage) }

    private fun history(vararg records: JsonObject): SessionTranscript {
        val through = records.last().integer("seq", 0)
        val window = SessionWindow("sub")
        window.accept(SessionContent("session.snapshot", "sub", "sync", jsonObject(
            "cursor" to through.json(), "has_more" to JsonBoolean(records.first().integer("seq", 0) > 0),
            "records" to JsonArray(records.toList()), "assistant" to JsonNull,
        )))
        window.accept(SessionContent("session.checkpoint", "sub", "sync", jsonObject(
            "state" to "persisted".json(), "through_seq" to through.json(),
        )))
        return window.snapshot()
    }
}
