package cn.edu.ustc.tokenworks

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechInputSheetTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val visible = mutableStateOf(true)
    private val inserted = mutableListOf<String>()
    private var dismissals = 0
    private lateinit var owner: SheetLifecycleOwner
    private val permission = SyntheticPermissionRegistry()

    private fun mount(coordinator: SpeechInputCoordinator) {
        rule.runOnUiThread { owner = SheetLifecycleOwner() }
        rule.setContent {
            // TEST ONLY: never launch an actual microphone permission dialog or acquire audio.
            CompositionLocalProvider(
                LocalContext provides DeniedMicrophoneContext(rule.activity),
                LocalLifecycleOwner provides owner,
                LocalActivityResultRegistryOwner provides permission,
            ) {
                MaterialTheme {
                    if (visible.value) SpeechInputSheet(
                        onInsert = { inserted += it; visible.value = false },
                        onDismiss = { dismissals++; visible.value = false },
                        speechInputCoordinator = coordinator,
                    )
                }
            }
        }
    }

    private fun start() = rule.onNodeWithText("开始识别").performScrollTo().performClick()

    @Test fun consentIsConsumedPerAttemptAndResultOnlyEntersDraftAfterExplicitEditedInsertion() {
        val platform = SyntheticSpeechPlatform(onDeviceAvailable = false)
        val coordinator = SpeechInputCoordinator(platform)
        mount(coordinator)
        rule.runOnIdle {
            assertTrue(platform.sessions.isEmpty())
            assertEquals(0, permission.launches)
        }
        rule.onNodeWithText("开始识别").assertIsNotEnabled()
        rule.onNodeWithText("允许本次使用系统语音服务").assertIsOff().performClick().assertIsOn()
        start()
        rule.runOnIdle {
            assertEquals(1, permission.launches)
            assertTrue(platform.sessions.isEmpty())
            permission.complete(true)
        }
        rule.runOnIdle {
            assertEquals(listOf(false), platform.createdOnDevice)
            platform.sessions.single().listener.onResults(speechResults("synthetic recognized text"))
        }
        rule.onNodeWithText("允许本次使用系统语音服务").assertIsOff()
        rule.onNodeWithText("开始识别").assertIsNotEnabled()
        rule.runOnIdle { assertTrue(inserted.isEmpty()) }
        rule.onNodeWithText("识别文字（可修改）").performTextReplacement("user edited draft")
        rule.onNodeWithText("插入草稿").assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(listOf("user edited draft"), inserted)
            assertEquals(0, dismissals)
            assertEquals(1, platform.sessions.single().destroys)
            platform.sessions.single().emitLateCallbacks()
            assertEquals(SpeechInputState(), coordinator.state.value)
        }
    }

    @Test fun cancelledSheetCannotStartWhenItsPermissionResultArrives() {
        val platform = SyntheticSpeechPlatform()
        val coordinator = SpeechInputCoordinator(platform)
        mount(coordinator)
        start()
        rule.onNodeWithText("插入草稿").assertIsNotEnabled()
        rule.onNodeWithText("取消").performClick()
        rule.runOnIdle {
            permission.complete(true)
            assertTrue(platform.sessions.isEmpty())
            assertEquals(SpeechInputState(), coordinator.state.value)
            assertEquals(1, dismissals)
            assertTrue(inserted.isEmpty())
        }
    }

    @Test fun permissionGrantedAfterBackgroundAndReturnDoesNotRestartMicrophoneOrRetainConsent() {
        val platform = SyntheticSpeechPlatform(onDeviceAvailable = false)
        val coordinator = SpeechInputCoordinator(platform)
        mount(coordinator)
        rule.onNodeWithText("允许本次使用系统语音服务").performClick()
        start()
        rule.runOnIdle {
            owner.background()
            owner.foreground()
            permission.complete(true)
        }
        rule.onNodeWithText("允许本次使用系统语音服务").assertIsOff()
        rule.onNodeWithText("开始识别").assertIsNotEnabled()
        rule.runOnIdle {
            assertTrue(platform.sessions.isEmpty())
            assertFalse(coordinator.state.value.listening)
            assertTrue(inserted.isEmpty())
        }
    }

    @Test fun permissionCallbackBeforeActivityResumeWaitsForResumeRatherThanStartingWhilePaused() {
        val platform = SyntheticSpeechPlatform()
        val coordinator = SpeechInputCoordinator(platform)
        mount(coordinator)
        start()
        rule.runOnIdle {
            owner.pause()
            permission.complete(true)
            assertTrue(platform.sessions.isEmpty())
            owner.foreground()
            assertEquals(1, platform.sessions.size)
            assertTrue(coordinator.state.value.listening)
        }
    }

    @Test fun backgroundAfterPausedPermissionGrantInvalidatesDeferredMicrophoneStart() {
        val platform = SyntheticSpeechPlatform()
        val coordinator = SpeechInputCoordinator(platform)
        mount(coordinator)
        start()
        rule.runOnIdle {
            owner.pause()
            permission.complete(true)
            assertTrue(platform.sessions.isEmpty())
            owner.background()
            owner.foreground()
            assertTrue(platform.sessions.isEmpty())
            assertEquals(SpeechInputState(), coordinator.state.value)
        }
    }

    @Test fun realLifecycleStopCancelsListeningAndLateCallbacksCannotRestoreResult() {
        val platform = SyntheticSpeechPlatform()
        val coordinator = SpeechInputCoordinator(platform)
        mount(coordinator)
        start()
        rule.runOnIdle { permission.complete(true) }
        rule.runOnIdle {
            val session = platform.sessions.single()
            session.listener.onPartialResults(speechResults("synthetic partial"))
            owner.background()
            session.emitLateCallbacks()
            owner.foreground()
            session.emitLateCallbacks()
            assertEquals(SpeechInputState(), coordinator.state.value)
            assertEquals(1, session.cancels)
            assertEquals(1, session.destroys)
            assertTrue(inserted.isEmpty())
        }
        rule.onNodeWithText("插入草稿").assertIsNotEnabled()
        rule.onNodeWithText("开始识别").assertIsEnabled()
    }

    @Test fun removingSheetDisposesRecognizerEvenWithoutDismissCallback() {
        val platform = SyntheticSpeechPlatform()
        val coordinator = SpeechInputCoordinator(platform)
        mount(coordinator)
        start()
        rule.runOnIdle { permission.complete(true) }
        rule.runOnIdle { visible.value = false }
        rule.runOnIdle {
            val session = platform.sessions.single()
            session.emitLateCallbacks()
            assertEquals(1, session.cancels)
            assertEquals(1, session.destroys)
            assertEquals(SpeechInputState(), coordinator.state.value)
            assertTrue(inserted.isEmpty())
            assertEquals(0, dismissals)
        }
    }

    @Test fun stopKeepsInsertDisabledUntilFinalResultAndDoesNotSendOrInsertByItself() {
        val platform = SyntheticSpeechPlatform()
        val coordinator = SpeechInputCoordinator(platform)
        mount(coordinator)
        start()
        rule.runOnIdle { permission.complete(true) }
        rule.runOnIdle { platform.sessions.single().listener.onPartialResults(speechResults("synthetic partial")) }
        rule.onNodeWithText("结束聆听").performScrollTo().performClick().assertIsNotEnabled()
        rule.onNodeWithText("插入草稿").assertIsNotEnabled()
        rule.runOnIdle {
            val session = platform.sessions.single()
            assertEquals(1, session.stops)
            assertEquals(0, session.destroys)
            assertTrue(inserted.isEmpty())
            session.listener.onResults(speechResults("synthetic final"))
        }
        rule.onNodeWithText("插入草稿").assertIsEnabled()
        rule.runOnIdle { assertTrue(inserted.isEmpty()) }
        rule.onNodeWithText("插入草稿").performClick()
        rule.runOnIdle { assertEquals(listOf("synthetic final"), inserted) }
    }

    @Test fun noProviderStillAllowsKeyboardDraftWithoutPermissionOrAutomaticInsertion() {
        val platform = SyntheticSpeechPlatform(onDeviceAvailable = false, systemAvailable = false)
        val coordinator = SpeechInputCoordinator(platform)
        mount(coordinator)
        rule.onNodeWithText("开始识别").assertIsNotEnabled()
        rule.onNodeWithText("识别文字（可修改）").performTextReplacement("keyboard draft")
        rule.runOnIdle {
            assertTrue(platform.sessions.isEmpty())
            assertEquals(0, permission.launches)
            assertTrue(inserted.isEmpty())
        }
        rule.onNodeWithText("插入草稿").performClick()
        rule.runOnIdle { assertEquals(listOf("keyboard draft"), inserted) }
    }
}

/** TEST ONLY: drives real Compose lifecycle observers without user audio or external apps. */
private class SheetLifecycleOwner : LifecycleOwner {
    override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
    fun pause() { lifecycle.currentState = Lifecycle.State.STARTED }
    fun background() { lifecycle.currentState = Lifecycle.State.CREATED }
    fun foreground() { lifecycle.currentState = Lifecycle.State.RESUMED }
}

/** TEST ONLY: makes the real permission-launch/result integration deterministic, independent of device grants. */
private class DeniedMicrophoneContext(base: Context) : ContextWrapper(base) {
    override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
        if (permission == Manifest.permission.RECORD_AUDIO) PackageManager.PERMISSION_DENIED
        else super.checkPermission(permission, pid, uid)
}

/** TEST ONLY: real ActivityResultRegistry delivery, no actual Android permission or microphone acquisition. */
private class SyntheticPermissionRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
    override val activityResultRegistry: ActivityResultRegistry get() = this
    var launches = 0
        private set
    private var requestCode: Int? = null
    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
        launches++
        this.requestCode = requestCode
    }
    fun complete(granted: Boolean) {
        val code = checkNotNull(requestCode)
        requestCode = null
        dispatchResult(code, granted)
    }
}
