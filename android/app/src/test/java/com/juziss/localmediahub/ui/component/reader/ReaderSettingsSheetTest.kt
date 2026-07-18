package com.juziss.localmediahub.ui.component.reader

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * the assertions exercising the real UI logic (font chips / sliders /
 * switches / ThemeChipRow / onChange handlers) without the host getting in
 * the way.
 *
 * Phase 3: discrete font/line-height chips replaced with continuous sliders
 * (verified via the "字号 16" / "行距 1.8" labels); font-family FilterChips
 * added; paragraph toggles added.
 *
 * Note: the project does not have Truth on the test classpath (confirmed in
 * T2), so we use plain JUnit [assertEquals] / [assertNotNull] assertions.
 */
@RunWith(RobolectricTestRunner::class)
class ReaderSettingsSheetTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * Phase 3: section titles and slider value labels render with default
     * settings (16 / 1.8 / DAY). The discrete "小"/"紧凑" chips no longer
     * exist — they are replaced by slider value labels "字号 16" / "行距 1.8".
     */
    @Test
    fun renders_all_sections_and_default_selections() {
        composeRule.setContent {
            ReaderSettingsSheetContent(
                settings = ReaderSettings(),  // SYSTEM / 16 / 1.8 / DAY / speed=5
                onChange = {},
            )
        }
        composeRule.waitForIdle()
        // Section titles
        composeRule.onNodeWithText("阅读设置").assertExists()
        composeRule.onNodeWithText("外观").assertExists()
        composeRule.onNodeWithText("字号与行距").assertExists()
        composeRule.onNodeWithText("段落").assertExists()
        composeRule.onNodeWithText("行为").assertExists()
        // Slider value labels (default font 16, line height 1.8, width 600, speed 5)
        composeRule.onNodeWithText("字号 16").assertExists()
        composeRule.onNodeWithText("行距 1.8").assertExists()
        composeRule.onNodeWithText("宽度 600").assertExists()
        composeRule.onNodeWithText("自动滚动速度 5").assertExists()
        // Paragraph toggle labels
        composeRule.onNodeWithText("首行缩进").assertExists()
        composeRule.onNodeWithText("段间距").assertExists()
        composeRule.onNodeWithText("沉浸模式").assertExists()
        // Theme chip (at least DAY)
        composeRule.onNodeWithText(ReaderTheme.DAY.label).assertExists()
    }

    @Test
    fun clicking_font_family_chip_fires_onchange_with_new_family() {
        var captured: ReaderSettings? = null
        composeRule.setContent {
            ReaderSettingsSheetContent(
                settings = ReaderSettings(),
                onChange = { captured = it },
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(ReaderFontFamily.SERIF.label).performClick()
        assertNotNull(captured)
        assertEquals(ReaderFontFamily.SERIF, captured?.fontFamily)
        // Other settings preserved
        assertEquals(16, captured?.fontSizeSp)
        assertEquals(1.8f, captured?.lineHeightMultiplier ?: -1f, 0.0001f)
        assertEquals(ReaderTheme.DAY, captured?.theme)
    }

    @Test
    fun clicking_theme_chip_fires_onchange_with_new_theme() {
        var captured: ReaderSettings? = null
        composeRule.setContent {
            ReaderSettingsSheetContent(
                settings = ReaderSettings(),
                onChange = { captured = it },
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(ReaderTheme.NIGHT.label).performClick()
        assertNotNull(captured)
        assertEquals(ReaderTheme.NIGHT, captured?.theme)
    }

    /**
     * Phase 2 Task 2.4: settings sheet must render all 7 theme FilterChips,
     * including AUTO (跟随系统). FlowRow layout means they wrap across rows;
     * we only assert presence of each label.
     */
    @Test
    fun settings_sheet_renders_all_seven_theme_options_including_auto() {
        composeRule.setContent {
            ReaderSettingsSheetContent(
                settings = ReaderSettings(),
                onChange = {},
            )
        }
        composeRule.waitForIdle()
        ReaderTheme.entries.forEach { theme ->
            composeRule.onNodeWithText(theme.label).assertExists()
        }
    }

    @Test
    fun clicking_auto_theme_chip_fires_onchange_with_auto() {
        var captured: ReaderSettings? = null
        composeRule.setContent {
            ReaderSettingsSheetContent(
                settings = ReaderSettings(),
                onChange = { captured = it },
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(ReaderTheme.AUTO.label).performClick()
        assertNotNull(captured)
        assertEquals(ReaderTheme.AUTO, captured?.theme)
    }

    /**
     * Phase 2 §1.2: when theme == AUTO, the 6 concrete theme FilterChips
     * must be visually disabled (greyed out) while the AUTO chip itself
     * stays enabled so the user can switch away from AUTO.
     */
    @Test
    fun auto_theme_disables_concrete_theme_chips() {
        composeRule.setContent {
            ReaderSettingsSheetContent(
                settings = ReaderSettings(theme = ReaderTheme.AUTO),
                onChange = {},
            )
        }
        composeRule.waitForIdle()
        val concreteThemes = listOf(
            ReaderTheme.DAY,
            ReaderTheme.DAY_BRIGHT,
            ReaderTheme.EYE_CARE,
            ReaderTheme.PARCHMENT,
            ReaderTheme.NIGHT,
            ReaderTheme.NIGHT_BLACK,
        )
        concreteThemes.forEach { theme ->
            composeRule.onNodeWithText(theme.label).assertIsNotEnabled()
        }
        // AUTO chip itself must remain selectable so the user can pick another theme
        composeRule.onNodeWithText(ReaderTheme.AUTO.label).assertIsEnabled()
    }

    /**
     * Phase 2 §1.2: when theme != AUTO, all theme chips (including AUTO and
     * the currently selected one) must be enabled.
     */
    @Test
    fun non_auto_theme_keeps_chips_enabled() {
        composeRule.setContent {
            ReaderSettingsSheetContent(
                settings = ReaderSettings(theme = ReaderTheme.DAY),
                onChange = {},
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(ReaderTheme.DAY.label).assertIsEnabled()
        composeRule.onNodeWithText(ReaderTheme.NIGHT.label).assertIsEnabled()
        composeRule.onNodeWithText(ReaderTheme.AUTO.label).assertIsEnabled()
    }

    /**
     * Phase 3 Task 3.5: settings sheet must render all 3 ReaderFontFamily
     * FilterChips (SYSTEM / SERIF / KAITI) so users can switch fonts.
     */
    @Test
    fun settings_sheet_renders_all_font_families() {
        composeRule.setContent {
            ReaderSettingsSheetContent(
                settings = ReaderSettings(),
                onChange = {},
            )
        }
        composeRule.waitForIdle()
        ReaderFontFamily.entries.forEach { ff ->
            composeRule.onNodeWithText(ff.label).assertExists()
        }
    }
}
