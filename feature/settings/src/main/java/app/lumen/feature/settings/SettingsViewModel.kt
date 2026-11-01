package app.lumen.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.lumen.core.apps.AppInfo
import app.lumen.core.apps.AppRepository
import app.lumen.core.settings.LauncherSettings
import app.lumen.core.settings.SettingsRepository
import app.lumen.core.settings.Wallpaper
import app.lumen.core.settings.WallpaperLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    apps: AppRepository,
    private val wallpapers: WallpaperLibrary,
) : ViewModel() {

    val settings: StateFlow<LauncherSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LauncherSettings())

    val hiddenApps: StateFlow<List<AppInfo>> =
        combine(apps.apps.filterNotNull(), settingsRepository.settings) { list, prefs ->
            list.filter { it.packageName in prefs.hidden }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _customWallpapers = MutableStateFlow<List<File>>(emptyList())
    val customWallpapers: StateFlow<List<File>> = _customWallpapers.asStateFlow()

    val wallpaperDirectory: String get() = wallpapers.directory.absolutePath

    fun refreshWallpapers() = viewModelScope.launch { _customWallpapers.value = wallpapers.list() }

    fun importWallpaper(uri: Uri) = viewModelScope.launch {
        val file = wallpapers.import(uri) ?: return@launch
        settingsRepository.setWallpaper(Wallpaper.Image(file.absolutePath))
        _customWallpapers.value = wallpapers.list()
    }

    fun setWallpaper(wallpaper: Wallpaper) = viewModelScope.launch { settingsRepository.setWallpaper(wallpaper) }
    fun setAmbientBackdrop(enabled: Boolean) = viewModelScope.launch { settingsRepository.setAmbientBackdrop(enabled) }
    fun setShowClock(enabled: Boolean) = viewModelScope.launch { settingsRepository.setShowClock(enabled) }
    fun setShowPhoneApps(enabled: Boolean) = viewModelScope.launch { settingsRepository.setShowPhoneApps(enabled) }
    fun setHomeOverride(enabled: Boolean) = viewModelScope.launch { settingsRepository.setHomeOverride(enabled) }
    fun setNavigationSounds(enabled: Boolean) = viewModelScope.launch { settingsRepository.setNavigationSounds(enabled) }
    fun setScreensaverMinutes(minutes: Int) = viewModelScope.launch { settingsRepository.setScreensaverMinutes(minutes) }
    fun unhide(app: AppInfo) = viewModelScope.launch { settingsRepository.setHidden(app.packageName, false) }

    companion object {
        fun factory(settings: SettingsRepository, apps: AppRepository, wallpapers: WallpaperLibrary): ViewModelProvider.Factory =
            viewModelFactory { initializer { SettingsViewModel(settings, apps, wallpapers) } }
    }
}
