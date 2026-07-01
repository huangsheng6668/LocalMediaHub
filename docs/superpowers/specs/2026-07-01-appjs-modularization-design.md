# app.js 模块化设计（Web Architecture · Round 7）

- **日期**: 2026-07-01
- **范围**: 内置 Web 管理器前端（`server/internal/web/app.js` 拆分 + 新增 7 个特性模块）
- **策略**: A — 全量拆分为特性模块
- **状态**: 待评审

---

## 1. 背景与动机

`server/internal/web/app.js` 是 ~1320 行的单文件，承载了仪表盘、媒体浏览、视频播放器、图片灯箱（含拼接视图）、标签管理、设置、删除、搜索等所有 Web 管理器逻辑。round-1 的 XSS/事件委托重构后它持续膨胀。继 round-5 拆分 `BrowseViewModel`（Android 端最大文件债）之后，`app.js` 是项目里最大的剩余文件债——难读、难改、改一处易意外波及无关功能。

既有前端已是 ES 模块结构（`state.js`/`api.js`/`toast.js`/`router.js`），但 `app.js` 自身未拆。本轮把它拆成内聚的特性模块（行为完全不变，纯重构）。

---

## 2. 目标与非目标

### 目标
1. 把 `app.js` 拆成 7 个特性模块（`utils`/`browserView`/`tagsView`/`lightbox`/`videoPlayer`/`dashboard`/`settings`）+ `delete` 模块，`app.js` 只留 `initApp`/编排。
2. 行为完全不变（Web UI 所有交互一致）。
3. 无循环依赖。
4. 增量提交（一模块一提交），每步保持 App 可用。

### 非目标（留待后续轮次）
- 新增功能、改变 Web UI 行为或样式。
- CSS 响应式 `@media`（另一轮）。
- 服务端 / Android 改动。
- 单元测试（Web 端无测试框架；靠浏览器手工回归）。

---

## 3. 模块划分

| 新模块 | 从 `app.js` 迁出的函数 | 备注 |
|--------|------------------------|------|
| `utils.js` | `formatSize`/`formatTime`/`encodeRoutePath`/`safeBtoa` | 纯函数，零风险，最先迁 |
| `settings.js` | `loadConfig`/`renderSettings` | 自包含叶子 |
| `dashboard.js` | `renderDashboard`/`onDashboardRecentClick` | 自包含叶子 |
| `tagsView.js` | `loadTags`/`openTaggingDialog`/`toggleFileTagAssociation`/`renderTagsManager`/`onTagsManagerListClick`/`onTagSelectorChange`/`deleteTag` + 标签监听器 | 自包含叶子 |
| `delete.js` | `deleteMediaFile`/`deleteFolder` | 跨 browserView/videoPlayer 共用；独立成模块避免循环依赖 |
| `videoPlayer.js` | `openVideoPlayer` + 现嵌在 `setupEventListeners` 内的播放器闭包（`togglePlayPause`/`seekTo`/`resetControlsTimer`）+ 播放器监听器 | 最棘手（闭包/状态）；最后迁 |
| `lightbox.js` | `openMedia`（video/image 分发）/`openImageLightbox`/`renderLightboxImage`/`navigateLightbox` + 灯箱监听器 | `openMedia` 是 2 行分发器，放此处 |
| `browserView.js` | `loadRoots`/`loadSystemDrives`/`browsePath`/`renderBrowserList`/`onBrowserListClick`/`renderBreadcrumbs`/`onBreadcrumbsClick`/`triggerBrowserSearch` + 浏览/面包屑监听器 | 最大的特性块 |
| `app.js`（保留） | `initApp` + `setupEventListeners`（编排：import 各模块 + 调各 `setupXxxListeners()`）+ 顶层 bootstrap | ~150 行 |

> `escapeHtml` 仍从 `api.js` import（已在、广用），不挪到 utils。

---

## 4. 交互模式

- **ES 模块**：每个新模块 `export` 自己的公开函数；共享依赖（`state`/`apiRequest`/`escapeHtml`/`showToast`/`handleRoute`）由各模块直接 import 既有 `state.js`/`api.js`/`toast.js`/`router.js`。
- **依赖图（无环）**：
  - 叶子（只依赖 state/api/toast/utils）：`utils`、`settings`、`tagsView`、`delete`
  - `videoPlayer` → `delete`（删除按钮）、utils
  - `lightbox` → `videoPlayer`（`openMedia` 分发到 `openVideoPlayer`）、utils
  - `browserView` → `lightbox`（`openMedia`）、`tagsView`（`openTaggingDialog`）、`delete`、utils
  - `dashboard` → `lightbox`（`openMedia`）、utils
  - `app.js` → 全部（编排）
  - 无反向边（`videoPlayer` 不 import `lightbox`；`delete`/`tagsView` 不 import `browserView`）→ **无循环依赖**。
- **监听器接线**：每个特性模块导出 `setupXxxListeners()`（注册该模块 DOM 元素的委托监听器，沿用 round-1 的事件委托模式）；`app.js` 的 `setupEventListeners` 改为依次调用各模块的 setup 函数。app.js 仍是编排入口。
- **videoPlayer 闭包**：现嵌在 `setupEventListeners` 内、捕获播放器 DOM/状态的 `togglePlayPause`/`seekTo`/`resetControlsTimer` → 迁到 `videoPlayer.js` 作**模块作用域**状态 + 函数；`setupVideoPlayerListeners()` 注册视频模态框的监听器。

---

## 5. 实现顺序（增量提交）

每步迁一个模块、删 app.js 中对应代码、app.js import 新模块、`go build`（嵌入资源）通过 + 浏览器冒烟。顺序按风险递增：

1. `utils.js`（纯函数，零风险）
2. `settings.js` / `dashboard.js`（自包含叶子）
3. `tagsView.js`
4. `delete.js`
5. `lightbox.js`
6. `browserView.js`
7. `videoPlayer.js`（闭包/状态，最棘手，最后）
8. 收缩 `app.js`（移除已迁代码，`setupEventListeners` 改为调各 setup 函数）

---

## 6. 验证（浏览器手工回归）

> chrome-devtools MCP 之前连接超时，需**手工浏览器回归**。每提交后冒烟，全量后完整回归。

冒烟（每提交）：起服务（`go run ./cmd/server`），打开 Web 管理器，确认无 `Uncaught` 控制台错误、主交互（浏览一层 + 开一个视频/图片）正常。

完整回归清单（全量拆分后）：
- 仪表盘 + 最近项点击打开视频。
- 媒体浏览：根目录卡 / 磁盘卡 / 子目录 / 面包屑逐级回跳 / 搜索。
- 文件卡点击：视频弹播放器、图片弹灯箱。
- 视频播放器：播放/暂停、进度条拖动、转码切换、播放器内删除、关闭。
- 图片灯箱：单张 + 拼接视图切换、左右导航、Esc 关闭。
- 标签：文件卡 🏷️ 打开对话框 → 勾选/取消关联、卡片色点更新；标签管理 → 新建/删除标签。
- 设置页加载 + 保存。
- 缩略图失败回退占位图标。
- DevTools Console 全程无 `ReferenceError` / 未捕获异常。

---

## 7. 已决策点

| 决策 | 选择 | 理由 |
|---|---|---|
| 方案 | A（全量拆分 7 特性模块） | 最大债削减；增量提交可独立验证/回滚 |
| 监听器接线 | 各模块导出 `setupXxxListeners()`，app.js 调 | 显式编排，app.js 仍是入口，无 import 副作用 |
| `delete` 独立成模块 | 是 | 避免 `browserView`↔`videoPlayer` 循环依赖 |
| `openMedia` 分发器位置 | `lightbox.js` | 灯箱已处理图片打开；2 行分发到 videoPlayer，单向依赖 |
| `escapeHtml` | 留 `api.js` | 已在、广用，不挪 utils |
| videoPlayer 闭包 | 迁为模块作用域状态/函数 | 解耦自 setupEventListeners，可独立测试/演进 |
| 行为 | 完全不变（纯重构） | 仅可维护性，无功能/样式变化 |

---

## 8. 后续轮次（不在本 spec，仅备忘）

- **Web**：`style.css` 响应式 `@media`（窄屏可用）、dashboard 冗余请求、stitch scroll 节流。
- **Android**：旋转屏 `rememberSaveable`、ExoPlayer 进程保留、OkHttp/Coil 网络缓存、`RetrofitClient` Hilt 可注入。
- **服务端**：singleflight key、Scanner 防御性拷贝、streaming Range 测试。
