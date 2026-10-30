package com.juziss.localmediahub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadWorkerHelpersTest {

    @Test
    fun `NotificationThrottle suppresses rapid updates within 500ms window`() {
        var nowMs = 1_000L
        val throttle = NotificationThrottle(minIntervalMs = 500L, clock = { nowMs })

        // First progress update is allowed immediately
        assertTrue(throttle.shouldNotify(percent = 1))
        // Same percent within window -> suppressed
        assertFalse(throttle.shouldNotify(percent = 1))
        // Different percent within 500ms window -> suppressed
        nowMs = 1_200L
        assertFalse(throttle.shouldNotify(percent = 2))
        // At 1500ms (500ms elapsed) -> allowed
        nowMs = 1_500L
        assertTrue(throttle.shouldNotify(percent = 2))
        // 100% completion is always allowed immediately even within window
        nowMs = 1_600L
        assertTrue(throttle.shouldNotify(percent = 100))
        // Duplicate 100% is suppressed
        assertFalse(throttle.shouldNotify(percent = 100))
    }

    @Test
    fun `NotificationThrottle throttles indeterminate chunked downloads`() {
        var nowMs = 0L
        val throttle = NotificationThrottle(minIntervalMs = 500L, clock = { nowMs })

        assertTrue(throttle.shouldNotify())
        nowMs = 100L
        assertFalse(throttle.shouldNotify())
        nowMs = 499L
        assertFalse(throttle.shouldNotify())
        nowMs = 500L
        assertTrue(throttle.shouldNotify())
    }

    @Test
    fun `MediaMetadataIndex resolves duplicate basenames across subdirectories in O(1)`() {
        val ch1Page1 = MediaFile(
            name = "001.jpg",
            path = "/media/Manga/Ch1/001.jpg",
            relativePath = "Manga/Ch1/001.jpg",
            size = 1000L,
            modifiedTime = "2026-01-01",
            mediaType = "image",
            extension = ".jpg",
        )
        val ch2Page1 = MediaFile(
            name = "001.jpg",
            path = "/media/Manga/Ch2/001.jpg",
            relativePath = "Manga/Ch2/001.jpg",
            size = 2000L,
            modifiedTime = "2026-01-02",
            mediaType = "image",
            extension = ".jpg",
        )
        val readme = MediaFile(
            name = "readme.txt",
            path = "/media/Manga/readme.txt",
            relativePath = "Manga/readme.txt",
            size = 50L,
            modifiedTime = "2026-01-03",
            mediaType = "text",
            extension = ".txt",
        )

        val index = MediaMetadataIndex(
            files = listOf(ch1Page1, ch2Page1, readme),
            folderRelativePath = "Manga",
        )

        assertEquals(ch1Page1, index.match("Ch1/001.jpg"))
        assertEquals(ch2Page1, index.match("Ch2/001.jpg"))
        assertEquals(readme, index.match("readme.txt"))
        assertNull(index.match("nonexistent.png"))
    }
}
