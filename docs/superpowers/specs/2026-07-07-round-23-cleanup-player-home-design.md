# Round 23 — 全面体检后优化批次

> 基于 2026-07-07 对 Go 服务端、Android 客户端、Web UI 三大模块的全面代码体检（剔除测试覆盖维度）。
> 包含三个独立子组：G1 死代码与 bug 清理 / G2 视频播放器体验 / G3 首页状态合并。

---

## 背景与范围

项目刚完成 P0/P1/P2 共 19 项大改造（round 21~22），整体代码质量已较高。
本次全面体检从 A 代码质量、B 性能、C 安全、D 正确性/健壮性、E UX 五个维度扫描，
找出 19 个具体问题。本 spec 把其中 14 个打包成 3 个独立子组执行，
其余 5 个（B1/B2/B4/B5/C3）属纯性能或重大设计议题，留待真实瓶颈出现再做。

### 子组划分

| 子组 | 主题 | 涉及问题 | 主要文件 |
|---|---|---|---|
| **G1** | 死代码与 bug 清理 | A1, A3, A4, A5, A6, A7, C1, C4, D1, D2, D5 | Go 服务端 + Android |
| **G2** | 视频播放器体验 | E1, E4（关联 A4） | `VideoPlayerScreen.kt`、`MainActivity.kt`、`strings.xml` |
| **G3** | 首页状态合并 | B3 | `HomeViewModel.kt` |

### 执行顺序

G1 → G3 → G2。

- G1 风险最低、范围最聚焦，先做掉清理噪音
- G3 是隔离的 ViewModel 重构，与 G1 不交叉
- G2 涉及 UI 视觉决策，最后做保留调整空间

### 不在本轮范围

- B1（搜索 O(n) 优化）、B2（cap 预估）、B4（sync.Pool）、B5（扫描并发调优）— 纯性能优化，待真实瓶颈出现
- C2/C3（admin 鉴权）— 需要单独的鉴权设计议题
- D3/D4/D6/E2/E3/E5 — 收益小或非阻塞

---

## G1 — 死代码与 bug 清理（11 项执行 + 1 项标 TODO 给 G2）

### G1-1 删除 `searchFolders` 死代码

- **文件**: `server/internal/server/handler/search.go:111-113`
- **现状**: `searchFolders` 函数已被 `searchFoldersCtx` 完全取代，全项目无调用方（grep 确认）
- **动作**: 删除 `searchFolders` 函数及其上方的注释
- **验收**: `grep -n "searchFolders\b" server --include="*.go"` 只返回 `searchFoldersCtx`

### G1-2 删除 `handler/tags.go` 的 `var _ models.FileTag`

- **文件**: `server/internal/server/handler/tags.go:133`
- **现状**: `var _ models.FileTag` + `// ensure models import is used` 注释
- **动作**: 删除该行与注释。`models` 已在 `GetTaggedMedia` 函数中实际使用（`[]models.MediaFile{}`），import 不需要兜底
- **验收**: `go build ./...` 通过

### G1-3 修 `AssociateTag` / `DisassociateTag` 的 `%2F` 双重解码

- **文件**: `server/internal/server/handler/tags.go:51, 71`
- **现状**:
  ```go
  pathStr := c.Param("*")
  pathStr = strings.ReplaceAll(pathStr, "%2F", "/")
  ```
- **问题**: Echo 的 wildcard param 已默认 URL 解码，此处手写替换是双重解码隐患。文件名含 `%2F` 字面量（理论上可能，如 URL 编码的中文路径残留）会出错
- **动作**: 删除 `strings.ReplaceAll` 那一行
- **回归风险评估**: Windows 文件名不允许 `/` 字符，Android 端 `MediaRepository.tagFile/untagFile` 走 `normalizeRoutePath`（反斜杠转正斜杠 + URLEncoder），实际无影响
- **验收**: 保留 `path_suffix_test.go` 现有用例通过；手动用 Android 客户端长按打标签验证

### G1-4 `VideoPlayerScreen.onDelete` 死参数标记

- **文件**: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt:102`
- **现状**: `onDelete: (() -> Unit)? = null` 参数被声明但屏幕内无任何 UI 触发
- **决策**: G1 阶段不删除该参数（G2 会重新启用它），仅加 TODO 注释
- **动作**: 在 `onDelete` 参数声明上方加 `// G2 will wire this to a top-bar delete IconButton`
- **验收**: G1 完成后 `onDelete` 仍是死参数；G2 完成后该 TODO 被移除

### G1-5 删除 `BrowseViewModel.kt` 死 import + 死函数

- **文件**: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt`
- **现状**:
  - import `android.os.Environment`、`java.io.File`、`java.io.FileOutputStream`、`java.util.zip.ZipFile`（下载逻辑已迁到 `DownloadManager`）
  - `private fun List<FavoriteMediaEntry>.associateFavoriteModes()` 私有函数无调用方
- **动作**: 删除 4 个 import + 删除 `associateFavoriteModes` 函数
- **验收**: `./gradlew compileDebugKotlin` 通过；Android Studio Lint 无未使用警告

### G1-6 修 `mdns.NewService` 误导 API

- **文件**: `server/internal/mdns/mdns.go:15-17` + `server/cmd/server/main.go:27`
- **现状**: `NewService(host, port)` 接收参数但完全不用（只 `make(chan struct{})`），所有真实工作在 `Start(host, port)` 里
- **动作**:
  - `NewService()` 改为不接受参数
  - `main.go:27` 调用方同步更新：`localmdns.NewService()`
- **验收**: mDNS 启动日志不变

### G1-7 统一 mDNS 日志到 slog

- **文件**: `server/internal/mdns/mdns.go:49`
- **现状**: `fmt.Printf("mDNS: advertising ...")`，与全项目 `log/slog` 风格不一致
- **动作**: 改为
  ```go
  slog.Info("mDNS advertising", "service", "_localmediahub._tcp.local.", "ip", ip, "port", port)
  ```
- **验收**: 启动日志格式与 server 启动日志一致

### G1-8 修 `DeletePath` 错误响应泄露（C1）

- **文件**: `server/internal/server/handler/system.go:192-237`
- **现状**: 行 194/199/204/209 用 `c.JSON(..., err.Error())` 直接回错，与 plan.md P0#4 修复政策不一致（其他 handler 已用 `respondError`/`respondInternalError`）
- **动作**: 全部改用 helper：
  - 194（删除未启用）: `respondError(c, http.StatusForbidden, "remote deletion is disabled")`
  - 199（bind 错）: `respondError(c, http.StatusBadRequest, "invalid request body", err)`
  - 204（path 空）: `respondError(c, http.StatusBadRequest, "path required")`
  - 209（ValidateDeletion 失败）: `respondError(c, http.StatusForbidden, "access denied")` — 不泄露 `ValidateDeletion` 内部原因
- **验收**: 恶意请求无法从错误响应推断文件系统结构

### G1-9 修 `start` 参数无校验（C4）

- **文件**: `server/internal/service/streaming.go:221-225`
- **现状**: `startSec := r.URL.Query().Get("start"); if startSec == "" { startSec = "0" }` 后直接拼到 ffmpeg `-ss` 参数，无类型/范围校验
- **风险**: ffmpeg 虽用 `args...` 形式（无 shell 注入），但负数/超大值未防御，且 "NaN" 会被 ParseFloat 认为合法并绕过范围检查
- **动作**:
  ```go
  startSecStr := r.URL.Query().Get("start")
  var startSec float64 = 0
  if startSecStr != "" {
      parsed, err := strconv.ParseFloat(startSecStr, 64)
      if err != nil || math.IsNaN(parsed) || parsed < 0 || parsed > 86400 {
          return fmt.Errorf("invalid start parameter")
      }
      startSec = parsed
  }
  // 后续 args 构造改用 strconv.FormatFloat(startSec, 'f', 3, 64)
  ```
  返回的 error 由 handler 转 400（已有逻辑），客户端非法 `start` 得到 400 而非喂垃圾给 ffmpeg
- **验收**: `curl "...?transcode=true&start=abc"` 返回 400；`start=-5` 返回 400；`start=10.5` 正常工作；`start=NaN` 返回 400


### G1-10 修 Range 解析静默吞错（D2）

- **文件**: `server/internal/service/streaming.go:135-156`
- **现状**:
  ```go
  start, err = strconv.ParseInt(parts[0], 10, 64)
  if err != nil {
      start = 0   // 静默吞错，违反 RFC 7233
  }
  ```
- **动作**: 解析失败时向 ResponseWriter 写入 400 Bad Request 并返回 nil，与已有的 range 格式校验风格保持一致（避免被外层 handler 当作 500 内部错误返回给客户端）：
  ```go
  start, err = strconv.ParseInt(parts[0], 10, 64)
  if err != nil {
      w.Header().Del("Accept-Ranges")
      w.WriteHeader(http.StatusBadRequest)
      return nil
  }
  // 同理处理 end 的解析
  ```
- **回归风险评估**: ExoPlayer 实测发的 Range 都是合法 `bytes=N-` 格式，影响为零；自定义客户端发非标 Range 会从"返回全文件"变 400（行为变更，但符合 RFC）
- **验收**: 现有 streaming_test.go 用例通过；ExoPlayer 视频播放正常


### G1-11 修 `Scanner.TriggerScan` 数据竞争（D1）

- **文件**: `server/internal/service/scanner.go:68-79`
- **现状**:
  ```go
  func (s *Scanner) TriggerScan(roots []string) {
      s.bgCancel()                                       // 无锁读
      s.bgCtx, s.bgCancel = context.WithCancel(...)      // 无锁写
      go s.Scan(s.bgCtx, roots)
  }
  func (s *Scanner) Shutdown() {
      s.bgCancel()                                       // 无锁读
  }
  ```
- **问题**: TriggerScan 与 Shutdown 并发调用时存在数据竞争（plan.md P1#7 修了 `preGenCancel` 但 Scanner 自身的 bgCtx/bgCancel 仍裸读写）
- **动作**: 用已有 `s.mu` 互斥锁保护：
  ```go
  func (s *Scanner) TriggerScan(roots []string) {
      s.mu.Lock()
      if s.bgCancel != nil {
          s.bgCancel()
      }
      s.bgCtx, s.bgCancel = context.WithCancel(context.Background())
      ctx := s.bgCtx
      s.mu.Unlock()
      go s.Scan(ctx, roots)
  }
  func (s *Scanner) Shutdown() {
      s.mu.Lock()
      if s.bgCancel != nil {
          s.bgCancel()
      }
      s.mu.Unlock()
  }
  ```
  注意：`Scan` 内部已用 `s.mu` 保护 cache，TriggerScan/Shutdown 与之共用同一锁不会死锁（临界区不重叠）
- **验收**: `go test -race ./internal/service/...` 通过

### G1-12 修 `serveTranscoded` 的 `cmd.Process.Kill()` nil panic（D5）

- **文件**: `server/internal/service/streaming.go:264-267, 279`
- **现状**:
  ```go
  go func() {
      <-ctx.Done()
      if cmd.Process != nil {
          cmd.Process.Kill()    // 位置 A：goroutine
      }
  }()
  // ...
  if _, wErr := w.Write(buf[:n]); wErr != nil {
      cmd.Process.Kill()        // 位置 B：主循环，无 nil 检查
      return nil
  }
  ```
- **问题**: 位置 B 无 nil 检查；两处可能并发调用 Kill
- **动作**: 用 `sync.Once` + nil 检查统一保护：
  ```go
  var killOnce sync.Once
  killCmd := func() {
      killOnce.Do(func() {
          if cmd.Process != nil {
              cmd.Process.Kill()
          }
      })
  }
  // goroutine 改为 killCmd()
  // 主循环也改为 killCmd()
  ```
- **验收**: 单元测试难以触发原 panic，靠代码评审 + 现有 streaming_test.go 通过即可

---

## G2 — 视频播放器体验

### G2-1 缓冲指示器（E1）

- **文件**: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt`
- **现状**: 无任何缓冲反馈，STATE_BUFFERING 时屏幕全黑
- **动作**:
  - 新增状态：`var isBuffering by remember { mutableStateOf(false) }`
  - 在现有 `DisposableEffect(exoPlayer)` 的 `Player.Listener.onPlaybackStateChanged` 里追加：
    ```kotlin
    isBuffering = playbackState == Player.STATE_BUFFERING
    ```
    （保留现有 STATE_ENDED 处理）
  - 在 Box 内（与 gesture indicator 同层）新增 overlay：
    ```kotlin
    AnimatedVisibility(
        visible = isBuffering,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.Center)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
        ) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
        }
    }
    ```
- **避免误显示**: 仅 STATE_BUFFERING 显示；STATE_IDLE / STATE_READY / STATE_ENDED 不显示
- **验收**:
  - 播放器打开时立即显示缓冲圈，开始播放后消失
  - 拖动 seek 后短暂显示缓冲圈
  - 网络抖动时显示缓冲圈

### G2-2 删除入口（E4 + 激活 G1-4 的 TODO）

- **文件**: `VideoPlayerScreen.kt` + `MainActivity.kt`
- **现状**: `VideoPlayerScreen.onDelete` 参数已声明，`MainActivity.kt:351-373` 已实现完整删除 lambda（调 `deletePathSync` → Toast → popBackStack → refreshCurrentDirectory），但 MainActivity 调用点（`MainActivity.kt:334`）未传入
- **UI 设计决策**: 删除按钮放在**顶部 back 按钮旁**，作为 IconButton 与 back 同尺寸、`Alignment.TopStart` 水平排列，避免遮挡手势区域
- **动作**:
  1. **VideoPlayerScreen.kt**:
     - 把现有 back `IconButton` 包进 `Row`，加删除 IconButton：
       ```kotlin
       Row(
           modifier = Modifier
               .align(Alignment.TopStart)
               .padding(top = 8.dp, start = 4.dp)
       ) {
           IconButton(onClick = onBack) {
               Icon(Icons.AutoMirrored.Filled.ArrowBack, ...)
           }
           if (onDelete != null) {
               IconButton(onClick = { showDeleteConfirm = true }) {
                   Icon(Icons.Filled.Delete, contentDescription = ..., tint = Color.White)
               }
           }
       }
       ```
     - 新增本地状态 `var showDeleteConfirm by remember { mutableStateOf(false) }`
     - 新增 AlertDialog（复用 strings.xml 已有的 `video_delete_title` / `video_delete_desc`）：
       ```kotlin
       if (showDeleteConfirm && onDelete != null) {
           AlertDialog(
               onDismissRequest = { showDeleteConfirm = false },
               title = { Text(stringResource(R.string.video_delete_title)) },
               text = { Text(stringResource(R.string.video_delete_desc)) },
               confirmButton = {
                   TextButton(onClick = {
                       showDeleteConfirm = false
                       onDelete?.invoke()
                   }) { Text(stringResource(R.string.delete)) }
               },
               dismissButton = {
                   TextButton(onClick = { showDeleteConfirm = false }) {
                       Text(stringResource(R.string.cancel))
                   }
               }
           )
       }
       ```
  2. **MainActivity.kt** (`composable("videoPlayer")` 块，行 331-374):
     - 把现有 `onDelete = { ... }` lambda 显式传入 `VideoPlayerScreen` 调用（当前是写在参数列表外/未传）
- **可见性规则**:
  - `onDelete == null`（下载离线播放场景）→ 不显示按钮，行为不变
  - `onDelete != null`（在线浏览/收藏/最近打开）→ 显示按钮
- **风险**: 删除按钮叠加在 `PlayerView` 之上，可能与其自带 `PlayerControlView` 顶部按钮冲突
- **缓解**: 现有 back IconButton 已在 TopStart 长期可见且工作正常；删除按钮放同 Row 即可
- **验收**:
  - 在线视频播放器显示删除按钮（左上角 back 旁）
  - 离线下载视频不显示删除按钮
  - 点删除 → 弹确认 → 确认 → Toast + 返回浏览页 + 列表刷新

### G2-3 strings.xml 复用

- **文件**: `android/app/src/main/res/values/strings.xml`
- **复用**:
  - `video_delete_title`（已存在，内容为 "确认彻底删除？"）
  - `video_delete_desc`（已存在，内容为 "您确定要从服务端永久删除该视频文件吗？此操作不可逆！"）
  - `delete`（已存在，内容为 "删除"）
  - `cancel`（已存在，内容为 "取消"）
- **决策**: 直接复用已有全局字符串，无需在 strings.xml 中新增任何字符串，保持资源文件整洁。
- **验收**: 无编译错误，视频删除对话框文案显示正确。


---

## G3 — 首页状态合并（B3）

### 现状

- **文件**: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/HomeViewModel.kt:57-92, 151-160`
- **问题**: 在两个 `init {}` 块里启动 **6 个独立 `viewModelScope.launch`**，每个 flow 发射都触发独立 `_uiState.value = _uiState.value.copy(...)`，多次串发引发多次 Compose 重组，`copy()` 拷贝整个 `HomeUiState`

### 目标

把 6 个 flow 合并成 **1 个 `combine` flow** 单次发射更新 `_uiState`，外加 **1 个独立的 `serverUrl` 副作用 launch** 保留原 refresh 触发语义。

### 实现

**步骤 1**: 定义内部聚合数据类（不暴露给 UI）：
```kotlin
private data class HomeRawInputs(
    val favoriteFiles: List<MediaFile> = emptyList(),
    val recentMedia: List<RecentMediaEntry> = emptyList(),
    val playbackProgress: List<PlaybackProgressEntry> = emptyList(),
    val lastBrowseLocation: LastBrowseLocation? = null,
    val serverUrl: String = "",
    val favoriteEntries: List<FavoriteMediaEntry> = emptyList(),
)
```

**步骤 2**: 由于 Kotlin Coroutines 官方库的 `combine` 扩展函数最高仅支持 5 个 Flow 合并，我们需要在 `HomeViewModel.kt` 文件末尾（或私有作用域）新增一个 6 参数的 `combine` 辅助扩展函数：
```kotlin
@Suppress("UNCHECKED_CAST")
private fun <T1, T2, T3, T4, T5, T6, R> combine(
    flow1: Flow<T1>,
    flow2: Flow<T2>,
    flow3: Flow<T3>,
    flow4: Flow<T4>,
    flow5: Flow<T5>,
    flow6: Flow<T6>,
    transform: suspend (T1, T2, T3, T4, T5, T6) -> R
): Flow<R> = kotlinx.coroutines.flow.combine(
    flow1, flow2, flow3, flow4, flow5, flow6
) { args ->
    transform(
        args[0] as T1,
        args[1] as T2,
        args[2] as T3,
        args[3] as T4,
        args[4] as T5,
        args[5] as T6
    )
}
```
然后在 `init` 块中调用该自定义 `combine` 收集 6 个 flow 并单次发射更新 `_uiState`：
```kotlin
init {
    viewModelScope.launch {
        combine(
            favoritesStore.favoriteFiles,
            recentActivityStore.recentMedia,
            recentActivityStore.playbackProgress,
            recentActivityStore.lastBrowseLocation,
            serverConfigStore.serverUrl,
            favoritesStore.favoriteEntries,
        ) { favs, recent, progress, loc, url, favEntries ->
            HomeRawInputs(favs, recent, progress, loc, url, favEntries)
        }.collect { raw ->
            _uiState.value = _uiState.value.copy(
                favoriteFiles = raw.favoriteFiles.take(6),
                recentMedia = raw.recentMedia,
                continueWatching = filterContinueWatching(raw.playbackProgress),
                lastBrowseLocation = raw.lastBrowseLocation,
                serverLabel = raw.serverUrl,
            )
            favoriteAccessModes = raw.favoriteEntries.associate {
                it.file.relativePath to it.isSystemBrowse
            }
        }
    }
}
```

**步骤 3**: 把 `serverUrl` 的"非空触发 refresh"副作用分离到独立 launch，**保留原行为**（含首值触发）：
```kotlin
init {
    viewModelScope.launch {
        serverConfigStore.serverUrl.collect { url ->
            if (url.isBlank()) {
                _uiState.value = _uiState.value.copy(isLoading = false)
                return@collect
            }
            ensureClientInitialized(url)
            refresh()
        }
    }
}
```

**步骤 4**: 删除两个旧 init 块（行 57-92、151-160），保留 `refresh()` / `buildCollections()` / URL builder 函数等。

### 关键决策

1. **6 参数 combine**: 自定义 6 参数 `combine` 辅助函数以规避 Kotlin Coroutines 官方内置 `combine` 最高只支持 5 个 Flow 的限制，保持 init 调用处的类型安全与代码整洁度。
2. **combine 发发射语义**: 默认在任一上游发射时重新计算，且等待所有上游首个值到达后才首次发射。DataStore 的 `data` flow 默认立即发射当前快照（即使空），所以无冷启动延迟
3. **副作用分离**: `serverUrl` 既投影到 `serverLabel`（在 combine 里），又触发 `refresh()` 副作用（在独立 launch 里）。两个 launch 各司其职，行为完全等价于原 6-launch 版本
4. **`favoriteAccessModes` 维护方式变化**: 从"独立 collector 维护 Map"改为"在 combine collector 里 `associate` 刷新"。`isFavoriteSystemBrowse(file)` API 不变


### 风险与缓解

- **风险 1**: combine 要求所有上游都至少发射过一次才首次触发 → 若某 store 冷启动慢，UI 停在 `isLoading = true`
  - **缓解**: DataStore 的 `data` flow 默认立即发射当前快照，实际无延迟
- **风险 2**: 原 6 个 collector 各自独立错误隔离；合并后任一上游抛异常会终止整条 combine
  - **缓解**: 上游都是 DataStore `.data.map { }`，DataStore 自身有异常处理；`refresh()` 内部 try-catch 未变
- **风险 3**: 行为兼容性 — 原 `serverUrl` collector 在首值非空时即触发 refresh
  - **缓解**: 步骤 3 的独立 launch 用 `collect`（不 drop）保留首值触发，行为完全等价

### 验收

- 首页打开后 6 个区块（libraries / collections / continueWatching / recentMedia / favoriteFiles / lastBrowseLocation）数据正确显示
- 在其他页面收藏变更后回到首页，`FavoritePreviewCard` 立即更新
- 切换服务器后 `refresh()` 仍被触发
- `filterContinueWatching` 仍正确过滤已看完条目
- 无新内存泄漏 / 重组抖动

---

## 执行顺序与依赖

```
G1（11 项 + 1 TODO）
  ├─ G1-1 ~ G1-3 (Go handler 清理)
  ├─ G1-4 (VideoPlayerScreen TODO 注释)
  ├─ G1-5 (BrowseViewModel 清理)
  ├─ G1-6 ~ G1-7 (mdns 清理)
  ├─ G1-8 ~ G1-10 (streaming/system bug 修复)
  └─ G1-11 ~ G1-12 (并发与 nil panic 修复)
       ↓
G3 (HomeViewModel 合并)
       ↓
G2 (VideoPlayerScreen + MainActivity + strings)
  ├─ G2-1 缓冲指示器
  ├─ G2-2 删除入口（激活 G1-4 的 TODO）
  └─ G2-3 strings.xml 复用
```

每组完成后单独提交，commit message 用 `refactor: ...` / `fix: ...` / `feat(android): ...` 风格，符合现有 git log 习惯。

---

## 总验收清单

### G1
- [ ] `go build ./...` 通过
- [ ] `go test -race ./internal/service/...` 通过
- [ ] `go test ./...` 通过
- [ ] `grep -n "searchFolders\b"` 只返回 `searchFoldersCtx`
- [ ] `grep -n "%2F"` 在 handler/tags.go 无结果
- [ ] Android 端 `./gradlew compileDebugKotlin` 通过
- [ ] Lint 无未使用 import/函数警告

### G3
- [ ] `./gradlew testDebugUnitTest` 通过
- [ ] 首页 6 个区块数据正确
- [ ] 收藏变更后首页立即更新
- [ ] 切换服务器后 refresh 触发

### G2
- [ ] `./gradlew assembleDebug` 通过
- [ ] 播放器缓冲圈正确显示/隐藏
- [ ] 在线视频显示删除按钮，离线视频不显示
- [ ] 删除流程（确认 → Toast → popBackStack → 刷新）完整
