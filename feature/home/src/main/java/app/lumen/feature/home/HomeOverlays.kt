package app.lumen.feature.home

import androidx.compose.runtime.getValue
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.core.designsystem.Clock
import app.lumen.core.designsystem.TextEntryDialog
import app.lumen.core.system.DeviceStatus
import app.lumen.core.system.HomeOverride
import app.lumen.core.system.NetworkStatus
import app.lumen.core.system.NowPlaying
import app.lumen.core.system.SleepTimer
import app.lumen.core.system.SystemActions

/** What the device is doing right now, for the clock's status icons and Control Center. */
internal class DeviceSnapshot(
    val device: DeviceStatus,
    val network: NetworkStatus?,
    val headphones: String?,
    val nowPlaying: NowPlaying?,
    val sleepEndsAt: Long?,
)

@Composable
internal fun rememberDeviceSnapshot(context: Context): DeviceSnapshot {
    val device = remember { DeviceStatus(context) }
    val network by device.network.collectAsStateWithLifecycle(null)
    val headphones by device.headphones.collectAsStateWithLifecycle(null)
    val nowPlaying by device.nowPlaying.collectAsStateWithLifecycle(null)
    val sleepEndsAt by SleepTimer.endsAt.collectAsStateWithLifecycle()
    return DeviceSnapshot(device, network, headphones, nowPlaying, sleepEndsAt)
}

/** The clock in the top-right corner, with tvOS's small status icons before the time. Opens Control Center. */
@Composable
internal fun HomeClock(device: DeviceSnapshot, profileName: String, profilePicture: ImageBitmap?, onClick: () -> Unit) {
    Clock(
        onClick = onClick,
        profileName = profileName,
        profilePicture = profilePicture,
        status = { color ->
            if (device.network?.hasInternet == false) StatusIcon("No internet", R.drawable.ic_status_no_internet, color)
            if (device.sleepEndsAt != null) StatusIcon("Sleep Timer", R.drawable.ic_cc_timer, color)
            if (device.nowPlaying?.isPlaying == true) StatusIcon("Now Playing", R.drawable.ic_status_music, color)
            if (device.headphones != null) StatusIcon("Headphones", R.drawable.ic_status_headphones, color)
        },
    )
}

/** One of tvOS's small status icons beside the clock. */
@Composable
private fun StatusIcon(description: String, @DrawableRes icon: Int, color: Color) {
    Image(painterResource(icon), description, Modifier.size(16.dp), colorFilter = ColorFilter.tint(color))
}

/** Whatever is open over the home screen: Control Center, a folder, a held tile's menu, or naming a folder. */
@Composable
internal fun HomeOverlays(
    ui: HomeScreenState,
    state: HomeUiState,
    viewModel: HomeViewModel,
    device: DeviceSnapshot,
    profilePicture: ImageBitmap?,
    openFolder: HomeItem.Folder?,
    folderActions: TileActions,
    shelfDock: List<HomeItem>,
) {
    val context = LocalContext.current

    if (ui.controlCenterOpen) {
        ControlCenter(
            profileName = state.settings.profileName,
            profilePicture = profilePicture,
            sleepReady = HomeOverride.isServiceEnabled(context),
            status = ControlCenterStatus(
                network = device.network,
                headphones = device.headphones,
                nowPlaying = device.nowPlaying,
                nowPlayingApp = state.allApps.find { it.packageName == device.nowPlaying?.packageName }?.label,
                sleepEndsAt = device.sleepEndsAt,
                clickSounds = state.settings.navigationSounds,
                device = device.device,
            ),
            onClickSounds = viewModel::setClickSounds,
            actions = ControlCenterActions(
                // Sleep goes through the Home Button service; without it, this leads to where it's turned on.
                onSleep = { if (!SystemActions.sleep()) SystemActions.openAccessibilitySettings(context) },
                onScreensaver = { ui.screensaverRequests.tryEmit(Unit) },
                onNetwork = { SystemActions.openNetworkSettings(context) },
                onSound = { SystemActions.openSoundSettings(context) },
                onProfile = { SystemActions.openAccounts(context) },
                onSearch = { SystemActions.openSearch(context) },
                onAccessibility = { SystemActions.openAccessibilitySettings(context) },
            ),
            onDismiss = { ui.controlCenterOpen = false },
        )
    }

    openFolder?.let { folder ->
        FolderView(
            folder = folder,
            actions = folderActions,
            onRename = { ui.renaming = folder.folder },
            onDismiss = {
                ui.openFolderId = null
                ui.pendingFocus = folder.key
            },
        )
    }

    ui.optionsFor?.let { (app, bounds) ->
        val ownFolder = state.folderOf(app)
        val inTopRow = state.favorites.any { it.packageName == app.packageName }
        AppOptionsDialog(
            anchor = bounds,
            onDismiss = { ui.optionsFor = null },
            options = AppOptions(
                onEditHomeScreen = if (ownFolder != null) null else {
                    { ui.editor.start(HomeItem.App(app), shelfDock, state.grid) }
                },
                moveTargets = buildList {
                    if (!inTopRow) add(MoveTarget.TopRow)
                    if (inTopRow || ownFolder != null) add(MoveTarget.HomeScreen)
                    add(MoveTarget.NewFolder)
                    state.grid.filterIsInstance<HomeItem.Folder>()
                        .filter { it !== ownFolder }
                        .forEach { add(MoveTarget.Folder(it.folder.id, it.folder.name)) }
                },
                // A new folder opens right away, as on tvOS, so it can be named.
                onMove = { target ->
                    val created = viewModel.moveApp(app, target)
                    if (created != null) ui.openFolderId = created
                    ui.pendingFocus = when (target) {
                        MoveTarget.TopRow, MoveTarget.HomeScreen -> app.packageName
                        MoveTarget.NewFolder -> created?.let(::folderKey)
                        is MoveTarget.Folder -> folderKey(target.id)
                    }
                },
                onHide = {
                    viewModel.hide(app)
                    ui.pendingFocus = state.favorites.firstOrNull { it.packageName != app.packageName }?.packageName
                },
                onDelete = if (SystemActions.canUninstall(context, app.packageName)) {
                    { SystemActions.requestUninstall(context, app.packageName) }
                } else {
                    null
                },
                onAppInfo = { SystemActions.openAppInfo(context, app.packageName) },
            ),
        )
    }

    ui.programOptionsFor?.let { options ->
        val app = state.allApps.find { it.packageName == options.item.packageName }
        ProgramOptionsDialog(
            anchor = options.bounds,
            onRemove = if (options.removable) {
                {
                    viewModel.removeProgram(options.item)
                    ui.refocus = ui.continueFirstTile
                }
            } else {
                null
            },
            appLabel = app?.label,
            onOpenApp = { app?.let { viewModel.launch(it) } },
            onDismiss = { ui.programOptionsFor = null },
        )
    }

    ui.folderOptionsFor?.let { (folder, bounds) ->
        FolderOptionsDialog(
            anchor = bounds,
            onEditHomeScreen = { ui.editor.start(folder, shelfDock, state.grid) },
            onRename = { ui.renaming = folder.folder },
            onDismiss = { ui.folderOptionsFor = null },
        )
    }

    ui.renaming?.let { folder ->
        TextEntryDialog(
            title = "Folder Name",
            initial = folder.name,
            onDone = { viewModel.renameFolder(folder.id, it) },
            onDismiss = { ui.renaming = null },
        )
    }
}
