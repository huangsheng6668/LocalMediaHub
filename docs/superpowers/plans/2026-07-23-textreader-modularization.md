# textReader.js 模块化拆分 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 1160 行的 `textReader.js` 拆成 8 个职责清晰的 ES module + 事件总线，消除 `_highlightCurrent` hack 与双路径抽屉关闭，顺带修复无测试的章节推断阈值，全程零行为回归。

**Architecture:** 增量迁移，按依赖底向上（bus → state → progress → toc → bookmarks → autoscroll → settings → 主模块）。引入极简事件总线 `bus.js` 解耦模块；核心共享状态收进 `state.js` 单例。每步独立可编译、可测、可 commit。行为不变性由 Step 0 记录的 jsdom 快照基线守护。

**Tech Stack:** 原生 ES module（无构建步骤）、Node.js 内置 `node:test`、jsdom（DOM 快照测试）、现有 `readerPrefs.js` / `api.js` / `toast.js`。

## Global Constraints

- **不加构建工具**：保持无构建步骤，浏览器靠 `<script type="module">` 原生加载；模块间用静态 `import`/`export`。
- **测试文件扩展名用 `.test.mjs`**：`server/internal/web/package.json` 无 `"type": "module"`，Node 默认按 CJS 解析 `.js`；用 `.mjs` 绕过（已有先例 `tools/build-tokens.mjs`）。源码 `.js` 保持不变（浏览器 `<script type="module">` 已正确加载）。
- **测试运行命令**：`cd server/internal/web && node --test`。
- **xsscheck lint 每步必过**：`cd tools/xsscheck && go run . ../../server/internal/web/` 必须输出 `OK: no unescaped innerHTML variables`。所有迁移的 DOM 写入 sink 必须带 `// XSS-SAFE:` 注释或调用 `escapeHtml()`。
- **不改 CSP**：CSP `script-src 'self'` 保持；新模块都是独立 `.js` 文件由 import 加载，不引入 inline script。
- **行为零回归**：每步迁移后，Step 0 记录的 jsdom 快照 diff 必须为空（允许的例外：文件拆分导致的注释位置变化，需在 report 中说明）。
- **commit 风格**：conventional commits，参考近期 `refactor(reader): ...`、`feat(reader): ...`。
- **不引入新运行时依赖**：jsdom 仅作 devDependency 加入 `package.json`，不进浏览器 bundle。
- **代码风格**：现有 web JS 用 4 空格缩进、单引号、分号结尾——新模块遵循。
- **ES module import 路径**：必须带 `.js` 扩展名（如 `import { on } from './bus.js'`），浏览器原生 module 要求显式扩展名。

## File Structure 总览

| 文件 | 职责 | 创建/修改 |
|---|---|---|
| `bus.js` | 事件总线 on/emit/EVT 常量 | Create |
| `bus.test.mjs` | bus 单测 | Create |
| `state.js` | 核心状态单例 + setCurrentIdx | Create |
| `state.test.mjs` | state 单测 | Create |
| `progress.js` | 进度计算 + 滚动章节推断（阈值修复） | Create |
| `progress.test.mjs` | progress 单测（含阈值边界） | Create |
| `toc.js` | 目录抽屉（修复 1 hack + 修复 2 双路径） | Create |
| `toc.test.mjs` | toc 快照 | Create |
| `bookmarks.js` | 书签 tab | Create |
| `bookmarks.test.mjs` | bookmarks 快照 | Create |
| `autoscroll.js` | 自动滚动面板 | Create |
| `settings.js` | 阅读设置 dialog | Create |
| `textReader.js` | 瘦身为主模块（编排 + cleanup + loadChapter + 手势） | Modify |
| `snapshot-baseline.test.mjs` | Step 0 记录的行为快照基线，全程守护 | Create |
| `package.json` | 加 `test` 脚本 + jsdom devDependency | Modify |
| `_snapshot-helpers.mjs` | 快照测试共享的 mock book + jsdom setup | Create |

---

## Task 0: 测试基础设施 + 行为快照基线

**Files:**
- Modify: `server/internal/web/package.json`
- Create: `server/internal/web/_snapshot-helpers.mjs`
- Create: `server/internal/web/snapshot-baseline.test.mjs`

**Interfaces:**
- Produces: `_snapshot-helpers.mjs` 导出 `setupJsdom()`、`mockBook`、`renderReader(path)`，供后续快照测试 import。
- Produces: `snapshot-baseline.test.mjs` 记录拆分前的 DOM 行为基线，后续每步迁移后重跑，diff 必须为空。

- [ ] **Step 1: 加 jsdom devDependency + test 脚本**

修改 `server/internal/web/package.json`，改为：

```json
{
  "name": "localmediahub-web",
  "version": "0.2.0",
  "private": true,
  "description": "Build-time tooling for the LocalMediaHub web manager.",
  "scripts": {
    "build:tokens": "node tools/build-tokens.mjs",
    "test": "node --test"
  },
  "devDependencies": {
    "jsdom": "25.0.1",
    "open-props": "1.7.7"
  }
}
```

- [ ] **Step 2: 安装 jsdom**

Run: `cd server/internal/web && npm install`
Expected: `added N packages`（jsdom + 其依赖）。`node_modules/jsdom` 存在。

- [ ] **Step 3: 创建快照测试 helpers**

创建 `server/internal/web/_snapshot-helpers.mjs`：

```javascript
// 快照测试共享工具：jsdom setup + mock book + reader render 入口。
// 仅供 .test.mjs import；不进浏览器 bundle（以 _ 开头 + 不被 index.html 引用）。
import { JSDOM } from 'jsdom';

// 3 章 + 2 书签的固定 mock book，所有快照测试用它保证可复现。
export const mockBook = {
    title: '测试书',
    path: '/test/book.txt',
    format: 'txt',
    chapters: [
        { title: '第一章 开端', index: 0 },
        { title: '第二章 发展', index: 1 },
        { title: '第三章 结局', index: 2 },
    ],
};

// 在 jsdom 里构造 #view-reader 容器并 stub 掉浏览器 API（rAF / scrollIntoView）。
export function setupJsdom() {
    const dom = new JSDOM('<!DOCTYPE html><html><body><div id="view-reader"></div></body></html>', {
        url: 'http://localhost/',
        pretendToBeVisual: true,
    });
    const { window } = dom;
    // stub requestAnimationFrame（jsdom 不实现）
    window.requestAnimationFrame = (cb) => setTimeout(cb, 0);
    window.cancelAnimationFrame = (id) => clearTimeout(id);
    // stub scrollIntoView（jsdom 不实现）
    window.Element.prototype.scrollIntoView = function () {};
    // 暴露到 global 让模块代码用到的全局可用
    global.window = window;
    global.document = window.document;
    global.requestAnimationFrame = window.requestAnimationFrame;
    global.cancelAnimationFrame = window.cancelAnimationFrame;
    global.localStorage = (() => {
        const store = {};
        return {
            getItem: (k) => (k in store ? store[k] : null),
            setItem: (k, v) => { store[k] = String(v); },
            removeItem: (k) => { delete store[k]; },
        };
    })();
    return { dom, window, document: window.document };
}

// 清理 global，避免测试间状态泄漏。
export function teardownJsdom() {
    delete global.window;
    delete global.document;
    delete global.requestAnimationFrame;
    delete global.cancelAnimationFrame;
    delete global.localStorage;
}
```

- [ ] **Step 4: 创建行为快照基线测试**

创建 `server/internal/web/snapshot-baseline.test.mjs`：

```javascript
// 行为快照基线：拆分前（Task 0）记录 textReader 的关键 DOM 行为，
// 后续每步迁移后重跑，diff 必须为空（证明行为零回归）。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setupJsdom, teardownJsdom, mockBook } from './_snapshot-helpers.mjs';

// 抓取关键 DOM 状态作为快照字符串。只断言结构 + 文本，不依赖布局。
function snapshotReader(container) {
    const toc = container.querySelector('.text-reader__drawer');
    const tocItems = [...container.querySelectorAll('.text-reader__drawer-item')];
    return {
        title: container.querySelector('.text-reader__title')?.textContent ?? '',
        progress: container.querySelector('.text-reader__progress')?.textContent ?? '',
        tocVisible: toc?.classList.contains('text-reader__drawer--hidden') === false,
        tocCount: tocItems.length,
        tocLabels: tocItems.map((el) => el.textContent),
        activeTocIndex: tocItems.findIndex((el) => el.classList.contains('text-reader__drawer-item--active')),
    };
}

test('baseline: initial render shows chapter 1 active', async () => {
    setupJsdom();
    try {
        document.getElementById('view-reader').innerHTML = `
            <div class="text-reader">
                <span class="text-reader__title"></span>
                <span class="text-reader__progress"></span>
                <div class="text-reader__drawer text-reader__drawer--hidden"></div>
            </div>`;
        // 注：真实 render 需 fetch；这里用预填 DOM 模拟"已 render 完"状态。
        // 基线快照的核心是：后续迁移后，相同输入产生相同 DOM 结构。
        const snap = snapshotReader(document.getElementById('view-reader'));
        assert.equal(snap.tocCount, 0); // 初始 DOM 无 TOC 项（由 render 填充）
        assert.equal(snap.tocVisible, false);
    } finally {
        teardownJsdom();
    }
});
```

**注**：Task 0 的基线测试是骨架——真正的端到端快照（含 fetch mock + 真 render）在 Task 8 主模块瘦身完成后补全。当前 Step 4 只验证 helpers + jsdom 工作正常。

- [ ] **Step 5: 运行测试验证 helpers 工作**

Run: `cd server/internal/web && node --test`
Expected: 1 test PASS（验证 jsdom setup + teardown 无报错）。

- [ ] **Step 6: Commit**

```bash
cd server/internal/web
git add package.json package-lock.json _snapshot-helpers.mjs snapshot-baseline.test.mjs
git commit -m "$(cat <<'EOF'
chore(web): add jsdom + node:test scaffold for textReader snapshots

Foundation for textReader modularization: jsdom devDependency, test
script, shared snapshot helpers (mock book + jsdom setup), and a
skeleton baseline test. No production code changed yet.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 1: bus.js 事件总线

**Files:**
- Create: `server/internal/web/bus.js`
- Create: `server/internal/web/bus.test.mjs`

**Interfaces:**
- Produces: `on(event, handler) → unsubFn`、`emit(event, payload)`、`off(event, handler)`、`EVT` 常量对象。

- [ ] **Step 1: 写失败测试**

创建 `server/internal/web/bus.test.mjs`：

```javascript
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { on, emit, off, EVT } from './bus.js';

test('on/emit: handler receives payload', () => {
    let received;
    on(EVT.CHAPTER_CHANGED, (p) => { received = p; });
    emit(EVT.CHAPTER_CHANGED, { idx: 2 });
    assert.deepEqual(received, { idx: 2 });
});

test('on returns unsub that stops further calls', () => {
    let count = 0;
    const unsub = on(EVT.CHAPTER_CHANGED, () => { count++; });
    emit(EVT.CHAPTER_CHANGED, {});
    unsub();
    emit(EVT.CHAPTER_CHANGED, {});
    assert.equal(count, 1);
});

test('multiple handlers all fire', () => {
    let a = 0, b = 0;
    on(EVT.CHAPTER_CHANGED, () => { a++; });
    on(EVT.CHAPTER_CHANGED, () => { b++; });
    emit(EVT.CHAPTER_CHANGED, {});
    assert.equal(a, 1);
    assert.equal(b, 1);
});

test('emit with no subscribers does not throw', () => {
    assert.doesNotThrow(() => emit('unheard', {}));
});

test('handler exception does not block siblings', () => {
    let siblingCalled = false;
    on(EVT.CHAPTER_CHANGED, () => { throw new Error('boom'); });
    on(EVT.CHAPTER_CHANGED, () => { siblingCalled = true; });
    emit(EVT.CHAPTER_CHANGED, {});
    assert.equal(siblingCalled, true);
});

test('off removes a specific handler', () => {
    let count = 0;
    const h = () => { count++; };
    on(EVT.CHAPTER_CHANGED, h);
    off(EVT.CHAPTER_CHANGED, h);
    emit(EVT.CHAPTER_CHANGED, {});
    assert.equal(count, 0);
});
```

- [ ] **Step 2: 运行测试验证 FAIL**

Run: `cd server/internal/web && node --test bus.test.mjs`
Expected: FAIL（`Cannot find module './bus.js'`）。

- [ ] **Step 3: 实现 bus.js**

创建 `server/internal/web/bus.js`：

```javascript
// 事件总线：textReader 各子模块间解耦通信。零依赖。
// 设计要点：
//   - on() 返回 unsub 函数，便于主模块 cleanup 统一取消订阅。
//   - emit() 对每个 handler try/catch，单个 handler 抛异常不影响其余。
//   - EVT 常量集中定义事件名，避免拼写错误。
const handlers = new Map();

// 订阅 event，返回取消订阅函数。
export function on(event, handler) {
    if (!handlers.has(event)) handlers.set(event, new Set());
    handlers.get(event).add(handler);
    return () => {
        const set = handlers.get(event);
        if (set) {
            set.delete(handler);
            if (set.size === 0) handlers.delete(event);
        }
    };
}

// 移除指定 handler。
export function off(event, handler) {
    const set = handlers.get(event);
    if (set) {
        set.delete(handler);
        if (set.size === 0) handlers.delete(event);
    }
}

// 发布 event。handler 抛异常被捕获并 console.error，不影响其余订阅者。
export function emit(event, payload) {
    const set = handlers.get(event);
    if (!set) return;
    for (const h of set) {
        try {
            h(payload);
        } catch (e) {
            console.error('[bus] handler error for', event, e);
        }
    }
}

// 事件类型常量。
export const EVT = {
    CHAPTER_CHANGED: 'chapter:changed',
    BOOKMARKS_CHANGED: 'bookmarks:changed',
    SETTINGS_CHANGED: 'settings:changed',
};
```

- [ ] **Step 4: 运行测试验证 PASS**

Run: `cd server/internal/web && node --test bus.test.mjs`
Expected: 6 tests PASS。

- [ ] **Step 5: Commit**

```bash
cd server/internal/web
git add bus.js bus.test.mjs
git commit -m "$(cat <<'EOF'
feat(reader): add event bus for module decoupling

bus.js provides on/emit/off/EVT — a minimal pub/sub that lets the
upcoming textReader submodules communicate without direct imports of
each other. on() returns an unsub for cleanup; emit() isolates handler
exceptions so one bad subscriber doesn't break the rest.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: state.js 核心状态单例

**Files:**
- Create: `server/internal/web/state.js`
- Create: `server/internal/web/state.test.mjs`

**Interfaces:**
- Consumes: `bus.js` 的 `on/emit/EVT`。
- Produces: `state` 单例对象（含 `currentIdx`、`book`、`chapterCount`、`els`、`settings` 字段）、`setCurrentIdx(idx)`、`resetState()`。

- [ ] **Step 1: 写失败测试**

创建 `server/internal/web/state.test.mjs`：

```javascript
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { state, setCurrentIdx, resetState } from './state.js';
import { on, EVT } from './bus.js';

test('setCurrentIdx updates state and emits chapter:changed', () => {
    resetState();
    let received;
    const unsub = on(EVT.CHAPTER_CHANGED, (p) => { received = p; });
    setCurrentIdx(3);
    assert.equal(state.currentIdx, 3);
    assert.deepEqual(received, { idx: 3 });
    unsub();
});

test('setCurrentIdx no-op when idx unchanged does not emit', () => {
    resetState();
    state.currentIdx = 2;
    let emitCount = 0;
    const unsub = on(EVT.CHAPTER_CHANGED, () => { emitCount++; });
    setCurrentIdx(2);
    assert.equal(emitCount, 0);
    unsub();
});

test('setCurrentIdx no-op for out-of-range negative', () => {
    resetState();
    state.currentIdx = 1;
    state.chapterCount = 5;
    setCurrentIdx(-1);
    assert.equal(state.currentIdx, 1); // unchanged
});

test('resetState restores defaults', () => {
    state.currentIdx = 9;
    state.book = { title: 'x' };
    resetState();
    assert.equal(state.currentIdx, 0);
    assert.equal(state.book, null);
    assert.equal(state.chapterCount, 0);
});
```

- [ ] **Step 2: 运行测试验证 FAIL**

Run: `cd server/internal/web && node --test state.test.mjs`
Expected: FAIL（`Cannot find module './state.js'`）。

- [ ] **Step 3: 实现 state.js**

创建 `server/internal/web/state.js`：

```javascript
// 核心状态单例：textReader 各子模块共享的可变状态。
// 设计要点：
//   - 单例（模块级 const），各模块 import 同一实例。
//   - setCurrentIdx 是唯一修改 currentIdx 的入口，同时 emit chapter:changed，
//     让 toc/bookmarks/progress 订阅自更新（替代 _highlightCurrent hack）。
//   - resetState 在 renderTextReader 入口调用，避免上一本书状态泄漏。
//   - els / settings 字段在主模块 render 时填充，子模块读取时按需 null 检查。
import { emit, EVT } from './bus.js';

export const state = {
    currentIdx: 0,
    chapterCount: 0,
    book: null,
    els: null,       // { content, drawer, title, progress, progressBar, ... }
    settings: null,  // readerPrefs.getSettings() 的缓存
    path: null,
};

// 更新当前章节 index。idx 未变化或越界时 no-op（不 emit）。
// 主模块 loadChapter、progress.detectActiveChapterOnScroll、TOC 点击都走这里。
export function setCurrentIdx(idx) {
    if (idx === state.currentIdx) return;
    if (idx < 0 || (state.chapterCount > 0 && idx >= state.chapterCount)) return;
    state.currentIdx = idx;
    emit(EVT.CHAPTER_CHANGED, { idx });
}

// 重置为默认值。renderTextReader 入口 + cleanup 调用。
export function resetState() {
    state.currentIdx = 0;
    state.chapterCount = 0;
    state.book = null;
    state.els = null;
    state.settings = null;
    state.path = null;
}
```

- [ ] **Step 4: 运行测试验证 PASS**

Run: `cd server/internal/web && node --test state.test.mjs`
Expected: 4 tests PASS。

- [ ] **Step 5: Commit**

```bash
cd server/internal/web
git add state.js state.test.mjs
git commit -m "$(cat <<'EOF'
feat(reader): add state singleton for shared reader state

state.js holds currentIdx/book/chapterCount/els/settings as a module
singleton. setCurrentIdx is the single mutation entry that also emits
chapter:changed — the mechanism that replaces the _highlightCurrent
hack (toc subscribes to the event instead).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: progress.js 进度计算 + 滚动章节推断（含阈值修复）

**Files:**
- Create: `server/internal/web/progress.js`
- Create: `server/internal/web/progress.test.mjs`

**Interfaces:**
- Consumes: `state` 单例。
- Produces: `updateProgressUI()`、`detectActiveChapterOnScroll(sections, container) → idx`（纯函数，可单测）。

**背景（修复 3）**：原 `onContentScroll` 的章节推断条件 `rect.top - containerTop <= 100 && rect.bottom - containerTop > 50` 在某些布局下不触发，导致 currentIdx 不更新。提取为纯函数 `detectActiveChapterOnScroll`，阈值放宽到 `rect.top - containerTop <= 120`，并用单测覆盖边界。

- [ ] **Step 1: 写失败测试（含纯函数边界）**

创建 `server/internal/web/progress.test.mjs`：

```javascript
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { detectActiveChapterOnScroll, computePercent } from './progress.js';

// mock section：模拟 getBoundingClientRect 返回的 {top, bottom}。
function mkSec(top, bottom, idx) {
    return { top, bottom, dataset: { chapterIndex: String(idx) } };
}

test('detectActive: single chapter → 0', () => {
    const sections = [mkSec(0, 800, 0)];
    assert.equal(detectActiveChapterOnScroll(sections, 0), 0);
});

test('detectActive: scrolled past chapter 0 into chapter 1', () => {
    // chapter0 顶部在容器顶 -200（已滚过），chapter1 顶部在 +50（刚露出）
    const sections = [mkSec(-200, -50, 0), mkSec(50, 600, 1)];
    assert.equal(detectActiveChapterOnScroll(sections, 0), 1);
});

test('detectActive: chapter boundary at exactly 120px threshold', () => {
    // chapter1 顶部恰在 containerTop+120（边界含，<= 120）
    const sections = [mkSec(-500, -100, 0), mkSec(120, 700, 1)];
    assert.equal(detectActiveChapterOnScroll(sections, 0), 1);
});

test('detectActive: first chapter still active when nothing scrolled past threshold', () => {
    const sections = [mkSec(0, 400, 0), mkSec(400, 800, 1)];
    // chapter0 top=0 (<=120 命中), chapter1 top=400 (>120 不命中)
    // 遍历后 activeIdx 被 chapter1 覆盖前，chapter0 已命中 → 返回最后一个命中的
    // 实现约定：返回顶部已滚过阈值的最靠后章节。chapter0 命中，chapter1 不命中 → 0
    assert.equal(detectActiveChapterOnScroll(sections, 0), 0);
});

test('detectActive: empty sections returns fallback', () => {
    assert.equal(detectActiveChapterOnScroll([], 0, 5), 5);
});

test('computePercent: clamps to [0, 100]', () => {
    assert.equal(computePercent(-1, 10), 0);
    assert.equal(computePercent(11, 10), 100);
    assert.equal(computePercent(5, 10), 50);
});

test('computePercent: zero max returns 0', () => {
    assert.equal(computePercent(5, 0), 0);
});
```

- [ ] **Step 2: 运行测试验证 FAIL**

Run: `cd server/internal/web && node --test progress.test.mjs`
Expected: FAIL（`Cannot find module './progress.js'`）。

- [ ] **Step 3: 实现 progress.js**

创建 `server/internal/web/progress.js`：

```javascript
// 进度计算 + 滚动模式章节推断。依赖 state 单例。
// 从 textReader.js 原 updateProgressUI + onContentScroll 章节推断逻辑提取。
// 修复 3：章节推断阈值从 <=100 放宽到 <=120，提取为可测纯函数。
import { state } from './state.js';

// 纯函数：根据各章节 section 的 bounding rect 推断当前活动章节。
// sections: [{ top, bottom, dataset: { chapterIndex } }]
// containerTop: 容器顶部 y 坐标
// fallbackIdx: 无命中时返回的回退值
// 约定：返回最后一个满足 top - containerTop <= 120 的章节（即"已滚到最下方的可见章节"）。
export function detectActiveChapterOnScroll(sections, containerTop, fallbackIdx) {
    let active = fallbackIdx;
    let hit = false;
    for (const sec of sections) {
        if (sec.top - containerTop <= 120) {
            const idx = parseInt(sec.dataset.chapterIndex, 10);
            if (!Number.isNaN(idx)) {
                active = idx;
                hit = true;
            }
        }
    }
    return hit ? active : fallbackIdx;
}

// 纯函数：计算百分比，clamp 到 [0, 100]。max<=0 时返回 0。
export function computePercent(value, max) {
    if (max <= 0) return 0;
    const pct = Math.round((value / max) * 100);
    return Math.min(100, Math.max(0, pct));
}

// 更新进度条 + 进度文本 UI。从 state 读 currentIdx/chapterCount/els。
// 分章模式：按内容区 scrollTop 算章内百分比。
// 滚动模式：按 currentIdx + 章内 fraction 算全书百分比。
export function updateProgressUI() {
    const { els, currentIdx, chapterCount, settings } = state;
    if (!els || !state.book || !state.book.chapters || chapterCount === 0) return;

    const isScrollMode = settings && settings.readingMode === 'scroll';
    let percent = 0;

    if (isScrollMode) {
        const activeSec = els.content.querySelector(
            `.text-reader__chapter-section[data-chapter-index="${currentIdx}"]`
        );
        let chapterFraction = 0;
        if (activeSec) {
            const rect = activeSec.getBoundingClientRect();
            const containerTop = els.content.getBoundingClientRect().top;
            const secHeight = Math.max(1, rect.height);
            const readTop = containerTop - rect.top;
            chapterFraction = Math.min(1, Math.max(0, readTop / secHeight));
        }
        const overallFraction = (currentIdx + chapterFraction) / chapterCount;
        percent = Math.min(100, Math.max(0, Math.round(overallFraction * 100)));
        els.progress.textContent = `全书进度 ${percent}% · 第 ${currentIdx + 1} / ${chapterCount} 章`;
    } else {
        const scrollTop = els.content.scrollTop;
        const maxScroll = Math.max(1, els.content.scrollHeight - els.content.clientHeight);
        percent = computePercent(scrollTop, maxScroll);
        els.progress.textContent = `第 ${currentIdx + 1} / ${chapterCount} 章 (${percent}%)`;
    }

    if (els.progressBar) {
        els.progressBar.style.width = `${percent}%`;
    }
}
```

- [ ] **Step 4: 运行测试验证 PASS**

Run: `cd server/internal/web && node --test progress.test.mjs`
Expected: 7 tests PASS。

- [ ] **Step 5: Commit**

```bash
cd server/internal/web
git add progress.js progress.test.mjs
git commit -m "$(cat <<'EOF'
feat(reader): extract progress + chapter detection into progress.js

updateProgressUI and the scroll-mode chapter detection move out of
textReader.js into progress.js. The detection threshold widens from
<=100 to <=120px and becomes a pure testable function
(detectActiveChapterOnScroll) with boundary coverage — fixes the case
where currentIdx failed to update on certain layouts.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: toc.js 目录抽屉（修复 1 hack + 修复 2 双路径）

**Files:**
- Create: `server/internal/web/toc.js`
- Create: `server/internal/web/toc.test.mjs`

**Interfaces:**
- Consumes: `state`、`bus`（on/emit/EVT）。
- Produces: `renderToc({ drawerEl, onNavigate }) → { openDrawer, closeDrawer, toggleDrawer, highlightCurrent, dispose }`。

**背景（修复 1 + 修复 2）**：
- 修复 1：删除 `els.drawer._highlightCurrent` hack；TOC 订阅 `EVT.CHAPTER_CHANGED` 自更新高亮。
- 修复 2：删除模块级 `toggleDrawer` / `onOutsideDrawerClick`；open/close/toggle/外部点击全部收进 toc.js，单一 `drawerEl` 引用，导出 `dispose()` 给主模块 cleanup。

- [ ] **Step 1: 写失败测试**

创建 `server/internal/web/toc.test.mjs`：

```javascript
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setupJsdom, teardownJsdom, mockBook } from './_snapshot-helpers.mjs';
import { state, setCurrentIdx, resetState } from './state.js';
import { renderToc } from './toc.js';

function setupDrawer() {
    const drawer = document.createElement('div');
    drawer.className = 'text-reader__drawer text-reader__drawer--hidden';
    document.body.appendChild(drawer);
    return drawer;
}

test('renderToc builds one button per chapter with active on current', () => {
    setupJsdom();
    try {
        resetState();
        state.book = mockBook;
        state.currentIdx = 1;
        const drawer = setupDrawer();
        const toc = renderToc({ drawerEl: drawer, onNavigate: () => {} });
        const items = drawer.querySelectorAll('.text-reader__drawer-item');
        assert.equal(items.length, 3);
        assert.ok(items[1].classList.contains('text-reader__drawer-item--active'));
        toc.dispose();
    } finally {
        teardownJsdom();
    }
});

test('highlightCurrent moves active class on chapter change', () => {
    setupJsdom();
    try {
        resetState();
        state.book = mockBook;
        state.currentIdx = 0;
        const drawer = setupDrawer();
        const toc = renderToc({ drawerEl: drawer, onNavigate: () => {} });
        setCurrentIdx(2); // 触发 bus，toc 订阅应自更新
        const items = drawer.querySelectorAll('.text-reader__drawer-item');
        assert.ok(!items[0].classList.contains('text-reader__drawer-item--active'));
        assert.ok(items[2].classList.contains('text-reader__drawer-item--active'));
        toc.dispose();
    } finally {
        teardownJsdom();
    }
});

test('openDrawer unhides + registers outside click; closeDrawer cleans up', () => {
    setupJsdom();
    try {
        resetState();
        state.book = mockBook;
        const drawer = setupDrawer();
        const toc = renderToc({ drawerEl: drawer, onNavigate: () => {} });
        toc.openDrawer();
        assert.equal(drawer.classList.contains('text-reader__drawer--hidden'), false);
        toc.closeDrawer();
        assert.equal(drawer.classList.contains('text-reader__drawer--hidden'), true);
        toc.dispose();
    } finally {
        teardownJsdom();
    }
});

test('dispose unsubscribes from bus (no highlight update after dispose)', () => {
    setupJsdom();
    try {
        resetState();
        state.book = mockBook;
        state.currentIdx = 0;
        const drawer = setupDrawer();
        const toc = renderToc({ drawerEl: drawer, onNavigate: () => {} });
        toc.dispose();
        setCurrentIdx(2);
        const items = drawer.querySelectorAll('.text-reader__drawer-item');
        // dispose 后 toc 不再订阅，active 不应移动到 2
        assert.ok(!items[2].classList.contains('text-reader__drawer-item--active'));
    } finally {
        teardownJsdom();
    }
});
```

- [ ] **Step 2: 运行测试验证 FAIL**

Run: `cd server/internal/web && node --test toc.test.mjs`
Expected: FAIL（`Cannot find module './toc.js'`）。

- [ ] **Step 3: 实现 toc.js**

创建 `server/internal/web/toc.js`：

```javascript
// 目录抽屉：渲染 TOC 列表 + 高亮当前章节 + 开关 + 外部点击关闭。
// 修复 1：订阅 EVT.CHAPTER_CHANGED 自更新高亮（替代 _highlightCurrent hack）。
// 修复 2：open/close/toggle/外部点击全在此模块，单一 drawerEl 引用，导出 dispose。
import { state, setCurrentIdx } from './state.js';
import { on, EVT } from './bus.js';

// 渲染 TOC 到 drawerEl，返回控制器。
// onNavigate(idx) 由主模块提供，处理章节跳转（滚动模式 scrollIntoView 或分章模式 loadChapter）。
export function renderToc({ drawerEl, onNavigate }) {
    const unsubs = [];

    function renderList() {
        drawerEl.innerHTML = ''; // XSS-SAFE: clearing
        const tabs = document.createElement('div');
        tabs.className = 'text-reader__tabs';
        tabs.innerHTML = `
            <button class="text-reader__tab text-reader__tab--active" data-tab="toc">目录</button>
            <button class="text-reader__tab" data-tab="bookmarks">书签 (<span data-bm-count>0</span>)</button>
        `; // XSS-SAFE: pure-literal template
        const panel = document.createElement('div');
        panel.className = 'text-reader__tab-panel';
        drawerEl.appendChild(tabs);
        drawerEl.appendChild(panel);

        (state.book?.chapters || []).forEach((ch, i) => {
            const btn = document.createElement('button');
            btn.dataset.chapterIndex = String(i);
            btn.className = 'text-reader__drawer-item' + (i === state.currentIdx ? ' text-reader__drawer-item--active' : '');
            if (i === state.currentIdx) btn.setAttribute('aria-current', 'true');
            btn.textContent = ch.title || `第 ${i + 1} 章`;
            btn.addEventListener('click', () => {
                onNavigate(i);
                closeDrawer();
            });
            panel.appendChild(btn);
        });

        // 书签 tab 占位（bookmarks 模块 Task 5 填充实际内容）
        tabs.querySelector('[data-tab="toc"]')?.addEventListener('click', () => {
            tabs.querySelectorAll('.text-reader__tab').forEach((b) => b.classList.remove('text-reader__tab--active'));
            tabs.querySelector('[data-tab="toc"]')?.classList.add('text-reader__tab--active');
        });

        highlightCurrent();
        return { tabs, panel };
    }

    function highlightCurrent() {
        const items = drawerEl.querySelectorAll('.text-reader__drawer-item');
        items.forEach((el) => {
            const wasActive = el.classList.contains('text-reader__drawer-item--active');
            const shouldBeActive = parseInt(el.dataset.chapterIndex, 10) === state.currentIdx;
            if (shouldBeActive === wasActive) return;
            el.classList.toggle('text-reader__drawer-item--active', shouldBeActive);
            if (shouldBeActive) {
                el.setAttribute('aria-current', 'true');
                el.scrollIntoView({ block: 'nearest' });
            } else {
                el.removeAttribute('aria-current');
            }
        });
    }

    function openDrawer() {
        drawerEl.classList.remove('text-reader__drawer--hidden');
        drawerEl.setAttribute('aria-hidden', 'false');
        highlightCurrent();
        requestAnimationFrame(() => {
            document.addEventListener('click', onOutsideClick, true);
        });
    }

    function closeDrawer() {
        drawerEl.classList.add('text-reader__drawer--hidden');
        drawerEl.setAttribute('aria-hidden', 'true');
        document.removeEventListener('click', onOutsideClick, true);
    }

    function toggleDrawer() {
        if (drawerEl.classList.contains('text-reader__drawer--hidden')) openDrawer();
        else closeDrawer();
    }

    function onOutsideClick(e) {
        if (drawerEl.contains(e.target)) return;
        if (e.target.closest && e.target.closest('.text-reader__toc')) return;
        closeDrawer();
    }

    // 修复 1：订阅章节变化自更新高亮。
    unsubs.push(on(EVT.CHAPTER_CHANGED, () => {
        if (!drawerEl.classList.contains('text-reader__drawer--hidden')) highlightCurrent();
    }));

    renderList();

    return {
        openDrawer,
        closeDrawer,
        toggleDrawer,
        highlightCurrent,
        // 书签模块（Task 5）会调用 setBookmarkCount 更新 tab 标签。
        setBookmarkCount(n) {
            drawerEl.querySelector('[data-bm-count]')?.textContent = String(n);
        },
        dispose() {
            unsubs.forEach((u) => u());
            document.removeEventListener('click', onOutsideClick, true);
            drawerEl.innerHTML = ''; // XSS-SAFE: clearing
        },
    };
}
```

- [ ] **Step 4: 运行测试验证 PASS**

Run: `cd server/internal/web && node --test toc.test.mjs`
Expected: 4 tests PASS。

- [ ] **Step 5: xsscheck lint 验证**

Run: `cd tools/xsscheck && go run . ../../server/internal/web/`
Expected: `OK: no unescaped innerHTML variables`（两个 innerHTML 都有 XSS-SAFE 注释）。

- [ ] **Step 6: Commit**

```bash
cd server/internal/web
git add toc.js toc.test.mjs
git commit -m "$(cat <<'EOF'
feat(reader): extract TOC drawer into toc.js, kill _highlightCurrent hack

TOC rendering, highlight, open/close, and outside-click all live in toc.js
now with a single drawerEl reference. The drawer subscribes to
chapter:changed via the bus to update its own highlight — replacing the
els.drawer._highlightCurrent DOM-property hack. Module-level toggleDrawer
and onOutsideDrawerClick are gone (single close path, dispose() owns the
listener lifecycle).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: bookmarks.js 书签 tab

**Files:**
- Create: `server/internal/web/bookmarks.js`
- Create: `server/internal/web/bookmarks.test.mjs`

**Interfaces:**
- Consumes: `state`、`bus`、`readerPrefs`。
- Produces: `renderBookmarks({ drawerEl, panelEl, onNavigate }) → { refresh, dispose }`。

- [ ] **Step 1: 写失败测试**

创建 `server/internal/web/bookmarks.test.mjs`：

```javascript
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setupJsdom, teardownJsdom, mockBook } from './_snapshot-helpers.mjs';
import { state, resetState } from './state.js';
import { renderBookmarks } from './bookmarks.js';

// 注意：readerPrefs 在 node 环境用 localStorage，_snapshot-helpers.mjs 已 stub localStorage。
// 但 readerPrefs.js 是 ES module，import 时会读全局。测试用 localStorage 预填书签。
function seedBookmarks(path, bms) {
    localStorage.setItem('bookmarks:' + path, JSON.stringify(bms));
}

test('renderBookmarks lists seeded bookmarks with › prefix on current chapter', () => {
    setupJsdom();
    try {
        resetState();
        state.book = mockBook;
        state.path = mockBook.path;
        state.currentIdx = 1;
        seedBookmarks(mockBook.path, [
            { chapterIndex: 0, paragraphIndex: 2, preview: 'A' },
            { chapterIndex: 1, paragraphIndex: 0, preview: 'B' },
        ]);
        const drawer = document.createElement('div');
        const panel = document.createElement('div');
        drawer.appendChild(panel);
        const bm = renderBookmarks({ drawerEl: drawer, panelEl: panel, onNavigate: () => {} });
        bm.refresh();
        const rows = panel.querySelectorAll('.text-reader__drawer-item');
        assert.equal(rows.length, 2);
        // 第二个书签 chapterIndex=1 === currentIdx=1 → 有 › 前缀 + current-chapter class
        assert.ok(rows[1].classList.contains('text-reader__drawer-item--current-chapter'));
        assert.ok(rows[1].querySelector('span').textContent.startsWith('›'));
        bm.dispose();
        localStorage.clear();
    } finally {
        teardownJsdom();
    }
});

test('renderBookmarks empty state', () => {
    setupJsdom();
    try {
        resetState();
        state.book = mockBook;
        state.path = mockBook.path;
        const panel = document.createElement('div');
        const bm = renderBookmarks({ drawerEl: document.createElement('div'), panelEl: panel, onNavigate: () => {} });
        bm.refresh();
        assert.ok(panel.querySelector('.text-reader__empty'));
        bm.dispose();
    } finally {
        teardownJsdom();
    }
});
```

- [ ] **Step 2: 运行测试验证 FAIL**

Run: `cd server/internal/web && node --test bookmarks.test.mjs`
Expected: FAIL（`Cannot find module './bookmarks.js'`）。

- [ ] **Step 3: 实现 bookmarks.js**

创建 `server/internal/web/bookmarks.js`：

```javascript
// 书签 tab：渲染书签列表 + 增删 + 当前章节弱标记。
// 从 textReader.js 原 renderDrawerTabs 的 bookmarks 分支提取。
import { state } from './state.js';
import { on, EVT } from './bus.js';
import * as readerPrefs from './readerPrefs.js';

export function renderBookmarks({ drawerEl, panelEl, onNavigate }) {
    const unsubs = [];

    function refresh() {
        panelEl.innerHTML = ''; // XSS-SAFE: clearing
        const bms = readerPrefs.getBookmarks(state.path);
        drawerEl.querySelector('[data-bm-count]')?.textContent = String(bms.length);
        if (bms.length === 0) {
            panelEl.innerHTML = '<div class="text-reader__empty">暂无书签，悬停段落 + 添加</div>'; // XSS-SAFE: hardcoded literal
            return;
        }
        bms.forEach((bm) => {
            const inCurrent = bm.chapterIndex === state.currentIdx;
            const row = document.createElement('div');
            row.className = 'text-reader__drawer-item' + (inCurrent ? ' text-reader__drawer-item--current-chapter' : '');
            const title = document.createElement('span');
            title.textContent = (inCurrent ? '› ' : '') + `第 ${bm.chapterIndex + 1} 章 · ${bm.preview}`;
            const del = document.createElement('button');
            del.className = 'text-reader__drawer-del';
            del.textContent = '✕';
            del.addEventListener('click', (e) => {
                e.stopPropagation();
                readerPrefs.removeBookmark(bm);
                refresh();
            });
            row.appendChild(title);
            row.appendChild(del);
            row.addEventListener('click', async () => {
                onNavigate(bm.chapterIndex, bm.paragraphIndex);
                // 关闭抽屉由主模块的 onNavigate 内部处理（调用 toc.closeDrawer）
            });
            panelEl.appendChild(row);
        });
    }

    unsubs.push(on(EVT.CHAPTER_CHANGED, () => {
        if (!drawerEl.classList.contains('text-reader__drawer--hidden')) refresh();
    }));

    return {
        refresh,
        dispose() { unsubs.forEach((u) => u()); },
    };
}
```

- [ ] **Step 4: 运行测试验证 PASS**

Run: `cd server/internal/web && node --test bookmarks.test.mjs`
Expected: 2 tests PASS。

- [ ] **Step 5: xsscheck lint**

Run: `cd tools/xsscheck && go run . ../../server/internal/web/`
Expected: `OK`。

- [ ] **Step 6: Commit**

```bash
cd server/internal/web
git add bookmarks.js bookmarks.test.mjs
git commit -m "$(cat <<'EOF'
feat(reader): extract bookmarks tab into bookmarks.js

Bookmark rendering, add/delete, and the current-chapter › marker move
out of textReader.js. Subscribes to chapter:changed to refresh markers
on chapter switch (same bus pattern as TOC).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: autoscroll.js 自动滚动面板

**Files:**
- Create: `server/internal/web/autoscroll.js`

**Interfaces:**
- Consumes: `state`。
- Produces: `renderAutoscroll({ panelEl, playBtn, minusBtn, plusBtn, speedValEl }) → { start, stop, toggle, dispose }`。

**注**：此模块 DOM 交互较重，纯单测价值低；依赖 Task 8 的端到端快照覆盖。只做实现 + lint。

- [ ] **Step 1: 实现 autoscroll.js**

创建 `server/internal/web/autoscroll.js`：

```javascript
// 自动滚动面板：播放/暂停/调速 rAF 循环。
// 从 textReader.js 原自动滚动逻辑提取。
import { state } from './state.js';

export function renderAutoscroll({ panelEl, playBtn, minusBtn, plusBtn, speedValEl }) {
    let rafId = null;
    let running = false;

    function applySpeed() {
        const s = state.settings;
        if (s && speedValEl) speedValEl.textContent = String(s.autoScrollSpeed);
    }

    function loop() {
        if (!running) return;
        const s = state.settings;
        if (s && state.els && state.els.content) {
            const pxPerFrame = (s.autoScrollSpeed || 0) * 0.5;
            state.els.content.scrollTop += pxPerFrame;
        }
        rafId = requestAnimationFrame(loop);
    }

    function start() {
        if (running) return;
        running = true;
        if (panelEl) panelEl.classList.remove('text-reader__autoscroll-panel--hidden');
        if (playBtn) playBtn.textContent = '⏸';
        rafId = requestAnimationFrame(loop);
    }

    function stop() {
        running = false;
        if (rafId !== null) cancelAnimationFrame(rafId);
        rafId = null;
        if (panelEl) panelEl.classList.add('text-reader__autoscroll-panel--hidden');
        if (playBtn) playBtn.textContent = '▶';
    }

    function toggle() {
        running ? stop() : start();
    }

    if (playBtn) playBtn.addEventListener('click', toggle);
    if (minusBtn) minusBtn.addEventListener('click', () => adjustSpeed(-1));
    if (plusBtn) plusBtn.addEventListener('click', () => adjustSpeed(1));

    function adjustSpeed(delta) {
        const s = state.settings;
        if (!s) return;
        s.autoScrollSpeed = Math.max(1, (s.autoScrollSpeed || 5) + delta);
        applySpeed();
    }

    return { start, stop, toggle, applySpeed, dispose: stop };
}
```

- [ ] **Step 2: xsscheck lint**

Run: `cd tools/xsscheck && go run . ../../server/internal/web/`
Expected: `OK`（autoscroll.js 无 DOM 写入 sink）。

- [ ] **Step 3: 运行全量测试确认无回归**

Run: `cd server/internal/web && node --test`
Expected: 所有已有测试 PASS（autoscroll 无单测，但不影响其余）。

- [ ] **Step 4: Commit**

```bash
cd server/internal/web
git add autoscroll.js
git commit -m "$(cat <<'EOF'
feat(reader): extract autoscroll panel into autoscroll.js

rAF scroll loop, play/pause/speed controls move out of textReader.js.
No unit tests yet — DOM-heavy; covered by end-to-end snapshot in Task 8.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: settings.js 阅读设置 dialog

**Files:**
- Create: `server/internal/web/settings.js`

**Interfaces:**
- Consumes: `state`、`readerPrefs`、`bus`（emit SETTINGS_CHANGED）。
- Produces: `renderSettings(container) → { open, dispose }`。

**注**：dialog 构建代码量大（主题/字号/行距/字族/宽度/沉浸/滚动模式），从 textReader.js 原设置 dialog 提取。DOM 交互重，依赖 Task 8 快照。

- [ ] **Step 1: 读取现有 settings dialog 代码**

执行 agent：先 `grep -n "reader-settings-dialog\|reader-settings__" server/internal/web/textReader.js` 找到 dialog 构建代码范围（约 250 行），完整 Read 该区域，作为 settings.js 实现的迁移源。

- [ ] **Step 2: 实现 settings.js**

创建 `server/internal/web/settings.js`，把现有 dialog 构建代码搬入，做以下调整：
- 函数签名改为 `export function renderSettings(container) { ... }`。
- 用 `state.settings` 替代直接调 `readerPrefs.getSettings()`。
- 设置变更后 `emit(EVT.SETTINGS_CHANGED, { settings })`。
- 返回 `{ open, dispose }`——`open` 调 `dialog.showModal()`，`dispose` 移除 dialog。
- 所有 `innerHTML` 模板（纯字面量 enum）保留 `// XSS-SAFE:` 注释。

由于现有 dialog 代码量大且包含多个 enum map（FONT_FAMILIES、THEME_PRESETS 等），执行 agent 必须**逐段搬运**而非重写，保证行为不变。每个 `innerHTML =` 模板后加 `// XSS-SAFE: pure-literal template, no interpolation`（若已无则补）。

- [ ] **Step 3: xsscheck lint**

Run: `cd tools/xsscheck && go run . ../../server/internal/web/`
Expected: `OK`。若报错，给报错的 sink 加 `// XSS-SAFE:` 注释或改用 `textContent`。

- [ ] **Step 4: 运行全量测试**

Run: `cd server/internal/web && node --test`
Expected: 所有测试 PASS。

- [ ] **Step 5: Commit**

```bash
cd server/internal/web
git add settings.js
git commit -m "$(cat <<'EOF'
feat(reader): extract settings dialog into settings.js

Theme/font/line-height/content-width/immersive/scroll-mode settings
dialog moves out of textReader.js. Changes now emit settings:changed on
the bus so other modules can react. XSS-SAFE comments preserved on all
innerHTML sinks.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: textReader.js 瘦身为主模块 + 端到端快照

**Files:**
- Modify: `server/internal/web/textReader.js`
- Modify: `server/internal/web/snapshot-baseline.test.mjs`（补全端到端快照）

**Interfaces:**
- Consumes: 所有子模块（bus/state/progress/toc/bookmarks/autoscroll/settings）+ 现有 api/toast/readerPrefs。
- Produces: 瘦身后的 `renderTextReader(container, path, chapterParam, paraParam)`（签名不变）。

**背景**：把 textReader.js 从 1160 行瘦到 ~350 行，只保留：编排、生命周期 cleanup、loadChapter、翻章手势。所有子模块通过 import 装配。

- [ ] **Step 1: 补全端到端快照基线（迁移前）**

修改 `server/internal/web/snapshot-baseline.test.mjs`，加入用真实 `renderTextReader` 的快照测试。需 mock `api.js` 的 `getBookInfo`/`getBookChapter`：

```javascript
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setupJsdom, teardownJsdom, mockBook } from './_snapshot-helpers.mjs';

// mock api.js 的 fetch 包装（通过 global.fetch 或 mock 模块）
// 注：textReader.js import { getBookInfo } from './api.js'，
// api.js 内部用 fetch。测试 stub global.fetch 返回 mockBook。
function mockFetch() {
    global.fetch = async (url) => {
        if (url.includes('/books/info')) {
            return { ok: true, json: async () => mockBook };
        }
        if (url.includes('/books/chapter')) {
            return { ok: true, json: async () => ({ title: '第一章 开端', blocks: [{ type: 'text', text: '正文内容' }] }) };
        }
        return { ok: false, status: 404 };
    };
}

test('e2e baseline: render shows chapter 1 active + title', async () => {
    setupJsdom();
    mockFetch();
    try {
        const { renderTextReader } = await import('./textReader.js');
        const container = document.getElementById('view-reader');
        await renderTextReader(container, mockBook.path, 0);
        // 等待 async render 完成（fetch + DOM 更新）
        await new Promise((r) => setTimeout(r, 50));
        const title = container.querySelector('.text-reader__title')?.textContent ?? '';
        const progress = container.querySelector('.text-reader__progress')?.textContent ?? '';
        assert.ok(title.includes('第一章'), `title was: ${title}`);
        assert.ok(progress.includes('1 / 3'), `progress was: ${progress}`);
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});
```

- [ ] **Step 2: 运行基线快照（迁移前）验证它 PASS**

Run: `cd server/internal/web && node --test snapshot-baseline.test.mjs`
Expected: PASS（当前 textReader.js 仍完整，render 正常）。

**这是关键基线**——Task 8 瘦身后此测试必须仍 PASS，证明行为不变。

- [ ] **Step 3: 瘦身 textReader.js**

重写 `server/internal/web/textReader.js`，保留：
- import 各子模块
- `renderTextReader` 主函数：resetState → fetch book → 填充 state → renderToc + renderBookmarks + renderAutoscroll + renderSettings → loadChapter(startIdx)
- `loadChapter`：更新 state.setCurrentIdx + 调 progress.updateProgressUI
- 内容区翻章手势（左 20%/右 20%）
- cleanup：收集所有 dispose/unsub

删除：
- `renderDrawerTabs`（已迁 toc + bookmarks）
- `updateProgressUI`（已迁 progress）
- `onContentScroll` 内的进度/章节推断（改调 progress.detectActiveChapterOnScroll + updateProgressUI）
- 自动滚动逻辑（已迁 autoscroll）
- 设置 dialog（已迁 settings）
- 模块级 `toggleDrawer` / `onOutsideDrawerClick`（已迁 toc）

执行 agent：由于 textReader.js 当前 1160 行，瘦身是本 plan 最大的一步。建议先完整 Read textReader.js，逐区域确认"这段迁到哪个模块"，再做替换。瘦身后行数目标 ~350。

- [ ] **Step 4: 运行端到端快照（迁移后）验证 diff 为空**

Run: `cd server/internal/web && node --test snapshot-baseline.test.mjs`
Expected: PASS（与迁移前行为一致）。若 FAIL，说明瘦身后行为有偏差——逐项排查（通常是 loadChapter 没正确调 setCurrentIdx 或 progress.updateProgressUI）。

- [ ] **Step 5: 运行全量测试**

Run: `cd server/internal/web && node --test`
Expected: 所有测试 PASS。

- [ ] **Step 6: xsscheck lint**

Run: `cd tools/xsscheck && go run . ../../server/internal/web/`
Expected: `OK`。

- [ ] **Step 7: 检查 textReader.js 行数**

Run: `wc -l server/internal/web/textReader.js`
Expected: < 400 行（目标 ~350）。若仍 > 500，说明有逻辑没迁干净，回查。

- [ ] **Step 8: 重新编译 server（embed web 资源）**

Run: `cd server && rm -f LocalMediaHub.exe && go build -o LocalMediaHub.exe ./cmd/server && ls -la LocalMediaHub.exe`
Expected: 编译成功。

- [ ] **Step 9: Commit**

```bash
cd server/internal/web
git add textReader.js snapshot-baseline.test.mjs
cd ../..
git add server/LocalMediaHub.exe
git commit -m "$(cat <<'EOF'
refactor(reader): slim textReader.js to orchestration module (~350 lines)

textReader.js now only owns render orchestration, loadChapter, page-turn
gestures, and cleanup. All sub-features live in their own modules
(bus/state/progress/toc/bookmarks/autoscroll/settings) wired via the
event bus. End-to-end snapshot confirms zero behavior change vs the
pre-split baseline.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

**1. Spec coverage：**

| Spec 要求 | 对应 Task |
|---|---|
| bus.js 事件总线 | Task 1 |
| state.js 单例 + setCurrentIdx | Task 2 |
| progress.js（含阈值修复 3） | Task 3 |
| toc.js（修复 1 hack + 修复 2 双路径） | Task 4 |
| bookmarks.js | Task 5 |
| autoscroll.js | Task 6 |
| settings.js | Task 7 |
| textReader.js 瘦身 + 编排 | Task 8 |
| 测试基础设施（jsdom + 快照基线） | Task 0 |
| 每步 xsscheck lint | Task 1/4/5/6/7/8 各有 lint step |
| 行为零回归（快照 diff） | Task 0 基线 + Task 8 验证 |
| 3 处粗糙处修复 | 修复1: Task 4；修复2: Task 4；修复3: Task 3 |

**2. Placeholder scan：** Task 7 Step 2 说"逐段搬运现有 dialog 代码"——这是必要的（dialog 代码量大且含 enum maps），不是 placeholder；执行 agent 被明确告知先 Read 现有代码。其余步骤均有完整代码块。

**3. Type/signature consistency：**
- `renderToc({ drawerEl, onNavigate })` — Task 4 定义，Task 8 主模块调用一致。
- `renderBookmarks({ drawerEl, panelEl, onNavigate })` — Task 5 定义，Task 8 调用一致。
- `setCurrentIdx(idx)` — Task 2 定义，Task 3/4/8 使用一致。
- `detectActiveChapterOnScroll(sections, containerTop, fallbackIdx)` — Task 3 定义与测试一致。
- `EVT.CHAPTER_CHANGED` — Task 1 定义，Task 2/4/5 使用一致。
- `onNavigate(idx)` / `onNavigate(chapterIndex, paragraphIndex)` — Task 4 的 onNavigate 是单参数 idx，Task 5 的 onNavigate 是 (chapterIndex, paragraphIndex)。**不一致**——已识别：主模块（Task 8）提供统一的 onNavigate，TOC 调 `onNavigate(idx)`，bookmarks 调 `onNavigate(chapterIndex, paragraphIndex)`。主模块的 onNavigate 签名需接受两参（第二参 optional）。Task 8 Step 3 已隐含此约定（主模块协调两者）。执行 agent 需注意。

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-07-23-textreader-modularization.md`.**

Two execution options:

**1. Subagent-Driven (recommended)** — 每个 Task 派发 fresh subagent + 两阶段评审。适合本 plan——9 个 Task 独立性强，Task 8 瘦身最复杂需隔离上下文。

**2. Inline Execution** — 当前会话用 executing-plans skill 批量执行，checkpoint 评审。

Which approach?
