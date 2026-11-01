package app.lumen.feature.settings

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.core.designsystem.LumenColors
import app.lumen.core.designsystem.LumenDimens
import app.lumen.core.designsystem.LumenText
import app.lumen.core.designsystem.LumenType
import app.lumen.core.designsystem.MenuRow
import app.lumen.core.designsystem.SectionTitle
import app.lumen.core.designsystem.WallpaperBackground
import app.lumen.core.settings.GradientPreset
import app.lumen.core.settings.LauncherSettings
import app.lumen.core.settings.Wallpaper
import app.lumen.core.system.HomeOverride
import app.lumen.core.system.SystemActions

private enum class SettingsPage(val title: String, val description: String) {
    Main("Settings", "Make Lumen yours. Changes apply instantly."),
    Wallpaper("Wallpaper", "Pick a background for the home screen. Focus a wallpaper to preview it."),
    HiddenApps("Hidden Apps", "Apps you've hidden from the home screen. Select one to bring it back."),
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(SettingsPage.Main) }
    var preview by remember { mutableStateOf<Wallpaper?>(null) }

    BackHandler(enabled = page != SettingsPage.Main) {
        page = SettingsPage.Main
        preview = null
    }

    Box(Modifier.fillMaxSize().background(LumenColors.Background)) {
        WallpaperBackground(preview ?: settings.wallpaper, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))

        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = LumenDimens.SafeHorizontal, vertical = LumenDimens.SafeVertical),
        ) {
            AnimatedContent(
                targetState = page,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) },
                modifier = Modifier.weight(0.4f).fillMaxHeight(),
                label = "settingsTitle",
            ) { p ->
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                    LumenText(p.title, LumenType.Display)
                    Spacer(Modifier.height(12.dp))
                    LumenText(p.description, LumenType.Body, color = LumenColors.TextSecondary)
                }
            }
            Spacer(Modifier.width(40.dp))
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    (fadeIn(tween(300)) + slideInHorizontally(tween(300)) { it / 12 }) togetherWith fadeOut(tween(150))
                },
                modifier = Modifier.weight(0.6f).fillMaxHeight(),
                label = "settingsPage",
            ) { p ->
                when (p) {
                    SettingsPage.Main -> MainPage(settings, viewModel, onNavigate = { page = it })
                    SettingsPage.Wallpaper -> WallpaperPage(settings, viewModel, onPreview = { preview = it })
                    SettingsPage.HiddenApps -> HiddenAppsPage(viewModel)
                }
            }
        }
    }
}

@Composable
private fun rememberInitialFocus(): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
    return requester
}

private fun onOff(value: Boolean) = if (value) "On" else "Off"

/** Select cycles through these, in minutes; 0 is Off. */
private val ScreensaverDelays = listOf(2, 5, 10, 15, 0)

private fun nextScreensaverDelay(current: Int): Int =
    ScreensaverDelays[(ScreensaverDelays.indexOf(current) + 1).mod(ScreensaverDelays.size)]

@Composable
private fun MainPage(settings: LauncherSettings, viewModel: SettingsViewModel, onNavigate: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val first = rememberInitialFocus()
    var serviceEnabled by remember { mutableStateOf(HomeOverride.isServiceEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { serviceEnabled = HomeOverride.isServiceEnabled(context) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    val hiddenCount = settings.hidden.size

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item { SectionTitle("Appearance") }
        item {
            MenuRow(
                title = "Wallpaper",
                value = when (val w = settings.wallpaper) {
                    is Wallpaper.Gradient -> w.preset.displayName
                    is Wallpaper.Image -> "Photo"
                },
                onClick = { onNavigate(SettingsPage.Wallpaper) },
                modifier = Modifier.focusRequester(first),
            )
        }
        item {
            MenuRow(
                title = "Ambient Backdrop",
                subtitle = "Tint the background with the focused app's colors",
                value = onOff(settings.ambientBackdrop),
                onClick = { viewModel.setAmbientBackdrop(!settings.ambientBackdrop) },
            )
        }
        item { MenuRow("Clock", { viewModel.setShowClock(!settings.showClock) }, value = onOff(settings.showClock)) }
        item {
            MenuRow(
                title = "Screensaver",
                subtitle = "Slowly drifting wallpaper and a clock when the home screen sits idle. Uses your photos if you've added any.",
                value = if (settings.screensaverMinutes == 0) "Off" else "After ${settings.screensaverMinutes} min",
                onClick = { viewModel.setScreensaverMinutes(nextScreensaverDelay(settings.screensaverMinutes)) },
            )
        }
        item {
            MenuRow(
                title = "Navigation Sounds",
                subtitle = "A soft click as you move around and select",
                value = onOff(settings.navigationSounds),
                onClick = { viewModel.setNavigationSounds(!settings.navigationSounds) },
            )
        }

        item { SectionTitle("Apps") }
        item {
            MenuRow(
                title = "Show Non-TV Apps",
                subtitle = "Include phone apps that aren't designed for a remote",
                value = onOff(settings.showPhoneApps),
                onClick = { viewModel.setShowPhoneApps(!settings.showPhoneApps) },
            )
        }
        item {
            MenuRow(
                title = "Hidden Apps",
                value = if (hiddenCount == 0) "None" else hiddenCount.toString(),
                onClick = { onNavigate(SettingsPage.HiddenApps) },
            )
        }

        item { SectionTitle("Home Button") }
        item {
            MenuRow(
                title = "Open Lumen with Home",
                subtitle = "Replace the Google TV home screen when you press Home",
                value = onOff(settings.homeOverride),
                onClick = { viewModel.setHomeOverride(!settings.homeOverride) },
            )
        }
        item {
            MenuRow(
                title = "Home Button Service",
                subtitle = if (serviceEnabled) {
                    "Enabled in Accessibility"
                } else {
                    "Required on Google TV. Turn on \"Lumen Home Button\" in Accessibility."
                },
                value = if (serviceEnabled) "On" else "Set Up",
                onClick = { SystemActions.openAccessibilitySettings(context) },
            )
        }
        item {
            MenuRow(
                title = "Open Google TV Home",
                subtitle = "Visit the original home screen. Lumen returns after you open another app.",
                onClick = { SystemActions.openStockHome(context) },
            )
        }

        item { SectionTitle("System") }
        item { MenuRow("Android Settings", { SystemActions.openAndroidSettings(context) }) }
        item { MenuRow("About Lumen", {}, value = "Version $version") }
    }
}

@Composable
private fun WallpaperPage(settings: LauncherSettings, viewModel: SettingsViewModel, onPreview: (Wallpaper) -> Unit) {
    val context = LocalContext.current
    val custom by viewModel.customWallpapers.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshWallpapers() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importWallpaper(uri)
    }
    // Many TV builds ship without a document picker, so only offer import when one exists.
    val canPick = remember {
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
            .resolveActivity(context.packageManager) != null
    }

    val presets = GradientPreset.entries.map { Wallpaper.Gradient(it) }
    val images = custom.map { Wallpaper.Image(it.absolutePath) }
    val firstFocusIndex = (presets + images).indexOf(settings.wallpaper).coerceAtLeast(0)
    val first = rememberInitialFocus()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { SectionTitle("Gradients") }
        item {
            SwatchGrid(presets, offset = 0, settings.wallpaper, first, firstFocusIndex, viewModel::setWallpaper, onPreview)
        }
        item { SectionTitle("Your Photos") }
        if (images.isNotEmpty()) {
            item {
                SwatchGrid(images, offset = presets.size, settings.wallpaper, first, firstFocusIndex, viewModel::setWallpaper, onPreview)
            }
        }
        if (canPick) {
            item { MenuRow("Import from Device…", { runCatching { picker.launch(arrayOf("image/*")) } }) }
        }
        item {
            LumenText(
                "Add photos from your computer over Wi-Fi:\nadb push photo.jpg ${viewModel.wallpaperDirectory}/",
                LumenType.Caption,
                color = LumenColors.TextTertiary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun SwatchGrid(
    wallpapers: List<Wallpaper>,
    offset: Int,
    selected: Wallpaper,
    firstFocus: FocusRequester,
    firstFocusIndex: Int,
    onSelect: (Wallpaper) -> Unit,
    onPreview: (Wallpaper) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        wallpapers.chunked(3).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEachIndexed { columnIndex, wallpaper ->
                    val index = offset + rowIndex * 3 + columnIndex
                    WallpaperSwatch(
                        wallpaper = wallpaper,
                        selected = wallpaper == selected,
                        onClick = { onSelect(wallpaper) },
                        onFocused = { onPreview(wallpaper) },
                        modifier = if (index == firstFocusIndex) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun HiddenAppsPage(viewModel: SettingsViewModel) {
    val hidden by viewModel.hiddenApps.collectAsStateWithLifecycle()
    val first = rememberInitialFocus()

    if (hidden.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            LumenText("No hidden apps.", LumenType.Headline, color = LumenColors.TextSecondary)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(hidden, key = { it.packageName }) { app ->
            val icon = remember(app.icon) { app.icon.asImageBitmap() }
            MenuRow(
                title = app.label,
                value = "Show",
                onClick = { viewModel.unhide(app) },
                leading = { Image(icon, null, Modifier.size(32.dp), filterQuality = FilterQuality.High) },
                modifier = if (app == hidden.first()) Modifier.focusRequester(first) else Modifier,
            )
        }
    }
}
