# LocalMediaHub

GitHub: [huangsheng6668/LocalMediaHub](https://github.com/huangsheng6668/LocalMediaHub)

将 PC 端的本地媒体资源（视频、图片）通过局域网串流到 Android 手机上浏览和播放。

## 系统架构

```
┌─────────────────────┐       HTTP/REST        ┌──────────────────────┐
│    PC Server        │◄──────────────────────►│   Android Client     │
│   Go / Echo v4      │    局域网 Wi-Fi/有线    │  Kotlin/Compose      │
│                     │                         │                      │
│  - 全盘浏览         │  /api/v1/folders       │  - 文件浏览器         │
│  - 视频流 (Range)   │  /api/v1/videos/stream │  - ExoPlayer 播放     │
│  - 缩略图生成       │  /api/v1/images/thumb  │  - Coil 图片加载      │
│  - mDNS 发现        │  /api/v1/tags          │  - NSD 自动发现       │
│  - 系统托盘         │  /api/v1/system/*      │  - 收藏/标签/搜索     │
└─────────────────────┘                         └──────────────────────┘
```

## 功能概览

### Server 端

| 功能 | 说明 |
|------|------|
| 全盘浏览 | 自动检测 Windows 驱动器，浏览任意目录，只显示媒体文件 |
| REST API | 24 个端点，覆盖目录浏览、媒体列表、搜索、标签、系统浏览等 |
| 视频流 | HTTP Range Requests (206 Partial Content)，64KB 分块，支持进度拖动 |
| 缩略图 | LANCZOS 缩放，MD5 磁盘缓存，懒加载 |
| 标签系统 | JSON 持久化，RWMutex 并发安全，CRUD + 文件关联 |
| 搜索 | 按文件名递归搜索，支持限定目录范围 |
| mDNS 发现 | 局域网服务注册，Android NSD 自动发现 |
| 双模式运行 | GUI 模式（系统托盘）或 headless 模式（无窗口） |
| 安全防护 | 路径遍历攻击防护（ValidatePath + isWithinRoots） |
| 媒体过滤 | 仅显示配置文件中指定的视频/图片扩展名文件 |

### Android 端

| 功能 | 说明 |
|------|------|
| 文件浏览器 | 网格/瀑布流视图，子目录浏览，滚动位置记忆 |
| 视频播放 | Media3 (ExoPlayer)，全屏切换，手势控制 |
| 图片预览 | 全屏查看，左右滑动，双指缩放 |
| 搜索 | 按文件名搜索，限定当前目录 |
| 排序 | 按名称/大小/时间/数字排序（文件夹和文件独立排序） |
| 收藏 | DataStore 持久化，筛选只看收藏 |
| 标签 | 长按打标签，按标签筛选 |
| 自动发现 | NSD 扫描局域网 mDNS 服务，无需手动输入 IP |
| 原生解码 | NDK/CMake 编译的 JPEG/WebP 原生解码器，提升图片加载性能 |
| FFmpeg 扩展 | 预编译 FFmpeg 库，支持更多视频格式 |

## 技术栈

### Server (Go, 当前版本)

- **语言**: Go 1.22+
- **框架**: Echo v4
- **依赖**: getlantern/systray (系统托盘), hashicorp/mdns
- **配置**: YAML 文件
- **运行**: 单文件可执行程序，双击即用

### Android Client

- **语言**: Kotlin
- **UI**: Jetpack Compose
- **架构**: MVVM (ViewModel + Repository)
- **网络**: Retrofit + OkHttp
- **图片**: Coil (含 NativeDecoderFactory)
- **视频**: Media3 (ExoPlayer) + FFmpeg 扩展
- **存储**: DataStore (偏好设置 + 收藏)
- **原生**: NDK/CMake (libjpeg-turbo + libwebp)
- **导航**: Navigation Compose

## 项目结构

```
localResourcesToPhone/
├── server/                    # Go 后端（当前主力版本）
│   ├── cmd/server/main.go        # 程序入口（--headless 参数）
│   ├── internal/
│   │   ├── config/               # 配置加载（YAML）
│   │   ├── models/               # 数据模型
│   │   ├── server/               # Echo 路由注册
│   │   │   ├── handler/          # 24 个 API handler
│   │   │   └── middleware/       # CORS 中间件
│   │   ├── service/              # 业务逻辑
│   │   │   ├── scanner.go        # 文件扫描（TTL 缓存）
│   │   │   ├── tags.go           # 标签系统（JSON 持久化）
│   │   │   ├── streaming.go      # 视频流传输（Range）
│   │   │   └── thumbnail.go      # 缩略图生成（MD5 缓存）
│   │   ├── mdns/                 # mDNS 服务注册
│   │   ├── systray/              # 系统托盘
│   │   └── gui/                  # GUI 模式入口
│   ├── config.yaml               # 运行时配置
│   └── go.mod
│
├── android/                      # Android 客户端
│   ├── app/
│   │   ├── build.gradle.kts      # 模块构建配置
│   │   ├── CMakeLists.txt        # NDK 原生编译
│   │   └── src/main/
│   │       ├── java/com/juziss/localmediahub/
│   │       │   ├── MainActivity.kt
│   │       │   ├── data/         # Model + Repository + DataStore
│   │       │   ├── network/      # Retrofit 接口 + OkHttp
│   │       │   ├── native/       # NativeDecoderFactory (Coil)
│   │       │   ├── ui/
│   │       │   │   ├── screen/   # 页面 Composable
│   │       │   │   └── component/# 可复用组件
│   │       │   └── viewmodel/    # ViewModel 层
│   │       ├── cpp/              # JNI 原生代码
│   │       └── jniLibs/          # 预编译 .so (FFmpeg)
│   ├── build.gradle.kts
│   └── settings.gradle.kts
└── docs/                         # 文档
```

## 快速开始

### 1. 启动 Go Server（推荐）

```bash
cd server
go build -o LocalMediaHub.exe ./cmd/server
./LocalMediaHub.exe          # GUI 模式，带系统托盘
./LocalMediaHub.exe --headless  # 无头模式，无窗口
```

Windows 用户直接双击 `LocalMediaHub.exe` 即可启动，托盘图标提供复制 URL 和退出功能。

如在中国大陆网络环境下编译：

```bash
GOPROXY=https://goproxy.cn,direct go mod tidy
```

### 2. 配置

编辑 `server/config.yaml`：

```yaml
server:
  host: "0.0.0.0"
  port: 8000

scan:
  video_extensions: [.mp4, .mkv, .avi, .mov, .wmv, .flv, .ts]
  image_extensions: [.jpg, .jpeg, .png, .gif, .bmp, .webp]

thumbnail:
  cache_dir: ".cache/thumbnails"
  max_size: 300
  format: "JPEG"

# 系统浏览根目录（可选，限制 Android 端可访问的目录范围）
system:
  allowed_roots:
    - "D:/Media"
    - "E:/Videos"
```

无需配置扫描目录，默认自动检测所有 Windows 驱动器。

### 3. 编译 Android 客户端

```bash
cd android
./gradlew assembleDebug      # Debug 版本
./gradlew assembleRelease     # Release 版本
```

APK 输出位置：`android/app/build/outputs/apk/`

### 4. 连接

- **自动**: App 通过 NSD 自动发现局域网内的 Server（需同一 WiFi）。
  *注：Android 端需要 `CHANGE_WIFI_MULTICAST_STATE` 权限以确保发现成功。*
- **手动**: 在 App 中输入 PC 的局域网 IP（如 `192.168.1.100:8000`）

## API 端点一览

### 目录浏览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/folders` | 获取根文件夹列表 |
| GET | `/api/v1/folders/{path}/browse` | 浏览指定目录 |

### 媒体文件

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/videos` | 视频列表（分页） |
| GET | `/api/v1/images` | 图片列表（分页） |
| GET | `/api/v1/videos/{path}/stream` | 视频流（支持 Range） |
| GET | `/api/v1/images/{path}/thumbnail` | 缩略图 |
| GET | `/api/v1/images/{path}/original` | 原图 |

### 搜索与标签

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/search` | 搜索文件 |
| GET | `/api/v1/tags` | 标签列表 |
| POST | `/api/v1/tags` | 创建标签 |
| DELETE | `/api/v1/tags/{id}` | 删除标签 |
| POST | `/api/v1/tags/{id}/files/{path}` | 给文件打标签 |
| DELETE | `/api/v1/tags/{id}/files/{path}` | 移除文件标签 |
| GET | `/api/v1/tags/{id}/files` | 获取标签下的文件 |

### 系统浏览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/system/drives` | 系统驱动器列表 |
| GET | `/api/v1/system/browse` | 全盘目录浏览 |
| GET | `/api/v1/system/thumbnail` | 系统级缩略图 |
| GET | `/api/v1/system/stream` | 系统级视频流 |
| GET | `/api/v1/system/images/original` | 系统级原图 |

### 管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/admin/config` | 获取配置 |
| PUT | `/api/v1/admin/config` | 更新扫描目录 |
| POST | `/api/v1/admin/scan/trigger` | 触发全量重扫描 |

## 原生库编译 (可选)

Android 端使用 NDK 编译了 JPEG/WebP 原生解码库以提升性能。如需重新编译：

```bash
bash build_native_libs.sh
```

依赖：Android NDK r27+，目标架构 arm64-v8a。

## 开发与同步

本项目代码在本地修改后，将通过 Antigravity 助手自动同步到 GitHub 仓库。

## License

MIT
