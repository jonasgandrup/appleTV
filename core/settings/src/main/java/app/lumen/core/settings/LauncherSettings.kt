package app.lumen.core.settings

/** What the screen saver shows. */
enum class ScreensaverType(val displayName: String) {
    Aerials("Aerials"),
    Photos("Your Photos"),
}

/** A folder of apps on the home screen, as on tvOS. [apps] are package names, in order. */
data class AppFolder(val id: String, val name: String, val apps: List<String>)

data class LauncherSettings(
    /** Ordered package names pinned to the Top Shelf. */
    val favorites: List<String> = emptyList(),
    val favoritesInitialized: Boolean = false,
    /** The order of the grid below the Top Shelf, as arranged by the user: package names and folder keys. Apps missing from it follow, in their usual order. */
    val gridOrder: List<String> = emptyList(),
    val folders: List<AppFolder> = emptyList(),
    /** The user's name, whose initial stands in for tvOS's profile picture. Blank shows a generic person. */
    val profileName: String = "",
    val hidden: Set<String> = emptySet(),
    /**
     * Programs removed from Up Next and Continue Watching, and where the user was in each when they
     * did. Apps' own lists are read-only here, so this hides them, until that place changes.
     */
    val removedPrograms: Map<String, String> = emptyMap(),
    /** Most recent first. Only launches made from Lumen are tracked. */
    val recents: List<String> = emptyList(),
    val wallpaper: Wallpaper = Wallpaper.Default,
    /** Tint the background with the focused app's artwork. */
    val ambientBackdrop: Boolean = true,
    val showClock: Boolean = true,
    /** Include apps without a TV (leanback) launcher activity. */
    val showPhoneApps: Boolean = false,
    /** Redirect the stock Google TV home to Lumen (needs the accessibility service). */
    val homeOverride: Boolean = true,
    /** Soft ticks when moving focus and selecting, like tvOS. */
    val navigationSounds: Boolean = true,
    /** Minutes idle on the home screen before the screensaver starts; 0 turns it off. */
    val screensaverMinutes: Int = 5,
    val screensaverType: ScreensaverType = ScreensaverType.Aerials,
    /** Keep the time on the screen saver, rather than only when Down is pressed as on tvOS. */
    val screensaverClock: Boolean = true,
)

enum class GradientPreset(val displayName: String, val stops: List<Long>, val glow: Long, val accent: Long) {
    Lagoon("Lagoon", listOf(0xFF071230, 0xFF0C2C63, 0xFF0B0E2E), 0xFF2F9BFF, 0xFF7B3FF2),
    Midnight("Midnight", listOf(0xFF05070D, 0xFF0E1526, 0xFF05060A), 0xFF2A3B6B, 0xFF1B2A5C),
    Aurora("Aurora", listOf(0xFF03100F, 0xFF0B2E33, 0xFF120C24), 0xFF1F9A8E, 0xFF5B2A86),
    Dusk("Dusk", listOf(0xFF0D0714, 0xFF2A1438, 0xFF1A0A12), 0xFFB04A86, 0xFFC8603C),
    Ember("Ember", listOf(0xFF0E0606, 0xFF2E120C, 0xFF0A0505), 0xFFC4582A, 0xFF8E1F2E),
    Ocean("Ocean", listOf(0xFF020A14, 0xFF07264A, 0xFF030A14), 0xFF1E6FC8, 0xFF0F8FA8),
    Graphite("Graphite", listOf(0xFF0A0A0B, 0xFF1C1C1F, 0xFF080809), 0xFF55555E, 0xFF35353C),
}

sealed interface Wallpaper {
    data class Gradient(val preset: GradientPreset) : Wallpaper
    data class Image(val path: String) : Wallpaper

    companion object {
        val Default: Wallpaper = Gradient(GradientPreset.Lagoon)

        internal fun decode(raw: String?): Wallpaper = when {
            raw == null -> Default
            raw.startsWith(GRADIENT) ->
                GradientPreset.entries.find { it.name == raw.removePrefix(GRADIENT) }?.let { Gradient(it) } ?: Default
            raw.startsWith(IMAGE) -> Image(raw.removePrefix(IMAGE))
            else -> Default
        }

        internal fun Wallpaper.encode(): String = when (this) {
            is Gradient -> GRADIENT + preset.name
            is Image -> IMAGE + path
        }

        private const val GRADIENT = "gradient:"
        private const val IMAGE = "image:"
    }
}
