package com.juziss.localmediahub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingMathTest {
    @Test fun percentMidBook() {
        assertEquals(50.0, ReadingMath.percent(2, 5, 10, 5), 1e-9)
    }
    @Test fun percentZeroDivisorSafe() {
        assertEquals(0.0, ReadingMath.percent(0, 0, 0, 0), 1e-9)
    }
    @Test fun percentClampsAndRounds() {
        assertEquals(100.0, ReadingMath.percent(4, 10, 10, 5), 1e-9)
        assertEquals(33.3, ReadingMath.percent(1, 5, 15, 4), 1e-9)
        assertEquals(22.2, ReadingMath.percent(1, 5, 15, 6), 1e-9)
    }
    @Test fun finishedOnlyAtLastChapterEnd() {
        assertTrue(ReadingMath.isFinished(4, 5, true))
        assertFalse(ReadingMath.isFinished(4, 5, false))
        assertFalse(ReadingMath.isFinished(3, 5, true))
        assertFalse(ReadingMath.isFinished(0, 0, true))
    }
}
