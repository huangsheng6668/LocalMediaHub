# app.js 模块化（Round 7）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 ~1320 行的 `app.js` 拆成 7 个特性模块 + delete 模块，app.js 只留 init/编排——纯重构、行为不变。

**Architecture:** 增量提取（一模块一提交）。每个任务：新建模块文件（imports + `export` 列出的函数，函数体从 app.js **原样搬移**）→ 从 app.js 删除这些函数 + 加 import → 把该模块的 `addEventListener` 调用从 `setupEventListeners` 抽到模块的 `setupXxxListeners()` 并在 app.js 调用 → `go build`（重新 embed web 资源）+ 浏览器冒烟。

**Tech Stack:** 原生 ES Modules（无构建）；Go `go:embed` 嵌入 web 资源。

## Global Constraints

- **提交策略**（`AGENTS.md`）：本地改动自动同步推送至 GitHub `master`。所有提交直接在 `master`，**不开 feature 分支**；conventional commit + `Co-Authored-By: Claude <noreply@anthropic.com>` 尾注。
- **Web 无构建**：直接编辑 `server/internal/web/*.js`，无打包/转译。
- **嵌入资源**：`server/internal/web/` 经 `go:embed` 嵌入；改 `.js` 后需 `cd server && go build ./...` 重新 embed，浏览器才看到新代码。
- **行为约束**：**纯重构、Web UI 行为完全不变**；沿用 round-1 的事件委托模式（`data-action`/`closest`）；`escapeHtml` 仍从 `api.js` import。
- **无自动化测试**：每个任务靠 `go build` + 浏览器手工冒烟（起服务、打开管理器、点一层 + 开一个视频/图片、控制台无错误）。
- **范围外**：新增功能、改 CSS/行为、服务端/Android。

## File Structure

- 新增 `server/internal/web/{utils,settings,dashboard,tagsView,delete,lightbox,browserView,videoPlayer}.js`。
- 修改 `server/internal/web/app.js`：逐步删除已迁函数、加 import、`setupEventListeners` 改为调各 `setupXxxListeners()`。

---

## Task 1: `utils.js`（纯函数，零风险）

**Files:**
- Create: `server/internal/web/utils.js`
- Modify: `server/internal/web/app.js`

**Interfaces:**
- Produces: `formatSize(bytes)`、`formatTime(seconds)`、`encodeRoutePath(path)`、`safeBtoa(str)`（均 `export function`）。

- [ ] **Step 1: 新建 `utils.js`**

新建 `server/internal/web/utils.js`，把 app.js 中的 `formatSize`（`:1209`）、`encodeRoutePath`（`:1218`）、`safeBtoa`（`:1224`）、`formatTime`（`:1233`）四个函数**原样搬过来**，每个加 `export`：

```js
// 纯工具函数，从 app.js 抽出。

export function formatSize(bytes) {
    // …原样搬自 app.js:1209…
}

export function encodeRoutePath(path) {
    // …原样搬自 app.js:1218…
}

export function safeBtoa(str) {
    // …原样搬自 app.js:1224…
}

export function formatTime(seconds) {
    // …原样搬自 app.js:1233…
}
```

- [ ] **Step 2: 改 `app.js`**

- 删除 app.js 中这 4 个函数的定义（`formatSize`/`encodeRoutePath`/`safeBtoa`/`formatTime`）。
- 在 app.js 顶部 import 块加：

```js
import { formatSize, formatTime, encodeRoutePath, safeBtoa } from './utils.js';
```

（app.js 内对这些函数的既有调用不变——现在走 import。）

- [ ] **Step 3: 构建 + 冒烟**

Run: `cd server && go build ./...`
Expected: 编译通过。起服务（`go run ./cmd/server`）→ 浏览器打开管理器 → 文件卡显示大小（`formatSize`）、视频时间（`formatTime`）正常；控制台无 `ReferenceError`。

- [ ] **Step 4: 提交**

```bash
git add server/internal/web/utils.js server/internal/web/app.js
git commit -m "refactor(web): extract utils module from app.js

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 2: `settings.js` + `dashboard.js`（小叶子）

**Files:**
- Create: `server/internal/web/settings.js`、`server/internal/web/dashboard.js`
- Modify: `server/internal/web/app.js`

**Interfaces:**
- Produces: settings: `loadConfig()`、`renderSettings()`、`setupSettingsListeners()`；dashboard: `renderDashboard()`、`onDashboardRecentClick(e)`、`setupDashboardListeners()`。

- [ ] **Step 1: 新建 `settings.js`**

```js
import { state } from './state.js';
import { apiRequest } from './api.js';

// loadConfig / renderSettings 原样搬自 app.js（loadConfig :542, renderSettings :1199）
export async function loadConfig() { /* …原样… */ }
export function renderSettings() { /* …原样… */ }

export function setupSettingsListeners() {
    // 从 app.js setupEventListeners 搬来这两段：
    //   elements.btnTriggerScan.addEventListener('click', …)   (原 :119)
    //   elements.btnSaveSettings.addEventListener('click', …)  (原 :163)
}
```

（`elements` 从 `app.js` 传入或由 settings.js 自行 `document.getElementById`；若用全局 `elements`，需把它也导出/共享——**采用：settings.js 内自行用 `document.getElementById` 取这两个按钮**，避免依赖 app.js 的 `elements` 对象。）

- [ ] **Step 2: 新建 `dashboard.js`**

```js
import { state } from './state.js';
import { apiRequest, escapeHtml } from './api.js';
import { formatTime } from './utils.js';
import { openMedia } from './lightbox.js';

// renderDashboard(:589) / onDashboardRecentClick(:579) 原样搬来
export async function renderDashboard() { /* …原样… */ }
export function onDashboardRecentClick(e) { /* …原样… */ }

export function setupDashboardListeners() {
    // 搬来：elements.dashboardRecent.addEventListener('click', onDashboardRecentClick) (原 :194)
    //       分页 dot click (原 :130)
}
```

> dashboard 依赖 `openMedia`（lightbox，Task 5 才建）。**为避免顺序耦合，Task 2 的 dashboard.js 暂用动态 import 或把 `openMedia` 调用留到 Task 5 后再接**——更简单：Task 2 只建 settings.js，dashboard.js 放到 Task 5（lightbox）之后。**调整顺序：Task 2 = settings only；dashboard 随 lightbox 任务一起做。**

- [ ] **Step 3: 改 `app.js`（仅 settings 部分）**

- 删 app.js 中 `loadConfig`/`renderSettings`。
- 加 `import { loadConfig, renderSettings, setupSettingsListeners } from './settings.js';`。
- `setupEventListeners` 里删掉 btnTriggerScan/btnSaveSettings 两段，改为调 `setupSettingsListeners()`。
- `initApp`/路由中对 `loadConfig`/`renderSettings` 的调用不变（走 import）。

- [ ] **Step 4: 构建 + 冒烟 + 提交**

Run: `cd server && go build ./...`；冒烟（设置页加载、点"扫描"按钮、保存设置）。
```bash
git add server/internal/web/settings.js server/internal/web/app.js
git commit -m "refactor(web): extract settings module from app.js

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 3: `tagsView.js`

**Files:**
- Create: `server/internal/web/tagsView.js`
- Modify: `server/internal/web/app.js`

**Interfaces:**
- Produces: `loadTags()`、`openTaggingDialog(file)`、`toggleFileTagAssociation(checkbox, tagId, filePath)`、`renderTagsManager()`、`onTagsManagerListClick(e)`、`onTagSelectorChange(e)`、`deleteTag(tagId, name)`、`setupTagsListeners()`。

- [ ] **Step 1: 新建 `tagsView.js`**

```js
import { state } from './state.js';
import { apiRequest, escapeHtml } from './api.js';
import { showToast } from './toast.js';

// 原样搬自 app.js：
export async function loadTags() { /* :560 */ }
export function openTaggingDialog(file) { /* :1076 */ }
export async function toggleFileTagAssociation(checkbox, tagId, filePath) { /* :1104 */ }
export function onTagsManagerListClick(e) { /* :1145 */ }
export function onTagSelectorChange(e) { /* :1154 */ }
export function renderTagsManager() { /* :1162 */ }
export async function deleteTag(tagId, name) { /* :1183 */ }

export function setupTagsListeners() {
    // 搬来：btnCreateTag click (:137)、tagsManagerList click (:197)、
    //       tagSelectorCheckboxes change (:200)、btnCloseFileTagsModal click (:533)
}
```

（`elements` 同 Task 2——模块内自行 `document.getElementById`，或把 `elements` 提到共享模块；**本计划统一：各模块自行用 `document.getElementById` 取自己负责的元素**。）

- [ ] **Step 2: 改 `app.js`**

删上述 7 个函数；加 import；`setupEventListeners` 删对应 4 段、改调 `setupTagsListeners()`。

- [ ] **Step 3: 构建 + 冒烟（标签对话框/管理/删除/勾选）+ 提交**

```bash
git add server/internal/web/tagsView.js server/internal/web/app.js
git commit -m "refactor(web): extract tagsView module from app.js

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 4: `delete.js`

**Files:**
- Create: `server/internal/web/delete.js`
- Modify: `server/internal/web/app.js`

**Interfaces:**
- Produces: `deleteMediaFile(file)`、`deleteFolder(folder)`。

- [ ] **Step 1: 新建 `delete.js`**

```js
import { state } from './state.js';
import { apiRequest } from './api.js';
import { showToast } from './toast.js';

export async function deleteMediaFile(file) { /* 原样 :1249 */ }
export async function deleteFolder(folder) { /* 原样 :1288 */ }
```

（delete 无独立监听器——由 browserView/videoPlayer 的按钮回调调用。）

- [ ] **Step 2: 改 `app.js` + 提交**

删两函数；加 import。`browserView`/`videoPlayer` 对它们的调用在各自任务里改 import 来源（此时仍在 app.js 作用域——Task 6/7 时改）。
```bash
git add server/internal/web/delete.js server/internal/web/app.js
git commit -m "refactor(web): extract delete module from app.js

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### 依赖顺序说明（lightbox 依赖 videoPlayer）

**Files:**
- Create: `server/internal/web/lightbox.js`、`server/internal/web/dashboard.js`
- Modify: `server/internal/web/app.js`

**Interfaces:**
- Produces: lightbox: `openMedia(file)`、`openImageLightbox(file)`、`renderLightboxImage()`、`navigateLightbox(dir)`、`setupLightboxListeners()`；dashboard: `renderDashboard()`、`onDashboardRecentClick(e)`、`setupDashboardListeners()`。

- [ ] **Step 1: 新建 `lightbox.js`**

```js
import { state } from './state.js';
import { escapeHtml } from './api.js';
import { openVideoPlayer } from './videoPlayer.js'; // videoPlayer 此时还未建（Task 7）
```

> **顺序问题**：lightbox→videoPlayer，但 videoPlayer 是 Task 7。**解法**：Task 5 先建 lightbox，`openMedia` 里对 video 的分发**暂留为调用 app.js 作用域的 `openVideoPlayer`**（即 lightbox 先不 import videoPlayer，`openMedia` 的 video 分支调一个 Task 7 时再接的引用）。更干净：**把 Task 5 与 Task 7 顺序对调**——先 videoPlayer（Task 5′），再 lightbox（Task 7′）。但 videoPlayer 最棘手、不宜先做。
>
> **最终采用**：lightbox 的 `openMedia` 分发到 video 时，**从 `app.js` 动态拿** `openVideoPlayer`（Task 7 后改为直接 import）。即 Task 5 lightbox.js 顶部 `import { openVideoPlayer } from './videoPlayer.js'`，**Task 5 同时建一个最小的 `videoPlayer.js` 占位**（只 `export function openVideoPlayer(file){}` 空实现）？——不行，会丢功能。
>
> **最简方案**：Task 5 建 lightbox.js（`openMedia` 的 video 分支调 `openVideoPlayer`），**`openVideoPlayer` 此时仍在 app.js**；lightbox 通过 `import { openVideoPlayer } from './app.js'` 引用。但 app.js 是入口、不 export。**所以 Task 5 暂把 `openVideoPlayer` 留在 app.js，lightbox 的 video 分支调一个由 app.js 注入的回调**（`state.openVideoPlayer = openVideoPlayer`）。Task 7 提取 videoPlayer 时改 lightbox 直接 import。
>
> 这太绕。**重排顺序最干净**：Task 5 = videoPlayer，Task 7 = lightbox。即 videoPlayer 先（虽有闭包，但可做），lightbox 后（可直接 import videoPlayer）。**采用此顺序**——见下方 Task 5（videoPlayer）。

（本 Task 5 改为 videoPlayer——见下。）

---

## Task 5: `videoPlayer.js`（最棘手）

**Files:**
- Create: `server/internal/web/videoPlayer.js`
- Modify: `server/internal/web/app.js`

**Interfaces:**
- Produces: `openVideoPlayer(file)`、`setupVideoPlayerListeners()`。

- [ ] **Step 1: 新建 `videoPlayer.js`**

把 app.js 中 `openVideoPlayer`（`:931`）+ 嵌在 `setupEventListeners` 内的播放器闭包（`togglePlayPause` `:292`、`seekTo` `:441`、`resetControlsTimer` `:450`）+ 所有视频相关 `addEventListener`（`:218-460`：btnCloseVideoModal/btnVideoDelete/btnVideoTranscode/videoPlayer loadedmetadata·play·pause·timeupdate·durationchange/videoProgress input·change/videoVolume/btnVideoMute/btnVideoFullscreen/keydown/mousemove）搬入 `videoPlayer.js` 作**模块作用域**状态（`let currentTime`/`let duration`/`let controlsTimer` 等）+ 函数：

```js
import { state } from './state.js';
import { apiRequest } from './api.js';
import { showToast } from './toast.js';
import { formatTime } from './utils.js';
import { deleteMediaFile } from './delete.js';

// 模块作用域播放器状态（原 setupEventListeners 内的局部变量）
let currentTime = 0, duration = 0, controlsTimer = null;

export function openVideoPlayer(file) { /* 原样 :931 */ }

function togglePlayPause() { /* 原样 :292 */ }
function seekTo(targetTime) { /* 原样 :441 */ }
function resetControlsTimer() { /* 原样 :450 */ }

export function setupVideoPlayerListeners() {
    // 搬来 app.js setupEventListeners 的 :218-460 全部视频监听器
}
```

- [ ] **Step 2: 改 `app.js`**

删 `openVideoPlayer`；`setupEventListeners` 删 `:218-460` 视频段，改调 `setupVideoPlayerListeners()`；加 `import { openVideoPlayer, setupVideoPlayerListeners } from './videoPlayer.js';`。

- [ ] **Step 3: 构建 + 冒烟（视频播放/暂停/进度/转码/删除/全屏/音量）+ 提交**

```bash
git add server/internal/web/videoPlayer.js server/internal/web/app.js
git commit -m "refactor(web): extract videoPlayer module from app.js

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 6: `lightbox.js` + `dashboard.js`

**Files:**
- Create: `server/internal/web/lightbox.js`、`server/internal/web/dashboard.js`
- Modify: `server/internal/web/app.js`

- [ ] **Step 1: 新建 `lightbox.js`**（videoPlayer 已在，可直接 import）

```js
import { state } from './state.js';
import { escapeHtml } from './api.js';
import { openVideoPlayer } from './videoPlayer.js';

export function openMedia(file) { /* 原样 :922 — video 分发到 openVideoPlayer，image 到 openImageLightbox */ }
export function openImageLightbox(file) { /* 原样 :989 */ }
export function renderLightboxImage() { /* 原样 :1003 */ }
export function navigateLightbox(dir) { /* 原样 :1064 */ }

export function setupLightboxListeners() {
    // 搬来 app.js setupEventListeners 的 :463-531（btnCloseImageModal/btnImagePrev/Next/btnImageModeToggle/lightboxStitchView scroll/keydown）
}
```

- [ ] **Step 2: 新建 `dashboard.js`**（openMedia 已在 lightbox）

```js
import { state } from './state.js';
import { apiRequest, escapeHtml } from './api.js';
import { formatTime } from './utils.js';
import { openMedia } from './lightbox.js';

export async function renderDashboard() { /* 原样 :589 */ }
export function onDashboardRecentClick(e) { /* 原样 :579 */ }

export function setupDashboardListeners() {
    // 搬来 dashboardRecent click (:194) + 分页 dot click (:130)
}
```

- [ ] **Step 3: 改 `app.js`**

删 `openMedia`/`openImageLightbox`/`renderLightboxImage`/`navigateLightbox`/`renderDashboard`/`onDashboardRecentClick`；`setupEventListeners` 删对应监听器段、改调 `setupLightboxListeners()` + `setupDashboardListeners()`；加 import。

- [ ] **Step 4: 构建 + 冒烟（灯箱单张/拼接/导航/Esc + 仪表盘/最近项）+ 提交**

```bash
git add server/internal/web/lightbox.js server/internal/web/dashboard.js server/internal/web/app.js
git commit -m "refactor(web): extract lightbox + dashboard modules from app.js

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 7: `browserView.js` + 收缩 `app.js`

**Files:**
- Create: `server/internal/web/browserView.js`
- Modify: `server/internal/web/app.js`

- [ ] **Step 1: 新建 `browserView.js`**

```js
import { state } from './state.js';
import { apiRequest, escapeHtml } from './api.js';
import { handleRoute } from './router.js';
import { formatSize, encodeRoutePath, safeBtoa } from './utils.js';
import { openMedia } from './lightbox.js';
import { openTaggingDialog } from './tagsView.js';
import { deleteMediaFile, deleteFolder } from './delete.js';

// 原样搬来：
export async function loadRoots() { /* :642 */ }
export async function loadSystemDrives() { /* :684 */ }
export async function browsePath(path) { /* :718 */ }
export function onBrowserListClick(e) { /* :743 */ }
export function renderBrowserList() { /* :763 */ }
export function onBreadcrumbsClick(e) { /* :856 */ }
export function renderBreadcrumbs(path) { /* :868 */ }
export async function triggerBrowserSearch() { /* :894 */ }

export function setupBrowserListeners() {
    // 搬来：btnBrowserSearch click (:182)、browserSearchInput keydown (:183)、
    //       browserList click (:188)、browserBreadcrumbs click (:191)、browserList error (:203)
}
```

- [ ] **Step 2: 改 `app.js`（最终收缩）**

删上述 8 函数；`setupEventListeners` 删对应 5 段、改调 `setupBrowserListeners()`；加 import。此时 app.js 只剩：imports、`initApp`、`setupEventListeners`（调各 `setupXxxListeners()`）、`document.addEventListener('DOMContentLoaded', …)`、`window.addEventListener('hashchange', …)`（顶层 bootstrap，`:88`/`:112`）。

- [ ] **Step 3: 构建 + 完整浏览器回归 + 提交**

Run: `cd server && go build ./...`；完整回归清单（spec §6）：仪表盘/最近、浏览（根/磁盘/子目录/面包屑/搜索）、文件卡点击（视频/图片）、视频播放器全功能、灯箱（单张/拼接/导航/Esc）、标签（对话框/管理/删除）、设置、缩略图回退；DevTools Console 全程无 `ReferenceError`/未捕获异常。

```bash
git add server/internal/web/browserView.js server/internal/web/app.js
git commit -m "refactor(web): extract browserView module; app.js is now init/orchestration only

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Self-Review（作者已执行）

**1. Spec 覆盖**：
- §3 的 7 特性模块 + delete + app.js 收缩 → Task 1(utils)/2(settings)/3(tagsView)/4(delete)/5(videoPlayer)/6(lightbox+dashboard)/7(browserView)。✅
- §4 交互模式（ES 模块、无环依赖图、setupXxxListeners、videoPlayer 模块作用域闭包）→ 各任务落地。✅
- §5 增量顺序 → Task 1→7（**重排**：videoPlayer 提前到 Task 5 以解 lightbox→videoPlayer 顺序耦合，见 Task 5 说明）。✅
- §6 验证（go build + 浏览器回归）→ 各任务冒烟 + Task 7 完整回归。✅
- §7 决策（setupXxxListeners、delete 独立、openMedia 在 lightbox、escapeHtml 留 api.js、videoPlayer 模块作用域）→ 各任务落地。✅

**2. 占位符扫描**：函数体标注"原样搬自 app.js:行号"——这是精确的 move 指令（非 vague placeholder）；模块 skeleton（imports + export 签名）是具体代码；监听器搬移给了确切行号。无 TBD/TODO。✅

**3. 类型/签名一致性**：
- 各模块 `export` 的函数名与 app.js 既有调用一致（`openVideoPlayer`/`openMedia`/`renderBrowserList`/`loadTags`/`deleteMediaFile` 等）。
- 依赖图：utils←all、delete←{videoPlayer,browserView}、videoPlayer←{lightbox}、tagsView←{browserView}、lightbox←{browserView,dashboard}、settings/dashboard/browserView←{app.js}——**无环**（videoPlayer 提前到 Task 5 使 lightbox 可直接 import videoPlayer）。✅
- `setupXxxListeners` 命名一致（settings/tags/browser/videoPlayer/lightbox/dashboard）。✅
- Task 顺序调整（videoPlayer Task 5、lightbox+dashboard Task 6）已在 Task 5 说明里讲清理由。✅
