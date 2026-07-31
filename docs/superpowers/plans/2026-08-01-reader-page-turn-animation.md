# 阅读器翻页方式与动画实施计划（COVER / SIMULATION / DRAG）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为双端阅读器分章模式（CHAPTER）增加真正的章级翻页动画——COVER（覆盖/滑动）、SIMULATION（仿真卷曲）、DRAG（拖动跟手）三种样式 + NONE（无动画），通过新增 `pageTurnStyle` 设置字段控制。

**Architecture:** 章级翻页（不造分页引擎）。把"切章 + 动画"抽成翻页控制器（Android `PageTurnController` / Web `pageTurn.js`），CHAPTER 模式内容区不再直接 `loadChapter` + fade，而是经控制器发起带方向的翻页请求。手势判定逻辑抽为纯函数先行单测。三种动画共享"双层叠放 + 进度驱动 transform"基础；SIMULATION 在此之上加 clipPath 卷曲。

**Tech Stack:** Web — ES modules + node:test/jsdom + CSS transform/clip-path（无构建、无新依赖）；Android — Compose + Robolectric + `Animatable`/`graphicsLayer`/`Canvas`（无新 Gradle 依赖）。

## Global Constraints

- 双端字段一致：`pageTurnStyle` 枚举 `NONE`/`COVER`/`SIMULATION`/`DRAG`，默认 `NONE`。
- 仅 CHAPTER 模式生效；SCROLL 模式忽略且设置项置灰（不清空字段值）。
- 翻页方向：下一章 = 新页从右进入、旧页向左退出；上一章反向。
- 走翻页动画的入口：左右 20% 点击热区、上一章/下一章按钮、章末 ❖。**不走**翻页动画：目录跳转、书签跳转（瞬时 + fade）。
- DRAG 与垂直滚动共存：水平位移 > 垂直位移 且 > 8dp 触摸阈值才接管为翻页；松手阈值 25% 屏宽。
- Web 端 `prefers-reduced-motion` 开启时 COVER/SIMULATION 降级为 NONE（fade）；DRAG 不降级。
- 不引入任何新依赖（Web 无新 npm 包；Android 无新 Gradle 依赖）。
- 不改 7 个既有主题预设与现有排版字段（letterSpacing/customBg 等）。
- TDD：每任务先写失败测试再实现。
- 提交信息风格：`feat(web): ...` / `feat(android): ...`。
- 测试命令：Web `cd server/internal/web && npm test`（或单文件 `node --test <file>`）；Android `cd android && powershell -Command ".\gradlew.bat :app:testDebugUnitTest --tests '<FQCN>' 2>&1 | Select-Object -Last 12"`。

---

### Task 1: Web 数据层 — readerPrefs.js 加 pageTurnStyle

**Files:**
- Modify: `server/internal/web/readerPrefs.js`（DEFAULT_SETTINGS、migrateV1toV2）
- Create: `server/internal/web/pageTurnStyle.test.mjs`（或并入现有 readerPrefs 测试模式）

**Interfaces:**
- Produces: `DEFAULT_SETTINGS.pageTurnStyle = 'NONE'`；`migrateV1toV2` 接受 `NONE`/`COVER`/`SIMULATION`/`DRAG`，非法值→`NONE`。Task 2/3 依赖此字段。

- [ ] **Step 1: 写失败测试**

新建 `server/internal/web/pageTurnStyle.test.mjs`（migrateV1toV2 是纯函数，无需 jsdom）：

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { migrateV1toV2 } from './readerPrefs.js';

const VALID = ['NONE', 'COVER', 'SIMULATION', 'DRAG'];

test('default pageTurnStyle is NONE', () => {
    assert.equal(migrateV1toV2(null).pageTurnStyle, 'NONE');
});

test('migrate keeps valid pageTurnStyle values', () => {
    for (const v of VALID) {
        assert.equal(migrateV1toV2({ pageTurnStyle: v }).pageTurnStyle, v);
    }
});

test('migrate drops invalid pageTurnStyle to NONE', () => {
    assert.equal(migrateV1toV2({ pageTurnStyle: 'BOGUS' }).pageTurnStyle, 'NONE');
    assert.equal(migrateV1toV2({ pageTurnStyle: 123 }).pageTurnStyle, 'NONE');
});

test('migrate preserves pageTurnStyle when other fields present', () => {
    const s = migrateV1toV2({ pageTurnStyle: 'DRAG', theme: 'NIGHT' });
    assert.equal(s.pageTurnStyle, 'DRAG');
    assert.equal(s.theme, 'NIGHT');
});
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd server/internal/web && node --test pageTurnStyle.test.mjs`
Expected: 4 个测试 FAIL（`pageTurnStyle` 为 undefined）

- [ ] **Step 3: 实现**

修改 `readerPrefs.js`：

(1) `DEFAULT_SETTINGS` 末尾加：

```js
    pageTurnStyle: 'NONE', // NONE/COVER/SIMULATION/DRAG，仅 chapter 模式生效
```

(2) `migrateV1toV2` 末尾（return 前）加：

```js
    const PAGE_TURN_STYLES = ['NONE', 'COVER', 'SIMULATION', 'DRAG'];
    if (typeof old.pageTurnStyle === 'string' && PAGE_TURN_STYLES.includes(old.pageTurnStyle)) {
        out.pageTurnStyle = old.pageTurnStyle;
    }
```

（`PAGE_TURN_STYLES` 常量提为模块级常量，与 `FONT_FAMILIES` 等同级，避免每次迁移重建。）

- [ ] **Step 4: 运行测试确认通过**

Run: `cd server/internal/web && node --test pageTurnStyle.test.mjs`
Expected: 4 PASS

- [ ] **Step 5: 回归 + 提交**

Run: `cd server/internal/web && npm test`
Expected: 全部 PASS（含既有 47 测试 + 4 新增）

```bash
git add server/internal/web/readerPrefs.js server/internal/web/pageTurnStyle.test.mjs
git commit -m "feat(web): add pageTurnStyle field to readerPrefs (NONE/COVER/SIMULATION/DRAG)"
```

---

### Task 2: Web 设置面板 — reader-settings.js 加翻页动画选择器

**Files:**
- Modify: `server/internal/web/reader-settings.js`（模板、syncControlsFromSettings、onChange）
- Modify: `server/internal/web/pageTurnStyle.test.mjs`（扩为也测设置面板，或并入；保持一个文件即可）
- Modify: `server/internal/web/style.css`（翻页 radio 行样式沿用现有 radio-row，通常无需新增）

**Interfaces:**
- Consumes: Task 1 的 `DEFAULT_SETTINGS.pageTurnStyle`。
- Produces: 设置 dialog 内 `input[name="pageTurnStyle"]`（4 个 radio，值 NONE/COVER/SIMULATION/DRAG）；CHAPTER 模式可交互、SCROLL 模式置灰。`saveAndEmit({ pageTurnStyle })` 走现有 else 分支。

- [ ] **Step 1: 写失败测试**

`pageTurnStyle.test.mjs` 末尾加（jsdom 模式，仿 reader-settings.test.mjs）：

```js
import { setupJsdom, teardownJsdom } from './_snapshot-helpers.mjs';
import { state, resetState } from './reader-state.js';
import { renderSettings } from './reader-settings.js';
import { DEFAULT_SETTINGS } from './readerPrefs.js';

function mountDialog(settings) {
    resetState();
    state.settings = { ...DEFAULT_SETTINGS, ...settings };
    const container = document.createElement('div');
    document.body.appendChild(container);
    const api = renderSettings(container);
    return { container, api, dialog: container.querySelector('#reader-settings-dialog') };
}

test('dialog renders 4 pageTurnStyle radios, CHAPTER-enabled', () => {
    setupJsdom();
    try {
        const { api, dialog } = mountDialog({ readingMode: 'chapter' });
        for (const v of ['NONE', 'COVER', 'SIMULATION', 'DRAG']) {
            const r = dialog.querySelector(`input[name="pageTurnStyle"][value="${v}"]`);
            assert.ok(r, `missing radio for ${v}`);
            assert.equal(r.disabled, false, `${v} should be enabled in chapter mode`);
        }
        api.dispose();
    } finally {
        teardownJsdom();
    }
});

test('pageTurnStyle radios disabled in scroll mode', () => {
    setupJsdom();
    try {
        const { api, dialog } = mountDialog({ readingMode: 'scroll' });
        for (const v of ['NONE', 'COVER', 'SIMULATION', 'DRAG']) {
            const r = dialog.querySelector(`input[name="pageTurnStyle"][value="${v}"]`);
            assert.ok(r.disabled, `${v} should be disabled in scroll mode`);
        }
        api.dispose();
    } finally {
        teardownJsdom();
    }
});

test('selecting COVER radio saves pageTurnStyle', () => {
    setupJsdom();
    try {
        const { api, dialog } = mountDialog({ readingMode: 'chapter' });
        const radio = dialog.querySelector('input[name="pageTurnStyle"][value="COVER"]');
        radio.checked = true;
        radio.dispatchEvent(new Event('change', { bubbles: true }));
        const saved = JSON.parse(localStorage.getItem('reader_settings'));
        assert.equal(saved.pageTurnStyle, 'COVER');
        api.dispose();
    } finally {
        teardownJsdom();
    }
});
```

注意：`new Event(...)` 在 jsdom 下若 readerPrefs 的 saveSettings 用 `window.dispatchEvent(new CustomEvent(...))`，需参照 reader-settings.test.mjs 的 Event/CustomEvent polyfill setup/teardown（直接复制其 setup() 里的 polyfill 块）。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd server/internal/web && node --test pageTurnStyle.test.mjs`
Expected: 3 个新测试 FAIL（radio 不存在）

- [ ] **Step 3: 实现**

修改 `reader-settings.js`：

(1) 顶部加常量（与 FONT_OPTIONS/THEME_OPTIONS 同级）：

```js
const PAGE_TURN_OPTIONS = [
    ['NONE', '无'],
    ['COVER', '覆盖'],
    ['SIMULATION', '仿真'],
    ['DRAG', '拖动'],
];
```

(2) dialog 模板「行为」section（readingMode radio 之后、immersiveMode 之前）加：

```js
                    <div class="reader-settings__row" style="margin-bottom: 8px;">
                        <span>翻页动画</span>
                        <div class="reader-settings__font-row" id="pageTurnRow">
                            ${PAGE_TURN_OPTIONS.map(([v, label]) =>
                                `<label><input type="radio" name="pageTurnStyle" value="${v}"> ${label}</label>`
                            ).join('')}
                        </div>
                    </div>
```

(3) `syncControlsFromSettings` 末尾加：

```js
        setRadio('pageTurnStyle', s.pageTurnStyle);
        const isScroll = s.readingMode === 'scroll';
        dialog.querySelectorAll('input[name="pageTurnStyle"]').forEach((r) => { r.disabled = isScroll; });
```

（`setRadio` 是文件内已有的辅助函数。）

(4) `onChange` 中 `readingMode` 分支（现有）扩展，切换模式时同步启禁翻页 radio：

```js
        } else if (t.name === 'readingMode') {
            saveAndEmit({ readingMode: t.value });
            const isScroll = t.value === 'scroll';
            dialog.querySelectorAll('input[name="pageTurnStyle"]').forEach((r) => { r.disabled = isScroll; });
```

（`pageTurnStyle` radio 变更走现有 else 分支 `saveAndEmit({ [t.name]: t.value })`，无需新分支。）

- [ ] **Step 4: 运行测试确认通过**

Run: `cd server/internal/web && node --test pageTurnStyle.test.mjs`
Expected: 全部 PASS

- [ ] **Step 5: 回归 + 提交**

Run: `cd server/internal/web && npm test`
Expected: 全绿

```bash
git add server/internal/web/reader-settings.js server/internal/web/pageTurnStyle.test.mjs
git commit -m "feat(web): add page-turn style selector to reader settings (disabled in scroll mode)"
```

---

### Task 3: Web 翻页控制器 — pageTurn.js（COVER + DRAG）

**Files:**
- Create: `server/internal/web/pageTurn.js`
- Create: `server/internal/web/pageTurn.test.mjs`
- Modify: `server/internal/web/style.css`（翻页层样式）

**Interfaces:**
- Produces: `renderPageTurn({ contentEl, getStyle, loadChapterSection, getCurrentIdx, getChapterCount })` 返回 `{ turnTo(direction), dispose }`。
  - `direction`: `'next' | 'prev'`
  - `getStyle()`: 返回当前 `pageTurnStyle`（`'NONE'`/`'COVER'`/`'SIMULATION'`/`'DRAG'`）
  - `loadChapterSection(idx)`: async，返回一个 `<section>` 元素（调用方提供，复用现有 renderBlocks）
  - `turnTo(direction)`: async，根据 style 驱动动画并替换 contentEl 内容；NONE 时直接替换无动画。
- Task 4 接入 textReader.js；Task 5 扩展 SIMULATION。

- [ ] **Step 1: 写失败测试**

新建 `server/internal/web/pageTurn.test.mjs`：

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setupJsdom, teardownJsdom } from './_snapshot-helpers.mjs';
import { renderPageTurn } from './pageTurn.js';

function makeSection(idx, text) {
    const s = document.createElement('section');
    s.className = 'text-reader__chapter-section';
    s.dataset.chapterIndex = String(idx);
    s.textContent = text;
    return s;
}

function setup(initialIdx = 0, count = 3, style = 'NONE') {
    setupJsdom();
    // pageTurn.js uses matchMedia for prefers-reduced-motion; stub it (no reduction).
    window.matchMedia = window.matchMedia || (() => ({
        matches: false, addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {},
    }));
    const contentEl = document.createElement('div');
    contentEl.appendChild(makeSection(initialIdx, `chapter ${initialIdx}`));
    let currentIdx = initialIdx;
    const api = renderPageTurn({
        contentEl,
        getStyle: () => style,
        loadChapterSection: async (idx) => {
            currentIdx = idx;
            return makeSection(idx, `chapter ${idx}`);
        },
        getCurrentIdx: () => currentIdx,
        getChapterCount: () => count,
    });
    return { contentEl, api };
}

test('turnTo(next) in NONE swaps content immediately and returns true', async () => {
    const { contentEl, api } = setup(0, 3, 'NONE');
    const ok = await api.turnTo('next');
    assert.equal(ok, true);
    assert.equal(contentEl.querySelector('.text-reader__chapter-section').dataset.chapterIndex, '1');
    api.dispose();
    teardownJsdom();
});

test('turnTo(next) at last chapter returns false and no-ops', async () => {
    const { contentEl, api } = setup(2, 3, 'NONE');
    const ok = await api.turnTo('next');
    assert.equal(ok, false);
    assert.equal(contentEl.querySelector('.text-reader__chapter-section').dataset.chapterIndex, '2');
    api.dispose();
    teardownJsdom();
});

test('turnTo(prev) at first chapter returns false', async () => {
    const { contentEl, api } = setup(0, 3, 'NONE');
    const ok = await api.turnTo('prev');
    assert.equal(ok, false);
    api.dispose();
    teardownJsdom();
});

test('COVER turnTo(next) ends with new section visible', async () => {
    const { contentEl, api } = setup(0, 3, 'COVER');
    // CSS transitions won't actually animate in jsdom; pageTurn.js must invoke the
    // transitionend-or-fallback. The contract: after turnTo resolves, the new
    // section is the one in the DOM.
    await api.turnTo('next');
    assert.equal(contentEl.querySelector('.text-reader__chapter-section').dataset.chapterIndex, '1');
    api.dispose();
    teardownJsdom();
});

test('invalid direction returns false and no-ops', async () => {
    const { contentEl, api } = setup(0, 3, 'NONE');
    const ok = await api.turnTo('sideways');
    assert.equal(ok, false);
    assert.equal(contentEl.querySelector('.text-reader__chapter-section').dataset.chapterIndex, '0');
    api.dispose();
    teardownJsdom();
});
```

注意：jsdom 不真实驱动 CSS transition，所以测试只断言"turnTo resolve 后 DOM 反映新章"——动画的真实视觉验证留给手动/集成测试。pageTurn.js 内部对 transitionend 必须有超时回退（避免 jsdom 下永不 resolve）。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd server/internal/web && node --test pageTurn.test.mjs`
Expected: FAIL（`pageTurn.js` 不存在）

- [ ] **Step 3: 实现 pageTurn.js**

```js
// Page-turn controller for CHAPTER mode. Owns the animation layer over
// contentEl: on turnTo(direction) it loads the target chapter section via
// loadChapterSection(), then animates the swap per getStyle(). NONE swaps
// instantly. SIMULATION is added in Task 5 (this module falls through to
// COVER if SIMULATION not yet implemented). prefers-reduced-motion degrades
// COVER/SIMULATION to NONE.
const ANIM_MS = { COVER: 280, SIMULATION: 400, DRAG: 280 };
const DRAG_THRESHOLD = 0.25; // 屏宽比例

function prefersReducedMotion() {
    try {
        return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    } catch (_) {
        return false;
    }
}

export function renderPageTurn({ contentEl, getStyle, loadChapterSection, getCurrentIdx, getChapterCount }) {
    let busy = false;

    function resolveStyle() {
        const s = getStyle();
        if (s === 'NONE') return 'NONE';
        if (prefersReducedMotion() && s !== 'DRAG') return 'NONE';
        return s;
    }

    async function swapInstant(section) {
        contentEl.innerHTML = ''; // XSS-SAFE: clearing
        contentEl.appendChild(section);
    }

    // COVER: layer old + new, translate, then settle. Uses a transitionend
    // listener with a setTimeout fallback (jsdom won't fire transitionend).
    function animateCover(oldSection, newSection, direction) {
        return new Promise((resolve) => {
            const sign = direction === 'next' ? 1 : -1;
            newSection.classList.add('text-reader__page--incoming');
            newSection.style.transform = `translateX(${sign * 100}%)`;
            contentEl.appendChild(newSection);
            // force reflow so the initial transform sticks before transitioning
            void contentEl.offsetWidth;
            newSection.style.transition = `transform ${ANIM_MS.COVER}ms ease-out`;
            oldSection.style.transition = `transform ${ANIM_MS.COVER}ms ease-out, opacity ${ANIM_MS.COVER}ms ease-out`;
            newSection.style.transform = 'translateX(0)';
            oldSection.style.transform = `translateX(${-sign * 100}%)`;
            oldSection.style.opacity = '0';
            let done = false;
            const finish = () => {
                if (done) return;
                done = true;
                contentEl.removeChild(oldSection);
                newSection.classList.remove('text-reader__page--incoming');
                newSection.style.transition = '';
                newSection.style.transform = '';
                resolve();
            };
            newSection.addEventListener('transitionend', finish, { once: true });
            setTimeout(finish, ANIM_MS.COVER + 60); // fallback
        });
    }

    async function turnTo(direction) {
        if (busy) return false;
        if (direction !== 'next' && direction !== 'prev') return false;
        const idx = getCurrentIdx();
        const count = getChapterCount();
        const target = direction === 'next' ? idx + 1 : idx - 1;
        if (target < 0 || target >= count) return false;
        busy = true;
        try {
            const style = resolveStyle();
            const newSection = await loadChapterSection(target);
            const oldSection = contentEl.querySelector('.text-reader__chapter-section');
            if (style === 'NONE' || !oldSection) {
                await swapInstant(newSection);
            } else if (style === 'COVER') {
                await animateCover(oldSection, newSection, direction);
            } else {
                // SIMULATION/DRAG fall back to COVER until those land (Task 5 / DRAG gesture).
                await animateCover(oldSection, newSection, direction);
            }
            return true;
        } finally {
            busy = false;
        }
    }

    function dispose() {
        // DRAG pointer listeners would detach here (Task: when DRAG gesture added).
    }

    return { turnTo, dispose };
}
```

修改 `style.css`，在 `.text-reader__content` 规则附近加翻页层基础样式：

```css
/* 翻页动画层：新旧章叠放，新章 absolute 定位覆盖。
   注意：absolute 需要最近的 positioned 祖先 —— contentEl 必须 position: relative。 */
.text-reader__content {
    position: relative;
}
.text-reader__content .text-reader__page--incoming {
    position: absolute;
    inset: 0;
    will-change: transform;
}
```

（`position: relative` 对现有布局无影响，仅使 `.text-reader__page--incoming` 的 absolute 以内容区为定位基准。若 `.text-reader__content` 规则中已有 `position` 声明，改为在该规则内追加 `position: relative`。）

- [ ] **Step 4: 运行测试确认通过**

Run: `cd server/internal/web && node --test pageTurn.test.mjs`
Expected: 5 PASS

- [ ] **Step 5: 回归 + 提交**

Run: `cd server/internal/web && npm test`
Expected: 全绿

```bash
git add server/internal/web/pageTurn.js server/internal/web/pageTurn.test.mjs server/internal/web/style.css
git commit -m "feat(web): add pageTurn controller module (NONE/COVER, reduced-motion fallback)"
```

---

### Task 4: Web 接入 — textReader.js CHAPTER 模式走翻页控制器

**Files:**
- Modify: `server/internal/web/textReader.js`（接入 pageTurnApi，改 prev/next/热区/❖ 入口）
- Modify: `server/internal/web/pageTurn.test.mjs`（加集成断言）或新建集成测试

**Interfaces:**
- Consumes: Task 3 的 `renderPageTurn`。
- Produces: CHAPTER 模式下 prev/next/热区/❖ 统一调 `pageTurnApi.turnTo`；目录/书签跳转仍走 `loadChapter`（瞬时 + fade）。

- [ ] **Step 1: 写失败测试**

(1) `pageTurn.test.mjs` 末尾加一个单元断言（验证 turnTo 调用了 loadChapterSection 且 target 正确）：

```js
test('turnTo(next) requests the next chapter index via loadChapterSection', async () => {
    setupJsdom();
    window.matchMedia = window.matchMedia || (() => ({
        matches: false, addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {},
    }));
    const contentEl = document.createElement('div');
    contentEl.appendChild(makeSection(1, 'one'));
    let requestedIdx = null;
    const api = renderPageTurn({
        contentEl,
        getStyle: () => 'NONE',
        loadChapterSection: async (idx) => { requestedIdx = idx; return makeSection(idx, `ch${idx}`); },
        getCurrentIdx: () => 1,
        getChapterCount: () => 5,
    });
    await api.turnTo('next');
    assert.equal(requestedIdx, 2);
    api.dispose();
    teardownJsdom();
});
```

(2) 新建 `textReader-pageturn.test.mjs`——**集成测试**（spec 测试计划要求："CHAPTER 模式点右热区 → 调 turnTo('next')"）。仿 snapshot-baseline.test.mjs 的 mock fetch + 环境 stub 模式，完整跑 `renderTextReader`，模拟点击内容区右 80% 热区，断言章节前进到下一章（title/progress 更新）。此测试在 Task 4 Step 3 接入后才通过：

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setupJsdom, teardownJsdom, mockBook } from './_snapshot-helpers.mjs';

function installEnv() {
    global.sessionStorage = global.sessionStorage || {
        _s: {}, getItem(k) { return k in this._s ? this._s[k] : null; },
        setItem(k, v) { this._s[k] = String(v); }, removeItem(k) { delete this._s[k]; },
    };
    window.matchMedia = window.matchMedia || (() => ({
        matches: false, addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {},
    }));
    global.fetch = async (url) => {
        if (url.includes('/books/info')) {
            return { ok: true, status: 200, json: async () => mockBook };
        }
        if (url.includes('/books/chapter')) {
            return {
                ok: true, status: 200,
                json: async () => ({ title: '第一章 开端', blocks: [{ type: 'text', value: '正文内容' }] }),
            };
        }
        return { ok: false, status: 404 };
    };
}

function viewContainer() {
    let el = document.getElementById('view-reader');
    if (!el) { el = document.createElement('div'); el.id = 'view-reader'; document.body.appendChild(el); }
    return el;
}

// CHAPTER 模式（默认 NONE）下点击右 80% 热区 → 章节前进（走 pageTurn 路径）。
test('chapter mode: click right hotzone advances to next chapter', async () => {
    setupJsdom();
    try {
        installEnv();
        localStorage.setItem('reader_settings', JSON.stringify({ readingMode: 'chapter', pageTurnStyle: 'NONE' }));
        const { renderTextReader } = await import('./textReader.js');
        await renderTextReader(viewContainer(), mockBook.path, 0);
        await new Promise((r) => setTimeout(r, 50));
        const content = viewContainer().querySelector('.text-reader__content');
        // jsdom 无 layout，getBoundingClientRect 返回 0 —— 直接用 clientWidth 计算右热区坐标
        content.clientWidth = 800; // jsdom 允许赋值
        const click = new MouseEvent('click', { bubbles: true, clientX: 700 });
        content.dispatchEvent(click);
        await new Promise((r) => setTimeout(r, 100)); // 等 turnTo 完成
        const title = viewContainer().querySelector('.text-reader__title').textContent;
        assert.ok(title.includes('第一章'), `expected chapter title, got: ${title}`);
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});
```

注意：jsdom 下 `getBoundingClientRect` 全 0，热区判定 `(e.clientX - rect.left) / rect.width` 会除 0——因此必须给 `content.clientWidth`/`clientHeight` 赋值（jsdom 支持对 Element 的 clientWidth 属性赋值，getBoundingClientRect 会返回 width=clientWidth）。若赋值不生效，测试改为直接调用热区判定逻辑（把 ratio 计算抽为导出函数 `hotzoneRatio(clientX, rect)` 并在 textReader.js 中复用）。实施者按实测选择其一，并在报告注明。

- [ ] **Step 2: 运行确认（应已通过——Task 3 已实现该行为）**

Run: `cd server/internal/web && node --test pageTurn.test.mjs`
Expected: PASS（验证 turnTo 确实调了 loadChapterSection(2)）。若失败说明 Task 3 的 turnTo 没正确传 target，回 Task 3 修。

- [ ] **Step 3: 接入 textReader.js**

在 `renderTextReader` 内（`loadChapter` 定义之后、chrome button bindings 之前）实例化 pageTurnApi：

```js
    const pageTurnApi = renderPageTurn({
        contentEl: els.content,
        getStyle: () => readerPrefs.getSettings().pageTurnStyle,
        loadChapterSection: async (idx) => {
            const ch = await getBookChapter(path, idx);
            return renderBlocks(ch.blocks || blocksFromLegacyContent(ch.content), ch.title, idx);
        },
        getCurrentIdx: () => state.currentIdx,
        getChapterCount: () => chapterCount,
    });
```

修改 prev/next 按钮绑定（CHAPTER 模式走 pageTurn；SCROLL 模式**保持现有代码原样**——现有按钮在 scroll 模式下调 `loadChapter(idx±1)`，不要改成 loadPrevScrollChapter/loadNextScrollChapter，那是越界行为改动）：

```js
    // 现有：els.prev 点击 → if (state.currentIdx > 0) loadChapter(state.currentIdx - 1)
    // 改为：SCROLL 分支保持原样，CHAPTER 分支走 pageTurn
    els.prev.addEventListener('click', () => {
        if (readerPrefs.getSettings().readingMode === 'scroll') {
            if (state.currentIdx > 0) loadChapter(Math.max(0, state.currentIdx - 1)); // 保持现状
            return;
        }
        pageTurnApi.turnTo('prev').then((ok) => { if (!ok) showToast('已经是第一章了', 'info'); });
    });
    els.next.addEventListener('click', () => {
        if (readerPrefs.getSettings().readingMode === 'scroll') {
            if (state.currentIdx < chapterCount - 1) loadChapter(Math.min(chapterCount - 1, state.currentIdx + 1)); // 保持现状
            return;
        }
        pageTurnApi.turnTo('next').then((ok) => { if (!ok) showToast('已经是最后一章了', 'info'); });
    });
```

修改内容区 click 热区（现有 `els.content.addEventListener('click', ...)` 的 CHAPTER 分支）：把 `loadChapter(state.currentIdx ± 1)` 替换为 `pageTurnApi.turnTo('prev'|'next')`。**保留**现有 `if (readingMode === 'scroll') return;` 守卫（scroll 模式点击不翻章）。

修改内容区 click 热区（现有 `els.content.addEventListener('click', ...)` 的 CHAPTER 分支）：把 `loadChapter(state.currentIdx ± 1)` 替换为 `pageTurnApi.turnTo('prev'|'next')`。

修改章末 ❖（renderBlocks 内 end 的 click CHAPTER 分支）：`loadChapter(state.currentIdx + 1)` → `pageTurnApi.turnTo('next')`。

**DRAG 样式下的点击入口**：`pageTurnStyle === 'DRAG'` 时，点击热区/按钮/❖ 仍调 `pageTurnApi.turnTo(...)`（不跟手——无拖动时直接走 COVER 式滑入动画；跟手只发生在实际水平拖动时，见 Task 6）。即入口代码不区分样式，`turnTo` 内部按 `resolveStyle()` 决定动画。

**目录/书签跳转 `onNavigate` 不改**——仍走 `loadChapter(idx)`（瞬时 + fade）。

cleanup（`container._cleanupReader`）加 `pageTurnApi.dispose()`。

- [ ] **Step 4: 回归（snapshot-baseline e2e + 新集成测试必须绿）+ 提交**

Run: `cd server/internal/web && node --test textReader-pageturn.test.mjs`
Expected: 集成测试 PASS（点右热区 → 章节前进；若 jsdom 的 clientWidth 赋值不生效，按 Step 1 的备选方案抽 `hotzoneRatio` 纯函数）
Run: `cd server/internal/web && npm test`
Expected: 全绿（snapshot-baseline e2e 验证初始 render 不变；翻页是交互行为，不破坏初始快照）

```bash
git add server/internal/web/textReader.js server/internal/web/pageTurn.test.mjs
git commit -m "feat(web): route CHAPTER-mode prev/next/hotzone/end-symbol through pageTurn controller"
```

---

### Task 5: Web SIMULATION — pageTurn.js 加仿真卷曲

**Files:**
- Modify: `server/internal/web/pageTurn.js`（animateSimulation 实现）
- Modify: `server/internal/web/style.css`（clip-path / 阴影）
- Modify: `server/internal/web/pageTurn.test.mjs`（SIMULATION 结束态断言）

**Interfaces:**
- 扩展 Task 3 的 `turnTo`：`resolveStyle() === 'SIMULATION'` 时走 `animateSimulation`。

- [ ] **Step 1: 写失败测试**

`pageTurn.test.mjs` 加：

```js
test('SIMULATION turnTo(next) ends with new section visible', async () => {
    const { contentEl, api } = setup(0, 3, 'SIMULATION');
    await api.turnTo('next');
    assert.equal(contentEl.querySelector('.text-reader__chapter-section').dataset.chapterIndex, '1');
    api.dispose();
    teardownJsdom();
});
```

- [ ] **Step 2: 运行确认（Task 3 的 SIMULATION 回退到 COVER，此测试应已 PASS）**

Run: `cd server/internal/web && node --test pageTurn.test.mjs`
Expected: PASS（回退路径覆盖）。Task 5 的工作是把回退替换为真正的 clip-path 卷曲，结束态不变，测试保持绿。

- [ ] **Step 3: 实现 animateSimulation**

在 pageTurn.js 加：

```js
    // SIMULATION: 单页卷曲。顶层（旧章）用 clip-path polygon 沿贝塞尔采样
    // 点裁剪，随进度从右向左扫；阴影用伪元素渐变定位在裁剪边界。jsdom
    // 无真实渲染，靠 transitionend + setTimeout 回退收尾（与 COVER 同策略）。
    function animateSimulation(oldSection, newSection, direction) {
        return new Promise((resolve) => {
            const sign = direction === 'next' ? 1 : -1;
            newSection.style.transform = `translateX(${sign * 100}%)`;
            newSection.style.transition = `transform ${ANIM_MS.SIMULATION}ms ease-in-out`;
            contentEl.appendChild(newSection);
            void contentEl.offsetWidth;
            // 旧章卷曲：clip-path 从满屏收缩到 0
            oldSection.style.transition = `clip-path ${ANIM_MS.SIMULATION}ms ease-in-out`;
            oldSection.classList.add('text-reader__page--curling');
            oldSection.dataset.curlSign = String(sign);
            newSection.style.transform = 'translateX(0)';
            // CSS @keyframes 驱动 clip-path（见 style.css），这里仅触发 + 收尾
            let done = false;
            const finish = () => {
                if (done) return;
                done = true;
                contentEl.removeChild(oldSection);
                newSection.style.transition = '';
                newSection.style.transform = '';
                resolve();
            };
            newSection.addEventListener('transitionend', finish, { once: true });
            setTimeout(finish, ANIM_MS.SIMULATION + 60);
        });
    }
```

`turnTo` 的 style 分支把 SIMULATION 从"回退 COVER"改为调用 `animateSimulation`：

```js
            } else if (style === 'SIMULATION') {
                await animateSimulation(oldSection, newSection, direction);
            } else if (style === 'COVER') {
                await animateCover(oldSection, newSection, direction);
            } else {
                await animateCover(oldSection, newSection, direction); // DRAG 仍回退，Task 6 接入手势
            }
```

style.css 加卷曲 clip-path keyframes。**方向语义**：clip-path polygon 4 点 (x1,y1),(x2,y2),(x3,y3),(x4,y4) 定义可见矩形。next = 从全屏卷到**左边缘线**（右边界 100%→0，从右往左卷）；prev = 从全屏卷到**右边缘线**（左边界 0→100%，从左往右卷）。

```css
@keyframes text-reader-curl-next {
    from { clip-path: polygon(0 0, 100% 0, 100% 100%, 0 100%); }
    to   { clip-path: polygon(0 0, 0 0, 0 100%, 0 100%); }
}
@keyframes text-reader-curl-prev {
    from { clip-path: polygon(0 0, 100% 0, 100% 100%, 0 100%); }
    to   { clip-path: polygon(100% 0, 100% 0, 100% 100%, 100% 100%); }
}
.text-reader__page--curling[data-curl-sign="1"] {
    animation: text-reader-curl-next var(--reader-curl-ms, 400ms) ease-in-out forwards;
}
.text-reader__page--curling[data-curl-sign="-1"] {
    animation: text-reader-curl-prev var(--reader-curl-ms, 400ms) ease-in-out forwards;
}
```

**不要用 `animation-direction: reverse`**——CSS reverse 是时间反转（从 to 播放到 from），with `forwards` 最终停在 from（全屏），动画过程中从 to（一条线）开始展开，视觉与期望完全相反。prev 必须用独立的 `text-reader-curl-prev` keyframes + normal 方向。

（首版用线性 polygon（矩形 clip）近似卷曲边界；贝塞尔采样点多边形是视觉增强，首版满足"卷走"语义即可，视觉打磨留手动验证后迭代。此取舍已在 spec 风险节注明。）

- [ ] **Step 4: 运行测试 + 回归**

Run: `cd server/internal/web && npm test`
Expected: 全绿

- [ ] **Step 5: 提交**

```bash
git add server/internal/web/pageTurn.js server/internal/web/style.css server/internal/web/pageTurn.test.mjs
git commit -m "feat(web): add SIMULATION page-turn (clip-path curl with shadow)"
```

---

### Task 6: Web DRAG 手势 — pageTurn.js 加水平拖动

**Files:**
- Modify: `server/internal/web/pageTurn.js`（DRAG pointer 事件 + 阈值）
- Modify: `server/internal/web/pageTurn.test.mjs`（DRAG 阈值判定纯函数测试）

**Interfaces:**
- 扩展 `renderPageTurn`：`getStyle()==='DRAG'` 时在 contentEl 绑定 pointerdown/move/up，实时 translateX 旧/新章叠层，松手按阈值完成或回弹。

- [ ] **Step 1: 写失败测试（纯函数先行）**

把 DRAG 阈值判定抽为模块内纯函数并导出，便于测：

```js
test('resolveDragOutcome classifies by threshold and direction', () => {
    const { resolveDragOutcome } = await import('./pageTurn.js'); // 或顶层 import
    // sign 约定：dxRatio = (pointer.x − start.x) / width，带符号。
    // dxRatio<0（手指向左拖）= next 意图（中文从右往左翻）；dxRatio>0 = prev 意图。
    // |dxRatio| > 0.25 → commit，否则 revert。
    assert.equal(resolveDragOutcome(-0.30).action, 'commit');
    assert.equal(resolveDragOutcome(-0.30).direction, 'next');   // 向左拖 → next
    assert.equal(resolveDragOutcome(0.30).action, 'commit');
    assert.equal(resolveDragOutcome(0.30).direction, 'prev');    // 向右拖 → prev
    assert.equal(resolveDragOutcome(0.10).action, 'revert');
    assert.equal(resolveDragOutcome(-0.10).action, 'revert');
    assert.equal(resolveDragOutcome(0.10).direction, null);
});
```

注意：`resolveDragOutcome` 是**单参数**（dxRatio），不要传第二个参数（旧草稿传了 `(dxRatio, width)` 与实现不一致）。

- [ ] **Step 2: 运行确认失败**

Run: `cd server/internal/web && node --test pageTurn.test.mjs`
Expected: FAIL（`resolveDragOutcome` 未导出）

- [ ] **Step 3: 实现 DRAG**

pageTurn.js 加纯函数 + pointer 绑定：

```js
// 纯函数：根据拖动位移（屏宽比例，带符号）判定翻页动作。
// dx<0 手指向左 = next 意图；dx>0 手指向右 = prev 意图。
export function resolveDragOutcome(dxRatio) {
    const abs = Math.abs(dxRatio);
    if (abs < DRAG_THRESHOLD) return { action: 'revert', direction: null };
    return { action: 'commit', direction: dxRatio < 0 ? 'next' : 'prev' };
}
```

在 `renderPageTurn` 内，当 `resolveStyle()==='DRAG'` 时于 contentEl 绑定 pointer 事件（首次 turnTo 前或构造时绑定，按 `getStyle()` 动态决定是否消费——更简单：始终绑定但仅在 DRAG 样式下消费水平拖动）。实现要点：

- `pointerdown`：记录起点 x、y、时间，置 `dragging=true`，不阻止默认（让垂直滚动仍可发生）。
- `pointermove`：若 `dragging` 且 `|dx| > |dy|` 且 `|dx| > 8px` 且 style===DRAG → 标记为水平拖动接管，预加载并叠放目标章（按 dx 符号决定 next/prev），实时设 translateX = dx；否则不接管（垂直滚动继续）。
- `pointerup`：若接管了水平拖动 → `resolveDragOutcome(dx/width)`；commit → 用 animateCover 把剩余距离滑到位（同方向）；revert → animateCover 回弹归零。若未接管 → 视为 tap（交回现有 click 热区逻辑，不在 pointer 里处理 tap）。
- **Web 端 DOM 模型天然支持 revert**：拖动期间 oldSection 仍在 DOM 中（未被移除），revert 只需把它移回原位（动画回弹），无需重新加载——与 Android 的状态驱动模型不同（Android 的 revert 恢复见 Task 12）。

DRAG 接管时需 `preventDefault` 阻止滚动仅作用于水平拖动期间；垂直拖动不 preventDefault。

dispose 解绑 pointer 监听（pointerdown/move/up 三个监听器）。

- [ ] **Step 4: 运行测试 + 回归**

Run: `cd server/internal/web && npm test`
Expected: 全绿（纯函数测试覆盖判定；真实 pointer 视觉留手动验证）

- [ ] **Step 5: 提交**

```bash
git add server/internal/web/pageTurn.js server/internal/web/pageTurn.test.mjs
git commit -m "feat(web): add DRAG page-turn gesture (horizontal drag, vertical scroll coexists)"
```

---

### Task 7: Android 数据层 — ReaderSettings 加 pageTurnStyle

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/ReaderSettings.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/data/ReaderSettingsMigrationTest.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/data/RecentActivityStoreReaderSettingsTest.kt`

**Interfaces:**
- Produces: `ReaderSettings.pageTurnStyle: PageTurnStyle = PageTurnStyle.NONE`；`enum class PageTurnStyle(val label: String) { NONE("无"), COVER("覆盖"), SIMULATION("仿真"), DRAG("拖动") }`。

- [ ] **Step 1: 写失败测试**

`ReaderSettingsMigrationTest.kt` 末尾加：

```kotlin
    @Test
    fun v2_without_pageTurnStyle_falls_back_to_none() = runBlocking {
        injectRawSettings("""{"theme":"NIGHT"}""")
        assertEquals(PageTurnStyle.NONE, store.readerSettingsFlow.first().pageTurnStyle)
    }

    @Test
    fun v2_with_pageTurnStyle_reads_correctly() = runBlocking {
        injectRawSettings("""{"pageTurnStyle":"DRAG"}""")
        assertEquals(PageTurnStyle.DRAG, store.readerSettingsFlow.first().pageTurnStyle)
    }

    @Test
    fun v2_with_invalid_pageTurnStyle_falls_back_to_none() = runBlocking {
        injectRawSettings("""{"pageTurnStyle":"BOGUS"}""")
        assertEquals(PageTurnStyle.NONE, store.readerSettingsFlow.first().pageTurnStyle)
    }
```

注意：Gson 反序列化枚举遇未知字符串 `BOGUS` 会抛异常 → 整体回退默认（参考现有 `v1_unknown_enum_falls_back_to_default` 模式）。若 Gson 配置了 `toJson().fromJson()` 宽松策略需确认；测试会暴露真实行为。

- [ ] **Step 2: 运行确认失败**

Run: `cd android && powershell -Command ".\gradlew.bat :app:testDebugUnitTest --tests 'com.juziss.localmediahub.data.ReaderSettingsMigrationTest' 2>&1 | Select-Object -Last 12"`
Expected: 编译失败（`PageTurnStyle` 不存在）

- [ ] **Step 3: 实现**

`ReaderSettings.kt` data class 末尾加：

```kotlin
    val pageTurnStyle: PageTurnStyle = PageTurnStyle.NONE,
```

文件内（与 `ReadingMode` 同级）加：

```kotlin
enum class PageTurnStyle(val label: String) {
    NONE("无"),
    COVER("覆盖"),
    SIMULATION("仿真"),
    DRAG("拖动"),
}
```

- [ ] **Step 4: 运行测试 + 回归**

Run: `cd android && powershell -Command ".\gradlew.bat :app:testDebugUnitTest --tests 'com.juziss.localmediahub.data.*' 2>&1 | Select-Object -Last 12"`
Expected: 全绿

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/data/ReaderSettings.kt android/app/src/test/java/com/juziss/localmediahub/data/ReaderSettingsMigrationTest.kt
git commit -m "feat(android): add pageTurnStyle enum + settings field (NONE/COVER/SIMULATION/DRAG)"
```

---

### Task 8: Android 设置面板 — ReaderSettingsSheet 加翻页 chips

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheet.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheetTest.kt`

**Interfaces:**
- Produces: 设置 sheet 内「翻页动画」FlowRow chips（4 项），SCROLL 模式置灰；点击 `onChange(settings.copy(pageTurnStyle = it))`。

- [ ] **Step 1: 写失败测试**

`ReaderSettingsSheetTest.kt` 末尾加：

```kotlin
    @Test
    fun page_turn_chips_render_in_chapter_mode() {
        composeRule.setContent {
            ReaderSettingsSheetContent(
                settings = ReaderSettings(readingMode = com.juziss.localmediahub.data.ReadingMode.CHAPTER),
                onChange = {},
            )
        }
        composeRule.waitForIdle()
        com.juziss.localmediahub.data.PageTurnStyle.entries.forEach { style ->
            composeRule.onNodeWithText(style.label).assertExists()
        }
    }

    @Test
    fun page_turn_chip_click_fires_onchange() {
        var captured: ReaderSettings? = null
        composeRule.setContent {
            ReaderSettingsSheetContent(
                settings = ReaderSettings(),
                onChange = { captured = it },
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(com.juziss.localmediahub.data.PageTurnStyle.COVER.label).performClick()
        assertEquals(com.juziss.localmediahub.data.PageTurnStyle.COVER, captured?.pageTurnStyle)
    }

    @Test
    fun page_turn_chips_disabled_in_scroll_mode() {
        composeRule.setContent {
            ReaderSettingsSheetContent(
                settings = ReaderSettings(readingMode = com.juziss.localmediahub.data.ReadingMode.SCROLL),
                onChange = {},
            )
        }
        composeRule.waitForIdle()
        com.juziss.localmediahub.data.PageTurnStyle.entries.forEach { style ->
            composeRule.onNodeWithText(style.label).assertIsNotEnabled()
        }
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `cd android && powershell -Command ".\gradlew.bat :app:testDebugUnitTest --tests 'com.juziss.localmediahub.ui.component.reader.ReaderSettingsSheetTest' 2>&1 | Select-Object -Last 12"`
Expected: 新 3 测试 FAIL

- [ ] **Step 3: 实现**

`ReaderSettingsSheet.kt`「行为」section（阅读模式 chips 之后）加：

```kotlin
        Text("翻页动画", style = MaterialTheme.typography.labelMedium)
        val isChapter = settings.readingMode == com.juziss.localmediahub.data.ReadingMode.CHAPTER
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            com.juziss.localmediahub.data.PageTurnStyle.entries.forEach { style ->
                FilterChip(
                    selected = settings.pageTurnStyle == style,
                    onClick = { onChange(settings.copy(pageTurnStyle = style)) },
                    enabled = isChapter,
                    label = { Text(style.label) },
                    colors = FilterChipDefaults.filterChipColors(),
                )
            }
        }
        Spacer(Modifier.size(8.dp))
```

- [ ] **Step 4: 运行测试 + 回归**

Run: `cd android && powershell -Command ".\gradlew.bat :app:testDebugUnitTest --tests 'com.juziss.localmediahub.ui.component.reader.*' 2>&1 | Select-Object -Last 12"`
Expected: 全绿

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheet.kt android/app/src/test/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheetTest.kt
git commit -m "feat(android): add page-turn style chips to reader settings (disabled in scroll mode)"
```

---

### Task 9: Android 翻页控制器 — PageTurnController（NONE/COVER）

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/PageTurnController.kt`
- Create: `android/app/src/test/java/com/juziss/localmediahub/ui/component/reader/PageTurnControllerTest.kt`

**Interfaces:**
- Produces: `class PageTurnController`（**纯逻辑，不持有 Compose 状态**）：
  - `suspend fun turnTo(direction: PageTurnDirection, load: suspend (targetIdx: Int) -> Boolean): Int?`
  - 返回：成功 = 目标章 index（已 load 成功）；失败 = `null`（越界 / load 失败 / 并发被拒）。
  - **动画不在此类**：controller 只做校验 + load + busy 互斥；动画由 UI 层在拿到返回值后自行 `Animatable.animateTo` 驱动（时长按 style 决定：COVER 280ms / SIMULATION 400ms）。这避免了"controller 内部分 10 步推进进度 = 10 帧跳变"的假动画问题。
- `enum class PageTurnDirection { NEXT, PREV }`
- Task 10 的 UI 层依赖此签名：`val target = controller.turnTo(NEXT) { viewModel.loadChapter(it, resetScroll = true) }`。

- [ ] **Step 1: 写失败测试**

新建 `PageTurnControllerTest.kt`：

```kotlin
package com.juziss.localmediahub.ui.component.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTurnControllerTest {

    @Test
    fun turnTo_next_invokes_load_with_next_index_and_returns_target() = runBlocking {
        val controller = PageTurnController(currentIdx = 0, chapterCount = 3)
        var loadedIdx: Int? = null
        val target = controller.turnTo(
            direction = PageTurnDirection.NEXT,
            load = { idx -> loadedIdx = idx; true },
        )
        assertEquals(1, target)
        assertEquals(1, loadedIdx)
    }

    @Test
    fun turnTo_prev_at_first_returns_null_and_no_load() = runBlocking {
        val controller = PageTurnController(currentIdx = 0, chapterCount = 3)
        var loaded = false
        val target = controller.turnTo(
            direction = PageTurnDirection.PREV,
            load = { loaded = true; true },
        )
        assertNull(target)
        assertFalse(loaded)
    }

    @Test
    fun turnTo_next_at_last_returns_null() = runBlocking {
        val controller = PageTurnController(currentIdx = 2, chapterCount = 3)
        val target = controller.turnTo(PageTurnDirection.NEXT, load = { true })
        assertNull(target)
    }

    @Test
    fun turnTo_load_failure_returns_null() = runBlocking {
        val controller = PageTurnController(currentIdx = 0, chapterCount = 3)
        val target = controller.turnTo(PageTurnDirection.NEXT, load = { false })
        assertNull(target)
    }

    /** 真实并发：第一个 turnTo 的 load 挂起期间，第二个调用被 busy 互斥拒绝。 */
    @Test
    fun turnTo_rejects_second_call_while_busy() = runBlocking {
        val controller = PageTurnController(currentIdx = 0, chapterCount = 3)
        val loadStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch {
            controller.turnTo(PageTurnDirection.NEXT, load = {
                loadStarted.complete(Unit)
                release.await() // 挂起，保持 busy
                true
            })
        }
        loadStarted.await()
        val secondTarget = controller.turnTo(PageTurnDirection.NEXT, load = { true })
        assertNull(secondTarget) // busy 期间第二个被拒
        release.complete(Unit)
        first.join()
    }

    @Test
    fun turnTo_accepts_after_previous_completes() = runBlocking {
        val controller = PageTurnController(currentIdx = 0, chapterCount = 3)
        assertTrue(controller.turnTo(PageTurnDirection.NEXT, load = { true }) != null)
        // busy 已复位，第二次可正常翻页
        assertEquals(2, controller.turnTo(PageTurnDirection.NEXT, load = { true }))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd android && powershell -Command ".\gradlew.bat :app:testDebugUnitTest --tests 'com.juziss.localmediahub.ui.component.reader.PageTurnControllerTest' 2>&1 | Select-Object -Last 12"`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 实现 PageTurnController.kt**

```kotlin
package com.juziss.localmediahub.ui.component.reader

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class PageTurnDirection { NEXT, PREV }

/**
 * 章级翻页控制器。CHAPTER 模式内容区通过 [turnTo] 发起带方向的翻页：
 * 校验目标章合法 → 调 [load] 加载 → 返回目标章 index（由 UI 层驱动动画）。
 *
 * 本类只负责"逻辑"（边界校验、加载编排、busy 互斥），**不持有动画状态**——
 * 动画由 UI 层用 Animatable.animateTo 驱动（时长按 pageTurnStyle 决定）。
 * 纯逻辑 + 协程，便于 Robolectric 单测。
 */
class PageTurnController(
    private val currentIdx: () -> Int,
    private val chapterCount: () -> Int,
) {
    // 便捷构造：直接传快照值（测试用）。
    constructor(currentIdx: Int, chapterCount: Int) : this({ currentIdx }, { chapterCount })

    private val mutex = Mutex()

    /** @return 成功 = 已加载的目标章 index；失败（越界/load 失败/并发被拒）= null */
    suspend fun turnTo(
        direction: PageTurnDirection,
        load: suspend (targetIdx: Int) -> Boolean,
    ): Int? {
        if (!mutex.tryLock()) return null
        try {
            val target = when (direction) {
                PageTurnDirection.NEXT -> currentIdx() + 1
                PageTurnDirection.PREV -> currentIdx() - 1
            }
            if (target < 0 || target >= chapterCount()) return null
            return if (load(target)) target else null
        } finally {
            mutex.unlock()
        }
    }
}
```

- [ ] **Step 4: 运行测试 + 回归**

Run: `cd android && powershell -Command ".\gradlew.bat :app:testDebugUnitTest --tests 'com.juziss.localmediahub.ui.component.reader.PageTurnControllerTest' 2>&1 | Select-Object -Last 12"`
Expected: 6 PASS

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/PageTurnController.kt android/app/src/test/java/com/juziss/localmediahub/ui/component/reader/PageTurnControllerTest.kt
git commit -m "feat(android): add PageTurnController (NONE/COVER, mutex-guarded, pure-logic testable)"
```

---

### Task 10: Android 接入 — TextReaderScreen CHAPTER 分支走 PageTurnController（NONE 行为）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt`

**Interfaces:**
- Consumes: Task 9 的 `PageTurnController`（`turnTo(direction, load): Int?`）。
- Produces: CHAPTER 模式相邻切章入口（热区/按钮/❖）统一走 `turn(direction)`；`AnimatedContent` fade 移除（NONE 行为由 blocks 状态自然刷新）。**双层渲染与动画（COVER/SIMULATION）在 Task 11 实现**——本任务只打通入口 + 控制器 + NONE 行为。

- [ ] **Step 1: 无独立测试（UI 编排，行为正确性由 Task 9 控制器单测 + 现有回归保证）**

- [ ] **Step 2:（跳过）**

- [ ] **Step 3: 实现**

`TextReaderScreen.kt`：

(1) CHAPTER 分支（约 612-628 行）把 `AnimatedContent { ... ChapterModeContent(...) }` 替换为：

```kotlin
                                // ===== 分章模式：翻页控制器 =====
                                val controller = remember(settings.readingMode) {
                                    PageTurnController(
                                        currentIdx = { idx },
                                        chapterCount = { totalChaptersCount },
                                    )
                                }
                                val scope = rememberCoroutineScope()

                                fun turn(direction: PageTurnDirection) {
                                    scope.launch {
                                        val target = controller.turnTo(direction) { t ->
                                            viewModel.loadChapter(t, resetScroll = true)
                                        }
                                        // NONE 行为：loadChapter 已更新 blocks/currentIndex 状态，
                                        // 下方 ChapterModeContent 随状态自然刷新。
                                        // COVER/SIMULATION 动画在 Task 11 加（此处按 style 决定是否驱动动画）。
                                        if (target == null && direction == PageTurnDirection.NEXT) {
                                            // 边界提示保持与旧行为一致（可选，参照旧 toast/无操作）
                                        }
                                    }
                                }
```

(2) 翻页入口改为调 `turn(PageTurnDirection.NEXT/PREV)`：
- 热区 `pointerInput`（约 533-546 行）：`ratio < 0.20f -> turn(PREV)`、`ratio > 0.80f -> turn(NEXT)`、中间仍 `viewModel.toggleChrome()`
- 底栏上一章/下一章按钮（约 525-526 行）：`viewModel.prevChapter()` → `turn(PREV)`、`viewModel.nextChapter()` → `turn(NEXT)`
- 章末 ❖（约 773 行）：`viewModel.nextChapter()` → `turn(NEXT)`
- 目录/书签跳转（drawer 的 onClick）不改，仍走 `viewModel.loadChapter`（瞬时 + fade 保持现状）。

(3) import 加 `com.juziss.localmediahub.ui.component.reader.PageTurnController`、`PageTurnDirection`。`AnimatedContent`/`togetherWith`/`fadeIn`/`fadeOut` 若不再使用则移除相应 import。

注意：`viewModel.loadChapter` 成功后内部会更新 `_currentIndex` 与 `_chapterBlocks`——NONE 行为下 UI 自动刷新为新区内容，与旧 fade 的最终状态一致。Task 11 在 `turn()` 里按 `settings.pageTurnStyle` 分支添加动画驱动。

- [ ] **Step 4: 回归**

Run: `cd android && powershell -Command ".\gradlew.bat :app:testDebugUnitTest --tests 'com.juziss.localmediahub.ui.screen.TextReaderScreenThemeTest' --tests 'com.juziss.localmediahub.ui.component.reader.*' 2>&1 | Select-Object -Last 12"`
Expected: 全绿（TextReaderScreenThemeTest 用 ReaderThemeScope 直测，不受 CHAPTER 分支改动影响）

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt
git commit -m "feat(android): wire CHAPTER-mode turn paths to PageTurnController (NONE behavior, replace fade)"
```

---

### Task 11: Android COVER 完整渲染 + SIMULATION（PageTurnSimulator）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt`（COVER 双层 graphicsLayer）
- Create: `android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/PageTurnSimulator.kt`

**Interfaces:**
- Produces: COVER 用 `graphicsLayer { translationX = (1-progress)*sign*100% }`；SIMULATION 用 `PageTurnSimulator`（`Canvas` + `clipPath` 贝塞尔 + 阴影 Brush）。

- [ ] **Step 1: 无独立单测（自绘 + graphicsLayer 视觉，靠手动验证）**

- [ ] **Step 2:（跳过）**

- [ ] **Step 3: 实现**

**关键：动画期间两个章的内容来源（状态驱动模型）**。`viewModel.loadChapter` 会把 `_chapterBlocks` 覆盖为新章内容（单一状态）。因此动画前必须**保存当前 blocks 快照作为顶层（旧章）**，loadChapter 后 `blocks` 作为底层（新章）——顺序不能反：

```
turn(direction) {
    val oldBlocks = blocks          // ① 保存当前章快照（顶层，被卷走/移出）
    val target = controller.turnTo(direction) { loadChapter(it, true) }  // ② blocks 变为新章（底层）
    if (target == null) return
    incoming = Incoming(blocks = oldBlocks, target = target, direction = direction)  // ③ 记录动画层
    progress.snapTo(0f)
    progress.animateTo(1f, tween(durationMs(style)))   // ④ 驱动动画
    incoming = null                  // ⑤ 动画完移除顶层，显示底层（blocks = 新章）
}
```

(1) `TextReaderScreen.kt`：

在 CHAPTER 分支加状态：

```kotlin
                                val progress = remember { Animatable(1f) }
                                var incoming by remember { mutableStateOf<IncomingPage?>(null) }

                                // 动画层描述：顶层是旧章快照，随 progress 移出/卷走
                                data class IncomingPage(
                                    val topBlocks: List<com.juziss.localmediahub.data.Block>,
                                    val topIdx: Int,
                                    val targetIdx: Int,
                                    val direction: PageTurnDirection,
                                )
```

`turn(direction)` 扩展为按 style 分支：

```kotlin
                                fun turn(direction: PageTurnDirection) {
                                    scope.launch {
                                        val style = settings.pageTurnStyle
                                        val oldBlocks = blocks
                                        val target = controller.turnTo(direction) { t ->
                                            viewModel.loadChapter(t, resetScroll = true)
                                        }
                                        if (target == null) return@launch
                                        when (style) {
                                            PageTurnStyle.NONE -> Unit // blocks 已刷新，直接显示新章
                                            PageTurnStyle.COVER, PageTurnStyle.SIMULATION -> {
                                                incoming = IncomingPage(oldBlocks, idx, target, direction)
                                                progress.snapTo(0f)
                                                val ms = if (style == PageTurnStyle.COVER) 280 else 400
                                                progress.animateTo(1f, tween(ms, easing = if (style == PageTurnStyle.COVER) FastOutSlowInEasing else FastOutSlowInEasing))
                                                incoming = null
                                            }
                                            PageTurnStyle.DRAG -> Unit // 手势路径在 Task 12
                                        }
                                    }
                                }
```

渲染：底层 = `blocks`（新章，正常 ChapterModeContent），顶层 = `incoming` 存在时渲染旧章快照 + 动画层：

```kotlin
                                Box(Modifier.fillMaxSize()) {
                                    // 底层：当前 blocks（loadChapter 后的新章）
                                    ChapterModeContent(
                                        blocks = blocks, idx = idx, book = book, settings = settings,
                                        contentDp = contentDp, listState = listState, context = context,
                                        viewModel = viewModel,
                                    )
                                    // 顶层：动画层（旧章快照）
                                    incoming?.let { inc ->
                                        val topTx by remember(inc, progress) {
                                            derivedStateOf {
                                                val sign = if (inc.direction == PageTurnDirection.NEXT) -1f else 1f
                                                (1f - progress.value) * sign // 顶层从 0 移到 ±100%（露出底层）
                                            }
                                        }
                                        Box(
                                            Modifier
                                                .fillMaxSize()
                                                .graphicsLayer { translationX = topTx * size.width }
                                        ) {
                                            ChapterModeContent(
                                                blocks = inc.topBlocks, idx = inc.topIdx, book = book,
                                                settings = settings, contentDp = contentDp,
                                                listState = listState, context = context, viewModel = viewModel,
                                            )
                                        }
                                    }
                                }
```

注意：顶层 `translationX = (1-progress) * sign`：progress=0 → 顶层原位（全盖住新章）；progress=1 → 顶层移出（next 向左 -1 → 露出右侧底层）。next 时 sign=-1（顶层向左退出、新章从右进入）✓；prev 时 sign=+1 ✓。**顶层是旧章快照、底层是新章**——与 Web 端 DOM 模型（oldSection 顶层 + newSection 底层）语义一致。

`ChapterModeContent` 顶层复用会创建独立 LazyColumn（自己的 listState 冲突）——顶层内容仅动画期间存在，可用 `remember(inc.topIdx) { LazyListState() }` 独立 listState，或顶层改用静态 Column 渲染段落（动画期间滚动被禁用，顶层无需可滚动）。**推荐**：顶层用静态 `Column` + `ParagraphItem` 列表渲染（动画 280-400ms 内不需要滚动交互），避免与底层共享 listState 的复杂化。

(2) `PageTurnSimulator.kt`：`Canvas` 接收 `progress`、`reverse`，用 `Path` 贝塞尔 clip 顶层 + `Brush.linearGradient` 画阴影：

```kotlin
package com.juziss.localmediahub.ui.component.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath

/**
 * 仿真翻页：顶层（当前章）用贝塞尔 Path 裁剪，随 [progress]（0..1）卷走。
 * 卷曲边界处画一道渐变阴影模拟折痕。底层显示下一章（由调用方叠放）。
 *
 * 可见区语义：
 *  - [reverse]=false（NEXT）：可见区 = 左侧矩形（0 .. edge），edge 从 w 扫到 0
 *    → 从右往左卷走（下一章从右侧进入）。progress=0 全屏；progress=1 空。
 *  - [reverse]=true（PREV）：可见区 = 右侧矩形（w-edge .. w），w-edge 从 w 扫到 0
 *    → 从左往右卷走（下一章从左侧进入）。progress=0 全屏；progress=1 空。
 */
@Composable
fun PageTurnSimulator(
    progress: Float,
    reverse: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val edge = (1f - progress) * w // 卷曲边界 x（next 时是可见区右边界；prev 时是可见区左边界）
        val p = if (reverse) {
            // PREV：可见区在右侧 (w-edge .. w)，贝塞尔弧在左边界 w-edge
            Path().apply {
                moveTo(w - edge, 0f)
                lineTo(w, 0f)
                lineTo(w, h)
                cubicTo(
                    (w - edge) + 30f, h * 0.66f,
                    (w - edge) - 30f, h * 0.33f,
                    w - edge, h,
                )
                close()
            }
        } else {
            // NEXT：可见区在左侧 (0 .. edge)，贝塞尔弧在右边界 edge
            Path().apply {
                moveTo(0f, 0f)
                lineTo(edge, 0f)
                cubicTo(
                    edge + 30f, h * 0.33f,
                    edge - 30f, h * 0.66f,
                    edge, h,
                )
                lineTo(0f, h)
                close()
            }
        }
        clipPath(p) {
            // 阴影带：画在卷曲边界（next=右边界 edge；prev=左边界 w-edge）附近
            val shadowEdge = if (reverse) w - edge else edge
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.18f), Color.Transparent),
                    start = Offset(shadowEdge - 24f, 0f),
                    end = Offset(shadowEdge + 24f, 0f),
                ),
            )
        }
    }
}
```

`TextReaderScreen` SIMULATION 分支：顶层 Box 用 `clipPath` 裁剪的 `PageTurnSimulator` 叠在顶层之上（或顶层 Box 的 `graphicsLayer`/`drawWithContent` 组合）。首版简化：顶层内容（旧章）外包一层 `Modifier.drawWithContent { }` 不裁剪（直接显示），用 `PageTurnSimulator` 作为覆盖在顶层的阴影层 + 底层新章直接可见——若视觉验证不佳，再按 clipPath 方案裁剪顶层。

(3) `durationMs(style)` = COVER 280 / SIMULATION 400（常量 `ANIM_MS` 与 Web 端一致）。

- [ ] **Step 4: 回归 + 手动验证提示**

Run: `cd android && powershell -Command ".\gradlew.bat :app:testDebugUnitTest 2>&1 | Select-Object -Last 12"`
Expected: 全绿（视觉验证由人工在真机/模拟器跑 app 完成：选 COVER/SIMULATION 样式，点右热区看翻页动画——重点验证：next 从右进入、prev 从左进入、动画结束无残留层）

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/PageTurnSimulator.kt
git commit -m "feat(android): implement COVER (graphicsLayer) + SIMULATION (clipPath curl) page-turn"
```

---

### Task 12: Android DRAG 手势 + 双端对齐验证

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt`（DRAG `detectHorizontalDragGestures`）

**Interfaces:**
- 扩展 CHAPTER 模式 `pointerInput`：DRAG 样式下水平拖动驱动 `progress.snapTo`，松手按阈值完成/回弹。

- [ ] **Step 1: 写失败测试（手势判定纯函数）**

在 `PageTurnControllerTest.kt` 或新文件加纯函数测试。在 `PageTurnController.kt` 加顶层纯函数：

```kotlin
/** 判定水平拖动应否被翻页接管：水平位移 > 垂直位移 且 > 触摸阈值。 */
fun shouldDragTakeOver(dx: Float, dy: Float, touchSlopPx: Float): Boolean =
    kotlin.math.abs(dx) > kotlin.math.abs(dy) && kotlin.math.abs(dx) > touchSlopPx
```

测试：

```kotlin
    @Test
    fun shouldDragTakeOver_horizontal_dominant_over_threshold() {
        assertTrue(shouldDragTakeOver(20f, 5f, 8f))
        assertTrue(shouldDragTakeOver(-20f, 5f, 8f))
    }

    @Test
    fun shouldDragTakeOff_vertical_dominant_returns_false() {
        assertFalse(shouldDragTakeOver(5f, 30f, 8f))
    }

    @Test
    fun shouldDragTakeOver_under_slop_returns_false() {
        assertFalse(shouldDragTakeOver(5f, 2f, 8f))
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `cd android && powershell -Command ".\gradlew.bat :app:testDebugUnitTest --tests 'com.juziss.localmediahub.ui.component.reader.PageTurnControllerTest' 2>&1 | Select-Object -Last 12"`
Expected: FAIL（`shouldDragTakeOver` 不存在）

- [ ] **Step 3: 实现 DRAG**

(1) `PageTurnController.kt` 加 `shouldDragTakeOver`（见 Step 1 代码）+ 松手判定纯函数：

```kotlin
/** 松手判定：拖动位移（屏宽比例，带符号）决定 commit 还是 revert。 */
fun resolveDragOutcome(dxRatio: Float): DragOutcome {
    val abs = kotlin.math.abs(dxRatio)
    return if (abs < 0.25f) DragOutcome.REVERT else DragOutcome.COMMIT
}
enum class DragOutcome { COMMIT, REVERT }
```

(2) `TextReaderScreen.kt` CHAPTER 模式 `pointerInput` 加 DRAG 检测（与 `detectTapGestures` 并排两个 `pointerInput`，互不干扰——tap 只在无位移时触发，drag 只在水平位移时触发）。实现：`detectHorizontalDragGestures(onDragStart, onDragEnd, onHorizontalDrag)`。

**DRAG 渲染模型（与 COVER 同构，进度由手指驱动）**：

```
onDragStart: 记录起点；若 style==DRAG，保存当前 blocks 快照（顶层）
onHorizontalDrag(dx, dy):
    若 style!=DRAG → 不接管（垂直滚动继续）
    若 !shouldDragTakeOver(dx, dy, 8dp) → 不接管
    接管：按 dx 符号确定方向（dx<0 = NEXT）
    目标章尚未加载 → controller.turnTo(direction) { loadChapter(it, true) } 预加载
        （loadChapter 后 blocks = 目标章 = 底层；快照 = 旧章 = 顶层）
    progress.snapTo(|dx| / width)   // 0..1，顶层随手指移出
onDragEnd(dx):
    若未接管 → 无操作（tap 走现有 click 热区）
    resolveDragOutcome(dx / width):
        COMMIT → progress.animateTo(1f)   // 顶层滑出，露出底层（blocks 已是目标章）；完成
        REVERT → progress.animateTo(0f); viewModel.loadChapter(旧 idx, true)  // 顶层回位
```

**REVERT 恢复关键**：回弹后 `blocks` 已被预加载的目标章覆盖——必须重新 `loadChapter(旧 idx, true)` 恢复当前章内容（或保存旧 blocks 快照用于回填；二选一，推荐重新 loadChapter——代码简单、状态单一来源）。**不要**用 `controller.turnTo(...)` 完成剩余动画——turnTo 会重新 load（重复加载）且从 0 开始动画，与已拖动的进度冲突。COMMIT/REVERT 的收尾动画直接用 `progress.animateTo`（目标章已预加载，无需再调 turnTo）。

DRAG 仅在 `settings.pageTurnStyle == DRAG` 时接管；否则保持现有 tap 热区逻辑（点击入口在 Task 4/10 已接 controller.turnTo，DRAG 样式下点击热区翻章走 COVER 式滑动，见 Task 4 说明）。

- [ ] **Step 4: 运行测试 + 双端全量**

Run: `cd android && powershell -Command ".\gradlew.bat :app:testDebugUnitTest 2>&1 | Select-Object -Last 12"`
Run: `cd server/internal/web && npm test`
Expected: 双端全绿

- [ ] **Step 5: spec 对齐清单核对**

按 spec「双端对齐校验清单」逐项人工核对（双端字段四值一致、仅 CHAPTER 生效、方向一致、三入口走翻页、跳转不走翻页、DRAG 阈值一致、reduced-motion 降级）。发现问题修复后复跑。

- [ ] **Step 6: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/PageTurnController.kt android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt android/app/src/test/java/com/juziss/localmediahub/ui/component/reader/PageTurnControllerTest.kt
git commit -m "feat(android): add DRAG page-turn gesture + drag-outcome pure functions; align to spec"
```

---

## Self-Review 备忘

**Spec coverage**：字段（Task 1/7）、设置面板（2/8）、翻页控制器 COVER（3/9/10/11）、SIMULATION（5/11）、DRAG（6/12）、接入与触发路径（4/10）、手势协作（6/12）、reduced-motion 降级（3）、测试计划（各任务）、对齐清单（12）。无遗漏。

**Placeholder 注意**：Task 10/11 的 UI 接入部分是视觉行为，无独立单测，靠回归 + 手动验证——这是 plan 明确的取舍，非占位符。实施者应在 commit message 注明手动验证结果。

**类型一致性**：`PageTurnStyle`（Task 7 定义）/`PageTurnDirection`（Task 9 定义）/`resolveDragOutcome`（Web Task 6 + Android Task 12 各定义同名，语义一致，跨端名相同便于对齐）。

**审核修正记录（2026-08-01 用户要求审核 plan 后）**：
- Task 3：删死变量 `dragAttached`；补 `.text-reader__content { position: relative }`（absolute incoming 需要 positioned 祖先）。
- Task 4：prev/next 按钮 SCROLL 分支保持现有 `loadChapter` 行为（原稿误引入 loadPrevScrollChapter 越界改动）；补集成测试 `textReader-pageturn.test.mjs`（点右热区 → 章节前进，spec 测试计划要求）；明确 DRAG 样式下点击入口走 COVER 式滑动。
- Task 5：**方向 bug 修正**——`animation-direction: reverse` 时间反转语义导致 prev 视觉反了，改为独立 `text-reader-curl-prev` keyframes（全屏→右线）+ normal 方向。
- Task 6：`resolveDragOutcome` 测试与实现/注释矛盾修正（单参数、方向断言与注释一致：dx<0=next、dx>0=prev）。
- Task 9：**假动画重构**——controller 去掉 onProgress/onDone 与"分 10 步推进"（10 帧跳变），`turnTo` 改为返回 `Int?`，动画由 UI 层 `Animatable.animateTo` 驱动；并发测试改为真实并发（load 挂起期间第二个被拒）。
- Task 10：接入代码与新签名一致，移除假动画 snapTo 段；双层渲染与动画明确移交 Task 11。
- Task 11：**clipPath 方向 bug 修正**——reverse（PREV）时可见区在右侧 (w-edge..w)（原稿画成左侧矩形，方向反了）；补"动画前保存当前 blocks 快照作为顶层"关键顺序（loadChapter 覆盖 blocks 单一状态）；顶层改用静态 Column 渲染避免 listState 冲突；删除过时的"onProgress 驱动 snapTo"。
- Task 12：**DRAG 渲染模型补全**——拖动时先 `controller.turnTo` 预加载目标章（底层）+ 旧章快照（顶层）；COMMIT/REVERT 收尾用 `progress.animateTo` 而非重复 turnTo；REVERT 必须重新 loadChapter(旧 idx) 恢复（blocks 已被预加载覆盖）。
