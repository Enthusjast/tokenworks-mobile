package cn.edu.ustc.tokenworks

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** Synthetic presentation fixtures only; no real credentials, readers or authentication bypass. */
@RunWith(AndroidJUnit4::class)
class TokenWorksAppShellTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var fixtureDensity = Density(1f)
    private val runtimeA = "11111111-1111-4111-8111-111111111111"
    // Deliberately share the alias AND first eight characters: short runtime IDs are ambiguous.
    private val runtimeB = "11111111-1111-4111-8111-222222222222"
    private val sessionA = "33333333-3333-4333-8333-333333333333"
    private val sessionB = "44444444-4444-4444-8444-444444444444"
    private val workspace = "55555555-5555-4555-8555-555555555555"
    private lateinit var controller: LoginController
    private lateinit var model: HomeViewModel

    @Before fun createPresentationOwner() {
        val slot = "shell-presentation-${UUID.randomUUID()}"
        rule.runOnUiThread {
            // Current composables require the real view model. Keep its account unconfigured:
            // presentation state below is public synthetic data, not an injected login session.
            controller = LoginController(
                InstrumentationRegistry.getInstrumentation().targetContext,
                LoginHttp.create(),
                LoginEnvironment({ throw FileNotFoundException("Public presentation fixture") }, slot, "$slot-keys"),
            )
            model = HomeViewModel(controller)
        }
    }

    @After fun closePresentationOwner() {
        rule.runOnUiThread { controller.close() }
    }

    private fun runtime(
        id: String,
        actions: List<String> = listOf("session/list", "session/follow"),
        alias: String = "同名电脑",
        resource: NativeResource = NativeResource("global", null),
    ): RuntimeRow {
        val grant = RuntimeDirectoryEntry.parse(jsonObject(
            "id" to id.json(), "host_id" to workspace.json(), "organization_id" to workspace.json(),
            "dsh_version" to "0.2.1".json(),
            "access" to jsonObject(
                "role" to "owner".json(), "grant_version" to 1L.json(),
                "bindings" to JsonArray(listOf(jsonObject(
                    "resource" to jsonObject("kind" to resource.kind.json(), "id" to (resource.id?.json() ?: JsonNull)),
                    "actions" to JsonArray(actions.map { it.json() }),
                ))),
            ),
        ))
        return RuntimeRow(id, workspace, "0.2.1", alias, grant)
    }

    private fun session(
        id: String,
        title: String = "合成会话 $id",
        directory: String? = "/synthetic/workspace",
        running: Boolean = false,
        agentAvailable: Boolean = true,
    ) = SessionDirectoryEntry.from(DshSessionListResultItemsItem(
        agentAvailable = agentAvailable,
        sessionId = id,
        updatedAt = 1_793_491_200_000.0,
        running = running,
        blank = false,
        projections = DshOptional.Value(DshSessionListResultItemsItemProjections(
            kind = "cached",
            asOfSeq = 4.0,
            values = DshSessionListResultItemsItemProjectionsValues(
                title = DshOptional.Value(title),
                workingDirectory = DshOptional.Value(directory),
            ),
        )),
    ))

    private fun state(rows: List<RuntimeRow> = listOf(runtime(runtimeA))) = HomeState(
        phase = HomePhase.READY, issuer = "https://id.example.edu", subject = "synthetic-student",
        deviceId = runtimeB, deviceName = "合成测试手机", runtimes = rows,
        selectedRuntimeId = runtimeA, sessions = listOf(session(sessionA)),
    )

    @Composable
    private fun FixtureTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        SideEffect { fixtureDensity = density }
        TokenWorksTheme(darkTheme = darkTheme, content = content)
    }

    @Composable
    private fun Shell(
        state: HomeState,
        onHosts: () -> Unit = {},
        onSettings: () -> Unit = {},
        onCloseNavigation: () -> Unit = {},
    ) {
        TokenWorksAppShell(state, model, onHosts, onSettings, onCloseNavigation)
    }

    private fun row(title: String) = hasText(title) and hasClickAction()
    private fun label(id: Int) = rule.activity.getString(id)

    private fun scrollTo(matcher: SemanticsMatcher) {
        rule.onNode(hasScrollAction()).performScrollToNode(matcher)
    }

    @Test fun semanticNamesKeepNativeSessionIdentitiesAndWorkingDirectories() {
        val entries = listOf(
            session(sessionA, "同名原生会话", "/synthetic/first", running = true),
            session(sessionB, "同名原生会话", "/synthetic/second"),
        )
        rule.setContent { FixtureTheme { Shell(state().copy(sessions = entries)) } }
        rule.onAllNodes(row("同名原生会话")).assertCountEquals(2)
        rule.onNode(row("同名原生会话") and hasText("/synthetic/first")).assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, label(R.string.session_running)))
        rule.onNode(row("同名原生会话") and hasText("/synthetic/second")).assertIsDisplayed()
        assertEquals(listOf(sessionA, sessionB), entries.map { it.id })
    }

    @Test fun readableSessionHasExactlyOneWholeRowAction() {
        rule.setContent { FixtureTheme(darkTheme = false) { Shell(state().copy(selectedSessionId = sessionA)) } }
        rule.onAllNodes(row("合成会话 $sessionA")).assertCountEquals(1)
        rule.onNode(row("合成会话 $sessionA")).assertIsEnabled().assertIsSelected()
        rule.onNodeWithText("读取会话").assertDoesNotExist()
        saveFixture("session-directory-light", dark = false)
    }

    @Test fun runtimeSelectionDistinguishesSameAliasAndPrefixAndHonorsListGrants() {
        val selected = mutableListOf<String>()
        val current = state(listOf(runtime(runtimeA), runtime(runtimeB), runtime(workspace, listOf("session/follow"))))
        rule.setContent { FixtureTheme(darkTheme = true) {
            RuntimePicker(current, model, onRuntime = { selected += it })
        } }
        rule.onNodeWithTag("computer-open-$runtimeA").assertTextContains(runtimeA, substring = true).assertIsEnabled()
        rule.onNodeWithTag("computer-open-$runtimeB").assertTextContains(runtimeB, substring = true)
            .performScrollTo().performClick()
        rule.onNodeWithTag("computer-open-$workspace").performScrollTo().assertIsNotEnabled()
            .performTouchInput { click() }
        rule.runOnIdle { assertEquals(listOf(runtimeB), selected) }
        saveFixture("computers-grant-selection-dark", dark = true)
    }

    @Test fun collidingRuntimeAbbreviationsKeepFullIdentifiersReachableInSelection() {
        val collision = "11111111-2222-4222-8222-222222221111"
        rule.setContent { FixtureTheme {
            RuntimePicker(state(listOf(runtime(runtimeA), runtime(collision))), model)
        } }
        for (id in listOf(runtimeA, collision)) {
            rule.onNodeWithTag("computer-open-$id").performScrollTo().assertTextContains(id, substring = true)
                .assertIsDisplayed()
        }
    }

    @Test fun refreshRetainsDisabledRowsUntilTheDirectoryIsReady() {
        var current by mutableStateOf(state())
        rule.setContent { FixtureTheme { Shell(current) } }
        rule.onNode(row("合成会话 $sessionA")).assertIsEnabled()
        rule.runOnIdle { current = current.copy(loadingSessions = true) }
        rule.onNode(row("合成会话 $sessionA")).assertIsNotEnabled()
        rule.onNodeWithContentDescription(label(R.string.action_refresh)).assertIsNotEnabled()
        scrollTo(hasText(label(R.string.session_loading)))
        rule.onNodeWithText(label(R.string.session_loading)).assertIsDisplayed()
        rule.runOnIdle { current = current.copy(loadingSessions = false) }
        rule.onNode(row("合成会话 $sessionA")).assertIsEnabled()
    }

    @Test fun sessionActionsUseNativeResourceBindings() {
        val grant = runtime(runtimeA, listOf("session/list", "session/follow", "session/rename"),
            resource = NativeResource("session", sessionA))
        var current by mutableStateOf(state(listOf(grant)))
        rule.setContent { FixtureTheme { Shell(current) } }
        rule.onNode(row("合成会话 $sessionA")).performTouchInput { longClick() }
        rule.onNodeWithText(label(R.string.session_rename)).assertIsEnabled()
        rule.onNodeWithText(label(R.string.session_fork)).assertIsNotEnabled()
        rule.onNodeWithText(label(R.string.conversation_archive)).assertIsNotEnabled()
        rule.onNodeWithText(label(R.string.action_cancel)).performClick()
        rule.runOnIdle { current = current.copy(sessions = listOf(session(sessionB))) }
        rule.onNode(row("合成会话 $sessionB")).performTouchInput { longClick() }
        rule.onNodeWithText(label(R.string.session_rename)).assertIsNotEnabled()
        rule.onNodeWithText(label(R.string.action_cancel)).performClick()
    }

    @Test fun shellNavigationInvokesHostsSettingsAndCloseCallbacks() {
        var hosts = 0
        var settings = 0
        var closes = 0
        val current = state()
        rule.setContent { FixtureTheme { Shell(current, { hosts++ }, { settings++ }, { closes++ }) } }
        rule.onNode(hasText("同名电脑") and hasClickAction()).performClick()
        rule.onNodeWithContentDescription(label(R.string.nav_settings)).performClick()
        rule.onNodeWithContentDescription(label(R.string.workspace_close_navigation)).performClick()
        rule.runOnIdle {
            assertEquals(1, hosts)
            assertEquals(1, settings)
            assertEquals(1, closes)
        }
    }

    @Test fun selectingAnotherSessionClosesNavigationOnlyOnce() {
        var current by mutableStateOf(state())
        var closes = 0
        rule.setContent { FixtureTheme { Shell(current, onCloseNavigation = { closes++ }) } }
        rule.runOnIdle { assertEquals(0, closes); current = current.copy(selectedSessionId = sessionA) }
        rule.runOnIdle { assertEquals(1, closes); current = current.copy(deviceName = "更新的合成手机") }
        rule.runOnIdle { assertEquals(1, closes); current = current.copy(selectedSessionId = sessionB) }
        rule.runOnIdle { assertEquals(2, closes) }
    }

    @Test fun settingsRequireLogoutConfirmationAndCancelDoesNotExit() {
        var exits = 0
        rule.setContent { FixtureTheme(darkTheme = false) { WorkspaceSettings(state(), {}, {}, { exits++ }, {}) } }
        saveFixture("settings-light", dark = false)
        clickSettingsItem(label(R.string.action_exit_account))
        rule.onNodeWithText(label(R.string.settings_exit_title)).assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, exits) }
        rule.onNodeWithText(label(R.string.action_cancel)).performClick()
        rule.runOnIdle { assertEquals(0, exits) }
        clickSettingsItem(label(R.string.action_exit_account))
        rule.onNode(hasText(label(R.string.action_exit_account)) and hasAnyAncestor(isDialog())).performClick()
        rule.runOnIdle { assertEquals(1, exits) }
    }

    @Test fun narrowLargeTextComputerActionsAndFullIdentifiersRemainReachable() {
        val longAlias = "合成长名称".repeat(32)
        val opened = mutableListOf<String>()
        rule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                FixtureTheme(darkTheme = false) { Box(Modifier.width(320.dp).height(480.dp)) {
                    RuntimePicker(state(listOf(runtime(runtimeA, alias = longAlias))), model, onRuntime = { opened += it })
                } }
            }
        }
        rule.onNodeWithText(label(R.string.action_bind_computer)).performScrollTo().assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithText(longAlias, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.computer_runtime, runtimeA), useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed().performTouchInput { click() }
        rule.runOnIdle { assertEquals(listOf(runtimeA), opened) }
        saveFixture("computers-narrow-large-text", dark = false)
    }

    @Test fun shortLandscapeLargeTextSettingsActionsRemainReachable() {
        rule.runOnUiThread { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(10_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        val account = state().copy(presentationOwner = CommandOwner(runtimeA, workspace, "a".repeat(64), sessionA, runtimeB))
        var appearances = 0
        var updates = 0
        rule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                FixtureTheme(darkTheme = true) { Box(Modifier.width(640.dp).height(320.dp)) {
                    WorkspaceSettings(account, { appearances++ }, { updates++ }, {}, {})
                } }
            }
        }
        clickSettingsItem(label(R.string.directory_appearance_entry))
        saveFixture("settings-landscape-large-text-dark", dark = true)
        clickSettingsItem(label(R.string.settings_updates))
        clickSettingsItem(label(R.string.action_exit_account))
        rule.onNodeWithText(label(R.string.action_cancel)).assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, appearances); assertEquals(1, updates) }
    }

    @Test fun wideWindowKeepsDirectoryAndNavigationUsable() {
        var hosts = 0
        var settings = 0
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(.75f, 1f)) {
                FixtureTheme { Box(Modifier.width(840.dp).height(600.dp)) {
                    Shell(state(), onHosts = { hosts++ }, onSettings = { settings++ })
                } }
            }
        }
        rule.onNode(row("合成会话 $sessionA")).assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithContentDescription(label(R.string.nav_settings)).performClick()
        rule.onNode(hasText("同名电脑") and hasClickAction()).performClick()
        rule.runOnIdle { assertEquals(1, hosts); assertEquals(1, settings) }
        saveFixture("sessions-wide-window", dark = false)
    }

    @Test fun sessionCreationTracksCurrentNativeGrant() {
        var current by mutableStateOf(state(listOf(runtime(runtimeA, listOf("session/list", "session/create")))))
        rule.setContent { FixtureTheme { Shell(current) } }
        rule.onNodeWithContentDescription(label(R.string.action_create_session)).assertIsEnabled()
        rule.runOnIdle { current = current.copy(runtimes = listOf(runtime(runtimeA, listOf("session/list")))) }
        rule.onNodeWithContentDescription(label(R.string.action_create_session)).assertIsNotEnabled()
    }

    @Test fun loadingErrorAndEmptyDirectoriesRemainDistinct() {
        var current by mutableStateOf(state().copy(loadingSessions = true, sessions = emptyList()))
        rule.setContent { FixtureTheme { Shell(current) } }
        rule.onNodeWithText(label(R.string.session_loading)).assertExists()
        rule.onNodeWithText(label(R.string.session_empty)).assertDoesNotExist()
        rule.runOnIdle { current = current.copy(loadingSessions = false, sessionError = "network.unavailable") }
        rule.onNodeWithText(label(R.string.session_loading)).assertDoesNotExist()
        rule.onNodeWithText(label(R.string.session_empty)).assertDoesNotExist()
        rule.onNodeWithText(label(R.string.session_directory_error)).assertIsDisplayed()
        rule.onNodeWithText("network.unavailable").assertIsDisplayed()
        rule.onNodeWithText(label(R.string.action_retry)).assertIsEnabled()
        rule.runOnIdle { current = current.copy(sessionError = null) }
        rule.onNodeWithText(label(R.string.session_directory_error)).assertDoesNotExist()
        rule.onNodeWithText(label(R.string.session_empty)).assertExists()
        rule.runOnIdle { current = current.copy(sessions = listOf(session(sessionA))) }
        rule.onNode(row("合成会话 $sessionA")).assertExists()
        rule.onNodeWithText(label(R.string.session_empty)).assertDoesNotExist()
    }

    @Test fun appearanceRequiresAnOwnerAndHasAWorkingCallbackAfterOneIsAvailable() {
        var current by mutableStateOf(state())
        var opened = 0
        rule.setContent { FixtureTheme { WorkspaceSettings(current, { opened++ }, {}, {}, {}) } }
        scrollTo(hasText(label(R.string.directory_appearance_entry)))
        rule.onNode(hasText(label(R.string.directory_appearance_entry)) and hasClickAction()).assertIsNotEnabled()
        rule.runOnIdle { current = current.copy(presentationOwner = CommandOwner(runtimeA, workspace, "a".repeat(64), sessionA, runtimeB)) }
        clickSettingsItem(label(R.string.directory_appearance_entry))
        rule.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun accountDetailsKeepFullIdentifiersReachable() {
        val account = state().copy(subject = "full-account-subject-".repeat(6),
            issuer = "https://identity.example.edu/school/complete-issuer")
        var backs = 0
        rule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                FixtureTheme { Box(Modifier.width(320.dp).height(480.dp)) {
                    WorkspaceSettings(account, {}, {}, {}, { backs++ })
                } }
            }
        }
        for (identifier in listOf(account.subject, account.issuer, account.deviceId)) {
            scrollTo(hasText(identifier))
            rule.onNodeWithText(identifier).assertIsDisplayed()
        }
        rule.onNodeWithContentDescription(label(R.string.action_close)).performClick()
        rule.runOnIdle { assertEquals(1, backs) }
    }

    @Test fun computerHasOnePrimaryDirectoryActionAndNeverInventsPresence() {
        val opened = mutableListOf<String>()
        rule.setContent { FixtureTheme {
            RuntimePicker(state(), model, onRuntime = { opened += it })
        } }
        rule.onNodeWithText(label(R.string.computer_presence_unknown), useUnmergedTree = true).assertExists()
        rule.onAllNodes(hasTestTag("computer-open-$runtimeA") and hasClickAction()).assertCountEquals(1)
        rule.onNodeWithTag("computer-open-$runtimeA").performScrollTo().assertTextContains(runtimeA, substring = true)
            .performClick()
        rule.runOnIdle { assertEquals(listOf(runtimeA), opened) }
    }

    @Test fun computerLoadingAndErrorsRemainExplicit() {
        var current by mutableStateOf(state(emptyList()).copy(refreshing = true))
        rule.setContent { FixtureTheme { RuntimePicker(current, model) } }
        rule.onNodeWithText(label(R.string.directory_loading)).assertExists()
        rule.onNodeWithText(label(R.string.computer_empty)).assertDoesNotExist()
        rule.runOnIdle { current = current.copy(refreshing = false, error = "network.unavailable") }
        rule.onNodeWithText(label(R.string.directory_loading)).assertDoesNotExist()
        scrollTo(hasText(label(R.string.workspace_error_operation)))
        rule.onNodeWithText(label(R.string.workspace_error_operation)).assertIsDisplayed()
        rule.runOnIdle { current = current.copy(error = null) }
        rule.onNodeWithText(label(R.string.workspace_error_operation)).assertDoesNotExist()
        scrollTo(hasText(label(R.string.computer_empty)))
        rule.onNodeWithText(label(R.string.computer_empty)).assertIsDisplayed()
    }

    private fun clickSettingsItem(label: String) {
        scrollTo(hasText(label))
        rule.onNodeWithText(label).assertIsDisplayed().performClick()
    }


    /** Captures only this synthetic ComponentActivity, never the FLAG_SECURE production activity. */
    private fun saveFixture(scene: String, dark: Boolean, dialog: Boolean = false) {
        val root = if (dialog) rule.onNode(isDialog()) else rule.onRoot()
        val rootBounds = root.fetchSemanticsNode().boundsInRoot
        val useDisplayCapture = dialog && android.os.Build.VERSION.SDK_INT < 28
        var captureMethod = if (useDisplayCapture) "uiAutomation-display" else "compose-root"
        val bitmap = if (useDisplayCapture) {
            check(rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0) {
                "Only the public synthetic fixture may use display capture"
            }
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.takeScreenshot() ?: run {
                captureMethod = "shell-display"
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p")).use { it.readBytes() }
                checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Cannot capture the synthetic dialog display" }
            }
        } else {
            root.captureToImage().asAndroidBitmap()
        }
        val density = fixtureDensity
        val directory = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "ui-navigation-fixtures",
        ).apply { check(isDirectory || mkdirs()) }
        val image = File(directory, "$scene.png.part")
        image.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        check(image.renameTo(File(directory, "$scene.png")))
        val metadata = JSONObject()
            .put("scene", scene)
            .put("density", density.density)
            .put("fontScale", density.fontScale)
            .put("dark", dark)
            .put("captureMethod", captureMethod)
            .put("rootSize", JSONObject()
                .put("widthPx", rootBounds.width)
                .put("heightPx", rootBounds.height)
                .put("widthDp", rootBounds.width / density.density)
                .put("heightDp", rootBounds.height / density.density))
            .put("imageSize", JSONObject()
                .put("widthPx", bitmap.width)
                .put("heightPx", bitmap.height))
            .put("build", JSONObject()
                .put("applicationId", BuildConfig.APPLICATION_ID)
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE)
                .put("buildType", BuildConfig.BUILD_TYPE))
        val metadataFile = File(directory, "$scene.json.part")
        metadataFile.writeText(metadata.toString(2))
        check(metadataFile.renameTo(File(directory, "$scene.json")))
    }
}
