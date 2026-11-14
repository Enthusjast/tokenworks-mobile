package cn.edu.ustc.tokenworks

import android.app.Service
import android.content.Intent

/** Synthetic instrumentation fixture, absent from release builds; no network/provider or account. */
class NotifyPlatformFixtureService : Service() {
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CONNECTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action != "synthetic-notify-platform-consent") { stopSelf(); return START_NOT_STICKY }
        enterSelectedConnectionForeground(javaClass)
        return START_NOT_STICKY
    }
    override fun onDestroy() { stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
}
