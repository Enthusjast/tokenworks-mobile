package cn.edu.ustc.tokenworks

import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Navigation renders the same native read window without raising session actions. */
@RunWith(AndroidJUnit4::class)
class NativeWorkspaceControllerTest {
    @Test fun drawerResizeKeepsNativeWindowAndDraftWithoutInvokingChatActions() {
        val id = "11111111-1111-4111-8111-111111111111"
        val sessionId = "native-public-session"
        val owner = CommandOwner(id, id, "a".repeat(64), id, id)
        val window = SessionWindow("native-fixture").apply {
            accept(SessionContent("session.snapshot", "native-fixture", "native-fixture", jsonObject(
                "cursor" to 0L.json(), "has_more" to JsonBoolean(false), "assistant" to JsonNull,
                "records" to JsonArray(listOf(jsonObject("seq" to 0L.json(), "time" to 1000L.json(),
                    "type" to "user/message".json(), "data" to jsonObject("content" to JsonArray(listOf(
                        jsonObject("type" to "text".json(), "text" to "公开原生正文".json()))))))),
            )))
        }
        val entry = SessionDirectoryEntry.from(DshSessionListResultItemsItem(
            agentAvailable = true, sessionId = sessionId, updatedAt = 1000.0, running = false, blank = false))
        val current = mutableStateOf(HomeState(HomePhase.READY, presentationOwner = owner,
            runtimes = listOf(RuntimeRow(id, id, "0.2.1-rc.5", "公开合成电脑")),
            selectedRuntimeId = id, selectedSessionId = sessionId, sessions = listOf(entry),
            reader = SessionReadState(SessionReadPhase.READY, window.snapshot()),
            composer = MessageComposerState(ConversationResource(owner, id, id, sessionId),
                androidx.compose.ui.text.input.TextFieldValue("公开原生草稿"))))
        var pages = 0
        var sends = 0
        var cancels = 0
        val labels = InstrumentationRegistry.getInstrumentation().targetContext
        NativeChatFixture.launch(WorkspaceViewport(360, 800)).use { ui ->
            ui.content { SyntheticSessionScreen(current.value, {}, { pages++ }, onSubmit = { sends++ }, onCancelSession = { cancels++ }, viewport = ui.viewport) }
            ui.await("active native window") { nodes -> nodes.any { it.contains("公开原生正文") } }
            ui.click(labels.getString(R.string.workspace_open_navigation))
            ui.await("drawer foreground") { nodes -> nodes.any { it.tag("workspace-drawer") } }
            ui.absent { it.tag("chat-list") }
            ui.back()
            ui.await("same native transcript restored") { nodes -> nodes.any { it.contains("公开原生正文") } }
            for (width in listOf(599, 600, 839, 840, 1119, 1120, 1280, 360)) {
                ui.resize(width, 800)
                ui.await("one native window at $width") { nodes ->
                    nodes.count { it.tag("chat-list") } == 1 && nodes.any { it.contains("公开原生草稿") }
                }
                assertEquals(0, pages)
                assertEquals(0, sends)
                assertEquals(0, cancels)
            }
            ui.draw {
                window.accept(SessionContent("session.event", "native-fixture", "native-fixture", jsonObject(
                    "seq" to 1L.json(), "time" to 2000L.json(), "type" to "assistant/message".json(),
                    "data" to jsonObject("message" to jsonObject("content" to JsonArray(listOf(
                        jsonObject("type" to "text".json(), "text" to "同一窗口的新原生事件".json()))))),
                )))
                current.value = current.value.copy(reader = current.value.reader!!.copy(window = window.snapshot()))
            }
            ui.await("same window continues after navigation") { nodes -> nodes.any { it.contains("同一窗口的新原生事件") } }
            assertEquals(listOf(0L, 1L), current.value.reader!!.window!!.records.map { it.seq })
            assertEquals(owner, current.value.presentationOwner)
            assertEquals(sessionId, current.value.composer!!.resource.sessionId)
            assertEquals(0, pages)
            assertEquals(0, sends)
            assertEquals(0, cancels)
        }
    }
}
