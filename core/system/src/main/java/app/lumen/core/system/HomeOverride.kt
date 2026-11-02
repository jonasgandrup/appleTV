package app.lumen.core.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings

/** Package names of the stock home screens we redirect away from. */
object StockLaunchers {
    val packages = setOf(
        "com.google.android.apps.tv.launcherx", // Google TV (Chromecast with Google TV, Google TV Streamer)
        "com.google.android.tvlauncher",        // Android TV home
        "com.google.android.leanbacklauncher",  // Legacy Android TV launcher
    )
}

object HomeOverride {

    // Shared in-process between the settings UI and the accessibility service.
    @Volatile
    private var suspendedUntil = 0L

    /**
     * Lets the stock home show without being redirected, until another app comes to the
     * foreground or [durationMs] elapses.
     */
    fun suspend(durationMs: Long = 10 * 60_000L) {
        suspendedUntil = SystemClock.elapsedRealtime() + durationMs
    }

    internal fun resume() {
        suspendedUntil = 0L
    }

    internal fun isSuspended(): Boolean = SystemClock.elapsedRealtime() < suspendedUntil

    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, HomeOverrideService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    /** The `adb shell settings` value that enables the service, for the docs and settings screen. */
    fun serviceComponent(context: Context): String =
        ComponentName(context, HomeOverrideService::class.java).flattenToString()

    /** An intent extra: open Control Center once Lumen is in front, for Home held down. */
    const val EXTRA_CONTROL_CENTER = "app.lumen.core.system.CONTROL_CENTER"

    /** An intent that brings Lumen's home activity to the front. */
    internal fun lumenHomeIntent(context: Context): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}
