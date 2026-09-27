# Ink Editorial 重设计（Slate Ink & Paper）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把已确认的 Ink Editorial 视觉语言（serif 展示 × 墨黑主操作 × 苔绿进度 × 幽灵阅读器 chrome）落进 web + Android 双端真实代码，不改任何交互逻辑。

**Architecture:** 纯 token/样式层改造——web 端重调 `themes.css` 等 CSS 变量与组件规则（CSP 禁外链字体，display serif 用已自托管 Noto Serif SC）；Android 端重调 Material3 ColorScheme 与 `ReaderTheme` hex（与 web 逐字对称）。阅读器 chrome 改为半透明 + backdrop-blur + hairline。

**Tech Stack:** vanilla CSS（无构建）/ Jetpack Compose Material3 / node:test / JUnit+Robolectric

**Spec:** `docs/superpowers/specs/2026-09-27-ink-editorial-redesign-design.md`（§3 token 表为唯一色值真值）

## Global Constraints

- CSP `default-src 'self'`：禁止任何外部字体/资源；display serif = 自托管 `"Noto Serif SC"`（web）/ `FontFamily.Serif`（Android）
- 零 inline style；零 class 重命名（现有测试断言 `.text-reader__*` 等 class 名，只增不改不删）
- 双端 reader 主题 hex 逐字对称（web `THEME_PRESETS` ↔ Android `ReaderTheme`）
- 中文文案不变；不改交互逻辑、设置项集合、持久化结构
- 每任务结束跑对应子系统测试；commit 遵循 Conventional Commits + `(Phase N)` 后缀

---

### Task 1: Web chrome tokens + editorial sidebar

**Files:**
- Modify: `server/internal/web/css/themes.css`（7 预设重调 + --action-ink 族）
- Modify: `server/internal/web/css/layout.css`（侧栏 220px / serif 品牌区 / 菜单静音化）
- Modify: `server/internal/web/css/components.css`（.btn-primary → --action-ink）
- Modify: `server/internal/web/index.html`（sidebar-brand 结构 + 新增「书架」菜单项）
- Modify: `server/internal/web/router.js`（bookshelf 路由激活 menu-bookshelf）

**Interfaces:**
- Produces: `--action-ink` / `--action-ink-hover` CSS 变量（后续任务全部主操作控件消费）

- [x] **Step 1: themes.css 重调 7 预设**

`themes.css` 顶部注释改为 `/* ── Slate Ink & Paper chrome palettes — spec 2026-09-27 §3.1 ── */`。整块替换内容：

```css
:root,
[data-theme="day"] {
    color-scheme: light;
    --surface-app:     #F7F7F5;
    --surface-card:    #FFFFFF;
    --surface-sidebar: #F1F1EE;
    --surface-hover:   rgba(15, 23, 42, 0.06);
    --text-primary:    #0F172A;
    --text-secondary:  #52575E;
    --text-muted:      #8A8F98;
    --text-on-accent:  #FFFFFF;
    --accent:          #3D6B4F;
    --accent-hover:    #2F5640;
    --accent-soft:     rgba(61, 107, 79, 0.10);
    --accent-text:     #2F5640;
    --action-ink:      #0A0A0A;
    --action-ink-hover:#26282E;
    --border-soft:     #EFEDE8;
    --border-subtle:   #E5E4DF;
    --shadow-sm:       0 1px 2px rgba(15, 23, 42, 0.04);
    --shadow-md:       0 4px 12px rgba(15, 23, 42, 0.06), 0 2px 4px rgba(15, 23, 42, 0.04);
    --radius-sm: 6px;  --radius-md: 10px; --radius-lg: 14px;
    --space-1: 4px; --space-2: 8px; --space-3: 12px; --space-4: 16px;
    --space-5: 24px; --space-6: 32px;
    --error: #DC2626;  --secondary: #3D6B4F;
    --font-display: "Noto Serif SC", "Songti SC", serif;
    --font-sans: system-ui, -apple-system, 'Segoe UI', Roboto, 'PingFang SC', 'Microsoft YaHei', sans-serif, "Apple Color Emoji", "Segoe UI Emoji", "Segoe UI Symbol", "Noto Color Emoji";
}

[data-theme="day_bright"] {
    color-scheme: light;
    --surface-app: #FFFFFF; --surface-card: #FFFFFF; --surface-sidebar: #F7F7F4;
    --surface-hover: rgba(15, 23, 42, 0.05);
    --text-primary: #0F172A; --text-secondary: #52575E; --text-muted: #8A8F98;
    --text-on-accent: #FFFFFF;
    --accent: #3D6B4F; --accent-hover: #2F5640;
    --accent-soft: rgba(61, 107, 79, 0.10); --accent-text: #2F5640;
    --action-ink: #0A0A0A; --action-ink-hover: #26282E;
    --border-soft: #F0F0EC; --border-subtle: #E7E7E2;
    --error: #DC2626; --secondary: #3D6B4F;
}

[data-theme="eye_care"] {
    color-scheme: light;
    --surface-app: #F5F1E6; --surface-card: #FBF8F0; --surface-sidebar: #EFE9DA;
    --surface-hover: rgba(74, 64, 52, 0.08);
    --text-primary: #3B3428; --text-secondary: #5E5442; --text-muted: #8F8470;
    --text-on-accent: #FFFFFF;
    --accent: #4A6B52; --accent-hover: #3C5843;
    --accent-soft: rgba(74, 107, 82, 0.12); --accent-text: #3C5843;
    --action-ink: #26211A; --action-ink-hover: #3B3428;
    --border-soft: #EAE3D2; --border-subtle: #E2D9C4;
    --error: #C0392B; --secondary: #4A6B52;
}

[data-theme="eye_care_green"] {
    color-scheme: light;
    --surface-app: #DDE6DA; --surface-card: #EDF2EA; --surface-sidebar: #D2DECF;
    --surface-hover: rgba(30, 42, 32, 0.08);
    --text-primary: #1E2A20; --text-secondary: #3E4C40; --text-muted: #66756A;
    --text-on-accent: #FFFFFF;
    --accent: #33593C; --accent-hover: #2A4831;
    --accent-soft: rgba(51, 89, 60, 0.12); --accent-text: #2A4831;
    --action-ink: #17211A; --action-ink-hover: #242E26;
    --border-soft: #D2DECF; --border-subtle: #C6D4C2;
    --error: #C0392B; --secondary: #33593C;
}

[data-theme="parchment"] {
    color-scheme: light;
    --surface-app: #EFE8D5; --surface-card: #F7F1E2; --surface-sidebar: #E7DEC7;
    --surface-hover: rgba(61, 51, 39, 0.08);
    --text-primary: #3D3327; --text-secondary: #5C5040; --text-muted: #8C7E66;
    --text-on-accent: #FFFFFF;
    --accent: #4E6B44; --accent-hover: #3F5837;
    --accent-soft: rgba(78, 107, 68, 0.12); --accent-text: #3F5837;
    --action-ink: #241D14; --action-ink-hover: #38301F;
    --border-soft: #E4DAC3; --border-subtle: #DCD0B5;
    --error: #C0392B; --secondary: #4E6B44;
}

[data-theme="night"] {
    color-scheme: dark;
    --surface-app: #0C0D10; --surface-card: #15161A; --surface-sidebar: #0F1013;
    --surface-hover: rgba(143, 191, 159, 0.10);
    --text-primary: #E7E9EE; --text-secondary: #9BA1AC; --text-muted: #74787F;
    --text-on-accent: #0F172A;
    --accent: #8FBF9F; --accent-hover: #A3CDAF;
    --accent-soft: rgba(143, 191, 159, 0.14); --accent-text: #A3CDAF;
    --action-ink: #E7E9EE; --action-ink-hover: #FFFFFF;
    --border-soft: #1F2128; --border-subtle: #22242B;
    --shadow-sm: 0 1px 2px rgba(0, 0, 0, 0.5);
    --shadow-md: 0 4px 12px rgba(0, 0, 0, 0.55), 0 2px 4px rgba(0, 0, 0, 0.45);
    --error: #F87171; --secondary: #8FBF9F;
}

[data-theme="night_black"] {
    color-scheme: dark;
    --surface-app: #000000; --surface-card: #101114; --surface-sidebar: #0A0B0D;
    --surface-hover: rgba(143, 191, 159, 0.12);
    --text-primary: #E7E9EE; --text-secondary: #9BA1AC; --text-muted: #74787F;
    --text-on-accent: #0F172A;
    --accent: #8FBF9F; --accent-hover: #A3CDAF;
    --accent-soft: rgba(143, 191, 159, 0.16); --accent-text: #A3CDAF;
    --action-ink: #E7E9EE; --action-ink-hover: #FFFFFF;
    --border-soft: #1C1E24; --border-subtle: #202228;
    --shadow-sm: 0 1px 2px rgba(0, 0, 0, 0.6);
    --shadow-md: 0 4px 12px rgba(0, 0, 0, 0.7), 0 2px 4px rgba(0, 0, 0, 0.5);
    --error: #F87171; --secondary: #8FBF9F;
}
```

（保留文件底部 `body[data-reader-theme]` 覆盖块不动。）

- [x] **Step 2: layout.css 侧栏改版**

- `.app-container` grid 列 `260px` → `220px`
- `.sidebar-brand`：去 `border-bottom`，改 `padding: var(--space-2) var(--space-3) var(--space-5)`；内部结构对应新 markup
- 删除 `.brand-monogram` / `.brand-name` / `.brand-version` 三条规则，新增：

```css
.brand-wordmark {
    font-family: var(--font-display);
    font-size: 21px;
    font-weight: 400;
    line-height: 1;
    letter-spacing: -0.01em;
    color: var(--text-primary);
}
.brand-subtitle {
    font-size: 11px;
    font-weight: 500;
    color: var(--text-muted);
    margin-top: 6px;
}
```

- `.menu-item`：`color: var(--text-secondary)`（不变），去 hover 背景改 `hover:bg`→ `color: var(--text-primary)`（保留 hover 无底色：`background-color: transparent`）；`.menu-item.active`：去 `background-color: var(--accent-soft)` 与 `color: var(--accent-text)`，改 `color: var(--text-primary); font-weight: 600; background-color: transparent;`
- `.menu-item.active::before`：`background: var(--accent)` → `background: var(--action-ink)`；`width: 3px` → `width: 2px`；`border-radius: 0 2px 2px 0` → `border-radius: 0 1px 1px 0`
- `.server-status`：`background: var(--surface-card)` → `background: transparent`；去 border（`border: none`）
- `.main-header h1`：加 `font-family: var(--font-display); font-weight: 400; font-size: 20px;`

- [x] **Step 3: components.css 主按钮换 ink**

```css
.btn-primary {
    background: var(--action-ink); border-color: var(--action-ink);
    color: var(--text-on-accent);
    border-radius: 9999px;
    padding: 8px 16px;
}
.btn-primary:hover { background: var(--action-ink-hover); border-color: var(--action-ink-hover); transform: scale(1.02); }
```

（`.btn` 基类 `border-radius: var(--radius-sm)` 不动，仅 primary 覆盖为胶囊。）

- [x] **Step 4: index.html sidebar markup**

`.sidebar-brand` 内部替换为：

```html
<div class="sidebar-brand">
    <span class="brand-wordmark">LocalMediaHub</span>
    <span class="brand-subtitle">局域网媒体中枢 · v0.2.0</span>
</div>
```

「媒体共享库」菜单项后新增（复用 bookmark 之前的 book SVG path，同 index.html 内已有书形图标）：

```html
<a href="#/bookshelf" class="menu-item" id="menu-bookshelf">
    <span class="menu-icon">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"></path>
            <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"></path>
        </svg>
    </span>
    书架
</a>
```

- [x] **Step 5: router.js 激活书架菜单**

- 顶部 de-activate 数组加 `elements.menuBookshelf`（需同步在 app.js 的 elements 绑定加 `menuBookshelf: document.getElementById('menu-bookshelf')`——先 grep app.js 的 elements 构造位置再插入同款行）
- `#/bookshelf` 分支加：`if (elements.menuBookshelf) elements.menuBookshelf.classList.add('active');`

- [x] **Step 6: 测试 + xsscheck + commit**

```bash
cd server/internal/web && node --test
cd ../../tools/xsscheck && go run . ../../server/internal/web
git add -A && git commit -m "feat(web): slate-ink chrome tokens and editorial sidebar (Ink Editorial Phase 1)"
```

---

### Task 2: Reader 幽灵 chrome + 主题重调（web）

**Files:**
- Modify: `server/internal/web/readerPrefs.js`（THEME_PRESETS + 默认值）
- Modify: `server/internal/web/textReader-theme.test.mjs`（DAY 回退 hex 断言）
- Modify: `server/internal/web/css/views/reader.css`（幽灵 chrome / 胶囊 / scrubber / 章题 / TOC / 设置 swatch）
- Modify: `server/internal/web/textReader.js`（仅 skeleton 里 next 按钮加修饰类）

**Interfaces:**
- Consumes: Task 1 的 `--action-ink` 族
- Produces: 新 reader 主题 hex（Task 4 Android 对称消费）

- [x] **Step 1: 先改测试（TDD）**

`textReader-theme.test.mjs` 第二个测试断言改为：

```js
assert.equal(root.style.getPropertyValue('--reader-bg'), '#F6F4EE'); // DAY.bg
assert.equal(root.style.getPropertyValue('--reader-fg'), '#26282E'); // DAY.fg
```

- [x] **Step 2: 跑测试确认失败**

```bash
cd server/internal/web && node --test textReader-theme.test.mjs
```
Expected: FAIL（DAY.bg 仍为 #FAF8F3）

- [x] **Step 3: readerPrefs.js THEME_PRESETS + 默认值**

```js
export const THEME_PRESETS = {
    DAY:            { bg: '#F6F4EE', fg: '#26282E', chromeBg: '#EDEBE3', chromeFg: '#3A3C44', muted: '#83858C', border: '#E0DDD2' },
    DAY_BRIGHT:     { bg: '#FFFFFF', fg: '#212121', chromeBg: '#F5F5F5', chromeFg: '#333333', muted: '#7A7A7A', border: '#E0E0E0' },
    EYE_CARE:       { bg: '#F2EAD8', fg: '#4A4034', chromeBg: '#E9DFC9', chromeFg: '#56493A', muted: '#9A8C74', border: '#D9CDB2' },
    EYE_CARE_GREEN: { bg: '#C6D2C4', fg: '#1E2A20', chromeBg: '#B9C7B6', chromeFg: '#22301F', muted: '#48584A', border: '#A3B39F' },
    PARCHMENT:      { bg: '#EFE6D2', fg: '#3D3327', chromeBg: '#E5D9BF', chromeFg: '#4D4034', muted: '#8C7E66', border: '#D3C7AB' },
    NIGHT:          { bg: '#111318', fg: '#C6CAD2', chromeBg: '#191C22', chromeFg: '#B4B9C4', muted: '#7E838D', border: '#252832' },
    NIGHT_BLACK:    { bg: '#000000', fg: '#B9BDC6', chromeBg: '#0A0B0D', chromeFg: '#A7ABB5', muted: '#74787F', border: '#1B1D24' },
    AUTO:           null,
};
```

`DEFAULT_SETTINGS`: `fontSize: 16` → `17`；`lineHeight: 1.8` → `1.9`。（其余字段与迁移函数不动。）

- [x] **Step 4: 跑测试确认通过**

```bash
cd server/internal/web && node --test
```
Expected: 全部 PASS

- [x] **Step 5: reader.css 幽灵 chrome 改版**

关键规则替换（保持既有选择器名，全部为值级修改）：

```css
/* header/footer 幽灵化 */
.text-reader__header, .text-reader__footer {
    background-color: color-mix(in srgb, var(--reader-chrome-bg) 85%, transparent);
    backdrop-filter: blur(12px);
    -webkit-backdrop-filter: blur(12px);
}

/* 返回键 + 图标键 → ghost 圆 */
.text-reader__back, .text-reader__icon-btn {
    background: transparent;
    border: 1px solid transparent;
    border-radius: 9999px;
}
.text-reader__back:hover, .text-reader__icon-btn:hover {
    border-color: var(--reader-border, var(--border-subtle));
    background: var(--surface-hover);
}

/* footer 按钮 → 胶囊 */
.text-reader__footer button {
    border-radius: 9999px;
    padding: 6px 16px;
}
.text-reader__footer button:hover:not(:disabled) {
    background-color: var(--action-ink);
    border-color: var(--action-ink);
    color: var(--text-on-accent);
}
.text-reader__next--primary {
    background-color: var(--action-ink);
    border-color: var(--action-ink);
    color: var(--text-on-accent);
}
.text-reader__next--primary:hover:not(:disabled) {
    background-color: var(--action-ink-hover);
    border-color: var(--action-ink-hover);
    color: var(--text-on-accent);
}

/* scrubber：thumb ink + 填充苔绿（thumb 规则改色，track 不变） */
.text-reader__scrubber-thumb { background-color: var(--action-ink); }

/* 顶部细进度条 → 苔绿 */
.text-reader__progress-bar { background-color: var(--accent); }

/* 章题去装饰线 */
.text-reader__chapter-title::after { display: none; }
.text-reader__chapter-title { font-weight: 400; letter-spacing: 0.02em; }

/* TOC 活动 item：去底色填充，ink 左条 */
.text-reader__drawer-item--active {
    background-color: transparent;
    color: var(--reader-fg, var(--text-primary));
    opacity: 1;
    font-weight: 600;
    box-shadow: inset 2px 0 0 0 var(--action-ink);
}
```

设置 dialog 主题 swatch 颜色同步新 hex：

```css
.reader-settings__theme-swatch[data-theme="DAY"]            { background: #F6F4EE; }
.reader-settings__theme-swatch[data-theme="DAY_BRIGHT"]     { background: #FFFFFF; }
.reader-settings__theme-swatch[data-theme="EYE_CARE"]       { background: #F2EAD8; }
.reader-settings__theme-swatch[data-theme="EYE_CARE_GREEN"] { background: #C6D2C4; }
.reader-settings__theme-swatch[data-theme="PARCHMENT"]      { background: #EFE6D2; }
.reader-settings__theme-swatch[data-theme="NIGHT"]          { background: #111318; }
.reader-settings__theme-swatch[data-theme="NIGHT_BLACK"]    { background: #000000; }
```

（AUTO/CUSTOM 渐变 swatch 保持。reader-content 首字下沉已是 serif 不动。）

- [x] **Step 6: textReader.js skeleton 微调**

skeleton 中下一章按钮：`<button class="text-reader__next" type="button">下一章</button>` → `<button class="text-reader__next text-reader__next--primary" type="button">下一章</button>`（仅加类，逻辑零改动）。

- [x] **Step 7: 全量测试 + commit**

```bash
cd server/internal/web && node --test
git add -A && git commit -m "feat(reader): ink editorial reader chrome and retuned themes (Phase 2)"
```

---

### Task 3: Dashboard / Browser / Bookshelf 重排版（web）

**Files:**
- Modify: `server/internal/web/css/views/dashboard.css`
- Modify: `server/internal/web/css/views/browser.css`
- Modify: `server/internal/web/css/views/bookshelf.css`

**Interfaces:**
- Consumes: Task 1 tokens（--action-ink/--font-display/hairline）

- [x] **Step 1: dashboard.css**

- `.stats-grid`：去卡片化——`grid-template-columns: repeat(3, 1fr)` 保留，加 `border-top: 1px solid var(--border-subtle)` 顶部分隔可省；`.stat-card` 去边框/阴影/背景（`background: transparent; border: none; box-shadow: none; padding: 0`），相邻列间 `border-left: 1px solid var(--border-soft)` + 左 padding
- `.stat-card__value`：`font-family: var(--font-display); font-size: 40px; font-weight: 400; line-height: 1;`
- `.stat-card__icon`：改 ghost 圆（`background: transparent; border: 1px solid var(--border-subtle); border-radius: 9999px;`）各 `--text/--video/--image` tint 换为 muted 描边色
- `.widget-card`：`border-radius: 12px`；`h2` 不变
- 新增继续阅读条样式（`.dashboard-bookshelf` 已有容器，补充 `.book-continue-card` 行内布局：书封块 `font-family: var(--font-display)` + 2px 苔绿进度线 + 11px muted 元信息，卡片 hairline hover ink）——若 bookshelf.js 现有 class 已固定，则按其 DOM 覆写样式（先读 bookshelf.js 渲染的 class 名再写规则，勿改 JS）

- [x] **Step 2: browser.css**

- `.filter-chip`：`border-radius: 9999px; padding: 6px 14px;`
- `.filter-chip[data-active="true"], .filter-chip.active`（按现有选择器）：active = `background: var(--action-ink); color: var(--text-on-accent); border-color: var(--action-ink)`（替换原 accent-soft 填充）
- `.media-card`（含 folder/video 卡）：`border-radius: 12px;`，`box-shadow` 降为 none/`--shadow-sm`，hover `border-color: var(--action-ink)`（去 accent hover）
- 文本卡书名区：加 `font-family: var(--font-display)`（对 text 卡标题 class；先读 browserView.js 实际 class）
- 阅读状态 chip：`background: var(--accent-soft); color: var(--accent-text);`（已是 accent 语义，自动换苔绿，确认无硬编码旧 hex）

- [x] **Step 3: bookshelf.css**

- 书卡：hairline 12px、hover ink；书名 `font-family: var(--font-display)`
- 进度线：`height: 2px; background: var(--accent);`（细线）
- 状态 chip：在读 = `var(--accent-soft)/var(--accent-text)`；已读完 = hairline；未读 = muted（按 bookshelf.js 现有 class 覆写）

- [x] **Step 4: 测试 + commit**

```bash
cd server/internal/web && node --test
cd ../../tools/xsscheck && go run . ../../server/internal/web
git add -A && git commit -m "feat(web): dashboard browser bookshelf ink editorial restyle (Phase 3)"
```

---

### Task 4: Android 主题 token 重调 + 阅读主题对称

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/theme/Theme.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/theme/ColorTokens.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/ReaderSettings.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/ui/theme/ThemeColorSchemeTest.kt`

**Interfaces:**
- Consumes: Task 2 的 reader hex（逐字对称）
- Produces: 新 Material3 scheme（Task 5 消费）

- [x] **Step 1: 先改 ThemeColorSchemeTest 断言（TDD）**

```kotlin
@Test
fun day_theme_uses_ink_primary_and_paper_bg() {
    val s = schemeFor("DAY")
    assertEquals(Color(0xFF26282E), s.primary)
    assertEquals(Color(0xFF3D6B4F), s.secondary)
    assertEquals(Color(0xFFF7F7F5), s.background)
    assertEquals(Color(0xFFFFFFFF), s.surface)
    assertEquals(Color(0xFFE9E9E4), s.primaryContainer)
    assertEquals(Color(0xFFE4EDE6), s.secondaryContainer)
}
@Test
fun day_theme_provides_slate_outline_soft() {
    // …assertEquals(Color(0xFFE5E4DF), captured.single())
}
@Test
fun night_theme_uses_paper_ink_primary_and_deep_slate_bg() {
    val s = schemeFor("NIGHT")
    assertEquals(Color(0xFFE7E9EE), s.primary)
    assertEquals(Color(0xFF8FBF9F), s.secondary)
    assertEquals(Color(0xFF0C0D10), s.background)
    assertEquals(Color(0xFF15161A), s.surface)
    assertEquals(Color(0xFF26282C), s.primaryContainer)
    assertEquals(Color(0xFF1C2A20), s.secondaryContainer)
}
@Test
fun night_theme_provides_dark_outline_soft() {
    // …assertEquals(Color(0xFF22242B), captured.single())
}
@Test
fun eye_care_theme_keeps_own_primary_but_gets_outline_soft() {
    // primary → Color(0xFF4A6B52)；outlineSoft → Color(0xFFE2D9C4) 保留暖纸
}
```

- [x] **Step 2: 跑测试确认失败**

```bash
cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.ui.theme.ThemeColorSchemeTest"
```
Expected: FAIL

- [x] **Step 3: Theme.kt 六 scheme 重调**

```kotlin
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFE7E9EE), secondary = Color(0xFF8FBF9F), tertiary = Color(0xFFA3CDAF),
    background = Color(0xFF0C0D10), surface = Color(0xFF15161A), surfaceVariant = Color(0xFF1F2128),
    primaryContainer = Color(0xFF26282C), secondaryContainer = Color(0xFF1C2A20),
    onPrimary = Color(0xFF0F172A), onSecondary = Color(0xFF0F172A), onTertiary = Color(0xFF0F172A),
    onBackground = Color(0xFFE7E9EE), onSurface = Color(0xFFE7E9EE),
    onSurfaceVariant = Color(0xFF9BA1AC), outline = Color(0xFF22242B), outlineVariant = Color(0xFF22242B),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF26282E), secondary = Color(0xFF3D6B4F), tertiary = Color(0xFF2F5640),
    background = Color(0xFFF7F7F5), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFF1F1EE),
    primaryContainer = Color(0xFFE9E9E4), secondaryContainer = Color(0xFFE4EDE6),
    onPrimary = Color.White, onSecondary = Color.White, onTertiary = Color.White,
    onBackground = Color(0xFF0F172A), onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF52575E), outline = Color(0xFFE5E4DF), outlineVariant = Color(0xFFE5E4DF),
)

// EyeCareGreen: primary #33593C, secondary #2A4831, tertiary #48584A,
//   background #DDE6DA, surface #EDF2EA, surfaceVariant #D2DECF,
//   primaryContainer #E4EDE6, outline #C6D4C2
// EyeCare:      primary #4A6B52, secondary #3C5843, tertiary #6B5E48,
//   background #F5F1E6, surface #FBF8F0, surfaceVariant #EFE9DA,
//   primaryContainer #EAE3D2, outline #E2D9C4
// Parchment:    primary #4E6B44, secondary #3F5837, tertiary #75654F,
//   background #EFE8D5, surface #F7F1E2, surfaceVariant #E7DEC7,
//   primaryContainer #E4DAC3, outline #DCD0B5
// NightBlack:   primary #E7E9EE, secondary #9BA1AC, tertiary #74787F,
//   background #000000, surface #101114, surfaceVariant #1A1C22,
//   primaryContainer #22242B, onPrimary #0F172A, onBackground #E7E9EE,
//   onSurface #E7E9EE, onSurfaceVariant #9BA1AC, outline #202228
```

- [x] **Step 4: ColorTokens.kt**

```kotlin
object OutlineSoft {
    val Light = Color(0xFFE5E4DF)
    val Dark = Color(0xFF22242B)
    val EyeCare = Color(0xFFE2D9C4)
    val EyeCareGreen = Color(0xFFC6D4C2)
    val Parchment = Color(0xFFDCD0B5)
    val NightBlack = Color(0xFF1C1E24)
}
object PrimaryText {
    val Light = Color(0xFF2F5640)   // moss 深化，AA on 纸面
    val Dark = Color(0xFFA3CDAF)
    val EyeCare = Color(0xFF3C5843)
    val EyeCareGreen = Color(0xFF2A4831)
    val Parchment = Color(0xFF3F5837)
    val NightBlack = Color(0xFFE7E9EE)
}
```

- [x] **Step 5: ReaderSettings.kt 对称**

ReaderTheme 七预设 hex 逐字替换为 Task 2 THEME_PRESETS 新值（如 `DAY(bg=Color(0xFFF6F4EE), fg=Color(0xFF26282E), chromeBg=Color(0xFFEDEBE3), chromeFg=Color(0xFF3A3C44), muted=Color(0xFF83858C), border=Color(0xFFE0DDD2))`；label 不变）；`ReaderSettings` 默认 `fontSizeSp = 17`、`lineHeightMultiplier = 1.9f`。

- [x] **Step 6: 跑 Android 测试 + commit**

```bash
cd android && ./gradlew testDebugUnitTest
git add -A && git commit -m "feat(android): slate-ink material schemes and reader theme parity (Phase 4)"
```

---

### Task 5: Android 阅读器/书架 chrome 改版

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderScrollbar.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/BookshelfScreen.kt`

**Interfaces:**
- Consumes: Task 4 scheme（MaterialTheme.colorScheme.primary 现为 ink 系）

- [x] **Step 1: TextReaderScreen**

- TopAppBar：`colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.85f))`（幽灵近似：无 elevation）；BottomAppBar 同理 `containerColor = …background.copy(alpha = 0.9f)`
- LinearProgressIndicator（顶/底两处）：`color = MaterialTheme.colorScheme.secondary`（苔绿）、`trackColor = Color.Transparent`
- 章模式底栏：`上一章` 保持 TextButton（`color = MaterialTheme.colorScheme.onSurfaceVariant`）；`下一章` 改 `Button(shape = RoundedCornerShape(50))`（ink 填充，默认 colors 即 primary=ink）
- 章节标题 `HorizontalDivider`（40dp 装饰线）：删除该 composable 调用（spec：去装饰线，留白替代）

- [x] **Step 2: ReaderScrollbar.kt**

thumb 颜色 → `MaterialTheme.colorScheme.primary`（ink）；填充段 → `secondary`（苔绿）；track → `surfaceVariant`。（先读文件确认现有 Color 引用名再替换。）

- [x] **Step 3: BookshelfScreen.kt**

- 筛选 chip：选中项 `FilterChip`/现有控件颜色 → `selectedContainerColor = MaterialTheme.colorScheme.primary, labelColor = onPrimary`（ink 胶囊）；未选中 hairline（按现有实现调 colors 参数）
- 进度条若有：`color = secondary`、高度 2dp
- 书名 Text 加 `fontFamily = FontFamily.Serif`

- [x] **Step 4: 测试 + commit**

```bash
cd android && ./gradlew testDebugUnitTest
git add -A && git commit -m "feat(android): reader and bookshelf ink editorial chrome (Phase 5)"
```

---

### Task 6: 文档同步

**Files:**
- Modify: `AGENTS.md`（Web 管理界面节：设计语言措辞 modern-neutral → Slate Ink & Paper / Ink Editorial）
- Modify: `.superdesign/design-system.md`（Part B 标注"已实施 2026-09-27"）

- [x] **Step 1: 更新 AGENTS.md 中「2026-09 现代中性风重设计」相关句子为 Ink Editorial（2026-09-27，spec 链接），并注明 reader 主题 hex 已按新 spec 重调、双端对称约定不变**
- [x] **Step 2: commit**

```bash
git add AGENTS.md .superdesign/design-system.md docs/
git commit -m "docs: sync handbook for ink editorial redesign"
```

---

## Self-Review 结论

- Spec 覆盖：§3.1→Task1/4；§3.2→Task2/4；§3.3→Task1/2/3/5；§4 表逐文件对应 Task1-3；§5 表逐文件对应 Task4-5；§6 提交切分=Task1-6。无缺口。
- 占位符：Task 3/5 涉及"按现有 class/引用名覆写"处已注明先读文件再写，无 TBD。
- 类型一致：`--action-ink`（CSS）与 Material `primary`（ink 语义）分工明确：CSS accent=苔绿 ↔ Compose secondary=苔绿，CSS action-ink ↔ Compose primary。
