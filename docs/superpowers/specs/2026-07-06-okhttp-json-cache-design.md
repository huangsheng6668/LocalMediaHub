# OkHttp JSON 缓存 + Hilt 单例设计（Round 17）

- **日期**: 2026-07-06
- **范围**: Android 客户端 OkHttpClient + 服务端 JSON 端点 Cache-Control
- **策略**: 3 commits — 服务端 3 档 TTL Cache-Control + Hilt 单例 OkHttpClient + 4 处调用点迁移
- **状态**: 待评审
- **前置**: Round 3（服务端媒体端点已加 `Cache-Control: public, max-age=86400`）；Round 12（Coil diskCache 已落地）；Round 12 spec §9 follow-up

---

## 1. 背景与动机

Android 客户端有 **4 个 OkHttpClient 实例**（全是 `OkHttpClient.Builder()` 裸调，零共享 Cache）：

1. `MediaRepository.http`（所有 JSON 端点 + 缩略图/视频 stream）
2. `RetrofitClient.client`（实际未被使用 — 现用 OkHttp+Gson）
3. `VideoPlayerScreen.okClient`（视频流）
4. `ConnectionViewModel.scanClient`（一次性扫描触发）

服务端 JSON 端点（`/folders`、`/browse`、`/search`、`/tags`、`/videos` 等）**全部无 Cache-Control 头**。媒体端点在 Round 3 已加（`setMediaCacheHeaders`），JSON 端点未加。

后果：
- 每次 `browseFolder`、`search`、`getTags` 都发起完整 HTTP 请求，**无浏览器/客户端缓存**。
- 冷启动、重复浏览、分页等场景带宽浪费 + 等待时间。
- 4 个 OkHttpClient 实例无连接池共享，握手 SSL/TLS 浪费。

Round 17 解决：**统一 OkHttp 单例 + 服务端 JSON Cache-Control + 客户端 20MB 缓存目录**。

### 1.1 范围明确

- ✅ 4 个 OkHttpClient 全部合并为 Hilt 单例 + 共享 Cache
- ✅ 服务端为 GET JSON 端点加 3 档 TTL Cache-Control
- ✅ 客户端 20MB JSON 缓存目录
- ❌ ETag + If-None-Match 304 机制（YAGNI）
- ❌ GET 请求预取（YAGNI）
- ❌ 离线模式（YAGNI）
- ❌ 任何客户端 UI 行为改动

---

## 2. 目标与非目标

### 目标
1. **C1 服务端 JSON Cache-Control**：3 档 TTL helper + 13 个 GET JSON 端点接入。
2. **C2 Android Hilt OkHttpClient 单例**：`OkHttpModule.kt` 提供 `@Singleton OkHttpClient + Cache(20MB)`。
3. **C3 调用点迁移**：4 处 `OkHttpClient.Builder()` 替换为 `@Inject` 注入。
4. **零行为变化**：所有现有 JVM/Robolectric 测试不回归。

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

| Commit | 文件 | 改动类型 |
|---|---|---|
| C1 | `server/internal/server/handler/handler.go` | 改：加 3 档 TTL helper |
| C1 | `server/internal/server/handler/folders.go` | 改：6 个 GET 端点加头 |
| C1 | `server/internal/server/handler/images.go` | 改：1 个 GET 端点加头 |
| C1 | `server/internal/server/handler/videos.go` | 改：1 个 GET 端点加头 |
| C1 | `server/internal/server/handler/tags.go` | 改：5 个 GET 端点加头 |
| C1 | `server/internal/server/handler/system.go` | 改：1 个 GET 端点加头（drives） |
| C1 | `server/internal/server/handler/search.go` | 改：1 个 GET 端点加头 |
| C1 | `server/internal/server/handler/media.go` | 改：1 个 GET 端点加头（file-tags） |
| C1 | `server/internal/server/server_test.go` | 改：扩展断言 Cache-Control |
| C2 | `android/.../network/OkHttpModule.kt` | **新增**：Hilt module |
| C2 | `android/.../network/RetrofitClient.kt` | 改：移除未用的 client 字段 |
| C3 | `android/.../data/MediaRepository.kt` | 改：构造注入 OkHttpClient |
| C3 | `android/.../ui/screen/VideoPlayerScreen.kt` | 改：通过 ViewModel 暴露 client |
| C3 | `android/.../viewmodel/ConnectionViewModel.kt` | 改：构造注入（已是 @HiltViewModel） |
| C3 | `android/.../viewmodel/VideoPlayerViewModel.kt`（如已存在） | 改：注入 OkHttpClient |

### 3.2 关键约束

- 服务端 Cache-Control 用 `private`（路径敏感信息），不用 `public`（Round 3 媒体用 public）
- `/system/browse` 不加缓存（路径敏感 + 用户输入）
- `/admin/scan` 不加缓存（POST 本来不缓存，无需动作）
- 客户端 20MB Cache 在 `cacheDir/okhttp/`（与 Coil `cacheDir/coil/` 分离）
- Hilt `@Singleton` OkHttpClient 通过 `ConnectionPool(15, 5min)` 共享
- HTTP 日志 interceptor 仅在 `BuildConfig.DEBUG` 时启用（release 节省内存）
- `VideoPlayerScreen` Composable 无法直接 Hilt 注入 → 通过 ViewModel 中转
- `ConnectionViewModel` 已是 `@HiltViewModel` → 直接加 `OkHttpClient` 构造参数

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

**端点接入（每处 `c.JSON` 前调用对应 helper）：**

| TTL | 端点 | Handler |
|---|---|---|
| brief (60s) | `GET /api/v1/folders` | folders.go::GetFolders |
| brief (60s) | `GET /api/v1/folders/{path}/browse` | folders.go::BrowseFolder |
| brief (60s) | `GET /api/v1/folders/{path}/files` | folders.go::GetFolderFilesRecursive |
| brief (60s) | `GET /api/v1/search` | search.go::Search |
| standard (300s) | `GET /api/v1/videos` | images.go::GetVideos |
| standard (300s) | `GET /api/v1/images` | images.go::GetImages |
| standard (300s) | `GET /api/v1/tags` | tags.go::GetTags |
| standard (300s) | `GET /api/v1/tags/{id}/media` | tags.go::GetTaggedMedia |
| standard (300s) | `GET /api/v1/tags/{id}/files` | tags.go::GetTaggedFiles |
| standard (300s) | `GET /api/v1/tags/file-tags` | tags.go::GetFileTags |
| static (3600s) | `GET /api/v1/system/drives` | system.go::GetSystemDrives |
| **不缓存** | `GET /api/v1/system/browse` | system.go::BrowseSystemPath（路径敏感） |
| **不缓存** | `POST /api/v1/admin/scan` | admin.go::TriggerScan（POST 本来不缓存） |

**测试：** `server_test.go` 扩展：
```go
func TestRegisterRoutesJsonCacheControl(t *testing.T) {
    s, _ := New(testConfig(t))
    req := httptest.NewRequest(http.MethodGet, "/api/v1/folders", nil)
    rec := httptest.NewRecorder()
    s.Echo.ServeHTTP(rec, req)
    if cc := rec.Header().Get("Cache-Control"); cc != "private, max-age=60" {
        t.Errorf("folders Cache-Control = %q, want brief", cc)
    }
    // ... similar assertions for /tags (standard), /system/drives (static)
}
```

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
 * Hilt module providing a singleton [OkHttpClient] + [Cache] shared by all
 * 4 historical call sites (MediaRepository, RetrofitClient, VideoPlayerScreen,
 * ConnectionViewModel). Round 17 collapses the duplicate instances into one.
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

        // Verbose HTTP logging only in debug; release builds skip the
        // interceptor to save memory and avoid leaking paths in logcat.
        if (BuildConfig.DEBUG) {
            builder.addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
        }

        return builder.build()
    }
}
```

**`RetrofitClient.kt`** — 删除未用的 `client` 字段（仅保留 `getBaseUrl` / URL builder helpers）。

### 4.3 C3: 4 处调用点迁移

**`MediaRepository.kt`：**

```kotlin
class MediaRepository @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    private val baseUrl get() = RetrofitClient.getBaseUrl()
    private val gson = Gson()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // 删除：private val http: OkHttpClient by lazy { ... }

    // 所有 http.newCall(...) 改为 httpClient.newCall(...)
    private suspend fun <T> httpGet(url: String, type: java.lang.reflect.Type): NetworkResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).get().build()
                httpClient.newCall(request).execute().use { resp -> /* ... */ }
            } catch (e: Exception) {
                NetworkResult.Error(e.toUserMessage())
            }
        }
    // ... 同理 httpPost / httpEmpty / httpStream
}
```

**`ConnectionViewModel.kt`：**

```kotlin
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val httpClient: OkHttpClient,  // ← 新增注入
) : ViewModel() {
    // 删除：scanClient = OkHttpClient.Builder()...
    // 改为：scanClient = httpClient
}
```

**`VideoPlayerScreen.kt`** — 通过 ViewModel 中转：

> 当前 `VideoPlayerScreen.kt:139` 在 `LaunchedEffect` 内局部构造 OkHttpClient 用于 ExoPlayer DataSource.Factory。Composable 无法直接 Hilt 注入，需通过持有它的 ViewModel（如 `VideoPlayerViewModel`）暴露 client。

```kotlin
// VideoPlayerViewModel (if exists) or BrowseViewModel:
@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    private val httpClient: OkHttpClient,
) : ViewModel() {
    fun provideHttpClient(): OkHttpClient = httpClient
}

// VideoPlayerScreen:
val videoPlayerViewModel: VideoPlayerViewModel = hiltViewModel()
val httpClient = videoPlayerViewModel.provideHttpClient()
// 用 httpClient 构造 DataSource.Factory
```

**复杂度警告：** 若项目无 `VideoPlayerViewModel`，需新建或迁移到现有 ViewModel。这是 C3 最棘手的部分，需先读 `VideoPlayerScreen.kt` 全文确认。

---

## 5. 测试

### 5.1 测试矩阵

| Commit | 新测试 | 现有测试 |
|---|---|---|
| C1 服务端 Cache-Control | server_test.go 扩展（断言 3 档 Cache-Control） | 现有 server tests 全过 |
| C2 Hilt 模块 | 无（Hilt 模块需 instrumented test，超范围） | `./gradlew assembleDebug` 通过 |
| C3 调用点迁移 | 无新测 | 现有 JVM tests (57+) 全过 |

### 5.2 真机/集成验证

- 浏览文件夹 → 第二次访问应直接命中 OkHttp Cache（adb logcat 看 HTTP 200 from cache）
- 修改服务端文件 → 60s 内仍命中缓存；60s 后重新验证（Coil 风格但 OkHttp 自动）
- `dumpsys diskstats` 看 `okhttp/` 目录 ~20MB 上限
- ExoPlayer 视频流仍正常工作（C3 不影响流式 URL 构造）

---

## 6. 实现顺序与提交策略

3 个 commit，按依赖顺序：

1. **C1 服务端 Cache-Control** — 先做，确保客户端 cache 一旦接入就有 TTL 头可遵循
2. **C2 Hilt OkHttpClient 模块** — 准备单例
3. **C3 调用点迁移** — 替换 4 处，最后做

每个 commit 之间：
- `cd server && go test ./...` 全过（C1）
- `cd android && ./gradlew assembleDebug testDebugUnitTest` 全过（C2/C3）

---

## 7. 已决策点

| 决策 | 选择 | 理由 |
|---|---|---|
| OkHttp 合并范围 | 4 个全部合并 | 用户明确选 |
| 服务端 JSON Cache-Control | 3 档 TTL：60s / 300s / 3600s | 用户明确选 |
| `private` vs `public` | `private`（路径敏感） | 路径/标签数据不应进 CDN |
| `/system/browse` | 不缓存 | 路径敏感（用户输入） |
| `/admin/scan` | 不缓存（POST 本来不缓存） | 无需动作 |
| 客户端 Cache 大小 | 20MB | 用户明确选 |
| Cache 目录 | `cacheDir/okhttp/`（与 Coil `cacheDir/coil/` 分离） | 互不干扰 |
| HTTP Logging | 仅 BuildConfig.DEBUG | release 节省内存 |
| 提交粒度 | 3 个 commit | 服务端 → Hilt 模块 → 调用点迁移 |

---

## 8. 已知限制（接受）

1. **无 ETag/304**（§1.1）：服务端 JSON 端点用 `c.JSON` 不易加 ETag，YAGNI。
2. **VideoPlayerScreen 中转复杂度**（§4.3）：若项目无 VideoPlayerViewModel，C3 需新建。可能需要调整方案——见实现时确认。
3. **缓存无主动清理**：OkHttp Cache 自带 LRU + 20MB 上限，无主动清理。可参照 Round 12 CacheCleanup 模式扩展（YAGNI 当前）。
4. **HTTP Logging 仅 BASIC 级**：不记录 body；如需详细调试临时改 `Level.BODY`。
5. **`RetrofitClient` 仍存在**：仅提供 URL builder；移除整个文件涉及更多调用点，留作后续轮次。

---

## 9. 非目标（再次明确）

- ❌ ETag + If-None-Match 304
- ❌ GET 请求预取
- ❌ 离线模式
- ❌ 客户端 UI 行为改动
- ❌ Rust native 改动
- ❌ Coil 升级
- ❌ 完全删除 RetrofitClient（仅移除未用 client 字段）

---

## 10. 后续轮次（不在本 spec，仅备忘）

- **Coil v3 升级**：原生"按访问时间淘汰"，解决 Round 12 mtime 限制
- **Logger 注入重构**：Round 12 Important follow-up
- **`BrowseViewModel` 拆分 + RetrofitClient 删除**：架构债
- **HTTP/2 + TLS**：服务端跨网络部署
- **配置文件 v2 + 热重载**：服务端配置层
