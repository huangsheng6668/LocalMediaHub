package com.juziss.localmediahub.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.ui.component.VideoCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 5: BLE degraded-mode UX for video list items.
 *
 * Spec §1.3: when `MediaRepository.isBleDegraded == true`, video items in the
 * media-file list render greyed (reduced alpha) and clicking shows a Snackbar
 * "BLE 模式下暂不支持播放视频" instead of opening the player. Non-video items
 * remain clickable.
 *
 * These tests drive [VideoCard] directly with `enabled = false` (the degraded
 * flag is plumbed down from BrowseScreen → BrowseContent → VideoCard) and
 * assert the two load-bearing UX contracts:
 *   1. The card's click routes to [onDisabledClick] (which the screen wires to
 *      `snackbarHostState.showSnackbar(...)`) — NOT to [onClick] (which would
 *      open the player).
 *   2. A disabled card carries an "BLE 模式下暂不支持播放视频" content
 *      description so the greyed/disabled affordance is observable from a
 *      Compose test AND announced to screen readers.
 *
 * The Snackbar message lives in the screen layer (BrowseScreen) — it is
 * verified here by asserting the disabled card surfaces the spec-mandated
 * string as its contentDescription, and that the screen-level callback
 * (`onDisabledClick`) is the one invoked (the screen wires that callback to
 * `showSnackbar("BLE 模式下暂不支持播放视频")`).
 */
@RunWith(RobolectricTestRunner::class)
class VideoItemDegradedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun videoFile(name: String = "trailer.mp4") = MediaFile(
        name = name,
        path = "/$name",
        relativePath = name,
        size = 1L,
        modifiedTime = "",
        mediaType = "video",
        extension = "mp4",
    )

    @Test
    fun disabledVideoCard_clickInvokesDisabledHandler_notOnClick() {
        var openPlayerCalls = 0
        var disabledClickCalls = 0

        composeRule.setContent {
            VideoCard(
                file = videoFile(),
                thumbnailUrl = "",
                isFavorite = false,
                onToggleFavorite = {},
                onClick = { openPlayerCalls += 1 },
                onLongClick = {},
                onDisabledClick = { disabledClickCalls += 1 },
                enabled = false,
            )
        }
        composeRule.waitForIdle()

        // The disabled affordance carries the spec-mandated message as its
        // contentDescription — that doubles as the test hook AND the
        // screen-reader announcement.
        composeRule
            .onNodeWithContentDescription("BLE 模式下暂不支持播放视频")
            .assertIsDisplayed()
            .performClick()
        composeRule.waitForIdle()

        assertEquals(
            "degraded click must route to onDisabledClick (snackbar), not open the player",
            0, openPlayerCalls,
        )
        assertEquals(
            "onDisabledClick must fire exactly once on a single click",
            1, disabledClickCalls,
        )
    }

    @Test
    fun enabledVideoCard_clickOpensPlayer_normally() {
        // Non-degraded regression: a normal (enabled) card must still route to
        // onClick. Guards against accidentally swapping the click wiring when
        // adding the degraded branch.
        var openPlayerCalls = 0
        var disabledClickCalls = 0

        composeRule.setContent {
            VideoCard(
                file = videoFile(),
                thumbnailUrl = "",
                isFavorite = false,
                onToggleFavorite = {},
                onClick = { openPlayerCalls += 1 },
                onLongClick = {},
                onDisabledClick = { disabledClickCalls += 1 },
                enabled = true,
            )
        }
        composeRule.waitForIdle()

        // The card name Text is the click surface in the enabled state. No
        // "BLE 模式下..." disabled node should exist.
        composeRule.onNodeWithText("trailer.mp4").performClick()
        composeRule.waitForIdle()

        assertEquals(
            "enabled click must route to onClick (open player)",
            1, openPlayerCalls,
        )
        assertEquals(
            "onDisabledClick must NOT fire when card is enabled",
            0, disabledClickCalls,
        )
    }
}
