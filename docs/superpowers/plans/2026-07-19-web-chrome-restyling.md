# Web 端 chrome 重写实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 web 端 chrome（顶栏/侧栏/卡片/按钮/状态条/模态外壳）从两版不一致的样式（深色紫渐变 vs 亮色三色混搭）重写为温暖中性 + 单一 terracotta 强调色 + DAY/NIGHT 双主题切换，引入 Open Props 作为 token 子集。

**Architecture:** 主题通过 `<html data-theme="day|night">` 切换，独立 localStorage key `chrome_theme`，独立 `chrome-theme-changed` 事件（与 `reader-prefs-changed` 解耦）。FOUC 防闪靠 `<head>` 内同步 inline script。Open Props 仅作 dev 时 token 子集来源（npm devDep + 构建脚本抽到 `tokens.css`），运行时零依赖。

**Tech Stack:** Vanilla JS ES Modules、CSS Custom Properties、Open Props（dev only）、Go embed。

## Global Constraints

- 项目无前端测试基建，每个任务用**手动浏览器验证**作为测试循环（与 spec §3 一致）
- 不修改阅读器内部 UI 作用域（`body[data-reader-theme]` 内的样式保持原样）
- 不引入 JS 框架、不动 Android 端、不动视频/lightbox 模态内容结构
- 所有新增/修改文件路径以仓库根 `E:\github_project\LocalMediaHub` 为基准
- Shell 使用 bash（Windows 11 下），路径用正斜杠
- 颜色 token 必须与 spec §5 逐字一致（不要二次创作色值）
- Open Props 必须锁定版本，`build-tokens.mjs` 只抽白名单变量（不导入完整 open-props）

---

### Task 1: chrome 主题状态基础设施（readerPrefs API + FOUC + 独立事件）

**Files:**
- Modify: `server/internal/web/readerPrefs.js`（在文件末尾追加）
- Create: `server/internal/web/boot.js`（FOUC 防闪外部脚本）
- Modify: `server/internal/web/index.html`（`<head>` 顶部插入 `<script src="boot.js">`，在 stylesheet link 之前）
- Test: 浏览器手动（详见步骤 5）

**CSP 约束（重要）：** 项目 CSP 是 `script-src 'self'`，明确禁止 inline script。**不要**用 inline `<script>` 写 FOUC 逻辑——会被浏览器拒绝。必须用外部 `boot.js` 通过 `<script src="boot.js"></script>` 加载。

**Interfaces:**
- Produces:
  - `getChromeTheme(): 'day' | 'night'` — 读取 `localStorage['chrome_theme']`，无值时返回 `'day'`
  - `saveChromeTheme(theme: 'day' | 'night'): void` — 写入并 dispatch `window` 上的 `chrome-theme-changed` 事件（`event.detail = { theme }`）
  - `<html data-theme="day|night">` — 由 `boot.js` 在 stylesheet 加载前同步设置

**Why this is Task 1:** 后续所有任务（顶栏按钮、CSS 主题块、app.js 监听）都依赖这两个函数和 `<html data-theme>` 已就位。

- [ ] **Step 1: 在 `readerPrefs.js` 末尾追加 chrome theme API**

打开 `server/internal/web/readerPrefs.js`，在文件最末尾追加：

```javascript

// ── Chrome theme (web shell, decoupled from reader_settings.theme) ──
// 独立 key + 独立事件，避免触发 textReader.js 重绘。
const CHROME_THEME_KEY = 'chrome_theme';
const CHROME_THEME_EVENT = 'chrome-theme-changed';

export function getChromeTheme() {
    const v = localStorage.getItem(CHROME_THEME_KEY);
    return v === 'night' ? 'night' : 'day';
}

export function saveChromeTheme(theme) {
    const next = theme === 'night' ? 'night' : 'day';
    try {
        localStorage.setItem(CHROME_THEME_KEY, next);
        window.dispatchEvent(new CustomEvent(CHROME_THEME_EVENT, { detail: { theme: next } }));
    } catch (e) {
        console.warn('readerPrefs.saveChromeTheme failed:', e);
    }
    return next;
}
```

- [ ] **Step 2: 创建 `server/internal/web/boot.js`**

写入 `server/internal/web/boot.js`（约 13 行 IIFE；外部文件以规避 CSP 对 inline script 的禁用）：

```javascript
// FOUC prevention: set <html data-theme> BEFORE stylesheets apply.
// Loaded via <script src="boot.js"> in <head>, ahead of <link rel="stylesheet">.
// Must NOT be inline — project CSP is script-src 'self' (no 'unsafe-inline').
(function () {
    try {
        var t = localStorage.getItem('chrome_theme');
        if (t !== 'day' && t !== 'night') {
            t = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'night' : 'day';
        }
        document.documentElement.dataset.theme = t;
    } catch (_) {
        document.documentElement.dataset.theme = 'day';
    }
})();
```

- [ ] **Step 3: 在 `index.html` 的 `<head>` 顶端插入 `<script src="boot.js">`**

打开 `server/internal/web/index.html`，在 `<head>` 内、`<link rel="stylesheet">` **之前**插入一行（其他 `<head>` 子元素保留不动）：

```html
    <script src="boot.js"></script>
```

具体定位：找到 `<link rel="icon" ...>` 那一行，紧接其后插入 `<script src="boot.js"></script>`，再后面是原有的 `<link rel="stylesheet" href="style.css">`。

注意：`tokens.css` 的 `<link>` 在 Task 2 加。

- [ ] **Step 4: 确认 `web.go` embed 会包含 boot.js**

`server/internal/web/web.go:6` 当前是 `//go:embed index.html style.css *.js fonts/*.woff2`，`*.js` 通配符已经覆盖 `boot.js`，无需改动。Task 2 会把 `style.css` 也改为 `*.css`。

- [ ] **Step 5: 启动 server 并打开浏览器**

```bash
cd server && go run ./cmd/server 2>&1 | head -20
```

（项目入口确认在 `server/cmd/server/main.go`。打开浏览器访问 `http://localhost:8000/` 或 server 启动日志里打印的端口。）

- [ ] **Step 6: 手动验证 FOUC + API 工作**

打开浏览器 DevTools Console，依次执行：

```javascript
// 1. 确认 <html data-theme> 已设置（应该有值，不再为空）
document.documentElement.dataset.theme
// 预期: 'day' 或 'night'

// 2. 切换到 night 并刷新
localStorage.setItem('chrome_theme', 'night'); location.reload();
// 刷新后页面背景应仍是默认深色（旧 CSS），但 <html data-theme> 应为 'night'
document.documentElement.dataset.theme  // 'night'

// 3. 验证 saveChromeTheme 派发事件
import('./readerPrefs.js').then(m => {
  window.addEventListener('chrome-theme-changed', e => console.log('event:', e.detail));
  m.saveChromeTheme('day');
});
// 预期 console 打印: event: { theme: 'day' }

// 4. 还原
localStorage.setItem('chrome_theme', 'day'); location.reload();
```

- [ ] **Step 7: Commit**

```bash
git add server/internal/web/readerPrefs.js server/internal/web/boot.js server/internal/web/index.html
git commit -m "$(cat <<'EOF'
feat(web): chrome theme API + FOUC prevention (boot.js)

Add getChromeTheme/saveChromeTheme in readerPrefs.js with independent
'chrome-theme-changed' event (decoupled from reader-prefs-changed to
avoid triggering textReader repaints). External boot.js sets
<html data-theme> synchronously before stylesheets load to prevent
FOUC; external file (not inline) because project CSP is
script-src 'self'.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: 引入 Open Props 作为 token 子集来源

**Files:**
- Create: `server/internal/web/package.json`
- Create: `server/internal/web/.gitignore`
- Create: `server/internal/web/tools/build-tokens.mjs`
- Create: `server/internal/web/tokens.css`（由脚本生成）
- Modify: `server/internal/web/web.go:6`
- Modify: `server/internal/web/index.html`（`<head>` 加 `<link>`）

**Interfaces:**
- Produces:
  - `server/internal/web/tokens.css` — Open Props 子集（仅 `--size-*`、`--radius-*`、`--shadow-*`），约 60 行
  - `web.go` embed 通过 `*.css` 通配自动包含 `tokens.css`

**Why before CSS 重写:** Task 3 的 `:root` 块会 `@import` 或直接复用 tokens.css 的 size/radius/shadow。

- [ ] **Step 1: 创建 `.gitignore`**

写入 `server/internal/web/.gitignore`：

```
node_modules/
```

- [ ] **Step 2: 创建 `package.json`**

写入 `server/internal/web/package.json`：

```json
{
  "name": "localmediahub-web",
  "version": "0.2.0",
  "private": true,
  "description": "Build-time tooling for the LocalMediaHub web manager.",
  "scripts": {
    "build:tokens": "node tools/build-tokens.mjs"
  },
  "devDependencies": {
    "open-props": "1.7.7"
  }
}
```

版本 `1.7.7` 是写计划时 Open Props 的稳定版本；如果 `npm install` 报 404/ETARGET，执行 `npm view open-props versions --json | tail -5` 选最新偶数版本（避免奇数测试版）。

- [ ] **Step 3: 创建 `tools/build-tokens.mjs`**

写入 `server/internal/web/tools/build-tokens.mjs`：

```javascript
// 从 open-props 抽取白名单 token 子集到 tokens.css。
// 只抽取我们需要的 size/radius/shadow；颜色由 style.css 自己定义（与 readerPrefs 对齐）。
// 运行：npm run build:tokens
import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = dirname(HERE);
const OP = join(WEB_DIR, 'node_modules', 'open-props');

// 白名单：每条 = (源文件名, 取行的正则)
const SOURCES = [
    // sizes（间距）
    ['sizes.min.css', /^--size-(extra-)?(small|large|[0-9]+).*$/gm],
    // radius
    ['radius.min.css', /^--radius-[a-z0-9-]+.*$/gm],
    // shadows
    ['shadows.min.css', /^--shadow-[a-z0-9-]+.*$/gm],
];

function pickRules(text, pattern) {
    return [...text.matchAll(pattern)].map(m => '  ' + m[0].trim());
}

const lines = ['/* AUTO-GENERATED by tools/build-tokens.mjs — do not edit by hand. */', ':root {'];
for (const [fname, pat] of SOURCES) {
    const p = join(OP, fname);
    const txt = await readFile(p, 'utf8').catch(() => '');
    if (!txt) {
        console.warn(`[build-tokens] WARN: ${fname} not found in open-props — skipping`);
        continue;
    }
    lines.push(...pickRules(txt, pat));
}
lines.push('}');

const out = lines.join('\n') + '\n';
await writeFile(join(WEB_DIR, 'tokens.css'), out);
console.log(`[build-tokens] wrote tokens.css (${out.split('\n').length} lines)`);
```

- [ ] **Step 4: 安装依赖并生成 tokens.css**

```bash
cd server/internal/web && npm install && npm run build:tokens && head -5 tokens.css
```

预期：`tokens.css` 创建，首行是 `/* AUTO-GENERATED ...`，包含若干 `--size-*`、`--radius-*`、`--shadow-*` 变量。如果失败（例如文件名不是 `sizes.min.css`），先 `ls node_modules/open-props/` 看实际文件名再调整 SOURCES。

- [ ] **Step 5: 修改 `web.go` embed 指令**

打开 `server/internal/web/web.go`，把第 6 行：

```go
//go:embed index.html style.css *.js fonts/*.woff2
```

改为：

```go
//go:embed index.html *.css *.js fonts/*.woff2
```

- [ ] **Step 6: 在 `index.html` 的 `<head>` 加 tokens.css 链接**

把 Task 1 Step 2 中的 `<link rel="stylesheet" href="style.css">` 那一行替换为两行：

```html
    <link rel="stylesheet" href="tokens.css">
    <link rel="stylesheet" href="style.css">
```

（顺序：tokens.css 在前，让 style.css 中的覆盖优先。）

- [ ] **Step 7: 验证 go build 通过 + 浏览器能取到 tokens.css**

```bash
cd server && go build ./...
```

预期：无错误。然后启动 server，浏览器 DevTools Network 面板应能看到 `tokens.css` 200 OK。

- [ ] **Step 8: Commit**

```bash
git add server/internal/web/package.json server/internal/web/.gitignore server/internal/web/tools/ server/internal/web/tokens.css server/internal/web/web.go server/internal/web/index.html
git commit -m "$(cat <<'EOF'
build(web): introduce Open Props as design token source

Add package.json with open-props devDep, tools/build-tokens.mjs that
extracts size/radius/shadow subset into tokens.css. Switch web.go
embed to *.css glob so tokens.css is picked up automatically.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: style.css token 体系与 DAY/NIGHT 主题块

**Files:**
- Modify: `server/internal/web/style.css:1-50`（替换现有 `:root` 块）

**Interfaces:**
- Produces:
  - CSS 变量定义见 spec §5（`--surface-app/--surface-card/--surface-sidebar/--text-primary/--text-secondary/--text-muted/--accent/--accent-hover/--accent-soft/--border-subtle/--shadow-sm/--shadow-md/--radius-*/--space-*`）
  - `[data-theme="day"]`（默认）和 `[data-theme="night"]` 两套

**Why now:** Task 4-5 的所有组件 CSS 都引用这些变量。

- [ ] **Step 1: 备份现有 :root 块的旧变量名清单**

打开 `server/internal/web/style.css`，把第 1-50 行（含 `@font-face` 与 `:root {...}`）原样保留作为参考。grep 出全文使用的旧变量名：

```bash
cd server/internal/web && grep -oE 'var\(--[a-z-]+\)' style.css | sort -u
```

记录输出（应包含 `--bg-main`、`--bg-card`、`--bg-sidebar`、`--primary`、`--primary-gradient`、`--text-main`、`--text-muted`、`--text-white`、`--border-color`、`--shadow-premium`、`--border-radius-*`、`--secondary`、`--error`、`--font-sans`、`--glass-blur`、`--transition-*` 等）。Task 4-5 会逐个替换它们；本 Step 只做记录。

- [ ] **Step 2: 替换 `:root` 块**

把 `style.css` 第 21-50 行的整个 `:root { ... }` 替换为：

```css
/* Core Design Tokens — DAY (default) */
:root,
[data-theme="day"] {
    color-scheme: light;

    /* surfaces (与 readerPrefs.js DAY 对齐) */
    --surface-app:     #FAF8F3;
    --surface-card:    #FFFFFF;
    --surface-sidebar: #F2EFE7;
    --surface-hover:   rgba(199, 91, 57, 0.08);

    /* text */
    --text-primary:    #2B2B2B;
    --text-secondary:  #5A5A57;
    --text-muted:      #7A7A78;
    --text-on-accent:  #FFFFFF;

    /* accent — terracotta（唯一强调色） */
    --accent:          #C75B39;
    --accent-hover:    #B14E2E;
    --accent-soft:     rgba(199, 91, 57, 0.12);

    /* borders & shadows */
    --border-subtle:   #E5E2D8;
    --shadow-sm:       0 1px 2px rgba(43, 43, 43, 0.04), 0 1px 3px rgba(43, 43, 43, 0.06);
    --shadow-md:       0 4px 12px rgba(43, 43, 43, 0.08);

    /* shape & rhythm */
    --radius-sm: 6px;
    --radius-md: 10px;
    --radius-lg: 14px;
    --space-1: 4px;
    --space-2: 8px;
    --space-3: 12px;
    --space-4: 16px;
    --space-5: 24px;
    --space-6: 32px;

    /* 语义别名（向后兼容现有引用） */
    --bg-main:     var(--surface-app);
    --bg-card:     var(--surface-card);
    --bg-sidebar:  var(--surface-sidebar);
    --primary:     var(--accent);
    --text-main:   var(--text-primary);
    --text-white:  var(--text-on-accent);
    --border-color: var(--border-subtle);
    --error: #C0392B;
    --secondary: #4F8A6B;
    --font-sans: system-ui, -apple-system, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, 'PingFang SC', 'Microsoft YaHei', sans-serif;
    --transition-smooth: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
    --transition-quick: all 0.15s ease;
}

/* Core Design Tokens — NIGHT */
[data-theme="night"] {
    color-scheme: dark;

    --surface-app:     #1A1A1F;
    --surface-card:    #232328;
    --surface-sidebar: #16161A;
    --surface-hover:   rgba(199, 91, 57, 0.16);

    --text-primary:    #C9C9CE;
    --text-secondary:  #A8A8AD;
    --text-muted:      #84848A;
    --text-on-accent:  #FFFFFF;

    --accent:          #D97A56;
    --accent-hover:    #E68B6A;
    --accent-soft:     rgba(217, 122, 86, 0.16);

    --border-subtle:   #2D2D33;
    --shadow-sm:       0 1px 2px rgba(0, 0, 0, 0.4);
    --shadow-md:       0 4px 12px rgba(0, 0, 0, 0.5);
}
```

注意：语义别名（`--bg-main`、`--primary` 等）的目的是让 Task 4-5 还没改完的旧规则不会立刻全部失效，降低中间态破坏面。Task 4-5 会逐步把这些旧别名引用替换为语义变量，最后 Task 8 视情况删除别名。

- [ ] **Step 3: 修改 `body` 默认背景**

把 `style.css` 中第 61-67 行的 `body { ... }` 规则改为：

```css
body {
    background-color: var(--surface-app);
    color: var(--text-primary);
    font-family: var(--font-sans);
    overflow: hidden;
    height: 100vh;
}
```

- [ ] **Step 4: 浏览器手动验证**

启动 server，浏览器访问。DevTools 给 `<html>` 添加/移除 `data-theme="night"`：

- `[data-theme="day"]`（默认）：背景应是温暖米色 `#FAF8F3`，文字深灰
- `[data-theme="night"]`：背景应变为深色 `#1A1A1F`，文字浅灰

此时旧组件样式还会出现紫色 gradient（因为还没改 Task 4），是预期的中间态。**关键校验**：背景色与文字色正确跟随 `data-theme`。

- [ ] **Step 5: Commit**

```bash
git add server/internal/web/style.css
git commit -m "$(cat <<'EOF'
feat(web): DAY/NIGHT design tokens with semantic aliases

Replace dark-themed :root with [data-theme=day] (default) and
[data-theme=night] token blocks. Surfaces/text/accent match the
readerPrefs DAY/NIGHT presets. Old variable names (--bg-main,
--primary, etc.) kept as semantic aliases so unrefactored rules
remain functional during the chrome rewrite.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: 重写 chrome 骨架（sidebar + main-header + menu-item + brand）

**Files:**
- Modify: `server/internal/web/style.css` 第 69-198 行附近（`.app-container` / `.sidebar` / `.sidebar-brand` / `.brand-gradient` / `.brand-version` / `.sidebar-menu` / `.menu-item` / `.menu-item:hover` / `.menu-item.active` / `.menu-icon` / `.sidebar-footer` / `.server-status` / `.status-indicator` / `.main-content` / `.main-header` / `.main-header h1`）

**Interfaces:**
- Consumes: Task 3 的所有 token
- Produces: chrome 组件级样式（侧栏、顶栏、菜单选中态、server-status pill）

- [ ] **Step 1: 重写 `.app-container` 与 `.sidebar`**

在 `style.css` 找到 `.app-container { ... }` 与 `.sidebar { ... }`（约第 70、78 行），整体替换为：

```css
/* App Layout Grid */
.app-container {
    display: grid;
    grid-template-columns: 260px 1fr;
    height: 100vh;
    width: 100vw;
}

/* Sidebar Navigation */
.sidebar {
    background-color: var(--surface-sidebar);
    border-right: 1px solid var(--border-subtle);
    display: flex;
    flex-direction: column;
    padding: var(--space-5) var(--space-4);
    justify-content: space-between;
}
```

- [ ] **Step 2: 重写 brand 区，删除紫色 gradient text**

把 `.sidebar-brand`、`.brand-gradient`、`.brand-version` 替换为：

```css
.sidebar-brand {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    padding: var(--space-2) var(--space-3) var(--space-5);
    border-bottom: 1px solid var(--border-subtle);
}

.brand-monogram {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 28px;
    height: 28px;
    background: var(--accent);
    color: var(--text-on-accent);
    font-weight: 700;
    font-size: 12px;
    letter-spacing: 0.5px;
    border-radius: var(--radius-sm);
}

.brand-name {
    font-size: 16px;
    font-weight: 600;
    color: var(--text-primary);
    letter-spacing: -0.3px;
}

.brand-version {
    font-size: 11px;
    background: var(--surface-card);
    color: var(--text-muted);
    padding: 2px 6px;
    border-radius: var(--radius-sm);
    border: 1px solid var(--border-subtle);
    font-weight: 500;
}
```

- [ ] **Step 3: 修改 `index.html` 的 brand 结构**

打开 `index.html`，找到第 14-17 行的 `.sidebar-brand`：

```html
<div class="sidebar-brand">
    <span class="brand-gradient">LocalMediaHub</span>
    <span class="brand-version">v0.2.0</span>
</div>
```

替换为：

```html
<div class="sidebar-brand">
    <span class="brand-monogram">LMH</span>
    <span class="brand-name">LocalMediaHub</span>
    <span class="brand-version">v0.2.0</span>
</div>
```

- [ ] **Step 4: 重写 menu-item 与选中态（删除紫色 gradient + glow）**

把 `.sidebar-menu`、`.menu-item`、`.menu-item:hover`、`.menu-item.active`、`.menu-icon` 整体替换为：

```css
.sidebar-menu {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    margin-top: var(--space-5);
    flex-grow: 1;
}

.menu-item {
    position: relative;
    display: flex;
    align-items: center;
    gap: var(--space-3);
    padding: var(--space-3) var(--space-4);
    color: var(--text-secondary);
    text-decoration: none;
    font-weight: 500;
    font-size: 14px;
    border-radius: var(--radius-md);
    transition: var(--transition-quick);
}

.menu-item:hover {
    color: var(--text-primary);
    background-color: var(--surface-hover);
}

.menu-item.active {
    color: var(--accent);
    background-color: var(--accent-soft);
}

.menu-item.active::before {
    content: "";
    position: absolute;
    left: 0;
    top: 8px;
    bottom: 8px;
    width: 3px;
    border-radius: 0 2px 2px 0;
    background: var(--accent);
}

.menu-icon {
    font-size: 16px;
    display: inline-flex;
    width: 20px;
    height: 20px;
    align-items: center;
    justify-content: center;
}
```

- [ ] **Step 5: 重写 server-status 为 pill badge**

把 `.sidebar-footer`、`.server-status`、`.status-indicator`、`.status-indicator.online` 替换为：

```css
.sidebar-footer {
    padding-top: var(--space-4);
    border-top: 1px solid var(--border-subtle);
}

.server-status {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    padding: 6px 12px;
    font-size: 13px;
    color: var(--text-secondary);
    background: var(--surface-card);
    border: 1px solid var(--border-subtle);
    border-radius: 999px;
}

.status-indicator {
    width: 8px;
    height: 8px;
    border-radius: 50%;
}

.status-indicator.online {
    background-color: #4F8A6B;
    box-shadow: 0 0 0 3px rgba(79, 138, 107, 0.2);
}
```

- [ ] **Step 6: 重写 main-header（删除 glass blur）**

把 `.main-content`、`.main-header`、`.main-header h1` 替换为：

```css
.main-content {
    display: flex;
    flex-direction: column;
    height: 100vh;
    overflow: hidden;
    background: var(--surface-app);
}

.main-header {
    height: 56px;
    border-bottom: 1px solid var(--border-subtle);
    padding: 0 var(--space-6);
    display: flex;
    align-items: center;
    justify-content: space-between;
    background-color: var(--surface-card);
    z-index: 10;
}

.main-header h1 {
    font-size: 18px;
    font-weight: 600;
    color: var(--text-primary);
    letter-spacing: -0.3px;
}
```

- [ ] **Step 7: 浏览器手动验证**

启动 server，访问每个页面（`#/dashboard`、`#/browser`、`#/bookmarks`、`#/settings`）：

- 侧栏背景是 `#F2EFE7`（DAY）/ `#16161A`（NIGHT），不再是深色 `#0f0f18`
- 选中菜单项显示 terracotta 软背景 + 左侧 3px terracotta 竖条
- brand 区显示 terracotta "LMH" monogram + "LocalMediaHub" + version badge
- server-status 是 pill badge（白底圆角带绿点）
- 顶栏 56px、白底、无 backdrop-blur
- 顶部"立即扫描媒体"按钮此时仍是紫色 gradient（Task 5 修），是预期中间态

切换 `data-theme="night"` 再看一遍。

- [ ] **Step 8: Commit**

```bash
git add server/internal/web/style.css server/internal/web/index.html
git commit -m "$(cat <<'EOF'
feat(web): rewrite chrome shell — sidebar, header, menu, status

Sidebar switches to surface-sidebar bg with terracotta-tinted active
state (3px left bar + soft fill) replacing the purple gradient+glow.
Brand uses LMH monogram tile. server-status becomes a pill badge.
Header shrinks to 56px and drops backdrop-filter glass.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: 卡片、按钮、表单控件重写（含暗色 input/textarea 覆盖）

**Files:**
- Modify: `server/internal/web/style.css`（搜索 `.btn-primary`、`.stat-card`、`.widget-card`、`.settings-card`、`.btn`、`.breadcrumbs`、`.search-box input`、`.browser-toolbar`，逐个改写；新增全局 `input, textarea, select` 规则）

**Interfaces:**
- Consumes: Task 3 token；Task 4 chrome 骨架
- Produces: 主按钮 terracotta、卡片统一 surface-card + shadow、表单控件暗色安全

- [ ] **Step 1: 全局 input/textarea/select 暗色覆盖**

在 `style.css` 的 `body { ... }` 规则之后（Task 3 Step 3 修改过的 body 块之后），插入：

```css
/* Native form controls: explicit colors so they don't fall back to
   browser-default white-on-black in night mode. */
input, textarea, select {
    background-color: var(--surface-card);
    color: var(--text-primary);
    border: 1px solid var(--border-subtle);
    border-radius: var(--radius-md);
    padding: 8px 12px;
    font: inherit;
}

input::placeholder, textarea::placeholder {
    color: var(--text-muted);
}

input:focus, textarea:focus, select:focus {
    outline: none;
    border-color: var(--accent);
    box-shadow: 0 0 0 3px var(--accent-soft);
}
```

- [ ] **Step 2: 重写主按钮**

搜索 `.btn-primary` 与基础 `.btn` 规则。如果存在 `.btn-primary` 规则带 `--primary-gradient`，整体替换为：

```css
.btn {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    padding: 8px 14px;
    font-size: 13px;
    font-weight: 500;
    color: var(--text-primary);
    background: var(--surface-card);
    border: 1px solid var(--border-subtle);
    border-radius: var(--radius-md);
    cursor: pointer;
    transition: var(--transition-quick);
}

.btn:hover {
    background: var(--surface-hover);
}

.btn:focus-visible {
    outline: none;
    box-shadow: 0 0 0 3px var(--accent-soft);
}

.btn-primary {
    background: var(--accent);
    color: var(--text-on-accent);
    border-color: transparent;
    box-shadow: var(--shadow-sm);
}

.btn-primary:hover {
    background: var(--accent-hover);
    color: var(--text-on-accent);
    box-shadow: var(--shadow-md);
}

.btn-primary:focus-visible {
    box-shadow: 0 0 0 3px var(--accent-soft);
}
```

注意：如果 `.btn` 原本就有大量子规则（如 `.btn-search`、`.btn-transcode`、`.btn-close`），保留这些子规则，只动 `.btn` 和 `.btn-primary`。其他子类在 Task 8 检查是否需要微调。

- [ ] **Step 3: 重写卡片（stat/widget/settings）**

搜索并替换 `.stat-card`、`.widget-card`、`.settings-card`。如果当前是 `background: var(--bg-card)` + 半透明，统一改为：

```css
.stat-card,
.widget-card,
.settings-card {
    background: var(--surface-card);
    border: 1px solid var(--border-subtle);
    border-radius: var(--radius-lg);
    padding: var(--space-5);
    box-shadow: var(--shadow-sm);
}
```

如果原 CSS 中 `.stat-card` 还有 `display: flex; gap: 12px` 等布局属性，**保留**布局，只替换背景/边框/阴影/圆角。

- [ ] **Step 4: 重写浏览器工具栏与搜索框（沿用 form 覆盖风格）**

找到 `.browser-toolbar`、`.breadcrumbs`、`.search-box`、`.search-box input`、`.btn-search`。把搜索框内 input 的硬编码背景（若有）移除，让其继承 Step 1 的全局 input 规则。`breadcrumbs` 颜色改为 `color: var(--text-muted)`，`.crumb.active` 改为 `color: var(--text-primary)`。

具体替换（按现有结构）：

```css
.browser-toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: var(--space-5);
    gap: var(--space-4);
}

.breadcrumbs {
    color: var(--text-muted);
    font-size: 14px;
}

.breadcrumbs .crumb.active {
    color: var(--text-primary);
    font-weight: 500;
}

.search-box {
    display: flex;
    align-items: center;
    gap: var(--space-2);
}

.search-box input {
    width: 240px;
    padding: 6px 10px;
    font-size: 13px;
}

.btn-search {
    background: var(--surface-card);
    color: var(--text-secondary);
    border: 1px solid var(--border-subtle);
    padding: 6px 10px;
    border-radius: var(--radius-md);
    cursor: pointer;
}

.btn-search:hover {
    background: var(--surface-hover);
    color: var(--accent);
}
```

- [ ] **Step 5: 浏览器手动验证**

启动 server，逐项验收：

- DAY：搜索框是白底深字；NIGHT：搜索框是 `#232328` 深底浅字（不再黑底白字）
- 设置页 textarea 在 NIGHT 下背景跟随 surface-card，文字是 `--text-primary`
- 主按钮（立即扫描媒体）是 terracotta `#C75B39`，hover 变深
- 卡片有 `--shadow-sm`，边框 `--border-subtle`
- focus 搜索框时出现 terracotta 软色光晕

- [ ] **Step 6: Commit**

```bash
git add server/internal/web/style.css
git commit -m "$(cat <<'EOF'
feat(web): terracotta buttons, unified cards, form-control theming

Replace purple-gradient primary button with terracotta + shadow.
Unify stat/widget/settings cards onto surface-card + shadow-sm +
radius-lg. Add global input/textarea/select rules so native controls
inherit theme correctly (no white-on-black in night mode).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: 顶栏主题切换按钮

**Files:**
- Modify: `server/internal/web/index.html`（顶栏 `.header-actions` 增 button）
- Modify: `server/internal/web/app.js`（导入 + click handler + 事件监听 + icon 切换）
- Modify: `server/internal/web/style.css`（新增 `.btn-theme-toggle` 规则，建议放在 `.btn-primary` 规则之后）

**Interfaces:**
- Consumes: Task 1 的 `getChromeTheme/saveChromeTheme` + `chrome-theme-changed` 事件
- Produces: 顶栏主题切换按钮，icon 随当前主题更新（DAY 显示 ☀️ / NIGHT 显示 🌙，使用 inline SVG）

- [ ] **Step 1: 在 `index.html` 顶栏加按钮**

找到 `index.html` 第 50-54 行的 `<div class="header-actions">`：

```html
<div class="header-actions">
    <button class="btn btn-primary btn-rescan" id="btn-trigger-scan">
        <span>🔄</span> 立即扫描媒体
    </button>
</div>
```

替换为：

```html
<div class="header-actions">
    <button class="btn btn-theme-toggle" id="btn-theme-toggle" aria-label="切换主题" title="切换日/夜主题">
        <span class="theme-toggle-icon" data-icon="sun">
            <!-- sun icon (DAY visible) -->
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <circle cx="12" cy="12" r="4"></circle>
                <path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"></path>
            </svg>
        </span>
        <span class="theme-toggle-icon" data-icon="moon" hidden>
            <!-- moon icon (NIGHT visible) -->
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path>
            </svg>
        </span>
    </button>
    <button class="btn btn-primary btn-rescan" id="btn-trigger-scan">
        <span>🔄</span> 立即扫描媒体
    </button>
</div>
```

- [ ] **Step 2: 新增 `.btn-theme-toggle` CSS**

在 `style.css` 的 `.btn-primary:focus-visible` 规则之后插入：

```css
.btn-theme-toggle {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 36px;
    height: 36px;
    padding: 0;
    background: var(--surface-card);
    color: var(--text-secondary);
    border: 1px solid var(--border-subtle);
    border-radius: var(--radius-md);
    cursor: pointer;
    transition: var(--transition-quick);
}

.btn-theme-toggle:hover {
    color: var(--accent);
    background: var(--surface-hover);
}

.btn-theme-toggle:focus-visible {
    outline: none;
    box-shadow: 0 0 0 3px var(--accent-soft);
}
```

- [ ] **Step 3: 修改 `app.js` 引入 chrome theme API 并绑定**

打开 `server/internal/web/app.js`。在文件顶部 import 区（第 1-20 行附近）找一处合适位置加：

```javascript
import { getChromeTheme, saveChromeTheme } from './readerPrefs.js';
```

然后在 `setupEventListeners()` 函数体内（第 74 行起）追加：

```javascript
    // Theme toggle (Task 6)
    const themeToggle = document.getElementById('btn-theme-toggle');
    if (themeToggle) {
        updateThemeToggleIcon(getChromeTheme());
        themeToggle.addEventListener('click', () => {
            const next = getChromeTheme() === 'day' ? 'night' : 'day';
            saveChromeTheme(next);
        });
        window.addEventListener('chrome-theme-changed', (e) => {
            document.documentElement.dataset.theme = e.detail.theme;
            updateThemeToggleIcon(e.detail.theme);
        });
    }
```

并在 `app.js` 末尾追加一个独立 helper（注意：因为 `setupEventListeners` 内引用了它，必须放在模块顶层）：

```javascript
function updateThemeToggleIcon(theme) {
    document.querySelectorAll('.theme-toggle-icon').forEach(el => {
        el.hidden = (el.dataset.icon !== (theme === 'night' ? 'moon' : 'sun'));
    });
}
```

- [ ] **Step 4: 浏览器手动验证**

启动 server，浏览器访问：

- 顶栏右侧出现主题切换按钮（DAY 时显示太阳图标）
- 点击按钮 → `<html data-theme>` 立即切换 → 整个 chrome 配色瞬切
- 按钮图标也立即更新（DAY 太阳 / NIGHT 月亮）
- 刷新页面后保持选择的主题（无 FOUC）
- DevTools Console 执行 `localStorage.getItem('chrome_theme')` 应能看到 `'day'` 或 `'night'`

- [ ] **Step 5: Commit**

```bash
git add server/internal/web/index.html server/internal/web/app.js server/internal/web/style.css
git commit -m "$(cat <<'EOF'
feat(web): add theme toggle button in header

Inline-SVG icon button in .header-actions toggles between day/night
by calling saveChromeTheme; app.js listens for 'chrome-theme-changed'
to update <html data-theme> and swap sun/moon icon without FOUC.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: 侧栏 menu-icon emoji 统一为 inline SVG

**Files:**
- Modify: `server/internal/web/index.html`（侧栏 4 个 `.menu-item` 的 icon）
- 不动 CSS（Task 4 已定义 `.menu-icon` 为 20×20 容器）

**Interfaces:**
- Consumes: Task 4 的 `.menu-icon` 容器（20×20 inline-flex）
- Produces: 4 个侧栏菜单项图标统一为 terracotta stroke SVG（dashboard/folder/bookmark/settings）

- [ ] **Step 1: 替换侧栏菜单图标**

打开 `index.html`，找到第 19-30 行的 4 个 `.menu-item`，把每个 `<span class="menu-icon">📊</span>` 等 emoji 替换为 inline SVG。下面给出 4 个图标的完整替换块：

```html
<nav class="sidebar-menu">
    <a href="#/dashboard" class="menu-item active" id="menu-dashboard">
        <span class="menu-icon">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <rect x="3" y="3" width="7" height="9"></rect>
                <rect x="14" y="3" width="7" height="5"></rect>
                <rect x="14" y="12" width="7" height="9"></rect>
                <rect x="3" y="16" width="7" height="5"></rect>
            </svg>
        </span>
        仪表盘
    </a>
    <a href="#/browser" class="menu-item" id="menu-browser">
        <span class="menu-icon">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path>
            </svg>
        </span>
        媒体共享库
    </a>
    <a href="#/bookmarks" class="menu-item" id="menu-bookmarks">
        <span class="menu-icon">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"></path>
            </svg>
        </span>
        书签管理
    </a>
    <a href="#/settings" class="menu-item" id="menu-settings">
        <span class="menu-icon">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <circle cx="12" cy="12" r="3"></circle>
                <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
            </svg>
        </span>
        系统设置
    </a>
</nav>
```

- [ ] **Step 2: 浏览器手动验证**

- 侧栏 4 个菜单项图标风格统一（细线 stroke），颜色继承 `color`（未选中 `--text-secondary`，选中 `--accent`）
- DAY/NIGHT 切换时图标颜色正确跟随

- [ ] **Step 3: Commit**

```bash
git add server/internal/web/index.html
git commit -m "$(cat <<'EOF'
feat(web): replace sidebar emoji icons with inline SVG

Swap dashboard/folder/bookmark/settings emoji for 18px stroke SVG
icons that inherit currentColor, so they pick up terracotta in
active state and text-secondary otherwise.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: modal/overlay 颜色适配 + 最终回归

**Files:**
- Modify: `server/internal/web/style.css`（搜索 `--bg-main`、`--primary-gradient`、`--glass-blur`、`--shadow-premium` 等旧变量残留，替换为语义变量；检查 `.overlay-modal`、`.modal-backdrop`、`.modal-wrapper`、`.modal-header`、`.toast-*`、`.lightbox-*` 等）

**Interfaces:**
- Consumes: Task 3-7 全部产出
- Produces: 模态/吐司/lightbox 跟随主题色；旧语义别名是否可清理的判断

- [ ] **Step 1: 搜索残留旧变量**

```bash
cd server/internal/web && grep -nE 'var\(--(bg-main|bg-card|bg-sidebar|primary-gradient|glass-blur|shadow-premium|border-radius-(sm|md|lg))\)' style.css
```

记录每一处 file:line。这些都需要替换为语义变量。映射表：

| 旧变量 | 新变量 |
|---|---|
| `--bg-main` | `--surface-app` |
| `--bg-card` | `--surface-card` |
| `--bg-sidebar` | `--surface-sidebar` |
| `--primary-gradient` | `--accent`（删除 gradient，改纯色背景；如果是 `.brand-gradient` 之类文本渐变，直接改为 `color: var(--accent)` 不再 `-webkit-background-clip: text`） |
| `--glass-blur` | 删除 `backdrop-filter`（spec §6.2 明确删除） |
| `--shadow-premium` | `--shadow-md` |
| `--border-radius-sm/md/lg` | `--radius-sm/md/lg` |

- [ ] **Step 2: 逐个替换**

对 Step 1 grep 输出的每一行，按映射表替换。常见情形：

- `.modal-backdrop`：把 `background: rgba(0, 0, 0, 0.7)` 改为 `background: rgba(0, 0, 0, 0.5)`（DAY）/保持（NIGHT 自动适配，因为基于 alpha）。如果模态有 `backdrop-filter: var(--glass-blur)`，删除该行。
- `.modal-wrapper` / `.modal-content` / `.modal-header`：`background: var(--surface-card)`，`border: 1px solid var(--border-subtle)`，`box-shadow: var(--shadow-md)`。
- `.toast`：`background: var(--surface-card)`，`color: var(--text-primary)`，`border-left: 3px solid var(--accent)`。
- `.lightbox-*`：`background: var(--surface-card)` 用于 caption，文字色 `--text-primary`。

- [ ] **Step 3: 检查 modal-header 上残留的 inline style**

`index.html:206` 有 `style="background-color: var(--error); ..."`，这处保留 `var(--error)`（语义别名仍定义）。无需改。

- [ ] **Step 4: 浏览器全量手动验收（按 spec §8 清单）**

启动 server，逐项确认：

- [ ] DAY / NIGHT 切换瞬时无闪烁
- [ ] 首次加载/刷新无 FOUC（关闭 Network 面板"Disable cache"前后各刷一次观察）
- [ ] dashboard / browser / bookmarks / settings 四页颜色与控件风格一致
- [ ] 顶栏主题切换按钮、立即扫描按钮、汉堡按钮交互正常
- [ ] 搜索框、设置页 textarea 在 DAY/NIGHT 下均可读
- [ ] 视频模态、图片 lightbox、auth modal 外层跟随主题（背景 surface-card、阴影 shadow-md、无 glass blur）
- [ ] toast 通知（手动 trigger：DevTools 调用 `fetch('/api/v1/scan', {method:'POST'})` 触发吐司）背景与文字对比正常
- [ ] 响应式：DevTools 切到 768px / 1024px / 800px，drawer 行为正常
- [ ] 回归：阅读器内主题（`#/read?path=...`）仍是原 6 套预设，不受 chrome 主题影响

DevTools 检查对比度（Elements → Computed → Contrast）：

- `--text-primary` on `--surface-app` ≥ 4.5:1
- `--text-muted` on `--surface-sidebar` ≥ 4.5:1

- [ ] **Step 5: 清理 task 中已不再使用的语义别名（可选，仅在确认无引用时）**

```bash
cd server/internal/web && grep -nE 'var\(--(bg-main|bg-card|bg-sidebar|primary|text-main|text-white|border-color|secondary|error)\)' style.css | head -30
```

如果某别名已无引用，可在 Task 3 Step 2 的 `:root` 块中删除对应别名声明。**保守策略**：保留所有别名，等下一轮迭代再清理，避免本次回归风险。

- [ ] **Step 6: Commit**

```bash
git add server/internal/web/style.css
git commit -m "$(cat <<'EOF'
feat(web): adapt modals/toasts/lightbox to new theme tokens

Replace remaining --bg-main/--primary-gradient/--glass-blur/
--shadow-premium/--border-radius-* references with semantic surface/
accent/radius/shadow variables. Drop backdrop-filter glass from
modals. Run through full DAY/NIGHT acceptance checklist from
spec §8.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## 任务依赖图

```
T1 (theme API + FOUC)
   ├── T2 (Open Props + tokens.css)  ← T3 依赖 tokens.css
   │        ↓
   └── T3 (token :root + DAY/NIGHT)
            ↓
            T4 (chrome 骨架: sidebar/header/menu/status)
            ↓
            T5 (button/card/form 控件)
            ↓
            T6 (主题切换按钮，需要 T1 API + T3 token + T5 button CSS)
            ↓
            T7 (menu-icon SVG，独立但视觉上依赖 T4)
            ↓
            T8 (modal 适配 + 最终回归)
```

T1、T2 可并行（无依赖）；T3 之后串行。T7 在 T4 之后任意时间可做。
