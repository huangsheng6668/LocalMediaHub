package com.juziss.localmediahub.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrimaryTextTokenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun primary_text_returns_provided_value() {
        val captured = mutableListOf<Color>()
        composeRule.setContent {
            ProvidePrimaryText(Color(0xFF2F5640)) {
                captured.add(primaryTextColor())
            }
        }
        composeRule.waitForIdle()
        assertEquals(Color(0xFF2F5640), captured.single())
    }

    @Test
    fun primary_text_falls_back_to_scheme_primary_when_not_provided() {
        val scheme = lightColorScheme(primary = Color(0xFFAAAAAA))
        val captured = mutableListOf<Color>()
        composeRule.setContent {
            MaterialTheme(colorScheme = scheme) {
                captured.add(primaryTextColor())
            }
        }
        composeRule.waitForIdle()
        assertEquals(Color(0xFFAAAAAA), captured.single())
    }

    @Test
    fun day_theme_provides_moss_primary_text() {
        val captured = mutableListOf<Color>()
        composeRule.setContent {
            LocalMediaHubTheme(themeKey = "DAY") { captured.add(primaryTextColor()) }
        }
        composeRule.waitForIdle()
        assertEquals(Color(0xFF2F5640), captured.single())
    }

    @Test
    fun night_theme_provides_soft_moss_primary_text() {
        val captured = mutableListOf<Color>()
        composeRule.setContent {
            LocalMediaHubTheme(themeKey = "NIGHT") { captured.add(primaryTextColor()) }
        }
        composeRule.waitForIdle()
        // night 显式 Provide #A3CDAF 与 Web --accent-text 对齐（Ink Editorial）。
        assertEquals(Color(0xFFA3CDAF), captured.single())
    }
}
