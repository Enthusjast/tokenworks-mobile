package cn.edu.ustc.tokenworks

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.enthusjast.tokenworks.protocol.*
import io.github.enthusjast.tokenworks.session.*
import io.github.enthusjast.tokenworks.ui.TokenWorksTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatTimelineErrorTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val request = "11111111-1111-4111-8111-111111111111"
    private val sync = "22222222-2222-4222-8222-222222222222"
    private val detailStart = "公开合成错误的必要说明："
    private val detail = detailStart + "该工具未能读取公开样例文件；此信息不代表整个任务失败，也不能被当成执行成功。".repeat(18)
    private val detailEnd = "错误详情末尾：保留原始说明以供核对。"

    private fun block(kind: String, text: String) = jsonObject("type" to kind.json(), "text" to text.json())
    private fun call(seq: Long) = jsonObject(
        "native_seq" to seq.json(), "time" to 1000L.json(), "type" to "tool/call".json(),
        "data" to jsonObject("callId" to "call-$seq".json(), "name" to "read".json(), "arguments" to jsonObject()),
    )

    /** Real accepted records enter through the same bounded window as an active reader. */
    private fun errorTranscript(type: String): SessionTranscript {
        val error = jsonObject(
            "native_seq" to 5L.json(), "time" to 1000L.json(), "type" to type.json(),
            "data" to jsonObject("message" to jsonObject("isError" to JsonBoolean(true),
                "content" to JsonArray(listOf(block("reasoning", detail), block("text", detailEnd))))),
        )
        // Three calls on each side: only a run of three or more collapses into a process header.
        val records = listOf(call(0), call(1), call(2), error, call(6), call(7), call(8))
        return SessionWindow(request).apply {
            accept(SessionContent("session.snapshot", request, sync, jsonObject(
                "cursor" to 8L.json(), "has_more" to JsonBoolean(false), "assistant" to JsonNull,
                "records" to JsonArray(records),
            )))
            accept(SessionContent("session.checkpoint", request, sync,
                jsonObject("state" to "persisted".json(), "through_seq" to 8L.json())))
        }.snapshot()
    }

    private fun showError(type: String, marker: String) {
        val projection = projectChatTimeline(errorTranscript(type).items)
        rule.setContent {
            TokenWorksTheme {
                ChatTranscript(projection, rememberLazyListState(), emptySet(), true, {}, {},
                    onProcessExpansion = { _, _ -> fail("Reading an error must not require expanding a process") },
                    onQuote = null, onMessageAction = null, onPreviewMedia = null,
                    modifier = Modifier.fillMaxSize().testTag("error-timeline"))
            }
        }
        rule.onNodeWithTag("g0").assertHeightIsAtLeast(48.dp).assertIsDisplayed()
        rule.onNodeWithText(marker).assertIsDisplayed()
        // Even a long reasoning-kind error block is initially open, without touching either process header.
        rule.onNodeWithTag("error-timeline").performScrollToNode(hasText(detailStart, substring = true))
        rule.onNodeWithText(detailStart, substring = true).assertIsDisplayed()
        rule.onNodeWithTag("error-timeline").performScrollToNode(hasText(detailEnd))
        rule.onNodeWithText(detailEnd).assertIsDisplayed()
        rule.onNodeWithTag("error-timeline").performScrollToNode(hasTestTag("g6"))
        rule.onNodeWithTag("g6").assertIsDisplayed()
        rule.onNodeWithText("执行成功").assertDoesNotExist()
    }

    @Test fun acceptedToolErrorKeepsItsLongExplanationVisibleBetweenCollapsedProcesses() {
        showError("tool/result", "工具返回错误")
    }

    @Test fun acceptedAssistantErrorIsNotFoldedIntoReasoningOrPresentedAsTaskFailure() {
        showError("assistant/message", "回复异常")
    }

    @Test fun expandedChildrenKeepOriginalMessageKeysAndCallbacksAcrossHistoryPrepend() {
        val source = mutableStateOf(listOf(
            ConversationItem("event:18", ConversationRole.TOOL,
                listOf(DisplayBlock(BlockKind.TEXT, "更早公开工具结果")), false, true),
            ConversationItem("event:19", ConversationRole.TOOL,
                listOf(DisplayBlock(BlockKind.TEXT, "相邻公开工具结果")), false, true),
            ConversationItem("event:20", ConversationRole.TOOL,
                listOf(DisplayBlock(BlockKind.TEXT, "公开工具结果")), false, true),
        ))
        val quotes = mutableListOf<String>()
        val expansions = mutableListOf<List<String>>()
        rule.setContent {
            var expanded by remember { mutableStateOf(emptySet<String>()) }
            val projection = projectChatTimeline(source.value)
            TokenWorksTheme {
                ChatTranscript(projection, rememberLazyListState(), expanded, true, {}, {},
                    onProcessExpansion = { ids, open ->
                        expansions += ids
                        expanded = if (open) expanded + ids else expanded - ids.toSet()
                    }, onQuote = { quotes += it }, onMessageAction = null, onPreviewMedia = null,
                    modifier = Modifier.fillMaxSize().testTag("identity-timeline"))
            }
        }
        rule.onNodeWithText("公开工具结果").assertDoesNotExist()
        rule.onNodeWithTag("g18").performClick()
        rule.onNodeWithTag("conversation-message:event:20").assertExists()
        rule.runOnIdle {
            source.value = listOf(
                ConversationItem("event:15", ConversationRole.TOOL,
                    listOf(DisplayBlock(BlockKind.TEXT, "最早工具结果")), false, true),
                ConversationItem("event:16", ConversationRole.TOOL,
                    listOf(DisplayBlock(BlockKind.TEXT, "中间工具结果")), false, true),
                ConversationItem("event:17", ConversationRole.TOOL,
                    listOf(DisplayBlock(BlockKind.TEXT, "更早工具结果")), false, true),
            ) + source.value
        }
        rule.onNodeWithText("公开工具结果").assertIsDisplayed()
        rule.onNodeWithTag("g15").assertExists()
        rule.onNodeWithText("公开工具结果").performTouchInput { longClick() }
        rule.onNodeWithText("引用到本地草稿").performClick()
        rule.runOnIdle {
            assertEquals(listOf("公开工具结果"), quotes)
            assertEquals(listOf(listOf("event:18", "event:19", "event:20")), expansions)
        }
    }

    @Test fun readonlyTranscriptHasNoMessageOrProcessActionsSemanticsOrUserScroll() {
        val projection = projectChatTimeline(errorTranscript("tool/result").items)
        lateinit var list: LazyListState
        rule.setContent {
            list = rememberLazyListState()
            TokenWorksTheme {
                ChatTranscript(projection, list, setOf("event:0", "event:2"), false, {}, {},
                    onProcessExpansion = { _, _ -> fail("Background process action") },
                    onQuote = { fail("Background quote") }, onMessageAction = { fail("Background message action") },
                    onPreviewMedia = { fail("Background preview") }, modifier = Modifier.fillMaxSize())
            }
        }
        rule.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithText(detailStart, substring = true, useUnmergedTree = true).assertDoesNotExist()
        rule.onRoot().performTouchInput { swipeUp() }
        rule.runOnIdle {
            assertEquals(0, list.firstVisibleItemIndex)
            assertEquals(0, list.firstVisibleItemScrollOffset)
        }
    }
}
