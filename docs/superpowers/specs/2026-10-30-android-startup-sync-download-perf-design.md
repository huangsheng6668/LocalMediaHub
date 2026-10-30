# Android 启动、同步与下载链路性能设计（Phase D）

日期：2026-10-30
范围：Android 客户端（服务端 / Web 不动）

## 背景与基线

Phase A/B/C 之后，剩余的三处可量化瓶颈：首页首屏网络串行、启动时库同步逐条串行、
批量下载入队与通知链路。真机（Pixel 6，debug 1.5.2，同网段 LAN，服务端处理 <1ms）
用服务端访问日志实测（2026-10-30 22:30:45 一次手动连接）：

```
22:30:45.626 GET /api/v1/folders        ← 链 A（HomeViewModel.refresh）
22:30:45.653 GET /api/v1/folders        ← 链 B（重复刷新）
22:30:45.677 GET /api/v1/tags
22:30:45.684 GET /api/v1/tags
22:30:45.731 GET /api/v1/tags/file-tags
22:30:45.733 GET /api/v1/tags/file-tags
```

结论：

1. **首屏三次请求严格串行**：`getFolders → getTags → getFileTags`，单次 Wi-Fi
   往返 25–50ms，一条链 ~80–105ms。
2. **一次连接会跑两条完整链**：`ConnectionViewModel.testConnection` 成功后
   `saveServerConfig()` 写 DataStore，`serverUrl` Flow（`map` 无去重）重新发射，
   `HomeViewModel` 的 collector 再次触发 `refresh()`。
3. **库同步逐条串行**：`LibrarySyncManager.syncOnce` 的进度迁移、收藏推送、阅读时长
   补传都是 `for` 循环逐条 HTTP。本机账号收藏为 0、pending 为 0，所以真机日志里看不到
   这部分成本；用单测在模拟 20ms RPC 下量化（见验证）。
4. **下载并发不是瓶颈**：WorkManager 2.9.0 默认执行器为
   `max(2, min(availableProcessors-1, 4))`（字节码确认），下载已有 4 路并发；
   `NotificationThrottle`（500ms + 百分比去重）已在既有实现中生效。
   真正的浪费在**批量下载入口**：每个选中文件一次 `viewModel.downloadFile()` →
   Main 线程上 N 个协程各自做 Gson 序列化 + `enqueueUniqueWork`，并弹 N 条
   "已加入后台下载队列" Toast。

## 方案

### D1 首屏刷新并行化 + 去重

`HomeViewModel`：

- `refresh()` 内三条独立请求改为 `coroutineScope { async… }` 并行，`isLoading`
  与错误语义不变（`getFolders` 失败仍设置 `errorMessage`）。
- 新增 `refreshJob`：新的 `refresh()` 取消上一次未完成的刷新，避免连接流程与
  订阅回调叠加出并发刷新。
- `serverUrl.collect` 增加 `distinctUntilChanged()`：相同地址的 DataStore 重写不再
  触发第二次全量刷新与 `ensureStarted`。这是本阶段收益最大的一条——直接消除
  一条完整链（~80–105ms）。

预期：连接后请求从「两条 3 串行链」变为「一条并发链」，单链耗时从 ~105ms
降到约一次 Wi-Fi 往返（冷启动需新建 3 条 TCP 连接，实测 0–53ms 内发出）。

> 实施后修正：`distinctUntilChanged()` 只消除**重复链**（6 → 3 个请求），首屏时延的下降
> 来自三请求并发。冷启动时第二条/第三条请求需要新开 TCP 连接，首尾间隔可能到 ~50ms
> 而非 <10ms；连接池已热时三条请求基本同毫秒发出（见「复测结果」）。

### D2 库同步有界并发

`LibrarySyncManager`：

- 新增 `internal suspend fun <T, R> Iterable<T>.mapBounded(limit, block)`：
  `Semaphore(limit)` + `async` + `awaitAll`，限流并发、保持取消传播。
- 三处逐条循环改为 `mapBounded(SYNC_CONCURRENCY = 4)`：
  1. 进度迁移上报（结果汇总为「全部成功才置迁移完成」）；
  2. 收藏全量推送（幂等 upsert，与顺序无关）；
  3. 阅读时长按书补传（**同一本书的分片仍串行**，只并行不同书）。
- 拉取/合并顺序不变：先全部推送，再 `mergeWithServerFavorites`。

非目标：不改同步的触发时机（仍为每进程一次）、不改服务端接口、不改失败重试语义。

### D3 批量下载入队合并

- `DownloadManager` 新增 `downloadFiles(files, videoStreamUrl, imageUrl): Int`：
  在 `Dispatchers.IO` 上一次完成全部 `enqueueUniqueWork`（Gson + Data.Builder 不再
  占用 Main），单文件路径保持不变。
- `DownloadController.downloadFiles(...)` 只回一条消息
  「已加入后台下载队列（N 项）」。
- `BrowseViewModel.downloadFiles(files)` 复用现有 URL 构造；`BrowseScreen`
  批量下载按钮改调一次。
- 下载 worker、通知与 Toast 保持现状（并发 4 路 + 500ms 节流已足够；
  聚合通知属于 UX 重设计，不在本期）。

## 非目标（留待后续）

- 单条聚合下载通知 / 单 WorkManager 任务承载批量清单（需把清单落盘绕开 Data 10KB 限制）。
- 覆盖 WorkManager 默认执行器（全局配置，影响所有后台任务）。
- 服务端 ZIP 打包链路（已流式 `zip.Store`，无缓冲放大）。
- `DownloadsStore` 的 O(n) JSON 重解析（DataStore stringSet 设计使然，量级小）。

## 验证

- 新增单测：
  - `mapBounded`：20 项 × 模拟 20ms 延迟，limit=4 时总耗时显著低于串行、
    最大并发 ≤ limit、结果完整且保持输入顺序。
  - `HomeViewModel` 相关纯逻辑若可测则复用现有测试；主要靠真机日志验证。
- `cd android && ./gradlew testDebugUnitTest assembleDebug`（版本 1.5.3 / code 10）。
- 真机复测（同一服务端、同一网络）：
  1. force-stop → 启动 → 首屏请求时间线：应只有一条链且三条请求时间戳相差 <10ms
     （冷启动需新建 TCP 连接时以 <60ms 为界；连接池已热时应 <10ms）。
  2. 手动连接一次：请求总数应为 3（folders / tags / file-tags）而不是 6。
  3. 批量下载 N 个文件：Main 线程无长任务（`dumpsys gfxinfo` 帧统计），
     Toast 只出现一条入队提示。

## 实施记录与复测结果（2026-10-30）

实施内容与上文 D1/D2/D3 一致（APK 1.5.3 / versionCode 10）。真机 Pixel 6 → 服务端
192.168.31.230:8000（Open 模式，服务端处理 <1ms），清空 App 的 OkHttp 缓存后冷启动：

```
22:41:47.401 GET /api/v1/tags
22:41:47.401 GET /api/v1/folders
22:41:47.454 GET /api/v1/tags/file-tags      (+53ms)
22:41:47.463 GET /api/v1/library/favorites   (LibrarySyncManager 收藏拉取)
22:41:47.626 GET /api/v1/health              (自动连接)
22:41:48.028 POST /api/v1/pair 403           (无 lan_pairing)
```

- **D1 首屏并发**：三条请求并发发出，首尾 53ms（基线严格串行 105ms）。53ms 来自冷启动
  时第 3 条请求需要新建 TCP 连接；另一轮实测（tags 命中 HTTP 缓存未发请求）为 folders 与
  file-tags 同毫秒、tags +12ms。第二轮启动（缓存已热）同一毫秒内完成 folders/tags 派发。
  连接池已热且缓存过期后点刷新：`22:47:50.435 file-tags / .436 tags / .438 folders`，
  首尾 3ms，符合 <10ms 预期。
- **D1 去重**：`health` 成功后 `saveServerConfig()` 重写 DataStore（`known_servers` 携带新
  时间戳必然重新发射），日志中**不再出现**第二组 folders/tags/file-tags。修复前一次连接
  为 6 个请求（两条交错链），修复后为 3 个。
- **D2 有界并发**：两本待补传书的 `GET /library/states` 在 22:39:53.768 同一毫秒发出
  （修复前每本书要等上一本 GET+POST 完成）。单测 `mapBounded`：20 项 × 20ms、limit=4 →
  虚拟时间 100ms（串行 400ms），最大并发 4，结果顺序与输入一致。
- **D3 批量入队**：浏览目录「全选 100 个文件 → 下载所选」，只出现一条
  「已加入后台下载队列（100 项）」（截图验证）；随后 100 个 `GET /media/original` 由
  WorkManager 4 路并发在 ~3.2s 内取完。修复前是 100 次独立调用、100 条提示。
- **测试**：`cd android && ./gradlew testDebugUnitTest assembleDebug` 通过
  （410 个单测；期间一次与本改动无关的 `ReaderSettingsMigrationTest` Windows
  文件锁 flake，单独/重跑均通过）。
- 测量附带发现：`/api/v1/tags`、`/api/v1/tags/file-tags` 服务端返回
  `Cache-Control: private, max-age=300`，OkHttp 在 5 分钟内直接命中磁盘缓存（不会产生
  服务端访问日志），因此复测需清空 `cache/okhttp` 或等缓存过期才能看到完整三条请求。
