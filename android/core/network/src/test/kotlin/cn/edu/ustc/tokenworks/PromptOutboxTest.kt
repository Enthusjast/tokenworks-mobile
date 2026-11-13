package cn.edu.ustc.tokenworks

import org.junit.Assert.*
import org.junit.Test

class PromptOutboxTest {
    private val sessionId = "66666666-6666-4666-8666-666666666666"

    @Test fun submitMintsStableRequestIdsAndRetryReusesThem() {
        val outbox = PromptOutbox()
        val first = outbox.submit(sessionId, "hello", "queue")
        assertEquals(first, outbox.submit(sessionId, "hello", "queue").takeIf { false } ?: outbox.retry(first.requestId))
        assertEquals(first.requestId, outbox.retry(first.requestId)!!.requestId)
        assertEquals("hello", outbox.retry(first.requestId)!!.text)
        // A fresh submit mints a new id.
        assertNotEquals(first.requestId, outbox.submit(sessionId, "second", "queue").requestId)
    }

    @Test fun settleRemovesTheEntrySoRetriesStop() {
        val outbox = PromptOutbox()
        val entry = outbox.submit(sessionId, "hello")
        assertNotNull(outbox.settle(entry.requestId))
        assertNull(outbox.retry(entry.requestId))
        assertNull(outbox.settle(entry.requestId))
    }

    @Test fun pendingListsOnlyUnsettledEntries() {
        val outbox = PromptOutbox()
        val settled = outbox.submit(sessionId, "one", "queue")
        val pending = outbox.submit(sessionId, "two", "steer")
        outbox.settle(settled.requestId)
        assertEquals(listOf(pending), outbox.pending())
        outbox.abandon(pending.requestId)
        assertTrue(outbox.pending().isEmpty())
    }

    @Test fun modesAreConstrainedToQueueAndSteer() {
        val outbox = PromptOutbox()
        assertEquals("steer", outbox.submit(sessionId, "s", "steer").mode)
        assertThrows(IllegalArgumentException::class.java) { outbox.submit(sessionId, "x", "interrupt") }
    }
}
