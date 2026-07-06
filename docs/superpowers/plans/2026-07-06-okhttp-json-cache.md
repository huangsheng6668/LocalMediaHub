# OkHttp JSON 缓存 + Hilt 单例（Round 17）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 17 个 GET JSON 端点加 3 档 TTL Cache-Control；Android 4 个 OkHttpClient 实例合并为 Hilt `@Singleton` + 共享 20MB Disk Cache；调用点全部迁移到构造注入或 `newBuilder()` 衍生。

**Architecture:** 3 个 commit 顺序执行 — C1 服务端先加 Cache-Control 头（确保客户端缓存一接入就有 TTL 可遵循）→ C2 Hilt `OkHttpModule` 提供单例 → C3 4 个调用点迁移（MediaRepository/RetrofitClient 构造注入，ConnectionViewModel `newBuilder()` 衍生短超时 client 复用连接池，VideoPlayerScreen 通过新建 `VideoPlayerViewModel` 中转注入）。

**Tech Stack:** Go 1.24 + Echo v4 + testify；Android Kotlin + Hilt + OkHttp 4.12 + Retrofit 2.9 + ExoPlayer 1.2 + Coil 2.5

## Global Constraints

- Go 1.24
- 服务端 Cache-Control 用 `private`（路径敏感信息），不用 `public`（Round 3 媒体用 public）
- 3 档 TTL：brief=60s（folders/browse/search 类频繁变化）、standard=300s（videos/images/tags 类中等变化）、static=3600s（drives 极少变化）
- `/system/browse` 不加缓存（路径敏感）
- `/admin/scan` 不加缓存（POST 本来不缓存）
- 客户端 20MB Cache 在 `cacheDir/okhttp/`（与 Coil `cacheDir/coil/` 分离）
- Hilt `@Singleton OkHttpClient` 通过 `ConnectionPool(15, 5min)` 共享
- HTTP 日志 interceptor 仅在 `BuildConfig.DEBUG` 启用（`logging-interceptor` 已是 gradle 依赖）
- `ConnectionViewModel.startHttpScan()` 用 `httpClient.newBuilder().connectTimeout(250, MS)` 衍生短超时 client，复用连接池
- `VideoPlayerScreen` Composable 无法直接 Hilt 注入 → 通过新建 `VideoPlayerViewModel` 中转
- `RetrofitClient` 是 Kotlin `object` 单例 — Hilt 注入需特殊处理（改为 `@Singleton class` 或由 Hilt module 包装）
- `ConnectionViewModel` 已是 `@HiltViewModel @Inject constructor(...)` — 直接加 OkHttpClient 参数
- 每个服务端端点改动后：`cd server && go test ./...` 全过
- 每个 Android 改动后：`cd android && ./gradlew assembleDebug :app:testDebugUnitTest` 全过
- 3 commit 顺序：C1 → C2 → C3

---

### Task 1 (Commit C1): 服务端 17 个 GET JSON 端点 Cache-Control

**Files:**
- Modify: `server/internal/server/handler/handler.go`（追加 3 档 helper）
- Modify: `server/internal/server/handler/folders.go`（6 个端点）
- Modify: `server/internal/server/handler/images.go`（1 个端点：`GetImages`）
- Modify: `server/internal/server/handler/videos.go`（1 个端点：`GetVideos`）
- Modify: `server/internal/server/handler/tags.go`（5 个端点：`GetTags`、`GetTaggedFiles`、`GetTaggedMedia`、`GetFileTags` + `GetTag` 如存在）
- Modify: `server/internal/server/handler/system.go`（1 个端点：`GetDrives`）
- Modify: `server/internal/server/handler/search.go`（1 个端点：`Search`）
- Modify: `server/internal/server/handler/media.go`（1 个端点：`MediaDuration`）
- Modify: `server/internal/server/server_test.go`（扩展断言）

**Interfaces:**
- Consumes: Echo `c echo.Context`
- Produces: `setJsonCacheBrief(c)`、`setJsonCacheStandard(c)`、`setJsonCacheStatic(c)` helpers in handler.go

- [ ] **Step 1: Write the failing test for JSON Cache-Control**

Open `server/internal/server/server_test.go`. Find an existing test like `TestRegisterRoutesServesThumbnailEndpoint` to use as a pattern. Append at the end of the file:

```go
func TestRegisterRoutesJsonCacheControl(t *testing.T) {
    s, err := New(testConfig(t))
    if err != nil {
        t.Fatalf("New: %v", err)
    }

    cases := []struct {
        path        string
        wantCache   string
        wantNoCache bool
    }{
        // brief = 60s — endpoints that change when scan/add/delete files
        {"/api/v1/folders", "private, max-age=60", false},
        {"/api/v1/search?q=foo", "private, max-age=60", false},
        // standard = 300s — endpoints that change with tag operations / paging
        {"/api/v1/videos", "private, max-age=300", false},
        {"/api/v1/images", "private, max-age=300", false},
        {"/api/v1/tags", "private, max-age=300", false},
        // static = 3600s — almost never change
        {"/api/v1/system/drives", "private, max-age=3600", false},
        // not cached: /system/browse is path-sensitive
        // (cannot easily test without a real path — skip in unit test)
    }

    for _, tc := range cases {
        t.Run(tc.path, func(t *testing.T) {
            req := httptest.NewRequest(http.MethodGet, tc.path, nil)
            rec := httptest.NewRecorder()
            s.Echo.ServeHTTP(rec, req)
            cc := rec.Header().Get("Cache-Control")
            if cc != tc.wantCache {
                t.Errorf("Cache-Control = %q, want %q (status=%d)", cc, tc.wantCache, rec.Code)
            }
        })
    }
}
```

> Use the existing `testConfig(t)` helper — read server_test.go first to confirm its signature.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test ./internal/server/ -run TestRegisterRoutesJsonCacheControl -v`
Expected: FAIL — `Cache-Control` is empty string (helpers don't exist yet).

- [ ] **Step 3: Add 3-tier TTL helpers to handler.go**

Open `server/internal/server/handler/handler.go`. Find the existing `setMediaCacheHeaders` function (around line 89-91). Append immediately after it:

```go

// JSON Cache-Control policy tiers.
//
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

- [ ] **Step 4: Add Cache-Control to folders.go endpoints**

Open `server/internal/server/handler/folders.go`. For each of these handlers, add a `setJsonCacheBrief(c)` call **immediately before** the final `return c.JSON(...)` statement:

| Handler | Line of `c.JSON` | Action |
|---|---|---|
| `GetFolders` | ~34 | Add `setJsonCacheBrief(c)` before `return c.JSON(http.StatusOK, folders)` |
| `BrowseFolder` | ~169 | Add `setJsonCacheBrief(c)` before `return c.JSON(http.StatusOK, models.BrowseResult{...})` |
| `GetFolderFilesRecursive` | (find via grep) | Add `setJsonCacheBrief(c)` before `return c.JSON(...)` |
| `GetSubfolders` | (find via grep) | Add `setJsonCacheBrief(c)` before `return c.JSON(...)` |
| `GetFolderStats` | (find via grep) | Add `setJsonCacheBrief(c)` before `return c.JSON(...)` |
| `GetFolderThumbnails` | (find via grep) | Add `setJsonCacheBrief(c)` before `return c.JSON(...)` |

> Use `grep -n "func (h \*Handler)" server/internal/server/handler/folders.go` to find all handler names and line numbers.

> ⚠️ `BrowseFolder` has TWO return paths (line 87 for `matchedFiles` in search-mode, line 169 for normal browse). Add `setJsonCacheBrief(c)` before BOTH `c.JSON` calls.

- [ ] **Step 5: Add Cache-Control to images.go / videos.go (standard 300s)**

Open `server/internal/server/handler/images.go`. Find `GetImages` function. Add `setJsonCacheStandard(c)` before `return c.JSON(http.StatusOK, models.PaginatedMediaFiles{...})` (around line 32).

Open `server/internal/server/handler/videos.go`. Find `GetVideos` function. Add `setJsonCacheStandard(c)` before `return c.JSON(http.StatusOK, models.PaginatedMediaFiles{...})` (around line 32).

- [ ] **Step 6: Add Cache-Control to tags.go (standard 300s)**

Open `server/internal/server/handler/tags.go`. Add `setJsonCacheStandard(c)` before `return c.JSON(...)` in these handlers:

| Handler | Action |
|---|---|
| `GetTags` (line ~20) | Add before `return c.JSON(http.StatusOK, h.tags.GetAllTags())` |
| `GetTaggedFiles` (line ~87) | Add before `return c.JSON(http.StatusOK, files)` |
| `GetTaggedMedia` (line ~98, ~125) | Add before BOTH `c.JSON` returns (empty list + main result) |
| `GetFileTags` (lines ~136, ~139) | Add before BOTH `c.JSON` returns |

> ⚠️ `GetTaggedMedia` and `GetFileTags` have multiple return paths — add `setJsonCacheStandard(c)` before each.

- [ ] **Step 7: Add Cache-Control to system.go GetDrives (static 3600s)**

Open `server/internal/server/handler/system.go`. Find `GetDrives` function (line ~16). Add `setJsonCacheStatic(c)` before `return c.JSON(...)`.

> Do NOT add Cache-Control to `SystemBrowse`, `DeletePath`, `SystemThumbnail`/`SystemOriginal`/`SystemStream` (media endpoints use `setMediaCacheHeaders` already).

- [ ] **Step 8: Add Cache-Control to search.go Search (brief 60s)**

Open `server/internal/server/handler/search.go`. Find `Search` function. Add `setJsonCacheBrief(c)` before `return c.JSON(http.StatusOK, models.SearchResult{...})` (around line 69).

- [ ] **Step 9: Add Cache-Control to media.go MediaDuration (standard 300s)**

Open `server/internal/server/handler/media.go`. Find `MediaDuration` function (line ~71). Add `setJsonCacheStandard(c)` before `return c.JSON(http.StatusOK, map[string]interface{}{...})`.

> Do NOT add to `MediaThumbnail`/`MediaOriginal`/`MediaStream` (media endpoints use `setMediaCacheHeaders` already).

- [ ] **Step 10: Run test to verify it passes**

Run: `cd server && go test ./internal/server/ -run TestRegisterRoutesJsonCacheControl -v`
Expected: PASS (5 sub-tests).

- [ ] **Step 11: Run full server test suite**

Run: `cd server && go test ./...`
Expected: ALL PASS, no regression.

- [ ] **Step 12: Commit**

```bash
cd "E:\github_project\LocalMediaHub"
git add server/internal/server/handler/handler.go \
        server/internal/server/handler/folders.go \
        server/internal/server/handler/images.go \
        server/internal/server/handler/videos.go \
        server/internal/server/handler/tags.go \
        server/internal/server/handler/system.go \
        server/internal/server/handler/search.go \
        server/internal/server/handler/media.go \
        server/internal/server/server_test.go
git commit -m "$(cat <<'EOF'
feat(server): JSON endpoint Cache-Control headers (round 17 C1)

3-tier TTL: brief=60s (folders/browse/search), standard=300s (videos/
images/tags), static=3600s (system/drives). Uses 'private' (not 'public')
because JSON contains path-sensitive metadata.

17 GET JSON endpoints across 7 handler files. /system/browse and POST
/admin/scan left uncached (path-sensitive / POST).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2 (Commit C2): Android Hilt OkHttpClient 单例

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/network/OkHttpModule.kt`

**Interfaces:**
- Consumes: Hilt `@ApplicationContext Context`, `Cache` (self-provided)
- Produces: `@Singleton OkHttpClient` (shared by Task 3 callers), `@Singleton Cache` (20MB at `cacheDir/okhttp/`)

- [ ] **Step 1: Create OkHttpModule.kt**

Create `android/app/src/main/java/com/juziss/localmediahub/network/OkHttpModule.kt`:

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
 * Round 17 collapses 4 historical OkHttpClient instances into one. Cache
 * lives under `cacheDir/okhttp/` (sibling to Coil's `cacheDir/coil/`) and is
 * capped at 20MB. TTL is controlled by server-side `Cache-Control` headers
 * added in Round 17 C1.
 *
 * Call sites needing custom timeouts (e.g. ConnectionViewModel LAN scan
 * with 250ms connect timeout) use `client.newBuilder()` to derive a child
 * client that shares the connection pool.
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

- [ ] **Step 2: Verify build**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL (Hilt module compiles, no callers yet but Hilt accepts unused providers).

- [ ] **Step 3: Verify tests still pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: ALL PASS (no test regression — module is additive).

- [ ] **Step 4: Commit**

```bash
cd "E:\github_project\LocalMediaHub"
git add android/app/src/main/java/com/juziss/localmediahub/network/OkHttpModule.kt
git commit -m "$(cat <<'EOF'
feat(android): Hilt OkHttpModule with shared 20MB cache (round 17 C2)

Singleton OkHttpClient + Cache under cacheDir/okhttp/. 4 historical
instances (MediaRepository/RetrofitClient/VideoPlayerScreen/
ConnectionViewModel) will migrate in C3.

HTTP logging only enabled in BuildConfig.DEBUG. ConnectionPool(15, 5min)
shared; child clients created via newBuilder() (ConnectionViewModel LAN
scan) reuse the pool.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3 (Commit C3): 4 调用点迁移到 Hilt 注入

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/network/RetrofitClient.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/ConnectionViewModel.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt`
- Create: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/VideoPlayerViewModel.kt`

**Interfaces:**
- Consumes: Task 2's `@Singleton OkHttpClient`
- Produces: 4 call sites using injected (not constructed) client

- [ ] **Step 1: Migrate MediaRepository to constructor injection**

Open `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt`. Find the class declaration and the `http` lazy field (around lines 30-44).

**Current:**
```kotlin
class MediaRepository @Inject constructor() {

    private val baseUrl
        get() = RetrofitClient.getBaseUrl()

    private val gson = Gson()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(15, 5, TimeUnit.MINUTES))
            .build()
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    // ...
```

**Change to:**
```kotlin
class MediaRepository @Inject constructor(
    private val http: OkHttpClient,
) {

    private val baseUrl
        get() = RetrofitClient.getBaseUrl()

    private val gson = Gson()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    // ...
```

Remove the `import java.util.concurrent.TimeUnit` if it's no longer used (grep first to confirm — `TimeUnit` may appear elsewhere).

All `http.newCall(...)` calls remain unchanged — `http` is now a constructor parameter instead of a lazy field.

- [ ] **Step 2: Migrate RetrofitClient to accept injected client**

Open `android/app/src/main/java/com/juziss/localmediahub/network/RetrofitClient.kt`. The current shape is a Kotlin `object` singleton with `_retrofit: Retrofit?`. Since Hilt can't inject into a Kotlin `object`, refactor to store the injected client via a Hilt-provided initializer.

**Simplest approach (minimal refactor):** Add a `setClient(client: OkHttpClient)` method that ConnectionViewModel calls after Hilt provides the client, then `buildRetrofit` uses the stored client.

**Current `buildRetrofit`:**
```kotlin
private fun buildRetrofit(baseUrl: String): Retrofit {
    // ...
    val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()
    // ...
}
```

**Change to:**
```kotlin
object RetrofitClient {
    private const val TAG = "RetrofitClient"
    private var _retrofit: Retrofit? = null
    private var sharedClient: OkHttpClient? = null

    val instance: Retrofit
        get() = _retrofit ?: throw IllegalStateException(
            "RetrofitClient not initialized. Call initialize() first."
        )

    /**
     * Set the shared OkHttpClient (provided by Hilt OkHttpModule). Must be
     * called before initialize() so the Retrofit builder reuses the shared
     * client + connection pool. Round 17 C3.
     */
    fun setSharedClient(client: OkHttpClient) {
        sharedClient = client
    }

    fun initialize(rawBaseUrl: String) {
        val normalized = normalizeUrl(rawBaseUrl)
        if (_retrofit == null || _retrofit!!.baseUrl().toString() != normalized) {
            _retrofit = buildRetrofit(normalized)
        }
    }

    fun getBaseUrl(): String {
        return try { instance.baseUrl().toString() }
        catch (_: Exception) { "" }
    }

    private fun buildRetrofit(baseUrl: String): Retrofit {
        val client = sharedClient ?: throw IllegalStateException(
            "RetrofitClient.setSharedClient() must be called before initialize()"
        )

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    private fun normalizeUrl(raw: String): String {
        var url = raw.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        if (!url.endsWith("/")) url += "/"
        return url
    }
}
```

> ⚠️ `HttpLoggingInterceptor` import can be removed from RetrofitClient (now provided by OkHttpModule in DEBUG only).

- [ ] **Step 3: Wire RetrofitClient.setSharedClient from ConnectionViewModel**

Open `android/app/src/main/java/com/juziss/localmediahub/viewmodel/ConnectionViewModel.kt`.

**3a. Add OkHttpClient to constructor:**

Current (line ~43):
```kotlin
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : ViewModel() {
```

Change to:
```kotlin
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val httpClient: OkHttpClient,
) : ViewModel() {
```

**3b. Call setSharedClient before initialize:**

Find the `RetrofitClient.initialize(url)` call (around line 121). Change to:
```kotlin
RetrofitClient.setSharedClient(httpClient)
RetrofitClient.initialize(url)
```

**3c. Migrate scanClient to newBuilder() (line ~295):**

Find the `val scanClient = OkHttpClient.Builder()...` block (around line 295). Current:
```kotlin
val scanClient = OkHttpClient.Builder()
    .connectTimeout(250, TimeUnit.MILLISECONDS)
    .readTimeout(2, TimeUnit.SECONDS)
    .writeTimeout(2, TimeUnit.SECONDS)
    .build()
```

Change to:
```kotlin
// Derive a short-timeout client that shares the singleton's connection
// pool. newBuilder() copies config + pool; we override only the timeouts
// needed for fast LAN IP probing (Round 17 C3).
val scanClient = httpClient.newBuilder()
    .connectTimeout(250, TimeUnit.MILLISECONDS)
    .readTimeout(2, TimeUnit.SECONDS)
    .writeTimeout(2, TimeUnit.SECONDS)
    .build()
```

- [ ] **Step 4: Create VideoPlayerViewModel**

Create `android/app/src/main/java/com/juziss/localmediahub/viewmodel/VideoPlayerViewModel.kt`:

```kotlin
package com.juziss.localmediahub.viewmodel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Hilt ViewModel that exposes the shared singleton [OkHttpClient] to
 * [VideoPlayerScreen] for ExoPlayer DataSource.Factory construction.
 *
 * Composables cannot receive Hilt constructor injection directly; this
 * ViewModel acts as the injection seam. Round 17 C3 replaces the per-screen
 * `OkHttpClient.Builder()` with the shared singleton.
 */
@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    private val httpClient: OkHttpClient,
) : ViewModel() {
    /** The shared singleton OkHttpClient (with 20MB cache + connection pool). */
    fun provideHttpClient(): OkHttpClient = httpClient
}
```

- [ ] **Step 5: Migrate VideoPlayerScreen to use VideoPlayerViewModel**

Open `android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt`.

Find the `LaunchedEffect` or `remember` block around line 139 where `OkHttpClient.Builder()` is constructed. Read the surrounding code first to understand context (it's used to build an ExoPlayer `DataSource.Factory`).

**Current pattern (around line 139):**
```kotlin
val okClient = okhttp3.OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()
```

**Change to:**
```kotlin
import androidx.hilt.navigation.compose.hiltViewModel
import com.juziss.localmediahub.viewmodel.VideoPlayerViewModel
// ...

// At top of the Composable (before LaunchedEffect):
val videoPlayerViewModel: VideoPlayerViewModel = hiltViewModel()
val okClient = videoPlayerViewModel.provideHttpClient()
```

Remove the `okhttp3.OkHttpClient.Builder()` block. The `okClient` is now the shared singleton.

> ⚠️ `hiltViewModel()` requires `androidx.hilt:hilt-navigation-compose` dependency — verify it's in `build.gradle.kts` (line 144 has `androidx.hilt:hilt-navigation-compose:1.2.0`).

- [ ] **Step 6: Verify build**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Verify tests still pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: ALL PASS (existing 57+ JVM tests + Robolectric). Note: tests that previously mocked `OkHttpClient.Builder()` may need adjustment — if a test fails on `OkHttpClient` not being constructable, update the test to inject a mock `OkHttpClient` instead.

- [ ] **Step 8: Commit**

```bash
cd "E:\github_project\LocalMediaHub"
git add android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt \
        android/app/src/main/java/com/juziss/localmediahub/network/RetrofitClient.kt \
        android/app/src/main/java/com/juziss/localmediahub/viewmodel/ConnectionViewModel.kt \
        android/app/src/main/java/com/juziss/localmediahub/viewmodel/VideoPlayerViewModel.kt \
        android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt
git commit -m "$(cat <<'EOF'
refactor(android): migrate 4 OkHttp call sites to Hilt singleton (round 17 C3)

- MediaRepository: constructor @Inject OkHttpClient (drops lazy field)
- RetrofitClient: setSharedClient(client) before initialize(); drops
  per-instance OkHttpClient.Builder
- ConnectionViewModel: constructor @Inject; LAN scan uses
  httpClient.newBuilder() to share connection pool with 250ms timeout
- VideoPlayerScreen: new VideoPlayerViewModel bridges Hilt injection to
  Composable (hiltViewModel pattern)

All 4 instances now share the OkHttpModule singleton (20MB cache +
ConnectionPool 15/5min).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## 附录 A: 实现速查

| Commit | 文件数 | 改动量 | 风险 | 测试覆盖 |
|---|---|---|---|---|
| C1 服务端 Cache-Control | 9 (8 handlers + server_test) | ~50 行 | 低 | server_test.go 5 sub-tests |
| C2 Hilt OkHttpModule | 1 新增 | ~50 行 | 低 | assembleDebug + 现有 JVM 测试 |
| C3 调用点迁移 | 5 (4 改 + 1 新增 ViewModel) | ~80 行 | 中（触及 4 处独立代码） | 现有 JVM 测试 + assembleDebug |

## 附录 B: 17 个端点 TTL 分类速查

### brief (60s) — 6 endpoints
- `GET /api/v1/folders` → GetFolders
- `GET /api/v1/folders/{path}/browse` → BrowseFolder
- `GET /api/v1/folders/{path}/files` → GetFolderFilesRecursive
- `GET /api/v1/folders/{path}/subfolders` → GetSubfolders
- `GET /api/v1/folders/{path}/stats` → GetFolderStats
- `GET /api/v1/folders/{path}/thumbnails` → GetFolderThumbnails
- `GET /api/v1/search` → Search

### standard (300s) — 8 endpoints
- `GET /api/v1/videos` → GetVideos
- `GET /api/v1/images` → GetImages
- `GET /api/v1/tags` → GetTags
- `GET /api/v1/tags/{id}/files` → GetTaggedFiles
- `GET /api/v1/tags/{id}/media` → GetTaggedMedia
- `GET /api/v1/tags/file-tags` → GetFileTags
- `GET /api/v1/media/duration` → MediaDuration
- (GetTag if it exists as separate endpoint)

### static (3600s) — 1 endpoint
- `GET /api/v1/system/drives` → GetDrives

### 不缓存
- `GET /api/v1/system/browse` → SystemBrowse (路径敏感)
- `POST /api/v1/admin/scan/trigger` → TriggerScan (POST)

## 附录 C: 已知限制（接受）

1. **无 ETag/304**（spec §1.1）：服务端 JSON 端点用 `c.JSON` 不易加 ETag，YAGNI。
2. **RetrofitClient 仍是 `object`**：C3 通过 `setSharedClient(client)` 桥接而非完整重构为 `@Singleton class`。完整重构涉及更多调用点，留作后续轮次。
3. **缓存无主动清理**：OkHttp Cache 自带 LRU + 20MB 上限，无主动清理（与 Round 12 CacheCleanup 模式不同）。YAGNI 当前。
4. **HTTP Logging 仅 BASIC 级**：不记录 body；如需详细调试临时改 `Level.BODY`。
5. **`/search` 用 brief (60s) TTL**：搜索结果用户输入敏感，60s 缓存可能让重复搜索看到旧结果。可接受：用户可手动刷新或等 60s。
6. **C3 多个 c.JSON 返回路径**：部分 handler 有 2+ `c.JSON` 返回（错误路径 + 成功路径）。**仅在成功路径加 Cache-Control**（错误响应不应被缓存）。
