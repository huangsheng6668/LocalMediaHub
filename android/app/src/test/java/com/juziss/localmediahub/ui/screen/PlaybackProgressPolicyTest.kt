package com.juziss.localmediahub.ui.screen

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the playback progress save gating (spec 2026-10-31-android-video-gesture-progress-perf). */
class PlaybackProgressPolicyTest {

    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    @Test
    fun unknownDurationNeverPersists() {
        // savePlaybackProgress DELETES the entry when duration <= 0.
        assertFalse(shouldPersistPlaybackProgress(60_000L, 0L, 0L))
        assertFalse(shouldPersistPlaybackProgress(60_000L, -1L, 0L))
    }

    @Test
    fun zeroPositionNeverPersists() {
        // Pre-seek startup reads 0 while the resume seek is still pending.
        assertFalse(shouldPersistPlaybackProgress(0L, 600_000L, 120_000L))
    }

    @Test
    fun unchangedPositionDoesNotRewrite() {
        assertFalse(shouldPersistPlaybackProgress(120_000L, 600_000L, 120_000L))
    }

    @Test
    fun smallDriftDoesNotRewrite() {
        assertFalse(shouldPersistPlaybackProgress(123_000L, 600_000L, 120_000L))
    }

    @Test
    fun forwardProgressPersistsAfterMinDelta() {
        assertTrue(shouldPersistPlaybackProgress(125_000L, 600_000L, 120_000L))
    }

    @Test
    fun backwardSeekPersistsAfterMinDelta() {
        assertTrue(shouldPersistPlaybackProgress(15_000L, 600_000L, 120_000L))
    }

    @Test
    fun holderRoundTripsThroughSaver() {
        val holder = PlaybackPositionHolder(93_000L)

        val saved = with(PlaybackPositionHolderSaver) { scope.save(holder) }

        assertEquals(93_000L, saved)
        assertEquals(93_000L, PlaybackPositionHolderSaver.restore(saved!!)?.value)
    }
}
