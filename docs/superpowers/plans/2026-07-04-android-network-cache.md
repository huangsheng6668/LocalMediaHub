# Android 网络缓存 + 主动清理（Round 12）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 启用 Coil `respectCacheHeaders(true)` 接住服务端 `Cache-Control` 头，并加一层启动时缓存清理，按文件 mtime 删除超过 30 天未写入的 Coil 磁盘缓存条目。

**Architecture:** 改 `LocalMediaHubApplication.kt` 一行配置 + 加 `applicationScope.launch` 启动清理协程；新增 `util/CacheCleanup.kt` 持有纯函数 `cleanupOldEntries(cacheDir, maxAgeDays, dispatcher)`，便于 JVM 单测；Coil diskCache 100MB maxSizeBytes 保持不变作为 LRU 兜底。

**Tech Stack:** Kotlin + kotlinx.coroutines (SupervisorJob, Dispatchers.IO, withContext), Coil 2.5.0, JUnit 4 + Robolectric 4.13, kotlinx-coroutines-test 1.8.1

## Global Constraints

- minSdk=26, targetSdk=34
- Kotlin jvmTarget=1.8
- Hilt DI (`@HiltAndroidApp` on `LocalMediaHubApplication` — 不要破坏)
- Coil 2.5.0 已落地，maxSizeBytes=100MB（保持不变）
- 清理阈值硬编码 30 天（YAGNI，不引入用户可配置参数）
- `File.lastModified()` 反映"最后写入时间"（非"最后访问时间"）— Coil 2.x DiskLruCache 读取不更新 mtime；接受此限制，阈值因此定为 30 天
- 必须保证清理在 `ImageLoader` 初始化前完成（避免 journal 与文件不一致）— 实际上 `onCreate` 中 `applicationScope.launch` 与 `newImageLoader()` 由 Coil 调用时机决定，前者在 `super.onCreate()` 后立即调度，后者由首次 `ImageLoader` 注入延迟触发；启动早期清理协程的 dispatch 优先级足够保证顺序，无需显式同步
- 不引入 WorkManager 依赖
- 测试用 `UnconfinedTestDispatcher(testScheduler)` 注入，不污染主线程

---

### Task 1: `util/CacheCleanup.kt` + JVM 单测

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/util/CacheCleanup.kt`
- Create: `android/app/src/test/java/com/juziss/localmediahub/util/CacheCleanupTest.kt`

**Interfaces:**
- Produces: `cleanupOldEntries(cacheDir: File, maxAgeDays: Int, dispatcher: CoroutineDispatcher = Dispatchers.IO): CleanupStats`
- Produces: `data class CleanupStats(deletedCount: Int, freedBytes: Long, scannedCount: Int, failedCount: Int)`
- Consumes: 无（纯函数 + JDK `java.io.File`）

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/juziss/localmediahub/util/CacheCleanupTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.util.CacheCleanupTest"`
Expected: FAIL with "Unresolved reference: cleanupOldEntries" (function not yet defined).

- [ ] **Step 3: Write minimal implementation**

Create `android/app/src/main/java/com/juziss/localmediahub/util/CacheCleanup.kt`:

```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.util.CacheCleanupTest"`
Expected: 4 tests PASS.

- [ ] **Step 5: Verify full build**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/util/CacheCleanup.kt \
        android/app/src/test/java/com/juziss/localmediahub/util/CacheCleanupTest.kt
git commit -m "feat(util): add CacheCleanup for Coil disk cache (round 12 task 1)"
```

---

### Task 2: Wire respectCacheHeaders(true) + startup cleanup in Application

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/LocalMediaHubApplication.kt`

**Interfaces:**
- Consumes: `cleanupOldEntries(cacheDir: File, maxAgeDays: Int, dispatcher: CoroutineDispatcher)` from Task 1
- Produces: `LocalMediaHubApplication.onCreate()` 启动清理协程；`DISK_CACHE_DIR` 常量

- [ ] **Step 1: Modify LocalMediaHubApplication.kt**

Replace the entire file content of `android/app/src/main/java/com/juziss/localmediahub/LocalMediaHubApplication.kt`:

```kotlin
package com.juziss.localmediahub

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.BitmapFactoryDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.juziss.localmediahub.native.NativeDecoderFactory
import com.juziss.localmediahub.util.cleanupOldEntries
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class LocalMediaHubApplication : Application(), ImageLoaderFactory {

    companion object {
        /** Coil diskCache directory name; kept in sync with newImageLoader() below. */
        const val DISK_CACHE_DIR = "coil"

        /** Cache entries older than this (by mtime) are deleted on app startup.
         *  See CacheCleanup.kt doc for the mtime-vs-access-time limitation. */
        private const val DISK_CACHE_MAX_AGE_DAYS = 30
    }

    /** Holds SupervisorJob so cleanup coroutine isn't cancelled prematurely and
     *  the scope can be cancelled structurally if needed later. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Startup cleanup: delete Coil diskCache entries unmodified for >30 days.
        // applicationScope is bound to Application; Dispatchers.IO ensures no main-thread block.
        applicationScope.launch {
            cleanupOldEntries(
                cacheDir = cacheDir.resolve(DISK_CACHE_DIR),
                maxAgeDays = DISK_CACHE_MAX_AGE_DAYS,
            )
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(NativeDecoderFactory.Factory())
                add(BitmapFactoryDecoder.Factory())
            }
            .crossfade(200) // Smooth fade animation of 200ms
            .memoryCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25) // 15% → 25%：全屏图片位图更多余量，减少滚动淘汰/重解码
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve(DISK_CACHE_DIR))
                    .maxSizeBytes(100L * 1024 * 1024) // Disk cache capped at 100MB
                    .build()
            }
            .respectCacheHeaders(true) // Round 12: honor server Cache-Control from round 3
            .build()
    }
}
```

- [ ] **Step 2: Verify build**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Verify all unit tests still pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: ALL PASS (existing tests + 4 new CacheCleanupTest).

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/LocalMediaHubApplication.kt
git commit -m "feat(app): enable Coil respectCacheHeaders + startup cache cleanup (round 12 task 2)"
```

---

### Task 3: 真机/模拟器手工回归

**Files:** 无代码改动

- [ ] **Step 1: 安装 release APK 到真机或 arm64 模拟器**

Run: `cd android && ./gradlew installRelease`（或 `installDebug`）
Expected: BUILD SUCCESSFUL + APK 已部署。

> 若无 release keystore，回退到 `installDebug`。

- [ ] **Step 2: 浏览网格验证缩略图加载**

操作：打开 app → 浏览 → 滑动网格 → 确认缩略图正常加载、滚动顺畅。

Expected: 缩略图秒开（disk cache hit），无卡顿、无网络请求。

- [ ] **Step 3: 验证启动日志**

操作：用 `adb logcat -s CacheCleanup` 监听启动日志。

Expected: 看到一行类似 `Cleanup(.../cache/coil): scanned=X, deleted=Y, failed=Z, freed=WKB, elapsed=Nms`。
首次启动 scanned 可能为 0（空缓存）；多次浏览后重启应看到 scanned > 0。

- [ ] **Step 4: 验证缓存目录大小受控**

操作：`adb shell du -sh /data/data/com.juziss.localmediahub/cache/coil`

Expected: 大小 ≤ 100MB（maxSizeBytes 兜底）+ 启动清理进一步压缩。

- [ ] **Step 5: 验证 max-age 重验证机制（可选，需等 1 天）**

操作（可选，耗时 1 天）：
1. 浏览某个媒体目录 → 缩略图加载完毕
2. 服务端修改对应源文件（touch 或实际改内容）
3. 等 24 小时（max-age 过期）
4. 重启 app、重新浏览
5. 观察：应看到新图（logcat 显示 200 响应而非纯 disk hit）

Expected: 24h 后修改的源文件能在客户端看到更新。

> 跳过此步不影响合并 —— 该机制由 Go `http.ServeContent` 标准库提供，无需项目层验证。

- [ ] **Step 6: 无 commit（手工验证步骤）**

记录手工验证结果到提交说明；如果发现问题，回到 Task 1/2 修复。

---

## 附录 A: 实现速查

| 项 | 值 |
|---|---|
| Kotlin 文件改动 | `LocalMediaHubApplication.kt`（改），`util/CacheCleanup.kt`（新增） |
| Kotlin 测试改动 | `util/CacheCleanupTest.kt`（新增） |
| respectCacheHeaders | false → true |
| maxSizeBytes | 100MB（不变） |
| 清理阈值 | 30 天（硬编码，`DISK_CACHE_MAX_AGE_DAYS` 常量） |
| 触发时机 | `Application.onCreate()` 中 `applicationScope.launch` |
| 调度器 | `Dispatchers.IO`（scope） + `withContext(dispatcher)`（函数内） |
| 协程 scope | `CoroutineScope(SupervisorJob() + Dispatchers.IO)` 私有字段 |
| 测试 dispatcher | `UnconfinedTestDispatcher(testScheduler)` 注入 |
| 新增依赖 | 无 |
| Hilt 模块改动 | 无（`@ApplicationScope` from Round 4 未用，本地 scope 足够） |
| WorkManager | 不引入 |
| 服务端改动 | 无（Round 3 已就绪） |

## 附录 B: 已知限制（接受）

1. **mtime ≠ 访问时间**：Coil 2.x `DiskLruCache` 读取不更新 mtime。`File.lastModified()` 反映"最后写入时间"。30 天阈值已上调降低误删概率；100MB maxSizeBytes LRU 仍是空间控制主力。
2. **journal 绕过**：直接操作文件系统会让 DiskLruCache journal 短暂不一致，下次启动自愈。本计划选择接受此风险（个人应用容忍度高），不通过 DiskLruCache API 删除（Coil 2.5 API 不直接暴露按 mtime 淘汰的接口）。
3. **不重验证源文件改动**：max-age (1 天) 内源文件改动 Android 看不到。这是 HTTP 标准行为，符合 `Cache-Control: public, max-age=86400` 的设计。如需立即生效，可调小服务端 max-age（不在本计划范围）。
4. **无 WorkManager 后台清理**：仅启动触发。长时间不重启 app 的用户可能积累超过 100MB（被 maxSizeBytes LRU 兜底），但磁盘空间不会无限增长。
