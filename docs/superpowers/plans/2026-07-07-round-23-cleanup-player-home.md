# Round 23 — 全面体检后优化批次：实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 G1 → G3 → G2 顺序执行 14 项优化：清理 11 处死代码/小 bug（G1）、合并 HomeViewModel 的 6 个 launch 为 1 个 combine（G3）、为视频播放器加缓冲指示器与删除入口（G2）。

**Spec:** `docs/superpowers/specs/2026-07-07-round-23-cleanup-player-home-design.md`

**Architecture:**
- G1：局部修补，无架构变化。涉及 Go 服务端 5 文件 + Android 2 文件。
- G3：HomeViewModel 内部数据流图重构，外部 API 不变。
- G2：VideoPlayerScreen 新增缓冲 overlay + 删除 IconButton；MainActivity 显式传入 onDelete lambda；strings.xml 纯复用现有 `delete` / `cancel` / `video_delete_title` / `video_delete_desc`。

**Tech Stack:** Go 1.24+ / Echo v4 / slog / Kotlin + Jetpack Compose + Hilt + Media3 ExoPlayer + Coroutines Flow。

## Global Constraints

- 项目根目录：`E:\github_project\LocalMediaHub`。所有 Bash 命令均在此目录运行。
- Go 命令：`cd server && go build ./...` / `go test -race ./...`。
- Android 命令：`cd android && ./gradlew compileDebugKotlin` / `./gradlew testDebugUnitTest` / `./gradlew assembleDebug`。
- 中国大陆网络编译 Go 时使用 `GOPROXY=https://goproxy.cn,direct`。
- 提交信息规范：`refactor:` / `fix:` / `feat(android):` / `refactor(android):` / `fix(android):` 前缀，与 recent commits 一致。
- 每个独立 Task 完成后单独提交（G1 各子项可按主题合并提交）。
- 不改动与本计划无关的代码。遇现有 dead code 与本计划冲突时，遵循 spec 决策。
- 不修改 spec 文件已最终确认的内容；若发现 spec 有遗漏，先停下来与用户确认再修改 spec。

---

## 文件结构

| 文件 | 操作 | 子组 |
|------|------|------|
| `server/internal/server/handler/search.go` | 修改 | G1-1 |
| `server/internal/server/handler/tags.go` | 修改 | G1-2, G1-3 |
| `server/internal/mdns/mdns.go` | 修改 | G1-6, G1-7 |
| `server/cmd/server/main.go` | 修改 | G1-6 |
| `server/internal/server/handler/system.go` | 修改 | G1-8 |
| `server/internal/service/streaming.go` | 修改 | G1-9, G1-10, G1-12 |
| `server/internal/service/scanner.go` | 修改 | G1-11 |
| `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt` | 修改 | G1-5 |
| `android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt` | 修改 | G1-4, G2-1, G2-2 |
| `android/app/src/main/java/com/juziss/localmediahub/MainActivity.kt` | 修改 | G2-2 |
| `android/app/src/main/java/com/juziss/localmediahub/viewmodel/HomeViewModel.kt` | 修改 | G3 |

---

## Task 1: G1-1 删除 `searchFolders` 死代码

**Files:**
- Modify: `server/internal/server/handler/search.go`

**Interfaces:** 无新接口（仅删除）

- [ ] **Step 1: 确认死代码**

```bash
grep -n "searchFolders\b" server --include="*.go" --exclude="*_test.go"
```

期望输出：只返回 `searchFoldersCtx` 的定义和调用，无对 `searchFolders`（无 Ctx 后缀）的调用。

- [ ] **Step 2: 删除函数**

在 `server/internal/server/handler/search.go` 删除以下行（约 111-113）：

```go
func (h *Handler) searchFolders(scopedPath, query string, limit int) ([]models.Folder, error) {
	return h.searchFoldersCtx(context.Background(), scopedPath, query, limit)
}
```

同时删除其上方的 `// searchFolders walks ...` 注释段（若有独立注释）。

- [ ] **Step 3: 检查 context import 是否还需要**

```bash
grep -n '"context"' server/internal/server/handler/search.go
```

若 `searchFoldersCtx` 仍在用 `context.Context`，import 保留。若文件内除被删函数外再无 context 用法，删除 import。

- [ ] **Step 4: 编译验证**

```bash
cd server && go build ./...
```

- [ ] **Step 5: 测试验证**

```bash
cd server && go test ./internal/server/handler/...
```

- [ ] **Step 6: 提交**

```
refactor(server): drop dead searchFolders wrapper
```

---

## Task 2: G1-2 + G1-3 清理 handler/tags.go

**Files:**
- Modify: `server/internal/server/handler/tags.go`

- [ ] **Step 1: G1-2 删除 `var _ models.FileTag`**

删除 `server/internal/server/handler/tags.go:133`：

```go
// ensure models import is used
var _ models.FileTag
```

确认 `models` 仍被文件内其他代码使用（`GetTaggedMedia` 中的 `[]models.MediaFile{}`）。

- [ ] **Step 2: G1-3 删除 `%2F` 双重解码**

在 `AssociateTag`（约行 48-66）和 `DisassociateTag`（约行 68-77）中，删除：

```go
pathStr = strings.ReplaceAll(pathStr, "%2F", "/")
```

保留 `pathStr := c.Param("*")`。

- [ ] **Step 3: 检查 strings import 是否还需要**

```bash
grep -n '"strings"' server/internal/server/handler/tags.go
grep -n 'strings\.' server/internal/server/handler/tags.go
```

若 `strings.` 已无使用，删除 import。

- [ ] **Step 4: 验证 path_suffix_test.go 仍通过**

```bash
cd server && go test ./internal/server/handler/...
```

- [ ] **Step 5: 提交**

```
refactor(server): drop dead var _ and remove %2F double-decode in tags handler
```

---

## Task 3: G1-5 清理 BrowseViewModel 死代码

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt`

- [ ] **Step 1: 删除 4 个未使用 import**

删除以下 import：
- `android.os.Environment`
- `java.io.File`
- `java.io.FileOutputStream`
- `java.util.zip.ZipFile`

- [ ] **Step 2: 删除 `associateFavoriteModes` 函数**

删除文件末尾（约 357-359）：

```kotlin
private fun List<FavoriteMediaEntry>.associateFavoriteModes(): Map<String, Boolean> {
    return associate { entry -> entry.file.relativePath to entry.isSystemBrowse }
}
```

- [ ] **Step 3: 验证 FavoriteMediaEntry import 是否还需要**

```bash
grep -n "FavoriteMediaEntry" android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt
```

若除被删函数外已无使用，删除 import。

- [ ] **Step 4: 编译验证**

```bash
cd android && ./gradlew compileDebugKotlin
```

- [ ] **Step 5: 提交**

```
refactor(android): remove unused imports and associateFavoriteModes from BrowseViewModel
```

---

## Task 4: G1-6 + G1-7 修复 mDNS 模块

**Files:**
- Modify: `server/internal/mdns/mdns.go`
- Modify: `server/cmd/server/main.go`

- [ ] **Step 1: G1-6 修改 NewService 签名**

在 `server/internal/mdns/mdns.go:15-17`，将：

```go
func NewService(host string, port int) (*Service, error) {
	return &Service{quit: make(chan struct{})}, nil
}
```

改为：

```go
func NewService() (*Service, error) {
	return &Service{quit: make(chan struct{})}, nil
}
```

- [ ] **Step 2: G1-6 更新 main.go 调用方**

在 `server/cmd/server/main.go:27`，将：

```go
mdnsSvc, err := localmdns.NewService(cfg.Server.Host, cfg.Server.Port)
```

改为：

```go
mdnsSvc, err := localmdns.NewService()
```

- [ ] **Step 3: G1-7 替换 fmt.Printf 为 slog**

在 `server/internal/mdns/mdns.go:49`，将：

```go
fmt.Printf("mDNS: advertising _localmediahub._tcp.local. on %s:%d\n", ip, port)
```

改为：

```go
slog.Info("mDNS advertising", "service", "_localmediahub._tcp.local.", "ip", ip, "port", port)
```

- [ ] **Step 4: 调整 import**

在 mdns.go 顶部：
- 添加 `"log/slog"` import
- 删除 `"fmt"` import（若文件内已无其他 `fmt.` 使用，需 grep 确认）

```bash
grep -n 'fmt\.' server/internal/mdns/mdns.go
```

- [ ] **Step 5: 编译 + 测试**

```bash
cd server && go build ./...
cd server && go test ./internal/mdns/... 2>/dev/null || echo "no mdns tests, OK"
```

- [ ] **Step 6: 提交**

```
refactor(server): simplify mdns.NewService signature and switch to slog
```

---

## Task 5: G1-8 修复 DeletePath 错误响应泄露

**Files:**
- Modify: `server/internal/server/handler/system.go` (DeletePath 函数)

- [ ] **Step 1: 替换 4 处错误响应**

将 `DeletePath` 函数中：

```go
if !h.cfg.System.EnableDelete {
    return c.JSON(http.StatusForbidden, map[string]string{"error": "remote deletion is disabled on the server"})
}

var req DeleteRequest
if err := c.Bind(&req); err != nil {
    return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid request body"})
}

if req.Path == "" {
    return c.JSON(http.StatusBadRequest, map[string]string{"error": "path required"})
}

allRoots := append(append([]string{}, h.cfg.Scan.GetRoots()...), h.cfg.GetSystemAllowedRoots()...)
resolved, err := service.ValidateDeletion(req.Path, allRoots)
if err != nil {
    return c.JSON(http.StatusForbidden, map[string]string{"error": err.Error()})
}
```

替换为：

```go
if !h.cfg.System.EnableDelete {
    return respondError(c, http.StatusForbidden, "remote deletion is disabled")
}

var req DeleteRequest
if err := c.Bind(&req); err != nil {
    return respondError(c, http.StatusBadRequest, "invalid request body", err)
}

if req.Path == "" {
    return respondError(c, http.StatusBadRequest, "path required")
}

allRoots := append(append([]string{}, h.cfg.Scan.GetRoots()...), h.cfg.GetSystemAllowedRoots()...)
resolved, err := service.ValidateDeletion(req.Path, allRoots)
if err != nil {
    return respondError(c, http.StatusForbidden, "access denied")
}
```

注意：`ValidateDeletion` 失败统一回 `"access denied"`，不泄露具体原因（如"cannot delete a root directory"）。

- [ ] **Step 2: 编译 + 测试**

```bash
cd server && go build ./...
cd server && go test ./internal/server/handler/...
```

- [ ] **Step 3: 提交**

```
fix(server): stop leaking internal error details from DeletePath
```

---

## Task 6: G1-9 修复 transcoded stream `start` 参数无校验

**Files:**
- Modify: `server/internal/service/streaming.go` (serveTranscoded 函数)

- [ ] **Step 1: 替换 startSec 解析逻辑**

在 `serveTranscoded` 函数（约 221-225），将：

```go
startSec := r.URL.Query().Get("start")
if startSec == "" {
    startSec = "0"
}
```

替换为：

```go
startSecStr := r.URL.Query().Get("start")
var startSec float64 = 0
if startSecStr != "" {
    parsed, parseErr := strconv.ParseFloat(startSecStr, 64)
    if parseErr != nil || math.IsNaN(parsed) || parsed < 0 || parsed > 86400 {
        return fmt.Errorf("invalid start parameter")
    }
    startSec = parsed
}
```

- [ ] **Step 2: 更新 args 构造**

将原本直接用 `startSec`（string）作为 ffmpeg arg 的部分改为格式化后的 float：

```go
args := []string{}
if startSec != 0 {
    args = append(args, "-ss", strconv.FormatFloat(startSec, 'f', 3, 64))
}
args = append(args, "-i", filePath)
```

注意：原来 `if startSec != "0"`，现在改为 `if startSec != 0`（float 比较）。

- [ ] **Step 3: 添加 math import**

在 streaming.go 顶部添加 `"math"` import（若已存在则跳过）。

- [ ] **Step 4: 编译 + 测试**

```bash
cd server && go build ./...
cd server && go test ./internal/service/...
```

- [ ] **Step 5: 提交**

```
fix(server): validate transcoded stream start parameter (NaN/negative/oversized)
```

---

## Task 7: G1-10 修复 Range 解析静默吞错

**Files:**
- Modify: `server/internal/service/streaming.go` (ServeFile 函数 Range 解析段)

- [ ] **Step 1: 修改 start 解析**

在 `ServeFile` 函数（约 135-140），将：

```go
} else {
    start, err = strconv.ParseInt(parts[0], 10, 64)
    if err != nil {
        start = 0
    }
```

改为：

```go
} else {
    start, err = strconv.ParseInt(parts[0], 10, 64)
    if err != nil {
        w.Header().Del("Accept-Ranges")
        w.WriteHeader(http.StatusBadRequest)
        return nil
    }
```

- [ ] **Step 2: 修改 end 解析**

紧随其后（约 140-145），将：

```go
    if parts[1] != "" {
        end, err = strconv.ParseInt(parts[1], 10, 64)
        if err != nil {
            end = size - 1
        }
    } else {
        end = size - 1
    }
```

改为：

```go
    if parts[1] != "" {
        end, err = strconv.ParseInt(parts[1], 10, 64)
        if err != nil {
            w.Header().Del("Accept-Ranges")
            w.WriteHeader(http.StatusBadRequest)
            return nil
        }
    } else {
        end = size - 1
    }
```

- [ ] **Step 3: 编译 + 测试**

```bash
cd server && go build ./...
cd server && go test ./internal/service/...
```

- [ ] **Step 4: 提交**

```
fix(server): reject malformed Range headers with 400 instead of silent fallback
```

---

## Task 8: G1-11 修复 Scanner 数据竞争

**Files:**
- Modify: `server/internal/service/scanner.go` (TriggerScan + Shutdown)

- [ ] **Step 1: 修改 TriggerScan**

将：

```go
func (s *Scanner) TriggerScan(roots []string) {
	s.bgCancel()
	s.bgCtx, s.bgCancel = context.WithCancel(context.Background())
	go s.Scan(s.bgCtx, roots)
}
```

改为：

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
```

- [ ] **Step 2: 修改 Shutdown**

将：

```go
func (s *Scanner) Shutdown() {
	s.bgCancel()
}
```

改为：

```go
func (s *Scanner) Shutdown() {
	s.mu.Lock()
	if s.bgCancel != nil {
		s.bgCancel()
	}
	s.mu.Unlock()
}
```

注意：复用已有 `s.mu`（`sync.RWMutex`）。`Scan` 内部对 cache 的写操作也持 `s.mu.Lock()`，但临界区不重叠（TriggerScan/Shutdown 只保护 bgCtx/bgCancel 字段），不会死锁。

- [ ] **Step 3: race 检测**

```bash
cd server && go test -race ./internal/service/...
```

- [ ] **Step 4: 提交**

```
fix(server): guard Scanner bgCtx/bgCancel with mutex to fix data race
```

---

## Task 9: G1-12 修复 serveTranscoded nil panic

**Files:**
- Modify: `server/internal/service/streaming.go` (serveTranscoded)

- [ ] **Step 1: 添加 sync import**

在 streaming.go 顶部确认 `"sync"` import 存在；若没有则添加。

- [ ] **Step 2: 引入 killOnce 闭包**

在 `serveTranscoded` 函数中，`cmd := exec.Command(...)` 之后、`cmd.Start()` 之前，添加：

```go
var killOnce sync.Once
killCmd := func() {
	killOnce.Do(func() {
		if cmd.Process != nil {
			cmd.Process.Kill()
		}
	})
}
```

- [ ] **Step 3: 替换 goroutine 内的 Kill**

将：

```go
go func() {
	<-ctx.Done()
	if cmd.Process != nil {
		cmd.Process.Kill()
	}
}()
```

改为：

```go
go func() {
	<-ctx.Done()
	killCmd()
}()
```

- [ ] **Step 4: 替换主循环的 Kill**

将：

```go
if _, wErr := w.Write(buf[:n]); wErr != nil {
	cmd.Process.Kill()
	return nil
}
```

改为：

```go
if _, wErr := w.Write(buf[:n]); wErr != nil {
	killCmd()
	return nil
}
```

- [ ] **Step 5: 编译 + 测试**

```bash
cd server && go build ./...
cd server && go test ./internal/service/...
```

- [ ] **Step 6: 提交**

```
fix(server): guard ffmpeg kill with sync.Once to prevent nil panic and double-kill
```

---

## Task 10: G1-4 标记 VideoPlayerScreen.onDelete TODO

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt:102`

- [ ] **Step 1: 加 TODO 注释**

在 `onDelete: (() -> Unit)? = null,` 上方添加：

```kotlin
// G2 will wire this to a top-bar delete IconButton
onDelete: (() -> Unit)? = null,
```

- [ ] **Step 2: 编译验证**

```bash
cd android && ./gradlew compileDebugKotlin
```

- [ ] **Step 3: 提交（与 Task 11-15 一起作为 G1 收尾提交，或单独提交）**

```
chore(android): mark VideoPlayerScreen.onDelete as pending G2 wiring
```

---

## Task 11: G3 HomeViewModel 6 flow 合并

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/HomeViewModel.kt`

**Interfaces:** 对外 API 完全不变（`uiState`、`refresh()`、URL builders、`isFavoriteSystemBrowse` 等）。

- [ ] **Step 1: 添加必要的 import**

在 HomeViewModel.kt 顶部 import 段，确认/添加：

```kotlin
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import com.juziss.localmediahub.data.FavoriteMediaEntry
import kotlinx.coroutines.flow.Flow
```

- [ ] **Step 2: 添加 HomeRawInputs 数据类**

在 `HomeViewModel` 类定义之外（文件顶层 private），添加：

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

- [ ] **Step 3: 添加 6 参数 combine 辅助扩展函数**

在文件末尾（或 private 作用域）添加：

```kotlin
@Suppress("UNCHECKED_CAST")
private fun <T1, T2, T3, T4, T5, T6, R> combine(
    flow1: Flow<T1>,
    flow2: Flow<T2>,
    flow3: Flow<T3>,
    flow4: Flow<T4>,
    flow5: Flow<T5>,
    flow6: Flow<T6>,
    transform: suspend (T1, T2, T3, T4, T5, T6) -> R,
): Flow<R> = kotlinx.coroutines.flow.combine(
    flow1, flow2, flow3, flow4, flow5, flow6
) { args ->
    transform(
        args[0] as T1,
        args[1] as T2,
        args[2] as T3,
        args[3] as T4,
        args[4] as T5,
        args[5] as T6,
    )
}
```

- [ ] **Step 4: 替换第一个 init 块**

将原 init 块（行 57-92，含 5 个 launch）替换为：

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
            if (raw.serverUrl.isBlank()) {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }
}
```

注意：把原"serverUrl 非空触发 refresh"逻辑作为副作用分离到 Step 5 的独立 launch。

- [ ] **Step 5: 添加 serverUrl 副作用 launch**

紧接 Step 4 的 init 块之后，添加：

```kotlin
init {
    viewModelScope.launch {
        serverConfigStore.serverUrl.collect { url ->
            if (url.isBlank()) {
                return@collect
            }
            ensureClientInitialized(url)
            refresh()
        }
    }
}
```

注意：用 `collect`（不 drop）保留首值触发 refresh 的原行为。

- [ ] **Step 6: 删除第二个 init 块**

删除原第二个 init 块（行 151-160，维护 favoriteAccessModes 的独立 collector）：

```kotlin
private val favoriteAccessModes = mutableMapOf<String, Boolean>()

init {
    viewModelScope.launch {
        favoritesStore.favoriteEntries.collect { entries ->
            favoriteAccessModes.clear()
            entries.forEach { entry ->
                favoriteAccessModes[entry.file.relativePath] = entry.isSystemBrowse
            }
        }
    }
}
```

改为：

```kotlin
private val favoriteAccessModes = mutableMapOf<String, Boolean>()
```

（保留字段声明，删除 init 块；维护逻辑已迁入 Step 4 的 combine collector）

- [ ] **Step 7: 编译验证**

```bash
cd android && ./gradlew compileDebugKotlin
```

- [ ] **Step 8: 单元测试验证**

```bash
cd android && ./gradlew testDebugUnitTest
```

- [ ] **Step 9: 提交**

```
refactor(android): merge 6 HomeViewModel launches into single combine flow
```

---

## Task 12: G2-1 视频播放器缓冲指示器

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt`

- [ ] **Step 1: 添加 CircularProgressIndicator import**

确认/添加：

```kotlin
import androidx.compose.material3.CircularProgressIndicator
```

- [ ] **Step 2: 添加 isBuffering 状态**

在 VideoPlayerScreen composable 函数顶部现有 gesture state 附近，添加：

```kotlin
var isBuffering by remember { mutableStateOf(false) }
```

- [ ] **Step 3: 修改 onPlaybackStateChanged**

在现有 `DisposableEffect(exoPlayer)` 的 `Player.Listener` 中（约 203-208），将：

```kotlin
override fun onPlaybackStateChanged(playbackState: Int) {
    if (playbackState == Player.STATE_ENDED) {
        wrappedOnProgress(exoPlayer.duration, exoPlayer.duration)
    }
}
```

改为：

```kotlin
override fun onPlaybackStateChanged(playbackState: Int) {
    isBuffering = playbackState == Player.STATE_BUFFERING
    if (playbackState == Player.STATE_ENDED) {
        wrappedOnProgress(exoPlayer.duration, exoPlayer.duration)
    }
}
```

- [ ] **Step 4: 添加缓冲 overlay**

在 Box 内（与 gesture indicators 同层），添加：

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

- [ ] **Step 5: 编译验证**

```bash
cd android && ./gradlew compileDebugKotlin
```

- [ ] **Step 6: 提交（与 Task 13 合并提交为 G2）**

---

## Task 13: G2-2 视频播放器删除入口

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/MainActivity.kt`

- [ ] **Step 1: VideoPlayerScreen — 删除 G1-4 TODO 注释**

删除 Task 10 添加的 `// G2 will wire this ...` 注释。

- [ ] **Step 2: VideoPlayerScreen — 添加 import**

确认/添加：

```kotlin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Delete
```

- [ ] **Step 3: VideoPlayerScreen — 添加 showDeleteConfirm 状态**

在 gesture state 附近添加：

```kotlin
var showDeleteConfirm by remember { mutableStateOf(false) }
```

- [ ] **Step 4: VideoPlayerScreen — 改造 back IconButton 为 Row**

将现有 back IconButton 块（约 492-503）：

```kotlin
IconButton(
    onClick = onBack,
    modifier = Modifier
        .align(Alignment.TopStart)
        .padding(top = 8.dp, start = 4.dp),
) {
    Icon(
        Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = "Back",
        tint = Color.White,
    )
}
```

替换为：

```kotlin
Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
        .align(Alignment.TopStart)
        .padding(top = 8.dp, start = 4.dp)
) {
    IconButton(onClick = onBack) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.common_back),
            tint = Color.White,
        )
    }
    if (onDelete != null) {
        IconButton(onClick = { showDeleteConfirm = true }) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.delete),
                tint = Color.White,
            )
        }
    }
}
```

注意：`common_back` 字符串若不存在则用现有最接近的 back 文案；可先 grep strings.xml 确认。若都没有，contentDescription 用 `"Back"`（与原代码一致，不破坏现有汉化基线）。

- [ ] **Step 5: VideoPlayerScreen — 添加 AlertDialog**

在 Box 内最后（紧接 Row 之后或独立段）添加：

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

- [ ] **Step 6: MainActivity — 传入 onDelete**

在 `MainActivity.kt` 的 `composable("videoPlayer")` 块（约 331-374），找到 `VideoPlayerScreen(...)` 调用。

现有调用未传 `onDelete`，但已存在完整的删除 lambda（在 `onDelete = { ... }` 注释段）。把这段 lambda 显式传给 `VideoPlayerScreen`：

```kotlin
VideoPlayerScreen(
    streamUrl = currentVideoUrl,
    initialPositionMs = currentVideoStartPositionMs,
    onProgress = { ... },  // 现有
    onBack = { navController.popBackStack() },
    onDelete = {
        val file = currentVideoFile
        if (file != null) {
            appScope.launch {
                when (val result = browseViewModel.deletePathSync(file.path, false)) {
                    is com.juziss.localmediahub.network.NetworkResult.Success -> {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            android.widget.Toast.makeText(context, context.getString(R.string.main_delete_success), android.widget.Toast.LENGTH_SHORT).show()
                            navController.popBackStack()
                            browseViewModel.refreshCurrentDirectory()
                        }
                    }
                    is com.juziss.localmediahub.network.NetworkResult.Error -> {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            android.widget.Toast.makeText(context, context.getString(R.string.main_delete_failed, result.message), android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                    else -> {}
                }
            }
        }
    },
)
```

注意：现有 MainActivity.kt 行 351-373 已是这段 lambda 逻辑，只需要把它从"参数列表外"挪到 `onDelete = { ... }` 参数位。

- [ ] **Step 7: 编译验证**

```bash
cd android && ./gradlew compileDebugKotlin
```

- [ ] **Step 8: assembleDebug 验证**

```bash
cd android && ./gradlew assembleDebug
```

- [ ] **Step 9: 提交（合并 Task 12 + 13）**

```
feat(android): add buffering indicator and delete button to video player
```

---

## Task 14: 最终验证

- [ ] **Step 1: Go 全量测试**

```bash
cd server && go test -race ./...
```

- [ ] **Step 2: Android 全量构建**

```bash
cd android && ./gradlew testDebugUnitTest assembleDebug
```

- [ ] **Step 3: 死代码 grep 验证**

```bash
grep -rn "searchFolders\b" server --include="*.go" --exclude="*_test.go"
# 期望：只返回 searchFoldersCtx

grep -n "%2F" server/internal/server/handler/tags.go
# 期望：无输出

grep -n "fmt.Printf" server/internal/mdns/mdns.go
# 期望：无输出
```

- [ ] **Step 4: 更新 plan.md 进度总览**

在 `plan.md` 末尾追加 round 23 简报（参照现有 P0/P1/P2 章节风格）。

- [ ] **Step 5: 最终提交**

```
docs(plan): round 23 cleanup + player UX + home state merge
```

---

## 风险与回滚

- 每个独立 Task 单独提交，便于 `git revert` 单个改动
- G1-3（`%2F` 双重解码）与 G1-10（Range 静默吞错）有行为变化，回滚后恢复"宽松接受"行为
- G3 重构若发现首页卡顿或状态丢失，回滚 commit 即可恢复 6-launch 版本
- G2 改动若与 PlayerControlView 冲突，回滚 Task 12+13 合并 commit 即可

## 不在本计划范围（明确剔除）

- B1（搜索 O(n)）、B2（cap 预估）、B4（sync.Pool）、B5（扫描并发调优）
- C2/C3（admin 鉴权）
- D3/D4/D6/E2/E3/E5
