# Web 端响应式 CSS 设计（Round 16）

- **日期**: 2026-07-06
- **范围**: Web 前端 — `server/internal/web/` 的 `index.html`、`style.css`、`app.js`、`dom.js`
- **策略**: 3 commits — Hamburger 抽屉 + 3 断点响应式 + 模态全屏化
- **状态**: 已审核 ✅（2026-07-06 代码审计后修订）
- **前置**: Round 6（app.js 模块化，无现有 @media 响应式断点）

---

## 1. 背景与动机

Web 端 1317 行 `style.css` 是桌面专供，5 处 `max-width` 都是单元素最大宽度（如 `.modal-wrapper { max-width: 800px }`、`.tag-selector-wrapper { max-width: 420px }`），**没有真正的 `@media` 响应式断点**（仅有 `@keyframes fadeIn` 和 `@keyframes slideIn`）。在手机/平板上：

- `.app-container` 用 CSS Grid `grid-template-columns: 260px 1fr` 固定侧栏，挤压主内容
- Stats grid 用 `repeat(auto-fit, minmax(220px, 1fr))`，在极窄屏幕仍会过小
- Dashboard widgets 固定 2 列（`grid-template-columns: 1fr 1fr`），窄屏溢出
- VideoPlayer 模态 `.modal-wrapper` 固定 `max-width: 800px`，手机上浪费空间
- Lightbox 已全屏（`.lightbox-wrapper` 用 `100vw × 100vh`），但导航按钮在触屏上体验差
- Header 固定 `height: 72px` + `padding: 0 32px`，按钮在窄屏 wrap 不规整

Round 6 spec §8 把"style.css 响应式 @media"列为最高优先级 Web follow-up。Round 16 解决之。

### 1.1 范围明确

- ✅ 3 断点：桌面 (≥1024px)、平板 (768-1023px)、手机 (≤767px)
- ✅ Hamburger 抽屉式 sidebar（手机 + 平板）
- ✅ Stats grid 自适应列数
- ✅ VideoPlayer/Lightbox 模态手机全屏化
- ❌ Swipe 手势（YAGNI）
- ❌ PWA / Service Worker
- ❌ CSS variables / BEM 重构
- ❌ 单元测试框架引入
- ❌ 服务端/Android 改动

---

## 2. 目标与非目标

### 目标
1. **C1 hamburger + 抽屉**：≤1023px 时 sidebar 收起为抽屉，hamburger 按钮控制开关。
2. **C2 3 断点响应式**：stats-grid 在平板 2 列、手机 1 列；widgets 单列；字体/padding 紧凑。
3. **C3 模态全屏化**：≤767px 时 videoPlayer `.modal-wrapper` 及 lightbox 导航优化。
4. **零新依赖**：纯 CSS + 极小 JS，不引入 UI 框架。
5. **行为兼容**：桌面 (≥1024px) 行为与现有完全一致（base styles 不动）。

### 非目标
- ❌ Swipe 手势（YAGNI；用 modal-close + 现有键盘 nav）
- ❌ PWA、Service Worker、manifest
- ❌ CSS variables / BEM 命名重构
- ❌ 单元测试框架
- ❌ Google Fonts 异步加载优化
- ❌ 任何服务端/Android 改动
- ❌ 任何用户可见行为变化（桌面端）

---

## 3. 架构与文件清单

### 3.1 文件改动矩阵（3 个 commit）

| Commit | 文件 | 改动类型 |
|---|---|---|
| C1 | `server/internal/web/index.html` | 改：加 hamburger button + sidebar-backdrop div |
| C1 | `server/internal/web/style.css` | 改：末尾追加 hamburger + backdrop 样式 |
| C1 | `server/internal/web/dom.js` | 改：加 hamburgerBtn / sidebarBackdrop / sidebar 引用 |
| C1 | `server/internal/web/app.js` | 改：setupEventListeners 加 hamburger/backdrop 监听 + 路由关闭 sidebar |
| C2 | `server/internal/web/style.css` | 改：末尾追加 3 断点 @media 段 |
| C3 | `server/internal/web/style.css` | 改：末尾追加 phone-only @media 模态全屏化段 |

无新增文件。`style.css` 总改动 ~150-200 行（追加，不改 base styles）。

### 3.2 关键约束

- HTML viewport meta 已就绪（`<meta name="viewport" content="width=device-width, initial-scale=1.0">`）
- Hamburger button 必须有 `aria-expanded` 属性（a11y）
- Backdrop 必须 `hidden` 默认（防止覆盖桌面）
- 桌面端（≥1024px）base styles 不动 — 所有改动都在 `@media (max-width: 1023px)` 或 `@media (max-width: 767px)` 内
- Sidebar 抽屉化用 CSS `transform: translateX(-100%)` 而非 `display: none`（保留过渡动画）
- 模态全屏化保留 close 按钮（`.btn-close` / `.lightbox-close`），手机上隐藏 lightbox 导航按钮（`.lightbox-nav`）
- 不引入 npm/build chain（保持 Web 端"纯静态"哲学）

### 3.3 现有 z-index 层级（代码审计结果）

| z-index | 选择器 | 用途 |
|---|---|---|
| 5 | `.card-actions-overlay` | 媒体卡片操作按钮 |
| 10 | `.main-header` | 顶部导航栏 |
| 10 | `.video-controls-overlay` | 视频播放器控件 |
| 100 | `.overlay-modal` | 所有模态框 |
| 100 | `.lightbox-stitch-view` | 拼接浏览模式 |
| 101 | `.modal-wrapper` | 模态框内容 |
| 105 | `.lightbox-nav` | 灯箱导航按钮 |
| 110 | `.lightbox-close` | 灯箱关闭按钮 |
| 110 | `.lightbox-toggle-mode` | 拼接模式切换 |
| 200 | `.toast-container` | Toast 通知 |

> **重要**：sidebar 抽屉和 backdrop 的 z-index 必须低于 `.overlay-modal`（100），避免抽屉遮挡模态框。推荐方案：backdrop = 50，sidebar = 60。

---

## 4. 实现细节

### 4.1 C1: Hamburger + 抽屉 sidebar

**`index.html` 改动：**

在 `main-header` 内 `<h1 id="page-title">` 之前加：

```html
<button class="hamburger-btn" id="btn-hamburger" aria-label="切换菜单" aria-expanded="false">
    <span></span><span></span><span></span>
</button>
```

在 `</aside>` 之后、`<main class="main-content">` 之前加：

```html
<div class="sidebar-backdrop" id="sidebar-backdrop" hidden></div>
```

**`style.css` 末尾追加（C1 段）：**

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
    color: var(--text-main); /* 使用现有 CSS 变量 #f3f4f6，深色背景上可见 */
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

> **审核修订**：原计划使用 `var(--text-primary, #1f2937)`，但 `--text-primary` 变量不存在于 `:root` 中，且回退值 `#1f2937` 是深色文字，在深色背景 `--bg-main: #0a0a0f` 上几乎不可见。改为 `var(--text-main)` (`#f3f4f6`)。
>
> **审核修订**：backdrop z-index 从 90 改为 50，确保低于所有模态框层级（100+）。

**`dom.js` 改动**（在 `elements` 对象内加 3 个引用）：

```js
hamburgerBtn: document.getElementById('btn-hamburger'),
sidebarBackdrop: document.getElementById('sidebar-backdrop'),
sidebar: document.querySelector('.sidebar'),
```

**`app.js::setupEventListeners` 改动**（在末尾加）：

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
        elements.hamburgerBtn.setAttribute('aria-expanded', 'false');
        elements.sidebarBackdrop.hidden = true;
    });
}
```

> `if (elements.hamburgerBtn)` null guard 防止老缓存 HTML 加载新 JS 时崩溃（向后兼容）。

**`app.js` hashchange 监听器改动**（路由变化时关闭 sidebar）：

```js
// 在 hashchange handler 中，路由变化后自动关闭移动端 sidebar
window.addEventListener('hashchange', () => {
    handleRoute(elements, renderDashboard, loadRoots, browsePath, renderTagsManager, renderSettings);
    // Round 16 C1: 移动端路由切换后关闭 sidebar
    if (elements.sidebar && elements.sidebar.classList.contains('open')) {
        elements.sidebar.classList.remove('open');
        elements.hamburgerBtn?.setAttribute('aria-expanded', 'false');
        if (elements.sidebarBackdrop) elements.sidebarBackdrop.hidden = true;
    }
});
```

> **审核修订**：原计划决策"路由变化后不强制关闭 sidebar"在移动端是糟糕的 UX — 用户点击菜单项导航后，如果抽屉不关闭，抽屉仍然覆盖整个内容区域。这里改为路由变化后自动关闭（桌面端 sidebar 不带 `.open` class，`classList.contains('open')` 为 false，不受影响）。

### 4.2 C2: 3 断点响应式

**`style.css` 末尾追加（C2 段）：**

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
    /* Tag manager: 移动端单列布局 */
    .tag-manager-container {
        grid-template-columns: 1fr; /* 移除桌面端 320px + 1fr 双列 */
    }
    .tag-chip {
        padding: 6px 10px;
        font-size: 13px;
    }
    /* Browser toolbar: 移动端堆叠 */
    .browser-toolbar {
        flex-direction: column;
        gap: 8px;
        align-items: stretch;
    }
}
```

> **审核修订（关键 5 处）**：
> 1. **`.main-content { margin-left: 0 }` 已移除** — 布局基于 `.app-container` 的 CSS Grid（`grid-template-columns: 260px 1fr`），而非 margin 偏移。正确做法是在 `.app-container` 上改为 `grid-template-columns: 1fr`。
> 2. **`.media-grid` → `.browser-grid`** — 代码中不存在 `.media-grid` 类，实际媒体浏览网格类名是 `.browser-grid`（style.css L460）。
> 3. **`.breadcrumb` → `.breadcrumbs`**（复数）— 代码中不存在 `.breadcrumb`，实际类名是 `.breadcrumbs`（style.css L398）。
> 4. **`.tag-item` → `.tag-chip`** — 代码中不存在 `.tag-item` 类，实际标签项类名是 `.tag-chip`（style.css L714）。
> 5. **新增 `.tag-manager-container` 和 `.browser-toolbar` 响应式规则** — tag-manager 在桌面端为 `grid-template-columns: 320px 1fr` 双列布局，手机上需要单列。browser-toolbar 在窄屏需要堆叠。
> 6. **新增 `.main-header { height: auto }` 覆盖** — 桌面端 header 固定 72px 高度，手机上 flex-wrap 后需要自适应。
> 7. **sidebar z-index 改为 60** — 低于 `.overlay-modal`(100)，高于 `.sidebar-backdrop`(50)。

### 4.3 C3: Video/Lightbox 模态手机全屏化

**`style.css` 末尾追加（C3 段）：**

```css
/* ─── Round 16 C3: Full-screen modal on phone ─── */

@media (max-width: 767px) {
    /* Video player modal — 让 .modal-wrapper 占满 viewport */
    #modal-video-player .modal-wrapper {
        width: 100%;
        max-width: none;
        height: 100vh;
        border-radius: 0;
        margin: 0;
    }
    #modal-video-player .video-container {
        height: calc(100vh - 56px); /* 减去 modal-header 高度 */
        aspect-ratio: auto; /* 覆盖桌面端 16:9 固定比例 */
    }
    #modal-video-player .video-container video {
        object-fit: contain;
    }

    /* Lightbox 已全屏（.lightbox-wrapper 100vw×100vh），
       但 .lightbox-content 只占 80%×80%，手机上应扩大 */
    .lightbox-content {
        width: 95%;
        height: 95%;
    }

    /* Close 按钮保留，增强触摸友好性 */
    .lightbox-close,
    .btn-close {
        font-size: 24px;
        padding: 8px 12px;
        min-width: 44px;  /* 触摸友好最小尺寸 */
        min-height: 44px;
    }

    /* 隐藏 lightbox prev/next 导航按钮（手机上覆盖 image 太多） */
    .lightbox-nav {
        display: none;
    }

    /* 拼接模式切换按钮：在手机上更紧凑 */
    .lightbox-toggle-mode {
        top: 12px;
        left: 12px;
        padding: 6px 12px;
        font-size: 12px;
    }
}
```

> **审核修订（关键 4 处）**：
> 1. **选择器完全重写** — 原计划使用 `.video-modal` / `.lightbox-modal` / `.video-modal-content` / `.lightbox-image` / `.modal-close` / `.video-modal-close` / `.lightbox-prev` / `.lightbox-next`，但**这些类名均不存在于代码中**。实际模态框架：
>    - 视频播放器：`.overlay-modal#modal-video-player` > `.modal-wrapper` > `.video-container`
>    - 灯箱：`.overlay-modal#modal-image-preview` > `.modal-wrapper.lightbox-wrapper` > `.lightbox-content`
>    - 关闭按钮：`.btn-close`（通用）和 `.lightbox-close`
>    - 导航按钮：`.lightbox-nav.nav-prev` / `.lightbox-nav.nav-next`
> 2. **灯箱 `.lightbox-wrapper` 已经是全屏** — 已设 `width: 100vw; height: 100vh; border-radius: 0`（style.css L1091-1103），无需重复设置。真正需要放大的是 `.lightbox-content`（当前 `width: 80%; height: 80%`）。
> 3. **`!important` 全部移除** — 使用 `#modal-video-player .modal-wrapper` 提高选择器特异性，避免 `!important` 导致未来维护困难。
> 4. **新增 `.lightbox-toggle-mode` 手机端紧凑化** — 原计划遗漏。

---

## 5. 测试

### 5.1 测试策略

Web 端无测试框架（Round 6 决策保留），靠**手工回归**：

#### 5.1.1 Chrome DevTools Device Mode 验证

每个视图（dashboard / browser / tags / settings）在 3 个断点检查：

| 断点 | DevTools 预设 | 期望 |
|---|---|---|
| 桌面 ≥1024px | Desktop (1920×1080) | sidebar 始终可见，stats 4 列，base 行为不变 |
| 平板 768-1023px | iPad Mini (768×1024) | sidebar 收起为抽屉，hamburger 显示，点击展开 |
| 手机 ≤767px | iPhone 12 (390×844) | sidebar 抽屉，stats 1 列，模态全屏化 |

#### 5.1.2 视图 × 断点矩阵

- Dashboard：stat-cards 自适应、最近打开列表、服务信息
- Browser：搜索框全宽、breadcrumbs wrap、browser-grid 列数
- Tags：tag-manager-container 单列、tag-chip 紧凑、modal 操作可用
- Settings：表单字段堆叠、保存按钮可达
- Lightbox：lightbox-content 95% 占屏、lightbox-close 可见、lightbox-nav 隐藏（手机）
- VideoPlayer：modal-wrapper 全屏、video 居中 contain、btn-close 可见

#### 5.1.3 交互验证

- Hamburger 点击：sidebar.open class 切换、aria-expanded 同步、backdrop 显示/隐藏
- Backdrop 点击：sidebar 关闭、aria-expanded=false、backdrop hidden
- 路由变化（hashchange）后 sidebar 自动关闭（仅在 `.sidebar.open` 时）
- 桌面端：hamburger `display: none`，无 backdrop，sidebar 始终可见
- 模态框打开时：sidebar 已收起不遮挡（z-index sidebar=60 < modal=100）

### 5.2 已知限制

- **无自动化测试**：Web 端无 Vitest/JSDOM（Round 6 决策）。CSS 响应式只能手工验证。
- **Google Fonts CDN 在窄屏阻塞渲染**：可选优化（次要），不在本轮范围。
- **lightbox-nav 在手机隐藏**：用户连续浏览 lightbox 受影响（YAGNI 当前轮次）。
- **landscape 模式未单独优化**：仅 portrait 视角断点。

---

## 6. 实现顺序与提交策略

3 个 commit，每个独立可提交：

1. **C1 hamburger + 抽屉机制**：HTML + CSS + JS（4 文件）
2. **C2 3 断点响应式**：仅 CSS（1 文件追加）
3. **C3 模态全屏化**：仅 CSS（1 文件追加）

每个 commit 之间：
- 浏览器手工验证（无 `npm test` 等价物）
- `cd server && go build ./...` 验证 web 嵌入资源未破坏

---

## 7. 已决策点

| 决策 | 选择 | 理由 |
|---|---|---|
| 断点数 | 3（桌面 ≥1024 / 平板 768-1023 / 手机 ≤767） | 业界标准断点，覆盖主流设备 |
| 手机 sidebar | Hamburger 抽屉 | 业界标准 UX |
| hamburger 位置 | main-header 内（h1 前） | 与现有 header 设计一致 |
| 模态全屏化 | 仅手机断点 | 平板空间够，无需全屏 |
| Mobile-first | 否（桌面 base + override） | base 不动，避免回归 |
| Swipe 手势 | 不做（YAGNI） | 复杂度高，依赖触摸事件 |
| 路由变化后 sidebar 状态 | **自动关闭**（审核修订） | 移动端抽屉覆盖全内容，不关闭则新页面不可见。桌面端无影响（无 `.open` class） |
| 测试 | 手工回归（与 Round 6 一致） | Web 端无测试框架 |
| 提交粒度 | 3 个 commit | C1 含 JS 逻辑，C2/C3 仅 CSS |
| 视口已就绪 | `<meta viewport>` 已存在 | 无需改动 HTML head |
| sidebar z-index | 60（backdrop 50） | 低于 .overlay-modal(100)，避免冲突 |
| hamburger color | `var(--text-main)` | 深色背景需亮色图标，`--text-main: #f3f4f6` |

---

## 8. 已知限制（接受）

1. **lightbox-nav 在手机隐藏**（§4.3）：用户连续浏览 lightbox 受影响。YAGNI；如反馈强烈再加 swipe 或浮动按钮。
2. **无自动化测试**（§5.2 #1）：Round 6 排除测试框架；本轮继续手工回归。
3. **Google Fonts CDN 阻塞**（§5.2 #2）：网络不佳时手机首次加载慢。次要问题。
4. **landscape 模式未优化**（§5.2 #4）：仅 portrait；横向手机/平板可能布局不理想。
5. **CSS 选择器特异性风险**：现有 1317 行 CSS 未全量审计，部分选择器可能与新 @media 冲突。C3 使用 `#id .class` 选择器提升特异性，避免使用 `!important`。

---

## 9. 非目标（再次明确）

- ❌ Swipe 手势、PWA、Service Worker
- ❌ CSS variables / BEM 重构
- ❌ 单元测试框架引入
- ❌ 服务端/Android 改动
- ❌ Google Fonts 异步加载优化
- ❌ 桌面端任何用户可见行为变化

---

## 10. 后续轮次（不在本 spec，仅备忘）

- **Swipe 手势**：lightbox 在手机上加 touchstart/touchend 左右滑动
- **Web 端测试基础设施**：Vitest + JSDOM（router/api/state 单测）
- **dashboard 冗余请求 + stitch scroll 节流**：Round 6 spec §8 列出，本轮未做
- **CSS variables 引入**：颜色/间距主题化
- **PWA + 离线支持**：若 mobile 使用场景重要

---

## 附录 A：审核修订摘要

以下是 2026-07-06 代码审计后的所有修订要点：

| # | 原计划内容 | 问题 | 修订 |
|---|---|---|---|
| 1 | `1316 行 style.css` | 实际 1317 行 | 已修正 |
| 2 | `sidebar 固定占用左侧 240px` | 实际 `.app-container` 用 `grid-template-columns: 260px 1fr` | 已修正为 260px |
| 3 | `color: var(--text-primary, #1f2937)` | `--text-primary` 不存在；回退值 `#1f2937` 在深色背景不可见 | 改为 `var(--text-main)` |
| 4 | `.main-content { margin-left: 0 }` | 布局用 CSS Grid 非 margin | 改为 `.app-container { grid-template-columns: 1fr }` |
| 5 | `.media-grid` | 类名不存在 | 改为 `.browser-grid` |
| 6 | `.breadcrumb` | 类名不存在 | 改为 `.breadcrumbs`（复数） |
| 7 | `.tag-item` | 类名不存在 | 改为 `.tag-chip` |
| 8 | `.video-modal` / `.lightbox-modal` | 类名不存在 | 改为 `#modal-video-player .modal-wrapper` / `.lightbox-content` |
| 9 | `.video-modal-content` | 类名不存在 | 改为 `.video-container` |
| 10 | `.video-modal-close` / `.modal-close` | 类名不存在 | 改为 `.btn-close` |
| 11 | `.lightbox-image` | 类名不存在 | 改为 `.lightbox-content img` |
| 12 | `.lightbox-prev` / `.lightbox-next` | 类名不存在 | 改为 `.lightbox-nav`（统一选择器） |
| 13 | backdrop z-index: 90, sidebar z-index: 100 | 与 `.overlay-modal` (z-index: 100) 冲突 | 改为 backdrop: 50, sidebar: 60 |
| 14 | 路由变化后"不强制关闭 sidebar" | 移动端 UX 糟糕 | 改为路由变化后自动关闭 |
| 15 | C3 使用 `!important` | 可维护性差 | 改用 `#id .class` 提高特异性 |
| 16 | 遗漏 `.tag-manager-container` | 桌面端 `320px + 1fr` 双列手机需单列 | 新增 `grid-template-columns: 1fr` |
| 17 | 遗漏 `.browser-toolbar` | 桌面端 flex 横向手机需堆叠 | 新增 `flex-direction: column` |
| 18 | 遗漏 `.main-header { height: auto }` | 固定 72px 在 flex-wrap 时不适应 | 新增覆盖 |
| 19 | Lightbox 全屏改动 | `.lightbox-wrapper` 已全屏 100vw×100vh | 改为仅放大 `.lightbox-content` 80%→95% |
| 20 | 遗漏 `.lightbox-toggle-mode` 手机适配 | 按钮在手机上过大 | 新增紧凑化样式 |
