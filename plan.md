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
- [x] 文件列表正确显示（Folders: 2 roots, Videos: 41 files, Images: 104)
 total)
- [x] 视频流正常播放（ Server Range Requests 206 OK,- [x] 图片缩略图正常加载,Server 燉略图生成, 点击可查看大图)
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

- [x] Server 配置界面（Web 管理页面 `/admin`）

### 5.2 Server 配置界面
- [x] Web 管理页面（内嵌在 FastAPI 中 `/admin`）
- [x] 在线修改扫描目录
- [x] 扫描任务管理（启动/停止/进度）

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
- **Server 配置 UI**： `/admin` Web 管理页面，可修改扫描目录/触发重扫描
- **文件分类标签**： Server JSON 存储 + Android 长按打标签 + 标签筛选

 **视频字幕支持**已跳过（用户确认不需要)。

- **搜索功能**： 已在阶段三实现

 - **排序功能**： 已在阶段五实现
 - **收藏功能**： 已在阶段五实现
 - **文件分类标签**： 已在阶段五实现

---

## 风险与注意事项

1. **路径安全**：Server 必须严格校验请求路径，防止通过 `../` 访问未授权文件
2. **大文件传输**：视频文件可能超过 4GB，注意内存管理和分块大小
3. **跨平台路径**：Server 在 Windows 上开发，使用 `pathlib` 确保兼容
4. **网络环境**：局域网传输，注意 WiFi 稳定性和带宽限制
5. **缩略图缓存**：需要定期清理策略，避免磁盘空间耗尽
