# Android 内存/性能核心（Round 4）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 降低 Android 客户端的内存峰值与无谓重组——Coil 内存缓存调大、favorites 解码一次、图片预览解码上限、网格卡片回调稳定化。

**Architecture:** 仅 Android、不动服务端，5 个任务。Task 1 调 Coil 缓存；Task 2 用新增 Hilt `@ApplicationScope` 把 `FavoritesStore` 的解码做成共享 `StateFlow` 并派生（纯函数抽出来单测）；Task 3 给 `ImagePreviewScreen` 的 `ImageRequest` 设有界解码尺寸；Task 4 在 `BrowseScreen`/`BrowseContent` 用 `remember` 稳定化回调；Task 5 真机/模拟器运行时回归。

**Tech Stack:** Kotlin / Jetpack Compose / MVVM / Coroutines / Coil / Hilt / JUnit4。

## Global Constraints

- **提交策略**（`AGENTS.md`）：本地改动自动同步推送至 GitHub `master`。所有提交直接在 `master`，**不开 feature 分支**；conventional commit + `Co-Authored-By: Claude <noreply@anthropic.com>` 尾注。
- **Kotlin 规则**（`AGENTS.md`）：Jetpack Compose / MVVM；异步 Coroutines；图片 Coil；DI Hilt。
- **测试**：JUnit4（`org.junit.Test` + `org.junit.Assert.*`），backtick 测试名，**纯 JVM 单测**放 `app/src/test/java/...`、不依赖 Android 框架。运行时行为（OOM / Compose 重组）靠真机/模拟器手工回归。
- **构建**：`cd android && ./gradlew testDebugUnitTest assembleDebug`。中国大陆网络拉依赖失败时配 gradle 代理。
- **行为约束**：**不动服务端**；`LocalMediaHubApplication` 的 `respectCacheHeaders(false)` 等其它配置不动；**不改** `VideoCard`/`ImageCard`/`FolderCard` 签名。
- **范围外**（spec §2 非目标）：OkHttp/Coil 网络缓存、旋转屏/ExoPlayer 状态持久化、`getFileTags` 全库拉取、服务端 medium 端点。

## File Structure

- 修改 `android/app/src/main/java/com/juziss/localmediahub/LocalMediaHubApplication.kt` — Coil 内存缓存 15%→25%。
- 新增 `android/app/src/main/java/com/juziss/localmediahub/di/CoroutineScopesModule.kt` — Hilt `@ApplicationScope`。
- 修改 `android/app/src/main/java/com/juziss/localmediahub/data/FavoritesStore.kt` — 解码一次 + 派生（纯函数 `favoriteEntriesToPaths`/`favoriteEntriesToFiles`）。
- 修改 `android/app/src/test/java/com/juziss/localmediahub/data/FavoritesStoreTest.kt` — 纯函数单测。
- 修改 `android/app/src/main/java/com/juziss/localmediahub/ui/screen/ImagePreviewScreen.kt` — `ZoomableImageItem` 解码上限。
- 修改 `android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt` — 屏幕级 `remember` 回调。
- 修改 `android/app/src/main/java/com/juziss/localmediahub/ui/component/BrowseContent.kt` — 每项 `remember`（含 `BrowseContent`/`FavoritesContent`/`SearchContent`）。

---

## Task 1: Coil 内存缓存 15%→25%

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/LocalMediaHubApplication.kt:24-28`

**Interfaces:** 无（独立 1 行改动）。

- [ ] **Step 1: 改缓存比例**

将 `LocalMediaHubApplication.kt` 第 24-28 行：

```kotlin
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15) // Memory cache capped at 15%
                    .build()
            }
```

替换为：

```kotlin
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25) // 15% → 25%：全屏图片位图更多余量，减少滚动淘汰/重解码
                    .build()
            }
```

- [ ] **Step 2: 编译**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/LocalMediaHubApplication.kt
git commit -m "perf(android): raise Coil memory cache from 15% to 25%

More headroom for full-screen image bitmaps; fewer evictions/re-decodes while
scrolling galleries.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 2: favorites 单次解码（Hilt `@ApplicationScope` + `stateIn` + 派生）

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/di/CoroutineScopesModule.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/FavoritesStore.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/data/FavoritesStoreTest.kt`

**Interfaces:**
- Produces（包级纯函数，可单测）：`favoriteEntriesToPaths(entries: List<FavoriteMediaEntry>): Set<String>`、`favoriteEntriesToFiles(entries: List<FavoriteMediaEntry>): List<MediaFile>`。
- `FavoritesStore` 构造新增 `@ApplicationScope scope: CoroutineScope`（由 Hilt 注入）；`favoriteEntries` 变为共享 `StateFlow`，`favorites`/`favoriteFiles` 派生。消费方（`HomeViewModel`/`BrowseViewModel`）`collect` 不变。

- [ ] **Step 1: 写失败测试（纯函数）**

在 `FavoritesStoreTest.kt` 末尾的 `}` 之前追加：

```kotlin
    @Test
    fun `favoriteEntriesToPaths maps entries to their relative-path set`() {
        val a = MediaFile("a.jpg", "p/a.jpg", "p/a.jpg", 1L, "", "image", ".jpg")
        val b = MediaFile("b.mp4", "p/b.mp4", "p/b.mp4", 1L, "", "video", ".mp4")
        val entries = listOf(
            FavoriteMediaEntry(a, true),
            FavoriteMediaEntry(b, false),
        )

        val paths = favoriteEntriesToPaths(entries)

        assertEquals(setOf("p/a.jpg", "p/b.mp4"), paths)
    }

    @Test
    fun `favoriteEntriesToFiles preserves order and file payload`() {
        val a = MediaFile("a.jpg", "p/a.jpg", "p/a.jpg", 1L, "", "image", ".jpg")
        val entries = listOf(FavoriteMediaEntry(a, true))

        val files = favoriteEntriesToFiles(entries)

        assertEquals(1, files.size)
        assertEquals("p/a.jpg", files[0].relativePath)
    }

    @Test
    fun `derived helpers handle empty lists`() {
        assertEquals(emptySet<String>(), favoriteEntriesToPaths(emptyList()))
        assertEquals(emptyList<MediaFile>(), favoriteEntriesToFiles(emptyList()))
    }
```

> `FavoriteMediaEntry`/`MediaFile` 已是 `data class`，构造参数顺序：`MediaFile(name, path, relativePath, size, modifiedTime, mediaType, extension)`、`FavoriteMediaEntry(file, isSystemBrowse)`。若签名不符，按实际字段名构造。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd android && ./gradlew testDebugUnitTest --tests "*.FavoritesStoreTest"`
Expected: 编译失败，提示 `favoriteEntriesToPaths` / `favoriteEntriesToFiles` unresolved。

- [ ] **Step 3: 新增 Hilt `@ApplicationScope` 模块**

新建 `android/app/src/main/java/com/juziss/localmediahub/di/CoroutineScopesModule.kt`：

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

- [ ] **Step 4: 改 `FavoritesStore` 解码一次 + 派生**

在 `FavoritesStore.kt` import 块加入：`kotlinx.coroutines.flow.SharingStarted`、`kotlinx.coroutines.flow.stateIn`、`com.juziss.localmediahub.di.ApplicationScope`、`javax.inject.Inject`（已在）。

文件末尾（`class FavoritesStore` 的最后一个 `}` 之后）追加两个纯函数：

```kotlin
/** 派生：收藏条目 → 相对路径集（无 JSON 解码）。 */
internal fun favoriteEntriesToPaths(entries: List<FavoriteMediaEntry>): Set<String> =
    entries.map { it.file.relativePath }.toSet()

/** 派生：收藏条目 → 文件列表（无 JSON 解码）。 */
internal fun favoriteEntriesToFiles(entries: List<FavoriteMediaEntry>): List<MediaFile> =
    entries.map { it.file }
```

把 `FavoritesStore` 类签名与三个 Flow 改为（构造加 scope；解码一次 + stateIn；派生用上面的纯函数）：

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
        favoriteEntries.map(::favoriteEntriesToPaths)

    /** 派生：文件列表（无 JSON 解码）。 */
    val favoriteFiles: Flow<List<MediaFile>> =
        favoriteEntries.map(::favoriteEntriesToFiles)

    // addFavorite / removeFavorite / toggleFavorite / toJson 保持原样不动。
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd android && ./gradlew testDebugUnitTest --tests "*.FavoritesStoreTest"`
Expected: BUILD SUCCESSFUL，新增 3 个用例 PASS（既有 2 个 decodeFavoriteEntry 用例不受影响）。

- [ ] **Step 6: 全量单测 + Debug 构建（验证 Hilt 接线）**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL（Hilt 生成 `@ApplicationScope` 绑定；`HomeViewModel`/`BrowseViewModel` 的 `collect` 不需改动）。

- [ ] **Step 7: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/di/CoroutineScopesModule.kt android/app/src/main/java/com/juziss/localmediahub/data/FavoritesStore.kt android/app/src/test/java/com/juziss/localmediahub/data/FavoritesStoreTest.kt
git commit -m "perf(android): decode favorites once via shared StateFlow

FavoritesStore exposed three flows that each JSON-decoded the same DataStore
data; Home/Browse ViewModels collected multiple, so a single toggle triggered
several decodes. Decode once into a shared StateFlow (stateIn on a new Hilt
@ApplicationScope) and derive the path-set and file-list from it (pure, unit-
tested helpers). Consumers' collect calls are unchanged.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 3: `ImagePreviewScreen` OOM 解码上限

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/ImagePreviewScreen.kt`（`ZoomableImageItem` 约 `:138-205`；import 块）

**Interfaces:** 无外部接口变化（`ZoomableImageItem` 内部改造）。

> 无单测（运行时图片解码）；靠 `assembleDebug` 编译 + Task 5 真机回归。

- [ ] **Step 1: 加 import**

在 `ImagePreviewScreen.kt` import 块加入：

```kotlin
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil.request.ImageRequest
import kotlin.math.min
```

（`androidx.compose.ui.unit.dp`、`coil.compose.AsyncImage` 已在。）

- [ ] **Step 2: 改 `ZoomableImageItem` 用有界 `ImageRequest`**

将 `ZoomableImageItem`（约 `:138`）函数体开头的：

```kotlin
    var scale by remember(file.relativePath) { mutableFloatStateOf(1f) }
    var offset by remember(file.relativePath) { mutableStateOf(Offset.Zero) }
    var hasMoved by remember { mutableStateOf(false) }
```

之前插入（获取屏尺寸、构造有界请求）：

```kotlin
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val cap = 2048
    val reqWidth = min(with(density) { configuration.screenWidthDp.dp.toPx() }.toInt(), cap)
    val reqHeight = min(with(density) { configuration.screenHeightDp.dp.toPx() }.toInt(), cap)
    val request = remember(imageUrl, reqWidth, reqHeight) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .size(reqWidth, reqHeight)
            .build()
    }
```

并把函数体末尾的 `AsyncImage(model = imageUrl, ...)`：

```kotlin
        AsyncImage(
            model = imageUrl,
            contentDescription = file.name,
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth,
        )
```

改为：

```kotlin
        AsyncImage(
            model = request,
            contentDescription = file.name,
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth,
        )
```

（`pointerInput`/`graphicsLayer`/scale/offset 逻辑全部不动。）

- [ ] **Step 3: 编译**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/screen/ImagePreviewScreen.kt
git commit -m "fix(android): cap image-preview decode size to prevent OOM

ZoomableImageItem loaded /original via AsyncImage with no decode-size bound, so
very tall originals decoded to huge bitmaps and OOMed on low-heap devices. Build
an ImageRequest with a screen-size, long-edge-capped (2048) size so Coil decodes
to a bounded bitmap. Zoom is unchanged (already graphicsLayer-scaled).

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 4: Compose 回调稳定性（屏幕级 + 每项 `remember`）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/BrowseContent.kt`（含 `BrowseContent`/`FavoritesContent`/`SearchContent`）

**Interfaces:** 无外部接口变化（回调仍是同类型 lambda，只是改成稳定引用 + 每项 remember）。

> 无单测（重组是运行时行为）；靠 `assembleDebug` 编译 + Task 5 真机回归（Layout Inspector 重组计数可选）。

- [ ] **Step 1: `BrowseScreen` 屏幕级 `remember` 回调**

在 `BrowseScreen.kt` 的 Scaffold 内容里、`var itemForActions by remember { mutableStateOf<Any?>(null) }`（约 `:299`）**之后**插入：

```kotlin
        // Stable callback refs so the grid content can skip recomposition when
        // these don't change (isFavorite only rebuilds when the favorites set does).
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

- [ ] **Step 2: 替换 `BrowseScreen` 内 5 处调用点的内联 lambda**

把以下 5 个 `*Content(...)` 调用里的三个回调替换为上面的稳定引用（其余参数不动）：

- `SearchContent(...)`（约 `:555`）：`onToggleFavorite = onToggleFavoriteCb`、`isFavorite = isFavoriteCb`、`onFileLongClick = onFileLongClickCb`。
- `FavoritesContent(...)`（约 `:596`）：同上三项。
- `BrowseContent(...)` 的 `SystemBrowsed` 分支（约 `:710`）：同上三项。
- `BrowseContent(...)` 的 `Browsed` 分支（约 `:758`）：同上三项。
- `BrowseContent(...)` 的 `TagCollection` 分支（约 `:803`）：`onToggleFavorite = onToggleFavoriteCb`、`isFavorite = isFavoriteCb`、`onFileLongClick = onFileLongClickCb`（该分支无 `onFolderLongClick`）。

（`onVideoClick`/`onImageClick`/`onFolderClick` 保持原样——它们不捕获会变化的 `favorites`。）

- [ ] **Step 3: `BrowseContent` 的 `items{}` 每项 `remember`**

在 `BrowseContent.kt` 的 `BrowseContent` 函数里，把 `items(files, key = { it.relativePath }, contentType = { it.mediaType }) { file -> ... }`（约 `:293-312`）整体替换为：

```kotlin
                items(files, key = { it.relativePath }, contentType = { it.mediaType }) { file ->
                    val toggle = remember(file, onToggleFavorite) { { onToggleFavorite(file) } }
                    val longClick = remember(file, onFileLongClick) { { onFileLongClick(file) } }
                    when (file.mediaType) {
                        "video" -> VideoCard(
                            file = file,
                            thumbnailUrl = viewModel.getThumbnailUrl(file),
                            isFavorite = isFavorite(file.relativePath),
                            onToggleFavorite = toggle,
                            onClick = remember(file, onVideoClick) { { onVideoClick(file) } },
                            onLongClick = longClick,
                        )
                        "image" -> ImageCard(
                            file = file,
                            thumbnailUrl = viewModel.getThumbnailUrl(file),
                            isFavorite = isFavorite(file.relativePath),
                            onToggleFavorite = toggle,
                            onClick = remember(file, onImageClick) { { onImageClick(file) } },
                            onLongClick = longClick,
                        )
                    }
                }
```

- [ ] **Step 4: `FavoritesContent` 的 `items{}` 每项 `remember`**

在 `BrowseContent.kt` 的 `FavoritesContent` 函数里，把 `items(favoriteFiles, key = { it.relativePath }) { file -> ... }`（约 `:88-107`）整体替换为：

```kotlin
            items(favoriteFiles, key = { it.relativePath }) { file ->
                val toggle = remember(file, onToggleFavorite) { { onToggleFavorite(file) } }
                val longClick = remember(file, onFileLongClick) { { onFileLongClick(file) } }
                when (file.mediaType) {
                    "video" -> VideoCard(
                        file = file,
                        thumbnailUrl = getThumbnailUrl(file),
                        isFavorite = isFavorite(file.relativePath),
                        onToggleFavorite = toggle,
                        onClick = remember(file, onVideoClick) { { onVideoClick(file) } },
                        onLongClick = longClick,
                    )
                    "image" -> ImageCard(
                        file = file,
                        thumbnailUrl = getThumbnailUrl(file),
                        isFavorite = isFavorite(file.relativePath),
                        onToggleFavorite = toggle,
                        onClick = remember(file, onImageClick, favoriteFiles) { { onImageClick(file, favoriteFiles) } },
                        onLongClick = longClick,
                    )
                }
            }
```

- [ ] **Step 5: `SearchContent` 的 `items{}` 每项 `remember`**

在 `BrowseContent.kt` 的 `SearchContent` 函数里，把文件 `items(result.files, key = { it.relativePath }) { file -> ... }`（约 `:165-184`）整体替换为：

```kotlin
                    items(result.files, key = { it.relativePath }) { file ->
                        val toggle = remember(file, onToggleFavorite) { { onToggleFavorite(file) } }
                        val longClick = remember(file, onFileLongClick) { { onFileLongClick(file) } }
                        when (file.mediaType) {
                            "video" -> VideoCard(
                                file = file,
                                thumbnailUrl = getThumbnailUrl(file),
                                isFavorite = isFavorite(file.relativePath),
                                onToggleFavorite = toggle,
                                onClick = remember(file, onVideoClick) { { onVideoClick(file) } },
                                onLongClick = longClick,
                            )
                            "image" -> ImageCard(
                                file = file,
                                thumbnailUrl = getThumbnailUrl(file),
                                isFavorite = isFavorite(file.relativePath),
                                onToggleFavorite = toggle,
                                onClick = remember(file, onImageClick) { { onImageClick(file) } },
                                onLongClick = longClick,
                            )
                        }
                    }
```

> `WaterfallImageGrid`（`FavoritesContent` 的纯图片分支）内部为独立组件，本轮不改；如需可后续顺带稳定化。

- [ ] **Step 6: 编译**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 7: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt android/app/src/main/java/com/juziss/localmediahub/ui/component/BrowseContent.kt
git commit -m "perf(android): stabilize browse grid callbacks to skip recomposition

BrowseScreen passed freshly-built lambdas (isFavorite capturing the favorites
set, onToggleFavorite, onFileLongClick) into the grid content on every
recomposition, and each items{} block wrapped per-item callbacks anew, so cards
could not be skipped. remember the screen-level callbacks (isFavorite keyed on
the favorites set) and the per-item callbacks so cards are skippable. No card
signatures change.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 5: 真机/模拟器运行时回归（无代码改动）

**Files:** 无改动（纯验证）。> OOM 与 Compose 重组是运行时行为，必须真机/模拟器。

- [ ] **Step 1: 装包**

Run: `cd android && ./gradlew assembleDebug`（产出 APK），安装到真机/模拟器。

- [ ] **Step 2: OOM 回归**

打开一个含大量图片、且至少一张**超长图**（如长截图）的目录 → 进入图片预览 → 快速左右滑 + 双指缩放 → **不崩溃**；观察内存平稳（Profiler 或 `adb shell dumpsys meminfo <pkg>` 看 Java heap 不持续飙升至 OOM）。

- [ ] **Step 3: favorites 回归**

在浏览网格里点收藏/取消 → 收藏图标即时翻转；进入"只看收藏"、首页收藏区均正确更新；连续多次 toggle 无明显卡顿。

- [ ] **Step 4: Compose 重组（可选，Layout Inspector）**

浏览网格时触发一次无关重组（如弹一个 toast / 切排序）→ 用 Layout Inspector 观察：可见卡片不应被全部标记为 recomposed（理想情况下 isFavorite 未变时卡片 skip）。

- [ ] **Step 5: 既有功能回归**

浏览（根目录/磁盘/子目录/面包屑返回）、搜索、标签筛选、排序、缩略图加载、视频播放均正常——确认 4 项改动无回归。

- [ ] **Step 6: 记录结果**

在交付说明记录：OOM 不再复现、收藏即时一致、（可选）重组下降、既有功能无回归。

---

## Self-Review（作者已执行）

**1. Spec 覆盖**：
- §1 Coil 缓存 15%→25% → Task 1。✅
- §2 favorites 单次解码（Hilt `@ApplicationScope` + `stateIn` + 派生）→ Task 2。✅
- §3 OOM 解码上限（`ImageRequest.size` 屏 px + 长边 2048）→ Task 3。✅
- §4 Compose 稳定（屏幕 + 每项 remember）→ Task 4。✅
- §7 测试与验证（JVM 单测 + 真机回归）→ Task 2 单测 + Task 5 真机回归。✅
- §9 决策（25%、stateIn+Hilt、不改卡片签名、size 屏 px+2048）→ 各任务落地。✅

**2. 占位符扫描**：无 TBD/TODO；每个代码步骤含完整代码；每条命令含期望输出；真机回归含具体步骤与观察点。✅

**3. 类型/签名一致性**：
- `favoriteEntriesToPaths(List<FavoriteMediaEntry>): Set<String>`、`favoriteEntriesToFiles(List<FavoriteMediaEntry>): List<MediaFile>` —— Task 2 Step 4 定义、Step 1 测试、`FavoritesStore.favorites/.favoriteFiles` 经 `::` 引用调用，一致。✅
- `FavoritesStore` 构造 `(context, @ApplicationScope scope)` —— Task 2 Step 4 定义；Hilt 由 Step 3 的 `CoroutineScopesModule` 提供 `@ApplicationScope`；消费方（`HomeViewModel`/`BrowseViewModel`，Hilt 注入 `FavoritesStore`）无需改构造。✅
- `BrowseScreen` 屏幕级回调 `onToggleFavoriteCb`/`isFavoriteCb`/`onFileLongClickCb` —— Step 1 定义、Step 2 五处调用引用，名称一致。✅
- 每项 `remember(file, <外层回调>)` 的外层回调名（`onToggleFavorite`/`onVideoClick`/`onImageClick`/`onFileLongClick`）与各 `*Content` 函数参数名一致。✅
- import 增补一致：`ImagePreviewScreen` +5（Task 3 Step 1）；`FavoritesStore` +3（Task 2 Step 4）。✅
