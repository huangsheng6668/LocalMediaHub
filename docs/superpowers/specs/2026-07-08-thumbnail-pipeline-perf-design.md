# 缩略图管线并发性能优化（Round 24）

- **日期**: 2026-07-08
- **范围**: 服务端缩略图管线强化（C1）+ 客户端网络层（C2）+ 客户端 Coil 调度（C3）
- **策略**: 3 commit 打包，每项独立可回滚，推荐执行顺序 C1 → C2 → C3
- **状态**: 待评审
- **前置**:
  - Round 3（缩略图 `setMediaCacheHeaders`）
  - Round 15（`memCache` LRU 200 项 + pprof 端点）
  - Round 17（OkHttp 单例 + 20MB Cache + ConnectionPool(15, 5min)）

---

## 1. 背景与动机

### 1.1 用户痛点

- **A1 客户端体感卡顿**：大目录（数百项）滑动时缩略图"啪啪"刷新、可见项加载延迟
- **B4 多客户端并发吞吐下降**：局域网 2-3 台设备同时浏览时明显变慢

### 1.2 规模假设

- **目录规模 S1**（200-1000 项）—— 不需要虚拟列表/分页，瓶颈在调度
- **客户端并发 C1**（2-3 台家用）—— 不需要分布式优化，瓶颈在共享资源争用

### 1.3 已确认的瓶颈（代码证据）

| 假设 | 证据 | 影响 |
|---|---|---|
| **G2 Coil 并发未限流** | `LocalMediaHubApplication.kt:46-68` 未设 `.limit(...)`，Coil 默认 64 并发 | 首屏几十个 `AsyncImage` 同时入队，可见项与预取项平等竞争 |
| **G3 缩略图生成阻塞** | `thumbnail.go:53` `sem chan struct{}` 容量 = `NumCPU`；视频 miss 每次都 fork ffprobe + ffmpeg | 多客户端请求同一视频时重复 N 次 CPU 密集工作 |
| **G4 Scanner 锁竞争** | `scanner.go` 已有 singleflight + 60s TTL cache | **已基本解决**，非本次主战场 |
| **G5 HTTP 连接复用** | `OkHttpModule.kt:52` `ConnectionPool(15, 5min)`；OkHttp 默认 `maxRequestsPerHost=5` | 单客户端首屏可占满 15 连接，第二台来时几乎要重建；`maxRequestsPerHost=5` 是隐藏瓶颈，连接池扩容无效 |

### 1.4 关键洞察

`maxRequestsPerHost=5` 是 OkHttp 最经典踩坑点：即便 ConnectionPool 扩到 40，dispatcher 仍只允许对同一 host 并发 5 个请求。多出来的连接永远用不上。**C2.2 是 B4 的关键修正**。

---

## 2. 目标与非目标

### 目标

1. **C1**: 服务端缩略图生成用 singleflight 防止多客户端击穿同一视频；duration cache 减少每次 miss 都 fork ffprobe；同时将 duration 缓存接入 `/api/v1/media/duration` 接口，全面消除不必要的 ffprobe 进程。
2. **C2**: OkHttp dispatcher `maxRequestsPerHost` 5 → 40 + ConnectionPool 15 → 40
3. **C3**: Coil ImageLoader `limit(12)`，依赖 LazyGrid 自动取消机制保证可见项优先加载
4. **行为完全兼容**：API、URL、磁盘缓存 key 格式、客户端视觉表现全部不变

### 非目标

- ❌ 改 ffmpeg/ffprobe 命令本身（`-ss` 前置等流式优化是后续轮次）
- ❌ 改 `models.MediaFile`（不动 scanner 持久化结构）
- ❌ 改 `NativeDecoderFactory`（Rust 解码层稳定）
- ❌ 改 Coil memoryCache / diskCache容量
- ❌ 自定义 visibility tracker（过度工程，`limit` + 自动取消已足够）
- ❌ 改 VideoCard 视觉结构（C3.3 已撤回，避免视觉变更引入回归）
- ❌ HTTP/2 / TLS（项目无 TLS，cleartext HTTP/2 ROI 低）
- ❌ 改服务端 `http.Server` timeout（已合理：Read=30s / Idle=120s / Write=0）
- ❌ wrk 性能 baseline（spec 记录建议但不强制）

---

## 3. 架构总览与 Commit 切分

| Commit | 主题 | 范围 | 主要收益 |
|---|---|---|---|
| **C1** | 服务端缩略图管线强化 | `thumbnail.go` + duration cache + duration 接口优化 + `server.go` 生命周期 | B4 主要：同视频不重复 fork ffmpeg/ffprobe，优化时长接口 |
| **C2** | 客户端网络层 | `OkHttpModule.kt` | B4 关键修正：解除 `maxRequestsPerHost=5` 隐藏瓶颈 |
| **C3** | 客户端 Coil 调度 | `LocalMediaHubApplication.kt` | A1 主要：`limit(12)` + 自动取消，可见项优先 |

**执行顺序 C1 → C2 → C3**：

- C1 是服务端纯内部改动，无 API/协议变更，最安全先做
- C2 改网络层，C1 的并发优化需要它配合（防止单客户端挤满连接池）
- C3 改客户端 UI 层调度，依赖 C2 的连接池扩容（否则 `limit` 提高反而更堵）

**跨 commit 不变约束**：

- API 契约不变（URL、参数、响应 schema）
- 磁盘缓存目录结构与 key 格式不变（与 Round 15 LRU 一致）
- `NativeDecoderFactory` 不动
- 所有现有测试不回归

---

## 4. C1 服务端缩略图管线强化

### 4.1 问题精确化

`thumbnail.go:117-185` 的 `generateThumbnailFromFile` 对视频：

1. `videoDuration()` → fork `ffprobe`（每次 miss 都跑）
2. `exec ffmpeg -ss <midpoint> -i ... -vframes 1` → fork `ffmpeg`（CPU 密集）

多客户端并发请求同一视频时，**每台都独立 fork ffprobe + ffmpeg**——`sem` 信号量（`runtime.NumCPU()`）保证不过载，但同一段工作重复 N 次。

### 4.2 C1.1 — `singleflight` 防视频缩略图击穿

`ThumbnailService` 加 `sf singleflight.Group`（项目已用 `golang.org/x/sync/singleflight`，scanner.go 在用）。

```go
type ThumbnailService struct {
    // existing fields...
    sf singleflight.Group
}

// generateBytesVia 改造：用 singleflight 包一层 genFunc 调用
func (s *ThumbnailService) generateBytesVia(
    sourcePath string,
    genFunc func(string) (string, error),
) ([]byte, error) {
    fi, err := os.Stat(sourcePath)
    if err != nil {
        return nil, err
    }
    cacheKey := s.thumbnailCacheKey(sourcePath, fi.ModTime())

    if cached, ok := s.memCache.Get(cacheKey); ok {
        return cached, nil
    }

    // singleflight key 用 cacheKey（含 modTime）——文件被修改后 key 变化，
    // 不会把新旧版本串到一起
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

**效果**：N 个客户端同时请求同一未缓存视频缩略图 → ffprobe + ffmpeg **只跑一次**，其余 N-1 个 goroutine 等待结果后直接拿 memCache 字节返回。

### 4.3 C1.2 — `ffprobe` duration cache（V2）

视频 miss 时省掉每次都 fork ffprobe。**独立 JSON 持久化**（不污染 `models.MediaFile`，避免 scanner 改动）。

为了提高并发读吞吐，使用 `sync.RWMutex` 代替 `sync.Mutex`。

```go
// durationCache 落盘到 cacheDir/durations.json，结构 map[string]durationEntry
type durationEntry struct {
    Duration float64   // seconds
    ModTime  time.Time // source file mtime; mismatch → invalidate
}

type ThumbnailService struct {
    // existing fields...
    durMu           sync.RWMutex
    durCache        map[string]durationEntry
    durDirty        bool               // 内存数据是否脏
    durTimerPending bool               // 是否已启动 5s 延迟落盘协程
    ctx             context.Context    // 用于 goroutine 生命周期控制
    durCancel       context.CancelFunc // 用于在服务停止时取消 goroutine
}

// videoDurationCached 先查内存 cache，miss 时 fork ffprobe 并写回
func (s *ThumbnailService) videoDurationCached(sourcePath string) (float64, bool) {
    fi, err := os.Stat(sourcePath)
    if err != nil {
        return s.videoDuration(sourcePath) // fallback 原路径
    }
    key := sourcePath + "|" + fi.ModTime().Format(time.RFC3339Nano)

    // 读路径使用 RLock，避免并发读时相互阻塞
    s.durMu.RLock()
    if entry, ok := s.durCache[key]; ok {
        s.durMu.RUnlock()
        return entry.Duration, true
    }
    s.durMu.RUnlock()

    d, ok := s.videoDuration(sourcePath) // 原始 fork ffprobe
    if ok {
        s.durMu.Lock()
        s.durCache[key] = durationEntry{Duration: d, ModTime: fi.ModTime()}
        s.markDurDirty()
        s.durMu.Unlock()
    }
    return d, ok
}

// markDurDirty 必须在持有 durMu.Lock() 时调用，防抖延迟落盘
func (s *ThumbnailService) markDurDirty() {
    s.durDirty = true
    if s.durTimerPending {
        return
    }
    s.durTimerPending = true

    go func() {
        select {
        case <-s.ctx.Done():
            // 退出时不在此协程处理，Shutdown 方法会做同步落盘
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

        s.persistDurationCache() // 释放锁后执行磁盘 I/O，避免阻塞读取
    }()
}

// persistDurationCache 落盘，内部使用读写锁防止磁盘 I/O 阻塞查询
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
        // 写入失败时恢复脏标记，以便下次重试
        s.durMu.Lock()
        s.durDirty = true
        s.durMu.Unlock()
    }
}

// loadDurationCache 启动时加载
func (s *ThumbnailService) loadDurationCache() {
    filePath := filepath.Join(s.cacheDir, "durations.json")
    bytes, err := os.ReadFile(filePath)
    if err != nil {
        s.durMu.Lock()
        s.durCache = make(map[string]durationEntry)
        s.durMu.Unlock()
        return
    }

    var cache map[string]durationEntry
    if err := json.Unmarshal(bytes, &cache); err != nil {
        slog.Warn("Failed to unmarshal durations.json", "error", err)
        cache = make(map[string]durationEntry)
    }

    s.durMu.Lock()
    s.durCache = cache
    s.durMu.Unlock()
}

// Shutdown 关闭服务，取消防抖协程并同步落盘
func (s *ThumbnailService) Shutdown() {
    s.durCancel() // 取消防抖协程
    s.persistDurationCache() // 同步落盘，内部会自行判断并解锁落盘
}
```

### 4.4 C1.3 — 共享 duration cache 到 `/api/v1/media/duration`

为避免客户端在播放视频或查询视频时长时再次 fork `ffprobe`，我们可以将 `ThumbnailService` 中的时长缓存共享出去。

在 `ThumbnailService` 增加导出方法：
```go
func (s *ThumbnailService) VideoDuration(sourcePath string) (float64, bool) {
    return s.videoDurationCached(sourcePath)
}
```

在 `server/internal/server/handler/media.go` 的 `MediaDuration` 接口中，优先调用此缓存接口：
```go
func (h *Handler) MediaDuration(c echo.Context) error {
    ...
    // 优先从缩略图服务中查询缓存的时长，避免 fork ffprobe 进程
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

并在 `server/internal/server/server.go` 的 `Server.Stop()` 方法中添加对 `s.Thumbnail.Shutdown()` 的调用：
```go
func (s *Server) Stop() error {
    s.Scanner.Shutdown()
    s.Thumbnail.Shutdown() // 增加此处调用
    ...
}
```

### 4.5 涉及文件

| 文件 | 改动类型 |
|---|---|
| `server/internal/service/thumbnail.go` | 改：加 `sf` / `durCache` / `durMu` / `durDirty` / `durTimerPending` / `ctx` / `durCancel` 字段；`generateBytesVia` 改用 singleflight；新增 `videoDurationCached` + `loadDurationCache` + `markDurDirty` + `persistDurationCache` + `Shutdown` + `VideoDuration`；`generateThumbnailFromFile` 调用点改为 `videoDurationCached`；引入 `"encoding/json"` 和 `"log/slog"` |
| `server/internal/server/server.go` | 改：`Stop()` 增加调用 `s.Thumbnail.Shutdown()` |
| `server/internal/server/handler/media.go` | 改：`MediaDuration` 优先查 `h.thumbnail.VideoDuration` |
| `server/internal/service/thumbnail_test.go` | 扩：singleflight 并发击穿测试 + duration cache hit/miss 测试 + durations.json 损坏不阻塞启动测试 + 持久化往返测试 |
| `server/internal/service/thumbnail_cache_test.go` | 扩：验证 singleflight 不会破坏现有 LRU 行为；memCache hit 时跳过 singleflight |

### 4.6 风险与缓解

1. **singleflight 错误共享**：leader 失败 → 所有 follower 拿 error。**预期行为**（ffmpeg/ffprobe 故障本就该报错，下次请求自然重试）。
2. **duration cache 落盘竞态**：`durMu` 读写锁保护，写持锁时间极短（map 赋值）。**读路径必须持读锁**（Go map 并发读写 runtime crash）。
3. **durations.json 文件损坏**：解析失败时 `durCache = make(...)`，slog warn，**不删除文件**，不阻塞服务。
4. **modTime 校验**：用 `RFC3339Nano` 编码到 key 里（与 LRU 一致），文件被替换后 key 变化，旧 entry 残留但永不命中，无害。
5. **服务关闭时脏数据丢失**：通过 `Server.Stop()` 中同步调用 `Thumbnail.Shutdown()` 强制写入磁盘缓存缓解。

---

## 5. C2 客户端网络层

### 5.1 问题精确化

`OkHttpModule.kt:52` 当前 `ConnectionPool(15, 5min)`：

- Coil 默认 64 并发 + Compose 首屏几十个 `AsyncImage` → OkHttp dispatcher 把超出连接数的请求塞 FIFO 队列
- **关键**：OkHttp `Dispatcher.maxRequestsPerHost` 默认 **5**——单服务端只允许 5 个并发请求，其余 59 个槽位浪费
- 第二台设备来时连接池几乎全占 → 新连接频繁建立/拆除 = 多客户端吞吐下降

### 5.2 C2.1 — OkHttp ConnectionPool 扩容 + keep-alive 调优

```kotlin
// 单客户端首屏可瞬间触发 30-50 个缩略图请求；扩到 40 给足余量。
// keepAlive 5min → 3min：缩略图访问是密集短脉冲，连接长时间闲置意义不大，
// 反而占着服务端文件描述符。
.connectionPool(ConnectionPool(40, 3, TimeUnit.MINUTES))
```

**为什么 40 而不是更大**：

- C1 场景 2-3 台 × 单台首屏 12-15 可见项 ≈ 30-45 并发，40 给余量
- Coil dispatcher 并发 64，但 C3 会加 `.limit(12)` 把上限拉到 ~12，所以 40 连接实际不会被同时占满——更多是"防止 dispatcher 队列排太长"
- 太大会占服务端 FD / 内存（每个连接 ~50KB）

### 5.3 C2.2 — OkHttp dispatcher 并发上限

```kotlin
val dispatcher = okhttp3.Dispatcher().apply {
    maxRequests = 64                  // 默认，不动
    maxRequestsPerHost = 40           // 默认 5 → 40，与 ConnectionPool 对齐
}
// ...
OkHttpClient.Builder()
    .dispatcher(dispatcher)
    .connectionPool(ConnectionPool(40, 3, TimeUnit.MINUTES))
```

**关键**：`maxRequestsPerHost=5` 是 OkHttp 最经典踩坑点。ConnectionPool 扩到 40 没用，如果 dispatcher 还是 5，多出来的连接永远用不上。

### 5.4 C2.3 — 服务端 Echo：无改动

复查 `server.go:83-89`：

- `ReadTimeout: 30s` — 覆盖 config JSON 等小 body ✓
- `IdleTimeout: 120s` — keep-alive 复用窗口足够 ✓
- `WriteTimeout: 0` — 不掐视频流 ✓

**不动**。B4 的根因在客户端 dispatcher/连接池，不在服务端。

### 5.5 涉及文件

| 文件 | 改动类型 |
|---|---|
| `android/app/src/main/java/com/juziss/localmediahub/network/OkHttpModule.kt` | 改：`Dispatcher.maxRequestsPerHost=40` + `ConnectionPool(40, 3min)` |

### 5.6 风险与缓解

1. **服务端 FD 压力**：40 连接 × 2-3 客户端 = ~120 连接，Windows 默认 FD 上限足够。
2. **keepAlive 5min → 3min 缩短**：故意为之——避免长期占着服务端不释放，3 分钟覆盖典型浏览会话间隙。

---

## 6. C3 客户端 Coil 调度

### 6.1 问题精确化

`LocalMediaHubApplication.kt:46-68` `ImageLoader` 未设 `.limit(...)`，Coil 2.x 默认 `limit` 为 `Integer.MAX_VALUE`，实际由 dispatcher worker 数决定（典型 64 并发）。

Compose 首屏一次性重组几十个 `AsyncImage`，所有请求平等排队 → 快速滑动时屏幕外项的请求和可见项请求竞争 → 可见项加载延迟 → 掉帧感。

### 6.2 C3.1 — ImageLoader 加 `limit`（限制总并发解码）

```kotlin
ImageLoader.Builder(this)
    .components {
        add(NativeDecoderFactory.Factory())
        add(BitmapFactoryDecoder.Factory())
    }
    .crossfade(200)
    // 限制同时运行的请求（fetch + decode）总数为 12。
    // 太大：CPU/IO 抢占主线程 composition，掉帧。
    // 太小：首屏并发不够，加载节奏拖。
    // 12 ≈ 单屏可见项 (4-6) × 2 (prefetch 余量)。
    .limit(12)
    .memoryCachePolicy(CachePolicy.ENABLED)
    // ... 其余不变
```

**为什么 12**：

- 单屏 2 列 × 3 行 = 6 项可见
- LazyGrid 默认 prefetch 上下各 1-2 行 → 总活跃 12-18 项
- 12 给可见区段留余量

### 6.3 C3.2 — 可见项优先：放弃 priority，靠自动取消

LazyGrid 的 prefetch 项和可见项**走同一个 `items()` 块**，无法在 composible 内部可靠区分。原 Q1 提议的 `priority(HIGH/LOW)` 需要自定义 visibility tracker，复杂度高且收益边际。

**方案 A（采用）**：所有卡片不显式 priority，**靠 `limit(12)` + Coil 自动取消机制**：

- `AsyncImage` 在 composible 离开 composition 时 `onDispose` → Coil `request.dispose()` → 从 dispatcher 队列移除
- 离开屏幕的项的请求被取消，新可见项的请求立即占据 limit 名额
- 这等价于"可见项优先"，且更朴素可靠

### 6.4 C3.3 — VideoCard 视觉重构（撤回）

考虑过简化 `MediaItems.kt:144-185` VideoCard 的 `Box` 嵌套层级（占位 Icon 永远盖在图片下、半透明黑层覆盖）。**已撤回**——视觉/行为变更不是性能优化本职，会引入"加载中视觉变化"回归风险。C3 不动 UI 结构。

### 6.5 涉及文件

| 文件 | 改动类型 |
|---|---|
| `android/app/src/main/java/com/juziss/localmediahub/LocalMediaHubApplication.kt` | 改：`.limit(12)` |
| `android/app/src/main/java/com/juziss/localmediahub/ui/component/MediaItems.kt` | 无改动 |
| `android/app/src/main/java/com/juziss/localmediahub/ui/component/GridContainers.kt` | 无改动（WaterfallImageGrid 的 `AsyncImage` 保持原样，Coil 自动取消机制生效即可） |

### 6.6 风险与缓解

1. **`limit(12)` 太小**：极端情况首屏多于 12 项时加载串行化。LazyGrid 默认 prefetch 不会一次性触发 >12 项（可见区段 + 上下各 1 行 prefetch）。视频/图片用 2 列布局，安全。
2. **AsyncImage onDispose 未及时取消**：Coil 2.x Compose 集成 `AsyncImagePainter` 内部用 `remember` + `onDisposable` 管理，离开 composition 触发 `request.dispose()`。**这是 Coil 内置行为，不依赖本次改动**。

---

## 7. 端到端数据流

### 7.1 场景 A：单客户端首次浏览大目录

```
[Compose 重组]
  WaterfallImageGrid / VideoCard / ImageCard
  └─ AsyncImage(model = thumbnailUrl)             ← C3.2：不显式 priority，靠 limit
       │
[Coil ImageLoader]                                 ← C3.1：limit(12) 总闸门
  ├─ memoryCache miss (首屏)
  ├─ diskCache miss (首次)
  └─ 入队 fetch（dispatcher maxRequestsPerHost=40） ← C2.2：限流放宽
       │
[OkHttp]                                           ← C2.1：ConnectionPool(40, 3min)
  └─ GET /api/v1/media/thumbnail?path=...
       │
[Server: handler/media.go::MediaThumbnail]
  └─ ThumbnailService.GenerateThumbnailBytes
       └─ generateBytesVia
            ├─ memCache miss
            ├─ singleflight.Do(cacheKey, ...)      ← C1.1：防击穿
            │    └─ GenerateThumbnail
            │         ├─ 磁盘 cache miss
            │         ├─ sem <- struct{}{}         （已有 NumCPU 限流）
            │         └─ generateThumbnailFromFile
            │              ├─ videoDurationCached  ← C1.2：durations.json cache
            │              │    └─ miss → fork ffprobe → 写 durCache → 防抖 5s 落盘
            │              └─ fork ffmpeg -ss <midpoint> -vframes 1
            │         → 写磁盘缓存 + 读 bytes + 写 memCache
            └─ 返回 bytes（其他等待者一并拿）
       │
[Server: 响应 Cache-Control headers]               ← Round 3 已加
  └─ 客户端 Coil respectCacheHeaders=true → 写 diskCache
       │
[Coil decode]                                      ← NativeDecoderFactory (Rust)
  └─ BitmapDrawable → AsyncImage 渲染
```

**首屏时序**：可见项 ~6 + prefetch ~6 = 12 并发 → C3.1 `limit(12)` 全部放出 → C2.2 `maxRequestsPerHost=40` 不卡 → 12 请求并行打服务端 → C1.1 singleflight 让"重复视频"合并 → ffprobe 只跑一次（C1.2 让"已扫过的视频"零 ffprobe）→ ffmpeg 在 sem 限流下并行生成 → 字节回客户端 → Coil decode → 渲染。

### 7.2 场景 B：多客户端并发请求同一视频缩略图

```
[Client A]                                    [Client B]                              [Client C]
  GET /thumb?path=X.mp4                         GET /thumb?path=X.mp4                   GET /thumb?path=X.mp4
       │                                              │                                        │
  memCache miss                                memCache miss                            memCache miss
  sf.Do(key_X) → leader                        sf.Do(key_X) → follower                  sf.Do(key_X) → follower
       │                                              │                                        │
       │                                       ──等 leader──                            ──等 leader──
  fork ffprobe (cached miss → 写 durCache)
  fork ffmpeg -ss midpoint
  写 memCache + 磁盘缓存
  返回 bytes ───────────────────────────────►  wake up → return bytes ─────────────────► wake up → return bytes
       │                                              │                                        │
  响应 200                                     响应 200                                  响应 200
```

- **改动前**：3 个 ffprobe + 3 个 ffmpeg fork，3 倍 CPU 浪费
- **改动后**：1 个 ffprobe + 1 个 ffmpeg，2 个 follower 等待 < 1s 后直接拿字节

### 7.3 场景 C：快速滑动时取消机制

```
[T=0ms]  用户开始快速滑动 LazyStaggeredGrid
         可见区段从 item[0..11] → item[20..31]
[T=10ms] item[0..11] 离开 composition
         └─ AsyncImage onDispose → Coil RequestDisposable.dispose()
              └─ 从 dispatcher 队列移除（如还没开始）
              └─ interrupt fetch（如正在进行，OkHttp Call.cancel()）
[T=20ms] item[20..31] 进入 composition
         └─ 12 个新 AsyncImage 进入 Coil limit(12)
         └─ 因 limit 上限 = 12，全部立即调度（不被旧请求堵）
```

- **改动前**：旧的 12 个未完成请求继续占用 dispatcher 队列，新请求排到 13-24 位 → 可见项加载延迟
- **改动后**：旧请求被 dispose 释放 limit 名额，新请求立即占据名额 → 可见项加载不延迟

### 7.4 关键不变量

- 缩略图字节内容 **完全不变**（同源、同 modTime → 同 md5 key → 同 ffmpeg 输出）
- 磁盘缓存路径与文件名 **完全不变**（`cacheDir/<md5>.jpg` / `cacheDir/system/<md5>.jpg`）
- API URL 与响应 schema **完全不变**
- 客户端看到的行为：**只是更快**，无任何功能/视觉变化

---

## 8. 错误处理与边界情况

### 8.1 C1 服务端

| 情况 | 处理 |
|---|---|
| singleflight 内 genFunc 返回 error | `sf.Do` 把 error 返回给所有 follower。所有客户端拿到 500。**预期行为**——ffmpeg/ffprobe 故障本就该报错，下次请求自然重试 |
| singleflight leader panic | `sf.Do` 不 recover panic。但 `generateBytesVia` 路径都是 error return（不 panic），ffmpeg 子进程是 `cmd.Run()` 不 panic，**风险为零** |
| durations.json 不存在 | 启动 `loadDurationCache` 静默跳过，`durCache = make(map...)`，正常工作 |
| durations.json 解析失败（损坏） | slog warn + 用空 map 启动，**不删除文件**（避免误删有用数据）。下次 ffprobe miss 会覆盖式重写 |
| durations.json 写入失败（磁盘满/权限） | slog warn，**不阻塞**请求路径。内存 `durCache` 仍然命中，下次启动丢失 |
| videoDurationCached 内 ffprobe 失败 | fallback 到原 `midpointSeek(0, false)` → `"5"`，与现状一致 |
| 并发读写 durCache | `durMu sync.RWMutex` 保护。**读路径持 RLock，写路径持 Lock**（Go map 并发读写 runtime crash） |
| 防抖落盘 goroutine 生命周期 | 由 ThumbnailService 持有，服务退出时用 `durCancel` 取消，避免泄漏 |
| 文件被替换（modTime 变） | cacheKey 含 RFC3339Nano modTime → key 变化 → memCache 自然 miss → sf 新 leader 重新生成 → duration cache 旧 entry 残留但永不命中，无害 |
| 视频时长为 0 / N/A | `parseFFprobeDuration` 返回 false → `midpointSeek` fallback `"5"`，与现状一致 |
| 服务关闭时脏数据丢失 | 通过 `Server.Stop()` 中同步调用 `Thumbnail.Shutdown()` 强制写入磁盘缓存缓解 |

### 8.2 C2 客户端网络层

| 情况 | 处理 |
|---|---|
| ConnectionPool 扩到 40 后服务端拒绝 | 服务端 `http.Server` 无 max-connections 限制，Windows FD 默认上限远高于 120，无风险 |
| maxRequestsPerHost=40 但实际 host 数 > 1 | 项目只有 1 个 server，`maxRequests=64` 总闸门足够。多 host 场景不存在 |
| Dispatcher 队列堆积 | OkHttp 默认队列无界，理论上可堆积。但 `limit(12)` 在 Coil 层已限制入队速率，不会无限堆积 |
| ConnectionPool keepAlive 缩到 3min，浏览间隔 > 3min | 连接被回收，下次新建。3min 已覆盖典型浏览间隙，重建成本（TCP handshake）可忽略 |

### 8.3 C3 客户端 Coil

| 情况 | 处理 |
|---|---|
| `limit(12)` 配置生效失败 | Coil 2.x `.limit(n)` API 稳定，无失败路径 |
| AsyncImage onDispose 没及时取消请求 | Coil 2.x Compose 集成 `AsyncImagePainter` 内部用 `remember` + `onDisposable` 管理，离开 composition 触发 `request.dispose()`。**Coil 内置行为，不依赖本次改动** |
| limit(12) 导致首屏多于 12 项加载串行 | LazyGrid 默认 prefetch 不会一次性触发 >12 项（可见区段 + 上下各 1 行 prefetch）。视频/图片用 2 列布局，安全 |
| Coil dispatcher 线程数不足 | `.limit(12)` 控制并发请求数，Coil 内部用默认 `Dispatchers.Default`（CPU 核数），12 并发在 8 核设备上够用 |

### 8.4 跨 commit 边界

| 边界 | 处理 |
|---|---|
| 只部署 C1 不部署 C2/C3 | 服务端独立加强，客户端按现状工作。**B4 部分收益**（同视频不重复 ffprobe/ffmpeg） |
| 只部署 C2 不部署 C1/C3 | 客户端连接池放宽，但 `limit` 仍默认 64，首屏仍可能挤爆。**收益有限** |
| 只部署 C3 不部署 C1/C2 | 客户端调度顺了，但 `maxRequestsPerHost=5` 卡着，可见项请求仍排队。**A1 部分收益** |
| 三个 commit 都部署 | 完整效果。**推荐** |

---

## 9. 测试策略

### 9.1 C1 服务端（可单测）

**`thumbnail_test.go` 扩展：**

```go
// 1. singleflight 并发击穿：30 个 goroutine 同时请求同一未缓存视频
//    断言：总耗时 ≈ 1 次 ffmpeg 耗时（计时断言，不注入 ffmpeg 计数器避免污染生产代码）
func TestGenerateThumbnailBytes_ConcurrentSameKey_SingleFlight(t *testing.T)

// 2. durations.json 加载/写入往返：写入 → 重启 → 读回一致
func TestDurationCache_PersistRoundTrip(t *testing.T)

// 3. durations.json 损坏时不阻塞启动
func TestDurationCache_LoadCorruptFile_NoCrash(t *testing.T)

// 4. videoDurationCached hit/miss：miss 后写入 → 第二次命中
func TestVideoDurationCached_HitAfterMiss(t *testing.T)

// 5. VideoDuration 缓存与 fallback：优先查 cache，未命中时直接查 ffprobe 并存入 cache
func TestVideoDuration_CacheAndFallback(t *testing.T)
```

**测试 1（singleflight 击穿）的真实价值在于"验证多个 follower 共享 leader 结果"**，但难以直接断言 ffmpeg 只跑一次（除非注入计数器）。简化方案：

- 用**计时**断言：N 个并发请求的总耗时 ≈ 1 次 ffmpeg 耗时（而非 N 次）
- 避免加测试用全局变量污染生产代码

**`thumbnail_cache_test.go` 扩展：**

```go
// 6. singleflight + memCache 协同：sf leader 写完 memCache 后，
//    新请求应直接命中 memCache（不再进入 sf.Do）
func TestGenerateThumbnailBytes_MemCacheHitSkipsSingleFlight(t *testing.T)
```

**`scanner_test.go` / `streaming_test.go` / `server_test.go` 不动**（C1 不碰这些）。

### 9.2 C2 客户端（配置校验单测）

OkHttp dispatcher / connectionPool 行为难单测（依赖真实 socket）。**只跑现有 `testDebugUnitTest` 确保不编译失败**，行为验证靠 smoke test。

加一个**配置校验测试**（验证 OkHttpModule 提供的 client 有正确的 dispatcher 配置）：

```kotlin
@Test
fun provideOkHttpClient_has40MaxRequestsPerHost() {
    val client = OkHttpModule.provideOkHttpClient(/* mock cache */)
    assertEquals(40, client.dispatcher.maxRequestsPerHost)
    assertEquals(40, client.connectionPool.maxIdleConnections)
}
```

便宜（纯配置断言），能防止未来误改 dispatcher 配置时静默回归。

### 9.3 C3 客户端（无单测）

Coil 调度行为无法单测（依赖 Compose runtime + 真实 IO）。**只跑现有 `testDebugUnitTest` 确保 `.limit(12)` 编译通过**。

`LocalMediaHubApplication.newImageLoader()` 不暴露 limit 给外部断言（Coil 2.x 内部）。**YAGNI，不加**。

### 9.4 Smoke test 矩阵

| 步骤 | C1 | C2 | C3 |
|---|---|---|---|
| 服务端 `go test ./...` 全过 | ✓ | — | — |
| 客户端 `./gradlew testDebugUnitTest assembleDebug` | — | ✓ | ✓ |
| 1. 安装 release APK 到真机 | — | ✓ | ✓ |
| 2. 连接服务端 | — | ✓ | ✓ |
| 3. 打开 200+ 项视频目录 | ✓ | ✓ | ✓ |
| 4. 主观评估首屏加载流畅度 | — | ✓ | ✓ |
| 5. 快速滑动 5 秒 → 停下 → 观察可见项加载 | — | — | ✓ |
| 6. **2 台设备同时**打开同一视频目录 | ✓ | ✓ | — |
| 7. 服务端 `durations.json` 文件正常生成 | ✓ | — | — |
| 8. 手动删除 `durations.json` 后重启服务 | ✓ | — | — |

### 9.5 性能 baseline（建议，非强制）

Round 23 spec 第 10 节提到"基于 pprof + benchmark 建立 baseline"。本次优化可借机：

- C1 部署后用 `wrk -c 10 -d 30s http://server:port/api/v1/media/thumbnail?path=X` 压缩同一视频缩略图，对比改动前后 QPS
- 主观对比够了的话跳过 wrk

**spec 记录建议但不强制做 baseline**，避免拖慢交付。

---

## 10. 回滚预案

每个 commit 独立可 `git revert`：

- **C1 revert**：恢复 thumbnail.go，durations.json 残留无害（下次启动忽略）
- **C2 revert**：恢复 OkHttpModule.kt，无副作用
- **C3 revert**：恢复 LocalMediaHubApplication.kt 一处

---

## 11. 决策点汇总

| 决策 | 选择 | 理由 |
|---|---|---|
| 范围 | P1 + P2 + P3 三 commit 打包 | 用户明确选方案 X |
| 视频缩略图策略 | V1 + V2（singleflight + duration cache） | V1 防击穿主收益，V2 顺手省 ffprobe |
| duration cache 存储 | 独立 `durations.json` | 不污染 `models.MediaFile`，不动 scanner |
| duration cache 落盘 | 防抖 5s | 写频率高，避免每次 miss 同步 IO |
| duration cache 锁类型 | `sync.RWMutex` | 提高高并发多端浏览下的读吞吐，防止读读阻塞 |
| duration cache 共享 | `/api/v1/media/duration` 接入缓存 | 避免播放视频/查询时长时重复 fork ffprobe 进程 |
| duration cache 优雅退出 | `Server.Stop()` 同步 `Shutdown` | 保证进程退出时内存脏数据不丢失，安全可靠 |
| 客户端可见项优先 | 方案 A（`limit(12)` + 自动取消） | LazyGrid 内部无法可靠区分 prefetch，priority 方案过度工程 |
| VideoCard 视觉重构 | 撤回 | 非性能优化本职，避免视觉回归 |
| ConnectionPool 容量 | 40 | 2-3 客户端 × 12-15 并发 + 余量 |
| keepAlive | 5min → 3min | 缩略图访问密集短脉冲，长时间闲置无意义 |
| `maxRequestsPerHost` | 5 → 40 | OkHttp 隐藏瓶颈，关键修正 |
| Coil `limit` | 12 | 单屏 6 项 × 2 prefetch 余量 |
| C1 singleflight 测试 | 计时断言 | 避免加测试用全局变量 |
| C2 单测 | 配置校验 | 防止 dispatcher 配置被误改 |
| wrk baseline | 记录建议，非强制 | 避免拖慢交付 |
| 服务端 `http.Server` timeout | 不动 | 已合理 |

---

## 12. 非目标（再次明确）

- ❌ 改 ffmpeg/ffprobe 命令本身
- ❌ 改 `models.MediaFile` 持久化结构
- ❌ 改 `NativeDecoderFactory` / Rust 解码层
- ❌ 改 Coil memoryCache / diskCache 容量
- ❌ 自定义 visibility tracker
- ❌ VideoCard 视觉重构
- ❌ HTTP/2 / TLS / QUIC
- ❌ 改服务端 `http.Server` timeout
- ❌ sendfile/splice（Windows 不支持）
- ❌ 强制 wrk baseline
- ❌ API 破坏性变更
- ❌ 配置文件 schema 变更

---

## 13. 后续轮次（备忘）

- **视频缩略图流式优化**：ffmpeg `-ss` 前置 + `-noaccurate_seek` + `-frames:v 1 -f image2pipe -`（避免中间临时文件），可能再省一次 disk IO
- **图片缩略图不全量读入**：当前 `imaging.Open` 全量 decode；用 `image.DecodeConfig` 先取尺寸 + `image/codec` 的 `decode` 区域裁剪
- **scanner B1/B2/B4/B5**（Round 23 显式延后）：搜索 O(n) 优化、cap 预估、sync.Pool、扫描并发调优
- **APK 体积 C2**（Round 21 显式延后）：FFmpeg 裁剪重编
