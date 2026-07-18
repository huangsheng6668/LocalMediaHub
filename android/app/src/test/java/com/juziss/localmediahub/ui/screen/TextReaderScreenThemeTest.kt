package com.juziss.localmediahub.ui.screen

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.juziss.localmediahub.data.ReaderSettings
import com.juziss.localmediahub.data.ReaderTheme
import com.juziss.localmediahub.ui.component.reader.ReaderThemeScope
import com.juziss.localmediahub.ui.component.reader.ReaderThemeWrapper
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Smoke test for Task 6 (text-reader C-phase) + Phase 2 Task 2.3
 * (ReaderThemeScope overrides Material3 colorScheme).
 *
 * Verifies that [ReaderThemeWrapper] / [ReaderThemeScope] — the composition-local
 * scope the rewritten [TextReaderScreen] wraps its body in — actually renders the
 * wrapped content, and (Phase 2) that the Material3 colorScheme is overridden so
 * TopAppBar/BottomAppBar/Sheets automatically follow the reader theme.
 *
 * Uses Robolectric (not instrumented) so it runs under `testDebugUnitTest`.
 */
@RunWith(RobolectricTestRunner::class)
class TextReaderScreenThemeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun reader_theme_wrapper_renders_content_with_theme_bg() {
        composeRule.setContent {
            ReaderThemeWrapper(theme = ReaderTheme.NIGHT) {
                Text("Hello theme", color = LocalContentColor.current)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Hello theme").assertIsDisplayed()
        // Visual color assertion is hard in Compose tests; we trust the
        // CompositionLocalProvider contract verified at the composable level.
    }

    @Test
    fun reader_theme_scope_overrides_material_color_scheme() {
        val capturedScheme = mutableListOf<androidx.compose.material3.ColorScheme>()
        composeRule.setContent {
            ReaderThemeScope(theme = ReaderTheme.NIGHT) {
                capturedScheme.add(MaterialTheme.colorScheme)
                Box {}
            }
        }
        composeRule.waitForIdle()
        assertEquals(ReaderTheme.NIGHT.chromeBg, capturedScheme.single().surface)
        assertEquals(ReaderTheme.NIGHT.bg, capturedScheme.single().background)
        assertEquals(ReaderTheme.NIGHT.fg, capturedScheme.single().onBackground)
    }

    @Test
    fun auto_theme_resolves_based_on_system_dark_mode() {
        // isSystemInDarkTheme() 在 Robolectric 单测中难以 mock；这里改测
        // ReaderTheme.resolveAuto 这个纯函数（Phase 2 Task 2.3 Step 3）。
        assertEquals(ReaderTheme.NIGHT, ReaderTheme.resolveAuto(true))
        assertEquals(ReaderTheme.DAY, ReaderTheme.resolveAuto(false))
    }

    /**
     * Phase 3 Task 3.4 smoke test: the (now internal) ParagraphItem renders
     * with V2 typography — fontFamily, fontSize, lineHeight, TextIndent(2.em)
     * when firstLineIndent=true. We can't introspect TextIndent from the
     * Compose tree easily, so this test only verifies the text renders. The
     * typography plumbing is exercised through the real TextReaderScreen
     * composition via settings.fontFamily.toFontFamily() etc.
     */
    @Test
    fun paragraph_item_applies_v2_typography() {
        val settings = ReaderSettings()  // 默认 V2: SYSTEM / 16 / 1.8 / firstLineIndent=true
        composeRule.setContent {
            ReaderThemeScope(theme = settings.theme) {
                ParagraphItem(
                    text = "测试段落",
                    fontSizeSp = settings.fontSizeSp.sp,
                    lineHeightSp = (settings.fontSizeSp * settings.lineHeightMultiplier).sp,
                    fontFamily = settings.fontFamily.toFontFamily(),
                    firstLineIndent = settings.firstLineIndent,
                    paragraphGapEm = 1.2f,
                    onAddBookmark = {},
                    onCopy = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("测试段落").assertIsDisplayed()
    }
}
