package app.lumen.core.system

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.net.Inet4Address

data class NetworkStatus(
    val kind: Kind,
    /** Actually reaches the internet, rather than only being connected to something. */
    val hasInternet: Boolean,
    /** 1..4 bars on Wi-Fi; null when unknown or not on Wi-Fi. */
    val signalBars: Int?,
    val ipAddress: String?,
) {
    enum class Kind { None, WiFi, Ethernet, Other }
}

/** What's playing in another app, and its transport controls. */
class NowPlaying internal constructor(
    val packageName: String,
    val title: String,
    val artist: String?,
    val art: Bitmap?,
    val isPlaying: Boolean,
    /** The track's length; 0 for a live stream or when the app didn't say. */
    val durationMs: Long,
    private val state: PlaybackState?,
    private val controller: MediaController,
    private val controls: MediaController.TransportControls = controller.transportControls,
) {
    /** How far along the track is right now, worked out from what the app last reported. */
    fun positionMs(): Long {
        val state = state ?: return 0
        if (state.position < 0) return 0
        val elapsed = if (isPlaying) SystemClock.elapsedRealtime() - state.lastPositionUpdateTime else 0
        return (state.position + (elapsed * state.playbackSpeed).toLong()).coerceIn(0, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
    }

    /** Opens the app that's playing, on its own player screen when it says where that is. */
    fun open(context: Context) {
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 34) {
            options.pendingIntentBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }
        val opened = runCatching {
            checkNotNull(controller.sessionActivity).send(context, 0, null, null, null, null, options.toBundle())
        }.isSuccess
        if (!opened) {
            val pm = context.packageManager
            (pm.getLeanbackLaunchIntentForPackage(packageName) ?: pm.getLaunchIntentForPackage(packageName))?.let { intent ->
                runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }

    fun playPause() = if (isPlaying) controls.pause() else controls.play()
    fun next() = controls.skipToNext()
    fun previous() = controls.skipToPrevious()
}

/** Live state of the device, for the status icons by the clock and Control Center. */
class DeviceStatus(context: Context) {

    private val context = context.applicationContext
    private val connectivity = this.context.getSystemService(ConnectivityManager::class.java)
    private val audio = this.context.getSystemService(AudioManager::class.java)

    val network: Flow<NetworkStatus> = callbackFlow {
        var capabilities = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
        var links = connectivity.activeNetwork?.let(connectivity::getLinkProperties)
        fun publish() = trySend(networkStatus(capabilities, links))
        publish()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                capabilities = networkCapabilities
                publish()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                links = linkProperties
                publish()
            }

            override fun onLost(network: Network) {
                capabilities = null
                links = null
                publish()
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    /** The name of the connected headphones, or null when sound goes to the TV. */
    val headphones: Flow<String?> = callbackFlow {
        fun publish() = trySend(connectedHeadphones())
        publish()
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { publish() }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { publish() }
        }
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        awaitClose { audio.unregisterAudioDeviceCallback(callback) }
    }.distinctUntilChanged()

    /** What's playing, preferring what's actually audible. Stays null without notification access. */
    val nowPlaying: Flow<NowPlaying?> = callbackFlow {
        val handler = Handler(Looper.getMainLooper())
        var controllers = emptyList<MediaController>()
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) { trySend(pickNowPlaying(controllers)) }
            override fun onMetadataChanged(metadata: MediaMetadata?) { trySend(pickNowPlaying(controllers)) }
            override fun onSessionDestroyed() { trySend(pickNowPlaying(controllers)) }
        }
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { active ->
            controllers.forEach { it.unregisterCallback(callback) }
            controllers = active.orEmpty()
            controllers.forEach { it.registerCallback(callback, handler) }
            trySend(pickNowPlaying(controllers))
        }
        val sessions = this@DeviceStatus.context.getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(this@DeviceStatus.context, MediaAccessService::class.java)
        val allowed = hasMediaAccess(this@DeviceStatus.context) && runCatching {
            sessions.addOnActiveSessionsChangedListener(listener, component, handler)
            listener.onActiveSessionsChanged(sessions.getActiveSessions(component))
        }.isSuccess
        if (!allowed) trySend(null)
        awaitClose {
            if (allowed) sessions.removeOnActiveSessionsChangedListener(listener)
            controllers.forEach { it.unregisterCallback(callback) }
        }
    }

    /** The volume of what's connected, 0..1, for headphones; the TV's own volume is out of an app's reach. */
    fun headphoneVolume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    fun adjustVolume(up: Boolean) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
    }

    private fun connectedHeadphones(): String? =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in HEADPHONE_TYPES }?.let { device ->
            device.productName?.toString()?.takeIf(String::isNotBlank) ?: "Headphones"
        }

    private fun networkStatus(capabilities: NetworkCapabilities?, links: LinkProperties?): NetworkStatus {
        val kind = when {
            capabilities == null -> NetworkStatus.Kind.None
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkStatus.Kind.WiFi
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkStatus.Kind.Ethernet
            else -> NetworkStatus.Kind.Other
        }
        val signal = if (kind == NetworkStatus.Kind.WiFi && Build.VERSION.SDK_INT >= 29) capabilities?.signalStrength else null
        return NetworkStatus(
            kind = kind,
            hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            signalBars = signal?.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }?.let {
                when {
                    it >= -55 -> 4
                    it >= -66 -> 3
                    it >= -77 -> 2
                    else -> 1
                }
            },
            ipAddress = links?.linkAddresses?.map(LinkAddress::getAddress)?.firstOrNull { it is Inet4Address }?.hostAddress,
        )
    }

    private fun pickNowPlaying(controllers: List<MediaController>): NowPlaying? {
        val candidates = controllers.filter { controller ->
            controller.playbackState?.state in SHOWN_STATES &&
                !controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()
        }
        val controller = candidates.firstOrNull { it.playbackState?.state in AUDIBLE_STATES } ?: candidates.firstOrNull() ?: return null
        val metadata = checkNotNull(controller.metadata)
        return NowPlaying(
            packageName = controller.packageName,
            title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf(String::isNotBlank)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)?.takeIf(String::isNotBlank),
            art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            isPlaying = controller.playbackState?.state in AUDIBLE_STATES,
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
            state = controller.playbackState,
            controller = controller,
        )
    }

    companion object {
        /** Whether the Now Playing setting has been turned on in Android's notification access. */
        fun hasMediaAccess(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        private val HEADPHONE_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        private val AUDIBLE_STATES = setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING)
        private val SHOWN_STATES = AUDIBLE_STATES + PlaybackState.STATE_PAUSED
    }
}
