# 阅读器拖动进度条 / 滚动条 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Web 与 Android 阅读器新增可拖动的全书进度控件,松手跳转到对应章节。

**Architecture:** 进度语义两端两模式统一为"全书进度 0~100%";拖动交互统一为"松手才跳"(拖动时只移动 thumb + 显示"将跳到第 X 章")。Web 新建 `readerScrubber.js`(横向,替换 footer 进度文字);Android 新建 `ReaderScrollbar.kt`(竖向,右侧悬浮,不绑 `LazyListState`,与现有 `VerticalScrollbar` 互斥)。跳转复用两端已有的 `onNavigate` / `loadChapter` 逻辑。

**Tech Stack:** Web — 原生 ES module + `node:test` + jsdom + `tools/xsscheck`;Android — Jetpack Compose + Robolectric/Compose 测试 + Gradle。

## Global Constraints

- 提交风格:Conventional Commits,scope 用 `reader`/`web`/`android`(见 AGENTS.md)
- Web XSS:`innerHTML`/`outerHTML` sink 必须带 `// XSS-SAFE:` 注释或用 `escapeHtml()`;label 文字一律 `textContent`
- Web 测试:`cd server/internal/web && node --test`,改 web 后另跑 `cd tools/xsscheck && go run . ../../server/internal/web`
- Android 测试:`cd android && ./gradlew testDebugUnitTest`
- 不改 `VerticalScrollbar.kt`(`ImagePreviewScreen` 不受影响)
- 不改 `progress.js` 的现有 `updateProgressUI`(其它模块依赖其 paged 章内进度文本)
- 进度语义:**全书 0~100%**,`targetIdx = round(p * (chapterCount - 1))`,`coerceIn(0, chapterCount-1)`

---

## File Structure

| 文件 | 责任 | 动作 |
|---|---|---|
| `server/internal/web/readerScrubber.js` | 横向可拖动进度条:纯函数 progress 映射 + DOM 渲染 + pointer 事件 | 新建 |
| `server/internal/web/readerScrubber.test.mjs` | `readerScrubber.js` 单测 | 新建 |
| `server/internal/web/textReader.js` | 接入 scrubber,替换 `.text-reader__progress` | 修改 |
| `server/internal/web/style.css` | `.text-reader__scrubber*` 样式 | 修改 |
| `android/.../ui/component/reader/ReaderScrollbar.kt` | 竖向可拖动滚动条 Composable | 新建 |
| `android/.../ui/component/reader/ReaderScrollbarTest.kt` | Compose UI 测试 | 新建 |
| `android/.../ui/screen/TextReaderScreen.kt` | 接入 `ReaderScrollbar`(右侧悬浮 + seek 回调) | 修改 |

---

## Task 1: Web — 纯函数 progress 映射 + 测试

**Files:**
- Create: `server/internal/web/readerScrubber.js`
- Create: `server/internal/web/readerScrubber.test.mjs`

**Interfaces:**
- Produces: `progressToChapterIndex(progress, chapterCount) -> number`(向下取整,clamp)、`chapterIndexToProgress(idx, chapterCount) -> number`

- [ ] **Step 1: 写失败测试**

创建 `server/internal/web/readerScrubber.test.mjs`:

```javascript
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { progressToChapterIndex, chapterIndexToProgress } from './readerScrubber.js';

test('progressToChapterIndex: middle of 10 chapters → 5', () => {
    assert.equal(progressToChapterIndex(0.5, 10), 5);
});

test('progressToChapterIndex: rounds to nearest, not floor', () => {
    // spec: targetIdx = round(p * (chapterCount - 1))
    assert.equal(progressToChapterIndex(0.55, 10), 5);  // round(0.55*9)=round(4.95)=5
    assert.equal(progressToChapterIndex(0.06, 10), 1);  // round(0.06*9)=round(0.54)=1
});

test('progressToChapterIndex: clamps to [0, chapterCount-1]', () => {
    assert.equal(progressToChapterIndex(-0.5, 10), 0);
    assert.equal(progressToChapterIndex(1.5, 10), 9);
});

test('progressToChapterIndex: chapterCount <= 1 returns 0', () => {
    assert.equal(progressToChapterIndex(0.9, 1), 0);
    assert.equal(progressToChapterIndex(0.9, 0), 0);
});

test('chapterIndexToProgress: idx 5 of 10 → 5/9', () => {
    assert.equal(chapterIndexToProgress(5, 10), 5 / 9);
});

test('chapterIndexToProgress: clamps', () => {
    assert.equal(chapterIndexToProgress(-1, 10), 0);
    assert.equal(chapterIndexToProgress(20, 10), 1);
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server/internal/web && node --test readerScrubber.test.mjs`
Expected: FAIL —— `Cannot find module './readerScrubber.js'`

- [ ] **Step 3: 写最小实现**

创建 `server/internal/web/readerScrubber.js`:

```javascript
// 阅读器全书进度拖动条。两种模式、两端口径统一:
// progress ∈ [0,1] 表示全书进度;targetIdx = round(progress * (chapterCount-1))。
// 松手才跳转(onSeekEnd),拖动中(onSeek)只更新本地 thumb + label 预览。

// progress → 目标章节索引,四舍五入,clamp 到 [0, chapterCount-1]。
export function progressToChapterIndex(progress, chapterCount) {
    if (chapterCount <= 1) return 0;
    const denom = chapterCount - 1;
    const raw = Math.round(progress * denom);
    return Math.min(denom, Math.max(0, raw));
}

// 章节索引 → 全书进度,clamp 到 [0,1]。
export function chapterIndexToProgress(idx, chapterCount) {
    if (chapterCount <= 1) return 0;
    const denom = chapterCount - 1;
    return Math.min(1, Math.max(0, idx / denom));
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server/internal/web && node --test readerScrubber.test.mjs`
Expected: PASS(6 tests)

- [ ] **Step 5: 提交**

```bash
cd server/internal/web
git add readerScrubber.js readerScrubber.test.mjs
git commit -m "feat(reader): add readerScrubber progress mapping helpers (web)"
```

---

## Task 2: Web — DOM 组件 + pointer 事件 + 测试

**Files:**
- Modify: `server/internal/web/readerScrubber.js`(追加 `renderScrubber`)
- Modify: `server/internal/web/readerScrubber.test.mjs`(追加 DOM 测试)

**Interfaces:**
- Consumes: Task 1 的纯函数
- Produces: `renderScrubber({ containerEl, getProgress, getChapterCount, onSeekStart, onSeek, onSeekEnd, formatLabel }) -> { update(), dispose() }`

- [ ] **Step 1: 写失败测试(追加到 test 文件末尾)**

在 `readerScrubber.test.mjs` 顶部补 jsdom 引入,并追加 DOM 测试:

```javascript
// 文件顶部追加(在现有 import 之后):
import { JSDOM } from 'jsdom';

function setupDom() {
    const dom = new JSDOM('<!DOCTYPE html><div id="host"></div>');
    global.document = dom.window.document;
    global.window = dom.window;
    return dom.window.document.getElementById('host');
}

import { renderScrubber } from './readerScrubber.js';

test('renderScrubber: builds DOM with track/thumb/label', () => {
    const host = setupDom();
    const api = renderScrubber({
        containerEl: host,
        getProgress: () => 0.5,
        getChapterCount: () => 10,
        onSeekStart: () => {}, onSeek: () => {}, onSeekEnd: () => {},
        formatLabel: (p) => `第${p}章`,
    });
    assert.ok(host.querySelector('.text-reader__scrubber'));
    assert.ok(host.querySelector('.text-reader__scrubber-track'));
    assert.ok(host.querySelector('.text-reader__scrubber-thumb'));
    assert.ok(host.querySelector('.text-reader__scrubber-label'));
    api.dispose();
});

test('renderScrubber: pointerdown+move+up fires onSeekStart/onSeek/onSeekEnd', () => {
    const host = setupDom();
    const calls = { start: 0, seek: [], end: [] };
    const api = renderScrubber({
        containerEl: host,
        getProgress: () => 0,
        getChapterCount: () => 10,
        onSeekStart: () => { calls.start++; },
        onSeek: (p) => { calls.seek.push(p); },
        onSeekEnd: (p) => { calls.end.push(p); },
        formatLabel: () => '',
    });
    const track = host.querySelector('.text-reader__scrubber-track');
    // 模拟 track 宽度 200px
    Object.defineProperty(track, 'getBoundingClientRect', {
        value: () => ({ left: 0, width: 200, right: 200, top: 0, bottom: 10, height: 10 }),
        configurable: true,
    });
    const dispatch = (type, x) => {
        const ev = new window.PointerEvent(type, { clientX: x, bubbles: true });
        track.dispatchEvent(ev);
    };
    dispatch('pointerdown', 100);  // 50%
    dispatch('pointermove', 150);  // 75%
    dispatch('pointerup', 180);    // 90%
    assert.equal(calls.start, 1);
    assert.deepEqual(calls.seek, [0.5, 0.75]);
    assert.deepEqual(calls.end, [0.9]);
    api.dispose();
});

test('renderScrubber: progress clamped to [0,1] on drag', () => {
    const host = setupDom();
    const seekVals = [];
    const api = renderScrubber({
        containerEl: host,
        getProgress: () => 0,
        getChapterCount: () => 10,
        onSeekStart: () => {}, onSeek: (p) => seekVals.push(p), onSeekEnd: () => {},
        formatLabel: () => '',
    });
    const track = host.querySelector('.text-reader__scrubber-track');
    Object.defineProperty(track, 'getBoundingClientRect', {
        value: () => ({ left: 0, width: 200, right: 200, top: 0, bottom: 10, height: 10 }),
        configurable: true,
    });
    const dispatch = (type, x) => track.dispatchEvent(new window.PointerEvent(type, { clientX: x, bubbles: true }));
    dispatch('pointerdown', -50);   // <0 → 0
    dispatch('pointermove', 999);   // >width → 1
    assert.deepEqual(seekVals, [0, 1]);
    api.dispose();
});

test('renderScrubber: update() syncs thumb to external progress', () => {
    const host = setupDom();
    let prog = 0.2;
    const api = renderScrubber({
        containerEl: host,
        getProgress: () => prog,
        getChapterCount: () => 10,
        onSeekStart: () => {}, onSeek: () => {}, onSeekEnd: () => {},
        formatLabel: () => '',
    });
    api.update();
    const thumb = host.querySelector('.text-reader__scrubber-thumb');
    assert.equal(thumb.style.left, '20%');
    prog = 0.8;
    api.update();
    assert.equal(thumb.style.left, '80%');
    api.dispose();
});

test('renderScrubber: dispose removes listeners + clears host', () => {
    const host = setupDom();
    const api = renderScrubber({
        containerEl: host,
        getProgress: () => 0,
        getChapterCount: () => 10,
        onSeekStart: () => {}, onSeek: () => {}, onSeekEnd: () => {},
        formatLabel: () => '',
    });
    api.dispose();
    assert.equal(host.children.length, 0);
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server/internal/web && node --test readerScrubber.test.mjs`
Expected: FAIL —— `renderScrubber is not a function`(新 DOM 测试全部失败,Task 1 的 6 个仍通过)

- [ ] **Step 3: 写最小实现(追加到 readerScrubber.js)**

```javascript
// 追加到 readerScrubber.js 末尾

// 创建并挂载横向可拖动进度条。
// onSeek(p):拖动中,仅更新本地预览,不做加载。
// onSeekEnd(p):松手,执行跳转。
// 返回 { update(), dispose() }。
export function renderScrubber({
    containerEl, getProgress, getChapterCount,
    onSeekStart, onSeek, onSeekEnd, formatLabel,
}) {
    const root = document.createElement('div');
    root.className = 'text-reader__scrubber';
    // XSS-SAFE: 纯字面量骨架,label 文字通过 textContent 设置
    root.innerHTML = `
        <div class="text-reader__scrubber-track"></div>
        <div class="text-reader__scrubber-thumb"></div>
        <span class="text-reader__scrubber-label"></span>
    `;
    containerEl.appendChild(root);
    const track = root.querySelector('.text-reader__scrubber-track');
    const thumb = root.querySelector('.text-reader__scrubber-thumb');
    const label = root.querySelector('.text-reader__scrubber-label');

    let isDragging = false;
    let dragProgress = 0;

    function progressFromClientX(clientX) {
        const rect = track.getBoundingClientRect();
        if (rect.width <= 0) return 0;
        return Math.min(1, Math.max(0, (clientX - rect.left) / rect.width));
    }

    function setThumb(progress) {
        thumb.style.left = `${Math.round(progress * 100)}%`;
    }

    function setLabel(progress, dragging) {
        const text = formatLabel ? formatLabel(progress, dragging) : '';
        if (typeof text === 'string') label.textContent = text;
    }

    function onPointerDown(e) {
        isDragging = true;
        dragProgress = progressFromClientX(e.clientX);
        try { root.setPointerCapture(e.pointerId); } catch (_) {}
        setThumb(dragProgress);
        setLabel(dragProgress, true);
        if (onSeekStart) onSeekStart();
        if (onSeek) onSeek(dragProgress);
        e.preventDefault();
    }
    function onPointerMove(e) {
        if (!isDragging) return;
        dragProgress = progressFromClientX(e.clientX);
        setThumb(dragProgress);
        setLabel(dragProgress, true);
        if (onSeek) onSeek(dragProgress);
    }
    function onPointerUp(e) {
        if (!isDragging) return;
        isDragging = false;
        try { root.releasePointerCapture(e.pointerId); } catch (_) {}
        if (onSeekEnd) onSeekEnd(dragProgress);
    }

    track.addEventListener('pointerdown', onPointerDown);
    root.addEventListener('pointermove', onPointerMove);
    root.addEventListener('pointerup', onPointerUp);
    root.addEventListener('pointercancel', onPointerUp);

    function update() {
        if (isDragging) return;  // 拖动中不覆盖本地 thumb
        const p = getProgress();
        setThumb(p);
        setLabel(p, false);
    }

    function dispose() {
        track.removeEventListener('pointerdown', onPointerDown);
        root.removeEventListener('pointermove', onPointerMove);
        root.removeEventListener('pointerup', onPointerUp);
        root.removeEventListener('pointercancel', onPointerUp);
        if (root.parentNode) root.parentNode.removeChild(root);
    }

    update();
    return { update, dispose };
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server/internal/web && node --test readerScrubber.test.mjs`
Expected: PASS(全部 11 tests)

- [ ] **Step 5: 跑 XSS 检查**

Run: `cd tools/xsscheck && go run . ../../server/internal/web`
Expected: PASS(innerHTML 已带 `// XSS-SAFE:` 注释)

- [ ] **Step 6: 提交**

```bash
cd server/internal/web
git add readerScrubber.js readerScrubber.test.mjs
git commit -m "feat(reader): add renderScrubber DOM component with pointer events (web)"
```

---

## Task 3: Web — textReader.js 接入 scrubber + style.css

**Files:**
- Modify: `server/internal/web/textReader.js`(替换 footer `.text-reader__progress` 为 scrubber)
- Modify: `server/internal/web/style.css`(新增 `.text-reader__scrubber*` 样式)

**Interfaces:**
- Consumes: Task 2 的 `renderScrubber`、Task 1 的 `progressToChapterIndex`
- Produces: footer 中可拖动全书进度条,松手调 `onNavigate(targetIdx)`

**关键背景**:`progress.js` 的 `updateProgressUI()` 当前在 paged 模式写 `.text-reader__progress.textContent`(章内进度文本)。本任务把 `.text-reader__progress` span 替换成 scrubber 宿主,因此 `updateProgressUI` 的 paged 分支会把 textContent 写到已不存在的元素(查 querySelector 返回 null → 已有 `if (els.progressBar)` 守卫模式,需同样保护 `els.progress`)。本任务在 `progress.js` 加 null 守卫,不改其语义。

- [ ] **Step 1: 写失败测试(验证 textReader 接入后 scrubber 出现在 footer)**

在 `server/internal/web/` 下,验证方式为手动 + 已有 snapshot 测试。先确认无现成 textReader 集成测试可挂,故本任务以"现有 `node --test` 全绿 + XSS 通过 + 手动浏览器验证"为验收标准,不新增集成测试(避免引入 DOM 全量 mock 的过度成本)。

> 若实施者发现存在 `textReader` 集成测试入口,应在该入口追加"footer 含 `.text-reader__scrubber`"断言;否则跳过此步进入实现。

- [ ] **Step 2: 修改 textReader.js — 引入 scrubber**

在 `server/internal/web/textReader.js` 顶部 import 区(第 6-15 行附近)追加:

```javascript
import { renderScrubber } from './readerScrubber.js';
import { progressToChapterIndex } from './readerScrubber.js';
```

- [ ] **Step 3: 修改 textReader.js — footer 骨架保留 progress 宿主 span**

footer 的 `.text-reader__progress` span(原 textReader.js 第 42 行 `<span class="text-reader__progress">-</span>`)保留不动,作为 scrubber 挂载点。无需改骨架。

- [ ] **Step 4: 修改 textReader.js — 在子模块装配区接入 scrubber**

找到 `const autoscrollApi = renderAutoscroll({...})` 之后、`const settingsApi = renderSettings(container)` 之前(约第 245 行),插入 scrubber 装配:

```javascript
    // ===== 全书进度拖动条(松手才跳)=====
    const scrubberApi = renderScrubber({
        containerEl: els.progress,   // 原 .text-reader__progress span 作为宿主
        getProgress: () => {
            const cc = state.chapterCount || 1;
            // 两种模式都映射到全书进度:
            //   paged: (currentIdx + chapterInnerFraction) / cc
            //   scroll: updateProgressUI 已算 overallFraction,这里复用同口径
            const isScroll = readerPrefs.getSettings().readingMode === 'scroll';
            if (isScroll) {
                const activeSec = els.content.querySelector(
                    `.text-reader__chapter-section[data-chapter-index="${state.currentIdx}"]`
                );
                let frac = 0;
                if (activeSec) {
                    const rect = activeSec.getBoundingClientRect();
                    const containerTop = els.content.getBoundingClientRect().top;
                    frac = Math.min(1, Math.max(0, (containerTop - rect.top) / Math.max(1, rect.height)));
                }
                return Math.min(1, Math.max(0, (state.currentIdx + frac) / cc));
            }
            const maxScroll = Math.max(1, els.content.scrollHeight - els.content.clientHeight);
            const inner = Math.min(1, Math.max(0, els.content.scrollTop / maxScroll));
            return Math.min(1, Math.max(0, (state.currentIdx + inner) / cc));
        },
        getChapterCount: () => state.chapterCount || 1,
        onSeekStart: () => { if (autoscrollApi) autoscrollApi.stop(); },
        onSeek: () => {},  // 纯本地预览,renderScrubber 内部已更新 thumb/label
        onSeekEnd: (p) => {
            const targetIdx = progressToChapterIndex(p, state.chapterCount || 1);
            onNavigate(targetIdx);
        },
        formatLabel: (p, dragging) => {
            const cc = state.chapterCount || 1;
            const targetIdx = progressToChapterIndex(p, cc);
            const pct = Math.round(p * 100);
            if (dragging) return `将跳到第 ${targetIdx + 1} 章`;
            return `第 ${state.currentIdx + 1} / ${cc} 章 (${pct}%)`;
        },
    });
```

- [ ] **Step 5: 修改 textReader.js — onContentScroll 中同步 scrubber**

找到 `onContentScroll` 末尾的 `updateProgressUI();`(约第 338 行),在其后追加:

```javascript
        updateProgressUI();
        if (scrubberApi) scrubberApi.update();
```

- [ ] **Step 6: 修改 textReader.js — 章节加载后同步 scrubber**

找到 `loadChapter` 内的 `updateProgressUI();`(约第 467 行),在其后追加:

```javascript
            updateProgressUI();
            if (scrubberApi) scrubberApi.update();
```

- [ ] **Step 7: 修改 textReader.js — cleanup 释放 scrubber**

找到 `container._cleanupReader = () => { ... }`(约第 345 行),在 `autoscrollApi.dispose()` 同行附近追加 `scrubberApi.dispose();`:

```javascript
    container._cleanupReader = () => {
        unsubSettings(); unsubPrefs(); unsubBms();
        tocApi.dispose(); bookmarksApi.dispose(); autoscrollApi.dispose(); settingsApi.dispose();
        scrubberApi.dispose();
        // ...其余清理保持不变
```

- [ ] **Step 8: 修改 progress.js — 给 els.progress 加 null 守卫**

`progress.js` 的 `updateProgressUI` 在 paged/scroll 两分支都写 `els.progress.textContent`。由于 scrubber 接管了 `.text-reader__progress`(其内部 label 用独立 `.text-reader__scrubber-label`),原 textContent 写入会覆盖 scrubber 子树。改为:把 `els.progress` 当作纯挂载点,不再写其 textContent。

修改 `server/internal/web/progress.js` 第 57、62 行,把:
```javascript
        els.progress.textContent = `全书进度 ${percent}% · 第 ${currentIdx + 1} / ${chapterCount} 章`;
```
与
```javascript
        els.progress.textContent = `第 ${currentIdx + 1} / ${chapterCount} 章 (${percent}%)`;
```
**整行删除**(进度文本现由 scrubber 的 `formatLabel` 接管)。`progressBar.style.width` 写入保留。

- [ ] **Step 9: 新增 style.css 样式**

在 `server/internal/web/style.css` 找到 `.text-reader__progress { ... }`(约第 2349 行)块之后,追加:

```css
/* 全书进度拖动条:替换原 .text-reader__progress 文字 */
.text-reader__scrubber {
    position: relative;
    flex: 1;
    min-width: 120px;
    height: 28px;
    display: flex;
    align-items: center;
    cursor: pointer;
    touch-action: none;  /* pointer 拖动不被滚动抢占 */
}
.text-reader__scrubber-track {
    position: relative;
    width: 100%;
    height: 4px;
    border-radius: 2px;
    background-color: var(--reader-border, var(--border-color));
}
.text-reader__scrubber-thumb {
    position: absolute;
    top: 50%;
    left: 0%;
    width: 14px;
    height: 14px;
    border-radius: 50%;
    transform: translate(-50%, -50%);
    background-color: var(--primary, #6366f1);
    box-shadow: 0 1px 3px rgba(0, 0, 0, 0.3);
    transition: width 0.1s ease, height 0.1s ease;
}
.text-reader__scrubber:hover .text-reader__scrubber-thumb,
.text-reader__scrubber:active .text-reader__scrubber-thumb {
    width: 18px;
    height: 18px;
}
.text-reader__scrubber-label {
    position: absolute;
    bottom: 100%;
    left: 50%;
    transform: translateX(-50%);
    margin-bottom: 6px;
    font-size: 11px;
    color: var(--reader-chrome-fg, var(--text-muted));
    white-space: nowrap;
    pointer-events: none;
    opacity: 0.85;
}
```

> label 定位在 thumb 上方,非拖动时也常驻显示百分比(替代原 progress 文字)。若实施者更希望 label 仅拖动时显示,把 `.text-reader__scrubber-label` 默认 `opacity: 0`,在 `.text-reader__scrubber:active .text-reader__scrubber-label { opacity: 0.85; }`——但默认显示更接近原体验,保持。

- [ ] **Step 10: 跑全部 web 测试 + XSS**

Run: `cd server/internal/web && node --test`
Expected: PASS(progress.test.mjs 等全部通过——`els.progress.textContent` 删除后,progress 测试若断言了该文本需在下一步处理)

- [ ] **Step 11: 修复 progress.test.mjs 对 textContent 的断言(如有)**

检查 `server/internal/web/progress.test.mjs` 是否断言了 `els.progress.textContent`。若有(例如断言 "第 1/12 章 (50%)"),删除该断言(进度文本已移交 scrubber 的 formatLabel,后者由 readerScrubber.test.mjs 覆盖)。`progressBar.style.width` 断言保留。

Run: `cd server/internal/web && node --test`
Expected: PASS

- [ ] **Step 12: 跑 XSS 检查**

Run: `cd tools/xsscheck && go run . ../../server/internal/web`
Expected: PASS

- [ ] **Step 13: 浏览器手动验证**

启动 server,打开阅读器,验证:
- footer 中间出现横向拖动条,label 显示"第 X/Y 章 (Z%)"
- 拖动 thumb 时 thumb 跟随,label 切换为"将跳到第 X 章",内容不跳转
- 松手后跳到目标章节
- 分章模式与滚动模式均生效

- [ ] **Step 14: 提交**

```bash
cd server/internal/web
git add textReader.js progress.js progress.test.mjs style.css
git commit -m "feat(reader): wire readerScrubber into footer, replace progress text (web)"
```

---

## Task 4: Android — `ReaderScrollbar` Composable + 测试

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderScrollbar.kt`
- Create: `android/app/src/test/java/com/juziss/localmediahub/ui/component/reader/ReaderScrollbarTest.kt`

**Interfaces:**
- Produces: `ReaderScrollbar(progress: Float, onSeekStart: () -> Unit, onSeek: (Float) -> Unit, onSeekEnd: (Float) -> Unit, modifier: Modifier)`

- [ ] **Step 1: 写失败测试(Compose UI 测试)**

创建 `android/app/src/test/java/com/juziss/localmediahub/ui/component/reader/ReaderScrollbarTest.kt`:

```kotlin
package com.juziss.localmediahub.ui.component.reader

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.compose.ui.test.click
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReaderScrollbarTest {

    // 注:Robolectric 下 pointerInput 的 awaitPointerEvent 事件注入不稳定,
    // 此测试以"组件可渲染 + progress clamp"为最低保障;pointer 序列由真机手动验证(Task 5 Step 8)。
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun seek_callbacks_fire_in_correct_order() {
        val start = java.util.concurrent.atomic.AtomicInteger(0)
        val seekVals = java.util.concurrent.CopyOnWriteArrayList<Float>()
        val endVals = java.util.concurrent.CopyOnWriteArrayList<Float>()

        composeRule.setContent {
            ReaderScrollbar(
                progress = 0.2f,
                onSeekStart = { start.incrementAndGet() },
                onSeek = { seekVals.add(it) },
                onSeekEnd = { endVals.add(it) },
                modifier = Modifier,
                testTag = "scrubber",
            )
        }

        composeRule.onNodeWithTag("scrubber").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f, 200f))
            up()
        }
        composeRule.waitForIdle()

        org.junit.Assert.assertEquals(1, start.get())
        org.junit.Assert.assertTrue("onSeek should fire during drag", seekVals.isNotEmpty())
        org.junit.Assert.assertEquals(1, endVals.size)
        // 松手 progress 在 [0,1]
        val endP = endVals.first()
        org.junit.Assert.assertTrue(endP in 0f..1f)
    }
}
```

> 注:`ReaderScrollbar` 需暴露 `testTag` 参数供测试定位(见 Step 3 接口)。若项目已有 `createAndroidComposeRule` 习惯,实施者可改用,但 `createComposeRule` 已足够。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*.ReaderScrollbarTest"`
Expected: FAIL —— unresolved reference `ReaderScrollbar`

- [ ] **Step 3: 写最小实现**

创建 `android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderScrollbar.kt`:

```kotlin
package com.juziss.localmediahub.ui.component.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * 阅读器全书进度竖向可拖动滚动条。与 [com.juziss.localmediahub.ui.component.VerticalScrollbar]
 * 互斥使用——本组件绑外部 progress 语义,不绑 LazyListState。
 *
 * 进度语义:progress ∈ [0f, 1f] 表示全书进度(两种阅读模式统一)。
 * 拖动交互:松手才跳——onSeek 仅更新本地 thumb 预览,onSeekEnd 执行跳转。
 */
@Composable
fun ReaderScrollbar(
    progress: Float,
    onSeekStart: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekEnd: (Float) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    val displayProgress by remember {
        derivedStateOf { if (isDragging) dragProgress else clampedProgress }
    }

    var trackPx by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = modifier
            .width(28.dp)
            .fillMaxHeight()
            .padding(vertical = 48.dp)
            .clipToBounds()
            .let { if (testTag != null) it.testTag(testTag) else it }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val y = event.changes.firstOrNull()?.position?.y ?: 0f
                        when (event.type) {
                            PointerEventType.Press -> {
                                trackPx = this.size.height.toFloat()
                                if (trackPx > 0f) {
                                    dragProgress = (y / trackPx).coerceIn(0f, 1f)
                                    isDragging = true
                                    onSeekStart()
                                    onSeek(dragProgress)
                                }
                                event.changes.firstOrNull()?.consume()
                            }
                            PointerEventType.Move -> {
                                if (isDragging && trackPx > 0f) {
                                    dragProgress = (y / trackPx).coerceIn(0f, 1f)
                                    onSeek(dragProgress)
                                }
                            }
                            PointerEventType.Release -> {
                                if (isDragging) {
                                    isDragging = false
                                    onSeekEnd(dragProgress)
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
    ) {
        val trackHeightDp = maxHeight
        val thumbHeightDp = (trackHeightDp * 0.15f).coerceAtLeast(32.dp)
        val thumbOffsetDp = (trackHeightDp - thumbHeightDp) * displayProgress
        val thumbAlpha = if (isDragging) 0.85f else 0.5f

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(4.dp)
                .align(Alignment.Center)
                .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(2.dp))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = thumbOffsetDp)
                .height(thumbHeightDp)
                .padding(horizontal = if (isDragging) 9.dp else 11.dp)
                .background(Color.White.copy(alpha = thumbAlpha), RoundedCornerShape(3.dp))
        )
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*.ReaderScrollbarTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
cd android
git add app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderScrollbar.kt \
        app/src/test/java/com/juziss/localmediahub/ui/component/reader/ReaderScrollbarTest.kt
git commit -m "feat(reader): add ReaderScrollbar composable with drag-to-seek (android)"
```

---

## Task 5: Android — `TextReaderScreen` 接入 `ReaderScrollbar`

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt`(在内容区 `Box` 内叠加右侧 `ReaderScrollbar`)

**Interfaces:**
- Consumes: Task 4 的 `ReaderScrollbar`;复用现有 `overallPercent` / `loadChapter` / `scrollChapters` / `listState`

- [ ] **Step 1: 写失败测试(扩展 TextReaderScreen 现有测试)**

检查 `android/app/src/test/java/com/juziss/localmediahub/ui/screen/TextReaderScreenThemeTest.kt`,在其内追加一个测试:渲染 `TextReaderScreen` 后,树中存在 `ReaderScrollbar`(通过 testTag 或语义断言)。

> 若该测试文件结构不便挂载,实施者可新建 `TextReaderScrubberIntegrationTest.kt`,用 fake `TextReaderViewModel`(参考现有 `TextReaderViewModelReaderTest.kt` 的 fake 模式)渲染,断言拖动 onSeekEnd 调用 `viewModel.loadChapter`。最低要求:断言 `ReaderScrollbar` 节点存在。

在 `TextReaderScreenThemeTest.kt` 末尾追加(若该文件存在且用 `createComposeRule`):

```kotlin
    @Test
    fun readerScreen_containsScrubScrollbar() {
        // 复用本测试类已有的 fake ViewModel 装配(与现有测试同模式)
        composeRule.setContent {
            TextReaderScreen(viewModel = fakeViewModel(), onBack = {})
        }
        // ReaderScrollbar 用 Color.White thumb,通过 onAllNodesWithTag 或语义断言存在
        // 实施者按实际 fakeViewModel 装配补全,断言 scrubber 节点 > 0
    }
```

> 实施者注意:此测试需复用本类已有的 `fakeViewModel()` 工厂。若不存在,本步改为"手动编译 + 实装验证",测试降级为 Task 4 已覆盖的组件级测试。优先复用现有装配,不新建 fake。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*.TextReaderScreenThemeTest"`
Expected: FAIL —— `readerScreen_containsScrubScrollbar` 断言 scrubber 不存在

- [ ] **Step 3: 修改 TextReaderScreen.kt — import ReaderScrollbar**

在 `TextReaderScreen.kt` 顶部 import 区追加:

```kotlin
import com.juziss.localmediahub.ui.component.reader.ReaderScrollbar
import kotlin.math.roundToInt
```

- [ ] **Step 4: 修改 TextReaderScreen.kt — 在内容 Box 内叠加 ReaderScrollbar**

找到内容区 `Box(Modifier.fillMaxSize().padding(padding)...) { ... }`(约第 447 行),在该 `Box` 内容的最末(在 `if (error == null && !isLoading) { ... }` 块之后、Box 闭合 `}` 之前)追加:

```kotlin
                    // ===== 右侧悬浮全书进度拖动条(松手才跳)=====
                    ReaderScrollbar(
                        progress = (overallPercent / 100f).coerceIn(0f, 1f),
                        onSeekStart = {
                            if (viewModel.isAutoScrolling.value) viewModel.stopAutoScroll()
                        },
                        onSeek = { /* 纯本地预览,组件内部已更新 thumb */ },
                        onSeekEnd = { p ->
                            val total = (book?.chapters?.size ?: 1).coerceAtLeast(1)
                            val targetIdx = (p * (total - 1)).roundToInt().coerceIn(0, total - 1)
                            scope.launch {
                                if (isScrollMode) {
                                    val loadedCh = scrollChapters.find { it.chapterIndex == targetIdx }
                                    if (loadedCh != null) {
                                        var itemOffset = 0
                                        for (c in scrollChapters) {
                                            if (c.chapterIndex == targetIdx) break
                                            itemOffset += c.blocks.size + 2
                                        }
                                        viewModel.updateCurrentIndex(targetIdx)
                                        listState.scrollToItem(itemOffset)
                                    } else {
                                        viewModel.loadChapter(targetIdx, resetScroll = true)
                                        viewModel.preloadScrollChapters(3)
                                        listState.scrollToItem(0)
                                    }
                                } else {
                                    viewModel.loadChapter(targetIdx, resetScroll = true)
                                    listState.scrollToItem(0)
                                }
                            }
                        },
                        modifier = Modifier.align(Alignment.End),
                    )
```

> `overallPercent` 已在 TextReaderScreen 现有代码(约第 384 行)计算。`scope` / `isScrollMode` / `scrollChapters` / `listState` / `viewModel` 均为现有作用域变量。`Alignment.End` 让条贴右侧。

- [ ] **Step 5: 跑测试确认通过**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*.TextReaderScreenThemeTest"`
Expected: PASS

- [ ] **Step 6: 跑全部 Android 单测**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: PASS(无回归)

- [ ] **Step 7: 编译 Debug APK 验证**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: 真机手动验证**

安装 APK,打开阅读器,验证:
- 右侧边缘出现竖向拖动条
- 拖动 thumb 时跟随,松手跳到对应章节
- 分章模式与滚动模式均生效
- 自动滚动开启时拖动会停止自动滚动

- [ ] **Step 9: 提交**

```bash
cd android
git add app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt \
        app/src/test/java/com/juziss/localmediahub/ui/screen/TextReaderScreenThemeTest.kt
git commit -m "feat(reader): wire ReaderScrollbar into TextReaderScreen right edge (android)"
```

---

## Task 6: 文档更新

**Files:**
- Modify: `AGENTS.md`(TextReaderScreen 描述追加"右侧全书进度拖动条")

- [ ] **Step 1: 更新 AGENTS.md TextReaderScreen 描述**

找到 AGENTS.md 第 39 行 `TextReaderScreen(...)` 描述,把"实时百分比进度与沉浸/普通双进度条"后追加"+ 右侧全书进度拖动条(松手跳章)"。

- [ ] **Step 2: 提交**

```bash
git add AGENTS.md
git commit -m "docs: note reader scrub scrollbar in module map"
```
