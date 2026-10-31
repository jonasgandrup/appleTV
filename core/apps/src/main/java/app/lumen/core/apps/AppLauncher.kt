package app.lumen.core.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

class AppLauncher(context: Context) {

    private val context = context.applicationContext

    /** Starts [app]. Returns false if the activity is gone or refuses to start. */
    fun launch(app: AppInfo): Boolean {
        val category = if (app.isTvApp) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(category)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
