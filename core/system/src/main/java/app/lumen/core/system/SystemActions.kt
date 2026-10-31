package app.lumen.core.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Shortcuts into system screens. Each returns false if the device has no matching screen. */
object SystemActions {

    fun openAndroidSettings(context: Context) = start(context, Intent(Settings.ACTION_SETTINGS))

    fun openAccessibilitySettings(context: Context) =
        start(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun openAppInfo(context: Context, packageName: String) = start(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
    )

    /** Opens the stock Google TV / Android TV home, pausing the Home override while it is up. */
    fun openStockHome(context: Context): Boolean {
        HomeOverride.suspend()
        val pm = context.packageManager
        val stock = StockLaunchers.packages.firstOrNull { pkg ->
            runCatching { pm.getApplicationInfo(pkg, 0).enabled }.getOrDefault(false)
        } ?: return false
        return start(context, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(stock))
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
