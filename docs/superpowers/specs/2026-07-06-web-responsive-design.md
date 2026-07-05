# Web 端响应式 CSS 设计（Round 16）

- **日期**: 2026-07-06
- **范围**: Web 前端 — `server/internal/web/` 的 `index.html`、`style.css`、`app.js`、`dom.js`
- **策略**: 3 commits — Hamburger 抽屉 + 3 断点响应式 + 模态全屏化
- **状态**: 待评审
- **前置**: Round 6（app.js 模块化，无现有 @media 响应式断点）

---

## 1. 背景与动机

Web 端 1316 行 `style.css` 是桌面专供，5 处 `max-width` 都是单元素最大宽度（如 `.recent-list { max-width: 800px }`），**没有真正的 `@media` 响应式断点**。在手机/平板上：

- Sidebar 固定占用左侧 240px，挤压主内容
- Stats grid 永远 4 列，手机上每列 < 80px
- Dashboard widgets 在窄屏溢出
- VideoPlayer/Lightbox 模态按固定大小显示，手机上几乎不可用
- Header 按钮 + 标题在窄屏 wrap 不规整

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
3. **C3 模态全屏化**：≤767px 时 videoPlayer + lightbox 占满 viewport。
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
| C1 | `server/internal/web/app.js` | 改：setupEventListeners 加 hamburger/backdrop 监听 |
| C2 | `server/internal/web/style.css` | 改：末尾追加 3 断点 @media 段 |
| C3 | `server/internal/web/style.css` | 改：末尾追加 phone-only @media 模态全屏化段 |

无新增文件。`style.css` 总改动 ~150-200 行（追加，不改 base styles）。

### 3.2 关键约束

- HTML viewport meta 已就绪（`<meta name="viewport" content="width=device-width, initial-scale=1.0">`）
- Hamburger button 必须有 `aria-expanded` 属性（a11y）
- Backdrop 必须 `hidden` 默认（防止覆盖桌面）
- 桌面端（≥1024px）base styles 不动 — 所有改动都在 `@media (max-width: 1023px)` 或 `@media (max-width: 767px)` 内
- Sidebar 抽屉化用 CSS `transform: translateX(-100%)` 而非 `display: none`（保留过渡动画）
- 模态全屏化保留 close 按钮，移除 prev/next nav button（手机上覆盖 image 太多；用户用键盘/外接键盘导航）
- 不引入 npm/build chain（保持 Web 端"纯静态"哲学）

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
    color: var(--text-primary, #1f2937);
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
    z-index: 90;
    cursor: pointer;
}
```

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

### 4.2 C2: 3 断点响应式

**`style.css` 末尾追加（C2 段）：**

```css
/* ─── Round 16 C2: Responsive Breakpoints ─── */

/* Tablet: 768-1023px — sidebar collapses to drawer, stat-grid → 2 cols */
@media (max-width: 1023px) {
    .hamburger-btn {
        display: flex;
    }
    .sidebar {
        position: fixed;
        top: 0;
        left: 0;
        bottom: 0;
        transform: translateX(-100%);
        transition: transform 0.25s ease;
        z-index: 100;
        width: 240px;
    }
    .sidebar.open {
        transform: translateX(0);
    }
    .main-content {
        margin-left: 0;
        width: 100%;
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
    }
    .header-actions .btn {
        padding: 6px 10px;
        font-size: 13px;
    }
    .view-container {
        padding: 12px;
    }
    .media-grid {
        grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
        gap: 8px;
    }
    .media-card {
        font-size: 12px;
    }
    .breadcrumb {
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
    /* Tag manager compact on phone */
    .tag-item {
        padding: 6px 10px;
        font-size: 13px;
    }
}
```

> 桌面端（≥1024px）base styles 完全不动 — 所有改动在 `@media (max-width: 1023px)` 内。

### 4.3 C3: Video/Lightbox 模态手机全屏化

**`style.css` 末尾追加（C3 段）：**

```css
/* ─── Round 16 C3: Full-screen modal on phone ─── */

@media (max-width: 767px) {
    /* Video player modal — full viewport on phone */
    .video-modal,
    .lightbox-modal {
        position: fixed;
        inset: 0;
        width: 100vw;
        height: 100vh;
        max-width: none !important;
        max-height: none !important;
        border-radius: 0;
        margin: 0;
        padding: 0;
    }
    .video-modal-content,
    .lightbox-content {
        width: 100%;
        height: 100%;
        max-width: 100%;
        max-height: 100%;
        border-radius: 0;
    }
    .video-modal video,
    .lightbox-image {
        width: 100%;
        height: auto;
        max-height: 85vh;
        object-fit: contain;
    }
    .modal-close,
    .video-modal-close,
    .lightbox-close {
        top: 8px;
        right: 8px;
        font-size: 24px;
        padding: 8px 12px;
        background: rgba(0, 0, 0, 0.6);
        color: #fff;
        border-radius: 50%;
        z-index: 10;
    }
    /* Hide prev/next arrow buttons on phone (overlay too dense; users use
       keyboard nav or back-and-forward routing). Swipe gesture is YAGNI. */
    .lightbox-prev,
    .lightbox-next,
    .lightbox-nav {
        display: none;
    }
}
```

> ⚠️ **UX 取舍：** 手机上隐藏 prev/next 是为了不覆盖 image 太多。**用户仍可关闭 + 重新打开**，但失去连续浏览。如果后续用户反馈强烈，再加 swipe 手势或浮动按钮（YAGNI 当前轮次）。

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
- Browser：搜索框全宽、breadcrumb wrap、media-grid 列数
- Tags：tag 列表紧凑、modal 操作可用
- Settings：表单字段堆叠、保存按钮可达
- Lightbox：模态全屏、close 按钮可见、prev/next 隐藏（手机）
- VideoPlayer：模态全屏、video 居中、close 可见

#### 5.1.3 交互验证

- Hamburger 点击：sidebar.open class 切换、aria-expanded 同步、backdrop 显示/隐藏
- Backdrop 点击：sidebar 关闭、aria-expanded=false、backdrop hidden
- 桌面端：hamburger `display: none`，无 backdrop，sidebar 始终可见
- 路由变化（hashchange）后 sidebar 状态保持（不强制关闭；用户可能想连续导航）

### 5.2 已知限制

- **无自动化测试**：Web 端无 Vitest/JSDOM（Round 6 决策）。CSS 响应式只能手工验证。
- **Google Fonts CDN 在窄屏阻塞渲染**：可选优化（次要），不在本轮范围。
- **prev/next nav 在手机隐藏**：用户连续浏览 lightbox 受影响（YAGNI 当前轮次）。
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
| 路由变化后 sidebar 状态 | 不强制关闭 | 用户可能想连续导航 |
| 测试 | 手工回归（与 Round 6 一致） | Web 端无测试框架 |
| 提交粒度 | 3 个 commit | C1 含 JS 逻辑，C2/C3 仅 CSS |
| 视口已就绪 | `<meta viewport>` 已存在 | 无需改动 HTML head |

---

## 8. 已知限制（接受）

1. **prev/next nav 在手机隐藏**（§4.3）：用户连续浏览 lightbox 受影响。YAGNI；如反馈强烈再加 swipe 或浮动按钮。
2. **无自动化测试**（§5.2 #1）：Round 6 排除测试框架；本轮继续手工回归。
3. **Google Fonts CDN 阻塞**（§5.2 #2）：网络不佳时手机首次加载慢。次要问题。
4. **landscape 模式未优化**（§5.2 #4）：仅 portrait；横向手机/平板可能布局不理想。
5. **CSS 选择器特异性风险**：现有 1316 行 CSS 未审计，部分选择器可能与新 @media 冲突。`!important` 仅在 §4.3 模态 `max-width/height` 用（覆盖现有 `max-width: 800px/420px` 等）。

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
