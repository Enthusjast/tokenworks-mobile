package cn.edu.ustc.tokenworks

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionModelsScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val first = SessionModelItem(SessionModelLabel("provider-a", "同名提供方"), SessionModelLabel("opaque-a", "同名模型"), listOf(SessionModelLabel("effort-a", "高强度")))
    private val second = SessionModelItem(SessionModelLabel("provider-b", "同名提供方"), SessionModelLabel("opaque-b", "同名模型"), emptyList())
    private fun text(@StringRes id: Int) = rule.activity.getString(id)
    private fun catalog(context: Long = 1) = SessionModelCatalogState(SessionModelCatalogPhase.READY,
        items = listOf(first, second), canSelect = true, contextId = context)
    private fun reveal(matcher: SemanticsMatcher) {
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
    }

    @Test fun duplicateLabelsKeepOpaqueIdentityAndEffortDoesNotFollowAnotherModel() {
        var submitted: SessionModelSelection? = null
        rule.setContent { TokenWorksTheme {
            SessionModelsScreen(catalog(), null, false, null, {}, {}, {}, { submitted = it }, {}, {}, {}, {},
                uiState = remember(1L) { ModelPanelUiState() })
        } }
        rule.onNodeWithText(text(R.string.models_apply)).assertIsNotEnabled()
        val firstChoice = hasText(first.model.id) and isSelectable()
        val secondChoice = hasText(second.model.id) and isSelectable()
        reveal(firstChoice)
        rule.onNode(firstChoice).performClick()
        reveal(hasText("高强度"))
        rule.onNodeWithText("高强度").performClick()
        reveal(secondChoice)
        rule.onNode(secondChoice).performClick()
        rule.runOnIdle { assertNull(submitted) }
        rule.onNodeWithText(text(R.string.models_apply)).performClick()
        rule.runOnIdle { assertEquals(SessionModelSelection(second.provider.id, second.model.id, null), submitted) }
    }

    @Test fun aNewCatalogContextCannotReviveTheOldLocalChoice() {
        val current = mutableStateOf(catalog())
        var submitted = false
        rule.setContent { TokenWorksTheme {
            SessionModelsScreen(current.value, null, false, null, {}, {}, {}, { submitted = true }, {}, {}, {}, {},
                uiState = remember(current.value.contextId) { ModelPanelUiState() })
        } }
        val firstChoice = hasText(first.model.id) and isSelectable()
        reveal(firstChoice)
        rule.onNode(firstChoice).performClick()
        rule.onNodeWithText(text(R.string.models_apply)).assertIsEnabled()
        rule.runOnIdle { current.value = catalog(context = 2) }
        rule.onNodeWithText(text(R.string.models_apply)).assertIsNotEnabled()
        reveal(firstChoice)
        rule.onNode(firstChoice).assertIsNotSelected()
        rule.runOnIdle { assertFalse(submitted) }
    }

    @Test fun uncertainOriginalDisablesNewChoiceAndCannotBeDiscardedAsUnsent() {
        val id = "11111111-1111-4111-8111-111111111111"
        val intent = CommandIntent.selectModel("22222222-2222-4222-8222-222222222222", id, id,
            "33333333-3333-4333-8333-333333333333", first.provider.id, first.model.id)
        val original = PendingCommand(intent, CommandDelivery.RECONCILE, RemoteCommandState.OUTCOME_UNKNOWN,
            1, 2, 1, 2, null, true, false, null)
        rule.setContent { TokenWorksTheme {
            SessionModelsScreen(catalog(), original, false, null, {}, {}, {}, { fail("Uncertain original must not create another selection") }, {}, {},
                { fail("Uncertain original must not be discarded") }, {},
                uiState = remember(1L) { ModelPanelUiState() })
        } }
        rule.onNodeWithText(text(R.string.models_apply)).assertIsNotEnabled()
        val secondChoice = hasText(second.model.id) and isSelectable()
        reveal(secondChoice)
        rule.onNode(secondChoice).assertIsNotEnabled()
        rule.onNodeWithText(text(R.string.models_discard_unsent)).assertDoesNotExist()
        reveal(hasText(text(R.string.models_unknown)))
        rule.onNodeWithText(text(R.string.models_unknown)).assertExists()
    }

    @Test fun containerChangeKeepsQueryOpaqueChoiceEffortAndReadingPosition() {
        val embedded = mutableStateOf(false)
        lateinit var ui: ModelPanelUiState
        var submitted: SessionModelSelection? = null
        val manyModels = catalog().copy(items = listOf(first, second) + List(30) { index ->
            SessionModelItem(first.provider, SessionModelLabel("opaque-extra-$index", "目录模型 $index"), emptyList())
        })
        rule.setContent { TokenWorksTheme {
            val panelState = remember(1L) { ModelPanelUiState().apply { query = "opaque" } }
            ui = panelState
            key(embedded.value) {
                SessionModelsScreen(manyModels, null, false, null, {}, {}, {}, { submitted = it }, {}, {}, {}, {},
                    uiState = panelState, embedded = embedded.value)
            }
        } }
        rule.onNode(hasSetTextAction()).assertTextContains("opaque")
        val firstChoice = hasText(first.model.id) and isSelectable()
        reveal(firstChoice)
        rule.onNode(firstChoice).performClick()
        reveal(hasText("高强度"))
        rule.onNodeWithText("高强度").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(5)
        var index = 0
        var offset = 0
        lateinit var previous: ModelPanelUiState
        rule.runOnIdle {
            previous = ui
            index = ui.listState.firstVisibleItemIndex
            offset = ui.listState.firstVisibleItemScrollOffset
            embedded.value = true
        }
        rule.runOnIdle {
            assertSame(previous, ui)
            assertEquals("opaque", ui.query)
            assertEquals(first.provider.id to first.model.id, ui.choice)
            assertEquals("effort-a", ui.effort)
            assertEquals(index, ui.listState.firstVisibleItemIndex)
            assertEquals(offset, ui.listState.firstVisibleItemScrollOffset)
        }
        rule.onNodeWithText(text(R.string.models_apply)).performClick()
        rule.runOnIdle { assertEquals(SessionModelSelection(first.provider.id, first.model.id, "effort-a"), submitted) }
    }

    @Test fun deniedCatalogNeverMakesAnExistingOpaqueChoiceSelectable() {
        rule.setContent { TokenWorksTheme {
            val ui = remember(1L) { ModelPanelUiState().apply { choice = first.provider.id to first.model.id } }
            SessionModelsScreen(catalog().copy(phase = SessionModelCatalogPhase.DENIED, canSelect = false), null,
                false, null, {}, {}, {}, { fail("Denied catalog cannot select") }, {}, {}, {}, {}, ui, embedded = true)
        } }
        rule.onNodeWithText(text(R.string.models_apply)).assertIsNotEnabled()
        val firstChoice = hasText(first.model.id) and isSelectable()
        reveal(firstChoice)
        rule.onNode(firstChoice).assertIsNotEnabled()
    }
}
