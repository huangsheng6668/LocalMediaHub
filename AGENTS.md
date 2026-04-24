# Claude Code Project Context: LocalMediaHub (C/S System)

GitHub Repo: https://github.com/huangsheng6668/LocalMediaHub

本地媒体资源管理系统。服务端运行在 PC 端，负责扫描和提供媒体流；客户端为原生 Android 应用，用于浏览和播放。

## 技术栈
- **Server:** Go 1.22+ / Echo v4 / System Tray
- **Client:** Android Native / Kotlin / Jetpack Compose
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
    - `internal/server/`: Echo 路由注册
    - `internal/server/handler/`: 24 个 API handler
    - `internal/server/middleware/`: CORS 中间件
    - `internal/service/`: 业务逻辑（scanner, tags, streaming, thumbnail）
    - `internal/mdns/`: mDNS 服务注册
    - `internal/systray/`: 系统托盘（getlantern/systray）
    - `internal/gui/`: GUI 模式入口
    - `config.yaml`: 运行时配置
- `/android`: Android Studio 项目
    - `app/src/main/java/.../ui/screen/`: Compose 页面
        - `HomeScreen.kt`: 首页聚合入口、最近活动、继续播放
        - `ConnectionScreen.kt`: 自动重连 + NSD 发现连接流
        - `BrowseScreen.kt`: 媒体浏览、筛选、滚动位置恢复
    - `app/src/main/java/.../viewmodel/`: ViewModel 层
        - `HomeViewModel.kt`: 首页推荐与继续播放数据聚合
    - `app/src/main/java/.../network/`: Retrofit 接口
    - `app/src/main/java/.../data/`: 模型与仓库层
        - `RecentActivityStore.kt`: 最近活动与浏览状态持久化

## 编码规则

### Go (Server)
- Handler 层通过 `Handler` struct 持有服务依赖，不使用全局变量。
- 路径安全：所有文件访问必须经过 `ValidatePath` 或 `isWithinRoots` 校验。
- **权限控制**: 目录访问受 `config.yaml` 中的 `system.allowed_roots` 限制（若配置）。
- 列表返回用 `make([]T, 0)` 初始化，避免 JSON 序列化为 `null`。
- 业务逻辑放在 `internal/service/`，handler 只做参数解析和响应。

### Kotlin (Android)
- **UI:** Jetpack Compose，MVVM 架构。
- **网络:** Retrofit + OkHttp。
- **图片:** Coil（含 NativeDecoderFactory）。
- **视频:** Media3 (ExoPlayer) + FFmpeg。
- **异步:** Coroutines。

## Go Server 架构

```
main.go --headless?→ server.New(cfg) → headless 模式
       └── GUI 模式 → gui.Run(cfg) → server + systray + 信号处理

Server struct 持有:
  - Scanner   (文件扫描，TTL 缓存)
  - TagsService (JSON 持久化，RWMutex)
  - StreamingService (Range 请求，64KB 分块)
  - ThumbnailService (MD5 磁盘缓存，LANCZOS 缩放)

Handler struct 接收所有 service 引用，方法挂在 struct 上。
```

## 核心功能
1. **全盘浏览:** 自动检测 Windows 驱动器，浏览任意目录，只显示媒体文件
2. **发现机制:** mDNS 注册 + Android NSD 自动发现
3. **首页体验:** Android 首页聚合 Libraries、最近活动、继续播放、收藏和标签集合
4. **浏览恢复:** 记录最近浏览路径、滚动位置和最近打开媒体，支持一键回到上次上下文
5. **媒体处理:** 视频流传输（Range）、缩略图生成、标签系统、标签下媒体聚合
6. **受限系统浏览:** `/api/v1/system/*` 仅允许访问 `config.yaml` 中 `system.allowed_roots` 范围
7. **双模式:** GUI（系统托盘）或 headless（无窗口）
8. **同步政策**: 任何本地代码改动将自动同步推送至 GitHub `master` 分支。
