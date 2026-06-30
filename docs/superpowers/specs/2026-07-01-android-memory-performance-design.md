# Android 内存/性能核心设计（Memory & Perf · Round 4）

- **日期**: 2026-07-01
- **范围**: Android 客户端（`ImagePreviewScreen`、`FavoritesStore`、`HomeViewModel`、`BrowseScreen`、`BrowseContent/FavoritesContent/SearchContent`、`LocalMediaHubApplication`、新增 Hilt 模块）
- **策略**: A — 内存/性能核心 4 项
- **状态**: 待评审
- **前置**: Round 3 服务端已为缩略图/原图加 `Cache-Control`（本轮不依赖、亦不改动服务端）

---

## 1. 背景与动机

三端深度审计里，Android 客户端这半此前未动。内存/性能核心有 4 个实打实的问题，直接影响主使用场景（手机看图）：

- **OOM**：`ImagePreviewScreen` 的 `ZoomableImageItem`（`:138`）用 `AsyncImage(model = getOriginalUrl(file))`（`:198`）加载 `/original`，无解码尺寸上限。对超长图（如 1080×10000 截图）Coil 按"测得的超高 item"解码 → 巨型位图 → 低堆设备 OOM。Coil 内存缓存仅堆的 15%（`LocalMediaHubApplication.kt:26`）。
- **favorites 三次解码**：`FavoritesStore` 的 `favorites`/`favoriteFiles`/`favoriteEntries` 三个 Flow（`:50/57/64`）各自 `map` 同一 DataStore 数据、各调一次 `decodeFavoriteEntry`。`HomeViewModel` 同时 collect `favoriteFiles`+`favoriteEntries`（`:58/150`）、`BrowseViewModel` collect `favorites` → 一次 toggle 触发多次 JSON 解码。
- **Compose 重组**：`BrowseScreen` 传给 `BrowseContent` 的 `isFavorite = { relativePath -> relativePath in favorites }`（`:770` 等）、`onToggleFavorite = { viewModel.toggleFavorite(file) }`（`:769`）每帧新建；`BrowseContent` 的 `items{}` 里 `onToggleFavorite = { onToggleFavorite(file) }`（`:299`）每项新建 → 卡片回调 prop 不稳定 → 无法 skip → 无关重组时整网格卡片重组。

本轮只做这 4 项（内存/性能核心）。OkHttp/Coil 网络缓存、旋转屏/ExoPlayer 状态、`getFileTags` 全库拉取留后续轮次。

---

## 2. 目标与非目标

### 目标
1. **Coil 内存缓存** 15%→25%，给全屏图片位图更多余量。
2. **favorites 单次解码**：用共享 `StateFlow` 解码一次，派生路径集/文件列表。
3. **Compose 回调稳定性**：屏幕级 + 每项 `remember` 稳定化 lambda，让网格卡片恢复 skippable。
4. **OOM 解码上限**：`ImagePreviewScreen` 给 `ImageRequest` 显式有界 `size`，原图解码到屏尺寸而非原始。

### 非目标（留待后续轮次）
- OkHttp 响应缓存、Coil `respectCacheHeaders=true`（网络缓存簇）。
- 旋转屏 `rememberSaveable`、ExoPlayer 进程被杀保留进度（状态持久化簇）。
- `getFileTags()` 全库拉取优化。
- 服务端任何改动（含 round-3 提到的 medium/preview 端点）。

---

## 3. Coil 内存缓存 15%→25%

`LocalMediaHubApplication.kt:26`：

```kotlin
.memoryCache {
    MemoryCache.Builder(this)
        .maxSizePercent(0.25) // 15% → 25%：全屏图片位图更多余量，减少滚动淘汰/重解码
        .build()
}
```

1 行改动。`respectCacheHeaders(false)` 等其它配置不动。

---

## 4. favorites 单次解码

### 4.1 新增 Hilt `@ApplicationScope`

新文件 `android/app/src/main/java/com/juziss/localmediahub/di/CoroutineScopesModule.kt`：

```kotlin
package com.juziss.localmediahub.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutineScopesModule {
    @Provides
    @ApplicationScope
    @Singleton
    fun applicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
```

### 4.2 `FavoritesStore` 解码一次 + 派生

`FavoritesStore.kt` 注入 scope，把"解码全部"做成共享 `StateFlow`，`favorites`/`favoriteFiles` 从它派生（不再各自解码）：

```kotlin
class FavoritesStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val gson = Gson()
    private val favoritesKey = stringSetPreferencesKey("favorite_files_json")

    // 解码一次：所有收藏条目（含 access-mode）。
    private val decoded: Flow<List<FavoriteMediaEntry>> =
        context.favoritesDataStore.data.map { preferences ->
            preferences[favoritesKey]?.mapNotNull { json ->
                decodeFavoriteEntry(gson, json)
            } ?: emptyList()
        }

    /** 共享热流：解码只在 upstream 发生一次，多消费方共享。 */
    val favoriteEntries: Flow<List<FavoriteMediaEntry>> =
        decoded.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 派生：路径集（无 JSON 解码）。 */
    val favorites: Flow<Set<String>> =
        favoriteEntries.map { entries -> entries.map { it.file.relativePath }.toSet() }

    /** 派生：文件列表（无 JSON 解码）。 */
    val favoriteFiles: Flow<List<MediaFile>> =
        favoriteEntries.map { entries -> entries.map { it.file } }

    // addFavorite / removeFavorite / toggleFavorite / toJson 保持不变。
}
```

新增 import：`kotlinx.coroutines.flow.SharingStarted`、`kotlinx.coroutines.flow.stateIn`、`com.juziss.localmediahub.di.ApplicationScope`。

消费方（`HomeViewModel` collect `favoriteFiles`/`favoriteEntries`、`BrowseViewModel` collect `favorites`）**无需改动**——`StateFlow` 也是 `Flow`，`collect` 行为一致。

---

## 5. Compose 回调稳定性

### 5.1 屏幕级 lambda 稳定化（`BrowseScreen`）

把传给各 `*Content` 的回调用 `remember` 包起来，避免无关重组时新建。三者都需在 `itemForActions`（`BrowseScreen.kt:299`，位于 Scaffold 内容里）声明**之后**定义，以便 `onFileLongClickCb` 捕获它（`onToggleFavoriteCb`/`isFavoriteCb` 捕获的 `viewModel`/`favorites` 在该作用域同样可见）：

```kotlin
val onToggleFavoriteCb: (MediaFile) -> Unit = remember(viewModel) {
    { file -> viewModel.toggleFavorite(file) }
}
val isFavoriteCb: (String) -> Boolean = remember(favorites) {
    { relativePath -> relativePath in favorites }
}
val onFileLongClickCb: (MediaFile) -> Unit = remember {
    { file -> itemForActions = file }
}
```

> `isFavoriteCb` 以 `favorites` 为 key——收藏集变化时才重建（正确：此时网格本就该刷新）。`onToggleFavoriteCb` 以 `viewModel` 为 key（Hilt 注入，单例稳定）。`onFileLongClickCb` 捕获的是 `remember` 的 `MutableState` 委托，无 key 即稳定。

把 `SearchContent`/`FavoritesContent`/`BrowseContent`（SystemBrowsed/Browsed/TagCollection 共 4 处 `BrowseContent`）调用里的内联 lambda 换成上述稳定引用（`onToggleFavorite = onToggleFavoriteCb`、`isFavorite = isFavoriteCb`、`onFileLongClick = onFileLongClickCb`）。其余回调（`onVideoClick`/`onImageClick`/`onFolderClick` 等）按需同理 `remember`。

### 5.2 每项 lambda 稳定化（`BrowseContent`/`FavoritesContent`/`SearchContent` 的 `items{}`）

在每个 `items(...) { file -> ... }` 里，把传给卡片的每项回调用 `remember(file, <稳定外层回调>)` 包起来，使卡片 prop 稳定、可 skip：

```kotlin
items(files, key = { it.relativePath }, contentType = { it.mediaType }) { file ->
    val toggle = remember(file, onToggleFavorite) { { onToggleFavorite(file) } }
    val longClick = remember(file, onFileLongClick) { { onFileLongClick(file) } }
    when (file.mediaType) {
        "video" -> VideoCard(
            file = file,
            thumbnailUrl = viewModel.getThumbnailUrl(file),
            isFavorite = isFavorite(file.relativePath), // Boolean，稳定值
            onToggleFavorite = toggle,
            onClick = remember(file, onVideoClick) { { onVideoClick(file) } },
            onLongClick = longClick,
        )
        "image" -> ImageCard(/* 同理 */)
    }
}
```

`FavoritesContent`/`SearchContent` 的 `items{}` 同样处理。不改 `VideoCard`/`ImageCard`/`FolderCard` 签名（避免大面积动调用点）。

> `file`（`data class MediaFile`）、`thumbnailUrl`（String）、`isFavorite`（Boolean）本就稳定；稳定化三个 lambda 后卡片所有 prop 稳定 → 可 skip。

---

## 6. OOM 解码上限（`ImagePreviewScreen`）

`ZoomableImageItem`（`:138`）改为构造一个**有界 `ImageRequest`**——按屏宽/屏高 px、长边封顶 2048——让 Coil 把原图解码到该尺寸，而非按"超高 item"解码原始尺寸：

```kotlin
@Composable
private fun ZoomableImageItem(
    file: MediaFile,
    imageUrl: String,
    onTap: () -> Unit = {},
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val cap = 2048
    val reqWidth = min(
        with(density) { configuration.screenWidthDp.dp.toPx() }.toInt(),
        cap,
    )
    val reqHeight = min(
        with(density) { configuration.screenHeightDp.dp.toPx() }.toInt(),
        cap,
    )
    val request = remember(imageUrl, reqWidth, reqHeight) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .size(reqWidth, reqHeight) // 解码到屏尺寸（长边 ≤2048），非原始
            .build()
    }
    // …（scale/offset/pointerInput/graphicsLayer 不变）…
    AsyncImage(
        model = request,
        contentDescription = file.name,
        modifier = Modifier.fillMaxWidth(),
        contentScale = ContentScale.FillWidth,
    )
}
```

新增 import：`androidx.compose.ui.platform.LocalContext`、`LocalConfiguration`、`LocalDensity`、`androidx.compose.ui.unit.dp`、`coil.request.ImageRequest`、`kotlin.math.min`。

效果：原始 1080×10000 → 解码到 ~屏尺寸（长边 ≤2048）→ 位图有界。配合 §3 缓存余量，OOM 基本消除。zoom 体验不变（本就是 `graphicsLayer` 缩放、放大本就模糊；解码上限不降质）。

> 显示高度（超长图仍渲染为超高 item）是另一 UX 议题，不在本轮；解码上限解决的是内存。

---

## 7. 测试与验证

### 7.1 JVM 单测（`./gradlew testDebugUnitTest`）
- **favorites 派生正确性**：扩展/新增 `FavoritesStoreTest`——写入若干 `FavoriteMediaEntry`，断言 `favorites`（路径集）、`favoriteFiles`、`favoriteEntries` 三个派生流一致、且只解码一次（可借由 `decodeFavoriteEntry` 的可观测性或等价数据断言）。需为 `FavoritesStore` 提供 `@ApplicationScope` 测试 scope（如 `TestScope(UnconfinedTestDispatcher())`）。
- 现有 `FavoritesStoreTest` 若直接 new `FavoritesStore(context)` 需补传测试 scope。

### 7.2 构建
`./gradlew assembleDebug` 必须成功（验证 Hilt 模块、import、Composable 改动编译通过）。

### 7.3 真机/模拟器运行时回归（手动）
> OOM 与 Compose 重组是运行时行为，必须真机/模拟器验证。
- **OOM**：打开一个含超长图（或大量大图）的目录 → 进入图片预览、快速左右滑 → 不崩溃、内存平稳（用 Profiler/`dumpsys meminfo` 观察）。
- **Compose 重组**（可选，靠 Layout Inspector 的 recomposition 计数）：浏览网格时无关重组（如弹 toast）不应导致可见卡片全部重组。
- **favorites**：在网格里收藏/取消 → 收藏图标即时翻转、首页收藏区更新；无重复解码导致的卡顿。
- **回归**：浏览/搜索/收藏/标签/排序/缩略图加载等既有交互正常。

---

## 8. 实现顺序与提交策略

按内聚度分次提交、每次 `./gradlew testDebugUnitTest assembleDebug`：

1. **Coil 缓存（§3）**：1 行。最低风险、先落地。
2. **favorites 单解码（§4）**：新增 Hilt 模块 + 改 `FavoritesStore` + 调整既有 `FavoritesStoreTest`。补派生流单测。
3. **OOM 解码上限（§6）**：改 `ImagePreviewScreen.ZoomableImageItem`。
4. **Compose 稳定性（§5）**：改 `BrowseScreen` + `BrowseContent`/`FavoritesContent`/`SearchContent`。最末，因触及多处调用点。

每步可独立编译/提交。运行时回归（7.3）在全部落地后一次性真机验证。

---

## 9. 已决策点

| 决策 | 选择 | 理由 |
|---|---|---|
| 方案 | A（内存/性能核心 4 项） | 覆盖完整、纯 Android、不动服务端 |
| Coil 内存缓存 | 15%→25% | 全屏位图余量；25% 是常用上限 |
| favorites 单解码 | `stateIn` + 新增 Hilt `@ApplicationScope` | 解码只发生一次、多消费方共享；标准模式 |
| Compose 稳定 | 屏幕 + 每项 `remember`，不改卡片签名 | 风险低、避免大面积动调用点；卡片可 skip |
| OOM 解码上限 | `ImageRequest.size(屏 px, 长边 ≤2048)` | 原图解码到屏尺寸、位图有界；zoom 不降质 |
| 网络缓存 / 状态持久化 / getFileTags | 不做（YAGNI，留后续轮次） | 聚焦内存/性能核心 |

---

## 10. 后续轮次（不在本 spec，仅备忘）

- **网络缓存**：OkHttp 响应缓存 + Coil `respectCacheHeaders=true`（接住 round-3 服务端缓存头）。
- **状态持久化**：旋转屏 `rememberSaveable`（不丢播放/预览载荷）+ ExoPlayer 进程被杀保留"继续播放"。
- **`getFileTags()` 全库拉取**：服务端加 tag-count 端点或客户端缓存。
- **架构**：`BrowseViewModel`（~850 行）/`app.js`（~1320 行）/`RetrofitClient` 拆分。
- **服务端读取热路径**：扫描器按类型缓存、scoped 搜索去重复 normalize、`DownloadFolderZip` FD/压缩。
