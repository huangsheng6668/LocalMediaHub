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
class OutlineSoftTokenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun outline_soft_defaults_to_explicit_value_when_provided() {
        val captured = mutableListOf<Color>()
        composeRule.setContent {
            ProvideOutlineSoft(Color(0xFFE2D9C6)) {
                captured.add(outlineSoftColor())
            }
        }
        composeRule.waitForIdle()
        assertEquals(Color(0xFFE2D9C6), captured.single())
    }

    @Test
    fun outline_soft_falls_back_to_outline_variant_when_not_provided() {
        val scheme = lightColorScheme(outlineVariant = Color(0xFFAAAAAA))
        val captured = mutableListOf<Color>()
        composeRule.setContent {
            MaterialTheme(colorScheme = scheme) {
                // 未 Provide 时,访问器回退到 outlineVariant
                captured.add(outlineSoftColor())
            }
        }
        composeRule.waitForIdle()
        assertEquals(Color(0xFFAAAAAA), captured.single())
    }
}
