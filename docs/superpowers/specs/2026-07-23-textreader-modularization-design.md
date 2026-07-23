# textReader.js 模块化拆分设计文档

**日期**：2026-07-23
**范围**：`server/internal/web/textReader.js` 及其拆分出的新模块
**目标读者**：实施 agent、未来维护者

## 背景

`textReader.js` 已涨到 1160 行，承担渲染、目录抽屉、书签、进度、自动滚动、阅读设置、手势、章节加载等多重职责。Round 32/33 修 TOC 高亮 bug 时暴露了架构问题：模块级 `toggleDrawer` 靠 `els.drawer._highlightCurrent` 挂钩子访问闭包状态——是 hack；`closeDrawer`（闭包内）与 `onOutsideDrawerClick`（模块级）双路径关闭抽屉，监听器生命周期分散；`onContentScroll` 章节推断阈值无测试覆盖。

## 目标

1. 1160 行单文件 → 8 个职责清晰的 ES module 文件（6 个子模块 + bus + state + 主模块）。
2. 引入事件总线，模块间通过事件解耦，消除 `_highlightCurrent` hack。
3. 顺带修复 3 处已知粗糙处（hack、双路径关闭、无测试的阈值）。
4. 拆分后各模块可独立单测；行为快照保证零回归。

## 非目标（明确不做）

- 不加新功能（目录搜索、章节进度百分比显示等留下轮）。
- 不改 CSP / 不加构建工具（保持无构建步骤，ES module 原生 import）。
- 不动 Android 端 TextReaderScreen（独立 Compose 实现）。
- Round 32 follow-up（Referer strip、token 移除等）和 Android 测试补强各自独立 spec。

## 总体结构

```
textReader.js   主 render + 编排 + 生命周期 + 章节加载 + 翻章手势（~350 行）
bus.js          事件总线（订阅/发布/取消，零依赖，~60 行）
reader-state.js        核心状态容器（currentIdx/book/chapterCount/els refs，~80 行）
toc.js          目录抽屉（渲染/高亮/开关/外部点击关闭，~200 行）
bookmarks.js    书签 tab（渲染/增删/当前章节标记，~150 行）
progress.js     进度计算 + UI + 滚动章节推断（~120 行）
autoscroll.js   自动滚动面板（播放/调速 rAF，~100 行）
reader-settings.js     阅读设置 dialog（主题/字号/行距等，~250 行）
```

## 模块边界与依赖

```
textReader.js (主)
  ├─ 编排：import 各子模块，组装 renderTextReader
  ├─ 生命周期：cleanup（移除监听、取消 bus 订阅、dispose 子模块）
  ├─ 章节加载：loadChapter（协调 state/progress/TOC）
  └─ 内容区手势：翻章热区（左20%/右20%）

bus.js (零依赖)
  ├─ on(event, handler) → 返回 unsub 函数
  ├─ emit(event, payload) → try/catch 每个 handler
  └─ EVT 常量集中定义

reader-state.js (依赖 bus)
  ├─ 单例：currentIdx / book / chapterCount / els / settings
  ├─ setCurrentIdx(idx) → 更新 + emit('chapter:changed', {idx})
  └─ 各模块 import 读写

toc.js (依赖 state, bus)
  ├─ renderDrawer(container, book, onNavigate)
  ├─ highlightCurrent() / openDrawer() / closeDrawer() / toggleDrawer()
  ├─ 外部点击监听（单一路径，模块内管理生命周期）
  └─ 订阅 'chapter:changed' → highlightCurrent

bookmarks.js (依赖 state, bus, readerPrefs)
  ├─ renderBookmarksTab(panel)
  └─ 订阅 readerPrefs + 'chapter:changed'

progress.js (依赖 state)
  ├─ updateProgressUI()
  └─ detectActiveChapterOnScroll() → 修复后阈值

autoscroll.js (依赖 state) → 面板 + rAF 循环
reader-settings.js (依赖 state, readerPrefs) → dialog 构建
```

## 事件总线契约

```javascript
// bus.js
const handlers = new Map();

export function on(event, handler) {
    if (!handlers.has(event)) handlers.set(event, new Set());
    handlers.get(event).add(handler);
    return () => handlers.get(event)?.delete(handler);
}

export function emit(event, payload) {
    handlers.get(event)?.forEach(h => {
        try { h(payload); } catch (e) { console.error(e); }
    });
}

export const EVT = {
    CHAPTER_CHANGED: 'chapter:changed',
    BOOKMARKS_CHANGED: 'bookmarks:changed',
    SETTINGS_CHANGED: 'settings:changed',
};
```

## 关键数据流（修复后）

```
[滚动跨章 / 点 TOC / 点上下一章]
  → state.setCurrentIdx(idx)
    → emit('chapter:changed', {idx})
      → toc.highlightCurrent()
      → bookmarks 刷新当前章节标记
      → progress.updateProgressUI()

[点目录按钮] → toc.openDrawer() → highlightCurrent() + 注册外部点击监听
[点抽屉外]   → toc.onOutsideClick → closeDrawer()（单一关闭路径）
```

## 3 处粗糙处修复

1. **消除 `_highlightCurrent` hack**：删除 `els.drawer._highlightCurrent` 挂载与 `toggleDrawer` 内的调用；TOC 模块构造时 `on(EVT.CHAPTER_CHANGED, () => highlightCurrent())`，`openDrawer` 内直接调本模块 `highlightCurrent()`。

2. **统一抽屉关闭路径**：删除模块级 `toggleDrawer` / `onOutsideDrawerClick`；全部收进 `toc.js`，`openDrawer`/`closeDrawer`/`toggleDrawer`/外部点击监听共享同一 `drawerEl` 引用；导出 `dispose()` 给主模块 cleanup 调用。

3. **章节推断阈值放宽 + 测试**：`progress.detectActiveChapterOnScroll` 阈值改为 `rect.top - containerTop <= 120`；单元测试覆盖单章、跨章边界、首章、末章、空内容。

## 迁移策略（增量、可回滚）

按依赖底向上、逐模块抽取，每步可独立验证 + commit：

```
Step 1: bus.js          （零依赖，纯新增）             → 单测 + commit
Step 2: reader-state.js        （依赖 bus）                   → 单测 + commit
Step 3: progress.js     （依赖 state，含阈值修复）       → 单测 + 快照 + commit
Step 4: toc.js          （依赖 state/bus，含修复 1+2）   → 快照 + commit
Step 5: bookmarks.js    （依赖 state/bus/readerPrefs）   → 快照 + commit
Step 6: autoscroll.js   （依赖 state）                  → 快照 + commit
Step 7: reader-settings.js     （依赖 state/readerPrefs）       → 快照 + commit
Step 8: textReader.js   （瘦身为主模块 + cleanup）       → 全量快照 + 手测 + commit
```

每步后跑：`xsscheck` lint + 快照测试 + 手动加载一本书验证。任一步出问题，回退单步 commit。

## 错误处理

| 场景 | 处理 |
|---|---|
| bus handler 抛异常 | emit 内 try/catch，console.error，不影响其余订阅者 |
| ES module 加载顺序 | import 静态依赖，浏览器保证先加载——无需运行时守卫 |
| state 未初始化被读 | 单例字段默认值（currentIdx:0, book:null）；消费方已有 null 检查 |
| cleanup 时 bus 订阅未取消 | 主模块收集所有 unsub 函数统一调用；emit 前检查 handler 有效性 |
| jsdom 缺浏览器 API | requestAnimationFrame / scrollIntoView 用 stub |
| TOC dispose 后仍收事件 | dispose 先 unsub bus 再移除 DOM |

## 测试方案（单测 + 快照）

### A. 纯逻辑单测（node:test，无 jsdom）

| 文件 | 覆盖 |
|---|---|
| `bus.test.js` | on/emit 基本、多 handler、unsub 后不再收、emit 无订阅不报错、handler 异常隔离 |
| `progress.test.js` | 百分比计算（分章 + 滚动模式）、clamp [0,100]、detectActiveChapterOnScroll 阈值（单章/跨章/首末章/空内容） |

### B. 行为快照测试（node:test + jsdom）

固定 mock book（3 章 + 2 书签），render textReader，断言关键 DOM：

| 快照 | 断言 |
|---|---|
| 初始 render | TOC 3 项、第 1 项 active、进度文本含"第 1 / 3 章" |
| 点击第 3 章 | currentIdx=2、第 3 项 active、标题更新、抽屉关闭 |
| 滚动模式跨章 | scroll → activeIdx 变 → TOC 高亮跟随、进度更新 |
| 加书签 | bookmarks tab 出现新行、当前章节行有 `›` 标记 |
| 打开抽屉瞬间 | `_highlightCurrent` hack 已不存在，高亮仍正确（验证修复 1） |
| 外部点击关闭 | 点抽屉外 → 关闭、监听器移除（验证修复 2） |

**快照基线**：Step 0（拆分前）先记录基线；每步迁移后重跑，diff 必须为空。

### C. 回归守护

- `package.json` 加 `"test": "node --test"`。
- xsscheck lint 每步必过。

## 验证标准

```
cd server/internal/web && node --test                                    # 全绿
cd tools/xsscheck && go run . ../../server/internal/web/                  # 0 findings
手动：加载 txt + epub 各一本，验证目录/书签/进度/自动滚动/设置全正常
```

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| ES module 跨文件 import 在嵌入式 web FS 的加载顺序 | Step 8 完成后手动验证；import 是静态的，浏览器保证依赖先加载 |
| 快照测试因 jsdom 与真实浏览器差异产生假阳性/假阴性 | 快照只断言关键 DOM（结构 + 文本），不依赖布局计算；纯逻辑用单测覆盖 |
| 迁移中途 commit 处于"半拆分"状态 | 每步独立可编译可测；主模块在 Step 8 前仍能完整工作（旧代码与新模块并存过渡） |
| xsscheck lint 对新模块的 sink 判定 | 每步跑 lint；`// XSS-SAFE:` 注释随迁移搬到对应模块 |
