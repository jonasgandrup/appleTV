package app.lumen.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.lumen.core.apps.TopShelfRepository
import app.lumen.core.system.HomeOverride
import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : ComponentActivity() {

    private val homePresses = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val controlCenterRequests = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as LumenApplication).container
        if (intent.getBooleanExtra(HomeOverride.EXTRA_CONTROL_CENTER, false)) controlCenterRequests.tryEmit(Unit)
        setContent { LumenApp(container, homePresses, controlCenterRequests) }
        // For the apps' own artwork on the stage. Android stops asking after the user declines twice.
        if (!container.topShelf.hasPermission) requestPermissions(arrayOf(TopShelfRepository.PERMISSION), 0)
    }

    /** Home pressed (or the override service redirected) while Lumen is already running. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when {
            intent.getBooleanExtra(HomeOverride.EXTRA_CONTROL_CENTER, false) -> controlCenterRequests.tryEmit(Unit)
            intent.hasCategory(Intent.CATEGORY_HOME) -> homePresses.tryEmit(Unit)
        }
    }
}
