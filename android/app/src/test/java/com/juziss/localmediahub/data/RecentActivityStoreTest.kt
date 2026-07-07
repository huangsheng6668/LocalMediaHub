package com.juziss.localmediahub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentActivityStoreTest {

    @Test
    fun `deriveLocationTitle prefers fallback when provided`() {
        assertEquals(
            "Anime",
            deriveLocationTitle("""F:\Media\Anime""", "Anime"),
        )
    }

    @Test
    fun `deriveLocationTitle falls back to last path segment`() {
        assertEquals(
            "Anime",
            deriveLocationTitle("""F:\Media\Anime"""),
        )
    }

    @Test
    fun `mergeRecentMedia keeps newest entry first and removes duplicates`() {
        val oldFile = MediaFile("one.mp4", "F:/Media/one.mp4", "F:/Media/one.mp4", 1, "", "video", "mp4")
        val newFile = MediaFile("two.mp4", "F:/Media/two.mp4", "F:/Media/two.mp4", 1, "", "video", "mp4")

        val existing = listOf(
            RecentMediaEntry(file = oldFile, isSystemBrowse = false, openedAt = 100),
            RecentMediaEntry(file = newFile, isSystemBrowse = false, openedAt = 50),
        )

        val merged = mergeRecentMedia(
            existing = existing,
            incoming = RecentMediaEntry(file = newFile, isSystemBrowse = false, openedAt = 200),
        )

        assertEquals(listOf("two.mp4", "one.mp4"), merged.map { it.file.name })
        assertEquals(listOf(200L, 100L), merged.map { it.openedAt })
    }

    @Test
    fun `isValidProgress rejects sub-threshold positions and invalid durations`() {
        assertFalse(isValidProgress(positionMs = 5_000L, durationMs = 120_000L))
        assertTrue(isValidProgress(positionMs = 10_000L, durationMs = 120_000L))
        assertFalse(isValidProgress(positionMs = 30_000L, durationMs = 0L))
        assertFalse(isValidProgress(positionMs = 30_000L, durationMs = -1L))
    }

    @Test
    fun `isCompleted treats positions at or above 95 percent as completed`() {
        assertFalse(isCompleted(positionMs = 94_999L, durationMs = 100_000L))
        assertTrue(isCompleted(positionMs = 95_000L, durationMs = 100_000L))
        assertTrue(isCompleted(positionMs = 99_999L, durationMs = 100_000L))
        // 无效时长不算完成
        assertFalse(isCompleted(positionMs = 95_000L, durationMs = 0L))
    }

    @Test
    fun `shouldFocusRestart only true at or above 98 percent`() {
        assertFalse(shouldFocusRestart(positionMs = 97_999L, durationMs = 100_000L))
        assertTrue(shouldFocusRestart(positionMs = 98_000L, durationMs = 100_000L))
        assertTrue(shouldFocusRestart(positionMs = 100_000L, durationMs = 100_000L))
        assertFalse(shouldFocusRestart(positionMs = 98_000L, durationMs = 0L))
    }

    @Test
    fun `isValidProgress allows completed-level positions to be saved`() {
        // 与旧 shouldKeepPlaybackProgress 不同,新逻辑允许 95% 以上的进度被保存。
        // savePlaybackProgress 改用 isValidProgress 后,这一行为变化通过此测试锁定。
        assertTrue(isValidProgress(positionMs = 95_000L, durationMs = 100_000L))
        assertTrue(isValidProgress(positionMs = 100_000L, durationMs = 100_000L))
    }

    @Test
    fun `mergePlaybackProgress keeps newest unique entries`() {
        val oldFile = MediaFile("one.mp4", "F:/Media/one.mp4", "F:/Media/one.mp4", 1, "", "video", "mp4")
        val newFile = MediaFile("two.mp4", "F:/Media/two.mp4", "F:/Media/two.mp4", 1, "", "video", "mp4")

        val existing = listOf(
            PlaybackProgressEntry(oldFile, false, 10_000L, 100_000L, 100L),
            PlaybackProgressEntry(newFile, false, 20_000L, 100_000L, 50L),
        )

        val merged = mergePlaybackProgress(
            existing = existing,
            incoming = PlaybackProgressEntry(newFile, false, 40_000L, 100_000L, 200L),
        )

        assertEquals(listOf("two.mp4", "one.mp4"), merged.map { it.file.name })
        assertEquals(listOf(40_000L, 10_000L), merged.map { it.positionMs })
    }
}
