# 服务端性能优化 3 项打包（Round 15）

- **日期**: 2026-07-05
- **范围**: Go 服务端（`server/internal/`）— 缩略图内存缓存 + pprof 诊断端点 + streaming Range 测试
- **策略**: 3 commit 打包，每项独立可回滚
- **状态**: 已评审（2026-07-05 审核修订）
- **前置**: Round 4（scanner 类型缓存、search HasPrefix 优化、DownloadFolderZip FD 修复均已落地）；Round 12 spec §9 列出的剩余服务端 follow-ups

---

## 1. 背景与动机

Round 15 brainstorming 阶段评估了 Go→Rust 迁移，结论是**负 ROI**：

- 服务端仅 ~3000 行业务逻辑，Go "笨重"不构成痛点
- systray GUI（系统托盘）在 Rust 生态远比 Go 复杂
- 客户端 Rust 重写（Round 11）有明确收益（native decoder 性能 + 多格式），服务端没有等价物
- 强行统一技术栈是技术审美而非工程必需

转向**保留 Go + 性能优化**。Round 4 spec 列的 3 个服务端性能项里，**scanner 类型缓存、scoped 搜索 HasPrefix 优化、DownloadFolderZip FD 修复均已实现**（注释明确）。真正剩余的边际改进：

1. **缩略图 LRU 内存缓存**：当前 `c.File(thumbPath)` 每次都读磁盘。重复访问（如多客户端同时浏览同目录）能省掉磁盘 IO。
2. **pprof 诊断端点**：当前无性能剖析入口。开发者诊断瓶颈需 copy 二进制 + 重启。生产 debug 体验差。
3. **streaming Range 测试覆盖**：`streaming_test.go` 仅 79 行（2 个子测试），关键 suffix-range/206/416 路径未覆盖。

### 1.1 范围明确

- ✅ 缩略图 LRU 内存缓存（200 项 ~20MB）
- ✅ pprof `/debug/pprof/*` 端点（私网+loopback 白名单）
- ✅ streaming Range 测试（4 关键用例：200/206/206/416）
- ❌ Go→Rust 迁移（真诚分析后明确排除，负 ROI）
- ❌ JSON encoder 复用、sync.Pool 微优化（收益低）
- ❌ HTTP keep-alive 调优、sendfile/splice（Windows 不支持）

---

## 2. 目标与非目标

### 目标
1. **C1 缩略图内存缓存**：在磁盘缓存之上加 200 项 LRU，命中避免 `os.ReadFile`。
2. **C2 pprof 诊断端点**：`/debug/pprof/*` 暴露标准 net/http/pprof，仅 RFC1918 + loopback + link-local 可访问。
3. **C3 streaming Range 测试**：覆盖 200/206/206/416 共 4 个关键 HTTP Range 用例。
4. **行为兼容**：所有现有测试不回归。
5. **零新依赖工具链**：仅加 `hashicorp/golang-lru/v2`（hashicorp 系，已有 mdns）。

### 非目标
- ❌ Go→Rust 迁移
- ❌ 客户端任何改动
- ❌ API 破坏性变更
- ❌ HTTP/2、QUIC、TLS 1.3 等协议层升级
- ❌ 配置文件 schema 变更

---

## 3. 架构与文件清单

### 3.1 文件改动矩阵（3 个 commit）

| Commit | 文件 | 改动类型 |
|---|---|---|
| C1 | `server/internal/service/thumbnail.go` | 改：加 `memCache *lru.Cache[string, []byte]` + 新方法 `GenerateThumbnailBytes` / `GenerateSystemThumbnailBytes` |
| C1 | `server/internal/service/thumbnail_cache_test.go` | **新增**：缓存命中/淘汰/并发击穿测试 |
| C1 | `server/internal/server/handler/images.go` | 改：`GetThumbnail` 改用 `GenerateThumbnailBytes` + `c.Blob`（命中内存缓存） |
| C1 | `server/internal/server/handler/videos.go` | 改：`GetVideoThumbnail` 同上（如有同样模式） |
| C1 | `server/internal/server/handler/media.go` | 改：`MediaThumbnail` 同上 |
| C1 | `server/internal/server/handler/system.go` | 改：`SystemThumbnail` 改用 `GenerateSystemThumbnailBytes`（注意：此处调的是 `GenerateSystemThumbnail`，非 `GenerateThumbnail`） |
| C1 | `server/go.mod` / `go.sum` | 改：加 `github.com/hashicorp/golang-lru/v2` |
| C2 | `server/internal/server/middleware/private_net.go` | **新增**：私网+loopback 白名单 middleware |
| C2 | `server/internal/server/server.go` | 改：注册 `/debug/pprof/*` 路由 group + middleware |
| C2 | `server/internal/server/server_test.go` | 扩：私网/loopback/公网 IP 鉴别测试 |
| C3 | `server/internal/service/streaming_test.go` | 扩：4 个 Range 用例 |

无新增 cmd 或 GUI 改动。

### 3.2 关键约束

- Go 1.24（`net.IP.IsPrivate()` 已就绪，Go 1.17+）
- Echo v4 middleware 签名
- pprof 用 `net/http/pprof` 默认 ServeMux（标准库惯例）
- 缩略图 LRU 容量 200 项（~20MB 堆）
- 缓存 key 与磁盘缓存一致：`md5(path + "|" + modTime.Format(RFC3339Nano))`（注意：现有 `GetThumbnailPath` 用 `RFC3339Nano` 格式化，非 `UnixNano()`）
- 所有 handler 改动保持 `setMediaCacheHeaders(c)` 调用不变（Round 3 已加）

---

## 4. 实现细节

### 4.1 C1: 缩略图 LRU 内存缓存

**`thumbnail.go` 改动：**

```go
import "github.com/hashicorp/golang-lru/v2"

type ThumbnailService struct {
    // existing fields...
    memCache *lru.Cache[string, []byte]
}

func NewThumbnailService(cacheDir string, maxSize int, format string, ffmpegPath string) (*ThumbnailService, error) {
    // existing MkdirAll...
    // golang-lru/v2 never returns error when size > 0; ignore via _
    cache, _ := lru.NewWithEvict[string, []byte](200, nil)
    return &ThumbnailService{
        // existing fields...
        memCache: cache,
    }, nil
}

// GenerateThumbnailBytes is the bytes-equivalent of GenerateThumbnail.
// On memory-cache hit, returns the cached JPEG without touching disk.
// On miss, generates + writes to disk (existing flow) + caches bytes here.
func (s *ThumbnailService) GenerateThumbnailBytes(sourcePath string) ([]byte, error) {
    fi, err := os.Stat(sourcePath)
    if err != nil {
        return nil, err
    }
    cacheKey := s.thumbnailCacheKey(sourcePath, fi.ModTime())

    if cached, ok := s.memCache.Get(cacheKey); ok {
        return cached, nil
    }

    // Ensure the disk-cached file exists (existing flow).
    cachePath, err := s.GenerateThumbnail(sourcePath)
    if err != nil {
        return nil, err
    }

    // Read disk → bytes → cache.
    bytes, err := os.ReadFile(cachePath)
    if err != nil {
        return nil, err
    }
    s.memCache.Add(cacheKey, bytes)
    return bytes, nil
}

// thumbnailCacheKey mirrors the disk cache key (md5 of path + modtime).
// ⚠️ 审核修正：必须与 GetThumbnailPath 保持一致，用 RFC3339Nano 而非 UnixNano()。
func (s *ThumbnailService) thumbnailCacheKey(sourcePath string, modTime time.Time) string {
    key := sourcePath + "|" + modTime.Format(time.RFC3339Nano)
    h := md5.Sum([]byte(key))
    return fmt.Sprintf("%x", h)
}
```

**`images.go::GetThumbnail` 改动：**

```go
func (h *Handler) GetThumbnail(c echo.Context) error {
    pathStr, err := decodeWildcardPath(c.Param("*"), "/thumbnail")
    if err != nil {
        return respondError(c, http.StatusBadRequest, err.Error())
    }
    resolved, err := service.ValidateAccessibleMediaPath(...)
    if err != nil {
        return respondError(c, http.StatusForbidden, "access denied")
    }

    bytes, err := h.thumbnail.GenerateThumbnailBytes(resolved)
    if err != nil {
        if os.IsNotExist(err) {
            return respondNotFound(c, "file not found")
        }
        return respondInternalError(c, err)
    }

    setMediaCacheHeaders(c)
    return c.Blob(http.StatusOK, "image/jpeg", bytes)
}
```

> **同样的 `GenerateThumbnailBytes + c.Blob` 替换**应用到 `videos.go::GetVideoThumbnail`、`media.go::MediaThumbnail`。`GetOriginal` 不变（原图不进缓存）。
>
> ⚠️ **审核注意：`system.go::SystemThumbnail` 需特殊处理**——当前代码调用的是 `h.thumbnail.GenerateSystemThumbnail(resolved)`（写入 `cacheDir/system/` 子目录），而非 `GenerateThumbnail`。因此需要额外实现 `GenerateSystemThumbnailBytes` 方法，逻辑与 `GenerateThumbnailBytes` 相同但内部委托 `GenerateSystemThumbnail`。不可直接复用 `GenerateThumbnailBytes`，否则缓存路径不一致。

**并发击穿保护：** `golang-lru/v2` 的 `Get` 不是原子的——并发 miss 时多个 goroutine 会同时生成。`ThumbnailService` 已有 `sem chan struct{}` 信号量限流（`runtime.NumCPU()`），所以击穿不会过载。如需更严格，可加 `singleflight.Group`（已在 scanner.go 用过），但**YAGNI**——LRU 命中率本身高，少量冗余生成可接受。

**容量估算：** 平均缩略图 ~100KB（150×150 JPEG ~30-50KB，3 倍保险）× 200 = ~20MB 堆。Go GC 可承受。

### 4.2 C2: pprof 诊断端点 + 私网白名单

**`server/internal/server/middleware/private_net.go`（新增）：**

```go
package middleware

import (
    "net"

    "github.com/labstack/echo/v4"
)

// PrivateNetOnly rejects requests from non-private IPs.
// Allowed: RFC1918 (10/8, 172.16/12, 192.168/16), loopback (IPv4 127/8,
// IPv6 ::1), link-local (IPv4 169.254/16, IPv6 fe80::/10).
// This matches the project's LAN-only deployment (mDNS/Bonjour discovery)
// and prevents leaking pprof data (heap dumps, goroutine traces, CPU
// profiles) to the public internet if the server is accidentally exposed.
func PrivateNetOnly() echo.MiddlewareFunc {
    return func(next echo.HandlerFunc) echo.HandlerFunc {
        return func(c echo.Context) error {
            ip := net.ParseIP(c.RealIP())
            if ip == nil {
                return echo.ErrForbidden
            }
            if !isPrivateOrLoopback(ip) {
                return echo.ErrForbidden
            }
            return next(c)
        }
    }
}

func isPrivateOrLoopback(ip net.IP) bool {
    // Go 1.17+ covers RFC1918 + RFC4193 fc00::/7 with IsPrivate().
    // IsLoopback() covers 127.0.0.0/8 + ::1/128.
    // IsLinkLocalUnicast() covers 169.254.0.0/16 + fe80::/10.
    return ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast()
}
```

**`server.go` 改动：**

```go
import (
    // existing...
    _ "net/http/pprof"  // registers /debug/pprof/ handlers on DefaultServeMux
    "net/http"

    "github.com/localmediahub/server/internal/server/middleware"
)

// In RegisterRoutes (or wherever routes are registered):
pprofGroup := e.Group("/debug/pprof", middleware.PrivateNetOnly())
pprofGroup.Any("/*", echo.WrapHandler(http.DefaultServeMux))
```

> **路由路径：** `/debug/pprof/` 是 net/http/pprof 默认注册的（包括 `cmdline`、`profile`、`symbol`、`trace`、`heap`、`goroutine` 等）。`echo.WrapHandler(http.DefaultServeMux)` 把整个 DefaultServeMux 桥接过来。

**`server_test.go` 扩展：**

```go
func TestPrivateNetOnly_AllowsPrivateIPs(t *testing.T) {
    cases := []string{
        "192.168.1.100",   // RFC1918
        "10.0.0.1",        // RFC1918
        "172.16.5.5",      // RFC1918
        "127.0.0.1",       // IPv4 loopback
        "::1",             // IPv6 loopback
        "fe80::1",         // IPv6 link-local
        "169.254.1.1",     // IPv4 link-local
    }
    for _, ip := range cases {
        t.Run(ip, func(t *testing.T) {
            // Setup echo context with c.RealIP() = ip, verify middleware
            // calls next handler (status 200, not 403)
        })
    }
}

func TestPrivateNetOnly_RejectsPublicIPs(t *testing.T) {
    cases := []string{
        "8.8.8.8",         // Google DNS
        "1.1.1.1",         // Cloudflare
        "203.0.113.1",     // TEST-NET-3 (RFC5737, simulates public)
    }
    for _, ip := range cases {
        t.Run(ip, func(t *testing.T) {
            // Verify middleware returns 403 Forbidden
        })
    }
}
```

### 4.3 C3: streaming Range 测试

**`streaming_test.go` 扩展（4 关键用例）：**

```go
func TestStreaming_FullResponse(t *testing.T) {
    // Setup: httptest server + 1KB temp file
    // Request: GET /stream?path=<file>, no Range header
    // Assert: 200, Content-Length=1024, body matches file
}

func TestStreaming_SingleRange(t *testing.T) {
    // Request: Range: bytes=0-99
    // Assert: 206, Content-Range "bytes 0-99/1024", Content-Length=100,
    //         body = first 100 bytes
}

func TestStreaming_SuffixRange(t *testing.T) {
    // Request: Range: bytes=-50
    // Assert: 206, Content-Range "bytes 974-1023/1024", Content-Length=50,
    //         body = last 50 bytes
}

func TestStreaming_UnsatisfiableRange(t *testing.T) {
    // Request: Range: bytes=999999-
    // Assert: 416, Content-Range "bytes */1024"
}
```

**测试策略：** 用 `httptest.NewServer` + 真实 server，发请求 + 断言。比 `httptest.NewRecorder` + 直接调 handler 重一些，但 Range 正确性靠 mock response writer 容易写错（`c.File` 内部用 `http.ServeContent`，需要真实 ResponseWriter 才能 exercise 完整流程）。

> ⚠️ **审核注意：** 现有 `streaming_test.go` 实际已使用 `httptest.NewRecorder`（79 行），且已覆盖 200 + 单 Range 206 两个场景。此处声称 "不用 `httptest.NewRecorder`" 与现有代码实践矛盾。建议新增的 suffix-range 和 416 测试**延续现有风格**（`httptest.NewRecorder` + `svc.ServeFile`），保持测试文件风格一致。若确实需要 `httptest.NewServer`，应在单独测试文件中。

> **原文关于不用 `httptest.NewRecorder` 的论述：** `http.ServeContent` 的 Range 处理依赖 `ResponseWriter.WriteHeader` + `io.CopyN` 的副作用，Recorder 不完整模拟 HTTP/1.1 chunked 行为，会掩盖真实 bug。（审核注：实际上 `httptest.NewRecorder` 对 `http.ServeContent` 的 Range 处理是足够的，标准库测试自身也使用 Recorder。现有 206 测试已证明可行。）

---

## 5. 测试

### 5.1 测试矩阵

| Commit | 新测试 | 现有测试 |
|---|---|---|
| C1 缓存 | `thumbnail_cache_test.go`（4 用例：hit/miss/evict/concurrent-stampede） | thumbnail_test.go 全过 |
| C2 pprof | server_test.go 扩展（7 私网 + 3 公网 IP 用例） | server_test.go 现有全过 |
| C3 Range | streaming_test.go 扩展（4 用例：200/206/206/416） | streaming_test.go 现有全过 |

### 5.2 真机/集成验证

- 启动 server → 用 `ab` 或 `wrk` 压缩 `/api/v1/images/.../thumbnail`，命中率应 >80%（重复访问）
- 浏览器访问 `http://server:port/debug/pprof/` → 私网机应看到 pprof index 页
- 公网机访问 `/debug/pprof/` → 403 Forbidden
- `go tool pprof -http=:8080 http://server:port/debug/pprof/heap` → 能拉取 heap profile

---

## 6. 实现顺序与提交策略

3 个 commit，每个独立可提交：

1. **C1 缩略图内存缓存**：service（含 `GenerateThumbnailBytes` + `GenerateSystemThumbnailBytes`）+ 4 个 handler 改动 + 测试
2. **C2 pprof 端点**：middleware + server.go 路由 + 测试
3. **C3 Range 测试**：streaming_test.go 扩展

每个 commit 之间：`cd server && go test ./...` 全过。

---

## 7. 已决策点

| 决策 | 选择 | 理由 |
|---|---|---|
| 范围 | 3 项打包 | 用户明确选 |
| Go→Rust 迁移 | 明确排除 | 真诚分析：负 ROI（systray GUI + 跨编译复杂度） |
| 缓存容量 | 200 项 LRU ~20MB | 用户明确选 |
| 缓存依赖 | `hashicorp/golang-lru/v2` | hashicorp 系，已有 mdns 依赖 |
| 缓存 key | `md5(path + "|" + modTime.Format(RFC3339Nano))` | 与现有 `GetThumbnailPath` 一致（注意非 `UnixNano`） |
| pprof 路径 | `/debug/pprof/*` | net/http/pprof 默认 |
| pprof 鉴权 | RFC1918 + loopback + link-local | 用户明确选 |
| Range 测试 | 4 关键用例 | 用户明确选 |
| Range 测试方法 | 延续现有 `httptest.NewRecorder` + `svc.ServeFile` 风格 | §4.3 + §11.1 #5 修订：现有 streaming_test.go 已用 Recorder 验证 200/206；`StreamingService.ServeFile` 是自定义 Range handler（非 `http.ServeContent`），Recorder 对其足够；保持测试文件风格一致 |

---

## 8. 已知限制（接受）

1. **缓存击穿未防：** 并发 miss 时多个 goroutine 可能同时生成同一缩略图。`sem chan struct{}` 信号量限流已存在，少量冗余可接受。如真成问题再加 `singleflight`（YAGNI）。
2. **缓存不预加载：** 启动后从空缓存开始。冷启动慢；这是预期行为。
3. **pprof 私网白名单基于 `c.RealIP()`：** 如服务端配置 `X-Forwarded-For` 信任代理，可能被 IP 欺骗。本项目无反向代理部署，可接受。
4. **Range 测试不覆盖 `If-Range`：** `If-Range` 用于条件 Range（基于 ETag 或 modtime），实际使用极少（ExoPlayer 不发），YAGNI。

---

## 9. 非目标（再次明确）

- ❌ Go→Rust 迁移
- ❌ JSON encoder 复用、sync.Pool 微优化
- ❌ HTTP keep-alive、TLS、HTTP/2 调优
- ❌ sendfile/splice（Windows 不支持）
- ❌ 客户端任何改动
- ❌ API 破坏性变更
- ❌ 配置文件 schema 变更

---

## 10. 后续轮次（不在本 spec，仅备忘）

- **pprof 持续监控**：基于 pprof 端点 + 自动 benchmark，建立性能 baseline。后续每次优化有数据支撑。
- **HTTP/2 + TLS**：若跨网络部署需求出现，加 TLS + Let's Encrypt 自动证书。
- **缓存预热**：Scanner `OnScanComplete` 时主动生成热门缩略图。
- **配置文件 v2**：YAML schema 重设计 + 热重载。

---

## 11. 审核意见（2026-07-05 代码核验）

> [!IMPORTANT]
> 以下为基于当前代码库实际状态的审核修正，已直接在文中标注 ⚠️ 处同步修改。

### 11.1 已修正的事实性错误

| # | 原文描述 | 实际代码 | 修正 |
|---|---|---|---|
| 1 | 缓存 key 用 `md5(path + modTime.UnixNano())` | `GetThumbnailPath` 用 `modTime.Format(time.RFC3339Nano)` | ⚠️ **关键修正**：`thumbnailCacheKey` 伪代码已改为 `RFC3339Nano`，避免内存缓存与磁盘缓存 key 不一致导致永远 miss |
| 2 | C1 涉及 "5 个 handler 改动" | 实际仅 4 个 handler（images/videos/media/system） | 改为 4 个 |
| 3 | `system.go::SystemThumbnail` 调用 `GenerateThumbnail` | 实际调用 `GenerateSystemThumbnail`（存储在 `system/` 子目录） | 新增 `GenerateSystemThumbnailBytes` 方法说明 |
| 4 | `streaming_test.go` 仅 78 行 | 实际 79 行 | 行数已修正 |
| 5 | Range 测试声称应用 `httptest.NewServer` | 现有代码用 `httptest.NewRecorder` + `svc.ServeFile` | 建议保持现有风格一致性 |

### 11.2 设计建议（非阻塞）

1. **`GenerateSystemThumbnailBytes` 与 `GenerateThumbnailBytes` 可提取公共逻辑**：两者仅磁盘路径不同，可用内部 helper `generateBytesVia(genFunc, sourcePath)` 减少重复。
2. **LRU 缓存可考虑按字节容量而非条目数限制**：200 项 ~20MB 是粗略估算（假设平均 100KB），但视频缩略图（ffmpeg 截帧）可能远大于图片缩略图。`hashicorp/golang-lru/v2` 不支持 size-aware eviction，如需精确控制可考虑 `dgraph-io/ristretto` 或自行计数。当前方案可接受，但应在 §8 已知限制中注明。
3. **`encoding/hex` 依赖差异**：伪代码用 `hex.EncodeToString(h[:])`，实际代码用 `fmt.Sprintf("%x", h)`。二者输出等价，但建议统一风格。已在修正的伪代码中改为 `fmt.Sprintf` 与现有代码一致。
4. **pprof `DefaultServeMux` 安全考量**：`net/http/pprof` 的 `init()` 会注册到 `http.DefaultServeMux`。如果代码中其他地方也意外使用了 `DefaultServeMux`（如 `http.ListenAndServe("", nil)`），可能会暴露 pprof 端点到未受保护的路由。建议实现时确认无其他 `DefaultServeMux` 使用。
