package com.juziss.localmediahub.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DownloadManagerTest {

    private fun newTempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "dlm-test-${System.nanoTime()}")
        require(dir.mkdirs()) { "failed to create temp dir" }
        return dir
    }

    @Test
    fun `isInside accepts a simple file inside dest dir`() {
        val dest = newTempDir()
        assertTrue(isInside(dest, File(dest, "video.mp4")))
    }

    @Test
    fun `isInside accepts a nested file inside dest dir`() {
        val dest = newTempDir()
        assertTrue(isInside(dest, File(dest, "sub/dir/video.mp4")))
    }

    @Test
    fun `isInside rejects parent traversal entry`() {
        val dest = newTempDir()
        assertFalse(isInside(dest, File(dest, "../escape.mp4")))
    }

    @Test
    fun `isInside rejects absolute path outside dest dir`() {
        val dest = newTempDir()
        assertFalse(isInside(dest, File("/etc/evil.mp4")))
    }

    @Test
    fun `isInside rejects the dest dir itself`() {
        val dest = newTempDir()
        assertFalse(isInside(dest, dest))
    }

    @Test
    fun unzipAbortsBeyondDeclaredBudget() {
        assertFalse(DownloadWorker.shouldAbortUnzip(extracted = 1_000, declared = 10_000))
        assertTrue(DownloadWorker.shouldAbortUnzip(extracted = 30_000, declared = 10_000)) // 3x declared
        assertTrue(DownloadWorker.shouldAbortUnzip(extracted = 5L * 1024 * 1024 * 1024, declared = 0)) // 绝对上限
    }
}
