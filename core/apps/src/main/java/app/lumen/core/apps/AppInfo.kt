package app.lumen.core.apps

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap

/** A launchable app as shown on the home screen. One entry per package. */
data class AppInfo(
    val packageName: String,
    val activityName: String,
    val label: String,
    /** 16:9 tile artwork: the app's TV banner when it has a sharp one, otherwise a card built from its icon. */
    val art: Bitmap,
    val icon: Bitmap,
    /** A deep, saturated color derived from the icon, used behind icon-only tiles. */
    val accentColor: Int,
    val category: AppCategory,
    /** True when the app declares a LEANBACK_LAUNCHER activity. */
    val isTvApp: Boolean,
) {
    val component: ComponentName get() = ComponentName(packageName, activityName)
}

enum class AppCategory(val displayName: String) {
    Video("Movies & TV"),
    Audio("Music & Audio"),
    Game("Games"),
    Image("Photos"),
    News("News"),
    Social("Social"),
    Maps("Maps"),
    Productivity("Productivity"),
    Other("App");

    companion object {
        fun from(category: Int): AppCategory = when (category) {
            ApplicationInfo.CATEGORY_VIDEO -> Video
            ApplicationInfo.CATEGORY_AUDIO -> Audio
            ApplicationInfo.CATEGORY_GAME -> Game
            ApplicationInfo.CATEGORY_IMAGE -> Image
            ApplicationInfo.CATEGORY_NEWS -> News
            ApplicationInfo.CATEGORY_SOCIAL -> Social
            ApplicationInfo.CATEGORY_MAPS -> Maps
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> Productivity
            else -> Other
        }
    }
}
