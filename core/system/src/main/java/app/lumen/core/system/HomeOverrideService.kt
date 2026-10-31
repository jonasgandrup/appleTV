package app.lumen.core.system

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import app.lumen.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Google TV hard-wires the remote's Home button to its own launcher and offers no way to pick
 * a different default home. This service watches for the stock launcher's window coming to the
 * foreground (after Home, boot, or leaving an app) and immediately brings Lumen back on top.
 *
 * It reads only the package name of foreground windows; it never reads screen content.
 * Being bound by the system also exempts us from background-activity-start restrictions.
 */
class HomeOverrideService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var enabled = true
    private var lastRedirect = 0L

    override fun onServiceConnected() {
        scope.launch {
            SettingsRepository(this@HomeOverrideService).settings
                .map { it.homeOverride }
                .distinctUntilChanged()
                .collect { enabled = it }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        if (pkg !in StockLaunchers.packages) {
            // The user moved on from the stock home they asked for; resume redirecting.
            if (pkg !in TRANSIENT_PACKAGES) HomeOverride.resume()
            return
        }
        if (!enabled || HomeOverride.isSuspended()) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastRedirect < DEBOUNCE_MS) return
        lastRedirect = now
        runCatching { startActivity(HomeOverride.lumenHomeIntent(this)) }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val DEBOUNCE_MS = 750L

        /** Overlays that appear on top of other apps and shouldn't end a suspension. */
        val TRANSIENT_PACKAGES = setOf("com.android.systemui", "android")
    }
}
