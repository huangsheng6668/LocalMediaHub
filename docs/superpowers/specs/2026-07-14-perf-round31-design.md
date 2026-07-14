# 服务端性能优化 Round 31（A1-A3 + B1-B3）

- **日期**: 2026-07-14
- **范围**: Go 服务端（`server/internal/`）—— SQLite 索引、Browse handler 剪枝、增量重扫、缩略图预热、GC 压力优化、JSON gzip
- **策略**: 6 commit 打包（A1-A3 + B1-B3），每项独立可回滚，推荐执行顺序 A1 → A2 → A3 → B1 → B2 → B3
- **状态**: 待评审
- **前置**: Round 28（thumbnail-deep-perf C1+C2+C3 已实施）、Round 15（server-perf 3 项已实施）

---

## 1. 背景与动机

### 1.1 用户痛点（按优先级排序）

| 代号 | 痛点 | 评分 |
|---|---|---|
| P1 | 大视频目录首屏缩略图加载慢 | 高 |
| P2 | 大图目录缩略图生成慢 | 高 |
| P3 | 文件夹/文件搜索响应慢 | 高 |
| P9 | 服务端 CPU/内存占用高 | 高 |
| P4 | 视频起播延迟、seek 卡顿 | 中 |
| P5 | 浏览页进入目录慢 | 中 |
| P6/P7/P8 | 标签/启动/下载 | 低 |

### 1.2 规模假设（用户实测）

- 媒体文件总数：**50k+**
- 单目录最大文件数：**500-2k**
- 典型视频：**4K / 高码率**
- 典型图片：**手机拍照 ~4000px**
- 服务端：现代 SSD + 4 核+
- 客户端：旗舰机

### 1.3 已确认的瓶颈（代码证据）

| 假设 | 证据 | 影响 |
|---|---|---|
| **G1 tags 表无索引、无 PRAGMA** | `tags.go:49-65` CREATE TABLE 后无 `CREATE INDEX`；无 `PRAGMA journal_mode/synchronous/mmap_size`；`foreign_keys=ON` 未设置导致 `ON DELETE CASCADE` 为死代码 | 所有 `WHERE tag_id = ?` / `WHERE file_path = ?` 全表扫；写性能差 5-10x |
| **G2 Browse `/files` 拉全部 cache + 全量过滤** | `folders.go:75-86` `GetCached` 返回 `cache["all"]` 全量 50k 项 → `for file := range files` 逐条 `IsPathWithinRoots(file.Path, [pathStr])` | 50k 文件规模下单次访问目录 ~5ms 全量遍历 |
| **G3 `GetTagsForFiles` 全表 query + 内存 filter** | `tags.go:279-283` `SELECT a.file_path, t.id, t.name, t.color FROM associations a JOIN tags t ON a.tag_id = t.id`（**无 WHERE**）+ Go 内存过滤 `if _, exists := result[filePath]` | 即使只查 1 个文件也全表扫 |
| **G4 fsnotify 触发整根重扫** | `scanner.go:440-496` `watchEvents` 任何事件 → 全局单 timer 防抖 → `TriggerScan(s.watchRoots)` 重扫所有 roots | 单文件编辑触发 50k 文件全树 walk，CPU 100% 30s |
| **G5 PreGenerateThumbnails 无热点优先 + 抢占交互** | `thumbnail.go:336-401` workers `NumCPU/2` 全速消费 + 与交互请求共享 `sem`（`NumCPU` 容量）；已有磁盘缓存跳过（L379-386），但无热点优先排序、无 worker 退让 | 用户首屏缩略图被预热 worker 的 sem 抢占阻塞 |
| **G6 JSON 响应未压缩** | `server.go:100-112` `registerRoutes` 未挂载 gzip middleware | 500KB JSON 响应在 100Mbps LAN 下传输 50ms |

### 1.4 关键洞察

- **A1 ROI 极高**：SQLite 索引 + PRAGMA 是 P3 标签搜索的根本性修复，单 PRAGMA `journal_mode=WAL` 就能让读写并发性能翻倍。启用 `foreign_keys=ON` 还能使已定义的 `ON DELETE CASCADE` 正确生效。
- **A2 直击规模痛点**：`cacheByDir` 把 Browse `/files` 从 O(N) 全量过滤降到 O(1) map lookup，50k 规模下数量级收益。
- **A3 解决 P9**：每 root 独立防抖 + 全 root 重扫；主要收益是事件密集的 root 不阻塞其他 root 的防抖窗口，整体减少无效重扫次数。
- **B1 是 P1 首屏体验的最后一块拼图**：与 A 系列无冲突，可叠加。
- **B2 诚实评估**：经过仔细分析，"并行化"已被 PreGen worker pool 覆盖；B2 真实可做的是 `sync.Pool` 复用 `jpeg.Encode` 输出 buffer 减少 GC 压力。注意：当前代码直接流式写磁盘 `jpeg.Encode(tempFile, ...)`，改为先编码到 pool buffer再写磁盘，会增加约 30-50KB 峰值内存/并发。
- **B3 LAN 友好**：纯 HTTP 项目，gzip middleware 不引入 HTTP/2 风险；ExoPlayer/Coil/OkHttp 默认支持自动解压。

---

## 2. 目标与非目标

### 目标

1. **A1**: SQLite PRAGMA（WAL + NORMAL + mmap + cache + foreign_keys=ON）+ 3 个索引（associations tag_id/file_path、tags LOWER(name)）+ `GetTagsForFiles` 改用 `IN (...)` 批查
2. **A2**: Scanner 新增 `cacheByDir` 字段；`BrowseFolder /files` 改用 `GetCachedByDir` O(1) map lookup
3. **A3**: `watchEvents` 改为“每 root 独立防抖”；防抖 timer fire 后仍扫所有 roots（保证缓存完整性），但独立防抖避免一个 root 的高频事件不断重置其他 root 的防抖窗口
4. **B1**: `PreGenerateThumbnails` 加 `hotPaths` 参数 + hot/cold 双队列排序 + worker 退让；新增 `hotTracker` 跟踪最近交互请求路径。注意：磁盘缓存跳过逻辑已存在（当前 worker 内部 L379-386），B1 将其前移到入队阶段以减少 goroutine 调度开销
5. **B2**: `encodeThumbnailToCache` 用 `sync.Pool` 复用 jpeg.Encode 输出 buffer，减少 GC 压力
6. **B3**: Echo gzip middleware（Level 5），Skipper 排除 `/stream` `/thumbnail` `/original` `/download` 并确保对通配路由正常工作
7. **行为兼容**：API URL 与响应 schema 完全不变；缩略图视觉质量不变；配置文件 schema 不变
8. **每个 commit 含单元测试 + baseline 数据**（4.3-B 约束）

### 非目标

- ❌ HTTP/2 / TLS / QUIC 升级（纯 HTTP LAN 部署，OkHttp 不支持 h2c）
- ❌ scanner `cache["all"]` 精确增量合并（需 badger/bolt，过度工程）
- ❌ `cacheByDir` 路径大小写不敏感修复（与现有 `IsPathWithinRoots` 同语义，留后续轮次）
- ❌ Android 端任何改动（C 方案推迟到下轮）
- ❌ `parallelEncode` 在 worker 内二次并行（`sem` 已限流，再细分只增调度开销）
- ❌ 媒体流 / 缩略图 / 原图压缩（已是压缩格式，gzip 反向 overhead）
- ❌ FTS5 全文索引、内存倒排索引（YAGNI）

---

## 3. 架构总览与 Commit 切分

| Commit | 主题 | 主要痛点 | 改动文件数 | 预期收益 |
|---|---|---|---|---|
| **A1** | SQLite 索引 + PRAGMA + `IN (...)` 批查 | P3/P6/P9 | 2 | 标签查询 100x 提速 |
| **A2** | Scanner `cacheByDir` + Browse `/files` 前缀剪枝 | P1/P3/P5 | 3 | Browse 100x 提速 |
| **A3** | fsnotify 每 root 独立防抖（全 root 重扫） | P9 | 2 | 减少无效重扫次数，高频事件场景 2-3x 提速 |
| **B1** | 缩略图预热策略调优（hot 优先 + 限流退让） | P1/P2 | 2 | 首屏体验显著改善 |
| **B2** | 图片缩略图 `sync.Pool` 减少 GC | P2/P9 | 1 | 输出 buffer B/op 下降，整体 alloc 约降 30-50% |
| **B3** | Echo gzip middleware | P3/P5 | 1 | JSON 传输 6x 提速 |

**执行顺序 A1 → A2 → A3 → B1 → B2 → B3**：

- A 系列先做（基础架构），B 系列后做（叠加优化）
- A1 先于 A2：A2 的 `cacheByDir` 构建在 Scan 中，不依赖 tags；但执行顺序 A1 在前让标签查询先变快
- A3 在 A2 后：A3 改 `watchEvents` 防抖逻辑，与 A2 的 `cacheByDir` 构建在 `Scan` 中共同写入
- B1 在 A3 后：B1 改 `OnScanComplete` callback 和 `PreGenerateThumbnails` 签名，依赖 A3 的扫描行为稳定
- B2 在 B1 后：B2 改 `encodeThumbnailToCache`，B1 已先调过 worker 数
- B3 最后：纯 middleware 挂载，独立无依赖

### 跨 commit 不变约束

- ✅ API URL + 响应 schema 完全不变（4.1-A 约束）
- ✅ 缩略图视觉质量不变（`maxSize`×`maxSize` Linear Q85，4.1-C 约束）
- ✅ 配置文件 schema 不变（4.1-D 约束）
- ⚠️ 磁盘缓存结构：A1 加索引会改变 `tags.db`（首次启动自动 CREATE INDEX IF NOT EXISTS）；B 系列不动磁盘缓存；升级不清缓存
- ✅ 现有所有测试不回归
- ✅ 每个 commit 独立可 `git revert`

---

## 4. A1 — SQLite 索引 + PRAGMA + `IN (...)` 批查

### 4.1 改造方案

#### A1.1 PRAGMA（启动时执行，幂等）

`NewTagsService` 在建表后立即执行：

```go
pragmas := []string{
    "PRAGMA journal_mode=WAL",
    "PRAGMA synchronous=NORMAL",
    "PRAGMA mmap_size=268435456",   // 256MB
    "PRAGMA temp_store=MEMORY",
    "PRAGMA cache_size=-65536",     // 64MB page cache (KB)
    "PRAGMA foreign_keys=ON",
    "PRAGMA busy_timeout=5000",
}
for _, p := range pragmas {
    if _, err := db.Exec(p); err != nil {
        slog.Warn("tags sqlite pragma failed", "pragma", p, "error", err)
    }
}
```

**WAL 模式说明**：会在 `.data/` 目录下生成 `tags.db-wal` 和 `tags.db-shm`。`.gitignore` 已排除 `.data/`。`SetMaxOpenConns(1)` 在 WAL 下不是必需，但保留避免 modernc/sqlite 并发 bug。

#### A1.2 索引（CREATE INDEX IF NOT EXISTS）

```go
indexes := []string{
    "CREATE INDEX IF NOT EXISTS idx_associations_tag_id ON associations(tag_id)",
    "CREATE INDEX IF NOT EXISTS idx_associations_file_path ON associations(file_path)",
    "CREATE INDEX IF NOT EXISTS idx_tags_name_lower ON tags(LOWER(name))",
}
for _, idx := range indexes {
    if _, err := db.Exec(idx); err != nil {
        db.Close()
        return nil, fmt.Errorf("failed to create index: %w", err)
    }
}
```

#### A1.3 `GetTagsForFiles` 改用 `IN (...)`

```go
func (s *TagsService) GetTagsForFiles(filePaths []string) map[string][]models.FileTag {
    s.mu.RLock()
    defer s.mu.RUnlock()

    result := make(map[string][]models.FileTag, len(filePaths))
    for _, fp := range filePaths {
        result[fp] = []models.FileTag{}
    }
    if len(filePaths) == 0 {
        return result
    }

    const batchSize = 500
    for start := 0; start < len(filePaths); start += batchSize {
        end := start + batchSize
        if end > len(filePaths) {
            end = len(filePaths)
        }
        batch := filePaths[start:end]

        placeholders := strings.Repeat("?,", len(batch)-1) + "?"
        args := make([]interface{}, len(batch))
        for i, fp := range batch {
            args[i] = fp
        }

        query := fmt.Sprintf(`
            SELECT a.file_path, t.id, t.name, t.color
            FROM associations a
            JOIN tags t ON a.tag_id = t.id
            WHERE a.file_path IN (%s)
        `, placeholders)

        rows, err := s.db.Query(query, args...)
        if err != nil {
            return result
        }
        for rows.Next() {
            var filePath string
            var t models.FileTag
            if err := rows.Scan(&filePath, &t.ID, &t.Name, &t.Color); err == nil {
                if _, exists := result[filePath]; exists {
                    result[filePath] = append(result[filePath], t)
                }
            }
        }
        rows.Close()
    }
    return result
}
```

### 4.2 涉及文件

| 文件 | 改动 |
|---|---|
| `server/internal/service/tags.go` | `NewTagsService` 加 PRAGMA + 索引；`GetTagsForFiles` 改 `IN (...)` 批查 |
| `server/internal/service/tags_test.go` | PRAGMA 生效测试、索引存在性测试、`IN (...)` 正确性 + 大批量测试 |

### 4.3 风险与缓解

| 风险 | 缓解 |
|---|---|
| WAL 产生 `-wal` / `-shm` 文件 | `.gitignore` 已排除 `.data/`；modernc sqlite 退出时自动 checkpoint |
| migration 失败导致启动崩溃 | PRAGMA 失败仅 `slog.Warn` 不阻断；索引失败返回 error（无索引仍可工作但启动失败更安全） |
| `idx_tags_name_lower` 函数索引兼容性 | modernc.org/sqlite 基于 SQLite 3.x，函数索引 3.9+ 支持；失败降级为普通索引 |
| 批查参数上限 | modernc 默认 32766 远超 500；保守用 500 |
| `foreign_keys=ON` 激活之前的死代码 | 当前未设 `PRAGMA foreign_keys=ON`，`ON DELETE CASCADE` 从未生效；`DeleteTag` 已手动 `DELETE FROM associations WHERE tag_id = ?` 作为 workaround。启用后 CASCADE 真正生效，与手动 DELETE 冗余但 harmless。**需确认无 orphan association 记录**（理论上不可能，因为 FK 引用的 tags.id 总是先插入的）|

### 4.4 Baseline

```bash
cd server
go test -bench=BenchmarkGetTagsForFiles -benchmem ./internal/service/
```

预期：50 个路径查询从全表扫 ~5ms（50k rows）→ 索引点查 ~50μs，**100x 提速**。

---

## 5. A2 — Scanner `cacheByDir` + Browse `/files` 前缀剪枝

### 5.1 改造方案

#### A2.1 Scanner 加 `cacheByDir` 字段

```go
type Scanner struct {
    // ... existing fields ...
    // cacheByDir 把扫描结果按"直接父目录"分组。
    // BrowseFolder /files 分支用 path 前缀直接查这个 map，
    // 替代遍历 cache["all"] + IsPathWithinRoots 全量过滤。
    cacheByDir map[string][]models.MediaFile
}
```

`Scan` 合并阶段构建（同一次遍历，零额外 IO）：

```go
byDir := make(map[string][]models.MediaFile)
for _, subList := range results {
    for _, f := range subList {
        dir := filepath.Clean(filepath.Dir(f.Path))
        byDir[dir] = append(byDir[dir], f)
    }
}

s.mu.Lock()
s.cache["all"] = allFiles
// ... existing writes ...
s.cacheByDir = byDir
// ...
```

`InvalidateCache` 同步清理：`s.cacheByDir = nil`。`NewScanner` 初始化：保持 nil（首次 Scan 前为 nil 表示"未填充"）。

#### A2.2 新增 `GetCachedByDir`

```go
// GetCachedByDir 返回指定目录直接子文件列表（不含子目录的文件）。
// cache miss 时触发 Scan（与 GetCached 共享 singleflight）。
// 区分"cache miss"和"目录为空"两种语义：返回 (emptySlice, nil) 表示目录无文件。
func (s *Scanner) GetCachedByDir(ctx context.Context, roots []string, dir string) ([]models.MediaFile, error) {
    cleanDir := filepath.Clean(dir)
    s.mu.RLock()
    cachedValid := time.Since(s.cacheTime) < s.cacheTTL
    if cachedValid {
        if files, ok := s.cacheByDir[cleanDir]; ok {
            s.mu.RUnlock()
            return files, nil
        }
        if s.cacheByDir != nil {
            s.mu.RUnlock()
            return []models.MediaFile{}, nil
        }
    }
    s.mu.RUnlock()

    _, err, _ := s.sf.Do("scan", func() (interface{}, error) {
        return s.Scan(ctx, roots)
    })
    if err != nil {
        return nil, err
    }

    s.mu.RLock()
    defer s.mu.RUnlock()
    if files, ok := s.cacheByDir[cleanDir]; ok {
        return files, nil
    }
    return []models.MediaFile{}, nil
}
```

#### A2.3 `BrowseFolder` 改用 `GetCachedByDir`

```go
if strings.HasSuffix(rawPath, "/files") {
    pathStr, err := decodeWildcardPath(rawPath, "/files")
    // ... existing NormalizePath + IsPathWithinRoots validation ...

    matchedFiles, err := h.scanner.GetCachedByDir(
        c.Request().Context(),
        h.cfg.Scan.GetRoots(),
        pathStr,
    )
    if err != nil {
        return respondInternalError(c, err)
    }

    setJsonCacheBrief(c)
    return c.JSON(http.StatusOK, matchedFiles)
}
```

### 5.2 涉及文件

| 文件 | 改动 |
|---|---|
| `server/internal/service/scanner.go` | 加 `cacheByDir`；`Scan` 构建；`InvalidateCache` 清理；新增 `GetCachedByDir` |
| `server/internal/service/scanner_test.go` | `cacheByDir` 填充、`GetCachedByDir` 命中/miss/空目录测试 |
| `server/internal/server/handler/folders.go` | `BrowseFolder /files` 改用 `GetCachedByDir` |
| `server/internal/server/handler/folders_test.go` | `/files` 返回直接子文件 + 不含子目录文件回归测试 |

### 5.3 行为变化（需在 commit message 标注）

**变更前**：`BrowseFolder /files` 返回 `IsPathWithinRoots(file.Path, [pathStr])` 为 true 的所有文件，**包含递归子目录的文件**。

**变更后**：只返回 pathStr 直接子文件，**不含子目录的文件**。

**这是修复而非回归**——客户端 `BrowseScreen` 进入目录后期望只看当前目录的文件，子目录文件通过进入子目录访问。客户端无依赖递归行为的代码。

### 5.4 风险与缓解

| 风险 | 缓解 |
|---|---|
| 内存占用上升 | 50k × ~100B MediaFile 值 = ~5MB；map 开销 ~1MB；家用 PC 可接受 |
| 路径大小写不一致 | 与现有 `IsPathWithinRoots` 同语义，不引入回归；后续可加 `EqualFold` 修复 |
| `cacheByDir` 与 `cacheDirs` 不一致 | 同一次 Scan 构建两者；`InvalidateCache` 同步清 |

### 5.5 Baseline

```bash
cd server
go test -bench=BenchmarkBrowseFolder_Files -benchmem ./internal/server/handler/

hyperfine --warmup 3 \
  'curl -s http://localhost:8000/api/v1/folders/D%3A%2FMedia%2FBigDir/files -o /dev/null'
```

预期：500 项目录响应从 ~5ms → ~50μs，**100x 提速**。

---

## 6. A3 — fsnotify 每 root 独立防抖

### 6.1 改造方案

> [!IMPORTANT]
> **原始设计纠正**：原方案为 `InvalidateCache()` + `TriggerScan([]string{ownerRoot})` 只扫单 root。
> 但 `InvalidateCache` 清除**全部** cache（`cache["all"]`/`cacheDirs`/`cacheByDir` 均清空），而 `Scan` 以传入的 roots 为准重建缓存。
> 如果只传 1 个 root，重建后的 `cache["all"]` 仅包含该 root 的文件，**其他 root 的数据丢失**直到下次事件或 TTL 过期。
> 修正方案：防抖 timer fire 后仍 `TriggerScan(s.watchRoots)` 扫所有 roots，但每个 root **独立防抖**——
> 一个 root 的高频事件不会重置其他 root 的 2s 窗口，从而减少不必要的重扫次数。

#### A3.1 `watchEvents` 重写：每 root 独立防抖（全 root 重扫）

```go
func (s *Scanner) watchEvents() {
    type pendingRoot struct {
        timer *time.Timer
    }
    var (
        pending   = make(map[string]*pendingRoot)
        pendingMu sync.Mutex
    )

    const debounceDuration = 2 * time.Second

    for {
        s.mu.RLock()
        watcher := s.watcher
        s.mu.RUnlock()
        if watcher == nil {
            return
        }

        select {
        case event, ok := <-watcher.Events:
            if !ok {
                return
            }

            if event.Op&(fsnotify.Write|fsnotify.Create|fsnotify.Remove|fsnotify.Rename) == 0 {
                continue
            }

            // A3：保留全 cache 失效语义（精确增量合并复杂度过高，YAGNI）
            s.InvalidateCache()

            if event.Op&fsnotify.Create == fsnotify.Create {
                if info, err := os.Stat(event.Name); err == nil && info.IsDir() {
                    s.mu.Lock()
                    if s.watcher != nil {
                        _ = s.watcher.Add(event.Name)
                    }
                    s.mu.Unlock()
                }
            }

            s.mu.RLock()
            roots := s.watchRoots
            s.mu.RUnlock()

            ownerRoot := findOwnerRoot(event.Name, roots)

            pendingMu.Lock()
            if p, ok := pending[ownerRoot]; ok {
                p.timer.Stop()
            }
            pending[ownerRoot] = &pendingRoot{}
            pending[ownerRoot].timer = time.AfterFunc(debounceDuration, func() {
                pendingMu.Lock()
                delete(pending, ownerRoot)
                pendingMu.Unlock()

                // A3 修正：始终扫所有 roots 保证缓存完整性。
                // 独立防抖的核心收益：root1 高频事件不会重置 root2 的
                // 2s 窗口，减少总重扫次数。singleflight 会自动合并
                // 同一时刻 fire 的多个 root 的扫描为一次。
                s.mu.RLock()
                allRoots := s.watchRoots
                s.mu.RUnlock()
                s.TriggerScan(allRoots)
            })
            pendingMu.Unlock()

        case _, ok := <-watcher.Errors:
            if !ok {
                return
            }
        }
    }
}

func findOwnerRoot(path string, roots []string) string {
    cleanPath := filepath.Clean(path)
    for _, root := range roots {
        cleanRoot := filepath.Clean(root)
        if cleanPath == cleanRoot || strings.HasPrefix(cleanPath, cleanRoot+string(filepath.Separator)) {
            return root
        }
    }
    if len(roots) > 0 {
        return roots[0]
    }
    return ""
}
```

**与当前代码的关键差异**：当前 `watchEvents` 使用全局单 `scanTimer`，任何事件都重置同一个 2s timer。A3 改为每 root 独立 timer——root1 持续产生事件时不影响 root2 的 timer fire。`TriggerScan` 内部的 `bgCancel()` + singleflight 自动合并同一时刻触发的多次 `Scan`。

### 6.2 涉及文件

| 文件 | 改动 |
|---|---|
| `server/internal/service/scanner.go` | `watchEvents` 重写（全局 timer → per-root timer map）；新增 `findOwnerRoot` helper |
| `server/internal/service/scanner_test.go` | 多 root 场景下，单 root 内高频事件不重置其他 root 的防抖 timer（mock timer 计数） |

### 6.3 风险与缓解

| 风险 | 缓解 |
|---|---|
| 多 root 同时 fire 导致重复扫描 | `TriggerScan` 内 `bgCancel()` 取消前一次 Scan + singleflight 合并，实际只跑一次 |
| 跨 root 文件移动 | 移动会触发 Create（目标）+ Remove（源）两个 root 都扫到，正确性 OK |
| 防抖 timer map 泄漏 | `time.AfterFunc` 触发时显式 `delete`；watcher 关闭后 timer fire 仍安全（TriggerScan 内 nil 检查） |
| `fsnotify.Rename` 跨目录 | fsnotify Rename 触发旧路径，新路径触发 Create；两个 root 都扫到 |
| `InvalidateCache` 仍清全部 cache | 保留全清语义避免增量合并复杂度；下一个请求触发 singleflight Scan 重建全量缓存 |
| 单 root 高频事件风暴（1000+ events/s） | per-root timer 仅保存最后一个 `time.AfterFunc`，内存占用 O(root 数) 恒定；Stop + 重建 ~100ns |

### 6.4 Baseline

```bash
cd server
go test -bench=BenchmarkWatchEvents_PerRootDebounce -benchmem ./internal/service/

# 端到端：3 个 root 各 5k 文件，root1 连续 touch 100 个文件
# 观察：root2/root3 的防抖 timer 是否受 root1 事件影响
# 对比当前代码：当前全局 timer 被 root1 不断重置，100 个事件后 2s 才扫一次
# A3 代码：root1 每次重置自己的 timer，root2/root3 的 timer 在首事件后 2s 即 fire
```

预期：高频事件场景下重扫响应时间改善 **2-3x**（不是扫描速度提升，而是减少不必要的延迟等待）；CPU 开销不变（仍扫全量 roots）。

---

## 7. B1 — 缩略图预热策略调优

### 7.1 改造方案

#### B1.1 `ThumbnailService` 加 `hotTracker`

```go
type ThumbnailService struct {
    // ... existing fields ...
    // hotTracker 跟踪最近 200 个被请求的 path（LRU）。
    // PreGenerateThumbnails 用它作为热点优先级来源。
    hotTracker *lru.Cache[string, struct{}]
}

func NewThumbnailService(...) {
    // ...
    hotTracker, _ := lru.New[string, struct{}](200)
    s := &ThumbnailService{
        // ...
        hotTracker: hotTracker,
    }
    // ...
}

func (s *ThumbnailService) generateBytesVia(sourcePath string, genFunc ...) ([]byte, error) {
    s.hotTracker.Add(sourcePath, struct{}{})  // 新增
    // ... existing logic ...
}

// HotTracker 导出 hotTracker 给 server.go::OnScanComplete 读取热点 key 列表。
// 不导出字段本身，避免外部包误操作 LRU 内部状态。
func (s *ThumbnailService) HotTracker() *lru.Cache[string, struct{}] {
    return s.hotTracker
}
```

> [!NOTE]
> `hotTracker` 放在 `generateBytesVia` 而非 `GenerateThumbnail` 中是有意设计——
> `generateBytesVia` 只被交互请求路径（`GenerateThumbnailBytes` / `GenerateSystemThumbnailBytes`）调用，
> 而 `PreGenerateThumbnails` 调用的是 `GenerateThumbnail`（返回磁盘路径），不经过 `generateBytesVia`。
> 因此 hotTracker 只追踪真实用户交互请求，预热不会污染热点数据。

#### B1.2 `PreGenerateThumbnails` 加 `hotPaths` + 跳过已缓存 + worker 退让

```go
func (s *ThumbnailService) PreGenerateThumbnails(
    files []models.MediaFile,
    ctx context.Context,
    hotPaths map[string]struct{},
) {
    hasFFmpeg := s.HasFFmpeg()

    var hotQueue, coldQueue []models.MediaFile
    for _, f := range files {
        switch f.MediaType {
        case "image":
        case "video":
            if !hasFFmpeg {
                continue
            }
        default:
            continue
        }

        // 跳过已缓存（注：当前代码 worker 内部已有此逻辑 L379-386，
        // B1 将其前移到入队阶段减少 goroutine 调度开销 + 缩小 jobs channel buffer）
        fi, err := os.Stat(f.Path)
        if err != nil {
            continue
        }
        cachePath := s.GetThumbnailPath(f.Path, fi.ModTime())
        if _, err := os.Stat(cachePath); err == nil {
            continue
        }

        if _, isHot := hotPaths[f.Path]; isHot {
            hotQueue = append(hotQueue, f)
        } else {
            coldQueue = append(coldQueue, f)
        }
    }

    queue := append(hotQueue, coldQueue...)
    if len(queue) == 0 {
        return
    }

    // B1：从 NumCPU/2 降到 NumCPU/4，保留 CPU 给交互请求
    numWorkers := runtime.NumCPU() / 4
    if numWorkers < 1 {
        numWorkers = 1
    }

    jobs := make(chan models.MediaFile, len(queue))
    for _, img := range queue {
        jobs <- img
    }
    close(jobs)

    var wg sync.WaitGroup
    for i := 0; i < numWorkers; i++ {
        wg.Add(1)
        go func() {
            defer wg.Done()
            var count int
            for {
                select {
                case <-ctx.Done():
                    return
                case img, ok := <-jobs:
                    if !ok {
                        return
                    }

                    // B1：每 5 个文件退让 100ms，让出 sem 给交互请求
                    count++
                    if count%5 == 0 {
                        select {
                        case <-ctx.Done():
                            return
                        case <-time.After(100 * time.Millisecond):
                        }
                    }

                    _, _ = s.GenerateThumbnail(img.Path)
                }
            }
        }()
    }
    wg.Wait()
}
```

#### B1.3 `server.go::OnScanComplete` 传入 hotPaths

```go
scanner.OnScanComplete = func(files []models.MediaFile) {
    s.preGenMu.Lock()
    if s.preGenCancel != nil {
        s.preGenCancel()
    }
    var ctx context.Context
    ctx, s.preGenCancel = context.WithCancel(context.Background())
    s.preGenMu.Unlock()

    hotPaths := make(map[string]struct{})
    for _, key := range s.Thumbnail.HotTracker().Keys() {
        hotPaths[key] = struct{}{}
    }

    s.Thumbnail.PreGenerateThumbnails(files, ctx, hotPaths)
}
```

### 7.2 涉及文件

| 文件 | 改动 |
|---|---|
| `server/internal/service/thumbnail.go` | `ThumbnailService` 加 `hotTracker` 字段 + `HotTracker()` 导出方法；`generateBytesVia` 记录 hot path；`PreGenerateThumbnails` 加 `hotPaths` 参数 + hot/cold 双队列 + worker 退让（跳过已缓存从 worker 内部前移到入队阶段） |
| `server/internal/server/server.go` | `OnScanComplete` 回调通过 `HotTracker().Keys()` 传入 hotPaths |
| `server/internal/service/thumbnail_test.go` | hot path 排序、跳过已缓存、worker 退让测试 |

### 7.3 风险与缓解

| 风险 | 缓解 |
|---|---|
| `hotTracker` 与 `memCache` 不一致 | 独立设计，两者独立淘汰，无同步问题 |
| 跨重启 hotPaths 丢失 | 接受。冷启动时无热点信号，按文件名字母序预热（与现状一致） |
| worker 退让降低整体预热速度 | 设计意图——交互优先。50k 文件预热从 ~30 分钟 → ~60 分钟，但首屏不被抢占 |
| `hotTracker.Add` 在快速路径上加锁 | golang-lru/v2 内部 mutex ~100ns/op，相对 `GenerateThumbnail` 100ms+ 可忽略 |
| `PreGenerateThumbnails` 签名变化 | 仅 `server.go::OnScanComplete` 一处调用，同步修改 |

### 7.4 Baseline

```bash
cd server
go test -bench=BenchmarkPreGenerateThumbnails -benchmem ./internal/service/
```

预期：扫描完成后访问 hot path 缩略图，从"等 30 分钟轮到"→"立即从缓存返回"，**首屏体验显著改善**。

---

## 8. B2 — 图片缩略图 `sync.Pool` 减少 GC

### 8.1 诚实评估

经过仔细分析：
- "并行化"已被 `PreGenerateThumbnails` worker pool 覆盖（B1 调优过）
- `sem` 已限流到 `runtime.NumCPU()`，再细分并行只增调度开销
- 真实可优化点：高并发下 `jpeg.Encode` 输出 buffer 的 GC 压力

**B2 范围限定为 `sync.Pool` 复用 `jpeg.Encode` 输出 buffer**，不做 worker 内部二次并行。

> [!WARNING]
> **实事求是的 tradeoff**：当前代码直接流式写磁盘 `jpeg.Encode(tempFile, ...)`，**峰值内存仅 jpeg 内部 buffer**。
> B2 改为先编码到内存 pool buffer 再写磁盘，峰值内存增加约 30-50KB/并发（300×300 Q85 JPEG 典型输出 ~30KB）。
> 在 `NumCPU` 并发下额外占用 ~200-400KB 总内存，对桌面 PC 可忽略。
> `sync.Pool` 的收益主要体现在减少 `bytes.Buffer` 底层 `[]byte` 的重复分配，而非 `jpeg.Encode` 内部分配（无法控制）。
> 整体 B/op 预计下降 **30-50%**，而非 70x。

### 8.2 改造方案

```go
var thumbBufPool = sync.Pool{
    New: func() interface{} {
        b := make([]byte, 0, 64*1024)  // 64KB 预分配（300×300 Q85 JPEG 典型输出 ~30KB，留余量）
        return &b
    },
}

func (s *ThumbnailService) encodeThumbnailToCache(src image.Image, cachePath string) (string, error) {
    thumb := imaging.Fit(src, s.maxSize, s.maxSize, imaging.Linear)

    bufPtr := thumbBufPool.Get().(*[]byte)
    buf := bytes.NewBuffer(*bufPtr)
    buf.Reset()

    if err := jpeg.Encode(buf, thumb, &jpeg.Options{Quality: 85}); err != nil {
        *bufPtr = buf.Bytes()  // 保留底层 array 给 pool
        thumbBufPool.Put(bufPtr)
        return "", err
    }

    // 原子写入磁盘
    tempFile, err := os.CreateTemp(filepath.Dir(cachePath), "thumb-tmp-*.jpg")
    if err != nil {
        *bufPtr = buf.Bytes()
        thumbBufPool.Put(bufPtr)
        return "", err
    }
    tempPath := tempFile.Name()
    defer os.Remove(tempPath)

    if _, err := tempFile.Write(buf.Bytes()); err != nil {
        tempFile.Close()
        *bufPtr = buf.Bytes()
        thumbBufPool.Put(bufPtr)
        return "", err
    }
    if err := tempFile.Close(); err != nil {
        *bufPtr = buf.Bytes()
        thumbBufPool.Put(bufPtr)
        return "", err
    }

    *bufPtr = buf.Bytes()
    thumbBufPool.Put(bufPtr)

    if err := os.Rename(tempPath, cachePath); err != nil {
        return "", err
    }
    return cachePath, nil
}
```

### 8.3 涉及文件

| 文件 | 改动 |
|---|---|
| `server/internal/service/thumbnail.go` | 新增 `thumbBufPool`；`encodeThumbnailToCache` 复用 buffer |

### 8.4 风险与缓解

| 风险 | 缓解 |
|---|---|
| sync.Pool 干扰 testing 内存统计 | benchmark 用 `runtime.MemStats.TotalAlloc` 而非 `HeapAlloc` |
| buffer 大小预估错 | `bytes.NewBuffer` 自动扩容，pool 复用第一次以后的 buffer |
| sync.Pool GC 时被清空 | 预期行为，长 idle 后 pool 为空，下次分配重建 |
| 峰值内存增加 | 每并发增加 ~30-50KB（JPEG 输出 buffer），`NumCPU` 并发下总计 ~200-400KB，桌面 PC 可忽略 |
| 从流式写磁盘改为先编码到内存再写 | 增加一次 `tempFile.Write(buf.Bytes())` 拷贝，但 JPEG 输出只有 ~30KB，IO 影响可忽略 |

### 8.5 Baseline

```bash
cd server
go test -bench=BenchmarkEncodeThumbnailToCache -benchmem ./internal/service/
```

预期：单次 encode 从 ~100ms + ~60KB alloc → ~100ms + ~30KB alloc，**输出 buffer B/op 下降，整体 alloc 约降 30-50%**。`jpeg.Encode` 内部分配无法通过 pool 控制。

---

## 9. B3 — Echo gzip middleware

### 9.1 改造方案

`server.go::registerRoutes` 挂载 gzip middleware：

```go
import echoMw "github.com/labstack/echo/v4/middleware"

// 在 Recover + Logger 之后、SecurityHeaders 之前挂载
s.Echo.Use(echoMw.GzipWithConfig(echoMw.GzipConfig{
    Level: 5,
    Skipper: func(c echo.Context) bool {
        // ⚠️ 必须用 c.Request().URL.Path 而非 c.Path()。
        // c.Path() 返回注册路由模板（如 "/api/v1/videos/*"、"/api/v1/folders/*"），
        // 而非实际请求路径。通配路由的模板不含子路径关键词（/stream、/download），
        // 导致 Skipper 无法正确排除 /api/v1/videos/*/stream 等二进制端点。
        path := c.Request().URL.Path
        // 排除已压缩的二进制端点
        if strings.Contains(path, "/stream") ||
           strings.Contains(path, "/thumbnail") ||
           strings.Contains(path, "/original") ||
           strings.Contains(path, "/download") {
            return true
        }
        return false
    },
}))
```

### 9.2 客户端兼容性

- **OkHttp**：默认发送 `Accept-Encoding: gzip`，自动解压，客户端无需任何改动
- **Coil**：基于 OkHttp，自动继承
- **ExoPlayer**：基于 OkHttp（项目配置），自动继承
- **Web UI（浏览器）**：默认支持 gzip

### 9.3 涉及文件

| 文件 | 改动 |
|---|---|
| `server/internal/server/server.go` | `registerRoutes` 挂载 `GzipWithConfig` |
| `server/internal/server/server_test.go` | JSON 响应带 `Content-Encoding: gzip`；视频/缩略图响应不带 gzip |

### 9.4 风险与缓解

| 风险 | 缓解 |
|---|---|
| 视频流被 gzip 二次压缩 | `Skipper` 显式排除 `/stream`；测试覆盖 |
| 缩略图被 gzip 增加延迟 | `Skipper` 排除 `/thumbnail`；JPEG 已压缩 |
| RateLimit middleware 与 Gzip 顺序 | Gzip 在 router 级，RateLimit 在 group 内部，不冲突 |
| Echo gzip min length 默认 1400 字节 | 适合本项目，小响应不压缩 |
| CPU 占用 | Level 5 ~500MB/s 压缩速度，500KB 响应压缩 ~1ms |

### 9.5 Baseline

```bash
hyperfine --warmup 3 \
  'curl -s -H "Accept-Encoding: gzip" --compressed \
    http://localhost:8000/api/v1/folders/D%3A%2FMedia%2FBigDir/files -o /dev/null'
```

预期：500KB JSON → ~80KB gzip，**响应大小下降 6x**；LAN 100Mbps 下传输时间从 50ms → 8ms。

---

## 10. 端到端数据流

### 10.1 场景 A：大视频目录首屏（A1 + A2 + B1 + B3 生效）

```
[Client] GET /api/v1/folders/D:/Media/BigDir/files
  ↓
[Server: handler/folders.go::BrowseFolder /files 分支]
  └─ scanner.GetCachedByDir(...) ← A2 新增
       ├─ cacheByDir[CleanDir] O(1) lookup
       └─ 返回 500 项目录的直接子文件
  ↓
[Server: Echo gzip middleware ← B3 新增]
  └─ Level 5 压缩 JSON: 500KB → ~80KB
  ↓
[Client: OkHttp 自动解压 → JSON 解析 → 渲染网格]
  ├─ 用户滚动到第 1-12 项缩略图
  └─ Coil 异步请求 /api/v1/media/thumbnail?path=...
       ↓
[Server: media.go::MediaThumbnail]
  └─ thumbnail.GenerateThumbnailBytes
       ├─ memCache 命中 → 直接返回（A1 B1 不直接参与，但缩略图管线已优化）
       └─ memCache miss → PreGenerateThumbnails ← B1 已预热热门 path
```

**对比改动前**：从"50k 全量过滤 + 500KB 未压缩传输 + 用户缩略图被预热 worker 阻塞"→"O(1) map lookup + 80KB 压缩传输 + 热点 path 已预热"。

### 10.2 场景 B：fsnotify 触发单文件（A3 生效）

```
[NAS / Plex] 写入 D:/Media/Series/Ep01.mp4
  ↓
[fsnotify] Create event → watchEvents
  ├─ InvalidateCache（保留全清语义）
  ├─ findOwnerRoot → "D:/Media"
  └─ pending["D:/Media"] 防抖 2s
       ↓ 2s 后
       └─ TriggerScan(s.watchRoots)  ← A3 每 root 独立防抖，但扫所有 roots
            └─ Scan walk 所有 roots（50k 文件，~30s）
                 └─ OnScanComplete
                      └─ PreGenerateThumbnails（B1 hot 优先）
```

**对比改动前**：当前全局单 timer 下，root1 频繁事件会不断重置 timer，导致所有 root 的扫描被延迟。A3 独立防抖后，root2/root3 的事件不受 root1 影响，各自在 2s 后触发扫描（singleflight 合并重复扫描）。

### 10.3 关键不变量

- API URL 与响应 schema **完全不变**
- 缩略图视觉质量 **完全不变**（`maxSize`×`maxSize` Linear Q85）
- 配置文件 schema **完全不变**
- 磁盘缓存目录结构 **完全不变**（A1 加索引在 tags.db 内部，对调用方透明）
- 客户端 **无需任何改动**

---

## 11. 错误处理与边界情况

### 11.1 A1 SQLite

| 情况 | 处理 |
|---|---|
| PRAGMA 失败（磁盘满 / 权限） | `slog.Warn` 不阻断；用默认配置启动 |
| 索引失败 | 返回 error 启动失败（保守策略，无索引虽可工作但用户期望失败可见） |
| `idx_tags_name_lower` 不支持 | 失败时降级为普通 idx_tags_name；保留 LOWER(name) 查询不命中索引但功能正确 |
| `IN (...)` 超过 batch 500 | 分批查询，单批 500；总查询次数 = ceil(N/500) |
| WAL 文件无法创建（只读 fs） | `PRAGMA journal_mode=WAL` 失败 → `slog.Warn` → 退回 DELETE 模式 |

### 11.2 A2 cacheByDir

| 情况 | 处理 |
|---|---|
| 首次启动 cacheByDir == nil | `GetCachedByDir` 触发 Scan 填充 |
| 目录无媒体文件 | 返回 `(emptySlice, nil)` 区分 cache miss |
| 路径大小写不一致 | 与现有 `IsPathWithinRoots` 同语义 |
| 跨平台路径分隔符 | `filepath.Clean` 统一处理 |

### 11.3 A3 每 root 独立防抖

| 情况 | 处理 |
|---|---|
| 事件 path 不在任何 root 下 | `findOwnerRoot` 降级返回 `roots[0]`，由于防抖后仍重扫所有 roots，正确性不受影响 |
| 多事件并发触发同一 root | `pendingMu` 保护 timer map，Stop + 重置原子 |
| watcher 关闭后 timer fire | `TriggerScan` 内 `bgCancel != nil` 检查安全 |
| Rename 跨目录 | 触发 Remove + Create 两个事件，两个 root 都各自进行防抖计时，最终全量重扫 |

### 11.4 B1 缩略图预热

| 情况 | 处理 |
|---|---|
| `hotPaths` 为空（冷启动） | 所有文件进 coldQueue，按字母序预热 |
| 文件不存在 | `os.Stat` 失败 → continue 跳过 |
| 已缓存文件 | `os.Stat(cachePath)` 成功 → continue 跳过 |
| ctx 取消（Server.Stop） | worker `select` 检查 `ctx.Done()` 立即返回 |

### 11.5 B2 sync.Pool

| 情况 | 处理 |
|---|---|
| buffer 扩容超过 cap | `bytes.NewBuffer` 自动扩容，pool 复用时保留新 cap |
| Encode 失败 | 把 `buf.Bytes()` 写回 `*bufPtr` 后 Put，保留底层 array |
| GC 清空 pool | 预期，下次 alloc 重建 |

### 11.6 B3 gzip

| 情况 | 处理 |
|---|---|
| 客户端不发 Accept-Encoding | middleware 自动跳过压缩 |
| 响应 < 1400 字节 | middleware 自动跳过（min length 默认值） |
| 视频流端点 | `Skipper` 显式返回 true 跳过 |

---

## 12. 测试策略

### 12.1 单元测试矩阵

| Commit | 新测试 | 现有测试 |
|---|---|---|
| A1 | PRAGMA 生效、索引存在性、`IN (...)` 正确性、大批量批查 | tags_test.go 全过 |
| A2 | `cacheByDir` 填充、`GetCachedByDir` 命中/miss/空目录、`BrowseFolder /files` 行为 | scanner_test.go / folders_test.go 全过 |
| A3 | 多 root 场景单 root 内高频事件、`findOwnerRoot` 边界 | scanner_test.go 全过 |
| B1 | hot path 排序、跳过已缓存、worker 退让（mock time） | thumbnail_test.go 全过 |
| B2 | encode B/op 对比（sync.Pool 命中 vs miss） | thumbnail_test.go 全过 |
| B3 | JSON 响应带 gzip header、媒体端点不带 gzip | server_test.go 全过 |

### 12.2 性能 baseline（强制）

每个 commit 必须包含 `go test -bench` 或 `hyperfine` 对比数据，记录到 commit message 末尾"实测数据"章节。

### 12.3 真机/集成验证

- 启动服务端，配置 3 个 root 共 50k 文件
- 客户端访问大目录（500 项）→ 主观评估首屏速度
- 客户端搜索文件夹名 → 主观评估响应速度
- 用 Plex / touch 触发 fsnotify → 监听服务端 CPU 占用
- `wrk -t4 -c10 -d10s http://localhost:8000/api/v1/tags/file-tags?path=...&path=...` → 测 QPS

---

## 13. 回滚预案

每个 commit 独立可 `git revert`：

- **A1 revert**：删除 PRAGMA 与索引执行；`GetTagsForFiles` 恢复全表 query。SQLite 数据库已有索引保留（无副作用）
- **A2 revert**：`BrowseFolder /files` 恢复 `GetCached + IsPathWithinRoots` 全量过滤；Scanner `cacheByDir` 字段可保留（不影响功能）
- **A3 revert**：`watchEvents` 恢复全局单 timer 防抖
- **B1 revert**：`PreGenerateThumbnails` 恢复无 `hotPaths` 参数 + workers=NumCPU/2；`hotTracker` 字段可保留
- **B2 revert**：`encodeThumbnailToCache` 恢复直接 `jpeg.Encode(tempFile, ...)`
- **B3 revert**：移除 `s.Echo.Use(echoMw.GzipWithConfig(...))`

---

## 14. 决策点汇总

| 决策 | 选择 | 理由 |
|---|---|---|
| 范围 | A1-A3 + B1-B3 共 6 commit | 用户明确选 |
| API schema | 完全不变 | 4.1-A 约束 |
| 缩略图视觉质量 | 不变 | 4.1-C 约束 |
| 配置文件 schema | 不变 | 4.1-D 约束 |
| 磁盘缓存结构 | A1 改变 tags.db（加索引），其他不变 | 4.1-B 未选，允许变 |
| 新依赖 | 接受成熟小依赖 | 4.2-C 约束；实际仅用现有 hashicorp/golang-lru/v2 |
| 测试要求 | 单元测试 + baseline 数据 | 4.3-B 约束 |
| 提交策略 | 多 commit 打包到一个 spec/plan | 4.4-B 约束 |
| SQLite 模式 | WAL + NORMAL + mmap + cache | 标准性能优化组合 |
| SQLite 参数上限 | 批 500 | 远低于 modernc 默认 32766 |
| Browse `/files` 行为变化 | 仅返回直接子文件 | 修复而非回归，客户端无递归依赖 |
| scanner 增量策略 | 每 root 独立防抖 + 全量 Scan | subtree 精确增量复杂度过高 YAGNI，只防抖不增量保证缓存数据完整性 |
| 缩略图预热策略 | hot 优先 + 限流退让 + 跳过已缓存 | 交互优先于吞吐 |
| `parallelEncode` | 不做 | `sem` 已限流，再细分只增调度开销 |
| HTTP/2 / TLS | 不做 | 纯 HTTP LAN 部署，OkHttp 不支持 h2c |
| gzip 范围 | 仅 JSON / Web UI 静态资源 | 媒体端点已压缩 |
| gzip Level | 5 | 压缩率/速度平衡点 |

---

## 15. 非目标（再次明确）

- ❌ HTTP/2 / TLS / QUIC
- ❌ `cache["all"]` 精确增量合并
- ❌ `cacheByDir` 路径大小写不敏感修复
- ❌ Android 端任何改动
- ❌ `parallelEncode` 二次并行
- ❌ 媒体流/缩略图/原图压缩
- ❌ FTS5 全文索引
- ❌ 内存倒排索引 / n-gram trie
- ❌ 配置文件 schema 变更
- ❌ API 破坏性变更

---

## 16. 后续轮次（备忘）

- **C 方案（Android 端）**：Coil cache 调优、Compose重组热点、native decoder 并行
- **subtree 精确增量扫**：若 A3 单 root 仍嫌粗，可考虑 per-subtree watcher
- **`cacheByDir` 大小写不敏感**：用 `strings.EqualFold` 包装 key lookup
- **性能 baseline 自动化**：CI 跑 `go test -bench` + Compare benchmark，回归自动报警
