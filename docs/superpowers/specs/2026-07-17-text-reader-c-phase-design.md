# 小说阅读器 C 阶段（阅读体验增强）

- **日期**：2026-07-17
- **作者**：brainstorming session
- **状态**：spec（待用户审阅 → 转入 writing-plans）
- **依赖**：基于 B 阶段实现（spec `docs/superpowers/specs/2026-07-17-text-reader-design.md`，已合并到 master `6199483`）

## 背景与目标

B 阶段已经实现 txt/epub 阅读核心：章节切分、翻页、章节级进度持久化、TOC 抽屉、HomeScreen 书架、Web 阅读器。但排版/主题/交互全是硬编码——`MaterialTheme.typography.bodyLarge`、固定 `lineHeight = 28.sp`（≈1.75x 于 16sp 字体）、跟随系统暗黑、段落无可交互入口（无 `combinedClickable`）。

C 阶段补齐 5 项阅读体验增强，跨 Android + Web 两端：

1. **字体大小**（4 档预设：小/中/大/超大）
2. **行距**（3 档预设：紧凑/标准/宽松）
3. **主题**（3 个预设：日间/夜间/护眼）
4. **自动滚动**（slider 1~10 档 + 播放/暂停）
5. **章节书签**（per-book，长按段落 / hover 段落添加，TOC Tab 查看与跳转）

## 范围

### 首期包含

- 全局阅读偏好（字体/行距/主题/自动滚动速度）—— 一套适用于所有书
- per-book 书签（每本书独立的位置标记列表）
- BottomSheet/`<dialog>` 设置入口（即时生效，无"确定"按钮）
- 自动滚动 + 屏幕常亮 + 标签页失活暂停（Web）
- TOC 抽屉 Tab 化（"目录" / "书签"）
- 主题仅覆盖阅读区域（不替换 App 级 MaterialTheme）

### 首期不包含（YAGNI，留作后续）

- 自定义颜色（背景/文字色用户自调）
- 用户为书签添加备注
- 跨设备偏好/书签同步
- 重力感应倾斜滚动
- 自动滚动状态持久化（重开书默认关闭）
- Web 测试框架（继续不引入 vitest/jest）
- App 级主题切换

## 架构原则

完全对齐 B 阶段"客户端各自存"模式：

- **Android**：DataStore（与 `RecentActivityStore` 同模式）+ Compose `StateFlow`
- **Web**：localStorage（与 `book_progress` 同模式）+ CSS 变量 + 自定义事件
- **服务端零改动**——C 阶段是纯客户端增强

## 数据模型与持久化

### Android 数据层

**`data/ReaderSettings.kt`**（新）— 全局偏好：

```kotlin
data class ReaderSettings(
    val fontSize: ReaderFontSize = ReaderFontSize.MEDIUM,
    val lineHeight: ReaderLineHeight = ReaderLineHeight.STANDARD,
    val theme: ReaderTheme = ReaderTheme.DAY,
    val autoScrollSpeed: Int = 5,  // 1..10 档
)

enum class ReaderFontSize(val sp: Int) { SMALL(14), MEDIUM(16), LARGE(18), XLARGE(20) }
enum class ReaderLineHeight(val multiplier: Float) { COMPACT(1.4f), STANDARD(1.8f), LOOSE(2.2f) }
enum class ReaderTheme(val bg: Color, val fg: Color, val label: String) {
    DAY(Color(0xFFFFFFFF), Color(0xFF212121), "日间"),
    NIGHT(Color(0xFF121212), Color(0xFFE0E0E0), "夜间"),
    EYE_CARE(Color(0xFFF4ECD8), Color(0xFF5B4636), "护眼"),
}
```

**`data/Bookmark.kt`**（新）— per-book 书签：

```kotlin
data class Bookmark(
    val bookPath: String,
    val chapterIndex: Int,
    val paragraphIndex: Int,    // 段落序号（"\n\n" split 后的 index）
    val preview: String,         // 段落前 30 字符预览
    val createdAt: Long,
)
```

> 选 `paragraphIndex` 而不是 `charOffset`：B 阶段 LazyColumn 的渲染单元是段落，书签定位精确到段落，跳转时用 `LazyListState.scrollToItem(paragraphIndex)`。比 charOffset 更稳定（章节正文变化时段落序号通常不变，charOffset 会漂移）。

**`data/RecentActivityStore.kt`** 扩展 — 2 个新 DataStore key：

- `reader_settings`：单个 JSON 对象（Gson 序列化 `ReaderSettings`）
- `book_bookmarks`：`Map<String, List<Bookmark>>`（key = bookPath）

新增 API：
- `getReaderSettingsFlow(): Flow<ReaderSettings>` / `saveReaderSettings(settings)`
- `getBookmarksFlow(path): Flow<List<Bookmark>>` / `getBookmarks(path)` / `addBookmark(bm): Boolean`（返回 false 表示重复）/ `deleteBookmark(bm)` / `clearBookmarks(path)`

### Web 数据层

**`server/internal/web/readerPrefs.js`**（新模块）— 封装 localStorage + 自定义事件：

- localStorage key `reader_settings`：JSON `ReaderSettings`
- localStorage key `book_bookmarks:<path>`：JSON `Bookmark[]`
- 通过 `window.dispatchEvent(new CustomEvent('reader-prefs-changed'))` 通知订阅者
- API：`getSettings() / saveSettings(partial) / getBookmarks(path) / addBookmark(bm) / removeBookmark(bm)`

### 偏好范围汇总

| 数据 | 范围 | 存储 |
|------|------|------|
| 字体/行距/主题/自动滚动速度 | 全局（所有书共用） | `reader_settings` 单条 |
| 书签 | per-book | `book_bookmarks` / `book_bookmarks:<path>` |

## Android 客户端

### TopAppBar 改造（`ui/screen/TextReaderScreen.kt`）

actions slot 新增 2 个图标按钮：
- **"Aa" 设置**（`Icons.Filled.FormatSize`）：打开 `ReaderSettingsSheet`（ModalBottomSheet）
- **"自动滚动"**（`Icons.Filled.PlayArrow` / `Pause`）：切换播放/暂停；启动时变 Pause，BottomAppBar 显示当前速度档位（"速:5"）。长按打开同一个 Sheet 并滚到自动滚动分区

> `material-icons-extended` 已被移除（`build.gradle.kts` line 336 注释 `// Round 21 D2: material-icons-extended removed`）。仅使用 `material-icons-core` 中的图标：`Icons.Filled.FormatSize`、`Icons.Filled.PlayArrow`、`Icons.Filled.Pause`、`Icons.Filled.Menu`、`Icons.AutoMirrored.Filled.ArrowBack` 均在 core 中可用。

### ReaderSettingsSheet（新 `ui/component/reader/ReaderSettingsSheet.kt`）

ModalBottomSheet，4 个分区：

```
阅读设置
├─ 字体大小：[小] [中●] [大] [超大]      ← 4 chip 单选
├─ 行距：    [紧凑] [标准●] [宽松]       ← 3 chip 单选
├─ 主题：    [日间●] [夜间] [护眼]       ← 3 chip 单选（带颜色圆点）
└─ 自动滚动速度：[====●==========] 5    ← Slider 1..10
```

每个 chip/slider 即时调用 `viewModel.updateSettings(...)` → 持久化 + recompose。无"确定"按钮。

### ReaderThemeWrapper（新 `ui/component/reader/ReaderThemeWrapper.kt`）

> **命名注意**：Composable 函数不能与 `ReaderTheme` enum 同名（Kotlin 编译器会产生歧义），因此命名为 `ReaderThemeWrapper`。

```kotlin
@Composable
fun ReaderThemeWrapper(theme: ReaderTheme, content: @Composable () -> Unit) {
    Box(Modifier.background(theme.bg)) {
        CompositionLocalProvider(LocalContentColor provides theme.fg) {
            content()
        }
    }
}
```

不替换 `MaterialTheme`——只覆盖阅读区域背景与文字色。TopAppBar/BottomAppBar/Sheet 仍跟随系统 Material 主题。

### 字体/行距渲染（LazyColumn 内 Text）

```kotlin
Text(
    para,
    style = LocalTextStyle.current.copy(
        fontSize = settings.fontSize.sp.sp,
        lineHeight = (settings.fontSize.sp * settings.lineHeight.multiplier).sp,
    ),
)
```

### 自动滚动（UI 层与 ViewModel 协同）

**VM 层状态**：
```kotlin
val isAutoScrolling: StateFlow<Boolean>
```

**协程与滚动控制（UI 层）**：
为了符合 Compose 的最佳实践并防止 UI 状态泄漏，`LazyListState` 保持在 Composable 内（使用 `rememberLazyListState()`）。自动滚动的循环逻辑通过 `LaunchedEffect` 在 UI 层执行，而非在 ViewModel 内：

```kotlin
val listState = rememberLazyListState()
val isAutoScrolling by viewModel.isAutoScrolling.collectAsState()
val settings by viewModel.readerSettings.collectAsState()

LaunchedEffect(isAutoScrolling, settings.autoScrollSpeed) {
    if (isAutoScrolling) {
        val pixelsPerFrame = settings.autoScrollSpeed * 0.5f // 60fps 下相当于 30~300 px/s
        while (isActive) {
            listState.scrollBy(pixelsPerFrame)
            delay(16) // 约 60fps
        }
    }
}
```

- **常亮控制**：在 UI 层通过 `DisposableEffect` 监听 `isAutoScrolling`。当滚动开启时，向 Activity Window 添加 `FLAG_KEEP_SCREEN_ON`；滚动停止或 Composable 销毁时清除。
- **停止触发器**：用户手动触碰屏幕滑动（可通过 `listState.isScrollInProgress` 监听）、翻页、加载新章节时，VM 更新 `isAutoScrolling` 为 `false`。
- **进度保存节流**：滚动期间，UI 层通过 `snapshotFlow { listState.firstVisibleItemIndex }` 监听位置变化，通过 `debounce(1000)` 触发 VM 写入临时进度，避免高频 I/O。

### 书签交互

**添加**：LazyColumn 段落 `Text` 包 `combinedClickable`：
- 单击：保留现有行为
- 长按：弹 `DropdownMenu`：「添加书签」「复制段落」
- 选「添加书签」→ VM 调 `addBookmarkFromParagraph(chapterIdx, paraIdx, preview = para.take(30))`
- 成功后 Toast「已添加书签」；重复返回 false 时 Toast「已存在书签」+ 高亮已存在项 1 秒

**TOC 抽屉 Tab 化**（`TextReaderScreen.kt`）：

ModalDrawerSheet 内：
```
[目录] [书签 (N)]   ← PrimaryTabRow
─── Tab "目录" ───
  NavigationDrawerItem...
─── Tab "书签" ───  （per本书签）
  - 第 3 章 · "主角觉醒…"    ← 章节名 + preview
    2 小时前 [🗑]
  - 第 12 章 · "…"
    昨天 [🗑]
```

- Tab 用 Material 3 `TabRow` + 简单 `when` 切内容（不引入 HorizontalPager）
- 书签 item `combinedClickable`：单击跳转，末尾删除图标
- 空状态：「暂无书签，长按段落添加」

**跳转**：VM 方法 `jumpToBookmark(bookmark)`：
1. `loadChapter(bookmark.chapterIndex)`（suspend）
2. 章节正文加载完后 `listState.scrollToItem(bookmark.paragraphIndex)`
3. 关闭抽屉

竞态处理：loadChapter 是 suspend，scrollToItem 必须在 LazyColumn 重组完之后调用。用 `snapshotFlow { chapterText }.filter { it.isNotEmpty() }.first()` 等待，或直接在 loadChapter 的 suspend 链尾做 scroll。

## Web 客户端

### Header / Footer 改造（`textReader.js`）

> **现状**：Header 仅有 `←返回` + `书名`；Footer 有 `上一章` / 进度 / `下一章` / `目录` 按钮。C 阶段在 Header 右侧新增 Aa 和自动滚动按钮。

```
Header: [←返回] [书名]              [Aa] [▶/⏸]
Footer: [上一章] [第 N/M 章] [下一章] [☰目录]  ← 保持现有布局
```

- **Aa**（Header 新增）：打开 `<dialog id="reader-settings-dialog">`（HTML5 `<dialog>` 原生 Modal）
- **▶/⏸**（Header 新增）：自动滚动切换（图标互换）
- **☰目录**（Footer 保持现有位置）：保留现有 TOC 抽屉

### 设置面板 DOM（`textReader.js` 动态创建）

> **注意**：`#view-reader` 是空的 `<section>`，所有 DOM 由 `textReader.js` 的 `renderTextReader()` 动态生成。设置 `<dialog>` 也应在 `renderTextReader()` 中创建，而非写在 `index.html` 中，以保持模块封装。

```html
<dialog id="reader-settings-dialog">
  <form method="dialog">
    <h3>阅读设置</h3>

    <fieldset>
      <legend>字体大小</legend>
      <label><input type="radio" name="fontSize" value="SMALL"> 小</label>
      <label><input type="radio" name="fontSize" value="MEDIUM" checked> 中</label>
      <label><input type="radio" name="fontSize" value="LARGE"> 大</label>
      <label><input type="radio" name="fontSize" value="XLARGE"> 超大</label>
    </fieldset>

    <fieldset>
      <legend>行距</legend>
      <label><input type="radio" name="lineHeight" value="COMPACT"> 紧凑</label>
      <label><input type="radio" name="lineHeight" value="STANDARD" checked> 标准</label>
      <label><input type="radio" name="lineHeight" value="LOOSE"> 宽松</label>
    </fieldset>

    <fieldset>
      <legend>主题</legend>
      <label><input type="radio" name="theme" value="DAY" checked> 日间</label>
      <label><input type="radio" name="theme" value="NIGHT"> 夜间</label>
      <label><input type="radio" name="theme" value="EYE_CARE"> 护眼</label>
    </fieldset>

    <fieldset>
      <legend>自动滚动速度</legend>
      <input type="range" name="autoScrollSpeed" min="1" max="10" value="5">
      <span data-bind="speedLabel">5</span>
    </fieldset>
  </form>
</dialog>
```

每个 input `change` 事件 → `readerPrefs.saveSettings({...})` → dispatch `reader-prefs-changed` → `textReader.js` 监听并应用 CSS 变量。

### 主题应用（CSS 变量）

`textReader.js` 监听 prefs 变化：
```js
const THEME_PRESETS = {
  DAY: { bg: '#FFFFFF', fg: '#212121' },
  NIGHT: { bg: '#121212', fg: '#E0E0E0' },
  EYE_CARE: { bg: '#F4ECD8', fg: '#5B4636' },
};
const FONT_SIZES = { SMALL: 14, MEDIUM: 16, LARGE: 18, XLARGE: 20 };
const LINE_HEIGHTS = { COMPACT: '1.4', STANDARD: '1.8', LOOSE: '2.2' };

function applySettings(settings) {
  const root = document.documentElement;
  const theme = THEME_PRESETS[settings.theme];
  root.style.setProperty('--reader-bg', theme.bg);
  root.style.setProperty('--reader-fg', theme.fg);
  root.style.setProperty('--reader-font-size', FONT_SIZES[settings.fontSize] + 'px');
  root.style.setProperty('--reader-line-height', LINE_HEIGHTS[settings.lineHeight]);
}
```

`style.css`（修改现有 `.text-reader__content` 规则，并新增段落样式）：
```css
.text-reader__content {
  flex-grow: 1;
  overflow-y: auto;
  padding: 24px 28px;
  background-color: var(--reader-bg, var(--bg-card));
  color: var(--reader-fg, var(--text-white));
  font-size: var(--reader-font-size, 16px);
  line-height: var(--reader-line-height, 1.85);
  word-break: break-word;
}

.text-reader__content p {
  margin-bottom: 1.2em;
  text-indent: 2em;
}

.text-reader__content p:last-child {
  margin-bottom: 0;
}
```

> **选择器说明**：Web 端 CSS 使用 BEM 命名（`.text-reader`、`.text-reader__content`、`.text-reader__header` 等），不是 `#view-reader .chapter-content`。CSS 变量仅作用于 `.text-reader` 容器——App 其他部分不受影响。由于渲染改为了 `<p>` 标签，不需要在 `.text-reader__content` 上使用 `white-space: pre-wrap`，但需要在 `<p>` 元素上添加行高和间距样式以提升排版美感。

### 自动滚动（`textReader.js`）

- **速度截断问题**：在 Web 端，直接操作 `el.scrollTop` 会在多数浏览器中被强制截断为整数。若 `speed` 较低导致 `pixelsPerFrame` 为小数（如 `0.5px`），直接累加会导致画面停滞不前。
- **解决方案**：在 JS 中维护一个浮点数类型的滚动值 `currentScrollTop`：
  ```javascript
  let currentScrollTop = el.scrollTop;
  function scrollLoop() {
      if (!isScrolling) return;
      currentScrollTop += pixelsPerFrame; // pixelsPerFrame = speed * 0.5
      el.scrollTop = currentScrollTop;
      
      // 若因滚动触底等原因导致实际 scrollTop 与内存值差距过大，进行同步
      if (Math.abs(el.scrollTop - currentScrollTop) > 1) {
          currentScrollTop = el.scrollTop;
      }
      requestAnimationFrame(scrollLoop);
  }
  ```
- ⏸ `cancelAnimationFrame`
- `document.visibilityState === 'hidden'` 自动暂停
- 进度持久化：滚动事件 debounce 1 秒更新 `localStorage['book_progress:'+path]` 的 scrollOffset

### 书签（`textReader.js`）

> **渲染模型变更**：当前 Web 端使用 `textContent` + `white-space: pre-wrap` 一次性渲染整个章节（出于 XSS 安全考虑）。为支持书签的段落级定位和 hover 交互，C 阶段需改为按段落创建 `<p>` 元素（类似 Android 的 `split("\n\n")`）。改用 `textContent` 逐段设置而非 `innerHTML`，仍保持 XSS 安全。

```js
// loadChapter 中替换 els.content.textContent = chapter.content
const paras = (chapter.content || '').split('\n\n').filter(p => p.trim());
els.content.innerHTML = '';
paras.forEach((text, idx) => {
  const p = document.createElement('p');
  p.textContent = text;  // textContent — XSS safe
  p.dataset.paraIndex = idx;
  els.content.appendChild(p);
});
```

**添加**：段落 `<p>` 右侧浮动 hover 显示「+ 书签」按钮（CSS `:hover` + 绝对定位），点击调用 `readerPrefs.addBookmark({...})`

不用右键菜单（Web 不直观，移动端不可用）。

**TOC 抽屉 Tab 化**：

> 现有抽屉是 `position: fixed; right: 0` 的侧滑面板（class toggle `text-reader__drawer--hidden` → `transform: translateX(100%)`），在此基础上加 Tab。

```
[目录] [书签 (N)]
```

- Tab 切换：纯 JS toggle `display: none`
- 书签 item：「第 N 章 · preview」+ 时间 + 删除按钮
- 点击书签 → 关闭抽屉 + `p[data-para-index].scrollIntoView({behavior: 'smooth'})`

## 错误处理与边界

### 设置持久化失败

- Android：DataStore 写入失败概率极低，失败时 VM 不更新本地 state（用户视觉无变化），下次启动读到旧值
- Web：localStorage 配额超限 `QuotaExceededError` → `readerPrefs.saveSettings` try/catch + `console.warn`，不阻塞用户操作

### 自动滚动状态恢复

- **进入阅读器时默认关闭**——自动滚动是"此刻正在读"的瞬时行为，重开书应有意识地启动
- **屏幕常亮 flag**：必须保证 `KEEP_SCREEN_ON` 在停止、Activity onPause/onStop、配置变更（旋屏）时清除；`onDestroy` 兜底清除一次
- **Web 标签页失活**：`visibilitychange` 暂停，返回标签页不自动恢复（用户重新点 ▶）

### 书签边界

- **同段落重复添加**：检测 `(bookPath, chapterIndex, paragraphIndex)`，命中 → Toast「已存在书签」+ 高亮已存在项 1 秒；不做 upsert（保留 createdAt）
- **章节失效**：`chapterIndex >= book.chapters.size` → Toast「书签所在章节已失效」+ 自动删除该书签
- **段落索引越界**：`scrollToItem` 自身 clamp 到末尾，安全；不删书签（段落序号小幅漂移时仍能定位大致位置）
- **删除书签**：UI 即时反馈，DataStore 异步写失败时回滚 UI（防御性处理）

### 主题切换瞬间

- 切换不会闪屏（Compose 重组同步、CSS 变量即时生效）
- 系统控件（进度条、对话框）不受 ReaderTheme 影响——有意为之，护眼是为了阅读舒适不是把整个 App 变米黄

### 设置面板与自动滚动并发

- 自动滚动中打开设置面板 → 滚动继续（边滚边调速度是核心交互）
- 调字体/行距/主题 → 自动滚动继续，1 帧视觉跳变可接受

### 设置默认值兜底

- 首次启动无 `reader_settings` key → `ReaderSettings()` 默认值
- localStorage 被手动清空 → 同样兜底
- JSON 解析失败 → 兜底默认值 + `console.warn`/`Log.w`，不抛异常

### 性能

- 字体/行距/主题变化触发 LazyColumn 全量重组——段落通常 < 200，重组 < 16ms，无性能问题
- 自动滚动 60fps：`scrollBy` + `delay(16)` 协程轻量，CPU < 1%
- Web `requestAnimationFrame` 是标准做法，浏览器自动优化

### 不改动的边界

- 服务端零变更——C 阶段纯客户端
- B 阶段 `book_progress` 系统不变——C 阶段书签是平行系统
  - **Android**：`BookProgress(path, chapterIndex, scrollOffsetPx, lastReadAt)` 数据类定义在 `RecentActivityStore.kt`，以 `Map<String, BookProgress>` 序列化存储。注意 `scrollOffsetPx` 字段存在但当前总是保存为 `0`（滚动位置恢复未实现），C 阶段不修改此行为
  - **Web**：`localStorage['book_progress:<path>']` → `{ chapterIndex, scrollOffset: 0, lastReadAt }`
- 服务端 bookparser、API、mediaType 不变

## 测试策略

### Android 单元测试

**`RecentActivityStoreReaderSettingsTest`**（新）：
- `getReaderSettingsFlow` 默认值（无 key 时）
- `saveReaderSettings` → 流更新往返一致
- 设置值损坏 → 兜底默认值，不抛
- 并发写：连续 saveSettings 5 次只保留最后一次

**`RecentActivityStoreBookmarksTest`**（新）：
- `addBookmark` → `getBookmarks(path)` 包含新条目
- 同段落重复 add → 返回 false
- `deleteBookmark` 移除对应条目
- `clearBookmarks(path)` 仅清当前书
- `getBookmarksFlow` 在 add/delete 时自动 emit

**`TextReaderViewModelTest`** 扩展：
- `updateSettings(...)` → settings state 更新 + saveReaderSettings 被调用
- `toggleAutoScroll()` on/off 状态切换
- `addBookmarkFromParagraph(...)` → addBookmark 调用
- `jumpToBookmark(bm)` 跨章节场景：loadChapter 完成后调用 `listState.scrollToItem`
- 翻页（next/prev/loadChapter）期间自动滚动停止

### Android UI 测试（Compose）

新增：
- `ReaderSettingsSheetTest`：4 分区渲染；点击 chip 触发 onSettingsChange；初始选中状态
- `TextReaderScreenThemeTest`：3 主题切换，背景/文字色正确应用

不写自动滚动 + UI 的端到端测试——动画/协程在 Robolectric 下不稳定，靠手动验收。

### Web 测试

继续不引入测试框架。靠服务端测试（C 阶段服务端零改动，应无失败）+ 手动验收覆盖。

### 验收清单

**Android**：
- [ ] 打开 .txt → TopAppBar 看到 "Aa" 和 ▶ 按钮
- [ ] 点 Aa → BottomSheet 4 分区显示，初始值与默认一致
- [ ] 切换 4 档字体，正文立刻变化
- [ ] 切换 3 档行距，行距立刻变化
- [ ] 切换夜间主题，阅读区域变黑底白字（TopAppBar/BottomBar 不变）
- [ ] 切护眼主题，背景米黄
- [ ] 关闭 Sheet → 重启 App → 设置全部保留
- [ ] 点 ▶ → 自动滚动开始，屏幕保持常亮
- [ ] 滑动速度 slider → 滚动速度立刻变化
- [ ] 点 ⏸ → 滚动停止，屏幕可熄灭
- [ ] 长按段落 → 弹菜单「添加书签」
- [ ] 添加书签后打开 TOC → "书签" Tab 显示条目
- [ ] 点击书签 → 跳转到对应段落
- [ ] 书签条目点击删除 → 立即移除
- [ ] 同段落重复添加 → Toast 提示

**Web**：
- [ ] 同样 14 项流程在浏览器跑通
- [ ] 切换标签页 → 自动滚动暂停

**跨端一致性**：
- [ ] Android 改字体 → Web 不受影响（各自全局）
- [ ] Android 加书签 → Web 看不到（per-book per-device）

CI 闸门：
- `cd server && go test ./...` PASS（服务端零改动）
- `cd android && ./gradlew testDebugUnitTest assembleDebug` PASS

## 后续扩展路径

- 自定义颜色（背景/文字色用户自调）
- 书签备注
- 跨设备偏好/书签同步（引入服务端表 + sync endpoint）
- 重力感应倾斜滚动
- 自动滚动状态持久化

## 风险

- **LazyListState 上提到 VM 层**：B 阶段 listState 在 Composable 内使用默认 `rememberLazyListState()`（`TextReaderScreen.kt` 的 LazyColumn 无显式 state 参数），C 阶段自动滚动需要 VM 调 `scrollBy`，必须上提。需要验证上提后段落渲染、配置变更（旋屏）行为正常。方案：VM 持有 `val listState = LazyListState()`，Screen 直接使用 `viewModel.listState`
- **TOC 抽屉 Tab 化重构**：现有抽屉是 `ModalNavigationDrawer` + `ModalDrawerSheet` + 内嵌 `LazyColumn`（`NavigationDrawerItem`），结构相对扁平，加 `TabRow` 后需要注意 LazyColumn 高度约束（Tab 内容区需 `Modifier.weight(1f)` 填充）
- **`<dialog>` 元素兼容性**：现代浏览器（Chrome 37+、Firefox 98+、Safari 15.4+）全部支持，项目 Web 端只在桌面浏览器用，无兼容性问题
- **自动滚动期间写进度节流**：高频 `saveBookProgress` 会触发 DataStore 频繁磁盘写——必须严格 debounce（1 秒上限）
- **ReaderTheme 与 Composable 命名冲突**：`ReaderTheme` enum 与同名 Composable 函数会导致 Kotlin 编译歧义，已改用 `ReaderThemeWrapper` 解决
- **Web 渲染模型变更**：当前 Web 端用 `els.content.textContent = chapter.content` + `white-space: pre-wrap` 整体渲染章节（XSS 安全），C 阶段书签需要段落级 DOM 节点才能 hover/定位。改为逐段创建 `<p>` 元素（仍用 `textContent` 保证 XSS 安全），但需验证 `text-indent: 2em` 段落样式在有无 `<p>` 两种模式下的视觉一致性。同时 CSS 中 `white-space: pre-wrap` 需调整为 `normal`（段落已按 `\n\n` 分割，不再需要 pre-wrap 保留换行）
- **Web 端段落定位精度**：段落 index 基于 `\n\n` split——若书源格式不规范（单换行 vs 双换行），Android 与 Web 的段落序号可能不一致。但由于书签 per-device 不跨端同步，此差异可接受
- **`material-icons-extended` 已移除**：所有 C 阶段新增图标必须确认在 `material-icons-core` 中可用。`FormatSize`、`PlayArrow`、`Pause`、`Menu`、`ArrowBack` 均在 core 中，安全
