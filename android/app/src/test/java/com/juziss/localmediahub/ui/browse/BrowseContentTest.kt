package com.juziss.localmediahub.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.ui.component.BrowseContent
import com.juziss.localmediahub.ui.component.browse.BrowseContentState
import com.juziss.localmediahub.viewmodel.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrowseContentTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun file(name: String, type: String = "video") = MediaFile(
        name = name, path = "/$name", relativePath = name,
        size = 1L, modifiedTime = "", mediaType = type,
        extension = if (type == "video") "mp4" else "jpg",
    )

    @Test
    fun renders_folders_and_files_without_view_model() {
        var saved: Pair<String, Int>? = null
        composeRule.setContent {
            BrowseContent(
                folders = listOf(Folder(name = "Films", path = "/Films", relativePath = "Films")),
                files = listOf(file("v.mp4")),
                onFolderClick = {}, onVideoClick = {}, onImageClick = {},
                onToggleFavorite = {}, isFavorite = { false },
                state = BrowseContentState(SortOrder.NAME_ASC, SortOrder.NAME_ASC, "/Films", null),
                onSaveScrollPosition = { p, i -> saved = p to i },
                onConsumeRestoreScroll = {},
                getScrollPosition = { 0 },
                getThumbnailUrl = { "" },
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Films").assertExists()
        assertNotNull("save-scroll LaunchedEffect should fire on initial composition", saved)
        assertEquals("/Films" to 0, saved)
    }
}
