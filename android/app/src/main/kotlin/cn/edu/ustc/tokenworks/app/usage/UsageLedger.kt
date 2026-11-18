package cn.edu.ustc.tokenworks

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

internal data class UsageBucket(val model: UsageModel?, val day: LocalDate?, val counts: UsageCounts)
internal data class UsageSession(val id: String, val title: String?, val counts: UsageCounts, val buckets: List<UsageBucket>)

/** The runtime's cumulative session total and the details recovered from confirmed transcript windows. */
internal class UsageLedger {
    private data class TimedEvent(val usage: SessionUsageEvent, val time: Long)
    private data class BucketKey(val model: UsageModel?, val day: LocalDate?)
    private val events = TreeMap<Long, TimedEvent>()
    private var snapshot: UsageSnapshot? = null
    private var observedFrom = -1L
    private var observedThrough = -1L

    fun replace(next: UsageSnapshot): Boolean {
        if (next.throughSeq < (snapshot?.throughSeq ?: -1L) || next == snapshot) return false
        snapshot = next
        return true
    }

    fun observe(transcript: SessionTranscript): Boolean {
        if (transcript.durableOnly || transcript.records.isEmpty()) return false
        val first = transcript.records.first().seq
        val through = minOf(transcript.usageThrough, transcript.records.last().seq)
        if (through < first || first == observedFrom && through == observedThrough) return false
        var changed = false
        fun add(start: Long, end: Long) {
            if (start > end) return
            for (index in (start - first).toInt()..(end - first).toInt()) {
                val record = transcript.records[index]
                val usage = record.usage ?: continue
                val event = TimedEvent(usage, record.time)
                if (events.put(record.seq, event) != event) changed = true
            }
        }
        if (observedThrough < first || observedFrom > through) add(first, through)
        else {
            add(first, minOf(through, observedFrom - 1))
            add(maxOf(first, observedThrough + 1), through)
        }
        observedFrom = first
        observedThrough = through
        return changed
    }

    fun summarize(id: String, title: String?, zone: ZoneId): UsageSession? {
        val totals = linkedMapOf<BucketKey, UsageCounts>()
        var total = UsageCounts()
        var knownAtCut = UsageCounts()
        val cut = snapshot?.throughSeq ?: -1L
        var pending: TimedEvent? = null
        var pendingAtCut: TimedEvent? = null

        fun flush() {
            val timed = pending ?: return
            val sample = timed.usage as SessionUsageEvent.Sample
            val bucket = BucketKey(sample.model, Instant.ofEpochMilli(timed.time).atZone(zone).toLocalDate())
            totals[bucket] = (totals[bucket] ?: UsageCounts()) + sample.counts
            total += sample.counts
            pendingAtCut?.let { knownAtCut += (it.usage as SessionUsageEvent.Sample).counts }
            pending = null
            pendingAtCut = null
        }
        for ((seq, event) in events) {
            val usage = event.usage
            val prior = pending?.usage
            val sameSlot = prior != null && prior.turn == usage.turn && prior.step == usage.step
            when (usage) {
                is SessionUsageEvent.Retry -> if (sameSlot) flush()
                is SessionUsageEvent.Sample -> {
                    if (!sameSlot) flush()
                    // One slot: the latest settlement replaces an earlier attempt prefix/sample.
                    pending = event
                    if (seq <= cut) pendingAtCut = event
                }
            }
        }
        flush()
        // A Host total is a replacement snapshot, not a second set of usage events.
        // Its unseen portion belongs to neither the latest model nor the reconnect day.
        val remainder = snapshot?.counts?.lessKnown(knownAtCut) ?: UsageCounts()
        total += remainder
        if (total.total == 0L) return null
        val buckets = totals.mapTo(ArrayList(totals.size + 1)) { (key, counts) -> UsageBucket(key.model, key.day, counts) }
        if (remainder.total > 0L) buckets += UsageBucket(null, null, remainder)
        return UsageSession(id, title, total, buckets)
    }
}
