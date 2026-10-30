package com.juziss.localmediahub.ui.screen

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Guards the HLS transcode fallback wiring (spec 2026-10-30-android-video-playback-perf). */
@RunWith(RobolectricTestRunner::class)
class VideoPlaybackMediaItemTest {

    private val directUrl = "http://192.168.1.2:8000/api/v1/media/stream?path=D%3A%5Cvideo.mp4"
    private val hlsUrl = "http://192.168.1.2:8000/api/v1/media/hls/playlist?path=D%3A%5Cvideo.mp4"

    @Test
    fun hlsMimeItemProducesHlsMediaSource() {
        val item = MediaItem.Builder()
            .setUri(hlsUrl)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()

        val source = DefaultMediaSourceFactory(DefaultHttpDataSource.Factory())
            .createMediaSource(item)

        assertTrue(
            "expected HlsMediaSource but got ${source.javaClass.name}",
            source is HlsMediaSource,
        )
    }

    @Test
    fun transcodeUrlGetsHlsMime() {
        val item = buildPlaybackMediaItem(directUrl, transcode = true)

        assertEquals(hlsUrl, item.localConfiguration?.uri.toString())
        assertEquals(MimeTypes.APPLICATION_M3U8, item.localConfiguration?.mimeType)
    }

    @Test
    fun directUrlKeepsNoMime() {
        val item = buildPlaybackMediaItem(directUrl)

        assertEquals(directUrl, item.localConfiguration?.uri.toString())
        assertNull(item.localConfiguration?.mimeType)
    }
}
