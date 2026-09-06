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
│  - BLE Central(实验)│  BLE GATT 控制通道(实验) │  - BLE Peripheral    │
└─────────────────────┘                         └──────────────────────┘
```

## 核心功能

### 1. 媒体浏览与播放

自动检测 Windows 驱动器，浏览任意目录。视频通过 HTTP Range 流式播放（256KB 缓冲 `BufferedReadSeeker`），缩略图采用 LANCZOS 缩放 + MD5 磁盘缓存。`fsnotify` 实时监听根目录，变更后防抖重扫。

### 2. 小说阅读器（txt / epub）

服务端章节解析（bookparser：LRU 文本缓存 + 字节/字符偏移映射，大文件章加载 ~200ms→<5ms；支持卷层级与非标准章节标题）与 epub 图片内联（签名 URL，绑定 clientIP）。Web 端阅读器已模块化为 bus 解耦架构（textReader + toc/bookmarks/progress/autoscroll/reader-settings 子模块）。7 套预设主题（日间 × 2 / 护眼纸质 × 3 / 夜间 × 2）另加 AUTO 跟随系统与 CUSTOM 自定义底色，嵌入 LXGW WenKai + Noto Serif SC 字体。V2 设置：字号 / 行距 / 段距 / 首行缩进 / 字体族 / 文本最大宽度（最高 1400px/1400dp）。支持分章模式与全文滚动模式、全屏沉浸模式（隐藏系统栏/顶底栏，Esc 或后退手势优先退出沉浸）、实时百分比阅读进度与细进度条（分章内进度 % + 全书进度 %）、分章模式左右区域点击翻页、自动滚动、书签、章节列表（高亮当前章节）、首字下沉、淡入过渡。

### 3. 续播与上下文恢复

跨入口（浏览 / 收藏 / 下载 / 最近打开）打开同一视频都自动从上次进度恢复。进度 ≥ 95% 时弹窗"继续 / 从头开始"，自动续播时右下角提供 3 秒"从头开始"chip。浏览页记录最近路径与滚动位置，一键重回上次上下文。

### 4. 收藏 / 标签 / 书签

Android 端用 DataStore 持久化收藏。服务端标签走 SQLite（pure-Go `modernc.org/sqlite`，无 CGO），CRUD + 文件关联，首次启动自动从 `tags.json` 迁移。Web 端书签视图（`bookmarksView.js`）。

### 5. 离线下载 / 画中画

WorkManager 前台服务执行下载，常驻通知栏显示进度；支持单文件与目录 ZIP 流式下载解压（含 Zip Slip 防护）。视频 PiP 浮窗使用独立 `VideoPlayerActivity`，关闭浮窗自动 `finish()` 释放 ExoPlayer，避免后台音频泄漏。

### 6. 安全加固

Bearer Token 认证（admin / system / media / books 路由组强制，SHA256 + constant-time 比较）；**books 图片签名 URL**（HMAC 绑定 clientIP + path + manifestID，替代明文 bearer token 入 URL/log）；access log `?token=` redact；**rate limit LRU + 容量上限**（防伪造 `X-Forwarded-For` 内存膨胀）；安全响应头（CSP / X-Frame-Options / nosniff / Referrer-Policy）；**`/debug/pprof` 默认关闭**（需显式 flag/config 开启）；Release APK 签名 fail-fast 守卫；libffmpeg SHA256 preBuild 校验；路径遍历防护（ValidatePath + ValidateSystemMediaAccess + ValidateAccessibleMediaPath）；**Web SPA XSS lint**（`tools/xsscheck` 扫描所有 DOM 写入 sink，缺 `// XSS-SAFE:` 注释即失败）；**BLE 帧认证**（v2 帧 seq+HMAC + 双 nonce 握手，专属密钥 `ble.token` 优先、`server.token` 回退，两者皆空 = 开放模式并打 WARN）。

### 7. Web 管理界面

浏览器直接访问 server 地址（如 `http://localhost:8000`）即是完整管理端：仪表盘（统计卡片 + 最近媒体缩略图）、文件浏览器、书架（封面卡片 + 阅读进度元数据）、书签、设置、视频播放器、图片灯箱与文本阅读器。2026-09 完成现代中性风重设计——7 套 `[data-theme]` 界面主题（与阅读区主题独立分离）、emoji 图标全部替换为内联 SVG、单文件 `style.css` 拆分为分层 `css/` 模块。零构建步骤（原生 ES module，CSP 兼容），Token 经 sessionStorage 持久化，401 自动弹输入框。

### 8. BLE 控制通道（实验）

并行低延迟控制通道（连接协调本身走 Wi-Fi/HTTP，不是离线兜底）：**server=Central**（tinygo bluetooth 栈，默认编入单一 server 构建，无蓝牙适配器则非致命降级），**Android=Peripheral**（`BluetoothGattServer`，Command Write + State Notify）。Android 通过 `/api/v1/ble/scan|connect|send` HTTP 接口协调连接，控制信令与数据帧走 GATT（`CMD_API_REQ` 可承载章节 / 目录等 API 响应）。默认关闭（Android 连接页 BLE 设置卡开启），蓝牙不可用完全退回 Wi-Fi/HTTP，零退化。

## 技术栈

| 层级 | 技术选型 | 说明 |
|---|---|---|
| **Server** | Go 1.25+ / Echo v4 | 纯 Go 高性能 Web 框架，无 CGO 依赖 |
| **Storage** | modernc.org/sqlite (pure-Go) | SQLite WAL 模式，管理标签、书架与阅读状态 |
| **File Watch** | fsnotify | 递归监听媒体目录变动并进行智能防抖重扫 |
| **Discovery** | hashicorp/mdns | 局域网服务组播广播（零配置发现） |
| **Android** | Kotlin / Jetpack Compose / Material 3 | 现代化声明式 Android UI 架构 |
| **Playback** | Media3 (ExoPlayer + MediaSession) | 视频流畅播放、软硬解回退与画中画 (PiP) |
| **Image** | Coil 3 + `localmedia_native` (Rust) | 纯 Rust 原生解码加速，低内存占用 |
| **Async & DI** | Coroutines / WorkManager / Hilt | 后台服务离线下载与依赖注入 |
| **Web 管理端** | 原生 ES Modules SPA | 服务端内嵌静态服务，零构建步骤，CSP 兼容 |
| **Native Libs**| Rust 2021 + cargo-ndk (arm64-v8a) + 预编译 `libffmpeg.so` | 高性能图像解算与多媒体解复用 |

---

## 环境准备 (Prerequisites)

在开始编译或运行前，请确认开发环境安装了相应工具：

| 工具 | 推荐版本 | 适用组件 | 用途与说明 |
|:---|:---|:---:|:---|
| **Go** | 1.23+（推荐 1.25+） | **服务端** | 编译 Go 后端服务，纯 Go 无 CGO 依赖 |
| **FFmpeg** | 5.0+（推荐 6.x~8.x） | **服务端运行** | 视频转码、获取时长与缩略图提取，需加入 PATH |
| **JDK** | Java 17 | **Android 编译** | Android Gradle Plugin 8.x 所需的 Java 运行时 |
| **Android SDK** | API 34 / 36, Build-Tools | **Android 编译** | Android 编译平台与工具包 |
| **Android NDK** | 27.0.12077973（或 25+） | **Android 编译** | 编译 Rust JNI 原生图片解码动态库 |
| **Rust & Cargo** | 1.75+ | **Android 编译** | 编译 `localmedia_native` 原生解码器 |
| **cargo-ndk** | 最新版 | **Android 编译** | Rust 跨平台交叉编译至 Android NDK 工具 |
| **Node.js** | 18+ | **前端测试（可选）** | 仅用于运行前端单元测试（`node --test`），运行与打包无需 Node |

---

## 服务端：编译与运行指南 (Server)

### 1. 源码与依赖准备

```bash
git clone https://github.com/huangsheng6668/LocalMediaHub.git
cd LocalMediaHub/server
```

> **中国大陆开发者加速**：
> 如遇依赖下载缓慢或超时，建议配置官方国内代理：
> ```bash
> go env -w GOPROXY=https://goproxy.cn,direct
> go mod tidy
> ```

### 2. 配置 FFmpeg 环境

服务端视频硬件转码与缩略图提取依赖 `ffmpeg` 与 `ffprobe` 二进制程序。

- **Windows 用户**：
  1. 前往 [gyan.dev/ffmpeg/builds](https://www.gyan.dev/ffmpeg/builds/) 下载 `ffmpeg-release-full.7z` 并解压；
  2. 将解压后的 `bin` 目录路径（内含 `ffmpeg.exe` 和 `ffprobe.exe`）添加进系统的环境变量 `Path` 中；
  3. 或直接将 `ffmpeg.exe` 与 `ffprobe.exe` 放置在 `LocalMediaHub.exe` 同级目录下；
  4. 打开命令行输入 `ffmpeg -version`，确认能输出版本信息。
- **Linux 用户 (Ubuntu / Debian)**：
  ```bash
  sudo apt update && sudo apt install -y ffmpeg
  ```
- **macOS 用户**：
  ```bash
  brew install ffmpeg
  ```

### 3. 配置运行文件 (`config.yaml`)

在 `server` 目录下从模板创建配置文件：

```bash
cp config.example.yaml config.yaml
```

使用文本编辑器编辑 `config.yaml`。关键配置项解析如下：

```yaml
server:
  host: "0.0.0.0"       # 监听地址，0.0.0.0 允许局域网内其他设备访问
  port: 8000            # 服务端口（默认 8000）
  token: ""             # 访问口令：留空为局域网开放模式；填入字符串后所有请求均需 Bearer Token
  lan_pairing: false    # 零接触配对：配对新设备时临时设为 true，手机可一键同步 Token，配对完建议改回 false

ble:
  token: ""             # 实验性 BLE 专属握手密钥：留空回退 server.token；两者皆空 = BLE 开放模式

scan:
  roots:                # 媒体扫描根目录列表
    - "D:/Media"
    - "E:/Books"
  auto_detect_roots: false  # 若 roots 为空且设为 true，服务端会自动扫描 Windows 所有挂载驱动器 (A-Z)
  video_extensions: [".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".ts", ".webm"]
  image_extensions: [".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp"]
  text_extensions: [".txt", ".epub", ".mobi", ".azw3"]

thumbnail:
  cache_dir: ".cache/thumbnails"
  max_size: 300
  format: "JPEG"

system:
  enable_delete: false  # 是否允许客户端远程删除文件（默认关闭，避免误删）
  allowed_roots:        # 允许受限文件系统访问与媒体直通的根目录范围
    - "D:/Media"
    - "E:/Books"

transcode:
  encoder_preference:   # 硬件转码器探测优先级（自动两级探测 + 运行时测试，失败自动降级）
    - "h264_nvenc"      # NVIDIA 显卡硬件编码
    - "h264_qsv"        # Intel 核显/独显 QuickSync
    - "h264_amf"        # AMD 显卡硬件编码
    # libx264 为底层软解兜底（无需手动配置）
  max_sessions: 3       # 最大并发转码会话限制（-1 为不限制）
```

> [!IMPORTANT]
> **安全启动规则**：若 `scan.roots` 为空、`system.allowed_roots` 为空且 `scan.auto_detect_roots: false`，服务端为防止未授权越界会**主动拒绝启动**。请至少在 `scan.roots` 或 `system.allowed_roots` 中配置一项有效目录。

### 4. 编译服务端

#### 选项 A：标准控制台版（推荐开发与调试）
编译出的程序在运行时会附带一个命令行窗口，方便查看实时日志与转码输出：
```bash
cd server
go build -o LocalMediaHub.exe ./cmd/server
```

#### 选项 B：Windows 无黑框纯托盘版（推荐日常长期使用）
添加 `-ldflags="-H windowsgui"` 参数，启动后不弹黑框控制台，静默在 Windows 右下角系统托盘运行：
```bash
cd server
go build -ldflags="-H windowsgui -s -w" -o LocalMediaHub.exe ./cmd/server
```

#### 选项 C：跨平台编译 Linux 二进制（NAS / 软路由 / VPS）
```bash
cd server
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags="-s -w" -o LocalMediaHub ./cmd/server
```

### 5. 启动服务端

- **桌面托盘模式（Windows 默认）**：
  直接双击 `LocalMediaHub.exe` 启动。托盘图标常驻右下角，右键菜单支持查看服务状态、在浏览器打开管理页、触发重新扫描或安全退出。
- **无头服务模式（Headless，后台/服务器推荐）**：
  添加 `--headless` 参数禁用 GUI 窗口与托盘组件，输出日志到标准输出：
  ```powershell
  .\LocalMediaHub.exe --headless
  ```
- **开发即时运行**：
  ```powershell
  go run ./cmd/server --headless
  ```

---

## Android 客户端：编译与安装指南 (Android)

### 1. 配置 Rust 交叉编译环境（首次必须）

为了实现超低内存占用的大图解码，Android 端通过 JNI 集成了纯 Rust 原生解码库（`localmedia_native`）。Gradle 构建的 `preBuild` 阶段会自动调用 `cargo-ndk` 交叉编译。

在首次编译前，请确保执行过以下三步配置：

```bash
# 1. 确认安装 Rust（如未安装请访问 https://rustup.rs）
rustup update

# 2. 安装 aarch64-linux-android 编译目标
rustup target add aarch64-linux-android

# 3. 安装 cargo-ndk 工具
cargo install cargo-ndk
```

同时请确保在环境变量中配置了 `ANDROID_HOME` 或在 `android/local.properties` 中指定了 SDK 与 NDK 路径：
```properties
sdk.dir=C\:\\Users\\<你的用户名>\\AppData\\Local\\Android\\Sdk
ndk.dir=C\:\\Users\\<你的用户名>\\AppData\\Local\\Android\\Sdk\\ndk\\27.0.12077973
```

### 2. 配置发布签名

项目开启了 Release 构建签名守卫。为了防止意外打出公钥未授权的安装包，Release 构建默认强制校验签名配置。

#### 方式 A：本地调试快速构建（使用 Debug 密钥签名 Release 包）
无需配置任何密钥文件，只需在命令中传递 `-PallowDebugSigning=true`：
```bash
cd android
./gradlew assembleRelease -PallowDebugSigning=true
```

#### 方式 B：配置正式签名证书（生产发布推荐）
1. 复制签名配置文件模板：
   ```bash
   cd android
   cp keystore.properties.example keystore.properties
   ```
2. （若无现成证书）使用 JDK `keytool` 生成新密钥对：
   ```bash
   keytool -genkeypair -v -keystore localmediahub.keystore -alias localmediahub -keyalg RSA -keysize 2048 -validity 10000
   ```
3. 打开 `android/keystore.properties` 填入对应的证书文件路径与密码：
   ```properties
   storeFile=localmediahub.keystore
   storePassword=你的证书密码
   keyAlias=localmediahub
   keyPassword=你的别名密码
   ```

### 3. 执行 Gradle 构建

进入 `android` 目录运行对应构建指令：

```bash
cd android

# 构建 Debug 版本（包含详细调试日志）
./gradlew assembleDebug

# 构建 Release 版本（开启 R8 混淆、资源压缩、体积最小化）
./gradlew assembleRelease

# 交付推荐完整验证（运行单元测试 + 构建 APK）
./gradlew testDebugUnitTest assembleDebug
```

> **Windows PowerShell 构建提示**：
> 在 Windows 环境下请使用 `.\gradlew.bat` 代替 `./gradlew`。

### 4. 安装 APK

构建完成后，安装包输出在以下路径：
- **Debug 包**：`android/app/build/outputs/apk/debug/app-debug.apk`
- **Release 包**：`android/app/build/outputs/apk/release/app-release.apk`

连接手机打开 USB 调试，通过 `adb` 安装：
```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

---

## Web 管理界面使用 (Web UI)

服务端自带完整的 Web 管理 SPA，**不需要任何前端构建步骤（Node/npm/Vite 均无需安装）**。

1. 服务端启动后，在浏览器直接访问：
   `http://localhost:8000`（本机）或 `http://<PC的局域网IP>:8000`（手机/平板等跨设备访问）
2. **功能模块**：
   - **仪表盘**：查看媒体库文件数、存储容量统计、最近观看/阅读记录；
   - **文件浏览器**：树形目录全盘翻看，支持网格/列表切换、多维排序与实时搜索；
   - **硬件加速 HLS 播放器**：支持任意进度秒级拖拽、倍速播放（0.75x~3x）、键盘快捷键快进；
   - **文本与电子书阅读器**：支持 TXT/EPUB、分章/全文滚动模式、7 套双层主题、字体/字号自由调整；
   - **书架与书签**：跨媒体统一收藏与阅读进度查看。
3. **身份验证**：
   - 若服务端设置了 `server.token`，首次访问会弹出认证口令对话框，输入后自动通过 sessionStorage 保持会话。

---

## 客户端与服务端连接方式 (Connection)

1. **自动发现（推荐）**：
   - 将手机与 PC 连接在同一个局域网（Wi-Fi）下（确保路由器未开启 AP 隔离）；
   - 打开 Android 客户端，应用通过 mDNS 协议在几秒内自动发现局域网内的 LocalMediaHub 实例，点击即可连接。
2. **手动 IP 直连**：
   - 查看 PC 本地 IP（在 Windows 命令提示符运行 `ipconfig`，找到对应网卡的 `IPv4 地址`，例如 `192.168.31.230`）；
   - 在客户端连接设置中手动输入 `http://192.168.31.230:8000` 并连接。
3. **零接触配对（LAN Pairing）**：
   - 若服务端配置了复杂的长随机 Token，手机不想繁琐输入；
   - 临时在服务端 `config.yaml` 中将 `server.lan_pairing: true` 保存并启动服务；
   - 手机客户端在同一 Wi-Fi 下点击“局域网一键配对”，服务端将自动下发 Token 与 BLE 握手密钥；
   - 配对成功后建议将 `lan_pairing` 改回 `false` 确保安全性。

---

## 常见问题排查 (Troubleshooting)

### Q1: 手机 App 提示“连接失败”或搜不到服务端？
- **Windows 防火墙拦截**：首次启动时 Windows 防火墙通常会弹出拦截警告。请前往“控制面板 -> Windows Defender 防火墙 -> 允许应用通过防火墙”，确保 `LocalMediaHub.exe` 的专用网络和公用网络均被勾选；或手动放行 TCP `8000` 端口与 UDP `5353` 端口（mDNS 组播发现）；
- **不同子网 / 访客网络**：部分路由器将 2.4G 与 5G 隔离开或手机误连了“访客 Wi-Fi”。请确认 PC 与手机分配到的 IP 处于同一子网（例如都在 `192.168.31.x`）。

### Q2: 视频播放提示“转码服务不可用”或报错？
- 确认服务端运行环境是否正确安装了 `ffmpeg`，在终端输入 `ffmpeg -version` 检查；
- 确认要播放的视频文件路径是否被包含在 `config.yaml` 的 `scan.roots` 或 `system.allowed_roots` 中；
- 查看服务端控制台日志中输出的具体转码错误信息。

### Q3: Android 构建报错 `cargo: command not found` 或 `cargo-ndk failed`？
- 确认系统安装了 Rust 工具链，并在系统 PATH 中可直接执行 `cargo --version`；
- 确认安装了 `cargo-ndk`（`cargo install cargo-ndk`）；
- 确认安装了 Android 编译架构：`rustup target add aarch64-linux-android`；
- 确认已在环境变量配置了 `ANDROID_NDK_HOME` 或在 `android/local.properties` 中指定了有效的 `ndk.dir`。

### Q4: Android 构建报错 `Release build signing guard rejected the task graph`？
- 这是项目的安全守卫机制，防止无意间发布 Debug 签名的 Release 安装包；
- 本地测试请添加参数：`./gradlew assembleRelease -PallowDebugSigning=true`；
- 正式发布请参考前文创建并配置 `android/keystore.properties`。

---

## 自动化测试与代码质量验证 (Testing)

修改代码后，可通过以下命令对受影响的子系统运行全量验证：

- **服务端单元测试**：
  ```bash
  cd server && go test ./...
  ```
- **Web 端单元测试（基于 Node 原生 test runner + jsdom）**：
  ```bash
  cd server/internal/web && node --test
  ```
- **前端安全与 XSS 静态语法检查**：
  ```bash
  cd tools/xsscheck && go run . ../../server/internal/web
  ```
- **Android 客户端单元测试**：
  ```bash
  cd android && ./gradlew testDebugUnitTest
  ```
- **Rust 原生解码模块单元测试**：
  ```bash
  cd android/app/src/main/rust && cargo test
  ```

---

## 想了解更多？

- **给 AI / 贡献者的工作手册**：[`AGENTS.md`](AGENTS.md) —— 模块架构速查、编码铁律、安全约束、命令清单
- **完整工程规范与设计文档索引**：[`docs/INDEX.md`](docs/INDEX.md) —— 接口端点定义、架构演进史、历史 Spec & Plan

---

## 开源协议

本项目基于 [MIT License](LICENSE) 许可协议开源。

