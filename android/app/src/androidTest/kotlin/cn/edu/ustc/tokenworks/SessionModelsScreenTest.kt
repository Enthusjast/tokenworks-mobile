package cn.edu.ustc.tokenworks

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateOf
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
            SessionModelsScreen(catalog(), null, false, null, {}, {}, {}, { submitted = it }, {}, {}, {}, {})
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
            SessionModelsScreen(current.value, null, false, null, {}, {}, {}, { submitted = true }, {}, {}, {}, {})
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
                { fail("Uncertain original must not be discarded") }, {})
        } }
        rule.onNodeWithText(text(R.string.models_apply)).assertIsNotEnabled()
        val secondChoice = hasText(second.model.id) and isSelectable()
        reveal(secondChoice)
        rule.onNode(secondChoice).assertIsNotEnabled()
        rule.onNodeWithText(text(R.string.models_discard_unsent)).assertDoesNotExist()
        reveal(hasText(text(R.string.models_unknown)))
        rule.onNodeWithText(text(R.string.models_unknown)).assertExists()
    }
}
