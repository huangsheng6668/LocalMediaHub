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
 * the assertions exercising the real UI logic (ChipRow / ThemeChipRow /
 * label / onChange handlers) without the host getting in the way.
 *
 * Phase 1: assertions migrated from V1 enum to V2 numeric form
 * (`fontSizeSp:Int`, `lineHeightMultiplier:Float`). Theme labels are now the
 * full V2 strings ("日间·纸白" / "夜间·深空" / "护眼·米黄" / ...).
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
                settings = ReaderSettings(),  // MEDIUM=16, STANDARD=1.8, DAY, speed=5
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
        composeRule.onNodeWithText(ReaderTheme.DAY.label).assertExists()
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
        assertEquals(20, captured?.fontSizeSp)  // V2 numeric (XLARGE -> 20)
        // Other settings preserved
        assertEquals(1.8f, captured?.lineHeightMultiplier ?: -1f, 0.0001f)  // V2 numeric (STANDARD -> 1.8)
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
}
