package app.lumen.core.system

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Puts the TV to sleep after a while, as tvOS's Sleep Timer does, whatever's in front by then.
 * Sleeping goes through the Home Button service, which also keeps this process alive behind other
 * apps. It's over once the TV sleeps by itself, and isn't remembered if the process does die.
 */
object SleepTimer {

    private val handler = Handler(Looper.getMainLooper())
    private val deadline = MutableStateFlow<Long?>(null)

    /** When the timer runs out, on the [SystemClock.elapsedRealtime] clock; null when it isn't running. */
    val endsAt: StateFlow<Long?> = deadline

    private val fire = Runnable {
        val at = deadline.value ?: return@Runnable
        deadline.value = null
        // A handler's clock stops while the device sleeps, so this can run long after it was due, on waking.
        if (SystemClock.elapsedRealtime() - at < LATE_LIMIT_MS) SystemActions.sleep()
    }

    fun start(minutes: Int) {
        handler.removeCallbacks(fire)
        val delay = minutes * 60_000L
        deadline.value = SystemClock.elapsedRealtime() + delay
        handler.postDelayed(fire, delay)
    }

    fun cancel() {
        handler.removeCallbacks(fire)
        deadline.value = null
    }

    private const val LATE_LIMIT_MS = 60_000L
}
