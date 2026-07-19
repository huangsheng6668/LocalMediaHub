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
