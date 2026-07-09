package com.juziss.localmediahub

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.juziss.localmediahub.data.MediaFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoPlayerIntentBuilderTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun sampleMediaFile() = MediaFile(
        name = "demo.mp4",
        path = "/videos/demo.mp4",
        relativePath = "videos/demo.mp4",
        size = 1234567L,
        modifiedTime = "2023-11-14T12:00:00Z",
        mediaType = "video",
        extension = "mp4",
    )

    @Test
    fun `build targets VideoPlayerActivity`() {
        val intent = VideoPlayerIntentBuilder.build(
            context = ctx,
            file = sampleMediaFile(),
            streamUrl = "http://example.com/demo.mp4",
            initialPositionMs = 5000L,
            isSystemBrowse = false,
        )
        assertNotNull(intent.component)
        assertEquals(
            "com.juziss.localmediahub.VideoPlayerActivity",
            intent.component?.className,
        )
    }

    @Test
    fun `build includes FLAG_ACTIVITY_NEW_TASK for independent task`() {
        val intent = VideoPlayerIntentBuilder.build(
            context = ctx,
            file = sampleMediaFile(),
            streamUrl = "http://example.com/demo.mp4",
            initialPositionMs = 0L,
            isSystemBrowse = false,
        )
        assertTrue(
            "Intent must carry FLAG_ACTIVITY_NEW_TASK for independent taskAffinity",
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
    }

    @Test
    fun `build includes all 4 extras with correct types`() {
        val file = sampleMediaFile()
        val intent = VideoPlayerIntentBuilder.build(
            context = ctx,
            file = file,
            streamUrl = "http://example.com/demo.mp4",
            initialPositionMs = 7500L,
            isSystemBrowse = true,
        )
        assertEquals("http://example.com/demo.mp4", intent.getStringExtra(VideoPlayerIntentBuilder.EXTRA_STREAM_URL))
        assertEquals(7500L, intent.getLongExtra(VideoPlayerIntentBuilder.EXTRA_INITIAL_POSITION_MS, -1L))
        assertEquals(true, intent.getBooleanExtra(VideoPlayerIntentBuilder.EXTRA_IS_SYSTEM_BROWSE, false))
        val restored: MediaFile? = intent.getParcelableExtra(VideoPlayerIntentBuilder.EXTRA_MEDIA_FILE)
        assertNotNull(restored)
        assertEquals(file.relativePath, restored?.relativePath)
        assertEquals(file.name, restored?.name)
    }
}
