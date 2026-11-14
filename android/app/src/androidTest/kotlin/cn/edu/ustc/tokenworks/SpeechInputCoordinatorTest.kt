package cn.edu.ustc.tokenworks

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechInputCoordinatorTest {
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test fun openingAndUnsolicitedPermissionResultsNeverStartRecognition() = onMain {
        val platform = SyntheticSpeechPlatform()
        val coordinator = SpeechInputCoordinator(platform)
        coordinator.setForeground(true)
        coordinator.onPermissionResult(true)
        assertTrue(platform.sessions.isEmpty())
        assertEquals(SpeechInputState(), coordinator.state.value)
        coordinator.close()
    }

    @Test fun missingProviderDoesNotEvenRequestMicrophonePermission() = onMain {
        val platform = SyntheticSpeechPlatform(onDeviceAvailable = false, systemAvailable = false)
        val coordinator = SpeechInputCoordinator(platform)
        coordinator.setForeground(true)
        coordinator.requestStart(false, true) { error("No microphone request without a provider") }
        assertNotNull(coordinator.state.value.error)
        assertFalse(coordinator.state.value.listening)
        assertTrue(platform.sessions.isEmpty())
        coordinator.close()
    }

    @Test fun systemProviderRequiresConsentForEachStartAndNeverGetsAnImplicitFallback() = onMain {
        val platform = SyntheticSpeechPlatform(onDeviceAvailable = false)
        val coordinator = SpeechInputCoordinator(platform)
        coordinator.setForeground(true)
        coordinator.requestStart(false, false) { error("Consent is required before permission") }
        assertTrue(platform.sessions.isEmpty())
        assertNotNull(coordinator.state.value.error)
        var permissionRequests = 0
        coordinator.requestStart(false, true) { permissionRequests++ }
        assertEquals(1, permissionRequests)
        assertTrue(platform.sessions.isEmpty())
        coordinator.onPermissionResult(true)
        assertEquals(listOf(false), platform.createdOnDevice)
        platform.sessions.single().listener.onResults(speechResults("synthetic result"))
        coordinator.requestStart(true, false) { error("Already granted") }
        assertEquals(1, platform.sessions.size)
        assertFalse(coordinator.state.value.listening)
        coordinator.close()
    }

    @Test fun onDeviceStartupFailureNeverSilentlyUsesSystemProvider() = onMain {
        val platform = SyntheticSpeechPlatform().apply { failStart = true }
        val coordinator = SpeechInputCoordinator(platform)
        coordinator.setForeground(true)
        coordinator.requestStart(true, false) { error("Already granted") }
        assertEquals(listOf(true), platform.createdOnDevice)
        assertFalse(coordinator.state.value.listening)
        assertNotNull(coordinator.state.value.error)
        assertEquals(1, platform.sessions.single().cancels)
        assertEquals(1, platform.sessions.single().destroys)
        coordinator.close()
    }

    @Test fun backgroundInvalidatesPermissionEvenAfterReturningAndBlocksOverlappingLaunches() = onMain {
        val platform = SyntheticSpeechPlatform()
        val coordinator = SpeechInputCoordinator(platform)
        coordinator.setForeground(true)
        var launches = 0
        coordinator.requestStart(false, false) { launches++ }
        coordinator.setForeground(false)
        coordinator.setForeground(true)
        coordinator.requestStart(false, false) { launches++ }
        assertEquals(1, launches)
        coordinator.onPermissionResult(true)
        assertTrue(platform.sessions.isEmpty())
        assertFalse(coordinator.state.value.awaitingPermission)
        coordinator.requestStart(true, false) { error("Already granted") }
        assertEquals(1, platform.sessions.size)
        coordinator.close()
    }

    @Test fun backgroundRejectsBothPermissionLaunchAndGrantedStart() = onMain {
        val platform = SyntheticSpeechPlatform()
        val coordinator = SpeechInputCoordinator(platform)
        coordinator.requestStart(false, false) { error("Background must not launch permission") }
        coordinator.requestStart(true, false) { error("Already granted") }
        assertTrue(platform.sessions.isEmpty())
        coordinator.close()
    }

    @Test fun clearInvalidatesPendingPermissionWithoutLettingItSatisfyANewerRequest() = onMain {
        val platform = SyntheticSpeechPlatform()
        val coordinator = SpeechInputCoordinator(platform)
        coordinator.setForeground(true)
        var launches = 0
        coordinator.requestStart(false, false) { launches++ }
        coordinator.cancel()
        coordinator.requestStart(false, false) { launches++ }
        coordinator.onPermissionResult(true)
        assertEquals(1, launches)
        assertTrue(platform.sessions.isEmpty())
        assertEquals(SpeechInputState(), coordinator.state.value)
        coordinator.close()
    }

    @Test fun disposalInvalidatesPermissionAndCannotBeReactivated() = onMain {
        val platform = SyntheticSpeechPlatform()
        val coordinator = SpeechInputCoordinator(platform)
        coordinator.setForeground(true)
        coordinator.requestStart(false, false) {}
        coordinator.close()
        coordinator.setForeground(true)
        coordinator.onPermissionResult(true)
        coordinator.requestStart(true, false) { error("Disposed sheet") }
        coordinator.editText("late edit")
        assertEquals(SpeechInputState(), coordinator.state.value)
        assertTrue(platform.sessions.isEmpty())
    }

    @Test fun deniedPermissionAndFailedLaunchCanBeRetriedOnlyByAnotherStart() = onMain {
        val platform = SyntheticSpeechPlatform()
        val coordinator = SpeechInputCoordinator(platform)
        coordinator.setForeground(true)
        coordinator.requestStart(false, false) { throw IllegalStateException("synthetic launch failure") }
        assertFalse(coordinator.state.value.awaitingPermission)
        assertNotNull(coordinator.state.value.error)
        coordinator.onPermissionResult(true)
        assertTrue(platform.sessions.isEmpty())
        coordinator.requestStart(false, false) {}
        coordinator.onPermissionResult(false)
        assertFalse(coordinator.state.value.awaitingPermission)
        assertNotNull(coordinator.state.value.error)
        assertTrue(platform.sessions.isEmpty())
        coordinator.requestStart(false, false) {}
        coordinator.onPermissionResult(true)
        assertEquals(1, platform.sessions.size)
        coordinator.close()
    }

    @Test fun retiredCallbacksCannotRestoreTextOrChangeANewerListeningSession() = onMain {
        val platform = SyntheticSpeechPlatform()
        val coordinator = SpeechInputCoordinator(platform)
        coordinator.setForeground(true)
        coordinator.requestStart(true, false) {}
        val old = platform.sessions.single()
        old.listener.onPartialResults(speechResults("old partial"))
        coordinator.cancel()
        old.emitLateCallbacks()
        assertEquals(SpeechInputState(), coordinator.state.value)
        coordinator.requestStart(true, false) {}
        val current = coordinator.state.value
        old.emitLateCallbacks()
        assertEquals(current, coordinator.state.value)
        platform.sessions.last().listener.onResults(speechResults("current result"))
        old.emitLateCallbacks()
        assertEquals("current result", coordinator.state.value.text)
        assertFalse(coordinator.state.value.listening)
        coordinator.close()
    }

    @Test fun disposalInvalidatesReentrantCleanupCallbacksAndOwnsDestructionExactlyOnce() = onMain {
        val platform = SyntheticSpeechPlatform()
        val coordinator = SpeechInputCoordinator(platform)
        coordinator.setForeground(true)
        coordinator.requestStart(true, false) {}
        val session = platform.sessions.single()
        session.duringCancel = { session.emitLateCallbacks() }
        session.duringDestroy = { session.emitLateCallbacks() }
        coordinator.close()
        coordinator.close()
        coordinator.cancel()
        coordinator.stop()
        session.emitLateCallbacks()
        assertEquals(SpeechInputState(), coordinator.state.value)
        assertEquals(1, session.cancels)
        assertEquals(1, session.destroys)
    }

    @Test fun stopWaitsForFinalResultButIsIdempotentAndDoesNotEnableEditingEarly() = onMain {
        val platform = SyntheticSpeechPlatform()
        val coordinator = SpeechInputCoordinator(platform)
        coordinator.setForeground(true)
        coordinator.requestStart(true, false) {}
        val session = platform.sessions.single()
        session.listener.onPartialResults(speechResults("partial"))
        coordinator.stop()
        coordinator.stop()
        coordinator.editText("must not replace an active result")
        assertTrue(coordinator.state.value.listening)
        assertTrue(coordinator.state.value.finishing)
        assertEquals("partial", coordinator.state.value.text)
        assertEquals(1, session.stops)
        assertEquals(0, session.destroys)
        session.listener.onResults(speechResults("final"))
        assertEquals("final", coordinator.state.value.text)
        assertFalse(coordinator.state.value.listening)
        assertFalse(coordinator.state.value.finishing)
        assertEquals(1, session.cancels)
        assertEquals(1, session.destroys)
        coordinator.editText("edited draft")
        session.emitLateCallbacks()
        assertEquals("edited draft", coordinator.state.value.text)
        coordinator.close()
        assertEquals(1, session.destroys)
    }

    @Test fun providerEndOfSpeechWaitsForFinalResultWithoutSendingAnotherStop() = onMain {
        val platform = SyntheticSpeechPlatform()
        val coordinator = SpeechInputCoordinator(platform)
        coordinator.setForeground(true)
        coordinator.requestStart(true, false) {}
        val session = platform.sessions.single()
        session.listener.onEndOfSpeech()
        coordinator.stop()
        assertTrue(coordinator.state.value.listening)
        assertTrue(coordinator.state.value.finishing)
        assertEquals(0, session.stops)
        session.listener.onResults(speechResults("final after automatic end"))
        assertFalse(coordinator.state.value.listening)
        assertEquals("final after automatic end", coordinator.state.value.text)
        coordinator.close()
    }

    @Test fun stopFailureReleasesOwnershipAndRejectsLateResults() = onMain {
        val platform = SyntheticSpeechPlatform()
        val coordinator = SpeechInputCoordinator(platform)
        coordinator.setForeground(true)
        coordinator.requestStart(true, false) {}
        val session = platform.sessions.single().apply { failStop = true }
        coordinator.stop()
        assertFalse(coordinator.state.value.listening)
        assertFalse(coordinator.state.value.finishing)
        assertNotNull(coordinator.state.value.error)
        val failed = coordinator.state.value
        session.emitLateCallbacks()
        assertEquals(failed, coordinator.state.value)
        assertEquals(1, session.cancels)
        assertEquals(1, session.destroys)
        coordinator.close()
    }

    @Test fun throwingCancelStillDestroysEngineAndDoesNotCrashClose() = onMain {
        val platform = SyntheticSpeechPlatform()
        val coordinator = SpeechInputCoordinator(platform)
        coordinator.setForeground(true)
        coordinator.requestStart(true, false) {}
        val session = platform.sessions.single().apply { failCancel = true; failDestroy = true }
        coordinator.close()
        assertEquals(1, session.cancels)
        assertEquals(1, session.destroys)
        session.emitLateCallbacks()
        assertEquals(SpeechInputState(), coordinator.state.value)
    }

    @Test fun providerErrorReleasesOwnershipAndRejectsFollowingResults() = onMain {
        val platform = SyntheticSpeechPlatform()
        val coordinator = SpeechInputCoordinator(platform)
        coordinator.setForeground(true)
        coordinator.requestStart(true, false) {}
        val session = platform.sessions.single()
        session.listener.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        assertFalse(coordinator.state.value.listening)
        assertNotNull(coordinator.state.value.error)
        val failed = coordinator.state.value
        session.emitLateCallbacks()
        assertEquals(failed, coordinator.state.value)
        assertEquals(1, session.destroys)
        coordinator.close()
    }

    @Test fun partialFinalAndEditedTextAreBoundedWithoutSplittingSupplementaryCharacters() = onMain {
        val platform = SyntheticSpeechPlatform()
        val coordinator = SpeechInputCoordinator(platform)
        coordinator.setForeground(true)
        coordinator.requestStart(true, false) {}
        val session = platform.sessions.single()
        val prefix = "a".repeat(32_767)
        val splitPair = prefix + "\uD83D\uDE00" + "z"
        session.listener.onPartialResults(speechResults(splitPair))
        assertEquals(prefix, coordinator.state.value.text)
        assertNotNull(coordinator.state.value.error)
        session.listener.onResults(speechResults(splitPair))
        assertEquals(prefix, coordinator.state.value.text)
        assertFalse(coordinator.state.value.listening)
        assertNotNull(coordinator.state.value.error)
        coordinator.editText(splitPair)
        assertEquals(prefix, coordinator.state.value.text)
        val completePairAtLimit = "b".repeat(32_766) + "\uD83D\uDE00"
        coordinator.editText(completePairAtLimit + "overflow")
        assertEquals(completePairAtLimit, coordinator.state.value.text)
        coordinator.editText("editable")
        assertEquals("editable", coordinator.state.value.text)
        assertEquals(null, coordinator.state.value.error)
        coordinator.close()
    }

    @Test fun permissionResultWhilePausedWaitsForResumeWithoutStartingInBackground() = onMain {
        val platform = SyntheticSpeechPlatform()
        val coordinator = SpeechInputCoordinator(platform)
        coordinator.setForeground(true)
        coordinator.requestStart(false, false) {}
        coordinator.setForeground(true, resumed = false)
        coordinator.onPermissionResult(true)
        assertTrue(platform.sessions.isEmpty())
        assertTrue(coordinator.state.value.awaitingPermission)
        coordinator.setForeground(true)
        assertEquals(1, platform.sessions.size)
        assertTrue(coordinator.state.value.listening)
        coordinator.close()
    }

    @Test fun backgroundAlsoInvalidatesAPermissionGrantDeferredUntilResume() = onMain {
        val platform = SyntheticSpeechPlatform()
        val coordinator = SpeechInputCoordinator(platform)
        coordinator.setForeground(true)
        coordinator.requestStart(false, false) {}
        coordinator.setForeground(true, resumed = false)
        coordinator.onPermissionResult(true)
        coordinator.setForeground(false)
        coordinator.setForeground(true)
        assertTrue(platform.sessions.isEmpty())
        assertEquals(SpeechInputState(), coordinator.state.value)
        coordinator.close()
    }

    @Test fun pausedSheetRejectsNewRequestsAndReleasesAnActiveMicrophoneUntilAnotherStart() = onMain {
        val platform = SyntheticSpeechPlatform()
        val coordinator = SpeechInputCoordinator(platform)
        coordinator.setForeground(true, resumed = false)
        coordinator.requestStart(false, false) { error("Paused sheet must not launch permissions") }
        coordinator.requestStart(true, false) { error("Already granted") }
        assertTrue(platform.sessions.isEmpty())
        coordinator.setForeground(true)
        coordinator.requestStart(true, false) {}
        val session = platform.sessions.single()
        coordinator.setForeground(true, resumed = false)
        session.emitLateCallbacks()
        coordinator.setForeground(true)
        assertEquals(1, platform.sessions.size)
        assertEquals(1, session.destroys)
        assertEquals(SpeechInputState(), coordinator.state.value)
        coordinator.close()
    }

    @Test fun realPre31PlatformRejectsUnconsentedSystemRecognitionBeforePermissionOrMicrophone() {
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT < 31)
        onMain {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val coordinator = SpeechInputCoordinator(context)
            coordinator.setForeground(true)
            coordinator.requestStart(false, false) { error("No on-device recognizer on pre-31; permission must not launch") }
            assertFalse(coordinator.state.value.listening)
            assertFalse(coordinator.state.value.awaitingPermission)
            assertNotNull(coordinator.state.value.error)
            println("Real speech capabilities: API=${Build.VERSION.SDK_INT}, onDevice=${coordinator.onDeviceAvailable}, system=${coordinator.systemAvailable}; no microphone started")
            coordinator.close()
        }
    }
}

/** ANDROID TEST ONLY: deterministic callbacks, no microphone, audio, network, services or credentials. */
internal class SyntheticSpeechPlatform(
    override val onDeviceAvailable: Boolean = true,
    override val systemAvailable: Boolean = true,
) : SpeechRecognitionPlatform {
    val sessions = mutableListOf<SyntheticSpeechSession>()
    val createdOnDevice = mutableListOf<Boolean>()
    var failStart = false
    override fun create(onDevice: Boolean): SpeechRecognitionSession {
        createdOnDevice += onDevice
        return SyntheticSpeechSession(failStart).also { sessions += it }
    }
}

/** ANDROID TEST ONLY: models even malicious/late platform delivery after cancel and destroy. */
internal class SyntheticSpeechSession(private val failStart: Boolean) : SpeechRecognitionSession {
    lateinit var listener: RecognitionListener
        private set
    var stops = 0
    var cancels = 0
    var destroys = 0
    var failStop = false
    var failCancel = false
    var failDestroy = false
    var duringCancel: (() -> Unit)? = null
    var duringDestroy: (() -> Unit)? = null
    override fun attachListener(listener: RecognitionListener) { this.listener = listener }
    override fun start(intent: Intent) {
        if (failStart) throw IllegalStateException("synthetic provider start failure")
    }
    override fun stop() {
        stops++
        if (failStop) throw IllegalStateException("synthetic provider stop failure")
    }
    override fun cancel() {
        cancels++
        duringCancel?.invoke()
        if (failCancel) throw IllegalStateException("synthetic provider cancel failure")
    }
    override fun destroy() {
        destroys++
        duringDestroy?.invoke()
        if (failDestroy) throw IllegalStateException("synthetic provider destroy failure")
    }
    fun emitLateCallbacks() {
        listener.onPartialResults(speechResults("stale partial"))
        listener.onResults(speechResults("stale final"))
        listener.onError(SpeechRecognizer.ERROR_NO_MATCH)
        listener.onEndOfSpeech()
    }
}

internal fun speechResults(text: String) = Bundle().apply {
    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
}
