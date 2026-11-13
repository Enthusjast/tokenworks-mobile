package cn.edu.ustc.tokenworks

import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.edu.ustc.tokenworks.TokenWorksTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Public synthetic content only: no readers, credentials, pickers or device configuration changes. */
@RunWith(AndroidJUnit4::class)
class WorkspaceScaffoldTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun closedDrawerDoesNotMountNavigationOrStealHorizontalGestures() {
        rule.setContent {
            TokenWorksTheme {
                WorkspaceScaffold(
                    workspaceLayout(WorkspaceViewport(360, 800), false, false, true), true,
                    railContent = { Text("rail-entry") },
                    navigationContent = { Text("drawer-entry") },
                    content = { openNavigation -> Button(onClick = openNavigation) { Text("open-navigation") } },
                )
            }
        }
        rule.onNodeWithText("drawer-entry").assertDoesNotExist()
        rule.onNodeWithTag("workspace-content").performTouchInput { swipeRight() }
        rule.onNodeWithText("drawer-entry").assertDoesNotExist()
        rule.onNodeWithText("open-navigation").performClick()
        rule.onNodeWithText("drawer-entry").assertIsDisplayed()
        // Native open-state dragging remains available, while the main content is not exposed.
        rule.onNodeWithText("open-navigation").assertDoesNotExist()
        rule.onNodeWithTag("workspace-drawer").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithTag("workspace-drawer").assertDoesNotExist()
        rule.onNodeWithText("drawer-entry").assertDoesNotExist()
        rule.onNodeWithText("open-navigation").assertIsDisplayed()
    }

    @Test fun backClosesDrawerBeforeTheUnderlyingRouteAndRemovesItsContent() {
        var routeBacks = 0
        rule.setContent {
            TokenWorksTheme {
                WorkspaceScaffold(
                    workspaceLayout(WorkspaceViewport(600, 960), false, false, true), true,
                    railContent = { Text("rail-owner") },
                    navigationContent = { Text("navigation-owner") },
                    content = { openNavigation ->
                        BackHandler { routeBacks++ }
                        Button(onClick = openNavigation) { Text("route-owner") }
                    },
                )
            }
        }
        rule.onNodeWithText("route-owner").performClick()
        rule.onNodeWithText("navigation-owner").assertIsDisplayed()
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("navigation-owner").assertDoesNotExist()
        rule.onNodeWithText("route-owner").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, routeBacks) }
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.runOnIdle { assertEquals(1, routeBacks) }
    }

    @Test fun chromeHiddenDoesNotMountNavigationOrReserveAnyNavigationWidth() {
        var chrome by mutableStateOf(false)
        rule.setContent {
            TokenWorksTheme {
                Box(Modifier.fillMaxSize().testTag("fixture-root")) {
                    WorkspaceScaffold(
                        workspaceLayout(WorkspaceViewport(1280, 800), true, false, true), chrome,
                        railContent = { Text("private-rail") },
                        navigationContent = { Text("private-navigation") },
                        content = { Text("full-width-task") },
                    )
                }
            }
        }
        rule.onNodeWithTag("workspace-navigation").assertDoesNotExist()
        rule.onNodeWithTag("workspace-rail").assertDoesNotExist()
        rule.onNodeWithTag("workspace-drawer").assertDoesNotExist()
        rule.onNodeWithText("private-navigation").assertDoesNotExist()
        val fullWidth = rule.onNodeWithTag("fixture-root").getUnclippedBoundsInRoot().let { it.right - it.left }
        rule.onNodeWithTag("workspace-content").assertWidthIsEqualTo(fullWidth)
        rule.runOnIdle { chrome = true }
        rule.onNodeWithTag("workspace-navigation").assertWidthIsEqualTo(320.dp)
    }

    @Test fun openingThePanelFoldsTheSidebarToTheRailAndRestoresIt() {
        var details by mutableStateOf(false)
        var expanded by mutableStateOf(true)
        var viewport by mutableStateOf(WorkspaceViewport(960, 800))
        rule.setContent {
            TokenWorksTheme {
                WorkspaceScaffold(
                    workspaceLayout(viewport, true, details, expanded), true,
                    railContent = { Text("rail-directory") },
                    navigationContent = { Text("one-directory") },
                    content = { Text("chat") },
                )
            }
        }
        rule.onNodeWithTag("workspace-navigation").assertWidthIsEqualTo(320.dp)
        rule.runOnIdle { details = true }
        rule.onNodeWithTag("workspace-navigation").assertDoesNotExist()
        rule.onNodeWithTag("workspace-rail").assertWidthIsEqualTo(68.dp)
        rule.runOnIdle { viewport = WorkspaceViewport(1440, 800) }
        rule.onNodeWithTag("workspace-navigation").assertWidthIsEqualTo(320.dp)
        rule.onNodeWithTag("workspace-rail").assertDoesNotExist()
        rule.runOnIdle { expanded = false; details = false }
        rule.onNodeWithTag("workspace-navigation").assertDoesNotExist()
        rule.onNodeWithTag("workspace-rail").assertWidthIsEqualTo(68.dp)
        rule.runOnIdle { viewport = WorkspaceViewport(839, 800) }
        rule.onNodeWithText("one-directory").assertDoesNotExist()
        rule.onNodeWithTag("workspace-rail").assertDoesNotExist()
        rule.runOnIdle { viewport = WorkspaceViewport(1120, 800) }
        rule.onNodeWithTag("workspace-rail").assertWidthIsEqualTo(68.dp)
        rule.onAllNodesWithTag("workspace-rail").assertCountEquals(1)
    }

    @Test fun exactlyOneNavigationColumnIsMountedPerLayout() {
        var viewport by mutableStateOf(WorkspaceViewport(1440, 800))
        var details by mutableStateOf(false)
        rule.setContent {
            TokenWorksTheme {
                WorkspaceScaffold(
                    workspaceLayout(viewport, true, details, true), true,
                    railContent = { Text("rail-entry") },
                    navigationContent = { Text("sidebar-entry") },
                    content = { Text("chat") },
                )
            }
        }
        rule.onNodeWithText("sidebar-entry").assertIsDisplayed()
        rule.onNodeWithText("rail-entry").assertDoesNotExist()
        rule.runOnIdle { viewport = WorkspaceViewport(960, 800); details = true }
        rule.onNodeWithTag("workspace-navigation").assertDoesNotExist()
        rule.onNodeWithText("sidebar-entry").assertDoesNotExist()
        rule.onNodeWithText("rail-entry").assertIsDisplayed()
        rule.runOnIdle { viewport = WorkspaceViewport(360, 800) }
        rule.onNodeWithTag("workspace-rail").assertDoesNotExist()
        rule.onNodeWithTag("workspace-navigation").assertDoesNotExist()
    }

    @Test fun activityFreeContextUsesCurrentConfigurationWithoutSubtractingInsetsAgain() {
        var measured: WorkspaceViewport? = null
        rule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = 731
                screenHeightDp = 913
            }
            val application = LocalContext.current.applicationContext
            CompositionLocalProvider(
                LocalContext provides ContextWrapper(ContextWrapper(application)),
                LocalConfiguration provides configuration,
                LocalDensity provides Density(2f),
            ) {
                measured = rememberWorkspaceViewport()
            }
        }
        rule.runOnIdle { assertEquals(WorkspaceViewport(731, 913), measured) }
    }

    @Test fun panelPresentationChangesKeepOnlyOneForegroundAndHideBackgroundActions() {
        var presentation by mutableStateOf(TaskPresentation.PAGE)
        var backgroundActions = 0
        var dismissals = 0
        rule.setContent {
            TokenWorksTheme {
                Box(Modifier.fillMaxSize().testTag("panel-root")) {
                    AdaptiveSessionPanel(
                        workspaceLayout(WorkspaceViewport(1280, 800), true, true, true),
                        presentation, { dismissals++ },
                        backdrop = { Button(onClick = { backgroundActions++ }) { Text("background-action") } },
                        content = { Text("foreground", Modifier.testTag("panel-foreground")) },
                    )
                }
            }
        }
        rule.onNodeWithTag("workspace-panel-page").assertIsDisplayed()
        rule.onAllNodesWithTag("panel-foreground").assertCountEquals(1)
        rule.onNodeWithText("background-action").assertDoesNotExist()
        rule.runOnIdle { presentation = TaskPresentation.SHEET }
        rule.onNodeWithTag("workspace-panel-sheet").assertIsDisplayed()
        rule.onAllNodesWithTag("panel-foreground").assertCountEquals(1)
        rule.onNodeWithText("background-action").assertDoesNotExist()
        rule.runOnIdle { presentation = TaskPresentation.SIDE_PANEL }
        rule.onNodeWithTag("workspace-panel-side").assertWidthIsEqualTo(384.dp)
        rule.onAllNodesWithTag("panel-foreground").assertCountEquals(1)
        rule.onNodeWithText("background-action").assertDoesNotExist()
        rule.onNodeWithTag("panel-root").performTouchInput { click(Offset(2f, centerY)) }
        rule.runOnIdle { assertEquals(0, backgroundActions); assertEquals(1, dismissals) }
    }

    @Test fun panelBackDelegatesToCallerAndDoesNotInstallASecondBusinessOwner() {
        var dismissals = 0
        rule.setContent {
            TokenWorksTheme {
                AdaptiveSessionPanel(
                    workspaceLayout(WorkspaceViewport(360, 800), true, true, true),
                    TaskPresentation.PAGE, { dismissals++ }, backdrop = {}, content = { Text("task") },
                )
            }
        }
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun openDrawerSurvivesRailLayoutChangesAndClosesThroughNavigationCallback() {
        var layout by mutableStateOf(workspaceLayout(WorkspaceViewport(360, 800), false, false, true))
        rule.setContent {
            TokenWorksTheme {
                WorkspaceScaffold(
                    layout, true,
                    railContent = { Text("rail-entry") },
                    navigationContent = { closeNavigation -> Button(closeNavigation) { Text("close directory") } },
                    content = { openNavigation -> Button(openNavigation) { Text("open directory") } },
                )
            }
        }
        rule.onNodeWithText("open directory").performClick()
        rule.onNodeWithTag("workspace-drawer").assertIsDisplayed()
        rule.runOnIdle { layout = workspaceLayout(WorkspaceViewport(840, 960), true, true, true) }
        rule.onNodeWithTag("workspace-drawer").assertIsDisplayed()
        rule.runOnIdle { layout = workspaceLayout(WorkspaceViewport(840, 960), true, true, false) }
        rule.onNodeWithTag("workspace-drawer").assertIsDisplayed()
        rule.onNodeWithText("close directory").performClick()
        rule.onNodeWithTag("workspace-drawer").assertDoesNotExist()
        rule.onNodeWithTag("workspace-rail").assertWidthIsEqualTo(68.dp)
        rule.runOnIdle { layout = workspaceLayout(WorkspaceViewport(360, 800), false, false, true) }
        rule.onNodeWithTag("workspace-drawer").assertDoesNotExist()
        rule.onNodeWithText("open directory").assertIsDisplayed()
    }

    @Test fun sheetIsCappedAtNinetyPercentOfAvailableHeight() {
        rule.setContent {
            TokenWorksTheme {
                Box(Modifier.fillMaxSize().testTag("panel-root")) {
                    AdaptiveSessionPanel(
                        workspaceLayout(WorkspaceViewport(600, 960), false, false, true),
                        TaskPresentation.SHEET, {}, backdrop = {}, content = {
                            Box(Modifier.fillMaxWidth().height(2000.dp)) { Text("long-task") }
                        },
                    )
                }
            }
        }
        val rootHeight = rule.onNodeWithTag("panel-root").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        val sheetHeight = rule.onNodeWithTag("workspace-panel-sheet").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertTrue("sheet=$sheetHeight root=$rootHeight", sheetHeight <= rootHeight * 0.9f + 1.dp)
    }
}
