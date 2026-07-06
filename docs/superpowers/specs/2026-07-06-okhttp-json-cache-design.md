# OkHttp JSON 缓存 + Hilt 单例设计（Round 17）

- **日期**: 2026-07-06
- **范围**: Android 客户端 OkHttpClient + 服务端 JSON 端点 Cache-Control
- **策略**: 3 commits — 服务端 3 档 TTL Cache-Control + Hilt 单例 OkHttpClient + 4 处调用点迁移
- **状态**: 待评审（已根据 2026-07-06 代码审计全面核对修正）
- **前置**: Round 3（服务端媒体端点已加 `Cache-Control: public, max-age=86400`）；Round 12（Coil diskCache 已落地）；Round 12 spec §9 follow-up

---

## 1. 背景与动机

Android 客户端当前有 **4 个 OkHttpClient 实例**（全是独立的 `OkHttpClient.Builder()` 裸调，零共享 Cache）：

1. `MediaRepository.http`（`by lazy { OkHttpClient.Builder()... }`，用于 JSON 请求、缩略图与视频流）
2. `RetrofitClient.buildRetrofit()`（每次 `initialize` 重新创建带 `logging-interceptor` 的 client）
3. `VideoPlayerScreen` → `ExoPlayerWrapper`（Line 139 在 `remember` 内裸调 `OkHttpClient.Builder()` 用于 ExoPlayer DataSource）
4. `ConnectionViewModel.startHttpScan()`（Line 295 裸调 `OkHttpClient.Builder()` 设 250ms 超时用于局域网 IP 扫描）

服务端 JSON 端点（`/folders`、`/browse`、`/search`、`/tags`、`/videos` 等）**全部无 Cache-Control 头**。媒体端点在 Round 3 已加（`setMediaCacheHeaders`），JSON 端点未加。

后果：
- 每次 `browseFolder`、`search`、`getTags` 都发起完整 HTTP 请求，**无客户端缓存**。
- 冷启动、重复浏览、分页等场景带宽浪费 + 等待时间。
- 4 个 OkHttpClient 实例无连接池共享，握手 SSL/TLS 与 TCP 连接未复用。

Round 17 解决：**统一 OkHttp 单例 + 服务端 JSON Cache-Control + 客户端 20MB 缓存目录**。

### 1.1 范围明确

- ✅ 4 个 OkHttpClient 创建点全部迁移至 Hilt `@Singleton OkHttpClient`（扫描 Client 使用 `sharedClient.newBuilder()` 共享连接池）
- ✅ 服务端为 17 个 GET JSON 端点加 3 档 TTL Cache-Control
- ✅ 客户端 20MB JSON 缓存目录（`cacheDir/okhttp/`）
- ❌ ETag + If-None-Match 304 机制（YAGNI）
- ❌ GET 请求预取（YAGNI）
- ❌ 离线模式（YAGNI）
- ❌ 任何客户端 UI 行为改动

---

## 2. 目标与非目标

### 目标
1. **C1 服务端 JSON Cache-Control**：3 档 TTL helper + 17 个 GET JSON 端点接入。
2. **C2 Android Hilt OkHttpClient 单例**：`OkHttpModule.kt` 提供 `@Singleton OkHttpClient + Cache(20MB)`。
3. **C3 调用点迁移**：4 处 `OkHttpClient` 创建点替换为 Hilt 注入或衍生 Client。
4. **零行为变化**：所有现有 JVM / Go 测试不回归。

### 非目标
- ❌ ETag + 304
- ❌ 预取
- ❌ 离线模式
- ❌ 任何客户端 UI 行为改动
- ❌ Rust native 层改动
- ❌ Coil 升级或替换

---

## 3. 架构与文件清单

### 3.1 文件改动矩阵（3 个 commit）

| Commit | 文件 | 改动类型 | 说明 |
|---|---|---|---|
| C1 | `server/internal/server/handler/handler.go` | 改 | 追加 3 档 JSON Cache-Control helper |
| C1 | `server/internal/server/handler/folders.go` | 改 | 6 个 GET 端点加 Cache-Control |
| C1 | `server/internal/server/handler/images.go` | 改 | 1 个 GET 端点加 Cache-Control (`GetImages`) |
| C1 | `server/internal/server/handler/videos.go` | 改 | 1 个 GET 端点加 Cache-Control (`GetVideos`) |
| C1 | `server/internal/server/handler/tags.go` | 改 | 5 个 GET 端点加 Cache-Control (`GetTags`, `GetTag`, `GetTaggedMedia`, `GetTaggedFiles`, `GetFileTags`) |
| C1 | `server/internal/server/handler/system.go` | 改 | 1 个 GET 端点加 Cache-Control (`GetDrives`) |
| C1 | `server/internal/server/handler/search.go` | 改 | 1 个 GET 端点加 Cache-Control (`Search`) |
| C1 | `server/internal/server/handler/media.go` | 改 | 1 个 GET 端点加 Cache-Control (`GetMediaFileTags`) |
| C1 | `server/internal/server/server_test.go` | 改 | 扩展断言 JSON 端点 Cache-Control |
| C2 | `android/.../network/OkHttpModule.kt` | **新增** | Hilt module 提供 `@Singleton OkHttpClient` 及 20MB Disk Cache |
| C3 | `android/.../data/MediaRepository.kt` | 改 | 注入 shared `OkHttpClient` 替换 `by lazy` 自建实例 |
| C3 | `android/.../network/RetrofitClient.kt` | 改 | 接收/使用 shared `OkHttpClient` |
| C3 | `android/.../ui/screen/VideoPlayerScreen.kt` | 改 | 通过 `VideoPlayerViewModel` 暴露 shared `OkHttpClient` 给 ExoPlayer |
| C3 | `android/.../viewmodel/VideoPlayerViewModel.kt` | **新增** | Hilt ViewModel 持有注入的 `OkHttpClient` |
| C3 | `android/.../viewmodel/ConnectionViewModel.kt` | 改 | 构造函数注入 `OkHttpClient`，`scanClient` 改用 `httpClient.newBuilder()` |

### 3.2 关键约束

- 服务端 Cache-Control 用 `private`（路径敏感信息），不用 `public`（Round 3 媒体用 public）
- `/system/browse` 不加缓存（路径敏感 + 用户输入）
- `/admin/scan` 不加缓存（POST 本来不缓存，无需动作）
- 客户端 20MB Cache 在 `cacheDir/okhttp/`（与 Coil `cacheDir/coil/` 分离）
- Hilt `@Singleton` OkHttpClient 通过 `ConnectionPool(15, 5min)` 共享
- HTTP 日志 interceptor 仅在 `BuildConfig.DEBUG` 时启用（release 节省内存，且 `logging-interceptor` 在 gradle 已依赖）
- `VideoPlayerScreen` Composable 无法直接 Hilt 注入 → 通过新建 `VideoPlayerViewModel` 中转
- `ConnectionViewModel` 使用 `httpClient.newBuilder().connectTimeout(250, MS)...` 衍生短超时 Client，复用连接池

---

## 4. 实现细节

### 4.1 C1: 服务端 3 档 TTL Cache-Control

**`server/internal/server/handler/handler.go` 追加：**

```go
// Cache-Control policy tiers for JSON responses.
// JSON responses are 'private' (not for CDN/proxy caching) because they
// contain path / file metadata specific to this server's filesystem.
// Contrast with media endpoints which use 'public, max-age=86400' (Round 3).
const (
    // cacheBrief: 60s — endpoints that change when scan/add/delete files.
    cacheBrief = "private, max-age=60"
    // cacheStandard: 300s — endpoints that change with tag operations / paging.
    cacheStandard = "private, max-age=300"
    // cacheStatic: 3600s — endpoints that almost never change.
    cacheStatic = "private, max-age=3600"
)

func setJsonCacheBrief(c echo.Context)    { c.Response().Header().Set("Cache-Control", cacheBrief) }
func setJsonCacheStandard(c echo.Context) { c.Response().Header().Set("Cache-Control", cacheStandard) }
func setJsonCacheStatic(c echo.Context)   { c.Response().Header().Set("Cache-Control", cacheStatic) }
```

**端点接入（共 17 个 GET JSON 端点）：**

| TTL | 端点 | Handler | 所在文件 |
|---|---|---|---|
| brief (60s) | `GET /api/v1/folders` | `GetFolders` | `folders.go` |
| brief (60s) | `GET /api/v1/folders/*` (browse) | `BrowseFolder` | `folders.go` |
| brief (60s) | `GET /api/v1/folders/*` (files) | `GetFolderFilesRecursive` | `folders.go` |
| brief (60s) | `GET /api/v1/folders/*` (subfolders) | `GetSubfolders` | `folders.go` |
| brief (60s) | `GET /api/v1/folders/*` (stats) | `GetFolderStats` | `folders.go` |
| brief (60s) | `GET /api/v1/folders/*` (thumbnails) | `GetFolderThumbnails` | `folders.go` |
| brief (60s) | `GET /api/v1/search` | `Search` | `search.go` |
| standard (300s) | `GET /api/v1/images` | `GetImages` | `images.go` |
| standard (300s) | `GET /api/v1/videos` | `GetVideos` | `videos.go` |
| standard (300s) | `GET /api/v1/tags` | `GetTags` | `tags.go` |
| standard (300s) | `GET /api/v1/tags/:tag_id` | `GetTag` | `tags.go` |
| standard (300s) | `GET /api/v1/tags/:tag_id/media` | `GetTaggedMedia` | `tags.go` |
| standard (300s) | `GET /api/v1/tags/:tag_id/files` | `GetTaggedFiles` | `tags.go` |
| standard (300s) | `GET /api/v1/tags/file-tags` | `GetFileTags` | `tags.go` |
| standard (300s) | `GET /api/v1/media/file-tags` | `GetMediaFileTags` | `media.go` |
| static (3600s) | `GET /api/v1/system/drives` | `GetDrives` | `system.go` |
| **不缓存** | `GET /api/v1/system/browse` | `SystemBrowse` | `system.go` |
| **不缓存** | `POST /api/v1/admin/scan/trigger` | `TriggerScan` | `admin.go` |

---

### 4.2 C2: Android Hilt OkHttpClient 单例

**`android/app/src/main/java/com/juziss/localmediahub/network/OkHttpModule.kt`（新增）：**

```kotlin
package com.juziss.localmediahub.network

import android.content.Context
import com.juziss.localmediahub.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Hilt module providing a singleton [OkHttpClient] + [Cache] shared across
 * MediaRepository, RetrofitClient, VideoPlayerScreen, and ConnectionViewModel.
 *
 * Cache lives under `cacheDir/okhttp/` (sibling to Coil's `cacheDir/coil/`)
 * and is capped at 20MB. TTL is controlled by server-side `Cache-Control`
 * headers added in Round 17 C1.
 */
@Module
@InstallIn(SingletonComponent::class)
object OkHttpModule {

    private const val CACHE_DIR = "okhttp"
    private const val CACHE_SIZE_BYTES = 20L * 1024 * 1024 // 20MB

    @Provides
    @Singleton
    fun provideOkHttpCache(@ApplicationContext context: Context): Cache =
        Cache(File(context.cacheDir, CACHE_DIR), CACHE_SIZE_BYTES)

    @Provides
    @Singleton
    fun provideOkHttpClient(cache: Cache): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .cache(cache)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(15, 5, TimeUnit.MINUTES))

        if (BuildConfig.DEBUG) {
            builder.addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
        }

        return builder.build()
    }
}
```

---

### 4.3 C3: 4 处调用点迁移

**1. `MediaRepository.kt`：**

```kotlin
@Singleton
class MediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient, // 注入共享的 OkHttpClient
) {
    // 移除：private val http: OkHttpClient by lazy { ... }
    // 所有 http.newCall(...) 改为 httpClient.newCall(...)
}
```

**2. `RetrofitClient.kt`：**

```kotlin
// 更新 buildRetrofit 使用传入的 OkHttpClient 或复用 OkHttpClient.Builder()
private fun buildRetrofit(baseUrl: String, okHttpClient: OkHttpClient): Retrofit {
    return Retrofit.Builder()
        .baseUrl("$baseUrl/")
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
}
```

**3. `ConnectionViewModel.kt`：**

```kotlin
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    application: Application,
    private val serverConfig: ServerConfig,
    private val repository: MediaRepository,
    private val httpClient: OkHttpClient, // 注入共享单例
) : AndroidViewModel(application) {

    // 在 startHttpScan() 中：
    // 使用 httpClient.newBuilder() 派生短超时 client，共享 ConnectionPool
    val scanClient = httpClient.newBuilder()
        .connectTimeout(250, TimeUnit.MILLISECONDS)
        .readTimeout(250, TimeUnit.MILLISECONDS)
        .build()
}
```

**4. `VideoPlayerScreen.kt` 与 `VideoPlayerViewModel.kt`：**

```kotlin
// 新建 VideoPlayerViewModel.kt
@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    val httpClient: OkHttpClient,
) : ViewModel()

// VideoPlayerScreen.kt line 139 (ExoPlayerWrapper 内)：
val videoPlayerViewModel: VideoPlayerViewModel = hiltViewModel()
val dataSourceFactory = remember(streamUrl) {
    OkHttpDataSource.Factory(videoPlayerViewModel.httpClient)
        .setUserAgent("LocalMediaHub")
}
```

---

## 5. 测试

### 5.1 测试矩阵

| Commit | 新测试 | 现有测试 |
|---|---|---|
| C1 服务端 Cache-Control | `server_test.go` 扩展（断言 3 档 17 个端点 Cache-Control） | 现有 server tests 全过 |
| C2 Hilt 模块 | 无 | `./gradlew assembleDebug` 通过 |
| C3 调用点迁移 | 无新测 | 现有 JVM / Robolectric tests 全过 |

---

## 6. 实现顺序与提交策略

3 个 commit，按依赖顺序：

1. **C1 服务端 Cache-Control** — 先做，确保客户端 cache 一旦接入就有 TTL 头可遵循
2. **C2 Hilt OkHttpClient 模块** — 提供单例和 Cache 实例
3. **C3 调用点迁移** — 替换 4 处 OkHttpClient 创建点

---

## 7. 已决策点

| 决策 | 选择 | 理由 |
|---|---|---|
| OkHttp 合并范围 | 4 个全部迁移/共享 | 统一 HTTP 基础设施，共享 ConnectionPool 与 Cache |
| 服务端 JSON Cache-Control | 3 档 TTL：60s / 300s / 3600s | 区分频繁变动、标准与静态端点 |
| `private` vs `public` | `private`（路径敏感） | 本地文件路径与标签信息不暴露给公共 CDN |
| `/system/browse` | 不缓存 | 实时系统路径浏览（依赖用户输入） |
| 客户端 Cache 大小 | 20MB | 满足 JSON 元数据缓存需求，不过度占用存储 |
| Cache 目录 | `cacheDir/okhttp/` | 与 Coil 图片缓存 `cacheDir/coil/` 分离 |
