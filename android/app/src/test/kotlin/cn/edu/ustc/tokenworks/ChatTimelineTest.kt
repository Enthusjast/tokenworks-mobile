package cn.edu.ustc.tokenworks

import io.github.enthusjast.tokenworks.protocol.ExportManifest
import io.github.enthusjast.tokenworks.protocol.VisibleMediaReference
import io.github.enthusjast.tokenworks.protocol.json
import io.github.enthusjast.tokenworks.protocol.jsonObject
import io.github.enthusjast.tokenworks.session.BlockKind
import io.github.enthusjast.tokenworks.session.ConversationItem
import io.github.enthusjast.tokenworks.session.ConversationRole
import io.github.enthusjast.tokenworks.session.DisplayBlock
import org.junit.Assert.*
import org.junit.Test

class ChatTimelineTest {
    private fun message(id: String, role: ConversationRole = ConversationRole.TOOL,
        blocks: List<DisplayBlock> = listOf(DisplayBlock(BlockKind.TEXT, "result $id")),
        live: Boolean = false, interrupted: Boolean = false, error: Boolean = false,
        seq: Long = id.filter { it.isDigit() }.toLongOrNull() ?: -1L,
        turn: Long? = null, step: Long? = null, time: Long? = null) =
        ConversationItem(id, role, blocks, live, !live, interrupted, error,
            seq = seq, turn = turn, step = step, time = time)

    private fun reasoning(id: String, text: String = "reasoning $id", live: Boolean = false, turn: Long? = null,
        seq: Long = id.filter { it.isDigit() }.toLongOrNull() ?: -1L) =
        message(id, ConversationRole.ASSISTANT, listOf(DisplayBlock(BlockKind.REASONING, text)), live, turn = turn, seq = seq)

    private fun answer(id: String, turn: Long?, seq: Long = id.filter { it.isDigit() }.toLongOrNull() ?: -1L,
        time: Long? = null, step: Long? = null) =
        message(id, ConversationRole.ASSISTANT, listOf(DisplayBlock(BlockKind.TEXT, "answer $id")),
            turn = turn, seq = seq, time = time, step = step)

    @Test fun emptyWindowHasNoRowsOrSourceAnchors() {
        val projection = projectChatTimeline(emptyList())
        assertTrue(projection.rows.isEmpty())
        assertTrue(projection.sourceToGroup.isEmpty())
        assertTrue(projection.renderKeys(emptySet()).isEmpty())
        assertNull(projection.renderKeyForSource("missing", emptySet()))
        assertNull(projection.sourceIdForRenderKey("missing"))
    }

    @Test fun adjacentCallsResultsAndPureReasoningShareOneGroupWithOriginalChildren() {
        val call = message("event:0", blocks = listOf(DisplayBlock(BlockKind.TOOL, "", "shell", "call-1")))
        val result = message("event:1")
        val thinking = reasoning("event:2")
        val source = listOf(call, result, thinking)
        val projection = projectChatTimeline(source)
        val group = projection.rows.single() as ChatTimelineRow.ToolGroup
        assertEquals("g0", group.key)
        assertEquals(source, group.items)
        group.items.zip(source).forEach { (child, original) -> assertSame(original, child) }
        assertEquals(source.associate { it.id to group.key }, projection.sourceToGroup)
        assertEquals(listOf(group.key), projection.renderKeys(emptySet()))
        assertEquals(listOf(group.key) + source.map { it.id }, projection.renderKeys(setOf(result.id)))
        for (item in source) {
            assertEquals(group.key, projection.renderKeyForSource(item.id, emptySet()))
            assertEquals(item.id, projection.renderKeyForSource(item.id, setOf(thinking.id)))
            assertEquals(item.id, projection.sourceIdForRenderKey(item.id))
        }
        assertEquals(call.id, projection.sourceIdForRenderKey(group.key))
    }

    @Test fun twoAdjacentProcessRowsStayIndividualAndThreeBecomeOneGroup() {
        val pair = listOf(message("event:1"), message("event:2"))
        assertEquals(listOf("event:1", "event:2"), projectChatTimeline(pair).rows.map { it.key })
        val triple = listOf(message("event:1"), message("event:2"), message("event:3"))
        val rows = projectChatTimeline(triple).rows
        assertEquals(1, rows.size)
        assertEquals(listOf("event:1", "event:2", "event:3"), (rows.single() as ChatTimelineRow.ToolGroup).items.map { it.id })
    }

    @Test fun finishedTurnFoldsItsProcessAndKeepsTheAnswerAndFootnote() {
        val user = message("event:1", ConversationRole.USER, listOf(DisplayBlock(BlockKind.TEXT, "do it")))
        val first = message("event:2", turn = 1, step = 1, time = 1_000)
        val second = message("event:3", turn = 1, step = 2, time = 2_000)
        val third = reasoning("event:4", turn = 1, seq = 4).copyTime(3_000, 2)
        val body = answer("event:5", turn = 1, time = 4_000, step = 3)
        val projection = projectChatTimeline(listOf(user, first, second, third, body))
        assertEquals(4, projection.rows.size)
        assertSame(user, (projection.rows[0] as ChatTimelineRow.Message).item)
        val process = projection.rows[1] as ChatTimelineRow.TurnProcess
        assertEquals(listOf(first, second, third), process.items)
        assertSame(body, (projection.rows[2] as ChatTimelineRow.Message).item)
        val footnote = projection.rows[3] as ChatTimelineRow.TurnFootnote
        assertEquals(1L, footnote.ofTurn)
        assertEquals(3_000L, footnote.durationMillis)
        assertEquals(3, footnote.steps)
        assertEquals(2, footnote.toolCalls)
        assertEquals(listOf("event:1", "tp2", "event:5", "ft1"), projection.renderKeys(emptySet()))
        assertEquals(listOf("event:1", "tp2", "event:2", "event:3", "event:4", "event:5", "ft1"),
            projection.renderKeys(setOf(second.id)))
    }

    private fun ConversationItem.copyTime(time: Long, step: Long) = ConversationItem(
        id, role, blocks, live, persisted, interrupted, hasError, seq = seq, turn = turn, step = step, time = time,
    )

    @Test fun runningInterruptedOrErroredTurnsNeverFold() {
        fun turn(interrupted: Boolean = false, error: Boolean = false) = listOf(
            message("event:1", ConversationRole.USER, listOf(DisplayBlock(BlockKind.TEXT, "do it"))),
            message("event:2", turn = 1, step = 1, time = 1_000),
            message("event:3", turn = 1, step = 2, time = 2_000, interrupted = interrupted, error = error),
            reasoning("event:4", turn = 1).copyTime(2_000, 2),
            answer("event:5", turn = 1, time = 3_000, step = 3),
        )
        val streaming = projectChatTimeline(turn(), running = true)
        assertTrue(streaming.rows.none { it is ChatTimelineRow.TurnProcess })
        assertTrue(streaming.rows.none { it is ChatTimelineRow.TurnFootnote })
        assertEquals(1, streaming.rows.count { it is ChatTimelineRow.ToolGroup })
        // An errored or interrupted row keeps its own identity, which also breaks the run around it.
        for (projection in listOf(
            projectChatTimeline(turn(interrupted = true)),
            projectChatTimeline(turn(error = true)),
        )) {
            assertTrue(projection.rows.none { it is ChatTimelineRow.TurnProcess })
            assertTrue(projection.rows.none { it is ChatTimelineRow.TurnFootnote })
            assertTrue(projection.rows.none { it is ChatTimelineRow.ToolGroup })
        }
    }

    @Test fun foldNeedsAnAnswerAndOnlyProcessBeforeIt() {
        val tools = listOf(message("event:1", turn = 1), message("event:2", turn = 1), message("event:3", turn = 1))
        assertTrue(projectChatTimeline(tools).rows.none { it is ChatTimelineRow.TurnProcess })

        val lateTool = message("event:6", turn = 1)
        val afterAnswer = projectChatTimeline(tools + answer("event:4", turn = 1) + lateTool)
        assertTrue(afterAnswer.rows.none { it is ChatTimelineRow.TurnProcess })
        assertTrue(afterAnswer.rows.any { it is ChatTimelineRow.Message && it.item.id == "event:4" })
        assertEquals("event:6", (afterAnswer.rows.last() as ChatTimelineRow.Message).item.id)
    }

    @Test fun bodyUserNoticeOtherImageErrorAndInterruptionEachBreakBothSidesOfAGroup() {
        val image = VisibleMediaReference.parse(jsonObject(
            "v" to 1L.json(), "origin" to "session_attachment".json(), "native_seq" to 0L.json(),
            "part_index" to 0L.json(), "event_version_sha256" to "a".repeat(64).json(),
            "manifest" to ExportManifest("public.png", 123, "b".repeat(64)).json(),
            "media_type" to "image/png".json(), "width" to 8L.json(), "height" to 8L.json(),
        ))
        val boundaries = listOf(
            message("body", ConversationRole.ASSISTANT),
            message("user", ConversationRole.USER),
            message("notice", ConversationRole.NOTICE),
            message("other", blocks = listOf(DisplayBlock(BlockKind.OTHER, "unknown content"))),
            message("image", blocks = listOf(DisplayBlock(BlockKind.IMAGE, "", media = image))),
            message("mixed-image", blocks = listOf(DisplayBlock(BlockKind.TEXT, "result"), DisplayBlock(BlockKind.IMAGE, "", media = image))),
            message("mixed-assistant", ConversationRole.ASSISTANT,
                listOf(DisplayBlock(BlockKind.REASONING, "process"), DisplayBlock(BlockKind.TEXT, "answer"))),
            message("tool-error", error = true),
            message("assistant-error", ConversationRole.ASSISTANT,
                listOf(DisplayBlock(BlockKind.REASONING, "explanation")), error = true),
            message("interrupted-tool", interrupted = true),
            message("interrupted-assistant", ConversationRole.ASSISTANT,
                listOf(DisplayBlock(BlockKind.REASONING, "incomplete")), interrupted = true),
        )
        for (boundary in boundaries) {
            val before = listOf(message("event:10"), message("event:11"), message("event:12"))
            val after = listOf(reasoning("event:20"), message("event:21"), message("event:22"))
            val projection = projectChatTimeline(before + boundary + after)
            assertEquals(3, projection.rows.size)
            assertSame(boundary, (projection.rows[1] as ChatTimelineRow.Message).item)
            assertEquals(listOf("g10", boundary.id, "g20"), projection.renderKeys(emptySet()))
            assertFalse(projection.sourceToGroup.containsKey(boundary.id))
            assertEquals(boundary.id, projection.renderKeyForSource(boundary.id, emptySet()))
        }
    }

    @Test fun emptyBlocksAndEmptyTextOrReasoningRetainTheirOwnIdentityIncludingLiveAttempts() {
        val empty = listOf(
            message("no-blocks", blocks = emptyList()),
            message("empty-result", blocks = listOf(DisplayBlock(BlockKind.TEXT, ""))),
            reasoning("empty-reasoning", ""),
            reasoning("live:empty", "", live = true),
            message("live:no-blocks", ConversationRole.ASSISTANT, emptyList(), live = true),
            message("empty-tool", blocks = listOf(DisplayBlock(BlockKind.TOOL, ""))),
        )
        for (item in empty) {
            val projection = projectChatTimeline(listOf(item))
            assertSame(item, (projection.rows.single() as ChatTimelineRow.Message).item)
            assertEquals(item.id, projection.renderKeyForSource(item.id, emptySet()))
            assertFalse(projection.sourceToGroup.containsKey(item.id))
        }
    }

    @Test fun toolMetadataIsContentButErrorWordsNeverInferFailure() {
        val items = listOf(
            message("event:1", blocks = listOf(DisplayBlock(BlockKind.TOOL, "", "shell", "call-1"))),
            message("event:2", blocks = listOf(DisplayBlock(BlockKind.TEXT, "Error is a quoted word, not a status"))),
            message("event:3", blocks = listOf(DisplayBlock(BlockKind.TEXT, "output"),
                DisplayBlock(BlockKind.REASONING, "note"), DisplayBlock(BlockKind.TOOL, "", "read", "call-2"))),
        )
        assertEquals(items, (projectChatTimeline(items).rows.single() as ChatTimelineRow.ToolGroup).items)
    }

    @Test fun prependingHistoryMergesHeadersWithoutLosingExpandedSourcesOrChangingActionIdentity() {
        val tools = (19L..21L).map { message("event:$it", seq = it, turn = 1) }
        val selected = tools[1]
        val body = answer("event:22", turn = 1, time = 4_000)
        val original = projectChatTimeline(tools + body)
        val expandedIds = setOf(selected.id)
        val originalKey = original.renderKeyForSource(selected.id, expandedIds)!!
        assertEquals(2, original.renderKeys(expandedIds).indexOf(originalKey))

        val olderTools = (16L..18L).map { message("event:$it", seq = it, turn = 0) }
        val prepended = projectChatTimeline(olderTools + answer("event:18b", turn = 0, seq = 18) + tools + body)
        assertEquals("tp19", prepended.sourceToGroup[selected.id])
        assertEquals(selected.id, prepended.renderKeyForSource(selected.id, expandedIds))
        assertTrue(prepended.renderKeys(expandedIds).indexOf(selected.id) > 0)
        assertSame(selected, (prepended.rows.filterIsInstance<ChatTimelineRow.TurnProcess>().last()).items[1])
        assertEquals(selected.id, prepended.sourceIdForRenderKey(selected.id))

        val collapsedKey = prepended.renderKeyForSource(selected.id, emptySet())!!
        assertEquals("tp19", collapsedKey)
        assertEquals(3, prepended.renderKeys(emptySet()).indexOf(collapsedKey))
        assertEquals(selected.id, prepended.renderKeyForSource(selected.id, expandedIds))
        assertFalse(prepended.renderKeys(expandedIds).contains("event:16"))
        assertFalse(prepended.renderKeys(expandedIds).contains("event:17"))
    }

    @Test fun retiredSourcesCannotExpandAnotherGroupAndMissingAnchorsDoNotGetInvented() {
        val selected = message("event:1")
        val remaining = listOf(message("event:2"), message("event:3"))
        val old = projectChatTimeline(listOf(selected) + remaining)
        assertEquals(listOf("g1", selected.id, "event:2", "event:3"), old.renderKeys(setOf(selected.id)))
        val current = projectChatTimeline(remaining)
        assertEquals(listOf("event:2", "event:3"), current.renderKeys(setOf(selected.id)))
        assertNull(current.renderKeyForSource(selected.id, setOf(selected.id)))
        assertNull(current.sourceIdForRenderKey("g1"))
        assertNull(current.sourceIdForRenderKey(selected.id))
        assertEquals("event:2", current.sourceIdForRenderKey("event:2"))
    }
}
