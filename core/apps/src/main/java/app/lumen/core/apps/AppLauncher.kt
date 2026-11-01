package app.lumen.core.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle

class AppLauncher(context: Context) {

    private val context = context.applicationContext

    /**
     * Starts [app], with [options] from [android.app.ActivityOptions] such as a zoom out of its tile.
     * Returns false if the activity is gone or refuses to start.
     */
    fun launch(app: AppInfo, options: Bundle? = null): Boolean {
        val category = if (app.isTvApp) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(category)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent, options)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
