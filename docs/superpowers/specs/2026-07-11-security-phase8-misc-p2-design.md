# Security Round 29 — Phase 8: Misc P2 Fixes Design

> **日期**：2026-07-11
> **范围**：服务端杂项 P2 修复（rate limit / ffmpeg context / blocked root / 错误脱敏）
> **威胁模型**：局域网半可信（继承 Round 29 主 spec）
> **依赖**：Phase 1（Bearer Token 鉴权）、Phase 3（config 默认安全）
> **审计轮次**：Round 29 Phase 8
> **源 spec**：`docs/superpowers/specs/2026-07-10-security-audit-design.md`（section 5.8）

---

## 0. 摘要

Phase 8 落实主 spec 第 5.8 节的"杂项 P2"修复，把 Round 29 主 spec 标记为 Phase 8 的 7 个发现一次扫干净：

- **T1-06（CVSS 3.7 Low）**：无 rate limit → 单 IP 无限并发连接
- **T3-04b（CVSS 3.7 Low）**：任意客户端可触发 `/admin/scan/trigger` 全盘扫描（耗尽 IO/CPU）
- **T5-02（CVSS 3.7 Low）**：`serveTranscoded` 无 timeout（客户端断连后 ffmpeg 仍运行）
- **T8-01（CVSS 6.1 Medium）**：PUT `/admin/config` 接受 `C:\Windows` 等敏感路径作为 root
- **T8-11（CVSS Low）**：`path.go:174` `validateMediaFilePath` 错误含路径泄漏

**核心改动**（按澄清问题决策）：
1. **Task 1**：`UpdateConfig` 加 blocked root 校验（复用 `service.blockedSegments`）+ `validateMediaFilePath` 错误分类脱敏
2. **Task 2**：新建 `RateLimit` 中间件 + 挂到 `/admin/scan/trigger`（2 req/30s）+ `/system/delete`（5 req/min）
3. **Task 3**：`serveTranscoded` 用 `exec.CommandContext` + `r.Context()` 实现客户端断连自动清理 ffmpeg

**Why now**：Phase 1/3/4/7 已堵住"高危链"（Chain-B/D/I/J 等）。Phase 8 是收尾——把 7 个 P2/P2 发现扫干净，让后续 Phase 2/6 聚焦。每个修复都是小动作（10-30 行），但合起来显著提升整体安全姿态。

---

## 1. 范围与方法论

### 1.1 范围

仅服务端 Go 代码。**不含**：
- T3-01c（blockedSegments 扩充 `programdata`/`inetpub`）——与 T8-01 同源，本 spec 一并处理
- T3-04a（缩略图 fork rate limit）——Task 2 的中间件可被未来复用，但本 spec 不挂载到缩略图（避免影响 UX）
- T8-03（三套媒体端点策略重复）——架构问题，留给未来重构
- T1-02c（删除无审计日志）——可独立 follow-up

### 1.2 方法论

继承 Round 29 主 spec 流程。

### 1.3 探索阶段已确认的事实

- `streaming.go:133-184` `serveTranscoded` 当前用 `exec.Command(ffmpegCmd, args...)`（无 context）。
- `service/path.go:22-32` `blockedSegments` 已定义 9 个敏感段（`windows`/`system32`/`program files` 等），但只在 browse/media/delete 路径上检查；`UpdateConfig` 不检查。
- `service/path.go:171-187` `validateMediaFilePath` 用 `fmt.Errorf("path not accessible: %w", err)`——`os.Stat` 错误形如 `open C:\Users\foo\secret: Access is denied`，含路径。
- `handler/admin.go:19-49` `UpdateConfig` 已有 Phase 3 的 `Validate(false)`，但只校验"roots 非空"，未校验"root 是否敏感目录"。
- `server.go:registerRoutes` 中 `/admin/scan/trigger`（`admin.POST`）和 `/system/delete`（`sys.POST`）当前只有 authMw，无 rate limit。
- Echo v4 路由 API 支持 per-route middleware：`admin.POST(path, handler, middlewares...)`。

---

## 2. 威胁与缓解对照

| 发现 ID | CVSS | 描述 | Phase 8 缓解 |
|---|---|---|---|
| **T8-01** | 6.1 Medium | PUT 接受 `C:\Windows` 作为 root | `UpdateConfig` 调 `service.IsBlockedRoot` 拒绝 |
| **T1-06** | 3.7 Low | 无 rate limit | `RateLimit` 中间件（仅敏感端点） |
| **T3-04b** | 3.7 Low | scan trigger 无限制 | `/admin/scan/trigger` 2 req/30s |
| **T5-02** | 3.7 Low | 转码无 timeout | `exec.CommandContext` + `r.Context()` |
| **T8-11** | Low | path 错误含路径 | 分类脱敏（not found / permission denied / 兜底） |
| **T3-01c** | Low（同源） | blockedSegments 不全 | 通过 Task 1 的 IsBlockedRoot 校验，用户配 `C:\ProgramData` 等时也会被拒（部分缓解；browse 时的段列表扩充留给未来） |

**攻击链影响**：
- **Chain-L**（CVSS 5.4 Medium）：T3-04b scan trigger DoS。Phase 8 限流后，单客户端 30s 内最多 2 次触发，配合 Phase 1 token 鉴权，攻击面显著收敛。

---

## 3. 设计决策

### 3.1 已确认决策（来自 brainstorming）

| 决策 | 选择 | 理由 |
|---|---|---|
| Rate limit 策略 | **仅敏感端点**（scan trigger 2/30s，delete 5/min） | Phase 1 token 已挡匿名洪水；rate limit 防误操作/脚本失控 |
| ffmpeg timeout 策略 | **客户端断连检测**（无硬时长） | 长视频不被切断；`exec.CommandContext` + `r.Context()` 简单可靠 |
| UpdateConfig blocked 校验 | **复用 `service.blockedSegments`，任意一段命中就拒绝（A1）** | 单一数据源，与 browse/media/delete 行为一致 |
| 错误脱敏策略 | **分类脱敏**（`os.IsNotExist` / `os.IsPermission` / 兜底） | 保留诊断价值，不引入新依赖 |
| Echo per-route middleware | **合法**（`admin.POST(path, h, mw...)`） | Echo v4 标准 API |
| rate limit IP 来源 | **`c.RealIP()`** | LAN 无 reverse proxy，RealIP = 直连 IP |
| Task 3 测试 | **依赖真实 ffmpeg**；CI 无则 `t.Skip` | 测真实行为最可靠 |

### 3.2 兼容性

| 现状 | Phase 8 后行为 | 破坏性 |
|---|---|---|
| 用户配 `C:\Users\me\Pictures` 作为 root | 正常（无敏感段命中） | 无 |
| 用户配 `C:\Windows` / `C:\Program Files` 作为 root | **400 拒绝**，错误消息提示 | 有（但属于"误配置纠正"，非破坏） |
| 任意客户端 spam scan trigger | 30s 内最多 2 次，第 3 次 429 | 无（正常使用不会触发） |
| 任意客户端 spam delete | 1 分钟内最多 5 次，第 6 次 429 | 无（正常使用不会触发） |
| 客户端关闭 tab 后 ffmpeg 进程 | **自动清理**（之前可能残留） | 无（改进） |
| 错误响应 body | 不再含路径，仅分类消息 | 无（改进） |

---

## 4. 修改清单

### 4.1 服务端代码

| 文件 | 改动 | Task |
|---|---|---|
| `server/internal/service/path.go` | 导出 `IsBlockedRoot`；`containsBlockedSegment` 重构为调 `IsBlockedRoot`；`validateMediaFilePath` 错误分类脱敏 | 1 |
| `server/internal/service/path_test.go` | 加 `TestIsBlockedRoot` + `TestValidateMediaFilePathErrorClassification` | 1 |
| `server/internal/server/handler/admin.go` | `UpdateConfig` 加 `IsBlockedRoot` 校验 | 1 |
| `server/internal/server/handler/admin_test.go` | 加 `TestUpdateConfigRejectsBlockedRoot` | 1 |
| `server/internal/server/middleware/ratelimit.go` | 新建 `RateLimit(max, window)` | 2 |
| `server/internal/server/middleware/ratelimit_test.go` | 单元测试：max + 1 返回 429，窗口重置 | 2 |
| `server/internal/server/server.go` | `/admin/scan/trigger` + `/system/delete` 挂 rate limit | 2 |
| `server/internal/service/streaming.go` | `serveTranscoded` 改用 `exec.CommandContext` | 3 |
| `server/internal/service/streaming_test.go` | 加客户端断连测试 | 3 |

---

## 5. 实施细节

### 5.1 Task 1：blocked root 校验 + 错误脱敏

#### 5.1.1 `service/path.go` — 导出 IsBlockedRoot + 重构 containsBlockedSegment

```go
// IsBlockedRoot reports whether any segment of absPath matches the blocked list.
// Exported so handler/admin.go can validate user-supplied scan roots (T8-01)
// without letting C:\Windows / D:\Program Files etc. become roots.
//
// Semantics: case-insensitive, whole-segment match — same as the internal
// containsBlockedSegment() used by browse/media/delete paths.
func IsBlockedRoot(absPath string) bool {
	for _, seg := range strings.Split(strings.ToLower(absPath), string(filepath.Separator)) {
		for _, blocked := range blockedSegments {
			if seg == blocked {
				return true
			}
		}
	}
	return false
}

// containsBlockedSegment now delegates to IsBlockedRoot (DRY — single source).
func containsBlockedSegment(absPath string) error {
	if IsBlockedRoot(absPath) {
		return fmt.Errorf("access denied: restricted directory")
	}
	return nil
}
```

#### 5.1.2 `service/path.go` — validateMediaFilePath 错误分类脱敏

```go
func validateMediaFilePath(absPath string, allowedExtensions []string) error {
	info, err := os.Stat(absPath)
	if err != nil {
		// Phase 8 T8-11: classify error without leaking the path.
		if os.IsNotExist(err) {
			return fmt.Errorf("file not found")
		}
		if os.IsPermission(err) {
			return fmt.Errorf("permission denied")
		}
		return fmt.Errorf("path not accessible")
	}
	if info.IsDir() {
		return fmt.Errorf("access denied: not a file")
	}

	ext := strings.ToLower(filepath.Ext(absPath))
	for _, allowedExt := range allowedExtensions {
		if strings.EqualFold(ext, allowedExt) {
			return nil
		}
	}
	return fmt.Errorf("access denied: file type not allowed")
}
```

#### 5.1.3 `handler/admin.go UpdateConfig` 加 blocked root 校验

在现有 `for _, r := range req.Roots` 循环（lines 27-31）中加：

```go
for _, r := range req.Roots {
    if !filepath.IsAbs(r) {
        return respondError(c, http.StatusBadRequest, "scan roots must be absolute paths")
    }
    // Phase 8 T8-01: reject sensitive system directories as roots.
    if service.IsBlockedRoot(r) {
        return respondError(c, http.StatusBadRequest,
            fmt.Sprintf("scan root %q matches a restricted system directory", r))
    }
}
```

确认 `handler/admin.go` 已 import `service`（探索阶段确认是，因 `h.scanner` 来自 service）。

### 5.2 Task 2：RateLimit 中间件 + 挂载

#### 5.2.1 `middleware/ratelimit.go`

```go
package middleware

import (
	"net/http"
	"sync"
	"time"

	"github.com/labstack/echo/v4"
)

// RateLimit returns a middleware that allows at most `max` requests per `window`
// per client IP. Requests over the limit get 429 Too Many Requests with a JSON
// error body matching the project's standard error envelope.
//
// Implementation: in-memory map[string]*bucket guarded by sync.Mutex. The
// bucket counter resets when the window elapses. Not distributed — sufficient
// for single-process LAN deployment. Memory grows with distinct client IPs,
// which is bounded by LAN size.
//
// Use case: per-route rate limiting on sensitive endpoints (scan trigger,
// delete) to prevent accidental or malicious resource exhaustion. Does NOT
// apply globally — media streaming endpoints (videos, system/stream) must not
// be rate-limited or normal playback breaks.
func RateLimit(max int, window time.Duration) echo.MiddlewareFunc {
	type bucket struct {
		count   int
		resetAt time.Time
	}
	var (
		mu      sync.Mutex
		buckets = make(map[string]*bucket)
	)
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			ip := c.RealIP()
			mu.Lock()
			now := time.Now()
			b, ok := buckets[ip]
			if !ok || now.After(b.resetAt) {
				buckets[ip] = &bucket{count: 1, resetAt: now.Add(window)}
				mu.Unlock()
				return next(c)
			}
			if b.count >= max {
				mu.Unlock()
				return c.JSON(http.StatusTooManyRequests,
					map[string]string{"error": "rate limit exceeded"})
			}
			b.count++
			mu.Unlock()
			return next(c)
		}
	}
}
```

#### 5.2.2 `server.go` 挂载

`registerRoutes` 中改 scan trigger + delete 路由：

```go
// Before:
admin.POST("/scan/trigger", h.TriggerScan)
sys.POST("/delete", h.DeletePath)

// After:
admin.POST("/scan/trigger", h.TriggerScan, middleware.RateLimit(2, 30*time.Second))
sys.POST("/delete", h.DeletePath, middleware.RateLimit(5, time.Minute))
```

需 `import "time"`（server.go 是否已 import 需验证——line 11 已有）。

### 5.3 Task 3：ffmpeg 客户端断连检测

#### 5.3.1 `streaming.go serveTranscoded`

```go
// Before (lines 177-184):
cmd := exec.Command(ffmpegCmd, args...)
stdout, err := cmd.StdoutPipe()
// ...

// After:
ctx, cancel := context.WithCancel(r.Context())
defer cancel()

cmd := exec.CommandContext(ctx, ffmpegCmd, args...)
stdout, err := cmd.StdoutPipe()
// ...
```

需 `import "context"`（streaming.go 是否已 import 需验证——line 7 有 `"context"`，确认）。

#### 5.3.2 Windows 进程清理

Go 1.20+ 的 `exec.CommandContext` 在 ctx cancel 时默认发 `CTRL_BREAK_EVENT`，但 ffmpeg 可能不响应。**保守做法**：显式设置 `cmd.Cancel`：

```go
cmd := exec.CommandContext(ctx, ffmpegCmd, args...)
// Windows ffmpeg subprocess may not respond to CTRL_BREAK_EVENT. Force kill.
cmd.Cancel = func() error {
    if cmd.Process != nil {
        return cmd.Process.Kill()
    }
    return os.ErrProcessDone
}
```

需 `import "os"`（streaming.go 已 import）。

---

## 6. 测试方案

### 6.1 单元测试

| Task | 测试 | 文件 |
|---|---|---|
| 1 | `TestIsBlockedRoot`（`C:\Windows` / `D:\Media\backup\system32` / `E:\Photos` 等多场景）；`TestValidateMediaFilePathErrorClassification`（不存在 / 权限拒绝 / 兜底）；`TestUpdateConfigRejectsBlockedRoot`（admin handler 集成） | `service/path_test.go` + `handler/admin_test.go` |
| 2 | `TestRateLimit`（窗口内 max 次允许 + max+1 次 429 + 窗口重置 + 多 IP 独立） | `middleware/ratelimit_test.go` |
| 3 | `TestServeTranscodedClientDisconnect`（启动 ffmpeg → cancel ctx → 验证进程退出） | `service/streaming_test.go` |

### 6.2 集成测试（手动）

| 测试 | 命令 |
|---|---|
| scan trigger rate limit | `for i in 1..5; do curl -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8000/api/v1/admin/scan/trigger; done` → 第 3 次起 429 |
| delete rate limit | 同上模式，1 分钟内 6 次 delete → 第 6 次 429 |
| UpdateConfig 拒绝 `C:\Windows` | `curl -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"roots":["C:\\Windows"]}' http://localhost:8000/api/v1/admin/config` → 400 |
| 客户端断连清理 ffmpeg | 启动长视频转码 → 关闭 tab → `tasklist \| grep ffmpeg` → 进程应在数秒内消失 |

### 6.3 回归测试

- `cd server && go test ./...` 全 green
- Phase 1/3/4 测试无回归（特别注意 Phase 3 的 `TestConfigValidate` 不能因 blocked root 校验破坏）

---

## 7. 风险与权衡

| 风险 | 影响 | 缓解 |
|---|---|---|
| `service.IsBlockedRoot` 跨包调用循环依赖 | 低 | `handler` 已 import `service`（无循环） |
| RateLimit in-memory map 无清理 goroutine | 低 | LAN IP 数量有限；YAGNI |
| Windows ffmpeg 进程不响应 ctx cancel | 中 | 显式 `cmd.Cancel = func() { cmd.Process.Kill() }` |
| Task 3 测试依赖 ffmpeg | 低 | `t.Skip` 若 ffmpeg 不在 PATH |
| blocked root 校验拒绝用户已有 config | 低 | Phase 8 探索未发现用户的 roots 含敏感目录（仅 `manga-translator-ui\output` + `IDM_Download\Video`） |

---

## 8. 验证完成标准

- ✅ `TestIsBlockedRoot` + `TestValidateMediaFilePathErrorClassification` + `TestUpdateConfigRejectsBlockedRoot` 通过
- ✅ `TestRateLimit` 通过（窗口 + 多 IP + 重置）
- ✅ `TestServeTranscodedClientDisconnect` 通过（或 ffmpeg 缺失时 skip）
- ✅ 手动：scan trigger 第 3 次 429、delete 第 6 次 429
- ✅ 手动：PUT `{"roots":["C:\\Windows"]}` → 400
- ✅ 手动：客户端断连后 `tasklist` 无残留 ffmpeg
- ✅ `cd server && go test ./...` 全 green
- ✅ Phase 1/3/4 测试无回归

---

## 9. 后续 Phase 衔接

| Phase | 内容 | 备注 |
|---|---|---|
| Phase 2 | libffmpeg.so 审计 + SBOM | 阻断 Chain-D（唯一 RCE 链） |
| Phase 5 | Web UI XSS 整改 + 移除 CSP `'unsafe-inline'` | 与 Phase 4 协同 |
| Phase 6 | 供应链扫描 + 依赖升级 | 独立 |

---

## 文档信息

- **创建日期**：2026-07-11
- **审计轮次**：Round 29 Phase 8
- **依赖**：Phase 1（commit `76b6d51`）、Phase 3（commit `36f9c9a`）
- **方法论**：brainstorming skill（澄清 → 方案 → 设计）
- **下一步**：经用户审核后，调用 writing-plans skill 转为实施计划
