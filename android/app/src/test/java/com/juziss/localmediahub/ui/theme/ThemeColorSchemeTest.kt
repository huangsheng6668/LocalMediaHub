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
    fun day_theme_uses_terracotta_primary() {
        val s = schemeFor("DAY")
        assertEquals(Color(0xFFB96D1D), s.primary)
        assertEquals(Color(0xFF3E7A7E), s.secondary)
        assertEquals(Color(0xFFF4EEE2), s.background)
        assertEquals(Color(0xFFFBF6EC), s.surface)
        assertEquals(Color(0xFFFBEBD8), s.primaryContainer)
        assertEquals(Color(0xFFD6EFF0), s.secondaryContainer)
    }

    @Test
    fun day_theme_provides_terracotta_outline_soft() {
        val captured = mutableListOf<Color>()
        composeRule.setContent {
            LocalMediaHubTheme(themeKey = "DAY") { captured.add(outlineSoftColor()) }
        }
        composeRule.waitForIdle()
        assertEquals(Color(0xFFE2D9C6), captured.single())
    }

    @Test
    fun night_theme_uses_warm_amber_primary_and_warm_black_bg() {
        val s = schemeFor("NIGHT")
        assertEquals(Color(0xFFE8915A), s.primary)
        assertEquals(Color(0xFF6FB8BC), s.secondary)
        assertEquals(Color(0xFF141210), s.background)
        assertEquals(Color(0xFF1E1A17), s.surface)
        assertEquals(Color(0xFF3A2516), s.primaryContainer)
        assertEquals(Color(0xFF1A3335), s.secondaryContainer)
    }

    @Test
    fun night_theme_provides_dark_outline_soft() {
        val captured = mutableListOf<Color>()
        composeRule.setContent {
            LocalMediaHubTheme(themeKey = "NIGHT") { captured.add(outlineSoftColor()) }
        }
        composeRule.waitForIdle()
        assertEquals(Color(0xFF332B24), captured.single())
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
        assertEquals(Color(0xFF8C6239), schemes.single().primary) // 保留自身 primary
        assertEquals(Color(0xFFD9C8B2), os.single())
    }
}
