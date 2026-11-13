package cn.edu.ustc.tokenworks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReadMarksTest {
    private class MemoryStore : WatermarkStore {
        var values: Map<String, Long> = emptyMap()
        var writes = 0
        override fun read(): Map<String, Long> = values
        override fun write(values: Map<String, Long>) {
            this.values = values
            writes++
        }
    }

    @Test fun markingTheSeenSequenceClearsUnreadUntilTheSessionAdvances() {
        val store = MemoryStore()
        val watermarks = ReadWatermarks(store)
        assertTrue(watermarks.isUnread("s1", 10))
        watermarks.mark("s1", 10)
        assertFalse(watermarks.isUnread("s1", 10))
        assertTrue(watermarks.isUnread("s1", 11))
        assertEquals(mapOf("s1" to 10L), watermarks.marks())
    }

    @Test fun olderSequencesNeverRewindTheWatermark() {
        val store = MemoryStore()
        val watermarks = ReadWatermarks(store)
        watermarks.mark("s1", 10)
        watermarks.mark("s1", 7)
        assertEquals(10L, watermarks.marks()["s1"])
        assertEquals(1, store.writes)
    }

    @Test fun nonPositiveSequencesAreIgnoredAndDroppedWhenRead() {
        val store = MemoryStore()
        store.values = mapOf("stale" to 0L, "kept" to 4L)
        val watermarks = ReadWatermarks(store)
        assertEquals(mapOf("kept" to 4L), watermarks.marks())
        watermarks.mark("fresh", 0)
        assertEquals(mapOf("stale" to 0L, "kept" to 4L), store.values)
        assertFalse(watermarks.isUnread("unknown", 0))
    }

    @Test fun onlyTheNewestTwoHundredSessionsAreRetained() {
        val store = MemoryStore()
        val watermarks = ReadWatermarks(store)
        for (index in 1..ReadWatermarks.MAX_ENTRIES + 5) watermarks.mark("s$index", index.toLong())
        val marks = watermarks.marks()
        assertEquals(ReadWatermarks.MAX_ENTRIES, marks.size)
        assertFalse(marks.containsKey("s1"))
        assertTrue(marks.containsKey("s6"))
        assertEquals(ReadWatermarks.MAX_ENTRIES + 5L, marks["s${ReadWatermarks.MAX_ENTRIES + 5}"])
    }
}
