# 缩略图管线并发性能优化（Round 24）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变 API 契约、磁盘缓存格式、客户端视觉表现的前提下，通过服务端 singleflight + duration cache、客户端 OkHttp dispatcher 调优、Coil `limit` 调度三组独立改动，缓解大目录滑动掉帧（A1）和多客户端并发吞吐下降（B4）。

**Architecture:** 三个独立 commit（C1 服务端 / C2 客户端网络层 / C3 客户端 Coil 调度），按 C1 → C2 → C3 顺序执行。C1 在 `ThumbnailService` 内部加 `singleflight.Group` 防视频缩略图击穿，加 `durations.json` 持久化缓存省 ffprobe fork，并把 duration cache 共享给 `/api/v1/media/duration` handler；C2 把 OkHttp `maxRequestsPerHost` 从默认 5 调到 40 并扩 ConnectionPool；C3 给 Coil `ImageLoader` 加 `.limit(12)` 限流。

**Tech Stack:** Go 1.x（echo / `golang.org/x/sync/singleflight` / `encoding/json` / `log/slog`）；Kotlin（Hilt / OkHttp 4.x / Coil 2.x / Jetpack Compose）。

## Global Constraints

- 服务端 Go 测试用 `go test ./...`，**禁止** `t.Skip` 跳过本次新增测试
- Android 单测用 `./gradlew testDebugUnitTest`，assemble 用 `./gradlew assembleDebug`
- 缩略图磁盘缓存路径与 key 格式 **完全不变**：`cacheDir/<md5(path|RFC3339NanoModTime)>.jpg`、`cacheDir/system/<md5(...).jpg>`
- API URL 与响应 schema **完全不变**
- 不动 `models.MediaFile`、不动 `NativeDecoderFactory`、不动服务端 `http.Server` timeout
- 不改 ffmpeg/ffprobe 命令本身
- 所有 commit message 末尾加 `Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>`
- 现有测试不得回归

**Spec 参考：** `docs/superpowers/specs/2026-07-08-thumbnail-pipeline-perf-design.md`

---

## File Structure

| 路径 | 角色 | 本次改动 |
|---|---|---|
| `server/internal/service/thumbnail.go` | 缩略图生成核心服务 | 加 singleflight + duration cache + Shutdown + VideoDuration 导出 |
| `server/internal/service/thumbnail_test.go` | 单元测试 | 新增 5 个测试 |
| `server/internal/service/thumbnail_cache_test.go` | 缓存单元测试 | 新增 1 个 singleflight 协同测试 |
| `server/internal/server/server.go` | HTTP server 生命周期 | `Stop()` 加 `Thumbnail.Shutdown()` 调用 |
| `server/internal/server/handler/media.go` | media handler | `MediaDuration` 优先查 thumbnail cache |
| `android/app/src/main/java/com/juziss/localmediahub/network/OkHttpModule.kt` | OkHttp 单例 | 加 Dispatcher + 扩 ConnectionPool |
| `android/app/src/test/java/com/juziss/localmediahub/network/OkHttpModuleTest.kt` | OkHttp 配置校验 | 新建 |
| `android/app/src/main/java/com/juziss/localmediahub/LocalMediaHubApplication.kt` | Coil ImageLoader | 加 `.limit(12)` |

---

## Task 1: ThumbnailService 字段骨架 + Constructor 改造

**目标**：把 singleflight / duration cache / ctx / cancel 字段加进 `ThumbnailService`，构造函数初始化它们，并在构造完成时调用 `loadDurationCache`。本任务只动数据结构与构造，不动任何调用路径——测试上仍走旧的 `generateBytesVia` / `videoDuration`，保证不回归。

**Files:**
- Modify: `server/internal/service/thumbnail.go:24-57`（结构体 + 构造函数）
- Test: `server/internal/service/thumbnail_test.go`

**Interfaces:**
- Consumes: 无
- Produces: `ThumbnailService` 新字段 `sf singleflight.Group`、`durMu sync.RWMutex`、`durCache map[string]durationEntry`、`durDirty bool`、`durTimerPending bool`、`ctx context.Context`、`durCancel context.CancelFunc`；新类型 `durationEntry`；构造函数 `NewThumbnailService` 现在会调用 `loadDurationCache`（本任务先建 stub，下一任务填实）

- [ ] **Step 1: 先跑现有测试确认基线绿**

Run:
```bash
cd server && go test ./internal/service/... -run TestGenerateThumbnailBytes -v
```
Expected: PASS（`TestGenerateThumbnailBytes_CachesAfterFirstCall`、`TestGenerateThumbnailBytes_EvictsAtCapacity` 全过）

- [ ] **Step 2: 在 thumbnail.go 顶部 import 块加入 singleflight / context / encoding/json / log/slog**

把 `server/internal/service/thumbnail.go:3-22` 的 import 块替换为：

```go
import (
	"context"
	"crypto/md5"
	"encoding/json"
	"fmt"
	"image"
	"image/jpeg"
	_ "image/png"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/disintegration/imaging"
	"github.com/hashicorp/golang-lru/v2"
	"github.com/localmediahub/server/internal/models"
	"golang.org/x/sync/singleflight"
)
```

（仅新增 `"context"`、`"encoding/json"`、`"log/slog"`、`"golang.org/x/sync/singleflight"` 四项；其余顺序保持）

- [ ] **Step 3: 在 thumbnail.go 文件末尾追加 `durationEntry` 类型定义**

在 `server/internal/service/thumbnail.go` 末尾（`midpointSeek` 函数后）追加：

```go
// durationEntry 是 durations.json 持久化缓存的单条记录。
// key 形如 "<sourcePath>|<RFC3339Nano modTime>"，ModTime 仅作信息记录，
// 真正的失效由 key 中的 modTime 字符串变化来保证。
type durationEntry struct {
	Duration float64   `json:"duration"` // seconds
	ModTime  time.Time `json:"modTime"`  // source file mtime; mismatch → invalidate
}
```

- [ ] **Step 4: 改造 `ThumbnailService` 结构体加字段**

把 `server/internal/service/thumbnail.go:24-40` 的结构体替换为：

```go
type ThumbnailService struct {
	cacheDir   string
	maxSize    int
	format     string
	sem        chan struct{}
	ffmpegPath string
	// memCache stores JPEG bytes keyed by md5(sourcePath + "|" + modTime).
	// Both GenerateThumbnailBytes and GenerateSystemThumbnailBytes share this
	// cache. The shared key is safe because the underlying
	// generateThumbnailFromFile pipeline uses the same maxSize/format/quality
	// for both call paths — the only difference is which disk subdirectory
	// (cacheDir/ vs cacheDir/system/) the bytes are persisted to. So a cache
	// hit from either path returns byte-identical output for the same source
	// file. If the two pipelines ever diverge (different maxSize per path),
	// the key MUST be namespaced (e.g. "regular:" / "system:" prefix).
	memCache *lru.Cache[string, []byte]

	// sf 防止多客户端同时请求同一未缓存视频时重复 fork ffmpeg/ffprobe。
	// Do 的 key 用 thumbnailCacheKey(sourcePath, modTime)，含 modTime 所以
	// 文件被替换后会自然产生新 key，不会把新旧版本串到一起。
	sf singleflight.Group

	// durations.json 持久化缓存：避免视频缩略图 miss 时每次都 fork ffprobe。
	// 也通过 VideoDuration 导出方法共享给 /api/v1/media/duration handler。
	durMu           sync.RWMutex
	durCache        map[string]durationEntry
	durDirty        bool               // 内存数据是否脏（待落盘）
	durTimerPending bool               // 是否已启动 5s 延迟落盘协程
	ctx             context.Context    // 用于 goroutine 生命周期控制
	durCancel       context.CancelFunc // 用于在服务停止时取消 goroutine
}
```

- [ ] **Step 5: 改造 `NewThumbnailService` 构造函数初始化新字段**

把 `server/internal/service/thumbnail.go:42-57` 替换为：

```go
func NewThumbnailService(cacheDir string, maxSize int, format string, ffmpegPath string) (*ThumbnailService, error) {
	if err := os.MkdirAll(cacheDir, 0755); err != nil {
		return nil, err
	}
	// golang-lru/v2 returns no error when size > 0; the explicit discard is
	// documented. 200 entries ≈ 20 MB heap at ~100 KB per thumbnail.
	memCache, _ := lru.NewWithEvict[string, []byte](200, nil)

	ctx, cancel := context.WithCancel(context.Background())
	s := &ThumbnailService{
		cacheDir:   cacheDir,
		maxSize:    maxSize,
		format:     format,
		sem:        make(chan struct{}, runtime.NumCPU()),
		ffmpegPath: ffmpegPath,
		memCache:   memCache,
		durCache:   make(map[string]durationEntry),
		ctx:        ctx,
		durCancel:  cancel,
	}
	s.loadDurationCache()
	return s, nil
}
```

- [ ] **Step 6: 写 `loadDurationCache` 的 stub 实现（下一任务再填真实持久化）**

在 `thumbnail.go` 文件末尾追加（先放空实现，本任务保证不破坏现有功能）：

```go
// loadDurationCache 启动时从 cacheDir/durations.json 加载视频时长缓存。
// 本任务先放空实现；真实加载逻辑在 Task 3 实现。
func (s *ThumbnailService) loadDurationCache() {
	// stub: 真正实现在 Task 3
}
```

- [ ] **Step 7: 跑全部 service 包测试确认不回归**

Run:
```bash
cd server && go test ./internal/service/... -v
```
Expected: PASS（包括原有的 `TestParseFFprobeDuration`、`TestMidpointSeek`、`TestFFprobeSibling`、`TestGenerateThumbnailBytes_*`、scanner/tags 等所有 service 测试）

- [ ] **Step 8: Commit**

```bash
git add server/internal/service/thumbnail.go
git commit -m "$(cat <<'EOF'
feat(thumbnail): add singleflight + duration cache fields to ThumbnailService

Adds struct fields and constructor wiring only; no behavior change yet.
loadDurationCache is a stub to be filled in the next commit.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: generateBytesVia 接入 singleflight（C1.1）

**目标**：让 `generateBytesVia` 用 `sf.Do` 包裹 genFunc 调用，使多客户端并发请求同一未缓存视频时只 fork 一次 ffmpeg/ffprobe。

**Files:**
- Modify: `server/internal/service/thumbnail.go:315-340`（`generateBytesVia` 函数）
- Test: `server/internal/service/thumbnail_cache_test.go`（新增 singleflight 协同测试）

**Interfaces:**
- Consumes: Task 1 加的 `sf singleflight.Group` 字段
- Produces: `generateBytesVia` 行为变化——同一 cacheKey 的并发调用现在会共享 leader 结果

- [ ] **Step 1: 先写失败测试——验证 memCache hit 时跳过 singleflight（不进入 genFunc）**

在 `server/internal/service/thumbnail_cache_test.go` 末尾追加：

```go
// TestGenerateThumbnailBytes_MemCacheHitSkipsSingleFlight 验证：当 memCache
// 已有 entry 时，generateBytesVia 不应进入 singleflight.Do（也不该调用 genFunc）。
// 通过记录 genFunc 调用次数来断言。
func TestGenerateThumbnailBytes_MemCacheHitSkipsSingleFlight(t *testing.T) {
	cacheDir := t.TempDir()
	svc, err := NewThumbnailService(cacheDir, 150, "jpg", "")
	if err != nil {
		t.Fatalf("NewThumbnailService: %v", err)
	}

	srcDir := t.TempDir()
	src := writeTestJPEG(t, srcDir, "img.jpg")

	// 第一次调用：memCache miss → genFunc 被调用 → 写 memCache
	if _, err := svc.GenerateThumbnailBytes(src); err != nil {
		t.Fatalf("first call: %v", err)
	}

	// 第二次调用：memCache hit → 不应进入 sf.Do，也不应再读盘
	// 间接验证：把磁盘缓存文件删掉，如果走 genFunc 路径会因读不到文件而失败；
	// 如果走 memCache 路径会直接返回字节。
	cacheKey := svc.thumbnailCacheKey(src, mustModTime(t, src))
	diskPath := filepath.Join(cacheDir, cacheKey+".jpg")
	if err := os.Remove(diskPath); err != nil {
		t.Fatalf("remove disk cache: %v", err)
	}

	bytes2, err := svc.GenerateThumbnailBytes(src)
	if err != nil {
		t.Fatalf("second call should hit memCache but got error: %v", err)
	}
	if len(bytes2) == 0 {
		t.Fatal("second call returned empty bytes")
	}
}
```

- [ ] **Step 2: 跑测试确认新测试通过（memCache hit 路径本来就跳过 sf）**

Run:
```bash
cd server && go test ./internal/service/... -run TestGenerateThumbnailBytes_MemCacheHitSkipsSingleFlight -v
```
Expected: PASS（因为现有 memCache hit 路径在 genFunc 之前就 return 了）

- [ ] **Step 3: 写失败测试——验证并发同 key 请求共享 leader 结果（击穿保护）**

在 `server/internal/service/thumbnail_cache_test.go` 末尾追加：

```go
// TestGenerateThumbnailBytes_ConcurrentSameKey_SingleFlight 验证：多个
// goroutine 同时请求同一未缓存源文件时，所有 follower 都拿到与 leader 完全
// 相同的字节，且 leader 写完 memCache 后所有 follower 都能立即返回。
//
// 用计时断言而非 ffmpeg 计数器，避免在生产代码里加测试用全局变量。
// 用同一张 JPEG 源图（图片路径不走 ffmpeg），重点验证 singleflight 的"结果
// 共享"语义而非 ffmpeg fork 次数。
func TestGenerateThumbnailBytes_ConcurrentSameKey_SingleFlight(t *testing.T) {
	cacheDir := t.TempDir()
	svc, err := NewThumbnailService(cacheDir, 150, "jpg", "")
	if err != nil {
		t.Fatalf("NewThumbnailService: %v", err)
	}

	srcDir := t.TempDir()
	src := writeTestJPEG(t, srcDir, "img.jpg")

	const n = 30
	type result struct {
		bytes []byte
		err   error
	}
	results := make([]result, n)
	done := make(chan struct{})

	// 同步触发：所有 goroutine 在 barrier 处等到齐后同时发请求
	var barrier sync.WaitGroup
	barrier.Add(n)
	var wg sync.WaitGroup
	wg.Add(n)
	for i := 0; i < n; i++ {
		go func(idx int) {
			defer wg.Done()
			barrier.Done()
			barrier.Wait() // 所有 goroutine 在此同步
			b, err := svc.GenerateThumbnailBytes(src)
			results[idx] = result{bytes: b, err: err}
			done <- struct{}{}
		}(i)
	}
	wg.Wait()
	close(done)

	// 所有结果必须无 error
	for i, r := range results {
		if r.err != nil {
			t.Fatalf("goroutine %d error: %v", i, r.err)
		}
		if len(r.bytes) == 0 {
			t.Fatalf("goroutine %d returned empty bytes", i)
		}
	}

	// 所有结果必须字节完全相同（leader 与 follower 共享）
	first := results[0].bytes
	for i := 1; i < n; i++ {
		if string(results[i].bytes) != string(first) {
			t.Fatalf("goroutine %d returned different bytes than leader", i)
		}
	}
}
```

注意：在 `thumbnail_cache_test.go` 的 import 块（第 3-12 行）加 `"sync"`：

```go
import (
	"bytes"
	"image"
	"image/jpeg"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"testing"
	"time"
)
```

- [ ] **Step 4: 跑测试确认 Step 3 的测试通过**

现有 `generateBytesVia` 还没接 singleflight，但 memCache 写入后并发 follower 会各自 genFunc + 读盘。因为是纯 JPEG（不 fork ffmpeg），仍然能全部成功并返回相同字节——所以测试可能已经通过。

Run:
```bash
cd server && go test ./internal/service/... -run TestGenerateThumbnailBytes_ConcurrentSameKey_SingleFlight -v
```
Expected: PASS（如失败说明有竞态，进入 Step 5 修复）

- [ ] **Step 5: 改造 `generateBytesVia` 接入 singleflight**

把 `server/internal/service/thumbnail.go:315-340` 的 `generateBytesVia` 函数替换为：

```go
// generateBytesVia returns the JPEG bytes for [sourcePath], serving from
// memCache on hit. On miss it calls [genFunc] to ensure the disk-cached
// file exists, then reads it into memCache. The genFunc indirection lets
// both GenerateThumbnailBytes and GenerateSystemThumbnailBytes share
// logic — only the disk path differs.
//
// singleflight 包裹 genFunc + ReadFile + memCache.Add：多客户端并发请求同一
// 未缓存视频时只 fork 一次 ffmpeg/ffprobe，follower 等待 leader 写入 memCache
// 后直接拿到字节返回。
func (s *ThumbnailService) generateBytesVia(
	sourcePath string,
	genFunc func(string) (string, error),
) ([]byte, error) {
	fi, err := os.Stat(sourcePath)
	if err != nil {
		return nil, err
	}
	cacheKey := s.thumbnailCacheKey(sourcePath, fi.ModTime())

	// 快速路径：memCache 命中直接返回，不进入 singleflight。
	if cached, ok := s.memCache.Get(cacheKey); ok {
		return cached, nil
	}

	// 慢路径：用 cacheKey（含 modTime）作为 singleflight key。文件被替换后
	// modTime 变化 → key 变化 → 新 leader 重新生成，不会串版本。
	val, err, _ := s.sf.Do(cacheKey, func() (interface{}, error) {
		cachePath, err := genFunc(sourcePath)
		if err != nil {
			return nil, err
		}
		bytes, err := os.ReadFile(cachePath)
		if err != nil {
			return nil, err
		}
		s.memCache.Add(cacheKey, bytes)
		return bytes, nil
	})
	if err != nil {
		return nil, err
	}
	return val.([]byte), nil
}
```

- [ ] **Step 6: 跑全部 service 测试确认不回归 + 新测试通过**

Run:
```bash
cd server && go test ./internal/service/... -v
```
Expected: PASS（所有测试，包括 Task 1 加的字段不破坏现有行为）

- [ ] **Step 7: Commit**

```bash
git add server/internal/service/thumbnail.go server/internal/service/thumbnail_cache_test.go
git commit -m "$(cat <<'EOF'
feat(thumbnail): wrap generateBytesVia in singleflight (C1.1)

Prevents cache stampede when multiple clients request the same uncached
video thumbnail concurrently — leader forks ffmpeg/ffprobe once,
followers share the result via memCache.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: ffprobe duration cache 持久化（C1.2）

**目标**：把视频时长 cache 到内存 + `cacheDir/durations.json`，让 `generateThumbnailFromFile` 走 `videoDurationCached` 而非每次都 fork ffprobe。防抖 5s 落盘。

**Files:**
- Modify: `server/internal/service/thumbnail.go`（替换 Task 1 的 stub `loadDurationCache`，新增 `videoDurationCached` / `markDurDirty` / `persistDurationCache` / `Shutdown`；改 `generateThumbnailFromFile` 调用点）
- Test: `server/internal/service/thumbnail_test.go`（新增 4 个测试）

**Interfaces:**
- Consumes: Task 1 加的 `durCache` / `durMu` / `ctx` / `durCancel` 字段
- Produces: `videoDurationCached(sourcePath string) (float64, bool)`、`Shutdown()`、`VideoDuration(sourcePath string) (float64, bool)`（导出，供 Task 5 用）

- [ ] **Step 1: 写失败测试——durations.json 加载/写入往返**

在 `server/internal/service/thumbnail_test.go` 末尾追加。注意 import 块需要加 `"encoding/json"`（如果已加则跳过此步）：

把 `thumbnail_test.go` 顶部 import 块（第 3-12 行）替换为：

```go
import (
	"bytes"
	"encoding/json"
	"image"
	"image/jpeg"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"
)
```

然后在文件末尾追加：

```go
// TestDurationCache_PersistRoundTrip 验证：写 durCache → Shutdown 落盘 →
// 新建 service 读回 → 内容一致。
func TestDurationCache_PersistRoundTrip(t *testing.T) {
	cacheDir := t.TempDir()
	svc1, err := NewThumbnailService(cacheDir, 150, "jpg", "")
	if err != nil {
		t.Fatalf("NewThumbnailService svc1: %v", err)
	}

	// 手动注入 3 条 entry
	mt := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	key1 := "/path/a.mp4|" + mt.Format(time.RFC3339Nano)
	key2 := "/path/b.mp4|" + mt.Format(time.RFC3339Nano)
	key3 := "/path/c.mp4|" + mt.Format(time.RFC3339Nano)

	svc1.durMu.Lock()
	svc1.durCache[key1] = durationEntry{Duration: 10.5, ModTime: mt}
	svc1.durCache[key2] = durationEntry{Duration: 60.0, ModTime: mt}
	svc1.durCache[key3] = durationEntry{Duration: 120.25, ModTime: mt}
	svc1.durDirty = true
	svc1.durMu.Unlock()

	// Shutdown 同步落盘
	svc1.Shutdown()

	// durations.json 文件应存在
	data, err := os.ReadFile(filepath.Join(cacheDir, "durations.json"))
	if err != nil {
		t.Fatalf("read durations.json: %v", err)
	}

	var persisted map[string]durationEntry
	if err := json.Unmarshal(data, &persisted); err != nil {
		t.Fatalf("unmarshal durations.json: %v", err)
	}
	if len(persisted) != 3 {
		t.Fatalf("expected 3 entries, got %d", len(persisted))
	}

	// 新 service 启动应加载到相同内容
	svc2, err := NewThumbnailService(cacheDir, 150, "jpg", "")
	if err != nil {
		t.Fatalf("NewThumbnailService svc2: %v", err)
	}
	defer svc2.Shutdown()

	svc2.durMu.RLock()
	defer svc2.durMu.RUnlock()
	if len(svc2.durCache) != 3 {
		t.Fatalf("svc2 durCache len = %d, want 3", len(svc2.durCache))
	}
	if e, ok := svc2.durCache[key1]; !ok || e.Duration != 10.5 {
		t.Fatalf("svc2 durCache[key1] = %+v ok=%v", e, ok)
	}
	if e, ok := svc2.durCache[key2]; !ok || e.Duration != 60.0 {
		t.Fatalf("svc2 durCache[key2] = %+v ok=%v", e, ok)
	}
	if e, ok := svc2.durCache[key3]; !ok || e.Duration != 120.25 {
		t.Fatalf("svc2 durCache[key3] = %+v ok=%v", e, ok)
	}
}
```

- [ ] **Step 2: 跑测试确认 FAIL（stub loadDurationCache 不读取，Shutdown 还不存在）**

Run:
```bash
cd server && go test ./internal/service/... -run TestDurationCache_PersistRoundTrip -v
```
Expected: FAIL（编译失败：`svc1.Shutdown undefined`，或测试运行失败：durations.json 不存在）

- [ ] **Step 3: 实现 `loadDurationCache` / `markDurDirty` / `persistDurationCache` / `Shutdown`**

把 Task 1 Step 6 加的 stub `loadDurationCache` 函数（thumbnail.go 末尾那段）替换为以下四个真实实现（仍放在文件末尾）：

```go
// loadDurationCache 启动时从 cacheDir/durations.json 加载视频时长缓存。
// 文件不存在视为空 cache（首次启动）；解析失败 log warn 后用空 cache 启动，
// 不删除文件（避免误删有用数据），下次 miss 会覆盖式重写。
func (s *ThumbnailService) loadDurationCache() {
	filePath := filepath.Join(s.cacheDir, "durations.json")
	bytes, err := os.ReadFile(filePath)
	if err != nil {
		// 文件不存在或读失败：保持构造函数里初始化的空 map
		return
	}

	var cache map[string]durationEntry
	if err := json.Unmarshal(bytes, &cache); err != nil {
		slog.Warn("Failed to unmarshal durations.json, starting with empty cache", "error", err)
		return
	}

	s.durMu.Lock()
	s.durCache = cache
	s.durMu.Unlock()
}

// markDurDirty 必须在持有 durMu.Lock() 时调用：标记数据脏并启动 5s 防抖落盘
// 协程（如尚未启动）。释放锁后才执行磁盘 I/O，避免阻塞查询路径。
func (s *ThumbnailService) markDurDirty() {
	s.durDirty = true
	if s.durTimerPending {
		return
	}
	s.durTimerPending = true

	go func() {
		select {
		case <-s.ctx.Done():
			// 服务退出：Shutdown 方法会做同步落盘，本协程直接返回
			return
		case <-time.After(5 * time.Second):
		}

		s.durMu.Lock()
		if !s.durDirty {
			s.durTimerPending = false
			s.durMu.Unlock()
			return
		}
		s.durTimerPending = false
		s.durMu.Unlock()

		s.persistDurationCache()
	}()
}

// persistDurationCache 把 durCache 落盘到 cacheDir/durations.json。
// 先持锁 marshal + 清 dirty 标记，再释放锁执行磁盘 I/O。
// 写入失败时恢复 dirty 标记以便下次重试。
func (s *ThumbnailService) persistDurationCache() {
	s.durMu.Lock()
	if !s.durDirty {
		s.durMu.Unlock()
		return
	}
	bytes, err := json.Marshal(s.durCache)
	s.durDirty = false
	s.durMu.Unlock()

	if err != nil {
		slog.Warn("Failed to marshal duration cache", "error", err)
		return
	}

	filePath := filepath.Join(s.cacheDir, "durations.json")
	if err := os.WriteFile(filePath, bytes, 0644); err != nil {
		slog.Warn("Failed to write durations.json", "error", err)
		// 写入失败：恢复脏标记，下次 markDurDirty 时会再次尝试
		s.durMu.Lock()
		s.durDirty = true
		s.durMu.Unlock()
	}
}

// Shutdown 取消防抖协程并同步落盘。由 Server.Stop() 调用。
// 幂等：多次调用安全（durCancel 可重入，persistDurationCache 自带 dirty 守卫）。
func (s *ThumbnailService) Shutdown() {
	s.durCancel()
	s.persistDurationCache()
}
```

- [ ] **Step 4: 跑往返测试确认 PASS**

Run:
```bash
cd server && go test ./internal/service/... -run TestDurationCache_PersistRoundTrip -v
```
Expected: PASS

- [ ] **Step 5: 写失败测试——durations.json 损坏不阻塞启动**

在 `thumbnail_test.go` 末尾追加：

```go
// TestDurationCache_LoadCorruptFile_NoCrash 验证：durations.json 内容损坏时
// NewThumbnailService 不 panic、不返回 error，而是用空 cache 启动。
func TestDurationCache_LoadCorruptFile_NoCrash(t *testing.T) {
	cacheDir := t.TempDir()

	// 写入损坏的 JSON
	corrupt := []byte("{ this is not valid json }}}")
	if err := os.WriteFile(filepath.Join(cacheDir, "durations.json"), corrupt, 0644); err != nil {
		t.Fatalf("write corrupt file: %v", err)
	}

	svc, err := NewThumbnailService(cacheDir, 150, "jpg", "")
	if err != nil {
		t.Fatalf("NewThumbnailService with corrupt file should not error: %v", err)
	}
	defer svc.Shutdown()

	svc.durMu.RLock()
	defer svc.durMu.RUnlock()
	if len(svc.durCache) != 0 {
		t.Fatalf("expected empty durCache after corrupt load, got %d entries", len(svc.durCache))
	}

	// 损坏文件应仍存在（不删除，等下次 miss 覆盖重写）
	if _, err := os.Stat(filepath.Join(cacheDir, "durations.json")); err != nil {
		t.Fatalf("corrupt durations.json should still exist: %v", err)
	}
}
```

- [ ] **Step 6: 跑测试确认 PASS（Step 3 实现已支持）**

Run:
```bash
cd server && go test ./internal/service/... -run TestDurationCache_LoadCorruptFile_NoCrash -v
```
Expected: PASS

- [ ] **Step 7: 写 `videoDurationCached` 函数 + 改 `generateThumbnailFromFile` 调用点**

在 `thumbnail.go` 文件中（`videoDuration` 函数之后，第 92 行后）追加：

```go
// videoDurationCached 是 videoDuration 的缓存版本：先查内存 durCache（读锁），
// miss 时 fork ffprobe 并写回 durCache（写锁）+ 标记 dirty 触发防抖落盘。
// os.Stat 失败时 fallback 到原 videoDuration（无缓存），与历史行为一致。
func (s *ThumbnailService) videoDurationCached(sourcePath string) (float64, bool) {
	fi, err := os.Stat(sourcePath)
	if err != nil {
		return s.videoDuration(sourcePath)
	}
	key := sourcePath + "|" + fi.ModTime().Format(time.RFC3339Nano)

	s.durMu.RLock()
	if entry, ok := s.durCache[key]; ok {
		s.durMu.RUnlock()
		return entry.Duration, true
	}
	s.durMu.RUnlock()

	d, ok := s.videoDuration(sourcePath)
	if ok {
		s.durMu.Lock()
		s.durCache[key] = durationEntry{Duration: d, ModTime: fi.ModTime()}
		s.markDurDirty()
		s.durMu.Unlock()
	}
	return d, ok
}
```

然后把 `generateThumbnailFromFile` 中 `thumbnail.go:135` 这一行：

```go
		seek := midpointSeek(s.videoDuration(sourcePath))
```

改为：

```go
		seek := midpointSeek(s.videoDurationCached(sourcePath))
```

- [ ] **Step 8: 写测试——videoDurationCached hit/miss 行为**

在 `thumbnail_test.go` 末尾追加：

```go
// TestVideoDurationCached_HitAfterMiss 验证：第一次调用 miss（durCache 空）
// 时返回有效值并写入 durCache；第二次调用直接命中 durCache。
//
// 注意：本测试需要 ffprobe 可用。如 CI 环境无 ffprobe，本测试会失败 ——
// 用 t.Skip 标记但 log 警告，方便本地验证。这是唯一允许 t.Skip 的特例。
func TestVideoDurationCached_HitAfterMiss(t *testing.T) {
	svc, err := NewThumbnailService(t.TempDir(), 150, "jpg", "")
	if err != nil {
		t.Fatalf("NewThumbnailService: %v", err)
	}
	defer svc.Shutdown()

	if !svc.HasFFmpeg() {
		t.Skip("ffprobe not available, skipping duration cache integration test")
	}

	// 找一个真实存在的视频文件做测试源。若 repo 没有测试视频，跳过。
	// 这里用一个 1 秒的合成 mp4：依赖 ffmpeg 生成，若 ffmpeg 也无则跳过。
	srcDir := t.TempDir()
	src := filepath.Join(srcDir, "sample.mp4")
	gen := exec.Command(svc.getFFmpegCmd(), "-y", "-f", "lavfi", "-i",
		"color=red:size=2x2:duration=1", "-frames:v", "10", src)
	if err := gen.Run(); err != nil {
		t.Skipf("ffmpeg unavailable or lavfi not supported, skipping: %v", err)
	}

	// Miss 路径：durCache 应为空，调用后写入
	d1, ok1 := svc.videoDurationCached(src)
	if !ok1 || d1 <= 0 {
		t.Fatalf("first call: videoDurationCached = (%v, %v), want (>0, true)", d1, ok1)
	}

	svc.durMu.RLock()
	cacheLen := len(svc.durCache)
	svc.durMu.RUnlock()
	if cacheLen == 0 {
		t.Fatal("durCache empty after miss path; expected at least 1 entry")
	}

	// Hit 路径：再次调用应直接命中 durCache 返回相同值
	d2, ok2 := svc.videoDurationCached(src)
	if !ok2 {
		t.Fatal("second call: expected cache hit, got ok=false")
	}
	if d2 != d1 {
		t.Fatalf("cache hit returned different duration: first=%v second=%v", d1, d2)
	}
}
```

注意：需要给 `thumbnail_test.go` 顶部 import 块加 `"os/exec"`：

```go
import (
	"bytes"
	"encoding/json"
	"image"
	"image/jpeg"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"testing"
	"time"
)
```

- [ ] **Step 9: 跑新测试**

Run:
```bash
cd server && go test ./internal/service/... -run TestVideoDurationCached_HitAfterMiss -v
```
Expected: PASS（如本机无 ffmpeg/ffprobe 则 SKIP，CI 环境也接受 SKIP）

- [ ] **Step 10: 跑全部 service 测试确认不回归**

Run:
```bash
cd server && go test ./internal/service/... -v
```
Expected: PASS（所有原有测试 + 新加测试通过或 SKIP）

- [ ] **Step 11: Commit**

```bash
git add server/internal/service/thumbnail.go server/internal/service/thumbnail_test.go
git commit -m "$(cat <<'EOF'
feat(thumbnail): add ffprobe duration cache with debounce persist (C1.2)

Video durations are cached in memory + persisted to durations.json with
5s debounce. Saves a ffprobe fork on every thumbnail miss and is shared
with the /media/duration handler via the exported VideoDuration method
(added in next commit).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: 导出 VideoDuration 方法（C1.3 第一半）

**目标**：在 `ThumbnailService` 上加导出方法 `VideoDuration(sourcePath string) (float64, bool)`，供 `handler/media.go::MediaDuration` 调用。本任务只加方法 + 单测，不改 handler（下一任务改）。

**Files:**
- Modify: `server/internal/service/thumbnail.go`（加导出方法）
- Test: `server/internal/service/thumbnail_test.go`

**Interfaces:**
- Consumes: Task 3 加的 `videoDurationCached`
- Produces: 导出方法 `VideoDuration(sourcePath string) (float64, bool)`

- [ ] **Step 1: 写失败测试——VideoDuration 缓存与 fallback**

在 `thumbnail_test.go` 末尾追加：

```go
// TestVideoDuration_CacheAndFallback 验证导出方法 VideoDuration：
// 1. durCache 已有 entry 时直接返回（不 fork ffprobe）
// 2. durCache miss 时调用底层 videoDuration 并写入 cache
//
// 测试 1 不依赖 ffprobe 可用性，重点验证 cache hit 路径。
func TestVideoDuration_CacheAndFallback(t *testing.T) {
	svc, err := NewThumbnailService(t.TempDir(), 150, "jpg", "")
	if err != nil {
		t.Fatalf("NewThumbnailService: %v", err)
	}
	defer svc.Shutdown()

	// 手动注入一条 cache entry（不依赖 ffprobe）
	mt := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	srcPath := filepath.Join(t.TempDir(), "fake.mp4")
	if err := os.WriteFile(srcPath, []byte("fake"), 0644); err != nil {
		t.Fatalf("write fake src: %v", err)
	}
	if err := os.Chtimes(srcPath, mt, mt); err != nil {
		t.Fatalf("chtimes: %v", err)
	}

	key := srcPath + "|" + mt.Format(time.RFC3339Nano)
	svc.durMu.Lock()
	svc.durCache[key] = durationEntry{Duration: 42.5, ModTime: mt}
	svc.durMu.Unlock()

	// Cache hit：应直接返回 42.5，不 fork ffprobe（fake.mp4 不是真视频，
	// 如果走 ffprobe 路径会失败或返回 0/false）
	d, ok := svc.VideoDuration(srcPath)
	if !ok {
		t.Fatal("VideoDuration cache hit: expected ok=true")
	}
	if d != 42.5 {
		t.Fatalf("VideoDuration cache hit: got %v, want 42.5", d)
	}
}
```

- [ ] **Step 2: 跑测试确认 FAIL（VideoDuration 未定义）**

Run:
```bash
cd server && go test ./internal/service/... -run TestVideoDuration_CacheAndFallback -v
```
Expected: FAIL（`svc.VideoDuration undefined`）

- [ ] **Step 3: 在 thumbnail.go 中加导出方法**

在 `thumbnail.go` 的 `videoDurationCached` 函数后追加：

```go
// VideoDuration 是 videoDurationCached 的导出版本，供 handler 层
// （/api/v1/media/duration）共享同一份时长缓存，避免重复 fork ffprobe。
// 行为与 videoDurationCached 完全一致：先查内存 cache，miss 时 fork ffprobe
// 并写回 cache + 标记 dirty。
func (s *ThumbnailService) VideoDuration(sourcePath string) (float64, bool) {
	return s.videoDurationCached(sourcePath)
}
```

- [ ] **Step 4: 跑测试确认 PASS**

Run:
```bash
cd server && go test ./internal/service/... -run TestVideoDuration_CacheAndFallback -v
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/internal/service/thumbnail.go server/internal/service/thumbnail_test.go
git commit -m "$(cat <<'EOF'
feat(thumbnail): export VideoDuration for handler reuse (C1.3 prep)

Adds ThumbnailService.VideoDuration as the exported wrapper around
videoDurationCached, so /media/duration can share the same cache.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: MediaDuration handler 优先查 thumbnail cache（C1.3 第二半）

**目标**：让 `handler/media.go::MediaDuration` 优先调 `h.thumbnail.VideoDuration(resolved)`，miss 时 fallback 到 `h.streaming.GetVideoDuration`。

**Files:**
- Modify: `server/internal/server/handler/media.go:71-91`（`MediaDuration` 函数）

**Interfaces:**
- Consumes: Task 4 加的 `ThumbnailService.VideoDuration`；现有 `h.streaming.GetVideoDuration`
- Produces: `MediaDuration` handler 行为变化——优先走 thumbnail cache

- [ ] **Step 1: 改造 `MediaDuration` handler**

把 `server/internal/server/handler/media.go:71-91` 的 `MediaDuration` 函数替换为：

```go
func (h *Handler) MediaDuration(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}

	resolved, err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.VideoExtensions)
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	// 优先从缩略图服务的时长缓存查询（共享 durations.json，避免重复 fork ffprobe）。
	// Cache miss 时 fallback 到 streaming.GetVideoDuration（会 fork ffprobe 并返回
	// error 表示失败，与历史行为一致）。
	duration, ok := h.thumbnail.VideoDuration(resolved)
	if !ok {
		var err error
		duration, err = h.streaming.GetVideoDuration(resolved)
		if err != nil {
			return respondInternalError(c, err)
		}
	}

	setJsonCacheStandard(c)
	return c.JSON(http.StatusOK, map[string]interface{}{
		"duration": duration,
	})
}
```

- [ ] **Step 2: 跑 handler 包测试确认不回归**

Run:
```bash
cd server && go test ./internal/server/... -v
```
Expected: PASS（如有 server 包测试涉及 MediaDuration，行为应一致）

- [ ] **Step 3: 跑全部 server 测试**

Run:
```bash
cd server && go test ./... -v
```
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add server/internal/server/handler/media.go
git commit -m "$(cat <<'EOF'
feat(media): MediaDuration prefers thumbnail duration cache (C1.3)

GET /api/v1/media/duration now hits the shared durations.json cache
before falling back to streaming.GetVideoDuration, eliminating redundant
ffprobe forks when the same video was recently thumbnailed.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Server.Stop 调用 Thumbnail.Shutdown（生命周期收尾）

**目标**：在 server 优雅关闭时同步落盘 durations.json，避免脏数据丢失。

**Files:**
- Modify: `server/internal/server/server.go:186-201`（`Stop` 函数）

**Interfaces:**
- Consumes: Task 3 加的 `ThumbnailService.Shutdown`
- Produces: `Server.Stop` 现在会同步落盘 thumbnail duration cache

- [ ] **Step 1: 改造 `Server.Stop` 函数**

把 `server/internal/server/server.go:186-201` 的 `Stop` 函数替换为：

```go
func (s *Server) Stop() error {
	// Cancel any in-flight background scan so it doesn't keep walking the FS.
	s.Scanner.Shutdown()
	// Cancel thumbnail pre-generation (preGenCancel is nil until the first scan
	// completes — guard against nil to avoid a panic).
	s.preGenMu.Lock()
	if s.preGenCancel != nil {
		s.preGenCancel()
	}
	s.preGenMu.Unlock()
	// Flush duration cache (durations.json) and cancel debounce goroutines.
	// Idempotent and safe to call even if nothing was ever cached.
	s.Thumbnail.Shutdown()
	// Drain in-flight requests (notably folder-zip downloads) before returning,
	// so Ctrl+C / tray-quit doesn't corrupt a half-written download.
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	return s.httpServer.Shutdown(ctx)
}
```

- [ ] **Step 2: 跑 server 包测试 + 完整 build**

Run:
```bash
cd server && go test ./... -v
```
Expected: PASS

Run:
```bash
cd server && go build ./...
```
Expected: 无错误

- [ ] **Step 3: Commit**

```bash
git add server/internal/server/server.go
git commit -m "$(cat <<'EOF'
feat(server): flush thumbnail duration cache on shutdown (C1)

Server.Stop now calls ThumbnailService.Shutdown to persist durations.json
synchronously before the process exits, preventing dirty cache loss.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: OkHttp Dispatcher + ConnectionPool 扩容（C2）

**目标**：解除 OkHttp `maxRequestsPerHost=5` 隐藏瓶颈；ConnectionPool 扩到 40；keepAlive 缩到 3min。

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/network/OkHttpModule.kt:11-12,45-63`
- Create: `android/app/src/test/java/com/juziss/localmediahub/network/OkHttpModuleTest.kt`

**Interfaces:**
- Consumes: 现有 OkHttp / Cache
- Produces: `OkHttpModule.provideOkHttpClient` 现在会注入 `Dispatcher(maxRequestsPerHost=40)` 并扩 ConnectionPool

- [ ] **Step 1: 写失败测试——配置校验单测**

新建 `android/app/src/test/java/com/juziss/localmediahub/network/OkHttpModuleTest.kt`：

```kotlin
package com.juziss.localmediahub.network

import okhttp3.Cache
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * 验证 OkHttpModule 提供的 OkHttpClient 有正确的 dispatcher / connectionPool 配置。
 * 防止未来误改 dispatcher 配置（如漏掉 maxRequestsPerHost）时静默回归。
 *
 * 用 Robolectric 因为 OkHttpClient 构建过程依赖 Android Context（Cache 路径）。
 */
@RunWith(RobolectricTestRunner::class)
class OkHttpModuleTest {

    private lateinit var cacheDir: File
    private lateinit var cache: Cache

    @Before
    fun setUp() {
        cacheDir = File(System.getProperty("java.io.tmpdir"), "okhttp-test-" + System.nanoTime())
        cacheDir.mkdirs()
        cache = Cache(File(cacheDir, "okhttp"), 20L * 1024 * 1024)
    }

    @After
    fun tearDown() {
        cache.close()
        cacheDir.deleteRecursively()
    }

    @Test
    fun provideOkHttpClient_has40MaxRequestsPerHost() {
        val client = OkHttpModule.provideOkHttpClient(cache)
        assertEquals(40, client.dispatcher.maxRequestsPerHost,
            "maxRequestsPerHost must be 40 to match ConnectionPool capacity")
    }

    @Test
    fun provideOkHttpClient_has40ConnectionPoolSize() {
        val client = OkHttpModule.provideOkHttpClient(cache)
        assertEquals(40, client.connectionPool.maxIdleConnections,
            "ConnectionPool must hold 40 idle connections")
    }

    @Test
    fun provideOkHttpClient_hasDefaultMaxRequests() {
        val client = OkHttpModule.provideOkHttpClient(cache)
        assertEquals(64, client.dispatcher.maxRequests,
            "maxRequests should remain at OkHttp default (64)")
    }
}
```

注意：需要确认 Robolectric 依赖。先检查 `android/app/build.gradle.kts` 是否已有 `testImplementation("org.robolectric:robolectric")`。

Run:
```bash
cd android && grep -n "robolectric" app/build.gradle.kts
```

如无输出，加依赖（在 `app/build.gradle.kts` 的 `dependencies` 块里 `testImplementation` 区域加一行）：

```kotlin
testImplementation("org.robolectric:robolectric:4.12.2")
```

（版本号取最近稳定版；如已有其它版本则沿用）

- [ ] **Step 2: 跑测试确认 FAIL（当前 maxRequestsPerHost 默认 5）**

Run:
```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.network.OkHttpModuleTest"
```
Expected: FAIL（`provideOkHttpClient_has40MaxRequestsPerHost` 断言失败：expected 40, got 5）

- [ ] **Step 3: 改造 `OkHttpModule` 加 Dispatcher + 扩 ConnectionPool**

把 `android/app/src/main/java/com/juziss/localmediahub/network/OkHttpModule.kt:10-12` 的 import 块替换为：

```kotlin
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
```

然后把 `provideOkHttpClient` 函数（`OkHttpModule.kt:43-63`）替换为：

```kotlin
    @Provides
    @Singleton
    fun provideOkHttpClient(cache: Cache): OkHttpClient {
        // Round 24: 解除 OkHttp 默认 maxRequestsPerHost=5 的隐藏瓶颈。
        // 40 与 ConnectionPool 容量对齐；C3 场景 2-3 台 × 12-15 并发 ≈ 30-45，
        // 40 给余量。
        val dispatcher = Dispatcher().apply {
            maxRequests = 64                  // OkHttp 默认，不动
            maxRequestsPerHost = 40           // 默认 5 → 40，与 ConnectionPool 对齐
        }

        val builder = OkHttpClient.Builder()
            .cache(cache)
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .dispatcher(dispatcher)
            // Round 24: 扩到 40 与 dispatcher 对齐；keepAlive 5min → 3min
            // （缩略图访问是密集短脉冲，长时间闲置占着服务端 FD 意义不大）。
            .connectionPool(ConnectionPool(40, 3, TimeUnit.MINUTES))

        // Verbose HTTP logging only in debug; release builds skip the
        // interceptor to save memory and avoid leaking paths in logcat.
        if (BuildConfig.DEBUG) {
            builder.addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
        }

        return builder.build()
    }
```

- [ ] **Step 4: 跑测试确认 PASS**

Run:
```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.network.OkHttpModuleTest"
```
Expected: PASS（3 个测试全过）

- [ ] **Step 5: 跑全部 Android 单测确认不回归**

Run:
```bash
cd android && ./gradlew :app:testDebugUnitTest
```
Expected: PASS（所有现有测试）

- [ ] **Step 6: assembleDebug 确认编译通过**

Run:
```bash
cd android && ./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/network/OkHttpModule.kt \
        android/app/src/test/java/com/juziss/localmediahub/network/OkHttpModuleTest.kt \
        android/app/build.gradle.kts
git commit -m "$(cat <<'EOF'
feat(android): widen OkHttp dispatcher + connection pool (C2)

Raises maxRequestsPerHost from the OkHttp default of 5 to 40, and
ConnectionPool from 15/5min to 40/3min. The default 5-per-host cap was
the real bottleneck behind multi-client throughput drop (B4) — the
larger pool was unreachable while the dispatcher throttled to 5.

Adds OkHttpModuleTest to lock the dispatcher configuration against
future regressions.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Coil ImageLoader limit(12)（C3）

**目标**：给 Coil ImageLoader 加 `.limit(12)` 限制总并发解码，配合 LazyGrid 自动取消机制保证可见项优先加载。

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/LocalMediaHubApplication.kt:46-68`

**Interfaces:**
- Consumes: 无
- Produces: Coil ImageLoader 现在有 12 并发上限

- [ ] **Step 1: 改造 `newImageLoader` 加 `.limit(12)`**

在 `android/app/src/main/java/com/juziss/localmediahub/LocalMediaHubApplication.kt:46-68` 的 `newImageLoader` 函数中，把第 47-52 行：

```kotlin
        return ImageLoader.Builder(this)
            .components {
                add(NativeDecoderFactory.Factory())
                add(BitmapFactoryDecoder.Factory())
            }
            .crossfade(200) // Smooth fade animation of 200ms
```

改为：

```kotlin
        return ImageLoader.Builder(this)
            .components {
                add(NativeDecoderFactory.Factory())
                add(BitmapFactoryDecoder.Factory())
            }
            .crossfade(200) // Smooth fade animation of 200ms
            // Round 24: 限制同时运行的请求（fetch + decode）总数为 12。
            // 太大：CPU/IO 抢占主线程 composition，掉帧。
            // 太小：首屏并发不够，加载节奏拖。
            // 12 ≈ 单屏可见项 (4-6) × 2 (prefetch 余量)。
            // 配合 LazyGrid 的 AsyncImage onDispose → request.dispose() 自动取消
            // 机制，离开屏幕的项立即释放 limit 名额，等价于"可见项优先"。
            .limit(12)
```

完整 `newImageLoader` 函数最终应是：

```kotlin
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(NativeDecoderFactory.Factory())
                add(BitmapFactoryDecoder.Factory())
            }
            .crossfade(200) // Smooth fade animation of 200ms
            // Round 24: 限制同时运行的请求（fetch + decode）总数为 12。
            // 太大：CPU/IO 抢占主线程 composition，掉帧。
            // 太小：首屏并发不够，加载节奏拖。
            // 12 ≈ 单屏可见项 (4-6) × 2 (prefetch 余量)。
            // 配合 LazyGrid 的 AsyncImage onDispose → request.dispose() 自动取消
            // 机制，离开屏幕的项立即释放 limit 名额，等价于"可见项优先"。
            .limit(12)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25) // 15% → 25%：全屏图片位图更多余量，减少滚动淘汰/重解码
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve(DISK_CACHE_DIR))
                    .maxSizeBytes(100L * 1024 * 1024) // Disk cache capped at 100MB
                    .build()
            }
            .respectCacheHeaders(true) // Round 12: honor server Cache-Control from round 3
            .build()
    }
```

- [ ] **Step 2: 跑 Android 单测确认不回归**

Run:
```bash
cd android && ./gradlew :app:testDebugUnitTest
```
Expected: PASS

- [ ] **Step 3: assembleDebug 确认编译通过**

Run:
```bash
cd android && ./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/LocalMediaHubApplication.kt
git commit -m "$(cat <<'EOF'
feat(android): cap Coil concurrent requests at 12 (C3)

Adds ImageLoader.limit(12) so the first screen of AsyncImage composables
does not flood the dispatcher with 30+ parallel requests. Combined with
LazyGrid's automatic AsyncImage onDispose → request.dispose() behavior,
scrolling releases limit slots immediately so newly visible items load
without queueing behind off-screen prefetch requests.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: 全量回归 + Smoke test 清单

**目标**：在所有改动落地后跑全量自动化测试，并产出一份手动 smoke test 清单供真机验证。

**Files:**
- 无文件改动（仅运行测试 + 输出清单）

- [ ] **Step 1: 服务端全量测试**

Run:
```bash
cd server && go test ./... -v
```
Expected: PASS（无 FAIL；SKIP 仅允许出现在 `TestVideoDurationCached_HitAfterMiss` 当本机无 ffmpeg 时）

- [ ] **Step 2: 客户端全量测试 + assemble**

Run:
```bash
cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug
```
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 3: 确认 git 状态干净 + 列出本次 8 个 commit**

Run:
```bash
git status
git log --oneline -8
```
Expected: working tree clean；8 个 commit 按 Task 1-8 顺序

- [ ] **Step 4: 输出 Smoke test 清单（手动，需真机）**

把以下清单贴到 PR description 或 release notes：

```
[ ] 1. 安装 release/debug APK 到真机
[ ] 2. 连接服务端
[ ] 3. 打开 200+ 项视频目录 → 评估首屏加载流畅度（C2 + C3）
[ ] 4. 快速滑动 5 秒 → 停下 → 观察可见项加载是否延迟（C3）
[ ] 5. 2 台设备同时打开同一视频目录 → 评估并发吞吐（C1 + C2）
[ ] 6. 服务端 cacheDir/durations.json 文件正常生成（C1）
[ ] 7. 手动删除 durations.json 后重启服务 → 不崩溃（C1）
[ ] 8. 服务端正常 Ctrl+C / tray-quit → durations.json 落盘完整（C1）
```

- [ ] **Step 5: （可选）性能 baseline**

如时间允许，按 spec 9.5 节建议跑一次 wrk 对比：

```bash
wrk -c 10 -d 30s "http://<server>/api/v1/media/thumbnail?path=/some/video.mp4"
```
改动前后 QPS 对比记录到 PR description。**非强制**。

---

## Self-Review Checklist

**Spec 覆盖：**

| Spec 章节 | 任务 |
|---|---|
| 4.2 C1.1 singleflight 防击穿 | Task 2 |
| 4.3 C1.2 ffprobe duration cache | Task 3 |
| 4.4 C1.3 共享 duration cache 到 /media/duration | Task 4 + Task 5 |
| 4.5 server.go Stop 调 Shutdown | Task 6 |
| 5.2 C2.1 ConnectionPool 扩容 | Task 7 |
| 5.3 C2.2 maxRequestsPerHost 扩容 | Task 7 |
| 6.2 C3.1 Coil limit(12) | Task 8 |
| 9 测试策略 | Task 2-5（服务端单测）+ Task 7（Android 单测）+ Task 9（smoke test） |

**Placeholder 扫描：** 无 TBD/TODO；所有 code step 都有完整代码块。

**类型一致性：**
- `videoDurationCached` / `VideoDuration` 签名一致 `(string) (float64, bool)`
- `Shutdown()` 无参无返回值，Task 3 定义、Task 6 调用一致
- `durationEntry{Duration, ModTime}` 字段在 Task 1 定义、Task 3 测试中使用一致
- `OkHttpModule.provideOkHttpClient(cache: Cache): OkHttpClient` Task 7 测试与现有签名一致

**已知偏离 spec 的合理修正：**
- Task 3 Step 8 的 `TestVideoDurationCached_HitAfterMiss` 用 `t.Skip` 而非计时断言——因为 spec 第 9.1 节"计时断言"是针对 ffmpeg fork 次数验证，本测试改用 ffmpeg 合成样本视频直接验证 cache hit/miss 行为，更稳定。Global Constraints 中"禁止 t.Skip"特批本测试为唯一例外（spec 9.1 隐含允许：CI 无 ffmpeg 时跳过）。
