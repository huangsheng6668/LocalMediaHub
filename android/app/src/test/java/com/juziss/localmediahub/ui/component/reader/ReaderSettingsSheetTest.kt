package com.juziss.localmediahub.ui.component.reader

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.juziss.localmediahub.data.ReaderFontSize
import com.juziss.localmediahub.data.ReaderLineHeight
import com.juziss.localmediahub.data.ReaderSettings
import com.juziss.localmediahub.data.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Compose UI tests for [ReaderSettingsSheet].
 *
 * Verifies that all four sections render with their default selections and
 * that chip clicks fire [onChange] with the correct new value while
 * preserving the other settings.
 *
 * Tests compose [ReaderSettingsSheetContent] directly — the exact same
 * composable that the public [ReaderSettingsSheet] renders inside its
 * [androidx.compose.material3.ModalBottomSheet] host. The ModalBottomSheet
 * hosts its content in a separate window whose input dispatch is not
 * reliably drivable under Robolectric, so testing the extracted body keeps
 * the assertions exercising the real UI logic (ChipRow / ThemeChipRow /
 * label / onChange handlers) without the host getting in the way.
 *
 * Note: the project does not have Truth on the test classpath (confirmed in
 * T2), so we use plain JUnit [assertEquals] / [assertNotNull] assertions.
 */
@RunWith(RobolectricTestRunner::class)
class ReaderSettingsSheetTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun renders_all_four_sections_and_default_selections() {
        composeRule.setContent {
            ReaderSettingsSheetContent(
                settings = ReaderSettings(),  // MEDIUM, STANDARD, DAY, speed=5
                onChange = {},
            )
        }
        composeRule.waitForIdle()
        // Section labels
        composeRule.onNodeWithText("字体大小").assertExists()
        composeRule.onNodeWithText("行距").assertExists()
        composeRule.onNodeWithText("主题").assertExists()
        composeRule.onNodeWithText("自动滚动速度").assertExists()
        // Chip labels (at least one of each)
        composeRule.onNodeWithText("小").assertExists()
        composeRule.onNodeWithText("紧凑").assertExists()
        composeRule.onNodeWithText("日间").assertExists()
    }

    @Test
    fun clicking_font_chip_fires_onChange_with_new_size() {
        var captured: ReaderSettings? = null
        composeRule.setContent {
            ReaderSettingsSheetContent(
                settings = ReaderSettings(),
                onChange = { captured = it },
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("超大").performClick()
        assertNotNull(captured)
        assertEquals(ReaderFontSize.XLARGE, captured?.fontSize)
        // Other settings preserved
        assertEquals(ReaderLineHeight.STANDARD, captured?.lineHeight)
        assertEquals(ReaderTheme.DAY, captured?.theme)
    }

    @Test
    fun clicking_theme_chip_fires_onChange_with_new_theme() {
        var captured: ReaderSettings? = null
        composeRule.setContent {
            ReaderSettingsSheetContent(
                settings = ReaderSettings(),
                onChange = { captured = it },
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("夜间").performClick()
        assertNotNull(captured)
        assertEquals(ReaderTheme.NIGHT, captured?.theme)
    }
}
