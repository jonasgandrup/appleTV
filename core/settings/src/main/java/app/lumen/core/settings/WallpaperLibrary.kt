package app.lumen.core.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Custom wallpapers live in the app's external files dir, which needs no storage permission
 * and is writable over ADB:
 *
 *   adb push photo.jpg /sdcard/Android/data/app.lumen.launcher/files/wallpapers/
 */
class WallpaperLibrary(context: Context) {

    private val context = context.applicationContext

    val directory: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "wallpapers").apply { mkdirs() }

    /** The user's own profile picture, pushed over ADB like a wallpaper. Android gives apps no access to the Google account's. */
    suspend fun profilePicture(): Bitmap? = withContext(Dispatchers.IO) {
        File(context.getExternalFilesDir(null) ?: context.filesDir, PROFILE_PICTURE)
            .takeIf { it.isFile }
            ?.let { decode(it.path, PROFILE_PICTURE_WIDTH) }
    }

    suspend fun list(): List<File> = withContext(Dispatchers.IO) {
        directory.listFiles { f -> f.isFile && f.extension.lowercase() in EXTENSIONS }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    /** Copies an image picked with the system document picker into the library. */
    suspend fun import(uri: Uri): File? = withContext(Dispatchers.IO) {
        runCatching {
            val target = File(directory, "imported-${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
                target
            }
        }.getOrNull()
    }

    companion object {
        private val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        private const val PROFILE_PICTURE = "profile.jpg"
        private const val PROFILE_PICTURE_WIDTH = 160

        /** Decodes [path] subsampled so its width is no larger than needed for [maxWidth]. Blocking. */
        fun decode(path: String, maxWidth: Int): Bitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }
}
