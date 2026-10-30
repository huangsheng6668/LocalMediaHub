package com.juziss.localmediahub.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the gesture IPC throttle (spec 2026-10-31-android-video-gesture-progress-perf). */
class GesturePercentGateTest {

    @Test
    fun firstValueIsEmitted() {
        assertTrue(GesturePercentGate().shouldEmit(42))
    }

    @Test
    fun repeatedValueIsSuppressed() {
        val gate = GesturePercentGate()
        assertTrue(gate.shouldEmit(42))
        assertFalse(gate.shouldEmit(42))
        assertFalse(gate.shouldEmit(42))
    }

    @Test
    fun changedValueIsEmitted() {
        val gate = GesturePercentGate()
        gate.shouldEmit(42)
        assertTrue(gate.shouldEmit(43))
        assertFalse(gate.shouldEmit(43))
    }

    @Test
    fun resetAllowsSameValueAgain() {
        val gate = GesturePercentGate()
        gate.shouldEmit(42)
        gate.reset()
        assertTrue(gate.shouldEmit(42))
    }

    @Test
    fun quantizeTruncatesLikeHudText() {
        assertEquals(45, quantizeGesturePercent(0.456f))
        assertEquals(100, quantizeGesturePercent(1f))
        assertEquals(0, quantizeGesturePercent(0f))
    }

    @Test
    fun quantizeClampsOutOfRange() {
        assertEquals(100, quantizeGesturePercent(1.4f))
        assertEquals(0, quantizeGesturePercent(-0.3f))
    }
}
