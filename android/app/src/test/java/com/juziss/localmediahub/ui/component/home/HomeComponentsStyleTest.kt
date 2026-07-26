package com.juziss.localmediahub.ui.component.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.juziss.localmediahub.ui.theme.LocalMediaHubTheme
import com.juziss.localmediahub.viewmodel.LibrarySummary
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeComponentsStyleTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun library_card_renders_under_refined_theme() {
        composeRule.setContent {
            LocalMediaHubTheme(themeKey = "DAY") {
                LibraryCard(
                    library = LibrarySummary(name = "电影库", path = "/srv/movies"),
                    onClick = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("电影库").assertIsDisplayed()
    }

    @Test
    fun section_header_renders_semi_bold() {
        composeRule.setContent {
            LocalMediaHubTheme(themeKey = "DAY") {
                SectionHeader(title = "媒体库", subtitle = "4 个库")
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("媒体库").assertIsDisplayed()
        composeRule.onNodeWithText("4 个库").assertIsDisplayed()
    }
}
