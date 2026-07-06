# BrowseViewModel 委托类拆分（Round 18）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 BrowseViewModel（749 行 / 46 函数 / 8 职责）拆分为 6 个委托类 + 1 个 BrowseSharedState，ViewModel 变薄为 ~200 行 shell。

**Architecture:** 7 个 commit 顺序执行——C1-C6 为纯新增文件（不触及 BrowseViewModel），C7 为唯一改动（重构 ViewModel 为代理 shell）。每个 commit 后现有 JVM 测试全过。委托类使用 `internal` 可见性（不暴露 UI），ViewModel 的 public API 签名完全保持。

**Tech Stack:** Kotlin + Hilt + StateFlow + Coroutines (viewModelScope)

## Global Constraints

- minSdk=26, targetSdk=34, Kotlin jvmTarget=1.8
- Hilt `@HiltViewModel` 构造签名不变
- **UI 零改动**：BrowseViewModel 所有 public 属性和方法签名完全保持
- 委托类可见性：`internal`（仅 viewmodel package 可访问）
- 委托类不持有 ViewModel scope — `viewModelScope.launch` 仍在 ViewModel 里，委托暴露 suspend 方法或 take CoroutineScope 参数
- **sealed classes**（BrowseState/SearchState/DeleteState）留在 BrowseViewModel.kt 顶部（最小改动）
- Toast 留在 ViewModel（跨委托横切关注点）
- 委托类由 ViewModel 内部 `new`（不通过 Hilt 注入）
- 每一行从 BrowseViewModel 移走的代码都必须经过"全量 JVM 回归"验证—每个 commit 后 `cd android && ./gradlew assembleDebug :app:testDebugUnitTest` 全过
- 7 个 commit 顺序：C1 BrowseSharedState → C2 BrowseNavigator → C3 FavoritesController → C4 TagController → C5 SearchController → C6 DownloadController + DeleteController → C7 BrowseViewModel 重构
- **C7 是唯一改动 BrowseViewModel 的 commit**——C1-C6 是纯新增文件，不能 break 任何现有测试
- **所有函数签名（参数名、返回类型、默认值）必须与原 BrowseViewModel 逐字一致**——UI 调用点依赖这些签名，任何不一致都是 UI behavior change
- 11 处 `viewModelScope.launch` 通过 `scope: CoroutineScope` 参数替代——View Model 调用 `delegate.xyz(scope = viewModelScope)`

---

### Task 1 (Commit C1): BrowseSharedState

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseSharedState.kt`

**Interfaces:**
- Consumes: 无
- Produces: `BrowseSharedState` 类（internal），含 `browseState` / `currentPath` / `pathStack` / `isSystemBrowse` / `rawFolders` / `rawFiles` 六个 `MutableStateFlow` + `emitBrowseError()` helper

- [ ] **Step 1: Create BrowseSharedState.kt**

Create `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseSharedState.kt`:

```kotlin
package com.juziss.localmediahub.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow

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

- [ ] **Step 2: Verify build + tests**

Run: `cd android && ./gradlew assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all 57 tests pass（纯新增 internal 文件，无影响）

- [ ] **Step 3: Commit**

```bash
cd "E:\github_project\LocalMediaHub"
git add android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseSharedState.kt
git commit -m "$(cat <<'EOF'
refactor(viewmodel): add BrowseSharedState holder (round 18 C1)

Internal class holding shared MutableStateFlows (browseState/currentPath/
pathStack/isSystemBrowse/rawFolders/rawFiles) for the 6 delegate
controllers. Additive only — no existing code touched.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2 (Commit C2): BrowseNavigator

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseNavigator.kt`

**Interfaces:**
- Consumes: `appContext: Context`, `repository: MediaRepository`, `recentActivityStore: RecentActivityStore`, `sharedState: BrowseSharedState`
- Produces: `BrowseNavigator` 类（internal）—— navigation + sort + scroll + 9 URL builders

**Extract these from BrowseViewModel into BrowseNavigator：**

去 BrowseViewModel 里找到以下函数和 state，把**函数体**完整搬到 `BrowseNavigator.kt`（保留函数签名、参数名、返回类型、默认值，全部逐字照搬）：
- `loadRoots()` (line ~180)
- `loadSystemDrives()` (line ~200)
- `browseSystemPath(absolutePath: String, folderName: String)` (line ~221)
- `browseFolder(relativePath: String, folderName: String)` (line ~246)
- `navigateBack()` (line ~268)
- `canGoBack()` (line ~316)
- `refreshCurrentDirectory()` (line ~685)
- `setFolderSortOrder(order: SortOrder)` (line ~318)
- `setFileSortOrder(order: SortOrder)` (line ~345)
- `saveScrollPosition(path: String, index: Int)` (line ~115)
- `getScrollPosition(path: String): Int` (line ~119)
- `consumeRestoreScroll()` (line ~121)
- `getVideoStreamUrl(file: MediaFile): String` (line ~417)
- `getThumbnailUrl(file: MediaFile): String` (line ~421)
- `getOriginalImageUrl(file: MediaFile): String` (line ~425)
- `getFavoriteVideoStreamUrl(file: MediaFile): String` (line ~433)
- `getFavoriteThumbnailUrl(file: MediaFile): String` (line ~437)
- `getFavoriteOriginalImageUrl(file: MediaFile): String` (line ~441)
- `emitBrowseError(message: String)` (line ~413) → `sharedState.emitBrowseError(message)`
- 私有 helper：`applyFolderResult(data: BrowseResult)` (line ~379)、`applySystemResult(data: SystemBrowseResult)` (line ~394)

**State 迁移：** 所有 `_folderSortOrder` / `_fileSortOrder` / `_scrollPositions` / `_restoreScrollTo` 从 BrowseViewModel 搬到 BrowseNavigator 作为私有字段。暴露 `val folderSortOrder: StateFlow<SortOrder>`、`val fileSortOrder: StateFlow<SortOrder>`、`val restoreScrollTo: StateFlow<String?>` 等 public 属性。

**函数体内引用替换：**
- `_browseState` → `sharedState.browseState`
- `_currentPath` → `sharedState.currentPath`
- `_pathStack` → `sharedState.pathStack`
- `_isSystemBrowse` → `sharedState.isSystemBrowse`
- `_rawFolders` → `sharedState.rawFolders`
- `_rawFiles` → `sharedState.rawFiles`

- [ ] **Step 1: Create BrowseNavigator.kt with all extracted functions**

Open `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt`，逐个找到上述函数，复制函数签名 + 完整函数体到新文件 `BrowseNavigator.kt`。保持每个字符一致（包括 private fun 注解、`@Suppress` 如存在、`!is` vs `as?` 风格等）。

在 BrowseNavigator 类声明的顶部加 `internal class BrowseNavigator(...)` 包裹所有函数和 state。

BrowseNavigator 的 state 结构（在类体内声明）：
```kotlin
private val _folderSortOrder = MutableStateFlow(SortOrder.NAME_ASC)
val folderSortOrder: StateFlow<SortOrder> = _folderSortOrder.asStateFlow()

private val _fileSortOrder = MutableStateFlow(SortOrder.NAME_ASC)
val fileSortOrder: StateFlow<SortOrder> = _fileSortOrder.asStateFlow()

private val _scrollPositions = mutableMapOf<String, Int>()
private val _restoreScrollTo = MutableStateFlow<String?>(null)
val restoreScrollTo: StateFlow<String?> = _restoreScrollTo.asStateFlow()
```

- [ ] **Step 2: Verify build + tests**

Run: `cd android && ./gradlew assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all 57 tests pass（纯新增文件，BrowseViewModel 仍持有原函数体——C7 才删）

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseNavigator.kt
git commit -m "$(cat <<'EOF'
refactor(viewmodel): extract BrowseNavigator delegate (round 18 C2)

Navigation, sort, scroll position and URL builders extracted from
BrowseViewModel into a new internal delegate. Shared state flows
(browseState/currentPath/etc.) accessed via BrowseSharedState.

Additive only — BrowseViewModel duplicate functions not yet removed
(C7 will clean up).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3 (Commit C3): FavoritesController

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/FavoritesController.kt`

**Interfaces:**
- Consumes: `favoritesStore: FavoritesStore`, `sharedState: BrowseSharedState`
- Produces: `FavoritesController` 类（internal）—— favorites state + toggle + filter

**Extract from BrowseViewModel：**

| 原函数 | 迁入方式 |
|---|---|
| `toggleFavorite(file, isSystemBrowse)` (line ~160) | 完整搬迁；`viewModelScope.launch { ... }` 改为 `suspend fun toggleFavorite(...)`
| `isFavorite(relativePath): Boolean` | 完整搬迁 |
| `isFavoriteSystemBrowse(file): Boolean` | 完整搬迁 |
| `setShowFavoritesOnly(show)` | 完整搬迁 |
| `filterFilesByFavorites(files): List<MediaFile>` | 完整搬迁 |
| `getFavoriteVideoStreamUrl(file): String` | 完整搬迁（BrowseNavigator 也有 URL builders——这两个有 favorites 前缀，留 FavoritesController）|
| `getFavoriteThumbnailUrl(file): String` | 同上 |
| `getFavoriteOriginalImageUrl(file): String` | 同上 |
| 3 个 init collectors (lines ~139-153) | 抽象为 `fun startCollecting(scope: CoroutineScope)` |

**State 迁移：**
```kotlin
private val _favorites = MutableStateFlow<Set<String>>(emptySet())
val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()
private val _favoriteFiles = MutableStateFlow<List<MediaFile>>(emptyList())
val favoriteFiles: StateFlow<List<MediaFile>> = _favoriteFiles.asStateFlow()
private val _favoriteAccessModes = MutableStateFlow<Map<String, Boolean>>(emptyMap())
private val _showFavoritesOnly = MutableStateFlow(false)
val showFavoritesOnly: StateFlow<Boolean> = _showFavoritesOnly.asStateFlow()
```

- [ ] **Step 1: Create FavoritesController.kt — 搬迁上述函数 + state**
- [ ] **Step 2: Verify build + tests**（`assembleDebug + testDebugUnitTest` 全过）
- [ ] **Step 3: Commit**

```bash
git commit -m "refactor(viewmodel): extract FavoritesController delegate (round 18 C3)"
```

---

### Task 4 (Commit C4): TagController

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/TagController.kt`

**Interfaces:**
- Consumes: `repository: MediaRepository`, `sharedState: BrowseSharedState`
- Produces: `TagController` 类（internal）—— tags state + CRUD + collection

**Extract from BrowseViewModel：** 12 个 tags 相关函数（lines ~466-618）+ 3 个 state flows。`openCollection(tag)` 函数体引用 `sharedState.rawFolders` / `sharedState.rawFiles` / `_fileSortOrder` → 需格外小心。

- [ ] **Step 1: Create TagController.kt**
- [ ] **Step 2: Verify build + tests**
- [ ] **Step 3: Commit**

```bash
git commit -m "refactor(viewmodel): extract TagController delegate (round 18 C4)"
```

---

### Task 5 (Commit C5): SearchController

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/SearchController.kt`

**Extract from BrowseViewModel：** `updateSearchQuery` / `search` / `clearSearch` + `_searchQuery` / `_searchState` state flows。`search()` 需引用 `sharedState.currentPath`。

- [ ] **Step 1: Create SearchController.kt**
- [ ] **Step 2: Verify build + tests**
- [ ] **Step 3: Commit**

```bash
git commit -m "refactor(viewmodel): extract SearchController delegate (round 18 C5)"
```

---

### Task 6 (Commit C6): DownloadController + DeleteController

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/DownloadController.kt`
- Create: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/DeleteController.kt`

**DownloadController**——从 BrowseViewModel 搬迁 `removeDownload` / `removeDownloads` / `downloadFile` / `downloadFolder` + `downloadedFiles`（passthrough from DownloadsStore）。依赖 `downloadManager` / `repository` / `downloadsStore`。

**DeleteController**——从 BrowseViewModel 搬迁 `clearDeleteState` / `deletePath` / `deletePathSync` + `_deleteState`。依赖 `repository` / `sharedState`。

- [ ] **Step 1: Create both files**
- [ ] **Step 2: Verify build + tests**
- [ ] **Step 3: Commit**

```bash
git commit -m "refactor(viewmodel): extract DownloadController + DeleteController (round 18 C6)"
```

---

### Task 7 (Commit C7): BrowseViewModel 重构为 thin shell

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt`

**This is the ONLY commit that modifies BrowseViewModel. All previous commits were additive.**

- [ ] **Step 1: 确认 C1-C6 的 6 个委托类全部编译通过且在 filesystem 存在**

Run: `ls android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseSharedState.kt BrowseNavigator.kt FavoritesController.kt TagController.kt SearchController.kt DownloadController.kt DeleteController.kt`
Expected: 7 files listed.

- [ ] **Step 2: 删除 BrowseViewModel 中已搬迁的函数体 + state**

逐类删除函调体和 state 声明：
1. 删除 6 处 URL builder 函数体 → 替换为 `= navigator.getVideoStreamUrl(file)` 代理调用
2. 删除 12 个 navigation 函数体 → 替换为 `viewModelScope.launch { navigator.loadRoots() }` 等代理调用
3. 删除 favorites state + 6 函数 → 替换为 `get() = favoritesController.favorites` 等属性代理
4. 删除 tags state + 12 函数 → 替换为委托代理
5. 删除 search state + 3 函数 → 替换为委托代理
6. 删除 downloads + delete 全部 → 替换为委托代理
7. 删除 scroll state + 3 函数 → 替换为 BrowseNavigator 代理
8. 删除 sort state + 2 函数 → 替换为 BrowseNavigator 代理
9. 删除 rawFolders/rawFiles/pathStack/... state 声明 → 替换为 `get() = sharedState.xxx.asStateFlow()` 代理
10. 删除 `emitBrowseError` → 替换为 `private fun emitBrowseError(msg: String) = sharedState.emitBrowseError(msg)`

**最终 BrowseViewModel 形状**（~200 行）：
- `@HiltViewModel @Inject constructor(...)` 签名不变
- 构造体内 `val sharedState = BrowseSharedState()` + 6 个委托实例化
- `init` 块调用 `favoritesController.startCollecting(viewModelScope)`
- toast 两个方法保持原位
- public state 全部用 `get() = delegate.someFlow` 代理
- public methods 全部用 `viewModelScope.launch { delegate.someMethod() }` 或直接代理到 delegate

- [ ] **Step 3: 验证 public API 兼容性**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL（如果编译失败，检查代理函数的签名是否与原函数完全一致）

**常见错误修复清单：**
- `suspend fun` vs `fun + viewModelScope.launch`：委托类的 toggleFavorite 如果原函数是 `fun`（内有 `viewModelScope.launch { ... }`），迁移到 delegate 时需改为 `fun toggleFavorite(file, isSystemBrowse, scope: CoroutineScope)` 保持调用模式。BrowseViewModel 还原为 `fun toggleFavorite(file, isSystemBrowse)` → `favoritesController.toggleFavorite(file, isSystemBrowse, viewModelScope)`
- 属性代理 `get() = ...` 丢失了 `StateFlow.asStateFlow()` 包装——BrowseSharedState 内部是 `MutableStateFlow`，外部需 `.asStateFlow()`
- `canGoBack()` 原 `fun canGoBack(): Boolean = _pathStack.value.isNotEmpty()` → 改为 `fun canGoBack(): Boolean = navigator.canGoBack()`
- Compose 调用点可能用 `val state by viewModel.xxx.collectAsState()` 或 `viewModel.xxx.value` ——代理属性的类型必须完全一致（`StateFlow<T>` vs `LiveData` vs `Flow`）

- [ ] **Step 4: Run full test suite**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: ALL PASS (57 tests). 如果任何测试失败，逐个检查失败原因——最常见是 Compose 测试直接构造 BrowseViewModel 时少了依赖或构造函数签名变了。BrowseViewModel 的 @Inject constructor 参数必须完全不变。

- [ ] **Step 5: Commit**

```bash
cd "E:\github_project\LocalMediaHub"
git add android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt
git commit -m "$(cat <<'EOF'
refactor(viewmodel): collapse BrowseViewModel to delegate shell (round 18 C7)

Remove ~550 lines of function bodies and state declarations from
BrowseViewModel, replace with proxies to 6 delegate classes (r18 C1-C6).

Public API signatures preserved byte-for-byte — zero UI changes.
BrowseViewModel is now a ~200-line shell that holds shared state +
delegates + toast. Each delegate is independently unit-testable (r19).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## 附录 A: 实现速查

| Commit | 文件数 | 改动量 | 风险 | 测试覆盖 |
|---|---|---|---|---|
| C1 BrowseSharedState | 1 新增 | ~25 行 | 极低 | 现有 57 tests |
| C2 BrowseNavigator | 1 新增 | ~200 行（搬迁） | 低 | 现有 57 tests |
| C3 FavoritesController | 1 新增 | ~80 行（搬迁） | 低 | 现有 57 tests |
| C4 TagController | 1 新增 | ~150 行（搬迁） | 低 | 现有 57 tests |
| C5 SearchController | 1 新增 | ~50 行（搬迁） | 低 | 现有 57 tests |
| C6 Download+Delete | 2 新增 | ~100 行总 | 低 | 现有 57 tests |
| **C7 BrowseViewModel 重构** | **1 改** | **-550 行** | **中** | **现有 57 tests 必须全过** |

## 附录 B: 函数搬迁核对清单

实施者在做 C2-C6 时，每搬迁一个函数，用此 checklist 逐项核对：

- [ ] 函数签名（参数名、类型、返回值、默认值）与 BrowseViewModel 原文完全一致？
- [ ] 函数体内所有 `_xxx` 引用已替换为对应的 `sharedState.xxx` / 委托类字段引用？
- [ ] State 声明移到了委托类的 private field，且 `val/suspend fun` 正确暴露？
- [ ] 函数体中的 `viewModelScope.launch { ... }` 正确处理？（suspend 化 or 接受 CoroutineScope 参数）
- [ ] `emitBrowseError(msg)` 调用已替换为 `sharedState.emitBrowseError(msg)`？
- [ ] import 依赖完整（MediaRepository/FavoritesStore/Context 等）？

## 附录 C: 委托类 → ViewModel 映射速查

| Public API | ViewModel 代理方式 | 委托类 | 委托方法 |
|---|---|---|---|
| `val browseState` | `get() = navigator.browseState` | BrowseNavigator | `browseState`（从 sharedState 来） |
| `fun loadRoots()` | `viewModelScope.launch { navigator.loadRoots() }` | BrowseNavigator | `suspend fun loadRoots()` |
| `fun toggleFavorite(f, b)` | `viewModelScope.launch { favoritesController.toggleFavorite(f, b) }` | FavoritesController | `suspend fun toggleFavorite(...)` |
| `val favorites` | `get() = favoritesController.favorites` | FavoritesController | `val favorites: StateFlow<Set<String>>` |
| `fun loadTags()` | `viewModelScope.launch { tagController.loadTags() }` | TagController | `suspend fun loadTags()` |
| `fun search()` | `viewModelScope.launch { searchController.search() }` | SearchController | `suspend fun search()` |
| `fun downloadFile(f)` | `viewModelScope.launch { downloadController.downloadFile(f, ...) }` | DownloadController | `fun downloadFile(..., scope: CoroutineScope)` |
| `fun deletePath(p, r)` | `viewModelScope.launch { deleteController.deletePath(p, r, viewModelScope) }` | DeleteController | `fun deletePath(..., scope: CoroutineScope, onRefresh: () -> Unit)` |

## 附录 D: 已知限制（接受）

1. **BrowseViewModel 仍 ~200 行**：主要是 public API proxy 方法（46 个）。进一步压缩需要 Kotlin `by` delegation 或 property delegation，YAGNI。
2. **委托类无单测**（spec §5.2）：本轮聚焦行为保持，测试留 Round 19。
3. **sealed classes 留在 BrowseViewModel.kt 顶部**（spec §8 #4）：理想是移到独立文件，但本轮最小改动。
4. **FavoritesController 需要 CoroutineScope 参数**（spec §8 #3）：因为委托类不持有 ViewModel scope。轻微 leak，但优于让委托类知道 ViewModel。
5. **DownloadController/DeleteController 有 CoroutineScope 参数**：同上。
6. **BrowseNavigator 的 URL builders 返回 String**：保持不变——不涉及依赖。
