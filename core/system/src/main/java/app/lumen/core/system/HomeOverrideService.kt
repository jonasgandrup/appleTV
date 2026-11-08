package app.lumen.core.system

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
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
 *
 * It also makes holding Home open Control Center, as holding the TV button does on tvOS. Apps can't
 * see the Home key themselves, so this is the only place that can tell a hold from a press. Of all
 * keys it sees it takes only Home, and passes a quick press on as an ordinary Home.
 */
class HomeOverrideService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var enabled = true
    private var lastRedirect = 0L
    /** The last thing in front other than the stock home, which Home always passes through first. */
    private var lastApp: String? = null
    private val handler = Handler(Looper.getMainLooper())
    /** The stock home appeared again too soon after a redirect, as a second quick press of Home makes it do. */
    private val redirectLater = Runnable { if (enabled && !HomeOverride.isSuspended()) redirect() }
    private var homeHeld = false
    private val onHomeHeld = Runnable {
        homeHeld = true
        runCatching { startActivity(HomeOverride.lumenHomeIntent(this).putExtra(HomeOverride.EXTRA_CONTROL_CENTER, true)) }
    }

    /** A Sleep Timer has nothing left to do once the TV sleeps by itself. */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = SleepTimer.cancel()
    }

    override fun onServiceConnected() {
        instance = this
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
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
            if (pkg !in TRANSIENT_PACKAGES) {
                HomeOverride.resume()
                lastApp = pkg
                handler.removeCallbacks(redirectLater)
            }
            return
        }
        if (!enabled || HomeOverride.isSuspended()) return

        val sinceRedirect = SystemClock.elapsedRealtime() - lastRedirect
        if (sinceRedirect < DEBOUNCE_MS) {
            // Dropped, this would strand the user on the stock home. Whatever comes to the front next cancels it.
            handler.removeCallbacks(redirectLater)
            handler.postDelayed(redirectLater, DEBOUNCE_MS - sinceRedirect)
            return
        }
        redirect()
    }

    private fun redirect() {
        lastRedirect = SystemClock.elapsedRealtime()
        val fromApp = lastApp != null && lastApp != packageName
        runCatching { startActivity(HomeOverride.lumenHomeIntent(this).putExtra(HomeOverride.EXTRA_FROM_APP, fromApp)) }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME || !enabled) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                homeHeld = false
                handler.postDelayed(onHomeHeld, HOME_HOLD_MS)
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(onHomeHeld)
                if (!homeHeld) performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
        return true
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        runCatching { unregisterReceiver(screenOff) }
        handler.removeCallbacks(onHomeHeld)
        handler.removeCallbacks(redirectLater)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** The running service, while it's turned on. */
        @Volatile
        private var instance: HomeOverrideService? = null

        /** Puts the TV to sleep; false when the service isn't turned on. */
        internal fun sleep(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false

        private const val DEBOUNCE_MS = 750L
        private const val HOME_HOLD_MS = 500L

        /** Overlays that appear on top of other apps and shouldn't end a suspension. */
        private val TRANSIENT_PACKAGES = setOf("com.android.systemui", "android")
    }
}
