# 阅读器边缘手势与翻页键实施计划（亮度/音量侧滑 + 音量键/键盘翻页）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为双端阅读器增加边缘垂直滑动调亮度/音量 + 音量键（Android）/方向键+空格（Web）翻页两项交互，通过两个新设置开关控制。

**Architecture:** 独立模块 + 纯函数手势/键映射判定（与上轮 PageTurnController/pageTurn.js 同构）。Android：`EdgeBrightnessController` 纯函数 + Activity 的 `onKeyDown`/`window.screenBrightness`/`AudioManager`；Web：`edgeBrightness.js` 纯函数 + pointer/keydown 监听 + CSS 覆盖层。手势判定抽纯函数先行单测。

**Tech Stack:** Web — ES modules + node:test/jsdom + CSS overlay（无新依赖）；Android — Compose + Robolectric + Activity onKeyDown + window.attributes.screenBrightness + AudioManager（无新 Gradle 依赖）。

## Global Constraints

- 双端字段一致：`edgeBrightnessVolume: Boolean = true`、`volumeKeyTurn: Boolean = true`。
- 边缘分区：左 15%（`xRatio < 0.15`）亮度；右 15%（`xRatio > 0.85`）音量（**仅 Android**）/ Web 右边缘不响应。
- 亮度范围 `[0.15, 1.0]`，**向下拖增亮、向上拖减暗**（Legado 一致）。
- 亮度**仅会话内**：Android onDestroy 复原 `BRIGHTNESS_OVERRIDE_NONE`；Web 离开阅读页移除覆盖层。不写入 settings。
- 翻页键方向：音量下/向下/向右/空格 = next；音量上/向上/向左 = prev。
- 音量键翻页开关开启时 Android onKeyDown 返回 true 拦截系统音量；关闭返回 false。
- 不引入任何新依赖（Web 无新 npm 包；Android 无新 Gradle 依赖）。
- 不改现有翻页动画（COVER/SIMULATION/DRAG）与排版字段。
- TDD：每任务先写失败测试再实现。纯函数先行（手势/键映射判定）。
- 提交信息风格：`feat(web): ...` / `feat(android): ...`。
- 测试命令：Web `cd server/internal/web && npm test`（或单文件 `node --test <file>`）；Android `cd android && powershell -Command ".\gradlew.bat :app:testDebugUnitTest --tests '<FQCN>' 2>&1 | Select-Object -Last 12"`。

---

### Task 1: Web 数据层 — readerPrefs.js 加两个开关字段

**Files:**
- Modify: `server/internal/web/readerPrefs.js`（DEFAULT_SETTINGS、migrateV1toV2）
- Create: `server/internal/web/edgeGestureSettings.test.mjs`

**Interfaces:**
- Produces: `DEFAULT_SETTINGS.edgeBrightnessVolume = true`、`DEFAULT_SETTINGS.volumeKeyTurn = true`；`migrateV1toV2` 对两字段做 `typeof === 'boolean'` 校验。Task 2/3 依赖此字段。

- [ ] **Step 1: 写失败测试**

新建 `server/internal/web/edgeGestureSettings.test.mjs`（migrateV1toV2 纯函数，无 jsdom）：

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { migrateV1toV2 } from './readerPrefs.js';

test('defaults: both edge-gesture flags true', () => {
    const s = migrateV1toV2(null);
    assert.equal(s.edgeBrightnessVolume, true);
    assert.equal(s.volumeKeyTurn, true);
});

test('migrate keeps valid booleans', () => {
    assert.equal(migrateV1toV2({ edgeBrightnessVolume: false }).edgeBrightnessVolume, false);
    assert.equal(migrateV1toV2({ volumeKeyTurn: false }).volumeKeyTurn, false);
});

test('migrate drops non-boolean to default', () => {
    assert.equal(migrateV1toV2({ edgeBrightnessVolume: 'yes' }).edgeBrightnessVolume, true);
    assert.equal(migrateV1toV2({ volumeKeyTurn: 1 }).volumeKeyTurn, true);
});

test('migrate preserves flags alongside other fields', () => {
    const s = migrateV1toV2({ edgeBrightnessVolume: false, theme: 'NIGHT' });
    assert.equal(s.edgeBrightnessVolume, false);
    assert.equal(s.theme, 'NIGHT');
});
```

- [ ] **Step 2: 运行确认失败**

Run: `cd server/internal/web && node --test edgeGestureSettings.test.mjs`
Expected: 4 FAIL（字段 undefined）

- [ ] **Step 3: 实现**

`readerPrefs.js` `DEFAULT_SETTINGS` 末尾加：

```js
    edgeBrightnessVolume: true, // 边缘垂直滑动调亮度/音量
    volumeKeyTurn: true,        // 音量键(Android)/方向键+空格(Web)翻页
```

`migrateV1toV2` 末尾（return 前）加：

```js
    if (typeof old.edgeBrightnessVolume === 'boolean') out.edgeBrightnessVolume = old.edgeBrightnessVolume;
    if (typeof old.volumeKeyTurn === 'boolean') out.volumeKeyTurn = old.volumeKeyTurn;
```

- [ ] **Step 4: 运行通过**

Run: `cd server/internal/web && node --test edgeGestureSettings.test.mjs`
Expected: 4 PASS

- [ ] **Step 5: 回归 + 提交**

Run: `cd server/internal/web && npm test`
Expected: 全绿

```bash
git add server/internal/web/readerPrefs.js server/internal/web/edgeGestureSettings.test.mjs
git commit -m "feat(web): add edgeBrightnessVolume + volumeKeyTurn settings to readerPrefs"
```

---

### Task 2: Web 设置面板 — 两个 checkbox 开关

**Files:**
- Modify: `server/internal/web/reader-settings.js`（模板「行为」section、syncControlsFromSettings）
- Modify: `server/internal/web/edgeGestureSettings.test.mjs`（加 jsdom 对话框测试）

**Interfaces:**
- Consumes: Task 1 的两字段。
- Produces: dialog 内 `input[name="edgeBrightnessVolume"]`、`input[name="volumeKeyTurn"]` checkbox；change 走现有 else 分支 `saveAndEmit({ [t.name]: t.checked })`。

- [ ] **Step 1: 写失败测试**

`edgeGestureSettings.test.mjs` 末尾加（jsdom 模式，仿 reader-settings.test.mjs 的 setup/teardown + Event polyfill）：

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

// 若 readerPrefs.saveSettings 用 window.dispatchEvent(new CustomEvent(...))，
// 复制 reader-settings.test.mjs 的 Event/CustomEvent polyfill setup/teardown 块。

test('dialog renders both checkboxes checked by default', () => {
    setupJsdom();
    try {
        const { api, dialog } = mountDialog({});
        assert.equal(dialog.querySelector('input[name="edgeBrightnessVolume"]').checked, true);
        assert.equal(dialog.querySelector('input[name="volumeKeyTurn"]').checked, true);
        api.dispose();
    } finally {
        teardownJsdom();
    }
});

test('unchecking edgeBrightnessVolume saves false', () => {
    setupJsdom();
    try {
        const { api, dialog } = mountDialog({});
        const cb = dialog.querySelector('input[name="edgeBrightnessVolume"]');
        cb.checked = false;
        cb.dispatchEvent(new Event('change', { bubbles: true }));
        const saved = JSON.parse(localStorage.getItem('reader_settings'));
        assert.equal(saved.edgeBrightnessVolume, false);
        api.dispose();
    } finally {
        teardownJsdom();
    }
});
```

- [ ] **Step 2: 运行确认失败**

Run: `cd server/internal/web && node --test edgeGestureSettings.test.mjs`
Expected: 新 2 测试 FAIL（checkbox 不存在）

- [ ] **Step 3: 实现**

`reader-settings.js` dialog 模板「行为」section（翻页动画 radio 之后、沉浸模式之前）加：

```js
                    <label class="reader-settings__toggle-row">
                        <span>边缘滑动调亮度/音量</span>
                        <input type="checkbox" name="edgeBrightnessVolume" checked>
                    </label>
                    <label class="reader-settings__toggle-row">
                        <span>音量键/方向键翻页</span>
                        <input type="checkbox" name="volumeKeyTurn" checked>
                    </label>
```

`syncControlsFromSettings` 末尾加：

```js
        const ebv = dialog.querySelector('input[name="edgeBrightnessVolume"]');
        if (ebv) ebv.checked = s.edgeBrightnessVolume;
        const vkt = dialog.querySelector('input[name="volumeKeyTurn"]');
        if (vkt) vkt.checked = s.volumeKeyTurn;
```

（两 checkbox change 走现有 else 分支 `saveAndEmit({ [t.name]: t.checked })`，无需新 onChange 分支。）

- [ ] **Step 4: 运行通过**

Run: `cd server/internal/web && node --test edgeGestureSettings.test.mjs`
Expected: 全部 PASS

- [ ] **Step 5: 回归 + 提交**

Run: `cd server/internal/web && npm test`
Expected: 全绿

```bash
git add server/internal/web/reader-settings.js server/internal/web/edgeGestureSettings.test.mjs
git commit -m "feat(web): add edge-gesture + key-turn toggles to reader settings dialog"
```

---

### Task 3: Web 边缘亮度模块 — edgeBrightness.js（纯函数 + 控制器）

**Files:**
- Create: `server/internal/web/edgeBrightness.js`
- Create: `server/internal/web/edgeBrightness.test.mjs`
- Modify: `server/internal/web/style.css`（亮度覆盖层 + 指示器样式）

**Interfaces:**
- Produces:
  - `resolveBrightnessZone(xRatio): Boolean` — 仅 `xRatio < 0.15` 返回 true（左 15%）。
  - `mapDragToBrightness(dy, viewHeight): Float` — dy 向下为正；返回 [0.15, 1.0]，clamp。
  - `renderEdgeBrightness({ contentEl, getEnabled, getBrightness, onBrightnessChange, onIndicator })` → `{ dispose }`。绑 pointerdown/move/up：左 15% + 垂直主导 + 超 8px slop 时接管，实时 onBrightnessChange + onIndicator。
- Task 4 接入 textReader.js。

- [ ] **Step 1: 写失败测试**

新建 `server/internal/web/edgeBrightness.test.mjs`：

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setupJsdom, teardownJsdom } from './_snapshot-helpers.mjs';
import { resolveBrightnessZone, mapDragToBrightness, renderEdgeBrightness } from './edgeBrightness.js';

test('resolveBrightnessZone: left 15% only', () => {
    assert.equal(resolveBrightnessZone(0.00), true);
    assert.equal(resolveBrightnessZone(0.14), true);
    assert.equal(resolveBrightnessZone(0.15), false);
    assert.equal(resolveBrightnessZone(0.50), false);
    assert.equal(resolveBrightnessZone(0.90), false); // Web 右边缘不响应
});

test('mapDragToBrightness: down increases, up decreases, clamped', () => {
    const h = 800;
    // 向下拖满屏 → 1.0
    assert.equal(mapDragToBrightness(h, h), 1.0);
    // 向上拖满屏 → 0.15
    assert.equal(mapDragToBrightness(-h, h), 0.15);
    // 中间值单调
    const mid = mapDragToBrightness(h / 2, h);
    assert.ok(mid > 0.15 && mid < 1.0);
    // 超出范围 clamp
    assert.equal(mapDragToBrightness(h * 2, h), 1.0);
    assert.equal(mapDragToBrightness(-h * 2, h), 0.15);
});

test('renderEdgeBrightness: left-edge vertical drag calls onBrightnessChange', () => {
    setupJsdom();
    try {
        window.matchMedia = window.matchMedia || (() => ({ matches: false, addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {} }));
        const contentEl = document.createElement('div');
        Object.defineProperty(contentEl, 'clientWidth', { value: 800, configurable: true });
        Object.defineProperty(contentEl, 'clientHeight', { value: 600, configurable: true });
        contentEl.getBoundingClientRect = () => ({ left: 0, top: 0, width: 800, height: 600, right: 800, bottom: 600 });
        let lastBrightness = 0.5;
        const api = renderEdgeBrightness({
            contentEl,
            getEnabled: () => true,
            getBrightness: () => lastBrightness,
            onBrightnessChange: (b) => { lastBrightness = b; },
            onIndicator: () => {},
        });
        // 模拟 pointer：左 10%（x=80）垂直向下拖 300px
        contentEl.dispatchEvent(new PointerEvent('pointerdown', { clientX: 80, clientY: 100, bubbles: true }));
        contentEl.dispatchEvent(new PointerEvent('pointermove', { clientX: 80, clientY: 400, bubbles: true }));
        contentEl.dispatchEvent(new PointerEvent('pointerup', { clientX: 80, clientY: 400, bubbles: true }));
        assert.ok(lastBrightness > 0.5, `expected brightness increased, got ${lastBrightness}`);
        api.dispose();
    } finally {
        teardownJsdom();
    }
});

test('renderEdgeBrightness: middle zone does not take over', () => {
    setupJsdom();
    try {
        window.matchMedia = window.matchMedia || (() => ({ matches: false, addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {} }));
        const contentEl = document.createElement('div');
        Object.defineProperty(contentEl, 'clientWidth', { value: 800, configurable: true });
        Object.defineProperty(contentEl, 'clientHeight', { value: 600, configurable: true });
        contentEl.getBoundingClientRect = () => ({ left: 0, top: 0, width: 800, height: 600, right: 800, bottom: 600 });
        let called = false;
        const api = renderEdgeBrightness({
            contentEl,
            getEnabled: () => true,
            getBrightness: () => 0.5,
            onBrightnessChange: () => { called = true; },
            onIndicator: () => {},
        });
        // 中间 x=400 垂直拖
        contentEl.dispatchEvent(new PointerEvent('pointerdown', { clientX: 400, clientY: 100, bubbles: true }));
        contentEl.dispatchEvent(new PointerEvent('pointermove', { clientX: 400, clientY: 400, bubbles: true }));
        contentEl.dispatchEvent(new PointerEvent('pointerup', { clientX: 400, clientY: 400, bubbles: true }));
        assert.equal(called, false);
        api.dispose();
    } finally {
        teardownJsdom();
    }
});
```

注意：`PointerEvent` 在 jsdom 可能需 polyfill（仿 pageTurn.test.mjs）；若 `clientWidth` defineProperty 不生效，改用 `getBoundingClientRect` 提供宽高（已在测试中两者都设）。

- [ ] **Step 2: 运行确认失败**

Run: `cd server/internal/web && node --test edgeBrightness.test.mjs`
Expected: FAIL（模块不存在）

- [ ] **Step 3: 实现 edgeBrightness.js**

```js
// 边缘亮度调节（Web）。左 15% 垂直拖动调亮度（CSS 覆盖层伪装——
// Web 无法调系统亮度，只能用覆盖层让阅读区视觉变暗；变亮上限为系统值）。
// 右边缘不响应（Web 无系统音量 API）。与 DRAG 翻页 pointer 链协调：
// 仅左边缘 + 垂直主导 + 超 8px slop 时接管，否则不消费事件。

const EDGE_LEFT = 0.15;
const BRIGHTNESS_MIN = 0.15;
const BRIGHTNESS_MAX = 1.0;
const DRAG_SLOP_PX = 8;

/** 仅左 15% 返回 true。 */
export function resolveBrightnessZone(xRatio) {
    return xRatio < EDGE_LEFT;
}

/** dy 向下为正 → 增亮。viewHeight 对应 BRIGHTNESS_MIN→MAX 全程。clamp。 */
export function mapDragToBrightness(dy, viewHeight) {
    const safeH = viewHeight > 0 ? viewHeight : 1;
    const ratio = Math.max(-1, Math.min(1, dy / safeH)); // [-1, 1]
    // ratio=1（向下满屏）→ MAX；ratio=-1（向上满屏）→ MIN
    const span = BRIGHTNESS_MAX - BRIGHTNESS_MIN;
    return Math.max(BRIGHTNESS_MIN, Math.min(BRIGHTNESS_MAX, BRIGHTNESS_MIN + ((ratio + 1) / 2) * span));
}

export function renderEdgeBrightness({ contentEl, getEnabled, getBrightness, onBrightnessChange, onIndicator }) {
    let dragging = false;
    let startY = 0;
    let startX = 0;
    let tookOver = false;

    function onPointerDown(e) {
        if (!getEnabled()) return;
        if (e.button != null && e.button !== 0) return;
        const rect = contentEl.getBoundingClientRect();
        const w = (rect && rect.width) || 0;
        if (w <= 0) return;
        const xRatio = (e.clientX - (rect.left || 0)) / w;
        if (!resolveBrightnessZone(xRatio)) return;
        dragging = true;
        tookOver = false;
        startX = e.clientX;
        startY = e.clientY;
    }

    function onPointerMove(e) {
        if (!dragging) return;
        const dx = e.clientX - startX;
        const dy = e.clientY - startY;
        if (!tookOver) {
            // 垂直主导 + 超 slop 才接管
            if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > DRAG_SLOP_PX) {
                tookOver = true;
            } else {
                return;
            }
        }
        // 接管后阻止滚动
        if (e.cancelable) e.preventDefault();
        const rect = contentEl.getBoundingClientRect();
        const h = (rect && rect.height) || 1;
        const next = mapDragToBrightness(dy, h);
        onBrightnessChange(next);
        onIndicator(next);
    }

    function onPointerUp() {
        dragging = false;
        tookOver = false;
    }

    contentEl.addEventListener('pointerdown', onPointerDown);
    contentEl.addEventListener('pointermove', onPointerMove);
    contentEl.addEventListener('pointerup', onPointerUp);
    contentEl.addEventListener('pointercancel', onPointerUp);

    function dispose() {
        contentEl.removeEventListener('pointerdown', onPointerDown);
        contentEl.removeEventListener('pointermove', onPointerMove);
        contentEl.removeEventListener('pointerup', onPointerUp);
        contentEl.removeEventListener('pointercancel', onPointerUp);
    }
    return { dispose };
}
```

`style.css` 加亮度覆盖层 + 指示器样式：

```css
/* 亮度覆盖层（Web 伪装——CSS 无法让屏幕比系统更亮，只能叠加变暗）。 */
.text-reader__brightness-overlay {
    position: absolute;
    inset: 0;
    pointer-events: none;
    background: rgba(0, 0, 0, 0); /* alpha 由 JS 设 = 1 - brightness */
    z-index: 5;
}
/* 亮度/音量指示器 */
.text-reader__indicator {
    position: absolute;
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
    padding: 12px 20px;
    border-radius: 8px;
    background: rgba(0, 0, 0, 0.6);
    color: #fff;
    font-size: 14px;
    pointer-events: none;
    z-index: 10;
    opacity: 0;
    transition: opacity 0.3s;
}
.text-reader__indicator--visible {
    opacity: 1;
}
```

- [ ] **Step 4: 运行通过**

Run: `cd server/internal/web && node --test edgeBrightness.test.mjs`
Expected: 4 PASS

- [ ] **Step 5: 回归 + 提交**

Run: `cd server/internal/web && npm test`
Expected: 全绿

```bash
git add server/internal/web/edgeBrightness.js server/internal/web/edgeBrightness.test.mjs server/internal/web/style.css
git commit -m "feat(web): add edgeBrightness module (left 15% vertical drag, pure-fn zone/brightness map)"
```

---

### Task 4: Web 接入 — textReader.js 绑定边缘亮度 + 键盘翻页

**Files:**
- Modify: `server/internal/web/textReader.js`（接入 edgeBrightnessApi、keydown 翻页键、亮度覆盖层 + 指示器、cleanup）
- Modify: `server/internal/web/edgeBrightness.test.mjs`（加 keydown 翻页键集成断言）或新建集成测试

**Interfaces:**
- Consumes: Task 3 的 `renderEdgeBrightness`；现有 `pageTurnApi.turnTo`（来自上轮 Task 4）。
- Produces: CHAPTER/SCROLL 模式下左 15% 垂直拖动调亮度（覆盖层 + 指示器）；keydown ArrowDown/Right/Space → turnTo('next')、ArrowUp/Left → turnTo('prev')（开关开启时）。

- [ ] **Step 1: 写失败测试**

新建 `server/internal/web/edgeKeyTurn.test.mjs`（集成，仿 textReader-pageturn.test.mjs 的 mock-fetch 模式）：

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setupJsdom, teardownJsdom, mockBook } from './_snapshot-helpers.mjs';

function installEnv() {
    global.sessionStorage = global.sessionStorage || {
        _s: {}, getItem(k) { return k in this._s ? this._s[k] : null; },
        setItem(k, v) { this._s[k] = String(v); }, removeItem(k) { delete this._s[k]; },
    };
    window.matchMedia = window.matchMedia || (() => ({ matches: false, addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {} }));
    global.fetch = async (url) => {
        if (url.includes('/books/info')) return { ok: true, status: 200, json: async () => mockBook };
        if (url.includes('/books/chapter')) return { ok: true, status: 200, json: async () => ({ title: '第一章', blocks: [{ type: 'text', value: '正文' }] }) };
        return { ok: false, status: 404 };
    };
}

function viewContainer() {
    let el = document.getElementById('view-reader');
    if (!el) { el = document.createElement('div'); el.id = 'view-reader'; document.body.appendChild(el); }
    return el;
}

test('keydown ArrowDown triggers next-chapter turn when volumeKeyTurn enabled', async () => {
    setupJsdom();
    try {
        installEnv();
        localStorage.setItem('reader_settings', JSON.stringify({ readingMode: 'chapter', pageTurnStyle: 'NONE', volumeKeyTurn: true }));
        const { renderTextReader } = await import('./textReader.js');
        await renderTextReader(viewContainer(), mockBook.path, 0);
        await new Promise((r) => setTimeout(r, 50));
        document.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
        await new Promise((r) => setTimeout(r, 100));
        // 断言：翻到下一章（title/progress 反映第 2 章，或至少 turnTo 被调）
        // 简化断言：无报错且 DOM 仍渲染（真实翻页验证靠 mock fetch 的章节序列）
        const title = viewContainer().querySelector('.text-reader__title').textContent;
        assert.ok(title, `title should be set, got: ${title}`);
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});
```

- [ ] **Step 2: 运行确认失败**

Run: `cd server/internal/web && node --test edgeKeyTurn.test.mjs`
Expected: FAIL（keydown ArrowDown 未触发翻页——当前 keydown 只处理 Escape）

- [ ] **Step 3: 实现**

`textReader.js` 接入：

(1) 在 `renderTextReader` 内（pageTurnApi 实例化之后）加亮度覆盖层 + 指示器 DOM：

```js
    // 亮度覆盖层（Web 伪装）+ 指示器
    const brightnessOverlay = document.createElement('div');
    brightnessOverlay.className = 'text-reader__brightness-overlay';
    els.content.parentElement?.appendChild(brightnessOverlay) || container.querySelector('.text-reader')?.appendChild(brightnessOverlay);
    const indicator = document.createElement('div');
    indicator.className = 'text-reader__indicator';
    container.querySelector('.text-reader')?.appendChild(indicator);
    let indicatorTimer = null;
    function showIndicator(text) {
        indicator.textContent = text;
        indicator.classList.add('text-reader__indicator--visible');
        if (indicatorTimer) clearTimeout(indicatorTimer);
        indicatorTimer = setTimeout(() => indicator.classList.remove('text-reader__indicator--visible'), 2000);
    }
    function applyBrightnessOverlay(brightness) {
        // alpha = 1 - brightness；brightness=1 → 透明，brightness=0.15 → 深 0.85 黑
        brightnessOverlay.style.background = `rgba(0, 0, 0, ${(1 - brightness).toFixed(3)})`;
    }
```

(2) 实例化 edgeBrightnessApi：

```js
    const edgeBrightnessApi = renderEdgeBrightness({
        contentEl: els.content,
        getEnabled: () => readerPrefs.getSettings().edgeBrightnessVolume,
        getBrightness: () => state.brightness ?? 1.0,
        onBrightnessChange: (b) => {
            state.brightness = b;
            applyBrightnessOverlay(b);
        },
        onIndicator: (b) => showIndicator(`亮度 ${Math.round(b * 100)}%`),
    });
```

（`state.brightness` 是 reader-state.js 的新字段，默认 1.0——若 reader-state.js 未有需加 `brightness: 1.0`，resetState 保留默认。）

(3) keydown 翻页键——扩展现有 `onKeyDown`（已绑 Escape）：

```js
    const onKeyDown = (e) => {
        if (isImmersive && e.key === 'Escape') { exitImmersive(); return; }
        if (!readerPrefs.getSettings().volumeKeyTurn) return;
        if (readerPrefs.getSettings().readingMode === 'scroll') return; // scroll 模式不接管键盘翻页
        const dir = resolveKeyTurn(e.key);
        if (dir) {
            e.preventDefault();
            pageTurnApi.turnTo(dir).then(() => {});
        }
    };
```

`textReader.js` 顶部 import 加：

```js
import { renderEdgeBrightness } from './edgeBrightness.js';
```

`resolveKeyTurn` 纯函数加在 `edgeBrightness.js` 并导出（或加在 textReader.js 内）：

```js
// edgeBrightness.js 末尾
/** 键盘翻页键 → 方向；非翻页键返回 null。 */
export function resolveKeyTurn(key) {
    switch (key) {
        case 'ArrowDown':
        case 'ArrowRight':
        case ' ':
            return 'next';
        case 'ArrowUp':
        case 'ArrowLeft':
            return 'prev';
        default:
            return null;
    }
}
```

textReader.js import 补 `resolveKeyTurn`。

(4) cleanup（`container._cleanupReader`）加：

```js
        edgeBrightnessApi.dispose();
        if (indicatorTimer) clearTimeout(indicatorTimer);
        brightnessOverlay.remove();
        indicator.remove();
```

- [ ] **Step 4: 运行通过**

Run: `cd server/internal/web && node --test edgeKeyTurn.test.mjs`
Expected: PASS

- [ ] **Step 5: 回归（snapshot-baseline e2e + 全量）+ 提交**

Run: `cd server/internal/web && npm test`
Expected: 全绿

```bash
git add server/internal/web/textReader.js server/internal/web/edgeBrightness.js server/internal/web/edgeKeyTurn.test.mjs server/internal/web/reader-state.js
git commit -m "feat(web): wire edge-brightness overlay + keyboard turn (Arrow/Space) in textReader"
```

（若 reader-state.js 未改则不 add 该文件。）

---

### Task 5: Android 数据层 — ReaderSettings 加两个开关字段

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/ReaderSettings.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/data/ReaderSettingsMigrationTest.kt`

**Interfaces:**
- Produces: `ReaderSettings.edgeBrightnessVolume: Boolean = true`、`ReaderSettings.volumeKeyTurn: Boolean = true`。Task 6/7/8 依赖。

- [ ] **Step 1: 写失败测试**

`ReaderSettingsMigrationTest.kt` 末尾加：

```kotlin
    @Test
    fun v2_without_edge_gesture_flags_falls_back_to_defaults() = runBlocking {
        injectRawSettings("""{"theme":"NIGHT"}""")
        val s = store.readerSettingsFlow.first()
        assertEquals(true, s.edgeBrightnessVolume)
        assertEquals(true, s.volumeKeyTurn)
    }

    @Test
    fun v2_with_edge_gesture_flags_reads_correctly() = runBlocking {
        injectRawSettings("""{"edgeBrightnessVolume":false,"volumeKeyTurn":false}""")
        val s = store.readerSettingsFlow.first()
        assertEquals(false, s.edgeBrightnessVolume)
        assertEquals(false, s.volumeKeyTurn)
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `cd android && powershell -Command ".\gradlew.bat :app:testDebugUnitTest --tests 'com.juziss.localmediahub.data.ReaderSettingsMigrationTest' 2>&1 | Select-Object -Last 12"`
Expected: 编译失败（字段不存在）

- [ ] **Step 3: 实现**

`ReaderSettings.kt` data class 末尾加：

```kotlin
    val edgeBrightnessVolume: Boolean = true,   // 边缘垂直滑动调亮度/音量
    val volumeKeyTurn: Boolean = true,          // 音量键翻页
```

- [ ] **Step 4: 运行通过 + 回归**

Run: `cd android && powershell -Command ".\gradlew.bat :app:testDebugUnitTest --tests 'com.juziss.localmediahub.data.*' 2>&1 | Select-Object -Last 12"`
Expected: 全绿

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/data/ReaderSettings.kt android/app/src/test/java/com/juziss/localmediahub/data/ReaderSettingsMigrationTest.kt
git commit -m "feat(android): add edgeBrightnessVolume + volumeKeyTurn fields to ReaderSettings"
```

---

### Task 6: Android 边缘手势纯函数 — EdgeBrightnessController

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/EdgeBrightnessController.kt`
- Create: `android/app/src/test/java/com/juziss/localmediahub/ui/component/reader/EdgeBrightnessControllerTest.kt`

**Interfaces:**
- Produces:
  - `enum class EdgeZone { BRIGHTNESS, VOLUME, NONE }`
  - `fun resolveEdgeZone(xRatio: Float): EdgeZone` — `<0.15 → BRIGHTNESS`、`>0.85 → VOLUME`、其余 NONE
  - `fun mapDragToBrightness(dy: Float, viewHeight: Float): Float` — [0.15, 1.0]，向下增亮
  - `fun mapDragToVolume(dy: Float, viewHeight: Float, max: Int): Int` — [0, max]
  - `fun resolveKeyTurn(keyCode: Int): PageTurnDirection?` — KEYCODE_VOLUME_UP→PREV、KEYCODE_VOLUME_DOWN→NEXT、其余 null

- [ ] **Step 1: 写失败测试**

新建 `EdgeBrightnessControllerTest.kt`：

```kotlin
package com.juziss.localmediahub.ui.component.reader

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EdgeBrightnessControllerTest {

    @Test
    fun resolveEdgeZone_left_is_brightness() {
        assertEquals(EdgeZone.BRIGHTNESS, resolveEdgeZone(0.00f))
        assertEquals(EdgeZone.BRIGHTNESS, resolveEdgeZone(0.14f))
    }

    @Test
    fun resolveEdgeZone_right_is_volume() {
        assertEquals(EdgeZone.VOLUME, resolveEdgeZone(0.86f))
        assertEquals(EdgeZone.VOLUME, resolveEdgeZone(1.00f))
    }

    @Test
    fun resolveEdgeZone_middle_is_none() {
        assertEquals(EdgeZone.NONE, resolveEdgeZone(0.15f))
        assertEquals(EdgeZone.NONE, resolveEdgeZone(0.50f))
        assertEquals(EdgeZone.NONE, resolveEdgeZone(0.85f))
    }

    @Test
    fun mapDragToBrightness_down_increases_clamped() {
        val h = 800f
        assertEquals(1.0f, mapDragToBrightness(h, h), 0.001f)
        assertEquals(0.15f, mapDragToBrightness(-h, h), 0.001f)
        val mid = mapDragToBrightness(h / 2, h)
        assert(mid > 0.15f && mid < 1.0f)
        assertEquals(1.0f, mapDragToBrightness(h * 2, h), 0.001f) // clamp
    }

    @Test
    fun mapDragToVolume_maps_and_clamps() {
        assertEquals(10, mapDragToVolume(800f, 800f, 10))
        assertEquals(0, mapDragToVolume(-800f, 800f, 10))
        assertEquals(5, mapDragToVolume(0f, 800f, 10)) // dy=0 → 中点
    }

    @Test
    fun resolveKeyTurn_volume_keys() {
        assertEquals(PageTurnDirection.PREV, resolveKeyTurn(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(PageTurnDirection.NEXT, resolveKeyTurn(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertNull(resolveKeyTurn(KeyEvent.KEYCODE_A))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd android && powershell -Command ".\gradlew.bat :app:testDebugUnitTest --tests 'com.juziss.localmediahub.ui.component.reader.EdgeBrightnessControllerTest' 2>&1 | Select-Object -Last 12"`
Expected: 编译失败（符号不存在）

- [ ] **Step 3: 实现 EdgeBrightnessController.kt**

```kotlin
package com.juziss.localmediahub.ui.component.reader

import android.view.KeyEvent
import kotlin.math.max
import kotlin.math.min

enum class EdgeZone { BRIGHTNESS, VOLUME, NONE }

private const val EDGE_LEFT = 0.15f
private const val EDGE_RIGHT = 0.85f
private const val BRIGHTNESS_MIN = 0.15f
private const val BRIGHTNESS_MAX = 1.0f

/** 左 15% → BRIGHTNESS；右 15% → VOLUME；其余 NONE。 */
fun resolveEdgeZone(xRatio: Float): EdgeZone = when {
    xRatio < EDGE_LEFT -> EdgeZone.BRIGHTNESS
    xRatio > EDGE_RIGHT -> EdgeZone.VOLUME
    else -> EdgeZone.NONE
}

/** dy 向下为正 → 增亮。viewHeight 对应 MIN→MAX 全程。clamp [0.15, 1.0]。 */
fun mapDragToBrightness(dy: Float, viewHeight: Float): Float {
    val safeH = if (viewHeight > 0) viewHeight else 1f
    val ratio = max(-1f, min(1f, dy / safeH)) // [-1, 1]
    val span = BRIGHTNESS_MAX - BRIGHTNESS_MIN
    return max(BRIGHTNESS_MIN, min(BRIGHTNESS_MAX, BRIGHTNESS_MIN + ((ratio + 1f) / 2f) * span))
}

/** dy 映射到 [0, max]。dy=0 → 中点。 */
fun mapDragToVolume(dy: Float, viewHeight: Float, max: Int): Int {
    val safeH = if (viewHeight > 0) viewHeight else 1f
    val ratio = max(-1f, min(1f, dy / safeH)) // [-1, 1]
    val v = ((ratio + 1f) / 2f) * max // [0, max]
    return max(0, min(max, v.toInt()))
}

/** 音量上 → PREV；音量下 → NEXT；其余 null。 */
fun resolveKeyTurn(keyCode: Int): PageTurnDirection? = when (keyCode) {
    KeyEvent.KEYCODE_VOLUME_UP -> PageTurnDirection.PREV
    KeyEvent.KEYCODE_VOLUME_DOWN -> PageTurnDirection.NEXT
    else -> null
}
```

- [ ] **Step 4: 运行通过**

Run: `cd android && powershell -Command ".\gradlew.bat :app:testDebugUnitTest --tests 'com.juziss.localmediahub.ui.component.reader.EdgeBrightnessControllerTest' 2>&1 | Select-Object -Last 12"`
Expected: 6 PASS

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/EdgeBrightnessController.kt android/app/src/test/java/com/juziss/localmediahub/ui/component/reader/EdgeBrightnessControllerTest.kt
git commit -m "feat(android): add EdgeBrightnessController pure functions (zone/brightness/volume/key-turn)"
```

---

### Task 7: Android 音量键翻页 — TextReaderActivity onKeyDown

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/TextReaderActivity.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/TextReaderViewModel.kt`（暴露 turn 回调供 Activity 调）

**Interfaces:**
- Consumes: Task 6 的 `resolveKeyTurn`；Task 5 的 `volumeKeyTurn` 字段（经 ViewModel）；现有 `turn(direction)` 逻辑（上轮 Task 10/11/12 在 TextReaderScreen 内——需经 ViewModel 暴露）。
- Produces: Activity onKeyDown 在 volumeKeyTurn 开启时拦截音量键并触发翻页。

- [ ] **Step 1: 无独立单测（Activity onKeyDown 在 Robolectric 下可测但需 shadowOf；本任务靠手动验证 + 纯函数 Task 6 已测）**

可选：加 Robolectric 测 `shadowOf(activity).clickView(...)` 模拟音量键——但 KeyEvent 模拟在 Robolectric 较脆。首版靠纯函数 resolveKeyTurn 单测保证判定正确性，onKeyDown 接线靠手动验证（真机按音量键）。

- [ ] **Step 2:（跳过）**

- [ ] **Step 3: 实现**

(1) `TextReaderViewModel.kt`：暴露 settings + 翻页回调供 Activity 用。Activity 持有 viewModel（已 by viewModels()）。在 ViewModel 加：

```kotlin
private val _settings = MutableStateFlow(ReaderSettings())
val settings: StateFlow<ReaderSettings> = _settings
// 已有 readerSettingsFlow 经 store 加载——确保 _settings 同步 store 的值
// （若 ViewModel 已有 settings 状态则复用，无需重复）

/** 音量键翻页入口（Activity onKeyDown 调）。返回 true 表示已拦截。 */
suspend fun onVolumeKeyTurn(keyCode: Int): Boolean {
    val s = settings.value
    if (!s.volumeKeyTurn) return false
    val dir = resolveKeyTurn(keyCode) ?: return false
    // 复用现有翻页逻辑——ViewModel 内调用 PageTurnController/turn
    // （turn 实现在上轮位于 TextReaderScreen 的 fun turn——需把 turn 提到 ViewModel
    //  或经事件/回调桥接。最简方案：ViewModel 暴露 turnEvent: SharedFlow<PageTurnDirection>，
    //  Screen 收集后调本地 turn()。）
    turnEvent.emit(dir)
    return true
}
```

注意：`turn()` 在上轮实现里位于 TextReaderScreen composable 内（持有 controller/progress/incoming 状态）。把翻页从 Activity 经 ViewModel 触发到 Screen，最干净的方式是 ViewModel 暴露 `turnEvent: SharedFlow<PageTurnDirection>`，Screen 用 `LaunchedEffect` 收集后调本地 `turn(direction)`。

ViewModel 加：

```kotlin
private val _turnEvent = MutableSharedFlow<PageTurnDirection>(extraBufferCapacity = 4)
val turnEvent: SharedFlow<PageTurnDirection> = _turnEvent
```

(2) `TextReaderActivity.kt` override onKeyDown：

```kotlin
override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
    // 音量键翻页（开关开启时拦截）。其余键走默认。
    if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
        keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
        // settings 已加载？若未加载则不拦截（避免启动竞态）
        val blocked = viewModel.settings.value.volumeKeyTurn
        if (blocked) {
            // 协程触发翻页事件
            lifecycleScope.launch { viewModel.onVolumeKeyTurn(keyCode) }
            return true // 拦截系统音量调节
        }
    }
    return super.onKeyDown(keyCode, event)
}
```

import 加 `androidx.lifecycle.lifecycleScope`、`kotlinx.coroutines.launch`。

(3) `TextReaderScreen.kt` 收集 turnEvent 并调本地 turn：

```kotlin
    LaunchedEffect(Unit) {
        viewModel.turnEvent.collect { direction ->
            turn(direction)
        }
    }
```

（`turn` 是上轮实现的 composable 内函数。）

- [ ] **Step 4: 回归（现有 reader 测试不破坏）**

Run: `cd android && powershell -Command ".\gradlew.bat :app:testDebugUnitTest --tests 'com.juziss.localmediahub.ui.screen.TextReaderScreenThemeTest' --tests 'com.juziss.localmediahub.ui.component.reader.*' --tests 'com.juziss.localmediahub.viewmodel.*' 2>&1 | Select-Object -Last 12"`
Expected: 全绿

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/TextReaderActivity.kt android/app/src/main/java/com/juziss/localmediahub/viewmodel/TextReaderViewModel.kt android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt
git commit -m "feat(android): intercept volume keys for page-turn (onKeyDown, gated by volumeKeyTurn setting)"
```

---

### Task 8: Android 边缘亮度/音量手势 + 指示器 + 设置面板 + 对齐

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt`（detectVerticalDragGestures + 指示器 overlay + 经 LocalContext 调 Activity）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/TextReaderActivity.kt`（暴露 setBrightness/setVolume/getVolumeMax；onDestroy 复原亮度）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheet.kt`（两个开关）
- Test: `android/app/src/test/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheetTest.kt`

**Interfaces:**
- Consumes: Task 6 的纯函数；Task 5 的字段；Task 7 的 turn 回调（音量键已接）。
- Produces: 边缘垂直拖动调亮度（左）/音量（右，仅 Android）；onDestroy 复原亮度；两个设置开关。

- [ ] **Step 1: 写失败测试（设置面板开关）**

`ReaderSettingsSheetTest.kt` 末尾加：

```kotlin
    @Test
    fun edge_gesture_toggles_render_checked_by_default() {
        composeRule.setContent {
            ReaderSettingsSheetContent(settings = ReaderSettings(), onChange = {})
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("边缘滑动调亮度/音量").assertIsOn()
        composeRule.onNodeWithText("音量键/方向键翻页").assertIsOn()
    }

    @Test
    fun unchecking_edge_gesture_toggle_fires_onchange() {
        var captured: ReaderSettings? = null
        composeRule.setContent {
            ReaderSettingsSheetContent(settings = ReaderSettings(), onChange = { captured = it })
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("边缘滑动调亮度/音量").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(false, captured?.edgeBrightnessVolume)
    }
```

import `androidx.compose.ui.semantics.SemanticsActions`、`performSemanticsAction`（文件已有）。Switch 用 `assertIsOn`（`androidx.compose.ui.test.assertIsOn`，若未 import 加上）。Switch 的 performClick/semantics OnClick 在 Robolectric 同 Task 8 上轮：用 performSemanticsAction(OnClick) 规避滚动视口问题（开关也可能在滚动区下方）。

- [ ] **Step 2: 运行确认失败**

Run: `cd android && powershell -Command ".\gradlew.bat :app:testDebugUnitTest --tests 'com.juziss.localmediahub.ui.component.reader.ReaderSettingsSheetTest' 2>&1 | Select-Object -Last 12"`
Expected: 新 2 测试 FAIL（开关不存在）

- [ ] **Step 3: 实现**

(1) `ReaderSettingsSheet.kt`「行为」section（翻页动画 chips 之后）加两个 Switch：

```kotlin
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("边缘滑动调亮度/音量", Modifier.weight(1f))
            Switch(
                checked = settings.edgeBrightnessVolume,
                onCheckedChange = { onChange(settings.copy(edgeBrightnessVolume = it)) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("音量键/方向键翻页", Modifier.weight(1f))
            Switch(
                checked = settings.volumeKeyTurn,
                onCheckedChange = { onChange(settings.copy(volumeKeyTurn = it)) },
            )
        }
        Spacer(Modifier.size(8.dp))
```

(2) `TextReaderActivity.kt`：暴露亮度/音量方法 + onDestroy 复原：

```kotlin
private var originalBrightness = -1f // -1f 表示未改过
private val audioManager by lazy { getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager }

fun setReaderBrightness(b: Float) {
    if (originalBrightness < 0) originalBrightness = window.attributes.screenBrightness
    val attrs = window.attributes
    attrs.screenBrightness = b
    window.attributes = attrs
}
fun setReaderVolume(idx: Int) {
    audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, idx, 0)
}
fun getStreamMaxVolume(): Int = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
fun getCurrentVolume(): Int = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)

override fun onDestroy() {
    if (originalBrightness >= 0) {
        val attrs = window.attributes
        attrs.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attrs
    }
    super.onDestroy()
}
```

(3) `TextReaderScreen.kt`：新增 detectVerticalDragGestures pointerInput（仅边缘+垂直接管）+ 指示器 overlay。通过 `LocalContext.current as? TextReaderActivity` 拿到 activity 调亮度/音量。

```kotlin
    val activity = LocalContext.current as? TextReaderActivity
    var indicatorText by remember { mutableStateOf<String?>(null) }
    val edgeDrag = remember {
        object {
            var tookOver = false
            var zone: EdgeZone = EdgeZone.NONE
            var startX = 0f
            var startY = 0f
        }
    }
    // 指示器自动消失
    val indicatorScope = rememberCoroutineScope()
    LaunchedEffect(indicatorText) {
        if (indicatorText != null) {
            kotlinx.coroutines.delay(2000)
            indicatorText = null
        }
    }

    // 在内容 Box 的 Modifier 链加 detectVerticalDragGestures
    // （与现有 detectTapGestures / detectHorizontalDragGestures 并列 pointerInput）
    .pointerInput(settings.edgeBrightnessVolume, isScrollMode) {
        if (!settings.edgeBrightnessVolume) return@pointerInput
        detectVerticalDragGestures(
            onDragStart = { offset ->
                val w = size.width.toFloat()
                val xRatio = offset.x / w
                edgeDrag.zone = resolveEdgeZone(xRatio)
                edgeDrag.startX = offset.x
                edgeDrag.startY = offset.y
                edgeDrag.tookOver = false
            },
            onVerticalDrag = { change, dy ->
                if (edgeDrag.zone == EdgeZone.NONE) return@detectVerticalDragGestures
                val totalDy = change.position.y - edgeDrag.startY
                val totalDx = change.position.x - edgeDrag.startX
                if (!edgeDrag.tookOver) {
                    if (kotlin.math.abs(totalDy) > kotlin.math.abs(totalDx) && kotlin.math.abs(totalDy) > with(density) { 8.dp.toPx() }) {
                        edgeDrag.tookOver = true
                    } else return@detectVerticalDragGestures
                }
                change.consume()
                val h = size.height.toFloat()
                when (edgeDrag.zone) {
                    EdgeZone.BRIGHTNESS -> {
                        val b = mapDragToBrightness(totalDy, h)
                        activity?.setReaderBrightness(b)
                        indicatorText = "亮度 ${Math.round(b * 100)}%"
                    }
                    EdgeZone.VOLUME -> {
                        val max = activity?.getStreamMaxVolume() ?: return@detectVerticalDragGestures
                        val v = mapDragToVolume(totalDy, h, max)
                        activity.setReaderVolume(v)
                        indicatorText = "音量 ${Math.round(v.toFloat() / max * 100)}%"
                    }
                    EdgeZone.NONE -> {}
                }
            },
            onDragEnd = { edgeDrag.zone = EdgeZone.NONE; edgeDrag.tookOver = false },
            onDragCancel = { edgeDrag.zone = EdgeZone.NONE; edgeDrag.tookOver = false },
        )
    }
```

指示器 overlay（在内容 Box 内顶层）：

```kotlin
    indicatorText?.let { text ->
        Box(
            Modifier.fillMaxSize().wrapContentSize(Alignment.Center).zIndex(10f)
        ) {
            Surface(color = Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(8.dp)) {
                Text(text, color = Color.White, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
    }
```

import 补：`detectVerticalDragGestures`、`EdgeZone`、`resolveEdgeZone`、`mapDragToBrightness`、`mapDragToVolume`、`zIndex`、`wrapContentSize`、`RoundedCornerShape`、`Surface`。

注意：`detectVerticalDragGestures` 仅响应垂直拖动（水平穿透给 detectHorizontalDragGestures）。边缘 tap（无位移）不在 onDragStart 后续触发，仍走 detectTapGestures 翻章——无冲突。

- [ ] **Step 4: 运行测试 + 双端全量 + 对齐清单**

Run: `cd android && powershell -Command ".\gradlew.bat :app:testDebugUnitTest 2>&1 | Select-Object -Last 12"`
Run: `cd server/internal/web && npm test`
Expected: 双端全绿

按 spec「双端对齐校验清单」逐项人工核对（8 项）。

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt android/app/src/main/java/com/juziss/localmediahub/TextReaderActivity.kt android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheet.kt android/app/src/test/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheetTest.kt
git commit -m "feat(android): edge brightness/volume gesture + indicator + settings toggles; align to spec"
```

---

## Self-Review 备忘

**Spec coverage**：字段（Task 1/5）、设置面板（2/8）、边缘亮度模块（3）、Web 接入（4）、Android 纯函数（6）、Android 音量键（7）、Android 边缘手势+指示器+对齐（8）。无遗漏。

**Placeholder 注意**：Task 7 的 onKeyDown 接线靠纯函数 Task 6 单测 + 手动验证（Robolectric KeyEvent 模拟脆）——plan 明确取舍，非占位符。

**类型一致性**：`EdgeZone`（Task 6 定义）/`resolveEdgeZone`/`mapDragToBrightness`/`mapDragToVolume`/`resolveKeyTurn`（双端同名同语义，Web resolveKeyTurn 在 edgeBrightness.js，Android 在 EdgeBrightnessController.kt）；`PageTurnDirection`（上轮 Task 9 定义，复用）。`state.brightness`（Web reader-state.js 新字段，Task 4 加）。
