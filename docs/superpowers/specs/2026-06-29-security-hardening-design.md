# 安全加固设计（Security Hardening）

- **日期**: 2026-06-29
- **范围**: Go 服务端、Android 客户端、内置 Web 管理器
- **策略**: B — Server/Android 外科补丁 + Web 事件委托重构
- **状态**: 待评审

---

## 1. 背景与动机

项目根 `plan.md` 记录了 2026-06-15 完成的一次三端代码审查（19 项 P0/P1/P2，全部完成）。此后至 06-29 又合入了 Web 拼接视图、视频/目录删除、快速 remux、进度条修复等改动。

本次对三端做了一次全新的深度审查（并行 3 个审查代理，对照 `plan.md` 排除已完成项），共挖出 30+ 个真实问题，横跨安全 / 性能 / 正确性 / 可维护性四类。范围过大无法塞进一份 spec，故按主题拆分。

**本轮只做安全加固**（3 个已确认漏洞 + 1 项附带清理）。性能、竞态、可见 Bug、可维护性等其余审计发现明确留待后续轮次（见第 8 节）。

所有结论已由作者亲自读源码核实（带 `文件:行号` 证据），非审查代理转述。

---

## 2. 目标与非目标

### 目标
1. 封堵服务端"系统媒体端点越权读任意文件"漏洞。
2. 封堵 Android 客户端 ZIP 解压路径穿越（Zip Slip）。
3. 封堵 Web 管理器存储型 XSS 与 inline onclick 路径注入，并移除其根因（inline 事件反模式）。
4. 统一 `system.go` 错误响应风格，不再向客户端回显内部信息。

### 非目标（out of scope，留待后续轮次）
- 性能（Android DataStore 主线程解析、Web 大目录渲染、ffmpeg 并发限流、缩略图缓存回收）
- 并发竞态（Server `GetRoots` race、Android `HomeViewModel` race、Web 请求竞态）
- 可见 Bug（Web `initDashboard` 不存在、Android 转码切换按钮、Android URL 未编码）
- 可维护性（`BrowseViewModel` 拆分、media/system handler 结构统一）
- 引入鉴权 / token 机制（本轮维持现有 CORS 局域网白名单鉴权模型）

---

## 3. 漏洞一：服务端系统媒体端点越权读

### 3.1 根因

`server/internal/service/path.go` 中存在两套系统路径校验：

- `ValidateSystemBrowseAllowed(path, allowedRoots)`（`path.go:118`）：校验路径是否在 `system.allowed_roots` 内。
- `ValidateSystemPath(path, allowedExtensions)`（`path.go:68`）：仅做规范化（去 `..`）、黑名单检查（`checkBlocked`）、扩展名检查，**不校验是否在 `allowed_roots` 内**。

`server/internal/server/handler/system.go` 中四个端点的校验不一致：

| 端点 | 位置 | 调用 | 是否校验 roots |
|---|---|---|---|
| `SystemBrowse` | `system.go:53` | `ValidateSystemBrowseAllowed` + `ValidateSystemBrowsePath` | ✅ |
| `SystemThumbnail` | `system.go:136` | `ValidateSystemPath` | ❌ |
| `SystemOriginal` | `system.go:157` | `ValidateSystemPath` | ❌ |
| `SystemStream` | `system.go:170` | `ValidateSystemPath` | ❌ |

后果：即使配置了 `system.allowed_roots: ["D:/Media"]`，局域网内任意客户端仍可请求 `GET /api/v1/system/original?path=C:/Users/admin/private.jpg`（或 thumbnail / stream）直接获取**不在 allowed_roots 内的任意媒体类型文件**，只要该路径不在黑名单目录（windows/system32 等）且扩展名属媒体类型。这是一个真实的权限边界绕过，可读取用户私人图片/视频。

### 3.2 修复方案

**新增统一入口** `service.ValidateSystemMediaAccess(pathStr string, allowedRoots []string, allowedExtensions []string) error`，位于 `path.go`，内部依次：

1. `ValidateSystemBrowseAllowed(pathStr, allowedRoots)` — 必须在 `allowed_roots` 内（空 roots 时拒绝，返回"system browse is not configured"）。
2. `checkBlocked(absPath)` — 黑名单目录拦截。
3. 扩展名校验（与 `ValidateSystemPath` 现有逻辑相同）。

**改三个 handler**：`SystemThumbnail`/`SystemOriginal`/`SystemStream` 把 `service.ValidateSystemPath(pathStr, h.mediaExtensions())` 替换为 `service.ValidateSystemMediaAccess(pathStr, h.cfg.GetSystemAllowedRoots(), h.mediaExtensions())`。

**语义决策（已定）**：系统媒体端点只接受 `allowed_roots` 内的路径，**不接受 scan roots**。理由：
- 系统端点是"受限系统浏览"功能的配套，其语义本就是"在 allowed_roots 范围内访问"。
- scan roots 内的媒体已由 `/api/v1/images`、`/api/v1/videos`、`/api/v1/media` 端点提供，无需经系统端点。
- 最小权限：系统端点收窄到 allowed_roots 是更严格、更正确的安全姿态。
- `allowed_roots` 为空时，系统缩略图/原图/流与 `SystemBrowse` 一同禁用，符合现有"未配置则系统浏览不可用"的行为。Android 与 Web 的系统浏览功能本就依赖 `allowed_roots`，无回归。

`ValidateSystemPath` 在本轮保留（避免影响面），但三个 handler 不再直接调用它。后续可移除（见第 8 节）。

### 3.3 附带清理：`system.go` 错误响应一致性（漏洞 #4）

`system.go` 多处用裸 `c.JSON(status, map[string]string{"error": err.Error()})`（如 `:54, :57, :63, :65, :133, :137, :155, :158, :167, :171`），与 `media.go` 全量使用 `respondError`/`respondInternalError`/`respondNotFound` 不一致。

虽然这些主要回显用户传入的 path（非服务端内部路径），危害有限，但为统一风格并杜绝任何内部信息外泄，将 `system.go` 中所有错误响应改用 helper：

- 客户端错误（参数缺失、路径不可达、扩展名不符、roots 外）→ `respondError(c, http.StatusBadRequest|Forbidden, "通用消息")`，**不回显 err.Error()**。
- 服务端内部错误 → `respondInternalError(c, err)`（仅写日志，回通用消息）。
- 不存在 → `respondNotFound(c, "...")`。

### 3.4 测试

新增/扩展 `server/internal/service/path_test.go`，覆盖 `ValidateSystemMediaAccess`：

- roots 内的合法媒体文件 → 放行
- roots 外的路径 → 拒绝（含 `C:/Users/...` 越权场景）
- `allowed_roots` 为空 → 拒绝
- 黑名单目录（如 `.../system32/...`）→ 拒绝
- 扩展名非媒体 → 拒绝
- `..` 穿越路径 → 拒绝（规范化后仍在 roots 内才算通过）

---

## 4. 漏洞二：Android ZIP 解压路径穿越（Zip Slip）

### 4.1 根因

`android/app/src/main/java/com/juziss/localmediahub/data/DownloadManager.kt`：

- `downloadFolder`（`:131`）：`val extractedFile = File(destDirectory, zipEntry.name)` 直接用服务端返回的 ZIP 条目名拼接目标路径，无边界校验。`zipEntry.name` 可含 `../`，写入 `destDirectory` 之外。
- `downloadFile`（`:53`）：`File(destDirectory, file.name)` 同理，`file.name` 来自网络。

ZIP 数据与文件名均来自网络（服务端 `downloadFolderZip` 与递归元数据接口）。即使服务端当前可信，客户端解压应做纵深防御；一旦服务端目录被改名含特殊字符、服务端被篡改或中间人介入，可造成任意文件写。

### 4.2 修复方案

在 `DownloadManager.kt` 新增：

- 文件级 `internal` 纯函数 `isInside(destDir: File, candidate: File): Boolean`
  - 返回 `candidate.canonicalPath.startsWith(destDir.canonicalPath + File.separator)`。
  - 取 `internal`（而非 `private`）可见性，使其对 app 模块的 JVM 单测可见，无需 Android 框架即可测试。
- 私有函数 `safeResolveChild(destDir: File, name: String): File?`
  - 解析 `File(destDir, name)`，若 `!isInside(destDir, it)` 返回 `null`。

应用：

- `downloadFolder`（`:131`）：`val extractedFile = safeResolveChild(destDirectory, zipEntry.name) ?: continue`（跳过越界条目并日志记录）。
- `downloadFile`（`:53`）：`val localFile = safeResolveChild(destDirectory, file.name) ?: run { onMessage("非法文件名，已拒绝下载"); return }`。

### 4.3 测试

新增 `android/app/src/test/java/com/juziss/localmediahub/data/DownloadManagerTest.kt`（纯 JVM 单测，不依赖 Android 框架），覆盖 `isInside`（必要时暴露为 `internal` 或提取到独立可测对象）：

- 合法简单文件名 → true
- `../escape.txt` → false
- 嵌套相对 `a/b/../c` → 规范化后判定
- 绝对路径名（如 `C:/x` 或 `/x`）→ false
- 子目录合法名 → true

---

## 5. 漏洞三：Web 管理器存储型 XSS 与路径注入

### 5.1 根因（两个注入向量）

文件：`server/internal/web/app.js`

**(a) 未转义的文件名/路径直接渲染进 HTML**
- `renderBrowserList`：`folder.name`（`:721` title 与正文）、`file.name`（`:782` title 与正文）原样插入。
- `renderLightboxImage` 拼接视图：`alt="${file.name}"`（`:960`）、caption `${file.name}`（`:961`）原样插入。

`loadRoots` 用了 `escapeHtml`，但上述位置未用。文件名在文件系统上允许包含 `<`、`"`、`'` → 一个名为 `<img src=x onerror=alert(1)>.jpg` 的文件被浏览时即执行脚本。Web 管理器拥有改配置与删除文件的权限，存储型 XSS 危害高。

**(b) inline `onclick` 路径注入**
- `browsePath('${path.replace(/\\/g, '/')}')`（`:625, :652, :711, :720, :819, :846`）：单引号包裹路径且零转义，路径含 `'` 闭合 JS 字符串 → 注入或至少点击失效。
- 同模式：`deleteTag('...', '...')`（`:1089`）、`toggleFileTagAssociation(this, '...', '...')`（`:1024`）。
- `openMedia(${JSON.stringify(file).replace(/"/g, '&quot;')})`（`:770, :781`）等 `JSON.stringify().replace()` idiom 本身相对安全，但与上述同属 inline onclick 反模式，是脆弱性根因。

底部 `app.js:1236-1244` 有 9 个 `window.xxx` 全局挂载专门为这些 inline handler 服务。

### 5.2 修复方案（治本：事件委托 + dataset + 索引引用）

**原则**：移除所有 inline `onclick`/`onchange`，改为统一的委托事件监听器；用户/网络可控数据通过 `data-*` 属性或数组索引传递，不再序列化进 HTML 属性。

**(1) 对象引用改用数组索引（消除 `JSON.stringify().replace()` 模式）**

卡片渲染时把整个 `file`/`folder` 对象改为索引：
- 文件卡：`data-action="open" data-index="3"`，点击时 `openMedia(state.currentFiles[3])`。
- 文件夹卡：`data-action="browse-folder" data-index="..."` / 删除 `data-action="delete-folder" data-index="..."` / 标签 `data-action="tag" data-index="..."`。
- 仪表盘最近项：需把 recent 列表存入 `state`（如 `state.dashboardRecentFiles`），用 `data-index` 引用。

约束（实现须保证）：渲染列表顺序与对应 `state.*` 数组一致；每次重新渲染前/后数组与索引同步。重渲染时整体替换，不存在陈旧索引。

**(2) 路径改用转义的 data 属性（消除单引号注入）**
- `data-action="browse" data-path="${escapeHtml(path)}"`：`escapeHtml` 转义 `"` 防属性 breakout。面包屑、根目录卡、磁盘卡、搜索结果卡统一此模式。
- 点击时通过 `el.dataset.path` 读取——浏览器读取 `dataset` 时已自动反转义 HTML 实体，直接得到原始路径，无需手动 decode。

**(3) 统一委托监听器**（替换全部 inline handler）

| 容器 | 事件 | 分发的 action |
|---|---|---|
| `browserList` | click | `browse-folder` / `open` / `tag` / `delete-folder` / `delete-file`（均按 `data-index`） |
| `browserBreadcrumbs` | click | `crumb`（按 `data-path`，含 `loadRoots` 回根） |
| `dashboardRecent` | click | 打开视频（按 `data-index`） |
| `tagsManagerList` | click | `delete-tag`（按 `data-id`） |
| `tagSelectorCheckboxes` | change | 切换关联（按 `data-tag-id` + `data-file-index`） |

实现要点：监听器用 `e.target.closest('[data-action]')` 找到动作元素再分发；在 `setupEventListeners` 中一次性绑定。

**(4) 所有 name/path 渲染统一 `escapeHtml`**：消除向量 (a)。包括 `folder.name`、`file.name`、拼接视图的 `alt` 与 caption、tag 名称等。

**(5) 移除 `app.js:1236-1244` 的 9 个 `window.xxx` 全局挂载**（委托后不再需要）。

**(6) 顺带（本轮纳入）**：缩略图失败回退的 inline `onerror`（`:747, :756`）层层转义、可读性差，改为 CSS 占位图标 + `<img>` 失败时隐藏（`addEventListener('error', ...)` 或委托），与事件委托重构一并完成，降低后续维护风险。

### 5.3 验证（无自动化测试，手工浏览器回归）

回归清单：
- 浏览根目录 / 磁盘驱动器 / 子目录、面包屑逐级跳转、返回上级。
- 打开视频（含转码切换）、打开图片（单张 + 拼接模式）、左右切换。
- 给文件打标签 / 解除关联、删除文件、删除文件夹、删除标签定义。
- 仪表盘最近项点击打开。

**XSS 专项验证**：
- 在服务端媒体目录放置名字含 `<img src=x onerror=alert(1)>` 与含 `'`、`"`、空格、中文的测试文件，确认浏览时**不执行脚本**、点击行为正常。
- 含 `'` 的目录名能正常进入浏览（修复前会因 inline onclick 语法错误而失效）。

---

## 6. 实现顺序与提交策略

三端改动互不耦合，建议分别提交、分别回归：

1. **Server**（漏洞一 + 清理 #4）：`path.go` 新增 `ValidateSystemMediaAccess` → 改 3 个 handler → 改 `system.go` 错误响应 → 补 `path_test.go` → `go test ./...` → 手工验证 roots 外 403。
2. **Android**（漏洞二）：`DownloadManager.kt` 加 `isInside`/`safeResolveChild` → 改两处解压 → 补 `DownloadManagerTest` → `./gradlew testDebugUnitTest assembleDebug`。
3. **Web**（漏洞三）：事件委托重构 + 统一 escape + 移除全局挂载 + onerror 改造 → 浏览器手工回归（含 XSS 专项）。

每端可独立交付，互不阻塞。

---

## 7. 验证与回归总表

| 端 | 自动化 | 手工 |
|---|---|---|
| Server | `go test ./...`（含新增 path 测试） | 起服务，roots 外的系统 thumbnail/original/stream 返回 403；roots 内正常 |
| Android | `./gradlew testDebugUnitTest assembleDebug`（含新增 DownloadManagerTest） | — |
| Web | （无自动化） | 浏览器回归清单 + 恶意文件名/路径名 XSS 专项 |

---

## 8. 已决策点

| 决策 | 选择 | 理由 |
|---|---|---|
| 系统端点接受的 roots 范围 | 仅 `allowed_roots`（不含 scan roots） | 最小权限；scan roots 媒体已有专用端点；与 SystemBrowse 语义一致 |
| Web XSS 修复方式 | 事件委托重构（非仅 escapeHtml 补丁） | 治本，消除 inline onclick 反模式与 `JSON.stringify().replace()` 脆弱性 |
| 缩略图 onerror 回退改造 | 本轮纳入 | 与事件委托重构同处代码、风险低、提升可维护性 |
| Web 对象引用方式 | 数组索引（非序列化对象） | 天然无 XSS，消除脆弱 idiom |
| 范围 | 仅安全，性能/竞态/Bug/重构留后续 | 避免范围爆炸，便于分别评审与回归 |

---

## 9. 后续轮次（不在本 spec，仅备忘）

按主题各开一份 spec：

- **性能**：Android DataStore 主线程解析（`flowOn(IO)` + 单次解码派生）、Web 大目录虚拟滚动/懒加载、Server ffmpeg 并发信号量、缩略图缓存 LRU + 删除联动。
- **并发竞态**：Server `GetRoots` 用 `atomic.Pointer`/`sync.Mutex` 替换 `sync.Once` 重置、Android `HomeViewModel` 用 `combine` 派生、Web 请求 token/AbortController。
- **可见 Bug**：Web `initDashboard` → `renderDashboard`、Android 转码切换按钮翻转 `isTranscodingEnabled`、Android 相对路径 URL 百分号编码。
- **可维护性**：`BrowseViewModel` 拆分、media/system handler 统一、移除空占位 `root.go`、补 streaming/thumbnail/media 测试。
