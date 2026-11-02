package app.lumen.feature.home

import android.graphics.Bitmap
import android.os.Bundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.lumen.core.apps.AppCategory
import app.lumen.core.apps.AppInfo
import app.lumen.core.apps.AppLauncher
import app.lumen.core.apps.AppRepository
import app.lumen.core.apps.ShelfArt
import app.lumen.core.apps.ShelfProgram
import app.lumen.core.apps.TopShelfRepository
import app.lumen.core.designsystem.LumenDimens
import app.lumen.core.settings.AppFolder
import app.lumen.core.settings.LauncherSettings
import app.lumen.core.settings.ScreensaverType
import app.lumen.core.settings.SettingsRepository
import app.lumen.core.settings.WallpaperLibrary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Something that takes a place in the home screen's arrangement. */
sealed interface HomeItem {
    val key: String

    data class App(val app: AppInfo) : HomeItem {
        override val key get() = app.packageName
    }

    data class Folder(val folder: AppFolder, val apps: List<AppInfo>) : HomeItem {
        override val key get() = folderKey(folder.id)
    }
}

/** Can't clash with a package name, which never has a colon. */
internal fun folderKey(id: String) = "folder:$id"

data class HomeUiState(
    val loading: Boolean = true,
    val favorites: List<AppInfo> = emptyList(),
    val allApps: List<AppInfo> = emptyList(),
    /** Everything not pinned to the Top Shelf, for the grid below it: apps, and folders holding the rest. */
    val grid: List<HomeItem> = emptyList(),
    val settings: LauncherSettings = LauncherSettings(),
) {
    fun folderOf(app: AppInfo): HomeItem.Folder? =
        grid.filterIsInstance<HomeItem.Folder>().firstOrNull { folder -> folder.apps.any { it.packageName == app.packageName } }
}

class HomeViewModel(
    private val apps: AppRepository,
    private val launcher: AppLauncher,
    private val settings: SettingsRepository,
    private val wallpapers: WallpaperLibrary,
    private val topShelf: TopShelfRepository,
) : ViewModel() {

    val state: StateFlow<HomeUiState> = combine(apps.apps, settings.settings) { list, prefs ->
        // Wait for first-run seeding too, so initial focus lands on the Top Shelf, not the grid.
        if (list == null || !prefs.favoritesInitialized) return@combine HomeUiState(loading = true, settings = prefs)
        val visible = list.filter {
            (it.isTvApp || prefs.showPhoneApps) && it.packageName !in prefs.hidden && it.packageName != ANDROID_SETTINGS_PACKAGE
        }
        val byPackage = visible.associateBy { it.packageName }
        val favorites = prefs.favorites.mapNotNull(byPackage::get)
        // A folder whose apps are all hidden or gone has nothing to show.
        val folders = prefs.folders.mapNotNull { folder ->
            folder.apps.mapNotNull(byPackage::get).takeIf { it.isNotEmpty() }?.let { HomeItem.Folder(folder, it) }
        }
        val elsewhere = favorites.toSet() + folders.flatMap { it.apps }
        val items = (visible - elsewhere).map(HomeItem::App) + folders
        HomeUiState(
            loading = false,
            favorites = favorites,
            allApps = visible,
            grid = items.sortedBy { prefs.gridOrder.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } },
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

    /** Artwork the app publishes to its TV channels, for the stage; null when it has none. */
    suspend fun shelfArt(app: AppInfo): ShelfArt? = topShelf.load(app.packageName)
    fun cachedShelfArt(app: AppInfo): ShelfArt? = topShelf.peek(app.packageName)
    suspend fun programBackdrop(program: ShelfProgram): Bitmap? = topShelf.backdrop(program)

    /** Which tile had focus when the home screen was last left, so coming back lands there. */
    var lastFocusKey: String? = null

    /**
     * Moves [app] where tvOS's "Move to" menu says. The top row has a fixed size: when full, its last
     * app makes way. A folder emptied by this goes away. Returns the id of a folder made for it.
     */
    internal fun moveApp(app: AppInfo, target: MoveTarget): String? {
        val current = state.value
        val pkg = app.packageName
        val place = current.grid.indexOfFirst { it.key == pkg }
        var favorites = current.favorites.map { it.packageName } - pkg
        var grid = current.grid.map { it.key } - pkg
        var folders = current.settings.folders.map { it.copy(apps = it.apps - pkg) }
        var created: String? = null
        when (target) {
            MoveTarget.TopRow -> {
                if (favorites.size >= LumenDimens.TilesPerRow) {
                    grid = listOf(favorites.last()) + grid
                    favorites = favorites.dropLast(1)
                }
                favorites = favorites + pkg
            }
            MoveTarget.HomeScreen -> grid = grid + pkg
            MoveTarget.NewFolder -> {
                val id = System.currentTimeMillis().toString(36)
                created = id
                folders = folders + AppFolder(id, app.category.takeIf { it != AppCategory.Other }?.displayName ?: "New Folder", listOf(pkg))
                grid = grid.toMutableList().apply { add(if (place in 0..size) place else size, folderKey(id)) }
            }
            is MoveTarget.Folder -> folders = folders.map { if (it.id == target.id) it.copy(apps = it.apps + pkg) else it }
        }
        val emptied = folders.filter { it.apps.isEmpty() }.map { folderKey(it.id) }.toSet()
        viewModelScope.launch {
            settings.saveHome(favorites, grid - emptied, folders.filter { it.apps.isNotEmpty() })
        }
        return created
    }

    fun setScreensaverType(type: ScreensaverType) = viewModelScope.launch { settings.setScreensaverType(type) }

    fun renameFolder(id: String, name: String) = viewModelScope.launch {
        val current = state.value
        settings.saveHome(
            current.favorites.map { it.packageName },
            current.grid.map { it.key },
            current.settings.folders.map { if (it.id == id) it.copy(name = name) else it },
        )
    }

    fun saveArrangement(favorites: List<HomeItem>, grid: List<HomeItem>) = viewModelScope.launch {
        settings.saveArrangement(favorites.map { it.key }, grid.map { it.key })
    }
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
            topShelf: TopShelfRepository,
        ): ViewModelProvider.Factory =
            viewModelFactory { initializer { HomeViewModel(apps, launcher, settings, wallpapers, topShelf) } }
    }
}
