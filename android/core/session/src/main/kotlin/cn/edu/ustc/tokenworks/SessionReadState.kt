package cn.edu.ustc.tokenworks

import java.util.Collections

/** Phases of one native session follower. There is no offline replay: history comes from dsh. */
enum class SessionReadPhase { CONNECTING, SYNCING, READY, BACKOFF, AUTH_REQUIRED, REVOKED, ERROR, CLOSED }

/**
 * Display state of a native session window. `window` is the projected transcript;
 * `pageError` reports a failed older-history page without dropping what is shown.
 */
data class SessionReadState(
    val phase: SessionReadPhase,
    val window: SessionTranscript? = null,
    val error: String? = null,
    val loadingOlder: Boolean = false,
    val pageError: String? = null,
) {
    val items: List<ConversationItem> get() = window?.items ?: emptyList()
    val running get() = window?.taskRunning == true
    override fun toString() = "SessionReadState($phase, items=${items.size})"
}

class CachedTranscript(val epoch: String, val savedAt: Long, records: List<SessionRecord>) {
    val transcript: SessionTranscript
    init {
        Contract.uuid(epoch)
        if (savedAt < 0 || records.size > SessionWindow.MAX_RECORDS || records.sumOf { it.weight } > SessionWindow.MAX_RETAINED_WEIGHT) throw SessionDataException("data.corrupt")
        for (i in 1 until records.size) if (records[i].seq != records[i - 1].seq + 1) throw SessionDataException("data.corrupt")
        val copy = Collections.unmodifiableList(records.toList())
        val cursor = copy.lastOrNull()?.seq ?: -1
        transcript = SessionTranscript(copy, cursor, cursor, copy.firstOrNull()?.seq?.let { it > 0 } ?: false,
            cursor, "persisted", Collections.unmodifiableList(copy.mapNotNull { it.conversationItem(cursor) }), false, true)
    }
    override fun toString() = "CachedTranscript(records=${transcript.records.size})"
}
