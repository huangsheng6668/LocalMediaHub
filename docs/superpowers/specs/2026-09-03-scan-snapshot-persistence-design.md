# Scan Snapshot Persistence Design（冷启动免全量遍历）

**Date:** 2026-09-03
**Topic:** 2026-09-03 性能评估的 P1 项：扫描结果快照持久化。进程重启后首请求不再同步 WalkDir 全量遍历，改为从磁盘快照瞬时 hydrate 内存缓存，并复用既有 stale-while-revalidate 语义完成后台校正。

## 1. Background & Motivation

现状证据（`server/internal/service/scanner.go`）：

- 扫描缓存**全内存**（`cache` / `cacheByDir` / `cacheDirs` / `cacheDirMap`），进程重启即失忆；`GetCached` 首次 miss 走**同步** `Scan`（L391-399，singleflight 合并但调用方仍阻塞整次 WalkDir）。
- `Scan` 与 `StartWatching` 每次启动都要 `filepath.WalkDir` 全量遍历（L192 / L648）。数万文件的库在机械盘上冷启动第一次浏览 = 秒级到分钟级阻塞。
- 仓库已有同模式先例：`durations.json`（ffprobe 结果）、`hot_directories.json`（访问计数）、tags SQLite（`.data/`）——本设计沿用"扫描结果落盘、启动回灌"的既有惯例。

## 2. Goals / Non-Goals

**Goals**

1. `Scan` 成功后把结果**原子落盘**到 `.data/scan_snapshot.json`（temp + rename，同 `config.Save` 模式）。
2. 启动时 **hydrate**：快照身份（roots + 三组扩展名）与当前配置全等 → 直接重建内存缓存，首请求**零磁盘遍历**。
3. 复用 stale-while-revalidate：hydrate 置 `cacheTime = SavedAt`——快照未过期则连后台扫描都不跑；过期则首个请求自动触发后台重扫校正（**零新增刷新逻辑**）。
4. 一切异常静默退回现状：身份不匹配 / JSON 损坏 / 写失败 → 走原有同步扫描路径。

**Non-Goals**

- 停机期间 fsnotify 事件"回放"——后台全量重扫即可校正，语义更简单（快照期间文件增删由 stale 路径兜底；播放时文件不存在由既有 404 处理）。
- 快照压缩 / 加密（本机私有文件，与 `.data/` 内 SQLite 同姿态）。
- 跨机器快照迁移（绝对路径绑定本机，身份键自然拦截）。
- config 新字段（快照路径用常量，YAGNI）。

## 3. Design

### 3.1 快照文件与身份键

路径：`.data/scan_snapshot.json`（`MkdirAll`，与 tags DB 同目录）。

```
type scanSnapshotFile struct {
    Version    int                  // 1；未来格式变更时递增，load 拒绝未知版本
    Roots      []string             // clean + sort 后的 roots —— 身份键之一
    VideoExts  []string             // sort 后扩展名 —— 身份键之二/三/四
    ImageExts  []string
    TextExts   []string
    SavedAt    time.Time            // hydrate 时回填 cacheTime（TTL 基准）
    Files      []models.MediaFile   // cache["all"]（Scan 已按 Path 排序）
    Dirs       map[string]time.Time // cacheDirMap 原样（目录 mtime 无法从 Files 派生）
}
```

身份校验：`Roots` 与三组扩展名**全等比较**（sorted clean）。任一不匹配 → 忽略快照 + INFO 日志（配置改过 roots / 扩展名，快照自然换代）。

### 3.2 写入（Scan 尾部，成功路径）

- `Scan` 完成缓存交换后同步写（100k 文件 ≈ 15MB JSON、marshal 数百 ms；扫描本身低频：boot / TTL 过期 / fsnotify 防抖 / admin 触发）。在后台 goroutine 的 Scan 里同步写不阻塞任何请求路径；同步 `GetCached` miss 路径的 Scan 会在返回前多付一次 marshal——可接受，换取实现简单。
- **30s 最小写间隔**：拷贝媒体时 fsnotify 2s 防抖会连续触发扫描，无间隔保护 = 持续 15MB 级重写。间隔内跳过（字段 `lastSnapWrite`，mu 保护）。间隔内跳过的最后状态不落盘——最多丢 30s 的变更，下轮扫描补上。
- 原子写：temp 文件同目录 + rename（Windows MoveFileEx REPLACE_EXISTING，同 `config.Save`）。
- 写失败仅 `WARN`，绝不让扫描失败。

### 3.3 Hydrate（StartWatching 尾部）

- `StartWatching` 成功启动 watcher 后尝试 load：身份匹配 → 重建 `cache["all"/"video"/"image"/"text"]`（从 Files 派生分型）、`cacheDirs`（Dirs 键排序）、`cacheDirMap`（Dirs）、`cacheByDir`（从 Files 按父目录分组），并置 `cacheTime = SavedAt`。
- **不触发 `OnScanComplete`**：缩略图磁盘缓存本就跨重启持久，boot 预热可跳过；快照过期时后台重扫会自然触发回调（与现状一致）。
- hydrate 与首个用户请求之间无竞态：`StartWatching` 在 server 开始监听前完成（main.go 顺序）。

### 3.4 失效与生命周期

- `InvalidateCache()` 只清内存，**不删快照文件**（运行时失效与启动加速互不相干）。
- `Shutdown()` 不强写快照（见 3.2 的 30s 窗口说明）。
- `UpdateConfig` 改 roots → `InvalidateCache` + 后续 `Scan` 以新 roots 覆盖快照，身份自动换代。

## 4. Security Review

- 快照含绝对路径，敏感级与 `.data/` 内 tags DB / durations.json 相同，不新增暴露面；无新端点。
- hydrate 数据视为**不可信输入**：JSON 解码失败 / 版本未知 → 弃用；快照中的路径仅作为列表数据返回，任何实际文件访问仍走路径校验三件套（`ValidatePath` / `ValidateSystemMediaAccess` / `ValidateAccessibleMediaPath`）——快照不可能绕过边界。
- 写入仅发生在服务进程内部，内容无用户可控字段注入（路径来自 WalkDir 结果）。

## 5. Testing Plan

- **snapshot 单测**（`scanner_snapshot_test.go`）：save→load roundtrip（Files/Dirs 保真 + 排序）；损坏 JSON → load 失败；roots 不匹配 → 弃用；扩展名不匹配 → 弃用；未知 Version → 弃用。
- **scanner 集成**（`scanner_test.go` 扩展）：Scan 后快照文件存在；新 Scanner hydrate 后 `GetCached` 零遍历（**删除源目录后仍能从快照读出列表** = 不可能来自 WalkDir）；`cacheTime=SavedAt` TTL 语义（旧快照 → `cachedSnapshot` fresh=false）；30s 写间隔防抖（第二次紧邻 Scan 不重写）。
- 回归：既有 scanner 测试全部不动照旧通过；`cd server && go test ./...`。

## 6. Rollout / Compatibility

- 默认启用，无 config 字段（路径常量 `.data/scan_snapshot.json`）。
- 存量用户首启无快照 → 行为与今天完全一致；首次 Scan 后自动生成。
- 纯服务端变更，Android / Web 零改动。

## 7. Task 分解（供 plan 文档展开）

1. `scanner_snapshot.go`：结构体 + 原子 save + 身份校验 load + 单测
2. `scanner.go` 集成：`NewScannerWithSnapshot` 构造变体 + Scan 尾写（30s 间隔）+ StartWatching hydrate + 集成单测
3. `server.go` 接线切到新构造 + `docs/INDEX.md` / `AGENTS.md` 更新 + 全量验证
