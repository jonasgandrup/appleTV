package app.lumen.feature.home

import android.os.Bundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.lumen.core.apps.AppInfo
import app.lumen.core.apps.AppLauncher
import app.lumen.core.apps.AppRepository
import app.lumen.core.settings.LauncherSettings
import app.lumen.core.settings.SettingsRepository
import app.lumen.core.settings.WallpaperLibrary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val favorites: List<AppInfo> = emptyList(),
    val allApps: List<AppInfo> = emptyList(),
    /** Everything not pinned to the Top Shelf, for the grid below it. */
    val others: List<AppInfo> = emptyList(),
    val settings: LauncherSettings = LauncherSettings(),
)

class HomeViewModel(
    private val apps: AppRepository,
    private val launcher: AppLauncher,
    private val settings: SettingsRepository,
    private val wallpapers: WallpaperLibrary,
) : ViewModel() {

    val state: StateFlow<HomeUiState> = combine(apps.apps, settings.settings) { list, prefs ->
        // Wait for first-run seeding too, so initial focus lands on the Top Shelf, not the grid.
        if (list == null || !prefs.favoritesInitialized) return@combine HomeUiState(loading = true, settings = prefs)
        val visible = list.filter {
            (it.isTvApp || prefs.showPhoneApps) && it.packageName !in prefs.hidden && it.packageName != ANDROID_SETTINGS_PACKAGE
        }
        val byPackage = visible.associateBy { it.packageName }
        val favorites = prefs.favorites.mapNotNull(byPackage::get)
        HomeUiState(
            loading = false,
            favorites = favorites,
            allApps = visible,
            others = visible - favorites.toSet(),
            settings = prefs,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        seedFavorites()
    }

    fun launch(app: AppInfo, options: Bundle? = null) {
        if (launcher.launch(app, options)) viewModelScope.launch { settings.recordLaunch(app.packageName) }
    }

    /** The user's wallpaper photos, which the screensaver prefers over the built-in gradients. */
    suspend fun screensaverPhotos(): List<String> = wallpapers.list().map { it.path }

    fun toggleFavorite(app: AppInfo) = viewModelScope.launch { settings.toggleFavorite(app.packageName) }
    fun moveFavorite(app: AppInfo, offset: Int) = viewModelScope.launch { settings.moveFavorite(app.packageName, offset) }
    fun hide(app: AppInfo) = viewModelScope.launch { settings.setHidden(app.packageName, true) }

    /** On first run, pin the popular streaming apps that are installed, topped up to a full row. */
    private fun seedFavorites() = viewModelScope.launch {
        if (settings.settings.first().favoritesInitialized) return@launch
        val tvApps = apps.apps.filterNotNull().first().filter { it.isTvApp && it.packageName != ANDROID_SETTINGS_PACKAGE }
        val preferred = PREFERRED_PACKAGES.mapNotNull { pkg -> tvApps.find { it.packageName == pkg } }
        val seed = (preferred + tvApps).distinct().take(SEED_COUNT).map { it.packageName }
        settings.initializeFavorites(seed)
    }

    companion object {
        private const val SEED_COUNT = 6

        /** Left off the home screen: the Settings tile ends the grid, and Lumen's Settings links to it. */
        private const val ANDROID_SETTINGS_PACKAGE = "com.android.tv.settings"

        private val PREFERRED_PACKAGES = listOf(
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.disney.disneyplus",
            "com.amazon.amazonvideo.livingroom",
            "com.apple.atve.androidtv.appletv",
            "com.wbd.stream",
            "com.spotify.tv.android",
            "com.plexapp.android",
            "com.google.android.youtube.tvmusic",
        )

        fun factory(
            apps: AppRepository,
            launcher: AppLauncher,
            settings: SettingsRepository,
            wallpapers: WallpaperLibrary,
        ): ViewModelProvider.Factory =
            viewModelFactory { initializer { HomeViewModel(apps, launcher, settings, wallpapers) } }
    }
}
