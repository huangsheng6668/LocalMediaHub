# BrowseViewModel 委托类拆分设计（Round 18）

- **日期**: 2026-07-06
- **范围**: Android 客户端 `viewmodel/` — BrowseViewModel（749 行）拆分为 6 个委托类 + 1 个共享 state holder
- **策略**: 委托类模式（delegates），ViewModel 持有共享 state，委托读写；UI 零改动
- **状态**: 待评审
- **前置**: Round 10（BrowseScreen/BrowseContent UI 解耦）；Round 4（Compose 回调稳定性）；Round 17（OkHttp Hilt 单例）

---

## 1. 背景与动机

`android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt` 是 **749 行 / 46 个函数**，承担 **8 大职责**：

1. **Browse navigation**（loadRoots/browseFolder/navigateBack/loadSystemDrives/browseSystemPath/refresh）— ~180 行
2. **Sort**（setFolderSortOrder/setFileSortOrder + applyFolderResult/applySystemResult）— ~120 行
3. **Favorites**（toggle/filter/showFavoritesOnly/isFavorite + 3 init collectors）— ~60 行
4. **Tags**（loadTags/createTag/deleteTag/tagFile/untagFile/openCollection/loadFileTags + 4 state flows）— ~140 行
5. **Search**（updateSearchQuery/search/clearSearch）— ~40 行
6. **Downloads**（removeDownload/removeDownloads/downloadFile/downloadFolder）— ~80 行
7. **Delete**（clearDeleteState/deletePath/deletePathSync）— ~30 行
8. **URL builders + Scroll position + Toast**（6 个 URL + scroll 3 个 + toast 2 个）— ~60 行

### 1.1 问题

- **单一职责违规**：8 大职责全在一个类里，测试困难（BrowseViewModel 自身 0 单测）
- **认知负荷高**：维护者读 749 行才能找到要改的函数
- **状态耦合**：所有 StateFlow 在一个类里互相影响，难以推理副作用
- **Round 10 不完整**：UI 已解耦（BrowseContentState + 浏览组件拆分），但 ViewModel 本身未拆

### 1.2 范围明确

- ✅ BrowseViewModel 拆为 6 个委托类 + 1 个 BrowseSharedState holder
- ✅ 每个委托类可独立单测
- ✅ UI 零改动（ViewModel 仍是单一入口，public API 完全保持）
- ❌ MVI / Clean Architecture 完全重写（YAGNI）
- ❌ 多 ViewModel（per-feature，破坏 UI 单入口假设）
- ❌ UseCase 层引入
- ❌ 服务端 / Web / Rust 改动
- ❌ Compose UI 改动

---

## 2. 目标与非目标

### 目标
1. **6 个委托类**：BrowseNavigator / FavoritesController / TagController / SearchController / DownloadController / DeleteController
2. **BrowseSharedState**：共享 StateFlow 持有者（currentPath / isSystemBrowse / rawFolders / rawFiles / pathStack / browseState）
3. **BrowseViewModel 变薄**：从 749 行降到 ~200 行（仅构造 + public API proxy + toast）
4. **UI 零改动**：所有现有 Compose 调用点（BrowseScreen 等）不需修改
5. **每个委托可独立单测**：用 mock repository + fake shared state
6. **所有现有 JVM 测试不回归**：BrowseViewModelTest（如存在）+ BrowseSorterTest + 其他

### 非目标
- ❌ MVI / Clean Architecture 完全重写
- ❌ 多 ViewModel（per-feature）
- ❌ UseCase 层引入
- ❌ 服务端 / Web / Rust 改动
- ❌ Compose UI 改动
- ❌ RetrofitClient 进一步重构（Round 17 已做 setSharedClient 桥接）

---

## 3. 架构与文件清单

### 3.1 目标文件结构

```
viewmodel/
├── BrowseViewModel.kt              ← 主入口，变薄到 ~200 行
├── BrowseSharedState.kt            ← 共享 StateFlow holder（新增）
├── BrowseNavigator.kt              ← navigation + sort + scroll + URL builders（新增）
├── FavoritesController.kt          ← favorites state + toggle + filter（新增）
├── TagController.kt                ← tags state + CRUD + collection（新增）
├── SearchController.kt             ← search state + logic（新增）
├── DownloadController.kt           ← downloads state + download actions（新增）
├── DeleteController.kt             ← delete state + action（新增）
├── BrowseSorter.kt                 ← 现有，不动
├── SortOrder.kt                    ← 现有，不动
├── ConnectionDecisions.kt          ← 现有，不动
└── (BrowseState / SearchState / DeleteState sealed classes 移到独立文件或留在 BrowseViewModel.kt 顶部)
```

### 3.2 文件改动矩阵（7 个 commit）

| Commit | 文件 | 改动类型 | 说明 |
|---|---|---|---|
| C1 | `viewmodel/BrowseSharedState.kt` | **新增** | 共享 StateFlow holder |
| C2 | `viewmodel/BrowseNavigator.kt` | **新增** | navigation + sort + scroll + URL builders |
| C3 | `viewmodel/FavoritesController.kt` | **新增** | favorites state + toggle + filter |
| C4 | `viewmodel/TagController.kt` | **新增** | tags state + CRUD + collection |
| C5 | `viewmodel/SearchController.kt` | **新增** | search state + logic |
| C6 | `viewmodel/DownloadController.kt` + `viewmodel/DeleteController.kt` | **新增** | 2 个较小的委托合并 1 commit |
| C7 | `viewmodel/BrowseViewModel.kt` | **改** | 重构为薄 shell，委托给 6 个委托类 |

无 Kotlin 测试新增（YAGNI 当前；委托类可独立单测但本轮先确保重构不破坏行为，测试留 Round 19）。

### 3.3 关键约束

- **UI 零改动**：BrowseViewModel 的所有 public 属性和方法签名**完全保持**——UI 调用点（BrowseScreen.kt 等）不需修改一个字符
- **BrowseSharedState 是 internal**：不暴露给 UI，仅 ViewModel + 委托可见
- **委托类不持有 ViewModel scope**：所有 `viewModelScope.launch` 仍在 ViewModel 里；委托暴露 suspend 方法
- **Toast 留在 ViewModel**：跨委托的横切关注点（多个委托都可能触发 toast）
- **sealed class（BrowseState/SearchState/DeleteState）**：留在 BrowseViewModel.kt 顶部或移到独立文件（本轮留原位置最小改动）
- **Hilt 注入不变**：BrowseViewModel 的 `@Inject constructor(...)` 签名不动；委托类由 ViewModel 内部 new（不通过 Hilt）
- **现有 BrowseViewModelTest**（如有）：必须继续通过——测试调的是 public API，不应感知内部重构

---

## 4. 实现细节

### 4.1 C1: BrowseSharedState

```kotlin
// viewmodel/BrowseSharedState.kt
package com.juziss.localmediahub.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds StateFlows shared across BrowseViewModel's delegate controllers.
 *
 * Round 18 refactor: BrowseViewModel was 749 lines mixing 8 concerns.
 * State that crosses delegate boundaries (e.g. currentPath is read by
 * Navigator + Favorites + Tags) lives here so each delegate can read/write
 * without coupling to the ViewModel itself.
 *
 * Internal to the viewmodel package — NOT exposed to UI. The ViewModel
 * re-exposes the relevant flows as public StateFlows for backward compat.
 */
internal class BrowseSharedState {
    val browseState = MutableStateFlow<BrowseState>(BrowseState.Idle)
    val currentPath = MutableStateFlow("")
    val pathStack = MutableStateFlow<List<String>>(emptyList())
    val isSystemBrowse = MutableStateFlow(false)
    val rawFolders = MutableStateFlow<List<Folder>>(emptyList())
    val rawFiles = MutableStateFlow<List<MediaFile>>(emptyList())

    fun emitBrowseError(message: String) {
        browseState.value = BrowseState.Error(message)
    }
}
```

### 4.2 C2: BrowseNavigator

```kotlin
// viewmodel/BrowseNavigator.kt
package com.juziss.localmediahub.viewmodel

import android.content.Context
import com.juziss.localmediahub.data.BrowseResult
import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.MediaRepository
import com.juziss.localmediahub.data.RecentActivityStore
import com.juziss.localmediahub.data.SystemBrowseResult
import com.juziss.localmediahub.network.NetworkResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Browse navigation + sort + scroll position + URL builders.
 *
 * Round 18 delegate extracted from BrowseViewModel. Owns the browse
 * state machine (load roots → browse folder → navigate back), sort order
 * application (via BrowseSorter), scroll position persistence, and the
 * 6 URL builders (stream/thumbnail/original × regular/favorite).
 *
 * Shares currentPath/isSystemBrowse/rawFolders/rawFiles via [sharedState]
 * so other delegates (Favorites/Tags/Search) can read the active context.
 */
internal class BrowseNavigator(
    private val appContext: Context,
    private val repository: MediaRepository,
    private val recentActivityStore: RecentActivityStore,
    private val sharedState: BrowseSharedState,
) {
    private val _folderSortOrder = MutableStateFlow(SortOrder.NAME_ASC)
    val folderSortOrder: StateFlow<SortOrder> = _folderSortOrder.asStateFlow()

    private val _fileSortOrder = MutableStateFlow(SortOrder.NAME_ASC)
    val fileSortOrder: StateFlow<SortOrder> = _fileSortOrder.asStateFlow()

    private val _scrollPositions = mutableMapOf<String, Int>()
    private val _restoreScrollTo = MutableStateFlow<String?>(null)
    val restoreScrollTo: StateFlow<String?> = _restoreScrollTo.asStateFlow()

    // Public state proxies (ViewModel re-exposes)
    val browseState = sharedState.browseState.asStateFlow()
    val currentPath = sharedState.currentPath.asStateFlow()
    val isSystemBrowse = sharedState.isSystemBrowse.asStateFlow()

    // Navigation methods (all the loadRoots/browseFolder/navigateBack/etc)
    suspend fun loadRoots() { /* migrated from BrowseViewModel */ }
    suspend fun loadSystemDrives() { /* ... */ }
    suspend fun browseSystemPath(absolutePath: String, folderName: String) { /* ... */ }
    suspend fun browseFolder(relativePath: String, folderName: String) { /* ... */ }
    fun navigateBack() { /* ... */ }
    fun canGoBack(): Boolean = sharedState.pathStack.value.isNotEmpty()

    // Sort
    fun setFolderSortOrder(order: SortOrder) { /* ... */ }
    fun setFileSortOrder(order: SortOrder) { /* ... */ }

    // Scroll position
    fun saveScrollPosition(path: String, index: Int) { /* ... */ }
    fun getScrollPosition(path: String): Int = _scrollPositions[path] ?: 0
    fun consumeRestoreScroll() { _restoreScrollTo.value = null }

    // URL builders
    fun getVideoStreamUrl(file: MediaFile): String { /* ... */ }
    fun getThumbnailUrl(file: MediaFile): String { /* ... */ }
    fun getOriginalImageUrl(file: MediaFile): String { /* ... */ }

    // Internal helpers (migrated from BrowseViewModel)
    private suspend fun applyFolderResult(data: BrowseResult) { /* ... */ }
    private suspend fun applySystemResult(data: SystemBrowseResult) { /* ... */ }
}
```

### 4.3 C3: FavoritesController

```kotlin
// viewmodel/FavoritesController.kt
internal class FavoritesController(
    private val favoritesStore: FavoritesStore,
    private val sharedState: BrowseSharedState,
) {
    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _favoriteFiles = MutableStateFlow<List<MediaFile>>(emptyList())
    val favoriteFiles: StateFlow<List<MediaFile>> = _favoriteFiles.asStateFlow()

    private val _favoriteAccessModes = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val favoriteAccessModes: StateFlow<Map<String, Boolean>> = _favoriteAccessModes.asStateFlow()

    private val _showFavoritesOnly = MutableStateFlow(false)
    val showFavoritesOnly: StateFlow<Boolean> = _showFavoritesOnly.asStateFlow()

    // Collects from favoritesStore — called from ViewModel init
    suspend fun startCollecting(scope: CoroutineScope) { /* ... */ }

    fun isFavorite(relativePath: String): Boolean = /* ... */
    suspend fun toggleFavorite(file: MediaFile, isSystemBrowse: Boolean) { /* ... */ }
    fun setShowFavoritesOnly(show: Boolean) { /* ... */ }
    fun filterFilesByFavorites(files: List<MediaFile>): List<MediaFile> = /* ... */
    fun isFavoriteSystemBrowse(file: MediaFile): Boolean = /* ... */

    // URL builders for favorites (3 variants)
    fun getFavoriteVideoStreamUrl(file: MediaFile): String { /* ... */ }
    fun getFavoriteThumbnailUrl(file: MediaFile): String { /* ... */ }
    fun getFavoriteOriginalImageUrl(file: MediaFile): String { /* ... */ }
}
```

> **Note**: `startCollecting` takes a CoroutineScope (the viewModelScope) so the controller can launch long-lived collectors. ViewModel calls this from init.

### 4.4 C4: TagController

```kotlin
// viewmodel/TagController.kt
internal class TagController(
    private val repository: MediaRepository,
    private val sharedState: BrowseSharedState,
) {
    private val _tags = MutableStateFlow<List<Tag>>(emptyList())
    val tags: StateFlow<List<Tag>> = _tags.asStateFlow()

    private val _fileTags = MutableStateFlow<Map<String, List<Tag>>>(emptyMap())
    val fileTags: StateFlow<Map<String, List<Tag>>> = _fileTags.asStateFlow()

    private val _activeTagFilter = MutableStateFlow<Tag?>(null)
    val activeTagFilter: StateFlow<Tag?> = _activeTagFilter.asStateFlow()

    suspend fun loadTags() { /* ... */ }
    suspend fun createTag(name: String, color: String = "#808080") { /* ... */ }
    suspend fun deleteTag(tagId: String) { /* ... */ }
    suspend fun tagFile(tagId: String, filePath: String) { /* ... */ }
    suspend fun untagFile(tagId: String, filePath: String) { /* ... */ }
    fun loadFileTagsForFile(filePath: String, scope: CoroutineScope) { /* ... */ }
    fun loadAllFileTags(scope: CoroutineScope) { /* ... */ }
    fun getTagsForFile(filePath: String): List<Tag> = /* ... */
    fun setActiveTagFilter(tag: Tag?) { /* ... */ }
    suspend fun openCollection(tag: Tag) { /* uses sharedState.rawFolders/rawFiles */ }
    fun currentCollectionTag(): Tag? = /* ... */
    fun filterFilesByTag(files: List<MediaFile>): List<MediaFile> = /* ... */
}
```

### 4.5 C5: SearchController

```kotlin
// viewmodel/SearchController.kt
internal class SearchController(
    private val repository: MediaRepository,
    private val sharedState: BrowseSharedState,
) {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    fun updateSearchQuery(query: String) { /* ... */ }
    suspend fun search() { /* uses sharedState.currentPath */ }
    fun clearSearch() { /* ... */ }
}
```

### 4.6 C6: DownloadController + DeleteController

```kotlin
// viewmodel/DownloadController.kt
internal class DownloadController(
    private val downloadManager: DownloadManager,
    private val repository: MediaRepository,
    private val downloadsStore: DownloadsStore,
) {
    val downloadedFiles = downloadsStore.downloadedFiles

    fun removeDownload(relativePath: String, scope: CoroutineScope) { /* ... */ }
    fun removeDownloads(relativePaths: List<String>, scope: CoroutineScope) { /* ... */ }
    fun downloadFile(file: MediaFile, videoStreamUrl: String, imageUrl: String,
                     onMessage: (String) -> Unit, scope: CoroutineScope) { /* ... */ }
    fun downloadFolder(folder: Folder, onMessage: (String) -> Unit, scope: CoroutineScope) { /* ... */ }
}
```

```kotlin
// viewmodel/DeleteController.kt
internal class DeleteController(
    private val repository: MediaRepository,
    private val sharedState: BrowseSharedState,
) {
    private val _deleteState = MutableStateFlow<DeleteState>(DeleteState.Idle)
    val deleteState: StateFlow<DeleteState> = _deleteState.asStateFlow()

    fun clearDeleteState() { _deleteState.value = DeleteState.Idle }
    suspend fun deletePathSync(path: String, recursive: Boolean): NetworkResult<String> { /* ... */ }
    fun deletePath(path: String, recursive: Boolean, scope: CoroutineScope,
                   onRefresh: () -> Unit) { /* ... */ }
}
```

### 4.7 C7: BrowseViewModel 重构后

```kotlin
@HiltViewModel
class BrowseViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    favoritesStore: FavoritesStore,
    recentActivityStore: RecentActivityStore,
    downloadsStore: DownloadsStore,
    repository: MediaRepository,
    downloadManager: DownloadManager,
) : ViewModel() {

    private val sharedState = BrowseSharedState()
    private val navigator = BrowseNavigator(appContext, repository, recentActivityStore, sharedState)
    private val favoritesController = FavoritesController(favoritesStore, sharedState)
    private val tagController = TagController(repository, sharedState)
    private val searchController = SearchController(repository, sharedState)
    private val downloadController = DownloadController(downloadManager, repository, downloadsStore)
    private val deleteController = DeleteController(repository, sharedState)

    init {
        // Favorites collectors (were 3 inline launches in old ViewModel)
        favoritesController.startCollecting(viewModelScope)
    }

    // ── Toast (cross-cutting, stays here) ──────────────────────────
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()
    fun showToast(msg: String) { _toastMessage.value = msg }
    fun onToastShown() { _toastMessage.value = null }

    // ── Public state (proxied from sharedState + delegates) ───────
    val browseState get() = navigator.browseState
    val currentPath get() = navigator.currentPath
    val isSystemBrowse get() = navigator.isSystemBrowse
    val folderSortOrder get() = navigator.folderSortOrder
    val fileSortOrder get() = navigator.fileSortOrder
    val restoreScrollTo get() = navigator.restoreScrollTo
    val favorites get() = favoritesController.favorites
    val favoriteFiles get() = favoritesController.favoriteFiles
    val showFavoritesOnly get() = favoritesController.showFavoritesOnly
    val tags get() = tagController.tags
    val fileTags get() = tagController.fileTags
    val activeTagFilter get() = tagController.activeTagFilter
    val searchQuery get() = searchController.searchQuery
    val searchState get() = searchController.searchState
    val downloadedFiles get() = downloadController.downloadedFiles
    val deleteState get() = deleteController.deleteState

    // ── Public methods (proxied to delegates) ─────────────────────
    // Navigation
    fun loadRoots() = viewModelScope.launch { navigator.loadRoots() }
    fun loadSystemDrives() = viewModelScope.launch { navigator.loadSystemDrives() }
    fun browseSystemPath(path: String, name: String) =
        viewModelScope.launch { navigator.browseSystemPath(path, name) }
    fun browseFolder(path: String, name: String) =
        viewModelScope.launch { navigator.browseFolder(path, name) }
    fun navigateBack() = navigator.navigateBack()
    fun canGoBack() = navigator.canGoBack()
    fun refreshCurrentDirectory() = viewModelScope.launch { navigator.loadRoots() /* or context-aware */ }

    // Sort
    fun setFolderSortOrder(order: SortOrder) = navigator.setFolderSortOrder(order)
    fun setFileSortOrder(order: SortOrder) = navigator.setFileSortOrder(order)

    // Scroll
    fun saveScrollPosition(path: String, index: Int) = navigator.saveScrollPosition(path, index)
    fun getScrollPosition(path: String) = navigator.getScrollPosition(path)
    fun consumeRestoreScroll() = navigator.consumeRestoreScroll()

    // URL builders
    fun getVideoStreamUrl(file: MediaFile) = navigator.getVideoStreamUrl(file)
    fun getThumbnailUrl(file: MediaFile) = navigator.getThumbnailUrl(file)
    fun getOriginalImageUrl(file: MediaFile) = navigator.getOriginalImageUrl(file)
    fun isFavoriteSystemBrowse(file: MediaFile) = favoritesController.isFavoriteSystemBrowse(file)
    fun getFavoriteVideoStreamUrl(file: MediaFile) = favoritesController.getFavoriteVideoStreamUrl(file)
    fun getFavoriteThumbnailUrl(file: MediaFile) = favoritesController.getFavoriteThumbnailUrl(file)
    fun getFavoriteOriginalImageUrl(file: MediaFile) = favoritesController.getFavoriteOriginalImageUrl(file)

    // Favorites
    fun isFavorite(relativePath: String) = favoritesController.isFavorite(relativePath)
    fun toggleFavorite(file: MediaFile, isSystemBrowse: Boolean = isSystemBrowse()) =
        viewModelScope.launch { favoritesController.toggleFavorite(file, isSystemBrowse) }
    fun setShowFavoritesOnly(show: Boolean) = favoritesController.setShowFavoritesOnly(show)
    fun filterFilesByFavorites(files: List<MediaFile>) = favoritesController.filterFilesByFavorites(files)

    // Tags
    fun loadTags() = viewModelScope.launch { tagController.loadTags() }
    fun createTag(name: String, color: String = "#808080") =
        viewModelScope.launch { tagController.createTag(name, color) }
    fun deleteTag(tagId: String) = viewModelScope.launch { tagController.deleteTag(tagId) }
    fun tagFile(tagId: String, filePath: String) =
        viewModelScope.launch { tagController.tagFile(tagId, filePath) }
    fun untagFile(tagId: String, filePath: String) =
        viewModelScope.launch { tagController.untagFile(tagId, filePath) }
    fun loadFileTagsForFile(filePath: String) = tagController.loadFileTagsForFile(filePath, viewModelScope)
    fun loadAllFileTags() = tagController.loadAllFileTags(viewModelScope)
    fun getTagsForFile(filePath: String) = tagController.getTagsForFile(filePath)
    fun setActiveTagFilter(tag: Tag?) = tagController.setActiveTagFilter(tag)
    fun openCollection(tag: Tag) = viewModelScope.launch { tagController.openCollection(tag) }
    fun currentCollectionTag() = tagController.currentCollectionTag()
    fun filterFilesByTag(files: List<MediaFile>) = tagController.filterFilesByTag(files)

    // Search
    fun updateSearchQuery(query: String) = searchController.updateSearchQuery(query)
    fun search() = viewModelScope.launch { searchController.search() }
    fun clearSearch() = searchController.clearSearch()

    // Downloads
    fun removeDownload(file: MediaFile) =
        downloadController.removeDownload(file.relativePath, viewModelScope)
    fun removeDownloads(relativePaths: List<String>) =
        downloadController.removeDownloads(relativePaths, viewModelScope)
    fun downloadFile(file: MediaFile) =
        downloadController.downloadFile(file, getVideoStreamUrl(file), getOriginalImageUrl(file), ::showToast, viewModelScope)
    fun downloadFolder(folder: Folder) =
        downloadController.downloadFolder(folder, ::showToast, viewModelScope)

    // Delete
    fun clearDeleteState() = deleteController.clearDeleteState()
    fun deletePathSync(path: String, recursive: Boolean) =
        viewModelScope.async { deleteController.deletePathSync(path, recursive) }
    fun deletePath(path: String, recursive: Boolean) =
        deleteController.deletePath(path, recursive, viewModelScope) { refreshCurrentDirectory() }

    fun isSystemBrowseMode(): Boolean = sharedState.isSystemBrowse.value
}
```

---

## 5. 测试

### 5.1 测试策略

**本轮目标：零回归。** 不新增委托类单测（留 Round 19），确保现有测试全过。

| 测试 | 状态 | 验证 |
|---|---|---|
| `BrowseViewModelTest`（现有，行 1-296） | 必须全过 | public API 行为不变 |
| `BrowseSorterTest` | 必须全过 | BrowseSorter 未动 |
| `HomeViewModelTest` | 必须全过 | 不依赖 BrowseViewModel |
| 所有其他 JVM 测试（57+） | 必须全过 | 无回归 |

### 5.2 委托类单测（Round 19 候选）

- `BrowseNavigatorTest`：loadRoots/browseFolder/navigateBack 状态转换
- `TagControllerTest`：loadTags/createTag/deleteTag/tagFile 状态变化
- `SearchControllerTest`：search/clearSearch 状态
- `FavoritesControllerTest`：toggleFavorite/filter
- `DownloadControllerTest`：downloadFile/downloadFolder（mock DownloadManager）
- `DeleteControllerTest`：deletePath 状态机

### 5.3 真机/模拟器手工回归

- 浏览文件夹 → 文件/文件夹列表正常
- 导航：进入/返回/系统浏览/根目录切换
- 排序：folder sort + file sort 各档
- 收藏：toggle + filter + favorites view
- 标签：create/delete/tag/untag/collection
- 搜索：query + result + clear
- 下载：file + folder
- 删除：file + folder

---

## 6. 实现顺序与提交策略

7 个 commit，按依赖顺序：

1. **C1 BrowseSharedState** — 先建 holder，无依赖
2. **C2 BrowseNavigator** — 最大委托（navigation + sort + scroll + URL builders）
3. **C3 FavoritesController** — favorites state + toggle
4. **C4 TagController** — tags CRUD（最大业务逻辑）
5. **C5 SearchController** — search（最简单）
6. **C6 DownloadController + DeleteController** — 2 个较小委托合并
7. **C7 BrowseViewModel 重构** — 最后做：删除旧代码、委托给 6 个委托类

**关键：C1-C6 是纯新增文件**（不触及现有 BrowseViewModel），所以每个 commit 后现有测试都应全过。C7 是唯一改动 BrowseViewModel 的 commit，是风险最高的一步。

每个 commit 后：`cd android && ./gradlew assembleDebug :app:testDebugUnitTest` 全过。

---

## 7. 已决策点

| 决策 | 选择 | 理由 |
|---|---|---|
| 拆分模式 | 委托类（delegates） | UI 零改动、可独立单测、ViewModel 仍是单一入口 |
| 委托数 | 6（Navigator + Favorites + Tag + Search + Download + Delete） | 按 8 大职责合理归并 |
| 状态共享 | ViewModel 持有 BrowseSharedState，委托读写 | 跨委托 state（currentPath/isSystemBrowse/raw\*）集中管理 |
| UI 改动 | 零（public API 完全保持） | 最小破坏性 |
| 委托可见性 | `internal`（不暴露 UI） | 实现细节 |
| sealed classes | 留在 BrowseViewModel.kt 顶部 | 最小改动（YAGNI 移到独立文件） |
| Hilt 注入 | BrowseViewModel constructor 签名不动；委托由 ViewModel 内部 new | 不增加 Hilt 图复杂度 |
| Toast | 留在 ViewModel | 跨委托横切关注点 |
| 委托类单测 | 不做（留 Round 19） | 本轮先确保重构不破坏行为 |
| 提交粒度 | 7 个 commit（C1-C6 纯新增 + C7 重构） | C7 是风险最高步，独立 commit 便于回滚 |

---

## 8. 已知限制（接受）

1. **委托类无单测**（§5.2）：本轮聚焦行为保持，测试留 Round 19。
2. **BrowseSharedState 是 internal 但非 final**：理论上可被同 package 子类覆盖，但 Kotlin internal 已足够限制。
3. **FavoritesController.startCollecting 需要 CoroutineScope 参数**：因为委托类不持有 ViewModel scope。轻微 leak（scope 参数传递），但优于让委托类知道 ViewModel。
4. **sealed classes 留在 BrowseViewModel.kt 顶部**：理想是移到 `BrowseState.kt` 独立文件，但本轮最小改动。后续可移。
5. **BrowseViewModel 仍 ~200 行**：主要是 public API proxy 方法（46 个）。进一步压缩需要 Kotlin delegation 或 property delegation，YAGNI。

---

## 9. 非目标（再次明确）

- ❌ MVI / Clean Architecture 完全重写
- ❌ 多 ViewModel（per-feature）
- ❌ UseCase 层引入
- ❌ 服务端 / Web / Rust 改动
- ❌ Compose UI 改动
- ❌ 委托类单测（Round 19）
- ❌ sealed classes 移到独立文件
- ❌ RetrofitClient 进一步重构

---

## 10. 后续轮次（不在本 spec，仅备忘）

- **委托类单测**（Round 19 候选）：6 个委托各加单测
- **sealed classes 独立文件**：BrowseState/SearchState/DeleteState 移出
- **RetrofitClient → Hilt @Singleton class**：消除 setSharedClient 桥接
- **GitHub Actions CI**：跨项目质量基础设施
- **Web Vitest + JSDOM**：router/api/state 纯函数单测
