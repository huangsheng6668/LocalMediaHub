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

---

### 6.2 Android 客户端优化（流畅度与内存）

#### 6.2.1 Lazy List 渲染优化（Key & Recomposition）
- **痛点分析：** 随着扫描文件增多，BrowseScreen 网格列表或 HomeScreen 列表在滚动时可能出现轻微掉帧。由于未定义 `key`，任何局部变更（如某项打上了标签、被收藏）都会导致整个 Lazy 列表所有 Item 进行不必要的重新绘制与 Recomposition。
- **优化方案：**
  - 在 `LazyVerticalGrid` 和 `LazyColumn` 的 `items` 声明中，显式指定唯一稳定的 `key`（如 `key = { it.path }`），并设定 `contentType`（如 `contentType = { it.mediaType }`），促使 Compose 高效复用列表 Item 的视图结构。
  - 将 UI 层的排序、过滤、标签匹配逻辑全部移至 ViewModel 中，在 `Dispatchers.Default` 线程池异步计算后暴露为 LiveData/Flow。严禁在 Compose 渲染层（List Row 中）直接执行字符串匹配或复杂的过滤计算，确保 UI 渲染的纯粹与极致高效。

#### 6.2.2 Coil 图片加载内存与缓存调优
- **痛点分析：** 大量的高清图片直接加载进内存可能导致 JVM 频繁发生 GC，甚至触发 OutOfMemory (OOM)。
- **优化方案：**
  - 在 `Application` 初始化时，为 Coil 配置自定义 `ImageLoader`。
  - 调优内存缓存：限制内存缓存占用 JVM 堆内存最大为 15%，启用 `bitmapPool` 提高图片对象复用率。
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

#### 6.3.2 OkHttpClient HTTP/2 局域网高并发连接复用
- **痛点分析：** 网格列表中快速加载上百张缩略图时，如果 OkHttp 仍在使用 HTTP/1.1，会创建大量 TCP 连接，造成极高的连接建立开销。
- **优化方案：**
  - 在 `RetrofitClient` 配置中，显式声明 OkHttpClient 支持 `Protocol.HTTP_2` 和 `Protocol.HTTP_1_1`。
  - 在 Go Server 端，配置 Echo / Standard HTTP 开启 HTTP/2 传输。利用多路复用（Multiplexing）技术，使得 Android 端只需通过单一 TCP 连接就能并发地流水线式拉取大量缩略图，大幅度降低网络 RTT（往返时延）并减少握手损耗。

---

### 6.4 远程 PC 文件与目录删除支持 (Remote PC File & Directory Deletion Support)

**目标：** 在保障系统路径安全和防误删的前提下，支持在 Android 手机上长按或通过操作菜单永久删除 PC 服务端上的文件与目录。

#### 6.4.1 Go Server 端安全删除接口
- **痛点/安全分析：** 远程删除是极高危的操作，一旦存在路径遍历漏洞或越权访问，可能导致 PC 端的系统关键目录被恶意清空。
- **设计与安全方案：**
  - **路由注册：** 注册 `DELETE /api/v1/system/delete` 接口。
  - **双重路径校验（防御性设计）：**
    - 使用 `service.NormalizePath` 规范化输入的目标路径。
    - 校验该路径是否在 `scanRoots` 或 `systemAllowedRoots` 的子路径中（强阻断对根目录本身的删除）。
    - 拒绝任何包含 blocked 关键字（如 `windows`、`system32` 等）的路径。
  - **物理删除逻辑：**
    - 检查路径是否存在，若是文件，调用 `os.Remove(path)`。
    - 若是目录，为了防止超大规模的递归删除带来灾难性后果，需限制仅当客户端显式传递 `recursive=true` 参数时才调用 `os.RemoveAll(path)` 递归删除，否则只允许删除空目录，保障物理安全。
  - **缓存与状态同步：** 删除成功后，Go 后端必须立即调用 `s.InvalidateCache()` 废弃扫描器的文件缓存，确保后续所有的浏览拉取结果实时刷新。

#### 6.4.2 Android 客户端 UI 与交互逻辑
- **设计与交互方案：**
  - **操作入口：** 在 `BrowseScreen.kt` 网格项（或 `BrowseContent.kt` 列表项）添加长按手势（LongPress）或三点操作按钮，弹出下拉动作菜单（DropdownMenu / BottomSheet），显示 “删除” (Delete) 选项。
  - **防误删确认弹窗（AlertDialog）：**
    - 点击删除选项后，必须展示强警告二次确认对话框。
    - 对话框文案：“确定要从 PC 端永久删除 [文件/目录名称] 吗？此操作将无法恢复！”
    - 选项：“取消”（默认聚焦）与“确认删除”（红色警告样式）。
  - **网络层封装（Retrofit）：**
    - 在 `MediaApi.kt` 中添加对应的 Retrofit 契约定义：
      ```kotlin
      @DELETE("api/v1/system/delete")
      suspend fun deletePath(
          @Query("path") path: String, 
          @Query("recursive") recursive: Boolean
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
4. **第四阶段：局域网连接与协议复用验证**
   - 验证手段：在 Android 端抓包或通过 Fiddler/Charles 观察连接建立情况，确认大批量缩略图请求走的是 HTTP/2 多路复用，并且 TCP 连接保持复用状态。
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
