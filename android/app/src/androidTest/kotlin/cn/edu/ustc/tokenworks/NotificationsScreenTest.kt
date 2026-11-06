package cn.edu.ustc.tokenworks

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.enthusjast.tokenworks.protocol.*
import io.github.enthusjast.tokenworks.ui.TokenWorksTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationsScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun emptyReferencesNeverClaimAllRequestsAreHandledAndUnconfiguredPushHasAReachableExit() {
        var state by mutableStateOf(NotificationUiState(phase = NotificationPhase.ACTIVE, allowed = true))
        var refreshes = 0
        var backs = 0
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                TokenWorksTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp)) {
                        NotificationsScreen(state, { refreshes++ }, {}, {}, {}, {}, {}, { _, _ -> }, { backs++ })
                    }
                }
            }
        }
        val empty = rule.activity.getString(R.string.flow_notifications_no_references)
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(empty))
        rule.onNodeWithText(empty).assertIsDisplayed()
        rule.onNodeWithText("全部已处理").assertDoesNotExist()
        rule.runOnIdle { state = NotificationUiState(phase = NotificationPhase.UNCONFIGURED, allowed = true) }
        rule.onNodeWithText("启用此账号通知").assertDoesNotExist()
        val details = rule.activity.getString(R.string.workspace_panel_details)
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(details))
        rule.onNodeWithText(details).performClick()
        rule.runOnIdle { assertEquals(0, refreshes) }
        val foreground = rule.activity.getString(R.string.flow_notifications_foreground)
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(foreground))
        rule.onNodeWithText(foreground).performScrollTo().assertIsDisplayed()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("核对通道状态"))
        rule.onNodeWithText("核对通道状态").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithContentDescription("返回设置").assertHeightIsAtLeast(48.dp).performClick()
        rule.runOnIdle { assertEquals(1, refreshes); assertEquals(1, backs) }
    }

    @Test fun referencesOpenTheirExactBindingAndPagingDoesNotPretendToHandleThem() {
        val notification = "11111111-1111-4111-8111-111111111111"
        val binding = "22222222-2222-4222-8222-222222222222"
        val reference = NotificationReference.parse(jsonObject(
            "notification_id" to notification.json(), "binding_id" to binding.json(), "kind" to "approval".json(),
            "reference_id" to "33333333-3333-4333-8333-333333333333".json(),
            "runtime_id" to "44444444-4444-4444-8444-444444444444".json(),
            "runtime_epoch" to "55555555-5555-4555-8555-555555555555".json(),
            "session_id" to "66666666-6666-4666-8666-666666666666".json(),
            "state" to "pending".json(), "availability" to "online".json(),
            "expires_at" to Contract.timestamp(System.currentTimeMillis() + 60_000).json()))
        var opened: Pair<String, String?>? = null
        var pages = 0
        rule.setContent { TokenWorksTheme {
            NotificationsScreen(NotificationUiState(phase = NotificationPhase.ACTIVE, allowed = true,
                items = listOf(reference), cursor = notification), {}, {}, {}, {}, {}, { pages++ },
                { owner, id -> opened = owner to id }, {})
        } }
        val open = rule.activity.getString(R.string.workspace_notifications_open)
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(open))
        rule.onNodeWithText(open).assertHeightIsAtLeast(48.dp).performClick()
        rule.runOnIdle { assertEquals(binding to notification, opened); assertEquals(0, pages) }
        val more = rule.activity.getString(R.string.workspace_notifications_more)
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(more))
        rule.onNodeWithText(more).performClick()
        rule.runOnIdle { assertEquals(1, pages); assertEquals(binding to notification, opened) }
        rule.onNodeWithText("允许一次").assertDoesNotExist()
    }
}
