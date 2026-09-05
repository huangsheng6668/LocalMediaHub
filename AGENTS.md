# AGENTS.md — LocalMediaHub 工作手册

GitHub Repo: https://github.com/huangsheng6668/LocalMediaHub

LocalMediaHub 是 PC ↔ Android 局域网媒体串流系统：服务端扫描和提供媒体流，Android 客户端浏览和播放。本文档是 AI agent 与贡献者的工作手册：模块地图让你快速定位代码，编码规则与安全约定让你改对地方，命令清单让你跑得起来。需要查阅 API 端点表、文件结构树或历史 spec 时，跳到 [`docs/INDEX.md`](docs/INDEX.md)。

## 模块地图

### Server (Go / Echo v4)

- **入口**：`server/cmd/server/main.go`（`--headless` 切无窗口）
- **路由**：`server/internal/server/server.go`（`Server` struct 持有所有 service 引用；`Server.Stop` 关闭 scanner + tags DB）
- **Handler**：`server/internal/server/handler/*.go`（只做参数解析与响应，不写业务逻辑；通过 `Handler` struct 持有 service 依赖，**不使用全局变量**）
- **Service**：`server/internal/service/*.go`
  - `scanner.go` — 文件扫描（TTL 缓存 + fsnotify 递归监听 `StartWatching` + per-root 防抖 + `cacheByDir` 每目录索引 + per-root 并发 `g.SetLimit` + 输出按路径排序）；快照持久化（2026-09-03）：`scanner_snapshot.go` Scan 后原子落盘 `.data/scan_snapshot.json`（30s 写节流）+ `StartWatching` 启动 hydrate（`cacheTime=SavedAt` 复用 stale-while-revalidate，roots/扩展名身份键不匹配即弃用），`NewScannerWithSnapshot` 为生产构造、`NewScanner` 保留给测试
  - `tags.go` — 标签系统（SQLite WAL + busy_timeout + `SetMaxOpenConns(max(4,NumCPU))` + 索引 + 批量 IN 查询 + JSON→SQLite 自动迁移，CRUD 走 `s.mu.RLock`，`Close()` 关闭 DB）
  - `library.go` — 阅读状态与跨媒体收藏（SQLite WAL + busy_timeout + 状态自动派生（unread/reading/finished）+ 批量 decorations 查询 + 收藏快照 8KB 上限，CRUD 走 `s.mu.RLock`，`Close()` 关闭 DB）
  - `streaming.go` — 视频流（`http.ServeContent` + 256KB `BufferedReadSeeker`（修正 SeekCurrent 偏移），原生 Range）；转码路径（2026-09-03）：`transcode_encoder.go` 两级编码器探测（静态 `-encoders` + 运行时 testsrc 微编码，NVENC→QSV→AMF→libx264 兜底）+ `vcodec` allowlist 查表（客户端值永不进 argv）+ 会话并发信号量（`transcode.max_sessions`，缺省 3 / -1 不限），状态端点 `GET /api/v1/admin/transcode/status`；HLS 化（2026-09-03 Phase B）：`transcode_hls.go` 会话管理（`/api/v1/media/hls/playlist|segment`，会话键去重 + 空闲回收 + 4GiB LRU 缓存），Android 转码走 HLS 原生 seek（`VideoPlayerScreen` 的 URL-rebuild 特殊分支已删）；seek 锚定重转码（2026-09-06）：playlist/segment 接受 `?start=`，ffmpeg 输入端 `-ss` 秒起新会话 + 同源 running 会话自动取消（spec `2026-09-06-hls-seek-restart-design.md`），web 拖动到未转码区域即时重锚（`needsHlsRestart`），`transcodeStartOffset` 统一为**秒**
  - `thumbnail.go` — 缩略图（LANCZOS→Linear + MD5 缓存 + sync.Pool + hot path priority + `durations.json` ffprobe 缓存 + per-file `hotTracker`）
  - `hot_dirs.go` — per-directory 访问计数 LRU（容量 256，5min flush + Shutdown 原子落盘到 `hot_directories.json`），驱动冷启动分层预热（Tier1 hot 目录 → Tier2 根目录 → Tier3 懒生成）
  - `book.go` — BookService（章节解析、epub 图片字节读取、`GetChapterBlocks` 注入签名 `<img src>`）
  - `book_signing.go` — `BookSigner`（HMAC-SHA256 签名，绑定 clientIP + path + manifestID，无 expire，进程重启失效）
  - `bookparser/` 子包 — `parser.go` / `txt.go` / `txt_cache.go`（LRU 文本缓存 + 字节/字符偏移映射）/ `rules.go`（章节+卷规则）/ `epub.go` / `unsupported.go`
  - `path.go` — 路径校验三件套（见 [安全约定](#安全约定触碰前必读)）
- **Middleware**：`server/internal/server/middleware/*.go`
  - `cors.go` — CORS
  - `auth.go` — Bearer Token（header + query fallback，**SHA256 + constant-time 比较**）
  - `security_headers.go` — CSP / XFO / nosniff / Referrer-Policy（**必须在 CORS 之前挂载**）
  - `ratelimit.go` — per-route rate limit（挂在 scan trigger + delete）+ **LRU 容量上限**（默认 4096，防伪造 `X-Forwarded-For` 内存膨胀；确定性淘汰：expired-first → oldest lastSeen → insertion seq）
  - `private_net.go` — 私网/loopback 限制（pprof 用）
- **周边**：`server/internal/mdns/`（mDNS 注册）/ `server/internal/systray/`（系统托盘）/ `server/internal/gui/`（GUI 模式入口）/ `server/internal/web/`（前端静态资源，详见 [Web 管理界面](#web-管理界面)）/ `server/internal/ble/`（**实验性** BLE GATT 控制通道，**server=Central**：`protocol.go` 帧 codec + `central.go`（Scan/Connect/Send 状态机）+ `central_adapter.go`（tinygo bluetooth 栈适配，**默认编入单一 server 构建**，运行时无蓝牙适配器则非致命降级）+ `ble_health.go`（BleHealthMonitor 连续 Connect 失败卡死检测）与 `restart_windows.go`（`LMH_BLE_RESTART_TS` 冷却自重启清 WinRT 残留 GATT 状态，接线在 `internal/server/ble_autorestart_windows.go`）。`/api/v1/ble/scan|connect|send` HTTP handler 在 `internal/server/handler/ble.go`（`/api/v1/ble/*` 鉴权跟随路由组：有 `server.token` 走 Bearer，开放模式透传；GATT 数据链路 Phase 9 起为 v2 帧认证，密钥源 `ble.token` 优先、`server.token` 回退，**两者皆空 = 开放模式**——跳过握手、v1 无认证帧（2026-08-30），见 [安全约定](#安全约定触碰前必读)）。非致命启动，BLE 不可用 server 继续 Wi-Fi/HTTP。详见 [spec §11](docs/superpowers/specs/2026-07-26-ble-gatt-wiring-design.md)）
- **配置**：`server/config.yaml`（运行时）/ `server/config.example.yaml`（模板）

### Android (Kotlin / Compose)

- **Application**：`android/app/src/main/java/com/juziss/localmediahub/LocalMediaHubApplication.kt`（Hilt `@HiltAndroidApplication`）
- **Activity**：
  - `MainActivity.kt`（singleTop + NavHost + 视频续播调度 `checkPlaybackProgress` / `playVideo` / `resumeRequest`；启动请求 `POST_NOTIFICATIONS`）
  - `VideoPlayerActivity.kt`（独立视频播放 Activity，承载 PiP 浮窗 + RemoteAction 广播接收；`exitingFromPip` 标志区分"关闭浮窗"vs"切后台"，关闭浮窗后 `onStop` 自动 `finish()` 释放 ExoPlayer）
- **Screen**（`ui/screen/`）：`HomeScreen` / `ConnectionScreen` / `BrowseScreen` / `VideoPlayerScreen` / `ImagePreviewScreen` / `TextReaderScreen`（支持分章/全文滚动模式、实时百分比进度与沉浸/普通双进度条、BackHandler手势退出沉浸模式、左右触控翻页 + 右侧全书进度拖动条(松手跳章)） / `DownloadsScreen`
- **Component**（`ui/component/`）：
  - `home/`（首页卡片：Hero / Library / ContinueWatching / RecentMedia / Favorite）
  - `browse/`（浏览子组件：TopBar / SortMenu / SearchView / BrowseFilterChipsRow / DeleteConfirmDialog / QuickActionsDialog 等）
  - `reader/`（`ReaderSettingsSheet`（带可滚动与1400dp最大宽度） / `ReaderThemeWrapper` / `ReaderFontFamily`）
  - 通用：`ResumePlaybackDialog` / `PlayerGestureDetector` / `PlayerGestureHud`（音量/亮度 Pill HUD + seek ripple）/ `BrowseContent` / `GridContainers` / `MediaItems` / `TagComponents` / `VerticalScrollbar` / `theme/NoRippleIndication`
- **ViewModel**（`viewmodel/`）：
  - `HomeViewModel` / `BrowseViewModel` / `ConnectionViewModel` / `VideoPlayerViewModel` / `TextReaderViewModel`
  - Browse 通过 delegate 分发：`BrowseNavigator`（导航）/ `BrowseSorter`（排序）/ `SearchController`（搜索）/ `TagController`（标签）/ `FavoritesController`（收藏）/ `LibraryController`（阅读装饰与状态筛选）/ `DownloadController`（下载）/ `DeleteController`（删除，`deletePath` + `deletePaths`）/ `BrowseSharedState`（共享状态）
- **Data**（`data/`）：
  - `Models.kt`（`MediaFile` / `Folder` / `Tag` / `FavoriteEntry` / `ReadingStatus` / `LibraryDecoration` / `ServerFavorite` / `PlaybackProgressEntry` / `RecentMediaEntry` / `LastBrowseLocation`）
  - `MediaRepository.kt`（Retrofit 包装 + library 状态与收藏端点）
  - `RecentActivityStore.kt`（最近活动 + 浏览状态 + 播放进度）
  - `FavoritesStore.kt`（DataStore 收藏，三代 Gson 兼容反序列化，支持文件与目录）
  - `LibrarySyncManager.kt`（连线双向同步：收藏全量推拉合并 + 本地阅读进度迁移）
  - `ReadingMath.kt`（阅读百分比与已读完判定纯函数）
  - `DownloadsStore.kt` + `DownloadManager.kt` + `DownloadWorker.kt`（CoroutineWorker 前台服务下载 + Zip Slip 防护）
  - `ServerConfigStore.kt`（含 `authToken` / `bleToken`，均加密存储；`bleToken` 对应 server 的 `ble.token`，为空回退 `authToken`）
  - `RoutePath.kt`（浏览路径与系统/库模式标记）
- **Network**（`network/`）：Retrofit 接口 + OkHttp + `AuthInterceptor`（注入 Bearer Token）
- **BLE**（`ble/`，**实验性**，默认关闭，**Android=Peripheral**）：`BleProtocol`（与 server 对称的帧 codec）/ `BleConnectionStateMachine`（纯逻辑状态机，含 ADVERTISING）/ `BleController`（@Singleton 门控，开关+硬件可用性→状态机；`markConnected`/`markDisconnected` 由 HTTP 协调结果驱动）/ `BlePeripheralManager` 接口 + `AndroidBlePeripheralManager`（`BluetoothGattServer` + advertiser，Command Write + State Notify + CCCD）/ `BleToggleRule`。`data/BleApi.kt` 通过 Wi-Fi/HTTP 调 server 的 `/api/v1/ble/*` 协调连接。设置入口在 `ConnectionScreen`（开关 + 扫描列表 + 选设备连接 + 发送测试 + echo），状态经 `BleSettingsViewModel`。角色反转原因：Windows winrt Peripheral 不稳，PC 当 Central。蓝牙不可用时完全退回 Wi-Fi/HTTP（零退化）。详见 [spec §11](docs/superpowers/specs/2026-07-26-ble-gatt-wiring-design.md)
- **Native**（`native/`）：`NativeImageDecoder.kt` / `NativeExif.kt` / `NaturalSorter.kt` / `NativeDecoderFactory.kt`（Coil 集成）
- **Native libs**：`app/src/main/jniLibs/arm64-v8a/`（`liblocalmedia_native.so` Rust 输出 + `libffmpeg.so` 预编译）
- **构建**：`app/build.gradle.kts` 注册 `buildRustNative` task（详见 [Rust 原生解码](#rust-原生解码)）

### Web 管理界面

服务端内置 SPA，浏览器访问 server 地址（如 `http://localhost:8000`）即可。

- **公共层**：`server/internal/web/` 下 `app.js` / `boot.js` / `router.js` / `state.js` / `dom.js` / `api.js` / `toast.js` / `utils.js` / `library.js`（阅读状态与跨媒体收藏：筛选矩阵/徽章/DOM装饰/双向同步） / `scrollMemory.js`（双键 session 滚动记忆）
- **样式层**：`css/` 分层模块（加载顺序 `base` → `themes` → `layout` → `components` → `views/*`，`responsive.css` 必须最后加载以在层叠上压过视图规则）——2026-09 现代中性风重设计（spec `docs/superpowers/specs/2026-09-02-web-ui-redesign-design.md`）：7 套 `[data-theme]` chrome 主题（与阅读区主题独立分离），emoji 图标全部替换为内联 SVG
- **视图层**：`dashboard.js` / `browserView.js` / `bookshelf.js` / `bookmarksView.js` / `settings.js` / `videoPlayer.js` / `lightbox.js` / `delete.js` / `readerPrefs.js`
- **阅读器（Round 33 拆分，bus 解耦架构）**：`textReader.js`（编排主模块 ~577 行）+ 子模块
  - `bus.js`（事件总线 on/emit/off/EVT，零依赖）
  - `reader-state.js`（共享状态单例 + `setCurrentIdx` 触发 `chapter:changed`）
  - `progress.js`（进度计算 + 滚动章节推断，纯函数 + 阈值 120px）
  - `toc.js`（目录抽屉：渲染/高亮/开关/外部点击关闭，单一 drawerEl）
  - `bookmarks.js`（书签 tab + 当前章节弱标记）
  - `autoscroll.js`（自动滚动 rAF 面板）
  - `reader-settings.js`（阅读设置 dialog，emit `settings:changed`）
  - **注意**：`state.js` / `settings.js` 是全局 app 模块，**勿与** `reader-state.js` / `reader-settings.js` 混淆
- **测试**：`node --test`（用 `.test.mjs` 扩展名 + jsdom，详见 [测试与验证](#测试与验证)）
- **Token 集成**：`api.js` 的 `apiRequest()` 自动注入 Bearer header + 401 事件 → `app.js` 弹 token modal；sessionStorage 持久化
- **CSP 兼容**：无 inline `<script>`、无 inline `style="..."` 属性（已全量迁移为 CSS 类，`style-src 'self'` 不含 `'unsafe-inline'`；动态样式走 CSSOM 属性赋值）
- **无构建步骤**，跟随 server 静态服务；**vendor 第三方库**：`vendor/hls.min.js`（hls.js 1.5.20 + `.sha256` 校验，embed 于 `web.go`，spec 2026-09-03-hls-transcode-b2）——转码播放走 HLS（`hlsCompat.js` 三级策略：hls.js → 原生 → 旧 fMP4 管道兜底），`videoPlayer.js` 的 URL-rebuild seek 已删除

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
go run . ../../server/internal/web
```

### Web 单元测试（node:test + jsdom）

```bash
cd server/internal/web
node --test
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

- 模块化（每个视图一个 `.js` 文件），无构建步骤，ES module 原生 `import`/`export`
- 阅读器（`textReader.js`）已拆分为 bus 解耦架构（见 [Web 管理界面](#web-管理界面)）；新增子模块改动后跑 `cd server/internal/web && node --test`
- 零 inline `<script>`（CSP `script-src 'self'`）
- 统一通过 `api.js` 的 `apiRequest()` 发请求（自动注入 Bearer header + 401 事件）
- Token 通过 sessionStorage 持久化
- 涉及 `innerHTML` / `outerHTML` / `insertAdjacentHTML` / `document.write` 的代码必须带 `// XSS-SAFE:` 注释或调用 `escapeHtml()`，否则 `tools/xsscheck` 失败
- 测试文件用 `.test.mjs` 扩展名（package.json 无 `type: module`）；`import` 路径必须带 `.js` 扩展名

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

- 挂载路由组：admin / system / media / books-image / library
- 接受 header（`Authorization: Bearer <token>`）与 query（`?token=`）双 fallback（query 仅为 `<img src>` 这种无法加 header 的场景）
- **SHA256 + constant-time 比较**，防 timing attack 与 length leakage

### 认证覆盖（Phase 9）

- 媒体读端点（folders / videos / images / texts / search）挂 Bearer auth；空 token 开放模式透传

### BLE 帧认证（Phase 9 + 2026-08-29 专属密钥）

- `server/internal/ble/protocol.go` v2 帧（seq+HMAC）与双 nonce 握手，两端对称（`BleProtocol.kt`）
- **密钥源**：有效 BLE 密钥 = `ble.token`（优先）→ `server.token`（回退）；server 端 `config.BLEConfig.EffectiveToken`，Android 端 `BleController.resolveBleKey`，**两端规则必须对称**。两者皆空 → **开放模式**（2026-08-30）：跳过双 nonce 握手，数据帧走 v1 无认证（无 seq 防重放）；BLE 半径内任何设备可交换数据，与开放 HTTP 姿态一致，启动时打 WARN；配 `ble.token` 即恢复 v2 HMAC 认证
- 开放 LAN 模式（`server.token` 空）+ `ble.token` 非空 = HTTP 开放 + BLE 认证并存；`server.token` 与 `ble.token` 同时设置时 HTTP 与 BLE 各用独立密钥（Android 需单独填 BLE 密钥）
- Android 端 BLE 密钥存 `ServerConfigStore.bleToken`（加密），UI 入口在 BLE 设置卡（`BleChannelSection.BleKeyCard`）；`lan_pairing` 配对响应会携带 `ble_token` 自动配置
- spec：`docs/superpowers/specs/2026-08-29-ble-dedicated-token-design.md`

### Books 图片签名 token（Round 32 S2）

`server/internal/service/book_signing.go` + `server/internal/server/handler/books.go`：

- `<img src="/api/v1/books/image?path=...&manifest=...&sig=...">`，sig = HMAC-SHA256(serverSecret, clientIP + "|" + path + "|" + manifestID)
- 绑定 clientIP + path + manifestID，无 expire；进程重启 serverSecret 重生成，旧 sig 全部失效
- `/api/v1/books/image` 优先认 `?sig=`；`?token=` 作为 deprecated fallback（打 `[DEPRECATED]` warn）
- 新增 `/api/v1/books/sign-image` endpoint 供动态场景（lightbox）
- access log redact：`?token=` 替换为 `REDACTED`（redact 中间件挂在 Logger 之后 = 请求侧先执行）

### /debug/pprof 默认关闭（Round 32 S3）

- 默认不注册 pprof 路由（404）
- 显式开启：`config.debug.pprof: true` 或 `--debug-pprof` flag（flag 覆盖 config）
- 开启后仍受 `PrivateNetOnly` 中间件限制

### Config 默认安全（Phase 3）

- `scan.roots` 空 + `system.allowed_roots` 空 + `scan.auto_detect_roots: false` → 服务端**拒绝启动**
- 一次性 override：启动加 `--auto-detect-roots` flag
- 详见 [`docs/INDEX.md`](docs/INDEX.md#迁移与升级历史) "迁移与升级历史"

### HTTP 安全响应头

`server/internal/server/middleware/security_headers.go`：

- `X-Frame-Options: DENY`
- `X-Content-Type-Options: nosniff`
- `Referrer-Policy: no-referrer`
- `Content-Security-Policy`: `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; media-src 'self' blob:; connect-src 'self'; worker-src 'self' blob:`
  - `media-src blob:` / `worker-src blob:` 为 hls.js（MSE 播放）所需：`<video>` 的源是 `URL.createObjectURL` 产生的 `blob:` URL，hls.js 的 transmuxer worker 同样从 `blob:` 派生，CSP `'self'` 不匹配 `blob:` scheme，缺失会导致转码播放全黑（2026-09-06 修复）
- 中间件**必须在 CORS 之前挂载**
- 新增 inline `style="..."` 属性会破坏 CSP（`style-src 'self'` 无 `'unsafe-inline'`）——必须放进 CSS 类；动态样式用 `el.style.prop =`（CSSOM 不受限）

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

### Rate Limit（Phase 8 + Round 32 S1）

`server/internal/server/middleware/ratelimit.go` per-route 限流，挂在 scan trigger + delete 路由。**LRU 容量上限**（默认 4096，`RateLimitWithConfig(max, window, maxBuckets)`），确定性淘汰（expired-first → oldest lastSeen → insertion seq），防伪造 `X-Forwarded-For` 内存膨胀。

### Web SPA XSS 防护（Round 29 Phase 5 + Round 32 S4）

`tools/xsscheck/` 静态扫描所有 `innerHTML` / `outerHTML` / `insertAdjacentHTML` / `document.write` sink：

- 每个 sink 必须同行或上一行有 `// XSS-SAFE:` 注释，或调用 `escapeHtml()`
- 缺注释/未转义 → lint 失败（阻断构建）
- 覆盖 26 个 web 文件，run: `cd tools/xsscheck && go run . ../../server/internal/web`

### 触碰安全敏感代码前先看

- `docs/superpowers/specs/2026-07-10-security-audit-design.md`（主审计）
- `docs/superpowers/specs/2026-07-11-security-phase4-http-hardening-design.md`（HTTP 头）
- [`docs/INDEX.md`](docs/INDEX.md#安全加固) "安全加固" 主题节

## 测试与验证

**修改后请跑相关子系统的测试**（不要盲跑全部，按改动范围选）：

- 改 `server/`：`cd server && go test ./...`
- 改 `android/`：`cd android && ./gradlew testDebugUnitTest`
- 改 Rust crate：`cd android/app/src/main/rust && cargo test`
- 改 `server/internal/web/`：`cd server/internal/web && node --test` + 额外跑 `cd tools/xsscheck && go run . ../../server/internal/web`
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

完整索引见 [`docs/INDEX.md`](docs/INDEX.md)：API 端点表 / 关键文件指针 / 历史 spec & plan / 安全 Phase 1-9 总览 / 迁移与升级历史。
