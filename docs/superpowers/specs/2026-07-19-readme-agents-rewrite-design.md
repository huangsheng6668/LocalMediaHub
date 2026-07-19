# README + AGENTS 重写设计

**Date**: 2026-07-19
**Author**: brainstorming session
**Status**: design — pending implementation plan

## 1. 背景与目标

### 1.1 现状

`README.md` 与 `AGENTS.md` 在多轮迭代后已显著落后于代码：

- 未覆盖 **小说阅读器子系统**（`bookparser` / `book.go` / `TextReaderScreen` / web `textReader.js` + `bookshelf.js` + `readerPrefs.js`、7 套主题、字体嵌入、书签、章节解析、epub 图片内联）
- 未覆盖 **Bearer Token 认证**（middleware + Android `AuthInterceptor` + Web token modal + `/api/v1/books/image` query fallback）
- 未覆盖 **书签系统**（Web `bookmarksView.js` 取代 `tagsView.js`、Android reader 书签）
- 未覆盖 **Round 27-31 性能优化**（视频 seek 防抖、thumbnail deep perf、SQLite PRAGMA+index、scanner `cacheByDir`、gzip 中间件、`sync.Pool`、Round 30 死代码清理 5 批次）
- 未覆盖 **新增 API**：`/api/v1/books/info`、`/api/v1/books/chapter`、`/api/v1/books/image`
- 两个文件主题高度重叠（项目结构、编码规则、命令清单在两处各写一份），维护成本高

### 1.2 本次目标

本次重写同时完成三件事：

1. **同步现状** —— 把上述新功能补进文档
2. **重构结构** —— 重新设计文档骨架，按读者画像分工
3. **拆分** —— 三件套分工：README 精简、AGENTS 聚焦干活上下文、新建 `docs/INDEX.md` 承载详细参考

### 1.3 非目标

- 不重写 spec/plan 文档体系（`docs/superpowers/specs|plans/`）
- 不新增 docs/INDEX.md 以外的文档文件
- 不调整代码、不调整配置
- 不放截图占位（用户偏好：暂不放）

## 2. 文档三件套边界

| 文件 | 受众 | 长度目标 | 主要内容 | 不包含 |
|------|------|---------|---------|--------|
| `README.md` | 访客 / 用户 | ~150-200 行 | 价值主张、核心功能、快速上手、配置示例、技术栈 | API 端点表、文件结构树、安全头表、迁移细节 |
| `AGENTS.md` | AI agent / 贡献者 | ~250-300 行 | 项目定位、模块地图、命令、编码规则、安全约定、测试、提交约定 | API 端点表、文件结构树、spec/plan 索引、迁移细节 |
| `docs/INDEX.md` | 需要深挖的读者 | ~200-250 行 | 按主题组织的参考库（API、文件、spec/plan、迁移） | 价值主张、命令、编码规则 |

**指针策略**：
- README 末尾放"想了解更多？→ AGENTS.md / docs/INDEX.md"
- AGENTS 末尾放"详细文档 → docs/INDEX.md"
- INDEX 不反向指向 README/AGENTS（保持参考库纯粹）

## 3. README.md 设计

### 3.1 文件骨架

```
# LocalMediaHub
GitHub 链接徽章 + 1 句价值主张

## 系统架构（保留 ASCII 图，精简版）

## 核心功能（6 张卡片）
  1. 媒体浏览与播放
  2. 小说阅读器（txt / epub）
  3. 续播与上下文恢复
  4. 收藏 / 标签 / 书签
  5. 离线下载 / 画中画
  6. 安全加固（Bearer Token + CSP + APK 签名守卫）

## 技术栈（紧凑表：Server / Android / Web / Rust 四行）

## 快速上手
  1. 启动 Server
  2. 配置 config.yaml
  3. 编译 Android APK（含 release 签名守卫提示）
  4. 连接

## 配置示例（YAML 片段）

## 项目状态（开发阶段，自动同步 master，MIT License）

## 想了解更多？
  - 给 AI/贡献者：AGENTS.md
  - 完整文档索引：docs/INDEX.md
```

### 3.2 各节细节

**价值主张**（标题下 1 句）：
> 把 PC 上散落的视频、图片、小说串流到 Android，在局域网里随时翻看。

**架构图**（保留 ASCII，与现有版本基本一致，删除已过时细节）：
- Server 端列：全盘浏览 / 视频流 (Range) / 缩略图 / 标签 + 书签 / 书籍阅读器 / mDNS / 系统托盘 / Bearer Token
- Android 端列：文件浏览器 / ExoPlayer / Coil / NSD / TextReader / 收藏+标签+书签 / PiP
- 中间：HTTP/REST 局域网

**核心功能卡片**（每张 ~3 行，混合"能干什么"与"技术亮点"）：

1. **媒体浏览与播放** —— 自动检测 Windows 驱动器；视频 Range 流（256KB 缓冲）；缩略图 LANCZOS + MD5 磁盘缓存；fsnotify 实时监听。
2. **小说阅读器（txt / epub）** —— 服务端章节解析与图片内联；7 套主题（AUTO + 6 明暗）；嵌入 LXGW WenKai + Noto Serif SC 字体；字号/行距/段距/缩进/字体族（V2 设置）；自动滚动、书签、章节列表、沉浸模式、首字下沉、章节末标记、淡入过渡。
3. **续播与上下文恢复** —— 跨入口（浏览 / 收藏 / 下载 / 最近打开）打开同一视频自动从上次进度恢复；进度 ≥95% 弹窗"继续 / 从头开始"；自动续播时右下角 3 秒"从头开始"chip。
4. **收藏 / 标签 / 书签** —— Android DataStore 收藏；服务端 SQLite 标签 + 文件关联；Web 端书签视图。
5. **离线下载 / 画中画** —— WorkManager 前台服务常驻通知栏；单文件 + 目录 ZIP 流式下载解压（含 Zip Slip 防护）；视频 PiP 浮窗（独立 Activity，关闭浮窗自动释放 ExoPlayer）。
6. **安全加固** —— Bearer Token（admin / system / media / books-image 路由组强制）；CSP / X-Frame-Options / nosniff / Referrer-Policy 响应头；APK release 签名 fail-fast 守卫；libffmpeg SHA256 preBuild 校验。

**技术栈表**（紧凑 4 行）：

| 层 | 技术 |
|---|---|
| Server | Go 1.25+ / Echo v4 / modernc.org/sqlite (pure-Go) / fsnotify / getlantern/systray |
| Android | Kotlin / Jetpack Compose / Media3 (ExoPlayer + MediaSession) / Coil 3 / WorkManager / Hilt |
| Web 管理界面 | 模块化 JS SPA（无构建步骤，CSP 兼容） |
| 原生解码 | Rust 2021 + cargo-ndk → arm64-v8a（pure-Rust crates）+ 预编译 libffmpeg.so |

**快速上手 4 步**：

1. **启动 Server**
   - `cd server && go build -o LocalMediaHub.exe ./cmd/server`
   - `./LocalMediaHub.exe`（GUI + 系统托盘）或 `./LocalMediaHub.exe --headless`
   - Windows 用户直接双击 exe
   - 中国大陆代理：`GOPROXY=https://goproxy.cn,direct go mod tidy`

2. **配置 config.yaml**（最小示例）：
   ```yaml
   server:
     host: "0.0.0.0"
     port: 8000
     token: "<可选：开启 Bearer Token 后填入>"

   scan:
     video_extensions: [.mp4, .mkv, .avi, .mov]
     image_extensions: [.jpg, .jpeg, .png, .gif, .webp]
     # roots 留空 + auto_detect_roots: true 会自动检测 Windows 驱动器
     # 若两者均未配置，服务端拒绝启动（fail-safe，详见 docs/INDEX.md "迁移"）

   system:
     allowed_roots:
       - "D:/Media"
   ```

3. **编译 Android APK**
   - `cd android && ./gradlew assembleDebug`
   - Release 构建默认要求 `keystore.properties`，未配置会失败（防误用 debug 签名发布）
   - 仅本地调试：`./gradlew assembleRelease -PallowDebugSigning=true`
   - 详见 docs/INDEX.md "构建与签名"

4. **连接**
   - 自动：优先尝试上次成功连接；失败回退 NSD 自动发现（需同一 WiFi + `CHANGE_WIFI_MULTICAST_STATE` 权限）
   - 手动：输入 PC 局域网 IP（如 `192.168.1.100:8000`）
   - 如服务端配置了 token，Android 与 Web 都会弹输入框

**项目状态**：
- 开发阶段，本地改动自动同步推送至 GitHub `master`
- License: MIT

**想了解更多？**
- 给 AI / 贡献者的工作手册：`AGENTS.md`
- 完整文档索引（API 端点表、文件结构、安全 spec、迁移指南）：`docs/INDEX.md`

### 3.3 与现有 README 的差异

**保留**：价值主张、ASCII 架构图、快速上手续列、配置示例片段、技术栈表。

**砍掉（搬到 INDEX）**：
- API 端点全表（5 张子表）
- 项目结构树（~50 行）
- 安全响应头表
- 升级迁移细节（Phase 3 fail-safe 升级路径）
- 原生库编译详细命令
- "开发与同步"独立小节（合并到"项目状态"）

**新增**：
- 小说阅读器卡片（核心功能 #2）
- Bearer Token 提及（核心功能 #6 + 配置示例 token 字段）
- 书签提及（核心功能 #4）
- 性能优化亮点融入各卡片（不单列）
- "想了解更多？"指针段

## 4. AGENTS.md 设计

### 4.1 文件骨架

```
# AGENTS.md — LocalMediaHub 工作手册
开篇 1 段：项目定位 + "读这一份就够干活"

## 模块地图
  ### Server (Go / Echo v4)
  ### Android (Kotlin / Compose)
  ### Web 管理界面（SPA）
  ### Rust 原生解码

## 常用命令
  ### Server
  ### Android
  ### Rust
  ### Web 管理界面（无独立构建）
  ### 静态分析

## 编码规则
  ### Go (Server)
  ### Kotlin (Android)
  ### Web (前端 JS)
  ### Rust

## 安全约定（触碰前必读）

## 测试与验证

## 提交与分支约定

## 详细文档 → docs/INDEX.md
```

### 4.2 各节细节

#### 开篇段

> LocalMediaHub 是 PC ↔ Android 局域网媒体串流系统。本文档是 AI agent 与贡献者的工作手册：模块地图让你快速定位代码，编码规则与安全约定让你改对地方，命令清单让你跑得起来。需要查阅 API 端点表、文件结构树或历史 spec 时，跳到 `docs/INDEX.md`。

#### 模块地图

**Server (Go / Echo v4)**：
- 入口：`cmd/server/main.go`（`--headless` 切无窗口）
- 路由：`internal/server/server.go`（持有所有 service 引用，`Server.Stop` 关闭 scanner + tags DB）
- Handler：`internal/server/handler/*.go`（参数解析 + 响应；不写业务逻辑）
- Service：`internal/service/*.go`（业务逻辑所在）
  - `scanner.go` 文件扫描（TTL 缓存 + fsnotify 递归监听 + 2s 防抖 + `cacheByDir` 每目录索引）
  - `tags.go` 标签系统（SQLite + RWMutex，JSON→SQLite 自动迁移）
  - `streaming.go` 视频流（`http.ServeContent` + 256KB `BufferedReadSeeker`，原生 Range）
  - `thumbnail.go` 缩略图（LANCZOS + MD5 缓存 + sync.Pool + hot path priority）
  - `book.go` 图书服务（章节解析 + epub 图片字节读取 + 路径重写为 `/api/v1/books/image`）
  - `bookparser/` 子包（`parser.go` / `txt.go` / `epub.go` / `unsupported.go`）
  - `path.go` 路径校验三件套（见安全约定）
- Middleware：`internal/server/middleware/*.go`
  - `cors.go` CORS
  - `auth.go` Bearer Token（header + query fallback）
  - `security_headers.go` CSP/XFO/nosniff/Referrer-Policy（必须在 CORS 之前挂载）
  - `ratelimit.go` per-route rate limit（scan trigger + delete）
- 周边：`internal/mdns/`、`internal/systray/`、`internal/gui/`、`internal/web/`（前端静态资源）
- 配置：`config.yaml` 运行时，`config.example.yaml` 模板

**Android (Kotlin / Compose)**：
- Application：`LocalMediaHubApplication.kt`（Hilt `@HiltAndroidApplication`）
- Activity：
  - `MainActivity.kt`（singleTop + NavHost + 续播调度 + POST_NOTIFICATIONS 权限请求）
  - `VideoPlayerActivity.kt`（独立视频 Activity，承载 PiP；`onStop` 判定关闭浮窗后 `finish()` 释放 ExoPlayer）
- Screen（`ui/screen/`）：Home / Connection / Browse / VideoPlayer / ImagePreview / **TextReader** / Downloads
- Component（`ui/component/`）：home / browse / **reader**（`ReaderSettingsSheet` / `ReaderThemeWrapper` / `ReaderFontFamily`）/ 通用组件
- ViewModel（`viewmodel/`）：
  - HomeViewModel / BrowseViewModel / ConnectionViewModel / VideoPlayerViewModel / **TextReaderViewModel**
  - Browse 通过 delegate 分发：Navigator / Sorter / Search / Tag / Favorites / Download / Delete / SharedState
- Data（`data/`）：Models / Repository / DataStore（Favorites / RecentActivity / PlaybackProgress / Downloads / ServerConfig / RoutePath）+ DownloadWorker（WorkManager 前台服务 + Zip Slip 防护）
- Network（`network/`）：Retrofit + OkHttp + **AuthInterceptor**（注入 Bearer Token）
- Native（`native/`）：Kotlin JNI 入口（NativeImageDecoder / NativeExif / NaturalSorter / NativeDecoderFactory for Coil）
- Rust crate：`src/main/rust/`（详见下文 Rust 小节）
- Native 库：`src/main/jniLibs/arm64-v8a/`（`liblocalmedia_native.so` Rust 输出 + `libffmpeg.so` 预编译）

**Web 管理界面（SPA）**：
- 公共层：`app.js` / `router.js` / `state.js` / `dom.js` / `api.js`
- 视图层：`dashboard.js` / `browserView.js` / **`bookshelf.js`** / **`textReader.js`** / **`readerPrefs.js`** / **`bookmarksView.js`**（取代 `tagsView.js`）/ `settings.js` / `videoPlayer.js` / `lightbox.js` / `delete.js` / `toast.js` / `utils.js`
- Token 集成：`api.js` 注入 Bearer header + 401 事件 + `app.js` token modal（sessionStorage 持久化）
- CSP 兼容：无 inline script；inline `style=` 属性暂留 `'unsafe-inline'`（待 Web UI XSS 整改）
- 无构建步骤，跟随 server 静态服务

**Rust 原生解码**：
- crate：`localmedia_native` @ `android/app/src/main/rust/`
- 依赖：pure-Rust（`jpeg-decoder` / `image-png` / `webp` / `kamadak-exif` / `fast-image-resize`），无 C 依赖
- 构建：`cargo ndk -t arm64-v8a -o jniLibs/ build --release`，由 Gradle `buildRustNative` task 在 `preBuild` 阶段自动调用
- 输出：`liblocalmedia_native.so`
- JNI 桥：`src/jni_bridge/`（`decoders.rs` / `exif_jni.rs` / `natural_sort_jni.rs`）
- 通道约定：Android `ARGB_8888` == NDK `RGBA_8888`，解码器直接 `copy_from_slice`，无需通道重排
- `libffmpeg.so` 为预编译产物（不参与 Rust 构建链），由 Android `preBuild` 校验 `.sha256`

#### 常用命令

**Server**：
```
cd server
go build -o LocalMediaHub.exe ./cmd/server
./LocalMediaHub.exe              # GUI + 系统托盘
./LocalMediaHub.exe --headless   # 无窗口
GOPROXY=https://goproxy.cn,direct go mod tidy   # 中国大陆代理
```

**Android**：
```
cd android
./gradlew assembleDebug                          # Debug
./gradlew assembleRelease                        # Release（默认要求 keystore.properties）
./gradlew assembleRelease -PallowDebugSigning=true   # 仅本地调试
./gradlew testDebugUnitTest assembleDebug        # 交付前推荐组合
```

**Rust**（正常构建无需手动执行，Gradle 自动调用）：
```
rustup target add aarch64-linux-android
cargo install cargo-ndk
cd android/app/src/main/rust
cargo ndk -t arm64-v8a -o jniLibs/ build --release
cargo test                                       # Rust 单测
```

**静态分析**（XSS 覆盖率检查）：
```
cd tools/xsscheck
go run . ../../internal/web
```

#### 编码规则

**Go (Server)**：
- Handler 通过 `Handler` struct 持有 service 依赖，**不使用全局变量**
- 业务逻辑放 `internal/service/`，handler 只做参数解析与响应
- 所有文件访问必须经过路径校验三件套（见安全约定）
- 受限端点（system/* 与 media/*）必须落在 `system.allowed_roots` 边界内
- 列表返回用 `make([]T, 0)` 初始化，避免 JSON 序列化为 `null`

**Kotlin (Android)**：
- UI: Jetpack Compose，MVVM（ViewModel + Repository）
- 网络: Retrofit + OkHttp，`AuthInterceptor` 注入 Bearer Token
- 图片: Coil 3（含 `NativeDecoderFactory`）
- 视频: Media3 (ExoPlayer + MediaSession) + 预编译 libffmpeg.so
- 异步: Coroutines
- 后台: WorkManager + 前台服务（`FOREGROUND_SERVICE_DATA_SYNC`）用于离线下载
- DI: Hilt
- **已知 Compose 版本错配**: foundation 1.11.x + material3 1.3.1 的 `clickable` 在 release R8 构建下崩溃，由 `theme/NoRippleIndication.kt` 提供无 ripple 的 `IndicationNodeFactory` 解决；material3 升级到 1.4.x+ 后可移除

**Web (前端 JS)**：
- 模块化（每个视图一个 `.js` 文件），无构建步骤
- 零 inline `<script>`（CSP `script-src 'self'`）；style 的 `'unsafe-inline'` 待 Phase 5 Web UI XSS 整改后移除
- 统一通过 `api.js` 的 `apiRequest()` 发请求（自动注入 Bearer header + 401 事件）
- Token 通过 sessionStorage 持久化
- 涉及 innerHTML 的代码需通过 `tools/xsscheck` 静态分析

**Rust**：
- 仅使用 pure-Rust crates（无 C 依赖，便于交叉编译）
- JNI 桥统一放 `src/jni_bridge/`
- Bitmap 写入直接 `copy_from_slice`（通道顺序约定见模块地图）
- 编译由 Gradle `buildRustNative` 自动驱动，**不要手动覆盖 jniLibs 产物**

#### 安全约定（触碰前必读）

**路径校验三件套**（`internal/service/path.go`）：
- `ValidatePath` —— 常规媒体扫描根目录校验
- `ValidateSystemMediaAccess` —— `/api/v1/system/*` 端点专用，强制 `system.allowed_roots` 边界
- `ValidateAccessibleMediaPath` —— `/api/v1/media/*` 统一媒体端点专用，覆盖扫描根目录与 `system.allowed_roots`

**Bearer Token 认证**（`middleware/auth.go`）：
- 挂载路由组：admin / system / media / books-image
- 接受 header（`Authorization: Bearer <token>`）与 query（`?token=`）双 fallback（query 仅为图片标签 `<img src>` 这种无法加 header 的场景）
- 常量时间比较，防 timing attack

**Config 默认安全**（Phase 3）：
- `scan.roots` 空 + `system.allowed_roots` 空 + `scan.auto_detect_roots: false` → 服务端**拒绝启动**
- 一次性 override：启动加 `--auto-detect-roots` flag
- 详见 `docs/INDEX.md` "迁移与升级历史"

**HTTP 安全响应头**（`middleware/security_headers.go`）：
- `X-Frame-Options: DENY`（防 clickjacking）
- `X-Content-Type-Options: nosniff`（防 MIME 嗅探）
- `Referrer-Policy: no-referrer`（防外链泄漏）
- `Content-Security-Policy`: `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'; media-src 'self'; connect-src 'self'`
- 中间件**必须在 CORS 之前挂载**
- 已知 TODO: `style 'unsafe-inline'` 待 Web UI XSS 整改后移除

**APK 签名守卫**（Phase 7）：
- Release 构建默认 fail-fast：无 `keystore.properties` 即拒
- `android:allowBackup="false"`（防 adb backup 提取）
- `-PallowDebugSigning=true` 仅本地调试；**切勿公开分发 debug 签名 APK**（Chain-I 供应链攻击风险）

**libffmpeg SHA256 校验**（Phase 2）：
- `preBuild` 阶段比对 `jniLibs/arm64-v8a/libffmpeg.so.sha256`
- 缺失会触发可操作的错误信息
- SBOM 与 CVE 审计见 `docs/superpowers/specs/2026-07-11-security-phase2-libffmpeg-design.md`

**Zip Slip 防护**：
- `DownloadWorker` 解压前校验每个 entry 路径不越界目标目录

**Rate Limit**（Phase 8）：
- `middleware/ratelimit.go` per-route 限流，挂在 scan trigger + delete 路由

**触碰安全敏感代码前先看**：
- `docs/superpowers/specs/2026-07-10-security-audit-design.md`（主审计）
- `docs/superpowers/specs/2026-07-11-security-phase4-http-hardening-design.md`（HTTP 头）
- `docs/INDEX.md` "安全加固" 主题节

#### 测试与验证

**修改后请跑相关子系统的测试**（不要盲跑全部，按改动范围选）：

- 改 `server/`：`cd server && go test ./...`
- 改 `android/`：`cd android && ./gradlew testDebugUnitTest`
- 改 Rust crate：`cd android/app/src/main/rust && cargo test`
- 改 `server/internal/web/`：额外跑 `cd tools/xsscheck && go run . ../server/internal/web`
- 交付前推荐组合：`cd android && ./gradlew testDebugUnitTest assembleDebug`

#### 提交与分支约定

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

#### 详细文档

完整索引见 `docs/INDEX.md`：API 端点表 / 文件结构树 / 历史 spec & plan / 安全 Phase 1-8 总览 / 迁移与升级历史。

### 4.3 与现有 AGENTS.md 的差异

**保留**：项目定位、技术栈、Go/Kotlin 编码规则、核心架构描述。

**砍掉（搬到 INDEX）**：
- 超长文件结构树（~80 行）
- 16 项核心功能详述（README 已覆盖；INDEX 按主题重写）
- 已知 Round 编号细节

**新增**：
- Web 管理界面模块（含 bookshelf / textReader / readerPrefs / bookmarksView）
- Rust 模块独立成节
- **图书阅读器**子系统（BookService / bookparser / TextReaderScreen / Web reader）
- **Bearer Token 认证**工作流
- **书签系统**
- **安全约定专区**（整合分散的安全信息）
- **测试与验证**命令清单（含 xsscheck）
- **提交与分支约定**（Conventional Commits 硬规则）
- 性能优化产物融入模块地图（`cacheByDir` / sync.Pool / hot path priority / gzip 中间件）

## 5. docs/INDEX.md 设计

### 5.1 文件骨架

```
# docs/INDEX.md — LocalMediaHub 详细文档索引
开篇 1 段 + 顶部快速跳转目录

## 主题：媒体浏览与播放
## 主题：小说阅读器
## 主题：标签 / 收藏 / 书签
## 主题：安全加固
## 主题：性能优化
## 主题：Android 体验
## 主题：Web 管理界面
## 主题：构建与签名
## 主题：测试
## 主题：迁移与升级历史
```

### 5.2 各主题节内容

每个主题节统一结构：
1. **API 端点**（适用时，按主题分组列，含方法/路径/说明/是否需 Token）
2. **关键文件**（相对路径）
3. **相关 spec/plan**（相对路径，列 `docs/superpowers/specs|plans/` 下相关文件）

#### 媒体浏览与播放

**API 端点**：
| 方法 | 路径 | 说明 | 需 Token |
|---|---|---|---|
| GET | `/api/v1/folders` | 根文件夹列表 | 否 |
| GET | `/api/v1/folders/{path}/browse` | 浏览指定目录 | 否 |
| GET | `/api/v1/videos` | 视频列表（分页） | 否 |
| GET | `/api/v1/images` | 图片列表（分页） | 否 |
| GET | `/api/v1/videos/{path}/stream` | 视频流（Range） | 否 |
| GET | `/api/v1/images/{path}/thumbnail` | 缩略图 | 否 |
| GET | `/api/v1/images/{path}/original` | 原图 | 否 |
| GET | `/api/v1/search` | 搜索（支持 path 限定） | 否 |
| GET | `/api/v1/media/thumbnail` | 绝对路径缩略图 | 是 |
| GET | `/api/v1/media/original` | 绝对路径原图 | 是 |
| GET | `/api/v1/media/stream` | 绝对路径视频流（Range） | 是 |
| GET | `/api/v1/media/duration` | 媒体时长 | 是 |

**关键文件**：
- `server/internal/service/scanner.go` / `streaming.go` / `thumbnail.go` / `path.go`
- `server/internal/server/handler/folders.go` / `videos.go` / `images.go` / `media.go` / `search.go`

**相关 spec/plan**：
- `docs/superpowers/specs/2026-07-05-server-perf-design.md`
- `docs/superpowers/specs/2026-07-08-thumbnail-pipeline-perf-design.md`
- `docs/superpowers/specs/2026-07-09-video-seek-perf-design.md`
- `docs/superpowers/specs/2026-07-10-thumbnail-deep-perf-design.md`
- `docs/superpowers/specs/2026-07-14-perf-round31-design.md`

#### 小说阅读器（txt + epub）

**API 端点**：
| 方法 | 路径 | 说明 | 需 Token |
|---|---|---|---|
| GET | `/api/v1/books/info?path=<abs>` | 图书元信息（标题 / 章节列表 / 总字数） | 否 |
| GET | `/api/v1/books/chapter?path=<abs>&index=<n>` | 章节内容（blocks 数组，含文本与图片块） | 否 |
| GET | `/api/v1/books/image?path=<abs>&manifest=<id>` | epub 内部图片字节（`<img>` 标签使用） | 是 |

**关键文件**：
- Server：`server/internal/service/book.go` / `bookparser/{parser,txt,epub,unsupported}.go` / `server/internal/server/handler/books.go`
- Android：`android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt` / `ui/component/reader/{ReaderSettingsSheet,ReaderThemeWrapper,ReaderFontFamily}.kt` / `viewmodel/TextReaderViewModel.kt`
- Web：`server/internal/web/textReader.js` / `bookshelf.js` / `readerPrefs.js`

**阅读体验特性**：
- 7 套主题（AUTO + 日间 × 3 + 夜间 × 3）
- 字体嵌入：LXGW WenKai + Noto Serif SC woff2
- V2 设置：字号 / 行距 / 段距 / 首行缩进 / 字体族
- 自动滚动、书签、章节列表、沉浸模式（chrome 自动隐藏）、首字下沉、章节末标记、淡入过渡

**相关 spec/plan**：
- `docs/superpowers/specs/2026-07-17-text-reader-design.md`
- `docs/superpowers/specs/2026-07-17-text-reader-c-phase-design.md`
- `docs/superpowers/specs/2026-07-18-epub-image-inline-design.md`
- `docs/superpowers/specs/2026-07-18-reader-ui-redesign-design.md`
- 对应 plans 同名前缀

#### 标签 / 收藏 / 书签

**API 端点（标签，需 Token: 否）**：
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

**关键文件**：
- Server：`server/internal/service/tags.go` / `server/internal/server/handler/tags.go`（SQLite 持久化 + PRAGMA + index + 批量 IN 查询）
- Android：`android/app/src/main/java/com/juziss/localmediahub/data/FavoritesStore.kt` + `viewmodel/TagController.kt`
- Web：`server/internal/web/bookmarksView.js`（取代 `tagsView.js`）

**相关 spec/plan**：
- `docs/superpowers/specs/2026-07-10-security-audit-design.md`（SQL 注入审计）

#### 安全加固

**Phase 1-8 总览**：
| Phase | 主题 | spec 路径 | 状态 |
|---|---|---|---|
| 1 | Bearer Token auth | `docs/superpowers/specs/2026-07-10-security-audit-design.md` | 完成 |
| 2 | libffmpeg SBOM + SHA256 + CVE 审计 | `docs/superpowers/specs/2026-07-11-security-phase2-libffmpeg-design.md` | 完成 |
| 3 | config 默认安全（auto_detect_roots fail-fast） | `docs/superpowers/specs/2026-07-10-security-phase3-config-defaults-design.md` | 完成 |
| 4 | HTTP 加固（CSP/XFO/nosniff/Referrer-Policy） | `docs/superpowers/specs/2026-07-11-security-phase4-http-hardening-design.md` | 完成 |
| 5 | XSS 静态分析工具 xsscheck | `docs/superpowers/specs/2026-07-11-security-phase5-xss-lint-design.md` | 完成 |
| 6 | CI | — | 未启动 |
| 7 | APK 签名 fail-fast + allowBackup=false | `docs/superpowers/specs/2026-07-10-security-phase7-apk-signing-design.md` | 完成 |
| 8 | 杂项 P2（rate limit / blocked roots / ffmpeg kill on disconnect / sanitize path errors） | `docs/superpowers/specs/2026-07-11-security-phase8-misc-p2-design.md` | 完成 |

**安全响应头表**：

| 头 | 值 | 缓解 |
|---|---|---|
| `X-Frame-Options` | `DENY` | Clickjacking |
| `X-Content-Type-Options` | `nosniff` | MIME 嗅探 |
| `Referrer-Policy` | `no-referrer` | 外链泄漏 |
| `Content-Security-Policy` | `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'; media-src 'self'; connect-src 'self'` | XSS 数据 exfiltration |

**当前已知 TODO**：
- CSP `style 'unsafe-inline'`（待 Web UI XSS 整改）
- HSTS（仅 HTTPS 下有效，TLS 留作未来）
- Permissions-Policy（项目不用相机/麦克风/地理位置）

**关键文件**：
- `server/internal/server/middleware/auth.go` / `security_headers.go` / `ratelimit.go`
- `server/internal/service/path.go`
- `android/app/build.gradle.kts`（签名守卫）
- `tools/xsscheck/`

#### 性能优化

**Round 27-31 清单**：
- **Round 27**：视频 seek 防抖（drop ForwardingPlayer debounce 层）— spec: `docs/superpowers/specs/2026-07-09-video-seek-perf-design.md`
- **Round 28**：thumbnail deep perf（ffmpeg pipe / BiLinear / encode helper）+ folder search index（`cacheDirs` / `GetCachedDirs` / `filterDirsByScope`）— spec: `docs/superpowers/specs/2026-07-10-thumbnail-deep-perf-design.md`
- **Round 30**：死代码清理 5 批次（server / android / web / deps / R8 fullMode）— spec: `docs/2026-07-13-deadcode-audit-design.md` + `docs/2026-07-13-deadcode-cleanup-design.md` + 报告 `docs/2026-07-13-deadcode-audit-report.md`
- **Round 31**：server SQLite PRAGMA + index + scanner `cacheByDir` + gzip 中间件 + `sync.Pool` + hot path priority — spec: `docs/superpowers/specs/2026-07-14-perf-round31-design.md`

**Android 性能 spec**（按主题归类）：
- `docs/superpowers/specs/2026-07-01-android-memory-performance-design.md`
- `docs/superpowers/specs/2026-07-04-native-rust-rewrite-design.md`
- `docs/superpowers/specs/2026-07-04-android-network-cache-design.md`
- `docs/superpowers/specs/2026-07-06-okhttp-json-cache-design.md`
- `docs/superpowers/specs/2026-07-07-exoplayer-state-preservation-design.md`
- `docs/superpowers/specs/2026-07-07-apk-size-optimization-design.md`

#### Android 体验

**PiP（多 Activity 架构）**：
- 独立 `VideoPlayerActivity` 承载 PiP 浮窗
- `VideoPlayerIntentBuilder` 构造启动 Intent
- `PipController` + `PipControllerStore` + `PipActionReceiver` 处理 PiP action
- `exitingFromPip` 标志区分"关闭浮窗"vs"切后台"，关闭浮窗后 `onStop` 自动 `finish()` 释放 ExoPlayer

**续播**：
- `RecentActivityStore` 持久化播放进度
- `ResumePlaybackDialog` + `VideoOpenAction` sealed class + `ResumePlaybackRequest`
- 跨入口恢复：Browse / Favorites / Downloads / Recent / 续播 chip

**批量选择**：
- `BrowseContent` 长按进入选择模式
- `deletePaths` 批量删除入口
- 批量下载到本地

**原生解码**：
- Rust crate 入口（见 AGENTS 模块地图）
- `NativeDecoderFactory` 集成到 Coil
- EXIF orientation 自动校正

**Compose workaround**：
- `theme/NoRippleIndication.kt` 解决 foundation 1.11.x + material3 1.3.1 错配（release R8 构建崩溃）
- material3 升级到 1.4.x+ 后可移除

**相关 spec/plan**（关键）：
- `docs/superpowers/specs/2026-07-08-android-pip-multi-activity-design.md`
- `docs/superpowers/specs/2026-07-02-android-state-persistence-design.md`
- `docs/superpowers/specs/2026-07-07-video-resume-from-all-entries-design.md`
- `docs/superpowers/specs/2026-07-04-browse-decouple-viewmodel-design.md`
- `docs/superpowers/specs/2026-07-06-browseviewmodel-delegates-design.md`
- `docs/superpowers/specs/2026-07-04-native-rust-rewrite-design.md`
- `docs/superpowers/specs/2026-07-05-native-security-hardening-design.md`

#### Web 管理界面

**模块结构**：
- 公共层：`app.js` / `router.js` / `state.js` / `dom.js` / `api.js`
- 视图层：`dashboard.js` / `browserView.js` / `bookshelf.js` / `textReader.js` / `readerPrefs.js` / `bookmarksView.js` / `settings.js` / `videoPlayer.js` / `lightbox.js` / `delete.js` / `toast.js` / `utils.js`

**Token 集成**：
- `api.js` `apiRequest()` 自动注入 Bearer header
- 401 响应触发事件，`app.js` 弹 token modal
- sessionStorage 持久化

**CSP 兼容要点**：
- 无 inline `<script>`
- inline `style=` 属性暂留 `'unsafe-inline'`（待 Phase 5 Web UI XSS 整改）
- 无 `data:` URI 例外
- 无 Google Fonts CDN（Phase 4 fixup 已移除，改用本地嵌入字体）

**相关 spec/plan**：
- `docs/superpowers/specs/2026-07-01-appjs-modularization-design.md`
- `docs/superpowers/specs/2026-07-06-web-responsive-design.md`
- `docs/superpowers/specs/2026-07-11-security-phase5-xss-lint-design.md`
- `docs/superpowers/specs/2026-07-17-text-reader-design.md`（reader 模块）

#### 构建与签名

**Server 构建**：
- 单文件可执行：`cd server && go build -o LocalMediaHub.exe ./cmd/server`
- 双模式：GUI（默认） / `--headless`

**Android 构建链**：
- Gradle 主驱动
- `buildRustNative` task 挂载 `preBuild`：`cargo ndk -t arm64-v8a -o jniLibs/ build --release`
- 输出 `liblocalmedia_native.so` 到 `jniLibs/arm64-v8a/`
- `libffmpeg.so` 预编译（不参与 Rust 构建链），由 `preBuild` 校验 `.sha256`
- APK 输出：`android/app/build/outputs/apk/`

**Release 签名流程**：
1. 生成 keystore：`keytool -genkeypair -v -keystore localmediahub.keystore -alias localmediahub -keyalg RSA -keysize 2048 -validity 10000`
2. 复制示例配置：`cp android/keystore.properties.example android/keystore.properties`，填入签名信息
3. 正常构建：`cd android && ./gradlew assembleRelease`
4. 仅本地调试：`./gradlew assembleRelease -PallowDebugSigning=true`（**切勿公开分发**）

**相关 spec/plan**：
- `docs/superpowers/specs/2026-07-04-native-rust-rewrite-design.md`
- `docs/superpowers/specs/2026-07-07-apk-size-optimization-design.md`
- `docs/superpowers/specs/2026-07-11-security-phase2-libffmpeg-design.md`
- `docs/superpowers/specs/2026-07-10-security-phase7-apk-signing-design.md`

#### 测试

**Go**：
```
cd server && go test ./...
```
关键测试文件：`server/internal/service/*_test.go` / `server/internal/server/handler/*_test.go` / `server/internal/service/bookparser/*_test.go`

**Android**：
```
cd android && ./gradlew testDebugUnitTest
```

**Rust**：
```
cd android/app/src/main/rust && cargo test
```

**XSS 静态分析**：
```
cd tools/xsscheck && go run . ../server/internal/web
```

#### 迁移与升级历史

**Round 29 Phase 3 — config 默认安全升级**：
- 升级后若遇 `refusing to start` 错误，选择以下任一：
  1. 在 `config.yaml` 的 `scan.roots` 下显式列出媒体目录（推荐）
  2. 配置 `system.allowed_roots`（同时作为 scan roots 的 fallback）
  3. 在 `config.yaml` 设置 `scan.auto_detect_roots: true`
  4. 启动加 `--auto-detect-roots` flag（一次性 override）

**标签存储迁移**：
- 旧版 `tags.json` → SQLite `server/.data/tags.db`
- 首次启动自动迁移，原文件备份为 `tags.json.bak`

**DataStore 1.0.0 → 1.1.1**：
- 为 Bearer Token 字段引入，旧版回退兼容

**Reader 设置 V1 → V2**：
- 自动迁移到新 shape（字号 / 行距 / 段距 / 缩进 / 字体族）

**Android version 1.2（token-auth breaking change）**：
- 旧客户端连接启用 Token 的服务端会失败，需升级到 ≥ 1.2

## 6. 实施顺序

1. 写 `docs/INDEX.md`（新建；可独立完成）
2. 重写 `README.md`（依赖 INDEX 完成，确保指针正确）
3. 重写 `AGENTS.md`（依赖 INDEX 完成）
4. 验证：
   - 所有相对路径 spec/plan 引用确实存在
   - 所有"详见 docs/INDEX.md"指针指向真实小节
   - 三件套之间无主题重叠（命令只在 AGENTS，API 表只在 INDEX，价值主张只在 README）
5. 一次性 commit

## 7. 验收标准

- README ≤ 200 行，不含 API 端点表 / 文件结构树 / 安全头表 / 迁移细节
- AGENTS ≤ 300 行，不含 API 端点表 / 文件结构树 / spec 索引
- INDEX 含全部 8 个安全 Phase + 全部阅读器 spec + Round 27-31 性能 spec
- 三件套之间无相同信息重复（命令、API 表、价值主张各只出现一次）
- 所有新功能（阅读器 / Token / 书签 / 性能优化）在三件套中至少一处有覆盖
- 所有 spec/plan 相对路径引用经过存在性校验

## 8. 风险与回退

**风险**：spec/plan 引用路径笔误。
**缓解**：实施阶段用 `Glob` 验证每个引用文件存在。

**风险**：信息在三件套之间意外重复。
**缓解**：实施完成后跑一遍 grep 检查（如"fsnotify"应只在 AGENTS 出现，不应在 README 出现）。

**回退**：本次改动纯文档，无代码变更，回退仅需 `git revert` 单个 commit。
