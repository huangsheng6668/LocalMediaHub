package com.juziss.localmediahub

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerGestureHudTest {
    @Test
    fun testFormatSeekSeconds() {
        val seconds = 10
        val text = "+${seconds}s"
        assertEquals("+10s", text)
    }

    @Test
    fun testCalculateVolumePercentage() {
        val currentVolume = 5
        val maxVolume = 15
        val percent = (currentVolume.toFloat() / maxVolume * 100).toInt()
        assertEquals(33, percent)
    }
}
