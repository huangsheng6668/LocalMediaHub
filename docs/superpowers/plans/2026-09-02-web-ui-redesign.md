# Web UI 现代中性风重写 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Web 管理界面从暖纸色单文件 CSS 重写为分层 css/ 架构的现代中性风设计，重点升级仪表盘、书架与阅读器 chrome，并修复主题系统两处既有缺陷。

**Architecture:** 纯前端改造（`server/internal/web/`），无构建步骤。12 个任务按 spec 的 5 个阶段推进：①style.css 机械拆分到 css/ 多文件（行为零变化）→ ②7 套 chrome 调色板重写 + boot.js/header 主题缺陷修复 → ③共享组件重写 + emoji→SVG + 旧别名变量清剿 → ④仪表盘/书架升级 → ⑤阅读器 chrome 与其余视图收尾 + 全量视觉验证。类名与规范 CSS 变量名契约不变。

**Tech Stack:** 原生 ES modules + CSS（无框架、无构建）、node:test + jsdom、Go embed 静态服务、`tools/xsscheck` lint。

**Spec:** `docs/superpowers/specs/2026-09-02-web-ui-redesign-design.md`

## Global Constraints

- CSP：`script-src 'self'; style-src 'self'`——**零** inline `<script>`、**零** inline `style="..."`；渐变/占位背景必须用 CSS 类；动态样式只能 `el.style.prop =`（CSSOM）。任何 `<img onerror>` 之类 inline 事件属性都违反 CSP，错误处理必须用 capture-phase 事件委托。
- **类名契约不变**：JS `querySelector`/`classList` 与 `node --test` 依赖的选择器一律不改名（新增类名可以）。
- 规范 CSS 变量名不变：`--surface-app/card/sidebar/hover`、`--text-primary/secondary/muted/on-accent`、`--accent/-hover/-soft/-text`、`--border-soft/subtle`、`--shadow-sm/md`、`--radius-sm:6px/md:10px/lg:14px`、`--space-1..6`、`--error`、`--secondary`、`--font-sans`。
- 旧别名变量（`--bg-main` `--bg-card` `--bg-sidebar` `--bg-elevated` `--primary` `--primary-light` `--text-main` `--text-white` `--border-color` `--border-radius-lg` `--transition-smooth` `--transition-quick`）在 Task 7 前由 themes.css 底部的临时别名块维持定义，Task 7 删除。
- `innerHTML`/`outerHTML`/`insertAdjacentHTML`/`document.write` 的每处使用必须带 `// XSS-SAFE:` 注释或调用 `escapeHtml()`。
- 阅读面板主题（`readerPrefs.js` 的 `THEME_PRESETS`、`--reader-*` 注入、swatch hex）**不动**；翻页动画 `@keyframes` 与 `.pt-*` 类名原样保留。
- 工作目录 `server/internal/web/`（相对路径均基于此）；测试命令在 Git Bash (Windows) 下执行。
- 每个 Task 一个 commit，Conventional Commits：`type(web): 描述 (Phase N)`。
- 测试：改 JS/CSS 后 `node --test` 必须全绿；改 JS 模板后追加 `cd ../../../tools/xsscheck && go run . ../../server/internal/web`；改 embed 后 `cd ../../../server && go build ./...`。

---

### Task 1: style.css 拆分到 css/ 多文件（行为零变化）

**Files:**
- Create: `css/base.css`, `css/themes.css`, `css/layout.css`, `css/components.css`, `css/responsive.css`, `css/views/dashboard.css`, `css/views/browser.css`, `css/views/bookshelf.css`, `css/views/bookmarks.css`, `css/views/settings.css`, `css/views/reader.css`, `css/views/video.css`, `css/views/lightbox.css`
- Delete: `style.css`, `tokens.css`, `tools/build-tokens.mjs`
- Modify: `web.go`, `index.html`, `package.json`

**Interfaces:**
- Produces: css/ 文件树与 `<link>` 加载顺序（后续所有任务的修改目标）；`web.go` embed 指令。

**分割映射表**（行号基于当前 style.css，逐段**原样搬运**，不改任何声明）：

| style.css 行段 | 内容 | 去向 |
|---|---|---|
| 1–17 | `@font-face` ×2 | `css/base.css` |
| 19–207 | `:root,[data-theme="day"]` + 6 个 `[data-theme]` 调色板块 | `css/themes.css` |
| 208–245 | Reset & Global + 原生表单控件色 | `css/base.css` |
| 246–430 | App Layout Grid / Sidebar / Main Content / View Sections | `css/layout.css` |
| 431–541 | Premium Buttons | `css/components.css` |
| 542–584 | Dashboard Statistics Grid（`.stats-grid`/`.stat-card`/`.stat-icon`/`.stat-info`） | `css/views/dashboard.css` |
| 585–655 | Dashboard Widgets & Info Lists（`.widget-card`、`.info-item` 系列跨 dashboard/settings 共用） | `css/components.css`（共用件）；仅 dashboard 专属行（`.recent-list`/`.dashboard-*`）→ `css/views/dashboard.css` |
| 656–1068 | Browser Toolbar / Grid / Cards / Tag Manager | `css/views/browser.css` |
| 1069–1096 | System Settings Card | `css/views/settings.css` |
| 1097–1162 | Glass Modal Framework | `css/components.css` |
| 1163–1457 | Video player frame/controls/sliders/transcode/checkboxes | `css/views/video.css` |
| 1458–1627 | Lightbox（含 stitch 滚动条） | `css/views/lightbox.css` |
| 1628–1665 | Toast | `css/components.css` |
| 1666–1684 | Custom Scrollbar | `css/base.css` |
| 1685–1722 | Round 16 C1 Hamburger + Sidebar Drawer | `css/layout.css` |
| 1723–1816 | Round 16 C2 响应式断点 | `css/responsive.css` |
| 1817–1908 | Auth Token Modal | `css/components.css` |
| 1909–1984 | Round 16 C3 手机全屏 modal（断点规则） | `css/responsive.css` |
| 1985–2606 | `.text-reader` 全部（含翻页 keyframes、TOC tabs、沉浸模式、TOC drawer） | `css/views/reader.css` |
| 2607–2638 | reader/bookshelf 共用 error/unsupported 样式 | `css/views/reader.css` |
| 2639–2691 | Bookshelf 卡片网格 | `css/views/bookshelf.css` |
| 2692–2754 | Autoscroll 面板 | `css/views/reader.css` |
| 2755–2923 | reader settings dialog（theme grid + Phase 4 布局） | `css/views/reader.css` |
| 2821–2832 | `body[data-reader-theme]` 变量覆盖块（夹在 dialog 段里） | `css/themes.css` 末尾 |
| 2924–2985 | CSP-safe 替代类 | 按选择器：`.browser-empty-grid`/`.browser-status-note`/`.card-badge--unsupported`→`browser.css`；`.dashboard-recent-*`→`dashboard.css`；`.bookmarks-manager-*`/`.bookmarks-empty-state`→`bookmarks.css`；`.settings-global-theme-grid`→`settings.css`；`.video-delete-btn`→`video.css`；`.reader-settings__row--mb8`→`reader.css` |

判断规则（映射表未尽处）：选择器被 ≥2 个视图使用 → `components.css`；仅单视图 → `views/*.css`；`@media` 断点规则 → `responsive.css`。

- [ ] **Step 1: 创建 css/ 目录并按映射表逐段搬运**

每段**剪切**（非复制）到目标文件，文件头加来源注释，如 `/* from style.css L431-541 (buttons) */`。搬运完 `style.css` 应为空并删除。`wc -l` 核对：13 个新 css 文件行数之和 ≈ 原文件（2985）+ 注释行（允许 ±40 行差）。

- [ ] **Step 2: 改 web.go embed**

```go
//go:embed index.html css/*.css css/views/*.css *.js fonts/*.woff2
```

- [ ] **Step 3: 改 index.html `<head>`**

```html
<script src="boot.js"></script>
<link rel="stylesheet" href="css/base.css">
<link rel="stylesheet" href="css/themes.css">
<link rel="stylesheet" href="css/layout.css">
<link rel="stylesheet" href="css/components.css">
<link rel="stylesheet" href="css/views/dashboard.css">
<link rel="stylesheet" href="css/views/browser.css">
<link rel="stylesheet" href="css/views/bookshelf.css">
<link rel="stylesheet" href="css/views/bookmarks.css">
<link rel="stylesheet" href="css/views/settings.css">
<link rel="stylesheet" href="css/views/reader.css">
<link rel="stylesheet" href="css/views/video.css">
<link rel="stylesheet" href="css/views/lightbox.css">
<link rel="stylesheet" href="css/responsive.css">
```

`boot.js` 必须保持在所有 `<link>` 之前（FOUC）。加载顺序即层叠顺序：`responsive.css` 必须最后（断点规则要压过视图规则）。

- [ ] **Step 4: 删除 tokens.css + tools/build-tokens.mjs + package.json 脚本**

`package.json` 删除 `"build:tokens": "node tools/build-tokens.mjs"` 行；删除 `tools/build-tokens.mjs` 与 `tokens.css` 文件。

- [ ] **Step 5: 构建与资源冒烟**

```bash
cd ../../../server && go build -o LocalMediaHub.exe ./cmd/server
./LocalMediaHub.exe --headless --auto-detect-roots &
sleep 2
for f in base themes layout components responsive views/dashboard views/browser views/bookshelf views/bookmarks views/settings views/reader views/video views/lightbox; do
  curl -sf -o /dev/null "http://127.0.0.1:8000/css/$f.css" && echo "OK $f" || echo "FAIL $f"
done
curl -sf -o /dev/null http://127.0.0.1:8000/ && echo "OK index"
taskkill //F //IM LocalMediaHub.exe
```

Expected: 全部 OK（端口以 config.example.yaml 为准，若 8000 被占用先确认实际端口）。

- [ ] **Step 6: 跑测试**

```bash
cd ../internal/web && node --test
```

Expected: 全部 pass（测试不读 CSS 内容）。

- [ ] **Step 7: 浏览器打开 `http://127.0.0.1:8000` 目视确认**：页面与拆分前视觉一致（无裸 HTML、无丢样式区块；重点看侧栏、浏览卡片、阅读器 drawer、视频弹层）。

- [ ] **Step 8: Commit**

```bash
git add -A .
git commit -m "refactor(web): split style.css into layered css/ modules (Phase 1)"
```

（cwd 为 `server/internal/web/`，`-A .` 同时覆盖该目录下的 `web.go`、`package.json` 与删除的文件。）

---

### Task 2: 7 套 chrome 调色板重写（现代中性风）

**Files:**
- Modify: `css/themes.css`

**Interfaces:**
- Consumes: Task 1 的 themes.css 结构（调色板块 + 末尾 reader 覆盖块）。
- Produces: 新调色板取值；文件末尾的**临时别名块**（Task 7 删除前所有旧消费者的定义来源）。

- [ ] **Step 1: 用以下内容替换全部 7 个调色板块**（保留文件末尾 reader 覆盖块不动）

```css
/* ── Modern-neutral chrome palettes — see spec §设计语言 ── */
:root,
[data-theme="day"] {
    color-scheme: light;
    --surface-app:     #FAFAFA;
    --surface-card:    #FFFFFF;
    --surface-sidebar: #F4F4F5;
    --surface-hover:   rgba(94, 106, 210, 0.08);
    --text-primary:    #17181C;
    --text-secondary:  #52525B;
    --text-muted:      #8A8A93;
    --text-on-accent:  #FFFFFF;
    --accent:          #5E6AD2;
    --accent-hover:    #4E59C9;
    --accent-soft:     rgba(94, 106, 210, 0.12);
    --accent-text:     #4348B8;
    --border-soft:     #EDEDF0;
    --border-subtle:   #E4E4E7;
    --shadow-sm:       0 1px 2px rgba(23, 24, 28, 0.05);
    --shadow-md:       0 4px 12px rgba(23, 24, 28, 0.08), 0 2px 4px rgba(23, 24, 28, 0.05);
    --radius-sm: 6px;  --radius-md: 10px; --radius-lg: 14px;
    --space-1: 4px; --space-2: 8px; --space-3: 12px; --space-4: 16px;
    --space-5: 24px; --space-6: 32px;
    --error: #DC2626;  --secondary: #16A34A;
    --font-sans: system-ui, -apple-system, 'Segoe UI', Roboto, 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

[data-theme="day_bright"] {
    color-scheme: light;
    --surface-app: #FFFFFF; --surface-card: #FFFFFF; --surface-sidebar: #F8FAFC;
    --surface-hover: rgba(37, 99, 235, 0.08);
    --text-primary: #0F172A; --text-secondary: #475569; --text-muted: #94A3B8;
    --text-on-accent: #FFFFFF;
    --accent: #2563EB; --accent-hover: #1D4ED8;
    --accent-soft: rgba(37, 99, 235, 0.12); --accent-text: #1E40AF;
    --border-soft: #EDF2F7; --border-subtle: #E2E8F0;
    --error: #DC2626; --secondary: #16A34A;
}

[data-theme="eye_care"] {
    color-scheme: light;
    --surface-app: #F7F1E3; --surface-card: #FCF8EE; --surface-sidebar: #EFE7D2;
    --surface-hover: rgba(160, 113, 60, 0.10);
    --text-primary: #3F3A2F; --text-secondary: #6B6353; --text-muted: #8F866F;
    --text-on-accent: #FFFFFF;
    --accent: #A0713C; --accent-hover: #8A5F30;
    --accent-soft: rgba(160, 113, 60, 0.14); --accent-text: #7C5527;
    --border-soft: #EAE1CD; --border-subtle: #E3D9C2;
    --error: #C0392B; --secondary: #4F7D3A;
}

[data-theme="eye_care_green"] {
    color-scheme: light;
    --surface-app: #EAF0E6; --surface-card: #F4F8F1; --surface-sidebar: #DFE8DA;
    --surface-hover: rgba(79, 125, 93, 0.10);
    --text-primary: #253326; --text-secondary: #4A5B4C; --text-muted: #6E7F70;
    --text-on-accent: #FFFFFF;
    --accent: #4F7D5D; --accent-hover: #40684C;
    --accent-soft: rgba(79, 125, 93, 0.14); --accent-text: #3A5C46;
    --border-soft: #DAE3D6; --border-subtle: #D3DFD0;
    --error: #C0392B; --secondary: #16A34A;
}

[data-theme="parchment"] {
    color-scheme: light;
    --surface-app: #F3EBD9; --surface-card: #FAF4E6; --surface-sidebar: #EBE1C8;
    --surface-hover: rgba(156, 107, 47, 0.10);
    --text-primary: #443C2C; --text-secondary: #6E6450; --text-muted: #948A74;
    --text-on-accent: #FFFFFF;
    --accent: #9C6B2F; --accent-hover: #83571F;
    --accent-soft: rgba(156, 107, 47, 0.14); --accent-text: #77531F;
    --border-soft: #E7DCC4; --border-subtle: #E0D4B8;
    --error: #C0392B; --secondary: #4F7D3A;
}

[data-theme="night"] {
    color-scheme: dark;
    --surface-app: #141517; --surface-card: #1D1F23; --surface-sidebar: #101113;
    --surface-hover: rgba(123, 135, 232, 0.12);
    --text-primary: #E8E9ED; --text-secondary: #A6A8B0; --text-muted: #7C7E87;
    --text-on-accent: #FFFFFF;
    --accent: #7B87E8; --accent-hover: #8C97EE;
    --accent-soft: rgba(123, 135, 232, 0.16); --accent-text: #A3ACEF;
    --border-soft: #24262C; --border-subtle: #2A2C33;
    --shadow-sm: 0 1px 2px rgba(0, 0, 0, 0.4);
    --shadow-md: 0 4px 12px rgba(0, 0, 0, 0.5), 0 2px 4px rgba(0, 0, 0, 0.4);
    --error: #F87171; --secondary: #4ADE80;
}

[data-theme="night_black"] {
    color-scheme: dark;
    --surface-app: #000000; --surface-card: #101114; --surface-sidebar: #0A0B0D;
    --surface-hover: rgba(123, 135, 232, 0.14);
    --text-primary: #E8E9ED; --text-secondary: #A6A8B0; --text-muted: #7C7E87;
    --text-on-accent: #FFFFFF;
    --accent: #7B87E8; --accent-hover: #8C97EE;
    --accent-soft: rgba(123, 135, 232, 0.18); --accent-text: #A3ACEF;
    --border-soft: #22242A; --border-subtle: #26282E;
    --shadow-sm: 0 1px 2px rgba(0, 0, 0, 0.6);
    --shadow-md: 0 4px 12px rgba(0, 0, 0, 0.7), 0 2px 4px rgba(0, 0, 0, 0.5);
    --error: #F87171; --secondary: #4ADE80;
}

/* ── 临时别名块：映射旧变量到规范名，供未迁移消费者引用。
      Task 7 别名清剿时整块删除。 ── */
:root,
[data-theme="day"], [data-theme="day_bright"], [data-theme="eye_care"],
[data-theme="eye_care_green"], [data-theme="parchment"],
[data-theme="night"], [data-theme="night_black"] {
    --bg-main:  var(--surface-app);
    --bg-card:  var(--surface-card);
    --bg-sidebar: var(--surface-sidebar);
    --primary:  var(--accent);
    --primary-light: var(--accent-text);
    --text-main: var(--text-primary);
    --text-white: var(--text-primary);
    --border-color: var(--border-subtle);
    --border-radius-lg: var(--radius-lg);
    --transition-smooth: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
    --transition-quick: all 0.15s ease;
}
```

注意：临时别名块必须放在 7 个调色板块**之后**（保证 `var()` 引用已定义的值），且在 reader 覆盖块**之前**。

- [ ] **Step 2: 快速视觉核对**：起 server，day / night 两主题各截一张仪表盘截图，确认整体中性灰+靛蓝 accent 生效、无大面积失色（别名块兜底中，旧组件仍可用）。

- [ ] **Step 3: 跑测试 + Commit**

```bash
node --test
git add css/themes.css
git commit -m "feat(web): modern-neutral chrome palettes for all 7 themes (Phase 2)"
```

---

### Task 3: boot.js FOUC 修复（TDD）

**Files:**
- Modify: `boot.js`
- Test: `boot.test.mjs`（新建）

**Interfaces:**
- Consumes: localStorage `reader_settings` JSON（`readerPrefs.js` 写入，形如 `{"theme":"NIGHT",...}`）；`DEFAULT_SETTINGS.theme = 'DAY'`。
- Produces: `<html data-theme>` 初值与 `app.js applyGlobalAppTheme` 完全一致（同 key、同映射、同默认 DAY）。

- [ ] **Step 1: 写失败测试 `boot.test.mjs`**

```js
// boot.js FOUC：主题初值必须读 reader_settings.theme（与 app.js 同源），
// 而非从未被写入过的 chrome_theme 键。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { setupJsdom, teardownJsdom } from './_snapshot-helpers.mjs';

const SRC = readFileSync(new URL('./boot.js', import.meta.url), 'utf8');

function runBoot() { window.eval(SRC); }

test('boot: NIGHT 主题硬刷新不闪 day', () => {
    setupJsdom();
    try {
        localStorage.setItem('reader_settings', JSON.stringify({ theme: 'NIGHT' }));
        runBoot();
        assert.equal(document.documentElement.dataset.theme, 'night');
    } finally { teardownJsdom(); }
});

test('boot: NIGHT_BLACK 映射 night_black', () => {
    setupJsdom();
    try {
        localStorage.setItem('reader_settings', JSON.stringify({ theme: 'NIGHT_BLACK' }));
        runBoot();
        assert.equal(document.documentElement.dataset.theme, 'night_black');
    } finally { teardownJsdom(); }
});

test('boot: AUTO 按系统偏好解析', () => {
    setupJsdom();
    try {
        window.matchMedia = () => ({ matches: true }); // 系统 dark
        localStorage.setItem('reader_settings', JSON.stringify({ theme: 'AUTO' }));
        runBoot();
        assert.equal(document.documentElement.dataset.theme, 'night');
    } finally { teardownJsdom(); }
});

test('boot: 无存储/坏 JSON 回退 day（与 app.js 默认一致）', () => {
    setupJsdom();
    try {
        runBoot();
        assert.equal(document.documentElement.dataset.theme, 'day');
        localStorage.setItem('reader_settings', '{broken json');
        document.documentElement.dataset.theme = '';
        runBoot();
        assert.equal(document.documentElement.dataset.theme, 'day');
    } finally { teardownJsdom(); }
});

test('boot: 不再读 chrome_theme', () => {
    setupJsdom();
    try {
        localStorage.setItem('chrome_theme', 'night');
        runBoot();
        assert.equal(document.documentElement.dataset.theme, 'day'); // 忽略死键
    } finally { teardownJsdom(); }
});
```

注意：第二个 test 里统一使用 `teardownJsdom()`。

- [ ] **Step 2: 跑测试确认失败**

```bash
node --test boot.test.mjs
```

Expected: FAIL（现 boot.js 读 chrome_theme / 系统偏好，NIGHT 用例得 `day`）。

- [ ] **Step 3: 重写 boot.js**

```js
// FOUC prevention: set <html data-theme> BEFORE stylesheets apply.
// Reads reader_settings.theme — the same key readerPrefs.js /
// app.js applyGlobalAppTheme consume — so the pre-paint theme always
// matches the post-boot theme. (The legacy chrome_theme key was never
// written by any code and ignored the user's chosen theme.)
// Non-module script: carries a minimal copy of app.js's theme map.
(function () {
    var MAP = {
        DAY: 'day', DAY_BRIGHT: 'day_bright', EYE_CARE: 'eye_care',
        EYE_CARE_GREEN: 'eye_care_green', PARCHMENT: 'parchment',
        NIGHT: 'night', NIGHT_BLACK: 'night_black'
    };
    function resolve() {
        try {
            var raw = localStorage.getItem('reader_settings');
            var key = raw ? (JSON.parse(raw).theme || 'DAY') : 'DAY';
            if (key === 'AUTO') {
                key = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'NIGHT' : 'DAY';
            }
            return MAP[key] || 'day';
        } catch (_) {
            return 'day';
        }
    }
    document.documentElement.dataset.theme = resolve();
})();
```

- [ ] **Step 4: 跑测试通过 + 全量回归**

```bash
node --test boot.test.mjs && node --test
```

Expected: 全部 pass。

- [ ] **Step 5: Commit**

```bash
git add boot.js boot.test.mjs
git commit -m "fix(web): boot FOUC reads reader_settings theme, drop dead chrome_theme key (Phase 2)"
```

---

### Task 4: header 日/夜按钮接线 + readerPrefs 死代码清理

**Files:**
- Modify: `dom.js`, `app.js`, `readerPrefs.js`

**Interfaces:**
- Consumes: `elements.btnThemeToggle`（新增 DOM 映射）；`readerPrefs.getSettings()/saveSettings()`（已存在，`saveSettings` 触发 `reader-prefs-changed` 事件 → `applyGlobalAppTheme` 已监听）。
- Produces: header 按钮在 DAY↔NIGHT 间切换并即时生效；`updateThemeToggleIcon` 被真实调用。

- [ ] **Step 1: dom.js 增加映射**（加在 `btnTriggerScan` 行后）

```js
    btnThemeToggle: document.getElementById('btn-theme-toggle'),
```

- [ ] **Step 2: app.js 接线**

在 `applyGlobalAppTheme` 末尾（`document.body.dataset.readerTheme = themeKey;` 之后）加：

```js
    updateThemeToggleIcon(themeKey === 'NIGHT' || themeKey === 'NIGHT_BLACK' ? 'night' : 'day');
```

在 `setupEventListeners` 的 theme 同步监听之前加：

```js
    // Header day/night toggle: flips the persisted theme key; the
    // reader-prefs-changed event re-runs applyGlobalAppTheme, so the
    // chrome updates without a reload.
    if (elements.btnThemeToggle) {
        elements.btnThemeToggle.addEventListener('click', () => {
            const s = readerPrefs.getSettings();
            const resolved = s.theme === 'AUTO'
                ? (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'NIGHT' : 'DAY')
                : (s.theme || 'DAY');
            readerPrefs.saveSettings({ theme: resolved === 'NIGHT' ? 'DAY' : 'NIGHT' });
        });
    }
```

注意：`updateThemeToggleIcon` 目前定义在文件底部、被 hoist（function 声明），`applyGlobalAppTheme` 内直接调用成立。

- [ ] **Step 3: readerPrefs.js 删除 chrome 主题死代码**

删除 `// ── Chrome theme ... ──` 注释起的整段：`CHROME_THEME_KEY`、`CHROME_THEME_EVENT`、`getChromeTheme`、`saveChromeTheme`（已确认零调用方、零测试引用）。

- [ ] **Step 4: 回归 + 手动验证**

```bash
node --test
cd ../../../tools/xsscheck && go run . ../../server/internal/web
```

浏览器：点 header 日/夜按钮 → chrome 立即切换且硬刷新后保持（配合 Task 3）；图标日月随主题变。

- [ ] **Step 5: Commit**

```bash
git add dom.js app.js readerPrefs.js
git commit -m "fix(web): wire header theme toggle and remove dead chrome theme helpers (Phase 2)"
```

---

### Task 5: 共享组件重写（components.css）

**Files:**
- Modify: `css/components.css`（按钮段 431–541 来源、Glass Modal 段、Toast 段、Auth Modal 段、info/widget 段）

**Interfaces:**
- Consumes: Task 2 规范变量。
- Produces: 新设计语言组件基元；后续视图任务直接引用这些类。

- [ ] **Step 1: 替换按钮段为以下内容**（类名全部保留：`.btn` `.btn-primary` `.btn-theme-toggle` `.btn-sort-order` `.btn-search` `.btn-browse-drives` `.btn-transcode` `.video-delete-btn` `.btn-close`）

```css
/* ── Buttons: modern-neutral ── */
.btn {
    display: inline-flex; align-items: center; gap: var(--space-2);
    padding: 8px 14px;
    font-size: 13px; font-weight: 500;
    font-family: var(--font-sans);
    border-radius: var(--radius-sm);
    border: 1px solid var(--border-subtle);
    background: var(--surface-card);
    color: var(--text-primary);
    cursor: pointer;
    transition: background-color .15s ease, border-color .15s ease, color .15s ease, box-shadow .15s ease;
}
.btn:hover { background: var(--surface-hover); border-color: var(--accent); }
.btn:active { transform: translateY(0.5px); }
.btn:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }

.btn-primary {
    background: var(--accent); border-color: var(--accent);
    color: var(--text-on-accent);
}
.btn-primary:hover { background: var(--accent-hover); border-color: var(--accent-hover); }

.btn-theme-toggle {
    width: 36px; height: 36px; padding: 0; justify-content: center;
    background: transparent; border-color: transparent;
    color: var(--text-secondary);
}
.btn-theme-toggle:hover { background: var(--surface-hover); border-color: transparent; color: var(--text-primary); }

.btn-sort-order, .btn-search {
    height: 34px; padding: 0 10px; background: var(--surface-card);
}
.btn-search svg { display: block; }

.btn-close {
    width: 32px; height: 32px; padding: 0; justify-content: center;
    background: transparent; border: none; border-radius: var(--radius-sm);
    font-size: 16px; color: var(--text-secondary); cursor: pointer;
}
.btn-close:hover { background: var(--surface-hover); color: var(--text-primary); }
```

`.btn-browse-drives`/`.btn-transcode`/`.video-delete-btn` 保留原规则，仅把其中的 `var(--primary)`/`var(--primary-light)`/`var(--text-white)` 等别名按 Task 7 的替换表改写（此任务顺手做掉这三处即可，其余别名留给 Task 7）。`.video-delete-btn` 的 `color` 改 `var(--text-on-accent)`。

- [ ] **Step 2: 重写表单基元**（追加到 components.css；对应 index.html 的 input/select/textarea 类）

```css
/* ── Form primitives ── */
input[type="text"], input[type="password"], select, textarea {
    font-family: var(--font-sans); font-size: 13px;
    color: var(--text-primary);
    background: var(--surface-card);
    border: 1px solid var(--border-subtle);
    border-radius: var(--radius-sm);
    padding: 8px 10px;
    transition: border-color .15s ease, box-shadow .15s ease;
}
input:focus-visible, select:focus-visible, textarea:focus-visible {
    outline: none;
    border-color: var(--accent);
    box-shadow: 0 0 0 3px var(--accent-soft);
}
textarea { resize: vertical; line-height: 1.6; }
```

- [ ] **Step 3: 卡片与信息列表**（`.widget-card` `.info-item` 重写，`.stat-card` 留给 Task 8）

```css
.widget-card {
    background: var(--surface-card);
    border: 1px solid var(--border-subtle);
    border-radius: var(--radius-lg);
    padding: var(--space-5);
    box-shadow: var(--shadow-sm);
}
.widget-card h2 {
    font-size: 15px; font-weight: 600;
    color: var(--text-primary);
    margin: 0 0 var(--space-4);
}
.info-item {
    display: flex; justify-content: space-between; align-items: baseline; gap: var(--space-4);
    padding: 10px 0;
    border-bottom: 1px solid var(--border-soft);
}
.info-item:last-child { border-bottom: none; }
.info-label { font-size: 13px; color: var(--text-secondary); }
.info-value { font-size: 13px; color: var(--text-primary); text-align: right; word-break: break-all; }
```

- [ ] **Step 4: Modal / Toast / 空态**（Glass Modal Framework、Auth Modal、Toast 段内所有边框/阴影/圆角改走 token：`border: 1px solid var(--border-subtle)`、`border-radius: var(--radius-lg)`、`box-shadow: var(--shadow-md)`、backdrop `rgba(0,0,0,.45)` 浅色 `.35` 深色；`.toast-*` 成功色 `var(--secondary)`、错误色 `var(--error)`、圆角 `var(--radius-md)`；`.empty-state` 统一 `color: var(--text-muted); text-align: center; padding: var(--space-6);`。原段中 rgba 阴影与 hex 全部替换为上述 token 引用。）

- [ ] **Step 5: 全局 focus / reduced-motion**（追加到 `css/base.css`）

```css
:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
@media (prefers-reduced-motion: reduce) {
    *, *::before, *::after {
        animation-duration: 0.01ms !important;
        animation-iteration-count: 1 !important;
        transition-duration: 0.01ms !important;
    }
}
```

例外：reader.css 的翻页动画关键帧不受影响（类级规则优先级足够时仍会被 reduce 压制——这是预期行为，尊重用户偏好）。

- [ ] **Step 6: 视觉核对 + 回归 + Commit**

起 server 看仪表盘/设置/auth modal/toast（触发一次保存设置）在 day+night 下的观感；`node --test`。

```bash
git add css/components.css css/base.css
git commit -m "feat(web): rewrite shared components in modern-neutral language (Phase 3)"
```

---

### Task 6: index.html emoji→SVG

**Files:**
- Modify: `index.html`

**Interfaces:**
- Consumes: 无。
- Produces: 内联 SVG 图标（stroke `currentColor`、`stroke-width="1.75"`、`viewBox="0 0 24 24"`、`aria-hidden="true"`，尺寸 18 或 20）。

替换对照（位置 → 现内容 → 新内容；外层结构/类名/id 不变，只换图标节点）：

- [ ] **Step 1: 仪表盘统计卡**：`.stat-icon` 内 `📄`/`🎬`/`🖼️` →
  - 文本：`<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6M9 13h6M9 17h6"/></svg>`
  - 视频：`<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="2" y="5" width="14" height="14" rx="2"/><path d="m16 10 6-3v10l-6-3"/></svg>`
  - 图片：`<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="9" cy="9" r="2"/><path d="m21 15-4.35-4.35a1 1 0 0 0-1.42 0L5 21"/></svg>`
- [ ] **Step 2: 扫描按钮** `🔄` → `<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 12a9 9 0 1 1-2.64-6.36"/><path d="M21 3v6h-6"/></svg>`；**搜索按钮** `🔍` → `<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="m21 21-4.3-4.3"/></svg>`
- [ ] **Step 3: 视频控制条**：`▶`(播放) → `<svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor" stroke="none" aria-hidden="true"><path d="M8 5v14l11-7z"/></svg>`；`🔊` → `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M11 5 6 9H2v6h4l5 4z"/><path d="M15.5 8.5a5 5 0 0 1 0 7"/></svg>`；`⛶` → `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M8 3H5a2 2 0 0 0-2 2v3M16 3h3a2 2 0 0 1 2 2v3M8 21H5a2 2 0 0 1-2-2v-3M16 21h3a2 2 0 0 0 2-2v-3"/></svg>`；`🗑️ 删除` → 删 emoji 留文字「删除」。播放/暂停切换：`videoPlayer.js` 里若有 JS 改写 `textContent '▶'/'⏸'` 的逻辑，同步改为切换两个 `<svg>` 的 `hidden`（结构与 header 主题按钮的 sun/moon 双 span 同款：`<span data-icon="play">svg</span><span data-icon="pause" hidden>svg</span>`）。先 `grep -n "textContent\|innerHTML" videoPlayer.js` 确认所有播放态改写点再动手。
- [ ] **Step 4: 灯箱**：`◀`/`▶` → `<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m15 18-6-6 6-6"/></svg>`（prev）/ 镜像 `m9 18 6-6-6-6`（next）；`📖 拼接模式` 删 emoji 留文字。
- [ ] **Step 5: `node --test` + xsscheck + Commit**

```bash
node --test && cd ../../../tools/xsscheck && go run . ../../server/internal/web
git add index.html videoPlayer.js
git commit -m "feat(web): replace emoji icons with inline SVG (Phase 3)"
```

---

### Task 7: 旧别名变量清剿

**Files:**
- Modify: `css/*.css` `css/views/*.css`、`bookmarksView.js`、`css/themes.css`（删临时块）

**Interfaces:**
- Consumes: Task 2 的临时别名块。
- Produces: css 全树零别名引用；reader 覆盖块重定向到规范名。

**替换表**（对 css/ 全树做字面替换；注意有 fallback 形式，grep 用**前缀**匹配）：

| 旧写法 | 新写法 |
|---|---|
| `var(--bg-main)` | `var(--surface-app)` |
| `var(--bg-card)` | `var(--surface-card)` |
| `var(--bg-elevated, var(--bg-card))`（reader.css L2149 来源，仅 1 处） | `var(--reader-chrome-bg, var(--surface-card))` |
| `var(--primary)` | `var(--accent)` |
| `var(--primary-light)` | `color:` 上下文 → `var(--accent-text)`；`border`/`background` 上下文 → `var(--accent)` |
| `var(--text-main)` | `var(--text-primary)` |
| `var(--text-white)` | accent/error 实底上的文字/边 → `var(--text-on-accent)`；标题类 → `var(--text-primary)`（逐行判断，见 Step 2 清单） |
| `var(--border-color)` | `var(--border-subtle)` |
| `var(--border-radius-lg, 12px)`（reader.css dialog，仅 1 处） | `var(--radius-lg)` |
| `var(--transition-smooth)` | `all .3s cubic-bezier(.4,0,.2,1)` |
| `var(--transition-quick)` | `all .15s ease` |
| `var(--reader-X, var(--别名))` 链 | `var(--reader-X, var(--规范名))` |

- [ ] **Step 1: bookmarksView.js 迁移 5 处 CSSOM 引用**

```js
row.style.border = '1px solid var(--border-subtle)';        // 原 var(--border-color)
row.style.borderColor = 'var(--accent)';                    // hover，原 var(--primary)
row.style.borderColor = 'var(--border-subtle)';             // leave，原 var(--border-color)
bookTitle.style.color = 'var(--text-primary)';              // 原 var(--text-white)
```

- [ ] **Step 2: css 树替换**

执行替换表；`--text-white` 的 12 个消费行逐一分类（行号为拆分前 style.css 行号，拆分后用 grep 定位同内容行）：

- `--text-on-accent`：L514/L539（btn 族）、L861（播放遮罩图标）、L939/L950（卡片收藏/标签角标）、L1297/L1406（视频控制按钮）、L1027（accent 底上的边框）
- `--text-primary`：L1045/L1066（tag 面板标题类）、L1891（手机 modal 关闭区）、L2040（reader fallback 链）
- 分类存疑的行：默认 `--text-primary`，截图 night 主题核对后定稿。

- [ ] **Step 3: reader 覆盖块重定向**（css/themes.css 末尾块整体替换）

```css
/* 整体覆盖 App 变量：reader 子树内的表面/文字/边框 token 被 reader theme 接管。 */
body[data-reader-theme][data-active-tab="read"] .view-container,
body[data-reader-theme] .text-reader,
body[data-reader-theme] .text-reader__drawer,
body[data-reader-theme] dialog#reader-settings-dialog,
body[data-reader-theme] .text-reader__autoscroll-panel {
    --surface-card: var(--reader-chrome-bg);
    --surface-hover: rgba(0, 0, 0, 0.08);
    --text-primary: var(--reader-fg);
    --text-secondary: var(--reader-fg);
    --text-muted: var(--reader-muted);
    --border-subtle: var(--reader-border);
    --border-soft: var(--reader-border);
}
```

- [ ] **Step 4: 删除 themes.css 临时别名块**（Task 2 Step 1 加的整块）。

- [ ] **Step 5: 审计 grep（期望全部为空）**

```bash
grep -rn "var(--bg-main\|var(--bg-card\|var(--bg-sidebar\|var(--bg-elevated\|var(--primary)\|var(--primary-light\|var(--text-main\|var(--text-white\|var(--border-color\|var(--border-radius-lg\|var(--transition-" css/ *.js
```

（`var(--primary)` 需带右括号精确匹配以免误伤 `--primary-light`。）

- [ ] **Step 6: 回归 + 视觉核对 + Commit**

`node --test` + xsscheck；浏览器 night 主题进入阅读器打开 TOC drawer 与设置 dialog，确认表面/文字/边框被阅读主题接管（非全局 night 灰）。

```bash
git add -A css/ bookmarksView.js
git commit -m "refactor(web): purge legacy CSS variable aliases, retarget reader override (Phase 3)"
```

---

### Task 8: 仪表盘升级（统计卡 + 最近媒体缩略图）

**Files:**
- Modify: `index.html`（统计卡结构）、`dashboard.js`、`css/views/dashboard.css`

**Interfaces:**
- Consumes: `utils.js` 的 `encodeRoutePath`（**已存在导出**，直接 import）；`/api/v1/videos/<path>/thumbnail` 公开路由（无需 token）。
- Produces: `.stat-card__icon/--label/--value`、`.recent-item__thumb` 新类名（纯新增，无契约冲突）。

- [ ] **Step 1: index.html 统计卡结构**（三张卡同款；`id` 不变）

```html
<div class="stat-card">
    <div class="stat-card__icon stat-card__icon--text">
        <!-- 与 Task 6 相同的文档 SVG -->
    </div>
    <div class="stat-card__body">
        <p class="stat-card__label">文本文件</p>
        <p class="stat-card__value num-tabular" id="stat-texts">0</p>
    </div>
</div>
```

（视频卡 `--video` 用视频 SVG + 「视频文件」；图片卡 `--image` 用图片 SVG + 「图片文件」。）

- [ ] **Step 2: dashboard.js 最近媒体模板改写**

import 行加 `import { encodeRoutePath } from './utils.js';`（与现有 formatSize import 合并为一条）。成功分支模板替换为：

```js
// XSS-SAFE: dynamic fields (thumb url / name / size) all pass through escapeHtml()/formatSize()
elements.dashboardRecent.innerHTML = items.map((file, index) => {
    const thumb = `${state.apiBase}/api/v1/videos/${encodeRoutePath(file.path)}/thumbnail`;
    return `
        <div class="recent-item dashboard-recent-item" data-action="open-video" data-index="${index}">
            <img class="recent-item__thumb" src="${escapeHtml(thumb)}" alt="" loading="lazy" decoding="async">
            <span class="recent-item__name">${escapeHtml(file.name)}</span>
            <span class="recent-item__size num-tabular">${formatSize(file.size)}</span>
        </div>
    `;
}).join('');
```

在 `setupDashboardListeners` 中追加缩略图错误回退（capture 委托，照抄 browserView 模式；CSP 禁 inline onerror）：

```js
    // Thumbnail error fallback (capture: img 'error' does not bubble)
    elements.dashboardRecent.addEventListener('error', (e) => {
        const img = e.target;
        if (img instanceof HTMLImageElement && img.classList.contains('recent-item__thumb')) {
            const fb = document.createElement('div');
            fb.className = 'recent-item__thumb recent-item__thumb--fallback';
            img.replaceWith(fb);
        }
    }, true);
```

- [ ] **Step 3: dashboard.css 重写统计卡与最近列表**

```css
.stats-grid {
    display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--space-4);
}
.stat-card {
    display: flex; align-items: center; gap: var(--space-4);
    background: var(--surface-card);
    border: 1px solid var(--border-subtle);
    border-radius: var(--radius-lg);
    padding: var(--space-5);
    box-shadow: var(--shadow-sm);
    transition: box-shadow .15s ease, transform .15s ease;
}
.stat-card:hover { box-shadow: var(--shadow-md); transform: translateY(-1px); }
.stat-card__icon {
    display: grid; place-items: center;
    width: 44px; height: 44px; border-radius: var(--radius-md);
    background: var(--accent-soft); color: var(--accent-text);
}
.stat-card__label { margin: 0; font-size: 13px; color: var(--text-secondary); }
.stat-card__value {
    margin: 2px 0 0; font-size: 26px; font-weight: 600;
    color: var(--text-primary); line-height: 1.1;
}
.recent-item {
    display: flex; align-items: center; gap: var(--space-3);
    padding: 10px 12px; border-radius: var(--radius-md);
    cursor: pointer; transition: background-color .15s ease;
}
.recent-item:hover { background: var(--surface-hover); }
.recent-item__thumb {
    width: 64px; height: 36px; border-radius: var(--radius-sm);
    object-fit: cover; background: var(--surface-hover); flex: none;
}
.recent-item__thumb--fallback { display: grid; place-items: center; color: var(--text-muted); }
.recent-item__name {
    flex: 1; min-width: 0; font-size: 13px; color: var(--text-primary);
    overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.recent-item__size { font-size: 12px; color: var(--text-muted); flex: none; }
```

`.recent-item__thumb--fallback` 内放播放 SVG（`dashboard.js` 创建 `fb` 后 `fb.innerHTML = '<svg …play…>' // XSS-SAFE: 纯字面量`）。

- [ ] **Step 4: 回归 + Commit**

`node --test` + xsscheck；浏览器看仪表盘（有视频库时缩略图懒加载、失败回退）。

```bash
git add index.html dashboard.js css/views/dashboard.css
git commit -m "feat(web): dashboard stat cards and recent-media thumbnails (Phase 4)"
```

---

### Task 9: 书架书封卡（TDD）

**Files:**
- Modify: `bookshelf.js`, `css/views/bookshelf.css`
- Test: `bookshelf.test.mjs`（新建）

**Interfaces:**
- Consumes: localStorage `book_progress:<path>` 项（`{chapterIndex, scrollOffset, lastReadAt}`）。
- Produces: `coverGradientClass(title)`、`relativeTime(ts)` 具名导出（测试用）；`renderCard` 新结构。

- [ ] **Step 1: 写失败测试**

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { coverGradientClass, relativeTime } from './bookshelf.js';

test('coverGradientClass: deterministic and in g1..g8', () => {
    const a = coverGradientClass('三体');
    assert.equal(a, coverGradientClass('三体'));
    assert.match(a, /^bookshelf-card__cover--g[1-8]$/);
    for (const t of ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i']) {
        assert.match(coverGradientClass(t), /^bookshelf-card__cover--g[1-8]$/);
    }
});

test('relativeTime boundaries', () => {
    const now = Date.now();
    assert.equal(relativeTime(now - 30 * 1000), '刚刚');
    assert.equal(relativeTime(now - 5 * 60 * 1000), '5 分钟前');
    assert.equal(relativeTime(now - 3 * 3600 * 1000), '3 小时前');
    assert.equal(relativeTime(now - 2 * 24 * 3600 * 1000), '2 天前');
    assert.equal(relativeTime(now - 40 * 24 * 3600 * 1000), '1 个月前');
    assert.equal(relativeTime(now - 400 * 24 * 3600 * 1000), '1 年前');
    assert.equal(relativeTime(0), '');
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
node --test bookshelf.test.mjs
```

Expected: FAIL — named exports 不存在。

- [ ] **Step 3: bookshelf.js 实现新卡片**

在文件头部（`PREFIX` 定义后）加：

```js
export function coverGradientClass(title) {
    let h = 0;
    const s = String(title || '');
    for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
    return `bookshelf-card__cover--g${(h % 8) + 1}`;
}

export function relativeTime(ts) {
    if (!ts) return '';
    const diff = Date.now() - ts;
    const m = Math.floor(diff / 60000);
    if (m < 1) return '刚刚';
    if (m < 60) return `${m} 分钟前`;
    const h = Math.floor(m / 60);
    if (h < 24) return `${h} 小时前`;
    const d = Math.floor(h / 24);
    if (d < 30) return `${d} 天前`;
    const mo = Math.floor(d / 30);
    if (mo < 12) return `${mo} 个月前`;
    return `${Math.floor(mo / 12)} 年前`;
}
```

`renderCard` 整体替换：

```js
function renderCard(entry) {
    const card = document.createElement('div');
    card.className = 'bookshelf-card';
    const title = baseName(entry.path);
    const meta = `第 ${(entry.chapterIndex || 0) + 1} 章 · ${relativeTime(entry.lastReadAt)}`;
    // XSS-SAFE: pure-literal template; user data (title/meta) is set via textContent below
    card.innerHTML = `
        <div class="bookshelf-card__cover ${coverGradientClass(title)}">
            <span class="bookshelf-card__cover-title"></span>
        </div>
        <div class="bookshelf-card__meta">
            <div class="bookshelf-card__title"></div>
            <div class="bookshelf-card__progress"></div>
        </div>
    `;
    card.querySelector('.bookshelf-card__cover-title').textContent = title.slice(0, 8);
    card.querySelector('.bookshelf-card__title').textContent = title;
    card.querySelector('.bookshelf-card__progress').textContent = meta;
    card.addEventListener('click', () => {
        location.hash = '#/read?path=' + encodeURIComponent(entry.path);
    });
    return card;
}
```

（旧 `.bookshelf-card__icon/__title/__progress` 类删除，无 JS/测试依赖。）

- [ ] **Step 4: bookshelf.css 重写**

```css
.bookshelf-grid {
    display: grid; gap: var(--space-4);
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
}
.bookshelf-card {
    background: var(--surface-card);
    border: 1px solid var(--border-subtle);
    border-radius: var(--radius-lg);
    overflow: hidden; cursor: pointer;
    transition: box-shadow .15s ease, transform .15s ease;
}
.bookshelf-card:hover { box-shadow: var(--shadow-md); transform: translateY(-2px); }
.bookshelf-card__cover {
    aspect-ratio: 3 / 4;
    display: grid; place-items: center; padding: var(--space-3);
}
.bookshelf-card__cover-title {
    font-size: 15px; font-weight: 600; color: rgba(255, 255, 255, 0.92);
    text-align: center; word-break: break-all; line-height: 1.4;
}
.bookshelf-card__cover--g1 { background: linear-gradient(150deg, #5E6AD2, #8B5CF6); }
.bookshelf-card__cover--g2 { background: linear-gradient(150deg, #0EA5E9, #5E6AD2); }
.bookshelf-card__cover--g3 { background: linear-gradient(150deg, #14B8A6, #0EA5E9); }
.bookshelf-card__cover--g4 { background: linear-gradient(150deg, #22C55E, #14B8A6); }
.bookshelf-card__cover--g5 { background: linear-gradient(150deg, #F59E0B, #EF4444); }
.bookshelf-card__cover--g6 { background: linear-gradient(150deg, #EC4899, #8B5CF6); }
.bookshelf-card__cover--g7 { background: linear-gradient(150deg, #64748B, #334155); }
.bookshelf-card__cover--g8 { background: linear-gradient(150deg, #7B87E8, #3B4A6B); }
.bookshelf-card__meta { padding: var(--space-3) var(--space-4) var(--space-4); }
.bookshelf-card__title {
    font-size: 13px; font-weight: 500; color: var(--text-primary);
    overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.bookshelf-card__progress { margin-top: 2px; font-size: 12px; color: var(--text-muted); }
```

（渐变是固定 CSS 类，非 inline style，CSP 合规。）

- [ ] **Step 5: 测试通过 + 回归 + Commit**

```bash
node --test bookshelf.test.mjs && node --test && cd ../../../tools/xsscheck && go run . ../../server/internal/web
git add bookshelf.js bookshelf.test.mjs css/views/bookshelf.css
git commit -m "feat(web): bookshelf cover cards with reading meta (Phase 4)"
```

---

### Task 10: 阅读器 chrome 对齐

**Files:**
- Modify: `css/views/reader.css`

**Interfaces:**
- Consumes: Task 7 后的规范 token + reader 覆盖块。
- Produces: 阅读器 chrome（非正文）新观感。**禁区**：`@keyframes`（翻页/章节淡入）、`.pt-*` 翻页类、`--reader-*` 消费链、正文排版规则（`--reader-font-size` 等消费处）一律不动。

- [ ] **Step 1: chrome 表面规则改写**（TOC drawer、书签 tab 面板、设置 dialog、autoscroll 面板、顶部进度条、面包屑/标题行）：所有 `border-radius` → `var(--radius-md)`（dialog 用 `--radius-lg`）；阴影 → `var(--shadow-md)`；边框 → `var(--border-subtle)`（这些选择器在 reader 覆盖块作用域内，自动吃到阅读主题）；按钮态 hover 背景 `var(--surface-hover)`。
- [ ] **Step 2: TOC tabs 激活态**：active tab `background: var(--accent-soft); color: var(--accent-text);`，非激活 `color: var(--text-secondary)`；active 条目左侧 3px accent 竖条（现有 inset box-shadow 已在 Task 7 变 accent，核对即可）。
- [ ] **Step 3: 顶部进度条**：进度条填充色 `var(--accent)`，轨道 `var(--border-soft)`，高度 3px 圆角。
- [ ] **Step 4: 回归（重点跑 reader 套件）+ Commit**

```bash
node --test
```

浏览器：打开一本 txt，检查 drawer/书签/设置 dialog/自动滚动面板在 DAY 与 NIGHT **阅读主题**下的观感与接管（chrome 用阅读主题色，不再是全局灰）。

```bash
git add css/views/reader.css
git commit -m "feat(web): reader chrome aligned to new tokens (Phase 5)"
```

---

### Task 11: 其余视图打磨 + 响应式容错

**Files:**
- Modify: `css/views/browser.css`, `css/views/settings.css`, `css/views/video.css`, `css/views/lightbox.css`, `css/views/bookmarks.css`, `css/responsive.css`

**Interfaces:**
- Consumes: 前序任务的组件基元与 token。

- [ ] **Step 1: browser.css** — 媒体/文件夹卡片：`border: 1px solid var(--border-subtle)`、`border-radius: var(--radius-lg)`、hover `translateY(-2px)` + `var(--shadow-md)`（150ms）；卡片标题 13px/500、次级信息 12px muted；排序/搜索控件沿用 Task 5 表单基元（核对 `.sort-select`/`.search-box input` 无残留旧样式覆盖）；空态（`.browser-empty-grid`）muted 居中。
- [ ] **Step 2: settings.css** — `.settings-card` 用 `.widget-card` 同款（边框+radius-lg+shadow-sm）；主题网格卡 hover 边框 accent。
- [ ] **Step 3: video.css** — 控制按钮统一 36px 圆形 hover `var(--surface-hover)`（半透明黑底上用 `rgba(255,255,255,.12)`）；进度/音量滑杆 accent 填充；速度菜单卡片 shadow-md + radius-md。
- [ ] **Step 4: lightbox.css / bookmarks.css** — 灯箱按钮半透明白 hover；书签列表行用 `--radius-md` hover `--surface-hover`（bookmarksView.js 的 CSSOM 已是规范名）。
- [ ] **Step 5: responsive.css 容错** — DevTools 375px/768px 宽过一遍各视图，修溢出：统计卡 3→1 列（已有断点则核对）、浏览网格列数下限、工具栏换行、dialog 宽度 `calc(100% - 32px)` 核对。只修破版，不做新布局。
- [ ] **Step 6: 回归 + Commit**

```bash
node --test
git add css/
git commit -m "feat(web): polish browser/settings/video/lightbox views and responsive fixes (Phase 5)"
```

---

### Task 12: 全量视觉验证 + 终回归

**Files:**
- Modify: 发现问题则修对应文件（允许追加 fix commit）

**Interfaces:** 无新接口。

- [ ] **Step 1: 起服务**

```bash
cd ../../../server && go build -o LocalMediaHub.exe ./cmd/server
./LocalMediaHub.exe --headless --auto-detect-roots &
```

- [ ] **Step 2: 截图矩阵**（浏览器工具，`http://127.0.0.1:8000`）：仪表盘 / 浏览 / 书签 / 设置 / 书架（`#/bookshelf`）/ 阅读器（任一书，含 TOC drawer 开）/ 视频弹层 / 灯箱 × 主题 day、night、night_black。每屏核对：无裸样式、无溢出、focus ring 可见（Tab 走查侧栏+工具栏）。
- [ ] **Step 3: 主题专项**：
  1. 设置页选 NIGHT → 硬刷新 → **无 day 闪白**（FOUC 修复）；
  2. header 日/夜按钮点击即时切换，硬刷新保持；
  3. NIGHT **阅读主题**下开 TOC drawer + 设置 dialog → chrome 是阅读主题色（覆盖块未失效）；
  4. 抽验 eye_care / parchment 亮色主题文字对比度（正文 ≥ 4.5:1）。
- [ ] **Step 4: 终回归**

```bash
cd ../internal/web && node --test
cd ../../../tools/xsscheck && go run . ../../server/internal/web
cd ../../server && go build ./...
taskkill //F //IM LocalMediaHub.exe
```

- [ ] **Step 5: 修复发现的问题并提交（如有）**

```bash
git commit -m "fix(web): visual verification fixes (Phase 5)"
```

---

## Self-Review 记录

- Spec 覆盖：文件架构（T1）、7 调色板（T2）、boot.js 缺陷（T3）、按钮接线（T4）、组件/表单/焦点/reduced-motion（T5/T6）、别名清剿+覆盖块重定向+bookmarksView 迁移（T7）、仪表盘（T8）、书架（T9）、阅读器 chrome（T10）、其余视图+响应式（T11）、验证矩阵（T12）——spec 全部章节有对应任务。
- 类型/名称一致性：`coverGradientClass`/`relativeTime` 在 T9 测试与实现一致；`recent-item__thumb` 类名 T8 内自洽；`encodeRoutePath` 来自 utils.js（无需改 browserView.js——修正了 spec 表中"从 browserView 导出"的表述）。
- 与 spec 的两处已确认偏差：① 新增 `css/responsive.css`（spec 把断点归 layout.css，但层叠顺序要求断点规则最后加载，独立文件最稳）；② `encodeRoutePath` 已在 utils.js 导出，无需动 browserView.js。均已在计划内注明。
