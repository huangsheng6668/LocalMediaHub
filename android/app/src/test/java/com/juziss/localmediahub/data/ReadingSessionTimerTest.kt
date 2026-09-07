package com.juziss.localmediahub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingSessionTimerTest {
    @Test
    fun `takeDelta accumulates and resets`() {
        var t = 0L
        val timer = ReadingSessionTimer { t }
        timer.start()
        t = 30_000
        assertEquals(30L, timer.takeDelta())
        t = 60_000
        timer.stop()
        assertEquals(30L, timer.takeDelta())
        assertEquals(0L, timer.takeDelta())
    }

    @Test
    fun `takeDelta while running keeps clock alive`() {
        var t = 0L
        val timer = ReadingSessionTimer { t }
        timer.start()
        t = 90_000
        assertEquals(90L, timer.takeDelta())
        t = 120_000
        assertEquals(30L, timer.takeDelta())
    }

    @Test
    fun `start and stop are idempotent`() {
        var t = 0L
        val timer = ReadingSessionTimer { t }
        timer.start()
        timer.start()
        t = 5_000
        timer.stop()
        timer.stop()
        assertEquals(5L, timer.takeDelta())
    }

    @Test
    fun `isRunning reflects session state`() {
        val timer = ReadingSessionTimer { 0L }
        assertFalse(timer.isRunning())
        timer.start()
        assertTrue(timer.isRunning())
        timer.stop()
        assertFalse(timer.isRunning())
    }
}
