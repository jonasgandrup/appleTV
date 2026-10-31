package app.lumen.core.settings

data class LauncherSettings(
    /** Ordered package names pinned to the Top Shelf. */
    val favorites: List<String> = emptyList(),
    val favoritesInitialized: Boolean = false,
    val hidden: Set<String> = emptySet(),
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
)

enum class GradientPreset(val displayName: String, val stops: List<Long>, val glow: Long) {
    Midnight("Midnight", listOf(0xFF05070D, 0xFF0E1526, 0xFF05060A), 0xFF2A3B6B),
    Aurora("Aurora", listOf(0xFF03100F, 0xFF0B2E33, 0xFF120C24), 0xFF1F7A72),
    Dusk("Dusk", listOf(0xFF0D0714, 0xFF2A1438, 0xFF1A0A12), 0xFF8A3D6B),
    Ember("Ember", listOf(0xFF0E0606, 0xFF2E120C, 0xFF0A0505), 0xFF9C4A22),
    Ocean("Ocean", listOf(0xFF020A14, 0xFF07264A, 0xFF030A14), 0xFF1E5FA8),
    Graphite("Graphite", listOf(0xFF0A0A0B, 0xFF1C1C1F, 0xFF080809), 0xFF4A4A52),
}

sealed interface Wallpaper {
    data class Gradient(val preset: GradientPreset) : Wallpaper
    data class Image(val path: String) : Wallpaper

    companion object {
        val Default: Wallpaper = Gradient(GradientPreset.Midnight)

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
