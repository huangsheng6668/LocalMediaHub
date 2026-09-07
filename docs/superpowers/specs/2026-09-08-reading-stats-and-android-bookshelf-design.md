# 阅读时长统计 + Android 独立书架页（阅读器阶段 4 收尾）设计

> 2026-09-08 brainstorming 定稿。范围：A 阅读时长统计（双端，服务端同步）+ B Android 独立书架页。
> 前置：阶段 4 的书架卡片化（Web `#/bookshelf` b129647 + Android 首页 BookshelfCard）与离线缓存（5e840ed）已完成；本 spec 只补剩余的"阅读统计"与 Android 书架承载页。

## 1. 背景与目标

- **A 阅读时长统计**：现状双端均无阅读时长记录。目标：记录每本书累计阅读秒数（服务端 `reading_states` 持久，双端一致），书架卡片显示"已读 X 小时"，提供今日/本周聚合。
- **B Android 独立书架页**：现状书架只是首页一个横排预览（`BookshelfCard`，无入口看全量）。目标：新路由 `bookshelf` 的网格书架页，承载全部最近书籍 + 状态筛选（在读/读完/收藏）+ 阅读时长展示 + 今日/本周统计条。

## 2. 非目标

- Web 书架页全面接 states/favorites 重构（Web 只加"已读时长"显示）
- epub 真实封面提取（双端保持渐变封面）
- 阅读目标/连击等激励功能
- 视频/图片的时长统计（只统计文本阅读）

## 3. 数据模型（服务端）

### 3.1 reading_states 加列

```sql
ALTER TABLE reading_states ADD COLUMN read_seconds INTEGER NOT NULL DEFAULT 0;
```

- 新库：建表 SQL 直接含该列；已有库：启动时 `PRAGMA table_info(reading_states)` 检测缺列则 ALTER（幂等，与现有 JSON→SQLite 迁移同风格，见 `library.go`）。

### 3.2 新表 reading_daily（按日聚合，支撑今日/本周）

```sql
CREATE TABLE IF NOT EXISTS reading_daily (
    path TEXT NOT NULL COLLATE NOCASE,
    day TEXT NOT NULL,            -- 服务器本地日期 'YYYY-MM-DD'
    seconds INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (path, day)
);
CREATE INDEX IF NOT EXISTS idx_reading_daily_day ON reading_daily(day);
```

- `day` 由服务端取自己本地日期（LAN 单用户场景，无需客户端传时区）。

### 3.3 API 变更

- `ProgressUpdate` 加 `ReadSecondsDelta int64 json:"read_seconds_delta"`（默认 0，旧客户端零影响）。
- `UpsertProgress`：同一事务内 `read_seconds = read_seconds + delta` 与 `reading_daily` upsert（`ON CONFLICT(path, day) DO UPDATE SET seconds = seconds + excluded.seconds`）。
- **delta 防护**：负数忽略（按 0）；`> 3600` clamp 到 3600（正常心跳间隔下 delta 为分钟级，异常大值视为客户端 bug，clamp 而非 400，容错且防刷爆）。
- `ReadingState` 与 `ReadingStateBadge` 加 `ReadSeconds int64 json:"read_seconds"`（decorations 批量响应自动携带）。
- 新端点 `GET /api/v1/library/stats/summary` → `{ "today_seconds": n, "week_seconds": n, "total_seconds": n }`。week = 近 7 天含今日（SUM over reading_daily）；total = SUM(reading_states.read_seconds)。挂现有 library 路由组（Bearer / 开放模式跟随）。

## 4. Web 端改动

### 4.1 阅读计时上报

- 新模块 `readingTimer.js`：纯逻辑 start/stop/takeDelta（注入时钟，node:test 可测）。`document.visibilityState !== 'visible'` 时不计。
- 挂点：textReader 打开阅读器 start；`visibilitychange`（hidden→stop / visible→start）；退出阅读器 flush。
- 载荷：`computeReportPayload`（library.js 现有纯函数）加 `read_seconds_delta` 参数；章节切换的既有 `reportState`（textReader.js:564）携带 delta；另加 30s interval 心跳上报（长读同一章节也累计）。

### 4.2 书架显示

- `bookshelf.js`：loadAll 后用现有 decorations 设施批量 POST 涉及路径，卡片 meta 追加 `已读 {formatDuration}`；无数据/离线退回纯进度 meta。`renderSection`（dashboard 嵌入）同样处理。
- `formatDuration` 纯函数：`< 60min → "X 分钟"`；`≥ 1h → "X.X 小时"`。

## 5. Android 端改动

### 5.1 阅读计时上报

- `ReadingSessionTimer`（注入时钟，纯逻辑）：TextReader `onResume` start / `onPause` stop + takeDelta。
- 上报：现有 write-through 进度上报载荷加 `read_seconds_delta`；停表时 flush 一次。
- 离线：DataStore `pendingReadDelta` 累积，`LibrarySyncManager` 连线补传；补传按 ≤3600s 分片多次上报（规避服务端 clamp 截断丢数据）。
- `MediaRepository`：states 上报函数加 delta 参数；新增 `getStatsSummary()`。

### 5.2 独立书架页

- 新路由 `bookshelf`（MainActivity NavHost）+ 首页书架 SectionHeader 加"查看全部"入口。
- `BookshelfScreen` + `BookshelfViewModel`：
  - 数据 = RecentActivityStore 全量书籍（非首页 take 预览）+ decorations（status / read_seconds / favorites）合并；
  - `LazyVerticalGrid`（Compact 2 列 / Expanded 3 列），书瓦 = 渐变封面 + 标题 + 章节进度 + 状态徽标（在读/读完）+ 已读时长；
  - 筛选芯片：全部 / 在读 / 读完 / 收藏（筛选为纯函数可测，风格对齐 BrowseFilterChipsRow）；
  - 排序固定 `lastReadAt desc`；
  - 头部统计条：`今日 X 分钟 · 本周 X.X 小时`（stats summary）；
  - 空态复用 EmptyHomeStateCard 模式；
  - 点击书瓦 → `TextReaderActivity.newIntent`（离线书籍走既有智能路由）。

## 6. 测试策略

- **Server**（go test）：delta 累加 / 负数忽略 / clamp 3600 / reading_daily 按日聚合 / summary 端点 / 旧 schema 打开的幂等迁移。
- **Web**（node --test）：formatDuration、readingTimer（时钟注入）、computeReportPayload 含 delta。
- **Android**（testDebugUnitTest）：ReadingSessionTimer、筛选纯函数、BookshelfViewModel 状态组合。
- **手动验证清单**：双端读同一本书时长一致；离线阅读→连线增量补传；书架筛选/统计条；Web dashboard 书架嵌入区时长显示。

## 7. 安全

- summary 端点跟随 library 路由组既有鉴权（Bearer / 开放模式透传）。
- delta 服务端 clamp，无新用户输入面（path 走既有 validateTextPath / validateAnyEntryPath）。

## 8. 提交切分

1. `feat(server): reading-time tracking with daily aggregation and stats summary`
2. `feat(web): reading-time tracking, report and bookshelf duration display`
3. `feat(android): reading-time tracking and standalone bookshelf screen`
