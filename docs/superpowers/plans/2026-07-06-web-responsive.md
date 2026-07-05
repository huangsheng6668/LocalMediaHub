# Web 端响应式 CSS（Round 16）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 Web 端加 3 断点响应式布局（桌面/平板/手机）+ hamburger 抽屉 sidebar + 模态手机全屏化，让手机/平板访问布局可用。

**Architecture:** 3 个 commit 分组：C1 hamburger 机制（HTML + CSS + JS 协同）+ C2 3 断点 @media 段 + C3 模态手机全屏化。所有改动在 `@media (max-width: ...)` 内或新加 HTML/CSS，桌面端 base styles 不动。z-index 层级：backdrop=50, sidebar=60, 低于 `.overlay-modal`=100。

**Tech Stack:** 纯静态 HTML + CSS + ES modules（无构建工具、无框架）

## Global Constraints

- HTML viewport meta 已就绪（`<meta name="viewport" content="width=device-width, initial-scale=1.0">`）
- 现有 CSS 变量：`--bg-main: #0a0a0f`、`--text-main: #f3f4f6`（`--text-primary` 不存在）
- 现有 z-index 层级（spec §3.3）：`.overlay-modal`=100 / `.modal-wrapper`=101 / `.lightbox-nav`=105 / `.lightbox-close`=110 / `.toast-container`=200
- 新增 z-index：backdrop=50（低于所有模态）、sidebar=60（高于 backdrop、低于模态）
- `.app-container` 是 CSS Grid `260px 1fr`（spec §1 + 代码审计）
- `.main-header` 固定 `height: 72px` — 手机断点必须改为 `height: auto`
- 选择器名：`.browser-grid`（不是 `.media-grid`）、`.breadcrumbs`（不是 `.breadcrumb`）
- Hamburger button 必须有 `aria-expanded` 属性（a11y）
- Backdrop 用 `hidden` HTML 属性默认隐藏
- Sidebar 抽屉化用 `transform: translateX(-100%)`（保留过渡动画）
- 路由变化（hashchange）后自动关闭移动端 sidebar（桌面端 sidebar 不带 `.open` class，不受影响）
- 桌面端（≥1024px）base styles 完全不动 — 所有改动在 @media 内
- 3 commit 顺序：C1 → C2 → C3
- 每个 commit 后：`cd server && go build ./...` 验证 web 嵌入资源未破坏（无 npm test 等价物）

---

### Task 1 (Commit C1): Hamburger + 抽屉 sidebar 机制

**Files:**
- Modify: `server/internal/web/index.html`
- Modify: `server/internal/web/style.css`
- Modify: `server/internal/web/dom.js`
- Modify: `server/internal/web/app.js`

**Interfaces:**
- Consumes: 现有 `elements` 对象（dom.js）
- Produces: `elements.hamburgerBtn`、`elements.sidebarBackdrop`、`elements.sidebar` 引用；`.sidebar.open` class toggle 行为

- [ ] **Step 1: Add hamburger button + backdrop to `index.html`**

Open `server/internal/web/index.html`. Find the `<header class="main-header">` opening tag. Inside it, BEFORE the `<h1 id="page-title">`, add:

```html
                    <button class="hamburger-btn" id="btn-hamburger" aria-label="切换菜单" aria-expanded="false">
                        <span></span><span></span><span></span>
                    </button>
```

Then find the closing `</aside>` tag of the sidebar (immediately before `<main class="main-content">`). Between `</aside>` and `<main class="main-content">`, add:

```html
        <div class="sidebar-backdrop" id="sidebar-backdrop" hidden></div>
```

The exact whitespace/indentation should match surrounding lines. Read the file first to confirm existing indentation depth.

- [ ] **Step 2: Append hamburger + backdrop CSS to `style.css`**

Open `server/internal/web/style.css`. Append at the END of file:

```css

/* ─── Round 16 C1: Hamburger + Sidebar Drawer ─── */

.hamburger-btn {
    display: none; /* shown via media query in C2 */
    background: transparent;
    border: 0;
    cursor: pointer;
    padding: 8px;
    flex-direction: column;
    gap: 4px;
    color: var(--text-main); /* #f3f4f6 — visible on dark --bg-main */
}
.hamburger-btn span {
    display: block;
    width: 22px;
    height: 2px;
    background: currentColor;
    border-radius: 1px;
    transition: transform 0.2s ease, opacity 0.2s ease;
}
.hamburger-btn[aria-expanded="true"] span:nth-child(1) {
    transform: translateY(6px) rotate(45deg);
}
.hamburger-btn[aria-expanded="true"] span:nth-child(2) {
    opacity: 0;
}
.hamburger-btn[aria-expanded="true"] span:nth-child(3) {
    transform: translateY(-6px) rotate(-45deg);
}

.sidebar-backdrop {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.5);
    z-index: 50; /* 低于 .overlay-modal(100)，避免遮挡模态框 */
    cursor: pointer;
}
```

- [ ] **Step 3: Add 3 new element references to `dom.js`**

Open `server/internal/web/dom.js`. Find the `export const elements = {` opening. Inside the object, immediately after the `menuSettings: document.getElementById('menu-settings'),` line, add:

```js
    // Round 16 C1: Responsive sidebar drawer
    hamburgerBtn: document.getElementById('btn-hamburger'),
    sidebarBackdrop: document.getElementById('sidebar-backdrop'),
    sidebar: document.querySelector('.sidebar'),
```

- [ ] **Step 4: Add hamburger + backdrop listeners to `app.js::setupEventListeners`**

Open `server/internal/web/app.js`. Find the `function setupEventListeners() {` body. At the END of the function (just before the closing `}`), add:

```js
    // Round 16 C1: Hamburger toggle for tablet/mobile sidebar drawer
    if (elements.hamburgerBtn) {
        elements.hamburgerBtn.addEventListener('click', () => {
            const expanded = elements.sidebar.classList.toggle('open');
            elements.hamburgerBtn.setAttribute('aria-expanded', String(expanded));
            elements.sidebarBackdrop.hidden = !expanded;
        });
    }
    if (elements.sidebarBackdrop) {
        elements.sidebarBackdrop.addEventListener('click', () => {
            elements.sidebar.classList.remove('open');
            elements.hamburgerBtn?.setAttribute('aria-expanded', 'false');
            elements.sidebarBackdrop.hidden = true;
        });
    }
```

- [ ] **Step 5: Add route-change sidebar close to `app.js` hashchange handler**

In `server/internal/web/app.js`, find the existing `window.addEventListener('hashchange', () => { ... });` block. Replace it with:

```js
window.addEventListener('hashchange', () => {
    handleRoute(elements, renderDashboard, loadRoots, browsePath, renderTagsManager, renderSettings);
    // Round 16 C1: 移动端路由切换后关闭 sidebar（桌面端 sidebar 无 .open class，不受影响）
    if (elements.sidebar && elements.sidebar.classList.contains('open')) {
        elements.sidebar.classList.remove('open');
        elements.hamburgerBtn?.setAttribute('aria-expanded', 'false');
        if (elements.sidebarBackdrop) elements.sidebarBackdrop.hidden = true;
    }
});
```

- [ ] **Step 6: Verify build**

Run: `cd server && go build ./...`
Expected: BUILD SUCCESSFUL (web 嵌入资源未破坏；HTML/CSS/JS 错误不会被 Go 编译捕获，但 embed 失败会)。

- [ ] **Step 7: Manual verification (desktop unchanged)**

Open `server/internal/web/index.html` in a browser (or run server and visit it). Verify on desktop viewport (≥1024px):
- Hamburger button is NOT visible (`display: none` from base style)
- Sidebar is visible in its normal position
- No backdrop element visible

- [ ] **Step 8: Commit**

```bash
cd "E:\github_project\LocalMediaHub"
git add server/internal/web/index.html \
        server/internal/web/style.css \
        server/internal/web/dom.js \
        server/internal/web/app.js
git commit -m "$(cat <<'EOF'
feat(web): hamburger drawer mechanism for responsive sidebar (round 16 C1)

Adds hamburger button in main-header, sidebar-backdrop div, and CSS for
the drawer transition. Hamburger is display:none by default; C2 will
show it via media query. JS toggles sidebar.open + aria-expanded +
backdrop hidden, and closes the drawer on route change. z-index
backdrop=50, sidebar=60 — below all modals (100+).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2 (Commit C2): 3 断点响应式布局

**Files:**
- Modify: `server/internal/web/style.css`（append @media 段）

**Interfaces:**
- Consumes: Task 1 的 `.hamburger-btn`、`.sidebar-backdrop`、`.sidebar.open` 定义
- Produces: `@media (max-width: 1023px)` 和 `@media (max-width: 767px)` 响应式规则

- [ ] **Step 1: Append tablet + phone @media block to `style.css`**

Open `server/internal/web/style.css`. Append at the END of file:

```css

/* ─── Round 16 C2: Responsive Breakpoints ─── */

/* Tablet: 768-1023px — sidebar collapses to drawer, stat-grid → 2 cols */
@media (max-width: 1023px) {
    .hamburger-btn {
        display: flex;
    }
    .app-container {
        grid-template-columns: 1fr; /* 移除 260px 固定列，全宽 */
    }
    .sidebar {
        position: fixed;
        top: 0;
        left: 0;
        bottom: 0;
        transform: translateX(-100%);
        transition: transform 0.25s ease;
        z-index: 60; /* backdrop(50) < sidebar(60) < modals(100) */
        width: 260px; /* 保持与桌面端一致的宽度 */
    }
    .sidebar.open {
        transform: translateX(0);
    }
    .stats-grid {
        grid-template-columns: repeat(2, 1fr);
    }
    .dashboard-widgets {
        grid-template-columns: 1fr;
    }
}

/* Phone: ≤767px — single column, denser UI */
@media (max-width: 767px) {
    .stats-grid {
        grid-template-columns: 1fr;
    }
    .main-header {
        height: auto; /* 释放固定 72px 高度，允许自然流式布局 */
        padding: 12px 16px;
        flex-wrap: wrap;
        gap: 8px;
    }
    .main-header h1 {
        font-size: 18px;
        flex: 1;
        order: 2; /* hamburger ← title ← actions */
    }
    .hamburger-btn {
        order: 1;
    }
    .header-actions {
        order: 3;
        width: 100%; /* 按钮独占一行 */
    }
    .header-actions .btn {
        padding: 6px 10px;
        font-size: 13px;
    }
    .view-container {
        padding: 12px;
    }
    .browser-grid {
        grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
        gap: 8px;
    }
    .media-card {
        font-size: 12px;
    }
    .breadcrumbs {
        font-size: 13px;
        flex-wrap: wrap;
    }
    .search-box {
        width: 100%;
        margin-bottom: 8px;
    }
    .info-item {
        flex-direction: column;
        align-items: flex-start;
        gap: 4px;
    }
    .info-label {
        font-size: 12px;
    }
    .info-value {
        font-size: 14px;
        word-break: break-all;
    }
    .tag-item {
        padding: 6px 10px;
        font-size: 13px;
    }
}
```

- [ ] **Step 2: Verify build**

Run: `cd server && go build ./...`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual verification (3 breakpoints in Chrome DevTools)**

Open the app in Chrome. Open DevTools → Toggle device toolbar (`Ctrl+Shift+M`). Test 3 viewports:

**Desktop (1920×1080 or any ≥1024px):**
- Sidebar visible in normal position (260px left column)
- Stats grid shows 4 cards in a row
- Hamburger button NOT visible
- All base behavior unchanged

**iPad Mini (768×1024):**
- Hamburger button visible in header (left of title)
- Sidebar hidden by default (translateX(-100%))
- Click hamburger → sidebar slides in from left, backdrop visible
- Click backdrop → sidebar closes
- Stats grid shows 2 cards per row
- Dashboard widgets stack to single column

**iPhone 12 (390×844):**
- All tablet behaviors apply
- Stats grid shows 1 card per row
- Header is taller (auto height), hamburger/title/actions stack vertically
- Browser grid shows ~3 cards per row (120px minmax)
- Breadcrumbs wrap if long
- Search box is full width

**Verify sidebar drawer interaction:**
- Click any sidebar menu item → view changes AND sidebar closes automatically (hashchange handler)

- [ ] **Step 4: Commit**

```bash
cd "E:\github_project\LocalMediaHub"
git add server/internal/web/style.css
git commit -m "$(cat <<'EOF'
feat(web): 3-breakpoint responsive CSS (round 16 C2)

Tablet (≤1023px): hamburger shows, sidebar becomes drawer (translateX
slide-in), app-container drops to single grid column, stat-grid → 2 cols.
Phone (≤767px): stat-grid → 1 col, header height auto + flex-wrap with
hamburger/title/actions ordering, browser-grid tighter minmax(120px).

Desktop (≥1024px) base styles untouched — all changes inside @media.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3 (Commit C3): 模态手机全屏化

**Files:**
- Modify: `server/internal/web/style.css`（append phone-only modal @media 段）

**Interfaces:**
- Consumes: Task 2 已建立的 `@media (max-width: 767px)` 习惯
- Produces: 模态全屏化规则（仅在手机断点生效）

- [ ] **Step 1: Verify existing modal class names**

Before writing CSS, confirm the modal class names match the spec. Run these greps:

```bash
cd server/internal/web
grep -n "overlay-modal\|modal-wrapper\|lightbox-wrapper\|btn-close\|lightbox-close\|lightbox-nav" style.css | head -20
```

Expected: see selectors matching the spec §3.3 z-index table. If the actual class names differ (e.g., `.video-modal` vs `.modal-wrapper`), adjust the CSS in Step 2 accordingly — match what's actually in the codebase, not the spec text.

- [ ] **Step 2: Append phone-only modal full-screen @media to `style.css`**

Open `server/internal/web/style.css`. Append at the END of file:

```css

/* ─── Round 16 C3: Full-screen modal on phone ─── */

@media (max-width: 767px) {
    /* Video player + image lightbox modals: full viewport on phone.
       Uses !important to override existing max-width: 800px / 420px
       constraints on .modal-wrapper / .lightbox-wrapper. */
    .overlay-modal {
        padding: 0 !important;
    }
    .modal-wrapper,
    .lightbox-wrapper {
        position: fixed !important;
        inset: 0 !important;
        width: 100vw !important;
        height: 100vh !important;
        max-width: none !important;
        max-height: none !important;
        border-radius: 0 !important;
        margin: 0 !important;
        padding: 0 !important;
    }
    /* Modal content fills the wrapper */
    .video-modal-content,
    .lightbox-content,
    .lightbox-single-view {
        width: 100%;
        height: 100%;
        max-width: 100%;
        max-height: 100%;
        border-radius: 0;
    }
    /* Media elements scale to viewport */
    .video-modal-content video,
    .lightbox-img {
        width: 100%;
        height: auto;
        max-height: 85vh;
        object-fit: contain;
    }
    /* Close buttons: larger touch target, top-right corner */
    .btn-close,
    .lightbox-close {
        top: 8px !important;
        right: 8px !important;
        font-size: 24px;
        padding: 8px 12px;
        background: rgba(0, 0, 0, 0.6);
        color: #fff;
        border-radius: 50%;
        z-index: 110;
    }
    /* Hide prev/next arrow buttons on phone — overlay too dense.
       Users close + reopen; swipe gesture is YAGNI for this round. */
    .lightbox-nav,
    .lightbox-prev,
    .lightbox-next {
        display: none !important;
    }
}
```

> **`!important` rationale:** Existing `.modal-wrapper { max-width: 800px }` and similar constraints have higher specificity than `@media` rules without `!important`. Without `!important`, the override fails on phone. This is documented in spec §8 limitation #5.

> **Step 1 fallback:** If your grep in Step 1 showed different class names (e.g., `.video-modal` instead of `.modal-wrapper`), substitute those names in the CSS above. The goal is to override whatever actually constrains the modal size on phone.

- [ ] **Step 3: Verify build**

Run: `cd server && go build ./...`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual verification (phone modal full-screen)**

Open the app in Chrome DevTools device mode at iPhone 12 (390×844). For each modal type:

**Video player modal:**
- Navigate to browser → click a video card
- Modal opens full-screen (no margins, no border radius)
- Video fills width, vertically centered
- Close button (top-right) is reachable with thumb
- Click close → modal dismisses

**Lightbox modal:**
- Navigate to browser → click an image card
- Modal opens full-screen
- Image fills width, vertically centered
- Close button reachable
- prev/next nav buttons are hidden (use keyboard arrows if needed, or close+reopen)

**Sidebar drawer still works during modal:**
- Open lightbox → try opening hamburger drawer → drawer should NOT appear above modal (z-index 60 < 100)
- This verifies the z-index layering from spec §3.3

- [ ] **Step 5: Commit**

```bash
cd "E:\github_project\LocalMediaHub"
git add server/internal/web/style.css
git commit -m "$(cat <<'EOF'
feat(web): full-screen modals on phone (round 16 C3)

@media (max-width: 767px): video/image modals go full-viewport
(100vw × 100vh), close buttons get larger touch targets + dark
background, lightbox prev/next nav hidden (overlay too dense on phone;
swipe gesture deferred). !important used to override existing
max-width constraints on .modal-wrapper / .lightbox-wrapper.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## 附录 A: 实现速查

| Commit | 文件数 | 改动量 | 风险 | 验证方法 |
|---|---|---|---|---|
| C1 hamburger 机制 | 4 (index.html + style.css + dom.js + app.js) | ~70 行 | 低 | DevTools 桌面端不变 |
| C2 3 断点响应式 | 1 (style.css append) | ~80 行 | 低-中（CSS 选择器） | DevTools 3 视口测试 |
| C3 模态全屏化 | 1 (style.css append) | ~50 行 | 中（!important + 选择器） | DevTools 手机端模态 |

## 附录 B: z-index 层级最终状态

| z-index | 选择器 | 用途 |
|---|---|---|
| 5 | `.card-actions-overlay` | 媒体卡片操作按钮 |
| 10 | `.main-header` / `.video-controls-overlay` | 顶部导航 / 视频控件 |
| **50** | **`.sidebar-backdrop`** | **Round 16 新增** |
| **60** | **`.sidebar.open`** | **Round 16 新增（仅在 ≤1023px 抽屉模式）** |
| 100 | `.overlay-modal` / `.lightbox-stitch-view` | 所有模态框 |
| 101 | `.modal-wrapper` | 模态框内容 |
| 105 | `.lightbox-nav` | 灯箱导航按钮 |
| 110 | `.lightbox-close` / `.btn-close(phone)` / `.lightbox-toggle-mode` | 关闭按钮 |
| 200 | `.toast-container` | Toast 通知 |

> 抽屉 z-index 60 < 模态 100：模态打开时，抽屉即使被触发也藏不住模态。

## 附录 C: 已知限制（接受）

1. **prev/next nav 在手机隐藏**（spec §8 #1）：用户连续浏览 lightbox 受影响。YAGNI；如反馈强烈再加 swipe。
2. **无自动化测试**（spec §8 #2）：Web 端无 Vitest/JSDOM。手工 DevTools 验证。
3. **Google Fonts CDN 阻塞**（spec §8 #3）：网络不佳时手机首次加载慢。次要。
4. **landscape 模式未优化**（spec §8 #4）：仅 portrait。
5. **`!important` 在 C3 模态覆盖**（spec §8 #5）：必要，覆盖现有 `max-width: 800px/420px` 约束。
6. **CSS 选择器名审计依赖**（C3 Step 1）：spec 假设 `.modal-wrapper` / `.lightbox-wrapper` / `.btn-close` 等，但实际项目可能不同。Step 1 grep 验证；如有差异，按实际项目调整。
