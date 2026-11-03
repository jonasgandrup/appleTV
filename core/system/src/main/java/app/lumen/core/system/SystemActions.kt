package app.lumen.core.system

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings

/** Shortcuts into system screens. Each returns false if the device has no matching screen. */
object SystemActions {

    fun openAndroidSettings(context: Context) = start(context, Intent(Settings.ACTION_SETTINGS))

    fun openNetworkSettings(context: Context) = start(context, Intent(Settings.ACTION_WIFI_SETTINGS))

    fun openSoundSettings(context: Context) = start(context, Intent(Settings.ACTION_SOUND_SETTINGS))

    /** Google accounts and profiles, which an app can open but can't switch between itself. */
    fun openAccounts(context: Context) = start(context, Intent(Settings.ACTION_SYNC_SETTINGS))

    /** Android's own confirmation for uninstalling [packageName]. */
    fun requestUninstall(context: Context, packageName: String) =
        start(context, Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null)))

    /** False for apps that came with the TV, which can't be uninstalled. */
    fun canUninstall(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(packageName, 0).flags and ApplicationInfo.FLAG_SYSTEM == 0
    }.getOrDefault(false)

    /** Puts the TV to sleep through the Home Button service. False when that's not turned on: nothing else may do this. */
    fun sleep(): Boolean = HomeOverrideService.sleep()

    fun openSearch(context: Context) = start(context, Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH))

    fun openNotificationAccess(context: Context) = start(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

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
