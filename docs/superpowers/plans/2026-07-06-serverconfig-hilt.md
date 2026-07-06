# ServerConfig Hilt @Singleton 重构（Round 19）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 RetrofitClient Kotlin `object`（含 mutable state + setSharedClient 桥接）重构为 `ServerConfig` Hilt `@Singleton class`（构造注入 OkHttpClient），同时将 `data.ServerConfig` 重命名为 `data.ServerConfigStore` 消解类名冲突，并完全移除 Retrofit 依赖（MediaApi 未使用）。

**Architecture:** 3 个 commit 顺序执行——C1 核心重构（新增 network.ServerConfig + 重命名 data.ServerConfigStore + 删 RetrofitClient/MediaApi + 6 处调用方迁移）；C2 HomeScreen 通过 ViewModel 暴露 baseUrl；C3 build.gradle.kts 删 Retrofit 依赖。所有现有 JVM 测试全过，UI 零行为变化。

**Tech Stack:** Kotlin + Hilt (dagger.hilt) + OkHttp 4.12 + StateFlow + Coroutines

## Global Constraints

- minSdk=26, targetSdk=34, Kotlin jvmTarget=1.8
- Hilt `@HiltViewModel` 构造签名可变（新增 ServerConfig/ServerConfigStore 参数，但不删除现有参数）
- `network.ServerConfig` 是 `@Singleton class`（非 internal，跨 package 注入）
- `data.ServerConfig` 重命名为 `data.ServerConfigStore`（消解与 `network.ServerConfig` 的类名冲突）
- `httpClient` 暴露为 `val`（public）—— ConnectionViewModel 的 LAN scan 用 `serverConfig.httpClient.newBuilder()` 衍生短超时 client
- `baseUrl` 用 `StateFlow<String>`（让 HomeScreen `collectAsState`）
- `setBaseUrl(url)` 内置 dedup（同 URL 不重设）
- `setSharedClient` 完全删除——不再需要
- `initialize()` 改名为 `setBaseUrl()`
- `isInitialized()` 保持（HomeViewModel 用）
- BrowseViewModel **不需注入** ServerConfig（MediaRepository 已封装 baseUrl）
- Retrofit + converter-gson 依赖完全移除（C3）
- `MediaApi.kt` 删除（未使用）
- 每个服务端改动后：`cd server && go test ./...` 全过（本轮不动 server）
- 每个 Android 改动后：`cd android && ./gradlew assembleDebug :app:testDebugUnitTest` 全过
- 3 commit 顺序：C1 → C2 → C3

---

### Task 1 (Commit C1): ServerConfig + ServerConfigStore + 调用方迁移

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/network/ServerConfig.kt`
- Rename: `android/app/src/main/java/com/juziss/localmediahub/data/ServerConfig.kt` → `data/ServerConfigStore.kt`（类名 `ServerConfig` → `ServerConfigStore`）
- Delete: `android/app/src/main/java/com/juziss/localmediahub/network/RetrofitClient.kt`
- Delete: `android/app/src/main/java/com/juziss/localmediahub/network/MediaApi.kt`
- Modify: `data/MediaRepository.kt:17,34`（import + baseUrl getter）
- Modify: `MainActivity.kt:18,45`（import + 返回类型）
- Modify: `viewmodel/ConnectionViewModel.kt:11,13,45,122-123`（双注入 + 删 setSharedClient）
- Modify: `viewmodel/HomeViewModel.kt:12,15,49,190-193`（双注入 + RetrofitClient.* → serverConfig.*）
- Modify: `network/OkHttpModule.kt:20`（KDoc 注释）
- Modify: `ui/screen/VideoPlayerScreen.kt:145`（注释）
- Modify: `test/.../HomeViewModelTest.kt:8-9,36,44-45,50,66-94`（删 resetRetrofitClient，改用 ServerConfig + ServerConfigStore）

**Interfaces:**
- Consumes: Hilt `@Singleton OkHttpClient`（Round 17 OkHttpModule）
- Produces: `network.ServerConfig` Hilt @Singleton class with `httpClient: OkHttpClient` + `baseUrl: StateFlow<String>` + `setBaseUrl()` + `isInitialized()` + `getBaseUrl()`

- [ ] **Step 1: Create `network/ServerConfig.kt`**

Create `android/app/src/main/java/com/juziss/localmediahub/network/ServerConfig.kt`:

```kotlin
package com.juziss.localmediahub.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hilt singleton holding the current server base URL + shared OkHttpClient.
 *
 * Round 19 refactor: replaces the Kotlin `object RetrofitClient` which
 * required a `setSharedClient()` bridge to receive the Hilt-provided
 * OkHttpClient. ServerConfig receives [httpClient] via constructor
 * injection (proper Hilt pattern) and exposes baseUrl as a StateFlow
 * so ViewModels can reactively observe changes.
 *
 * The Retrofit dependency is removed entirely — all API calls use
 * OkHttp + Gson directly (see MediaRepository).
 *
 * [httpClient] is exposed as a public val so ViewModels that need a
 * derived client (e.g. ConnectionViewModel LAN scan with 250ms timeout)
 * can call `serverConfig.httpClient.newBuilder()` to share the connection
 * pool without a separate Hilt injection.
 */
@Singleton
class ServerConfig @Inject constructor(
    val httpClient: OkHttpClient,
) {
    private val _baseUrl = MutableStateFlow("")
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    fun setBaseUrl(url: String) {
        val normalized = url.trimEnd('/')
        if (normalized != _baseUrl.value) {
            _baseUrl.value = normalized
        }
    }

    fun isInitialized(): Boolean = _baseUrl.value.isNotEmpty()

    fun getBaseUrl(): String = _baseUrl.value
}
```

- [ ] **Step 2: Rename `data/ServerConfig.kt` → `data/ServerConfigStore.kt`**

Open `android/app/src/main/java/com/juziss/localmediahub/data/ServerConfig.kt`. The class is named `ServerConfig`. Rename:
1. File: `ServerConfig.kt` → `ServerConfigStore.kt`
2. Class: `class ServerConfig @Inject constructor(...)` → `class ServerConfigStore @Inject constructor(...)`
3. KDoc: update any self-references from `ServerConfig` to `ServerConfigStore`

> Use `git mv` for the file rename to preserve history: `git mv android/app/src/main/java/com/juziss/localmediahub/data/ServerConfig.kt android/app/src/main/java/com/juziss/localmediahub/data/ServerConfigStore.kt`

- [ ] **Step 3: Delete `network/RetrofitClient.kt` and `network/MediaApi.kt`**

```bash
cd "E:\github_project\LocalMediaHub"
rm android/app/src/main/java/com/juziss/localmediahub/network/RetrofitClient.kt
rm android/app/src/main/java/com/juziss/localmediahub/network/MediaApi.kt
```

- [ ] **Step 4: Migrate `data/MediaRepository.kt`**

Open `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt`. Three changes:

**4a. Replace import (line 17):**
```kotlin
// 删除: import com.juziss.localmediahub.network.RetrofitClient
// 新增:
import com.juziss.localmediahub.network.ServerConfig
```

**4b. Add serverConfig to constructor:**
```kotlin
class MediaRepository @Inject constructor(
    private val http: OkHttpClient,
    private val serverConfig: ServerConfig,
) {
```

**4c. Change baseUrl getter (line 34):**
```kotlin
// 原: get() = RetrofitClient.getBaseUrl()
// 新:
    private val baseUrl
        get() = serverConfig.getBaseUrl()
```

- [ ] **Step 5: Migrate `MainActivity.kt`**

Open `android/app/src/main/java/com/juziss/localmediahub/MainActivity.kt`. Two changes:

**5a. Replace import (line 18):**
```kotlin
// 删除: import com.juziss.localmediahub.data.ServerConfig
// 新增:
import com.juziss.localmediahub.data.ServerConfigStore
```

**5b. Change return type (line 45):**
```kotlin
// 原: fun serverConfig(): ServerConfig
// 新:
    fun serverConfig(): ServerConfigStore
```

> Read the full function to confirm the return type name and any internal references.

- [ ] **Step 6: Migrate `viewmodel/ConnectionViewModel.kt`**

Open `android/app/src/main/java/com/juziss/localmediahub/viewmodel/ConnectionViewModel.kt`. Four changes:

**6a. Replace imports (lines 11, 13):**
```kotlin
// 删除:
// import com.juziss.localmediahub.data.ServerConfig
// import com.juziss.localmediahub.network.RetrofitClient
// 新增:
import com.juziss.localmediahub.data.ServerConfigStore
import com.juziss.localmediahub.network.ServerConfig
```

**6b. Add network.ServerConfig to constructor:**
```kotlin
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    application: Application,
    private val serverConfigStore: ServerConfigStore,  // 原 data.ServerConfig 重命名
    private val serverConfig: ServerConfig,            // 新增 network.ServerConfig
    private val repository: MediaRepository,
    private val httpClient: OkHttpClient,
) : AndroidViewModel(application) {
```

> ⚠️ Read the actual constructor to confirm parameter names + order. The existing `serverConfig: ServerConfig` (data) becomes `serverConfigStore: ServerConfigStore`. The new `serverConfig: ServerConfig` (network) is added.

**6c. Replace setSharedClient + initialize (lines 122-123):**
```kotlin
// 原:
// RetrofitClient.setSharedClient(httpClient)
// RetrofitClient.initialize(url)
// 新:
serverConfig.setBaseUrl(url)
```

**6d. LAN scan client (line ~295):**
```kotlin
// 原: val scanClient = httpClient.newBuilder()
// 新: 可保持 httpClient.newBuilder() 或改为 serverConfig.httpClient.newBuilder()
// 推荐保持 httpClient.newBuilder()（已有注入），不改
```

- [ ] **Step 7: Migrate `viewmodel/HomeViewModel.kt`**

Open `android/app/src/main/java/com/juziss/localmediahub/viewmodel/HomeViewModel.kt`. Four changes:

**7a. Replace imports (lines 12, 15):**
```kotlin
// 删除:
// import com.juziss.localmediahub.data.ServerConfig
// import com.juziss.localmediahub.network.RetrofitClient
// 新增:
import com.juziss.localmediahub.data.ServerConfigStore
import com.juziss.localmediahub.network.ServerConfig
```

**7b. Rename constructor param + add network.ServerConfig:**
```kotlin
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val favoritesStore: FavoritesStore,
    private val recentActivityStore: RecentActivityStore,
    private val serverConfigStore: ServerConfigStore,  // 原 serverConfig: ServerConfig
    private val serverConfig: ServerConfig,            // 新增 network.ServerConfig
    private val repository: MediaRepository,
) : ViewModel() {
```

> ⚠️ Read the actual constructor first. The old `private val serverConfig: ServerConfig` (data) becomes `serverConfigStore: ServerConfigStore`. Internal uses of `serverConfig.saveServerConfig()` must change to `serverConfigStore.saveServerConfig()`.

**7c. Replace RetrofitClient calls (lines 190-193):**
```kotlin
// 原:
// if (serverUrl.isBlank()) return RetrofitClient.isInitialized()
// if (!RetrofitClient.isInitialized() || RetrofitClient.getBaseUrl() != serverUrl) {
//     RetrofitClient.initialize(serverUrl)
// }
// 新:
    fun checkAndInitialize(serverUrl: String): Boolean {
        if (serverUrl.isBlank()) return serverConfig.isInitialized()
        if (!serverConfig.isInitialized() || serverConfig.getBaseUrl() != serverUrl) {
            serverConfig.setBaseUrl(serverUrl)
        }
        return true
    }
```

**7d. Fix any remaining `serverConfig.saveServerConfig` references:**
Search for `serverConfig.` within HomeViewModel.kt and rename to `serverConfigStore.` for all data-layer calls (e.g., `saveServerConfig`, `getServerConfig`, `loadKnownServers`). Read the file first to find all occurrences.

- [ ] **Step 8: Update KDoc comments in OkHttpModule.kt + VideoPlayerScreen.kt**

**`network/OkHttpModule.kt` line 20:**
```kotlin
// 原: * MediaRepository, RetrofitClient, VideoPlayerScreen, and ConnectionViewModel.
// 新:
 * MediaRepository, ServerConfig, VideoPlayerScreen, and ConnectionViewModel.
```

**`ui/screen/VideoPlayerScreen.kt` line 145:**
```kotlin
// 原: // cache shared with MediaRepository / RetrofitClient / LAN scan.
// 新:
// cache shared with MediaRepository / ServerConfig / LAN scan.
```

- [ ] **Step 9: Migrate `test/.../HomeViewModelTest.kt`**

Open `android/app/src/test/java/com/juziss/localmediahub/viewmodel/HomeViewModelTest.kt`. Multiple changes:

**9a. Replace imports:**
```kotlin
// 删除:
// import com.juziss.localmediahub.data.ServerConfig
// import com.juziss.localmediahub.network.RetrofitClient
// 新增:
import com.juziss.localmediahub.data.ServerConfigStore
import com.juziss.localmediahub.network.ServerConfig
import okhttp3.OkHttpClient
```

**9b. Change field declarations:**
```kotlin
// 原: private lateinit var serverConfig: ServerConfig
// 新:
    private lateinit var serverConfigStore: ServerConfigStore
    private lateinit var serverConfig: ServerConfig
```

**9c. Change setup() initialization:**
```kotlin
// 原:
// serverConfig = ServerConfig(context)
// resetRetrofitClient()
// 新:
        serverConfigStore = ServerConfigStore(context)
        serverConfig = ServerConfig(OkHttpClient())
```

**9d. Delete resetRetrofitClient() method entirely** (the whole private fun including reflection on `_retrofit` and `_baseUrl` fields).

**9e. Replace RetrofitClient.setSharedClient + initialize in test body:**
```kotlin
// 原:
// RetrofitClient.setSharedClient(httpClient)
// RetrofitClient.initialize(...)
// 新:
serverConfig.setBaseUrl(...)
```

**9f. Replace RetrofitClient.isInitialized assertions:**
```kotlin
// 原: assertTrue(RetrofitClient.isInitialized())
// 新: assertTrue(serverConfig.isInitialized())
```

**9g. Replace serverConfig → serverConfigStore for data-layer calls:**
```kotlin
// 原: serverConfig.saveServerConfig("127.0.0.1", "1")
// 新: serverConfigStore.saveServerConfig("127.0.0.1", "1")
```

**9h. Delete tearDown resetRetrofitClient() call:**
```kotlin
// 原:
// @After fun teardown() { resetRetrofitClient() }
// 新: delete the entire @After method (no mutable state to reset)
```

> ⚠️ Read the full test file first. There are multiple occurrences of `serverConfig.saveServerConfig`, `RetrofitClient.*`, and `resetRetrofitClient`. Each must be migrated. The test's HomeViewModel constructor call must pass both `serverConfigStore` and `serverConfig` params.

- [ ] **Step 10: Verify build**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. If compile errors, check:
- Missing `serverConfigStore` / `serverConfig` constructor params
- Lingering `RetrofitClient.*` references
- Wrong import for `ServerConfig` (network vs data)

- [ ] **Step 11: Verify tests**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: ALL PASS (57 tests). The HomeViewModelTest should now construct `ServerConfig(OkHttpClient())` without reflection.

- [ ] **Step 12: Commit**

```bash
cd "E:\github_project\LocalMediaHub"
git add android/app/src/main/java/com/juziss/localmediahub/network/ServerConfig.kt \
        android/app/src/main/java/com/juziss/localmediahub/data/ServerConfigStore.kt \
        android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt \
        android/app/src/main/java/com/juziss/localmediahub/MainActivity.kt \
        android/app/src/main/java/com/juziss/localmediahub/viewmodel/ConnectionViewModel.kt \
        android/app/src/main/java/com/juziss/localmediahub/viewmodel/HomeViewModel.kt \
        android/app/src/main/java/com/juziss/localmediahub/network/OkHttpModule.kt \
        android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt \
        android/app/src/test/java/com/juziss/localmediahub/viewmodel/HomeViewModelTest.kt
git rm android/app/src/main/java/com/juziss/localmediahub/network/RetrofitClient.kt \
       android/app/src/main/java/com/juziss/localmediahub/network/MediaApi.kt
git mv android/app/src/main/java/com/juziss/localmediahub/data/ServerConfig.kt \
       android/app/src/main/java/com/juziss/localmediahub/data/ServerConfigStore.kt 2>/dev/null || true
git commit -m "$(cat <<'EOF'
refactor(android): RetrofitClient → ServerConfig Hilt singleton (round 19 C1)

RetrofitClient Kotlin object replaced by ServerConfig Hilt @Singleton
class with constructor-injected OkHttpClient. data.ServerConfig renamed
to data.ServerConfigStore to resolve class name conflict. MediaApi.kt +
RetrofitClient.kt deleted (unused). setSharedClient bridge eliminated.
HomeViewModelTest drops resetRetrofitClient reflection helper.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2 (Commit C2): HomeScreen baseUrl via ViewModel

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/HomeViewModel.kt`（加 `serverBaseUrl` public flow）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/HomeScreen.kt:125,201`（改 collectAsState）

**Interfaces:**
- Consumes: Task 1 的 `serverConfig.baseUrl: StateFlow<String>`
- Produces: `HomeViewModel.serverBaseUrl: StateFlow<String>`（public）

- [ ] **Step 1: Add serverBaseUrl to HomeViewModel**

Open `android/app/src/main/java/com/juziss/localmediahub/viewmodel/HomeViewModel.kt`. Add after the class declaration + before any private fun:

```kotlin
    /** Server base URL for UI consumption (TopAppBar actions, web links). */
    val serverBaseUrl: StateFlow<String> = serverConfig.baseUrl
```

> If `StateFlow` import is missing, add `import kotlinx.coroutines.flow.StateFlow`.

- [ ] **Step 2: Migrate HomeScreen.kt line 125**

Open `android/app/src/main/java/com/juziss/localmediahub/ui/screen/HomeScreen.kt`. Find line ~125:

```kotlin
// 原:
val url = com.juziss.localmediahub.network.RetrofitClient.getBaseUrl()
// 新:
val url = homeViewModel.serverBaseUrl.value
```

> ⚠️ Read the surrounding context first. If HomeScreen already has `homeViewModel` instance via `hiltViewModel()`, use `.value` for one-shot read. If the URL is used in a click lambda, `.value` is fine. For reactive display, use `collectAsState()`.

- [ ] **Step 3: Migrate HomeScreen.kt line 201**

Same pattern:
```kotlin
// 原:
val url = com.juziss.localmediahub.network.RetrofitClient.getBaseUrl()
// 新:
val url = homeViewModel.serverBaseUrl.value
```

- [ ] **Step 4: Verify build + tests**

Run: `cd android && ./gradlew assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all 57 tests pass.

- [ ] **Step 5: Commit**

```bash
cd "E:\github_project\LocalMediaHub"
git add android/app/src/main/java/com/juziss/localmediahub/viewmodel/HomeViewModel.kt \
        android/app/src/main/java/com/juziss/localmediahub/ui/screen/HomeScreen.kt
git commit -m "$(cat <<'EOF'
refactor(android): HomeScreen reads baseUrl via ViewModel (round 19 C2)

HomeViewModel exposes serverBaseUrl (StateFlow<String>) re-exported from
ServerConfig.baseUrl. HomeScreen TopAppBar + HeroCard use
homeViewModel.serverBaseUrl.value instead of RetrofitClient.getBaseUrl().

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3 (Commit C3): Remove Retrofit dependency

**Files:**
- Modify: `android/app/build.gradle.kts`（删 retrofit + converter-gson deps）
- Modify: `android/app/proguard-rules.pro`（删 Retrofit keep rules 如有）

- [ ] **Step 1: Remove Retrofit dependencies from build.gradle.kts**

Open `android/app/build.gradle.kts`. Find and delete these two lines:

```kotlin
// 删除:
implementation("com.squareup.retrofit2:retrofit:2.9.0")
implementation("com.squareup.retrofit2:converter-gson:2.9.0")
```

> ⚠️ Keep `com.google.code.gson:gson:2.8.9` — MediaRepository still uses Gson directly.

- [ ] **Step 2: Check proguard-rules.pro for Retrofit keep rules**

Open `android/app/proguard-rules.pro`. Search for Retrofit-related keep rules (e.g., `-keep class retrofit2.**`, `-keepattributes Signature`). If found, delete them. If none exist, skip.

```bash
cd "E:\github_project\LocalMediaHub"
grep -n "retrofit\|Retrofit" android/app/proguard-rules.pro
```

- [ ] **Step 3: Verify build (debug + release)**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

Run: `cd android && ./gradlew assembleRelease`
Expected: BUILD SUCCESSFUL (R8 minify works without Retrofit keep rules).

- [ ] **Step 4: Verify tests**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: ALL PASS (57 tests).

- [ ] **Step 5: Commit**

```bash
cd "E:\github_project\LocalMediaHub"
git add android/app/build.gradle.kts android/app/proguard-rules.pro
git commit -m "$(cat <<'EOF'
chore(android): remove unused Retrofit dependency (round 19 C3)

Retrofit + converter-gson removed from build.gradle.kts. MediaApi was
never used — all API calls go through OkHttp + Gson directly via
MediaRepository. Saves ~400KB in APK. ProGuard Retrofit keep rules
removed if present.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## 附录 A: 实现速查

| Commit | 文件数 | 改动量 | 风险 | 测试覆盖 |
|---|---|---|---|---|
| C1 ServerConfig + 迁移 | 12 (1 create + 1 rename + 2 delete + 8 modify) | ~200 行 | 中（触及 8 调用方 + 测试） | 57 tests |
| C2 HomeScreen ViewModel | 2 modify | ~10 行 | 低 | 57 tests |
| C3 Retrofit 依赖移除 | 2 modify | ~5 行 | 低 | assembleDebug + assembleRelease |

## 附录 B: ServerConfig vs ServerConfigStore 区别

| 类 | package | 职责 | 注入方式 |
|---|---|---|---|
| `ServerConfig` (network) | `com.juziss.localmediahub.network` | 内存中持有 baseUrl + httpClient，Hilt @Singleton | Hilt constructor injection |
| `ServerConfigStore` (data) | `com.juziss.localmediahub.data` | DataStore 持久化 server IP/port + 已知服务器列表 | Hilt constructor injection (@ApplicationContext) |

> 原 `data.ServerConfig` 持有 DataStore preferences（`saveServerConfig` / `getServerConfig` / `loadKnownServers`）。重命名为 `ServerConfigStore` 对齐 `FavoritesStore` / `RecentActivityStore` / `DownloadsStore` 命名规范。

## 附录 C: 已知限制（接受）

1. **`network.ServerConfig.httpClient` public val**：轻微违反单一职责（ServerConfig 应该只管 baseUrl），但让 ConnectionViewModel 不需重复注入 OkHttpClient。可接受。
2. **`StateFlow` 可能过度设计**：HomeScreen 的 baseUrl 基本不变，StateFlow 响应式优势不大。保留以备未来 UI 需要动态显示。
3. **`DEFAULT_TIMEOUT` 常量删除**：原 RetrofitClient 的 `DEFAULT_TIMEOUT = 30L` 无引用（OkHttpModule 已设 30s），随文件删除自然消失。
4. **HomeScreen 用 `.value` 而非 `collectAsState()`**：baseUrl 在 HomeScreen 中只用于点击事件（一次性读），不需要重组。如果未来需要响应式显示，再改为 `collectAsState()`。
