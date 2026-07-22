# Round 32：性能 + 安全混合轮 设计文档

**日期**：2026-07-23
**范围**：server Go 代码、Web SPA JS 代码、config schema、cmd flag
**不在范围**：Android 端（本轮不涉及）、HTTPS/TLS、视频 streaming buffer、Web SPA HTTP/2

## 背景

Round 29（安全加固 8 阶段）、Round 30（死代码审计）、Round 31（性能优化 A1-A3 + B1-B3）完成后，对当前代码库做整体扫描，识别出 11 个**尚未**优化过的性能/安全候选项。本 spec 挑选其中 7 项高 ROI 的组成 Round 32，分两个 phase 实施。

## 总体结构

```
Phase P (Performance) — 三项互相独立，可并行
  P1  Thumbnail 冷启动加速（hot 目录内存 LRU + 5min flush）
  P2  SQLite WAL + busy_timeout + 单写多读连接池
  P3  Scanner 并发遍历（per-root goroutine + worker pool，输出有序）

Phase S (Security) — 四项互相独立
  S1  RateLimit 加 LRU + 容量上限（默认 4096）
  S2  Books 图片签名 token（HMAC + client IP + path + manifestID 绑定，无 expire）
       + access log redact + query token 标记 deprecated
  S3  /debug/pprof 默认关闭（flag --debug-pprof + config debug.pprof，flag 覆盖 config）
  S4  Web SPA XSS 审计 + 修复 + lint 规则（一次性闭环）

测试：以单元测试为主（详见各节）
```

P 与 S 之间无依赖；同 phase 内各项亦无相互阻塞。

---

## Phase P — 性能

### P1. Thumbnail 冷启动加速

**问题**：扫描完成 → `PreGenerateThumbnails(files, ctx, hotPaths)` 全量预热。大库（>10K 文件）首次启动占满 CPU 数分钟。

**设计**：

- **Hot 目录统计源**：每次 `MediaThumbnail` / `SystemThumbnail` 访问（在 `generateBytesVia` 中根据 `filepath.Dir(sourcePath)` 自动触发） → `ThumbnailService.RecordHotAccess(dirPath)` → 内存 LRU（容量 256，按目录粒度）+ 计数器。
- **分层预热**：
  - Tier 1（优先）：hot 目录内的文件（从 LRU 取 top-N 目录）。
  - Tier 2（次优）：每个 scan root 的第一层文件（用户最可能先浏览的入口）。
  - Tier 3（兜底）：不预热，懒生成（首次访问时按需生成）。
  - `PreGenerateThumbnails` 签名调整为 `(files []models.MediaFile, ctx context.Context, hotDirs map[string]struct{}, scanRoots []string)`。
- **LRU 持久化**：
  - 内存 cache 启动时从 `filepath.Join(s.cacheDir, "hot_directories.json")` 读取（作为首次扫描的 hot 种子，与 `durations.json` 保持同一目录）。
  - 运行中累积；每 5 分钟定时 flush；`Shutdown` 时强制 flush。
  - flush 用 atomic write（写 `.tmp` 后 `os.Rename`），同 `durations.json` 风格。
  - 持久化只保留 top-256（按计数排序）。

**关键点**：Tier 3 改为懒生成意味着首次浏览冷目录仍会有首次加载延迟（与现状相同），但不再阻塞 hot 目录的预热。

**单元测试**：
- `RecordHotAccess` 后 LRU 顺序正确。
- 容量满时 LRU 淘汰最旧。
- flush 写入文件可被重新加载且 top-256 截断正确。
- 分层预热：mock 文件集，验证 Tier 1 先于 Tier 2 完成调度。

---

### P2. SQLite WAL + busy_timeout + 连接池

**问题**：`NewTagsService` 打开单连接，无 PRAGMA。并发读写互相阻塞；`journal_mode` 默认 `DELETE`。

**设计**：

```go
dsn := "file:.data/tags.db?_pragma=journal_mode(WAL)"
                   +"&_pragma=busy_timeout(5000)"
                   +"&_pragma=synchronous(NORMAL)"
                   +"&_pragma=foreign_keys(ON)"
db, _ := sql.Open("sqlite", dsn)

// 单写多读：WAL 允许并发读 + 串行写
db.SetMaxOpenConns(max(4, runtime.NumCPU()))
db.SetMaxIdleConns(2)
db.SetConnMaxLifetime(0)  // 长连接，避免频繁重连
```

**锁优化与并发取舍**：
- **`TagsService.mu` 锁粒度重构**：现有的 `CreateTag`/`DeleteTag` 等写方法使用了 Go 级别的 `s.mu.Lock()`，这会导致 Go 协程在写操作期间阻塞并发读操作 `s.mu.RLock()`。改动为所有 CRUD 方法统一下发 `s.mu.RLock()` 访问 `s.db`，由 SQLite WAL 机制 + `busy_timeout(5000)` 在数据库层原生管理写串行与读快照隔离；`s.mu.Lock()` 仅留给 `Close()` 销毁 `s.db` 时使用。
- `synchronous=NORMAL`（非 FULL）：崩溃时可能丢最后 1 个事务，但 WAL 模式下不会损坏 DB。tags 数据非关键，可接受。
- `busy_timeout=5000ms`：并发写冲突时等 5s 而非立即报错。

**错误处理**：PRAGMA 设置失败 → fatal log + 退出（fail-fast，不静默运行）；`busy_timeout` 超时 → 返回 503 + JSON error，client 可重试。

**单元测试**：
- 并发 N goroutine 同时写 tag，无 `SQLITE_BUSY` 错误。
- 并发读 + 写不互相阻塞（读走 WAL snapshot）。
- PRAGMA 验证：连接建立后 `journal_mode` 返回 `wal`。

---

### P3. Scanner 并发遍历

**问题**：`filepath.Walk` 串行遍历所有 root，大目录首次索引慢。

**设计**：

- **per-root goroutine**：每个 `cfg.Scan.Roots` 一个 goroutine；并发上限 = `g.SetLimit(min(len(roots), runtime.NumCPU()))`。
- **每个 goroutine 内部**：`filepath.WalkDir` 遍历（比 `filepath.Walk` 少一次 lstat）；每个文件的扩展名判断 + Stat 调用丢给 worker pool。
- **worker pool**：大小 `runtime.NumCPU()`；处理"判断扩展名是否匹配 + Stat 取大小/时长"。
- **结果合并**：所有 root 完成后合并 → 按路径字典序排序输出。
- **fsnotify 防抖**：不变（Round 31 A3 的 per-root debounce 保留）。

**错误处理**：worker panic 由 per-root goroutine 的 recover 捕获，记录错误，继续其余文件；context cancel 时所有 goroutine 在 1s 内退出，`Scanner.Shutdown` 等待完成。

**关键约束**：输出排序保证外部行为不变（测试无需改、下游消费方无感知）。

**单元测试**：
- 并发遍历输出与串行遍历输出（同输入）按路径排序后**完全一致**。
- 多 root 场景下文件不丢、不重。
- context cancel 时所有 goroutine 在 1s 内退出。

---

## Phase S — 安全

### S1. RateLimit LRU + 容量上限

**问题**：`buckets map[string]*bucket` 无上限。LAN 场景影响小，但伪造 `X-Forwarded-For` 即可触发内存膨胀。

**设计**：

```go
type bucket struct {
    count    int
    resetAt  time.Time
    lastSeen time.Time  // 新增，用于 LRU 淘汰
}

const maxBuckets = 4096  // 默认，可配置

// 每次访问更新 lastSeen
// 当 len(buckets) > maxBuckets 时，淘汰 lastSeen 最旧的条目
// （O(n) 扫描淘汰——4096 规模可接受；不用 heap，避免复杂度）
```

**API**：新增 `RateLimitWithConfig(max, window, maxBuckets)`；保留原 `RateLimit(max, window)` 作为 wrapper（默认 4096）。

**单元测试**：
- 容量满后新 IP 挤掉最旧 IP。
- 已有 IP 重复访问不会触发淘汰。
- 并发安全（N goroutine 同时进入中间件，无 race；`-race` 运行验证）。

---

### S2. Books 图片签名 token

**问题**：`<img src="/api/v1/books/image?path=...&manifest=...&token=BEARER_TOKEN">`——bearer token 明文落 URL/日志/历史。

**设计**：

- **签名公式**：`sig = base64url(HMAC-SHA256(serverSecret, clientIP + "|" + path + "|" + manifestID))`。
  - `serverSecret` 启动时生成（随机 32 字节，进程重启后旧 sig 失效）。
  - `clientIP` 来源：`c.RealIP()`（签名与校验必须用同一来源）。
  - `path` 为 epub 路径，`manifestID` 为 epub 内部图片 ID（非 epub 资源时为空）。防止跨资源复用 sig。
  - 无 expire（按用户决策）。
- **新 endpoint**：`GET /api/v1/books/sign-image?path=...&manifest=...` → 返回 `{"src": "/api/v1/books/image?path=...&manifest=...&sig=<hmac>"}`。供 Web SPA 动态场景（如 lightbox）使用。
- **`/api/v1/books/image` 改造**：同时支持 `?sig=` 和 `?token=`。
  - `?sig=` 路径：重算期望 sig，`crypto/subtle.ConstantTimeCompare` 比较；匹配 → 返回图片，不匹配 → 401。
  - `?token=` 路径：保留（BearerToken 中间件已校验），日志加 `[DEPRECATED]` 前缀警告。计划下一轮移除。
- **GetBookChapter 渲染**：`BookService.GetChapterBlocks(path string, idx int, clientIP string)` 调整方法签名，服务端直接根据 `clientIP` 预生成每个 `<img>` 的 signed src（内联到章节 JSON）。客户端无需额外请求 `sign-image`。
- **Bearer Token 仍需**：`/books/sign-image` 和 `/books/image` 都挂在 `authMw` 下——双保险。
- **access log redact**：`echoMw.LoggerWithConfig` 加 `LogValuesFunc` 或 URI 拦截，对所有路径将 `?token=XXX` 替换为 `?token=REDACTED`。`?sig=` 不需 redact（无 bearer token，且绑定 IP）。

**错误处理**：sig 验证失败 → 401（与现有 BearerToken 风格一致）；client IP 切换 → sig 失效 → 客户端重新请求 chapter（Web SPA 自动重试）；server 重启 → serverSecret 重生成 → 旧 sig 全部失效 → 客户端重新请求 chapter。

**单元测试**：
- 同 IP + 同 path + 同 manifestID 生成相同 sig。
- 不同 IP 或不同 manifestID 生成不同 sig。
- sig 验证 constant-time 比较。
- 旧 `?token=` 路径仍工作（向后兼容）。
- log redact：模拟带 token 的请求，验证日志输出。

---

### S3. /debug/pprof 默认关闭

**问题**：`/debug/pprof` 虽限制私网，但默认开启。生产部署意外暴露公网会泄漏 heap/goroutine。

**设计**：

```yaml
# config.yaml 新增
debug:
  pprof: false  # 默认 false
```

```go
// internal/config/config.go Config 结构体新增
type Config struct {
    ...
    Debug DebugConfig `yaml:"debug,omitempty" json:"debug,omitempty"`
}

type DebugConfig struct {
    Pprof bool `yaml:"pprof,omitempty" json:"pprof,omitempty"`
}
```

```go
// cmd/server flag 新增
--debug-pprof  // bool，覆盖 config（flag > config）
```

```go
// server.New() / registerRoutes() 中
if s.Config.Debug.Pprof {
    pprofGroup := s.Echo.Group("/debug/pprof", middleware.PrivateNetOnly())
    pprofGroup.Any("/*", echo.WrapHandler(http.DefaultServeMux))
}
// else：不注册路由（访问返回 404）
```

**关键点**：`net/http/pprof` 包仍被 import（保持 `PrivateNetOnly` 守护不退化），但路由默认不挂载 = 即使私网访问默认也是 404。

**错误处理**：flag 解析失败 → server 启动 fatal。

**单元测试**：
- 默认配置下 `GET /debug/pprof/` 返回 404。
- `debug.pprof=true` 时返回 200。
- flag=true + config=false 时 flag 生效。

---

### S4. Web SPA XSS 审计 + 修复 + lint

**问题**：Round 29 phase 5 的 XSS lint 工具是 server 端静态扫描——未必覆盖 Web 端 `innerHTML` 类操作。

**设计**：

- **审计范围**：所有 `server/internal/web/*.js` 中的 DOM 写入 sink：
  - `element.innerHTML = ...`
  - `element.outerHTML = ...`
  - `element.insertAdjacentHTML(...)`
  - `document.write(...)`
  - `$().html(...)`（若有 jQuery 类库）
- **审计输出**：每个 sink 记录：
  - 文件:行号
  - 数据源（用户输入 / 服务端 JSON / 硬编码）
  - 当前是否 escape
  - 风险等级（高/中/低）
- **修复策略**：
  - 高危（用户输入直接拼）：改为 `textContent` 或 DOM API 构造节点。
  - 中危（服务端 JSON 字段）：加 escape 函数（HTML entity 编码）。
  - 低危（硬编码）：加 `// XSS-SAFE:` 注释说明。
- **lint 规则**：扩展现有 XSS lint 工具 `tools/xsscheck`（Round 29 phase 5）：
  - 正则扫描 `.innerHTML =`, `.outerHTML =`, `.insertAdjacentHTML(`, `document.write(`, `$().html(`.
  - 对每个 sink 要求同行或上一行有 `// XSS-SAFE:` 注释解释为何安全或调用了 `escapeHtml()`。
  - 缺注释/未转义 → lint 失败。

**单元测试**：
- escape 函数：`<script>` → `&lt;script&gt;`。
- lint 工具：mock 含 sink 的 JS 文件，正确识别 + 报错（缺 `// XSS-SAFE:` 注释）。

**风险说明**：审计阶段若发现 5+ 高危漏洞，spec 范围会显著扩大；届时按发现量决定是否拆分下一轮（但本轮目标是闭环）。

---

## 数据流（核心场景）

```
[冷启动]
  Scanner.StartWatching
    → per-root goroutine 并发 WalkDir (P3)
    → worker pool 判断扩展名 + Stat
    → 合并 + 按路径排序
    → OnScanComplete
      → 读 filepath.Join(cacheDir, "hot_directories.json") (P1 启动种子)
      → PreGenerateThumbnails 分层预热 (Tier1 hot → Tier2 根 → Tier3 懒)
  TagsService 初始化 → WAL + 连接池 + s.mu.RLock (P2)

[用户浏览图片]
  GET /api/v1/books/chapter?path=book.epub
    → BookService 解析章节 (传入 clientIP)
    → 每个 <img> 的 src 服务端预签名: sig = HMAC(IP + path + manifest) (S2)
    → 返回章节 JSON，img src 已含 sig
  GET /api/v1/books/image?path=...&manifest=...&sig=...
    → 重算 sig，constant-time 比较 (S2)
    → 命中 → ThumbnailService 命中 → RecordHotAccess(dir) 更新 LRU (P1)
    → 5min 后台 flush LRU → filepath.Join(cacheDir, "hot_directories.json")

[高频打 rate limit]
  每个请求进 RateLimit 中间件
    → buckets[lastSeen] 更新
    → 容量满 → 淘汰最旧 (S1)

[访问 /debug/pprof]
  默认 config.debug.pprof=false → 路由未注册 → 404 (S3)
```

## 错误处理汇总

| 场景 | 处理 |
|---|---|
| P1 hot 文件读取失败 | 跳过，不阻塞预热；记录 logger.Warn |
| P1 flush 文件锁竞争 | atomic write（写 `.tmp` 后 `os.Rename`） |
| P2 WAL 切换失败 | fatal log + 退出（fail-fast） |
| P2 busy_timeout 超时 | 返回 503 + JSON error，client 可重试 |
| P3 worker panic | per-root goroutine recover，继续其余文件 |
| P3 context cancel | 所有 goroutine 1s 内退出 |
| S1 LRU 淘汰并发竞争 | mu.Lock 保护，LRU 扫描在锁内 |
| S2 sig 验证失败 | 401 Unauthorized |
| S2 client IP 切换 | sig 失效 → 客户端重新请求 chapter |
| S2 server 重启 | serverSecret 重生成 → 旧 sig 全部失效 |
| S3 flag 解析失败 | server 启动 fatal |
| S4 lint 发现未注释 sink | pre-commit / CI 失败，要求补注释 |

## 测试策略（单元为主）

| 改动 | 单元测试 | 备注 |
|---|---|---|
| P1 LRU | 记录/淘汰/容量 | 4 个用例 |
| P1 flush | 写入/读取/top-N 截断 | 3 个用例 |
| P1 分层预热 | Tier 顺序 | mock ThumbnailService |
| P2 PRAGMA | journal_mode=wal | 启动后查询验证 |
| P2 并发写 | N goroutine 无 SQLITE_BUSY | 用 t.Parallel |
| P2 读写并发 | 读不阻塞写 | 用 sync.WaitGroup |
| P3 并发有序 | 与串行输出一致 | 2 root × 50 文件 |
| P3 context cancel | 1s 内退出 | ctx.WithTimeout |
| S1 LRU | 满容量淘汰 + race | -race 运行 |
| S2 sig | 生成/校验/IP 绑定 | 5 个用例 |
| S2 deprecation | 旧 token 仍工作 | |
| S2 log redact | token 被替换 | echo LoggerWithConfig mock |
| S3 开关 | 默认关闭 / flag / config | 3 个用例 |
| S4 escape | `<script>` 转义 | |
| S4 lint | sink 识别 + 报错 | mock JS 文件 |

**验证标准**：`go test ./... -race -count=1` 全绿；Android `./gradlew testDebugUnitTest` 不受影响（本轮不涉及 Android）。

## 范围之外（明确不做）

- HTTPS / TLS 配置（独立大改，下一轮）。
- 视频 streaming buffer 调优（ROI 低）。
- Web SPA HTTP/2 push / preload（需要 server 升级，下一轮）。
- 全局 rate limit（会破坏 media streaming，明确排除）。
- `?token=` 路径完全移除（本轮仅 deprecate，下一轮移除）。

## 实施顺序建议（供 writing-plans 参考）

1. **P2（SQLite WAL）** 最先做——基础设施，影响面最小，测试独立。
2. **S3（pprof 开关）** 次之——独立、低风险、快速完成。
3. **S1（RateLimit LRU）**——独立、低风险。
4. **P3（Scanner 并发）**——中等复杂度，需谨慎处理测试。
5. **S2（签名 token）**——涉及客户端/server 协作，需端到端验证。
6. **P1（Thumbnail hot 目录）**——依赖 P3 完成（scanner 输出是 PreGen 输入）。
7. **S4（XSS 审计）**——最后做，审计结果可能影响范围判断。
