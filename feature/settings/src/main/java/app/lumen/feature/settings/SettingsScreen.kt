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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.core.designsystem.LocalGlassBackdrop
import app.lumen.core.designsystem.LumenColors
import app.lumen.core.designsystem.LumenDimens
import app.lumen.core.designsystem.LumenText
import app.lumen.core.designsystem.LumenType
import app.lumen.core.designsystem.MenuRow
import app.lumen.core.designsystem.SectionTitle
import app.lumen.core.designsystem.TextEntryDialog
import app.lumen.core.designsystem.WallpaperBackground
import app.lumen.core.designsystem.rememberFrostedWallpaper
import app.lumen.core.settings.GradientPreset
import app.lumen.core.settings.LauncherSettings
import app.lumen.core.settings.ScreensaverType
import app.lumen.core.settings.Wallpaper
import app.lumen.core.system.HomeOverride
import app.lumen.core.system.SystemActions

private enum class SettingsPage(val title: String, val description: String) {
    Main("Settings", "Make Atrium yours. Changes apply instantly."),
    Wallpaper("Wallpaper", "Pick a background for the home screen. Focus a wallpaper to preview it."),
    ScreenSaver("Screen Saver", "Looping nature scenes when the home screen sits idle. While one runs: Up picks a category, Left and Right change scene, Down names it. Back on the first app starts one."),
    HiddenApps("Hidden Apps", "Apps you've hidden from the home screen. Select one to bring it back."),
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(SettingsPage.Main) }
    // The row a sub-page was opened from, which takes focus again on the way back.
    var openedFrom by rememberSaveable { mutableStateOf(MAIN_FIRST_ROW) }
    var preview by remember { mutableStateOf<Wallpaper?>(null) }
    // What the focused row does, shown under the icon as tvOS does rather than in the row itself.
    var hint by remember { mutableStateOf(page.description) }

    fun open(target: SettingsPage, fromRow: String) {
        openedFrom = fromRow
        page = target
        hint = target.description
    }

    BackHandler(enabled = page != SettingsPage.Main) {
        page = SettingsPage.Main
        preview = null
    }

    Box(Modifier.fillMaxSize().background(LumenColors.Background)) {
        WallpaperBackground(preview ?: settings.wallpaper, Modifier.fillMaxSize())
        // Dark and even, as tvOS Settings is: the wallpaper only tints it, and the list needs no glass to stay readable.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)))

        // The page's name, small and centered at the top, like tvOS.
        AnimatedContent(
            targetState = page,
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = LumenDimens.SafeVertical),
            label = "settingsHeading",
        ) { p -> LumenText(p.title, LumenType.Headline, color = LumenColors.TextSecondary) }

        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = LumenDimens.SafeHorizontal, vertical = LumenDimens.SafeVertical)
                .padding(top = 36.dp),
        ) {
            Column(
                Modifier.weight(0.4f).fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(painterResource(R.drawable.ic_settings_glyph), null, Modifier.size(170.dp))
                Spacer(Modifier.height(24.dp))
                // Holds its height, so the icon doesn't shift as descriptions of different lengths come and go.
                AnimatedContent(
                    targetState = hint,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(100)) },
                    modifier = Modifier.height(HintHeight),
                    contentAlignment = Alignment.TopCenter,
                    label = "settingsHint",
                ) { text ->
                    LumenText(
                        text,
                        LumenType.Caption,
                        color = LumenColors.TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 280.dp),
                    )
                }
            }
            Spacer(Modifier.width(40.dp))
            // A plain list, no sheet behind it, as in tvOS Settings. Swatches still frost their own glass.
            CompositionLocalProvider(LocalGlassBackdrop provides rememberFrostedWallpaper(preview ?: settings.wallpaper)) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        (fadeIn(tween(300)) + slideInHorizontally(tween(300)) { it / 12 }) togetherWith fadeOut(tween(150))
                    },
                    modifier = Modifier
                        .weight(0.6f)
                        .fillMaxHeight()
                        // Rows fade out at the edges as they scroll, rather than being cut off under the heading.
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .drawWithContent {
                            drawContent()
                            val edge = ListFadeEdge.toPx() / size.height
                            drawRect(
                                Brush.verticalGradient(0f to Color.Transparent, edge to Color.Black, 1f - edge to Color.Black, 1f to Color.Transparent),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                    label = "settingsPage",
                ) { p ->
                    when (p) {
                        SettingsPage.Main -> MainPage(settings, viewModel, focusRow = openedFrom, onOpen = ::open, onHint = { hint = it })
                        SettingsPage.Wallpaper -> WallpaperPage(settings, viewModel, onPreview = { preview = it })
                        SettingsPage.ScreenSaver -> ScreenSaverPage(settings, viewModel)
                        SettingsPage.HiddenApps -> HiddenAppsPage(viewModel)
                    }
                }
            }
        }
    }
}

private const val MAIN_FIRST_ROW = "Wallpaper"
private val HintHeight = 96.dp
private val ListFadeEdge = 20.dp

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

/** The choices on the Screen Saver page, in minutes; 0 is Never. */
private val ScreensaverDelays = listOf(2, 5, 10, 15, 0)

private fun screensaverLabel(minutes: Int) = if (minutes == 0) "Never" else "$minutes Minutes"

@Composable
private fun MainPage(
    settings: LauncherSettings,
    viewModel: SettingsViewModel,
    focusRow: String,
    onOpen: (SettingsPage, String) -> Unit,
    onHint: (String) -> Unit,
) {
    val context = LocalContext.current
    val initial = rememberInitialFocus()
    var serviceEnabled by remember { mutableStateOf(HomeOverride.isServiceEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { serviceEnabled = HomeOverride.isServiceEnabled(context) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    val hiddenCount = settings.hidden.size
    var editingName by remember { mutableStateOf(false) }
    if (editingName) {
        TextEntryDialog(
            title = "Your Name",
            initial = settings.profileName,
            onDone = viewModel::setProfileName,
            onDismiss = { editingName = false },
            allowBlank = true,
        )
    }

    // One row of the list: the same height throughout, as tvOS's are, with its explanation under the icon.
    @Composable
    fun SettingsRow(title: String, hint: String, value: String? = null, chevron: Boolean = false, onClick: () -> Unit) {
        MenuRow(
            title = title,
            onClick = onClick,
            value = value,
            chevron = chevron,
            onFocused = { onHint(hint) },
            modifier = if (title == focusRow) Modifier.focusRequester(initial) else Modifier,
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item { SectionTitle("Appearance") }
        item {
            SettingsRow(
                title = MAIN_FIRST_ROW,
                hint = SettingsPage.Wallpaper.description,
                value = when (val w = settings.wallpaper) {
                    is Wallpaper.Gradient -> w.preset.displayName
                    is Wallpaper.Image -> "Photo"
                },
                chevron = true,
                onClick = { onOpen(SettingsPage.Wallpaper, MAIN_FIRST_ROW) },
            )
        }
        item {
            SettingsRow(
                title = "Top Shelf Artwork",
                hint = "Show artwork from the focused app above the top row, or its colors when it has none.",
                value = onOff(settings.ambientBackdrop),
                onClick = { viewModel.setAmbientBackdrop(!settings.ambientBackdrop) },
            )
        }
        item {
            SettingsRow(
                title = "Clock",
                hint = "Show the time in the corner of the home screen. Press Up to reach it and open Control Center.",
                value = onOff(settings.showClock),
                onClick = { viewModel.setShowClock(!settings.showClock) },
            )
        }
        item {
            SettingsRow(
                title = "Screen Saver",
                hint = SettingsPage.ScreenSaver.description,
                value = settings.screensaverType.displayName,
                chevron = true,
                onClick = { onOpen(SettingsPage.ScreenSaver, "Screen Saver") },
            )
        }
        item {
            SettingsRow(
                title = "Click Sounds",
                hint = "A soft click as you move around and select.",
                value = onOff(settings.navigationSounds),
                onClick = { viewModel.setNavigationSounds(!settings.navigationSounds) },
            )
        }

        item {
            SettingsRow(
                title = "Your Name",
                hint = "Your initial is your picture next to the clock, unless you've added a photo as profile.jpg over ADB. Google doesn't share your account's photo with other apps.",
                value = settings.profileName.ifBlank { "None" },
                chevron = true,
                onClick = { editingName = true },
            )
        }

        item { SectionTitle("Apps") }
        item {
            SettingsRow(
                title = "Show Non-TV Apps",
                hint = "Include phone apps that aren't designed for a remote.",
                value = onOff(settings.showPhoneApps),
                onClick = { viewModel.setShowPhoneApps(!settings.showPhoneApps) },
            )
        }
        item {
            SettingsRow(
                title = "Hidden Apps",
                hint = SettingsPage.HiddenApps.description,
                value = if (hiddenCount == 0) "None" else hiddenCount.toString(),
                chevron = true,
                onClick = { onOpen(SettingsPage.HiddenApps, "Hidden Apps") },
            )
        }

        item { SectionTitle("Home Button") }
        item {
            SettingsRow(
                title = "Open Atrium with Home",
                hint = "Replace the Google TV home screen when you press Home.",
                value = onOff(settings.homeOverride),
                onClick = { viewModel.setHomeOverride(!settings.homeOverride) },
            )
        }
        item {
            SettingsRow(
                title = "Home Button Service",
                hint = if (serviceEnabled) {
                    "Enabled in Accessibility."
                } else {
                    "Required on Google TV for the Home button, holding it for Control Center, and Sleep. Turn on \"Atrium Home Button\" in Accessibility."
                },
                value = if (serviceEnabled) "On" else "Set Up",
                chevron = true,
                onClick = { SystemActions.openAccessibilitySettings(context) },
            )
        }
        item {
            SettingsRow(
                title = "Open Google TV Home",
                hint = "Visit the original home screen. Atrium returns after you open another app.",
                chevron = true,
                onClick = { SystemActions.openStockHome(context) },
            )
        }

        item { SectionTitle("System") }
        item {
            SettingsRow(
                title = "Android Settings",
                hint = "Network, accounts, display and everything else on this TV.",
                chevron = true,
                onClick = { SystemActions.openAndroidSettings(context) },
            )
        }
        item { SettingsRow(title = "About Atrium", hint = "A minimalist home screen for your TV.", value = "Version $version", onClick = {}) }
    }
}

/** Lists of choices with a checkmark on the current one, as tvOS's Settings pages have. */
@Composable
private fun ScreenSaverPage(settings: LauncherSettings, viewModel: SettingsViewModel) {
    val first = rememberInitialFocus()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item { SectionTitle("Show") }
        items(ScreensaverType.entries) { type ->
            val current = type == settings.screensaverType
            MenuRow(
                title = type.displayName,
                onClick = { viewModel.setScreensaverType(type) },
                checked = current,
                modifier = if (current) Modifier.focusRequester(first) else Modifier,
            )
        }
        item { MenuRow("Show Clock", { viewModel.setScreensaverClock(!settings.screensaverClock) }, value = onOff(settings.screensaverClock)) }
        item { SectionTitle("Start After") }
        items(ScreensaverDelays) { minutes ->
            MenuRow(
                title = screensaverLabel(minutes),
                onClick = { viewModel.setScreensaverMinutes(minutes) },
                checked = minutes == settings.screensaverMinutes,
            )
        }
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
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { SectionTitle("Gradients", inset = 0.dp) }
        item {
            SwatchGrid(presets, offset = 0, settings.wallpaper, first, firstFocusIndex, viewModel::setWallpaper, onPreview)
        }
        item { SectionTitle("Your Photos", inset = 0.dp) }
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
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
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
