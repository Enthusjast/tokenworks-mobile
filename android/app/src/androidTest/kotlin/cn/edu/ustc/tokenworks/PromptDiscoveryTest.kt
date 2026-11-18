package cn.edu.ustc.tokenworks

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PromptDiscoveryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun referencesAndSkillsEditTheDraftWithoutSending() {
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
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                TokenWorksTheme(darkTheme = true) {
                    NativeSessionContent(HomeState(HomePhase.READY, lanOnly = true, selectedRuntimeId = host,
                        selectedSessionId = session, reader = SessionReadState(SessionReadPhase.READY), composer = draft.value),
                        ChatReadingPosition(), actions, {}, {}, transport = transport)
                }
            }
        }
        val add = rule.activity.getString(R.string.discovery_add)
        rule.onNodeWithContentDescription(add).performClick()
        rule.onNodeWithText("fixture-skill").performClick()
        rule.runOnIdle { assertEquals("/fixture-skill ", draft.value.value.text) }
        rule.onNodeWithContentDescription(add).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.discovery_references)).performClick()
        rule.waitUntil(timeoutMillis = 5_000) { rule.onAllNodesWithText("work notes.txt").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("work notes.txt").performClick()
        rule.runOnIdle {
            assertEquals("/fixture-skill @\"work notes.txt\" ", draft.value.value.text)
            assertEquals(0, sent)
        }
    }
}
