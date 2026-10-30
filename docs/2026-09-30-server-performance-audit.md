# 后端性能审查报告（LocalMediaHub Go Server）

- 审查日期：2026-09-30
- 审查范围：`server/`（`cmd/` + `internal/{server,service,middleware,web,config,models}`），共 203 个 Go 文件 / 约 42.8k 行（含 `third_party/bluetooth`）
- 审查方式：静态阅读全部热路径 + 微基准实测（`go test -bench`，Go 1.25.0，i7-13700K）
- 关联文档：[`2026-09-30-web-frontend-audit.md`](2026-09-30-web-frontend-audit.md)（同日前端审查）

## 总体评价

后端性能工程的**底子很扎实**，绝大多数常见坑已经踩过并修掉了，本次审查没有发现需要推倒重来的结构性问题：

- **扫描侧**：TTL 缓存 + stale-while-revalidate + singleflight 防击穿 + `cacheByDir` 每目录索引（把 `/files` 从 ~5ms 全量过滤降到 ~1µs map 查）+ 快照持久化冷启动免走盘 + per-root 独立防抖。
- **缩略图侧**：三级内存/磁盘/ffprobe 缓存 + `sync.Pool` + singleflight 合并并发 miss + 分层预热（Tier1 hot 目录 → Tier2 根目录 → Tier3 懒生成）+ 512MB 磁盘 LRU 上限。
- **流媒体侧**：`http.ServeContent` 原生 Range + `BufferedReadSeeker` 修正 SeekCurrent 偏移 + 转码会话信号量 + HLS 原生 seek + 4GiB LRU 缓存 + 空闲回收。
- **数据侧**：SQLite WAL + `busy_timeout` + `mmap_size` + 64MB page cache + 函数索引 + 批量 500 一组的 IN 查询（避免 N+1）。
- **HTTP 侧**：静态资源强 ETag + 304 复用 + CSS 合并 + `modulepreload` 消除模块瀑布（`web/static.go` 已做得很好）。

**问题集中在四个方向**：① fsnotify 事件风暴期间缓存被硬清空，浏览请求退化为同步全盘扫描；② 少数热路径仍有「每次比较/每次请求」的重复分配与重复系统调用；③ 批量端点是串行的；④ SQLite 写路径缺少事务合并，且 tags 的写操作用了读锁。

---

## P0 — 会直接影响用户可感知延迟

### P0-1 fsnotify 事件立即硬清缓存，拷贝大文件期间浏览请求退化为同步全盘扫描

`internal/service/scanner.go:843-848`

```go
if event.Op&(fsnotify.Write|fsnotify.Create|fsnotify.Remove|fsnotify.Rename) == 0 {
    continue
}
// A3：保留全 cache 失效语义（精确增量合并复杂度过高，YAGNI）
s.InvalidateCache()
```

`InvalidateCache`（`scanner.go:625-633`）是**硬清空**：

```go
s.cache = make(map[string][]models.MediaFile)
s.cacheDirs = nil
s.cacheDirMap = nil
s.cacheByDir = nil
s.cacheTime = time.Time{}
```

而 `GetCached`（`scanner.go:410-417`）的判定是 `ok == false` 就走进同步扫描分支：

```go
files, ok, fresh := s.cachedSnapshot("all")
if ok { ... return files, nil }          // ← 走不到
val, err, _ := s.sf.Do("scan", func() (interface{}, error) {
    return s.Scan(ctx, roots)            // ← 请求线程在这里做全树 walk
})
```

**后果**：往媒体库拷一个几十 GB 的文件时，fsnotify 会持续产生 Write 事件，每一次都立即清空缓存。此时**任何**浏览 / 搜索 / 目录列表请求都落到同步 `Scan`，在请求线程里走完整棵目录树。2 秒防抖只延后了「事件触发的重扫」，**完全不保护请求**——防抖窗口内到达的请求照样全量走盘。singleflight 只能保证这些请求共享同一次扫描，不能消除等待。

**修复方向**：区分「软失效」与「硬失效」。

```go
// softInvalidate：保留数据，只把 cacheTime 归零 → 标记为 stale。
// cachedSnapshot 会返回 ok=true, fresh=false →
// GetCached 走 stale-while-revalidate：立即返回旧快照 + 后台刷新一次。
func (s *Scanner) softInvalidate() {
    s.mu.Lock()
    s.cacheTime = time.Time{}
    s.mu.Unlock()
}
```

- `watchEvents` 里的调用改为 `softInvalidate()`；
- `InvalidateCache()`（硬清空）只保留给 `DeletePath` / `admin` 这类明确的破坏性变更路径。

这样拷文件期间浏览请求立刻返回（数据最多旧 2 秒），后台合并成一次重扫。改动量小、收益直接。

---

### P0-2 批量缩略图端点是串行的，最多 64 个逐个 fork

`internal/server/handler/media.go:52-73`

```go
allowedExts := append(h.cfg.Scan.ImageExtensions, h.cfg.Scan.VideoExtensions...)
items := make([]ThumbnailItem, 0, len(req.Paths))
for _, p := range req.Paths {                 // ← 串行
    ...
    thumbBytes, err := h.thumbnail.GenerateThumbnailBytes(resolved)
```

`maxBatchThumbnails = 64`（`media.go:19`）。视频缩略图 miss 的单个成本是 `ffprobe` + `ffmpeg` 抽帧 + `image.Decode` + `imaging.Fit` + `jpeg.Encode`（`thumbnail.go:403-431`），实测量级在几十到几百毫秒。64 个全 miss 时串行执行会把响应时间推到**十几秒**，正好卡在客户端超时边缘。

注意这条路径**本来就有并发保护**：`ThumbnailService.sem`（`thumbnail.go:51`，容量 `NumCPU`）+ 磁盘缓存 double-check（`thumbnail.go:445-454`）+ `singleflight`（`thumbnail.go:647`）。所以并行化是安全的，不需要新增限流机制。

**修复方向**：用 `errgroup` + `SetLimit(min(4, NumCPU))` 并行执行，保留 per-item 错误隔离（每项独立 append 到预分配切片，按原索引写回，保证响应顺序与请求顺序一致）。

---

### P0-3 `naturalCompare` 每次比较做 2 次 `strings.ToLower`

`internal/service/naturalsort.go:20-55`

```go
func naturalCompare(a, b string) int {
    a = strings.ToLower(a)     // ← 每次比较都分配
    b = strings.ToLower(b)     // ←
    ab, bb := []byte(a), []byte(b)
    ...
```

`SortMediaFiles`（`naturalsort.go:111`）用 `sort.SliceStable`，比较次数是 `O(N log N)`。文件名带大写（`Episode 12 - Some Title.mkv`）时 `strings.ToLower` 必然分配新字符串。

**实测**（`go test -bench`，Go 1.25.0 / i7-13700K）：

| 基准 | ns/op | B/op | allocs/op |
|---|---|---|---|
| `naturalCompare` 单次调用 | 104.0 | 64 | **2** |
| `SortMediaFiles(files, "name", "asc")` N=500 | 98,960 | 114,771 | **1,269** |
| 装饰排序原型 N=500（小写键预计算一次） | 63,824 | 146,166 | **516** |

即：**排序 500 个文件一次请求产生 1269 次分配 / 114 KB**；换成「先算小写键再比较」降到 516 次（−59%），耗时降 36%。按 5000 个文件的目录线性外推，单次浏览请求约 **1.1 MB 临时分配**，全部压在 GC 上。这条路径覆盖 `BrowseFolder`（默认 `sort` 为空即走 `default` 分支）、`SystemBrowse`。

**修复方向**：decorate-sort-undecorate——每个元素只算一次小写键，比较函数接受已小写的输入（不再调用 `ToLower`），并把 `[]byte(a)` 换成直接字符串下标 `a[i]`（`naturalsort.go:23` 的 `ab, bb := []byte(a), []byte(b)` 只用于索引，转换本身是多余的）。

```go
type sortEntry struct {
    key string   // strings.ToLower(name)，算一次
    idx int      // 原索引，避免拷贝整个 MediaFile
    f   models.MediaFile
}
```

用 `idx` 而不是拷贝结构体，可以避免原型里 B/op 上升的副作用（原型 146 KB 是因为每个元素都拷了一份 `MediaFile`）。

`sort == "numeric"` 分支的 `leadingNumber`（`naturalsort.go:78-95`）有同样问题：`strings.Builder` + `sb.String()` 每次比较分配两次，同样应该预计算。

---

## P1 — 热路径上的重复开销

### P1-1 `os.Stat` 在全局 `dirMu` 锁内执行

`internal/service/scanner.go:273-296`

```go
dir := filepath.Clean(filepath.Dir(path))
for dir != "" && dir != cleanRoot {
    dirMu.Lock()
    _, exists := dirMap[dir]
    if !exists {
        var mtime time.Time
        if statInfo, err := os.Stat(dir); err == nil {   // ← 持锁做阻塞系统调用
            mtime = statInfo.ModTime()
        }
        dirMap[dir] = mtime
        dirMu.Unlock()
        ...
```

`dirMu` 是所有 walk goroutine 共享的全局锁。虽然只在「目录首次入库」时触发，但每个目录的首个文件都会沿祖先链一路 stat 到 root，在冷扫描大目录树时这条链的每一次 stat 都串行化全部 walker。

**修复方向**：把 stat 移到锁外，用「先查、解锁、stat、再加锁 double-check 写入」的模式。最坏情况多 stat 一次，换取锁内零系统调用。

### P1-2 快照落盘（含 `fsync`）在请求线程上同步执行

`internal/service/scanner.go:382` → `maybeWriteSnapshot`（`scanner.go:646-677`）→ `saveScanSnapshot`（`scanner_snapshot.go:106-133`）

```go
func saveScanSnapshot(path string, snap *scanSnapshotFile) error {
    data, err := json.Marshal(snap)      // ← 全量文件列表，大库可达数 MB
    ...
    if _, err := tmp.Write(data); err != nil { ... }
    if err := tmp.Sync(); err != nil { ... }   // ← fsync，最慢的一步
    return os.Rename(tmpName, path)
}
```

`Scan` 在缓存 miss 时由 `GetCached` 在**请求线程**同步调用（P0-1 已说明）。于是「首次请求 / 清缓存后的首次请求」要付：全树 walk + 数 MB JSON marshal + fsync + rename。30 秒节流只限制频率，不改变它落在请求路径上这个事实。

**修复方向**：把 `maybeWriteSnapshot` 丢进 goroutine（它自带节流，无需额外同步），或用单独的 writer 单飞（singleflight）保证同时只有一个写。落盘失败只 WARN，本来就是非关键路径。

### P1-3 每次流媒体请求分配 256 KB `bufio` 缓冲区

`internal/service/streaming.go:217-220`

```go
// Wrap in a 256 KB BufferedReadSeeker to minimize system calls on Windows
bufferedFile := NewBufferedReadSeeker(f, 256*1024)
http.ServeContent(w, r, fi.Name(), fi.ModTime(), bufferedFile)
```

两个问题：

1. **每请求 256 KB 堆分配**。N 个并发播放器 = N × 256 KB，全部走 GC。这部分完全可以用 `sync.Pool` 复用（`BufferedReadSeeker` 结构本身无状态，只需 `reader.Reset(f)` + 换 `file`）。
2. **读放大**。`bufio.Reader` 首次 `Read` 会填满整个缓冲区；`http.ServeContent` 处理 Range 时用 32 KB 的 `io.CopyN` 缓冲，`len(p) < 256KB` 于是触发 `bufio` 的「填满缓冲」分支——一个 1 字节的 Range 探测（或一次 seek）会实际从磁盘读 256 KB。顺序播放时这是好事，频繁 seek 时是纯浪费。

**修复方向**：`sync.Pool` 复用缓冲区；并按 Range 长度决定是否启用缓冲（`Content-Range` 长度小于阈值时直接读 `*os.File`）。

> 附注：`streaming.go:187-194` 的注释自相矛盾——上一段说「用 1 MB 缓冲的自定义 Range handler 替代 `http.ServeContent` 的 32 KB 默认值」，下一段又说「用 Go 标准库 `http.ServeContent`」。实现是后者，注释是前者。建议顺手清理，避免后续维护者被误导。

### P1-4 `UpsertProgress` 每次上报 3–4 条独立 SQL，无事务

`internal/service/library.go:133-180`

```go
_, err := s.db.Exec(`INSERT INTO reading_states ... ON CONFLICT ...`)   // 1
...
if delta > 0 {
    s.db.Exec(`UPDATE reading_states SET read_seconds = read_seconds + ? ...`)  // 2
    s.db.Exec(`INSERT INTO reading_daily ... ON CONFLICT ...`)                  // 3
}
st, err := s.getStateLocked(u.Path)     // 4：再 SELECT 回来
```

阅读进度在 Android 端是周期性上报的（每几秒一次，且离线补传会分片连续提交）。每次上报 3–4 次独立的 statement 往返，加上最后的 `SELECT` 回读。

**修复方向**：
- 包进单个 `tx`（`Begin` → 3 条语句 → `Commit`），把 3 次提交开销压成 1 次；
- 用 `RETURNING`（modernc.org/sqlite 支持 SQLite 3.35+）把回读合并进 upsert，省掉第 4 次往返；
- `read_seconds` 与 `reading_daily` 两条 UPDATE 可以合成一条 CTE。

### P1-5 `GetStatsSummary` 三条独立查询

`internal/service/library.go:251-267`

today / week / total 三条 `QueryRow` 串行执行。统计端点被 `BookshelfScreen` 调用，可以合成一条带条件聚合的查询（`SUM(CASE WHEN day = ? THEN seconds END)`），或在同一个读事务里跑。

### P1-6 `TagsService` 的写操作用的是 `s.mu.RLock()`

`internal/service/tags.go:196`（`CreateTag`）、`222`（`DeleteTag`）、`246`（`AssociateFile`）、`267`（`DisassociateFile`）

```go
func (s *TagsService) CreateTag(name, color string) (*models.FileTag, error) {
    s.mu.RLock()                        // ← 写操作拿读锁
    defer s.mu.RUnlock()

    var count int
    err := s.db.QueryRow("SELECT COUNT(*) FROM tags WHERE LOWER(name) = LOWER(?)", name).Scan(&count)
    if count > 0 { return nil, fmt.Errorf("tag already exists") }

    _, err = s.db.Exec("INSERT INTO tags (id, name, color) VALUES (?, ?, ?)", ...)
```

两个后果：

1. **RWMutex 的读锁不互斥写**。`s.mu` 对这些写路径等于没有保护，两个并发 `CreateTag` 可以同时通过 COUNT 检查，都执行 INSERT。SQLite 的 `UNIQUE(name)` 会挡住第二个（返回 error 而不是 "tag already exists"），但错误语义就错了。`AssociateFile` 同理（不过它靠 `PRIMARY KEY (tag_id, file_path)` 兜底）。
2. 即使改成写锁，**COUNT + INSERT 两次往返也是多余的**。

**修复方向**：改用 `INSERT OR IGNORE` 并检查 `RowsAffected`，一次往返同时完成「去重 + 插入 + 冲突判定」，然后按需把 `RLock` 收紧为 `Lock`（或干脆去掉——SQLite 侧已经由 WAL 写锁串行化）。

---

## P2 — 值得顺手做，但收益有限

### P2-1 扫描热循环里每个文件 3 次带锁 map 查询

`internal/service/scanner.go:232-236`

```go
s.mu.RLock()
isVideo := s.videoExts[ext]
isImage := s.imageExts[ext]
isText := s.textExts[ext]
s.mu.RUnlock()
```

`videoExts` / `imageExts` / `textExts` 在构造后不再变更（`newScanner` 里一次性填充），`VideoExts()` 等导出的也只是 map 本身。10 万文件规模下这是 10 万次 RLock/RUnlock，虽然单次是原子操作级别，但会与 `Scan` 末尾的写锁（`scanner.go:367-378`）争用。

**修复方向**：在 `Scan` 开头把三个 map 取一次到局部变量，循环内直接查（或改 `atomic.Pointer[map[string]bool]`）。

### P2-2 `serveThumbnailBytes` 每次请求重算 SHA256 + 重复 `os.Stat`

`internal/server/handler/handler.go:163-186`

```go
sum := sha256.Sum256(thumbBytes)      // ← memCache 命中也要重算
etag := `"` + hex.EncodeToString(sum[:]) + `"`

sourceMod := time.Time{}
if fi, err := os.Stat(sourcePath); err == nil {   // ← 又一次 stat
```

字节来自 `memCache`，其 key 已经是 `sha256(path + modTime)`（`thumbnail.go:398-401`），ETag 完全可以和字节一起缓存。而且 `GenerateThumbnailBytes` 内部刚刚 `os.Stat` 过同一个路径（`thumbnail.go:634`），这里的第二次 stat 是冗余的。单个开销在微秒级，属于「顺手能省」。

### P2-3 限流中间件的 LRU 淘汰是持锁 O(n) 全表扫描

`internal/server/middleware/ratelimit.go:79-112`

桶满（默认 4096）后，每个新 IP 请求都要在**持有全局 `mu`** 的情况下遍历全部 4096 个桶选淘汰对象。`server.go:64` 已经用 `ExtractIPDirect()` 堵掉了伪造 `X-Forwarded-For` 的路径，所以实际风险有限；但 IPv6 局域网或多客户端场景下仍会周期性触发，而此时**所有被限流的路由会串行等待**。

**修复方向**：容量降到 1024；或改成「随机采样 K 个选最旧」（Redis 的近似 LRU），把 O(n) 降到 O(K)。

### P2-4 `reverseLookupManifest` 对每个图片块线性扫整个 manifest

`internal/service/book.go:167-181`

```go
for id, href := range manifest {
    if bookparser.NormalizeHref(href) == normalized {
        return id
    }
}
```

每个图片块调用一次，每次 O(manifest 条目数) 并重复 `NormalizeHref`。含数百张图的 epub 单章渲染是 O(图片数 × manifest 条目数)。

**修复方向**：解析时一次性构建 `normalizedHref → manifestID` 反查表，挂在 `*Book` 上（和 `EpubManifest()` 同级缓存）。

---

## 已排查但判定「不该动」的项

记录一下，避免后续重复分析：

- **`RelativePath` 与 `Path` 重复导致 JSON 体积翻倍**（`folders.go:157`、`search.go:102`、`system.go:107` 都把 `RelativePath` 填成绝对路径）。初看像是可省的冗余字段，但 Android 端把它当作**下载 / 收藏的 identity key**（`DownloadsStore.kt`、`FavoritesStore.kt:38`、`DownloadManager.kt` 的 `workTag` 都依赖 `relativePath`）。**不能删**。真要减体积只能在客户端同步改造，收益不值这个联动成本。
- **`cacheByDir` 的拷贝 + 排序**（`scanner.go:511-522`）：每个 `/files` 请求做一次切片拷贝 + `sort.Slice`。单目录文件数通常几百，纳秒级，换来的并发安全是对的取舍。
- **`enforceDiskCacheCap` 每次落盘起一个 goroutine**（`thumbnail.go:333`）：节流判定在 goroutine 内部，绝大多数立即返回。goroutine 创建成本约 1µs，可接受。
- **`PreGenerateThumbnails` 的 `jobs` 通道容量 = `len(queue)`**（`thumbnail.go:573`）：整批入队会一次性拷贝全部 `MediaFile`，但队列本身已被 Tier 过滤到很小，且预热在后台。
- **`Echo` 中间件链顺序**（`server.go:205-268`）：`Recover` → `Logger` → `BodyLimit` → token redact → `Gzip` → `SecurityHeaders` → `CORS`，顺序正确且注释解释了 LIFO 语义，无需调整。
- **`web/static.go`**：ETag / 304 / CSS 合并 / `modulepreload` 已做完整，无待优化项。

---

## 建议的推进顺序

| 顺序 | 项 | 改动量 | 收益 |
|---|---|---|---|
| 1 | P0-1 软失效替代硬清缓存 | 小（~15 行） | 消除拷文件期间的浏览卡死 |
| 2 | P0-3 排序小写键预计算 | 小（~40 行） | 浏览请求分配 −59%，耗时 −36%（实测） |
| 3 | P0-2 批量缩略图并行化 | 中（~30 行） | 64 项批次从十几秒降到秒级 |
| 4 | P1-4 / P1-5 SQLite 事务合并 | 中 | 进度上报往返 4→1，统计 3→1 |
| 5 | P1-1 / P1-2 锁内 stat 外移 + 快照异步落盘 | 小 | 冷扫描争用与请求路径 fsync 消除 |
| 6 | P1-3 流缓冲池化 | 小 | 并发流内存 N×256KB → 复用 |
| 7 | P1-6 tags 写路径 | 小 | 修掉 TOCTOU + 省一次往返 |
| 8 | P2 系列 | 小 | 顺手清理 |

## 如何验证

```bash
cd server

# 排序热点（P0-3）——建议把原型固化为正式 benchmark 后接入 CI
go test ./internal/service/ -run '^$' -bench 'SortMediaFiles|NaturalCompare' -benchmem

# 已有基准，可作为回归基线
go test ./internal/service/ -run '^$' -bench . -benchmem
go test ./internal/server/  -run '^$' -bench 'Gzip' -benchmem

# 全量测试（改动后必跑）
go test ./...
```

P0-1 的行为验证建议手工做一次：往库根目录拷一个 ≥1GB 的文件，同时在 Web UI 里连续刷新目录列表，观察响应时间是否出现秒级抖动；修复后应稳定在毫秒级（最多返回 2 秒前的旧列表）。

---

## 附：修复记录（同日）

全部 P0 / P1 项与 P2-1 已修复。P2-2 / P2-3 / P2-4 经评估后判定不做，理由见本节末尾。

### 修复清单

| 项 | 状态 | 改动文件 |
|---|---|---|
| P0-1 缓存软失效 | 已修 | `service/scanner.go`、`scanner_test.go` |
| P0-2 批量缩略图并行 | 已修 | `server/handler/media.go` |
| P0-3 排序零分配 | 已修 | `service/naturalsort.go`、`naturalsort_test.go` |
| P1-1 锁内 stat 外移 | 已修 | `service/scanner.go` |
| P1-2 快照异步落盘 | 已修 | `service/scanner.go`、`scanner_test.go` |
| P1-3 流缓冲池化 | 已修 | `service/streaming.go` |
| P1-4 进度上报事务化 | 已修 | `service/library.go` |
| P1-5 统计合并查询 | 已修 | `service/library.go` |
| P1-6 tags 写锁 | 已修 | `service/tags.go` |
| P2-1 扫描热循环去锁 | 已修 | `service/scanner.go` |
| P2-2 缩略图 ETag | 不做 | — |
| P2-3 限流淘汰采样 | 不做 | — |
| P2-4 manifest 反查表 | 不做 | — |

### 关键实现说明

**P0-1 —— 软失效**

新增 `Scanner.softInvalidate()`：只归零 `cacheTime`，保留 `cache` / `cacheByDir` / `cacheDirs`。
`watchEvents` 改调它；`InvalidateCache()`（硬清空）保留给 `DeletePath` 与 admin 配置更新。

关键点：**不需要改任何读路径**。`GetCached` / `GetCachedByDir` / `GetCachedDirs` 三处的
「缓存是否可用」判定都是 `ok`（结构非 nil），只有 `fresh` 才决定是否后台刷新。所以只要结构还在，
软失效就自动落到既有的 stale-while-revalidate 分支 —— 请求立即拿到最多旧 2 秒的数据，
后台合并成一次重扫。

**P0-2 —— 并行化**

`errgroup.Group` + `SetLimit(min(4, NumCPU))`，按索引写回 `items[i]`（不同元素是不同内存位置，
无需同步），响应顺序与请求顺序恒等；per-item 错误仍编码在各自的 item 里，不会中断整批。
`ThumbnailService.sem`（容量 `NumCPU`）仍是真正的背压，这里只是不再一次性放行 64 个 ffmpeg。

顺带修掉一个隐患：原代码 `append(h.cfg.Scan.ImageExtensions, h.cfg.Scan.VideoExtensions...)`
在配置切片容量有余时会**写穿底层数组**，污染 `cfg`；并行化后多个请求同时执行该 append 就是真实
数据竞争。改为每次新建切片的 `thumbnailAllowedExts()`。

**P0-3 —— 排序**

拆出 `naturalCompareLowered`（要求入参已小写、零分配），`naturalCompare` 保留为包装函数
（测试在用，且供持有混合大小写字符串的调用方使用）。`SortMediaFiles` 改为
decorate-sort-undecorate。

实测（N=500，`-benchtime 1000x -count 2`）：

| | ns/op | allocs/op |
|---|---|---|
| 修复前 | 98,960 | 1,269 |
| 修复后 | 59,117 ~ 60,485 | **507** |
| `naturalCompareLowered` 单次 | 11.1 ~ 11.3 | **0** |

耗时 −40%，分配次数 −60%，已接近理论下限（≈ N 次 `strings.ToLower` + 常数）。

`B/op` 从 114 KB 升到 154 KB —— 但其中约 60 KB 是 benchmark 每轮为保持输入顺序所做的 fixture
拷贝（已在基准注释里标注），排序本身约 94 KB：用一次大切片分配换掉了 760 次小对象分配。

试过 `slices.SortStableFunc` 后**回退**：实测 70 µs vs 65 µs，更慢。`sortEntry` 是 152 B 的结构，
泛型排序的元素交换成本高于 `sort.SliceStable` 的 `reflect.Swapper` 路径。

**P1-2 —— 异步落盘，且退出不丢**

节流判定（`lastSnapWrite`）保持在锁内同步完成 —— 放进 goroutine 会让并发调用同时通过检查，
节流彻底失效。marshal + fsync 移到 goroutine。

新增 `snapshotWG`，`Shutdown()` 等待在途写入落盘，所以「进程退出丢快照」这个 trade-off
实际上被消除了，而不只是被接受。

**P1-6 —— tags**

`CreateTag` **保留** `COUNT` 预检：`tags.name` 的 UNIQUE 约束是 **BINARY** collation，而业务语义是
**NOCASE**（"Tag" 与 "tag" 视为同名），换成 `INSERT OR IGNORE` 会放行大小写不同的重名标签。
所以这里只改锁（`RLock` → `Lock`），修掉 check-then-act 的 TOCTOU。

`AssociateFile` 的「是否已关联」判定口径与主键 `(tag_id, file_path)` 完全一致，可以安全换成
`INSERT OR IGNORE` + `RowsAffected` —— 一次往返同时完成去重、插入与结果判定。
注意 `OR IGNORE` 只吞 UNIQUE / PK / NOT NULL / CHECK 冲突，**不吞外键冲突**，所以 `tag_id`
不存在时仍会像原来一样返回错误。

### 未做项与理由

- **P2-2（缩略图 ETag 重算 + 重复 `os.Stat`）**：省掉一次 `os.Stat`（OS cache 命中约 5 µs）
  需要给 `GenerateThumbnailBytes` 增加 modTime 返回值并改 5 个调用点；`sha256` 本身对 30 KB 约
  30 µs，而且 ETag 值不能变（会作废客户端已缓存的校验器）。收益不抵接口膨胀。
- **P2-3（限流 LRU 持锁 O(n) 扫描）**：4096 桶的**确定性**淘汰是刻意设计（`ratelimit.go` 的注释
  详细论证了 expired-first → oldest lastSeen → insertion seq 的优先级，`ratelimit_test.go:129`
  也依赖它）。改成随机采样会推翻这个契约。而且 `server.go` 已用 `ExtractIPDirect()` 堵掉了伪造
  `X-Forwarded-For`，真实局域网触达 4096 个不同客户端 IP 的概率极低。
- **P2-4（epub manifest 反查表）**：单章的 O(图片数 × manifest 条目数) 绝对量在几十微秒；
  消除它需要给 `bookparser.Book` 加惰性索引字段（引入同步原语或改解析流程），与收益不成比例。

### 验证

```
go build ./...                     # OK
go vet ./internal/server/...       # 干净
go test ./...                      # 仅 2 个既有失败
```

- `internal/service` 的 2 个失败是 `TestResolveWithinRootsRejectsSymlinkEscape` /
  `TestResolveWithinRootsRejectsInRootSymlink`，属**既有环境问题**（Windows 创建符号链接需开发者
  模式）。`git status` 确认 `path.go` / `path_test.go` 未被本次改动触及。
- 新增测试 `TestSoftInvalidateKeepsCacheReadable`，守护 P0-1 的契约：软失效后 `cache` 可读、
  标记为 stale、`cacheByDir` 保留（`hasDirIndex()` 仍为 true），而 `InvalidateCache` 仍清空一切。
- 新增基准 `BenchmarkSortMediaFilesName` / `BenchmarkSortMediaFilesNumeric` /
  `BenchmarkNaturalCompareLowered`，守护 P0-3 的 O(N) 分配特性。
- 因实现语义变更（同步 → 异步落盘）而同步调整的既有测试：
  `TestScannerWritesSnapshotAfterScan` / `TestScannerSnapshotWriteThrottle` ——
  在 `Scan` 后补 `sc.snapshotWG.Wait()`，**断言本身未改**。

