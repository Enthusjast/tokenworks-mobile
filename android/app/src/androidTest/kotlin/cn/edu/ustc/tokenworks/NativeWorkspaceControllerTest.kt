package cn.edu.ustc.tokenworks

import android.app.KeyguardManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.enthusjast.tokenworks.network.SessionReadPhase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real TLS/account/reader owners rendered through native automation, without Espresso input APIs. */
@RunWith(AndroidJUnit4::class)
class NativeWorkspaceControllerTest {
    @Test fun drawerResizeAndCurrentRowKeepOneAuthorizedReaderAndNeverClaimQuestions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("accountFixture") == "true") { "Explicit owned account fixture required" }
        val context = instrumentation.targetContext
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        check(keyguard.isDeviceSecure && !keyguard.isDeviceLocked) { "Existing secure unlocked device required" }
        AccountFixture(context).use { fixture ->
            fixture.questionMode = true
            fixture.seed()
            lateinit var controller: LoginController
            instrumentation.runOnMainSync {
                controller = LoginController(context, fixture.http, fixture.environment())
                controller.foreground(true)
            }
            fun awaitState(predicate: (HomeState) -> Boolean): HomeState = runBlocking {
                withTimeout(30_000) { controller.state.first(predicate) }
            }
            fun counters() = listOf(fixture.meRequests.get(), fixture.hostRequests.get(), fixture.sessionRequests.get(),
                fixture.ticketRequests.get(), fixture.subscriptions.get(), fixture.relayClosures.get(),
                fixture.commandWrites.get(), fixture.claimWrites.get(), fixture.releaseWrites.get(), fixture.questionWrites.get())
            try {
                awaitState { it.phase == HomePhase.READY && !it.refreshing && it.runtimes.size == 1 }
                val model = HomeViewModel(controller)
                NativeChatFixture.launch(WorkspaceViewport(360, 800)).use { ui ->
                    ui.content { HomeRoute(model = model, onSecuritySettings = {}, viewport = ui.viewport, onCancel = controller::cancel) }
                    awaitState { !it.loadingSessions && it.selectedRuntimeId == fixture.runtimeId && it.sessions.size == 1 }
                    ui.await("authorized directory row") { nodes -> nodes.any { it.tag("session-${fixture.sessionId}") } }
                    ui.clickTag("session-${fixture.sessionId}")
                    val original = awaitState { it.reader?.let { read -> read.phase == SessionReadPhase.READY && read.questions.items.isNotEmpty() } == true }
                    ui.await("active authorized chat") { nodes -> nodes.any { it.tag("chat-list") } }
                    val before = counters()
                    assertEquals(1, fixture.subscriptions.get())
                    assertEquals(0, fixture.claimWrites.get())
                    ui.click(context.getString(R.string.workspace_open_navigation))
                    ui.await("drawer without resource read") { nodes -> nodes.any { it.tag("directory-search") } }
                    ui.back()
                    ui.await("drawer closes without retiring chat") { nodes -> nodes.any { it.tag("chat-list") } && nodes.none { it.tag("directory-search") } }
                    for (width in listOf(599, 600, 839, 840, 1119, 1120, 1280, 360)) {
                        ui.resize(width, 800)
                        val current = controller.state.value
                        assertEquals(original.presentationOwner, current.presentationOwner)
                        assertEquals(original.selectedRuntimeId, current.selectedRuntimeId)
                        assertEquals(original.selectedSessionId, current.selectedSessionId)
                        assertEquals(SessionReadPhase.READY, current.reader?.phase)
                        assertEquals(before, counters())
                    }
                    ui.click(context.getString(R.string.workspace_open_navigation))
                    ui.clickTag("session-${fixture.sessionId}")
                    ui.await("same row only closes drawer") { nodes -> nodes.any { it.tag("chat-list") } && nodes.none { it.tag("directory-search") } }
                    assertEquals(before, counters())
                    assertEquals(0, fixture.claimWrites.get())
                    assertEquals(0, fixture.questionWrites.get())
                    fixture.failure.get()?.let { throw AssertionError(it) }
                }
            } finally { instrumentation.runOnMainSync { controller.close() } }
        }
    }
}
