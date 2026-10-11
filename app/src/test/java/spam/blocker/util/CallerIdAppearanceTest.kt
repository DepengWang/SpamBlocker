package spam.blocker.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallerIdAppearanceTest {
    @Test fun transparentOverridesEveryTheme() {
        for (theme in 0..2) {
            val color = CallerIdAppearance.backgroundColor(theme, 0xFF123456.toInt(), true)
            assertEquals(0, color)
            assertFalse(CallerIdAppearance.hasBackground(color))
        }
    }

    @Test fun disablingTransparencyRestoresOriginalThemeOrColor() {
        assertEquals(0x80123456.toInt(), CallerIdAppearance.backgroundColor(0, 0x80123456.toInt(), false))
        assertEquals(0xEE202124.toInt(), CallerIdAppearance.backgroundColor(1, 0, false))
        assertEquals(0xEEF5F5F5.toInt(), CallerIdAppearance.backgroundColor(2, 0, false))
    }

    @Test fun onlyZeroAlphaRemovesBackgroundAndBorder() {
        assertFalse(CallerIdAppearance.hasBackground(0x00123456))
        assertTrue(CallerIdAppearance.hasBackground(0x01123456))
        assertTrue(CallerIdAppearance.hasBackground(0xFF123456.toInt()))
    }
}
