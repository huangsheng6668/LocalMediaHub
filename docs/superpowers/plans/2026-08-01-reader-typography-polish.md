# 阅读器排版细节打磨实施计划（字间距 / 字体扩充 / 自定义主题色）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Web + Android 双端阅读器新增字间距调节、字体扩充（文楷/黑体/等宽）与自定义主题色（CUSTOM 独立主题），双端字段与行为完全对齐。

**Architecture:** 最小侵入式增量改动。Web 端在 `readerPrefs.js`（数据层）/`reader-settings.js`（设置面板）/`textReader.js`+`style.css`（渲染）三个既有边界内扩展；Android 端在 `ReaderSettings.kt`（数据层）/`ReaderSettingsSheet.kt`（设置面板）/`ReaderThemeWrapper.kt`+`TextReaderScreen.kt`（渲染）内扩展。CUSTOM 主题的解析由各端渲染处处理（Web `applySettingsToUI` / Android 抽出的纯函数 `resolveReaderColors`），三色 null 回退到系统深浅对应的 DAY/NIGHT。

**Tech Stack:** Web — ES modules + node:test/jsdom（无构建步骤）；Android — Compose + Robolectric + Gson（DataStore 持久化）。

## Global Constraints

- 双端字段必须一致：`letterSpacing: Float/em`（默认 0，范围 0–1.0，步进 0.05）、`customBg/customFg/customMuted: String?/#RRGGBB`（默认 null）。
- CUSTOM 解析规则：`customX` 为 null 时该项回退到系统深浅对应的 DAY/NIGHT 预设色；chromeBg=bg、chromeFg=fg、border=muted（从三色派生，不新增字段）。
- 切换主题不清空三色 custom 值。
- 不新增任何依赖：Web 无新 npm 包；Android 无新 Gradle 依赖。
- 7 个既有主题预设色值零改动；THEME_PRESETS（Web）与 6 个预设枚举（Android）仅增不改。
- 测试先行（TDD）：每个任务先写失败测试，再实现。
- 提交信息风格沿用仓库习惯：`feat(web): ...` / `feat(android): ...`。
- 文楷字体文件体积不可接受或下载失败时，按 Task 5 回退方案执行（仅落地 MONO），必须提交信息与报告中说明。

---

### Task 1: Web 数据层 — readerPrefs.js 新字段/迁移/字体映射

**Files:**
- Modify: `server/internal/web/readerPrefs.js`（DEFAULT_SETTINGS 42-53 行、migrateV1toV2 57-98 行、FONT_FAMILIES 16-20 行、clamp 工具 100-101 行）
- Create: `server/internal/web/readerPrefs.test.mjs`

**Interfaces:**
- Produces: `DEFAULT_SETTINGS` 含 `letterSpacing: 0`、`customBg: null`、`customFg: null`、`customMuted: null`；`FONT_FAMILIES` 含 `HEITI`/`MONO`；`migrateV1toV2` 迁移上述字段；`theme === 'CUSTOM'` 被保留。Task 2/3 依赖这些导出。

- [ ] **Step 1: 写失败测试**

新建 `server/internal/web/readerPrefs.test.mjs`（migrateV1toV2 是纯函数，无需 jsdom；模块顶层无 window 访问）：

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { migrateV1toV2 } from './readerPrefs.js';

test('defaults include new typography fields', () => {
    const s = migrateV1toV2(null);
    assert.equal(s.letterSpacing, 0);
    assert.equal(s.customBg, null);
    assert.equal(s.customFg, null);
    assert.equal(s.customMuted, null);
});

test('migrate keeps valid letterSpacing and clamps out-of-range', () => {
    assert.equal(migrateV1toV2({ letterSpacing: 0.25 }).letterSpacing, 0.25);
    assert.equal(migrateV1toV2({ letterSpacing: 5 }).letterSpacing, 1);
    assert.equal(migrateV1toV2({ letterSpacing: -1 }).letterSpacing, 0);
    assert.equal(migrateV1toV2({ letterSpacing: 'x' }).letterSpacing, 0);
});

test('migrate keeps valid custom colors and drops invalid', () => {
    const s = migrateV1toV2({ customBg: '#ABCDEF', customFg: '#1a2b3c', customMuted: 'red' });
    assert.equal(s.customBg, '#ABCDEF');
    assert.equal(s.customFg, '#1a2b3c');
    assert.equal(s.customMuted, null);
});

test('migrate keeps CUSTOM theme', () => {
    assert.equal(migrateV1toV2({ theme: 'CUSTOM' }).theme, 'CUSTOM');
});
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd server/internal/web && node --test readerPrefs.test.mjs`
Expected: 4 个测试 FAIL（`letterSpacing` 为 undefined、`customBg` 为 undefined、`theme` 丢为 DAY）

- [ ] **Step 3: 实现**

修改 `readerPrefs.js`：

(1) `FONT_FAMILIES`（16-20 行）末尾加两条：

```js
    HEITI: '"Heiti SC", "Microsoft YaHei", "PingFang SC", sans-serif',
    MONO: '"Cascadia Mono", Consolas, "Courier New", monospace',
```

(2) `DEFAULT_SETTINGS`（42-53 行）末尾加：

```js
    letterSpacing: 0, // 0..1 em，步进 0.05
    customBg: null,   // #RRGGBB，仅 theme=CUSTOM 时生效
    customFg: null,
    customMuted: null,
```

(3) `migrateV1toV2` 中 theme 校验（75 行）改为：

```js
    if (typeof old.theme === 'string' && (THEME_PRESETS.hasOwnProperty(old.theme) || old.theme === 'CUSTOM')) {
        out.theme = old.theme;
    }
```

(4) `migrateV1toV2` 末尾（96 行 return 之前）加：

```js
    // 排版打磨（2026-08-01）：新字段。注意步进 0.05，不能复用 clampFloat（步进 0.1）
    if (typeof old.letterSpacing === 'number' && Number.isFinite(old.letterSpacing)) {
        out.letterSpacing = clampLetterSpacing(old.letterSpacing);
    }
    if (typeof old.customBg === 'string' && HEX6.test(old.customBg)) out.customBg = old.customBg;
    if (typeof old.customFg === 'string' && HEX6.test(old.customFg)) out.customFg = old.customFg;
    if (typeof old.customMuted === 'string' && HEX6.test(old.customMuted)) out.customMuted = old.customMuted;
```

(5) 工具函数区（100-101 行附近）加：

```js
const HEX6 = /^#[0-9a-fA-F]{6}$/;
function clampLetterSpacing(n) { return Math.max(0, Math.min(1, Math.round(n * 20) / 20)); }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd server/internal/web && node --test readerPrefs.test.mjs`
Expected: 4 个测试全部 PASS

- [ ] **Step 5: 提交**

```bash
git add server/internal/web/readerPrefs.js server/internal/web/readerPrefs.test.mjs
git commit -m "feat(web): add letterSpacing/custom-theme-color fields and HEITI/MONO fonts to readerPrefs"
```

---

### Task 2: Web 设置面板 — reader-settings.js 控件 + style.css

**Files:**
- Modify: `server/internal/web/reader-settings.js`（FONT_OPTIONS 14-19 行、THEME_OPTIONS 21-30 行、dialog 模板 43-128 行、syncControlsFromSettings 132-168 行、onChange 180-199 行）
- Modify: `server/internal/web/style.css`（.reader-settings__theme-swatch 区 2710-2719 行后）
- Create: `server/internal/web/reader-settings.test.mjs`

**Interfaces:**
- Consumes: Task 1 的 `DEFAULT_SETTINGS`（含新字段）、`FONT_FAMILIES`（含 HEITI/MONO）。
- Produces: `renderSettings(container)` 返回 `{ open, dispose }`（接口不变）；dialog 内新增控件 name：`letterSpacingSlider`、`customBg`、`customFg`、`customMuted`；自定义颜色区 class `reader-settings__custom-colors`（hidden 属性控制显隐）。Task 3 不依赖本任务产物（渲染读取的是 Task 1 的字段）。

- [ ] **Step 1: 写失败测试**

新建 `server/internal/web/reader-settings.test.mjs`：

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setupJsdom, teardownJsdom } from './_snapshot-helpers.mjs';
import { state, resetState } from './reader-state.js';
import { renderSettings } from './reader-settings.js';
import { DEFAULT_SETTINGS } from './readerPrefs.js';

function mount(settings) {
    resetState();
    state.settings = { ...DEFAULT_SETTINGS, ...settings };
    const container = document.createElement('div');
    document.body.appendChild(container);
    const api = renderSettings(container);
    return { container, api, dialog: container.querySelector('#reader-settings-dialog') };
}

test('dialog renders letterSpacing slider, new fonts and CUSTOM theme', () => {
    setupJsdom();
    try {
        const { api, dialog } = mount({});
        assert.ok(dialog.querySelector('input[name="letterSpacingSlider"]'));
        assert.ok(dialog.querySelector('input[name="fontFamily"][value="HEITI"]'));
        assert.ok(dialog.querySelector('input[name="fontFamily"][value="MONO"]'));
        assert.ok(dialog.querySelector('input[name="theme"][value="CUSTOM"]'));
        assert.equal(dialog.querySelector('.reader-settings__custom-colors').hidden, true);
        api.dispose();
    } finally {
        teardownJsdom();
    }
});

test('CUSTOM theme reveals color section; switching away hides it', () => {
    setupJsdom();
    try {
        const { api, dialog } = mount({ theme: 'CUSTOM' });
        const colors = dialog.querySelector('.reader-settings__custom-colors');
        assert.equal(colors.hidden, false);
        const dayRadio = dialog.querySelector('input[name="theme"][value="DAY"]');
        dayRadio.checked = true;
        dayRadio.dispatchEvent(new Event('change', { bubbles: true }));
        assert.equal(colors.hidden, true);
        api.dispose();
    } finally {
        teardownJsdom();
    }
});

test('letterSpacing slider saves float; customBg saves hex', () => {
    setupJsdom();
    try {
        const { api, dialog } = mount({ theme: 'CUSTOM' });
        const slider = dialog.querySelector('input[name="letterSpacingSlider"]');
        slider.value = '0.25';
        slider.dispatchEvent(new Event('change', { bubbles: true }));
        const bg = dialog.querySelector('input[name="customBg"]');
        bg.value = '#123456';
        bg.dispatchEvent(new Event('change', { bubbles: true }));
        const saved = JSON.parse(localStorage.getItem('reader_settings'));
        assert.equal(saved.letterSpacing, 0.25);
        assert.equal(saved.customBg, '#123456');
        assert.equal(saved.theme, 'CUSTOM');
        api.dispose();
    } finally {
        teardownJsdom();
    }
});
```

注意：`_snapshot-helpers.mjs` 已 stub localStorage（bookmarks.test.mjs 同款用法）。若 `mount` 里 `reader-settings.js` import 的 `reader-state.js` 与 `readerPrefs.js` 在模块顶层访问 window，jsdom 环境已由 `setupJsdom()` 建立——本测试先 `setupJsdom()` 再 `mount`，顺序正确。若 import 报错，改为在测试函数内 `await import()`（参照 snapshot-baseline.test.mjs:80 的写法）。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd server/internal/web && node --test reader-settings.test.mjs`
Expected: 3 个测试 FAIL（新控件不存在、CUSTOM 区不存在）

- [ ] **Step 3: 实现**

修改 `reader-settings.js`：

(1) `FONT_OPTIONS`（14-19 行）改为：

```js
const FONT_OPTIONS = [
    ['SYSTEM', '无衬线'],
    ['SERIF', '宋体'],
    ['KAITI', '文楷'],
    ['HEITI', '黑体'],
    ['MONO', '等宽'],
];
```

(2) `THEME_OPTIONS`（21-30 行）末尾（AUTO 之后）加：

```js
    ['CUSTOM', '自定义'],
```

(3) dialog 模板「字号与行距」section（74-91 行）末尾、宽度 slider 之后加：

```js
                    <label class="reader-settings__slider-row">
                        <span>字间距</span>
                        <input type="range" name="letterSpacingSlider" min="0" max="1" step="0.05" value="0">
                        <output data-bind="letterSpacingLabel">0.00 em</output>
                    </label>
```

(4) dialog 模板「行为」section（105-124 行）结束后、`</div>` 前（124 行 `</form>` 之前）加自定义颜色区：

```js
                <section class="reader-settings__group reader-settings__custom-colors" hidden>
                    <h4>自定义颜色</h4>
                    <label class="reader-settings__color-row">
                        <span>背景</span>
                        <input type="color" name="customBg" value="#FAF8F3">
                        <output data-bind="customBgLabel">#FAF8F3</output>
                    </label>
                    <label class="reader-settings__color-row">
                        <span>正文</span>
                        <input type="color" name="customFg" value="#2B2B2B">
                        <output data-bind="customFgLabel">#2B2B2B</output>
                    </label>
                    <label class="reader-settings__color-row">
                        <span>次要</span>
                        <input type="color" name="customMuted" value="#7A7A78">
                        <output data-bind="customMutedLabel">#7A7A78</output>
                    </label>
                </section>
```

(5) `syncControlsFromSettings`（132-168 行）末尾加：

```js
        const lsSlider = dialog.querySelector('input[name="letterSpacingSlider"]');
        if (lsSlider) lsSlider.value = String(s.letterSpacing);
        const lsLabel = dialog.querySelector('[data-bind="letterSpacingLabel"]');
        if (lsLabel) lsLabel.textContent = s.letterSpacing.toFixed(2) + ' em';
        ['customBg', 'customFg', 'customMuted'].forEach((name) => {
            const input = dialog.querySelector(`input[name="${name}"]`);
            if (input) input.value = s[name] || '#000000';
            const out = dialog.querySelector(`[data-bind="${name}Label"]`);
            if (out) out.textContent = s[name] || '未设置';
        });
        const customSection = dialog.querySelector('.reader-settings__custom-colors');
        if (customSection) customSection.hidden = s.theme !== 'CUSTOM';
```

(6) `onChange`（180-199 行）加分支（在 `autoScrollSpeed` 分支之后）：

```js
        } else if (t.name === 'letterSpacingSlider') {
            saveAndEmit({ letterSpacing: parseFloat(t.value) });
        } else if (t.name === 'theme') {
            saveAndEmit({ theme: t.value });
            const customSection = dialog.querySelector('.reader-settings__custom-colors');
            if (customSection) customSection.hidden = t.value !== 'CUSTOM';
```

（`customBg/customFg/customMuted` 无需新分支——现有 else 分支 `saveAndEmit({ [t.name]: t.value })` 已覆盖。）

修改 `style.css`：

(7) `.reader-settings__theme-swatch[data-theme="AUTO"]` 规则（2717-2719 行）之后加：

```css
.reader-settings__theme-swatch[data-theme="CUSTOM"] {
    background: linear-gradient(135deg, #FAF8F3 0 50%, #2B2B2B 50% 100%);
}
```

(8) `.reader-settings__theme-label` 规则（2720-2722 行）之后加：

```css
.reader-settings__color-row {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 8px;
}
.reader-settings__color-row input[type="color"] {
    width: 40px;
    height: 28px;
    padding: 0;
    border: 1px solid var(--reader-border, var(--border-color));
    border-radius: 4px;
    background: none;
}
.reader-settings__color-row span { min-width: 2.5em; }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd server/internal/web && node --test reader-settings.test.mjs`
Expected: 3 个测试全部 PASS

- [ ] **Step 5: 回归确认（现有测试不破坏）**

Run: `cd server/internal/web && npm test`
Expected: 全部既有测试 PASS（含 snapshot-baseline）

- [ ] **Step 6: 提交**

```bash
git add server/internal/web/reader-settings.js server/internal/web/reader-settings.test.mjs server/internal/web/style.css
git commit -m "feat(web): add letter-spacing slider, HEITI/MONO fonts and CUSTOM theme colors to settings dialog"
```

---

### Task 3: Web 渲染 — textReader.js CUSTOM 解析 + letter-spacing CSS 变量

**Files:**
- Modify: `server/internal/web/textReader.js`（applySettingsToUI 101-125 行）
- Modify: `server/internal/web/style.css`（.text-reader__content p 规则 2036-2039 行）
- Create: `server/internal/web/textReader-theme.test.mjs`

**Interfaces:**
- Consumes: Task 1 的 `DEFAULT_SETTINGS` 字段（`s.letterSpacing`、`s.customBg/customFg/customMuted`）、`readerPrefs.THEME_PRESETS`。
- Produces: `applySettingsToUI` 注入 CSS 变量 `--reader-bg/--reader-fg/--reader-chrome-bg/--reader-chrome-fg/--reader-muted/--reader-border`（CUSTOM 时取 custom 值）与 `--reader-letter-spacing`（`0.25em` 形式）。

- [ ] **Step 1: 写失败测试**

新建 `server/internal/web/textReader-theme.test.mjs`（mock 模式完全照抄 snapshot-baseline.test.mjs 的 mockFetch/环境 stub）：

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setupJsdom, teardownJsdom, mockBook } from './_snapshot-helpers.mjs';

// 环境 stub 与 snapshot-baseline.test.mjs 一致：sessionStorage + matchMedia + fetch。
function installEnv() {
    global.sessionStorage = global.sessionStorage || {
        _s: {},
        getItem(k) { return k in this._s ? this._s[k] : null; },
        setItem(k, v) { this._s[k] = String(v); },
        removeItem(k) { delete this._s[k]; },
    };
    window.matchMedia = window.matchMedia || (() => ({
        matches: false,
        addEventListener() {},
        removeEventListener() {},
        addListener() {},
        removeListener() {},
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

test('CUSTOM theme injects custom colors into CSS vars (and border derives from muted)', async () => {
    setupJsdom();
    try {
        installEnv();
        localStorage.setItem('reader_settings', JSON.stringify({
            theme: 'CUSTOM', customBg: '#112233', customFg: '#445566', customMuted: '#778899',
        }));
        const { renderTextReader } = await import('./textReader.js');
        await renderTextReader(viewContainer(), mockBook.path, 0);
        await new Promise((r) => setTimeout(r, 50));
        const root = document.documentElement;
        assert.equal(root.style.getPropertyValue('--reader-bg'), '#112233');
        assert.equal(root.style.getPropertyValue('--reader-fg'), '#445566');
        assert.equal(root.style.getPropertyValue('--reader-muted'), '#778899');
        assert.equal(root.style.getPropertyValue('--reader-border'), '#778899');
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});

test('CUSTOM with missing colors falls back to DAY palette (light system)', async () => {
    setupJsdom();
    try {
        installEnv();
        localStorage.setItem('reader_settings', JSON.stringify({ theme: 'CUSTOM' }));
        const { renderTextReader } = await import('./textReader.js');
        await renderTextReader(viewContainer(), mockBook.path, 0);
        await new Promise((r) => setTimeout(r, 50));
        const root = document.documentElement;
        assert.equal(root.style.getPropertyValue('--reader-bg'), '#FAF8F3'); // DAY.bg
        assert.equal(root.style.getPropertyValue('--reader-fg'), '#2B2B2B'); // DAY.fg
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});

test('letterSpacing setting injects --reader-letter-spacing', async () => {
    setupJsdom();
    try {
        installEnv();
        localStorage.setItem('reader_settings', JSON.stringify({ letterSpacing: 0.25 }));
        const { renderTextReader } = await import('./textReader.js');
        await renderTextReader(viewContainer(), mockBook.path, 0);
        await new Promise((r) => setTimeout(r, 50));
        assert.equal(document.documentElement.style.getPropertyValue('--reader-letter-spacing'), '0.25em');
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd server/internal/web && node --test textReader-theme.test.mjs`
Expected: 3 个测试 FAIL（`--reader-bg` 为 DAY 预设而非 custom 色；`--reader-letter-spacing` 为空）

- [ ] **Step 3: 实现**

修改 `textReader.js` applySettingsToUI（101-114 行）。将：

```js
        let themeKey = s.theme;
        if (themeKey === 'AUTO') {
            themeKey = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'NIGHT' : 'DAY';
        }
        const theme = readerPrefs.THEME_PRESETS[themeKey];
```

替换为：

```js
        let themeKey = s.theme;
        let theme;
        if (themeKey === 'AUTO') {
            themeKey = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'NIGHT' : 'DAY';
            theme = readerPrefs.THEME_PRESETS[themeKey];
        } else if (themeKey === 'CUSTOM') {
            const fb = readerPrefs.THEME_PRESETS[
                window.matchMedia('(prefers-color-scheme: dark)').matches ? 'NIGHT' : 'DAY'
            ];
            // 三色自定义；null 回退到系统深浅对应预设。chrome/border 从三色派生。
            theme = {
                bg: s.customBg || fb.bg,
                fg: s.customFg || fb.fg,
                chromeBg: s.customBg || fb.chromeBg,
                chromeFg: s.customFg || fb.chromeFg,
                muted: s.customMuted || fb.muted,
                border: s.customMuted || fb.border,
            };
        } else {
            theme = readerPrefs.THEME_PRESETS[themeKey];
        }
```

并在 `setVar('--reader-content-width', s.contentWidth + 'px');`（120 行）之后加：

```js
        setVar('--reader-letter-spacing', s.letterSpacing + 'em');
```

修改 `style.css`：`.text-reader__content p` 规则（2036-2039 行，含 font-size/line-height 的那条）内加一行：

```css
    letter-spacing: var(--reader-letter-spacing, 0em);
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd server/internal/web && node --test textReader-theme.test.mjs`
Expected: 3 个测试全部 PASS

- [ ] **Step 5: 回归确认**

Run: `cd server/internal/web && npm test`
Expected: 全部 PASS（含 snapshot-baseline 的 e2e 基线）

- [ ] **Step 6: 提交**

```bash
git add server/internal/web/textReader.js server/internal/web/style.css server/internal/web/textReader-theme.test.mjs
git commit -m "feat(web): render CUSTOM theme colors and letter-spacing in text reader"
```

---

### Task 4: Android 数据层与主题解析 — ReaderSettings + ReaderThemeWrapper

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/ReaderSettings.kt`（17-29 行 data class、35-97 行枚举）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderThemeWrapper.kt`（全文件重构，33-66 行主体）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt`（291 行 ReaderThemeWrapper 调用点）
- Test: `android/app/src/test/java/com/juziss/localmediahub/data/ReaderSettingsMigrationTest.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/data/RecentActivityStoreReaderSettingsTest.kt`
- Create: `android/app/src/test/java/com/juziss/localmediahub/ui/component/reader/ReaderThemeWrapperTest.kt`

**Interfaces:**
- Produces:
  - `ReaderSettings` 新字段：`letterSpacing: Float = 0f`、`customBg: String? = null`、`customFg: String? = null`、`customMuted: String? = null`。
  - `ReaderTheme.CUSTOM` 枚举项（Transparent 占位，label "自定义"）。
  - `CustomReaderColors(bg: Color?, fg: Color?, muted: Color?)` data class。
  - `ReaderColors(bg/fg/chromeBg/chromeFg/muted/border: Color)` data class + `ReaderColors.fromTheme(ReaderTheme)`。
  - `resolveReaderColors(theme: ReaderTheme, isDark: Boolean, custom: CustomReaderColors?): ReaderColors`（顶层 internal 纯函数）。
  - `ReaderThemeScope(theme, bgImageUri, customColors: CustomReaderColors? = null, content)`——新参数带默认值，既有调用（测试 TextReaderScreenThemeTest 等）不破坏。
  - `fun ReaderSettings.toCustomReaderColors(): CustomReaderColors`（在 ReaderThemeWrapper.kt 内）与 `fun String?.toComposeColorOrNull(): Color?`（内部）。
- Task 6 的设置面板与渲染依赖上述符号；Task 6 中 `ReaderSettingsSheet` 使用 `ReaderTheme.CUSTOM` 与 settings 新字段。

- [ ] **Step 1: 写失败测试**

(1) 新建 `ReaderThemeWrapperTest.kt`：

```kotlin
package com.juziss.localmediahub.ui.component.reader

import androidx.compose.ui.graphics.Color
import com.juziss.localmediahub.data.ReaderSettings
import com.juziss.localmediahub.data.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReaderThemeWrapperTest {

    @Test
    fun auto_resolves_to_day_or_night() {
        assertEquals(ReaderTheme.DAY.bg, resolveReaderColors(ReaderTheme.AUTO, isDark = false, custom = null).bg)
        assertEquals(ReaderTheme.NIGHT.bg, resolveReaderColors(ReaderTheme.AUTO, isDark = true, custom = null).bg)
    }

    @Test
    fun custom_uses_three_colors_and_derives_chrome() {
        val custom = CustomReaderColors(Color(0xFF112233), Color(0xFF445566), Color(0xFF778899))
        val r = resolveReaderColors(ReaderTheme.CUSTOM, isDark = false, custom = custom)
        assertEquals(Color(0xFF112233), r.bg)
        assertEquals(Color(0xFF445566), r.fg)
        assertEquals(Color(0xFF778899), r.muted)
        assertEquals(Color(0xFF112233), r.chromeBg)  // chromeBg = bg
        assertEquals(Color(0xFF445566), r.chromeFg)  // chromeFg = fg
        assertEquals(Color(0xFF778899), r.border)    // border = muted
    }

    @Test
    fun custom_null_fields_fall_back_by_system_mode() {
        val custom = CustomReaderColors(null, null, null)
        val day = resolveReaderColors(ReaderTheme.CUSTOM, isDark = false, custom = custom)
        assertEquals(ReaderTheme.DAY.bg, day.bg)
        assertEquals(ReaderTheme.DAY.fg, day.fg)
        assertEquals(ReaderTheme.DAY.muted, day.muted)
        val night = resolveReaderColors(ReaderTheme.CUSTOM, isDark = true, custom = custom)
        assertEquals(ReaderTheme.NIGHT.bg, night.bg)
    }

    @Test
    fun concrete_theme_uses_preset_colors() {
        val r = resolveReaderColors(ReaderTheme.EYE_CARE, isDark = false, custom = null)
        assertEquals(ReaderTheme.EYE_CARE.bg, r.bg)
        assertEquals(ReaderTheme.EYE_CARE.muted, r.muted)
    }

    @Test
    fun hex_parsing_accepts_rrggbb_and_rejects_garbage() {
        assertEquals(Color(0xFFABCDEF), "#ABCDEF".toComposeColorOrNull())
        assertEquals(Color(0xFF1A2B3C), "#1a2b3c".toComposeColorOrNull())
        assertNull("red".toComposeColorOrNull())
        assertNull("#12345".toComposeColorOrNull())
        assertNull((null as String?).toComposeColorOrNull())
    }

    @Test
    fun settings_without_custom_colors_produce_empty_custom() {
        val c = ReaderSettings().toCustomReaderColors()
        assertNull(c.bg)
        assertNull(c.fg)
        assertNull(c.muted)
    }
}
```

(2) `ReaderSettingsMigrationTest.kt` 末尾加两个测试：

```kotlin
    @Test
    fun v2_without_new_fields_falls_back_to_defaults() = runBlocking {
        injectRawSettings("""{"theme":"NIGHT"}""")
        val s = store.readerSettingsFlow.first()
        assertEquals(0f, s.letterSpacing, 0.0001f)
        assertEquals(null, s.customBg)
        assertEquals(null, s.customFg)
        assertEquals(null, s.customMuted)
    }

    @Test
    fun v2_with_new_fields_reads_correctly() = runBlocking {
        injectRawSettings("""{"letterSpacing":0.25,"customBg":"#ABCDEF","customFg":"#112233","customMuted":"#445566"}""")
        val s = store.readerSettingsFlow.first()
        assertEquals(0.25f, s.letterSpacing, 0.0001f)
        assertEquals("#ABCDEF", s.customBg)
        assertEquals("#112233", s.customFg)
        assertEquals("#445566", s.customMuted)
    }
```

(3) `RecentActivityStoreReaderSettingsTest.kt` 末尾加：

```kotlin
    @Test
    fun save_with_new_fields_round_trips() = runBlocking {
        val updated = ReaderSettings(
            theme = ReaderTheme.CUSTOM,
            letterSpacing = 0.3f,
            customBg = "#111111",
            customFg = "#eeeeee",
            customMuted = "#888888",
        )
        store.saveReaderSettings(updated)
        assertEquals(updated, store.readerSettingsFlow.first())
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd android && ./gradlew.bat :app:testDebugUnitTest --tests "com.juziss.localmediahub.ui.component.reader.ReaderThemeWrapperTest" --tests "com.juziss.localmediahub.data.ReaderSettingsMigrationTest" --tests "com.juziss.localmediahub.data.RecentActivityStoreReaderSettingsTest"`
Expected: 编译失败（`ReaderSettings` 无新字段 / `resolveReaderColors` 不存在）或断言失败

- [ ] **Step 3: 实现**

(1) `ReaderSettings.kt` data class（17-29 行）末尾加：

```kotlin
    val letterSpacing: Float = 0f,      // 字间距，em 单位，0..1，步进 0.05
    val customBg: String? = null,       // #RRGGBB，仅 theme=CUSTOM 生效
    val customFg: String? = null,
    val customMuted: String? = null,
```

(2) `ReaderTheme` 枚举（86-91 行 AUTO 之后）加：

```kotlin
    CUSTOM(
        bg = Color.Transparent, fg = Color.Transparent,
        chromeBg = Color.Transparent, chromeFg = Color.Transparent,
        muted = Color.Transparent, border = Color.Transparent,
        label = "自定义",
    );
```

（注意原枚举以 `AUTO(...);` 结尾，需把分号移到 CUSTOM 后。）

(3) `ReaderThemeWrapper.kt` 重构。将 `ReaderThemeScope` 主体（33-66 行）替换为：

```kotlin
/**
 * 解析后的阅读器完整配色。CUSTOM 主题的 chrome/border 从三色派生
 * （chromeBg=bg、chromeFg=fg、border=muted），语义与 Web 端一致。
 */
data class ReaderColors(
    val bg: Color,
    val fg: Color,
    val chromeBg: Color,
    val chromeFg: Color,
    val muted: Color,
    val border: Color,
) {
    companion object {
        fun fromTheme(t: ReaderTheme) =
            ReaderColors(t.bg, t.fg, t.chromeBg, t.chromeFg, t.muted, t.border)
    }
}

/** CUSTOM 主题的三色；null 表示该项回退到系统深浅对应的 DAY/NIGHT 预设。 */
data class CustomReaderColors(val bg: Color?, val fg: Color?, val muted: Color?)

/**
 * 纯函数解析（便于单测）：AUTO → DAY/NIGHT；CUSTOM → 三色 + 派生色，
 * null 回退；其余主题 → 自身预设色。
 */
internal fun resolveReaderColors(
    theme: ReaderTheme,
    isDark: Boolean,
    custom: CustomReaderColors?,
): ReaderColors = when (theme) {
    ReaderTheme.AUTO -> ReaderColors.fromTheme(ReaderTheme.resolveAuto(isDark))
    ReaderTheme.CUSTOM -> {
        val fb = ReaderColors.fromTheme(ReaderTheme.resolveAuto(isDark))
        ReaderColors(
            bg = custom?.bg ?: fb.bg,
            fg = custom?.fg ?: fb.fg,
            chromeBg = custom?.bg ?: fb.chromeBg,
            chromeFg = custom?.fg ?: fb.chromeFg,
            muted = custom?.muted ?: fb.muted,
            border = custom?.muted ?: fb.border,
        )
    }
    else -> ReaderColors.fromTheme(theme)
}

/** 解析 #RRGGBB；非法或 null 返回 null。 */
internal fun String?.toComposeColorOrNull(): Color? {
    val h = this?.removePrefix("#") ?: return null
    if (h.length != 6 || h.toLongOrNull(16) == null) return null
    return Color(0xFF000000L or h.toLong(16))
}

/** 把 settings 的 hex 三色解析为 [CustomReaderColors]（非法/缺失 → null → 渲染处回退）。 */
fun ReaderSettings.toCustomReaderColors(): CustomReaderColors =
    CustomReaderColors(
        bg = customBg.toComposeColorOrNull(),
        fg = customFg.toComposeColorOrNull(),
        muted = customMuted.toComposeColorOrNull(),
    )
```

`ReaderThemeScope` 签名与主体（28-66 行）替换为：

```kotlin
@Composable
fun ReaderThemeScope(
    theme: ReaderTheme,
    bgImageUri: String? = null,
    customColors: CustomReaderColors? = null,
    content: @Composable () -> Unit,
) {
    val resolved = resolveReaderColors(theme, isSystemInDarkTheme(), customColors)
    val scheme = MaterialTheme.colorScheme.copy(
        background = resolved.bg,
        onBackground = resolved.fg,
        surface = resolved.chromeBg,
        onSurface = resolved.chromeFg,
        surfaceVariant = resolved.chromeBg,
        onSurfaceVariant = resolved.muted,
    )
    CompositionLocalProvider(LocalContentColor provides resolved.fg) {
        MaterialTheme(colorScheme = scheme) {
            ProvideNoRippleIndication {
                Box(Modifier.background(resolved.bg)) {
                    if (!bgImageUri.isNullOrBlank()) {
                        AsyncImage(
                            model = bgImageUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(resolved.bg.copy(alpha = 0.70f))
                        )
                    }
                    content()
                }
            }
        }
    }
}
```

`ReaderThemeWrapper` 旧名别名（69-75 行）同步加参数：

```kotlin
@Composable
fun ReaderThemeWrapper(
    theme: ReaderTheme,
    bgImageUri: String? = null,
    customColors: CustomReaderColors? = null,
    content: @Composable () -> Unit,
) = ReaderThemeScope(theme = theme, bgImageUri = bgImageUri, customColors = customColors, content = content)
```

(4) `TextReaderScreen.kt` 291 行调用点改为：

```kotlin
    ReaderThemeWrapper(
        theme = settings.theme,
        bgImageUri = settings.bgImageUri,
        customColors = settings.toCustomReaderColors(),
    ) {
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd android && ./gradlew.bat :app:testDebugUnitTest --tests "com.juziss.localmediahub.ui.component.reader.ReaderThemeWrapperTest" --tests "com.juziss.localmediahub.data.ReaderSettingsMigrationTest" --tests "com.juziss.localmediahub.data.RecentActivityStoreReaderSettingsTest"`
Expected: 全部 PASS

- [ ] **Step 5: 回归确认（阅读器相关测试不破坏）**

Run: `cd android && ./gradlew.bat :app:testDebugUnitTest --tests "com.juziss.localmediahub.ui.screen.TextReaderScreenThemeTest" --tests "com.juziss.localmediahub.ui.component.reader.*"`
Expected: 全部 PASS（`ReaderThemeScope`/`ReaderThemeWrapper` 新参数带默认值，既有调用不变）

- [ ] **Step 6: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/data/ReaderSettings.kt android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderThemeWrapper.kt android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt android/app/src/test/java/com/juziss/localmediahub/ui/component/reader/ReaderThemeWrapperTest.kt android/app/src/test/java/com/juziss/localmediahub/data/ReaderSettingsMigrationTest.kt android/app/src/test/java/com/juziss/localmediahub/data/RecentActivityStoreReaderSettingsTest.kt
git commit -m "feat(android): add letterSpacing/custom-theme-color settings fields and CUSTOM theme resolution"
```

---

### Task 5: Android 字体扩充 — 文楷打包 + MONO

**Files:**
- Create: `android/app/src/main/res/font/lxgw_wenkai.ttf`（下载；如失败走回退）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderFontFamily.kt`
- Create: `android/app/src/test/java/com/juziss/localmediahub/ui/component/reader/ReaderFontFamilyTest.kt`

**Interfaces:**
- Consumes: 无（独立任务，不依赖 Task 4）。
- Produces: `ReaderFontFamily.KAITI` label "楷体·文楷"、`toFontFamily()` 返回 `FontFamily(Font(R.font.lxgw_wenkai))`；新增 `ReaderFontFamily.MONO`（"等宽"，`FontFamily.Monospace`）。Task 6 的设置面板 chips 遍历 `ReaderFontFamily.entries` 自动包含新项。

- [ ] **Step 1: 下载文楷字体（可回退）**

Run: 从 `https://github.com/lxgw/LxgwWenKai/releases`（最新 release assets）下载 `LXGWWenKai-Regular.ttf`（全量版约 13MB；优先找 `LXGWWenKai-Regular.ttf` 的子集/精简版，约 4-6MB）：

```bash
curl -L -o android/app/src/main/res/font/lxgw_wenkai.ttf "https://github.com/lxgw/LxgwWenKai/releases/latest/download/LXGWWenKai-Regular.ttf"
```

Expected: 文件存在且 `ls -la android/app/src/main/res/font/lxgw_wenkai.ttf` 显示 4-13MB。
**回退**：若下载失败/体积不可接受（APK 膨胀），跳过 Step 1-2 的文件部分，KAITI 保持现状（label 不变、映射 `FontFamily.Serif`），仅做 Step 3 的 MONO 枚举与测试；提交信息注明。

- [ ] **Step 2: 写失败测试**

新建 `ReaderFontFamilyTest.kt`：

```kotlin
package com.juziss.localmediahub.ui.component.reader

import androidx.compose.ui.text.font.FontFamily
import com.juziss.localmediahub.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReaderFontFamilyTest {

    @Test
    fun kaiti_maps_to_bundled_wenkai_font() {
        // R.font.lxgw_wenkai 非 0 证明字体资源已打包
        assertNotNull(R.font.lxgw_wenkai)
        assertEquals(FontFamily(Font(R.font.lxgw_wenkai)).size, ReaderFontFamily.KAITI.toFontFamily().size)
    }

    @Test
    fun mono_maps_to_monospace() {
        assertEquals(FontFamily.Monospace, ReaderFontFamily.MONO.toFontFamily())
    }
}
```

注意：若 `Font` 未导入，`assertEquals` 中的 `Font(...)` 需要 `import androidx.compose.ui.text.font.Font`；`FontFamily(Font(R.font.lxgw_wenkai)).size` 与 `toFontFamily()` 返回的实例大小比较即可（构造不崩溃即通过）。更简单可靠的断言：

```kotlin
    @Test
    fun kaiti_maps_to_bundled_wenkai_font() {
        assertNotNull(R.font.lxgw_wenkai)
        val fam = ReaderFontFamily.KAITI.toFontFamily()
        assertNotNull(fam)
    }
```

（用第二个版本，避免 FontFamily 相等性/内部结构断言不稳定。）

- [ ] **Step 3: 运行测试确认失败**

Run: `cd android && ./gradlew.bat :app:testDebugUnitTest --tests "com.juziss.localmediahub.ui.component.reader.ReaderFontFamilyTest"`
Expected: 编译失败（`R.font.lxgw_wenkai` 不存在 / `MONO` 不存在）

- [ ] **Step 4: 实现**

`ReaderFontFamily.kt` 替换为：

```kotlin
package com.juziss.localmediahub.ui.component.reader

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.juziss.localmediahub.R

enum class ReaderFontFamily(val label: String) {
    SYSTEM("无衬线"),
    SERIF("宋体"),
    KAITI("楷体·文楷"),
    MONO("等宽");

    fun toFontFamily(): FontFamily = when (this) {
        SYSTEM -> FontFamily.Default
        SERIF  -> FontFamily.Serif
        KAITI  -> FontFamily(Font(R.font.lxgw_wenkai))
        MONO   -> FontFamily.Monospace
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd android && ./gradlew.bat :app:testDebugUnitTest --tests "com.juziss.localmediahub.ui.component.reader.ReaderFontFamilyTest"`
Expected: 全部 PASS

- [ ] **Step 6: 提交**

```bash
git add android/app/src/main/res/font/lxgw_wenkai.ttf android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderFontFamily.kt android/app/src/test/java/com/juziss/localmediahub/ui/component/reader/ReaderFontFamilyTest.kt
git commit -m "feat(android): bundle LXGW WenKai font for KAITI and add MONO font option"
```

（若走回退：只 add 后两个文件，commit message 注明 "KAITI unchanged due to font download failure"。）

---

### Task 6: Android 设置面板与渲染 — ReaderSettingsSheet + ParagraphItem

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheet.kt`（字号与行距节 166-205 行；外观节主题后 118 行处）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt`（BlockItem 885 行处、ParagraphItem 签名 931-943 行）
- Test: `android/app/src/test/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheetTest.kt`

**Interfaces:**
- Consumes: Task 4 的 `ReaderSettings` 新字段与 `ReaderTheme.CUSTOM`；Task 5 的 `ReaderFontFamily.MONO`/KAITI 标签。
- Produces: `ReaderSettingsSheetContent` 新增"字间距"slider（testTag `letterSpacingSlider`）与 CUSTOM 主题下的"自定义颜色"区（testTag：`customBgHex`/`customFgHex`/`customMutedHex` 文本输入框）；`ParagraphItem` 新参数 `letterSpacingSp: TextUnit = 0.sp`（带默认值，既有测试调用不受影响）。

- [ ] **Step 1: 写失败测试**

`ReaderSettingsSheetTest.kt` 末尾加：

```kotlin
    @Test
    fun letter_spacing_slider_renders_and_fires_onchange() {
        var captured: ReaderSettings? = null
        composeRule.setContent {
            ReaderSettingsSheetContent(
                settings = ReaderSettings(),
                onChange = { captured = it },
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("字间距 0.00").assertExists()
        composeRule.onNodeWithTag("letterSpacingSlider").performSemantics {
            setProgress(0.25f)
        }
        composeRule.waitForIdle()
        assertEquals(0.25f, captured?.letterSpacing ?: -1f, 0.0001f)
    }

    @Test
    fun custom_theme_shows_color_section() {
        composeRule.setContent {
            ReaderSettingsSheetContent(
                settings = ReaderSettings(theme = ReaderTheme.CUSTOM),
                onChange = {},
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("自定义颜色").assertExists()
        composeRule.onNodeWithTag("customBgHex").assertExists()
    }

    @Test
    fun non_custom_theme_hides_color_section() {
        composeRule.setContent {
            ReaderSettingsSheetContent(
                settings = ReaderSettings(),
                onChange = {},
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("自定义颜色").assertDoesNotExist()
    }

    @Test
    fun hex_input_commits_valid_color_and_ignores_invalid() {
        var captured: ReaderSettings? = null
        composeRule.setContent {
            ReaderSettingsSheetContent(
                settings = ReaderSettings(theme = ReaderTheme.CUSTOM),
                onChange = { captured = it },
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("customBgHex").performTextInput("#ABCDEF")
        composeRule.waitForIdle()
        assertEquals("#ABCDEF", captured?.customBg)
    }
```

需要新增 import：`androidx.compose.ui.semantics.setProgress`、`androidx.compose.ui.test.onNodeWithTag`、`androidx.compose.ui.test.performSemantics`、`androidx.compose.ui.test.performTextInput`。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd android && ./gradlew.bat :app:testDebugUnitTest --tests "com.juziss.localmediahub.ui.component.reader.ReaderSettingsSheetTest"`
Expected: 新 4 个测试 FAIL（控件不存在/标签不存在）

- [ ] **Step 3: 实现**

(1) `ReaderSettingsSheet.kt`「字号与行距」节末尾（宽度 slider 205 行后、HorizontalDivider 前）加：

```kotlin
        // 字间距 Slider 0..1 step 0.05（20 档 -> steps = 19），吸附到 0.05 步进
        Text(
            "字间距 ${String.format(java.util.Locale.US, "%.2f", settings.letterSpacing)}",
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(
            value = settings.letterSpacing,
            onValueChange = { onChange(settings.copy(letterSpacing = (it * 20).roundToInt() / 20f)) },
            valueRange = 0f..1f,
            steps = 19,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("letterSpacingSlider"),
        )
```

(2) 主题 chips（118 行后、背景图片节前）加自定义颜色区：

```kotlin
        if (settings.theme == ReaderTheme.CUSTOM) {
            Spacer(Modifier.size(8.dp))
            Text("自定义颜色", style = MaterialTheme.typography.labelMedium)
            CustomColorRow("背景", settings.customBg, "customBgHex") {
                onChange(settings.copy(customBg = it))
            }
            CustomColorRow("正文", settings.customFg, "customFgHex") {
                onChange(settings.copy(customFg = it))
            }
            CustomColorRow("次要", settings.customMuted, "customMutedHex") {
                onChange(settings.copy(customMuted = it))
            }
        }
```

(3) 文件末尾加 `CustomColorRow` 与色板常量：

```kotlin
/** 常用阅读背景/文字色预设（每行各 12 色）。 */
private val PRESET_COLORS = listOf(
    "#FAF8F3", "#FFFFFF", "#F4ECD8", "#B9C7B6", "#EFE6D2", "#1A1A1F",
    "#000000", "#2B2B2B", "#3D3D3D", "#5B4636", "#1F2E20", "#C9C9CE",
)

/**
 * 一行自定义颜色控件：12 色预设色板 + hex 文本输入。
 * 仅当输入匹配 #RRGGBB 时提交；否则不触发 onChange。
 */
@Composable
private fun CustomColorRow(
    label: String,
    value: String?,
    inputTag: String,
    onCommit: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
        ) {
            PRESET_COLORS.forEach { hex ->
                val selected = value.equals(hex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Color(0xFF000000L or (hex.removePrefix("#").toLongOrNull(16) ?: 0L)))
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = CircleShape,
                        )
                        .clickable { onCommit(hex) },
                )
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("hex", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(48.dp))
        androidx.compose.material3.OutlinedTextField(
            value = value ?: "",
            onValueChange = { input ->
                val v = input.trim().uppercase()
                if (v.isEmpty()) onCommit("")
                else if (Regex("^#[0-9A-Fa-f]{6}$").matches(v)) onCommit(v)
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(inputTag),
            textStyle = MaterialTheme.typography.bodySmall,
        )
    }
}
```

注意：`FlowRow` 已由现有 import 提供（`ExperimentalLayoutApi` 已 import）；`CircleShape`/`clip`/`clickable`/`border` 均已 import。色块 `Color(0xFF000000L or ...)` 需要 `androidx.compose.ui.graphics.Color` 全限定（避免与 `com.juziss...ReaderTheme` 的 import 冲突——文件顶部已 import `androidx.compose.ui.graphics.Brush`，没有直接 import `Color`，全限定安全）。hex 输入"清空"提交空串（等于清除该色，渲染回退预设）。

(4) `TextReaderScreen.kt` `BlockItem` 的 ParagraphItem 调用（885-902 行）加参数：

```kotlin
        "text" -> ParagraphItem(
            text = block.value ?: "",
            fontSizeSp = settings.fontSizeSp.sp,
            lineHeightSp = (settings.fontSizeSp * settings.lineHeightMultiplier).sp,
            letterSpacingSp = (settings.fontSizeSp * settings.letterSpacing).sp,
            fontFamily = settings.fontFamily.toFontFamily(),
            ...
```

(5) `ParagraphItem` 签名（931-943 行）加参数（带默认值，既有测试不受影响）：

```kotlin
internal fun ParagraphItem(
    text: String,
    fontSizeSp: TextUnit,
    lineHeightSp: TextUnit,
    letterSpacingSp: TextUnit = 0.sp,
    fontFamily: FontFamily,
    ...
```

(6) `ParagraphItem` 的 style（970-975 行）加：

```kotlin
            style = LocalTextStyle.current.copy(
                fontSize = fontSizeSp,
                lineHeight = lineHeightSp,
                fontFamily = fontFamily,
                letterSpacing = letterSpacingSp,
                textIndent = if (firstLineIndent) TextIndent(firstLine = 2.em) else TextIndent.None,
            ),
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd android && ./gradlew.bat :app:testDebugUnitTest --tests "com.juziss.localmediahub.ui.component.reader.ReaderSettingsSheetTest" --tests "com.juziss.localmediahub.ui.screen.TextReaderScreenThemeTest"`
Expected: 全部 PASS（含既有 sheet 测试与 ParagraphItem 测试——`letterSpacingSp` 默认 0.sp 不破坏旧调用）

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheet.kt android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt android/app/src/test/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheetTest.kt
git commit -m "feat(android): add letter-spacing slider and custom theme color picker to reader settings; apply letterSpacing to paragraphs"
```

---

### Task 7: 双端对齐校验 + 全量验证

**Files:**
- 无代码改动；如发现修复，改动落在对应端文件。

- [ ] **Step 1: Web 全量测试**

Run: `cd server/internal/web && npm test`
Expected: 全部 PASS（readerPrefs / reader-settings / textReader-theme 新增 3 文件 + 全部既有测试）

- [ ] **Step 2: Android 全量测试**

Run: `cd android && ./gradlew.bat :app:testDebugUnitTest`
Expected: 全部 PASS

- [ ] **Step 3: 按 spec 校验清单人工核对**

逐项核对（spec `docs/superpowers/specs/2026-08-01-reader-typography-polish-design.md` 的「双端对齐校验清单」）：
- [ ] `letterSpacing` 默认 0、范围 0–1.0、步进 0.05，双端一致（Web slider min/max/step + migrate clamp；Android slider valueRange + round 吸附）
- [ ] `customBg/customFg/customMuted` 默认 null、格式 `#RRGGBB`，双端一致（Web HEX6 正则 + color input；Android hex 校验 + 预设色板）
- [ ] CUSTOM 解析规则（null 回退 DAY/NIGHT；chromeBg=bg、chromeFg=fg、border=muted）双端一致（Web textReader.js 派生；Android resolveReaderColors 派生）
- [ ] 字体选项语义：SYSTEM/SERIF/KAITI（文楷）/HEITI/MONO 双端对齐（Web FONT_OPTIONS 5 项；Android entries 4 项——HEITI 仅 Web 有，Android 无对应系统黑体选项，属预期差异，在报告说明）
- [ ] 切换主题不清空 custom 三色（双端 settings 均为整体覆盖模型，切换主题仅改 theme 字段）
- [ ] 旧数据（无新字段）在双端均以默认值工作（Web migrateV1toV2 测试；Android v2_without_new_fields_falls_back_to_defaults）

- [ ] **Step 4: 修复并复跑（如有问题）**

若 Step 1-3 发现问题：修复 → 重跑对应端全量测试 → 提交：

```bash
git add <修复的文件>
git commit -m "fix(web|android): <问题描述>"
```

- [ ] **Step 5: 提交对齐结论**

在提交信息或单独 commit 中记录核对结论（如无改动，不提交）：

```bash
git log --oneline -6
```

（确认 6 个功能 commit 均在 master 上：Task 1-3 的 3 个 web commit + Task 4-6 的 3 个 android commit。）
