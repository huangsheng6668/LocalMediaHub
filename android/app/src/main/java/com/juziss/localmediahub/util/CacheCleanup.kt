package com.juziss.localmediahub.util

import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "CacheCleanup"

data class CleanupStats(
    val deletedCount: Int,
    val freedBytes: Long,
    val scannedCount: Int,
    val failedCount: Int,
)

/**
 * Delete cache entries whose last-modified time is older than [maxAgeDays].
 *
 * **mtime 限制**：Coil 2.x 底层 DiskLruCache 在读取（cache hit）时不更新文件 mtime，
 * 仅在写入（首次下载或重验证更新）时设置 mtime。因此 [File.lastModified] 反映的是
 * "最后写入时间"而非"最后访问时间"。阈值建议 ≥ 30 天以降低误删活跃缓存的概率。
 *
 * **journal 绕过**：本函数直接操作文件系统而非通过 DiskLruCache API 删除条目，
 * 会导致 journal 与实际文件不一致。DiskLruCache 下次启动时会自动重建 journal（自愈），
 * 但可能产生一次性的启动延迟。调用方应确保本函数在 ImageLoader 初始化之前完成。
 *
 * Runs on [dispatcher] (default Dispatchers.IO). Recursively walks [cacheDir].
 * Best-effort: any individual file delete failure is logged and skipped, not propagated.
 */
suspend fun cleanupOldEntries(
    cacheDir: File,
    maxAgeDays: Int,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): CleanupStats = withContext(dispatcher) {
    if (!cacheDir.isDirectory) {
        return@withContext CleanupStats(0, 0, 0, 0)
    }

    val startMs = System.currentTimeMillis()
    val thresholdMs = startMs - maxAgeDays.toLong() * 24 * 60 * 60 * 1000L
    var scanned = 0
    var deleted = 0
    var freed = 0L
    var failed = 0

    cacheDir.walkTopDown().forEach { file ->
        if (file == cacheDir) return@forEach
        if (file.isFile) {
            scanned++
            if (file.lastModified() < thresholdMs) {
                val size = file.length()
                if (file.delete()) {
                    deleted++
                    freed += size
                } else {
                    failed++
                    Log.w(TAG, "Failed to delete ${file.absolutePath}")
                }
            }
        }
    }

    // Clean up empty subdirectories (Coil buckets files by hash, may leave empty dirs)
    cacheDir.walkBottomUp()
        .filter { it.isDirectory && it != cacheDir && it.listFiles()?.isEmpty() == true }
        .forEach { it.delete() }

    val elapsedMs = System.currentTimeMillis() - startMs
    Log.i(TAG, "Cleanup($cacheDir): scanned=$scanned, deleted=$deleted, failed=$failed, freed=${freed / 1024}KB, elapsed=${elapsedMs}ms")
    CleanupStats(deleted, freed, scanned, failed)
}
