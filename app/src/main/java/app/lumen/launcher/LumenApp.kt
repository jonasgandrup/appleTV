package app.lumen.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lumen.core.designsystem.LocalUiSounds
import app.lumen.core.designsystem.LumenColors
import app.lumen.core.settings.LauncherSettings
import app.lumen.feature.home.HomeScreen
import app.lumen.feature.home.HomeViewModel
import app.lumen.feature.settings.SettingsScreen
import app.lumen.feature.settings.SettingsViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

private enum class Destination { Home, Settings }

@Composable
fun LumenApp(container: AppContainer, homePresses: Flow<Boolean>, controlCenterRequests: MutableSharedFlow<Unit>) {
    var destination by rememberSaveable { mutableStateOf(Destination.Home) }
    // Re-broadcast Home presses to the home screen only once it's showing. Coming back from an app
    // isn't one: focus stays on the tile the app was opened from, as on tvOS.
    val homeScreenPresses = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }

    LaunchedEffect(homePresses) {
        homePresses.collect { fromApp ->
            when {
                destination != Destination.Home -> destination = Destination.Home
                !fromApp -> homeScreenPresses.tryEmit(Unit)
            }
        }
    }

    // Control Center lives on the home screen, so a request for it from Settings goes there first.
    LaunchedEffect(controlCenterRequests) {
        controlCenterRequests.collect { destination = Destination.Home }
    }

    val settings by container.settings.settings.collectAsStateWithLifecycle(LauncherSettings())
    // Keeps the home screen's scroll position while Settings is open, so coming back lands where it left off.
    val saved = rememberSaveableStateHolder()
    // Where the Settings tile was when it was selected, as a place on the screen, so Settings can grow
    // out of it and shrink back into it, the way apps do from their tiles.
    var screen by remember { mutableStateOf(IntSize.Zero) }
    var settingsOrigin by remember { mutableStateOf(TransformOrigin.Center) }

    // A launcher has nowhere to go back to: Back on home does nothing.
    BackHandler(enabled = true) {
        if (destination != Destination.Home) destination = Destination.Home
    }

    CompositionLocalProvider(LocalUiSounds provides container.sounds.takeIf { settings.navigationSounds }) {
        AnimatedContent(
            targetState = destination,
            transitionSpec = {
                val grow = tween<Float>(SETTINGS_ZOOM_MS, easing = FastOutSlowInEasing)
                if (targetState == Destination.Settings) {
                    // Home stays lit behind Settings until it's covered, so Settings never grows over black.
                    (fadeIn(tween(SETTINGS_FADE_MS)) + scaleIn(grow, initialScale = SETTINGS_TILE_SCALE, transformOrigin = settingsOrigin)) togetherWith
                        fadeOut(snap(delayMillis = SETTINGS_ZOOM_MS))
                } else {
                    // Settings stays in front as it leaves, so it's seen going back into its tile.
                    (fadeIn(tween(SETTINGS_FADE_MS)) togetherWith
                        (fadeOut(tween(SETTINGS_FADE_MS, delayMillis = SETTINGS_ZOOM_MS - SETTINGS_FADE_MS)) +
                            scaleOut(grow, targetScale = SETTINGS_TILE_SCALE, transformOrigin = settingsOrigin)))
                        .apply { targetContentZIndex = -1f }
                }
            },
            modifier = Modifier.fillMaxSize().onSizeChanged { screen = it }.background(LumenColors.Background),
            label = "destination",
        ) { target ->
            saved.SaveableStateProvider(target.name) {
                when (target) {
                    Destination.Home -> HomeScreen(
                        viewModel = viewModel(factory = HomeViewModel.factory(container.apps, container.launcher, container.settings, container.wallpapers, container.topShelf)),
                        homePresses = homeScreenPresses,
                        controlCenterRequests = controlCenterRequests,
                        onOpenSettings = { tile ->
                            settingsOrigin = if (tile.isEmpty || screen == IntSize.Zero) {
                                TransformOrigin.Center
                            } else {
                                TransformOrigin(
                                    (tile.center.x / screen.width).coerceIn(0f, 1f),
                                    (tile.center.y / screen.height).coerceIn(0f, 1f),
                                )
                            }
                            destination = Destination.Settings
                        },
                    )
                    Destination.Settings -> SettingsScreen(
                        viewModel = viewModel(factory = SettingsViewModel.factory(container.settings, container.apps, container.wallpapers)),
                    )
                }
            }
        }
    }
}

/** About a tile's share of the screen, so Settings starts at the size of the tile it grows from. */
private const val SETTINGS_TILE_SCALE = 0.16f
private const val SETTINGS_ZOOM_MS = 380
private const val SETTINGS_FADE_MS = 220
