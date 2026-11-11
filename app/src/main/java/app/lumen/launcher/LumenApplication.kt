package app.lumen.launcher

import android.app.Application
import android.content.Context
import app.lumen.core.apps.AppLauncher
import app.lumen.core.apps.AppRepository
import app.lumen.core.apps.TopShelfRepository
import app.lumen.core.designsystem.UiSounds
import app.lumen.core.settings.SettingsRepository
import app.lumen.core.settings.WallpaperLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val topShelf = TopShelfRepository(context)
    val wallpapers = WallpaperLibrary(context)
    val sounds by lazy { UiSounds(context) }
    private var releaseRows: Job? = null

    /**
     * Another app has the screen. Rows are let go of after a while, as this process stays alive behind that
     * app and they'd otherwise come out of the memory it, and whatever is paused behind it, could use. Not
     * at once, so a quick trip to an app doesn't mean reading them all again.
     *
     * Called when the activity stops rather than from onTrimMemory: with the Home Button service bound,
     * Android counts this process as in the foreground, so it wouldn't be told its UI is hidden.
     *
     * Not when the TV went to sleep: no app is using the screen then, and reading every row again on
     * waking, while Google TV's own home and services start up too, kept the remote from answering.
     */
    fun onHidden(asleep: Boolean) {
        releaseRows?.cancel()
        if (asleep) return
        releaseRows = scope.launch {
            delay(ROWS_KEPT_WHILE_HIDDEN_MS)
            topShelf.releaseRows()
        }
    }

    fun onShown() {
        releaseRows?.cancel()
    }

    private companion object {
        const val ROWS_KEPT_WHILE_HIDDEN_MS = 60_000L
    }
}
