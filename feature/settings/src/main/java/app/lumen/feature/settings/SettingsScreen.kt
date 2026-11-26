package app.lumen.feature.settings

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import app.lumen.core.designsystem.GradientFill
import app.lumen.core.designsystem.rememberWallpaperImage
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
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
import app.lumen.core.designsystem.drawGlassRim
import app.lumen.core.designsystem.rememberFrostedWallpaper
import app.lumen.core.settings.GradientPreset
import app.lumen.core.settings.LauncherSettings
import app.lumen.core.settings.ScreensaverType
import app.lumen.core.settings.Wallpaper
import app.lumen.core.system.DeviceStatus
import app.lumen.core.system.HomeOverride
import app.lumen.core.system.SystemActions

/** Where a page sits in the tree, what it's for, and the picture beside its list. */
private enum class SettingsPage(val title: String, val parent: SettingsPage?, @DrawableRes val glyph: Int, val description: String) {
    Main("Settings", null, R.drawable.ic_settings_glyph, "Make Atrium yours. Changes apply instantly."),
    Appearance("Appearance", Main, R.drawable.ic_settings_appearance, "How the home screen looks."),
    Wallpaper("Wallpaper", Appearance, R.drawable.ic_settings_appearance, "Pick a background for the home screen. Focus one to preview it."),
    ScreenSaver("Screen Saver", Main, R.drawable.ic_settings_screensaver, "Looping scenes when the home screen sits idle. While one runs, Up picks a category, Left and Right change scene, and Down names it."),
    Apps("Apps", Main, R.drawable.ic_settings_apps, "Which apps show on the home screen."),
    HiddenApps("Hidden Apps", Apps, R.drawable.ic_settings_apps, "Apps you've hidden from the home screen. Select one to bring it back."),
    Sound("Sound", Main, R.drawable.ic_settings_sound, "Interface sounds, and what's playing."),
    HomeButton("Remote and Home Button", Main, R.drawable.ic_settings_remote, "What the remote's Home button does."),
    System("System", Main, R.drawable.ic_settings_info, "This TV, and Atrium."),
}

/** What the column beside the list shows: tvOS keeps a large picture and the focused row's explanation there. */
private data class SettingsVisual(@DrawableRes val glyph: Int, val text: String)

/**
 * Laid out as tvOS Settings is: a short list of topics, each leading to a page of its own, with a
 * large picture and the focused row's explanation beside it. Pages push in from the side they lead
 * to and go back the way they came. Always opens at the top, whatever was open last.
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf(SettingsPage.Main) }
    // The row each page was opened from, which takes focus again on the way back.
    var openedFrom by remember { mutableStateOf(emptyMap<SettingsPage, String>()) }
    var preview by remember { mutableStateOf<Wallpaper?>(null) }
    var visual by remember { mutableStateOf(SettingsVisual(page.glyph, page.description)) }

    fun open(target: SettingsPage, fromRow: String) {
        openedFrom = openedFrom + (page to fromRow)
        page = target
        visual = SettingsVisual(target.glyph, target.description)
    }

    BackHandler(enabled = page != SettingsPage.Main) {
        if (page == SettingsPage.Wallpaper) preview = null
        page = page.parent ?: SettingsPage.Main
    }

    // Lifted on the Wallpaper page, so what's being previewed can be judged.
    val dim by animateFloatAsState(if (page == SettingsPage.Wallpaper) WALLPAPER_PAGE_DIM else SETTINGS_DIM, tween(400), label = "settingsDim")
    Box(Modifier.fillMaxSize().background(LumenColors.Background)) {
        WallpaperBackground(preview ?: settings.wallpaper, Modifier.fillMaxSize())
        // Dark and even, as tvOS Settings is: the wallpaper only tints it, and the list needs no glass to stay readable.
        Spacer(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black, alpha = dim) })

        // The page's name, small and centered at the top, like tvOS.
        AnimatedContent(
            targetState = page,
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = LumenDimens.SafeVertical),
            label = "settingsHeading",
        ) { p -> LumenText(p.title, LumenType.Section, color = LumenColors.TextSecondary) }

        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = LumenDimens.SafeHorizontal, vertical = LumenDimens.SafeVertical)
                .padding(top = 28.dp),
        ) {
            Column(
                Modifier.weight(0.44f).fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // On the Wallpaper page the picture is the home screen itself, wearing whichever wallpaper is in focus.
                PageGlyph(visual.glyph, (preview ?: settings.wallpaper).takeIf { page == SettingsPage.Wallpaper })
                Spacer(Modifier.height(22.dp))
                // Holds its height, so the picture doesn't shift as descriptions of different lengths come and go.
                AnimatedContent(
                    targetState = visual.text,
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
            Spacer(Modifier.width(24.dp))
            // A plain list, no sheet behind it, as in tvOS Settings. Swatches still frost their own glass.
            CompositionLocalProvider(LocalGlassBackdrop provides rememberFrostedWallpaper(preview ?: settings.wallpaper)) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        // In from the side it leads to, and back the way it came.
                        val direction = if (targetState.parent == initialState) 1 else -1
                        val slide = tween<IntOffset>(320, easing = FastOutSlowInEasing)
                        (fadeIn(tween(260, delayMillis = 60)) + slideInHorizontally(slide) { direction * it / 10 }) togetherWith
                            (fadeOut(tween(140)) + slideOutHorizontally(slide) { -direction * it / 14 })
                    },
                    modifier = Modifier
                        .weight(0.56f)
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
                    val rows = SettingsRows(focusRow = openedFrom[p], glyph = p.glyph, onVisual = { visual = it })
                    when (p) {
                        SettingsPage.Main -> MainPage(rows, ::open)
                        SettingsPage.Appearance -> AppearancePage(rows, settings, viewModel, ::open)
                        SettingsPage.Wallpaper -> WallpaperPage(settings, viewModel, onPreview = { preview = it })
                        SettingsPage.ScreenSaver -> ScreenSaverPage(settings, viewModel)
                        SettingsPage.Apps -> AppsPage(rows, settings, viewModel, ::open)
                        SettingsPage.HiddenApps -> HiddenAppsPage(viewModel)
                        SettingsPage.Sound -> SoundPage(rows, settings, viewModel)
                        SettingsPage.HomeButton -> HomeButtonPage(rows, settings, viewModel)
                        SettingsPage.System -> SystemPage(rows)
                    }
                }
            }
        }
    }
}

private const val HIDDEN_APP_ICON_PX = 64
private const val SETTINGS_DIM = 0.72f
private const val WALLPAPER_PAGE_DIM = 0.35f
private val HintHeight = 84.dp
private val ListFadeEdge = 20.dp
private val ListPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp)

/**
 * The large picture beside the list, drawn as one of tvOS's graphite Settings tiles. With a
 * [wallpaper], it's a small home screen in that wallpaper instead, so a choice can be judged as it
 * will look rather than through the page's dim.
 */
@Composable
private fun PageGlyph(@DrawableRes glyph: Int, wallpaper: Wallpaper? = null) {
    val shape = RoundedCornerShape(GlyphCorner)
    Box(
        Modifier
            .size(GlyphWidth, GlyphHeight)
            .clip(shape)
            .background(Brush.verticalGradient(0f to GlyphTop, 1f to GlyphBottom))
            .drawWithContent {
                drawContent()
                drawGlassRim(GlyphCorner, strength = 0.5f)
            },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = wallpaper ?: glyph, animationSpec = tween(200), label = "pageGlyph") { shown ->
            when (shown) {
                is Wallpaper -> MiniHomeScreen(shown)
                is Int -> Image(painterResource(shown), null, Modifier.size(GlyphIconSize))
            }
        }
    }
}

/** The home screen in miniature: [wallpaper] with the top row's glass and tiles sketched over it. */
@Composable
private fun MiniHomeScreen(wallpaper: Wallpaper) {
    Box(Modifier.fillMaxSize()) {
        when (wallpaper) {
            is Wallpaper.Gradient -> GradientFill(wallpaper.preset, Modifier.fillMaxSize())
            is Wallpaper.Image -> rememberWallpaperImage(wallpaper.path, maxWidth = MINI_HOME_DECODE_WIDTH)?.let {
                Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                val inset = size.width * 0.06f
                val dockHeight = size.height * 0.2f
                val dockTop = size.height * 0.68f
                drawRoundRect(
                    Color.White.copy(alpha = 0.16f),
                    topLeft = Offset(inset, dockTop),
                    size = Size(size.width - inset * 2, dockHeight),
                    cornerRadius = CornerRadius(dockHeight * 0.36f),
                )
                val gap = dockHeight * 0.16f
                val tileWidth = (size.width - inset * 2 - gap * (MINI_HOME_TILES + 1)) / MINI_HOME_TILES
                val tileHeight = dockHeight - gap * 2
                repeat(MINI_HOME_TILES) { i ->
                    drawRoundRect(
                        // The first stands a little brighter, as the tile in focus does.
                        Color.White.copy(alpha = if (i == 0) 0.92f else 0.5f),
                        topLeft = Offset(inset + gap + i * (tileWidth + gap), dockTop + gap),
                        size = Size(tileWidth, tileHeight),
                        cornerRadius = CornerRadius(tileHeight * 0.24f),
                    )
                }
            },
        )
    }
}

private const val MINI_HOME_TILES = 6
private const val MINI_HOME_DECODE_WIDTH = 500

private val GlyphWidth = 250.dp
private val GlyphHeight = 150.dp
private val GlyphCorner = 26.dp
private val GlyphIconSize = 76.dp
private val GlyphTop = Color(0xFF77777C)
private val GlyphBottom = Color(0xFF39393C)

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

/** What every row of a page shares: which one takes focus first, and what to show beside the list. */
private class SettingsRows(val focusRow: String?, val glyph: Int, val onVisual: (SettingsVisual) -> Unit)

/** A page of [SettingsRow]s, with focus starting on the first unless it's coming back to one. */
@Composable
private fun RowsPage(content: LazyListScope.(FocusRequester) -> Unit) {
    val initial = rememberInitialFocus()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = ListPadding,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        content(initial)
    }
}

/** One row of a page: the same height throughout, as tvOS's are, with its explanation beside the list. */
@Composable
private fun SettingsRow(
    rows: SettingsRows,
    initial: FocusRequester,
    title: String,
    hint: String,
    first: Boolean = false,
    value: String? = null,
    chevron: Boolean = false,
    @DrawableRes glyph: Int = rows.glyph,
    onClick: () -> Unit,
) {
    val takesFocus = if (rows.focusRow != null) title == rows.focusRow else first
    MenuRow(
        title = title,
        onClick = onClick,
        value = value,
        chevron = chevron,
        onFocused = { rows.onVisual(SettingsVisual(glyph, hint)) },
        modifier = if (takesFocus) Modifier.focusRequester(initial) else Modifier,
    )
}

/** Only topics, as on tvOS's first page. */
@Composable
private fun MainPage(rows: SettingsRows, onOpen: (SettingsPage, String) -> Unit) {
    val topics = listOf(
        SettingsPage.Appearance to "Wallpaper, the Top Shelf, the clock and your name.",
        SettingsPage.ScreenSaver to "Scenes or your own photos, and when they start.",
        SettingsPage.Apps to "Phone apps, and apps you've hidden.",
        SettingsPage.Sound to "Click sounds, and Now Playing in Control Center.",
        SettingsPage.HomeButton to "Make the remote's Home button open Atrium.",
        SettingsPage.System to "Android Settings, Google TV and About.",
    )
    RowsPage { initial ->
        items(topics, key = { it.first.name }) { (topic, hint) ->
            SettingsRow(
                rows,
                initial,
                title = topic.title,
                hint = hint,
                first = topic == topics.first().first,
                chevron = true,
                glyph = topic.glyph,
                onClick = { onOpen(topic, topic.title) },
            )
        }
    }
}

@Composable
private fun AppearancePage(rows: SettingsRows, settings: LauncherSettings, viewModel: SettingsViewModel, onOpen: (SettingsPage, String) -> Unit) {
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
    RowsPage { initial ->
        item {
            SettingsRow(
                rows,
                initial,
                title = SettingsPage.Wallpaper.title,
                hint = "The background of the home screen.",
                first = true,
                value = when (val w = settings.wallpaper) {
                    is Wallpaper.Gradient -> w.preset.displayName
                    is Wallpaper.Image -> "Photo"
                },
                chevron = true,
                onClick = { onOpen(SettingsPage.Wallpaper, SettingsPage.Wallpaper.title) },
            )
        }
        item {
            SettingsRow(
                rows,
                initial,
                title = "Top Shelf",
                hint = "Show the focused app's programs above the top row, lit in their colors.",
                value = onOff(settings.ambientBackdrop),
                onClick = { viewModel.setAmbientBackdrop(!settings.ambientBackdrop) },
            )
        }
        item {
            SettingsRow(
                rows,
                initial,
                title = "Clock",
                hint = "Show the time in the corner of the home screen. It also opens Control Center.",
                value = onOff(settings.showClock),
                onClick = { viewModel.setShowClock(!settings.showClock) },
            )
        }
        item {
            SettingsRow(
                rows,
                initial,
                title = "Your Name",
                hint = "Your initial becomes your picture next to the clock.",
                value = settings.profileName.ifBlank { "None" },
                chevron = true,
                onClick = { editingName = true },
            )
        }
    }
}

@Composable
private fun AppsPage(rows: SettingsRows, settings: LauncherSettings, viewModel: SettingsViewModel, onOpen: (SettingsPage, String) -> Unit) {
    val hiddenCount = settings.hidden.size
    RowsPage { initial ->
        item {
            SettingsRow(
                rows,
                initial,
                title = "Show Non-TV Apps",
                hint = "Include phone apps that aren't designed for a remote.",
                first = true,
                value = onOff(settings.showPhoneApps),
                onClick = { viewModel.setShowPhoneApps(!settings.showPhoneApps) },
            )
        }
        item {
            SettingsRow(
                rows,
                initial,
                title = SettingsPage.HiddenApps.title,
                hint = SettingsPage.HiddenApps.description,
                value = if (hiddenCount == 0) "None" else hiddenCount.toString(),
                chevron = true,
                onClick = { onOpen(SettingsPage.HiddenApps, SettingsPage.HiddenApps.title) },
            )
        }
    }
}

@Composable
private fun SoundPage(rows: SettingsRows, settings: LauncherSettings, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    var mediaAccess by remember { mutableStateOf(DeviceStatus.hasMediaAccess(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { mediaAccess = DeviceStatus.hasMediaAccess(context) }
    RowsPage { initial ->
        item {
            SettingsRow(
                rows,
                initial,
                title = "Click Sounds",
                hint = "A soft click as you move around and select.",
                first = true,
                value = onOff(settings.navigationSounds),
                onClick = { viewModel.setNavigationSounds(!settings.navigationSounds) },
            )
        }
        item {
            SettingsRow(
                rows,
                initial,
                title = "Now Playing",
                hint = if (mediaAccess) {
                    "Shows what's playing in Control Center, with its controls, and an icon by the clock."
                } else {
                    "Show what's playing in Control Center. Turn on \"Atrium Now Playing\" in Notification Access; Android asks for that to share what's playing, and Atrium doesn't read your notifications."
                },
                value = if (mediaAccess) "On" else "Set Up",
                chevron = true,
                onClick = { SystemActions.openNotificationAccess(context) },
            )
        }
    }
}

@Composable
private fun HomeButtonPage(rows: SettingsRows, settings: LauncherSettings, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    var serviceEnabled by remember { mutableStateOf(HomeOverride.isServiceEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { serviceEnabled = HomeOverride.isServiceEnabled(context) }
    RowsPage { initial ->
        item {
            SettingsRow(
                rows,
                initial,
                title = "Open Atrium with Home",
                hint = "Replace the Google TV home screen when you press Home.",
                first = true,
                value = onOff(settings.homeOverride),
                onClick = { viewModel.setHomeOverride(!settings.homeOverride) },
            )
        }
        item {
            SettingsRow(
                rows,
                initial,
                title = "Home Button Service",
                hint = if (serviceEnabled) {
                    "Enabled in Accessibility."
                } else {
                    "Needed for the Home button, holding it for Control Center, and Sleep. Turn on \"Atrium Home Button\" in Accessibility."
                },
                value = if (serviceEnabled) "On" else "Set Up",
                chevron = true,
                onClick = { SystemActions.openAccessibilitySettings(context) },
            )
        }
    }
}

@Composable
private fun SystemPage(rows: SettingsRows) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    RowsPage { initial ->
        item {
            SettingsRow(
                rows,
                initial,
                title = "Android Settings",
                hint = "Network, accounts, display and everything else on this TV.",
                first = true,
                chevron = true,
                onClick = { SystemActions.openAndroidSettings(context) },
            )
        }
        item {
            SettingsRow(
                rows,
                initial,
                title = "Open Google TV Home",
                hint = "Visit the original home screen. Atrium returns after you open another app.",
                chevron = true,
                onClick = { SystemActions.openStockHome(context) },
            )
        }
        item {
            SettingsRow(
                rows,
                initial,
                title = "About Atrium",
                hint = "A home screen for your TV, modeled on Apple TV's. Select to see its permissions and storage in Android.",
                value = "Version $version",
                chevron = true,
                onClick = { SystemActions.openAppInfo(context, context.packageName) },
            )
        }
    }
}

/** Lists of choices with a checkmark on the current one, as tvOS's Settings pages have. */
@Composable
private fun ScreenSaverPage(settings: LauncherSettings, viewModel: SettingsViewModel) {
    val first = rememberInitialFocus()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = ListPadding,
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
        item {
            MenuRow(
                "Show During Music and Podcasts",
                { viewModel.setScreensaverDuringMusic(!settings.screensaverDuringMusic) },
                value = onOff(settings.screensaverDuringMusic),
            )
        }
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
        contentPadding = ListPadding,
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
    val context = LocalContext.current
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
        contentPadding = ListPadding,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(hidden, key = { it.packageName }) { app ->
            // Read here rather than kept for every installed app, as this short list is the only place that shows it.
            val icon = remember(app.packageName) {
                runCatching { context.packageManager.getActivityIcon(app.component).toBitmap(HIDDEN_APP_ICON_PX, HIDDEN_APP_ICON_PX).asImageBitmap() }.getOrNull()
            }
            MenuRow(
                title = app.label,
                value = "Show",
                onClick = { viewModel.unhide(app) },
                leading = { icon?.let { Image(it, null, Modifier.size(26.dp), filterQuality = FilterQuality.High) } },
                modifier = if (app == hidden.first()) Modifier.focusRequester(first) else Modifier,
            )
        }
    }
}
