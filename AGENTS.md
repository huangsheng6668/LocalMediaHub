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
go run . ../server/internal/web
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
