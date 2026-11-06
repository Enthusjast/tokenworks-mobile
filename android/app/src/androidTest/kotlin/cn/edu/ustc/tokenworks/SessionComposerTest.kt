package cn.edu.ustc.tokenworks

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.enthusjast.tokenworks.command.*
import io.github.enthusjast.tokenworks.network.*
import io.github.enthusjast.tokenworks.protocol.*
import io.github.enthusjast.tokenworks.ui.TokenWorksTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionComposerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val id = "11111111-1111-4111-8111-111111111111"

    private fun state(online: Boolean = true): HomeState {
        val access = RuntimeDirectoryEntry.parse(jsonObject("id" to id.json(), "host_id" to id.json(), "organization_id" to id.json(), "protocol_version" to 1L.json(),
            "access" to jsonObject("role" to "operator".json(), "grant_version" to 1L.json(), "scope" to RelayScope(listOf("session.read", "session.list", "message.submit", "command.status"), emptyList(), listOf(id)).json())))
        val owner = CommandOwner(id, id, "a".repeat(64), id, id)
        return HomeState(HomePhase.READY, presentationOwner = owner, composer = MessageComposerState(ConversationResource(owner, id, id, id)),
            runtimes = listOf(RuntimeRow(id, id, "fixture", "电脑夹具", access)), selectedRuntimeId = id, selectedSessionId = id,
            reader = SessionReadState(if (online) SessionReadPhase.READY else SessionReadPhase.OFFLINE), outbox = CommandOutboxState(connected = online))
    }

    @Composable
    private fun ComposerFixture(initial: HomeState = state(), current: MutableState<HomeState>? = null,
        onBack: () -> Unit = {}, onSubmit: (MessageSubmission) -> Unit = {}, onPickFile: (String?) -> Unit = {}) {
        val live = current ?: remember { mutableStateOf(initial) }
        SyntheticSessionScreen(live.value, onBack, {}, {}, {}, onSubmit = onSubmit, onPickFile = onPickFile,
            onComposerChange = { live.value = live.value.copy(composer = it.copy(revision = it.revision + 1)) })
    }

    private fun openAttachmentMenu() = rule.onNodeWithContentDescription("添加附件与编辑选项").performClick()

    @Test fun attachmentMenuExplainsUnavailableFilesAndOnlyExplicitSelectionStartsPicker() {
        val current = mutableStateOf(state().copy(uploads = SessionUploadState()))
        var picks = 0
        rule.setContent { TokenWorksTheme { ComposerFixture(current = current, onPickFile = { assertNull(it); picks++ }) } }
        openAttachmentMenu()
        rule.onNodeWithText("选择文件").assertIsNotEnabled()
        rule.onNodeWithText("当前连接未开放文件上传，或文件服务不可用。").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, picks) }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.runOnIdle { current.value = current.value.copy(outbox = CommandOutboxState(connected = true, canAttachFiles = true)) }
        openAttachmentMenu()
        rule.runOnIdle { assertEquals(0, picks) }
        rule.onNodeWithText("选择文件").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, picks) }
        rule.onNodeWithText("选择文件").assertDoesNotExist()
    }

    @Test fun queueAndSteerAreExplicitAndOfflineActionOnlySavesToThisDevice() {
        var submitted: Triple<String, String, Boolean>? = null
        rule.setContent { TokenWorksTheme { ComposerFixture(state(false), onSubmit = { submitted = Triple(it.draft.mode, it.draft.value.text, it.sendNow) }) } }
        rule.onNodeWithContentDescription("保存待发送").assertIsNotEnabled()
        rule.onNodeWithText("消息内容").performTextInput("离线草稿🙂")
        rule.onNodeWithText("排队发送").performClick()
        rule.onNodeWithText("补充当前任务").performClick()
        rule.onNodeWithContentDescription("保存待发送").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(Triple("steer", "离线草稿🙂", false), submitted) }
    }

    @Test fun unsubmittedTextDoesNotEnterSavedInstanceState() {
        val restore = StateRestorationTester(rule)
        restore.setContent { TokenWorksTheme { ComposerFixture() } }
        rule.onNodeWithText("消息内容").performTextInput("UNSAVED_SENSITIVE_INPUT")
        rule.onNodeWithContentDescription("发送 · 排队").assertIsEnabled()
        restore.emulateSavedInstanceStateRestore()
        rule.onNodeWithContentDescription("发送 · 排队").assertIsNotEnabled()
    }

    @Test fun uncertainDeliveryNeverClaimsAgentQueueOrTaskCompletion() {
        val command = CommandIntent.message(id, id, id, "queue", "待核对正文")
        val record = CommandEvolution.begin(CommandEvolution.created(command, 1), 2)
        rule.setContent { TokenWorksTheme {
            SyntheticSessionScreen(state().copy(outbox = CommandOutboxState(listOf(record), true, error = "command.delivery_unconfirmed")), {}, {}, {}, {})
        } }
        rule.onNodeWithText("尚未核对发送结果，请查询原请求状态；不会自动重复发送。").assertIsDisplayed()
        rule.onNodeWithText("查看").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("结果待核对"))
        rule.onNodeWithText("结果待核对").assertIsDisplayed()
        rule.onNodeWithText("Agent 已入队").assertDoesNotExist()
        rule.onNodeWithText("命令已完成").assertDoesNotExist()
        rule.onNodeWithText("重新转交同一请求").assertDoesNotExist()
    }

    @Test fun savingARequestShowsProgressWithoutClearingTheInputAndModeExplainsItsEffect() {
        val current = mutableStateOf(state())
        var submitted: MessageSubmission? = null
        rule.setContent { TokenWorksTheme { ComposerFixture(current = current, onSubmit = { submitted = it }) } }
        rule.onNodeWithText("消息内容").performTextInput("保持中的输入")
        rule.runOnIdle { current.value = current.value.copy(submitting = true) }
        rule.onNodeWithText("正在保存请求…").assertIsDisplayed()
        rule.onNodeWithContentDescription("正在保存…").assertIsNotEnabled()
        rule.onNodeWithText("保持中的输入").assertExists()
        rule.onNodeWithText("排队发送").performClick()
        rule.onNodeWithText("等待轮到此消息时处理").assertIsDisplayed()
        rule.onNodeWithText("向电脑当前任务补充内容").assertIsDisplayed()
        rule.runOnIdle { assertNull(submitted) }
    }

    @Test fun anOversizedEditPreservesTheOriginalAndRequiresAValidEditBeforeSending() {
        var submitted: MessageSubmission? = null
        rule.setContent { TokenWorksTheme { ComposerFixture(onSubmit = { submitted = it }) } }
        rule.onNodeWithText("消息内容").performTextInput("保留🙂")
        rule.onNodeWithText("消息内容").performTextReplacement("z".repeat(65_537))
        assertEquals("保留🙂", rule.onNodeWithText("消息内容").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        rule.onNodeWithText("内容过长，本次输入未接收；原输入未改变。").assertIsDisplayed()
        rule.onNodeWithContentDescription("发送 · 排队").assertIsNotEnabled()
        rule.runOnIdle { assertNull(submitted) }
        rule.onNodeWithText("消息内容").performTextReplacement("修正后的请求")
        rule.onNodeWithText("内容过长，本次输入未接收；原输入未改变。").assertDoesNotExist()
        rule.onNodeWithContentDescription("发送 · 排队").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals("修正后的请求", submitted!!.draft.value.text); assertTrue(submitted!!.sendNow) }
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun fullscreenUsesTheSameDraftSelectionCompositionAndEnterNeverSubmits() {
        val base = state()
        val value = TextFieldValue("首行\n候选🙂", selection = TextRange(3, 5), composition = TextRange(3, 5))
        val current = mutableStateOf(base.copy(composer = base.composer!!.copy(value = value, mode = "steer", revision = 7)))
        var submitted: MessageSubmission? = null
        rule.setContent { TokenWorksTheme { ComposerFixture(current = current, onSubmit = { submitted = it }) } }
        val before = current.value.composer
        openAttachmentMenu()
        rule.onNodeWithText("展开编辑").performClick()
        rule.onNodeWithText("首行\n候选🙂").assertExists()
        rule.runOnIdle { assertEquals(before, current.value.composer); assertNull(submitted) }
        rule.onNodeWithText("收起编辑").performClick()
        rule.runOnIdle { assertEquals(before, current.value.composer) }
        rule.onNodeWithText("消息内容").performClick().performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { assertNull(submitted) }
        rule.onNodeWithText("消息内容").performTextReplacement("首行\n候选🙂\n下一行")
        rule.runOnIdle { assertTrue(current.value.composer!!.revision > 7); assertEquals("steer", current.value.composer!!.mode) }
        rule.onNodeWithContentDescription("发送 · 补充").performClick()
        rule.runOnIdle { assertEquals(current.value.composer, submitted!!.draft) }
    }

    @Test fun missingSelectedAttachmentBlocksSubmissionAndCannotBecomeAnEmptyDraftStop() {
        val current = mutableStateOf(state().copy(uploads = SessionUploadState(selected = setOf(id))))
        var stops = 0
        var submits = 0
        rule.setContent { TokenWorksTheme {
            val home = current.value
            SessionComposer(home, { submits++ }, home.composer!!,
                { current.value = home.copy(composer = it) }, {}, {}, {}, onExpand = {}, onResolveFailure = { _, _ -> },
                canCancel = true, onCancel = { stops++ })
        } }
        rule.onNodeWithContentDescription("中断当前任务").assertDoesNotExist()
        rule.onNodeWithContentDescription("发送 · 排队").assertIsNotEnabled()
        rule.onNodeWithText("所选附件尚未就绪，请在会话详情中核对或移出本次消息。").assertIsDisplayed()
        rule.runOnIdle { current.value = current.value.copy(uploads = SessionUploadState()) }
        rule.onNodeWithContentDescription("中断当前任务").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, stops); assertEquals(0, submits) }
        rule.onNodeWithText("消息内容").performTextInput("新请求")
        rule.onNodeWithContentDescription("中断当前任务").assertDoesNotExist()
        rule.onNodeWithContentDescription("发送 · 排队").assertIsEnabled()
        rule.runOnIdle { current.value = current.value.copy(reader = SessionReadState(SessionReadPhase.OFFLINE), outbox = CommandOutboxState(connected = false), composer = current.value.composer!!.copy(value = TextFieldValue())) }
        rule.onNodeWithContentDescription("中断当前任务").assertDoesNotExist()
        rule.onNodeWithContentDescription("保存待发送").assertIsNotEnabled()
    }

    @Test fun attachmentAnchorAlwaysOpensParentMenuEvenWhenUnavailableOrBusy() {
        val home = state(false).copy(uploads = SessionUploadState(busy = true))
        var opens = 0
        rule.setContent { TokenWorksTheme {
            SessionComposer(home, {}, home.composer!!, {}, { opens++ }, {}, {}, onExpand = {}, onResolveFailure = { _, _ -> })
        } }
        openAttachmentMenu()
        rule.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun failedOriginalRequiresConfirmationBeforeReplacingNewInputAndNeverSendsIt() {
        val drafts = ConversationDrafts()
        val base = state()
        val original = drafts.edit(drafts.open(base.composer!!.resource).copy(value = TextFieldValue("先前输入"), mode = "steer"))!!
        val newer = drafts.edit(original.copy(value = TextFieldValue("继续编辑的新稿"), mode = "queue"))!!
        val current = mutableStateOf(base.copy(composer = newer, failedInput = drafts.retainFailed(original)))
        var submitted = false
        rule.setContent { TokenWorksTheme {
            SyntheticSessionScreen(current.value, {}, {}, {}, {}, onSubmit = { submitted = true },
                onComposerChange = { candidate -> drafts.edit(candidate)?.let { current.value = current.value.copy(composer = it) } },
                onResolveFailedInput = { failed, restore -> drafts.resolveFailure(failed, restore)?.let { current.value = current.value.copy(composer = it, failedInput = null) } })
        } }
        rule.onNodeWithText("取回原输入").performScrollTo().performClick()
        rule.onNodeWithText("继续编辑的新稿").assertExists()
        rule.onNodeWithText("取消").performClick()
        rule.runOnIdle { assertEquals(newer, current.value.composer); assertFalse(submitted) }
        rule.onNodeWithText("取回原输入").performScrollTo().performClick()
        rule.onNodeWithText("确认取回").performClick()
        rule.onNodeWithText("先前输入").assertIsDisplayed()
        rule.onNodeWithContentDescription("发送 · 补充").assertIsEnabled()
        rule.runOnIdle { assertTrue(current.value.composer!!.revision > newer.revision); assertFalse(submitted); assertNull(current.value.failedInput) }
    }
}
