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
        live: Boolean = false, interrupted: Boolean = false, error: Boolean = false) =
        ConversationItem(id, role, blocks, live, !live, interrupted, error)

    private fun reasoning(id: String, text: String = "reasoning $id", live: Boolean = false) =
        message(id, ConversationRole.ASSISTANT, listOf(DisplayBlock(BlockKind.REASONING, text)), live)

    @Test fun emptyWindowHasNoRowsOrSourceAnchors() {
        val projection = projectChatTimeline(emptyList())
        assertTrue(projection.rows.isEmpty())
        assertTrue(projection.sourceToGroup.isEmpty())
        assertTrue(projection.renderKeys(emptySet()).isEmpty())
        assertNull(projection.renderKeyForSource("missing", emptySet()))
        assertNull(projection.sourceIdForRenderKey("missing"))
    }

    @Test fun adjacentCallsResultsAndPureReasoningShareOneNeutralGroupWithOriginalChildren() {
        val call = message("event:0", blocks = listOf(DisplayBlock(BlockKind.TOOL, "", "shell", "call-1")))
        val result = message("event:1")
        val thinking = reasoning("live:attempt", live = true)
        val source = listOf(call, result, thinking)
        val projection = projectChatTimeline(source)
        val group = projection.rows.single() as ChatTimelineRow.ProcessGroup
        assertEquals("process:event:0", group.key)
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
            val before = message("before")
            val after = reasoning("after")
            val projection = projectChatTimeline(listOf(before, boundary, after))
            assertEquals(boundary.id, 3, projection.rows.size)
            assertSame(boundary, (projection.rows[1] as ChatTimelineRow.Message).item)
            assertEquals(listOf("process:before", boundary.id, "process:after"), projection.renderKeys(emptySet()))
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
            val projection = projectChatTimeline(listOf(reasoning("before"), item, reasoning("after")))
            assertSame(item, (projection.rows[1] as ChatTimelineRow.Message).item)
            assertEquals(item.id, projection.renderKeyForSource(item.id, emptySet()))
            assertFalse(projection.sourceToGroup.containsKey(item.id))
        }
    }

    @Test fun toolMetadataIsContentButErrorWordsNeverInferFailure() {
        val items = listOf(
            message("call", blocks = listOf(DisplayBlock(BlockKind.TOOL, "", "shell", "call-1"))),
            message("result", blocks = listOf(DisplayBlock(BlockKind.TEXT, "Error is a quoted word, not a status"))),
            message("tool-process", blocks = listOf(DisplayBlock(BlockKind.TEXT, "output"),
                DisplayBlock(BlockKind.REASONING, "note"), DisplayBlock(BlockKind.TOOL, "", "read", "call-2"))),
        )
        assertEquals(items, (projectChatTimeline(items).rows.single() as ChatTimelineRow.ProcessGroup).items)
    }

    @Test fun prependingHistoryMergesHeadersWithoutLosingExpandedSourcesOrChangingActionIdentity() {
        val first = reasoning("event:20")
        val selected = message("event:21")
        val body = message("event:22", ConversationRole.ASSISTANT)
        val original = projectChatTimeline(listOf(first, selected, body))
        val expandedIds = setOf(selected.id)
        val originalKey = original.renderKeyForSource(selected.id, expandedIds)!!
        assertEquals(2, original.renderKeys(expandedIds).indexOf(originalKey))

        val earlier = reasoning("event:19")
        val prepended = projectChatTimeline(listOf(earlier, first, selected, body))
        assertEquals("process:event:19", prepended.sourceToGroup[selected.id])
        assertEquals(selected.id, prepended.renderKeyForSource(selected.id, expandedIds))
        assertEquals(3, prepended.renderKeys(expandedIds).indexOf(selected.id))
        assertSame(selected, (prepended.rows.first() as ChatTimelineRow.ProcessGroup).items[2])
        assertEquals(selected.id, prepended.sourceIdForRenderKey(selected.id))

        val collapsedKey = prepended.renderKeyForSource(selected.id, emptySet())!!
        assertEquals("process:event:19", collapsedKey)
        assertEquals(0, prepended.renderKeys(emptySet()).indexOf(collapsedKey))
        assertEquals(selected.id, prepended.renderKeyForSource(selected.id, expandedIds))
        assertFalse(prepended.renderKeys(expandedIds).contains("process:event:20"))
    }

    @Test fun retiredSourcesCannotExpandAnotherGroupAndMissingAnchorsDoNotGetInvented() {
        val selected = message("event:1")
        val remaining = message("event:2")
        val old = projectChatTimeline(listOf(selected, remaining))
        assertEquals(listOf("process:event:1", selected.id, remaining.id), old.renderKeys(setOf(selected.id)))
        val current = projectChatTimeline(listOf(remaining))
        assertEquals(listOf("process:event:2"), current.renderKeys(setOf(selected.id)))
        assertNull(current.renderKeyForSource(selected.id, setOf(selected.id)))
        assertNull(current.sourceIdForRenderKey("process:event:1"))
        assertNull(current.sourceIdForRenderKey(selected.id))
        assertEquals(remaining.id, current.sourceIdForRenderKey("process:event:2"))
    }
}
