package app.lumen.core.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import java.net.URISyntaxException

/**
 * Opens [item] where the user left off, through the intent its app published. A web link is kept
 * inside the app rather than handed to a browser. If the app gave no intent, or it no longer
 * resolves, the app opens at its start screen instead. Returns false when neither works.
 */
fun openUpNext(context: Context, item: UpNextItem, options: Bundle? = null): Boolean =
    openProgram(context, item.packageName, item.intentUri, options)

private fun openProgram(context: Context, packageName: String, intentUri: String?, options: Bundle?): Boolean {
    val deepLink = intentUri?.let { uri ->
        try {
            Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).apply {
                // An app's row only opens that app: never another app, nor Atrium itself.
                if (component?.packageName != packageName) component = null
                setPackage(packageName)
                selector = null
                flags = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION).inv()
            }
        } catch (e: URISyntaxException) {
            Log.w(TAG, "Bad intent URI from $packageName", e)
            null
        }
    }
    val pm = context.packageManager
    val fallback = pm.getLeanbackLaunchIntentForPackage(packageName) ?: pm.getLaunchIntentForPackage(packageName)
    for (intent in listOfNotNull(deepLink, fallback)) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options)
            return true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Can't open $intent", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Can't open $intent", e)
        }
    }
    return false
}

private const val TAG = "UpNext"
