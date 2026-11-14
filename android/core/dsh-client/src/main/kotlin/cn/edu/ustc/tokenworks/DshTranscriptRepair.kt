package cn.edu.ustc.tokenworks

/** A validated native page, not a second transport protocol. */
internal class DshTranscriptPage(val records: List<DshTranscriptRecord>, val hasMore: Boolean) {
    init {
        require(records.isNotEmpty())
        for (i in 1 until records.size) require(records[i].seq == records[i - 1].seq + 1)
        require(hasMore == (records.first().seq > 0))
    }
    companion object {
        fun decode(value: JsonValue): DshTranscriptPage {
            DshMethods.SessionPage.output.decode(value)
            val root = value as? JsonObject ?: throw ProtocolException()
            return DshTranscriptPage(root.array("records").map { entry ->
                DshTranscriptRecord.fromNative((entry as? JsonObject ?: throw ProtocolException()).obj("event"))
            }, SessionJson.boolean(root, "hasMore"))
        }
    }
}

internal class DshTranscriptRepairResult(val records: List<DshTranscriptRecord>, val conflict: Boolean)

/**
 * Continuation uses only session/page's fresh throughSeq and beforeSeq. Each page deliberately
 * includes the known boundary record so an ID wipe during paging cannot splice two incarnations.
 */
internal object DshTranscriptRepair {
    const val MAX_PAGES = 8
    const val MAX_REPAIR_RECORDS = 2048

    suspend fun resume(
        cached: DshTranscriptSnapshot,
        identity: DshTranscriptIdentity,
        openingCursor: Long,
        baseline: List<DshTranscriptRecord>,
        page: suspend (beforeSeq: Long) -> DshTranscriptPage,
    ): DshTranscriptRepairResult {
        fun conflict() = DshTranscriptRepairResult(baseline, true)
        if (cached.identity != identity || cached.cursor > openingCursor) return conflict()
        if (cached.records.isEmpty()) return DshTranscriptRepairResult(baseline, false)
        if (baseline.isEmpty()) return conflict()
        var fresh = baseline
        var pages = 0
        while (true) {
            val first = fresh.first().seq
            val common = cached.records.filter { it.seq >= first && it.seq <= openingCursor }
            if (common.isNotEmpty()) {
                for (old in common) {
                    val index = old.seq - first
                    if (index !in 0L until fresh.size.toLong() || !old.same(fresh[index.toInt()])) return conflict()
                }
                val prefix = cached.records.takeWhile { it.seq < first }
                return DshTranscriptRepairResult(prefix + fresh, false)
            }
            if (first <= cached.cursor || first == 0L || pages++ >= MAX_PAGES) return conflict()
            val older = page(first + 1).records
            if (older.last().seq != first || !older.last().same(fresh.first()) || older.size < 2 ||
                older.size + fresh.size - 1 > MAX_REPAIR_RECORDS) return conflict()
            fresh = older.dropLast(1) + fresh
        }
    }
}
