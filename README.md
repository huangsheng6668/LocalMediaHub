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

| 层 | 技术 |
|---|---|
| Server | Go 1.25+ / Echo v4 / modernc.org/sqlite (pure-Go) / fsnotify / getlantern/systray / hashicorp/mdns / tinygo.org/x/bluetooth（BLE Central） |
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
  # lan_pairing: true   # 零接触配对（临时开启）：App 在局域网内免认证自动获取
  #                     # token（含 ble.token），配对完成后改回 false

ble:
  token: ""   # BLE 专属握手密钥：留空回退 server.token；两者皆空 = BLE 开放模式

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
- **零接触配对**：服务端临时开启 `server.lan_pairing: true` 后，App 在局域网内免认证 `POST /api/v1/pair` 即可自动获取 token（含 BLE 密钥），无需手动抄写；配对完成后应改回 `false`。

如服务端配置了 `token`，Android 与 Web 都会弹输入框。

## 项目状态

- 开发阶段；本地改动自动同步推送至 GitHub `master` 分支（个人项目约定）
- License: MIT

## 想了解更多？

- **给 AI / 贡献者的工作手册**：[`AGENTS.md`](AGENTS.md) —— 模块地图、编码规则、安全约定、命令清单、提交约定
- **完整文档索引**：[`docs/INDEX.md`](docs/INDEX.md) —— API 端点表、关键文件指针、历史 spec/plan、迁移与升级指南
