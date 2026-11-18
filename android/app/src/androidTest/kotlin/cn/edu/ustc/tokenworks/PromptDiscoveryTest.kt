package cn.edu.ustc.tokenworks

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PromptDiscoveryTest {
    @Test fun referencesAndSkillsEditTheDraftWithoutSending() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val host = "11111111-1111-4111-8111-111111111111"
        val session = "discovery-fixture"
        val resource = ConversationResource(CommandOwner(host, host, "a".repeat(64), host, host), host, host, session)
        val draft = mutableStateOf(MessageComposerState(resource, TextFieldValue("")))
        val transport = object : DshTransport {
            override val runtimeId = host
            override val state = MutableStateFlow(DshTransportState(DshTransportPath.LAN, DshTransportPhase.READY))
            override suspend fun native(endpoint: String, args: JsonObject): JsonValue = when (endpoint) {
                "skills/list" -> {
                    check(args.obj("request").string("sessionId") == session)
                    jsonObject("skills" to JsonArray(listOf(jsonObject("name" to "fixture-skill".json(),
                        "description" to "公开测试技能".json(), "modelInvocable" to JsonBoolean(true)))))
                }
                "fileReferences/list" -> {
                    check(args.string("agentId") == session)
                    JsonArray(listOf(jsonObject("path" to "work notes.txt".json(), "kind" to "file".json())))
                }
                else -> error("Unexpected discovery endpoint: $endpoint")
            }
            override suspend fun openMux(): DshMux = error("unused")
            override suspend fun download(path: String, sink: OutputStream, maxBytes: Long, range: DshByteRange?): DshDownload = error("unused")
            override suspend fun upload(path: String, mediaType: String, source: InputStream, sizeBytes: Long): JsonValue = error("unused")
            override fun close() = Unit
        }
        var sent = 0
        val actions = SessionContentActions(editDraft = { draft.value = draft.value.copy(value = it) }, send = { sent++ })
        NativeChatFixture.launch(WorkspaceViewport(375, 760), dark = true, fontScale = 1.3f).use { ui ->
            ui.content {
                NativeSessionContent(HomeState(HomePhase.READY, lanOnly = true, selectedRuntimeId = host,
                    selectedSessionId = session, reader = SessionReadState(SessionReadPhase.READY), composer = draft.value),
                    ChatReadingPosition(), actions, {}, {}, transport = transport)
            }
            ui.click(context.getString(R.string.discovery_add))
            ui.click("fixture-skill")
            ui.await("skill is inserted without sending") { nodes -> nodes.any { it.tag("composer-input") && it.contains("/fixture-skill") } }
            ui.click(context.getString(R.string.discovery_add))
            ui.click(context.getString(R.string.discovery_references))
            ui.click("work notes.txt")
            ui.await("quoted path is inserted") { nodes -> nodes.any { it.tag("composer-input") && it.contains("@\"work notes.txt\"") } }
            assertEquals("/fixture-skill @\"work notes.txt\" ", draft.value.value.text)
            assertEquals(0, sent)
        }
    }
}
