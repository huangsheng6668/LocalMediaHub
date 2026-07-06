# RetrofitClient Hilt @Singleton 重构设计（Round 19）

- **日期**: 2026-07-06
- **范围**: Android 客户端 `network/` — RetrofitClient 重构为 ServerConfig Hilt @Singleton class
- **策略**: 3 commits — C1 ServerConfig + 调用方迁移 → C2 HomeScreen ViewModel 暴露 → C3 Retrofit 依赖移除
- **状态**: 待评审
- **前置**: Round 17（OkHttp Hilt 单例 + RetrofitClient setSharedClient 桥接）

---

## 1. 背景与动机

Round 17 把 OkHttpClient 合并为 Hilt `@Singleton` 时，`RetrofitClient` 是 Kotlin `object`（无法 Hilt 注入），只能用 `setSharedClient(client)` mutable-state 桥接。这是反模式：

- **init 顺序坑**：必须先 `setSharedClient` 再 `initialize`，否则 `buildRetrofit()` 抛 `IllegalStateException`
- **全局 mutable state**：`_baseUrl` / `_retrofit` / `sharedClient` 三个 mutable 字段
- **测试困难**：`HomeViewModelTest` 需要 `resetRetrofitClient()` 反射 helper 清理 state
- **Retrofit 未实际使用**：`MediaApi` 接口存在但无人调用（所有 API 走 OkHttp+Gson 手写）
- **依赖浪费**：Retrofit + converter-gson 合计 ~400KB，从未被用到

Round 19 解决：
1. `RetrofitClient` Kotlin `object` → `ServerConfig` Hilt `@Singleton class`（构造注入 OkHttpClient）
2. 所有调用方迁移到 Hilt 注入
3. Retrofit 依赖 + MediaApi 完全移除

### 1.1 范围明确

- ✅ `RetrofitClient.kt` 删除 → `ServerConfig.kt` 新增（Hilt @Singleton）
- ✅ `MediaApi.kt` 删除（未使用）
- ✅ Retrofit + converter-gson 依赖移除
- ✅ 5 处调用方迁移
- ❌ Coil 升级（YAGNI 当前）
- ❌ Logger 注入重构
- ❌ 委托类单测
- ❌ 任何 UI 行为改动

---

## 2. 目标与非目标

### 目标
1. **`ServerConfig` Hilt @Singleton class**：构造注入 `OkHttpClient`，持有 `baseUrl: StateFlow<String>`
2. **`setSharedClient` 桥接删除**：消除 Round 17 init 顺序坑
3. **Retrofit 依赖完全移除**：`build.gradle.kts` 删除 retrofit + converter-gson，`MediaApi.kt` 删除
4. **5 处调用方迁移**：MediaRepository / ConnectionViewModel / HomeViewModel / HomeScreen / HomeViewModelTest
5. **APK 体积减少 ~400KB**：Retrofit + converter-gson 不再打包
6. **零行为变化**：所有现有 JVM 测试不回归

### 非目标
- ❌ Coil 升级
- ❌ Logger 注入重构
- ❌ 委托类单测
- ❌ 任何 UI 行为改动
- ❌ 服务端 / Web 改动

---

## 3. 架构与文件清单

### 3.1 文件改动矩阵（3 个 commit）

| Commit | 文件 | 改动类型 | 说明 |
|---|---|---|---|
| C1 | `network/ServerConfig.kt` | **新增** | Hilt @Singleton class，替换 RetrofitClient |
| C1 | `network/RetrofitClient.kt` | **删除** | 被 ServerConfig 替换 |
| C1 | `network/MediaApi.kt` | **删除** | 未使用 |
| C1 | `data/MediaRepository.kt` | 改 | 构造加 `serverConfig: ServerConfig`，baseUrl 从 `serverConfig.getBaseUrl()` |
| C1 | `viewmodel/ConnectionViewModel.kt` | 改 | 加 `serverConfig` 注入，删 `setSharedClient + initialize`，改为 `serverConfig.setBaseUrl(url)` |
| C1 | `viewmodel/HomeViewModel.kt` | 改 | 加 `serverConfig` 注入，`RetrofitClient.*` → `serverConfig.*` |
| C1 | `viewmodel/BrowseViewModel.kt` | 改 | 构造加 `serverConfig`（间接传递给 repository / delegates 的 URL builders） |
| C1 | `test/.../HomeViewModelTest.kt` | 改 | 删 `resetRetrofitClient()` helper，改用 `ServerConfig(OkHttpClient())` 实例 |
| C2 | `viewmodel/HomeViewModel.kt` | 改 | 暴露 `val serverBaseUrl: StateFlow<String> = serverConfig.baseUrl` |
| C2 | `ui/screen/HomeScreen.kt` | 改 | `RetrofitClient.getBaseUrl()` → `homeViewModel.serverBaseUrl.collectAsState()` |
| C3 | `app/build.gradle.kts` | 改 | 删 `retrofit:2.9.0` + `converter-gson:2.9.0` |
| C3 | `app/proguard-rules.pro` | 改 | 删 Retrofit 相关 keep 规则（如有） |

### 3.2 关键约束

- `ServerConfig` 是 `@Singleton class`（非 `internal`，跨 package 注入）
- `httpClient` 暴露为 `val`（public）—— ConnectionViewModel 的 LAN scan 用 `serverConfig.httpClient.newBuilder()` 衍生短超时 client
- `baseUrl` 用 `StateFlow<String>`（让 HomeScreen `collectAsState`）
- `setBaseUrl(url)` 内置 dedup（同 URL 不重设）
- `setSharedClient` 完全删除——不再需要
- `initialize()` 改名为 `setBaseUrl()`（更准确描述职责）
- `isInitialized()` 保持（HomeViewModel 用）
- 所有 ViewModels 通过 `@Inject constructor` 获取 `ServerConfig`（不通过 RetrofitClient 全局 object）
- 现有测试全过

---

## 4. 实现细节

### 4.1 ServerConfig（新增，替换 RetrofitClient）

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

### 4.2 调用方迁移

**MediaRepository.kt:**
```kotlin
class MediaRepository @Inject constructor(
    private val http: OkHttpClient,
    private val serverConfig: ServerConfig,
) {
    private val baseUrl get() = serverConfig.getBaseUrl()
    // 其余不变
}
```

**ConnectionViewModel.kt:**
```kotlin
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val serverConfig: ServerConfig,
) : ViewModel() {
    // 原: RetrofitClient.setSharedClient(httpClient); RetrofitClient.initialize(url)
    // 新: serverConfig.setBaseUrl(url)
    // LAN scan 原超时衍生: httpClient.newBuilder() → serverConfig.httpClient.newBuilder()
}
```

> ConnectionViewModel 不再需要单独注入 `httpClient: OkHttpClient`——通过 `serverConfig.httpClient` 取。

**HomeViewModel.kt:**
```kotlin
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val serverConfig: ServerConfig,
) : ViewModel() {
    val serverBaseUrl: StateFlow<String> = serverConfig.baseUrl

    fun checkAndInitialize(serverUrl: String): Boolean {
        if (serverUrl.isBlank()) return serverConfig.isInitialized()
        if (!serverConfig.isInitialized() || serverConfig.getBaseUrl() != serverUrl) {
            serverConfig.setBaseUrl(serverUrl)
        }
        return true
    }
}
```

**BrowseViewModel.kt:**
```kotlin
@HiltViewModel
class BrowseViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    favoritesStore: FavoritesStore,
    recentActivityStore: RecentActivityStore,
    downloadsStore: DownloadsStore,
    repository: MediaRepository,
    downloadManager: DownloadManager,
    private val serverConfig: ServerConfig,  // ← 新增
) : ViewModel() {
    // navigator 和 controller 不变——它们用 repository（已注入 serverConfig）
    // 但 URL builders 直接调 repository.getMediaStreamUrl / getThumbnailUrl，
    // 后者内部调 RetrofitClient.getBaseUrl() → 需改为 serverConfig.getBaseUrl()
}
```

> BrowseViewModel 的 URL builders 调 `repository.getVideoStreamUrl()` 等，这些内部用 `RetrofitClient.getBaseUrl()`。重构后 repository 直接持有 `serverConfig`，`baseUrl get() = serverConfig.getBaseUrl()`。BrowseViewModel 本身不需直接注入 `serverConfig`，除非 BrowseNavigator / delegates 直接构建 URL（而非通过 repository）。需检查 BrowseNavigator 的 URL builders 是否直接调 `RetrofitClient.getBaseUrl()`。

**HomeScreen.kt:**
```kotlin
@Composable
fun HomeScreen(...) {
    val homeViewModel: HomeViewModel = hiltViewModel()
    val serverBaseUrl by homeViewModel.serverBaseUrl.collectAsState()
    // 原: val url = RetrofitClient.getBaseUrl()
    // 新: val url = serverBaseUrl
}
```

### 4.3 HomeViewModelTest 调整

```kotlin
// 原: resetRetrofitClient() 反射清理 sharedClient / _retrofit / _baseUrl
// 新: 直接 new ServerConfig(OkHttpClient()) 实例，无需反射清理

class HomeViewModelTest {
    private lateinit var serverConfig: ServerConfig
    // ...
    @Before fun setup() {
        serverConfig = ServerConfig(OkHttpClient())
        // ... homeViewModel = HomeViewModel(serverConfig, ...)
    }
    // resetRetrofitClient() 删除
}
```

### 4.4 Retrofit 依赖移除（C3）

**`build.gradle.kts` 删除：**
```kotlin
// 删除:
implementation("com.squareup.retrofit2:retrofit:2.9.0")
implementation("com.squareup.retrofit2:converter-gson:2.9.0")
```

**`MediaApi.kt` 删除**（整个文件）——项目从未使用，所有 API 调用走 OkHttp+Gson 手写。

**`proguard-rules.pro`** ——检查是否有 Retrofit 相关 keep 规则，如有删除。

---

## 5. 测试

### 5.1 测试矩阵

| Commit | 现有测试 | 新测试 | 验证 |
|---|---|---|---|
| C1 ServerConfig + 调用方 | HomeViewModelTest / BrowseViewModelTest / 其他 JVM 57 tests 全过 | 无 | 行为不变 |
| C2 HomeScreen 暴露 | 同上 | 无 | collectAsState 正确 |
| C3 Retrofit 移除 | 同上 + assembleDebug BUILD SUCCESSFUL | 无 | 依赖移除不 break 编译 |

### 5.2 真机/模拟器手工回归

- 连接页输入 server URL → HomeViewModel.checkAndInitialize → serverConfig.setBaseUrl
- HomeScreen 显示 serverBaseUrl（collectAsState）
- 浏览文件夹 → MediaRepository 用 serverConfig.getBaseUrl() 拼请求 URL
- ConnectionViewModel LAN scan 用 serverConfig.httpClient.newBuilder()

---

## 6. 实现顺序与提交策略

3 个 commit：

1. **C1 ServerConfig + 调用方迁移**：新增 ServerConfig.kt，删除 RetrofitClient.kt + MediaApi.kt，改 4 个调用方 + 1 个测试
2. **C2 HomeScreen ViewModel 暴露**：HomeViewModel 加 serverBaseUrl，HomeScreen collectAsState
3. **C3 Retrofit 依赖移除**：build.gradle.kts 删 retrofit + converter-gson

每个 commit 后：`cd android && ./gradlew assembleDebug :app:testDebugUnitTest` 全过。

---

## 7. 已决策点

| 决策 | 选择 | 理由 |
|---|---|---|
| 类名 | `ServerConfig` | 更准确（仅存 baseUrl，不用 Retrofit） |
| 模式 | Hilt `@Singleton class` | 消除 `setSharedClient` 桥接 + mutable state |
| `httpClient` 暴露 | `val`（public） | ConnectionViewModel LAN scan 用 `newBuilder()` 衍生，避免额外 Hilt 注入 |
| baseUrl 暴露 | `StateFlow<String>` + `getBaseUrl()` 双接口 | StateFlow 让 HomeScreen `collectAsState`；getBaseUrl 让 MediaRepository 同步取 |
| `setBaseUrl` dedup | 有（同 URL 不重设） | 避免 StateFlow 不必要 emit |
| `initialize()` → `setBaseUrl()` | 改名 | 更准确描述职责 |
| Retrofit 依赖 | 完全移除 | MediaApi 未使用 |
| MediaApi.kt | 删除 | 未使用 |
| HomeScreen 取 baseUrl | 通过 HomeViewModel.serverBaseUrl collectAsState | Composable 不能直接注入 Hilt |
| 提交粒度 | 3 个 commit | C1 核心迁移，C2 HomeScreen，C3 依赖移除 |

---

## 8. 已知限制（接受）

1. **`ServerConfig.httpClient` 暴露为 public val**：轻微违反"单一职责"（ServerConfig 应该只管 baseUrl），但让 ConnectionViewModel 不需重复注入 OkHttpClient。可接受。
2. **`StateFlow` 可能过度设计**：HomeScreen 的 baseUrl 基本不变（设置一次后），StateFlow 的响应式优势不大。但保留以备未来 UI 需要动态显示 baseUrl。
3. **`DEFAULT_TIMEOUT` 常量删除**：原 RetrofitClient 的 `DEFAULT_TIMEOUT = 30L` 现在无引用（OkHttpModule 已设 30s），删除。

---

## 9. 非目标（再次明确）

- ❌ Coil 升级
- ❌ Logger 注入重构
- ❌ 委托类单测
- ❌ 任何 UI 行为改动
- ❌ 服务端 / Web 改动
- ❌ BrowseViewModel 委托拆分（Round 18 已做）

---

## 10. 后续轮次（不在本 spec，仅备忘）

- **委托类单测**（Round 18 follow-up）
- **Logger 注入重构**（Round 12 follow-up）
- **Coil v3 升级**（Round 12 follow-up）
- **Web Vitest + JSDOM**（Round 6 follow-up）
- **GitHub Actions CI**（用户明确不做）
