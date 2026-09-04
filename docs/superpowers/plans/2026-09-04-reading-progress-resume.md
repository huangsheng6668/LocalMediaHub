# 阅读进度恢复（章节+段落）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Web 与 Android 阅读器打开书时恢复到上次阅读的章节与该章内首个可见段落（纯本地存储，无服务端改动）。

**Architecture:** Android 扩展 `BookProgress` 增加 `blockIndex`，恢复查询 key 统一为 `b.path`，新增纯函数 `ReaderListLayout` 做 LazyColumn 全局 item 索引 ↔ (章, 段) 映射，VM 以一次性消费的 `pendingResume` 交付恢复目标，Screen 在保存/恢复两端接线。Web 进度 payload 改为 `{chapterIndex, paraIndex, lastReadAt}`，`progress.js` 新增纯函数 `firstVisibleParagraph`，`textReader.js` 新增防抖保存 + `resolveResume` 决策函数，恢复复用现有 `scrollToParagraph`。

**Tech Stack:** Kotlin/Compose（Android）、ES module 原生 JS + node:test + jsdom（Web）、Gson/Preferences DataStore。

**Spec:** `docs/superpowers/specs/2026-09-04-reading-progress-resume-design.md`

## Global Constraints

- 服务端（`server/`）**零改动**；不新增任何 HTTP 端点。
- 测试命令按改动范围选择：改 `server/internal/web/` → `cd server/internal/web && node --test`；改 Android → `cd android && ./gradlew testDebugUnitTest`。
- Web 代码涉及 `innerHTML` 等 sink 必须带 `// XSS-SAFE:` 注释或使用 DOM API（本计划新代码全部走 DOM API / textContent，无新增 sink）。
- Web `import` 路径必须带 `.js` 扩展名；测试文件用 `.test.mjs`。
- Kotlin 遵循现有 MVVM 分层：映射纯函数放 `data/`，VM 持状态，Screen 只做 UI 接线。
- Conventional Commits，scope 用 `reader` / `android`。
- **提交受阻预案**：宿主 Mimosa 钩子会对 `git commit` 做全项目扫描并可能拦截（既有误报：transcode/thumbnail 等 `exec.Command` 模式，与本计划无关）。若 commit 被拦：保留工作区改动，执行 `git add` 暂存后**继续下一任务**，在最终交付说明中列出未落盘的 commit 清单，由用户侧处理闸门后一次性补提交。禁止为绕过闸门修改无关安全代码。
- 每个任务结束时改动必须在该侧测试全绿后才进入提交步骤。

---

### Task 1: Android — `BookProgress` 增加 `blockIndex` 字段 + 旧 JSON 兼容

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/RecentActivityStore.kt:47-52`
- Test: `android/app/src/test/java/com/juziss/localmediahub/data/RecentActivityStoreBookProgressTest.kt`

**Interfaces:**
- Produces: `BookProgress(path: String, chapterIndex: Int, blockIndex: Int = 0, scrollOffsetPx: Int, lastReadAt: Long)` —— `blockIndex` = 章内首个可见 block 索引；`scrollOffsetPx` 语义变为"blockIndex item 内 px 偏移"。Task 3/4 依赖此构造函数签名。

- [ ] **Step 1: 写失败测试（含旧 JSON 反序列化用例）**

在 `RecentActivityStoreBookProgressTest.kt` 末尾追加两个测试（沿用该文件现有 import；`Gson`/`TypeToken` 需补 `import com.google.gson.Gson` 与 `import com.google.gson.reflect.TypeToken`）：

```kotlin
    @Test
    fun roundTripWithBlockIndex() = runTest {
        val p = BookProgress(
            path = "/books/a.txt",
            chapterIndex = 3,
            blockIndex = 12,
            scrollOffsetPx = 45,
            lastReadAt = 1_700_000_000_000,
        )
        withContext(Dispatchers.IO) { store.saveBookProgress(p) }
        val loaded = withContext(Dispatchers.IO) { store.getBookProgress("/books/a.txt") }
        assertEquals(12, loaded?.blockIndex)
        assertEquals(45, loaded?.scrollOffsetPx)
    }

    @Test
    fun legacyJsonWithoutBlockIndexDecodesToZero() {
        // 旧版本存储的 payload 没有 blockIndex 字段；Gson（Unsafe 分配）应给 int 缺省 0，
        // 使旧记录退化为"章级恢复"。锁定该行为防止未来重构破坏向后兼容。
        val legacy = """{"/books/a.txt":{"path":"/books/a.txt","chapterIndex":2,"scrollOffsetPx":7,"lastReadAt":123}}"""
        val map = Gson().fromJson<MutableMap<String, BookProgress>>(
            legacy,
            object : TypeToken<MutableMap<String, BookProgress>>() {}.type,
        )
        assertEquals(2, map["/books/a.txt"]?.chapterIndex)
        assertEquals(0, map["/books/a.txt"]?.blockIndex)
    }
```

- [ ] **Step 2: 运行验证失败**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.data.RecentActivityStoreBookProgressTest"`
Expected: 编译失败 —— `BookProgress` 构造函数没有 `blockIndex` 参数。

- [ ] **Step 3: 最小实现**

`RecentActivityStore.kt` 中数据类改为（doc comment 同步语义变更）：

```kotlin
/**
 * 客户端保存的电子书阅读进度。path 为书在服务端的 relativePath;
 * chapterIndex 是当前章节索引;blockIndex 是章内首个可见 block 索引;
 * scrollOffsetPx 是 blockIndex item 内的像素偏移;
 * lastReadAt 是 epoch 毫秒,用于排序书架展示。
 */
data class BookProgress(
    val path: String,
    val chapterIndex: Int,
    val blockIndex: Int = 0,
    val scrollOffsetPx: Int,
    val lastReadAt: Long,
)
```

注意：文件内其他 `BookProgress(...)` 构造点（测试夹具、`HomeViewModel` 等若有无 blockIndex 的位置参数调用）不受影响——`blockIndex` 是带默认值的中位参数，**已有调用必须用具名参数**。检查 `grep -rn "BookProgress(" android/app/src --include=*.kt`，任何使用位置参数且在 `chapterIndex` 之后还有实参的调用点会编译失败，逐一补 `blockIndex = 0` 或按语义赋值。

- [ ] **Step 4: 运行验证通过**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.data.RecentActivityStoreBookProgressTest"`
Expected: PASS（含新增两用例）。

- [ ] **Step 5: 提交（含 spec 文档）**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/data/RecentActivityStore.kt \
        android/app/src/test/java/com/juziss/localmediahub/data/RecentActivityStoreBookProgressTest.kt \
        docs/superpowers/specs/2026-09-04-reading-progress-resume-design.md
git commit -m "feat(reader): BookProgress gains blockIndex for paragraph-level resume"
```

---

### Task 2: Android — `ReaderListLayout` 映射纯函数

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/data/ReaderListLayout.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/data/ReaderListLayoutTest.kt`

**Interfaces:**
- Consumes: `ScrollModeChapter(chapterIndex: Int, title: String, blocks: List<Block>)`（`Models.kt:142`，已有）。
- Produces（Task 4 依赖）:
  - `ReaderListLayout.CHAPTER_MODE_HEADER_ITEMS: Int`（= 1）
  - `ReaderListLayout.scrollItemIndex(chapters: List<ScrollModeChapter>, chapterIndex: Int, blockIndex: Int): Int` —— 目标章不在列表返回 -1；blockIndex 越界 coerce 到该章末块。
  - `ReaderListLayout.scrollChapterBlock(chapters: List<ScrollModeChapter>, itemIndex: Int): Pair<Int, Int>` —— 返回 (chapterIndex, blockIndex)；itemIndex 超出已加载范围（末尾加载指示器等）返回 `(-1, -1)`；标题 item → (章, 0)；分隔符 item → (章, 末块)。

- [ ] **Step 1: 写失败测试**

创建 `ReaderListLayoutTest.kt`（纯 JVM 单测，无需 Robolectric；`Block` 构造：文本块 `Block(type="text", value="x")`，若 `Block` 有必填 image 字段则用其具名默认——先查 `Models.kt` 中 `Block` 定义再写夹具）：

```kotlin
package com.juziss.localmediahub.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderListLayoutTest {

    private fun ch(idx: Int, blocks: Int) = ScrollModeChapter(
        chapterIndex = idx,
        title = "C$idx",
        blocks = List(blocks) { Block(type = "text", value = "p$it") },
    )

    // 布局事实：每章 = [标题, blocks..., 分隔符] 共 blocks.size + 2 个 item
    private val chapters = listOf(ch(0, 2), ch(1, 3), ch(5, 1))

    @Test
    fun `scrollItemIndex maps within first chapter`() {
        // 章 0：标题=item0, b0=item1, b1=item2, 分隔符=item3
        assertEquals(1, ReaderListLayout.scrollItemIndex(chapters, 0, 0))
        assertEquals(2, ReaderListLayout.scrollItemIndex(chapters, 0, 1))
    }

    @Test
    fun `scrollItemIndex maps across chapters`() {
        // 章 1 起始 item = (2+2) = 4；b2 = item 4+1+2 = 7
        assertEquals(5, ReaderListLayout.scrollItemIndex(chapters, 1, 1))
        assertEquals(7, ReaderListLayout.scrollItemIndex(chapters, 1, 2))
        // 章 5 起始 = 4 + (3+2) = 9；b0 = item 10
        assertEquals(10, ReaderListLayout.scrollItemIndex(chapters, 5, 0))
    }

    @Test
    fun `scrollItemIndex coerces out-of-range block`() {
        assertEquals(2, ReaderListLayout.scrollItemIndex(chapters, 0, 99)) // → 末块 b1
    }

    @Test
    fun `scrollItemIndex returns -1 when chapter absent`() {
        assertEquals(-1, ReaderListLayout.scrollItemIndex(chapters, 3, 0))
    }

    @Test
    fun `scrollChapterBlock reverse maps block items`() {
        assertEquals(0 to 0, ReaderListLayout.scrollChapterBlock(chapters, 1))
        assertEquals(0 to 1, ReaderListLayout.scrollChapterBlock(chapters, 2))
        assertEquals(1 to 2, ReaderListLayout.scrollChapterBlock(chapters, 7))
        assertEquals(5 to 0, ReaderListLayout.scrollChapterBlock(chapters, 10))
    }

    @Test
    fun `scrollChapterBlock maps title and separator items`() {
        assertEquals(0 to 0, ReaderListLayout.scrollChapterBlock(chapters, 0)) // 章 0 标题
        assertEquals(0 to 1, ReaderListLayout.scrollChapterBlock(chapters, 3)) // 章 0 分隔符 → 末块
        assertEquals(1 to 0, ReaderListLayout.scrollChapterBlock(chapters, 4)) // 章 1 标题
    }

    @Test
    fun `scrollChapterBlock returns minus1 pair beyond loaded range`() {
        assertEquals(-1 to -1, ReaderListLayout.scrollChapterBlock(chapters, 12)) // 全部 item = 4+5+3 = 12
    }

    @Test
    fun `empty chapters degenerate safely`() {
        assertEquals(-1, ReaderListLayout.scrollItemIndex(emptyList(), 0, 0))
        assertEquals(-1 to -1, ReaderListLayout.scrollChapterBlock(emptyList(), 0))
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.data.ReaderListLayoutTest"`
Expected: 编译失败 —— `ReaderListLayout` 不存在。

- [ ] **Step 3: 实现**

创建 `ReaderListLayout.kt`：

```kotlin
package com.juziss.localmediahub.data

/**
 * 阅读器 LazyColumn 全局 item 索引 ↔ (章, 章内 block) 的双向映射（纯函数）。
 *
 * 布局契约（TextReaderScreen）：
 * - 分章模式：item 0 = 章标题，item 1..N = blocks，末尾 1 个 ❖。
 * - 滚动模式：每章 [标题, blocks..., 分隔符] 共 blocks.size + 2 个 item，
 *   列表末尾可能附加加载指示器（位于所有章之后）。
 */
object ReaderListLayout {

    /** 分章模式中章标题占据的前置 item 数。block b 对应全局 item 1 + b。 */
    const val CHAPTER_MODE_HEADER_ITEMS = 1

    /** 滚动模式：目标 (章, 段) 的全局 item 索引。目标章不在已加载列表返回 -1。 */
    fun scrollItemIndex(chapters: List<ScrollModeChapter>, chapterIndex: Int, blockIndex: Int): Int {
        var base = 0
        for (ch in chapters) {
            if (ch.chapterIndex == chapterIndex) {
                val lastBlock = (ch.blocks.size - 1).coerceAtLeast(0)
                return base + 1 + blockIndex.coerceIn(0, lastBlock)
            }
            base += ch.blocks.size + 2
        }
        return -1
    }

    /** 滚动模式：全局 item 索引 → (章, 章内 block)。超出已加载范围返回 (-1, -1)。 */
    fun scrollChapterBlock(chapters: List<ScrollModeChapter>, itemIndex: Int): Pair<Int, Int> {
        var base = 0
        for (ch in chapters) {
            val size = ch.blocks.size + 2
            if (itemIndex < base + size) {
                val local = itemIndex - base
                return when {
                    local == 0 -> ch.chapterIndex to 0                                  // 章标题
                    local <= ch.blocks.size -> ch.chapterIndex to (local - 1)           // 段落
                    else -> ch.chapterIndex to (ch.blocks.size - 1).coerceAtLeast(0)    // 分隔符 → 末段
                }
            }
            base += size
        }
        return -1 to -1
    }
}
```

- [ ] **Step 4: 运行验证通过**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.data.ReaderListLayoutTest"`
Expected: PASS 全绿。

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/data/ReaderListLayout.kt \
        android/app/src/test/java/com/juziss/localmediahub/data/ReaderListLayoutTest.kt
git commit -m "feat(reader): ReaderListLayout item-index mapping helpers"
```

---

### Task 3: Android — ViewModel：key 统一 + 保存签名 + pendingResume

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/TextReaderViewModel.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/viewmodel/TextReaderViewModelReaderTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `BookProgress(path, chapterIndex, blockIndex, scrollOffsetPx, lastReadAt)`。
- Produces（Task 4 依赖）:
  - `pendingResume: StateFlow<BookProgress?>` + `fun consumePendingResume()`
  - `fun persistScrollProgress(chapterIndex: Int, blockIndex: Int, scrollOffsetPx: Int)`（**签名变更**，旧两参签名删除）

- [ ] **Step 1: 写失败测试**

在 `TextReaderViewModelReaderTest.kt` 追加（该文件已 import mockk/runTest 等；需补 `import com.juziss.localmediahub.data.BookProgress`）。`store` 在现有测试里如何构造请沿用该文件当前做法（mockk `RecentActivityStore`）；下列用例按 mockk 风格：

```kotlin
    @Test
    fun `loadBook restores progress keyed by book path not request path`() = runTest {
        // 服务端规范化后 Book.path 与请求 path 不同（如盘符大写）；恢复必须用 b.path 查询
        val repo = mockk<MediaRepository>()
        val store = mockk<RecentActivityStore>(relaxed = true)
        coEvery { repo.getBookInfo("/raw/path/book.txt") } returns NetworkResult.Success(fakeBook(path = "/E:/Canonical/book.txt"))
        coEvery { store.readerSettingsFlow } returns flowOf(ReaderSettings())
        coEvery { store.getBookProgress("/E:/Canonical/book.txt") } returns BookProgress(
            path = "/E:/Canonical/book.txt", chapterIndex = 1, blockIndex = 2,
            scrollOffsetPx = 30, lastReadAt = 1L,
        )
        val vm = createVm(repo, store)

        vm.loadBook("/raw/path/book.txt")
        advanceUntilIdle()

        assertEquals(1, vm.currentIndex.value)
        assertEquals(2, vm.pendingResume.value?.blockIndex)
        coVerify { store.getBookProgress("/E:/Canonical/book.txt") }
        coVerify(exactly = 0) { store.getBookProgress("/raw/path/book.txt") }
    }

    @Test
    fun `consumePendingResume clears target`() = runTest {
        val vm = createVm(mockk(relaxed = true), mockk(relaxed = true))
        vm.consumePendingResume()
        assertEquals(null, vm.pendingResume.value)
    }
```

注：`advanceUntilIdle` 若该文件尚未使用，需 `import kotlinx.coroutines.test.advanceUntilIdle`。若 `ReaderSettings()` 构造需要参数（查看 `ReaderSettings.kt`），按现有测试夹具写法提供。

- [ ] **Step 2: 运行验证失败**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.viewmodel.TextReaderViewModelReaderTest"`
Expected: 编译失败 —— `pendingResume` 不存在。

- [ ] **Step 3: 实现 VM 变更**

`TextReaderViewModel.kt`：

1）新增状态（放在 `_bookmarks` 定义附近）：

```kotlin
    // 开书时的一次性恢复目标：processBookLoaded 写入，Screen 定位后 consume。
    // 只在打开书时存在——阅读模式切换触发的 reload 不再恢复。
    private val _pendingResume = MutableStateFlow<BookProgress?>(null)
    val pendingResume: StateFlow<BookProgress?> = _pendingResume.asStateFlow()

    fun consumePendingResume() {
        _pendingResume.value = null
    }
```

2）`processBookLoaded` 全量替换为（key 统一 + 等待设置加载 + pendingResume）：

```kotlin
    private suspend fun processBookLoaded(b: Book, path: String) {
        _book.value = b
        if (b.format == "unsupported") {
            _error.value = appContext.getString(R.string.reader_unsupported_format)
            _isLoading.value = false
            return
        }
        // 等设置首次从 DataStore 加载完成，保证 Screen 依据 readingMode 分派恢复分支时
        // 模式已定型（默认值 CHAPTER 与用户持久化的 SCROLL 之间的竞态窗口关闭）。
        store.readerSettingsFlow.first()
        // 进度 key 统一为服务端规范化路径 b.path：保存点全部用 b.path，
        // 恢复查询也必须用 b.path，否则请求 path 与规范化 path 不一致时永远查不到。
        val saved = store.getBookProgress(b.path)
        val lastValid = b.chapters.lastIndex.coerceAtLeast(0)
        val idx = saved?.chapterIndex?.coerceIn(0, lastValid) ?: 0
        if (saved != null && idx > 0) {
            _pendingResume.value = saved.copy(chapterIndex = idx)
        }
        loadChapter(idx)
        _chromeVisible.value = !_readerSettings.value.immersiveMode
    }
```

需补 `import kotlinx.coroutines.flow.first`。

3）三处保存点改写：
- `loadChapter` 成功分支（约 :294）：
```kotlin
                store.saveBookProgress(
                    BookProgress(
                        path = b.path,
                        chapterIndex = index,
                        blockIndex = 0,
                        scrollOffsetPx = 0,
                        lastReadAt = System.currentTimeMillis(),
                    )
                )
```
- `updateCurrentIndex`（约 :434）同样改为 `blockIndex = 0, scrollOffsetPx = 0`。
- `persistScrollProgress` 签名与实现替换（约 :473-485）：
```kotlin
    /**
     * Called by the UI layer (throttled via snapshotFlow + debounce) to persist
     * the within-chapter reading position: first visible block + its px offset.
     * Does NOT re-fetch chapter content — only writes progress so the next
     * session can resume mid-chapter.
     */
    fun persistScrollProgress(chapterIndex: Int, blockIndex: Int, scrollOffsetPx: Int) {
        val b = _book.value ?: return
        viewModelScope.launch {
            store.saveBookProgress(
                BookProgress(
                    path = b.path,
                    chapterIndex = chapterIndex,
                    blockIndex = blockIndex.coerceAtLeast(0),
                    scrollOffsetPx = scrollOffsetPx.coerceAtLeast(0),
                    lastReadAt = System.currentTimeMillis(),
                )
            )
        }
    }
```

- [ ] **Step 4: 运行验证**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.viewmodel.TextReaderViewModelReaderTest"`
Expected: 新用例 PASS；旧用例若因 `persistScrollProgress` 签名或 `getBookProgress` 参数 mock 不匹配而失败，按新契约修正旧用例的 stub（`coEvery { store.getBookProgress(any()) } returns null` 等）。

注意：此步 **Screen 尚未接线新签名**，`TextReaderScreen.kt` 对旧 `persistScrollProgress(itemIdx, offset)` 的调用会编译失败——本任务的验证范围是 VM 测试，`testDebugUnitTest` 编译 app 模块 main source set 时会连带编译 Screen。**因此 Task 3 与 Task 4 必须在同一次编译通过**：执行顺序为先完成 Task 3 Step 3，随即做 Task 4 Step 1（Screen 接线），再统一跑 `testDebugUnitTest`，两个任务一起提交。若按任务隔离执行，可临时将 Task 4 Step 1 提前合并进本步。

- [ ] **Step 5: 提交（与 Task 4 合并执行后）**

见 Task 4 Step 4。

---

### Task 4: Android — Screen：保存映射接线 + 开书恢复 scrollToItem

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt:325-331`（debounce collect）与 `:335-349`（scroll 预载 effect）及 collectAsState 区（新增 pendingResume）

**Interfaces:**
- Consumes: Task 2 `ReaderListLayout`、Task 3 `pendingResume`/`consumePendingResume`/`persistScrollProgress(chapterIndex, blockIndex, scrollOffsetPx)`。

- [ ] **Step 1: Screen 接线**

1）在 `collectAsState` 区（`:150` 附近）新增：

```kotlin
    val pendingResume by viewModel.pendingResume.collectAsState()
```

2）替换节流保存 effect（原 `:325-331`）——把全局 item 索引按模式映射为 (章, 段) 再上抛：

```kotlin
    // 节流保存阅读进度（停止滚动 1s 后写入）。把 listState 的全局 item 索引映射为
    // (章, 章内 block)，使记录与已加载章节数解耦、跨会话可恢复。
    LaunchedEffect(listState, idx) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .debounce(1000)
            .collect { (itemIdx, offset) ->
                if (isScrollMode) {
                    val (chIdx, blockIdx) = ReaderListLayout.scrollChapterBlock(
                        viewModel.scrollChapters.value, itemIdx,
                    )
                    if (chIdx >= 0) viewModel.persistScrollProgress(chIdx, blockIdx, offset)
                } else {
                    // 分章模式：item 0 = 章标题；滚到底首可见为 ❖ 时 coerce 到末段
                    val lastBlock = (blocks.size - 1).coerceAtLeast(0)
                    val blockIdx = (itemIdx - ReaderListLayout.CHAPTER_MODE_HEADER_ITEMS)
                        .coerceIn(0, lastBlock)
                    viewModel.persistScrollProgress(idx, blockIdx, offset)
                }
            }
    }
```

3）滚动模式预载 effect（原 `:335-349`）末尾追加开书恢复：

```kotlin
    // ---------- 滚动模式：进入后同时向前和向后预加载 ----------
    LaunchedEffect(isScrollMode) {
        if (!isScrollMode) return@LaunchedEffect
        viewModel.preloadScrollChapters(3)
        val addedItems = viewModel.preloadPreviousScrollChapters(2)
        if (addedItems > 0) {
            val current = listState.firstVisibleItemIndex
            listState.scrollToItem(
                index = (current + addedItems).coerceAtLeast(0),
                scrollOffset = listState.firstVisibleItemScrollOffset,
            )
        }
        // 开书恢复：预载完成后定位到上次阅读段落（覆盖上方的补偿滚动）。
        // 必须读 viewModel.scrollChapters.value（StateFlow 即时值）——collectAsState
        // 的 State 更新可能滞后于挂起函数返回，读委托属性会拿到预载前的旧列表。
        val saved = viewModel.pendingResume.value ?: return@LaunchedEffect
        val target = ReaderListLayout.scrollItemIndex(
            viewModel.scrollChapters.value, saved.chapterIndex, saved.blockIndex,
        )
        if (target >= 0 && (saved.blockIndex > 0 || saved.scrollOffsetPx > 0)) {
            listState.scrollToItem(target, saved.scrollOffsetPx.coerceAtLeast(0))
        }
        viewModel.consumePendingResume()
    }
```

4）新增分章模式恢复 effect（放在上面两个 effect 之后）：

```kotlin
    // 开书恢复（分章模式）：目标章内容就绪后一次性定位，随后消费。
    // 旧格式记录（blockIndex=0 且 offset=0）等价于章顶——跳过 scrollToItem，无行为回归。
    LaunchedEffect(pendingResume, blocks) {
        val saved = pendingResume ?: return@LaunchedEffect
        if (isScrollMode) return@LaunchedEffect // 滚动模式在 isScrollMode effect 内处理
        if (blocks.isEmpty()) return@LaunchedEffect
        val lastBlock = (blocks.size - 1).coerceAtLeast(0)
        val blk = saved.blockIndex.coerceIn(0, lastBlock)
        if (blk > 0 || saved.scrollOffsetPx > 0) {
            listState.scrollToItem(
                ReaderListLayout.CHAPTER_MODE_HEADER_ITEMS + blk,
                saved.scrollOffsetPx.coerceAtLeast(0),
            )
        }
        viewModel.consumePendingResume()
    }
```

5）`import com.juziss.localmediahub.data.ReaderListLayout` 补进 import 区。

- [ ] **Step 2: 运行 Android 全量单测**

Run: `cd android && ./gradlew testDebugUnitTest`
Expected: 全绿（含 Task 1/2/3 用例与既有全部用例）。

- [ ] **Step 3: 手工冒烟（如有设备/模拟器，可选但推荐）**

打开一本多章书 → 翻到第 N 章 → 滚到中间段落 → 等 1 秒 → 返回退出 → 分别从「浏览页」与「首页书架」重新打开 → 应落在第 N 章该段落附近；两个入口都要验证（覆盖 key 统一修复）。

- [ ] **Step 4: 提交（Task 3 + Task 4 一起）**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/viewmodel/TextReaderViewModel.kt \
        android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt \
        android/app/src/test/java/com/juziss/localmediahub/viewmodel/TextReaderViewModelReaderTest.kt
git commit -m "feat(android): resume reading at chapter+paragraph, unify progress key to b.path"
```

---

### Task 5: Web — `progress.js` 新增 `firstVisibleParagraph` 纯函数

**Files:**
- Modify: `server/internal/web/progress.js`
- Test: `server/internal/web/progress.test.mjs`

**Interfaces:**
- Produces（Task 6 依赖）: `firstVisibleParagraph(paragraphs: Array<{top: number, bottom: number, chapterIndex: number, paraIndex: number}>, containerTop: number): ({top, bottom, chapterIndex, paraIndex} | null)` —— 返回首个"底部边超过容器顶"的段落（即未完全滚出视口的第一个）；全部滚出时返回最后一段；空数组返回 null。

- [ ] **Step 1: 写失败测试**

在 `progress.test.mjs` 追加（沿用文件现有 `test`/`assert` import）：

```javascript
// mock paragraph：模拟 getBoundingClientRect 的 {top, bottom} + 章节/段落索引。
function mkPara(top, bottom, chapterIndex, paraIndex) {
    return { top, bottom, chapterIndex, paraIndex };
}

test('firstVisibleParagraph: returns first paragraph whose bottom crosses container top', () => {
    const paras = [
        mkPara(-100, -10, 0, 0),   // 已完全滚出
        mkPara(-10, 40, 0, 1),     // 部分可见 → 目标
        mkPara(40, 120, 0, 2),
    ];
    const hit = firstVisibleParagraph(paras, 0);
    assert.equal(hit.paraIndex, 1);
    assert.equal(hit.chapterIndex, 0);
});

test('firstVisibleParagraph: fully-visible first paragraph wins', () => {
    const paras = [mkPara(10, 80, 2, 3)];
    assert.equal(firstVisibleParagraph(paras, 0).paraIndex, 3);
});

test('firstVisibleParagraph: all scrolled past → last paragraph', () => {
    const paras = [mkPara(-200, -100, 0, 0), mkPara(-100, -20, 0, 4)];
    assert.equal(firstVisibleParagraph(paras, 0).paraIndex, 4);
});

test('firstVisibleParagraph: empty → null', () => {
    assert.equal(firstVisibleParagraph([], 0), null);
});

test('firstVisibleParagraph: honours nonzero container top', () => {
    const paras = [mkPara(50, 90, 1, 0), mkPara(90, 150, 1, 1)];
    // containerTop=100：第一段 bottom 90 < 100 已滚出，第二段部分可见
    assert.equal(firstVisibleParagraph(paras, 100).paraIndex, 1);
});
```

并把文件顶部 import 行改为：

```javascript
import { detectActiveChapterOnScroll, computePercent, firstVisibleParagraph } from './progress.js';
```

- [ ] **Step 2: 运行验证失败**

Run: `cd server/internal/web && node --test progress.test.mjs`
Expected: FAIL —— `firstVisibleParagraph` 未导出。

- [ ] **Step 3: 实现**

`progress.js` 末尾追加：

```javascript
// 纯函数：给定按文档顺序排列的段落 rect 列表与阅读容器顶边，返回"首个可见段落"
// （底部边尚未完全滚出视口的第一个段落）。用于阅读进度的段级保存：
// - 部分露出（top < containerTop < bottom）或完整在视口内都算可见；
// - 全部滚出（用户停在章末）返回最后一段；空列表返回 null。
export function firstVisibleParagraph(paragraphs, containerTop) {
    for (const p of paragraphs) {
        if (p.bottom - containerTop > 0) return p;
    }
    return paragraphs.length ? paragraphs[paragraphs.length - 1] : null;
}
```

- [ ] **Step 4: 运行验证通过**

Run: `cd server/internal/web && node --test progress.test.mjs`
Expected: PASS 全绿。

- [ ] **Step 5: 提交**

```bash
git add server/internal/web/progress.js server/internal/web/progress.test.mjs
git commit -m "feat(web): firstVisibleParagraph helper for paragraph-level progress"
```

---

### Task 6: Web — `textReader.js` 存/取改造 + 恢复接线

**Files:**
- Modify: `server/internal/web/textReader.js`（:19 常量区、:245-254 恢复决策、:400/:499/:652 保存点、:474-505 scroll handler、:509-526 cleanup、:529 初始加载）
- Test: `server/internal/web/textReader.test.mjs`

**Interfaces:**
- Consumes: Task 5 `firstVisibleParagraph`。
- Produces: localStorage `book_progress:<path>` payload 变更为 `{ chapterIndex, paraIndex, lastReadAt }`（`bookshelf.js` 只读 chapterIndex/lastReadAt/path，兼容）；导出纯函数 `resolveResume({ chapterParam, paraParam, saved, chapterCount }) → { startIdx, resumePara }`。

- [ ] **Step 1: 写失败测试**

在 `textReader.test.mjs` 追加（`renderTextReader` 已在该文件 import；需补 `import { state } from './reader-state.js'` 与 `import { resolveResume } from './textReader.js'`——合并进现有 import 语句）：

```javascript
// ============================================================================
// resolveResume pure helper tests
// ============================================================================

test('resolveResume: explicit chapter param wins over saved progress', () => {
    const r = resolveResume({
        chapterParam: '0',
        paraParam: null,
        saved: { chapterIndex: 2, paraIndex: 5 },
        chapterCount: 10,
    });
    assert.equal(r.startIdx, 0);
    assert.equal(r.resumePara, null); // URL 只指定章 → 章顶，不套用存档段落
});

test('resolveResume: URL para param applies', () => {
    const r = resolveResume({
        chapterParam: '1',
        paraParam: '3',
        saved: null,
        chapterCount: 10,
    });
    assert.equal(r.startIdx, 1);
    assert.equal(r.resumePara, 3);
});

test('resolveResume: saved progress restores chapter and paragraph', () => {
    const r = resolveResume({
        chapterParam: null,
        paraParam: null,
        saved: { chapterIndex: 4, paraIndex: 7 },
        chapterCount: 10,
    });
    assert.equal(r.startIdx, 4);
    assert.equal(r.resumePara, 7);
});

test('resolveResume: legacy payload without paraIndex → chapter only', () => {
    const r = resolveResume({
        chapterParam: null,
        paraParam: null,
        saved: { chapterIndex: 4 },
        chapterCount: 10,
    });
    assert.equal(r.startIdx, 4);
    assert.equal(r.resumePara, null);
});

test('resolveResume: clamps out-of-range chapter', () => {
    const r = resolveResume({ chapterParam: null, paraParam: null, saved: { chapterIndex: 99 }, chapterCount: 3 });
    assert.equal(r.startIdx, 2);
});

// ============================================================================
// integration: saved progress restores chapter via render
// ============================================================================

test('renderTextReader: reopens at saved chapter when no chapter param', async () => {
    localStorage.setItem('book_progress:/test/book.txt', JSON.stringify({ chapterIndex: 2, paraIndex: 0 }));
    await renderTextReader(viewContainer(), '/test/book.txt', null, null);
    assert.equal(state.currentIdx, 2);
    assert.ok(localStorage.getItem('book_progress:/test/book.txt').includes('"paraIndex"'));
});
```

注：jsdom 无布局（offsetTop/scrollTop 均为 0），段落级定位在单测中不可断言——段级行为由 `resolveResume` 纯函数与 `firstVisibleParagraph`（Task 5）覆盖；集成用例只断言章级与 payload 形状。渲染后请调用该容器上的清理（现有测试若有 `container._cleanupReader` 收尾惯例则沿用）。

- [ ] **Step 2: 运行验证失败**

Run: `cd server/internal/web && node --test textReader.test.mjs`
Expected: FAIL —— `resolveResume` 未导出 / 集成用例 currentIdx 为 0。

- [ ] **Step 3: 实现**

`textReader.js`：

1）import 区补 `firstVisibleParagraph`：

```javascript
import { updateProgressUI, detectActiveChapterOnScroll, firstVisibleParagraph } from './progress.js';
```

2）模块级新增导出纯函数（放在 `formatHeaderTitle` 之后）：

```javascript
// Pure helper: decide which chapter/paragraph to open at. URL params win
// (TOC / bookmark deep links); otherwise the saved localStorage progress
// restores chapter + first-visible paragraph. Legacy payloads without
// paraIndex degrade to chapter-top. Exported for unit testing.
export function resolveResume({ chapterParam, paraParam, saved, chapterCount }) {
    const maxIdx = Math.max(0, chapterCount - 1);
    let startIdx = 0;
    let resumePara = null;
    if (chapterParam !== undefined && chapterParam !== null) {
        startIdx = clamp(parseInt(chapterParam, 10) || 0, 0, maxIdx);
        if (paraParam !== undefined && paraParam !== null) {
            resumePara = parseInt(paraParam, 10) || 0;
        }
    } else if (saved) {
        startIdx = clamp(saved.chapterIndex || 0, 0, maxIdx);
        const p = saved.paraIndex || 0;
        if (p > 0) resumePara = p;
    }
    return { startIdx, resumePara };
}
```

3）替换恢复决策（原 `:245-254`）：

```javascript
    const savedProgress = loadProgress(path);
    const { startIdx, resumePara } = resolveResume({
        chapterParam, paraParam, saved: savedProgress, chapterCount,
    });
    minLoadedIdx = startIdx;
    maxLoadedIdx = startIdx;
    setCurrentIdx(startIdx);
```

4）初始加载与段级恢复（原 `:529-530` 替换）：

```javascript
    await loadChapter(startIdx, true);
    if (resumePara !== null) scrollToParagraph(resumePara, startIdx);
```

5）三处保存点 payload 改为段级语义：
- 翻页 `loadChapterSection`（原 `:400`）：
```javascript
            saveProgress(path, { chapterIndex: idx, paraIndex: 0, lastReadAt: Date.now() });
```
- 滚动模式活动章切换（原 `:499`）：
```javascript
                saveProgress(path, { chapterIndex: activeIdx, paraIndex: 0, lastReadAt: Date.now() });
```
- `loadChapter`（原 `:652`）：
```javascript
            saveProgress(path, { chapterIndex: idx, paraIndex: 0, lastReadAt: Date.now() });
```

6）防抖保存 + 关页 flush：在 `handleContentScroll` 定义之前（`onContentScroll` 附近）加：

```javascript
    // 段级进度保存：滚动停止 800ms 后写入当前首个可见段落；关闭页面时立即 flush。
    let progressSaveTimer = null;
    function collectVisibleParagraphs() {
        const containerTop = els.content.getBoundingClientRect().top;
        const paragraphs = [];
        els.content.querySelectorAll('.text-reader__chapter-section').forEach(sec => {
            const chIdx = parseInt(sec.dataset.chapterIndex, 10);
            sec.querySelectorAll('.text-reader__p').forEach(p => {
                const r = p.getBoundingClientRect();
                const paraIdx = parseInt(p.dataset.paraIndex, 10);
                paragraphs.push({
                    top: r.top,
                    bottom: r.bottom,
                    chapterIndex: Number.isNaN(chIdx) ? state.currentIdx : chIdx,
                    paraIndex: Number.isNaN(paraIdx) ? 0 : paraIdx,
                });
            });
        });
        return { paragraphs, containerTop };
    }
    function persistVisibleProgress() {
        const { paragraphs, containerTop } = collectVisibleParagraphs();
        const vis = firstVisibleParagraph(paragraphs, containerTop);
        if (vis) {
            saveProgress(path, { chapterIndex: vis.chapterIndex, paraIndex: vis.paraIndex, lastReadAt: Date.now() });
        }
    }
    function scheduleProgressSave() {
        if (progressSaveTimer) clearTimeout(progressSaveTimer);
        progressSaveTimer = setTimeout(() => {
            progressSaveTimer = null;
            persistVisibleProgress();
        }, 800);
    }
    const onPageHide = () => {
        if (progressSaveTimer) {
            clearTimeout(progressSaveTimer);
            progressSaveTimer = null;
        }
        persistVisibleProgress();
    };
    window.addEventListener('pagehide', onPageHide);
    document.addEventListener('visibilitychange', () => {
        if (document.hidden) onPageHide();
    });
```

并在 `handleContentScroll` 函数体末尾（`scrubberApi.update()` 之后）追加一行：

```javascript
        scheduleProgressSave();
```

7）cleanup（原 `:510-526` 的 `container._cleanupReader` 内）追加：

```javascript
        if (progressSaveTimer) clearTimeout(progressSaveTimer);
```

并把 visibilitychange 监听提名为具名函数以便移除（第 6 步代码中改为：

```javascript
    const onVisibilityChangeSave = () => { if (document.hidden) onPageHide(); };
    window.addEventListener('pagehide', onPageHide);
    document.addEventListener('visibilitychange', onVisibilityChangeSave);
```

cleanup 中对应 `document.removeEventListener('visibilitychange', onVisibilityChangeSave); window.removeEventListener('pagehide', onPageHide);`。注意与既有 `onVisibilityChange`（autoscroll 停止）是两个独立监听，互不替代）。

- [ ] **Step 4: 运行验证通过 + 全量回归**

Run: `cd server/internal/web && node --test`
Expected: 全部 PASS（含既有 textReader/bookshelf/progress 用例）。

Run: `cd tools/xsscheck && go run . ../../server/internal/web`
Expected: 无新增违规（新代码无 innerHTML sink）。

- [ ] **Step 5: 提交**

```bash
git add server/internal/web/textReader.js server/internal/web/textReader.test.mjs
git commit -m "feat(web): save and resume paragraph-level reading progress"
```

---

### Task 7: 全量验证与收尾

**Files:**
- 无新改动（验证 + 文档收尾）。

- [ ] **Step 1: Android 全量测试**

Run: `cd android && ./gradlew testDebugUnitTest`
Expected: 全绿。

- [ ] **Step 2: Web 全量测试 + XSS 扫描**

Run: `cd server/internal/web && node --test && cd ../../tools/xsscheck && go run . ../../server/internal/web`
Expected: 全绿 / 无违规。

- [ ] **Step 3: 核对提交完整落盘**

Run: `git status --short && git log --oneline -6`
Expected: 工作区干净；6 个提交（Task1/2/4+3/5/6 + 文档）。若 Mimosa 闸门导致部分 commit 未落盘（见 Global Constraints 提交受阻预案），改动应已全部 `git add` 暂存——在交付说明中列出待补提交清单。

- [ ] **Step 4: 交付说明（手动冒烟指引，写给用户）**

- Android：打开多章书 → 翻到第 N 章滚到中间段落 → 停 1 秒 → 退出重开（浏览页、首页书架两个入口各一次）→ 应落在第 N 章该段附近。
- Web：同一流程从「媒体共享库」与「书架」两个入口验证；再改一次字号后重开，应仍落在同一段落顶部（段落锚点对字号变更鲁棒）。
- 旧进度记录自动退化为章级恢复，无需任何迁移操作。

---

## Self-Review 记录

- **Spec 覆盖**：G1（key 统一）→ Task 3；G2（Android 段级）→ Task 1/2/3/4；G3（Web 段级）→ Task 5/6；G4（向后兼容）→ Task 1 Step 1 legacy 用例 + Task 6 resolveResume legacy 分支。非目标未引入服务端改动 ✓。
- **占位符扫描**：无 TBD/TODO；所有代码步骤含完整代码块 ✓。
- **类型一致性**：`persistScrollProgress(chapterIndex, blockIndex, scrollOffsetPx)` 三处引用一致；`ReaderListLayout.scrollItemIndex/scrollChapterBlock/CHAPTER_MODE_HEADER_ITEMS` 定义与 Task 4 用法一致；web `resolveResume` 返回 `{startIdx, resumePara}` 与调用点一致 ✓。
- **已知合并约束**：Task 3 的 VM 签名变更与 Task 4 的 Screen 调用必须同一次编译通过（Task 3 Step 4 已注明）。
