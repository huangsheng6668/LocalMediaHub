# 阅读时长统计 + Android 独立书架页 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 spec `docs/superpowers/specs/2026-09-08-reading-stats-and-android-bookshelf-design.md`——服务端阅读时长持久（read_seconds + 按日聚合 + stats summary），双端计时上报，Android 新增独立书架页（网格 + 状态/收藏筛选 + 时长展示）。

**Architecture:** 服务端复用 `reading_states` upsert 管道（delta 独立于 lastReadAt 守卫累加）；Web 计时器纯逻辑模块 + textReader 接线（心跳/隐藏 flush）；Android 计时器 + DataStore pending 缓冲（离线累积、失败回退、连线分片补传）；书架页 = RecentActivityStore 全量书籍 + decorations 合并 + 纯函数筛选。

**Tech Stack:** Go (SQLite modernc) / 原生 ES Module JS (node:test) / Kotlin Compose (Hilt, DataStore)

## Global Constraints

- delta 防护：负数忽略、>3600 clamp 到 3600（服务端 `server/internal/service/library.go`）
- Android 补传分片 ≤3600s/次（`takePendingUpto(3600)`）
- week = 近 7 天含今日（`day >= today-6`，服务器本地日期 `2006-01-02`）
- Web 无 inline `<script>`/`style=` 属性（CSP）；涉及 innerHTML 的代码带 `// XSS-SAFE:` 注释
- Commit 风格 Conventional Commits，scope：`reader` / `web` / `android` / `server`
- 每 task 完成后跑该子系统测试；交付前组合：`go test ./...` + `node --test` + xsscheck + `gradlew testDebugUnitTest assembleDebug`

---

### Task 1: Server — read_seconds 列迁移 + UpsertProgress delta 累加

**Files:**
- Modify: `server/internal/models/library_models.go:6-33`（ProgressUpdate / ReadingState）
- Modify: `server/internal/service/library.go:48-76`（建表 + 迁移）、`:104-134`（UpsertProgress）、`:201-229`（getStateLocked / scanReadingState）
- Test: `server/internal/service/library_test.go`

**Interfaces:**
- Produces: `models.ProgressUpdate.ReadSecondsDelta int64 json:"read_seconds_delta"`；`models.ReadingState.ReadSeconds int64 json:"read_seconds"`；`UpsertProgress` 语义扩展（delta 累加不受 lastReadAt 守卫影响）。Task 2/3/5 依赖这些字段名。

- [ ] **Step 1: 写失败测试**（追加到 library_test.go，沿用现有临时库构造方式——参照文件内 `TestUpsertProgressInsert` 的 `newLibraryServiceForTest(t)` 或等价 helper；若 helper 名不同以现有代码为准）

```go
func TestUpsertProgressAccumulatesReadSeconds(t *testing.T) {
	s := newLibraryServiceForTest(t)
	now := time.Now().UnixMilli()
	s.UpsertProgress(models.ProgressUpdate{Path: "a.txt", Percent: 10, LastReadAt: now, ReadSecondsDelta: 30})
	s.UpsertProgress(models.ProgressUpdate{Path: "a.txt", Percent: 20, LastReadAt: now + 1000, ReadSecondsDelta: 45})
	st, err := s.GetState("a.txt")
	if err != nil || st == nil {
		t.Fatalf("get state: %v %v", st, err)
	}
	if st.ReadSeconds != 75 {
		t.Fatalf("read_seconds = %d, want 75", st.ReadSeconds)
	}
}

func TestUpsertProgressDeltaClampAndNegative(t *testing.T) {
	s := newLibraryServiceForTest(t)
	now := time.Now().UnixMilli()
	s.UpsertProgress(models.ProgressUpdate{Path: "a.txt", Percent: 10, LastReadAt: now, ReadSecondsDelta: 999999})
	s.UpsertProgress(models.ProgressUpdate{Path: "a.txt", Percent: 20, LastReadAt: now + 1000, ReadSecondsDelta: -50})
	st, _ := s.GetState("a.txt")
	if st.ReadSeconds != 3600 {
		t.Fatalf("read_seconds = %d, want 3600 (clamped, negative ignored)", st.ReadSeconds)
	}
}

func TestReadingDailyAggregates(t *testing.T) {
	s := newLibraryServiceForTest(t)
	now := time.Now().UnixMilli()
	s.UpsertProgress(models.ProgressUpdate{Path: "a.txt", Percent: 10, LastReadAt: now, ReadSecondsDelta: 60})
	s.UpsertProgress(models.ProgressUpdate{Path: "b.txt", Percent: 10, LastReadAt: now, ReadSecondsDelta: 90})
	var sec int64
	today := time.Now().Format("2006-01-02")
	err := s.db.QueryRow(`SELECT COALESCE(SUM(seconds),0) FROM reading_daily WHERE day = ?`, today).Scan(&sec)
	if err != nil || sec != 150 {
		t.Fatalf("daily sum = %d err=%v, want 150", sec, err)
	}
}

func TestDeltaBypassesLastReadAtGuard(t *testing.T) {
	s := newLibraryServiceForTest(t)
	now := time.Now().UnixMilli()
	s.UpsertProgress(models.ProgressUpdate{Path: "a.txt", Percent: 50, LastReadAt: now, ReadSecondsDelta: 60})
	// 陈旧上报（lastReadAt 更旧）：progress 被 WHERE 守卫拒绝，delta 仍须累加（离线补传场景）
	s.UpsertProgress(models.ProgressUpdate{Path: "a.txt", Percent: 10, LastReadAt: now - 5000, ReadSecondsDelta: 120})
	st, _ := s.GetState("a.txt")
	if st.ReadSeconds != 180 {
		t.Fatalf("read_seconds = %d, want 180 (delta bypasses guard)", st.ReadSeconds)
	}
	if st.Percent != 50 {
		t.Fatalf("percent = %v, want 50 (stale progress rejected)", st.Percent)
	}
}

func TestMigrateAddsReadSecondsColumn(t *testing.T) {
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "lib.db")
	db, err := sql.Open("sqlite", dbPath)
	if err != nil {
		t.Fatal(err)
	}
	// 旧 schema：无 read_seconds 列
	_, err = db.Exec(`CREATE TABLE reading_states (
		path TEXT PRIMARY KEY COLLATE NOCASE,
		chapter_index INTEGER NOT NULL DEFAULT 0,
		para_index INTEGER NOT NULL DEFAULT 0,
		percent REAL NOT NULL DEFAULT 0,
		finished INTEGER NOT NULL DEFAULT 0,
		manual_status TEXT,
		last_read_at INTEGER NOT NULL DEFAULT 0,
		updated_at INTEGER NOT NULL DEFAULT 0)`)
	if err != nil {
		t.Fatal(err)
	}
	db.Close()
	s, err := service.NewLibraryService(dir) // 打开旧库触发迁移
	if err != nil {
		t.Fatalf("open legacy db: %v", err)
	}
	defer s.Close()
	var cnt int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM pragma_table_info('reading_states') WHERE name='read_seconds'`).Scan(&cnt); err != nil || cnt != 1 {
		t.Fatalf("read_seconds column missing after migration: cnt=%d err=%v", cnt, err)
	}
}
```

注意：library_test.go 在 `service` 包内部还是外部包——以文件顶部 `package` 声明为准；若为 `package service` 内部测试，去掉 `service.` 前缀直接 `NewLibraryService(dir)`，`s.db` 直接可访问。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && go test ./internal/service/ -run "ReadSeconds|ReadingDaily|DeltaBypasses|MigrateAdds" -v`
Expected: FAIL（`ReadSecondsDelta` 字段不存在 → 编译错误即失败）

- [ ] **Step 3: 实现**

`library_models.go`：

```go
// ProgressUpdate 末尾加：
	ReadSecondsDelta int64 `json:"read_seconds_delta"` // 本次新增阅读秒数（服务端累加，0 = 无）

// ReadingState 结构体里 UpdatedAt 之后加：
	ReadSeconds int64 `json:"read_seconds"`
```

`library.go` 建表 SQL：`reading_states` 的 CREATE TABLE 列清单 `updated_at` 行后加 `read_seconds INTEGER NOT NULL DEFAULT 0,`；同一 Exec 里追加：

```sql
CREATE TABLE IF NOT EXISTS reading_daily (
	path TEXT NOT NULL COLLATE NOCASE,
	day TEXT NOT NULL,
	seconds INTEGER NOT NULL DEFAULT 0,
	PRIMARY KEY (path, day)
);
CREATE INDEX IF NOT EXISTS idx_reading_daily_day ON reading_daily(day);
```

建表 Exec 之后追加迁移函数调用（对已存在的旧库 ALTER）：

```go
func ensureColumn(db *sql.DB, table, column, ddl string) error {
	var cnt int
	if err := db.QueryRow(
		`SELECT COUNT(*) FROM pragma_table_info(?) WHERE name = ?`, table, column,
	).Scan(&cnt); err != nil {
		return err
	}
	if cnt == 0 {
		if _, err := db.Exec(ddl); err != nil {
			return fmt.Errorf("migrate %s.%s: %w", table, column, err)
		}
	}
	return nil
}
```

在 NewLibraryService 建表 Exec 后调用 `ensureColumn(db, "reading_states", "read_seconds", "ALTER TABLE reading_states ADD COLUMN read_seconds INTEGER NOT NULL DEFAULT 0")`（失败返回 nil, err）。

`UpsertProgress` 在现有 upsert Exec 成功后、`getStateLocked` 前插入：

```go
	delta := u.ReadSecondsDelta
	if delta < 0 {
		delta = 0
	}
	if delta > 3600 {
		delta = 3600
	}
	if delta > 0 {
		// delta 累加独立于上方 lastReadAt 守卫：离线补传时 lastReadAt 可能陈旧，但时长增量仍有效
		if _, err = s.db.Exec(`UPDATE reading_states SET read_seconds = read_seconds + ? WHERE path = ?`, delta, u.Path); err != nil {
			return models.ReadingState{}, err
		}
		if _, err = s.db.Exec(`INSERT INTO reading_daily (path, day, seconds) VALUES (?, ?, ?)
			ON CONFLICT(path, day) DO UPDATE SET seconds = seconds + excluded.seconds`,
			u.Path, time.Now().Format("2006-01-02"), delta); err != nil {
			return models.ReadingState{}, err
		}
	}
```

（time 包已导入；`SetManualStatus` 的 'unread' 重置分支不动 read_seconds——时长是历史事实，手动标记未读不清零。）

`getStateLocked` 的 SELECT 列清单 `updated_at` 后加 `, read_seconds`；`scanReadingState` 的 Scan 参数列表 `&st.UpdatedAt` 后加 `&st.ReadSeconds`。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && go test ./internal/service/ -run "ReadSeconds|ReadingDaily|DeltaBypasses|MigrateAdds" -v`
Expected: PASS 全绿

- [ ] **Step 5: 全包回归 + commit**

Run: `cd server && go test ./...`
```bash
git add server/internal/models/library_models.go server/internal/service/library.go server/internal/service/library_test.go
git commit -m "feat(server): read_seconds persistence with daily aggregation and legacy-db migration"
```

### Task 2: Server — stats summary 端点 + decorations 带 read_seconds

**Files:**
- Modify: `server/internal/models/library_models.go`（StatsSummary）
- Modify: `server/internal/service/library.go:336-380`（BatchDecorations SELECT/Scan）
- Create (in existing): `server/internal/server/handler/library.go` 加 GetStatsSummary
- Modify: `server/internal/server/server.go:371-378`（路由）
- Test: `server/internal/service/library_test.go`

**Interfaces:**
- Produces: `GET /api/v1/library/stats/summary` → `{"today_seconds":n,"week_seconds":n,"total_seconds":n}`；decorations badge 加 `read_seconds`。Task 3/5/7 依赖。

- [ ] **Step 1: 写失败测试**

```go
func TestGetStatsSummary(t *testing.T) {
	s := newLibraryServiceForTest(t)
	now := time.Now().UnixMilli()
	s.UpsertProgress(models.ProgressUpdate{Path: "a.txt", Percent: 10, LastReadAt: now, ReadSecondsDelta: 600})
	s.UpsertProgress(models.ProgressUpdate{Path: "b.txt", Percent: 10, LastReadAt: now, ReadSecondsDelta: 60})
	sum, err := s.GetStatsSummary()
	if err != nil {
		t.Fatal(err)
	}
	if sum.TodaySeconds != 660 || sum.WeekSeconds != 660 || sum.TotalSeconds != 660 {
		t.Fatalf("summary = %+v, want all 660", sum)
	}
}

func TestBatchDecorationsIncludesReadSeconds(t *testing.T) {
	s := newLibraryServiceForTest(t)
	now := time.Now().UnixMilli()
	s.UpsertProgress(models.ProgressUpdate{Path: "a.txt", Percent: 10, LastReadAt: now, ReadSecondsDelta: 42})
	res, err := s.BatchDecorations([]string{"a.txt"})
	if err != nil {
		t.Fatal(err)
	}
	if badge, ok := res.States["a.txt"]; !ok || badge.ReadSeconds != 42 {
		t.Fatalf("badge read_seconds = %+v ok=%v, want 42", badge, ok)
	}
}
```

`ReadingStateBadge` 加字段：`ReadSeconds int64 json:"read_seconds"`。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && go test ./internal/service/ -run "StatsSummary|DecorationsIncludes" -v`
Expected: FAIL（GetStatsSummary 未定义 → 编译失败）

- [ ] **Step 3: 实现**

`library_models.go`：

```go
// StatsSummary 是 GET /api/v1/library/stats/summary 的响应。
type StatsSummary struct {
	TodaySeconds int64 `json:"today_seconds"`
	WeekSeconds  int64 `json:"week_seconds"`
	TotalSeconds int64 `json:"total_seconds"`
}
```

`library.go`（GetState 后面加）：

```go
// GetStatsSummary 汇总阅读时长：今日/近7天（reading_daily）/ 累计（reading_states）。
func (s *LibraryService) GetStatsSummary() (models.StatsSummary, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	var out models.StatsSummary
	today := time.Now().Format("2006-01-02")
	weekAgo := time.Now().AddDate(0, 0, -6).Format("2006-01-02")
	queries := []struct {
		sql  string
		arg  string
		dest *int64
	}{
		{`SELECT COALESCE(SUM(seconds),0) FROM reading_daily WHERE day = ?`, today, &out.TodaySeconds},
		{`SELECT COALESCE(SUM(seconds),0) FROM reading_daily WHERE day >= ?`, weekAgo, &out.WeekSeconds},
		{`SELECT COALESCE(SUM(read_seconds),0) FROM reading_states`, "", &out.TotalSeconds},
	}
	for _, q := range queries {
		var row *sql.Row
		if q.arg != "" {
			row = s.db.QueryRow(q.sql, q.arg)
		} else {
			row = s.db.QueryRow(q.sql)
		}
		if err := row.Scan(q.dest); err != nil {
			return out, err
		}
	}
	return out, nil
}
```

`BatchDecorations`：states 查询 SELECT 列清单 `last_read_at` 后加 `, read_seconds`；Scan 参数与 badge 构造加：

```go
		var readSec int64
		// Scan(..., &lastAt, &readSec)
		res.States[p] = models.ReadingStateBadge{
			Status:      deriveStatus(finished == 1, manual, true),
			Percent:     pct,
			LastReadAt:  lastAt,
			ReadSeconds: readSec,
		}
```

`handler/library.go`（文件末尾加）：

```go
// GetStatsSummary: GET /api/v1/library/stats/summary — 今日/本周/累计阅读时长。
func (h *Handler) GetStatsSummary(c echo.Context) error {
	sum, err := h.library.GetStatsSummary()
	if err != nil {
		return respondInternalError(c, err)
	}
	return c.JSON(http.StatusOK, sum)
}
```

`server.go:378`（`lib.DELETE("/favorites", ...)` 行后）加：

```go
	lib.GET("/stats/summary", h.GetStatsSummary)
```

- [ ] **Step 4: 跑测试 + 回归 + commit**

Run: `cd server && go test ./...`
```bash
git add server/internal/models/library_models.go server/internal/service/library.go server/internal/service/library_test.go server/internal/server/handler/library.go server/internal/server/server.go
git commit -m "feat(server): reading stats summary endpoint and decorations read_seconds badge"
```

### Task 3: Web — readingTimer 纯逻辑 + reportState delta + decorations 导出

**Files:**
- Create: `server/internal/web/readingTimer.js`
- Create: `server/internal/web/readingTimer.test.mjs`
- Modify: `server/internal/web/library.js:237-259`（reportState）、`:107-120`（fetchDecorationsFor 导出）
- Modify: `server/internal/web/bookshelf.js`（formatReadDuration 导出）
- Test: `server/internal/web/bookshelf.test.mjs`（追加）

**Interfaces:**
- Produces: `createReadingTimer(now)` → `{ start(), stop(), takeDelta(), isRunning() }`；`reportState(path, { readSecondsDelta })`；`fetchDecorationsFor(paths)` 导出；`formatReadDuration(sec)` → `"X 分钟" / "X.X 小时"`。Task 4 依赖。

- [ ] **Step 1: 写失败测试**

`readingTimer.test.mjs`：

```js
import test from 'node:test';
import assert from 'node:assert/strict';
import { createReadingTimer } from './readingTimer.js';

test('takeDelta accumulates between start/stop and resets', () => {
    let t = 1000;
    const timer = createReadingTimer(() => t);
    timer.start();
    t = 31000;
    assert.equal(timer.takeDelta(), 30);
    t = 61000;
    timer.stop();
    assert.equal(timer.takeDelta(), 30);
    assert.equal(timer.takeDelta(), 0);
});

test('takeDelta while running keeps the clock alive', () => {
    let t = 0;
    const timer = createReadingTimer(() => t);
    timer.start();
    t = 60000;
    assert.equal(timer.takeDelta(), 60);
    t = 90000;
    assert.equal(timer.takeDelta(), 30);
});

test('start/stop are idempotent', () => {
    let t = 0;
    const timer = createReadingTimer(() => t);
    timer.start();
    timer.start();
    t = 5000;
    timer.stop();
    timer.stop();
    assert.equal(timer.takeDelta(), 5);
});
```

`bookshelf.test.mjs` 追加：

```js
test('formatReadDuration renders minutes below an hour, hours above', () => {
    assert.equal(formatReadDuration(0), '');
    assert.equal(formatReadDuration(45 * 60), '45 分钟');
    assert.equal(formatReadDuration(90 * 60), '1.5 小时');
    assert.equal(formatReadDuration(3660), '1.0 小时');
});
```

（import 行补 `formatReadDuration`。）

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server/internal/web && node --test readingTimer.test.mjs bookshelf.test.mjs`
Expected: FAIL（模块不存在 / 函数未导出）

- [ ] **Step 3: 实现**

`readingTimer.js`：

```js
// 纯逻辑阅读计时器：start/stop 括起活跃阅读时段，takeDelta 取走并清零已累积
// 秒数（运行中取走不停止计时）。时钟注入以便 node:test。
export function createReadingTimer(now = () => Date.now()) {
    let startTs = null;
    let pendingSec = 0;
    return {
        start() { if (startTs === null) startTs = now(); },
        stop() {
            if (startTs !== null) {
                pendingSec += Math.floor((now() - startTs) / 1000);
                startTs = null;
            }
        },
        takeDelta() {
            let d = pendingSec;
            pendingSec = 0;
            if (startTs !== null) {
                d += Math.floor((now() - startTs) / 1000);
                startTs = now();
            }
            return d;
        },
        isRunning() { return startTs !== null; },
    };
}
```

`library.js` `reportState`：函数体开头取 `const readSecondsDelta = payload.readSecondsDelta || 0;`，body JSON 对象 `last_read_at` 后加 `read_seconds_delta: readSecondsDelta,`。

`library.js` `fetchDecorationsFor` 前加 `export`（改声明 `async function` → `export async function`；内部调用点不变）。

`bookshelf.js` 导出（relativeTime 之后）：

```js
export function formatReadDuration(sec) {
    if (!sec || sec < 60) return '';
    if (sec < 3600) return `${Math.floor(sec / 60)} 分钟`;
    return `${(sec / 3600).toFixed(1)} 小时`;
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server/internal/web && node --test`
Expected: 全绿

- [ ] **Step 5: Commit**

```bash
git add server/internal/web/readingTimer.js server/internal/web/readingTimer.test.mjs server/internal/web/library.js server/internal/web/bookshelf.js server/internal/web/bookshelf.test.mjs
git commit -m "feat(web): reading timer pure module, report delta and duration formatting"
```

### Task 4: Web — textReader 计时接线 + 书架时长显示

**Files:**
- Modify: `server/internal/web/textReader.js:18`（import）、`:540-590`（persistVisibleProgress / 事件挂载）、initReader（创建 timer + 30s 心跳 + cleanup）
- Modify: `server/internal/web/bookshelf.js`（loadAll 后异步拉时长 patch 卡片）

**Interfaces:**
- Consumes: Task 3 的 `createReadingTimer` / `reportState readSecondsDelta` / `fetchDecorationsFor` / `formatReadDuration`。

- [ ] **Step 1: textReader 接线**

1. import 行加：`import { createReadingTimer } from './readingTimer.js';`（并入现有 library.js 的 import 旁）。
2. 模块内（现有 `const STORAGE_PREFIX = 'book_progress:'` 附近）加：

```js
const readTimer = createReadingTimer();
let lastReportedProg = null; // 最近一次上报的进度载荷（心跳/隐藏 flush 复用）
let readingHeartbeat = null;
```

3. `persistVisibleProgress` 内 `reportState(path, {...})` 调用前存 `lastReportedProg = { chapterIndex: vis.chapterIndex, paraIndex: vis.paraIndex, percent: payload.percent, finished: payload.finished, lastReadAt: prog.lastReadAt };`，并把 `readSecondsDelta: readTimer.takeDelta()` 加进该 reportState 载荷对象。
4. 阅读器初始化处（首次 loadBook 成功 / initReader 挂监听处）：`readTimer.start();` 并启动心跳：

```js
readingHeartbeat = setInterval(() => {
    const d = readTimer.takeDelta();
    if (d <= 0 || !lastReportedProg) return;
    reportState(state.currentBookPath || '', { ...lastReportedProg, lastReadAt: Date.now(), readSecondsDelta: d });
}, 30000);
```

（`state.currentBookPath` 若实际字段名不同，以 textReader 现有 path 变量为准——persistVisibleProgress 闭包内的 `path` 同源。）
5. `onPageHide`（现有）里加 `readTimer.stop();`；`onVisibilityChangeSave` 改为 hidden 时 `readTimer.stop()` + 若 `lastReportedProg` 则带 `readSecondsDelta: readTimer.takeDelta()` flush 一次 reportState，visible 时 `readTimer.start()`。注意 624 行另有 auto-scroll 的 visibilitychange 监听，**不要动它**。
6. 阅读器 cleanup（现有 removeEventListener 处）加 `clearInterval(readingHeartbeat); readTimer.stop();`。

- [ ] **Step 2: bookshelf 时长显示**

`bookshelf.js` import 加 `fetchDecorationsFor`（from './library.js'）。`render()` 在 grid 构建后追加异步 patch：

```js
    // 异步补时长：不阻塞首屏；离线/失败静默退回纯进度 meta
    fetchDecorationsFor(list.map(e => e.path))
        .then(d => {
            grid.querySelectorAll('.bookshelf-card').forEach(card => {
                const path = card.dataset.path;
                const sec = d.states[path] && d.states[path].read_seconds;
                const dur = formatReadDuration(sec);
                if (!dur) return;
                const el = card.querySelector('.bookshelf-card__progress');
                if (el) el.textContent = `${el.textContent} · 已读 ${dur}`;
            });
        })
        .catch(() => {});
```

`renderCard` 的 card 元素加 `card.dataset.path = entry.path;`。

- [ ] **Step 3: 验证**

Run: `cd server/internal/web && node --test && cd ../../tools/xsscheck && go run . ../../server/internal/web && cd ../../server && go build ./...`
Expected: 全绿（dataset/textContent 不触发 xsscheck；若 innerHTML 行报警检查 XSS-SAFE 注释仍完整）

- [ ] **Step 4: Commit**

```bash
git add server/internal/web/textReader.js server/internal/web/bookshelf.js
git commit -m "feat(web): reading-time heartbeat reporting and bookshelf duration display"
```

### Task 5: Android — ReadingSessionTimer + ReadingTimeStore + repository 扩展

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/data/ReadingSessionTimer.kt`
- Create: `android/app/src/main/java/com/juziss/localmediahub/data/ReadingTimeStore.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt:544-561`（reportReadingState 加参 + getStatsSummary）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/Models.kt:176-180`（DecorationBadge）、新 StatsSummaryResponse
- Test: `android/app/src/test/java/com/juziss/localmediahub/data/ReadingSessionTimerTest.kt`、`ReadingTimeStoreTest.kt`

**Interfaces:**
- Consumes: Task 1/2 的 API（`read_seconds_delta` 载荷字段、`/stats/summary` 响应）。
- Produces: `ReadingSessionTimer`（start/stop/takeDelta）；`ReadingTimeStore`（`addPending(path, delta)` / `takePendingUpto(path, max): Long` / `pendingPaths(): List<String>`）；`MediaRepository.reportReadingState(..., readSecondsDelta: Long = 0)`、`getStatsSummary()`。Task 6/7 依赖。

- [ ] **Step 1: 写失败测试**

`ReadingSessionTimerTest.kt`：

```kotlin
package com.juziss.localmediahub.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingSessionTimerTest {
    @Test
    fun `takeDelta accumulates and resets`() {
        var t = 0L
        val timer = ReadingSessionTimer { t }
        timer.start()
        t = 30_000
        assertEquals(30L, timer.takeDelta())
        t = 60_000
        timer.stop()
        assertEquals(30L, timer.takeDelta())
        assertEquals(0L, timer.takeDelta())
    }

    @Test
    fun `takeDelta while running keeps clock alive`() {
        var t = 0L
        val timer = ReadingSessionTimer { t }
        timer.start()
        t = 90_000
        assertEquals(90L, timer.takeDelta())
        t = 120_000
        assertEquals(30L, timer.takeDelta())
    }

    @Test
    fun `start and stop are idempotent`() {
        var t = 0L
        val timer = ReadingSessionTimer { t }
        timer.start(); timer.start()
        t = 5_000
        timer.stop(); timer.stop()
        assertEquals(5L, timer.takeDelta())
    }
}
```

`ReadingTimeStoreTest.kt`（Robolectric，参照 `ServerConfigStoreAuthTokenTest` 的 Context 获取方式）：

```kotlin
package com.juziss.localmediahub.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReadingTimeStoreTest {
    @Test
    fun `addPending accumulates per path and takePendingUpto caps`() = runBlocking {
        val store = ReadingTimeStore(ApplicationProvider.getApplicationContext())
        store.addPending("a.txt", 2000)
        store.addPending("a.txt", 3000)
        assertEquals(3600, store.takePendingUpto("a.txt", 3600))
        assertEquals(1400, store.takePendingUpto("a.txt", 3600))
        assertEquals(0, store.takePendingUpto("a.txt", 3600))
    }

    @Test
    fun `pending is tracked per path`() = runBlocking {
        val store = ReadingTimeStore(ApplicationProvider.getApplicationContext())
        store.addPending("a.txt", 2000)
        store.addPending("b.txt", 100)
        assertEquals(listOf("a.txt", "b.txt"), store.pendingPaths())
        assertEquals(2000, store.takePendingUpto("a.txt", 3600))
        assertEquals(listOf("b.txt"), store.pendingPaths())
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.data.ReadingSessionTimerTest" --tests "com.juziss.localmediahub.data.ReadingTimeStoreTest"`
Expected: FAIL（类不存在 → 编译失败）

- [ ] **Step 3: 实现**

`ReadingSessionTimer.kt`：

```kotlin
package com.juziss.localmediahub.data

/**
 * 阅读会话计时器：start/stop 括起活跃阅读（Activity onResume/onPause 驱动），
 * takeDelta 取走并清零累积秒数（运行中取走不停止计时）。时钟注入便于单测。
 */
class ReadingSessionTimer(private val now: () -> Long = System::currentTimeMillis) {
    private var startTs: Long? = null
    private var pendingSec = 0L

    fun start() {
        if (startTs == null) startTs = now()
    }

    fun stop() {
        startTs?.let { pendingSec += (now() - it) / 1000; startTs = null }
    }

    fun takeDelta(): Long {
        var d = pendingSec
        pendingSec = 0
        startTs?.let {
            d += (now() - it) / 1000
            startTs = now()
        }
        return d
    }
}
```

`ReadingTimeStore.kt`：

```kotlin
package com.juziss.localmediahub.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private val Context.readingTimeDataStore by preferencesDataStore("reading_time")

/**
 * 阅读时长 pending 缓冲（per-path Map，DataStore 里持久为 JSON 字符串）：
 * 离线累积、上报失败回退、连线分片补传的单一事实源。
 * takePendingUpto(path, max) 取出该书 ≤max 的一片并扣除（与服务端 clamp 3600 对齐）。
 */
@Singleton
class ReadingTimeStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val gson = Gson()
    private val mapKey = stringPreferencesKey("pending_read_seconds_map")
    private val mutex = Mutex()

    private suspend fun readMap(): MutableMap<String, Long> {
        val raw = context.readingTimeDataStore.data.firstOrNull()?.get(mapKey)
        if (raw.isNullOrEmpty()) return mutableMapOf()
        return try {
            gson.fromJson(raw, object : TypeToken<MutableMap<String, Long>>() {}.type) ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private suspend fun writeMap(map: Map<String, Long>) {
        context.readingTimeDataStore.edit { prefs ->
            val filtered = map.filterValues { it > 0 }
            if (filtered.isEmpty()) prefs.remove(mapKey) else prefs[mapKey] = gson.toJson(filtered)
        }
    }

    suspend fun addPending(path: String, delta: Long) {
        if (delta <= 0) return
        mutex.withLock {
            val m = readMap()
            m[path] = (m[path] ?: 0L) + delta
            writeMap(m)
        }
    }

    /** 取出该书 pending 中 ≤max 的一片并扣除；返回 0 表示该书无待传。 */
    suspend fun takePendingUpto(path: String, max: Long): Long = mutex.withLock {
        val m = readMap()
        val pending = m[path] ?: return@withLock 0L
        val take = pending.coerceAtMost(max)
        val rest = pending - take
        if (rest > 0) m[path] = rest else m.remove(path)
        writeMap(m)
        take
    }

    suspend fun pendingPaths(): List<String> = readMap().keys.toList()
}
```

（import：`androidx.datastore.preferences.core.edit` / `stringPreferencesKey` / `preferencesDataStore`、`com.google.gson.Gson` / `TypeToken`、`kotlinx.coroutines.flow.firstOrNull`、`kotlinx.coroutines.sync.Mutex` / `withLock`。longPreferencesKey 不需要。）

`Models.kt` `DecorationBadge` 加 `@SerializedName("read_seconds") val readSeconds: Long = 0,`；同文件加：

```kotlin
data class StatsSummaryResponse(
    @SerializedName("today_seconds") val todaySeconds: Long,
    @SerializedName("week_seconds") val weekSeconds: Long,
    @SerializedName("total_seconds") val totalSeconds: Long,
)
```

`MediaRepository.reportReadingState` 加参数 `readSecondsDelta: Long = 0`，body map 加 `"read_seconds_delta" to readSecondsDelta`；新方法（getStatsSummary 附近）：

```kotlin
    suspend fun getStatsSummary(): NetworkResult<StatsSummaryResponse> =
        httpGet("$baseUrl/api/v1/library/stats/summary", object : TypeToken<StatsSummaryResponse>() {}.type)
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.data.Reading*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/data/ReadingSessionTimer.kt android/app/src/main/java/com/juziss/localmediahub/data/ReadingTimeStore.kt android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt android/app/src/main/java/com/juziss/localmediahub/data/Models.kt android/app/src/test/java/com/juziss/localmediahub/data/ReadingSessionTimerTest.kt android/app/src/test/java/com/juziss/localmediahub/data/ReadingTimeStoreTest.kt
git commit -m "feat(android): reading session timer, pending store and repository read-time APIs"
```

### Task 6: Android — 阅读计时接线 + LibrarySyncManager 分片补传

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/TextReaderActivity.kt`（onResume/onPause）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/TextReaderViewModel.kt:535-560`（上报点带 delta + 失败回退）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/LibrarySyncManager.kt:40-70`（syncOnce 补传段）

**Interfaces:**
- Consumes: Task 5 全部。
- Produces: 无下游依赖（Task 7 独立）。

- [ ] **Step 1: TextReaderActivity 接线**

`TextReaderActivity` 类内加：

```kotlin
    override fun onResume() {
        super.onResume()
        viewModel.startReadingSession()
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopReadingSession()
    }
```

- [ ] **Step 2: TextReaderViewModel 接线**

构造注入 `private val readingTimeStore: ReadingTimeStore`；类内加：

```kotlin
    private val readingTimer = ReadingSessionTimer()

    /** Activity onResume：开始累计活跃阅读秒数。 */
    fun startReadingSession() { readingTimer.start() }

    /** Activity onPause：停表并把累积秒数沉入 pending 缓冲（离线安全）。 */
    fun stopReadingSession() {
        readingTimer.stop()
        val b = _book.value ?: return
        val d = readingTimer.takeDelta()
        if (d > 0) viewModelScope.launch { readingTimeStore.addPending(b.path, d) }
    }
```

上报点（`:554` 附近）改为：`lastPushKey` 守卫同时放行 delta——

```kotlin
            val key = "$chapterIndex:$blockIndex:$finished"
            // 计时器内存值先沉入 pending 缓冲（离线安全），再取 ≤3600 一片随载荷上报；
            // 失败回退到 pending 缓冲，下次上报或连线补传重试。
            val liveDelta = readingTimer.takeDelta()
            if (liveDelta > 0) readingTimeStore.addPending(b.path, liveDelta)
            val delta = readingTimeStore.takePendingUpto(b.path, MAX_REPORT_DELTA_SECONDS)
            if (key != lastPushKey || delta > 0) {
                lastPushKey = key
                val result = repo.reportReadingState(b.path, chapterIndex, blockIndex, percent, finished, now, delta)
                if (result !is NetworkResult.Success && delta > 0) {
                    readingTimeStore.addPending(b.path, delta)
                }
            }
```

文件顶层常量：`private const val MAX_REPORT_DELTA_SECONDS = 3600L`。`ReadingSessionTimer` / `ReadingTimeStore` 同为 `data` 包 import 使用。

- [ ] **Step 3: LibrarySyncManager 分片补传**

构造注入 `private val readingTimeStore: ReadingTimeStore`；`syncOnce()` 末尾（收藏同步之后）加：

```kotlin
        // ── 3. 阅读时长 pending 补传：逐书按 ≤3600 分片，规避服务端 clamp 截断 ──
        for (bookPath in readingTimeStore.pendingPaths()) {
            // 防进度回退：先用服务端当前进度回传（等值 upsert no-op），仅叠加 delta；
            // 拉不到服务端状态时用 0 进度 + 当前时间（首行 upsert 场景）。
            var ch = 0; var para = 0; var pct = 0.0
            when (val remote = repository.getReadingState(bookPath)) {
                is NetworkResult.Success -> remote.data.state?.let { st ->
                    ch = st.chapterIndex; para = st.paraIndex; pct = st.percent
                }
                else -> return // 网络不可用：整段补传下轮再试（pending 未扣减）
            }
            while (true) {
                val chunk = readingTimeStore.takePendingUpto(bookPath, MAX_SYNC_DELTA_SECONDS)
                if (chunk <= 0) break
                val result = repository.reportReadingState(
                    path = bookPath,
                    chapterIndex = ch, paraIndex = para, percent = pct,
                    finished = false,
                    lastReadAt = System.currentTimeMillis(),
                    readSecondsDelta = chunk,
                )
                if (result !is NetworkResult.Success) {
                    readingTimeStore.addPending(bookPath, chunk)
                    break // 该书剩余片与后续书下轮再试
                }
            }
        }
```

文件顶层常量：`private const val MAX_SYNC_DELTA_SECONDS = 3600L`。

- [ ] **Step 4: 验证 + commit**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: 全绿

```bash
git add android/app/src/main/java/com/juziss/localmediahub/TextReaderActivity.kt android/app/src/main/java/com/juziss/localmediahub/viewmodel/TextReaderViewModel.kt android/app/src/main/java/com/juziss/localmediahub/data/LibrarySyncManager.kt android/app/src/main/java/com/juziss/localmediahub/data/ReadingTimeStore.kt android/app/src/test/java/com/juziss/localmediahub/data/ReadingTimeStoreTest.kt
git commit -m "feat(android): reading session wiring and chunked offline read-time backfill"
```

### Task 7: Android — BookshelfViewModel（数据组合 + 筛选纯函数）

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BookshelfViewModel.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/viewmodel/BookshelfViewModelTest.kt`

**Interfaces:**
- Consumes: `RecentActivityStore.getAllBookProgressFlow()`、`MediaRepository.fetchDecorations/getStatsSummary`（Task 5 的 `DecorationBadge.readSeconds`）、Task 6 的 ReadingTimeStore（不消费——本 task 独立）。
- Produces: `BookshelfItem` / `BookshelfFilter`（ALL/READING/FINISHED/FAVORITES）/ `applyBookshelfFilter(List<BookshelfItem>, BookshelfFilter): List<BookshelfItem>` / `BookshelfViewModel.uiState: StateFlow<BookshelfUiState>`。Task 8 消费。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.juziss.localmediahub.viewmodel

import com.juziss.localmediahub.data.BookProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class BookshelfViewModelTest {
    private fun item(path: String, status: String?, fav: Boolean, lastAt: Long) =
        BookshelfItem(path, path, 0, lastAt, "txt", status, 0L, fav)

    @Test
    fun `filter ALL keeps everything`() {
        val items = listOf(item("a", "reading", false, 3), item("b", null, false, 2))
        assertEquals(items, applyBookshelfFilter(items, BookshelfFilter.ALL))
    }

    @Test
    fun `filter READING keeps reading and null-status rows`() {
        val items = listOf(
            item("a", "reading", false, 3),
            item("b", "finished", false, 2),
            item("c", null, false, 1),
        )
        val out = applyBookshelfFilter(items, BookshelfFilter.READING)
        assertEquals(listOf("a", "c"), out.map { it.path })
    }

    @Test
    fun `filter FINISHED keeps finished rows`() {
        val items = listOf(item("a", "finished", false, 3), item("b", "reading", false, 2))
        assertEquals(listOf("a"), applyBookshelfFilter(items, BookshelfFilter.FINISHED).map { it.path })
    }

    @Test
    fun `filter FAVORITES keeps favorites`() {
        val items = listOf(item("a", "reading", true, 3), item("b", "reading", false, 2))
        assertEquals(listOf("a"), applyBookshelfFilter(items, BookshelfFilter.FAVORITES).map { it.path })
    }

    @Test
    fun `books are projected from progress rows sorted by lastReadAt`() {
        // projectBooks 的行为经由 ViewModel 内部 flow 断言成本高；这里锁定排序契约
        val rows = listOf(
            BookProgress("a.txt", 3, 1, 0, 100L),
            BookProgress("b.epub", 0, 0, 0, 200L),
        )
        val sorted = rows.sortedByDescending { it.lastReadAt }
        assertEquals(listOf("b.epub", "a.txt"), sorted.map { it.path })
    }

    @Test
    fun `formatReadDuration renders minutes below an hour, hours above`() {
        assertEquals("", formatReadDuration(0))
        assertEquals("45 分钟", formatReadDuration(45 * 60))
        assertEquals("1.5 小时", formatReadDuration(90 * 60))
        assertEquals("1.0 小时", formatReadDuration(3660))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.viewmodel.BookshelfViewModelTest"`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现**

`BookshelfViewModel.kt`：

```kotlin
package com.juziss.localmediahub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juziss.localmediahub.data.DecorationBadge
import com.juziss.localmediahub.data.MediaRepository
import com.juziss.localmediahub.data.RecentActivityStore
import com.juziss.localmediahub.network.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BookshelfItem(
    val path: String,
    val title: String,
    val chapterIndex: Int,
    val lastReadAt: Long,
    val format: String,
    val status: String?,        // null = 服务端无状态行（视为在读）
    val readSeconds: Long,
    val isFavorite: Boolean,
)

enum class BookshelfFilter { ALL, READING, FINISHED, FAVORITES }

data class BookshelfUiState(
    val items: List<BookshelfItem> = emptyList(),
    val filter: BookshelfFilter = BookshelfFilter.ALL,
    val isLoading: Boolean = true,
    val todaySeconds: Long = 0,
    val weekSeconds: Long = 0,
)

private data class BookshelfMeta(
    val isLoading: Boolean = true,
    val todaySeconds: Long = 0,
    val weekSeconds: Long = 0,
)

fun applyBookshelfFilter(items: List<BookshelfItem>, filter: BookshelfFilter): List<BookshelfItem> =
    when (filter) {
        BookshelfFilter.ALL -> items
        BookshelfFilter.READING -> items.filter { it.status == null || it.status == "reading" || it.status == "unread" }
        BookshelfFilter.FINISHED -> items.filter { it.status == "finished" }
        BookshelfFilter.FAVORITES -> items.filter { it.isFavorite }
    }

private fun isSupportedBookFormat(path: String): Boolean {
    val lower = path.lowercase()
    return lower.endsWith(".txt") || lower.endsWith(".epub")
}

@HiltViewModel
class BookshelfViewModel @Inject constructor(
    recentActivityStore: RecentActivityStore,
    private val repository: MediaRepository,
) : ViewModel() {

    private val filter = MutableStateFlow(BookshelfFilter.ALL)
    private val decorated = MutableStateFlow<Map<String, DecorationBadge>>(emptyMap())
    private val favPaths = MutableStateFlow<Set<String>>(emptySet())
    private val meta = MutableStateFlow(BookshelfMeta())

    init {
        viewModelScope.launch {
            val local = recentActivityStore.getAllBookProgressFlow().firstOrNull().orEmpty()
            val paths = local.filter { isSupportedBookFormat(it.path) }.map { it.path }
            if (paths.isNotEmpty()) {
                when (val res = repository.fetchDecorations(paths)) {
                    is NetworkResult.Success -> {
                        decorated.value = res.data.states
                        favPaths.value = res.data.favorites.toSet()
                    }
                    else -> Unit // 离线降级：仅本地进度书架
                }
            }
            val todayWeek = when (val s = repository.getStatsSummary()) {
                is NetworkResult.Success -> s.data.todaySeconds to s.data.weekSeconds
                else -> 0L to 0L
            }
            meta.value = BookshelfMeta(isLoading = false, todaySeconds = todayWeek.first, weekSeconds = todayWeek.second)
        }
    }

    fun setFilter(f: BookshelfFilter) { filter.value = f }

    val uiState: StateFlow<BookshelfUiState> = combine(
        recentActivityStore.getAllBookProgressFlow().map { list ->
            list.filter { isSupportedBookFormat(it.path) }
        },
        decorated,
        favPaths,
        filter,
        meta,
    ) { rows, dec, favs, fl, m ->
        val items = rows.map { p ->
            val badge = dec[p.path]
            BookshelfItem(
                path = p.path,
                title = p.path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.'),
                chapterIndex = p.chapterIndex,
                lastReadAt = p.lastReadAt,
                format = if (p.path.lowercase().endsWith(".epub")) "epub" else "txt",
                status = badge?.status,
                readSeconds = badge?.readSeconds ?: 0L,
                isFavorite = p.path in favs,
            )
        }
        BookshelfUiState(
            items = applyBookshelfFilter(items, fl),
            filter = fl,
            isLoading = m.isLoading,
            todaySeconds = m.todaySeconds,
            weekSeconds = m.weekSeconds,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BookshelfUiState())
}

fun formatReadDuration(sec: Long): String = when {
    sec < 60 -> ""
    sec < 3600 -> "${sec / 60} 分钟"
    else -> String.format(java.util.Locale.ROOT, "%.1f 小时", sec / 3600.0)
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.viewmodel.BookshelfViewModelTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/viewmodel/BookshelfViewModel.kt android/app/src/test/java/com/juziss/localmediahub/viewmodel/BookshelfViewModelTest.kt
git commit -m "feat(android): bookshelf view model with status and favorite filtering"
```

### Task 8: Android — BookshelfScreen + 路由 + 首页入口

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/BookshelfScreen.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/MainActivity.kt:128-401`（路由 + onOpenBookshelf）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/HomeScreen.kt:93-106`（参数）、`:309-326`（书架 Section）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/home/HomeComponents.kt:298-314`（SectionHeader action slot）
- Modify: `android/app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: Task 7 的 `BookshelfViewModel` / `BookshelfItem` / `BookshelfFilter`。

- [ ] **Step 1: strings.xml**（`home_section_bookshelf_desc` 之后追加）

```xml
    <string name="bookshelf_view_all">查看全部</string>
    <string name="bookshelf_filter_all">全部</string>
    <string name="bookshelf_filter_reading">在读</string>
    <string name="bookshelf_filter_finished">读完</string>
    <string name="bookshelf_filter_favorites">收藏</string>
    <string name="bookshelf_stats_today">今日 %1$s</string>
    <string name="bookshelf_stats_week">本周 %1$s</string>
    <string name="bookshelf_read_prefix">已读 %1$s</string>
    <string name="bookshelf_status_reading">在读</string>
    <string name="bookshelf_status_finished">读完</string>
    <string name="bookshelf_empty_title">书架还是空的</string>
    <string name="bookshelf_empty_desc">在媒体库中打开小说或书籍后，将自动出现在这里。</string>
```

- [ ] **Step 2: SectionHeader 加 action slot**（HomeComponents.kt:298）

```kotlin
@Composable
fun SectionHeader(
    title: String,
    subtitle: String,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke()
    }
}
```

（保留原实现的其他修饰；Row/Alignment/Arrangement 已在该文件 import。）

- [ ] **Step 3: BookshelfScreen.kt**

```kotlin
package com.juziss.localmediahub.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.juziss.localmediahub.R
import com.juziss.localmediahub.TextReaderActivity
import com.juziss.localmediahub.viewmodel.BookshelfFilter
import com.juziss.localmediahub.viewmodel.BookshelfViewModel
import com.juziss.localmediahub.viewmodel.formatReadDuration
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    onBack: () -> Unit,
    viewModel: BookshelfViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_section_bookshelf), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // 统计条：今日 · 本周
            if (uiState.todaySeconds > 0 || uiState.weekSeconds > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AssistChip(onClick = {}, label = { Text(stringResource(R.string.bookshelf_stats_today, formatReadDuration(uiState.todaySeconds))) })
                    AssistChip(onClick = {}, label = { Text(stringResource(R.string.bookshelf_stats_week, formatReadDuration(uiState.weekSeconds))) })
                }
            }
            // 筛选芯片
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BookshelfFilter.entries.forEach { f ->
                    FilterChip(
                        selected = uiState.filter == f,
                        onClick = { viewModel.setFilter(f) },
                        label = { Text(filterLabel(f)) },
                    )
                }
            }
            if (uiState.items.isEmpty() && !uiState.isLoading) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(stringResource(R.string.bookshelf_empty_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.bookshelf_empty_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(uiState.items, key = { it.path }) { item ->
                        BookshelfGridTile(item = item, onClick = {
                            context.startActivity(TextReaderActivity.newIntent(context, item.path))
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun filterLabel(f: BookshelfFilter): String = when (f) {
    BookshelfFilter.ALL -> stringResource(R.string.bookshelf_filter_all)
    BookshelfFilter.READING -> stringResource(R.string.bookshelf_filter_reading)
    BookshelfFilter.FINISHED -> stringResource(R.string.bookshelf_filter_finished)
    BookshelfFilter.FAVORITES -> stringResource(R.string.bookshelf_filter_favorites)
}

@Composable
private fun BookshelfGridTile(item: com.juziss.localmediahub.viewmodel.BookshelfItem, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(item.format.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                if (item.status == "finished") {
                    Text(stringResource(R.string.bookshelf_status_finished), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val dur = formatReadDuration(item.readSeconds)
            val chapterText = "第 ${item.chapterIndex + 1} 章" + if (dur.isNotEmpty()) " · ${stringResource(R.string.bookshelf_read_prefix, dur)}" else ""
            Text(chapterText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
```

（`formatReadDuration` 已在 Task 7 的 `BookshelfViewModel.kt` 顶层定义，直接 import；`R.string.back` 若不存在用现有返回字符串资源——grep strings.xml 里 back 相关资源名，以现有为准。）

- [ ] **Step 4: MainActivity 路由 + 首页入口**

`composable("home")` 的 HomeScreen 参数加 `onOpenBookshelf = { navController.navigate("bookshelf") }`；NavHost 内（`composable("downloads")` 旁）加：

```kotlin
        composable("bookshelf") {
            BookshelfScreen(onBack = { navController.popBackStack() })
        }
```

`HomeScreen.kt`：参数列表加 `onOpenBookshelf: () -> Unit = {}`；书架 Section（`:309`）改为：

```kotlin
                item {
                    SectionHeader(
                        title = stringResource(R.string.home_section_bookshelf),
                        subtitle = stringResource(R.string.home_section_bookshelf_desc),
                        action = {
                            TextButton(onClick = onOpenBookshelf) {
                                Text(stringResource(R.string.bookshelf_view_all))
                            }
                        },
                    )
                }
```

- [ ] **Step 5: 验证 + commit**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: 全绿

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/screen/BookshelfScreen.kt android/app/src/main/java/com/juziss/localmediahub/MainActivity.kt android/app/src/main/java/com/juziss/localmediahub/ui/screen/HomeScreen.kt android/app/src/main/java/com/juziss/localmediahub/ui/component/home/HomeComponents.kt android/app/src/main/res/values/strings.xml android/app/src/main/java/com/juziss/localmediahub/viewmodel/BookshelfViewModel.kt
git commit -m "feat(android): standalone bookshelf screen with filters, stats and home entry"
```

### Task 9: 终验 + 文档同步

**Files:**
- Modify: `AGENTS.md`（模块地图：BookshelfScreen / BookshelfViewModel / ReadingTimeStore / readingTimer.js + stats 端点一句话）

- [ ] **Step 1: 三端全量验证**

```bash
cd server && go test ./... && cd ../server/internal/web && node --test && cd ../../tools/xsscheck && go run . ../../server/internal/web && cd ../../android && ./gradlew testDebugUnitTest assembleDebug
```

- [ ] **Step 2: AGENTS.md 更新**（Android Screen 列表加 `BookshelfScreen`；Data 列表加 `ReadingTimeStore` / `ReadingSessionTimer`；ViewModel 列表加 `BookshelfViewModel`；Web 公共层加 `readingTimer.js`；library.go 描述追加"阅读时长 read_seconds + reading_daily + stats/summary"）

- [ ] **Step 3: 手动验证清单**（输出给用户，不阻塞 commit）
  - Web 阅读一本书 ≥1 分钟 → 书架卡片出现"已读 X 分钟"
  - Android 阅读 → onPause 后重进书架可见时长；断网阅读后连线重启 app → 时长补传
  - Android 首页书架"查看全部" → 书架页筛选芯片全部可用
  - 双端读同一本书 → 时长数值一致（误差 < 计时粒度）

- [ ] **Step 4: Commit**

```bash
git add AGENTS.md
git commit -m "docs(reader): sync handbook for reading stats and bookshelf"
```

## Self-Review

- **Spec 覆盖**：§3.1/3.2/3.3 → Task 1+2；§4.1 → Task 3+4；§4.2 → Task 4；§5.1 → Task 5+6；§5.2 → Task 7+8；§6 测试分布各 task + Task 9 终验；§7 安全（clamp/鉴权跟随/validateTextPath 不动）→ Task 1+2；§8 提交切分 → 每 task 一 commit（逻辑上 server/web/android 三组）。
- **占位符**：无 TBD；Task 4 Step 1 的 `state.currentBookPath` 与 Task 8 的 `R.string.back` 标注了"以现有代码为准"的核对点，属定向核对而非缺实现。
- **类型一致性**：`ReadSecondsDelta`/`read_seconds_delta`（Go ↔ JS ↔ Kotlin 三处载荷字段统一 snake_case JSON）；`read_seconds`（badge/state/响应）；`ReadingTimeStore` 全 plan 统一 per-path 版（`addPending(path, delta)` / `takePendingUpto(path, max)` / `pendingPaths()`）。`BookshelfFilter`/`BookshelfItem` 字段 Task 7 定义 = Task 8 消费。
