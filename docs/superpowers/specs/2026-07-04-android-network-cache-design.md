# Android 网络缓存 + 主动清理设计（Round 12）

- **日期**: 2026-07-04
- **范围**: Android 客户端 Coil `ImageLoader` 配置 + 新增启动时缓存清理
- **策略**: A — 启用服务端 Cache-Control + 启动时按写入时间清理
- **状态**: 已评审 / 已修订
- **前置**: Round 3（服务端 7 个媒体端点已加 `Cache-Control: public, max-age=86400`）；Round 4（Coil memoryCache 25%、diskCache 100MB 已落地）

---

## 1. 背景与动机

Round 3 给服务端 7 个媒体端点（缩略图/原图）加了 `Cache-Control: public, max-age=86400`，期望惠及所有客户端。但 Android 端 Coil 当前配置 `respectCacheHeaders(false)`，**主动屏蔽**了这个头——意味着：

- 缩略图/原图每次都走 Coil 自己的 diskCache（100MB LRU），不做 HTTP freshness 重验证。
- 源文件改动后，1 天窗口期内 Android 看不到更新（max-age 头被忽略，行为退化为"Coil diskCache 没淘汰就用旧的"）。
- 没有"主动清理"——只靠 maxSizeBytes LRU 兜底，100MB 上限在某些场景下用户感觉"被占满"且不透明。

Round 12 解决两件事：
1. **接住服务端缓存头**：`respectCacheHeaders(true)`，让 max-age 与 `Last-Modified` 重验证生效。
2. **加主动清理层**：应用启动时按文件写入时间（`File.lastModified()`）删除超过 30 天未更新的缓存条目，在 maxSizeBytes LRU 之上提供确定性空间释放。

> **⚠️ mtime 限制**：Coil 2.x 底层 `DiskLruCache` 读取缓存时**不更新**文件 mtime，仅写入（首次下载或重验证更新）时设置。因此 `lastModified()` 反映的是“最后写入时间”而非“最后访问时间”。阈值设为 30 天以降低误删频繁访问但长期未更新的缩略图的概率。100MB LRU 淘汰仍是空间控制的主力手段。

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
2. **启动时主动清理**：应用启动时遍历 Coil diskCache 目录，删除 `lastModified > 30 天` 的文件。
3. **纯函数 + 协程**：清理逻辑抽成 `util/CacheCleanup.kt`，便于 JVM 单测。
4. **保持 100MB maxSizeBytes**：作为 LRU 兜底，不调整。
5. **不引入新依赖**：不用 WorkManager、不用 Hilt 模块（简单启动协程即可）。

### 非目标
- ❌ OkHttp Cache / Retrofit 缓存配置
- ❌ 视频 stream 缓存
- ❌ WorkManager 后台调度
- ❌ 用户可配置的清理参数（YAGNI，硬编码 30 天）
- ❌ 服务端改动
- ❌ Coil 升级

---

## 3. 架构与文件组织

### 3.1 整体架构

```
LocalMediaHubApplication.onCreate()
  ├── newImageLoader()                      // 现有，改一行：respectCacheHeaders(true)
  └── applicationScope.launch {              // 新增，SupervisorJob 绑定
        CacheCleanup.cleanupOldEntries(
            cacheDir = cacheDir.resolve(DISK_CACHE_DIR),
            maxAgeDays = 30,
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
 * “最后写入时间”而非“最后访问时间”。阈值建议 ≥ 30 天以降低误删活跃缓存的概率。
 *
 * **journal 绕过**：本函数直接操作文件系统而非通过 DiskLruCache API 删除条目，
 * 会导致 journal 与实际文件不一致。DiskLruCache 下次启动时会自动重建 journal（自愈），
 * 但可能产生一次性的启动延迟。应确保本函数在 ImageLoader 初始化之前完成。
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

    // 清理空的子目录（Coil 内部按 hash 分桶，可能产生空目录）
    cacheDir.walkBottomUp().filter { it.isDirectory && it != cacheDir && it.listFiles()?.isEmpty() == true }
        .forEach { it.delete() }

    val elapsedMs = System.currentTimeMillis() - startMs
    Log.i(TAG, "Cleanup($cacheDir): scanned=$scanned, deleted=$deleted, failed=$failed, freed=${freed / 1024}KB, elapsed=${elapsedMs}ms")
    CleanupStats(deleted, freed, scanned, failed)
}
```

### 4.2 `LocalMediaHubApplication.kt` 改动

```kotlin
// 现有：
.respectCacheHeaders(false)

// 改为：
.respectCacheHeaders(true)
```

`onCreate` 加启动清理（新增常量 + scope + 方法）：

```kotlin
companion object {
    /** Coil diskCache 目录名，与 newImageLoader() 中保持一致。 */
    const val DISK_CACHE_DIR = "coil"
}

/** 持有 SupervisorJob 引用，支持结构化取消。 */
private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

override fun onCreate() {
    super.onCreate()
    // 启动时清理 30 天未更新的 Coil 磁盘缓存条目。
    // applicationScope 绑定 SupervisorJob；Dispatchers.IO 保证不阻塞主线程。
    applicationScope.launch {
        val cacheDir = this@LocalMediaHubApplication.cacheDir.resolve(DISK_CACHE_DIR)
        cleanupOldEntries(cacheDir, maxAgeDays = 30)
    }
}
```

新增 import：
- `import kotlinx.coroutines.CoroutineScope`
- `import kotlinx.coroutines.Dispatchers`
- `import kotlinx.coroutines.SupervisorJob`
- `import kotlinx.coroutines.launch`
- `import com.juziss.localmediahub.util.cleanupOldEntries`

> **协程作用域选择：** 不用 `GlobalScope`（标注 `@DelicateCoroutinesApi`，非 deprecated），改用持有 `SupervisorJob` 引用的 `applicationScope`——既避免匿名 scope 引用丢失，又支持未来结构化取消。清理任务本身幂等、可中断，App 进程被杀也无影响。

### 4.3 `respectCacheHeaders(true)` 行为变化

| 时刻 |旧行为 | 新行为 |
|---|---|---|
| 缩略图首次访问 | 下载 + 写 diskCache | 下载 + 写 diskCache（不变） |
| 1 天内再次访问 | 命中 diskCache（无重验证） | 命中 diskCache（无重验证）——同 |
| 1 天后访问 | 仍命中 diskCache（无重验证） | max-age 过期 → Coil 发 `If-Modified-Since` 重验证†；源文件未改 304 不重传、已改 200 拉新 |
| 源文件改动后 | 1 天内看不到更新 | max-age 过期后重验证 → 服务端返回新文件 |

> † **304 机制说明**：7 个缩略图/原图端点均使用 Echo `c.File()` → 内部调用 Go `http.ServeContent()`，由 **Go 标准库隐式**设置 `Last-Modified`（取自文件 modtime）并处理 `If-Modified-Since` → 304。项目代码中没有显式的 `Last-Modified` / 304 逻辑。如果未来 handler 改用 `c.Blob()` 或自定义响应方式，304 会失效——届时需显式实现。

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
        oldFile.setLastModified(System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000) // 60 days ago

        val stats = cleanupOldEntries(tmpDir, maxAgeDays = 30, dispatcher = UnconfinedTestDispatcher(testScheduler))

        assertEquals(1, stats.deletedCount)
        assertEquals(100L, stats.freedBytes)
        assertEquals(1, stats.scannedCount)
        assertEquals(0, stats.failedCount)
        assertFalse(oldFile.exists())
    }

    @Test fun `keeps recent files`() = runTest {
        val recentFile = File(tmpDir, "recent.jpg").apply { writeBytes(ByteArray(100)) }
        recentFile.setLastModified(System.currentTimeMillis() - 1L * 24 * 60 * 60 * 1000) // 1 day ago

        val stats = cleanupOldEntries(tmpDir, maxAgeDays = 30, dispatcher = UnconfinedTestDispatcher(testScheduler))

        assertEquals(0, stats.deletedCount)
        assertTrue(recentFile.exists())
    }

    @Test fun `nonexistent directory returns zero stats`() = runTest {
        val stats = cleanupOldEntries(File(tmpDir, "does-not-exist"), maxAgeDays = 30, dispatcher = UnconfinedTestDispatcher(testScheduler))
        assertEquals(CleanupStats(0, 0, 0, 0), stats)
    }

    @Test fun `recurses into subdirectories and cleans empty dirs`() = runTest {
        val subDir = File(tmpDir, "sub").apply { mkdirs() }
        val oldFile = File(subDir, "old.jpg").apply {
            writeBytes(ByteArray(50))
            setLastModified(System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000)
        }

        val stats = cleanupOldEntries(tmpDir, maxAgeDays = 30, dispatcher = UnconfinedTestDispatcher(testScheduler))

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
- 关 app、改服务端源文件、等 1 天（或改系统时间）再开 → max-age 过期后看到新图（304/200 取决于 modtime）。
- `dumpsys diskstats` 或 `ls -lh cache/coil/` 看缓存大小可控。
- 启动日志含 `CacheCleanup(<path>): scanned=X, deleted=Y, failed=Z, freed=WKB, elapsed=Nms`。

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
| 主动清理 | 启动时触发 + 按写入时间（mtime） | 用户要求；mtime ≠ 访问时间，已知限制见 §1 |
| 清理阈值 | 30 天 | 因 mtime 限制从 14 天上调至 30 天，降低误删概率 |
| 调度器 | Dispatchers.IO + `applicationScope`（SupervisorJob） | 持有引用可结构化取消，无需 WorkManager |
| 测试 | JVM 单测（4 用例 + 注入 TestDispatcher） | 纯函数易测；dispatcher 参数化避免逃逸 |
| 304 依赖 | Go `http.ServeContent()` 隐式提供 | 非项目显式实现，handler 改写方式时需注意 |

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
- **服务端显式 `Last-Modified` + 304**：当前 304 依赖 Go `http.ServeContent()` 隐式行为；如 handler 改用 `c.Blob()` 等方式则需显式实现 `Last-Modified` 头 + `If-Modified-Since` → 304 逻辑。
- **WorkManager 周期清理**：若启动清理不足以控空间（如用户长时间不重启 app），加 PeriodicWorkRequest。
- **Coil DiskCache API 升级到 v3**：v3 提供原生"按访问时间淘汰"接口，可省略本轮手写清理，也能从根本上解决 mtime ≠ 访问时间的问题。
- **Coil Interceptor touch mtime**：若阈值仍不够保守，可在 Coil Interceptor 中于 cache hit 时 `file.setLastModified(System.currentTimeMillis())` 使 mtime 真正反映访问时间（代价：每次 hit 增加 1 次 IO 写）。
