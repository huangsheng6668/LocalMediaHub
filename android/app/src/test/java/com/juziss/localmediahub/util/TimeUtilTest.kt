package com.juziss.localmediahub.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeUtilTest {

    @Test
    fun `formatTime formats seconds under a minute as M_SS`() {
        assertEquals("0:05", formatTime(5_000L))
        assertEquals("0:59", formatTime(59_000L))
    }

    @Test
    fun `formatTime formats minutes under an hour as M_SS`() {
        assertEquals("1:00", formatTime(60_000L))
        assertEquals("12:34", formatTime(12 * 60_000L + 34_000L))
    }

    @Test
    fun `formatTime formats hours and above as H_MM_SS`() {
        assertEquals("1:00:00", formatTime(3_600_000L))
        assertEquals("1:02:03", formatTime(3_600_000L + 2 * 60_000L + 3_000L))
    }

    @Test
    fun `formatTime clamps negative values to zero`() {
        assertEquals("0:00", formatTime(-5_000L))
    }

    @Test
    fun `formatTime handles zero`() {
        assertEquals("0:00", formatTime(0L))
    }
}
