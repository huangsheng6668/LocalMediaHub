# LocalMediaHub 项目整体优化建议 (v2 — 修正版)

> [!IMPORTANT]
> 本版本对 v1 进行了二次深度代码审查，**修正了 3 处事实性错误、补充了 5 项重大遗漏**。已删除的条目在下方标注 ~~删线~~。

---

## 一、 服务端 (Go Backend) 优化

### 1. ✅ 流传输引擎 — 替换为 `http.ServeContent` *(保留，有效)*
- **问题**：[streaming.go](file:///server/internal/service/streaming.go) 手动解析 Range、手动 `WriteHeader` + `Set Header`。存在一个**严重 Bug**：第 100-104 行先调用 `w.WriteHeader(206)` 再 `w.Header().Set(...)`，Go 的 `http.ResponseWriter` 在 `WriteHeader` 之后设置的 Header **不会被发送**，导致客户端收不到 `Content-Range` / `Content-Length` 等头字段，ExoPlayer 无法正确 seek。
- **方案**：整个 `ServeFile` 方法体替换为 `http.ServeContent(w, r, filepath.Base(filePath), fi.ModTime(), f)`，一行代码解决 Range/ETag/Content-Type 全部问题。

### 2. ✅ 目录扫描 — `filepath.Walk` → `filepath.WalkDir` *(保留，有效)*
- [scanner.go](file:///server/internal/service/scanner.go) 第 55 行使用 `filepath.Walk`。
- **补充说明**：但在实际主流使用场景（系统浏览模式）中，用户通过 [handler/system.go](file:///server/internal/server/handler/system.go) 的 `SystemBrowse` （第 55 行 `os.ReadDir`）和 [handler/folders.go](file:///server/internal/server/handler/folders.go) 的 `BrowseFolder`（第 67 行 `os.ReadDir`）进入目录，这两者**已经使用了高效的 `os.ReadDir`**，不做递归扫描。`filepath.Walk` 仅在全盘 Scan 时触发。这意味着 Walk → WalkDir 改造的**实际优先级低于 v1 的判断**，仅影响 `/api/v1/videos` 和 `/api/v1/images` 的全量列表接口。

### ~~3. API 分页 — 引入 Offset/Limit 分页~~ *(v1 错误，已删除)*
> [!WARNING]
> **v1 事实性错误**：v1 声称 "现有接口将列表一次性响应给客户端"，但实际代码中 [handler/videos.go](file:///server/internal/server/handler/videos.go) 第 15-46 行和 [handler/images.go](file:///server/internal/server/handler/images.go) 第 14-46 行**已经实现了 `page` / `page_size` 分页**，并返回 `PaginatedMediaFiles` 结构体。Android 端 [MediaApi.kt](file:///android/app/src/main/java/com/juziss/localmediahub/network/MediaApi.kt) 第 33-44 行对应接口也已支持分页参数。此条建议完全不适用。

### 4. ✅ 缩略图重采样算法降级 *(保留，有效)*
- 但补充：`Lanczos` → `Linear` 的画质损耗在缩略图场景几乎不可感知，推荐作为**默认值变更**。

### 5. 🆕 **安全漏洞 — System 端点缺少路径校验** *(v1 完全遗漏)*
- **严重问题**：[handler/system.go](file:///server/internal/server/handler/system.go) 的 `SystemOriginal`（第 98-105 行）和 `SystemStream`（第 107-120 行）接受用户通过 `?path=` 传入的**任意绝对路径**，直接调用 `c.File(pathStr)` 或 `h.streaming.ServeFile(..., pathStr)` 进行文件服务，**没有任何路径合法性校验**。这意味着局域网内任何人都可以通过 `GET /api/v1/system/original?path=C:\Users\xxx\Desktop\secret.docx` 访问 PC 上的任意文件。
- **方案**：至少应限制在 `cfg.Scan.GetRoots()` 范围内，或仅允许媒体扩展名文件通过。对比 `StreamVideo`（videos.go 第 53 行）和 `GetThumbnail`（images.go 第 53 行）中已有的 `ValidatePath` 调用。

### 6. 🆕 **`ValidatePath` 路径遍历绕过风险** *(v1 完全遗漏)*
- [streaming.go](file:///server/internal/service/streaming.go) 第 128 行和 [thumbnail.go](file:///server/internal/service/thumbnail.go) 第 112 行的 `ValidatePath` 使用 `!strings.HasPrefix(rel, "..")` 来判断路径是否在 root 内。但 `filepath.Rel` 可能返回 `"."` 或以 `".."` 开头但随后加分隔符的路径。这种检查方式存在**边界情况风险**。
- **方案**：改用更严格的判断 `!strings.HasPrefix(rel, ".."+string(filepath.Separator)) && rel != ".."`。

### 7. 🆕 **重复代码 — `ValidatePath` 和 `GenerateThumbnail` 重复** *(v1 遗漏)*
- `ValidatePath` 在 `StreamingService` 和 `ThumbnailService` 中**逐字重复**（streaming.go 114-133, thumbnail.go 98-117）。`GenerateThumbnail` 和 `GenerateSystemThumbnail` 也几乎完全相同。
- **方案**：将 `ValidatePath` 抽为公共函数（或单独的 `PathValidator` 服务）。将缩略图生成提取为接受 `cacheDir` 参数的通用私有方法。

### 8. 🆕 **CORS 配置冲突** *(v1 遗漏)*
- [cors.go](file:///server/internal/server/middleware/cors.go) 同时设置了 `AllowOrigins: ["*"]` 和 `AllowCredentials: true`。根据 HTTP 规范，当 `Access-Control-Allow-Credentials` 为 `true` 时，`Access-Control-Allow-Origin` **不能**为 `*`。这在 modern 浏览器中会直接被拒绝（虽然 Android 原生客户端不受影响，但如未来有 Web 管理面板则会出问题）。
- **方案**：移除 `AllowCredentials: true`（当前项目不使用 Cookie/Auth），或改为动态回显请求的 `Origin`。

### 9. 🆕 **缩略图缓存不计算源文件修改时间** *(v1 遗漏)*
- [thumbnail.go](file:///server/internal/service/thumbnail.go) 第 33-36 行的缓存键仅基于 `md5(sourcePath)` 生成。如果用户替换了同名图片文件（内容不同），旧缩略图会被永久返回。
- **方案**：将 `modTime` 纳入哈希计算：`md5(sourcePath + modTime.String())`。

---

## 二、 客户端 (Android / Kotlin) 优化

### 1. ✅ BrowseScreen 文件拆分 *(保留，有效)*
- 1134 行的 [BrowseScreen.kt](file:///android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt) 确实需要拆分。建议提取：`FolderCard`、`VideoCard`、`ImageCard`、`WaterfallImageGrid`、`TagMenuDialog`、`TagFilterBar`、`SystemDrivesContent` 到 `ui/component/` 目录。

### ~~2. Paging 3 接入~~ *(v1 判断有偏差，降级为可选)*
> [!NOTE]
> v1 声称需要 Paging 3 来解决"服务端直接抛回所有列表"的问题。但实际上：
> - 主力浏览路径（`BrowseFolder` / `SystemBrowse`）是**按目录读取**的，每次只返回当前目录下的文件和子目录，不是全盘列表。目录内通常只有几十到几百个文件。
> - `/api/v1/videos` 和 `/api/v1/images` 虽然是全量扫描后分页，但这两个接口在当前 Android 端实际**并未被调用**（客户端通过 `browseFolder` / `browseSystemPath` 浏览，不经过这两个全量接口）。
> - 因此 Paging 3 的必要性远低于 v1 的判断。仅在未来新增"全部图片/全部视频"聚合视图时才需要。

### 3. ✅ Coil 缓存优化 *(保留，有效)*
- 当前 [MainActivity.kt](file:///android/app/src/main/java/com/juziss/localmediahub/MainActivity.kt) 第 38-46 行的 `newImageLoader` 没有配置 `memoryCache` 和 `diskCache`，使用的是 Coil 默认值。建议显式配置：
  ```kotlin
  .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.30).build() }
  .diskCache { DiskCache.Builder().directory(cacheDir.resolve("coil")).maxSizePercent(0.05).build() }
  ```

### 4. 🆕 **导航参数通过 `remember` mutableState 传递 — 进程重建后丢失** *(v1 遗漏)*
- [MainActivity.kt](file:///android/app/src/main/java/com/juziss/localmediahub/MainActivity.kt) 第 54-58 行通过 `var currentVideoFile by remember { ... }` 在 Composable 内临时存储导航参数。**Activity 重建（旋转屏幕、系统回收）后这些值会丢失**，导致从视频/图片预览返回时崩溃（`currentVideoFile == null`）。
- **方案**：使用 `rememberSaveable` + 自定义 `Saver`，或通过 Navigation 参数（序列化为 JSON 字符串经 URL 编码传递）。

### 5. 🆕 **`loadFileTagsForFile` 的 N+1 查询问题** *(v1 遗漏)*
- [BrowseViewModel.kt](file:///android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt) 第 494-509 行的 `loadFileTagsWithTags` 对**每一个 Tag** 都发起一次 `repository.getTaggedFiles(tag.id)` 网络请求来判断该文件是否被打了某个标签。如果有 10 个 Tag，就会发起 10 次请求。
- **方案**：服务端新增 `GET /api/v1/files/{path}/tags` 接口，一次返回某个文件关联的所有 Tag，避免 N+1 问题。

### 6. 🆕 **`RetrofitClient` 单例的线程安全问题** *(v1 遗漏)*
- [RetrofitClient.kt](file:///android/app/src/main/java/com/juziss/localmediahub/network/RetrofitClient.kt) 的 `initialize()` 方法不是线程安全的。并发调用时可能读到半构造的 `_retrofit` 实例。
- **方案**：加 `@Synchronized` 注解，或使用 `Lazy` + `AtomicReference` 模式。

---

## 三、 优化优先级排序 (修正后)

| 优先级 | 改动项 | 影响面 | 工作量 |
|:---:|---|---|---|
| 🔴 P0 | System 端点路径校验（安全漏洞） | 任意文件泄露 | 小 |
| 🔴 P0 | `streaming.go` WriteHeader 顺序 Bug | 视频 seek 失败 | 小 |
| 🟡 P1 | `streaming.go` → `http.ServeContent` | 流传输性能/兼容性 | 小 |
| 🟡 P1 | 缩略图缓存键加入 modTime | 缓存一致性 | 小 |
| 🟡 P1 | `ValidatePath` 去重 + 路径遍历加固 | 安全/可维护性 | 中 |
| 🟢 P2 | BrowseScreen.kt 拆分 | 可维护性/性能 | 中 |
| 🟢 P2 | Coil 缓存配置 | 图片加载流畅度 | 小 |
| 🟢 P2 | N+1 Tag 查询优化 | 网络性能 | 中 |
| 🟢 P2 | 导航参数持久化 | 旋转屏/进程回收 | 中 |
| ⚪ P3 | `filepath.Walk` → `WalkDir` | 全盘扫描场景 | 小 |
| ⚪ P3 | 缩略图 Lanczos → Linear | CPU 占用 | 小 |
| ⚪ P3 | CORS 配置修正 | Web 兼容 | 小 |
| ⚪ P3 | RetrofitClient 线程安全 | 并发初始化 | 小 |

---

## 推荐实施路径

1. **第一批（立即修复）**：P0 安全漏洞 + streaming Bug → 仅涉及 `system.go` 和 `streaming.go` 两个文件
2. **第二批（性能加固）**：`ServeContent` 替换 + 缩略图缓存键 + `ValidatePath` 重构
3. **第三批（Android 体验）**：BrowseScreen 拆分 + Coil 缓存 + N+1 查询优化 + 导航持久化
