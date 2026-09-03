package com.juziss.localmediahub.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Test

/** buildStreamUrl: HLS-era URL transform (spec 2026-09-03-hls-transcode). */
class VideoPlayerStreamUrlTest {

    private val base = "http://192.168.1.2:8000/api/v1/media/stream?path=D%3A%5Cvideo.mp4"

    @Test
    fun transcodeMapsStreamToHlsPlaylist() {
        assertEquals(
            "http://192.168.1.2:8000/api/v1/media/hls/playlist?path=D%3A%5Cvideo.mp4",
            buildStreamUrl(base, transcode = true),
        )
    }

    @Test
    fun staleTranscodeAndStartParamsAreStripped() {
        val dirty = base + "&transcode=true&start=12.500"
        assertEquals(
            "http://192.168.1.2:8000/api/v1/media/hls/playlist?path=D%3A%5Cvideo.mp4",
            buildStreamUrl(dirty, transcode = true),
        )
    }

    @Test
    fun directModeIsPassthrough() {
        assertEquals(base, buildStreamUrl(base, transcode = false))
    }

    @Test
    fun directModeStripsStaleParamsToo() {
        val dirty = base + "&transcode=true"
        assertEquals(base, buildStreamUrl(dirty, transcode = false))
    }
}
