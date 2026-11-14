package cn.edu.ustc.tokenworks

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Public synthetic-only references. This proves Android behavior, never real FCM delivery. */
class NotifyPlatformTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = "11111111-1111-4111-8111-111111111111"
    private val owner = LocalDataOwner(id, id, "a".repeat(64), id, id)
    private fun reference() = PushReference.parse(mapOf("v" to "1", "binding_id" to id, "notification_id" to "22222222-2222-4222-8222-222222222222",
        "account_id" to id, "organization_id" to id, "deployment_sha256" to owner.deploymentHash, "user_id" to id,
        "device_id" to id, "owner_fingerprint" to owner.fingerprint, "runtime_id" to id, "session_id" to "public-synthetic-session",
        "kind" to "interaction", "expires_at" to Instant.ofEpochMilli(System.currentTimeMillis() + 60_000).toString()), System.currentTimeMillis())
    private fun grantNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }
    private fun await(description: String, check: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10_000
        while (!check() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(25)
        assertTrue(description, check())
    }
    @Test fun dataOnlyReferenceUsesGenericPrivateNotificationAndImmutableExplicitIntent() {
        grantNotificationPermission()
        val manager = context.getSystemService(NotificationManager::class.java)
        val reference = reference()
        try {
            showReferenceNotification(context, reference)
            await("generic reference notification posted") { manager.activeNotifications.any { it.tag == reference.notificationId } }
            val posted = manager.activeNotifications.single { it.tag == reference.notificationId }.notification
            assertEquals("会话有新事件", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals("打开应用后验证访问权限并查看。", posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals(Notification.VISIBILITY_SECRET, posted.visibility)
            assertEquals(context.packageName, posted.contentIntent.creatorPackage)
            if (Build.VERSION.SDK_INT >= 31) assertTrue(posted.contentIntent.isImmutable)
            val click = referenceIntent(context, reference)
            assertEquals(MainActivity::class.java.name, click.component?.className)
            assertEquals(reference.data(), parseReferenceIntent(click, System.currentTimeMillis())?.data())
            assertNull(parseReferenceIntent(click, reference.expiresAt))
            click.getBundleExtra(REFERENCE_EXTRA)!!.putString("body", "FORBIDDEN")
            assertNull(parseReferenceIntent(click, System.currentTimeMillis()))
            manager.cancel(reference.notificationId, 1)
            val denied = object : ContextWrapper(context) {
                override fun checkSelfPermission(permission: String): Int = if (permission == Manifest.permission.POST_NOTIFICATIONS) PackageManager.PERMISSION_DENIED else super.checkSelfPermission(permission)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                showReferenceNotification(denied, reference)
                assertFalse(manager.activeNotifications.any { it.tag == reference.notificationId })
            }
        } finally { manager.cancel(reference.notificationId, 1) }
    }
    @Test fun actualConnectedDeviceForegroundServiceHasGenericOngoingNotificationAndStopAction() {
        grantNotificationPermission()
        val manager = context.getSystemService(NotificationManager::class.java)
        ActivityScenario.launch(ComponentActivity::class.java).use {
            try {
                ContextCompat.startForegroundService(context, Intent(context, NotifyPlatformFixtureService::class.java).setAction("synthetic-notify-platform-consent"))
                await("actual connected-device foreground notification") { manager.activeNotifications.any { it.id == CONNECTION_ID } }
                val notification = manager.activeNotifications.single { it.id == CONNECTION_ID }.notification
                assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
                assertTrue(notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
                assertEquals("后台只读连接已开启", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                assertEquals("停止连接", notification.actions.single().title.toString())
                notification.actions.single().actionIntent.send()
                await("stop action tears down service notification") { manager.activeNotifications.none { it.id == CONNECTION_ID } }
                // The actual production service rejects restart without selected authenticated authority.
                context.startService(Intent(context, SelectedConnectionService::class.java))
                instrumentation.waitForIdleSync()
                assertFalse(manager.activeNotifications.any { it.id == CONNECTION_ID })
            } finally { context.stopService(Intent(context, NotifyPlatformFixtureService::class.java)); context.stopService(Intent(context, SelectedConnectionService::class.java)) }
        }
    }
    @Test fun backgroundLeaseClearsDraftRejectsWritesAndAuthorityFenceRetiresLease() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val resource = ConversationResource(owner, id, id, "public-synthetic-session")
        val home = MutableStateFlow(HomeState(HomePhase.READY, presentationOwner = owner, selectedRuntimeId = id,
            selectedSessionId = resource.sessionId, runtimes = listOf(RuntimeRow(id, id, "synthetic", "public"))))
        val account = AccountCoordinator(context, LoginHttp.create(), LoginEnvironment({ byteArrayOf() }), scope, home)
        val sessions = SessionCoordinator(context, scope, home, account, RuntimeRoutes(account, home))
        var retired = 0
        try {
            instrumentation.runOnMainSync {
                sessions.onAuthorityLost = { retired++; sessions.holdBackground(false) }
                sessions.foreground(true)
                sessions.editDraft(TextFieldValue("PUBLIC LOCAL DRAFT"))
                assertEquals("PUBLIC LOCAL DRAFT", home.value.composer?.value?.text)
                sessions.holdBackground(true)
                sessions.foreground(false)
                assertTrue(sessions.backgroundHold)
                assertTrue(home.value.backgroundReadOnly)
                assertNull(home.value.composer)
                sessions.editDraft(TextFieldValue("MUST NOT ENTER")); sessions.prompt("MUST NOT SEND")
                sessions.sendDraft(); sessions.retryPrompt(id); sessions.cancelSession(); sessions.approveInteraction(id)
                assertNull(home.value.composer)
                assertFalse(home.value.submitting)
                sessions.fence(closeKeys = true, preserveSelection = false)
                assertEquals(1, retired)
                assertFalse(sessions.backgroundHold)
                assertFalse(home.value.backgroundReadOnly)
                assertNull(home.value.reader)
            }
        } finally { scope.cancel() }
    }
}
