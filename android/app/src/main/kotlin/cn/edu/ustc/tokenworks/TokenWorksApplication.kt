package cn.edu.ustc.tokenworks

import android.app.Application
import io.github.enthusjast.tokenworks.network.LoginHttp
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import javax.inject.Inject
import io.github.enthusjast.tokenworks.auth.LoginConfiguration

@HiltAndroidApp
class TokenWorksApplication : Application() {
    @Inject lateinit var notifications: NotificationCenter
    override fun onCreate() {
        super.onCreate()
        // Process death discards all staging keys. Remove the unusable ciphertext
        // before a new foreground transfer can start.
        java.io.File(cacheDir, "file-exports").deleteRecursively()
        // APK staging is disposable. A new process must fetch current metadata
        // and validate newly acquired bytes; no previous in-memory verdict survives.
        java.io.File(cacheDir, "apk-updates").deleteRecursively()
        val deployment = runCatching { LoginConfiguration.parse(assets.open("deployment.json").use { it.readBytes() }).fingerprint }.getOrNull()
        notifications.reconcileDeployment(deployment, publicPushConfiguration(this)?.fingerprint)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun loginHttp(): LoginHttp = LoginHttp.create()
}
