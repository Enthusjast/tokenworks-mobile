package cn.edu.ustc.tokenworks

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val REFERENCE_ACTION = "cn.edu.ustc.tokenworks.NOTIFICATION_REFERENCE"
internal const val REFERENCE_EXTRA = "notification_reference"
internal const val CONNECTION_STOP = "cn.edu.ustc.tokenworks.STOP_CONNECTION"
internal const val CONNECTION_ID = 4100
internal const val REFERENCE_CHANNEL = "reference-events"
internal const val CONNECTION_CHANNEL = "selected-connection"

internal fun notificationsPermitted(context: Context): Boolean =
    (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()

internal fun notificationChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(REFERENCE_CHANNEL, context.getString(R.string.platform_notice_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
        description = context.getString(R.string.platform_notice_channel_detail); lockscreenVisibility = Notification.VISIBILITY_SECRET
    })
    manager.createNotificationChannel(NotificationChannel(CONNECTION_CHANNEL, context.getString(R.string.platform_connection_channel), NotificationManager.IMPORTANCE_LOW).apply {
        description = context.getString(R.string.platform_connection_channel_detail); lockscreenVisibility = Notification.VISIBILITY_SECRET
    })
}

internal fun referenceIntent(context: Context, reference: PushReference): Intent =
    Intent(context, MainActivity::class.java).setAction(REFERENCE_ACTION)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(REFERENCE_EXTRA, Bundle().apply { reference.data().forEach { (key, value) -> putString(key, value) } })

internal fun parseReferenceIntent(intent: Intent?, now: Long): PushReference? {
    if (intent?.action != REFERENCE_ACTION) return null
    return runCatching {
        val bundle = intent.getBundleExtra(REFERENCE_EXTRA) ?: return null
        PushReference.parse(bundle.keySet().associateWith { bundle.getString(it) ?: throw ProtocolException() }, now)
    }.getOrNull()
}

internal fun showReferenceNotification(context: Context, reference: PushReference) {
    if (!notificationsPermitted(context)) return
    notificationChannels(context)
    val pending = PendingIntent.getActivity(context, reference.notificationId.hashCode(), referenceIntent(context, reference),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification = NotificationCompat.Builder(context, REFERENCE_CHANNEL).setSmallIcon(R.drawable.ic_tokenworks)
        .setContentTitle(context.getString(R.string.platform_notice_title)).setContentText(context.getString(R.string.platform_notice_detail))
        .setContentIntent(pending).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_SECRET)
        .setTimeoutAfter((reference.expiresAt - System.currentTimeMillis()).coerceAtLeast(1)).build()
    try { NotificationManagerCompat.from(context).notify(reference.notificationId, 1, notification) } catch (_: SecurityException) { }
}

/** Explicit configuration is loaded only after user consent; Firebase has no auto-init before then. */
internal class FirebasePushClient(private val context: Context) {
    private var messaging: FirebaseMessaging? = null
    fun configure(configuration: PushDeployment): FirebaseMessaging {
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            ?: FirebaseApp.initializeApp(context, FirebaseOptions.Builder().setProjectId(configuration.projectId)
                .setApplicationId(configuration.applicationId).setGcmSenderId(configuration.senderId).setApiKey(configuration.apiKey).build())
        if (app.options.applicationId != configuration.applicationId || app.options.projectId != configuration.projectId) throw ProtocolException()
        app.setDataCollectionDefaultEnabled(false)
        return FirebaseMessaging.getInstance().also { messaging = it; it.isAutoInitEnabled = false; it.setNotificationDelegationEnabled(false) }
    }
    suspend fun token(configuration: PushDeployment): String {
        val client = configure(configuration)
        val result = suspendCancellableCoroutine<String> { continuation ->
            client.token.addOnCompleteListener { task ->
                if (!continuation.isActive) return@addOnCompleteListener
                if (task.isSuccessful) continuation.resume(task.result)
                else continuation.resumeWithException(IllegalStateException("push.provider_unavailable"))
            }
        }
        client.isAutoInitEnabled = true
        return result
    }
    fun disable() {
        messaging?.let { it.isAutoInitEnabled = false; it.deleteToken() }
        messaging = null
    }
}

@AndroidEntryPoint
class ReferenceMessagingService : FirebaseMessagingService() {
    @Inject lateinit var login: LoginController
    override fun onMessageReceived(message: RemoteMessage) {
        // Server notification messages are never rendered by our code. Only exact data references qualify.
        if (message.notification != null) return
        login.receiveNotification(message.data)
    }
    override fun onNewToken(token: String) { login.rotateNotificationToken(token) }
}

@AndroidEntryPoint
class SelectedConnectionService : Service() {
    @Inject lateinit var login: LoginController
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CONNECTION_STOP) { login.stopBackgroundConnection(); stopSelf(); return START_NOT_STICKY }
        if (!login.canStartBackgroundConnection()) { stopSelf(); return START_NOT_STICKY }
        try {
            enterSelectedConnectionForeground()
            login.backgroundServiceStarted()
        } catch (_: Exception) { login.stopBackgroundConnection(); stopSelf() }
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) { login.stopBackgroundConnection(); stopSelf() }
    override fun onDestroy() { login.backgroundServiceStopped(); super.onDestroy() }
}

/** The OS-facing foreground path is shared with an isolated instrumentation service fixture. */
internal fun Service.enterSelectedConnectionForeground(serviceClass: Class<out Service> = SelectedConnectionService::class.java) {
    notificationChannels(this)
    val stop = PendingIntent.getService(this, 0, Intent(this, serviceClass).setAction(CONNECTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val notification = NotificationCompat.Builder(this, CONNECTION_CHANNEL).setSmallIcon(R.drawable.ic_tokenworks)
        .setContentTitle(getString(R.string.platform_connection_title)).setContentText(getString(R.string.platform_connection_detail))
        .setContentIntent(open).setOngoing(true).setVisibility(NotificationCompat.VISIBILITY_SECRET)
        .addAction(0, getString(R.string.platform_connection_stop), stop).build()
    if (Build.VERSION.SDK_INT >= 29) startForeground(CONNECTION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    else startForeground(CONNECTION_ID, notification)
}
