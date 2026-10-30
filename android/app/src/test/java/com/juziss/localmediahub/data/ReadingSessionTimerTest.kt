package com.juziss.localmediahub.data

import com.juziss.localmediahub.viewmodel.TextReaderViewModel
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
    fun `takeDelta preserves sub-second remainder across frequent polls`() {
        var t = 0L
        val timer = ReadingSessionTimer { t }
        timer.start()
        // Poll 1 at 900ms: 0 whole seconds, 900ms remainder preserved
        t = 900L
        assertEquals(0L, timer.takeDelta())
        // Poll 2 at 1800ms: 1 whole second, 800ms remainder preserved
        t = 1_800L
        assertEquals(1L, timer.takeDelta())
        // Poll 3 at 2700ms: 1 whole second (800ms + 900ms = 1700ms), 700ms remainder preserved
        t = 2_700L
        assertEquals(1L, timer.takeDelta())
        // Poll 4 at 3000ms: 1 whole second (700ms + 300ms = 1000ms), 0ms remainder
        t = 3_000L
        assertEquals(1L, timer.takeDelta())
    }

    @Test
    fun `clock backward skew does not produce negative delta`() {
        var t = 10_000L
        val timer = ReadingSessionTimer { t }
        timer.start()
        t = 5_000L // NTP clock rollback
        assertEquals(0L, timer.takeDelta())
        t = 8_000L // 3 seconds after rollback anchor
        assertEquals(3L, timer.takeDelta())
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

    @Test
    fun `resolveInitialChapterProgress preserves pendingResume offset on matching chapter`() {
        val pending = BookProgress(
            path = "/m/book.txt",
            chapterIndex = 3,
            blockIndex = 42,
            scrollOffsetPx = 180,
            lastReadAt = 1_000L,
        )
        val sameChapter = TextReaderViewModel.resolveInitialChapterProgress(
            bookPath = "/m/book.txt",
            chapterIndex = 3,
            pendingResume = pending,
            nowMs = 2_000L,
        )
        assertEquals(3, sameChapter.chapterIndex)
        assertEquals(42, sameChapter.blockIndex)
        assertEquals(180, sameChapter.scrollOffsetPx)

        val otherChapter = TextReaderViewModel.resolveInitialChapterProgress(
            bookPath = "/m/book.txt",
            chapterIndex = 4,
            pendingResume = pending,
            nowMs = 2_000L,
        )
        assertEquals(4, otherChapter.chapterIndex)
        assertEquals(0, otherChapter.blockIndex)
        assertEquals(0, otherChapter.scrollOffsetPx)
    }
}
