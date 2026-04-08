# LocalMediaHub

将 PC 端的本地媒体资源（视频、图片）通过局域网串流到 Android 手机上浏览和播放。

## 系统架构

```
┌─────────────────────┐       HTTP/REST        ┌──────────────────────┐
│    PC Server        │◄──────────────────────►│   Android Client     │
│   Python/FastAPI    │    局域网 Wi-Fi/有线    │  Kotlin/Compose      │
│                     │                         │                      │
│  - 文件系统扫描      │  /api/v1/folders       │  - 文件浏览器         │
│  - 视频流 (Range)   │  /api/v1/videos/stream │  - ExoPlayer 播放     │
│  - 缩略图生成       │  /api/v1/images/thumb  │  - Coil 图片加载      │
│  - mDNS 发现        │  /api/v1/tags          │  - NSD 自动发现       │
│  - Web 管理         │  /admin                │  - 收藏/标签/搜索     │
└─────────────────────┘                         └──────────────────────┘
```

## 功能概览

### Server 端

| 功能 | 说明 |
|------|------|
| 文件扫描 | 递归扫描指定目录，识别视频/图片文件，收集元数据 |
| REST API | 20+ 个端点，覆盖目录浏览、媒体列表、搜索、标签等 |
| 视频流 | HTTP Range Requests (206 Partial Content)，支持进度拖动 |
| 缩略图 | Pillow 生成，磁盘缓存，懒加载 |
| 全盘浏览 | Windows 驱动器列表 + 任意目录浏览（不限于配置的 roots） |
| 标签系统 | JSON 持久化，CRUD + 文件关联 |
| 搜索 | 按文件名递归搜索，支持限定目录范围 |
| mDNS 发现 | zeroconf 注册服务，Android NSD 自动发现 |
| Web 管理 | `/admin` 内嵌管理页面，在线修改扫描目录、触发重扫描 |
| 安全防护 | 路径遍历攻击防护（`resolve()` + `is_relative_to()`） |

### Android 端

| 功能 | 说明 |
|------|------|
| 文件浏览器 | 网格/列表视图，面包屑导航，子目录浏览 |
| 视频播放 | Media3 (ExoPlayer)，全屏切换，手势控制 |
| 图片预览 | 全屏查看，左右滑动，双指缩放 |
| 搜索 | 按文件名搜索，限定当前目录 |
| 排序 | 按名称/大小/时间排序 |
| 收藏 | DataStore 持久化，筛选只看收藏 |
| 标签 | 长按打标签，按标签筛选 |
| 自动发现 | NSD 扫描局域网 mDNS 服务，无需手动输入 IP |
| 原生解码 | NDK/CMake 编译的 JPEG/WebP 原生解码器，提升图片加载性能 |
| FFmpeg 扩展 | 预编译 FFmpeg 库，支持更多视频格式 |

## 技术栈

### Server

- **语言**: Python 3.10+
- **框架**: FastAPI + Uvicorn
- **依赖**: Pillow, aiofiles, zeroconf, Pydantic, PyYAML
- **配置**: YAML 文件 + Pydantic 校验

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
├── server/                      # Python 后端
│   ├── main.py                  # FastAPI 入口，路由挂载，CORS
│   ├── scanner.py               # 文件系统扫描器（含缓存）
│   ├── config.py                # 配置模型（Pydantic）
│   ├── config.yaml              # 运行时配置文件
│   ├── models.py                # 数据模型定义
│   ├── mdns.py                  # mDNS 服务注册
│   ├── requirements.txt         # Python 依赖
│   ├── api/
│   │   ├── folders.py           # 目录浏览接口
│   │   ├── videos.py            # 视频列表（分页）
│   │   ├── images.py            # 图片列表（分页）
│   │   ├── streaming.py         # 视频流传输（Range）
│   │   ├── thumbnails.py        # 缩略图生成 + 原图接口
│   │   ├── search.py            # 文件搜索
│   │   ├── tags.py              # 标签 CRUD + 文件关联
│   │   ├── admin.py             # 管理页面 + 配置 API
│   │   └── system.py            # 全盘浏览 + 系统级流传输
│   └── .data/
│       └── tags.json            # 标签数据持久化
│
├── android/                     # Android 客户端
│   ├── app/
│   │   ├── build.gradle.kts     # 模块构建配置
│   │   ├── CMakeLists.txt       # NDK 原生编译
│   │   └── src/main/
│   │       ├── java/com/juziss/localmediahub/
│   │       │   ├── MainActivity.kt
│   │       │   ├── data/        # Model + Repository + DataStore
│   │       │   ├── network/     # Retrofit 接口 + OkHttp
│   │       │   ├── native/      # NativeDecoderFactory (Coil)
│   │       │   ├── ui/
│   │       │   │   ├── screen/  # 页面 Composable
│   │       │   │   └── component/ # 可复用组件
│   │       │   └── viewmodel/   # ViewModel 层
│   │       ├── cpp/             # JNI 原生代码
│   │       │   ├── jni/         # JNI 桥接
│   │       │   ├── third_party/ # libjpeg-turbo + libwebp 源码
│   │       │   └── libs/        # 编译产物 (.a)
│   │       └── jniLibs/         # 预编译 .so (FFmpeg)
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── gradle/                  # Gradle Wrapper
│
├── start-server.bat             # Windows 一键启动脚本
├── build_native_libs.sh         # NDK 原生库编译脚本 (WSL)
└── plan.md                      # 开发计划与进度
```

## 快速开始

### 1. 启动 Server

```bash
cd server
pip install -r requirements.txt
python -m uvicorn server.main:app --host 0.0.0.0 --port 8000
```

或 Windows 双击 `start-server.bat`。

启动后访问：
- API 文档: `http://localhost:8000/docs`
- 管理页面: `http://localhost:8000/admin`

### 2. 配置扫描目录

编辑 `server/config.yaml`：

```yaml
scan:
  roots:
    - "D:/Movies"
    - "D:/Photos"
```

或通过管理页面 `http://localhost:8000/admin` 在线修改。

### 3. 编译 Android 客户端

```bash
cd android
./gradlew assembleDebug
```

安装到设备：

```bash
./gradlew installDebug
```

### 4. 连接

- **手动**: 在 App 中输入 PC 的局域网 IP (如 `192.168.1.100:8000`)
- **自动**: App 通过 NSD 自动发现局域网内的 Server（需同一 WiFi）

## API 端点一览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/folders` | 获取配置的根文件夹列表 |
| GET | `/api/v1/folders/{path}/browse` | 浏览指定目录 |
| GET | `/api/v1/videos` | 视频列表（分页） |
| GET | `/api/v1/images` | 图片列表（分页） |
| GET | `/api/v1/videos/{path}/stream` | 视频流（支持 Range） |
| GET | `/api/v1/images/{path}/thumbnail` | 缩略图 |
| GET | `/api/v1/images/{path}/original` | 原图 |
| GET | `/api/v1/search` | 搜索文件 |
| GET | `/api/v1/tags` | 标签列表 |
| POST | `/api/v1/tags` | 创建标签 |
| DELETE | `/api/v1/tags/{id}` | 删除标签 |
| POST | `/api/v1/tags/{id}/files/{path}` | 给文件打标签 |
| DELETE | `/api/v1/tags/{id}/files/{path}` | 移除文件标签 |
| GET | `/api/v1/tags/{id}/files` | 获取标签下的文件 |
| GET | `/api/v1/admin/config` | 获取配置 |
| PUT | `/api/v1/admin/config` | 更新扫描目录 |
| POST | `/api/v1/admin/scan/trigger` | 触发全量重扫描 |
| GET | `/api/v1/system/drives` | 系统驱动器列表 |
| GET | `/api/v1/system/browse` | 全盘目录浏览 |
| GET | `/api/v1/system/thumbnail` | 系统级缩略图 |
| GET | `/api/v1/system/stream` | 系统级视频流 |
| GET | `/admin` | Web 管理页面 |

## 原生库编译 (可选)

Android 端使用 NDK 编译了 JPEG/WebP 原生解码库以提升性能。如需重新编译：

```bash
# 在 WSL 环境中执行
bash build_native_libs.sh
```

依赖：Android NDK r27+，目标架构 arm64-v8a。

## License

MIT
