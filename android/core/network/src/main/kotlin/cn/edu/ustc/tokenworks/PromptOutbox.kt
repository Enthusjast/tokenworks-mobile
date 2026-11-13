package cn.edu.ustc.tokenworks

import java.util.UUID

/**
 * F-05: prompt delivery with a stable requestId. The id is minted once per
 * send attempt, kept with the text until the Host accepts the call, and
 * reused on retry - dsh treats a repeated requestId as the same message, so a
 * retry after a lost confirmation can never double-send.
 */
data class PromptOutboxEntry(val requestId: String, val sessionId: String, val text: String, val mode: String) {
    init {
        Contract.uuid(requestId)
        Contract.boundedText(sessionId, 128)
        Contract.boundedText(mode, 16)
        require(mode in setOf("queue", "steer")) { "prompt mode invalid" }
    }
}

class PromptOutbox {
    private val entries = linkedMapOf<String, PromptOutboxEntry>()

    /** Mints a fresh entry per send; the caller keeps it until the RPC settles. */
    fun submit(sessionId: String, text: String, mode: String = "queue"): PromptOutboxEntry =
        PromptOutboxEntry(UUID.randomUUID().toString(), sessionId, text, mode).also { entries[it.requestId] = it }

    /** Retry with the SAME requestId (dsh deduplicates); only unsettled entries retry. */
    fun retry(requestId: String): PromptOutboxEntry? = entries[requestId]

    fun settle(requestId: String): PromptOutboxEntry? = entries.remove(requestId)

    /** Entries whose outcome is unknown (send threw without a definite refusal). */
    fun pending(): List<PromptOutboxEntry> = entries.values.toList()

    fun abandon(requestId: String): PromptOutboxEntry? = entries.remove(requestId)

    fun clear() = entries.clear()
}
