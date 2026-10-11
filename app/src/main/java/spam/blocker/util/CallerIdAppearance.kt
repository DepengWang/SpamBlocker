package spam.blocker.util

/** Background-only styling; never changes content opacity, size or drag behavior. */
internal object CallerIdAppearance {
    fun backgroundColor(theme: Int, customColor: Int, transparent: Boolean): Int = when {
        transparent -> 0
        theme == 1 -> 0xEE202124.toInt()
        theme == 2 -> 0xEEF5F5F5.toInt()
        else -> customColor
    }

    fun hasBackground(color: Int): Boolean = color ushr 24 != 0
}
