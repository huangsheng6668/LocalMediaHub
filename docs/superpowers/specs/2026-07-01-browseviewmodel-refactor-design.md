# BrowseViewModel 去重 + 抽 BrowseSorter 设计（Architecture · Round 5）

- **日期**: 2026-07-01
- **范围**: Android 客户端（`BrowseViewModel.kt`、新增 `BrowseSorter.kt` + `BrowseSorterTest.kt`）
- **策略**: A — 去重 6 分支 + 抽 `BrowseSorter` 纯对象 + 单测
- **状态**: 待评审

---

## 1. 背景与动机

`BrowseViewModel`（`android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt`，818 行）是历轮审计反复标记的最大单点可维护性债。核心问题：

- **6 处近乎重复的"成功→存 raw→排序→emit 状态"分支**：`browseSystemPath`（`:259-286`）、`browseFolder`（`:297-321`）、`navigateBack` 系统分支（`:353-376`）、`navigateBack` 文件夹分支（`:378-398`）、`refreshCurrentDirectory` 系统分支（`:774-786`）、`refreshCurrentDirectory` 文件夹分支（`:798-808`）。每处都重复"写 `_rawFolders`/`_rawFiles` → `withContext(Default){ applySortTo* }` → 构造 `SystemBrowsed`/`Browsed`"，只在"是否存 recentActivity / 是否设 `_restoreScrollTo`"上有差异。这是 bug 磁场——一处改了排序语义，其余 5 处容易漏改。
- **排序逻辑是未测的纯函数**：`compareNatural`/`extractLeadingNumber`（`:46-72`）、`applySortToFolders`/`applySortToFiles`（`:465-488`）埋在 VM 里、无单测（历轮"测试补强"清单里点名）。

本轮聚焦去掉这块债：抽公共 helper 折叠 6 分支 + 把排序逻辑抽成可测的 `BrowseSorter` 纯对象并补单测。纯重构、**行为不变**、JVM 可测、免设备。

---

## 2. 目标与非目标

### 目标
1. 抽 `BrowseSorter`（`object`，无状态）：`sortFolders(list, order)`/`sortFiles(list, order)` + `internal` 的 `compareNatural`/`extractLeadingNumber`。从 `BrowseViewModel` 移除这 4 个函数。
2. 折叠 6 处 browse-result 分支：新增 `applyFolderResult(data)`/`applySystemResult(data)`/`emitBrowseError(msg)` 私有 helper；6 处调用点改成一行 + 各自上下文动作。
3. 表驱动单测 `BrowseSorterTest`：覆盖自然序、数字序、SIZE（文件）/文件夹忽略 SIZE、提取前导数字、边界。
4. 既有 `BrowseViewModelTest` 仍通过（回归保护）。

### 非目标（留待后续轮次）
- `BrowseViewModel` 拆成多文件/委托类（`BrowseNavigator`/`TagController`/`SearchController` 等，方案 C）——更大重组，单独一轮。
- `RetrofitClient` 可注入、`app.js` 模块化、Scanner 共享切片竞态——其它架构债。
- 任何用户可见行为变化（纯重构，行为保持）。

---

## 3. 抽 `BrowseSorter` 纯对象

新文件 `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseSorter.kt`：

```kotlin
package com.juziss.localmediahub.viewmodel

import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile

/** Extract leading number from a string like "007_gjco" → 7.0, "abc" → null. */
internal fun extractLeadingNumber(s: String): Double? {
    val sb = StringBuilder()
    for (ch in s) if (ch.isDigit()) sb.append(ch) else break
    return if (sb.isNotEmpty()) sb.toString().toDouble() else null
}

/** Compare two strings with natural/numeric ordering (e.g., "2" < "10"). */
internal fun compareNatural(a: String, b: String): Int {
    val regex = Regex("\\d+|\\D+")
    val tokensA = regex.findAll(a.lowercase()).map { it.value }.toList()
    val tokensB = regex.findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(tokensA.size, tokensB.size)) {
        val ta = tokensA[i]; val tb = tokensB[i]
        val numA = ta.toIntOrNull(); val numB = tb.toIntOrNull()
        val cmp = if (numA != null && numB != null) numA.compareTo(numB) else ta.compareTo(tb)
        if (cmp != 0) return cmp
    }
    return tokensA.size.compareTo(tokensB.size)
}

/** Pure, stateless browse-grid sort logic. Extracted from BrowseViewModel for testability. */
object BrowseSorter {
    fun sortFolders(folders: List<Folder>, order: SortOrder): List<Folder> = when (order) {
        SortOrder.NAME_ASC -> folders.sortedWith { a, b -> compareNatural(a.name, b.name) }
        SortOrder.NAME_DESC -> folders.sortedWith { a, b -> compareNatural(b.name, a.name) }
        SortOrder.NUMERIC_ASC -> folders.sortedBy { extractLeadingNumber(it.name) ?: Double.MAX_VALUE }
        SortOrder.NUMERIC_DESC -> folders.sortedByDescending { extractLeadingNumber(it.name) ?: Double.MIN_VALUE }
        SortOrder.TIME_ASC -> folders.sortedBy { it.modifiedTime }
        SortOrder.TIME_DESC -> folders.sortedByDescending { it.modifiedTime }
        else -> folders // SIZE 排序不适用于文件夹
    }

    fun sortFiles(files: List<MediaFile>, order: SortOrder): List<MediaFile> = when (order) {
        SortOrder.NAME_ASC -> files.sortedWith { a, b -> compareNatural(a.name, b.name) }
        SortOrder.NAME_DESC -> files.sortedWith { a, b -> compareNatural(b.name, a.name) }
        SortOrder.NUMERIC_ASC -> files.sortedBy { extractLeadingNumber(it.name) ?: Double.MAX_VALUE }
        SortOrder.NUMERIC_DESC -> files.sortedByDescending { extractLeadingNumber(it.name) ?: Double.MIN_VALUE }
        SortOrder.SIZE_ASC -> files.sortedBy { it.size }
        SortOrder.SIZE_DESC -> files.sortedByDescending { it.size }
        SortOrder.TIME_ASC -> files.sortedBy { it.modifiedTime }
        SortOrder.TIME_DESC -> files.sortedByDescending { it.modifiedTime }
    }
}
```

`BrowseViewModel` 改动：删除文件级 `extractLeadingNumber`/`compareNatural`（`:46-72`）与私有方法 `applySortToFolders`/`applySortToFiles`（`:465-488`）。所有原 `applySortToFolders(x)` 调用改为 `BrowseSorter.sortFolders(x, _folderSortOrder.value)`，`applySortToFiles(x)` 改为 `BrowseSorter.sortFiles(x, _fileSortOrder.value)`（出现在 `setFolderSortOrder`/`setFileSortOrder`/`openCollection` 及下面 §2 的 helper 里）。

> `compareNatural`/`extractLeadingNumber` 用 `internal`（非 `private`）以便同包测试直接调用；它们从 `private`（文件私有）改为 `internal` 不影响生产可见性（仍限本模块）。

---

## 4. 折叠 6 处 browse-result 分支

`BrowseViewModel` 新增 3 个私有 helper（承载"存 raw + 排序 + emit"的公共部分）：

```kotlin
/** 成功的文件夹浏览结果：存 raw、排序、emit Browsed。 */
private suspend fun applyFolderResult(data: BrowseResult) {
    _rawFolders.value = data.folders
    _rawFiles.value = data.files
    val sortedFolders = withContext(Dispatchers.Default) { BrowseSorter.sortFolders(data.folders, _folderSortOrder.value) }
    val sortedFiles = withContext(Dispatchers.Default) { BrowseSorter.sortFiles(data.files, _fileSortOrder.value) }
    _browseState.value = BrowseState.Browsed(data.copy(folders = sortedFolders, files = sortedFiles))
}

/** 成功的系统浏览结果：存 raw、排序、emit SystemBrowsed。 */
private suspend fun applySystemResult(data: SystemBrowseResult) {
    _rawFolders.value = data.folders
    _rawFiles.value = data.files
    val sortedFolders = withContext(Dispatchers.Default) { BrowseSorter.sortFolders(data.folders, _folderSortOrder.value) }
    val sortedFiles = withContext(Dispatchers.Default) { BrowseSorter.sortFiles(data.files, _fileSortOrder.value) }
    _browseState.value = BrowseState.SystemBrowsed(SystemBrowseResult(
        currentPath = data.currentPath, drives = data.drives,
        folders = sortedFolders, files = sortedFiles,
    ))
}

private fun emitBrowseError(message: String) {
    _browseState.value = BrowseState.Error(message)
}
```

6 处调用点改成（仅列差异；每处 `when` 的 Success/Error/Loading 结构保留）：

| 位置 | Success 分支改为 | Error 分支改为 |
|------|------|------|
| `browseSystemPath` | `recentActivityStore.saveLastBrowseLocation(...)` 后 `applySystemResult(data)` | `emitBrowseError(result.message)` |
| `browseFolder` | `recentActivityStore.saveLastBrowseLocation(...)` 后 `applyFolderResult(result.data)` | `emitBrowseError(result.message)` |
| `navigateBack` 系统分支 | `applySystemResult(data)` 后 `_restoreScrollTo.value = previousPath` | `emitBrowseError(result.message)` |
| `navigateBack` 文件夹分支 | `applyFolderResult(result.data)` 后 `_restoreScrollTo.value = previousPath` | `emitBrowseError(result.message)` |
| `refreshCurrentDirectory` 系统分支 | `applySystemResult(data)` | `emitBrowseError(result.message)` |
| `refreshCurrentDirectory` 文件夹分支 | `applyFolderResult(result.data)` | `emitBrowseError(result.message)` |

**行为保持**：`saveLastBrowseLocation`/`_restoreScrollTo` 等上下文动作留在调用点；helper 只做公共的"存 raw + 排序 + emit"。排序顺序、`Loading` 空分支、最终 `_browseState` 的 emit 均与原实现一致。唯一可能的相对顺序变化是 `saveLastBrowseLocation` 与 `_rawFolders`/`_rawFiles` 写入的前后——但二者互不观察（`_raw*` 是私有 `StateFlow`、仅被排序 setter 读取，且不在浏览中途被调用），故无可见差异。

`setFolderSortOrder`/`setFileSortOrder` 内的 `applySortTo*` 也改走 `BrowseSorter`（这两处本来就在 `viewModelScope.launch` + `withContext` 里，保持）。`openCollection` 的 `applySortToFiles(result.data)` 同理改 `BrowseSorter.sortFiles(result.data, _fileSortOrder.value)`。

---

## 5. 测试

新文件 `android/app/src/test/java/com/juziss/localmediahub/viewmodel/BrowseSorterTest.kt`（JUnit4，backtick 名，纯 JVM）：

- `compareNatural`：`("2","10") < 0`、`("img2","img10") < 0`、`("a","b") < 0`、`("x","x") == 0`、`("10","2") > 0`。
- `extractLeadingNumber`：`"007_gjco" → 7.0`、`"abc" → null`、`"10" → 10.0`、`"" → null`。
- `sortFiles` NAME_ASC：`["img10.jpg","img2.jpg","img1.jpg"]` → `["img1.jpg","img2.jpg","img10.jpg"]`（自然序，验证数字感知）。
- `sortFiles` SIZE_ASC / SIZE_DESC：按 `size` 升/降。
- `sortFiles` NUMERIC_ASC：按 `extractLeadingNumber`。
- `sortFolders` NAME_ASC / TIME_DESC：正常排序；**SIZE_ASC / SIZE_DESC 对文件夹应返回原序**（验证 `else -> folders` 分支）。
- 边界：`sortFiles(emptyList(), ...)` → 空、单元素列表不变。

既有 `BrowseViewModelTest`（round 4 已为它补了 `FavoritesStore` 测试 scope）必须仍通过——验证重构未改变 VM 对外行为。

---

## 6. 实现顺序与提交策略

按内聚度分次提交、每次 `./gradlew testDebugUnitTest assembleDebug`：

1. **抽 `BrowseSorter`（§3）**：新建 `BrowseSorter.kt`（含 `internal` helper）；从 `BrowseViewModel` 删除原 4 个排序函数，调用点改走 `BrowseSorter`。补 `BrowseSorterTest`。→ `testDebugUnitTest` 通过。
2. **折叠 6 分支（§4）**：加 3 个 helper，6 处调用点 + Error 分支改写。→ `testDebugUnitTest assembleDebug` 通过（含既有 `BrowseViewModelTest`）。

两步可独立提交；运行时无需真机（纯逻辑重构，构建+单测即证）。

---

## 7. 已决策点

| 决策 | 选择 | 理由 |
|---|---|---|
| 方案 | A（去重 + 抽排序 + 测试） | 直击 6 分支债；`BrowseSorter` 抽出后排序才有单测兜底；内聚、可测、风险可控 |
| `BrowseSorter` 形态 | `object`（无状态） | 纯逻辑、无实例状态，最简 |
| `compareNatural`/`extractLeadingNumber` | `internal`（非 private） | 同包测试可直接断言，更精确 |
| `emitBrowseError` helper | 保留 | Error 分支也去重，风格一致 |
| 多文件/委托类拆分（方案 C） | 不做（YAGNI/风险） | 留后续轮次单独评估 |
| 行为 | 完全不变（纯重构） | 仅可维护性 + 测试覆盖提升，无用户感知 |

---

## 8. 后续轮次（不在本 spec，仅备忘）

- **架构**：`BrowseViewModel` 多文件/委托类拆分（方案 C）、`RetrofitClient` Hilt 可注入、`app.js`（~1320 行）模块化、Scanner 共享切片竞态。
- **服务端读取热路径**：扫描器按类型缓存、scoped 搜索去重复 normalize、`DownloadFolderZip` FD/压缩。
- **Web**：`style.css` 响应式 `@media` + dashboard/stitch/标签渲染性能。
- **Android**：旋转屏 `rememberSaveable`、ExoPlayer 进程保留、OkHttp/Coil 网络缓存。
- **测试**：`streaming` Range、media handler。
