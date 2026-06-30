# BrowseViewModel 重构（Round 5）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `BrowseViewModel` 的排序逻辑抽成可测的 `BrowseSorter` 纯对象、折叠 6 处重复的 browse-result 分支——纯重构、行为不变、补排序单测。

**Architecture:** 仅 Android，2 个任务。Task 1 新建 `BrowseSorter.kt`（`sortFolders`/`sortFiles` + `internal` 纯函数）+ 表驱动 `BrowseSorterTest`（TDD），`BrowseViewModel` 的两个排序方法改为委托；Task 2 加 `applyFolderResult`/`applySystemResult`/`emitBrowseError` helper 折叠 6 分支，并移除现已无用的排序方法包装。

**Tech Stack:** Kotlin / Jetpack Compose MVVM / JUnit4。

## Global Constraints

- **提交策略**（`AGENTS.md`）：本地改动自动同步推送至 GitHub `master`。所有提交直接在 `master`，**不开 feature 分支**；conventional commit + `Co-Authored-By: Claude <noreply@anthropic.com>` 尾注。
- **Kotlin 规则**（`AGENTS.md`）：Jetpack Compose / MVVM；异步 Coroutines；DI Hilt。
- **测试**：JUnit4（`org.junit.Test` + `org.junit.Assert.*`），backtick 测试名，**纯 JVM 单测**放 `app/src/test/java/...`、不依赖 Android 框架。
- **构建**：`cd android && ./gradlew testDebugUnitTest assembleDebug`。中国大陆网络拉依赖失败时配 gradle 代理。
- **行为约束**：**纯重构、对外行为完全不变**；不动 `BrowseViewModel` 的公开 API（公开 `StateFlow`/方法签名不变）；只动 `BrowseViewModel.kt` + 新增 `BrowseSorter.kt`/`BrowseSorterTest.kt`，不碰其它文件。
- **范围外**（spec §2 非目标）：VM 多文件/委托类拆分、`RetrofitClient`/`app.js`/Scanner 等其它架构债。

## File Structure

- 新增 `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseSorter.kt` — 纯排序逻辑（`object BrowseSorter` + `internal` `compareNatural`/`extractLeadingNumber`）。
- 新增 `android/app/src/test/java/com/juziss/localmediahub/viewmodel/BrowseSorterTest.kt` — 排序单测。
- 修改 `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt` — Task 1 委托排序；Task 2 折叠 6 分支 + 移除排序包装。

---

## Task 1: 抽 `BrowseSorter` 纯对象 + 单测（TDD）

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseSorter.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/viewmodel/BrowseSorterTest.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt`（删文件级 `extractLeadingNumber`/`compareNatural`；`applySortToFolders`/`applySortToFiles` 改委托）

**Interfaces:**
- Produces: `object BrowseSorter` 的 `fun sortFolders(folders: List<Folder>, order: SortOrder): List<Folder>`、`fun sortFiles(files: List<MediaFile>, order: SortOrder): List<MediaFile>`；`internal fun compareNatural(a: String, b: String): Int`、`internal fun extractLeadingNumber(s: String): Double?`。Task 2 的 helper 依赖它们。

- [ ] **Step 1: 写失败测试**

新建 `android/app/src/test/java/com/juziss/localmediahub/viewmodel/BrowseSorterTest.kt`：

```kotlin
package com.juziss.localmediahub.viewmodel

import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseSorterTest {
    private fun file(name: String, size: Long = 0L, time: String = "") =
        MediaFile(name, "p/$name", "p/$name", size, time, "image", ".jpg")
    private fun folder(name: String, time: String = "") =
        Folder(name, "p/$name", "p/$name", false, time)

    @Test
    fun `compareNatural orders numerically`() {
        assertTrue(compareNatural("2", "10") < 0)
        assertTrue(compareNatural("img2", "img10") < 0)
        assertTrue(compareNatural("a", "b") < 0)
        assertEquals(0, compareNatural("x", "x"))
        assertTrue(compareNatural("10", "2") > 0)
    }

    @Test
    fun `extractLeadingNumber parses leading digits`() {
        assertEquals(7.0, extractLeadingNumber("007_gjco"), 0.0)
        assertEquals(10.0, extractLeadingNumber("10"), 0.0)
        assertNull(extractLeadingNumber("abc"))
        assertNull(extractLeadingNumber(""))
    }

    @Test
    fun `sortFiles NAME_ASC is natural order`() {
        val sorted = BrowseSorter.sortFiles(
            listOf(file("img10.jpg"), file("img2.jpg"), file("img1.jpg")),
            SortOrder.NAME_ASC,
        )
        assertEquals(listOf("img1.jpg", "img2.jpg", "img10.jpg"), sorted.map { it.name })
    }

    @Test
    fun `sortFiles SIZE_ASC and SIZE_DESC by size`() {
        val files = listOf(file("a", size = 30), file("b", size = 10), file("c", size = 20))
        assertEquals(
            listOf("b", "c", "a"),
            BrowseSorter.sortFiles(files, SortOrder.SIZE_ASC).map { it.name },
        )
        assertEquals(
            listOf("a", "c", "b"),
            BrowseSorter.sortFiles(files, SortOrder.SIZE_DESC).map { it.name },
        )
    }

    @Test
    fun `sortFolders ignores SIZE orders`() {
        val folders = listOf(folder("b"), folder("a"), folder("c"))
        assertEquals(
            listOf("b", "a", "c"),
            BrowseSorter.sortFolders(folders, SortOrder.SIZE_ASC).map { it.name },
        )
    }

    @Test
    fun `sortFolders TIME_DESC by modifiedTime`() {
        val folders = listOf(folder("old", "2024-01-01"), folder("new", "2024-12-31"))
        assertEquals(
            listOf("new", "old"),
            BrowseSorter.sortFolders(folders, SortOrder.TIME_DESC).map { it.name },
        )
    }

    @Test
    fun `sort handles empty and single-element lists`() {
        assertEquals(emptyList<MediaFile>(), BrowseSorter.sortFiles(emptyList(), SortOrder.NAME_ASC))
        assertEquals(
            listOf("only"),
            BrowseSorter.sortFiles(listOf(file("only")), SortOrder.NAME_ASC).map { it.name },
        )
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd android && ./gradlew testDebugUnitTest --tests "*.BrowseSorterTest"`
Expected: 编译失败，`Unresolved reference: BrowseSorter` / `compareNatural` / `extractLeadingNumber`。

- [ ] **Step 3: 新建 `BrowseSorter.kt`**

新建 `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseSorter.kt`：

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
        val ta = tokensA[i]
        val tb = tokensB[i]
        val numA = ta.toIntOrNull()
        val numB = tb.toIntOrNull()
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

- [ ] **Step 4: 运行测试确认通过**

Run: `cd android && ./gradlew testDebugUnitTest --tests "*.BrowseSorterTest"`
Expected: PASS（7 个用例）。

- [ ] **Step 5: `BrowseViewModel` 删除文件级 helper、排序方法改委托**

在 `BrowseViewModel.kt`：
- **删除**文件级 `extractLeadingNumber`（约 `:46-52`）与 `compareNatural`（约 `:55-72`）两个函数（逻辑已迁入 `BrowseSorter.kt`）。
- 把 `applySortToFolders`（约 `:465-475`）整体替换为一行委托：

```kotlin
    private fun applySortToFolders(folders: List<Folder>): List<Folder> =
        BrowseSorter.sortFolders(folders, _folderSortOrder.value)
```

- 把 `applySortToFiles`（约 `:477-488`）整体替换为一行委托：

```kotlin
    private fun applySortToFiles(files: List<MediaFile>): List<MediaFile> =
        BrowseSorter.sortFiles(files, _fileSortOrder.value)
```

（所有既有调用点——6 个 browse 分支、`setFolderSortOrder`/`setFileSortOrder`/`openCollection`——仍调用 `applySortToFolders`/`applySortToFiles`，签名未变，无需改动。`Folder` import 已在。）

- [ ] **Step 6: 全量单测 + 构建**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL；`BrowseSorterTest` + 既有 `BrowseViewModelTest`/`FavoritesStoreTest` 等全部 PASS（行为未变）。

- [ ] **Step 7: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseSorter.kt android/app/src/test/java/com/juziss/localmediahub/viewmodel/BrowseSorterTest.kt android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt
git commit -m "refactor(android): extract testable BrowseSorter from BrowseViewModel

Move the natural/numeric sort comparators out of BrowseViewModel into a pure,
stateless BrowseSorter object and add the first unit tests for them. The VM's
applySortToFolders/applySortToFiles now delegate; behavior unchanged.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 2: 折叠 6 处 browse-result 分支 + 移除排序包装

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt`

**Interfaces:**
- Consumes: Task 1 的 `BrowseSorter.sortFolders`/`sortFiles`。
- Produces（私有 helper）：`private suspend fun applyFolderResult(data: BrowseResult)`、`private suspend fun applySystemResult(data: SystemBrowseResult)`、`private fun emitBrowseError(message: String)`。

- [ ] **Step 1: 新增 3 个 helper**

在 `BrowseViewModel` 内（建议放在 `applySortToFiles` 委托方法之后、`getVideoStreamUrl` 之前）插入：

```kotlin
    /** 成功的文件夹浏览结果：存 raw、排序、emit Browsed。 */
    private suspend fun applyFolderResult(data: BrowseResult) {
        _rawFolders.value = data.folders
        _rawFiles.value = data.files
        val sortedFolders = withContext(Dispatchers.Default) {
            BrowseSorter.sortFolders(data.folders, _folderSortOrder.value)
        }
        val sortedFiles = withContext(Dispatchers.Default) {
            BrowseSorter.sortFiles(data.files, _fileSortOrder.value)
        }
        _browseState.value = BrowseState.Browsed(
            data.copy(folders = sortedFolders, files = sortedFiles)
        )
    }

    /** 成功的系统浏览结果：存 raw、排序、emit SystemBrowsed。 */
    private suspend fun applySystemResult(data: SystemBrowseResult) {
        _rawFolders.value = data.folders
        _rawFiles.value = data.files
        val sortedFolders = withContext(Dispatchers.Default) {
            BrowseSorter.sortFolders(data.folders, _folderSortOrder.value)
        }
        val sortedFiles = withContext(Dispatchers.Default) {
            BrowseSorter.sortFiles(data.files, _fileSortOrder.value)
        }
        _browseState.value = BrowseState.SystemBrowsed(
            SystemBrowseResult(
                currentPath = data.currentPath,
                drives = data.drives,
                folders = sortedFolders,
                files = sortedFiles,
            )
        )
    }

    private fun emitBrowseError(message: String) {
        _browseState.value = BrowseState.Error(message)
    }
```

- [ ] **Step 2: 折叠 `browseSystemPath` 的 Success/Error 分支**

把 `browseSystemPath`（约 `:259-286`）的 `when (val result = ...)` 整体替换为：

```kotlin
            when (val result = repository.browseSystemPath(absolutePath)) {
                is NetworkResult.Success -> {
                    recentActivityStore.saveLastBrowseLocation(
                        path = absolutePath,
                        title = folderName,
                        isSystemBrowse = true,
                    )
                    applySystemResult(result.data)
                }
                is NetworkResult.Error -> emitBrowseError(result.message)
                is NetworkResult.Loading -> {}
            }
```

- [ ] **Step 3: 折叠 `browseFolder` 的 Success/Error 分支**

把 `browseFolder`（约 `:297-321`）的 `when` 整体替换为：

```kotlin
            when (val result = repository.browseFolder(relativePath)) {
                is NetworkResult.Success -> {
                    recentActivityStore.saveLastBrowseLocation(
                        path = relativePath,
                        title = folderName,
                        isSystemBrowse = false,
                    )
                    applyFolderResult(result.data)
                }
                is NetworkResult.Error -> emitBrowseError(result.message)
                is NetworkResult.Loading -> {}
            }
```

- [ ] **Step 4: 折叠 `navigateBack` 的两个分支**

把 `navigateBack` 内 `else if (_isSystemBrowse.value)` 分支里的 `when (val result = repository.browseSystemPath(previousPath))`（约 `:353-376`）整体替换为：

```kotlin
                when (val result = repository.browseSystemPath(previousPath)) {
                    is NetworkResult.Success -> {
                        applySystemResult(result.data)
                        _restoreScrollTo.value = previousPath
                    }
                    is NetworkResult.Error -> emitBrowseError(result.message)
                    is NetworkResult.Loading -> {}
                }
```

把 `navigateBack` 内 `else` 分支里的 `when (val result = repository.browseFolder(previousPath))`（约 `:378-398`）整体替换为：

```kotlin
                when (val result = repository.browseFolder(previousPath)) {
                    is NetworkResult.Success -> {
                        applyFolderResult(result.data)
                        _restoreScrollTo.value = previousPath
                    }
                    is NetworkResult.Error -> emitBrowseError(result.message)
                    is NetworkResult.Loading -> {}
                }
```

- [ ] **Step 5: 折叠 `refreshCurrentDirectory` 的两个分支**

把 `refreshCurrentDirectory` 内系统分支的 `when (val result = repository.browseSystemPath(path))`（约 `:774-792`）整体替换为：

```kotlin
                    when (val result = repository.browseSystemPath(path)) {
                        is NetworkResult.Success -> applySystemResult(result.data)
                        is NetworkResult.Error -> emitBrowseError(result.message)
                        is NetworkResult.Loading -> {}
                    }
```

把 `refreshCurrentDirectory` 内文件夹分支的 `when (val result = repository.browseFolder(path))`（约 `:798-813`）整体替换为：

```kotlin
                    when (val result = repository.browseFolder(path)) {
                        is NetworkResult.Success -> applyFolderResult(result.data)
                        is NetworkResult.Error -> emitBrowseError(result.message)
                        is NetworkResult.Loading -> {}
                    }
```

- [ ] **Step 6: 移除现已无用的排序包装、rewire 剩余调用方**

折叠后，`applySortToFolders`/`applySortToFiles` 的调用方只剩 `setFolderSortOrder`/`setFileSortOrder`/`openCollection`。把它们改为直接调 `BrowseSorter`：

- `setFolderSortOrder`（约 `:411`）：`applySortToFolders(rawFolders)` → `BrowseSorter.sortFolders(rawFolders, _folderSortOrder.value)`。
- `setFileSortOrder`（约 `:437`）：`applySortToFiles(rawFiles)` → `BrowseSorter.sortFiles(rawFiles, _fileSortOrder.value)`。
- `openCollection`（约 `:664`）：`applySortToFiles(result.data)` → `BrowseSorter.sortFiles(result.data, _fileSortOrder.value)`。

然后**删除** `applySortToFolders` 与 `applySortToFiles` 两个委托方法（现已无调用方）。

- [ ] **Step 7: 全量单测 + 构建**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL；既有 `BrowseViewModelTest` + `BrowseSorterTest` 全部 PASS（行为未变；VM 体积下降）。

- [ ] **Step 8: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt
git commit -m "refactor(android): collapse BrowseViewModel browse-result branches

Six near-identical reload+sort+emit branches (browseSystemPath/browseFolder,
navigateBack x2, refreshCurrentDirectory x2) each duplicated raw-cache + sort +
state-emit logic, a bug magnet where one branch's sort change could be missed
in the other five. Extract applyFolderResult/applySystemResult/emitBrowseError
helpers and drop the now-unused applySortTo* wrappers. Behavior-preserving.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Self-Review（作者已执行）

**1. Spec 覆盖**：
- §3 抽 `BrowseSorter`（`object` + `internal` helper + 从 VM 移除 4 排序函数）→ Task 1 Step 3/5 + Task 2 Step 6。✅
- §4 折叠 6 分支（3 helper + 6 调用点）→ Task 2 Step 1-5。✅
- §5 `BrowseSorterTest`（自然/数字/SIZE/文件夹忽略 SIZE/TIME/边界）→ Task 1 Step 1。✅
- §6 两步提交 → Task 1 / Task 2。✅
- §7 决策（`object`、`internal`、`emitBrowseError`、纯重构）→ 各步骤落地。✅

**2. 占位符扫描**：无 TBD/TODO；每个代码步骤含完整代码；每条命令含期望输出。✅

**3. 类型/签名一致性**：
- `BrowseSorter.sortFolders(List<Folder>, SortOrder): List<Folder>` / `sortFiles(List<MediaFile>, SortOrder): List<MediaFile>` —— Task 1 Step 3 定义；Task 1 Step 5 委托、Task 2 Step 1/6 调用，签名一致。✅
- `compareNatural(String,String): Int` / `extractLeadingNumber(String): Double?`（`internal`）—— Task 1 Step 3 定义、Step 1 测试调用，一致。✅
- `applyFolderResult(BrowseResult)` / `applySystemResult(SystemBrowseResult)` / `emitBrowseError(String)` —— Task 2 Step 1 定义、Step 2-5 调用，签名一致。✅
- `Folder(name,path,relativePath,isRoot,modifiedTime)` / `MediaFile(name,path,relativePath,size,modifiedTime,mediaType,extension)`（`modifiedTime` 为 `String`）—— Task 1 Step 1 测试构造与 `Models.kt` 一致。✅
- Task 2 Step 6 删除 `applySortToFolders`/`applySortToFiles` 前，已把 3 个剩余调用方（`setFolderSortOrder`/`setFileSortOrder`/`openCollection`）rewire 到 `BrowseSorter`，无悬空引用。✅
