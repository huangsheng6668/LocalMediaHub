# 阅读器拖动进度条 / 滚动条设计

**日期**: 2026-07-25
**范围**: Web 端 (`server/internal/web/`) + Android 端 (`android/app/src/main/java/com/juziss/localmediahub/`)
**目标**: 在小说阅读器中加入可拖动的进度控件,用于快速跳转阅读进度。

---

## 1. 背景与现状

阅读器有两种阅读模式(两端语义一致):

- **分章模式 (PAGED, 默认)**: 一次显示一章,底部"上一章/下一章"按钮翻章。
- **滚动模式 (SCROLL)**: 全书章节连续滚动,接近边界时按需惰性加载前后章。

两端目前的进度展示都是**只读**的:

| 端 | 进度展示 | 可拖动? |
|---|---|---|
| Android `TextReaderScreen.kt` | 底部 `LinearProgressIndicator` + "第 X/Y 章 (Z%)" 文字 + 上一章/下一章按钮 | 否 |
| Web `textReader.js` | 顶部 fixed `text-reader__progress-bar` + 底部 `text-reader__progress` 文字("第2/12章(0%)") + 上一章/下一章/目录按钮 | 否 |

Android 端已存在 `VerticalScrollbar.kt` 组件(支持 drag-to-seek,绑 `LazyListState`),但**仅用于 `ImagePreviewScreen`,阅读器未接入**。

两端都缺少"拖动跳转"能力。本设计为阅读器新增可拖动进度控件。

---

## 2. 核心规则(两端统一,模式分化)

进度控件的语义**按阅读模式分化**,两端口径一致:

- **分章模式 (PAGED, 默认)**:
  - **进度语义**: Thumb 位置 = **当前章内**进度 (0~100%)。全书位置由底部"第 X/Y 章"文字 + 顶部固定进度条体现,Thumb 不承担全书定位。
  - **拖动交互**: **实时跟随**——拖动过程中实时滚动本章内容(`scrollTop` / `listState.scrollToItem`),不触发跳章,到顶/底即停。翻章继续用底部"上一章/下一章"按钮。
  - *为什么不跳章*: 分章模式一次只显示一章,拖动 Thumb 只是"本章内快速定位"。拖动只滚本章彻底消除了"误跨章"问题(用户在章内拖 Thumb 永远不会跳到别的章)。内容已在内存,实时跟随无加载开销、体验连贯。

- **滚动模式 (SCROLL)**:
  - **进度语义**: Thumb 位置 = **全书**进度 (0~100%),`targetIdx = round(p * (totalChaptersCount - 1))`。
  - **拖动交互**: **松手才跳转**——拖动过程中只移动 Thumb 并实时显示"将跳到第 X 章"提示,**松手 (Release) 时才触发章节加载与滚动跳转**。
  - *为什么松手才跳转*: 避免拖动过程中频繁触发网络 API 加载章节、重复重置滚动模式的缓冲窗口 (`scrollChapters`),保证滑动流畅无卡顿。Kindle / 微信读书等主流阅读器普遍采用此策略。

> **统一结论**: 两端代码口径一致;分章与滚动两种模式的差异仅在进度语义(章内 vs 全书)和拖动交互(实时跟随 vs 松手才跳),由 `readingMode` 分支处理。

---

## 3. Android 端设计

### 3.1 新建组件 `ReaderScrollbar.kt`

**路径**: `android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderScrollbar.kt`

**职责**: 接收外部进度 + seek 回调的竖向可拖动滚动条,**不绑 `LazyListState`**(与现有 `VerticalScrollbar` 职责分离)。

**接口**:
```kotlin
@Composable
fun ReaderScrollbar(
    progress: Float,                    // 0f..1f,外部传入的当前进度
    modifier: Modifier = Modifier,
    onSeek: (Float) -> Unit,            // 拖动中实时回调(progress)
    onSeekStart: () -> Unit = {},       // 按下 thumb
    onSeekEnd: (Float) -> Unit,         // 松手,带最终 progress
)
```

**内部逻辑**:
- `isDragging` 状态:拖动中显示 `dragProgress`,否则显示外部 `progress`。
- `pointerInput` 处理 Press/Move/Release:Press 触发 `onSeekStart` 并置 `isDragging=true`;Move 实时算 `(y/trackHeight).coerceIn(0,1)` → 更新 `dragProgress` + 调 `onSeek`;Release 置 `isDragging=false` + 调 `onSeekEnd(dragProgress)`。
- thumb 渲染:track + thumb,thumb 透明度拖动时加深(参考现有 `VerticalScrollbar` 的视觉)。
- 视觉风格沿用现有 `VerticalScrollbar` 的配色(`Color.White.copy(alpha=...)`),贴右侧边缘、不占内容宽度。

**与 `VerticalScrollbar` 的关系**: 两个组件**互斥使用**,不会同时出现。
- `VerticalScrollbar` 绑 `LazyListState`,仅 `ImagePreviewScreen` 调用。
- `ReaderScrollbar` 绑外部 progress 语义,仅 `TextReaderScreen` 调用。
- 各自只被自己的 Screen 渲染,运行时永远是单例。

### 3.2 `TextReaderScreen.kt` 接入

**放置位置**: 内容区右侧边缘竖向悬浮(对应方案 C),与现有底部 `LinearProgressIndicator` 并存——右侧条负责"拖",底部条负责"看数字"。

**progress 计算**:
- 两种阅读模式均传 `overallPercent / 100f` (0f..1f,全书进度)。
- `overallPercent` 直接复用 `TextReaderScreen.kt` 现有计算逻辑: `remember(idx, chapterPercent, totalChaptersCount)`。

**seek 回调实现**(两种模式均松手才跳):

- **`onSeekStart()`**:
  - 若 `isAutoScrolling == true` → 调用 `viewModel.stopAutoScroll()` 暂停自动滚动。

- **`onSeek(p)`** (拖动中):
  - 不触发任何加载/跳转,仅更新内部 thumb 位置并在控件侧边/底部实时显示 label 提示 ("将跳到第 X 章")。

- **`onSeekEnd(p)`** (松手):
  - 算目标章节 `targetIdx = (p * (totalChaptersCount - 1).coerceAtLeast(1)).roundToInt().coerceIn(0, totalChaptersCount - 1)`。
  - **分章模式**: 调 `viewModel.loadChapter(targetIdx, resetScroll=true)`,成功后 `listState.scrollToItem(0)` (复用目录抽屉点击逻辑 `TextReaderScreen.kt:303-308`)。
  - **滚动模式**: 若目标章已在 `scrollChapters` (已加载) → 计算 item offset, `listState.scrollToItem(offset)` + `viewModel.updateCurrentIndex(targetIdx)`; 若未加载 → `viewModel.loadChapter(targetIdx, resetScroll=true)` + `preloadScrollChapters(3)`,完成后 `listState.scrollToItem(0)` (复用 `TextReaderScreen.kt:286-308`)。

- **拖动提示**: `onSeekStart` / `onSeek` 期间,控件上方/底部临时显示"将跳到第 X 章",`onSeekEnd` 后恢复原百分比显示。

### 3.3 不改的部分
- `VerticalScrollbar.kt` 不动(`ImagePreviewScreen` 不受影响)。
- 底部 `LinearProgressIndicator` + 百分比文字保留(只读展示)。
- 目录抽屉、书签、自动滚动逻辑不动。

---

## 4. Web 端设计

### 4.1 新建模块 `readerScrubber.js`

**路径**: `server/internal/web/readerScrubber.js`

**职责**: 横向可拖动进度条组件,替换底部 footer 中的 `.text-reader__progress` 文字 span。

**接口**:
```javascript
export function renderScrubber({ 
    containerEl,      // 宿主元素(原 .text-reader__progress 的父级 footer)
    getProgress,      // () => number 0..1, 读取当前全书进度
    getMode,          // () => 'paged' | 'scroll'
    onSeekStart,      // () => void (例如暂停自动滚动)
    onSeek,           // (progress) => void 拖动实时回调 (仅更新 thumb 与 label 预览)
    onSeekEnd,        // (progress) => void 松手跳转回调
    formatLabel,      // (progress, mode, isDragging) => string 生成条上/浮动文字
});
// 返回 { update(), dispose() }
```

**内部逻辑**:
- 创建 `.text-reader__scrubber` DOM:`<div class="text-reader__scrubber"><div class="text-reader__scrubber-track"></div><div class="text-reader__scrubber-thumb"></div><span class="text-reader__scrubber-label"></span></div>`。
- Pointer 事件: `pointerdown` → `setPointerCapture` + `isDragging=true` + `onSeekStart()`; `pointermove` → 算 `(clientX-rect.left)/rect.width` → 更新 thumb 位置 + `onSeek(progress)`; `pointerup` / `pointercancel` → `isDragging=false` + `onSeekEnd(progress)`。
- 拖动中 thumb 位置由本地 `dragProgress` 控制,非拖动时由 `getProgress()` 驱动 (`update()` 在 `onContentScroll` / `updateProgressUI()` 中被同步触发)。
- XSS 安全: label 文字统一通过 `textContent` 设置,符合 `tools/xsscheck` 规范。

### 4.2 `textReader.js` 接入

**放置位置**: 替换 `.text-reader__footer` 中的 `.text-reader__progress` span,横向 slider 占据 footer 中间区域,两侧"上一章/下一章/目录"按钮保持定位。

**接入点**:
- `bindEls` 中将 `progress` 替换为 `scrubberHost` (或在原 `.text-reader__progress` 挂载点初始化 `renderScrubber`)。
- 在 `renderTextReader` 中调用 `renderScrubber({...})`:
  - `getProgress`: 读取全书进度 (分章模式 = `(currentIdx + chapterPercent/100) / chapterCount`, 滚动模式 = `(currentIdx + chapterFraction) / chapterCount`)。
  - `onSeekStart`: 若自动滚动在运行,调用 `autoscrollApi.stop()` 停止自动滚动。
  - `onSeek(p)` (两种模式): 仅更新本地 thumb + label 提示,不触发 DOM 加载。
  - `onSeekEnd(p)` (两种模式): 计算目标章节 `targetIdx = Math.round(p * (Math.max(1, chapterCount - 1)))`, 调用 `onNavigate(targetIdx)` 跳转(复用 `textReader.js` 已有的 `onNavigate` 逻辑)。
  - `formatLabel`: 非拖动时显示"第 X/Y 章 (Z%)",拖动中显示"将跳到第 X 章"。

**进度同步**: 现有 `onContentScroll` → `updateProgressUI()` 链路保留,scrubber 的 `update()` 在其中被调用以同步 thumb 位置。

### 4.3 `style.css` 新增

```css
.text-reader__scrubber { /* footer 中间区域,flex:1,可点击/拖动 */ }
.text-reader__scrubber-track { /* 横向 track,高度 ~4px,圆角 */ }
.text-reader__scrubber-thumb { /* thumb,绝对定位 left: progress% */ }
.text-reader__scrubber-label { /* 条上/下方文字 */ }
```
- 配色用现有 reader 主题变量(`--reader-fg`/`--reader-chrome-bg`/`--reader-muted`),自动适配 DAY/NIGHT/AUTO。
- 拖动中 thumb 放大 + 加深(参考 Android 端视觉一致性)。
- 响应式: 移动端窄屏时 thumb 触控区足够大(>=24px 命中区)。

### 4.4 不改的部分
- 顶部 fixed `text-reader__progress-bar` 保留(只读整体进度指示)。
- `progress.js` / `toc.js` / `bookmarks.js` / `autoscroll.js` / `reader-settings.js` 不动。
- 页面翻章手势(左右 20% 点击区)不动。

---

## 5. 数据流(两端统一口径,模式分化)

两端口径一致;分章与滚动两种模式的数据流如下:

### 分章模式 (PAGED) —— 实时跟随,不跳章
```
用户按住 thumb (pointerdown / Press)
  → onSeekStart()
      → 若正在自动滚动 → 停止自动滚动
      → 标记 isDragging = true
用户滑动 thumb (pointermove / Move)
  → onSeek(p)
      → p 范围限制在 [0f, 1f] (代表章内进度)
      → 实时滚动本章内容:
          [Web]    els.content.scrollTop = p * (scrollHeight - clientHeight)
          [Android] listState.scrollToItem(round(p * (totalItems-1)))
      → 更新 Thumb UI 位置 + label "第 X/Y 章 · 本章 round(p*100)%"
用户松开 thumb (pointerup / Release)
  → onSeekEnd(p)
      → 标记 isDragging = false
      → [不跳章,本章位置已在 onSeek 实时定位;直接 return]
```

### 滚动模式 (SCROLL) —— 松手才跳章
```
用户按住 thumb (pointerdown / Press)
  → onSeekStart()
      → 若正在自动滚动 → 停止自动滚动
      → 标记 isDragging = true
用户滑动 thumb (pointermove / Move)
  → onSeek(p)
      → p 范围限制在 [0f, 1f] (代表全书进度)
      → targetIdx = round(p * (chapterCount - 1))
      → 更新 Thumb UI 位置 + 显示提示 "将跳到第 targetIdx + 1 章"
      → [不触发任何章节 fetch 或页面 scroll]
用户松开 thumb (pointerup / Release)
  → onSeekEnd(p)
      → 标记 isDragging = false
      → targetIdx = round(p * (chapterCount - 1))
      → 已加载? scrollToItem(offset) : loadChapter(targetIdx, reset=true) + preload
```

> **结论**: `ReaderScrollbar` / `renderScrubber` 组件本身是模式无关的纯 UI(progress + onSeekStart/onSeek/onSeekEnd 回调),模式分化由 `TextReaderScreen` / `textReader.js` 在回调实现里按 `readingMode` 分支处理。

---

## 6. 测试策略

### Android (Robolectric/Compose 测试)
- `ReaderScrollbarTest`: progress 显示正确、pointer 拖动触发 onSeekStart/onSeek/onSeekEnd 序列、progress coerceIn(0,1)。
- `TextReaderScreen` 现有测试扩展:分章模式拖动 → listState 滚到正确 item;滚动模式拖动 → 松手触发 loadChapter(用 fake ViewModel 验证调用)。

### Web (node test runner,参考现有 `*.test.mjs`)
- `readerScrubber.test.mjs`: pointer 事件序列、progress coerceIn、label 文本正确、dispose 清理监听。
- 现有 `textReader` 相关测试扩展:scrubber 接入后 footer 结构正确、onSeekEnd 调用 onNavigate。

---

## 7. 不做的事 (YAGNI)

- 不做按字符/精确位置定位——章节粒度足够小说快速跳转场景。
- 不做拖动时的内容预览缩略图——过度设计。
- 不改 `VerticalScrollbar`——图片预览不受影响。
- 不统一两端形态(Android 竖向 / Web 横向)——两端布局差异大,各自贴"进度条原生位置"更自然。
- 不为 scrubber 增加独立设置项(开关/粗细等)——默认行为即可。

---

## 8. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 滚动模式 onSeekEnd 跳到未加载章节有短暂空白 | 复用现有 loadChapter + preloadScrollChapters,加载完成前 thumb 保持在松手位置,加载后 scrollIntoView;必要时 toast 提示"加载中" |
| Android 拖动与自动滚动冲突 | onSeekStart 时若 `isAutoScrolling` → 调 `viewModel.stopAutoScroll()`(复用现有"手动滑动停止自动滚动"逻辑) |
| 沉浸模式 (immersive) 下 chrome 隐藏导致 scrubber 不可见 | Android: 右侧条独立于 chrome 可见性,沉浸时半透明常驻;Web: footer 沉浸时隐藏,scrubber 在 footer 内随之隐藏,符合现有沉浸语义 |
| paged 模式 progress 计算与现有 chapterPercent 口径不一致 | 直接复用 `chapterPercent` / `overallPercent` / `updateProgressUI` 的现有计算,不另造口径 |
