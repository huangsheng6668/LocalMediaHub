# LocalMediaHub 开发计划

> 基于 CLAUDE.md 规范，分阶段实现本地媒体资源管理系统（PC Server + Android Client）

---

## 阶段一：项目初始化

**目标：** 建立项目骨架、依赖管理和基础配置

### 1.1 通用基础设施
- [x] `git init` + `.gitignore`（Python/Android/IDE 通用规则）
- [ ] 项目根目录 README.md

### 1.2 Server 初始化（`/server`）
- [x] `server/requirements.txt`：fastapi, uvicorn, pillow, python-multipart, aiofiles
- [x] `server/main.py`：FastAPI app 入口，挂载路由，CORS 配置
- [x] `server/config.yaml`：监听端口、扫描根路径、缩略图缓存目录等配置项
- [x] `server/api/__init__.py`
- [x] 验证：`uvicorn main:app --reload` 能启动，访问 `/docs` 看到 Swagger UI

### 1.3 Android 初始化（`/android`）
- [x] 使用 Android Studio 创建 Kotlin + Compose 项目
- [x] 配置依赖：Retrofit, OkHttp, Coil, Media3(ExoPlayer), Navigation Compose
- [x] 建立 MVVM 目录结构：
  ```
  app/src/main/java/com/juziss/localmediahub/
  ├── ui/            # Compose 组件
  ├── network/       # Retrofit 接口
  ├── data/          # Model + Repository
  ├── viewmodel/     # ViewModel
  └── MainActivity.kt
  ```
- [x] 验证：`./gradlew assembleDebug` 编译通过

---

## 阶段二：Server 端核心功能

**目标：** 实现文件扫描、REST API、视频流、图片缩略图

### 2.1 文件扫描器（`server/scanner.py`）
- [x] 使用 `pathlib` 递归扫描指定根目录
- [x] 识别视频文件（mp4, mkv, avi, mov, wmv, flv）
- [x] 识别图片文件（jpg, jpeg, png, gif, bmp, webp）
- [x] 收集文件元数据：文件名、路径、大小、修改时间、类型
- [x] 安全检查：防止路径遍历攻击（`resolve()` + `is_relative_to()`）
- [x] 异步实现（`asyncio.to_thread` 包装同步 I/O）
- [x] 扫描缓存（60s TTL，避免重复全量扫描）

### 2.2 REST API（`server/api/`）

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/v1/folders` | GET | 获取配置的根文件夹列表 |
| `/api/v1/folders/{path:path}/browse` | GET | 浏览指定目录下的文件和子目录 |
| `/api/v1/videos` | GET | 获取所有视频列表（支持分页） |
| `/api/v1/images` | GET | 获取所有图片列表（支持分页） |
| `/api/v1/videos/{path:path}/stream` | GET | 视频流（支持 Range Requests） |
| `/api/v1/images/{path:path}/thumbnail` | GET | 获取缩略图 |
| `/api/v1/images/{path:path}/original` | GET | 获取原图 |

### 2.3 视频流传输
- [x] 解析 `Range` 请求头
- [x] 返回 `206 Partial Content` + `Content-Range`
- [x] 支持 `Accept-Ranges: bytes`
- [x] 使用 `aiofiles` 异步读取文件分块传输
- [x] 正确设置 MIME 类型

### 2.4 图片缩略图
- [x] 使用 Pillow 生成缩略图（按比例缩放，最大边 300px）
- [x] 缩略图缓存到配置的缓存目录
- [x] 支持 JPEG/WebP 格式输出
- [x] 懒生成：首次请求时生成并缓存，后续直接返回缓存文件
- [x] 原图接口改为 StreamingResponse（避免大图 OOM）

### 2.5 配置管理
- [x] `config.yaml` 定义：
  ```yaml
  server:
    host: "0.0.0.0"
    port: 8000
  scan:
    roots:
      - "D:/Media/Movies"
      - "D:/Media/Photos"
    video_extensions: [".mp4", ".mkv", ".avi", ".mov"]
    image_extensions: [".jpg", ".jpeg", ".png", ".gif", ".webp"]
  thumbnail:
    cache_dir: ".cache/thumbnails"
    max_size: 300
  ```
- [x] Pydantic 模型校验配置

### 验证
- [x] `curl http://localhost:8000/api/v1/folders` 返回文件夹列表
- [x] `curl http://localhost:8000/api/v1/videos` 返回视频列表
- [x] 浏览器直接访问视频流 URL 可播放并支持拖动进度条
- [x] 缩略图接口返回压缩后的图片

---

## 阶段三：Android 客户端开发

**目标：** 实现 Android 端 UI、网络请求、媒体播放

### 3.1 网络层（`network/`）
- [x] `MediaApi.kt`：Retrofit 接口定义（对应 Server 所有 API）
- [x] `RetrofitClient.kt`：OkHttp + Retrofit 单例配置（动态 baseUrl）
- [x] `NetworkResult.kt`：统一网络响应封装（Success/Error/Loading）

### 3.2 数据层（`data/`）
- [x] `Models.kt`：媒体文件 + 文件夹数据模型（对应 Server models.py）
- [x] `ServerConfig.kt`：DataStore 持久化服务器配置
- [x] `MediaRepository.kt`：仓库层，封装 API 调用 + 错误处理

### 3.3 连接配置 UI
- [x] 输入 Server IP 地址 + 端口的界面
- [x] 连接测试按钮
- [x] 使用 DataStore 持久化保存服务器地址
- [x] 连接失败时显示友好错误提示

### 3.4 主界面（文件浏览器）
- [x] 导航：Jetpack Navigation Compose
- [x] 文件夹列表 + 文件网格/列表切换
- [x] 面包屑导航（显示当前路径层级）
- [ ] 下拉刷新 + 分页加载
- [x] 图片使用 Coil 加载缩略图

### 3.5 视频播放器
- [x] 使用 Media3 (ExoPlayer) 播放视频流
- [x] 支持全屏/小窗切换
- [x] 进度条拖动（依赖 Server Range Requests）
- [x] 播放控制：播放/暂停/快进/快退
- [ ] 横竖屏自动切换

### 3.6 图片预览
- [x] 全屏查看原图
- [x] 左右滑动切换图片
- [x] 双指缩放手势

### 验证
- [x] App 能连接到 PC Server（ `http://<PC_IP>:8000` 已 server 返回数据确认)
- [x] 文件列表正确显示（Folders: 2 roots, Videos: 41 files, Images: 104 total)
- [x] 视频流正常播放（ Server Range Requests 206 OK, 图片缩略图正常加载, Server 缩略图生成, 点击可查看大图)
- [x] 浏览子目录正常（子目录 browse ✅)

---

## 阶段四：集成测试与优化

**目标：** 端到端测试、性能优化、稳定性提升

### 4.1 Server 端测试
- [ ] 单元测试：scanner、API 路由、缩略图生成
- [x] 安全测试：路径遍历攻击防护验证（已通过端到端测试）
- [x] 大文件测试：4GB+ 视频流传输稳定性（有 3.5GB+ 文件测试通过）
- [ ] 并发测试：多客户端同时请求

### 4.2 Android 端测试
- [ ] Repository 层单元测试（Mock API）
- [ ] ViewModel 单元测试
- [ ] UI 测试（Compose Testing）

### 4.3 性能优化
- [x] Server：大目录扫描缓存（60s TTL，已实现）
- [ ] Server：缩略图生成异步化 + 队列
- [ ] Android：列表滚动性能优化（LazyColumn）
- [ ] Android：内存优化（图片加载配置）

### 4.4 异常处理
- [ ] 网络断开重连机制
- [ ] Server 不可达时的友好提示
- [ ] 文件加载失败的占位图
- [ ] 超时处理

---

## 阶段五：高级功能（远期）

**目标：** 锦上添花的高级特性

### 5.1 mDNS 服务发现
- [x] Server：注册 mDNS 服务（`zeroconf` 库）
- [x] Android：NSD (Network Service Discovery) 扫描
- [x] 自动发现局域网内的 Server，无需手动输入 IP

- [x] Server 配置 API（`/api/v1/admin/*`）

### 5.2 Server 配置界面
- [x] 配置读取 API
- [x] 在线修改扫描目录 API
- [x] 触发重扫描 API

### 5.3 其他增强
- [x] 搜索功能（按文件名搜索，当前目录）
- [x] 排序功能（按名称/大小/时间）
- [x] 收藏功能（DataStore 持久化，标记常用文件）
- [ ] 视频字幕支持（不需要）
- [x] 文件分类标签（JSON 存储，标签筛选，长按打标签）

---

## 关键技术决策

| 决策 | 选择 | 原因 |
|------|------|------|
| 视频流协议 | HTTP Partial Content | 简单可靠，ExoPlayer 原生支持 |
| 缩略图策略 | Server 端生成 + 磁盘缓存 | 避免 Android 端处理大图 OOM |
| 配置格式 | YAML | 人类可读，适合手动编辑 |
| 发现机制 | 先手动 IP，后 mDNS | 渐进式开发，降低初期复杂度 |
| 并发模型 | asyncio（Server）/ Coroutines（Android） | 两个平台的惯用异步方案 |

## 依赖清单

### Server (Python)
```
fastapi>=0.100.0
uvicorn[standard]>=0.23.0
pillow>=10.0.0
aiofiles>=23.0
pyyaml>=6.0
pydantic>=2.0
python-multipart>=0.0.6
```

### Android (Gradle)
```kotlin
// Compose BOM
implementation(platform("androidx.compose:compose-bom:2024.01.00"))

// Core
implementation("androidx.core:core-ktx:1.12.0")
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
implementation("androidx.navigation:navigation-compose:2.7.0")

// Network
implementation("com.squareup.retrofit2:retrofit:2.9.0")
implementation("com.squareup.retrofit2:converter-gson:2.9.0")
implementation("com.squareup.okhttp3:okhttp:4.12.0")

// Image
implementation("io.coil-kt:coil-compose:2.5.0")

// Video
implementation("androidx.media3:media3-exoplayer:1.2.0")
implementation("androidx.media3:media3-ui:1.2.0")

// DataStore
implementation("androidx.datastore:datastore-preferences:1.0.0")
```

## 阶段五完成总结

2026-04-05

阶段五所有功能已实现：
- **排序功能**： BrowseScreen TopAppBar 下拉菜单，支持按名称/大小/时间排序
- **收藏功能**： DataStore 持久化，BrowseScreen 收藏按钮，支持筛选只显示收藏文件
- **mDNS 服务发现**： Server 端 zeroconf 注册，Android NSD 扫描，自动发现服务
- **Server 配置 API**： `/api/v1/admin/*` 可修改扫描目录并触发重扫描
- **文件分类标签**： Server JSON 存储 + Android 长按打标签 + 标签筛选

 **视频字幕支持**已跳过（用户确认不需要)。

- **搜索功能**： 已在阶段三实现

 - **排序功能**： 已在阶段五实现
 - **收藏功能**： 已在阶段五实现
 - **文件分类标签**： 已在阶段五实现

---

## 阶段六：系统性能与体验深度优化方案

**目标：** 针对 Go Server 端的并发访问、缩略图计算开销，以及 Android 端的列表滑动流畅度、内存占用和连接韧性进行深度优化，提升整体系统在多文件、大并发和复杂局域网环境下的性能与稳定性。

### 6.1 Go Server 端优化（并发与性能）

#### 6.1.1 扫描器并发化（Concurrent Scan）
- **痛点分析：** 现有的 `Scanner.Scan` 在扫描多个 roots 路径时，是单线程串行调用 `filepath.WalkDir` 的，并且在扫描过程中全程持有全局排他写锁 `s.mu.Lock()`。若扫描目录包含数万个媒体文件，会导致其他读取/请求接口长时间挂起。
- **优化方案：** 
  - 引入 `golang.org/x/sync/errgroup`。在扫描多个根目录时，为每个根目录分配一个独立的 Goroutine 协程进行并行 WalkDir。
  - 在并行 Walk 过程中使用局部切片（Thread-local Slice）收集结果，最后合并到一个总切片中。
  - 仅在合并完成后，获取全局锁 `s.mu.Lock()` 进行极速写入更新缓存，缩短写锁持有时间至微秒级。

#### 6.1.2 缓存击穿与雪崩防护（Cache Stampede Protection）
- **痛点分析：** 当扫描缓存过期后（TTL = 60s），如果有多个并发接口请求获取媒体列表，它们会同时检测到缓存失效，并同时释放读锁、争抢写锁并并行触发物理磁盘扫描。这在多设备、高并发时会导致磁盘 I/O 瞬间飙升。
- **优化方案：**
  - 引入 Go 标准库的 `golang.org/x/sync/singleflight`。
  - 在 `GetCached` 中检测到缓存失效时，通过 `singleflight.Group` 合并并发请求。
  - 保证对于相同的扫描 roots 请求，同一时间仅有一个 Goroutine 执行真实的物理磁盘 WalkDir 操作，其余并发等待的请求直接复用该次执行的返回结果，彻底解决 Thundering Herd（惊群）和 Cache Stampede 问题。

#### 6.1.3 缩略图生成限流与队列（Thumbnail Throttling）
- **痛点分析：** 当 Android 端的网格列表快速滚动时，会发起数十甚至上百个并发缩略图请求。因为图片解码 (imaging.Open) 和缩略图计算 (imaging.Thumbnail) 是高度 CPU 密集的，如果不做控制，会导致瞬间 CPU 100% 满载、响应超时甚至由于内存瞬间申请过多导致 Go 进程被 Windows 强制杀死或引发 GC 卡顿。
- **优化方案：**
  - 在 `ThumbnailService` 中引入一个大小可配置的并发信号量（如 `chan struct{}` 构成的 Semaphore，推荐并发量为 `runtime.NumCPU()`）。
  - 对正在生成缩略图的请求进行严格并发数限制。当并发量达到上限时，后续请求在通道上挂起排队，而不是疯狂创建解码协程。
  - 提供缩略图预生成机制：在 `Scanner` 扫描完成后，可异步在后台以低优先级（Low Priority/Worker Pool）对未缓存的媒体生成缩略图，提升首次浏览的体验。
  - **预生成生命周期管理：** 预生成任务必须接受 `context.Context` 控制。当用户通过 Admin API 触发重扫描（`POST /admin/scan/trigger`）或配置变更时，旧的预生成任务应被 `context.Cancel()` 终止，避免无效工作堆积和资源浪费。

---

### 6.2 Android 客户端优化（流畅度与内存）

#### 6.2.1 Lazy List 渲染优化（contentType & Recomposition）
- **现状分析：** 代码中所有 `LazyVerticalGrid`、`LazyVerticalStaggeredGrid` 的 `items` 已正确设置了稳定的 `key`（如 `key = { it.path }`、`key = { it.relativePath }`），基础的列表复用已到位。但当前缺少 `contentType` 声明，导致 Compose 在混合列表（文件夹 + 视频 + 图片）中无法按类型复用 Item 布局结构，存在不必要的布局重建开销。
- **优化方案：**
  - 在所有 `items` 声明中补充 `contentType` 参数（如 `contentType = { it.mediaType }` 或按 Folder/File 区分），使 Compose 能够按内容类型精确复用 Item 视图树，减少混合列表滚动时的布局重建。
  - 将 UI 层的排序、过滤、标签匹配逻辑全部移至 ViewModel 中，在 `Dispatchers.Default` 线程池异步计算后暴露为 LiveData/Flow。严禁在 Compose 渲染层（List Row 中）直接执行字符串匹配或复杂的过滤计算，确保 UI 渲染的纯粹与极致高效。

#### 6.2.2 Coil 图片加载内存与缓存调优
- **痛点分析：** 大量的高清图片直接加载进内存可能导致 JVM 频繁发生 GC，甚至触发 OutOfMemory (OOM)。
- **优化方案：**
  - 在 `Application` 初始化时，为 Coil 配置自定义 `ImageLoader`。
  - 调优内存缓存：通过 `MemoryCache.Builder` 设置 `maxSizePercent(0.15)` 限制内存缓存占 JVM 堆内存最大 15%。
  - 调优磁盘缓存：在局域网下，限制 Disk Cache 大小为 100MB，防止缩略图缓存过度增长。
  - 对 Coil 启用 `.crossfade(true)`，为缩略图渐现渲染提供 200ms 的平滑过渡动画，消除图片闪现的生硬感。

#### 6.2.3 ExoPlayer 缓存与生命周期安全
- **痛点分析：** 视频播放器如果未在 Activity/Fragment 生命周期暂停（如进入后台或锁屏）时及时释放，会持续占用解码硬件资源和内存。同时，弱 WiFi 环境下大文件视频播放容易频繁缓冲。
- **优化方案：**
  - 将 ExoPlayer 的生命周期与 Compose Lifecycle 彻底绑定。在 `DisposableEffect` 中监听 `Lifecycle.Event.ON_PAUSE` 或 `ON_STOP` 时自动释放/暂停播放器。
  - 自定义 ExoPlayer `DefaultLoadControl` 缓冲区大小：
    - 将 `minBufferMs` 从默认 of 15s 降低为 2-3s（局域网下带宽极佳，较低的启动缓冲能实现“秒播”）。
    - 将 `maxBufferMs` 设为 30s，在后台持续缓冲。

---

### 6.3 网络与局域网韧性优化（Connection Resilience）

#### 6.3.1 NSD mDNS 连接稳定性锁（WifiMulticastLock）
- **痛点分析：** Android 设备由于系统的电源管理或厂商定制的省电策略，往往会在后台或熄屏时过滤掉局域网内的组播包，导致 NSD (Network Service Discovery) 服务发现随机性失效，无法搜索到局域网内的 Go 服务端。
- **优化方案：**
  - 在执行 mDNS 自动搜索前，从 `WifiManager` 申请 `WifiMulticastLock`：
    ```kotlin
    val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val multicastLock = wifiManager.createMulticastLock("LocalMediaHubLock")
    multicastLock.acquire()
    ```
  - 保证在搜索完毕或 ViewModel 销毁时，及时释放该锁，兼顾服务发现的“高成功率”与设备“低功耗”的平衡。

#### 6.3.2 OkHttpClient 连接池调优（Connection Pool Tuning）
- **痛点分析：** 网格列表中快速加载上百张缩略图时，默认的 OkHttp 连接池（`maxIdleConnections=5`，`keepAliveDuration=5min`）可能不足以支撑高并发的缩略图请求，导致频繁的 TCP 连接建立与销毁开销。
- **技术约束：** HTTP/2 多路复用要求 TLS 加密传输（h2 over TLS），而当前 Go Server 监听明文 HTTP（`0.0.0.0:8000`），Android 端通过 `http://<IP>:8000` 连接。在不引入自签名证书的局域网场景下，HTTP/2 不可行。
- **优化方案：**
  - 在 `RetrofitClient` 的 `OkHttpClient.Builder()` 中显式配置连接池参数：`connectionPool(ConnectionPool(maxIdleConnections = 15, keepAliveDuration = 5, TimeUnit.MINUTES))`，增大空闲连接上限以适配缩略图并发场景。
  - 确保 Go Server 端 Echo 的 `Keep-Alive` 保持开启（Go `net/http` 默认开启），使 Android 端在 HTTP/1.1 下也能高效复用已建立的 TCP 连接，避免重复握手。
  - **未来可选升级路径：** 若后续需要 HTTP/2，可在 Go Server 启用 TLS（`e.StartTLS`）+ 自签名证书，并在 Android 端配置 `network_security_config.xml` 信任该证书。

---

### 6.4 远程 PC 文件与目录删除支持 (Remote PC File & Directory Deletion Support)

**目标：** 在保障系统路径安全和防误删的前提下，支持在 Android 手机上长按或通过操作菜单永久删除 PC 服务端上的文件与目录。

#### 6.4.1 Go Server 端安全删除接口
- **痛点/安全分析：** 远程删除是极高危的操作，一旦存在路径遍历漏洞或越权访问，可能导致 PC 端的系统关键目录被恶意清空。
- **设计与安全方案：**
  - **全局开关（Kill-Switch）：** 在 `config.yaml` 的 `system` 段新增 `enable_delete: false` 配置项（默认关闭），对应 `SystemConfig` 结构体增加 `EnableDelete bool` 字段。Handler 在处理删除请求时，首先检查此开关，若未显式开启则直接返回 `403 Forbidden: delete not enabled`。用户必须手动编辑配置文件开启此高危功能。
  - **路由注册：** 注册 `POST /api/v1/system/delete` 接口（使用 POST 而非 DELETE，原因：路径中可能包含特殊字符如 `#`、`&`、中文，放在 Query 参数中需要额外 URL 编码处理，且某些代理/网关会将 URL 含 Query 参数记录到访问日志中，导致敏感路径泄露。POST + JSON Body 更安全）。
  - **三重路径校验（防御性设计）：**
    - 使用 `service.NormalizePath` 规范化输入的目标路径。
    - 校验该路径是否在 `scanRoots` 或 `systemAllowedRoots` 的**严格子路径**中（强阻断对根目录本身的删除——即 `rel != "."` 时才允许）。
    - 拒绝任何包含 blocked 关键字（如 `windows`、`system32` 等）的路径。
  - **物理删除逻辑：**
    - 检查路径是否存在，若是文件，调用 `os.Remove(path)`。
    - 若是目录，为了防止超大规模的递归删除带来灾难性后果，需限制仅当客户端显式传递 `recursive=true` 参数时才调用 `os.RemoveAll(path)` 递归删除，否则只允许删除空目录，保障物理安全。
  - **缓存与状态同步：** 删除成功后，Go 后端必须：
    1. 立即调用 `scanner.InvalidateCache()` 废弃扫描器的文件缓存，确保后续浏览拉取结果实时刷新。
    2. 调用 `TagsService` 清理已删除路径的所有标签关联记录（若删除的是文件，清理该 `filePath` 的关联；若删除的是目录，清理该目录前缀下所有 `filePath` 的关联），防止 `tags.json` 中产生孤儿数据（Dead Reference），避免按标签浏览时返回已不存在的文件。

#### 6.4.2 Android 客户端 UI 与交互逻辑
- **设计与交互方案：**
  - **操作入口：** 在 `BrowseScreen.kt` 网格项（或 `BrowseContent.kt` 列表项）添加长按手势（LongPress）或三点操作按钮，弹出下拉动作菜单（DropdownMenu / BottomSheet），显示 “删除” (Delete) 选项。
  - **防误删确认弹窗（AlertDialog）：**
    - 点击删除选项后，必须展示强警告二次确认对话框。
    - 对话框文案：“确定要从 PC 端永久删除 [文件/目录名称] 吗？此操作将无法恢复！”
    - 选项：“取消”（默认聚焦）与“确认删除”（红色警告样式）。
  - **网络层封装（Retrofit）：**
    - 在 `MediaApi.kt` 中添加对应的 Retrofit 契约定义（使用 POST + JSON Body，与 Server 端 POST 路由对应）：
      ```kotlin
      data class DeleteRequest(val path: String, val recursive: Boolean = false)

      @POST("api/v1/system/delete")
      suspend fun deletePath(
          @Body request: DeleteRequest
      ): Response<ResponseBody>
      ```
  - **界面状态即时刷新：**
    - 删除成功后，客户端弹出 “删除成功” 的 Toast，并立即触发 Local list 的重绘或发起数据刷新拉取，确保 UI 上的删除表现灵敏且无延迟。

---

## 优化实施验证路线图

1. **第一阶段：Go Server 并发与防击穿重构**
   - 验证手段：使用 `ab` 或 `wrk` 工具进行多客户端并发扫描请求（`/api/v1/folders/browse` 和 `/api/v1/videos`），观察 CPU 和内存曲线是否稳定，确保无 Race Conditions。
2. **第二阶段：Go Server 缩略图 CPU 并发限制**
   - 验证手段：局域网内使用 Android App 狂划图片列表，观察 Go Server 进程的线程数 and CPU 占用率是否符合预期，确认没有瞬间暴涨导致系统卡死。
3. **第三阶段：Android Compose Lazy List 滑动帧率验证**
   - 验证手段：开启 Android 开发者选项中的“Profile GPU Rendering”（GPU 呈现模式分析），观察柱状图是否在 16ms/60fps (或 90fps/120fps) 的基准线以下，验证滑动流畅度。
4. **第四阶段：局域网连接池复用验证**
   - 验证手段：在 Android 端抓包或通过 Fiddler/Charles 观察连接建立情况，确认大批量缩略图请求在 HTTP/1.1 Keep-Alive 下高效复用 TCP 连接，连接数稳定在连接池上限附近而非持续创建新连接。
5. **第五阶段：远程删除功能安全性与一致性验证**
   - 验证手段：
     1. 安全黑盒测试：输入非授权路径（如 `C:\Windows\system32` 或 `..\..\etc\passwd` 等），验证 Go Server 接口是否坚定返回 `403 Forbidden`。
     2. 物理删除测试：在 Android App 上触发删除 PC 上的某个测试媒体文件或测试文件夹，验证 PC 上该路径是否确实被移除，且 App 端的浏览列表即时移除了对应节点。

---

## 风险与注意事项

1. **路径安全**：Server 必须严格校验请求路径，防止通过 `../` 访问未授权文件。对于高风险的远程物理删除操作，必须实施最严苛的子路径判定，严禁删除根目录。
2. **大文件传输**：视频文件可能超过 4GB，注意内存管理和分块大小。
3. **并发同步**：并发扫描和 singleflight 引入了多协程竞争，必须确保读写锁的正确匹配，防止锁死或脏读。
4. **网络环境**：局域网传输，注意 WiFi 稳定性和带宽限制，多路复用虽然好，但在网络质量极差时可能会触发重传阻塞。
5. **缩略图缓存**：需要定期清理策略，避免磁盘空间耗尽。
6. **误删风险**：PC 端物理删除操作是永久且不可逆的（局域网删除不经过系统回收站）。Android 客户端必须严格限制交互确认流程；Go 服务端必须杜绝系统核心路径的任何写操作。
