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
            ProvidePrimaryText(Color(0xFF965410)) {
                captured.add(primaryTextColor())
            }
        }
        composeRule.waitForIdle()
        assertEquals(Color(0xFF965410), captured.single())
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
    fun day_theme_provides_terracotta_primary_text() {
        val captured = mutableListOf<Color>()
        composeRule.setContent {
            LocalMediaHubTheme(themeKey = "DAY") { captured.add(primaryTextColor()) }
        }
        composeRule.waitForIdle()
        assertEquals(Color(0xFF965410), captured.single())
    }

    @Test
    fun night_theme_provides_warm_amber_primary_text() {
        val captured = mutableListOf<Color>()
        composeRule.setContent {
            LocalMediaHubTheme(themeKey = "NIGHT") { captured.add(primaryTextColor()) }
        }
        composeRule.waitForIdle()
        // night primary (#E8915A) 已 7.8:1 合规；显式 Provide #F2A878 与 Web --accent-text 对齐。
        assertEquals(Color(0xFFF2A878), captured.single())
    }
}
