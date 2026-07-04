package com.juziss.localmediahub.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.file.Files
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class CacheCleanupTest {
    private lateinit var tmpDir: File

    @Before fun setup() {
        tmpDir = Files.createTempDirectory("cache_cleanup_test").toFile()
    }

    @After fun teardown() {
        tmpDir.deleteRecursively()
    }

    @Test fun `deletes files older than threshold`() = runTest {
        val oldFile = File(tmpDir, "old.jpg").apply { writeBytes(ByteArray(100)) }
        oldFile.setLastModified(System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000) // 60 days ago

        val stats = cleanupOldEntries(
            tmpDir,
            maxAgeDays = 30,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertEquals(1, stats.deletedCount)
        assertEquals(100L, stats.freedBytes)
        assertEquals(1, stats.scannedCount)
        assertEquals(0, stats.failedCount)
        assertFalse(oldFile.exists())
    }

    @Test fun `keeps recent files`() = runTest {
        val recentFile = File(tmpDir, "recent.jpg").apply { writeBytes(ByteArray(100)) }
        recentFile.setLastModified(System.currentTimeMillis() - 1L * 24 * 60 * 60 * 1000) // 1 day ago

        val stats = cleanupOldEntries(
            tmpDir,
            maxAgeDays = 30,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertEquals(0, stats.deletedCount)
        assertEquals(0, stats.failedCount)
        assertTrue(recentFile.exists())
    }

    @Test fun `nonexistent directory returns zero stats`() = runTest {
        val stats = cleanupOldEntries(
            File(tmpDir, "does-not-exist"),
            maxAgeDays = 30,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        assertEquals(CleanupStats(0, 0, 0, 0), stats)
    }

    @Test fun `recurses into subdirectories and cleans empty dirs`() = runTest {
        val subDir = File(tmpDir, "sub").apply { mkdirs() }
        val oldFile = File(subDir, "old.jpg").apply {
            writeBytes(ByteArray(50))
            setLastModified(System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000)
        }

        val stats = cleanupOldEntries(
            tmpDir,
            maxAgeDays = 30,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertEquals(1, stats.deletedCount)
        assertEquals(50L, stats.freedBytes)
        assertFalse(oldFile.exists())
        // Empty sub directory should be cleaned up
        assertFalse(subDir.exists())
    }
}
