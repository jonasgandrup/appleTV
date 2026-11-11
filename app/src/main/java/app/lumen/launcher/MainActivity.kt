package app.lumen.launcher

import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.lumen.core.apps.TopShelfRepository
import app.lumen.core.system.HomeOverride
import kotlinx.coroutines.flow.MutableSharedFlow
import java.lang.ref.WeakReference

class MainActivity : ComponentActivity() {

    /** True when Home was pressed inside another app rather than on the home screen. */
    private val homePresses = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    private val controlCenterRequests = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opened from Google TV's list of apps, Atrium lands in an ordinary task; brought back by Home, in a
        // home task. Android keeps one copy in each, so two whole home screens, with their artwork and
        // timers, would stay alive at once. Only the newest is wanted.
        live?.get()?.takeIf { it !== this && !it.isFinishing }?.finishAndRemoveTask()
        live = WeakReference(this)
        val container = (application as LumenApplication).container
        if (intent.getBooleanExtra(HomeOverride.EXTRA_CONTROL_CENTER, false)) controlCenterRequests.tryEmit(Unit)
        setContent { LumenApp(container, homePresses, controlCenterRequests) }
        // For the apps' own artwork on the stage. Android stops asking after the user declines twice.
        if (!container.topShelf.hasPermission) requestPermissions(arrayOf(TopShelfRepository.PERMISSION), 0)
    }

    override fun onStart() {
        super.onStart()
        (application as LumenApplication).container.onShown()
    }

    override fun onStop() {
        super.onStop()
        // The screen going off stops this too, but then nothing else wants the memory.
        val asleep = !getSystemService(PowerManager::class.java).isInteractive
        (application as LumenApplication).container.onHidden(asleep)
    }

    override fun onDestroy() {
        if (live?.get() === this) live = null
        super.onDestroy()
    }

    /** Home pressed (or the override service redirected) while Lumen is already running. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when {
            intent.getBooleanExtra(HomeOverride.EXTRA_CONTROL_CENTER, false) -> controlCenterRequests.tryEmit(Unit)
            intent.hasCategory(Intent.CATEGORY_HOME) -> homePresses.tryEmit(intent.getBooleanExtra(HomeOverride.EXTRA_FROM_APP, false))
        }
    }

    private companion object {
        /** The newest home screen. */
        var live: WeakReference<MainActivity>? = null
    }
}
