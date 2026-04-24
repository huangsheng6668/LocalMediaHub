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
    fun `shouldKeepPlaybackProgress only keeps meaningful unfinished playback`() {
        assertFalse(shouldKeepPlaybackProgress(positionMs = 5_000L, durationMs = 120_000L))
        assertTrue(shouldKeepPlaybackProgress(positionMs = 30_000L, durationMs = 120_000L))
        assertFalse(shouldKeepPlaybackProgress(positionMs = 119_000L, durationMs = 120_000L))
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
