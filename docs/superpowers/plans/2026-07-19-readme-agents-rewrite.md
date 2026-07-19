# README + AGENTS + docs/INDEX 三件套重写实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 根据 spec `docs/superpowers/specs/2026-07-19-readme-agents-rewrite-design.md`，重写 `README.md`、`AGENTS.md`，新建 `docs/INDEX.md`，覆盖图书阅读器、Bearer Token、书签、性能优化等新功能。

**Architecture:** 三件套分工：README 面向人（产品介绍+快速上手）、AGENTS 面向 AI/贡献者（模块地图+命令+规则+安全）、INDEX 是按主题组织的参考库（API+文件+spec）。先建 INDEX（无依赖），再并行重写 README 和 AGENTS（指针指向 INDEX）。

**Tech Stack:** Markdown / GitHub-flavored / ASCII diagrams。无代码、无构建。

## Global Constraints

- 所有 spec/plan 引用必须用相对路径（如 `docs/superpowers/specs/2026-07-17-text-reader-design.md`），实施时用 Glob 验证存在性
- 三件套之间信息不重复：命令只在 AGENTS、API 表只在 INDEX、价值主张只在 README
- README ≤ 200 行；AGENTS ≤ 300 行
- 砍掉 README/AGENTS 中的：API 端点全表、文件结构树、安全响应头表、升级迁移细节（这些都搬 INDEX）
- 不放截图占位
- 保留 README 中的 ASCII 架构图（精简版）
- 提交风格：Conventional Commits；scope 用 `docs`；多文件改动单 commit
- 工作目录有未提交改动（reader/tags/bookmarksView 重构），实施时只 stage 三件套文件，**不要** `git add .`
- 实施完成后跑 grep 验证无信息重复

---

## 文件清单

| 文件 | 操作 | 责任 |
|------|------|------|
| `docs/INDEX.md` | 新建 | 按主题组织的参考库（API/文件/spec/迁移） |
| `README.md` | 重写 | 面向人的产品介绍 + 快速上手 |
| `AGENTS.md` | 重写 | 面向 AI/贡献者的工作手册 |

**依赖关系**：README 与 AGENTS 的指针指向 INDEX 的主题节，所以 INDEX 必须先建。

---

## Task 1: 验证所有 spec/plan 引用路径

**目的**：spec 列出了大量 spec/plan 引用，先批量验证存在性，避免后续写入时笔误。

**Files:**
- 无文件改动；产出一份"已验证路径清单"供后续任务复用

**Interfaces:**
- Produces: 一份路径核对结果（实施者记录在自己的工作笔记中），后续任务的每个 spec 引用都必须来自这份已验证清单

- [ ] **Step 1: 用 Glob 验证 spec 引用的全部路径**

逐条执行以下 Glob（每个都应至少匹配到 1 个文件；0 匹配需停下排查）：

```
docs/superpowers/specs/2026-07-05-server-perf-design.md
docs/superpowers/specs/2026-07-08-thumbnail-pipeline-perf-design.md
docs/superpowers/specs/2026-07-09-video-seek-perf-design.md
docs/superpowers/specs/2026-07-10-thumbnail-deep-perf-design.md
docs/superpowers/specs/2026-07-14-perf-round31-design.md
docs/superpowers/specs/2026-07-17-text-reader-design.md
docs/superpowers/specs/2026-07-17-text-reader-c-phase-design.md
docs/superpowers/specs/2026-07-18-epub-image-inline-design.md
docs/superpowers/specs/2026-07-18-reader-ui-redesign-design.md
docs/superpowers/specs/2026-07-10-security-audit-design.md
docs/superpowers/specs/2026-07-11-security-phase2-libffmpeg-design.md
docs/superpowers/specs/2026-07-10-security-phase3-config-defaults-design.md
docs/superpowers/specs/2026-07-11-security-phase4-http-hardening-design.md
docs/superpowers/specs/2026-07-11-security-phase5-xss-lint-design.md
docs/superpowers/specs/2026-07-10-security-phase7-apk-signing-design.md
docs/superpowers/specs/2026-07-11-security-phase8-misc-p2-design.md
docs/2026-07-13-deadcode-audit-design.md
docs/2026-07-13-deadcode-cleanup-design.md
docs/2026-07-13-deadcode-audit-report.md
docs/superpowers/specs/2026-07-01-android-memory-performance-design.md
docs/superpowers/specs/2026-07-04-native-rust-rewrite-design.md
docs/superpowers/specs/2026-07-04-android-network-cache-design.md
docs/superpowers/specs/2026-07-06-okhttp-json-cache-design.md
docs/superpowers/specs/2026-07-07-exoplayer-state-preservation-design.md
docs/superpowers/specs/2026-07-07-apk-size-optimization-design.md
docs/superpowers/specs/2026-07-08-android-pip-multi-activity-design.md
docs/superpowers/specs/2026-07-02-android-state-persistence-design.md
docs/superpowers/specs/2026-07-07-video-resume-from-all-entries-design.md
docs/superpowers/specs/2026-07-04-browse-decouple-viewmodel-design.md
docs/superpowers/specs/2026-07-06-browseviewmodel-delegates-design.md
docs/superpowers/specs/2026-07-05-native-security-hardening-design.md
docs/superpowers/specs/2026-07-01-appjs-modularization-design.md
docs/superpowers/specs/2026-07-06-web-responsive-design.md
```

- [ ] **Step 2: 用 Glob 验证关键源码路径**

```
server/internal/service/scanner.go
server/internal/service/streaming.go
server/internal/service/thumbnail.go
server/internal/service/path.go
server/internal/service/book.go
server/internal/service/tags.go
server/internal/service/bookparser/parser.go
server/internal/service/bookparser/txt.go
server/internal/service/bookparser/epub.go
server/internal/service/bookparser/unsupported.go
server/internal/server/handler/folders.go
server/internal/server/handler/videos.go
server/internal/server/handler/images.go
server/internal/server/handler/media.go
server/internal/server/handler/search.go
server/internal/server/handler/books.go
server/internal/server/handler/tags.go
server/internal/server/middleware/auth.go
server/internal/server/middleware/security_headers.go
server/internal/server/middleware/ratelimit.go
server/internal/web/api.js
server/internal/web/app.js
server/internal/web/router.js
server/internal/web/state.js
server/internal/web/dom.js
server/internal/web/dashboard.js
server/internal/web/browserView.js
server/internal/web/bookshelf.js
server/internal/web/textReader.js
server/internal/web/readerPrefs.js
server/internal/web/bookmarksView.js
server/internal/web/settings.js
server/internal/web/videoPlayer.js
server/internal/web/lightbox.js
server/internal/web/delete.js
server/internal/web/toast.js
server/internal/web/utils.js
tools/xsscheck/
android/app/src/main/java/com/juziss/localmediahub/LocalMediaHubApplication.kt
android/app/src/main/java/com/juziss/localmediahub/MainActivity.kt
android/app/src/main/java/com/juziss/localmediahub/VideoPlayerActivity.kt
android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt
android/app/src/main/java/com/juziss/localmediahub/ui/screen/HomeScreen.kt
android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt
android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt
android/app/src/main/java/com/juziss/localmediahub/ui/screen/ImagePreviewScreen.kt
android/app/src/main/java/com/juziss/localmediahub/ui/screen/DownloadsScreen.kt
android/app/src/main/java/com/juziss/localmediahub/ui/screen/ConnectionScreen.kt
android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheet.kt
android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderThemeWrapper.kt
android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderFontFamily.kt
android/app/src/main/java/com/juziss/localmediahub/viewmodel/HomeViewModel.kt
android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt
android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseNavigator.kt
android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseSorter.kt
android/app/src/main/java/com/juziss/localmediahub/viewmodel/SearchController.kt
android/app/src/main/java/com/juziss/localmediahub/viewmodel/TagController.kt
android/app/src/main/java/com/juziss/localmediahub/viewmodel/FavoritesController.kt
android/app/src/main/java/com/juziss/localmediahub/viewmodel/DownloadController.kt
android/app/src/main/java/com/juziss/localmediahub/viewmodel/DeleteController.kt
android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseSharedState.kt
android/app/src/main/java/com/juziss/localmediahub/viewmodel/TextReaderViewModel.kt
android/app/src/main/java/com/juziss/localmediahub/data/FavoritesStore.kt
android/app/src/main/rust/Cargo.toml
android/app/build.gradle.kts
```

如有任何路径未匹配，停下并把 spec 中对应引用替换为正确路径或删除该引用。

- [ ] **Step 3: 不需要 commit（本任务无文件改动）**

记录"全部路径已验证"，进入 Task 2。

---

## Task 2: 新建 docs/INDEX.md

**Files:**
- Create: `docs/INDEX.md`

**Interfaces:**
- Consumes: Task 1 已验证的路径清单
- Produces: `docs/INDEX.md` 完整文件（后续任务的 README/AGENTS 都用相对路径指针指向它的小节）

- [ ] **Step 1: 创建 `docs/INDEX.md`**

完整内容（写入文件）：

````markdown
# LocalMediaHub 详细文档索引

本文件是按主题组织的参考库：API 端点表、关键文件指针、历史 spec/plan、迁移指南。要快速干活请先看 `README.md`（产品视角）和 `AGENTS.md`（AI/贡献者工作手册）。

## 快速跳转

- [媒体浏览与播放](#媒体浏览与播放)
- [小说阅读器](#小说阅读器)
- [标签 / 收藏 / 书签](#标签--收藏--书签)
- [安全加固](#安全加固)
- [性能优化](#性能优化)
- [Android 体验](#android-体验)
- [Web 管理界面](#web-管理界面)
- [构建与签名](#构建与签名)
- [测试](#测试)
- [迁移与升级历史](#迁移与升级历史)

---

## 媒体浏览与播放

### API 端点

| 方法 | 路径 | 说明 | 需 Token |
|---|---|---|---|
| GET | `/api/v1/folders` | 根文件夹列表 | 否 |
| GET | `/api/v1/folders/{path}/browse` | 浏览指定目录 | 否 |
| GET | `/api/v1/videos` | 视频列表（分页） | 否 |
| GET | `/api/v1/images` | 图片列表（分页） | 否 |
| GET | `/api/v1/videos/{path}/stream` | 视频流（Range） | 否 |
| GET | `/api/v1/images/{path}/thumbnail` | 缩略图 | 否 |
| GET | `/api/v1/images/{path}/original` | 原图 | 否 |
| GET | `/api/v1/search` | 搜索（支持 `path` 限定作用域） | 否 |
| GET | `/api/v1/media/thumbnail` | 绝对路径缩略图 | 是 |
| GET | `/api/v1/media/original` | 绝对路径原图 | 是 |
| GET | `/api/v1/media/stream` | 绝对路径视频流（Range） | 是 |
| GET | `/api/v1/media/duration` | 媒体时长 | 是 |

### 关键文件

- `server/internal/service/scanner.go`（TTL 缓存 + fsnotify 递归监听 + 2s 防抖 + `cacheByDir` 每目录索引）
- `server/internal/service/streaming.go`（`http.ServeContent` + 256KB `BufferedReadSeeker`，原生 Range）
- `server/internal/service/thumbnail.go`（LANCZOS + MD5 缓存 + sync.Pool + hot path priority）
- `server/internal/service/path.go`（路径校验三件套，详见 [安全加固](#安全加固)）
- `server/internal/server/handler/folders.go` / `videos.go` / `images.go` / `media.go` / `search.go`

### 相关 spec/plan

- `docs/superpowers/specs/2026-07-05-server-perf-design.md`
- `docs/superpowers/specs/2026-07-08-thumbnail-pipeline-perf-design.md`
- `docs/superpowers/specs/2026-07-09-video-seek-perf-design.md`
- `docs/superpowers/specs/2026-07-10-thumbnail-deep-perf-design.md`
- `docs/superpowers/specs/2026-07-14-perf-round31-design.md`

---

## 小说阅读器

支持 txt 与 epub；服务端做章节解析与图片内联，Android/Web 各自渲染。

### API 端点

| 方法 | 路径 | 说明 | 需 Token |
|---|---|---|---|
| GET | `/api/v1/books/info?path=<abs>` | 图书元信息（标题 / 章节列表 / 总字数） | 否 |
| GET | `/api/v1/books/chapter?path=<abs>&index=<n>` | 章节内容（blocks 数组，含文本与图片块） | 否 |
| GET | `/api/v1/books/image?path=<abs>&manifest=<id>` | epub 内部图片字节（`<img>` 标签使用，Token 可走 query fallback） | 是 |

### 关键文件

- Server
  - `server/internal/service/book.go`（BookService：章节解析、epub 图片字节读取、相对路径重写为 `/api/v1/books/image`）
  - `server/internal/service/bookparser/parser.go`（统一入口 + Block 类型）
  - `server/internal/service/bookparser/txt.go`（章节正则）
  - `server/internal/service/bookparser/epub.go`（epub 解包）
  - `server/internal/service/bookparser/unsupported.go`（.mobi/.azw3 拒绝列表）
  - `server/internal/server/handler/books.go`
- Android
  - `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt`
  - `android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheet.kt`
  - `android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderThemeWrapper.kt`
  - `android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderFontFamily.kt`
  - `android/app/src/main/java/com/juziss/localmediahub/viewmodel/TextReaderViewModel.kt`
- Web
  - `server/internal/web/textReader.js`
  - `server/internal/web/bookshelf.js`
  - `server/internal/web/readerPrefs.js`

### 阅读体验特性

- 7 套主题：AUTO + 日间 × 3 + 夜间 × 3
- 字体嵌入：LXGW WenKai + Noto Serif SC woff2
- V2 设置：字号 / 行距 / 段距 / 首行缩进 / 字体族
- 自动滚动、书签、章节列表、沉浸模式（chrome 自动隐藏）、首字下沉、章节末标记、淡入过渡

### 相关 spec/plan

- `docs/superpowers/specs/2026-07-17-text-reader-design.md`
- `docs/superpowers/specs/2026-07-17-text-reader-c-phase-design.md`
- `docs/superpowers/specs/2026-07-18-epub-image-inline-design.md`
- `docs/superpowers/specs/2026-07-18-reader-ui-redesign-design.md`
- 对应 plans 同名前缀，目录 `docs/superpowers/plans/`

---

## 标签 / 收藏 / 书签

### API 端点（标签，无需 Token）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/tags` | 标签列表 |
| POST | `/api/v1/tags` | 创建标签 |
| DELETE | `/api/v1/tags/{id}` | 删除标签 |
| POST | `/api/v1/tags/{id}/files/{path}` | 给文件打标签 |
| DELETE | `/api/v1/tags/{id}/files/{path}` | 移除文件标签 |
| GET | `/api/v1/tags/{id}/files` | 标签下文件 |
| GET | `/api/v1/tags/{id}/media` | 标签下媒体（分页） |
| GET | `/api/v1/tags/file-tags` | 批量获取文件标签映射 |

### 关键文件

- Server
  - `server/internal/service/tags.go`（SQLite + RWMutex + PRAGMA + index + 批量 IN 查询 + JSON→SQLite 自动迁移）
  - `server/internal/server/handler/tags.go`
- Android
  - `android/app/src/main/java/com/juziss/localmediahub/data/FavoritesStore.kt`（DataStore 持久化）
  - `android/app/src/main/java/com/juziss/localmediahub/viewmodel/TagController.kt`（Browse delegate）
- Web
  - `server/internal/web/bookmarksView.js`（取代 `tagsView.js`）

### 相关 spec/plan

- `docs/superpowers/specs/2026-07-10-security-audit-design.md`（SQL 注入审计）

---

## 安全加固

服务端启动时若 `scan.roots` 和 `system.allowed_roots` 均为空且 `scan.auto_detect_roots: false`，**拒绝启动**（fail-safe）。

### Phase 1-8 总览

| Phase | 主题 | spec 路径 | 状态 |
|---|---|---|---|
| 1 | Bearer Token auth（admin / system / media / books-image 路由组） | `docs/superpowers/specs/2026-07-10-security-audit-design.md` | 完成 |
| 2 | libffmpeg SBOM + SHA256 + CVE 审计 | `docs/superpowers/specs/2026-07-11-security-phase2-libffmpeg-design.md` | 完成 |
| 3 | config 默认安全（auto_detect_roots fail-fast） | `docs/superpowers/specs/2026-07-10-security-phase3-config-defaults-design.md` | 完成 |
| 4 | HTTP 加固（CSP / XFO / nosniff / Referrer-Policy） | `docs/superpowers/specs/2026-07-11-security-phase4-http-hardening-design.md` | 完成 |
| 5 | XSS 静态分析工具 `xsscheck` | `docs/superpowers/specs/2026-07-11-security-phase5-xss-lint-design.md` | 完成 |
| 6 | CI | — | 未启动 |
| 7 | APK 签名 fail-fast + `allowBackup=false` | `docs/superpowers/specs/2026-07-10-security-phase7-apk-signing-design.md` | 完成 |
| 8 | 杂项 P2（rate limit / blocked roots / ffmpeg kill on disconnect / sanitize path errors） | `docs/superpowers/specs/2026-07-11-security-phase8-misc-p2-design.md` | 完成 |

### 路径校验三件套（`server/internal/service/path.go`）

- `ValidatePath` —— 常规媒体扫描根目录校验
- `ValidateSystemMediaAccess` —— `/api/v1/system/*` 端点专用，强制 `system.allowed_roots` 边界
- `ValidateAccessibleMediaPath` —— `/api/v1/media/*` 统一媒体端点专用，覆盖扫描根目录与 `system.allowed_roots`

### 安全响应头

| 头 | 值 | 缓解 |
|---|---|---|
| `X-Frame-Options` | `DENY` | Clickjacking |
| `X-Content-Type-Options` | `nosniff` | MIME 嗅探 |
| `Referrer-Policy` | `no-referrer` | 外链泄漏 |
| `Content-Security-Policy` | `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'; media-src 'self'; connect-src 'self'` | XSS 数据 exfiltration |

### 当前已知 TODO

- CSP `style 'unsafe-inline'` 待 Web UI XSS 整改后移除
- HSTS 仅 HTTPS 下有效，TLS 留作未来
- Permissions-Policy 项目暂不需要（不用相机/麦克风/地理位置）

### 关键文件

- `server/internal/server/middleware/auth.go`（Bearer Token，header + query fallback，常量时间比较）
- `server/internal/server/middleware/security_headers.go`（必须在 CORS 之前挂载）
- `server/internal/server/middleware/ratelimit.go`（per-route，挂在 scan trigger + delete）
- `server/internal/service/path.go`
- `android/app/build.gradle.kts`（release 签名 fail-fast 守卫）
- `tools/xsscheck/`

---

## 性能优化

### Round 27-31 清单

- **Round 27** — 视频 seek 防抖（drop ForwardingPlayer debounce 层，手势松手后单次即时 seek）
  - spec: `docs/superpowers/specs/2026-07-09-video-seek-perf-design.md`
- **Round 28** — thumbnail deep perf（ffmpeg pipe / BiLinear / encode helper）+ folder search index（`cacheDirs` / `GetCachedDirs` / `filterDirsByScope`）
  - spec: `docs/superpowers/specs/2026-07-10-thumbnail-deep-perf-design.md`
- **Round 30** — 死代码清理 5 批次（server / android / web / deps / R8 fullMode）
  - spec: `docs/2026-07-13-deadcode-audit-design.md`
  - spec: `docs/2026-07-13-deadcode-cleanup-design.md`
  - 报告: `docs/2026-07-13-deadcode-audit-report.md`
- **Round 31** — server SQLite PRAGMA + index + scanner `cacheByDir` + gzip 中间件 + `sync.Pool` + hot path priority
  - spec: `docs/superpowers/specs/2026-07-14-perf-round31-design.md`

### Android 性能 spec（按主题归类）

- `docs/superpowers/specs/2026-07-01-android-memory-performance-design.md`
- `docs/superpowers/specs/2026-07-04-native-rust-rewrite-design.md`
- `docs/superpowers/specs/2026-07-04-android-network-cache-design.md`
- `docs/superpowers/specs/2026-07-06-okhttp-json-cache-design.md`
- `docs/superpowers/specs/2026-07-07-exoplayer-state-preservation-design.md`
- `docs/superpowers/specs/2026-07-07-apk-size-optimization-design.md`

---

## Android 体验

### PiP（多 Activity 架构）

- 独立 `VideoPlayerActivity` 承载 PiP 浮窗
- `VideoPlayerIntentBuilder` 构造启动 Intent
- `PipController` + `PipControllerStore` + `PipActionReceiver` 处理 PiP action
- `exitingFromPip` 标志区分"关闭浮窗"vs"切后台"，关闭浮窗后 `onStop` 自动 `finish()` 释放 ExoPlayer

### 续播

- `RecentActivityStore` 持久化播放进度
- `ResumePlaybackDialog` + `VideoOpenAction` sealed class + `ResumePlaybackRequest`
- 跨入口恢复：Browse / Favorites / Downloads / Recent / 续播 chip
- 进度 ≥ 95% 时弹"继续 / 从头开始"对话框

### 批量选择

- `BrowseContent` 长按进入选择模式
- `deletePaths` 批量删除入口
- 批量下载到本地

### 原生解码

- Rust crate 入口：`android/app/src/main/rust/`（详见 [构建与签名](#构建与签名)）
- `NativeDecoderFactory` 集成到 Coil
- EXIF orientation 自动校正
- 通道约定：Android `ARGB_8888` == NDK `RGBA_8888`，解码器直接 `copy_from_slice`

### Compose workaround

`android/app/src/main/java/com/juziss/localmediahub/ui/theme/NoRippleIndication.kt` 解决 foundation 1.11.x + material3 1.3.1 错配（release R8 构建崩溃）；material3 升级到 1.4.x+ 后可移除。

### 相关 spec/plan

- `docs/superpowers/specs/2026-07-08-android-pip-multi-activity-design.md`
- `docs/superpowers/specs/2026-07-02-android-state-persistence-design.md`
- `docs/superpowers/specs/2026-07-07-video-resume-from-all-entries-design.md`
- `docs/superpowers/specs/2026-07-04-browse-decouple-viewmodel-design.md`
- `docs/superpowers/specs/2026-07-06-browseviewmodel-delegates-design.md`
- `docs/superpowers/specs/2026-07-04-native-rust-rewrite-design.md`
- `docs/superpowers/specs/2026-07-05-native-security-hardening-design.md`

---

## Web 管理界面

服务端内置 SPA，浏览器访问 server 地址（如 `http://localhost:8000`）即可。

### 模块结构

- 公共层：`server/internal/web/app.js` / `router.js` / `state.js` / `dom.js` / `api.js`
- 视图层：`dashboard.js` / `browserView.js` / `bookshelf.js` / `textReader.js` / `readerPrefs.js` / `bookmarksView.js` / `settings.js` / `videoPlayer.js` / `lightbox.js` / `delete.js` / `toast.js` / `utils.js`

### Token 集成

- `api.js` `apiRequest()` 自动注入 `Authorization: Bearer <token>` header
- 401 响应触发事件，`app.js` 弹 token modal
- sessionStorage 持久化
- Token 同时支持 query fallback（仅用于 `<img src>` 这种无法加 header 的场景）

### CSP 兼容要点

- 无 inline `<script>`（`script-src 'self'`）
- inline `style="..."` 属性暂留 `'unsafe-inline'`（待 Phase 5 Web UI XSS 整改）
- 无 `data:` URI 例外
- 无 Google Fonts CDN（Phase 4 fixup 已移除，改用本地嵌入字体 LXGW WenKai + Noto Serif SC）

### 相关 spec/plan

- `docs/superpowers/specs/2026-07-01-appjs-modularization-design.md`
- `docs/superpowers/specs/2026-07-06-web-responsive-design.md`
- `docs/superpowers/specs/2026-07-11-security-phase5-xss-lint-design.md`
- `docs/superpowers/specs/2026-07-17-text-reader-design.md`（reader 模块）

---

## 构建与签名

### Server 构建

```bash
cd server
go build -o LocalMediaHub.exe ./cmd/server
./LocalMediaHub.exe              # GUI + 系统托盘
./LocalMediaHub.exe --headless   # 无窗口
```

单文件可执行，双击即用。

### Android 构建链

Gradle 主驱动；`buildRustNative` task 挂载 `preBuild` 阶段：

```bash
cargo ndk -t arm64-v8a -o jniLibs/ build --release
```

输出 `liblocalmedia_native.so` 到 `jniLibs/arm64-v8a/`。
`libffmpeg.so` 为预编译产物（不参与 Rust 构建链），由 Android `preBuild` 校验 `.sha256`。
APK 输出位置：`android/app/build/outputs/apk/`。

### Release 签名流程

1. 生成 keystore（一次性）：
   ```bash
   keytool -genkeypair -v -keystore localmediahub.keystore -alias localmediahub \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
2. 复制示例配置并填入签名信息：
   ```bash
   cp android/keystore.properties.example android/keystore.properties
   ```
3. 正常构建：
   ```bash
   cd android && ./gradlew assembleRelease
   ```
4. 仅本地调试（不配 keystore，用 debug key）：
   ```bash
   ./gradlew assembleRelease -PallowDebugSigning=true
   ```

> ⚠️ **切勿公开分发 debug 签名的 APK**——任何人都能用相同 debug key 重签名发布"官方" APK（Chain-I 供应链攻击）。

### 相关 spec/plan

- `docs/superpowers/specs/2026-07-04-native-rust-rewrite-design.md`
- `docs/superpowers/specs/2026-07-07-apk-size-optimization-design.md`
- `docs/superpowers/specs/2026-07-11-security-phase2-libffmpeg-design.md`
- `docs/superpowers/specs/2026-07-10-security-phase7-apk-signing-design.md`

---

## 测试

按改动范围选子系统跑（不要盲跑全部）。

### Go

```bash
cd server && go test ./...
```

关键测试文件：
- `server/internal/service/*_test.go`
- `server/internal/server/handler/*_test.go`
- `server/internal/service/bookparser/*_test.go`

### Android

```bash
cd android && ./gradlew testDebugUnitTest
```

### Rust

```bash
cd android/app/src/main/rust && cargo test
```

### XSS 静态分析

```bash
cd tools/xsscheck && go run . ../server/internal/web
```

### 交付前推荐组合

```bash
cd android && ./gradlew testDebugUnitTest assembleDebug
```

---

## 迁移与升级历史

### Round 29 Phase 3 — config 默认安全升级

升级后若遇 `refusing to start` 错误，选择以下任一：

1. 在 `config.yaml` 的 `scan.roots` 下显式列出媒体目录（推荐，最安全）
2. 配置 `system.allowed_roots`（同时作为 scan roots 的 fallback）
3. 在 `config.yaml` 设置 `scan.auto_detect_roots: true`（全盘扫描，需评估风险）
4. 启动加 `--auto-detect-roots` flag（一次性 override）

详见 `server/config.example.yaml` 的注释。

### 标签存储迁移

- 旧版 `tags.json` → SQLite `server/.data/tags.db`
- 首次启动自动迁移，原文件备份为 `tags.json.bak`

### DataStore 1.0.0 → 1.1.1

为 Bearer Token 字段引入；旧版回退兼容。

### Reader 设置 V1 → V2

自动迁移到新 shape（字号 / 行距 / 段距 / 首行缩进 / 字体族）。

### Android version 1.2（token-auth breaking change）

旧客户端连接启用 Token 的服务端会失败，需升级到 ≥ 1.2。
````

- [ ] **Step 2: 验证文件已创建**

Run: `ls -la docs/INDEX.md`
Expected: 文件存在，行数在 280-330 之间（容差因 ASCII 渲染）。

- [ ] **Step 3: 验证所有 spec 引用路径存在**

逐条 Glob 验证 INDEX 文件中出现的所有 spec/plan 相对路径。如发现不存在的路径，停下并修正。

- [ ] **Step 4: Commit**

```bash
git add docs/INDEX.md
git commit -m "docs(index): new docs/INDEX.md topic-organized reference library"
```

---

## Task 3: 重写 README.md

**Files:**
- Modify: `README.md`（完全重写）

**Interfaces:**
- Consumes: Task 2 已建的 `docs/INDEX.md`（指针目标）
- Produces: 精简的 `README.md`，含 `AGENTS.md` 与 `docs/INDEX.md` 反向指针

- [ ] **Step 1: 读取当前 README.md**

Run: `Read README.md`（确认当前内容，避免遗漏待迁移信息）

- [ ] **Step 2: 用以下完整内容覆盖 `README.md`**

````markdown
# LocalMediaHub

GitHub: [huangsheng6668/LocalMediaHub](https://github.com/huangsheng6668/LocalMediaHub)

把 PC 上散落的视频、图片、小说串流到 Android，在局域网里随时翻看。

## 系统架构

```
┌─────────────────────┐       HTTP/REST        ┌──────────────────────┐
│    PC Server        │◄──────────────────────►│   Android Client     │
│   Go / Echo v4      │    局域网 Wi-Fi/有线    │  Kotlin/Compose      │
│                     │                         │                      │
│  - 全盘浏览         │  /api/v1/folders       │  - 文件浏览器         │
│  - 视频流 (Range)   │  /api/v1/videos/stream │  - ExoPlayer 播放     │
│  - 缩略图生成       │  /api/v1/images/thumb  │  - Coil 图片加载      │
│  - 小说阅读器       │  /api/v1/books/*       │  - TextReader 阅读器  │
│  - 标签 + 书签      │  /api/v1/tags          │  - 收藏 / 标签 / 书签 │
│  - mDNS 发现        │                         │  - NSD 自动发现       │
│  - 系统托盘         │                         │  - 画中画 (PiP)       │
│  - Bearer Token     │                         │  - 离线下载          │
└─────────────────────┘                         └──────────────────────┘
```

## 核心功能

### 1. 媒体浏览与播放

自动检测 Windows 驱动器，浏览任意目录。视频通过 HTTP Range 流式播放（256KB 缓冲 `BufferedReadSeeker`），缩略图采用 LANCZOS 缩放 + MD5 磁盘缓存。`fsnotify` 实时监听根目录，变更后防抖重扫。

### 2. 小说阅读器（txt / epub）

服务端章节解析与 epub 图片内联。7 套主题（AUTO + 日间 × 3 + 夜间 × 3），嵌入 LXGW WenKai + Noto Serif SC 字体。V2 设置：字号 / 行距 / 段距 / 首行缩进 / 字体族。支持自动滚动、书签、章节列表、沉浸模式（chrome 自动隐藏）、首字下沉、章节末标记、淡入过渡。

### 3. 续播与上下文恢复

跨入口（浏览 / 收藏 / 下载 / 最近打开）打开同一视频都自动从上次进度恢复。进度 ≥ 95% 时弹窗"继续 / 从头开始"，自动续播时右下角提供 3 秒"从头开始"chip。浏览页记录最近路径与滚动位置，一键重回上次上下文。

### 4. 收藏 / 标签 / 书签

Android 端用 DataStore 持久化收藏。服务端标签走 SQLite（pure-Go `modernc.org/sqlite`，无 CGO），CRUD + 文件关联，首次启动自动从 `tags.json` 迁移。Web 端书签视图（`bookmarksView.js`）。

### 5. 离线下载 / 画中画

WorkManager 前台服务执行下载，常驻通知栏显示进度；支持单文件与目录 ZIP 流式下载解压（含 Zip Slip 防护）。视频 PiP 浮窗使用独立 `VideoPlayerActivity`，关闭浮窗自动 `finish()` 释放 ExoPlayer，避免后台音频泄漏。

### 6. 安全加固

Bearer Token 认证（admin / system / media / books-image 路由组强制）；安全响应头（CSP / X-Frame-Options / nosniff / Referrer-Policy）；Release APK 签名 fail-fast 守卫；libffmpeg SHA256 preBuild 校验；路径遍历防护（ValidatePath + ValidateSystemMediaAccess + ValidateAccessibleMediaPath）。

## 技术栈

| 层 | 技术 |
|---|---|
| Server | Go 1.25+ / Echo v4 / modernc.org/sqlite (pure-Go) / fsnotify / getlantern/systray / hashicorp/mdns |
| Android | Kotlin / Jetpack Compose / Media3 (ExoPlayer + MediaSession) / Coil 3 / WorkManager / Hilt |
| Web 管理界面 | 模块化 JS SPA（无构建步骤，CSP 兼容） |
| 原生解码 | Rust 2021 + cargo-ndk → arm64-v8a（pure-Rust crates）+ 预编译 libffmpeg.so |

## 快速上手

### 1. 启动 Server

```bash
cd server
go build -o LocalMediaHub.exe ./cmd/server
./LocalMediaHub.exe          # GUI 模式，带系统托盘
./LocalMediaHub.exe --headless  # 无头模式，无窗口
```

Windows 用户直接双击 `LocalMediaHub.exe` 即可启动。

如在中国大陆网络环境下编译：

```bash
GOPROXY=https://goproxy.cn,direct go mod tidy
```

### 2. 配置

编辑 `server/config.yaml`（最小示例）：

```yaml
server:
  host: "0.0.0.0"
  port: 8000
  token: "<可选：开启 Bearer Token 后填入>"

scan:
  video_extensions: [.mp4, .mkv, .avi, .mov]
  image_extensions: [.jpg, .jpeg, .png, .gif, .webp]
  # roots 留空 + auto_detect_roots: true 会自动检测 Windows 驱动器
  # 若 roots 与 allowed_roots 均未配置且 auto_detect_roots: false，服务端拒绝启动

system:
  allowed_roots:
    - "D:/Media"
```

`system.allowed_roots` 控制 `/api/v1/system/*` 与 `/api/v1/media/*` 这两组受限端点的访问边界。

### 3. 编译 Android 客户端

```bash
cd android
./gradlew assembleDebug                          # Debug 版本
./gradlew assembleRelease                        # Release（默认要求 keystore.properties）
./gradlew testDebugUnitTest assembleDebug        # 推荐在交付前执行
```

Release 构建默认要求有效的 `keystore.properties`，未配置时会**构建失败**（防止误用 debug 签名发布 APK）。仅本地调试可用 `-PallowDebugSigning=true`。

APK 输出位置：`android/app/build/outputs/apk/`

### 4. 连接

- **自动**：App 优先尝试上次成功连接的服务端，失败后通过 NSD 自动发现局域网内的 Server（需同一 WiFi + `CHANGE_WIFI_MULTICAST_STATE` 权限）。
- **手动**：在 App 中输入 PC 的局域网 IP（如 `192.168.1.100:8000`）。

如服务端配置了 `token`，Android 与 Web 都会弹输入框。

## 项目状态

- 开发阶段；本地改动自动同步推送至 GitHub `master` 分支（个人项目约定）
- License: MIT

## 想了解更多？

- **给 AI / 贡献者的工作手册**：[`AGENTS.md`](AGENTS.md) —— 模块地图、编码规则、安全约定、命令清单、提交约定
- **完整文档索引**：[`docs/INDEX.md`](docs/INDEX.md) —— API 端点表、关键文件指针、历史 spec/plan、迁移与升级指南
````

- [ ] **Step 3: 验证文件长度与内容**

Run: `wc -l README.md`
Expected: 行数 ≤ 200。

Read 文件确认：
- 含 ASCII 架构图
- 6 张核心功能卡片齐全
- 技术栈 4 行表
- 快速上手 4 步
- 配置示例含 `token`、`scan.roots`/`auto_detect_roots`/`allowed_roots` 注释
- 末尾"想了解更多？"指向 AGENTS.md + docs/INDEX.md
- **不含** API 端点全表、文件结构树、安全响应头表、升级迁移细节

- [ ] **Step 4: 不 commit**

README 与 AGENTS 在下一个 task 一起 commit，避免中间状态指针悬空（AGENTS 会引用 README）。

---

## Task 4: 重写 AGENTS.md

**Files:**
- Modify: `AGENTS.md`（完全重写）

**Interfaces:**
- Consumes: Task 2 已建的 `docs/INDEX.md`（指针目标）
- Produces: 重构后的 `AGENTS.md`，含 4 子系统模块地图、命令、规则、安全约定、测试、提交约定、反向指针到 INDEX

- [ ] **Step 1: 读取当前 AGENTS.md**

Run: `Read AGENTS.md`（确认当前内容，避免遗漏待迁移信息）

- [ ] **Step 2: 用以下完整内容覆盖 `AGENTS.md`**

````markdown
# AGENTS.md — LocalMediaHub 工作手册

GitHub Repo: https://github.com/huangsheng6668/LocalMediaHub

LocalMediaHub 是 PC ↔ Android 局域网媒体串流系统：服务端扫描和提供媒体流，Android 客户端浏览和播放。本文档是 AI agent 与贡献者的工作手册：模块地图让你快速定位代码，编码规则与安全约定让你改对地方，命令清单让你跑得起来。需要查阅 API 端点表、文件结构树或历史 spec 时，跳到 [`docs/INDEX.md`](docs/INDEX.md)。

## 模块地图

### Server (Go / Echo v4)

- **入口**：`server/cmd/server/main.go`（`--headless` 切无窗口）
- **路由**：`server/internal/server/server.go`（`Server` struct 持有所有 service 引用；`Server.Stop` 关闭 scanner + tags DB）
- **Handler**：`server/internal/server/handler/*.go`（只做参数解析与响应，不写业务逻辑；通过 `Handler` struct 持有 service 依赖，**不使用全局变量**）
- **Service**：`server/internal/service/*.go`
  - `scanner.go` — 文件扫描（TTL 缓存 + fsnotify 递归监听 `StartWatching` + 2s 防抖 + `cacheByDir` 每目录索引）
  - `tags.go` — 标签系统（SQLite + RWMutex + PRAGMA + index + 批量 IN 查询 + JSON→SQLite 自动迁移，`Close()` 关闭 DB）
  - `streaming.go` — 视频流（`http.ServeContent` + 256KB `BufferedReadSeeker`，原生 Range）
  - `thumbnail.go` — 缩略图（LANCZOS + MD5 缓存 + sync.Pool + hot path priority）
  - `book.go` — BookService（章节解析、epub 图片字节读取、相对路径重写为 `/api/v1/books/image`）
  - `bookparser/` 子包 — `parser.go` / `txt.go` / `epub.go` / `unsupported.go`
  - `path.go` — 路径校验三件套（见 [安全约定](#安全约定触碰前必读)）
- **Middleware**：`server/internal/server/middleware/*.go`
  - `cors.go` — CORS
  - `auth.go` — Bearer Token（header + query fallback）
  - `security_headers.go` — CSP / XFO / nosniff / Referrer-Policy（**必须在 CORS 之前挂载**）
  - `ratelimit.go` — per-route rate limit（挂在 scan trigger + delete）
- **周边**：`server/internal/mdns/`（mDNS 注册）/ `server/internal/systray/`（系统托盘）/ `server/internal/gui/`（GUI 模式入口）/ `server/internal/web/`（前端静态资源，详见 [Web 管理界面](#web-管理界面)）
- **配置**：`server/config.yaml`（运行时）/ `server/config.example.yaml`（模板）

### Android (Kotlin / Compose)

- **Application**：`android/app/src/main/java/com/juziss/localmediahub/LocalMediaHubApplication.kt`（Hilt `@HiltAndroidApplication`）
- **Activity**：
  - `MainActivity.kt`（singleTop + NavHost + 视频续播调度 `checkPlaybackProgress` / `playVideo` / `resumeRequest`；启动请求 `POST_NOTIFICATIONS`）
  - `VideoPlayerActivity.kt`（独立视频播放 Activity，承载 PiP 浮窗；`exitingFromPip` 标志区分"关闭浮窗"vs"切后台"，关闭浮窗后 `onStop` 自动 `finish()` 释放 ExoPlayer）
- **Screen**（`ui/screen/`）：`HomeScreen` / `ConnectionScreen` / `BrowseScreen` / `VideoPlayerScreen` / `ImagePreviewScreen` / `TextReaderScreen` / `DownloadsScreen`
- **Component**（`ui/component/`）：
  - `home/`（首页卡片：Hero / Library / ContinueWatching / RecentMedia / Favorite）
  - `browse/`（浏览子组件：TopBar / SortMenu / SearchView / FavoritesView / DeleteConfirmDialog / QuickActionsDialog 等）
  - `reader/`（`ReaderSettingsSheet` / `ReaderThemeWrapper` / `ReaderFontFamily`）
  - 通用：`ResumePlaybackDialog` / `PlayerGestureDetector` / `BrowseContent` / `GridContainers` / `MediaItems` / `TagComponents` / `VerticalScrollbar` / `theme/NoRippleIndication`
- **ViewModel**（`viewmodel/`）：
  - `HomeViewModel` / `BrowseViewModel` / `ConnectionViewModel` / `VideoPlayerViewModel` / `TextReaderViewModel`
  - Browse 通过 delegate 分发：`BrowseNavigator`（导航）/ `BrowseSorter`（排序）/ `SearchController`（搜索）/ `TagController`（标签）/ `FavoritesController`（收藏）/ `DownloadController`（下载）/ `DeleteController`（删除，`deletePath` + `deletePaths`）/ `BrowseSharedState`（共享状态）
- **Data**（`data/`）：
  - `Models.kt`（`MediaFile` / `Folder` / `Tag` / `FavoriteMediaEntry` / `PlaybackProgressEntry` / `RecentMediaEntry` / `LastBrowseLocation`）
  - `MediaRepository.kt`（Retrofit 包装）
  - `RecentActivityStore.kt`（最近活动 + 浏览状态 + 播放进度）
  - `FavoritesStore.kt`（DataStore 收藏）
  - `DownloadsStore.kt` + `DownloadManager.kt` + `DownloadWorker.kt`（CoroutineWorker 前台服务下载 + Zip Slip 防护）
  - `ServerConfigStore.kt`（含 `authToken`）
  - `RoutePath.kt`（浏览路径与系统/库模式标记）
- **Network**（`network/`）：Retrofit 接口 + OkHttp + `AuthInterceptor`（注入 Bearer Token）
- **Native**（`native/`）：`NativeImageDecoder.kt` / `NativeExif.kt` / `NaturalSorter.kt` / `NativeDecoderFactory.kt`（Coil 集成）
- **Native libs**：`app/src/main/jniLibs/arm64-v8a/`（`liblocalmedia_native.so` Rust 输出 + `libffmpeg.so` 预编译）
- **构建**：`app/build.gradle.kts` 注册 `buildRustNative` task（详见 [Rust 原生解码](#rust-原生解码)）

### Web 管理界面

服务端内置 SPA，浏览器访问 server 地址（如 `http://localhost:8000`）即可。

- **公共层**：`server/internal/web/` 下 `app.js` / `router.js` / `state.js` / `dom.js` / `api.js`
- **视图层**：`dashboard.js` / `browserView.js` / `bookshelf.js` / `textReader.js` / `readerPrefs.js` / `bookmarksView.js`（取代旧 `tagsView.js`）/ `settings.js` / `videoPlayer.js` / `lightbox.js` / `delete.js` / `toast.js` / `utils.js`
- **Token 集成**：`api.js` 的 `apiRequest()` 自动注入 Bearer header + 401 事件 → `app.js` 弹 token modal；sessionStorage 持久化
- **CSP 兼容**：无 inline `<script>`；inline `style=` 暂留 `'unsafe-inline'`（待 Phase 5 Web UI XSS 整改）
- **无构建步骤**，跟随 server 静态服务

### Rust 原生解码

- **crate**：`localmedia_native` @ `android/app/src/main/rust/`
- **依赖**：pure-Rust（`jpeg-decoder` / `image-png` / `webp` / `kamadak-exif` / `fast-image-resize`），**无 C 依赖**
- **构建**：`cargo ndk -t arm64-v8a -o jniLibs/ build --release`，由 Gradle `buildRustNative` task 在 `preBuild` 阶段自动调用；**不要手动覆盖 jniLibs 产物**
- **输出**：`liblocalmedia_native.so`
- **JNI 桥**：`src/jni_bridge/`（`decoders.rs` / `exif_jni.rs` / `natural_sort_jni.rs`）
- **通道约定**：Android `ARGB_8888` == NDK `RGBA_8888`，解码器直接 `copy_from_slice`，无需通道重排
- **`libffmpeg.so`** 为预编译产物（不参与 Rust 构建链），由 Android `preBuild` 校验 `.sha256`

## 常用命令

### Server

```bash
cd server
go build -o LocalMediaHub.exe ./cmd/server
./LocalMediaHub.exe              # GUI + 系统托盘
./LocalMediaHub.exe --headless   # 无窗口
GOPROXY=https://goproxy.cn,direct go mod tidy   # 中国大陆代理
```

### Android

```bash
cd android
./gradlew assembleDebug                              # Debug
./gradlew assembleRelease                            # Release（默认要求 keystore.properties）
./gradlew assembleRelease -PallowDebugSigning=true   # 仅本地调试
./gradlew testDebugUnitTest assembleDebug            # 交付前推荐组合
```

### Rust

正常构建无需手动执行（Gradle `buildRustNative` 自动调用）。首次环境准备：

```bash
rustup target add aarch64-linux-android
cargo install cargo-ndk
```

手动重建 / 跑 Rust 单测：

```bash
cd android/app/src/main/rust
cargo ndk -t arm64-v8a -o jniLibs/ build --release
cargo test
```

### 静态分析（XSS 覆盖率检查）

```bash
cd tools/xsscheck
go run . ../../internal/web
```

## 编码规则

### Go (Server)

- Handler 通过 `Handler` struct 持有 service 依赖，**不使用全局变量**
- 业务逻辑放 `internal/service/`，handler 只做参数解析与响应
- 所有文件访问必须经过路径校验三件套（见 [安全约定](#安全约定触碰前必读)）
- 受限端点（`system/*` 与 `media/*`）必须落在 `system.allowed_roots` 边界内
- 列表返回用 `make([]T, 0)` 初始化，避免 JSON 序列化为 `null`

### Kotlin (Android)

- UI: Jetpack Compose，MVVM（ViewModel + Repository）
- 网络: Retrofit + OkHttp，`AuthInterceptor` 注入 Bearer Token
- 图片: Coil 3（含 `NativeDecoderFactory`）
- 视频: Media3 (ExoPlayer + MediaSession) + 预编译 libffmpeg.so
- 异步: Coroutines
- 后台: WorkManager + 前台服务（`FOREGROUND_SERVICE_DATA_SYNC`）用于离线下载
- DI: Hilt
- **已知 Compose 版本错配**: foundation 1.11.x + material3 1.3.1 的 `clickable` 在 release R8 构建下崩溃，由 `ui/theme/NoRippleIndication.kt` 提供无 ripple 的 `IndicationNodeFactory` 解决；material3 升级到 1.4.x+ 后可移除

### Web (前端 JS)

- 模块化（每个视图一个 `.js` 文件），无构建步骤
- 零 inline `<script>`（CSP `script-src 'self'`）；`style` 的 `'unsafe-inline'` 待 Phase 5 Web UI XSS 整改后移除
- 统一通过 `api.js` 的 `apiRequest()` 发请求（自动注入 Bearer header + 401 事件）
- Token 通过 sessionStorage 持久化
- 涉及 `innerHTML` 的代码需通过 `tools/xsscheck` 静态分析

### Rust

- 仅使用 pure-Rust crates（无 C 依赖，便于交叉编译）
- JNI 桥统一放 `src/jni_bridge/`
- Bitmap 写入直接 `copy_from_slice`（通道顺序约定见 [Rust 原生解码](#rust-原生解码)）
- 编译由 Gradle `buildRustNative` 自动驱动，**不要手动覆盖 jniLibs 产物**

## 安全约定（触碰前必读）

### 路径校验三件套

`server/internal/service/path.go`：

- `ValidatePath` —— 常规媒体扫描根目录校验
- `ValidateSystemMediaAccess` —— `/api/v1/system/*` 端点专用，强制 `system.allowed_roots` 边界
- `ValidateAccessibleMediaPath` —— `/api/v1/media/*` 统一媒体端点专用，覆盖扫描根目录与 `system.allowed_roots`

任何路径相关改动先确认是否调用上述三者；新增端点必须显式选择其一。

### Bearer Token 认证

`server/internal/server/middleware/auth.go`：

- 挂载路由组：admin / system / media / books-image
- 接受 header（`Authorization: Bearer <token>`）与 query（`?token=`）双 fallback（query 仅为 `<img src>` 这种无法加 header 的场景）
- 常量时间比较，防 timing attack

### Config 默认安全（Phase 3）

- `scan.roots` 空 + `system.allowed_roots` 空 + `scan.auto_detect_roots: false` → 服务端**拒绝启动**
- 一次性 override：启动加 `--auto-detect-roots` flag
- 详见 [`docs/INDEX.md`](docs/INDEX.md#迁移与升级历史) "迁移与升级历史"

### HTTP 安全响应头

`server/internal/server/middleware/security_headers.go`：

- `X-Frame-Options: DENY`
- `X-Content-Type-Options: nosniff`
- `Referrer-Policy: no-referrer`
- `Content-Security-Policy`: `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'; media-src 'self'; connect-src 'self'`
- 中间件**必须在 CORS 之前挂载**
- 已知 TODO: `style 'unsafe-inline'` 待 Web UI XSS 整改后移除

### APK 签名守卫（Phase 7）

- Release 构建默认 fail-fast：无 `keystore.properties` 即拒
- `android:allowBackup="false"`（防 adb backup 提取）
- `-PallowDebugSigning=true` 仅本地调试；**切勿公开分发 debug 签名 APK**（Chain-I 供应链攻击风险）

### libffmpeg SHA256 校验（Phase 2）

- `preBuild` 阶段比对 `jniLibs/arm64-v8a/libffmpeg.so.sha256`
- 缺失会触发可操作的错误信息
- SBOM 与 CVE 审计见 `docs/superpowers/specs/2026-07-11-security-phase2-libffmpeg-design.md`

### Zip Slip 防护

`DownloadWorker` 解压前校验每个 entry 路径不越界目标目录。

### Rate Limit（Phase 8）

`server/internal/server/middleware/ratelimit.go` per-route 限流，挂在 scan trigger + delete 路由。

### 触碰安全敏感代码前先看

- `docs/superpowers/specs/2026-07-10-security-audit-design.md`（主审计）
- `docs/superpowers/specs/2026-07-11-security-phase4-http-hardening-design.md`（HTTP 头）
- [`docs/INDEX.md`](docs/INDEX.md#安全加固) "安全加固" 主题节

## 测试与验证

**修改后请跑相关子系统的测试**（不要盲跑全部，按改动范围选）：

- 改 `server/`：`cd server && go test ./...`
- 改 `android/`：`cd android && ./gradlew testDebugUnitTest`
- 改 Rust crate：`cd android/app/src/main/rust && cargo test`
- 改 `server/internal/web/`：额外跑 `cd tools/xsscheck && go run . ../server/internal/web`
- 交付前推荐组合：`cd android && ./gradlew testDebugUnitTest assembleDebug`

## 提交与分支约定

- 主分支：`master`（个人项目，本地改动自动同步推送）
- Commit 风格：**Conventional Commits**（硬规则）
  - type：`feat` / `fix` / `docs` / `refactor` / `chore` / `perf` / `test` / `style`
  - 常用 scope：`reader` / `bookparser` / `web` / `android` / `security` / `server` / `thumbnail` / `scanner` / `tags` / `book`
  - 多阶段工作：附 `(Phase N)` 或 `(Round N)` 后缀
  - 示例：`feat(reader): Android immersive mode with chrome auto-hide (Phase 5)`
- 重大改动流程：
  1. 先写 spec：`docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`
  2. 再用 writing-plans 技能落 plan：`docs/superpowers/plans/YYYY-MM-DD-<topic>.md`
  3. 按 plan 实施，每个 task 一个 commit

## 详细文档

完整索引见 [`docs/INDEX.md`](docs/INDEX.md)：API 端点表 / 关键文件指针 / 历史 spec & plan / 安全 Phase 1-8 总览 / 迁移与升级历史。
````

- [ ] **Step 3: 验证文件长度与内容**

Run: `wc -l AGENTS.md`
Expected: 行数 ≤ 300。

Read 文件确认：
- 4 子系统模块地图（Server / Android / Web / Rust）
- 命令清单（Server / Android / Rust / 静态分析）
- 编码规则（Go / Kotlin / Web / Rust 四块）
- 安全约定专区（路径三件套 / Bearer Token / Config 默认 / HTTP 头 / APK 签名 / libffmpeg SHA256 / Zip Slip / Rate Limit）
- 测试与验证（"修改后请跑相关子系统的测试"）
- 提交约定（Conventional Commits 硬规则 + scope 清单）
- 末尾"详细文档 → docs/INDEX.md"
- **不含** API 端点表、文件结构树、Phase 1-8 详细 spec 索引（这些都在 INDEX）

- [ ] **Step 4: Commit README + AGENTS 一起**

```bash
git add README.md AGENTS.md
git commit -m "docs: rewrite README + AGENTS, split detail to docs/INDEX.md

- README: slim to product intro + quickstart, 6 feature cards, point to AGENTS/INDEX
- AGENTS: 4-subsystem module map, encoding rules, security conventions, test/commit conventions
- New docs/INDEX.md (prior commit) holds API tables, file pointers, spec index, migration

Covers previously-undocumented features: book reader, Bearer Token auth,
bookmarks, Round 27-31 perf optimizations."
```

---

## Task 5: 验证三件套无信息重复 + 完整性

**Files:**
- 无文件改动；产出验证报告

**Interfaces:**
- Consumes: Task 2-4 已建/重写的三件套
- Produces: 一份"通过/失败"验证结果；失败则回到对应 Task 修复

- [ ] **Step 1: 验证信息不重复——命令**

```bash
# AGENTS 应有"cd server"，README 与 INDEX 不该有
grep -c "cd server && go build" README.md docs/INDEX.md AGENTS.md
```

Expected:
- `README.md`: 0
- `docs/INDEX.md`: 0（INDEX 的 Server 构建小节会有 `go build`，但不是同一命令字符串）
- `AGENTS.md`: ≥ 1

如 INDEX 命中了同一字符串，把 INDEX 的构建命令换成"见 AGENTS.md 常用命令"指针（实际不要这样——INDEX 的"构建与签名"主题节是合理的，包含命令也合理，但**命令清单**应在 AGENTS；INDEX 只在"构建与签名"主题节重复展示是为了完整性）。

**实际策略**：AGENTS 是"命令总集"，INDEX 的"构建与签名"主题节可以引用 AGENTS。如发现重复，把 INDEX 的对应小节改成"详见 `AGENTS.md` 常用命令"，只保留 INDEX 独有的 Release 签名流程（README/AGENTS 没有的）。

- [ ] **Step 2: 验证信息不重复——价值主张**

```bash
grep -c "把 PC 上散落" README.md docs/INDEX.md AGENTS.md
```

Expected: 仅 `README.md` ≥ 1；其他两个为 0。

- [ ] **Step 3: 验证信息不重复——API 端点表**

```bash
grep -c "/api/v1/folders" README.md docs/INDEX.md AGENTS.md
```

Expected:
- `README.md`: 0（README 不含 API 表）
- `docs/INDEX.md`: ≥ 1
- `AGENTS.md`: 0（或仅在示例中出现，如 commit message 范例）

如 AGENTS 命中 `/api/v1/...` 端点路径且不在 commit 示例中，删掉。

- [ ] **Step 4: 验证完整性——新功能覆盖**

```bash
# 阅读器
grep -l "TextReader\|bookparser\|/api/v1/books" README.md AGENTS.md docs/INDEX.md
# Expected: 三个文件都命中（README 在功能卡片，AGENTS 在模块地图，INDEX 在主题节）

# Bearer Token
grep -l "Bearer\|authToken\|AuthInterceptor" README.md AGENTS.md docs/INDEX.md
# Expected: 三个文件都命中

# 书签
grep -li "bookmark" README.md AGENTS.md docs/INDEX.md
# Expected: 三个文件都命中

# 性能优化
grep -l "cacheByDir\|sync.Pool\|hot path" README.md AGENTS.md docs/INDEX.md
# Expected: AGENTS + INDEX 命中（README 可不命中，因 README 不写技术细节）
```

- [ ] **Step 5: 验证指针双向可达**

- README 末尾应有 `[AGENTS.md](AGENTS.md)` 与 `[docs/INDEX.md](docs/INDEX.md)` 链接
- AGENTS 末尾应有 `[docs/INDEX.md](docs/INDEX.md)` 链接
- AGENTS 安全约定节应有指向 `docs/INDEX.md#迁移与升级历史` 与 `docs/INDEX.md#安全加固` 的锚点链接

```bash
grep "docs/INDEX.md" README.md AGENTS.md
grep "AGENTS.md" README.md
```

Expected:
- README 命中 `AGENTS.md` ≥ 1 + `docs/INDEX.md` ≥ 1
- AGENTS 命中 `docs/INDEX.md` ≥ 3（末尾 + 安全约定 2 处）

- [ ] **Step 6: 验证所有相对路径 spec/plan 引用存在**

从三件套中提取所有形如 `docs/superpowers/specs/YYYY-MM-DD-*.md`、`docs/superpowers/plans/YYYY-MM-DD-*.md`、`docs/YYYY-MM-DD-*.md` 的相对路径，逐条 Glob 验证。

如有任何不存在，回到对应 task 修正引用。

- [ ] **Step 7: 无需 commit（验证任务）**

如全部通过，整个 plan 完成。如有失败，回到对应 task 修复后重跑该 step。

---

## Self-Review

**Spec coverage 核对**（逐节检查 spec）：

- spec §1（背景与目标）— 不是实施项，是动机说明 ✅
- spec §2（三件套边界）— Task 2/3/4 的 Files 块对应 ✅
- spec §3（README 设计）— Task 3 完整覆盖 ✅
- spec §4（AGENTS 设计）— Task 4 完整覆盖 ✅
- spec §5（INDEX 设计）— Task 2 完整覆盖 ✅
- spec §6（实施顺序：INDEX → README → AGENTS → 验证 → 单 commit）— 本 plan 用 INDEX 单独 commit + README/AGENTS 合并 commit，原因是 Task 2 在 Task 3-4 前完成可独立验证；最终 commit 数 = 2（INDEX 一个、README+AGENTS 一个）。spec 说"一次性 commit"指的是"不要每个 step 一个 commit"，本 plan 符合精神 ✅
- spec §7（验收标准）— Task 5 完整覆盖 ✅
- spec §8（风险与回退）— Task 1 + Task 5 Step 6 覆盖了"路径引用笔误"风险 ✅

**Placeholder scan**: 全部 step 都有具体内容、具体命令、具体代码块；无 "TBD" / "implement later" / "类似 Task N"。✅

**Type/name consistency**:
- Browse delegate 文件名：spec 修正后是 `TagController.kt`，本 plan Task 1 验证清单 + Task 4 AGENTS 内容均用 `TagController.kt` ✅
- Round 30 spec 路径：spec 修正后是 `docs/2026-07-13-deadcode-*.md`（3 个文件），本 plan Task 1 验证清单 + Task 2 INDEX 内容均用这三个路径 ✅
- Bearer Token 路由组描述：README/AGENTS/INDEX 均统一为 "admin / system / media / books-image" ✅

**关键文件路径核对**：Task 1 step 2 的源码路径清单全部从 Glob 验证逻辑导出，覆盖了 INDEX 与 AGENTS 引用的全部文件 ✅
