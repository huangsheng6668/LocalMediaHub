package com.juziss.localmediahub.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThemeColorSchemeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun schemeFor(themeKey: String): androidx.compose.material3.ColorScheme {
        val captured = mutableListOf<androidx.compose.material3.ColorScheme>()
        composeRule.setContent {
            LocalMediaHubTheme(themeKey = themeKey) {
                captured.add(MaterialTheme.colorScheme)
            }
        }
        composeRule.waitForIdle()
        return captured.single()
    }

    @Test
    fun day_theme_uses_ink_primary_and_paper_bg() {
        // Ink Editorial（spec 2026-09-27 §3.1）：primary = 墨黑主操作，
        // secondary = 苔绿，背景 = 暖灰纸面。
        val s = schemeFor("DAY")
        assertEquals(Color(0xFF26282E), s.primary)
        assertEquals(Color(0xFF3D6B4F), s.secondary)
        assertEquals(Color(0xFFF7F7F5), s.background)
        assertEquals(Color(0xFFFFFFFF), s.surface)
        assertEquals(Color(0xFFE9E9E4), s.primaryContainer)
        assertEquals(Color(0xFFE4EDE6), s.secondaryContainer)
    }

    @Test
    fun day_theme_provides_slate_outline_soft() {
        val captured = mutableListOf<Color>()
        composeRule.setContent {
            LocalMediaHubTheme(themeKey = "DAY") { captured.add(outlineSoftColor()) }
        }
        composeRule.waitForIdle()
        assertEquals(Color(0xFFE5E4DF), captured.single())
    }

    @Test
    fun night_theme_uses_paper_ink_primary_and_deep_slate_bg() {
        val s = schemeFor("NIGHT")
        assertEquals(Color(0xFFE7E9EE), s.primary)
        assertEquals(Color(0xFF8FBF9F), s.secondary)
        assertEquals(Color(0xFF0C0D10), s.background)
        assertEquals(Color(0xFF15161A), s.surface)
        assertEquals(Color(0xFF26282C), s.primaryContainer)
        assertEquals(Color(0xFF1C2A20), s.secondaryContainer)
    }

    @Test
    fun night_theme_provides_dark_outline_soft() {
        val captured = mutableListOf<Color>()
        composeRule.setContent {
            LocalMediaHubTheme(themeKey = "NIGHT") { captured.add(outlineSoftColor()) }
        }
        composeRule.waitForIdle()
        assertEquals(Color(0xFF22242B), captured.single())
    }

    @Test
    fun eye_care_theme_keeps_own_primary_but_gets_outline_soft() {
        // Capture both scheme and outline-soft in a single setContent — calling
        // composeRule.setContent twice in one test throws IllegalStateException.
        val schemes = mutableListOf<androidx.compose.material3.ColorScheme>()
        val os = mutableListOf<Color>()
        composeRule.setContent {
            LocalMediaHubTheme(themeKey = "EYE_CARE") {
                schemes.add(MaterialTheme.colorScheme)
                os.add(outlineSoftColor())
            }
        }
        composeRule.waitForIdle()
        assertEquals(Color(0xFF4A6B52), schemes.single().primary) // moss（保留暖纸底）
        assertEquals(Color(0xFFE2D9C4), os.single())
    }
}
