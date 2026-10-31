package app.lumen.launcher

import android.app.Application
import android.content.Context
import app.lumen.core.apps.AppLauncher
import app.lumen.core.apps.AppRepository
import app.lumen.core.settings.SettingsRepository
import app.lumen.core.settings.WallpaperLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class LumenApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Manual dependency wiring; process-wide singletons. */
class AppContainer(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(context)
    val apps = AppRepository(context, scope)
    val launcher = AppLauncher(context)
    val wallpapers = WallpaperLibrary(context)
}
