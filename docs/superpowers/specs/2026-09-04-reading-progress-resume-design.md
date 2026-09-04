# 小说阅读进度恢复（章节+段落，纯本地修复）设计（2026-09-04）

## 背景与问题

用户报告：Web 与 Android 端打开小说时没有恢复到上次阅读位置，每次都从最初章节开始。

调查结论——**两端都已实现本地进度持久化，但存在三类真实缺口**：

| # | 缺口 | 位置 | 用户可见症状 |
|---|------|------|--------------|
| 1 | **Android 进度 key 不一致**：恢复用"打开时传入的 path"（`TextReaderViewModel.processBookLoaded` 中 `store.getBookProgress(path)`），保存用"服务端返回的 `b.path`"（`loadChapter` / `updateCurrentIndex` / `persistScrollProgress` 三处）。服务端 `ValidateAccessibleMediaPath` → `NormalizePath` 会做盘符大写 + `filepath.Clean` 规范化，扫描器输出的 `file.path`（保留 config.yaml 原始写法）与规范化结果不一致时，从浏览页/下载页打开永远查不到进度 | `TextReaderViewModel.kt:193` vs `:296/:436/:478` | Android 打开总是第一章 |
| 2 | **段落位置"存而不恢复"**：两端都保存了章内滚动数据但从不应用——Web 保存 `scrollOffset`（px）但 `loadChapter` 恒 `scrollTop = 0`；Android 的 `persistScrollProgress` 保存全局 `firstVisibleItemIndex` 的 `scrollOffsetPx`，但 `BookProgress` 无段落索引字段，全局 item index 依赖已加载章节数（不稳定），且 Screen 打开后恒 `scrollToItem(0,0)` | `textReader.js:644`；`TextReaderViewModel.kt:473-485` | 即使章对了也回到章顶；用户期望"打开到相应章节对应的段落" |
| 3 | **跨端/跨源不同步**：Web 存 localStorage（按源隔离）、Android 存本机 DataStore，服务端零参与 | — | 换设备打开各自从头 |

**用户决策**（2026-09-04）：
- 存储架构选**纯本地修复**——不新增服务端进度存储与端点，缺口 3 明确列为非目标；
- 恢复精度选**章节+段落**——解决缺口 1 与 2。

## 目标

- **G1（Android 章级恢复）**：打开书恢复到上次章节；修复 key 不一致，从任何入口（浏览页、首页书架、下载页）打开都能命中。
- **G2（Android 段级恢复）**：分章模式恢复到上次章节内首个可见段落（含段内 px 偏移）；滚动模式恢复到上次位置对应的 (章, 段, 偏移)。
- **G3（Web 段级恢复）**：打开书恢复到上次章节内首个可见段落顶部（复用现有书签深链的 `scrollToParagraph` 机制）。
- **G4（向后兼容）**：两端旧进度记录（无段落字段）反序列化后退化为现有章级恢复，无行为回归。

## 非目标

- 服务端进度同步 / 跨设备同步（用户已明确排除；未来如需可在此基础上加服务端表 + lastReadAt 合并）。
- 书签、阅读设置、书架展示逻辑的改动（书架卡片继续只展示章号）。
- 阅读模式切换（分章 ↔ 滚动）时的位置保留——现状是重置，维持不变。

## 关键现状事实（实现时依赖）

- 书籍身份 = 服务端文件 path；`/api/v1/books/info` 返回的 `Book.Path` 是 `NormalizePath` 规范化后的绝对路径。
- Android 分章模式 LazyColumn 结构（`TextReaderScreen.kt:1034-1093`）：item 0 = 章标题，item 1..N = blocks，末尾 1 个 ❖。全局 item index = `1 + blockIndex`。
- Android 滚动模式 LazyColumn 结构（`TextReaderScreen.kt:1111+`）：每章 `[标题, blocks..., 分隔符]` 共 `blocks.size + 2` 个 item；列表末尾可能有加载指示器（位于所有章之后，不影响映射）。
- 两种模式共享同一 `listState`；现有节流保存点在 `TextReaderScreen.kt:325-331`（snapshotFlow + debounce 1000ms → `persistScrollProgress(itemIdx, offset)`）。
- Web 已有 `scrollToParagraph(paraIdx, chIdx)`（`textReader.js:763-784`，书签深链用，1.5s 重试等 reflow），支持跨章定位 `section[data-chapter-index] p[data-para-index]`。
- Web 进度 key = 打开时传入 path，save/load 一致，无 key 问题。
- 滚动模式恢复的锚章不会被 `trimScrollWindow` 裁掉（保留锚章上方 3 章）。

---

## Android 端设计

### 1. 数据模型：`BookProgress` 增加段落索引

`data/RecentActivityStore.kt`：

```kotlin
data class BookProgress(
    val path: String,        // 服务端 Book.path（规范化绝对路径）
    val chapterIndex: Int,
    val blockIndex: Int = 0, // 新增：章内首个可见 block 索引
    val scrollOffsetPx: Int, // 语义变更：blockIndex item 内的 px 偏移（原为无索引的全局偏移，不可恢复）
    val lastReadAt: Long,
)
```

旧 JSON 无 `blockIndex` 字段 → Gson（Unsafe 分配）缺省 0，天然满足 G4；补一条旧格式反序列化测试锁定该行为。

### 2. key 统一（G1）

`processBookLoaded` 中 `store.getBookProgress(path)` → `store.getBookProgress(b.path)`。所有保存点已统一用 `b.path`，存储中的 key 本来就是 `b.path` 值，**无需数据迁移**。本地模式（`LocalBookRepository` 构造的 `Book.path = originalPath`）与在线模式遵循同一规则：以 Book 对象报告的 path 为准。

### 3. 列表索引映射：纯函数 util（G2 核心）

新增 `data/ReaderListLayout.kt`（纯 Kotlin 对象，无 Android 依赖，可单测）：

```kotlin
object ReaderListLayout {
    // 滚动模式：每章 items = blocks.size + 2（标题 + blocks + 分隔符）
    fun scrollItemIndex(chapters: List<ScrollModeChapter>, chapterIndex: Int, blockIndex: Int): Int
    fun scrollChapterBlock(chapters: List<ScrollModeChapter>, itemIndex: Int): Pair<Int, Int> // (chapter, block)
    // 分章模式：item 0 = 标题，block b = item 1+b
    const val CHAPTER_MODE_HEADER_ITEMS = 1
}
```

边界处理：目标章不在已加载列表 → 返回 -1（调用方放弃恢复）；blockIndex 超出该章 blocks → coerce 到最后一块。

### 4. 保存路径改造

`TextReaderScreen.kt` 现有 debounce collect 改为先做模式相关映射再上抛：

```kotlin
.collect { (itemIdx, offset) ->
    if (isScrollMode) {
        val (ch, blk) = ReaderListLayout.scrollChapterBlock(scrollChapters, itemIdx)
        viewModel.persistScrollProgress(ch, blk, offset)
    } else {
        viewModel.persistScrollProgress(idx, maxOf(0, itemIdx - CHAPTER_MODE_HEADER_ITEMS), offset)
    }
}
```

`TextReaderViewModel.persistScrollProgress` 签名改为 `(chapterIndex: Int, blockIndex: Int, scrollOffsetPx: Int)`，保存 `BookProgress(b.path, chapterIndex, blockIndex, scrollOffsetPx, now)`。

翻章 / 章节切换的既有保存点（`loadChapter`、`updateCurrentIndex`）改为写 `blockIndex = 0, scrollOffsetPx = 0`（语义：位于新章顶部，与现状一致）。

### 5. 恢复路径（一次性消费）

- VM 新增 `_pendingResume = MutableStateFlow<BookProgress?>`；`processBookLoaded` 中查到 saved 后写入，并照旧 `loadChapter(saved.chapterIndex)`。
- Screen 新增 `LaunchedEffect(pendingResume, ...)`，在目标章内容就绪后执行一次并调用 `viewModel.consumePendingResume()`：
  - **分章模式**：`listState.scrollToItem(CHAPTER_MODE_HEADER_ITEMS + saved.blockIndex, saved.scrollOffsetPx)`。
  - **滚动模式**：在现有 `LaunchedEffect(isScrollMode)` 的向前预载 + 位置补偿完成**之后**执行 `scrollToItem(ReaderListLayout.scrollItemIndex(scrollChapters, saved.chapterIndex, saved.blockIndex), saved.scrollOffsetPx)`——映射基于补偿后的 `scrollChapters` 计算，覆盖补偿位置。
  - 旧格式（`blockIndex == 0 && scrollOffsetPx == 0`）等价于现状章级恢复，直接跳过 scrollToItem 也无回归。
- `updateSettings` 切换阅读模式触发的 `loadChapter(resetScroll = true)` 不消费 pendingResume（恢复只在开书时发生一次）。

### 6. Android 测试

- `ReaderListLayoutTest.kt`（新增）：滚动模式映射正反双向、单章/多章、目标章缺失返回 -1、blockIndex 越界 coerce、分章模式 header 偏移。
- `RecentActivityStoreBookProgressTest.kt`（扩展）：旧 JSON（无 blockIndex）反序列化 → blockIndex=0。
- `viewmodel/` 下如有 TextReaderViewModel 现有测试则补：`processBookLoaded` 用 `b.path` 查进度、pendingResume 设置与消费。

---

## Web 端设计

### 1. 存储格式

`book_progress:<path>` payload 改为 `{ chapterIndex, paraIndex, lastReadAt }`：
- `paraIndex` = 首个可见段落（视口顶边之下第一个 `.text-reader__p`）在其章节内的 `data-para-index`；首可见为章标题时为 0。
- 移除 `scrollOffset` 字段（存而不用；段级精度由 paraIndex 承担，且对字体/字号变更鲁棒）。
- 旧 payload 无 `paraIndex` → `|| 0` 退化章级恢复（G4）。
- `bookshelf.js` 只读 chapterIndex / lastReadAt / path，payload 变更对其无影响。

### 2. 首可见段落计算：纯函数进 `progress.js`

与现有 `detectActiveChapterOnScroll` 同风格（预收集 rect 的纯函数，便于 jsdom 测试）：

```javascript
// paragraphs: [{ top, bottom, chapterIndex, paraIndex }]; containerTop: 视口顶
export function firstVisibleParagraph(paragraphs, containerTop) { ... }
```

编排层在 scroll handler 中收集当前已加载各 section 的段落 rect 后调用。

### 3. 保存触发

- 既有保存点（翻页 `loadChapterSection`、`loadChapter`、滚动模式活动章切换）改为写 `paraIndex: 0`。
- `handleContentScroll`（rAF 节流）内新增 **800ms debounce** 的位置保存：计算活动章 + 首可见段落 → `saveProgress(path, { chapterIndex, paraIndex, lastReadAt: Date.now() })`。
- 新增 `pagehide` + `visibilitychange(hidden)` 时立即 flush 一次 pending 保存，防快速关标签丢末次位置。
- 清理：`_cleanupReader` 中清 debounce timer 与两个 document 监听。

### 4. 恢复路径

`renderTextReader` 中 `await loadChapter(startIdx, true)` 后：

```javascript
const saved = (chapterParam != null || paraParam != null) ? null : loadProgress(path);
if (saved && (saved.paraIndex || 0) > 0) {
    scrollToParagraph(saved.paraIndex, saved.chapterIndex);  // 现有函数，跨章重试 1.5s
}
```

- URL 显式带 `chapter`/`para`（目录/书签深链）时优先 URL，不套用 saved——沿用现状语义。
- 滚动模式：目标章即 `loadChapter` 锚章，section 在 DOM 中即时可用；上方预载与裁剪不影响锚章。

### 5. Web 测试

- `progress.test.mjs`（扩展）：`firstVisibleParagraph` 首段可见 / 标题占位 / 全部在视口上方等边界。
- `textReader.test.mjs`（扩展）：恢复决策——URL para 优先 > saved.paraIndex > 章顶；旧 payload 无 paraIndex。

---

## 验证

```bash
cd server/internal/web && node --test
cd tools/xsscheck && go run . ../../server/internal/web
cd android && ./gradlew testDebugUnitTest
```

手工冒烟（交付说明中提示用户）：
1. Android：打开书 → 翻到第 N 章滚到中间段落 → 等 1s → 退出重开 → 应落在该段落附近；从浏览页与首页书架两个入口分别验证（覆盖 key 统一）。
2. Web：同上，从媒体库与书架两个入口验证；改字号后重开，应仍落在同一段落顶部。

## 风险与对策

| 风险 | 对策 |
|------|------|
| Android 恢复时 contentPadding / 标题 item 实际高度与保存时不一致，段内 px 偏移有细偏差 | 段级精度本就允许 ±；偏移只作用于 item 内部，不会跨段漂移 |
| 书籍文件被编辑后 block/para 索引漂移，恢复到错误段落 | 越界 coerce 到章末；属可接受的退化（本地进度无内容指纹，加指纹超出本次范围） |
| Gson 反序列化旧数据缺字段 | int 缺省 0 = 章级恢复；测试锁定 |
| Web 滚动模式恢复与 `loadPrevScrollChapter` 的滚动补偿竞争 | 恢复通过 `scrollToParagraph` 重试机制在锚章 section 上定位，锚章不会被裁剪；补偿只影响上方缓冲 |
