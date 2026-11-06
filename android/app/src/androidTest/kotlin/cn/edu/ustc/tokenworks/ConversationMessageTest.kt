package cn.edu.ustc.tokenworks

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.enthusjast.tokenworks.session.BlockKind
import io.github.enthusjast.tokenworks.session.ConversationItem
import io.github.enthusjast.tokenworks.session.ConversationRole
import io.github.enthusjast.tokenworks.session.DisplayBlock
import io.github.enthusjast.tokenworks.ui.TokenWorksTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationMessageTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun openStreamingMenuCopiesAndQuotesCurrentExactBlockTextsWithoutMetadata() {
        val item = mutableStateOf(message(listOf(DisplayBlock(BlockKind.TEXT, "old"))))
        val copies = mutableListOf<String>()
        val quotes = mutableListOf<String>()
        rule.setContent {
            val system = LocalClipboardManager.current
            val clipboard = remember(system) { object : ClipboardManager by system {
                override fun setText(annotatedString: AnnotatedString) { copies += annotatedString.text }
            } }
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                TokenWorksTheme { ConversationMessage(item.value, onQuote = { quotes += it }) }
            }
        }
        rule.onNodeWithTag("conversation-message:message-1").assertExists()
        rule.onNodeWithContentDescription("助手").assertExists()
        rule.onNodeWithContentDescription("更多消息操作").assertHeightIsAtLeast(48.dp).performClick()
        val blocks = listOf(
            DisplayBlock(BlockKind.TEXT, " \n**最新**🙂  \n"),
            DisplayBlock(BlockKind.REASONING, " hidden \n"),
            DisplayBlock(BlockKind.TOOL, "", name = "metadata-name", callId = "metadata-call-id"),
            DisplayBlock(BlockKind.OTHER, " raw ")
        )
        rule.runOnIdle { item.value = message(blocks) }
        rule.onNodeWithText("复制消息原文").assertHeightIsAtLeast(48.dp).performClick()
        rule.runOnIdle {
            assertEquals(listOf(" \n**最新**🙂  \n\n\n hidden \n\n\n\n\n raw "), copies)
            assertEquals(emptyList<String>(), quotes)
        }
        rule.onNodeWithContentDescription("更多消息操作").performClick()
        val newest = listOf(DisplayBlock(BlockKind.TEXT, "\n newest  "), DisplayBlock(BlockKind.REASONING, "\n"))
        rule.runOnIdle { item.value = message(newest) }
        rule.onNodeWithText("引用到本地草稿").assertHeightIsAtLeast(48.dp).performClick()
        rule.runOnIdle {
            assertEquals(listOf("\n newest  \n\n\n"), quotes)
            assertEquals(1, copies.size)
        }
    }

    @Test fun longPressOpensTheSameMenuAndClipboardFailureIsVisibleWithoutQuotePermission() {
        rule.setContent {
            val system = LocalClipboardManager.current
            val unavailable = remember(system) { object : ClipboardManager by system {
                override fun setText(annotatedString: AnnotatedString) { throw SecurityException("clipboard unavailable") }
            } }
            CompositionLocalProvider(LocalClipboardManager provides unavailable) {
                TokenWorksTheme { ConversationMessage(message(listOf(DisplayBlock(BlockKind.TEXT, "消息正文"))), onQuote = null) }
            }
        }
        rule.onNodeWithText("消息正文").performTouchInput { longClick() }
        rule.onNodeWithText("引用到本地草稿").assertDoesNotExist()
        rule.onNodeWithText("复制消息原文").assertIsDisplayed().performClick()
        rule.onNodeWithText("复制失败，请通过“更多”重试。").assertIsDisplayed()
        rule.onNodeWithContentDescription("更多消息操作").performClick()
        rule.onNodeWithText("复制消息原文").assertIsDisplayed()
    }

    @Test fun disclosuresExposeExpandedStateAndRetainCurrentRealDetailsAcrossDeltas() {
        val item = mutableStateOf(message(listOf(
            DisplayBlock(BlockKind.REASONING, "reasoning detail"),
            DisplayBlock(BlockKind.TOOL, "tool detail", name = "shell", callId = "call-1")
        )))
        rule.setContent { TokenWorksTheme { ConversationMessage(item.value, onQuote = null) } }
        rule.onNodeWithText("reasoning detail").assertDoesNotExist()
        rule.onNodeWithText("tool detail").assertDoesNotExist()
        rule.onNodeWithText("思考过程").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals("已展开", rule.onNodeWithText("思考过程").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        rule.onNodeWithText("shell").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals("已展开", rule.onNodeWithText("shell").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        rule.onNodeWithText("调用标识：call-1").assertIsDisplayed()
        rule.onNodeWithText("tool detail").assertIsDisplayed()
        rule.runOnIdle { item.value = message(listOf(
            DisplayBlock(BlockKind.REASONING, "reasoning detail latest"),
            DisplayBlock(BlockKind.TOOL, "tool detail latest", name = "shell", callId = "call-1")
        )) }
        rule.onNodeWithText("reasoning detail latest").assertIsDisplayed()
        rule.onNodeWithText("tool detail latest").assertIsDisplayed()
        rule.onNodeWithText("shell").performClick()
        assertEquals("已收起", rule.onNodeWithText("shell").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        rule.onNodeWithText("tool detail latest").assertDoesNotExist()
    }

    @Test fun visibleCopyAlwaysUsesLatestOriginalText() {
        val item = mutableStateOf(message(listOf(DisplayBlock(BlockKind.TEXT, "**first**"))))
        val copies = mutableListOf<String>()
        rule.setContent {
            val system = LocalClipboardManager.current
            val clipboard = remember(system) { object : ClipboardManager by system {
                override fun setText(annotatedString: AnnotatedString) { copies += annotatedString.text }
            } }
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                TokenWorksTheme { ConversationMessage(item.value, onQuote = null) }
            }
        }
        rule.runOnIdle { item.value = message(listOf(DisplayBlock(BlockKind.TEXT, "**first** plus `latest`"))) }
        rule.onNodeWithText("复制").assertHeightIsAtLeast(48.dp).performClick()
        rule.runOnIdle { assertEquals(listOf("**first** plus `latest`"), copies) }
    }

    @Test fun readOnlyMessageAndStandaloneRichTextExposeNoActionsOrBackgroundText() {
        val interactive = mutableStateOf(true)
        val calls = mutableListOf<String>()
        val content = "[外部链接](https://example.invalid/background)\n\n```text\ncode payload\n```"
        rule.setContent {
            TokenWorksTheme {
                Column {
                    Box(Modifier.testTag("background-message")) {
                        ConversationMessage(message(listOf(DisplayBlock(BlockKind.TEXT, content))),
                            onQuote = { calls += "quote" }, onMessageAction = { calls += "action" },
                            onPreviewMedia = { calls += "media" }, interactive = interactive.value)
                    }
                    RichMessageText(content, interactive = false)
                }
            }
        }
        rule.onNodeWithContentDescription("更多消息操作").performClick()
        rule.runOnIdle { interactive.value = false }
        rule.onNodeWithText("复制消息原文").assertDoesNotExist()
        rule.onNodeWithText("引用到本地草稿").assertDoesNotExist()
        rule.onNodeWithContentDescription("更多消息操作").assertDoesNotExist()
        rule.onNodeWithText("code payload\n").assertDoesNotExist()
        rule.onAllNodes(SemanticsMatcher("interactive action") {
            it.config.contains(SemanticsActions.OnClick) || it.config.contains(SemanticsActions.OnLongClick) ||
                it.config.contains(SemanticsActions.SetSelection) || it.config.contains(SemanticsActions.CustomActions)
        }, useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithTag("background-message").performTouchInput { longClick() }
        rule.onNodeWithText("复制消息原文").assertDoesNotExist()
        rule.runOnIdle { assertEquals(emptyList<String>(), calls) }
    }

    @Test fun errorMessagesKeepToolDetailsVisibleWithoutClaimingWholeTaskFailure() {
        val item = mutableStateOf(ConversationItem(id = "error-tool", role = ConversationRole.TOOL,
            blocks = listOf(DisplayBlock(BlockKind.TOOL, "tool error details", name = "shell")),
            live = false, persisted = true, hasError = true))
        rule.setContent { TokenWorksTheme { ConversationMessage(item.value, onQuote = null) } }
        rule.onNodeWithText("工具返回错误").assertIsDisplayed()
        rule.onNodeWithText("tool error details").assertIsDisplayed()
        rule.onNodeWithText("shell").assertHasNoClickAction()
        rule.runOnIdle { item.value = item.value.withState(role = ConversationRole.ASSISTANT) }
        rule.onNodeWithText("回复异常").assertIsDisplayed()
        rule.onNodeWithText("tool error details").assertIsDisplayed()
        rule.onNodeWithText("工具返回错误").assertDoesNotExist()
    }

    @Test fun appendedFormattedSourceFinishesAndReplacementRemovesPreviousLinks() {
        val source = mutableStateOf("**first**\n\n[old link](https://example.invalid/old)")
        rule.setContent { TokenWorksTheme { RichMessageText(source.value) } }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("first").fetchSemanticsNodes().isNotEmpty() }
        rule.runOnIdle { source.value += "\n\n```text\nfinal-code\n```\n\n| a | b |\n| --- | --- |\n| final-cell | last |" }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("final-cell").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("final-code\n").assertExists()
        rule.runOnIdle { source.value = "**replacement**" }
        rule.onNodeWithText("old link").assertDoesNotExist()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("replacement").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions), useUnmergedTree = true)
            .assertCountEquals(0)
        rule.runOnIdle { source.value = "x" }
        rule.onNodeWithText("replacement").assertDoesNotExist()
        rule.onNodeWithText("x").assertExists()
    }

    @Test fun remoteActionsOnlyReceivePersistedOriginalMessageIdentity() {
        val item = mutableStateOf(message(listOf(DisplayBlock(BlockKind.TEXT, "body"))).withState(live = false, persisted = true))
        val actions = mutableListOf<String>()
        rule.setContent { TokenWorksTheme { ConversationMessage(item.value, onQuote = null, onMessageAction = { actions += it }) } }
        rule.onNodeWithContentDescription("更多消息操作").performClick()
        rule.runOnIdle { item.value = item.value.withState(live = true, persisted = false) }
        rule.onNodeWithText("预览、分享或创建分支").assertDoesNotExist()
        rule.runOnIdle { item.value = item.value.withState(live = false, persisted = true) }
        rule.onNodeWithText("预览、分享或创建分支").performClick()
        rule.runOnIdle { assertEquals(listOf("message-1"), actions) }
    }

    private fun ConversationItem.withState(role: ConversationRole = this.role, live: Boolean = this.live,
        persisted: Boolean = this.persisted) = ConversationItem(id, role, blocks, live, persisted, interrupted, hasError)

    private fun message(blocks: List<DisplayBlock>) = ConversationItem(
        id = "message-1", role = ConversationRole.ASSISTANT, blocks = blocks, live = true, persisted = false
    )
}
