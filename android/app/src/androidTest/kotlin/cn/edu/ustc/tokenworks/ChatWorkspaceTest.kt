package cn.edu.ustc.tokenworks

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.enthusjast.tokenworks.command.*
import io.github.enthusjast.tokenworks.network.*
import io.github.enthusjast.tokenworks.protocol.*
import io.github.enthusjast.tokenworks.session.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Public native UI proof for API images whose platform input APIs are incompatible with Espresso. */
@RunWith(AndroidJUnit4::class)
class ChatWorkspaceTest {
    private val runtime = "11111111-1111-4111-8111-111111111111"
    private val session = "22222222-2222-4222-8222-222222222222"
    private val request = "33333333-3333-4333-8333-333333333333"
    private val epoch = "44444444-4444-4444-8444-444444444444"
    private val workspace = "55555555-5555-4555-8555-555555555555"
    private val nextEpoch = "66666666-6666-4666-8666-666666666666"
    private val owner = CommandOwner(runtime, runtime, "a".repeat(64), runtime, runtime)
    private val title = "公开合成会话"
    private val body = "这是公开合成正文，不来自真实账号。"

    private fun record(seq: Long, type: String, data: JsonObject) = jsonObject(
        "native_seq" to seq.json(), "time" to 1000L.json(), "type" to type.json(),
        "surface_op" to "append".json(), "data" to data)
    private fun text(text: String, kind: String = "text") = jsonObject("type" to kind.json(), "text" to text.json())
    private fun user(seq: Long, value: String) = record(seq, "user/message",
        jsonObject("content" to JsonArray(listOf(text(value)))))
    private fun assistant(seq: Long, value: String) = record(seq, "assistant/message",
        jsonObject("message" to jsonObject("content" to JsonArray(listOf(text(value))))))
    private fun tool(seq: Long) = record(seq, "tool/call", jsonObject("callId" to "public-call-$seq".json(),
        "name" to "public-read-$seq".json(), "arguments" to jsonObject()))
    private fun window(records: List<JsonObject>, more: Boolean = false) = SessionWindow(request).apply {
        accept(SessionContent("session.snapshot", request, session, jsonObject(
            "cursor" to records.last().integer("native_seq", 0).json(), "has_more" to JsonBoolean(more),
            "assistant" to jsonObject("revision" to 0L.json()), "records" to JsonArray(records))))
        accept(SessionContent("session.checkpoint", request, session,
            jsonObject("state" to "persisted".json(), "through_seq" to records.last().integer("native_seq", 0).json())))
    }
    private fun grant(metadata: Boolean = true) = RuntimeDirectoryEntry.parse(jsonObject(
        "id" to runtime.json(), "host_id" to runtime.json(), "organization_id" to runtime.json(), "protocol_version" to 1L.json(),
        "access" to jsonObject("role" to "operator".json(), "grant_version" to 1L.json(), "scope" to RelayScope(
            listOf("session.list", "session.read", "message.submit", "command.status", "session.cancel", "session.create") +
                if (metadata) listOf("session.metadata.read") else emptyList(), listOf(workspace), listOf(session)).json())))
    private fun initial(transcript: SessionTranscript = window(listOf(assistant(0, body))).snapshot(), metadata: Boolean = true): HomeState {
        val named = SessionDirectoryEntry.parse(jsonObject("session_id" to session.json(), "workspace_id" to workspace.json(),
            "state" to "available".json(), "last_persisted_seq" to 0L.json(), "updated_at" to "2026-10-30T12:00:00Z".json(),
            "metadata" to jsonObject("v" to 1L.json(), "title" to title.json(), "workspace_title" to "公开合成工作区".json(),
                "title_source" to "cached".json(), "title_as_of_seq" to 0L.json())), namesProjection = true)
        return HomeState(HomePhase.READY, presentationOwner = owner,
            runtimes = listOf(RuntimeRow(runtime, runtime, "0.2.1", "公开合成电脑", grant(metadata))),
            selectedRuntimeId = runtime, selectedSessionId = session, sessions = listOf(named),
            reader = SessionReadState(SessionReadPhase.READY, transcript, runtimeEpoch = epoch),
            composer = MessageComposerState(ConversationResource(owner, runtime, runtime, session)),
            outbox = CommandOutboxState(connected = true))
    }
    private fun running() = window(listOf(user(0, "公开历史消息"),
        record(1, "turn/start", jsonObject("turn" to 1L.json())), tool(2))).snapshot()
    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun NativeChatFixture.more() = click(label(R.string.chat_task_more))
    private fun NativeChatFixture.closeDetails() {
        val close = nodes().firstOrNull { it.label("返回消息") || it.label(label(R.string.workspace_panel_close)) ||
            it.label(label(R.string.workspace_panel_back)) }
        if (close != null) click(if (close.description.isNotEmpty()) close.description else close.text) else back()
        await("active transcript after closing details") { nodes -> nodes.any { it.tag("chat-list") } &&
            nodes.none { it.tag("workspace-panel-side") || it.tag("workspace-panel-sheet") || it.tag("workspace-panel-page") } }
    }

    @Test fun nativeMenuHidesBackgroundAndSearchDoesNotReadOrSubmit() {
        val current = mutableStateOf(initial())
        var refreshes = 0
        var pages = 0
        var submissions = 0
        var claims = 0
        NativeChatFixture.launch().use { ui ->
            ui.content { SyntheticSessionScreen(current.value, {}, { refreshes++ }, { pages++ }, {},
                onSubmit = { submissions++ }, onClaim = { claims++ }, viewport = ui.viewport) }
            ui.await("authorized title") { nodes -> nodes.any { it.contains(title) } }
            ui.click(label(R.string.workspace_open_navigation))
            ui.await("open directory drawer") { nodes -> nodes.any { it.tag("directory-search") } }
            ui.absent { it.tag("chat-list") || it.tag("conversation-message:event:0") || it.contains(body) }
            ui.requireNode { it.label(label(R.string.workspace_close_navigation)) }
            ui.setText("directory-search", "不会匹配的公开合成查询")
            ui.await("directory query applied") { nodes -> nodes.none { it.tag("session-$session") } }
            ui.setText("directory-search", "")
            ui.await("same authorized row restored") { nodes -> nodes.any { it.tag("session-$session") } }
            assertEquals(1, ui.nodes().count { it.tag("session-$session") })
            ui.capture("native-chat-drawer-light-360-f1")
            ui.click(label(R.string.workspace_close_navigation))
            ui.await("drawer closed") { nodes -> nodes.any { it.tag("chat-list") } && nodes.none { it.tag("directory-search") } }
            ui.click(label(R.string.workspace_open_navigation))
            ui.await("drawer reopened") { nodes -> nodes.any { it.tag("directory-search") } }
            ui.back()
            ui.await("native back closes drawer before chat") { nodes -> nodes.any { it.tag("chat-list") } &&
                nodes.none { it.tag("directory-search") } }
            assertEquals(0, refreshes)
            assertEquals(0, pages)
            assertEquals(0, submissions)
            assertEquals(0, claims)
        }
    }

    @Test fun nativeAuthorizedNamesRevokeImmediatelyAndDetailsRetainFullResourceIds() {
        val current = mutableStateOf(initial())
        NativeChatFixture.launch().use { ui ->
            ui.content { SyntheticSessionScreen(current.value, {}, {}, {}, {}, viewport = ui.viewport) }
            ui.await("authorized name") { nodes -> nodes.any { it.contains(title) } }
            ui.more()
            ui.await("task menu") { nodes -> nodes.any { it.label("会话详情") } }
            ui.click("会话详情")
            ui.await("full authorized resource identity") { nodes -> nodes.any { it.contains("会话：$session") &&
                it.contains("电脑：$runtime") && it.contains("运行环境：$runtime") } }
            ui.closeDetails()
            ui.draw { current.value = current.value.copy(runtimes = listOf(current.value.runtimes.single().copy(access = grant(false)))) }
            ui.await("revoked metadata is no longer exposed") { nodes -> nodes.none { it.contains(title) } }
            ui.absent { it.contains("公开合成工作区") }
            ui.more()
            ui.click("会话详情")
            ui.await("ID remains available without metadata names") { nodes -> nodes.any { it.contains("会话：$session") } }
            ui.closeDetails()
            ui.capture("native-chat-metadata-revoked")
        }
    }

    @Test fun nativeOriginalRequestAndStopConfirmationNeverRetargetAnotherEpoch() {
        val original = PendingCommand(CommandIntent.message(request, runtime, session, "queue", "公开原请求"),
            CommandDelivery.PENDING, null, 0, 1, 100, 100, null, false, false, null)
        val current = mutableStateOf(initial(running()).copy(outbox = CommandOutboxState(listOf(original), connected = true)))
        val cancelled = mutableListOf<String>()
        NativeChatFixture.launch().use { ui ->
            ui.content { SyntheticSessionScreen(current.value, {}, {}, {}, {}, onCancelSession = { cancelled += it }, viewport = ui.viewport) }
            ui.await("fixed original request entry") { nodes -> nodes.any { it.label("查看发送记录") } }
            ui.click("查看发送记录")
            ui.await("original request identity") { nodes -> nodes.any { it.contains("原请求：$request") } }
            ui.closeDetails()
            ui.click(label(R.string.workspace_stop_task))
            ui.await("epoch-bound stop confirmation") { nodes -> nodes.any { it.label(label(R.string.conversation_cancel_confirm)) } }
            assertTrue(cancelled.isEmpty())
            ui.draw { current.value = current.value.copy(reader = current.value.reader!!.copy(runtimeEpoch = nextEpoch)) }
            ui.await("old epoch confirmation retired") { nodes -> nodes.none { it.label(label(R.string.conversation_cancel_confirm)) } }
            ui.draw { current.value = current.value.copy(reader = current.value.reader!!.copy(runtimeEpoch = epoch)) }
            ui.absent { it.label(label(R.string.conversation_cancel_confirm)) }
            assertTrue(cancelled.isEmpty())
            ui.click(label(R.string.workspace_stop_task))
            ui.await("new explicit confirmation") { nodes -> nodes.any { it.label(label(R.string.conversation_cancel_confirm)) } }
            ui.click(label(R.string.conversation_cancel_confirm))
            ui.await("stop dialog dismissed") { nodes -> nodes.none { it.label(label(R.string.conversation_cancel_confirm)) } }
            assertEquals(listOf(epoch), cancelled)
            ui.draw { current.value = current.value.copy(outbox = current.value.outbox!!.copy(connected = false)) }
            ui.await("offline removes stop authority") { nodes -> nodes.none { it.label(label(R.string.workspace_stop_task)) } }
            assertEquals(listOf(epoch), cancelled)
        }
    }

    @Test fun nativeEditorSharesDraftAndSystemBackClosesImeBeforeLeaving() {
        val original = initial()
        val input = androidx.compose.ui.text.input.TextFieldValue("公开候选草稿",
            selection = androidx.compose.ui.text.TextRange(2))
        val current = mutableStateOf(original.copy(composer = original.composer!!.copy(value = input, revision = 7)))
        val submissions = mutableListOf<MessageSubmission>()
        var backs = 0
        NativeChatFixture.launch().use { ui ->
            ui.content { SyntheticSessionScreen(current.value, { backs++ }, {}, {}, {},
                onSubmit = { submissions += it },
                onComposerChange = { current.value = current.value.copy(composer = it.copy(revision = it.revision + 1)) },
                viewport = ui.viewport) }
            val before = current.value.composer!!
            fun unchanged() {
                val now = current.value.composer!!
                assertEquals(before.value.text, now.value.text)
                assertEquals(before.value.selection, now.value.selection)
                assertEquals(before.mode, now.mode)
                assertEquals(before.resource, now.resource)
            }
            ui.click(label(R.string.workspace_attachment_menu))
            ui.await("editor options") { nodes -> nodes.any { it.label(label(R.string.chat_editor_expand)) } }
            ui.click(label(R.string.chat_editor_expand))
            ui.await("fullscreen editor") { nodes -> nodes.any { it.label(label(R.string.chat_editor_compact)) } }
            unchanged()
            ui.click(label(R.string.chat_editor_compact))
            ui.await("compact editor restored") { nodes -> nodes.any { it.tag("chat-list") } }
            unchanged()
            ui.act(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, { it.editable })
            ui.await("actual system keyboard visible") { ui.imeVisible() }
            ui.act(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, { it.editable }, android.os.Bundle().apply {
                putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "公开中文输入")
            })
            ui.enter()
            ui.await("Enter edits the current draft") { current.value.composer!!.value.text.contains('\n') }
            assertTrue(submissions.isEmpty())
            val edited = current.value.composer!!
            ui.back()
            ui.await("first back hides keyboard") { !ui.imeVisible() }
            assertEquals(0, backs)
            assertEquals(edited.value.text, current.value.composer!!.value.text)
            assertEquals(edited.mode, current.value.composer!!.mode)
            ui.back()
            ui.await("second back delegates to parent") { backs == 1 }
            assertTrue(submissions.isEmpty())
            ui.capture("native-chat-editor-ime-restored")
        }
    }

    @Test fun nativeProcessesCollapseButAcceptedLongToolErrorStaysUnfolded() {
        val start = "公开工具错误开头："
        val end = "公开工具错误末尾：保留必要说明。"
        val error = record(3, "tool/result", jsonObject("message" to jsonObject("isError" to JsonBoolean(true),
            "content" to JsonArray(listOf(text(start + "这是必要的错误说明，不等于整个任务失败。".repeat(80), "reasoning"), text(end))))))
        val source = window(listOf(tool(0), tool(1), tool(2), error, user(4, "公开尾部消息"))).snapshot()
        assertTrue(source.items.single { it.id == "event:3" }.hasError)
        val position = ChatReadingPosition().apply { key = "event:0"; following = false }
        NativeChatFixture.launch().use { ui ->
            ui.content { SyntheticSessionScreen(initial(source), {}, {}, {}, {}, readingPosition = position, viewport = ui.viewport) }
            ui.scrollTo({ it.tag("g0") }, forward = false)
            val collapsed = ui.requireNode { it.tag("g0") }
            assertEquals("已收起", collapsed.state)
            assertTrue(collapsed.bounds.height() + 1 >= 48f * ui.density)
            ui.absent { it.label("public-read-0") }
            ui.clickTag("g0")
            ui.await("original process message expanded") { nodes -> nodes.any { it.label("public-read-0") } }
            ui.requireNode { it.tag("conversation-message:event:0") }
            ui.scrollTo({ it.tag("g0") }, forward = false)
            ui.clickTag("g0")
            ui.await("process collapsed again") { nodes -> nodes.none { it.label("public-read-0") } }
            ui.scrollTo({ it.label("工具返回错误") }, forward = true)
            ui.scrollTo({ it.contains(start) }, forward = true)
            ui.scrollTo({ it.contains(end) }, forward = true)
            ui.requireNode { it.contains(end) }
            ui.absent { it.label("执行成功") || it.label("任务已完成") }
            assertFalse(position.key.orEmpty().startsWith("g"))
            ui.capture("native-chat-long-error-unfolded")
        }
    }

    @Test fun nativeHeaderAndTouchTargetsRenderAllPublicViewportThemeAndFontCombinations() {
        val sizes = listOf(320 to 640, 360 to 800, 640 to 360, 600 to 960, 840 to 960, 1280 to 800)
        for (dark in listOf(false, true)) for (font in listOf(1f, 2f)) {
            var creates = 0
            NativeChatFixture.launch(dark = dark, fontScale = font).use { ui ->
                ui.content { SyntheticSessionScreen(initial(), {}, {}, {}, {}, viewport = ui.viewport, onCreateSession = { creates++ }) }
                for ((width, height) in sizes) {
                    ui.resize(width, height)
                    ui.await("header at $width x $height font $font") { nodes -> nodes.any {
                        it.label(label(R.string.chat_task_more)) && it.bounds.width() > 0 } }
                    val navigation = ui.actionNode { it.label(label(R.string.workspace_open_navigation)) }
                    val more = ui.actionNode { it.label(label(R.string.chat_task_more)) }
                    val contentLeft = ui.requireNode { it.tag("workspace-content") }.bounds.left
                    val create = ui.actionNode { it.description == label(R.string.action_create_session) && it.bounds.left >= contentLeft }
                    val models = ui.actionNode { it.label(label(R.string.workspace_model)) }
                    val hosts = ui.nodes().filter { it.description == label(R.string.workspace_host_connection_details) }
                        .map { labelNode -> ui.actionNode { it.description == labelNode.description && it.bounds == labelNode.bounds } }
                    assertTrue("Host capsule has an accessible native action", hosts.isNotEmpty())
                    val visibleBounds = ui.requireNode { it.tag("native-public-fixture") }.bounds
                    for (button in listOf(navigation, more, create, models) + hosts) {
                        assertTrue("48dp header touch width: $button", button.bounds.width() + 1 >= 48f * ui.density)
                        assertTrue("48dp header touch height: $button", button.bounds.height() + 1 >= 48f * ui.density)
                        assertTrue("Header inside rendered window: $button", visibleBounds.contains(button.bounds))
                    }
                    assertTrue("Header actions overlap at $width x $height font=$font: menu=${navigation.bounds}, create=${create.bounds}, more=${more.bounds}",
                        navigation.bounds.right <= create.bounds.left && create.bounds.right <= more.bounds.left)
                    ui.requireNode { it.contains(title) }
                    ui.capture("native-chat-${width}x$height-${if (dark) "dark" else "light"}-f${font.toInt()}")
                }
                assertEquals(0, creates)
                val contentLeft = ui.requireNode { it.tag("workspace-content") }.bounds.left
                ui.act(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,
                    { it.description == label(R.string.action_create_session) && it.bounds.left >= contentLeft })
                assertEquals(1, creates)
            }
        }
    }

    @Test fun nativeFinishedTurnFoldsProcessAndKeepsTheAnswerAndFootnote() {
        val records = listOf(
            record(0, "turn/start", jsonObject("turn" to 1L.json())),
            tool(1), tool(2), tool(3),
            record(4, "assistant/message", jsonObject("turn" to 1L.json(), "step" to 3L.json(),
                "message" to jsonObject("content" to JsonArray(listOf(text("公开回合答案")))))),
            record(5, "turn/end", jsonObject("turn" to 1L.json(), "reason" to "completed".json())),
        )
        val source = window(records).snapshot()
        assertFalse(source.taskRunning)
        NativeChatFixture.launch(WorkspaceViewport(360, 800)).use { ui ->
            ui.content { SyntheticSessionScreen(initial(source), {}, {}, {}, {}, viewport = ui.viewport) }
            ui.await("folded turn header") { nodes -> nodes.any { it.tag("tp1") } }
            ui.requireNode { it.contains("过程 · 3 项") }
            ui.requireNode { it.contains("公开回合答案") }
            ui.requireNode { it.contains("用时") }
            ui.absent { it.label("public-read-1") }
            ui.capture("native-turn-folded")
            ui.clickTag("tp1")
            ui.await("turn process expanded") { nodes -> nodes.any { it.label("public-read-1") } }
            ui.capture("native-turn-expanded")
        }
    }

    @Test fun nativeNavigationColumnRendersAcrossPublicBoundaryWidths() {
        NativeChatFixture.launch(WorkspaceViewport(360, 800)).use { ui ->
            ui.content { SyntheticSessionScreen(initial(), {}, {}, {}, {}, viewport = ui.viewport) }
            for (width in listOf(320, 360, 600)) {
                ui.resize(width, 800)
                ui.click(label(R.string.workspace_open_navigation))
                ui.await("drawer at $width") { nodes -> nodes.any { it.tag("directory-search") } }
                ui.capture("native-navigation-drawer-${width}x800")
                ui.back()
                ui.await("drawer closed at $width") { nodes -> nodes.none { it.tag("directory-search") } }
            }
            for (width in listOf(840, 1280)) {
                ui.resize(width, 800)
                ui.await("navigation column at $width") { nodes ->
                    nodes.any { it.tag("workspace-navigation") || it.tag("workspace-rail") }
                }
                ui.capture("native-navigation-column-${width}x800")
            }
            ui.absent { it.tag("workspace-drawer") }
        }
    }

    @Test fun nativeBoundaryResizesKeepTheSameDetailsAndDoNotInventControllerAuthority() {
        var refreshes = 0
        var pages = 0
        var claims = 0
        var submits = 0
        NativeChatFixture.launch(WorkspaceViewport(840, 960)).use { ui ->
            ui.content { SyntheticSessionScreen(initial(), {}, { refreshes++ }, { pages++ }, {},
                onSubmit = { submits++ }, onClaim = { claims++ }, viewport = ui.viewport) }
            ui.more()
            ui.click("会话详情")
            for (width in listOf(599, 600, 839, 840, 1119, 1120, 1119, 839, 599, 1120)) {
                ui.resize(width)
                ui.await("same details across boundary $width") { nodes ->
                    nodes.any { it.contains("会话：$session") } &&
                        ui.modalForeground() == (width in 600..839)
                }
                assertEquals(1, ui.nodes().count { it.contains("会话：$session") })
                if (width >= 840) {
                    // The open details panel keeps chat at its minimum, so navigation stays a rail.
                    val rail = ui.requireNode { it.tag("workspace-rail") }
                    assertEquals(68f, rail.bounds.width() / ui.density, 2f)
                } else ui.absent { it.tag("workspace-rail") }
            }
            ui.closeDetails()
            ui.await("expanded navigation preference restored") { nodes -> nodes.any { it.tag("workspace-navigation") &&
                kotlin.math.abs(it.bounds.width() / ui.density - 320f) < 2f } }
            assertEquals(0, refreshes)
            assertEquals(0, pages)
            assertEquals(0, claims)
            assertEquals(0, submits)
            ui.capture("native-chat-boundary-restored")
        }
    }

    @Test fun nativePublicStreamingPerformance60SecondsKeepsAnOldMessageAnchor() {
        val records = (0 until 500).map { index -> user(index.toLong(), "公开消息 $index " + "文".repeat(200 - "公开消息 $index ".length)) } +
            (500 until 530).map { index -> assistant(index.toLong(), "```text\n" + ("x".repeat(63) + "\n").repeat(32) + "```") } +
            assistant(530, "| A | B | C | D | E |\n|---|---|---|---|---|\n" + (1..20).joinToString("\n") { "| $it | b | c | d | e |" })
        val accepted = window(records)
        assertEquals(531, accepted.snapshot().items.size)
        val current = mutableStateOf(initial(accepted.snapshot()))
        val position = ChatReadingPosition()
        NativeChatFixture.launch().use { ui ->
            ui.content { SyntheticSessionScreen(current.value, {}, {}, {}, {}, readingPosition = position, viewport = ui.viewport) }
            ui.await("last synthetic table rendered") { nodes -> nodes.any { it.tag("conversation-message:event:530") } }
            // Actual native history scrolling traverses the table and code workload before sampling.
            ui.scrollTo({ it.tag("conversation-message:event:495") }, forward = false, limit = 240)
            ui.await("history observer records original identity") { position.key != null && !position.following }
            // The scroll that established the anchor can still emit one trailing capture; sample until it settles.
            var anchor = 0
            var originalOffset = -1
            for (attempt in 0 until 40) {
                val observed = position.offset
                val top = ui.requireNode { it.tag("conversation-message:event:495") }.bounds.top
                if (observed == originalOffset && top == anchor) break
                originalOffset = observed
                anchor = top
                Thread.sleep(50)
            }
            val originalKey = position.key
            val memoryBefore = Debug.MemoryInfo().also(Debug::getMemoryInfo)
            val frameDurations = ArrayList<Long>()
            var droppedCallbacks = 0
            val frameListener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
                synchronized(frameDurations) { frameDurations += metrics.getMetric(FrameMetrics.TOTAL_DURATION); droppedCallbacks += dropped }
            }
            val finished = CountDownLatch(1)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            var increments = 0
            var elapsed = 0L
            var maximumLateNanos = 0L
            var streamFailure: Throwable? = null
            lateinit var ticker: Choreographer.FrameCallback
            ui.draw {
                ui.activity.window.addOnFrameMetricsAvailableListener(frameListener, Handler(Looper.getMainLooper()))
                accepted.accept(SessionContent("assistant.start", request, session, jsonObject("type" to "start".json(),
                    "attemptId" to "public-performance".json(), "revision" to 1L.json(), "startedAfterSeq" to 530L.json(),
                    "turn" to 1L.json(), "step" to 1L.json())))
                var firstFrame = 0L
                ticker = object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        try {
                            if (firstFrame == 0L) firstFrame = frameTimeNanos
                            elapsed = frameTimeNanos - firstFrame
                            while (increments < 1200 && elapsed >= (increments + 1) * 50_000_000L) {
                                maximumLateNanos = maxOf(maximumLateNanos, elapsed - (increments + 1) * 50_000_000L)
                                accepted.accept(SessionContent("assistant.chunk", request, session, jsonObject("type" to "chunk".json(),
                                    "attemptId" to "public-performance".json(), "revision" to (increments + 2L).json(),
                                    "index" to increments.toLong().json(), "time" to (1000L + increments * 50L).json(),
                                    "chunk" to jsonObject("type" to "text-delta".json(), "index" to 0L.json(), "text" to "增".repeat(32).json()))))
                                increments++
                                current.value = current.value.copy(reader = current.value.reader!!.copy(window = accepted.snapshot()))
                            }
                            if (increments == 1200) finished.countDown() else Choreographer.getInstance().postFrameCallback(this)
                        } catch (failure: Throwable) { streamFailure = failure; finished.countDown() }
                    }
                }
                Choreographer.getInstance().postFrameCallback(ticker)
            }
            try {
                assertTrue("60-second frame-driven stream did not finish", finished.await(90, TimeUnit.SECONDS))
                streamFailure?.let { throw AssertionError("Public streaming fixture failed", it) }
                ui.draw()
                ui.await("new-message control while reading old history") { nodes -> nodes.any { it.contains("回到最新") } }
                val memoryAfter = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                val frames = synchronized(frameDurations) { frameDurations.toList() }
                val sorted = frames.sorted()
                val facts = JSONObject().put("durationNanos", elapsed).put("increments", increments).put("charactersPerIncrement", 32)
                    .put("requestedHz", 20).put("maximumIncrementLatenessNanos", maximumLateNanos)
                    .put("messages", 500).put("charactersPerMessage", 200).put("codeBlocks", 30).put("codeBytesEach", 2048)
                    .put("tableRows", 20).put("tableColumns", 5).put("frameCount", frames.size)
                    .put("frameDurationsNanos", JSONArray(frames)).put("droppedFrameMetricCallbacks", droppedCallbacks)
                    .put("p95FrameNanos", if (sorted.isEmpty()) JSONObject.NULL else sorted[((sorted.size - 1) * 0.95).toInt()])
                    .put("totalPssBeforeKiB", memoryBefore.totalPss).put("totalPssAfterKiB", memoryAfter.totalPss)
                    .put("javaHeapBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                    .put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize()).put("anchorOriginalId", originalKey)
                    .put("anchorOffsetBefore", originalOffset).put("anchorOffsetAfter", position.offset)
                    .put("anchorScreenTopBefore", anchor).put("anchorScreenTopAfter",
                        ui.requireNode { it.tag("conversation-message:event:495") }.bounds.top)
                    .put("baselineAvailable", false).put("physicalTabletVerified", false)
                ui.capture("native-chat-performance-60s", facts)
                assertEquals(1200, increments)
                assertTrue("Real Window frame metrics must be delivered", frames.isNotEmpty())
                assertEquals(38_400, accepted.snapshot().items.last().blocks.single().text.length)
                assertEquals(originalKey, position.key)
                assertEquals(originalOffset.toFloat(), position.offset.toFloat(), 1f)
                assertEquals(anchor.toFloat(), ui.requireNode { it.tag("conversation-message:event:495") }.bounds.top.toFloat(), 1f)
                assertFalse(position.following)
            } finally {
                instrumentation.runOnMainSync {
                    Choreographer.getInstance().removeFrameCallback(ticker)
                    ui.activity.window.removeOnFrameMetricsAvailableListener(frameListener)
                }
            }
        }
    }
}
