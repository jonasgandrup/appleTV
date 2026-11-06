package app.lumen.feature.home

import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import app.lumen.core.apps.AppInfo
import app.lumen.core.apps.ShelfProgram
import app.lumen.core.apps.UpNextItem
import app.lumen.core.designsystem.LiveFrost
import app.lumen.core.designsystem.UiSounds
import app.lumen.core.settings.AppFolder
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Everything the home screen remembers about what the user is doing on it: where focus is, what's
 * open over it, and how the remote moves things. The screen's composables read and set it, and the
 * rules for Back, Up and moving an app live here, apart from how things are drawn.
 */
@Stable
internal class HomeScreenState(browsingBelow: Boolean, openFolderId: String?, val listState: LazyListState) {
    /** Held as State and read only by the stage, so moving along the dock recomposes the stage rather than the whole screen. */
    val focusedApp = mutableStateOf<AppInfo?>(null)
    /** Held as State and read only by the stage, like [focusedApp]. */
    val focusedRecommendation = mutableStateOf<UpNextItem?>(null)
    /** The program whose picture the full-screen Top Shelf is showing. */
    val shownProgram = mutableStateOf<ShelfProgram?>(null)

    /** Focus is below the top row, so the stage gives way to the rows under it. */
    var browsingBelow by mutableStateOf(browsingBelow)
    var openFolderId by mutableStateOf(openFolderId)
    var optionsFor by mutableStateOf<Pair<AppInfo, Rect>?>(null)
    var folderOptionsFor by mutableStateOf<Pair<HomeItem.Folder, Rect>?>(null)
    var programOptionsFor by mutableStateOf<ProgramOptions?>(null)
    var renaming by mutableStateOf<AppFolder?>(null)
    var controlCenterOpen by mutableStateOf(false)

    /** Which of the app's featured programs fills the screen; null when the Top Shelf isn't full screen. */
    var fullScreen by mutableStateOf<Int?>(null)
    val isFullScreen: Boolean get() = fullScreen != null
    /** What the stage has cycled to while the dock rested, which stays up when the dock comes back, so nothing jumps. */
    var restIndex by mutableStateOf<Int?>(null)
    /** Left alone on sharp artwork, the dock has slid away and the picture has the screen. */
    var dockResting by mutableStateOf(false)

    var dockHasFocus by mutableStateOf(false)
    var continueHasFocus by mutableStateOf(false)
    /** Where focus goes after a program leaves a row, since it was on that program's tile. */
    var refocus by mutableStateOf<FocusRequester?>(null)
    /** A tile that should take focus as soon as it exists: where an app just moved to, say. */
    var pendingFocus by mutableStateOf<String?>(null)
    /** Focus was asked for while the dock was scrolled away, so it goes there once the dock is back. */
    var dockFocusOwed by mutableStateOf<DockFocus?>(null)
    /** Back's press began here, so its release is ours to act on. Read only when keys arrive. */
    private var backPressed = false

    val firstFocus = FocusRequester()
    val settingsFocus = FocusRequester()
    val playFocus = FocusRequester()
    val continueFirstTile = FocusRequester()
    val recommendationsFirstTile = FocusRequester()
    private val tileFocus = mutableMapOf<String, FocusRequester>()

    val editor = HomeEditor()
    val liveFrost = LiveFrost()
    val screensaverRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** One requester per home screen item, so focus can follow one that moved in Edit Home Screen. */
    fun focusFor(key: String): FocusRequester = tileFocus.getOrPut(key) { FocusRequester() }

    /** The requester for [key] if its tile has ever been shown. */
    fun knownFocus(key: String): FocusRequester? = tileFocus[key]

    /** Something is open over the home screen. */
    val overlayOpen: Boolean
        get() = controlCenterOpen || openFolderId != null || optionsFor != null || programOptionsFor != null ||
            folderOptionsFor != null || renaming != null

    /** Home pressed while already home: everything closes, as on tvOS. */
    fun closeEverything() {
        optionsFor = null
        folderOptionsFor = null
        programOptionsFor = null
        renaming = null
        openFolderId = null
        controlCenterOpen = false
        fullScreen = null
        browsingBelow = false
    }

    /**
     * Home pressed while already home: everything closes and focus goes to the first app, as on tvOS.
     * From below, it waits for the scroll back up that leaving the rows below starts, as a second scroll
     * would cancel that one.
     */
    suspend fun homePressed(drop: () -> Unit) {
        if (editor.editing) drop()
        val wasBelow = browsingBelow
        closeEverything()
        if (wasBelow) dockFocusOwed = DockFocus.FirstApp else focusFirstApp()
    }

    /**
     * The dock hands focus arriving from outside it to the tile last focused there, which is right for Up
     * and Back but not for Home, so a second request, once focus is inside, moves it on to the first app.
     */
    suspend fun focusFirstApp() {
        repeat(2) {
            runCatching { firstFocus.requestFocus() }
            withFrameNanos { }
        }
    }

    /** Focus back to the top row, on the app last focused there if it's still around. */
    fun focusDock() {
        // The app last focused there can be gone by now, which leaves its requester unattached.
        val returned = focusedApp.value?.let { app -> runCatching { focusFor(app.packageName).requestFocus() }.isSuccess } ?: false
        if (!returned) runCatching { firstFocus.requestFocus() }
    }

    /**
     * Back climbs to the top row from anywhere, to the app last focused there, and from its first app
     * starts the screen saver, as on tvOS. While moving an app, it drops the app, keeping the new arrangement.
     */
    private fun back(onFirstApp: Boolean, drop: () -> Unit) {
        when {
            editor.editing -> drop()
            !dockHasFocus -> {
                // Scrolled far enough away, the dock doesn't exist to take focus until it's scrolled back.
                val dockExists = listState.layoutInfo.visibleItemsInfo.any { it.key == SHELF_KEY }
                if (dockExists) focusDock() else dockFocusOwed = DockFocus.LastApp
                browsingBelow = false
            }
            onFirstApp -> screensaverRequests.tryEmit(Unit)
        }
    }

    /**
     * The home screen's own answer to a key, ahead of Compose's focus handling; true when it's taken.
     * [canOpenFullScreen] says the focused app features something for Up to open.
     */
    fun onKey(event: KeyEvent, canOpenFullScreen: Boolean, onFirstApp: Boolean, sounds: UiSounds?, drop: () -> Unit): Boolean {
        // Any button only brings the dock back, as nothing it would act on is showing.
        // What follows ignores a key-up whose press it didn't see, so that isn't taken here.
        if (dockResting && event.type == KeyEventType.KeyDown) {
            dockResting = false
            return true
        }
        // Taken here, ahead of Compose, which would otherwise treat Back as a focus move of its own and
        // send focus somewhere unexpected. Acts on a press that began here, not on the release of one
        // that closed an app or a menu.
        if (event.key == Key.Back) {
            if (event.type == KeyEventType.KeyDown) backPressed = true
            if (event.type == KeyEventType.KeyUp && backPressed) {
                backPressed = false
                back(onFirstApp, drop)
            }
            return true
        }
        // Up from the dock opens what's on the stage to full screen, as on tvOS. Apps with nothing to open let focus move on as usual.
        if (event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown && !editor.editing && !isFullScreen &&
            canOpenFullScreen && dockHasFocus
        ) {
            fullScreen = restIndex ?: 0
            restIndex = null
            sounds?.move()
            return true
        }
        // In Edit Home Screen the D-pad moves the lifted app rather than focus, and Select drops it.
        if (!editor.editing) return false
        val direction = when (event.key) {
            Key.DirectionLeft -> HomeEditor.Direction.Left
            Key.DirectionRight -> HomeEditor.Direction.Right
            Key.DirectionUp -> HomeEditor.Direction.Up
            Key.DirectionDown -> HomeEditor.Direction.Down
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                if (event.type == KeyEventType.KeyUp) drop()
                return true
            }
            else -> return false
        }
        if (event.type == KeyEventType.KeyDown && editor.move(direction)) sounds?.move()
        return true
    }
}

/** Kept across a trip to Settings and back: whether the user was below the top row, and which folder was open. */
@Composable
internal fun rememberHomeScreenState(): HomeScreenState {
    val listState = rememberLazyListState()
    return rememberSaveable(
        saver = listSaver(
            save = { listOf(it.browsingBelow, it.openFolderId) },
            restore = { HomeScreenState(it[0] as Boolean, it[1] as String?, listState) },
        ),
    ) { HomeScreenState(browsingBelow = false, openFolderId = null, listState = listState) }
}

/** Where focus goes in the dock once it's scrolled back into view. */
internal enum class DockFocus { FirstApp, LastApp }

/** A program held down in a row, for [ProgramOptionsDialog]. */
internal class ProgramOptions(
    val item: UpNextItem,
    val bounds: Rect,
    val removable: Boolean,
)

internal const val SHELF_KEY = "shelf"

/** How the rows move between the top row and what's below it. Everything that moves then shares it, so it moves as one. */
internal val BrowseSpec = tween<Float>(500, easing = FastOutSlowInEasing)
