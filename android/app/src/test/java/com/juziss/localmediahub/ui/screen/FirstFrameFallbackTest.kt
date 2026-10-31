package com.juziss.localmediahub.ui.screen

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 2026-10-31-android-video-playback-perf-phase3: some decoders report
 * support but never output a frame (real-device H.264 High 4:4:4). The
 * watchdog treats "playing, ready, video track present, no first frame for
 * 6s" as a silent decode failure and falls back to server transcode.
 */
class FirstFrameFallbackTest {

    @Test
    fun firesAtThreshold() {
        assertTrue(shouldFallback())
    }

    @Test
    fun belowThresholdDoesNotFire() {
        assertFalse(shouldFallback(unrenderedPlayingMs = FIRST_FRAME_FALLBACK_TIMEOUT_MS - 1))
    }

    @Test
    fun renderedFirstFrameDoesNotFire() {
        assertFalse(shouldFallback(hasRenderedFirstFrame = true))
    }

    @Test
    fun noVideoTrackDoesNotFire() {
        assertFalse(shouldFallback(hasVideoTrack = false))
    }

    @Test
    fun pausedDoesNotFire() {
        assertFalse(shouldFallback(isPlaying = false))
    }

    @Test
    fun notReadyDoesNotFire() {
        assertFalse(shouldFallback(playbackState = Player.STATE_BUFFERING))
        assertFalse(shouldFallback(playbackState = Player.STATE_IDLE))
        assertFalse(shouldFallback(playbackState = Player.STATE_ENDED))
    }

    @Test
    fun localUriDoesNotFire() {
        assertFalse(shouldFallback(isLocalUri = true))
    }

    @Test
    fun transcodingDoesNotFire() {
        assertFalse(shouldFallback(isTranscoding = true))
    }

    @Test
    fun secondAttemptDoesNotFire() {
        assertFalse(shouldFallback(alreadyAttempted = true))
    }

    @Test
    fun guardBeatsElapsedTime() {
        assertFalse(
            shouldFallback(
                isTranscoding = true,
                hasRenderedFirstFrame = false,
                unrenderedPlayingMs = FIRST_FRAME_FALLBACK_TIMEOUT_MS * 10,
            )
        )
    }

    private fun shouldFallback(
        isTranscoding: Boolean = false,
        alreadyAttempted: Boolean = false,
        isLocalUri: Boolean = false,
        hasVideoTrack: Boolean = true,
        hasRenderedFirstFrame: Boolean = false,
        isPlaying: Boolean = true,
        playbackState: Int = Player.STATE_READY,
        unrenderedPlayingMs: Long = FIRST_FRAME_FALLBACK_TIMEOUT_MS,
    ): Boolean = shouldFallbackForMissingFirstFrame(
        isTranscoding = isTranscoding,
        alreadyAttempted = alreadyAttempted,
        isLocalUri = isLocalUri,
        hasVideoTrack = hasVideoTrack,
        hasRenderedFirstFrame = hasRenderedFirstFrame,
        isPlaying = isPlaying,
        playbackState = playbackState,
        unrenderedPlayingMs = unrenderedPlayingMs,
    )
}
