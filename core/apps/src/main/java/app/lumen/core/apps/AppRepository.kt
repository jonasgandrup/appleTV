package app.lumen.core.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import java.text.Collator
import java.util.concurrent.ConcurrentHashMap

/**
 * Discovers launchable apps and keeps the list current as packages are installed,
 * updated or removed. Icons and banners are rasterized once and cached per package.
 */
class AppRepository(context: Context, scope: CoroutineScope) {

    private val context = context.applicationContext
    private val pm = this.context.packageManager
    private val cache = ConcurrentHashMap<String, AppInfo>()

    /** `null` until the first scan completes. */
    val apps: StateFlow<List<AppInfo>?> = packageChanges()
        .onStart { emit(Unit) }
        .conflate()
        .map { queryApps() }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, null)

    private fun packageChanges(): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                intent.data?.schemeSpecificPart?.let(cache::remove)
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }

    private fun queryApps(): List<AppInfo> {
        // TV activities win over phone activities from the same package.
        val entries = LinkedHashMap<String, Pair<ResolveInfo, Boolean>>()
        resolve(Intent.CATEGORY_LEANBACK_LAUNCHER).forEach { entries.putIfAbsent(it.activityInfo.packageName, it to true) }
        resolve(Intent.CATEGORY_LAUNCHER).forEach { entries.putIfAbsent(it.activityInfo.packageName, it to false) }
        entries.remove(context.packageName)

        val collator = Collator.getInstance()
        return entries.values
            .mapNotNull { (info, isTv) ->
                runCatching { cache.getOrPut(info.activityInfo.packageName) { load(info, isTv) } }.getOrNull()
            }
            .sortedWith(compareBy(collator) { it.label })
    }

    private fun resolve(category: String): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        return if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
    }

    private fun load(info: ResolveInfo, isTv: Boolean): AppInfo {
        val activity = info.activityInfo
        val iconDrawable = activity.loadIcon(pm)
        val accent = accentColor(iconDrawable.toBitmap(ICON_PX, ICON_PX))
        val banner = if (isTv) activity.loadBanner(pm) else null
        return AppInfo(
            packageName = activity.packageName,
            activityName = activity.name,
            label = info.loadLabel(pm).toString().trim(),
            art = TileArt.render(banner, iconDrawable, accent, BANNER_WIDTH_PX, BANNER_HEIGHT_PX),
            accentColor = accent,
            category = AppCategory.from(activity.applicationInfo.category),
            isTvApp = isTv,
        )
    }

    private companion object {
        // Tile art is kept for every installed app, so it's only as large as a focused tile is drawn: 291 px wide.
        const val BANNER_WIDTH_PX = 320
        const val BANNER_HEIGHT_PX = 180
        const val ICON_PX = 144
        const val SAMPLE = 24
        val DEFAULT_ACCENT = Color.rgb(44, 44, 52)

        /** Saturation-weighted average of the icon, clamped dark enough for white text on top. */
        fun accentColor(icon: Bitmap): Int {
            val small = Bitmap.createScaledBitmap(icon, SAMPLE, SAMPLE, true)
            val pixels = IntArray(SAMPLE * SAMPLE).also { small.getPixels(it, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE) }
            val hsv = FloatArray(3)
            var r = 0.0
            var g = 0.0
            var b = 0.0
            var total = 0.0
            for (c in pixels) {
                if (Color.alpha(c) < 200) continue
                Color.colorToHSV(c, hsv)
                val weight = hsv[1] * hsv[2] + 0.02
                r += Color.red(c) * weight
                g += Color.green(c) * weight
                b += Color.blue(c) * weight
                total += weight
            }
            if (total == 0.0) return DEFAULT_ACCENT
            Color.colorToHSV(Color.rgb((r / total).toInt(), (g / total).toInt(), (b / total).toInt()), hsv)
            hsv[2] = hsv[2].coerceIn(0.22f, 0.5f)
            return Color.HSVToColor(hsv)
        }
    }
}
