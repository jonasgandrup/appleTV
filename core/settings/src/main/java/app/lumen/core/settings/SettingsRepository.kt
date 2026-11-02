package app.lumen.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.lumen.core.settings.Wallpaper.Companion.encode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

// Top-level delegate: one DataStore per process, shared by the activity and the accessibility service.
private val Context.launcherDataStore: DataStore<Preferences> by preferencesDataStore(name = "launcher_settings")

class SettingsRepository(context: Context) {

    private val store = context.applicationContext.launcherDataStore

    val settings: Flow<LauncherSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }
        .distinctUntilChanged()

    suspend fun initializeFavorites(packages: List<String>) {
        store.edit {
            it[Keys.Favorites] = packages.encodeList()
            it[Keys.FavoritesInitialized] = true
        }
    }

    suspend fun toggleFavorite(packageName: String) = editFavorites { list ->
        if (packageName in list) list - packageName else list + packageName
    }

    /** Saves a whole home screen arrangement at once, as tvOS does when an app is dropped in Edit Home Screen. */
    suspend fun saveArrangement(favorites: List<String>, grid: List<String>) {
        store.edit {
            it[Keys.Favorites] = favorites.encodeList()
            it[Keys.FavoritesInitialized] = true
            it[Keys.GridOrder] = grid.encodeList()
        }
    }

    /** Saves the arrangement together with its folders, as moving an app in or out of one changes both. */
    suspend fun saveHome(favorites: List<String>, grid: List<String>, folders: List<AppFolder>) {
        store.edit {
            it[Keys.Favorites] = favorites.encodeList()
            it[Keys.FavoritesInitialized] = true
            it[Keys.GridOrder] = grid.encodeList()
            it[Keys.Folders] = folders.encodeFolders()
        }
    }

    suspend fun setHidden(packageName: String, hidden: Boolean) {
        store.edit {
            val current = it[Keys.Hidden].orEmpty()
            it[Keys.Hidden] = if (hidden) current + packageName else current - packageName
        }
    }

    suspend fun recordLaunch(packageName: String) {
        store.edit {
            val recents = it[Keys.Recents].decodeList()
            it[Keys.Recents] = (listOf(packageName) + (recents - packageName)).take(MAX_RECENTS).encodeList()
        }
    }

    suspend fun setWallpaper(wallpaper: Wallpaper) = set(Keys.Wallpaper, wallpaper.encode())
    suspend fun setAmbientBackdrop(enabled: Boolean) = set(Keys.AmbientBackdrop, enabled)
    suspend fun setShowClock(enabled: Boolean) = set(Keys.ShowClock, enabled)
    suspend fun setShowPhoneApps(enabled: Boolean) = set(Keys.ShowPhoneApps, enabled)
    suspend fun setHomeOverride(enabled: Boolean) = set(Keys.HomeOverride, enabled)
    suspend fun setNavigationSounds(enabled: Boolean) = set(Keys.NavigationSounds, enabled)
    suspend fun setScreensaverMinutes(minutes: Int) = set(Keys.ScreensaverMinutes, minutes)
    suspend fun setScreensaverType(type: ScreensaverType) = set(Keys.ScreensaverType, type.name)
    suspend fun setScreensaverClock(enabled: Boolean) = set(Keys.ScreensaverClock, enabled)
    suspend fun setProfileName(name: String) = set(Keys.ProfileName, name)

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }

    private suspend fun editFavorites(transform: (List<String>) -> List<String>) {
        store.edit { prefs: MutablePreferences ->
            prefs[Keys.Favorites] = transform(prefs[Keys.Favorites].decodeList()).encodeList()
            prefs[Keys.FavoritesInitialized] = true
        }
    }

    private fun Preferences.toSettings(): LauncherSettings {
        val defaults = LauncherSettings()
        return LauncherSettings(
            favorites = this[Keys.Favorites].decodeList(),
            favoritesInitialized = this[Keys.FavoritesInitialized] ?: false,
            gridOrder = this[Keys.GridOrder].decodeList(),
            folders = this[Keys.Folders].decodeFolders(),
            profileName = this[Keys.ProfileName].orEmpty(),
            hidden = this[Keys.Hidden].orEmpty(),
            recents = this[Keys.Recents].decodeList(),
            wallpaper = Wallpaper.decode(this[Keys.Wallpaper]),
            ambientBackdrop = this[Keys.AmbientBackdrop] ?: defaults.ambientBackdrop,
            showClock = this[Keys.ShowClock] ?: defaults.showClock,
            showPhoneApps = this[Keys.ShowPhoneApps] ?: defaults.showPhoneApps,
            homeOverride = this[Keys.HomeOverride] ?: defaults.homeOverride,
            navigationSounds = this[Keys.NavigationSounds] ?: defaults.navigationSounds,
            screensaverMinutes = this[Keys.ScreensaverMinutes] ?: defaults.screensaverMinutes,
            screensaverClock = this[Keys.ScreensaverClock] ?: defaults.screensaverClock,
            screensaverType = ScreensaverType.entries.find { it.name == this[Keys.ScreensaverType] } ?: defaults.screensaverType,
        )
    }

    private object Keys {
        val Favorites = stringPreferencesKey("favorites")
        val FavoritesInitialized = booleanPreferencesKey("favorites_initialized")
        val GridOrder = stringPreferencesKey("grid_order")
        val Folders = stringPreferencesKey("folders")
        val ProfileName = stringPreferencesKey("profile_name")
        val Hidden = stringSetPreferencesKey("hidden")
        val Recents = stringPreferencesKey("recents")
        val Wallpaper = stringPreferencesKey("wallpaper")
        val AmbientBackdrop = booleanPreferencesKey("ambient_backdrop")
        val ShowClock = booleanPreferencesKey("show_clock")
        val ShowPhoneApps = booleanPreferencesKey("show_phone_apps")
        val HomeOverride = booleanPreferencesKey("home_override")
        val NavigationSounds = booleanPreferencesKey("navigation_sounds")
        val ScreensaverMinutes = intPreferencesKey("screensaver_minutes")
        val ScreensaverType = stringPreferencesKey("screensaver_type")
        val ScreensaverClock = booleanPreferencesKey("screensaver_clock")
    }

    private companion object {
        const val MAX_RECENTS = 10

        // Package names never contain newlines, so a newline-joined string keeps order cheaply.
        fun String?.decodeList(): List<String> = this?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        fun List<String>.encodeList(): String = joinToString("\n")

        // JSON, since a folder's name can hold any character.
        fun List<AppFolder>.encodeFolders(): String = JSONArray().also { array ->
            forEach { folder ->
                array.put(
                    JSONObject()
                        .put("id", folder.id)
                        .put("name", folder.name)
                        .put("apps", JSONArray(folder.apps)),
                )
            }
        }.toString()

        fun String?.decodeFolders(): List<AppFolder> {
            if (this == null) return emptyList()
            val array = runCatching { JSONArray(this) }.getOrNull() ?: return emptyList()
            return List(array.length()) { i ->
                val folder = array.getJSONObject(i)
                val apps = folder.getJSONArray("apps")
                AppFolder(folder.getString("id"), folder.getString("name"), List(apps.length()) { apps.getString(it) })
            }
        }
    }
}
