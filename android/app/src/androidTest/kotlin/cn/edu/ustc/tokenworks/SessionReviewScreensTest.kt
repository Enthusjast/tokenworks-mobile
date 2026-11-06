package cn.edu.ustc.tokenworks

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.enthusjast.tokenworks.command.*
import io.github.enthusjast.tokenworks.network.*
import io.github.enthusjast.tokenworks.protocol.*
import io.github.enthusjast.tokenworks.ui.TokenWorksTheme
import java.util.Base64
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionReviewScreensTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val id = "11111111-1111-4111-8111-111111111111"
    private val other = "22222222-2222-4222-8222-222222222222"
    private fun text(resource: Int) = rule.activity.getString(resource)
    private fun reveal(label: String) { rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label)) }
    private fun preview(message: String?) = SessionMessagePreview.parse(jsonObject("v" to 1L.json(),
        "anchor" to SessionForkAnchor(id, other, 8, "a".repeat(64), "b".repeat(64)).json(),
        "text" to (message?.json() ?: JsonNull), "shareable" to JsonBoolean(message != null), "complete" to JsonBoolean(true)))

    @Test fun partialPatchCannotBeCopiedAsCompleteAndRetryAndBackStayReachable() {
        val body = "diff --git a/a b/a\n--- a/a\n"
        val path = jsonObject("bytes_base64" to "YQ==".json(), "display" to "a".json())
        val file = SessionChangeFile.parse(jsonObject("file_id" to id.json(), "status" to "modified".json(),
            "old_path" to path, "new_path" to path, "old_mode" to "100644".json(), "new_mode" to "100644".json(),
            "kind" to "text".json(), "patch_bytes" to 100L.json(), "complete" to JsonBoolean(true), "copyable" to JsonBoolean(false)))
        val patch = SessionChangesPatch(other, other, id, "a".repeat(64), "text", false, false, true,
            Base64.getEncoder().encodeToString(body.toByteArray()), body, emptyList(), other)
        val state = SessionChangesState(SessionChangesPhase.READY, listOf(file),
            SessionChangesManifest("a".repeat(40), "a".repeat(64), "b".repeat(64), System.currentTimeMillis() + 30_000, other),
            patch, patchHasMore = true)
        rule.setContent { TokenWorksTheme { SessionChangesScreen(state, {}, {}, {}, {}, {},
            { fail("A fragment must never be copied as a complete patch") }, {},
            uiState = remember { ChangesPanelUiState() }, embedded = true) } }
        val copy = text(R.string.changes_fork_copy_complete)
        reveal(copy)
        rule.onNodeWithText(copy).assertIsNotEnabled()
        rule.onNodeWithContentDescription(text(R.string.workspace_panel_back)).assertIsDisplayed()
        captureUiFixture(rule, "review-partial-patch", darkTheme = false)
    }

    @Test fun attachmentOnlyPreviewCannotShareAndForkStillRequiresSeparateConfirmation() {
        val selected = preview(null)
        val state = SessionMessageActionState(SessionMessageActionPhase.READY, selected, canFork = true, canPreview = true)
        var forks = 0
        rule.setContent { TokenWorksTheme { SessionMessageActionPreview(state, null, { forks++ },
            { fail("An attachment-only message has no shareable text") }, {}, {}, {}) } }
        reveal(text(R.string.changes_fork_share_action))
        rule.onNodeWithText(text(R.string.changes_fork_share_action)).assertIsNotEnabled()
        reveal(text(R.string.changes_fork_new_child))
        rule.onNodeWithText(text(R.string.changes_fork_new_child)).assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(0, forks) }
        rule.onNodeWithText(text(R.string.changes_fork_cancel)).performClick()
        rule.runOnIdle { assertEquals(0, forks) }
        reveal(text(R.string.changes_fork_new_child))
        rule.onNodeWithText(text(R.string.changes_fork_new_child)).performClick()
        rule.onNodeWithText(text(R.string.changes_fork_confirm_action)).performClick()
        rule.runOnIdle { assertEquals(1, forks) }
    }

    @Test fun anUnresolvedOriginalCannotExposeASecondForkAction() {
        val selected = preview("actual visible text only")
        val state = SessionMessageActionState(SessionMessageActionPhase.READY, selected, canFork = true,
            originalRequestId = id, canPreview = true)
        rule.setContent { TokenWorksTheme { SessionMessageActionPreview(state, null,
            { fail("Unknown original must be queried, not forked again") }, {}, {}, {}, {}) } }
        reveal(text(R.string.changes_fork_query_original))
        rule.onNodeWithText(text(R.string.changes_fork_query_original)).assertIsEnabled()
        rule.onNodeWithText(text(R.string.changes_fork_new_child)).assertDoesNotExist()
        captureUiFixture(rule, "review-original-unknown", darkTheme = false)
    }

    @Test fun panelResizeDoesNotRestoreAnUnfinishedForkConfirmation() {
        val embedded = mutableStateOf(false)
        val state = SessionMessageActionState(SessionMessageActionPhase.READY, preview("authorized visible source"),
            canFork = true, canPreview = true)
        var forks = 0
        var shares = 0
        rule.setContent { TokenWorksTheme {
            SessionMessageActionPreview(state, null, { forks++ }, { shares++ }, {}, {}, {}, embedded = embedded.value)
        } }
        rule.onNode(isDialog()).assertDoesNotExist()
        reveal(text(R.string.changes_fork_new_child))
        rule.onNodeWithText(text(R.string.changes_fork_new_child)).performClick()
        rule.onNode(isDialog()).assertExists()
        rule.runOnIdle { embedded.value = true }
        rule.onNode(isDialog()).assertDoesNotExist()
        rule.runOnIdle { assertEquals(0, forks); assertEquals(0, shares) }
        reveal(text(R.string.changes_fork_new_child))
        rule.onNodeWithText(text(R.string.changes_fork_new_child)).performClick()
        rule.onNodeWithText(text(R.string.changes_fork_confirm_action)).performClick()
        rule.runOnIdle { assertEquals(1, forks); assertEquals(0, shares) }
    }

    @Test fun stalePreviewCannotShareOrCreateAReplacementFork() {
        val current = mutableStateOf(SessionMessageActionState(SessionMessageActionPhase.READY,
            preview("only this source may be shared"), canFork = true, canPreview = true))
        var shares = 0
        rule.setContent { TokenWorksTheme {
            SessionMessageActionPreview(current.value, null, { fail("Stale preview must not fork") }, { shares++ }, {}, {}, {}, embedded = true)
        } }
        reveal(text(R.string.changes_fork_share_action))
        rule.onNodeWithText(text(R.string.changes_fork_share_action)).assertIsEnabled()
        rule.runOnIdle { current.value = current.value.copy(phase = SessionMessageActionPhase.STALE) }
        rule.onNodeWithText(text(R.string.changes_fork_share_action)).assertIsNotEnabled()
        reveal(text(R.string.changes_fork_new_child))
        rule.onNodeWithText(text(R.string.changes_fork_new_child)).assertIsNotEnabled()
        rule.runOnIdle { assertEquals(0, shares) }
    }
    @Test fun diffResizeRetainsDisclosureWrapAndSharedHorizontalColumn() {
        val lines = listOf("+" + "long-first-column-".repeat(20), "-" + "long-second-column-".repeat(20))
        val body = lines.joinToString("\n")
        val patch = SessionChangesPatch(other, other, id, "a".repeat(64), "text", true, true, false,
            Base64.getEncoder().encodeToString(body.toByteArray()), body, emptyList(), null)
        val state = SessionChangesState(SessionChangesPhase.READY, patch = patch)
        val ui = ChangesPanelUiState()
        val code = ChangePatchUiState()
        val embedded = mutableStateOf(true)
        rule.setContent { TokenWorksTheme {
            key(embedded.value) {
                SessionChangesScreen(state, {}, {}, {}, {}, {}, {}, {}, uiState = ui,
                    embedded = embedded.value, patchUiState = code)
            }
        } }
        reveal(text(R.string.workspace_panel_source))
        rule.onNodeWithText(text(R.string.workspace_panel_source)).performClick()
        reveal(text(R.string.workspace_panel_wrap_code))
        rule.onNodeWithText(text(R.string.workspace_panel_wrap_code)).performClick().assertIsNotSelected()
        rule.onNodeWithTag("changes-list").performScrollToNode(hasTestTag("changes-code:line:0"))
        rule.onNodeWithTag("changes-code:line:1").assertIsDisplayed()
        val beforeFirst = rule.onNodeWithTag("changes-code:line:0").getUnclippedBoundsInRoot().left.value
        val beforeSecond = rule.onNodeWithTag("changes-code:line:1").getUnclippedBoundsInRoot().left.value
        rule.onNodeWithTag("changes-code:line:0").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        val firstDelta = rule.onNodeWithTag("changes-code:line:0").getUnclippedBoundsInRoot().left.value - beforeFirst
        val secondDelta = rule.onNodeWithTag("changes-code:line:1").getUnclippedBoundsInRoot().left.value - beforeSecond
        assertTrue("horizontal input must move actual code", firstDelta < -1f)
        assertEquals(firstDelta, secondDelta, 1f)
        val previousHorizontal = code.horizontal.value
        rule.runOnIdle { embedded.value = false }
        reveal(text(R.string.workspace_panel_hide_source))
        rule.onNodeWithText(text(R.string.workspace_panel_hide_source)).assertIsDisplayed()
        reveal(text(R.string.workspace_panel_wrap_code))
        rule.onNodeWithText(text(R.string.workspace_panel_wrap_code)).assertIsNotSelected()
        rule.runOnIdle { assertEquals(previousHorizontal, code.horizontal.value) }
    }

}
