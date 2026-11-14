package cn.edu.ustc.tokenworks

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DshTranscriptRepairTest {
    @Test fun reconnectFillsGapThroughFreshCutAndMergesOnlyAfterExactOverlap() = runBlocking {
        val before = mutableListOf<Long>()
        val result = DshTranscriptRepair.resume(transcriptSnapshot(), transcriptIdentity, 12, transcriptRecords(10, 12)) { boundary ->
            before += boundary
            when (boundary) {
                11L -> DshTranscriptPage(transcriptRecords(7, 10), true)
                8L -> DshTranscriptPage(transcriptRecords(4, 7), true)
                else -> error("unexpected page")
            }
        }
        assertFalse(result.conflict)
        assertEquals(listOf(11L, 8L), before)
        assertEquals((0L..12L).toList(), result.records.map { it.seq })
    }

    @Test fun cursorRollbackAndReusedSessionHeaderNeverPageOrMixEpochs() = runBlocking {
        for ((identity, cursor) in listOf(transcriptIdentity to 3L,
            transcriptIdentity.copy(createdAt = transcriptIdentity.createdAt + 1) to 12L,
            transcriptIdentity.copy(version = transcriptIdentity.version + 1) to 12L,
            transcriptIdentity.copy(id = "reused-other-id") to 12L)) {
            val baseline = transcriptRecords(maxOf(0, cursor - 2), cursor)
            val result = DshTranscriptRepair.resume(transcriptSnapshot(), identity, cursor, baseline) { error("must not page") }
            assertTrue(result.conflict)
            assertSame(baseline, result.records)
        }
    }

    @Test fun everyOverlappingNativeEventMustMatchIncludingFieldsExcludedFromDisplay() = runBlocking {
        val altered = transcriptRecords(2, 7).toMutableList()
        altered[0] = DshTranscriptRecord.fromNative(transcriptEvent(2).with("data" to transcriptEvent(2).obj("data").with(
            "source" to jsonObject("kind" to "user".json(), "rpcId" to "different-native-identity".json()))))
        assertTrue(SessionJson.same(altered[0].record.raw, transcriptRecords(2, 2).single().record.raw))
        val result = DshTranscriptRepair.resume(transcriptSnapshot(), transcriptIdentity, 7, altered) { error("overlap already exists") }
        assertTrue(result.conflict)
        assertSame(altered, result.records)
    }

    @Test fun pageBoundaryMismatchDetectsWipeDuringRepairAndFallsBackToNativeBaseline() = runBlocking {
        val baseline = transcriptRecords(10, 12)
        val result = DshTranscriptRepair.resume(transcriptSnapshot(), transcriptIdentity, 12, baseline) {
            val page = transcriptRecords(7, 10).toMutableList()
            page[page.lastIndex] = DshTranscriptRecord.fromNative(transcriptEvent(10, "replacement epoch"))
            DshTranscriptPage(page, true)
        }
        assertTrue(result.conflict)
        assertSame(baseline, result.records)
    }

    @Test fun gappedReorderedAndNonProgressingPagesCannotBeAccepted() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) { DshTranscriptPage(listOf(transcriptRecords(3, 3).single(), transcriptRecords(5, 5).single()), true) }
        assertThrows(IllegalArgumentException::class.java) { DshTranscriptPage(transcriptRecords(3, 5).reversed(), true) }
        assertThrows(IllegalArgumentException::class.java) { DshTranscriptPage(transcriptRecords(0, 4), true) }
        val baseline = transcriptRecords(10, 12)
        val result = DshTranscriptRepair.resume(transcriptSnapshot(), transcriptIdentity, 12, baseline) {
            DshTranscriptPage(transcriptRecords(10, 10), true)
        }
        assertTrue(result.conflict)
        assertSame(baseline, result.records)
    }

    @Test fun repairHasFinitePageAndRecordBudgetsAndDoesNotHoldLiveReadingForever() = runBlocking {
        var count = 0
        val baseline = transcriptRecords(100, 102)
        val result = DshTranscriptRepair.resume(transcriptSnapshot(), transcriptIdentity, 102, baseline) { before ->
            count++
            DshTranscriptPage(transcriptRecords(before - 2, before - 1), true)
        }
        assertEquals(DshTranscriptRepair.MAX_PAGES, count)
        assertTrue(result.conflict)
        assertSame(baseline, result.records)
    }

    @Test fun malformedNativeFollowOrPageUnionsAreNotSilentlyProjectedAsCursorRecords() {
        assertThrows(ProtocolException::class.java) {
            DshMethods.SessionFollow.output.decode(jsonObject("type" to "invented-wire-frame".json()))
        }
        assertThrows(ProtocolException::class.java) {
            DshTranscriptPage.decode(jsonObject("records" to JsonArray(listOf(jsonObject("type" to "invented-record".json(), "event" to transcriptEvent(0)))), "hasMore" to JsonBoolean(false)))
        }
    }
}
