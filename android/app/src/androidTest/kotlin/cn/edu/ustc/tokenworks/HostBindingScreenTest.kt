package cn.edu.ustc.tokenworks

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.ustc.tokenworks.HostBindingDetails
import cn.edu.ustc.tokenworks.*
import cn.edu.ustc.tokenworks.*
import cn.edu.ustc.tokenworks.TokenWorksTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HostBindingScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val id = "11111111-1111-4111-8111-111111111111"
    private val code = "L".repeat(42) + "A"
    private fun details() = HostBindingDetails.parse(jsonObject("enrollment_id" to id.json(), "request_id" to id.json(), "organization_id" to id.json(), "organization_name" to "学校夹具".json(),
        "host_id" to id.json(), "host_alias" to "实验室电脑".json(), "installation_id" to id.json(), "installation_key_jkt" to "A".repeat(43).json(), "runtime_id" to id.json(),
        "profile_ref" to "research".json(), "protocol_version" to 1L.json(), "state" to "pending".json(), "expires_at" to Contract.timestamp(System.currentTimeMillis() + 300_000).json(),
        "host_confirmed" to JsonBoolean(false), "user_confirmed" to JsonBoolean(false), "user_confirmed_by_me" to JsonBoolean(false), "workspaces" to JsonArray(listOf(jsonObject("reference" to "project".json(), "workspace_id" to id.json())))), id, id)
    @Test fun confirmationRequiresAnExplicitFullFingerprintCheckOnASmallLargeTextScreen() {
        var confirmations = 0
        val public = details()
        lateinit var clipboard: androidx.compose.ui.platform.ClipboardManager
        rule.setContent {
            val density = LocalDensity.current
            clipboard = LocalClipboardManager.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                TokenWorksTheme(darkTheme = true) { Box(Modifier.width(320.dp)) { HostBindingScreen(HostBindingState(HostBindingPhase.REVIEW, id, public), "fixture-account", { _, _ -> }, {}, { confirmations++ }, {}, {}, {}) } }
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.binding_confirm_computer)).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText(public.keyThumbprint.chunked(8).joinToString(" ")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.flow_binding_copy_fingerprint)).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.runOnIdle { assertEquals(public.keyThumbprint, clipboard.getText()?.text); assertEquals(0, confirmations) }
        rule.onNodeWithText(rule.activity.getString(R.string.binding_confirm_computer)).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText(rule.activity.getString(R.string.conversation_binding_consent)).performScrollTo().performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.binding_confirm_computer)).performScrollTo().assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, confirmations) }
    }
    @Test fun capabilityCodeIsNeverRestoredFromSavedInstanceState() {
        val restore = StateRestorationTester(rule)
        restore.setContent { TokenWorksTheme { HostBindingScreen(HostBindingState(id = id), "fixture-account", { _, _ -> }, {}, {}, {}, {}, {}) } }
        rule.onNodeWithText(rule.activity.getString(R.string.binding_review_computer)).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText(rule.activity.getString(R.string.binding_code_label)).performScrollTo().performTextInput(code)
        rule.onNodeWithText(rule.activity.getString(R.string.binding_review_computer)).performScrollTo().assertIsEnabled()
        restore.emulateSavedInstanceStateRestore()
        rule.onNodeWithText(rule.activity.getString(R.string.binding_review_computer)).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText(rule.activity.getString(R.string.binding_request_number)).performScrollTo().assertTextContains(id)
    }
    @Test fun unknownConfirmationExposesAStatusReadAndNeverAnotherConfirmationButton() {
        var queries = 0
        rule.setContent { TokenWorksTheme { HostBindingScreen(HostBindingState(HostBindingPhase.UNKNOWN, id, details(), "network.unavailable"), "fixture-account", { _, _ -> }, {}, {}, { queries++ }, {}, {}) } }
        rule.onNodeWithText(rule.activity.getString(R.string.binding_result_unknown)).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.binding_confirm_computer)).assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.flow_binding_phone)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.flow_binding_host)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.flow_binding_access)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.binding_check_status)).performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, queries) }
    }
    @Test fun unknownWithoutReviewFactsRetainsTheOriginalPublicIdentifierWithoutASecretForm() {
        var queries = 0
        rule.setContent { TokenWorksTheme { HostBindingScreen(HostBindingState(HostBindingPhase.UNKNOWN, id), "fixture-account", { _, _ -> }, {}, {}, { queries++ }, {}, {}) } }
        rule.onNodeWithText(id).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.binding_request_number)).assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.binding_code_label)).assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.binding_confirm_computer)).assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.binding_check_status)).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.runOnIdle { assertEquals(1, queries) }
    }
    @Test fun alreadyConfirmedRequestsCanBeQueriedWithOnlyTheirPublicIdentifier() {
        var queried: String? = null; var previews = 0
        rule.setContent { TokenWorksTheme { HostBindingScreen(HostBindingState(id = id), "fixture-account", { _, _ -> previews++ }, { queried = it }, {}, {}, {}, {}) } }
        rule.onNodeWithText(rule.activity.getString(R.string.binding_review_computer)).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText(rule.activity.getString(R.string.binding_query_confirmed)).performScrollTo().assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(id, queried); assertEquals(0, previews) }
    }
    @Test fun landscapeTabletLayoutWithLargeTextKeepsRecoveryAndBackActionsReachable() {
        rule.runOnUiThread { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(10_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        var queries = 0; var backs = 0
        val public = details()
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density * 0.7f, 1.8f)) {
                TokenWorksTheme(darkTheme = false) { HostBindingScreen(HostBindingState(HostBindingPhase.UNKNOWN, id, public), "fixture-account", { _, _ -> }, {}, {}, { queries++ }, {}, { backs++ }) }
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.binding_check_status)).performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.flow_binding_back)).performClick()
        rule.runOnIdle { assertEquals(1, queries); assertEquals(1, backs) }
        // Export only synthetic public UI state; the live activity remains secure.
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "ui-fixtures").apply { mkdirs() }
        File(directory, "binding-landscape.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
