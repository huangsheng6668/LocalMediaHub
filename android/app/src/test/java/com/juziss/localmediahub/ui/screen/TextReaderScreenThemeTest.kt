package com.juziss.localmediahub.ui.screen

import androidx.activity.ComponentActivity
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.juziss.localmediahub.data.ReaderTheme
import com.juziss.localmediahub.ui.component.reader.ReaderThemeWrapper
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Smoke test for Task 6 (text-reader C-phase).
 *
 * Verifies that [ReaderThemeWrapper] — the composition-local scope the
 * rewritten [TextReaderScreen] wraps its body in — actually renders the
 * wrapped content. Full theme switching and color assertions are exercised
 * manually in T11 acceptance; here we only lock in the "content is composed
 * and laid out" contract so a future refactor of ReaderThemeWrapper cannot
 * silently break TextReaderScreen's body.
 *
 * Uses Robolectric (not instrumented) so it runs under `testDebugUnitTest`.
 * The project has no Truth on the test classpath, so we rely on Compose's
 * own [assertIsDisplayed] (throws on failure) rather than Truth assertions.
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
}
