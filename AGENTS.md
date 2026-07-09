# Claude Code Project Context: LocalMediaHub (C/S System)

GitHub Repo: https://github.com/huangsheng6668/LocalMediaHub

本地媒体资源管理系统。服务端运行在 PC 端，负责扫描和提供媒体流；客户端为原生 Android 应用，用于浏览和播放。

## 技术栈
- **Server:** Go 1.25+ / Echo v4 / System Tray / fsnotify / modernc.org/sqlite (pure-Go)
- **Client:** Android Native / Kotlin / Jetpack Compose / WorkManager / Media3 + MediaSession
- **通信协议:** HTTP (REST API / Streaming)

## 常用命令

### Go Server (推荐)
- **编译:** `cd server && go build -o LocalMediaHub.exe ./cmd/server`
- **启动 (GUI 模式):** `./LocalMediaHub.exe`（双击即可，带系统托盘）
- **启动 (无头模式):** `./LocalMediaHub.exe --headless`
- **依赖代理:** `GOPROXY=https://goproxy.cn,direct go mod tidy`

### Frontend (Android)
- **Debug:** `cd android && ./gradlew assembleDebug`
- **Release:** `cd android && ./gradlew assembleRelease`
- **验证:** `cd android && ./gradlew testDebugUnitTest assembleDebug`
- **APK 位置:** `android/app/build/outputs/apk/release/app-release.apk`

## 项目结构规范
- `/server`: Go 后端（当前主力版本）
    - `cmd/server/main.go`: 程序入口
    - `internal/config/`: 配置加载（YAML）
    - `internal/models/`: 数据模型
    - `internal/server/`: Echo 路由注册（`Server.Stop` 关闭 scanner + tags DB）
    - `internal/server/handler/`: 29 个 API handler
    - `internal/server/middleware/`: CORS 中间件
    - `internal/service/`: 业务逻辑
        - `scanner.go`: 文件扫描（TTL 缓存 + fsnotify 递归监听 `StartWatching`，2s 防抖重扫，新目录动态加入）
        - `tags.go`: 标签系统（SQLite 持久化，JSON→SQLite 自动迁移，`Close()` 关闭 DB）
        - `streaming.go`: 视频流（`http.ServeContent` + 256KB `BufferedReadSeeker`，原生 Range 支持）
        - `thumbnail.go` / `path.go`: 缩略图生成 / 路径校验
    - `internal/mdns/`: mDNS 服务注册
    - `internal/systray/`: 系统托盘（getlantern/systray）
    - `internal/gui/`: GUI 模式入口
    - `internal/web/`: Web 管理器前端静态资源与模块化脚本
    - `config.yaml`: 运行时配置
- `/android`: Android Studio 项目
    - `app/src/main/java/.../LocalMediaHubApplication.kt`: Hilt `@HiltAndroidApplication` 入口
    - `app/src/main/java/.../MainActivity.kt`: `ComponentActivity` (singleTop) + `NavHost` 路由 + 视频续播调度（`checkPlaybackProgress`、`playVideo`、`resumeRequest` 状态）；启动时请求 `POST_NOTIFICATIONS`（Android 13+）
    - `app/src/main/java/.../VideoPlayerActivity.kt`: 独立视频播放 Activity，承载 PiP 浮窗；`onPictureInPictureModeChanged(false)` + `onStop` 判定关闭浮窗后 `finish()` 释放 ExoPlayer（`exitingFromPip` 标志区分「关闭浮窗」与「切后台」）
    - `app/src/main/java/.../ui/screen/`: Compose 页面
        - `HomeScreen.kt`: 首页聚合入口、最近活动、继续播放、收藏、标签集合
        - `ConnectionScreen.kt`: 自动重连 + NSD 发现连接流
        - `BrowseScreen.kt`: 媒体浏览、筛选、滚动位置恢复
        - `VideoPlayerScreen.kt`: Media3 ExoPlayer 全屏播放 + 手势 + 3 秒重启 chip；PlayerView 直连底层 `exoPlayer`（已删除 ForwardingPlayer 防抖层，手势松手后单次即时 seek）；`MediaSession` 暴露系统媒体控制；`SeekParameters.DEFAULT`（PRIOR_APPROACH_SYNC）向前对齐到最近关键帧；OkHttp cache 关闭避免 Range 请求磁盘抖动；`ON_STOP` 强制 pause 防后台音频泄漏
        - `ImagePreviewScreen.kt`: 图片全屏预览 + 双指缩放 + 同目录左右滑动；`onImageVisible` 回调记录最近活动；Coil 预加载相邻原图
        - `DownloadsScreen.kt`: 离线下载列表
    - `app/src/main/java/.../ui/component/`: Compose 可复用组件
        - `ResumePlaybackDialog.kt`: 视频已看完（≥95%）时的"继续 / 从头开始"对话框 + `VideoOpenAction` sealed class + `ResumePlaybackRequest`
        - `PlayerGestureDetector.kt`: 播放器手势（亮度/音量/进度）
        - `BrowseContent.kt`、`GridContainers.kt`、`MediaItems.kt`、`TagComponents.kt`、`VerticalScrollbar.kt`: 浏览/网格/媒体项等通用组件（`MediaItems.kt` / `GridContainers.kt` 的 `VideoCard` / `ImageCard` / `WaterfallImageGrid` 支持 `isSelected` 批量选择高亮覆盖层 + 勾选标记）
        - `theme/NoRippleIndication.kt`: Compose foundation 1.11.x + material3 1.3.1 版本错配的临时 workaround（material3 1.3.1 的 `PlatformRipple` 未实现 `IndicationNodeFactory`，release R8 构建会崩）；material3 升级到 1.4.x+ 后应删除
        - `home/HomeComponents.kt`: 首页卡片（Hero / Library / ContinueWatching / RecentMedia / Favorite 等）
        - `browse/`: 浏览子组件（`BrowseTopBar` / `BrowseSortMenu` / `BrowseSearchView` / `BrowseFavoritesView` / `DeleteConfirmDialog` / `DeleteLoadingDialog` / `QuickActionsDialog` 等）
    - `app/src/main/java/.../viewmodel/`: ViewModel 层
        - `HomeViewModel.kt`: 首页推荐与继续播放数据聚合（`filterContinueWatching` 过滤已看完条目；`getSisterImages` 获取同目录图片列表用于图片预览左右滑动）
        - `BrowseViewModel.kt`: 浏览主 VM，通过 delegate 分发职责；`deletePaths(paths)` 批量删除入口
        - Browse delegates: `BrowseNavigator`（目录导航）/ `BrowseSorter`（排序）/ `SearchController`（搜索）/ `TagController`（标签）/ `FavoritesController`（收藏）/ `DownloadController`（下载）/ `DeleteController`（删除，`deletePath` 单条 + `deletePaths` 批量）/ `BrowseSharedState`（共享状态）
        - `ConnectionViewModel.kt`、`ConnectionDecisions.kt`: 连接决策
        - `VideoPlayerViewModel.kt`: 提供共享 OkHttpClient
    - `app/src/main/java/.../network/`: Retrofit 接口 + OkHttp
    - `app/src/main/java/.../data/`: 模型与仓库层
        - `Models.kt`: `MediaFile` / `Folder` / `Tag` / `FavoriteMediaEntry` / `PlaybackProgressEntry` / `RecentMediaEntry` / `LastBrowseLocation` 等数据类（`MediaFile` 为 `@Parcelize`）
        - `MediaRepository.kt`: Retrofit 包装
        - `RecentActivityStore.kt`: 最近活动 + 浏览状态 + 播放进度持久化；含 `getPlaybackProgress`、`savePlaybackProgress`、`clearPlaybackProgress`、`addRecentMedia`、`saveLastBrowseLocation` 等
        - `FavoritesStore.kt`: 收藏列表持久化
        - `DownloadsStore.kt` + `DownloadManager.kt` + `DownloadWorker.kt`: 离线下载持久化与执行。`DownloadManager` 仅负责入队（构造 `OneTimeWorkRequest` + Gson 序列化参数），真正的下载在 `DownloadWorker`（CoroutineWorker）内以前台服务 + 进度通知执行；`DownloadWorker` 通过 Hilt `@EntryPoint` 获取 `MediaRepository` / `DownloadsStore`，自带 Zip Slip 防护
        - `ServerConfigStore.kt`: 上次连接的服务端配置
        - `RoutePath.kt`: 浏览路径与系统/库模式标记
    - `app/src/main/java/.../di/`: Hilt 模块（`CoroutineScopesModule`）
    - `app/src/main/java/.../util/`: 公共工具（`TimeUtil.formatTime`、`NetUtil`、`CacheCleanup`）
    - `app/src/main/java/.../native/`: Rust JNI 入口（Kotlin 侧）
        - `NativeImageDecoder.kt` / `NativeExif.kt` / `NaturalSorter.kt` / `NativeDecoderFactory.kt`（Coil 集成）
    - `app/src/main/rust/`: Rust 原生解码 crate（`localmedia_native`，cargo-ndk 交叉编译到 arm64-v8a）
        - `Cargo.toml`: pure-Rust deps（`jpeg-decoder` / `image-png` / `webp` / `kamadak-exif` / `fast-image-resize`），无 C 依赖
        - `src/`: `lib.rs`、`bitmap.rs`（EXIF orientation 旋转）、`exif_reader.rs`、`jpeg.rs`、`png.rs`、`webp.rs`、`heif.rs`、`natural_sort.rs`
        - `src/jni_bridge/`: JNI 桥（`decoders.rs` / `exif_jni.rs` / `natural_sort_jni.rs` / `mod.rs`）
    - `app/src/main/jniLibs/arm64-v8a/`: 编译产物 —— `liblocalmedia_native.so`（Rust 输出，由 `buildRustNative` Gradle task 在 `preBuild` 阶段自动生成）+ `libffmpeg.so`（预编译 FFmpeg 扩展）
    - `app/build.gradle.kts`: 注册 `buildRustNative` task（`cargo ndk -t arm64-v8a -o jniLibs/ build --release`），挂载到 `preBuild`

## 编码规则

### Go (Server)
- Handler 层通过 `Handler` struct 持有服务依赖，不使用全局变量。
- 路径安全：所有文件访问必须经过 `ValidatePath` 或 `isWithinRoots` 校验。
- **系统/统一媒体端点**：`/api/v1/system/*`（缩略图/原图/流）与 `/api/v1/media/*` 必须经 `ValidateSystemMediaAccess` / `ValidateAccessibleMediaPath`，强制 `system.allowed_roots` 边界，禁止越界读取。
- **权限控制**: 目录访问受 `config.yaml` 中的 `system.allowed_roots` 限制（若配置）。
- 列表返回用 `make([]T, 0)` 初始化，避免 JSON 序列化为 `null`。
- 业务逻辑放在 `internal/service/`，handler 只做参数解析和响应。

### Kotlin (Android)
- **UI:** Jetpack Compose，MVVM 架构。
- **网络:** Retrofit + OkHttp。
- **图片:** Coil 3（含 NativeDecoderFactory）。
- **视频:** Media3 (ExoPlayer + MediaSession) + 预编译 libffmpeg.so。
- **后台任务:** WorkManager + 前台服务（`FOREGROUND_SERVICE_DATA_SYNC`），用于常驻通知栏的离线下载。
- **原生解码:** Rust crate `localmedia_native`（`android/app/src/main/rust/`），通过 `cargo-ndk` 交叉编译到 `arm64-v8a`，Gradle `buildRustNative` task 在 `preBuild` 阶段自动调用。Kotlin 侧入口在 `native/`（`NativeImageDecoder` / `NativeExif` / `NaturalSorter`）。Rust 侧 `ARGB_8888` == NDK `RGBA_8888`，解码器直接 `copy_from_slice`，无需通道重排。
- **异步:** Coroutines。
- **已知 Compose 版本错配:** foundation 1.11.x + material3 1.3.1 的 `clickable` 在 release R8 构建下崩溃，由 `theme/NoRippleIndication.kt` 提供无 ripple 的 `IndicationNodeFactory` 解决。material3 升级到 1.4.x+ 后可移除该 workaround 并恢复 ripple。

## Go Server 架构

```
main.go --headless?→ server.New(cfg) → headless 模式
       └── GUI 模式 → gui.Run(cfg) → server + systray + 信号处理

Server struct 持有:
  - Scanner   (文件扫描，TTL 缓存 + fsnotify 递归监听)
  - TagsService (SQLite 持久化，RWMutex)
  - StreamingService (http.ServeContent + 256KB BufferedReadSeeker)
  - ThumbnailService (MD5 磁盘缓存，LANCZOS 缩放)

Handler struct 接收所有 service 引用，方法挂在 struct 上。
```

## 核心功能
1. **全盘浏览:** 自动检测 Windows 驱动器，浏览任意目录，只显示媒体文件
2. **发现机制:** mDNS 注册 + Android NSD 自动发现
3. **首页体验:** Android 首页聚合 Libraries、最近活动、继续播放、收藏和标签集合
4. **视频续播:** 任意入口（浏览 / 收藏 / 下载 / 最近打开等）打开同一视频都自动从上次进度恢复；进度 ≥95% 时弹窗询问"继续 / 从头开始"；自动续播时右下角提供 3 秒"从头开始"快捷入口
5. **浏览恢复:** 记录最近浏览路径、滚动位置和最近打开媒体，支持一键回到上次上下文
6. **离线下载:** WorkManager 前台服务 + 进度通知；支持单文件与目录 ZIP 流式下载解压；DownloadsStore 持久化条目，`file://` URL 直接喂给 ExoPlayer/Coil
7. **媒体处理:** 视频流传输（Range）、缩略图生成、标签系统（SQLite 持久化）、标签下媒体聚合
8. **实时文件监听:** fsnotify 递归监听扫描根目录，磁盘变更后即时失效缓存并防抖触发重扫
9. **画中画 (PiP):** 独立 `VideoPlayerActivity` 承载 PiP 浮窗，关闭浮窗自动 `finish()` 释放资源
10. **批量选择:** 浏览页长按进入选择模式，支持全选/反选、批量删除（调 `deletePaths`）、批量下载
11. **受限系统浏览:** `/api/v1/system/*` 仅允许访问 `config.yaml` 中 `system.allowed_roots` 范围
12. **双模式:** GUI（系统托盘）或 headless（无窗口）
13. **Rust 原生解码:** Rust crate `localmedia_native`（JPEG/PNG/WebP 解码 + EXIF orientation 校正 + 自然排序），pure-Rust 依赖，通过 cargo-ndk 交叉编译到 arm64-v8a，Gradle `buildRustNative` task 在 `preBuild` 阶段自动构建。Kotlin 侧通过 JNI（`native/NativeImageDecoder.kt` 等）调用。Bitmap 写入直接 `copy_from_slice`（Android `ARGB_8888` == NDK `RGBA_8888`，无需通道重排）。
14. **中文汉化与视觉美观度优化**: 深度汉化原生 Android 界面所有硬编码文案。引入柔和的线性色彩渐变（Linear Gradients）与高阶毛玻璃面板拟态（Glassmorphism）胶囊，为多媒体和文件夹卡片引入精致超细描边及按压阻尼动态立体悬浮效果。
15. **Web 管理界面**: 内置精致的 Web Single Page App，提供仪表盘、媒体共享库浏览、标签增删改查、以及系统设置功能。
16. **统一媒体访问**: `/api/v1/media/*` 通过绝对路径 `?path=` 统一提供缩略图、原图、视频流与时长，覆盖扫描根目录与 `system.allowed_roots`，均经路径与边界校验。

> **同步政策:** 任何本地代码改动将自动同步推送至 GitHub `master` 分支（个人项目约定）。

