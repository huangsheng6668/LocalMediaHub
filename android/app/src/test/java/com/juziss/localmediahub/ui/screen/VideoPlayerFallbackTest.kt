package com.juziss.localmediahub.ui.screen

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 2026-09-03-android-transcode-fallback: the auto-retry decision must
 * fire ONLY for codec-level failures on remote streams, at most once, and
 * never for streams that are already transcoded.
 */
class VideoPlayerFallbackTest {

    @Test
    fun codecCapabilityErrorTriggersFallback() {
        assertTrue(
            shouldAutoFallbackToTranscode(
                isTranscoding = false,
                alreadyAttempted = false,
                isLocalUri = false,
                errorCode = PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            )
        )
    }

    @Test
    fun unsupportedCodecErrorTriggersFallback() {
        assertTrue(
            shouldAutoFallbackToTranscode(
                isTranscoding = false,
                alreadyAttempted = false,
                isLocalUri = false,
                errorCode = PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            )
        )
    }

    @Test
    fun unsupportedContainerErrorTriggersFallback() {
        assertTrue(
            shouldAutoFallbackToTranscode(
                isTranscoding = false,
                alreadyAttempted = false,
                isLocalUri = false,
                errorCode = PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            )
        )
    }

    @Test
    fun alreadyTranscodingNeverRetries() {
        for (code in intArrayOf(
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        )) {
            assertFalse(
                shouldAutoFallbackToTranscode(
                    isTranscoding = true,
                    alreadyAttempted = false,
                    isLocalUri = false,
                    errorCode = code,
                )
            )
        }
    }

    @Test
    fun secondAttemptNeverRetries() {
        assertFalse(
            shouldAutoFallbackToTranscode(
                isTranscoding = false,
                alreadyAttempted = true,
                isLocalUri = false,
                errorCode = PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            )
        )
    }

    @Test
    fun localStreamsNeverRetry() {
        assertFalse(
            shouldAutoFallbackToTranscode(
                isTranscoding = false,
                alreadyAttempted = false,
                isLocalUri = true,
                errorCode = PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            )
        )
    }

    @Test
    fun networkAndGenericErrorsDoNotRetry() {
        for (code in intArrayOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_UNSPECIFIED,
            PlaybackException.ERROR_CODE_REMOTE_ERROR,
        )) {
            assertFalse(
                shouldAutoFallbackToTranscode(
                    isTranscoding = false,
                    alreadyAttempted = false,
                    isLocalUri = false,
                    errorCode = code,
                )
            )
        }
    }

    @Test
    fun localUriDetection() {
        assertTrue(isLocalStreamUri("file:///storage/emulated/0/a.mp4"))
        assertTrue(isLocalStreamUri("content://media/external/video/1"))
        assertTrue(isLocalStreamUri("android.resource://pkg/raw/x"))
        assertFalse(isLocalStreamUri("http://192.168.1.2:8000/api/v1/media/stream?path=a"))
    }
}
