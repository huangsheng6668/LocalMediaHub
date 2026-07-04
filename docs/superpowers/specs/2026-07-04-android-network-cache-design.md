# Android 网络缓存 + 主动清理设计（Round 12）

- **日期**: 2026-07-04
- **范围**: Android 客户端 Coil `ImageLoader` 配置 + 新增启动时缓存清理
- **策略**: A — 启用服务端 Cache-Control + 启动时按访问时间清理
- **状态**: 待评审
- **前置**: Round 3（服务端 7 个媒体端点已加 `Cache-Control: public, max-age=86400`）；Round 4（Coil memoryCache 25%、diskCache 100MB 已落地）

---

## 1. 背景与动机

Round 3 给服务端 7 个媒体端点（缩略图/原图）加了 `Cache-Control: public, max-age=86400`，期望惠及所有客户端。但 Android 端 Coil 当前配置 `respectCacheHeaders(false)`，**主动屏蔽**了这个头——意味着：

- 缩略图/原图每次都走 Coil 自己的 diskCache（100MB LRU），不做 HTTP freshness 重验证。
- 源文件改动后，1 天窗口期内 Android 看不到更新（max-age 头被忽略，行为退化为"Coil diskCache 没淘汰就用旧的"）。
- 没有"主动清理"——只靠 maxSizeBytes LRU 兜底，100MB 上限在某些场景下用户感觉"被占满"且不透明。

Round 12 解决两件事：
1. **接住服务端缓存头**：`respectCacheHeaders(true)`，让 max-age 与 `Last-Modified` 重验证生效。
2. **加主动清理层**：应用启动时按访问时间（`File.lastModified()`）删除超过 14 天未访问的缓存条目，在 maxSizeBytes LRU 之上提供确定性空间释放。

### 1.1 范围明确

- ✅ 仅图片缓存（缩略图 + 原图，由 Coil diskCache 承载）
- ❌ OkHttp JSON 响应缓存（留作后续轮次）
- ❌ 视频 stream 缓存（ExoPlayer 自管）
- ❌ WorkManager 后台定期清理（启动触发对个人应用足够）
- ❌ 服务端任何改动（Round 3 已就绪）

---

## 2. 目标与非目标

### 目标
1. **Coil `respectCacheHeaders(true)`**：服务端 `Cache-Control: public, max-age=86400` 生效。
2. **启动时主动清理**：应用启动时遍历 Coil diskCache 目录，删除 `lastModified > 14 天` 的文件。
3. **纯函数 + 协程**：清理逻辑抽成 `util/CacheCleanup.kt`，便于 JVM 单测。
4. **保持 100MB maxSizeBytes**：作为 LRU 兜底，不调整。
5. **不引入新依赖**：不用 WorkManager、不用 Hilt 模块（简单启动协程即可）。

### 非目标
- ❌ OkHttp Cache / Retrofit 缓存配置
- ❌ 视频 stream 缓存
- ❌ WorkManager 后台调度
- ❌ 用户可配置的清理参数（YAGNI，硬编码 14 天）
- ❌ 服务端改动
- ❌ Coil 升级

---

## 3. 架构与文件组织

### 3.1 整体架构

```
LocalMediaHubApplication.onCreate()
  ├── newImageLoader()                      // 现有，改一行：respectCacheHeaders(true)
  └── CoroutineScope(Dispatchers.IO).launch {
        CacheCleanup.cleanupOldEntries(       // 新增
            cacheDir = cacheDir.resolve("coil"),
            maxAgeDays = 14,
        )
      }
```

### 3.2 改动文件清单

| 文件 | 改动类型 | 说明 |
|---|---|---|
| `android/app/src/main/java/com/juziss/localmediahub/LocalMediaHubApplication.kt` | 改 | (1) `respectCacheHeaders(false)` → `true`；(2) `onCreate` 中 launch 清理协程 |
| `android/app/src/main/java/com/juziss/localmediahub/util/CacheCleanup.kt` | **新增** | 纯函数 `cleanupOldEntries(cacheDir, maxAgeDays): CleanupStats` |
| `android/app/src/test/java/com/juziss/localmediahub/util/CacheCleanupTest.kt` | **新增** | JVM 单测：4 个用例覆盖边界 |

---

## 4. 组件实现

### 4.1 `util/CacheCleanup.kt`（新增）

```kotlin
package com.juziss.localmediahub.util

import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "CacheCleanup"

data class CleanupStats(
    val deletedCount: Int,
    val freedBytes: Long,
    val scannedCount: Int,
)

/**
 * Delete cache entries whose last-modified time is older than [maxAgeDays].
 *
 * Coil DiskCache uses file mtime as the LRU ordering key (writes/reads update it),
 * so [File.lastModified] serves as a proxy for "last access time."
 *
 * Runs on Dispatchers.IO. Recursively walks [cacheDir]. Best-effort: any individual
 * file delete failure is logged and skipped, not propagated.
 */
suspend fun cleanupOldEntries(
    cacheDir: File,
    maxAgeDays: Int,
): CleanupStats = withContext(Dispatchers.IO) {
    if (!cacheDir.isDirectory) {
        return@withContext CleanupStats(0, 0, 0)
    }

    val thresholdMs = System.currentTimeMillis() - maxAgeDays.toLong() * 24 * 60 * 60 * 1000L
    var scanned = 0
    var deleted = 0
    var freed = 0L

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
                    Log.w(TAG, "Failed to delete ${file.absolutePath}")
                }
            }
        }
    }

    // 清理空的子目录（Coil 内部按 hash 分桶，可能产生空目录）
    cacheDir.walkBottomUp().filter { it.isDirectory && it != cacheDir && it.listFiles()?.isEmpty() == true }
        .forEach { it.delete() }

    Log.i(TAG, "Cleanup: scanned=$scanned, deleted=$deleted, freed=${freed / 1024}KB")
    CleanupStats(deleted, freed, scanned)
}
```

### 4.2 `LocalMediaHubApplication.kt` 改动

```kotlin
// 现有：
.respectCacheHeaders(false)

// 改为：
.respectCacheHeaders(true)
```

`onCreate` 加启动清理（新增方法）：

```kotlin
override fun onCreate() {
    super.onCreate()
    // 启动时清理 14 天未访问的 Coil 磁盘缓存条目。
    // 协程作用域跟随 Application 生命周期；Dispatchers.IO 保证不阻塞主线程。
    CoroutineScope(Dispatchers.IO).launch {
        val cacheDir = this@LocalMediaHubApplication.cacheDir.resolve("coil")
        cleanupOldEntries(cacheDir, maxAgeDays = 14)
    }
}
```

新增 import：
- `import kotlinx.coroutines.CoroutineScope`
- `import kotlinx.coroutines.Dispatchers`
- `import kotlinx.coroutines.launch`
- `import com.juziss.localmediahub.util.cleanupOldEntries`

> **协程作用域选择：** 不用 `GlobalScope`（已 deprecated），用临时 `CoroutineScope(Dispatchers.IO)`——清理任务幂等、可中断，App 进程被杀也无所谓。如未来需要结构化取消，再注入 `@ApplicationScope CoroutineScope`（Round 4 已建，但本轮不强制接入）。

### 4.3 `respectCacheHeaders(true)` 行为变化

| 时刻 |旧行为 | 新行为 |
|---|---|---|
| 缩略图首次访问 | 下载 + 写 diskCache | 下载 + 写 diskCache（不变） |
| 1 天内再次访问 | 命中 diskCache（无重验证） | 命中 diskCache（无重验证）——同 |
| 1 天后访问 | 仍命中 diskCache（无重验证） | Coil 发 `If-Modified-Since` 重验证；304 不重传、200 拉新 |
| 源文件改动后 | 1 天内看不到更新 | max-age 过期或 modtime 变 → 看到新图 |

---

## 5. 测试

### 5.1 JVM 单测（`CacheCleanupTest.kt`）

```kotlin
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
        oldFile.setLastModified(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000) // 30 days ago

        val stats = cleanupOldEntries(tmpDir, maxAgeDays = 14)

        assertEquals(1, stats.deletedCount)
        assertEquals(100L, stats.freedBytes)
        assertEquals(1, stats.scannedCount)
        assertFalse(oldFile.exists())
    }

    @Test fun `keeps recent files`() = runTest {
        val recentFile = File(tmpDir, "recent.jpg").apply { writeBytes(ByteArray(100)) }
        recentFile.setLastModified(System.currentTimeMillis() - 1L * 24 * 60 * 60 * 1000) // 1 day ago

        val stats = cleanupOldEntries(tmpDir, maxAgeDays = 14)

        assertEquals(0, stats.deletedCount)
        assertTrue(recentFile.exists())
    }

    @Test fun `nonexistent directory returns zero stats`() = runTest {
        val stats = cleanupOldEntries(File(tmpDir, "does-not-exist"), maxAgeDays = 14)
        assertEquals(CleanupStats(0, 0, 0), stats)
    }

    @Test fun `recurses into subdirectories and cleans empty dirs`() = runTest {
        val subDir = File(tmpDir, "sub").apply { mkdirs() }
        val oldFile = File(subDir, "old.jpg").apply {
            writeBytes(ByteArray(50))
            setLastModified(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        }

        val stats = cleanupOldEntries(tmpDir, maxAgeDays = 14)

        assertEquals(1, stats.deletedCount)
        assertFalse(oldFile.exists())
        // 空的 sub 目录应被清理
        assertFalse(subDir.exists())
    }
}
```

### 5.2 Robolectric 集成测试（可选）

`LocalMediaHubApplicationTest`：mock `cacheDir/coil/` 含若干文件，验证 `onCreate` 后被清理。**本轮跳过**——Coil DiskCache 路径依赖 Android Context，单测层覆盖已足够。

### 5.3 真机/模拟器手工回归

- 浏览网格 → 缩略图正常加载、秒开。
- 关 app、改服务端源文件、再开 → 看到新图（max-age 过期或 modtime 变）。
- `dumpsys diskstats` 或 `ls -lh cache/coil/` 看缓存大小可控。
- 启动日志含 `CacheCleanup: scanned=X, deleted=Y, freed=ZKB`。

---

## 6. 实现顺序与提交策略

按内聚度分次提交、每次 `./gradlew testDebugUnitTest assembleDebug`：

1. **新增 `util/CacheCleanup.kt` + 单测**：纯函数 + 4 个 JVM 测试用例。
2. **改 `LocalMediaHubApplication.kt`**：respectCacheHeaders=true + onCreate 启动清理。
3. **真机回归**：一次性验证。

---

## 7. 已决策点

| 决策 | 选择 | 理由 |
|---|---|---|
| 范围 | 仅图片缓存 | 用户明确；OkHttp JSON 缓存留后续 |
| respectCacheHeaders | false → true | 接住 Round 3 服务端 Cache-Control |
| maxSizeBytes | 保持 100MB | Round 4 已设置，作为 LRU 兜底足够 |
| 主动清理 | 启动时触发 + 按访问时间 | 用户明确要求 |
| 清理阈值 | 14 天 | 个人应用合理窗口；硬编码 YAGNI |
| 调度器 | Dispatchers.IO + 临时 CoroutineScope | 启动一次性任务，无需 WorkManager |
| 测试 | JVM 单测（4 用例） | 纯函数易测；Robolectric 集成测试跳过 |

---

## 8. 非目标（再次明确）

- ❌ OkHttp Cache / Retrofit 缓存配置
- ❌ 视频 stream 缓存（ExoPlayer 自管）
- ❌ WorkManager 后台调度
- ❌ 用户可配置清理参数
- ❌ 服务端改动
- ❌ Coil 升级

---

## 9. 后续轮次（不在本 spec，仅备忘）

- **OkHttp JSON 缓存**：4 个 OkHttpClient 实例（MediaRepository / RetrofitClient / VideoPlayerScreen / ConnectionViewModel）合并为 Hilt 单例 + 共享 Cache，给 `/folders`、`/browse`、`/search` 等元数据端点加缓存头。
- **服务端 JSON 端点 Cache-Control**：本轮只覆盖媒体端点，JSON 端点未加（如 `/folders` 列表相对静态，可加 `max-age=300`）。
- **WorkManager 周期清理**：若启动清理不足以控空间（如用户长时间不重启 app），加 PeriodicWorkRequest。
- **Coil DiskCache API 升级到 v3**：v3 提供原生"按访问时间淘汰"接口，可省略本轮手写清理。
