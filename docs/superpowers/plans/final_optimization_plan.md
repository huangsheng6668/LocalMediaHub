# LocalMediaHub 深度优化终极作战计划 (Final Detail Plan)

本计划整合了之前的版本分析，剔除了事实性错误，并针对**安全性、性能、稳定性、可维护性**四个维度进行了深度具象化。

---

## 阶段一：红色警报 — 安全与核心缺陷修复 (紧急)

### 1. 彻底根除系统浏览端点的路径遍历漏洞
- **目标**：防止局域网用户通过 `?path=` 参数读取系统敏感文件。
- **实施细节**：
    - 在 `internal/service/path.go` [NEW] 中实现统一的 `ValidatePath(target string, allowedRoots []string)`。
    - 校验逻辑：使用 `filepath.Abs` 展开路径，并确保 `filepath.Rel` 结果不以 `..` 开头且不等于 `..`。
    - **应用位置**：`handler/system.go` 中的 `SystemOriginal`、`SystemStream`、`SystemThumbnail` 必须强制调用此校验。
    - **补充策略**：如果路径不在 Roots 内，则检查文件扩展名是否在配置的白名单中。

### 2. 修复 `streaming.go` 的响应头发送时序 Bug
- **目标**：解决部分播放器拖动进度条失败的问题。
- **实施细节**：
    - **彻底简化**：废弃 `StreamingService.ServeFile` 中的手动 Range 解析逻辑。
    - **重构换代**：直接使用 `http.ServeContent(w, r, name, modTime, seeker)`。
    - **优势**：利用 Go 标准库处理所有的复杂边界（Content-Range, ETag, If-Range, Content-Type 自动探测）。

---

## 阶段二：架构重塑 — Android UI 分层与状态解耦

### 1. 拆解 1100 行的 "巨型类" — `BrowseScreen.kt`
- **目标**：减少重组开销，提高代码可读性。
- **实施细节**：
    - 创建 `ui/component/` 包，将以下组件独立：
        - `FolderItem.kt`: 包含 `FolderCard` 布局。
        - `MediaItem.kt`: 包含 `VideoCard` 和 `ImageCard`。
        - `TagComponents.kt`: 包含 `TagFilterBar` 和 `TagMenuDialog`。
        - `GridContainers.kt`: 包含 `WaterfallImageGrid` (Staggered) 和标准 `FolderGrid`。
    - **状态提升**：在 `BrowseScreen` 中仅保留导航和全局 Scaffold 逻辑，内容区域封装为 `BrowseBodyContent`。

### 2. 导航状态持久化 — 解决 "旋转屏消失" 的 Bug
- **目标**：防止 Activity 重建导致导航参数丢失。
- **实施细节**：
    - 将 `MainActivity` 中的 `currentVideoFile` 等 `remember { mutableStateOf }` 替换为 `rememberSaveable`。
    - **进阶方案**：重构 `NavHost` 路由，通过 `navController.navigate("videoPlayer/${encodedPath}")` 传递参数，使系统自动处理恢复。

---

## 3. 性能起飞 — 缓存、IO 与 网络优化

### 1. 完善图片加载流水线 (Coil)
- **目标**：实现秒开预览，滑动无白块。
- **实施细节**：
    - 在 `MainActivity.newImageLoader` 中显式配置其缓存策略：
        - `MemoryCache`: 占用可用内存的 25%。
        - `DiskCache`: 在 `cacheDir/coil` 创建 100MB 限制的磁盘缓存。
    - **缩略图键值优化**：在 `ThumbnailService` 生成哈希时，强制加入文件的 `ModTime`，防止同名文件覆盖后显示旧图。

### 2. 服务端 IO 升级
- **目标**：万级文件扫描不卡死。
- **实施细节**：
    - `scanner.go`: 将 `filepath.Walk` 替换为 `filepath.WalkDir`。
    - **算法优化**：`thumbnail.go` 中的 `imaging.Lanczos` 原为 6 级采样，改为 `imaging.Linear` 或 `imaging.Box` 以大幅降低生成首图时的 CPU 等待时间。

### 3. 消灭 N+1 Tag 查询
- **目标**：进入目录时瞬间显示标签。
- **实施细节**：
    - **后端新增接口**：`GET /api/v1/folders/:path/tags` [NEW]，一次性返回该目录下所有已打标文件的映射关系 `map[string][]Tag`。
    - **前端适配**：`BrowseViewModel` 改为按需一次性预取目录标签，而不是循环发起网络请求。

---

## 阶段四：选备增强 — 针对超大规模库

### 1. 真正的 Paging 3 应用场景
- **实施条件**：仅在用户开启 "查看所有视频/图片" 聚合视图时启用。
- **后端配合**：确保 `GetVideos` / `GetImages` 的分页参数在扫描缓存中高效切片。

---

## 优先级摘要表

| 模块 | 任务 | 严重性 | 难易度 |
| :--- | :--- | :--- | :--- |
| **Go** | **系统浏览路径校验 (Security)** | 🔴 P0 (致命) | 🟢 易 |
| **Go** | **ServeContent 修复 (Streaming)** | 🔴 P0 (致命) | 🟢 易 |
| **Android** | **Navigation 参数 Saveable** | 🟡 P1 (高) | 🟢 易 |
| **Android** | **BrowseScreen.kt 深度拆分** | 🟡 P1 (高) | 🟡 中 |
| **Go/Droid** | **Tag 分页/聚合查询优化** | 🟢 P2 (中) | 🟡 中 |
| **Go** | **Thumbnail 缓存/算法优化** | 🟢 P2 (中) | 🟢 易 |
