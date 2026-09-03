# Web 端快速置顶与置底悬浮导航（FAB）设计

- **日期**：2026-09-03
- **状态**：Draft (Pending Approval)
- **作者**：Antigravity & User

---

## 1. 背景与目标

### 1.1 背景
LocalMediaHub 的 Web 管理界面中：
1. **媒体共享库（`#/browser`）**：当某一目录下包含海量文件（如数百部小说、文本、视频等）时，列表卡片极为冗长，用户需要长时间滑动滚轮才能触达底部或返回顶部。
2. **文本阅读器（`#/read`）**：在阅读超长章节或开启全文滚动模式时，正文内容很长，缺乏一键直达开头与末尾的便捷操作。

### 1.2 目标
1. **双场景支持**：在媒体共享库列表浏览与文本阅读器中均提供快速置顶（Scroll to Top）与快速置底（Scroll to Bottom）功能。
2. **智能显隐（Smart Visibility）**：
   - 当页面无可滚动余量时，按钮组自动隐藏；
   - 在页面顶部时仅显示“置底”；
   - 滚至内容中间时“置顶”与“置底”均显示；
   - 滚到接近底部时仅显示“置顶”。
3. **架构解耦与现代化设计**：
   - 独立为通用控制器模块 `scrollNav.js`，通过动态检测当前视图绑定对应的滚动容器（`.view-container` 或 `.text-reader__content`）；
   - 采用右下角悬浮按钮组（FAB），对齐 2026-09-02 Web UI 现代中性风设计语言，完美适配 7 套日/夜主题与无级响应式布局；
   - 遵循项目的 CSP（无 inline style）与 XSS 规范（通过 `tools/xsscheck`），并通过原生 `node --test` 进行单测。

### 1.3 非目标
- 不修改后端 Go API 或路由协议；
- 不干扰阅读器已有的手势翻页（`pageTurn.js`）、自动滚动（`autoscroll.js`）与全书进度跳章（`readerScrubber.js`）；
- 不引入额外的前端构建工具或外部 npm 依赖。

---

## 2. 界面与交互规范

### 2.1 结构与布局
在 `server/internal/web/index.html` 的 `.main-content` 内声明静态 HTML 结构：

```html
<!-- Quick Scroll Navigation FAB (Top & Bottom) -->
<div class="scroll-fab-group" id="scroll-fab-group" aria-label="页面快捷滚动">
    <button class="scroll-fab-btn" id="btn-scroll-top" title="返回顶部" aria-label="返回顶部">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="m18 15-6-6-6 6"/>
        </svg>
    </button>
    <button class="scroll-fab-btn" id="btn-scroll-bottom" title="直达底部" aria-label="直达底部">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="m6 9 6 6 6-6"/>
        </svg>
    </button>
</div>
```

### 2.2 视觉与样式设计
- **定位**：
  - 默认（媒体库等常规视图）：`position: fixed; right: 28px; bottom: 28px; z-index: 40;`；
  - 文本阅读器视图（`body[data-active-tab="read"]`）：`bottom: 72px; right: 24px;`，自动避让阅读器底栏（高度 48px）；
  - 全屏沉浸阅读模式（`body[data-reader-immersive="on"]`）：跟随阅读器 Header/Footer 自动淡出（`opacity: 0; pointer-events: none;`）；
  - 移动端（`@media (max-width: 768px)`）：右边距与底边距收敛为 `16px`。
- **按钮样式（`.scroll-fab-btn`）**：
  - 规格：`38px × 38px` 圆形，内联 SVG 图标 `18px × 18px`，按钮间距 `8px`；
  - 默认状态：背景 `var(--surface-card)`，边框 `1px solid var(--border-subtle)`，阴影 `var(--shadow-md)`，图标色 `var(--text-secondary)`；
  - 交互状态（Hover / Focus-visible）：背景 `var(--surface-hover)`，边框与图标色切为 `var(--accent)`，微动效 `transform: translateY(-1px)`；
  - 显隐动画：
    - 隐藏时：`opacity: 0; pointer-events: none; transform: scale(0.85);`；
    - 显示时（增加 `.scroll-fab-btn--visible` 类）：`opacity: 1; pointer-events: auto; transform: scale(1);`；
    - 过渡曲线：`transition: opacity 0.2s ease, transform 0.2s ease, background-color 0.15s ease, border-color 0.15s ease, color 0.15s ease;`。

---

## 3. 核心逻辑与模块设计

### 3.1 模块结构（`scrollNav.js`）
核心模块划分为：
1. **纯计算函数（可测）**：
   ```javascript
   export function computeFabVisibility(scrollTop, clientHeight, scrollHeight, threshold = 120) {
       const maxScroll = scrollHeight - clientHeight;
       if (maxScroll <= 100) {
           return { showTop: false, showBottom: false };
       }
       return {
           showTop: scrollTop > threshold,
           showBottom: scrollTop < (maxScroll - threshold)
       };
   }
   ```
2. **容器解析函数**：
   ```javascript
   export function resolveScrollContainer() {
       if (document.body.dataset.activeTab === 'read') {
           const readerContent = document.querySelector('.text-reader__content');
           if (readerContent) return readerContent;
       }
       return document.querySelector('.view-container');
   }
   ```
3. **滚动与点击绑定**：
   - 置顶：`container.scrollTo({ top: 0, behavior: 'smooth' })`；
   - 置底：`container.scrollTo({ top: container.scrollHeight, behavior: 'smooth' })`；
   - 滚动监听使用 `requestAnimationFrame` 防抖；
   - 监听 `window.addEventListener('hashchange')`，在路由改变时重新解析容器并更新可见性；
   - 使用 `ResizeObserver` 或 DOM 变动监听，在媒体库异步加载列表（`renderBrowserList`）或阅读器章节加载后自动触发高度校准。

---

## 4. 文件改动清单

| 文件 | 类型 | 职责说明 |
|---|---|---|
| `server/internal/web/index.html` | 修改 | 在 `.main-content` 尾部添加静态 `.scroll-fab-group` 结构 |
| `server/internal/web/scrollNav.js` | 新建 | 滚动导航控制器（计算、绑定、平滑滚动） |
| `server/internal/web/scrollNav.test.mjs` | 新建 | 单元测试，覆盖显隐算法与容器切换 |
| `server/internal/web/css/components.css` | 修改 | 添加 `.scroll-fab-group` 和 `.scroll-fab-btn` 样式 |
| `server/internal/web/css/views/reader.css` | 修改 | 添加阅读器模式避让与沉浸模式淡出规则 |
| `server/internal/web/app.js` | 修改 | 在启动入口初始化 `initScrollNav()` |

---

## 5. 测试与验证方案

1. **单元测试**：
   - 运行：`cd server/internal/web && node --test`
   - 验证：`scrollNav.test.mjs` 中的 `computeFabVisibility` 各种边界（顶部、中间、底部、内容不足一屏）测试通过，既有 40+ 测试不受影响。
2. **XSS 静态分析**：
   - 运行：`cd tools/xsscheck && go run . ../../server/internal/web`
   - 验证：零违规（无未转义 sink、符合 CSP 要求）。
3. **Go 构建测试**：
   - 运行：`cd server && go test ./...`
   - 验证：embed 资源打包与服务端正常通过。
4. **手动操作验证**：
   - 在媒体共享库进入长列表，观察顶部仅显示置底、滚动后出现置顶、滚到底部置底消失；
   - 点击两枚按钮，验证是否平滑滚动至首尾；
   - 进入小说阅读器，验证避让 footer，以及点击滚动正文区；
   - 切换 7 套主题，验证按钮颜色与悬浮高亮与主题相符。
