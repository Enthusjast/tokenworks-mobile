package cn.edu.ustc.tokenworks

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@HiltAndroidApp
class TokenWorksApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // APK staging is disposable. A new process must fetch current metadata
        // and validate newly acquired bytes; no previous in-memory verdict survives.
        java.io.File(cacheDir, "apk-updates").deleteRecursively()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun loginHttp(): LoginHttp = LoginHttp.create()
}
