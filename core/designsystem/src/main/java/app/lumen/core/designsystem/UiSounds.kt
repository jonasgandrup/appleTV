package app.lumen.core.designsystem

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import androidx.compose.runtime.staticCompositionLocalOf
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * tvOS-style interface sounds: a soft tick when focus moves and a deeper tock on select.
 * Both are synthesized once into the cache directory, so the app ships no audio files.
 */
class UiSounds(context: Context) {

    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val tick = pool.load(synthesize(context, "tick.wav", 0.022, ::tick), 1)
    private val tock = pool.load(synthesize(context, "tock.wav", 0.09, ::tock), 1)

    fun move() {
        pool.play(tick, MOVE_VOLUME, MOVE_VOLUME, 1, 0, 1f)
    }

    fun select() {
        pool.play(tock, SELECT_VOLUME, SELECT_VOLUME, 1, 0, 1f)
    }

    /** Remembers that the user pressed a direction, so the focus change that follows plays a tick. */
    fun onDirectionPressed() {
        lastDirectionAt = SystemClock.uptimeMillis()
    }

    /**
     * Ticks only for focus changes the user caused, not for focus Lumen moves itself (on launch,
     * on Home, when a dialog opens).
     */
    fun onFocusMoved() {
        if (SystemClock.uptimeMillis() - lastDirectionAt < USER_MOVE_WINDOW_MS) move()
    }

    private var lastDirectionAt = 0L

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val MOVE_VOLUME = 0.35f
        const val SELECT_VOLUME = 0.5f
        const val USER_MOVE_WINDOW_MS = 400L

        /** A short, bright click: two high partials with a very fast decay. */
        fun tick(t: Double) = (0.6 * sin(2 * PI * 1900 * t) + 0.4 * sin(2 * PI * 3100 * t)) * exp(-t / 0.0035)

        /** A rounder, lower knock for select. */
        fun tock(t: Double) = (0.7 * sin(2 * PI * 620 * t) + 0.3 * sin(2 * PI * 1240 * t)) * exp(-t / 0.018)

        /** Renders [wave] to a 16-bit mono WAV in the cache and returns its path. */
        fun synthesize(context: Context, name: String, seconds: Double, wave: (Double) -> Double): String {
            val file = File(context.cacheDir, name)
            val samples = (SAMPLE_RATE * seconds).toInt()
            val pcm = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until samples) {
                val t = i.toDouble() / SAMPLE_RATE
                val attack = min(1.0, t / 0.0006) // avoids a pop at the start
                pcm.putShort((wave(t) * attack * Short.MAX_VALUE * 0.9).toInt().toShort())
            }
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(samples * 2)
            }
            file.outputStream().use {
                it.write(header.array())
                it.write(pcm.array())
            }
            return file.path
        }
    }
}

/** The app's interface sounds, or null when they're turned off. */
val LocalUiSounds = staticCompositionLocalOf<UiSounds?> { null }
