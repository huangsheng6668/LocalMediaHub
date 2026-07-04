package com.juziss.localmediahub.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.Tag
import com.juziss.localmediahub.ui.component.TagMenuDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TagMenuDialogTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val file = MediaFile(
        name = "movie.mp4", path = "/movie.mp4", relativePath = "movie.mp4",
        size = 1L, modifiedTime = "", mediaType = "video", extension = "mp4",
    )

    @Test
    fun renders_all_tag_names() {
        composeRule.setContent {
            TagMenuDialog(
                file = file,
                tags = listOf(Tag("1", "Music"), Tag("2", "Work")),
                fileTags = emptyList(),
                onTagFile = {}, onUntagFile = {}, onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Music").assertExists()
        composeRule.onNodeWithText("Work").assertExists()
    }

    @Test
    fun click_unapplied_applies_and_click_applied_removes() {
        val applied = mutableListOf<String>()
        val removed = mutableListOf<String>()
        composeRule.setContent {
            TagMenuDialog(
                file = file,
                tags = listOf(Tag("1", "Music"), Tag("2", "Work")),
                fileTags = listOf(Tag("2", "Work")), // "Work" is applied
                onTagFile = { applied.add(it) },
                onUntagFile = { removed.add(it) },
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Music").performClick()   // unapplied -> onTagFile("1")
        assertEquals(listOf("1"), applied)
        composeRule.onNodeWithText("Work").performClick()    // applied -> onUntagFile("2")
        assertEquals(listOf("2"), removed)
    }
}
