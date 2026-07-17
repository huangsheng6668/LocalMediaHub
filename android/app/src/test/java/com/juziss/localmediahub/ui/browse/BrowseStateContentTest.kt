package com.juziss.localmediahub.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.juziss.localmediahub.R
import com.juziss.localmediahub.ui.component.browse.BrowseContentState
import com.juziss.localmediahub.ui.component.browse.BrowseStateContent
import com.juziss.localmediahub.viewmodel.BrowseState
import com.juziss.localmediahub.viewmodel.SortOrder
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrowseStateContentTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun error_state_retry_button_invokes_onRetry() {
        var retried = false
        composeRule.setContent {
            BrowseStateContent(
                browseState = BrowseState.Error("boom"),
                state = BrowseContentState(SortOrder.NAME_ASC, SortOrder.NAME_ASC, "", null),
                isSystemBrowse = false,
                tags = emptyList(),
                activeTagFilter = null,
                onVideoClick = {}, onImageClick = { _, _ -> }, onTextClick = {},
                onToggleFavorite = {}, isFavorite = { false },
                onFileLongClick = {}, onFolderLongClick = {},
                onRetry = { retried = true },
                onBrowseFolder = { _, _ -> },
                onBrowseSystemPath = { _, _ -> },
                onActiveTagFilterChange = {},
                filterFilesByTag = { it },
                onSaveScrollPosition = { _, _ -> },
                onConsumeRestoreScroll = {},
                getScrollPosition = { 0 },
                getThumbnailUrl = { "" },
                innerPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            )
        }
        val retry = composeRule.activity.getString(R.string.browse_retry)
        composeRule.onNodeWithText(retry).performClick()
        assertTrue(retried)
    }
}
