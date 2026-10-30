# 服务端目录浏览缓存与批量缩略图并发（Phase C）

日期：2026-10-30
范围：`server/internal/server/handler/folders.go` / `media.go` / `browse_cache.go`（新增）/
`system.go` / `handler.go`
状态：已实施

## 问题与基线

用生产二进制在临时工作目录（`/tmp/lmh-perf`，独立 config.yaml，端口 8011）测得，
`curl -w time_total` 本机回环，warmup 后取中位：

| 场景 | 基线 | 问题 |
|---|---|---|
| 5000 文件目录 `browse` 第 1 页（page_size=100） | 12–17 ms | 每次请求都 `os.ReadDir` + 5000×`entry.Info()` + 全量排序，只为返回 100 项 |
| 5000 文件目录 `browse` 第 2 页 | 12 ms | Android 打开目录会连发 page=1、page=2，同样的全量扫描再跑一遍 |
| 5000 文件目录 `browse` 全量返回 | 24 ms | 同上（客户端 Web 不分页） |
| 434 文件目录 `browse` 单页 | 6.5 ms | 同上，随目录项数线性增长 |
| 批量缩略图 30 张冷图（POST /media/thumbnails） | **339 ms** | 图片解码是纯 CPU，但批量并发固定 4；本机 24 核，`ThumbnailService.sem` 容量 NumCPU，实际只用了 1/6 |
| 批量缩略图 30 张热图 | 22 ms | 热路径（memCache）正常，无需改动 |
| 单张缩略图冷生成 | ~45–66 ms | JPEG 解码 + Linear 缩放 + 编码，已是既有优化后的成本 |

关键结论：浏览接口的「全量扫描 + 排序」在每次翻页/重进目录时重复执行，且不利用
目录 mtime 判断是否需要重扫；批量缩略图的瓶颈不是算法而是并发度被固定为 4，
图片与视频共用同一条限流。

## 方案

### C1 目录浏览结果缓存（LRU + mtime/TTL + singleflight）

新增 `server/internal/server/handler/browse_cache.go`：

- 键：`PathKey(目录) + "|" + sort + "|" + order`（排序参数的组合很少，缓存按组合隔离）。
- 值：`[]models.Folder` + `[]models.MediaFile`（已排序）+ 目录 ModTime + 写入时刻。
- 命中条件：`目录 ModTime 相同 && now-storedAt < browseCacheTTL(5s)`。目录增删通常会
  推进 mtime 从而立即失效；但 NTFS 目录 mtime 取自 ~15.6ms 的系统时钟 tick，若新文件
  与缓存写入落在同一 tick 内则 mtime 不变（实测复现），因此 **TTL 是硬性陈旧上限**，
  mtime 只是常见的快速失效路径；原地修改文件内容不改变目录 mtime，同样由 TTL 兜底。
- 容量：最多 128 个目录、合计 100k 个文件条目，超限按 LRU 淘汰（`container/list`）。
- 并发：`singleflight.Group`（键同上）合并同一目录的并发 miss，避免 page=1 与
  page=2 同时到达时各扫一遍。
- `BrowseFolder` 仍先做 `ResolveBrowsePath` + `os.Stat`（安全边界与 mtime 来源不变），
  命中后直接对缓存切片分页，不再 `os.ReadDir`。
- 失效：`DeletePath` 成功后调用 `InvalidateTree`——删除文件使父目录条目失效，删除
  目录则失效该目录及其所有后代条目（缓存 ≤128 项，线性扫描即可）。

### C2 批量缩略图按媒体类型分组并发

`MediaThumbnails` 现在把图片与视频塞进同一个 `errgroup`（上限
`maxBatchThumbnailConcurrency=4`）。改为：

- 图片（含校验失败/未知扩展名）单独一组，并发 `min(16, NumCPU)`：纯 Go 解码是
  CPU-bound，`ThumbnailService.sem`（容量 NumCPU）仍是总量兜底。
- 视频保持 4：一次 miss 会 fork ffprobe + ffmpeg，进程创建与磁盘 IO 成本高，
  不应随请求放大。

## 非目标（留待后续）

- 缩略图解码算法本身（stdlib JPEG / imaging Linear）不改——已是既有 benchmark
  验证过的选择。
- 批量响应仍为 base64 JSON（客户端已按此实现，改二进制协议需双端同步）。
- `GetCachedByDir`（scanner 索引）已按目录缓存，不在本次范围。
- 服务端搜索、扫描器快照不在本次范围。

## 验证

- 新增单测：
  - `browse_cache_test.go`：TTL 内命中、目录 mtime 变化失效、TTL 过期失效、
    文件数超限 LRU 淘汰、`InvalidateTree` 清除后代条目。
  - `folders_test.go`：新增「目录内新增文件后再次 Browse 能看到新文件」（写文件后显式
    `os.Chtimes` 制造 mtime 变化，规避上述 tick 竞态，保证测试确定性）；
    「同一目录两次 Browse 命中缓存且结果一致」；`DeletePath` 后缓存失效。
  - `media_thumbnails_test.go`：图片/视频分组并发上限函数、视频扩展名判定。
- `cd server && go test ./...`。
- 复测同一临时环境：5000 文件目录 page1/page2/full、30 张冷图批量、热图批量。

### 复测结果（2026-10-30，临时环境，Go HTTP 客户端单进程计时）

| 场景 | 改前 | 改后 |
|---|---|---|
| 5000 文件 page1（冷构建） | 12–17 ms | 9.5–11.4 ms（扫描成本不变，差异属噪声） |
| 5000 文件 page1（缓存命中） | 无此路径（每次全量重扫） | p50 3.5 ms |
| 5000 文件 page2 | 12 ms（全量重扫 + 排序） | p50 3.4 ms |
| 5000 文件全量列表（命中） | 24 ms | p50 13.6 ms（剩余为 1.5MB JSON 序列化） |
| 30 张冷图批量 | 339 ms | 170 ms |
| 30 张热图批量 | 22 ms | 14 ms |

正确性：缓存命中响应与冷构建响应逐字节一致；新文件写入后立即可见（mtime 变化路径），
未触发 mtime 变化时由 5s TTL 兜底；`DeletePath` 对缓存显式失效（单测在目录 mtime 被
刻意保持不变时验证），HTTP 实测删除后 5.3s 不再出现。
