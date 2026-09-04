# 列表页阅读状态 + 滚动恢复 + 收藏 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 [spec](../specs/2026-09-04-library-states-favorites-design.md) 实现服务端 LibraryService（阅读状态 + 收藏，SQLite 统一存储）、Web 与 Android 两端的状态徽章 / 收藏心形 / 双筛选 / 滚动恢复 / 进度同步。

**Architecture:** Phase 1 服务端新增 `LibraryService`（仿 `tags.go`，`.data/library.db`，两张表）+ `/api/v1/library/*` 7 端点；Phase 2 Web 新增 `library.js`（API + 纯函数 + DOM Patch）与 `scrollMemory.js`（锚点式滚动恢复），改造 `browserView.js` / `textReader.js` / `app.js`；Phase 3 Android 新增 `LibraryController` / `LibrarySyncManager` / `ReadingMath`，扩展 `FavoritesStore`（目录收藏）与 `TextReaderViewModel`（写透上报），`BrowseContent` 滚动修复。

**Tech Stack:** Go (Echo v4 + modernc.org/sqlite + testify) / 原生 ES Module JS (node:test + jsdom) / Kotlin (Compose + OkHttp + Gson + DataStore + Robolectric)。

## Global Constraints

- JSON 字段一律 **snake_case**（服务端 `internal/models` 既有约定，Android 用 `@SerializedName`）。
- Go：handler 只做参数解析与响应（`Handler` struct 持依赖，无全局变量）；service 业务逻辑；列表返回 `make([]T, 0)` 防 null。
- Web：**零 inline `style="..."`**（CSP `style-src 'self'`，动态样式走 `el.style.prop =`）；所有 `innerHTML` sink 带 `// XSS-SAFE:` 注释或 `escapeHtml()`；测试 `.test.mjs` + `import` 带 `.js` 扩展名；新模块**不得在模块求值期读 localStorage**（jsdom 测试约束）。
- Android：MVVM + delegate 模式；网络走 `MediaRepository` 裸 OkHttp + Gson TypeToken（**无 Retrofit**）；进度/收藏上报失败一律静默降级。
- Commit 风格：Conventional Commits，scope 用 `library`，多阶段带 `(Phase N)` 后缀。
- ⚠️ **Mimosa pre-commit 钩子当前会拦截一切 commit**（既有代码的历史告警，与本次改动无关）。执行者遇拦截**不得自行绕过**：向用户报告并请其放行或调整钩子后重试；同一任务内的代码改动照常完成，commit 步骤标记为 blocked 留待用户处理。
- 验证命令（按改动范围选）：`cd server && go test ./...`；`cd server/internal/web && node --test`；`cd tools/xsscheck && go run . ../../server/internal/web`；`cd android && ./gradlew testDebugUnitTest assembleDebug`。

---

## Phase 1 — 服务端

### Task 1: LibraryService 骨架 + 进度 upsert（含合并规则）

**Files:**
- Create: `server/internal/service/library.go`
- Create: `server/internal/models/library_models.go`
- Test: `server/internal/service/library_test.go`

**Interfaces:**
- Produces（后续任务依赖的精确签名）:
  - `func NewLibraryService(dataDir string) (*LibraryService, error)`
  - `func (s *LibraryService) Close() error`
  - `func (s *LibraryService) UpsertProgress(u models.ProgressUpdate) (models.ReadingState, error)`
  - `func (s *LibraryService) GetState(path string) (*models.ReadingState, error)`（nil = 无行）
  - `func deriveStatus(finished bool, manual *string, rowExists bool) string`
  - models：`ProgressUpdate` / `ReadingState`（下方完整定义）

- [ ] **Step 1: 定义 models**（`server/internal/models/library_models.go` 全量）

```go
package models

import "encoding/json"

// ProgressUpdate 是 POST /api/v1/library/states 的请求体。
type ProgressUpdate struct {
	Path         string  `json:"path"`
	ChapterIndex int     `json:"chapter_index"`
	ParaIndex    int     `json:"para_index"`
	Percent      float64 `json:"percent"`
	Finished     bool    `json:"finished"`
	LastReadAt   int64   `json:"last_read_at"` // Unix 毫秒
}

// ReadingState 是单本书的完整状态行（GET states 响应 / 内部行模型）。
type ReadingState struct {
	Path         string  `json:"path,omitempty"`
	ChapterIndex int     `json:"chapter_index"`
	ParaIndex    int     `json:"para_index"`
	Percent      float64 `json:"percent"`
	Finished     bool    `json:"finished"`
	ManualStatus *string `json:"manual_status"`
	LastReadAt   int64   `json:"last_read_at"`
	UpdatedAt    int64   `json:"updated_at"`
}

// ReadingStateBadge 是 decorations 批量响应里的单条徽章。
type ReadingStateBadge struct {
	Status     string  `json:"status"`
	Percent    float64 `json:"percent"`
	LastReadAt int64   `json:"last_read_at"`
}

// DecorationsResult 是 POST /api/v1/library/decorations 的响应。
type DecorationsResult struct {
	States    map[string]ReadingStateBadge `json:"states"`
	Favorites []string                     `json:"favorites"`
}

// FavoriteRecord 是服务端收藏行（snapshot 为客户端 JSON，服务端不解释）。
type FavoriteRecord struct {
	Path      string          `json:"path"`
	IsDir     bool            `json:"is_dir"`
	IsSystem  bool            `json:"is_system"`
	Title     string          `json:"title"`
	MediaType string          `json:"media_type"`
	Snapshot  json.RawMessage `json:"snapshot"`
	AddedAt   int64           `json:"added_at"`
}

// FavoriteUpdate 是 POST /api/v1/library/favorites 的请求体。
type FavoriteUpdate struct {
	Path      string          `json:"path"`
	IsDir     bool            `json:"is_dir"`
	IsSystem  bool            `json:"is_system"`
	Title     string          `json:"title"`
	MediaType string          `json:"media_type"`
	Snapshot  json.RawMessage `json:"snapshot"`
	AddedAt   int64           `json:"added_at"`
}
```

- [ ] **Step 2: 写失败测试**（`server/internal/service/library_test.go`）

```go
package service

import (
	"testing"

	"github.com/stretchr/testify/assert"

	"github.com/localmediahub/server/internal/models"
)

func newTestLibraryService(t *testing.T) *LibraryService {
	t.Helper()
	svc, err := NewLibraryService(t.TempDir())
	assert.NoError(t, err)
	t.Cleanup(func() { _ = svc.Close() })
	return svc
}

func mkUpdate(path string, ch, para int, pct float64, finished bool, lastReadAt int64) models.ProgressUpdate {
	return models.ProgressUpdate{Path: path, ChapterIndex: ch, ParaIndex: para,
		Percent: pct, Finished: finished, LastReadAt: lastReadAt}
}

func TestUpsertProgressInsert(t *testing.T) {
	svc := newTestLibraryService(t)
	st, err := svc.UpsertProgress(mkUpdate("/media/a.txt", 1, 2, 10.5, false, 1000))
	assert.NoError(t, err)
	assert.Equal(t, "reading", deriveStatus(st.Finished, st.ManualStatus, true))
	assert.InEpsilon(t, 10.5, st.Percent, 1e-9)

	got, err := svc.GetState("/media/a.txt")
	assert.NoError(t, err)
	assert.NotNil(t, got)
	assert.Equal(t, 1, got.ChapterIndex)
	assert.Equal(t, int64(1000), got.LastReadAt)
}

func TestUpsertProgressMerge(t *testing.T) {
	svc := newTestLibraryService(t)
	// 首次读完结
	_, err := svc.UpsertProgress(mkUpdate("/media/b.txt", 9, 5, 99.0, true, 1000))
	assert.NoError(t, err)
	// 更新的重读进度（lastReadAt 更新，finished=false）：finished 粘滞 + manual unread 自动清除
	_, _ = svc.UpsertProgress(mkUpdate("/media/b.txt", 0, 0, 0, false, 2000))
	got, _ := svc.GetState("/media/b.txt")
	assert.True(t, got.Finished)                       // 粘滞
	assert.Equal(t, 0, got.ChapterIndex)               // 进度更新为新值
	// 陈旧上报 no-op
	st, err := svc.UpsertProgress(mkUpdate("/media/b.txt", 5, 0, 50, false, 1500))
	assert.NoError(t, err)
	assert.Equal(t, 0, st.ChapterIndex) // 仍是 0
}

func TestUpsertProgressAutoClearsManualUnread(t *testing.T) {
	svc := newTestLibraryService(t)
	_, _ = svc.UpsertProgress(mkUpdate("/media/c.txt", 1, 0, 5, false, 1000))
	// 手动标未读（Task 2 实现 SetManualStatus；此处先直接 SQL 置位以锁定 upsert 行为）
	_, err := svc.db.Exec(`UPDATE reading_states SET manual_status='unread' WHERE path=?`, "/media/c.txt")
	assert.NoError(t, err)
	_, _ = svc.UpsertProgress(mkUpdate("/media/c.txt", 2, 0, 8, false, 3000))
	got, _ := svc.GetState("/media/c.txt")
	assert.Nil(t, got.ManualStatus) // 自动清除
}

func TestUpsertProgressCaseInsensitivePath(t *testing.T) {
	svc := newTestLibraryService(t)
	_, _ = svc.UpsertProgress(mkUpdate("/Media/A.TXT", 1, 0, 5, false, 1000))
	got, _ := svc.GetState("/media/a.txt")
	assert.NotNil(t, got) // COLLATE NOCASE 命中同一行
}

func TestDeriveStatus(t *testing.T) {
	assert.Equal(t, "unread", deriveStatus(false, nil, false))
	assert.Equal(t, "reading", deriveStatus(false, nil, true))
	assert.Equal(t, "finished", deriveStatus(true, nil, true))
	manual := "unread"
	assert.Equal(t, "unread", deriveStatus(true, &manual, true)) // manual 优先
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `cd server && go test ./internal/service/ -run TestUpsertProgress -v && go test ./internal/service/ -run TestDeriveStatus -v`
Expected: FAIL（`undefined: NewLibraryService`）

- [ ] **Step 4: 实现 `server/internal/service/library.go`**

```go
package service

import (
	"database/sql"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"runtime"
	"sync"

	_ "modernc.org/sqlite"

	"github.com/localmediahub/server/internal/models"
)

// LibraryService 存储跨设备共享的阅读状态与收藏（spec 2026-09-04-library-states-favorites）。
type LibraryService struct {
	mu sync.RWMutex
	db *sql.DB
}

func NewLibraryService(dataDir string) (*LibraryService, error) {
	if err := os.MkdirAll(dataDir, 0755); err != nil {
		return nil, err
	}
	dsn := filepath.Join(dataDir, "library.db") +
		"?_pragma=foreign_keys(1)&_pragma=journal_mode(WAL)&_pragma=synchronous(NORMAL)&_pragma=busy_timeout(5000)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(max(4, runtime.NumCPU()))
	db.SetMaxIdleConns(2)
	db.SetConnMaxLifetime(0)
	for _, p := range []string{
		"PRAGMA journal_mode=WAL", "PRAGMA synchronous=NORMAL", "PRAGMA mmap_size=268435456",
		"PRAGMA temp_store=MEMORY", "PRAGMA cache_size=-65536", "PRAGMA foreign_keys=ON", "PRAGMA busy_timeout=5000",
	} {
		if _, err := db.Exec(p); err != nil {
			slog.Warn("library sqlite pragma failed", "pragma", p, "error", err)
		}
	}
	_, err = db.Exec(`
		CREATE TABLE IF NOT EXISTS reading_states (
			path TEXT PRIMARY KEY COLLATE NOCASE,
			chapter_index INTEGER NOT NULL DEFAULT 0,
			para_index INTEGER NOT NULL DEFAULT 0,
			percent REAL NOT NULL DEFAULT 0,
			finished INTEGER NOT NULL DEFAULT 0 CHECK (finished IN (0, 1)),
			manual_status TEXT CHECK (manual_status IS NULL OR manual_status IN ('unread', 'reading', 'finished')),
			last_read_at INTEGER NOT NULL DEFAULT 0,
			updated_at INTEGER NOT NULL DEFAULT 0
		);
		CREATE TABLE IF NOT EXISTS favorites (
			path TEXT PRIMARY KEY COLLATE NOCASE,
			is_dir INTEGER NOT NULL DEFAULT 0 CHECK (is_dir IN (0, 1)),
			is_system INTEGER NOT NULL DEFAULT 0 CHECK (is_system IN (0, 1)),
			title TEXT NOT NULL DEFAULT '',
			media_type TEXT NOT NULL DEFAULT '',
			snapshot TEXT NOT NULL DEFAULT '{}',
			added_at INTEGER NOT NULL DEFAULT 0
		);
		CREATE INDEX IF NOT EXISTS idx_reading_states_last_read_at ON reading_states(last_read_at);
		CREATE INDEX IF NOT EXISTS idx_favorites_added_at ON favorites(added_at);
	`)
	if err != nil {
		db.Close()
		return nil, err
	}
	return &LibraryService{db: db}, nil
}

func (s *LibraryService) Close() error {
	if s == nil || s.db == nil {
		return nil
	}
	return s.db.Close()
}

// deriveStatus：manual 优先 → finished → 有行即 reading → 无行 unread。
func deriveStatus(finished bool, manual *string, rowExists bool) string {
	if manual != nil {
		return *manual
	}
	if finished {
		return "finished"
	}
	if rowExists {
		return "reading"
	}
	return "unread"
}

// UpsertProgress 单条原子 upsert：lastReadAt 守卫 + finished 粘滞 + manual unread 自动清除。
func (s *LibraryService) UpsertProgress(u models.ProgressUpdate) (models.ReadingState, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	now := nowMillis()
	_, err := s.db.Exec(`
		INSERT INTO reading_states (path, chapter_index, para_index, percent, finished, last_read_at, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(path) DO UPDATE SET
			chapter_index = excluded.chapter_index,
			para_index = excluded.para_index,
			percent = excluded.percent,
			finished = MAX(reading_states.finished, excluded.finished),
			manual_status = CASE WHEN reading_states.manual_status = 'unread' THEN NULL ELSE reading_states.manual_status END,
			last_read_at = excluded.last_read_at,
			updated_at = excluded.updated_at
		WHERE excluded.last_read_at >= reading_states.last_read_at`,
		u.Path, u.ChapterIndex, u.ParaIndex, u.Percent, boolToInt(u.Finished), u.LastReadAt, now)
	if err != nil {
		return models.ReadingState{}, err
	}
	st, err := s.GetState(u.Path)
	if err != nil || st == nil {
		return models.ReadingState{}, err
	}
	return *st, nil
}

func (s *LibraryService) GetState(path string) (*models.ReadingState, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	row := s.db.QueryRow(`SELECT path, chapter_index, para_index, percent, finished, manual_status, last_read_at, updated_at
		FROM reading_states WHERE path = ?`, path)
	return scanReadingState(row)
}

func scanReadingState(row *sql.Row) (*models.ReadingState, error) {
	var st models.ReadingState
	var finished int
	if err := row.Scan(&st.Path, &st.ChapterIndex, &st.ParaIndex, &st.Percent, &finished,
		&st.ManualStatus, &st.LastReadAt, &st.UpdatedAt); err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	st.Finished = finished == 1
	return &st, nil
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

// nowMillis 定义为变量便于测试注入（默认 Unix 毫秒）。
var nowMillis = func() int64 { return time.Now().UnixMilli() }
```

补 import `"time"`。若包内已有 `boolToInt`/`nowMillis` 等同名 helper（检查 tags.go），复用而不是重复定义。

- [ ] **Step 5: 跑测试确认通过**

Run: `cd server && go test ./internal/service/ -run 'TestUpsertProgress|TestDeriveStatus' -v`
Expected: PASS 全绿

- [ ] **Step 6: Commit**

```bash
git add server/internal/service/library.go server/internal/service/library_test.go server/internal/models/library_models.go
git commit -m "feat(library): LibraryService progress upsert with sticky-finished and manual-unread auto-clear (Phase 1)"
```

---

### Task 2: 手动状态覆盖 SetManualStatus

**Files:**
- Modify: `server/internal/service/library.go`
- Test: `server/internal/service/library_test.go`

**Interfaces:**
- Produces: `func (s *LibraryService) SetManualStatus(path string, status *string) (models.ReadingState, error)`（status 为 nil 表示清除；行不存在且 status 非 nil 时插入新行，status 为 nil 且行不存在时返回 zero ReadingState + deriveStatus unread）

- [ ] **Step 1: 写失败测试**（追加到 library_test.go）

```go
func TestSetManualStatus(t *testing.T) {
	svc := newTestLibraryService(t)
	_, _ = svc.UpsertProgress(mkUpdate("/media/d.txt", 3, 0, 30, true, 1000))

	fin := "finished"
	st, err := svc.SetManualStatus("/media/d.txt", &fin)
	assert.NoError(t, err)
	assert.Equal(t, "finished", deriveStatus(st.Finished, st.ManualStatus, true))

	unread := "unread"
	st, err = svc.SetManualStatus("/media/d.txt", &unread)
	assert.NoError(t, err)
	assert.False(t, st.Finished)  // 手动未读重置 finished
	assert.InDelta(t, 0.0, st.Percent, 1e-9) // 且重置 percent
	assert.Equal(t, "unread", deriveStatus(st.Finished, st.ManualStatus, true))

	// 清除覆盖 → 回到自动（行仍在，percent 已被重置为 0）
	st, err = svc.SetManualStatus("/media/d.txt", nil)
	assert.NoError(t, err)
	assert.Nil(t, st.ManualStatus)
	assert.Equal(t, "reading", deriveStatus(st.Finished, st.ManualStatus, true))
}

func TestSetManualStatusInsertsRow(t *testing.T) {
	svc := newTestLibraryService(t)
	fin := "finished"
	st, err := svc.SetManualStatus("/media/new.txt", &fin) // 无进度行也可手动标记
	assert.NoError(t, err)
	assert.True(t, st.Finished)
	assert.InDelta(t, 100.0, st.Percent, 1e-9)
	got, _ := svc.GetState("/media/new.txt")
	assert.NotNil(t, got)
}

func TestSetManualStatusFinishedSetsPercent(t *testing.T) {
	svc := newTestLibraryService(t)
	_, _ = svc.UpsertProgress(mkUpdate("/media/e.txt", 2, 0, 20, false, 1000))
	fin := "finished"
	st, _ := svc.SetManualStatus("/media/e.txt", &fin)
	assert.InDelta(t, 100.0, st.Percent, 1e-9)
	assert.True(t, st.Finished)
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && go test ./internal/service/ -run TestSetManualStatus -v`
Expected: FAIL（`undefined: SetManualStatus`）

- [ ] **Step 3: 实现**（library.go 追加）

```go
// SetManualStatus 手动覆盖：unread 重置 finished/percent；finished 置 finished/100；reading/nil 仅改 manual_status。
func (s *LibraryService) SetManualStatus(path string, status *string) (models.ReadingState, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	var raw *string
	finInit, pctInit := 0, 0.0
	if status != nil {
		raw = status
		if *status == "finished" {
			finInit, pctInit = 1, 100.0
		}
	}
	_, err := s.db.Exec(`
		INSERT INTO reading_states (path, chapter_index, para_index, percent, finished, manual_status, last_read_at, updated_at)
		VALUES (?, 0, 0, ?, ?, ?, 0, ?)
		ON CONFLICT(path) DO UPDATE SET
			manual_status = excluded.manual_status,
			finished = CASE
				WHEN excluded.manual_status = 'unread' THEN 0
				WHEN excluded.manual_status = 'finished' THEN 1
				ELSE reading_states.finished END,
			percent = CASE
				WHEN excluded.manual_status = 'unread' THEN 0
				WHEN excluded.manual_status = 'finished' THEN 100.0
				ELSE reading_states.percent END,
			updated_at = excluded.updated_at`,
		path, pctInit, finInit, raw, nowMillis())
	if err != nil {
		return models.ReadingState{}, err
	}
	st, err := s.GetState(path)
	if err != nil || st == nil {
		return models.ReadingState{}, err
	}
	return *st, nil
}
```

注意：`VALUES (?, 0, 0, ?, ?, ?, 0, ?)` 与列序 `path, chapter_index, para_index, percent, finished, manual_status, last_read_at, updated_at` 对齐（?,0,0,?,?,?,0,?）。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && go test ./internal/service/ -run TestSetManualStatus -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/internal/service/library.go server/internal/service/library_test.go
git commit -m "feat(library): manual reading-status override with unread/finished field resets (Phase 1)"
```

---

### Task 3: 收藏 CRUD（含 snapshot 透传与 added_at 择新）

**Files:**
- Modify: `server/internal/service/library.go`
- Test: `server/internal/service/library_test.go`

**Interfaces:**
- Produces:
  - `func (s *LibraryService) UpsertFavorite(f models.FavoriteUpdate) error`
  - `func (s *LibraryService) RemoveFavorite(path string) error`
  - `func (s *LibraryService) ListFavorites() ([]models.FavoriteRecord, error)`（空表返回空切片非 nil）
  - `func (s *LibraryService) FavoritePaths(paths []string) ([]string, error)`（输入路径子集中已收藏的，按输入原序返回）

- [ ] **Step 1: 写失败测试**（追加）

```go
func mkFav(path, title string, isDir bool, addedAt int64) models.FavoriteUpdate {
	return models.FavoriteUpdate{Path: path, Title: title, IsDir: isDir,
		MediaType: map[bool]string{true: "folder", false: "text"}[isDir],
		Snapshot: json.RawMessage(`{"file":{"name":"` + title + `"}}`), AddedAt: addedAt}
}

func TestFavoritesUpsertIdempotentAndMerge(t *testing.T) {
	svc := newTestLibraryService(t)
	assert.NoError(t, svc.UpsertFavorite(mkFav("/media/novel", "novel", true, 1000)))
	// 重复收藏：幂等保 added_at
	assert.NoError(t, svc.UpsertFavorite(mkFav("/media/novel", "novel", true, 9999)))
	list, err := svc.ListFavorites()
	assert.NoError(t, err)
	assert.Len(t, list, 1)
	assert.Equal(t, int64(1000), list[0].AddedAt)
	// snapshot 随 added_at 胜者覆盖：更晚 added_at 的新 snapshot 生效
	assert.NoError(t, svc.UpsertFavorite(models.FavoriteUpdate{Path: "/media/novel", IsDir: true,
		MediaType: "folder", Snapshot: json.RawMessage(`{"folder":{"name":"v2"}}`), AddedAt: 2000}))
	list, _ = svc.ListFavorites()
	assert.Equal(t, int64(2000), list[0].AddedAt)
	assert.JSONEq(t, `{"folder":{"name":"v2"}}`, string(list[0].Snapshot))
}

func TestFavoritesRemoveAndList(t *testing.T) {
	svc := newTestLibraryService(t)
	assert.NoError(t, svc.UpsertFavorite(mkFav("/a.txt", "a", false, 1)))
	assert.NoError(t, svc.UpsertFavorite(mkFav("/b.txt", "b", false, 2)))
	assert.NoError(t, svc.RemoveFavorite("/a.txt"))
	list, err := svc.ListFavorites()
	assert.NoError(t, err)
	assert.Len(t, list, 1)
	assert.Equal(t, "/b.txt", list[0].Path)
	assert.NoError(t, svc.RemoveFavorite("/missing.txt")) // 删除不存在不报错
}

func TestFavoritePaths(t *testing.T) {
	svc := newTestLibraryService(t)
	assert.NoError(t, svc.UpsertFavorite(mkFav("/media/x.txt", "x", false, 1)))
	assert.NoError(t, svc.UpsertFavorite(mkFav("/media/z.txt", "z", false, 2)))
	got, err := svc.FavoritePaths([]string{"/media/y.txt", "/media/z.txt", "/MEDIA/X.TXT"})
	assert.NoError(t, err)
	assert.Equal(t, []string{"/media/z.txt", "/MEDIA/X.TXT"}, got) // 输入原序 + 大小写不敏感命中
}
```

补 import `"encoding/json"`。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && go test ./internal/service/ -run TestFavorite -v`
Expected: FAIL

- [ ] **Step 3: 实现**（library.go 追加）

```go
func (s *LibraryService) UpsertFavorite(f models.FavoriteUpdate) error {
	s.mu.RLock()
	defer s.mu.RUnlock()
	snap := "{}"
	if len(f.Snapshot) > 0 {
		snap = string(f.Snapshot)
	}
	if len(snap) > 8192 {
		return fmt.Errorf("snapshot too large")
	}
	_, err := s.db.Exec(`
		INSERT INTO favorites (path, is_dir, is_system, title, media_type, snapshot, added_at)
		VALUES (?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(path) DO UPDATE SET
			is_dir = excluded.is_dir, is_system = excluded.is_system, title = excluded.title,
			media_type = excluded.media_type, snapshot = excluded.snapshot,
			added_at = CASE WHEN excluded.added_at >= favorites.added_at THEN excluded.added_at ELSE favorites.added_at END`,
		f.Path, boolToInt(f.IsDir), boolToInt(f.IsSystem), f.Title, f.MediaType, snap, f.AddedAt)
	return err
}

func (s *LibraryService) RemoveFavorite(path string) error {
	s.mu.RLock()
	defer s.mu.RUnlock()
	_, err := s.db.Exec(`DELETE FROM favorites WHERE path = ?`, path)
	return err
}

func (s *LibraryService) ListFavorites() ([]models.FavoriteRecord, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	rows, err := s.db.Query(`SELECT path, is_dir, is_system, title, media_type, snapshot, added_at
		FROM favorites ORDER BY added_at DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]models.FavoriteRecord, 0)
	for rows.Next() {
		var r models.FavoriteRecord
		var isDir, isSystem int
		var snap string
		if err := rows.Scan(&r.Path, &isDir, &isSystem, &r.Title, &r.MediaType, &snap, &r.AddedAt); err != nil {
			return nil, err
		}
		r.IsDir, r.IsSystem = isDir == 1, isSystem == 1
		r.Snapshot = json.RawMessage(snap)
		out = append(out, r)
	}
	return out, rows.Err()
}

// FavoritePaths 返回 paths（已是规范化形态）中已收藏者，保持输入顺序；批量 500 一组 IN 查询。
func (s *LibraryService) FavoritePaths(paths []string) ([]string, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	found := make(map[string]bool)
	const batchSize = 500
	for start := 0; start < len(paths); start += batchSize {
		end := min(start+batchSize, len(paths))
		batch := paths[start:end]
		placeholders := strings.Repeat("?,", len(batch)-1) + "?"
		args := make([]interface{}, len(batch))
		for i, p := range batch {
			args[i] = p
		}
		rows, err := s.db.Query(`SELECT path FROM favorites WHERE path IN (`+placeholders+`)`, args...)
		if err != nil {
			return nil, err
		}
		for rows.Next() {
			var p string
			if err := rows.Scan(&p); err != nil {
				rows.Close()
				return nil, err
			}
			found[p] = true
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return nil, err
		}
	}
	out := make([]string, 0)
	for _, p := range paths {
		if found[p] {
			out = append(out, p)
		}
	}
	return out, nil
}
```

补 import `"strings"`（若已存在则跳过）。`min` 需要 Go 1.21+（go.mod 已满足；若不足则手写）。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && go test ./internal/service/ -run TestFavorite -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/internal/service/library.go server/internal/service/library_test.go
git commit -m "feat(library): favorites CRUD with snapshot passthrough and added-at merge (Phase 1)"
```

---

### Task 4: 批量装饰查询 BatchDecorations

**Files:**
- Modify: `server/internal/service/library.go`
- Test: `server/internal/service/library_test.go`

**Interfaces:**
- Consumes: `deriveStatus`（Task 1）
- Produces: `func (s *LibraryService) BatchDecorations(paths []string) (models.DecorationsResult, error)`——`States` key 为**调用方传入的原字符串**（handler 负责 normalized→original 回填）；无行路径省略；`manual_status='unread'` 行以 status unread 包含

- [ ] **Step 1: 写失败测试**（追加）

```go
func TestBatchDecorations(t *testing.T) {
	svc := newTestLibraryService(t)
	_, _ = svc.UpsertProgress(mkUpdate("/m/a.txt", 5, 0, 50, false, 1000))
	_, _ = svc.UpsertProgress(mkUpdate("/m/b.txt", 9, 9, 100, true, 1000))
	_, _ = svc.UpsertProgress(mkUpdate("/m/c.txt", 1, 0, 5, false, 1000))
	_, _ = svc.SetManualStatus("/m/c.txt", strPtr("unread"))
	assert.NoError(t, svc.UpsertFavorite(mkFav("/m/b.txt", "b", false, 1)))

	res, err := svc.BatchDecorations([]string{"/m/a.txt", "/m/b.txt", "/m/c.txt", "/m/d.txt"})
	assert.NoError(t, err)
	assert.Len(t, res.States, 3) // d.txt 无行省略
	assert.Equal(t, "reading", res.States["/m/a.txt"].Status)
	assert.InDelta(t, 50.0, res.States["/m/a.txt"].Percent, 1e-9)
	assert.Equal(t, "finished", res.States["/m/b.txt"].Status)
	assert.Equal(t, "unread", res.States["/m/c.txt"].Status) // manual unread 显式返回
	assert.Equal(t, []string{"/m/b.txt"}, res.Favorites)
}

func strPtr(s string) *string { return &s }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && go test ./internal/service/ -run TestBatchDecorations -v`
Expected: FAIL

- [ ] **Step 3: 实现**（library.go 追加；批量模式同 FavoritePaths）

```go
// BatchDecorations：批量查状态 + 收藏。States key 用调用方原字符串；重复路径去重后仍按首个位置回填。
func (s *LibraryService) BatchDecorations(paths []string) (models.DecorationsResult, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	res := models.DecorationsResult{States: map[string]models.ReadingStateBadge{}, Favorites: []string{}}
	const batchSize = 500
	for start := 0; start < len(paths); start += batchSize {
		end := min(start+batchSize, len(paths))
		batch := paths[start:end]
		placeholders := strings.Repeat("?,", len(batch)-1) + "?"
		args := make([]interface{}, len(batch))
		for i, p := range batch {
			args[i] = p
		}
		// states：行存在才返回
		rows, err := s.db.Query(`SELECT path, percent, finished, manual_status, last_read_at
			FROM reading_states WHERE path IN (`+placeholders+`)`, args...)
		if err != nil {
			return res, err
		}
		for rows.Next() {
			var p string
			var pct float64
			var finished int
			var manual *string
			var lastAt int64
			if err := rows.Scan(&p, &pct, &finished, &manual, &lastAt); err != nil {
				rows.Close()
				return res, err
			}
			res.States[p] = models.ReadingStateBadge{
				Status: deriveStatus(finished == 1, manual, true), Percent: pct, LastReadAt: lastAt,
			}
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return res, err
		}
		// favorites
		frows, err := s.db.Query(`SELECT path FROM favorites WHERE path IN (`+placeholders+`)`, args...)
		if err != nil {
			return res, err
		}
		favSet := map[string]bool{}
		for frows.Next() {
			var p string
			if err := frows.Scan(&p); err != nil {
				frows.Close()
				return res, err
			}
			favSet[p] = true
		}
		frows.Close()
		if err := frows.Err(); err != nil {
			return res, err
		}
		for _, p := range batch {
			if favSet[p] {
				res.Favorites = append(res.Favorites, p)
			}
		}
	}
	return res, nil
}
```

注意：SQLite 返回的 `path` 是**入库时的规范化形态**，与请求的原始形态可能大小写不同——service 层在结果 map 里先以 DB 形态返回；**handler 层负责映射回原始 key**（Task 5），本测试用同形态路径不触发该差异。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && go test ./internal/service/ -run TestBatchDecorations -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/internal/service/library.go server/internal/service/library_test.go
git commit -m "feat(library): batch decorations query for list badges and favorites (Phase 1)"
```

---

### Task 5: Handler + 路由 + Server 接线

**Files:**
- Create: `server/internal/server/handler/library.go`
- Create: `server/internal/server/handler/library_test.go`
- Modify: `server/internal/server/handler/handler.go:37-70`（Handler struct + New 增第 8 参）
- Modify: `server/internal/server/server.go`（Server struct / New / registerRoutes / Stop）
- Modify: `server/internal/server/handler/tags_test.go` 等所有 `handler.New(...)` 调用点（加 nil 参数）
- Modify: `docs/INDEX.md`（API 端点表追加 library 组）

**Interfaces:**
- Consumes: Task 1-4 全部 service 方法；`service.NormalizePath` / `service.IsPathWithinRoots` / `service.IsBlockedRoot`（path.go:58/109/41）；`h.isPathWithinConfiguredRoots(pathStr)`（tags.go:100-107，返回 normalized path + error——实现时核对实际返回形态，若只返回 bool 则自行组合）
- Produces: 7 个 handler 方法（`PostReadingState` / `GetReadingState` / `PutReadingStatus` / `PostDecorations` / `ListFavorites` / `AddFavorite` / `DeleteFavorite`）

- [ ] **Step 1: 修正 handler.New 签名**（handler.go）

```go
// struct 增字段
library *service.LibraryService

// New 签名追加 library *service.LibraryService（放 bookSigner 之前，保持 cfg, scanner, tags, streaming, thumbnail, library, books, bookSigner 顺序）
```

全仓 `grep -rn "handler.New(" server/` 更新调用点（server.go:128 传 `libraryService`，其余测试文件补 `nil`）。

- [ ] **Step 2: 写失败 handler 测试**（`library_test.go`，仿 tags_test.go 直调模式）

```go
package handler

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/labstack/echo/v4"
	"github.com/stretchr/testify/assert"

	"github.com/localmediahub/server/internal/config"
	"github.com/localmediahub/server/internal/service"
)

func newLibraryTestHandler(t *testing.T) (*Handler, string) {
	t.Helper()
	root := t.TempDir()
	mediaDir := filepath.Join(root, "novels")
	assert.NoError(t, os.MkdirAll(mediaDir, 0755))
	assert.NoError(t, os.WriteFile(filepath.Join(mediaDir, "a.txt"), []byte("hello"), 0644))
	cfg := &config.Config{
		Scan:    config.ScanConfig{Roots: []string{root}, TextExtensions: []string{".txt", ".epub"}},
		System:  config.SystemConfig{},
	}
	libSvc, err := service.NewLibraryService(t.TempDir())
	assert.NoError(t, err)
	t.Cleanup(func() { _ = libSvc.Close() })
	return New(cfg, nil, nil, nil, nil, libSvc, nil, nil), filepath.Join(mediaDir, "a.txt")
}

func postJSON(t *testing.T, h *Handler, method, target, body string) *httptest.ResponseRecorder {
	t.Helper()
	e := echo.New()
	req := httptest.NewRequest(method, target, strings.NewReader(body))
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	rec := httptest.NewRecorder()
	assert.NoError(t, h.PostReadingState(e.NewContext(req, rec)))
	return rec
}

func TestPostReadingStateRoundtrip(t *testing.T) {
	h, mediaPath := newLibraryTestHandler(t)
	rec := postJSON(t, h, http.MethodPost, "/api/v1/library/states",
		`{"path":`+strconv.Quote(mediaPath)+`,"chapter_index":2,"para_index":1,"percent":25.5,"finished":false,"last_read_at":1000}`)
	assert.Equal(t, http.StatusOK, rec.Code)
	assert.Contains(t, rec.Body.String(), `"status":"reading"`)

	// GET 单条
	e := echo.New()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/library/states?path="+mediaPath, nil)
	rec2 := httptest.NewRecorder()
	assert.NoError(t, h.GetReadingState(e.NewContext(req, rec2)))
	assert.Equal(t, http.StatusOK, rec2.Code)
	assert.Contains(t, rec2.Body.String(), `"chapter_index":2`)
}

func TestPostReadingStateRejectsOutsideRoots(t *testing.T) {
	h, _ := newLibraryTestHandler(t)
	rec := postJSON(t, h, http.MethodPost, "/api/v1/library/states",
		`{"path":"Z:\\elsewhere\\x.txt","chapter_index":0,"para_index":0,"percent":0,"finished":false,"last_read_at":1}`)
	assert.Equal(t, http.StatusBadRequest, rec.Code)
}

func TestPostDecorationsKeyFidelity(t *testing.T) {
	h, mediaPath := newLibraryTestHandler(t)
	rec := postJSON(t, h, http.MethodPost, "/api/v1/library/states",
		`{"path":`+strconv.Quote(mediaPath)+`,"chapter_index":0,"para_index":0,"percent":5,"finished":false,"last_read_at":1}`)
	assert.Equal(t, http.StatusOK, rec.Code)
	// 用与入库形态仅大小写不同的字符串请求，响应 key 必须原样返回
	variant := strings.ToUpper(mediaPath)
	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/library/decorations",
		strings.NewReader(`{"paths":[`+strconv.Quote(variant)+`]}`))
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	rec2 := httptest.NewRecorder()
	assert.NoError(t, h.PostDecorations(e.NewContext(req, rec2)))
	assert.Equal(t, http.StatusOK, rec2.Code)
	assert.Contains(t, rec2.Body.String(), strconv.Quote(variant)) // key 保真
}

func TestPostDecorationsRejectsOversize(t *testing.T) {
	h, _ := newLibraryTestHandler(t)
	paths := make([]string, 501)
	for i := range paths {
		paths[i] = "x"
	}
	body := `{"paths":["` + strings.Join(paths, `","`) + `"]}`
	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/library/decorations", strings.NewReader(body))
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	rec := httptest.NewRecorder()
	assert.NoError(t, h.PostDecorations(e.NewContext(req, rec)))
	assert.Equal(t, http.StatusBadRequest, rec.Code)
}
```

补 `"strconv"` import。`strconv.Quote` 用于 Windows 路径反斜杠转义。

- [ ] **Step 3: 跑测试确认失败**

Run: `cd server && go test ./internal/server/handler/ -run 'TestPostReadingState|TestPostDecorations' -v`
Expected: FAIL（方法未定义）

- [ ] **Step 4: 实现 handler**（`handler/library.go` 全量）

```go
package handler

import (
	"net/http"
	"strings"

	"github.com/labstack/echo/v4"

	"github.com/localmediahub/server/internal/models"
	"github.com/localmediahub/server/internal/service"
)

const maxDecorationPaths = 500

// validateTextPath：阅读状态对象必须是扫描/系统根内的文本媒体文件（ValidateAccessibleMediaPath 拒目录）。
func (h *Handler) validateTextPath(c echo.Context, rawPath string) (string, bool) {
	if rawPath == "" {
		_ = respondError(c, http.StatusBadRequest, "path is required")
		return "", false
	}
	normalized, err := service.ValidateAccessibleMediaPath(rawPath,
		h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.TextExtensions)
	if err != nil {
		_ = respondError(c, http.StatusBadRequest, "path not accessible")
		return "", false
	}
	return normalized, true
}

// validateAnyEntryPath：收藏/批量装饰对象可为文件或目录——边界 roots + 敏感段阻断。
func (h *Handler) validateAnyEntryPath(rawPath string) (string, bool) {
	if rawPath == "" {
		return "", false
	}
	normalized, err := service.NormalizePath(rawPath)
	if err != nil {
		return "", false
	}
	allRoots := append(h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots()...)
	ok, err := service.IsPathWithinRoots(normalized, allRoots)
	if err != nil || !ok || service.IsBlockedRoot(normalized) {
		return "", false
	}
	return normalized, true
}

func (h *Handler) PostReadingState(c echo.Context) error {
	var req models.ProgressUpdate
	if err := c.Bind(&req); err != nil {
		return respondError(c, http.StatusBadRequest, "invalid request body")
	}
	normalized, ok := h.validateTextPath(c, req.Path)
	if !ok {
		return nil
	}
	req.Path = normalized
	st, err := h.library.UpsertProgress(req)
	if err != nil {
		return respondInternalError(c, err)
	}
	status := deriveStatusPublic(st)
	return c.JSON(http.StatusOK, map[string]interface{}{
		"status": status, "updated_at": st.UpdatedAt,
	})
}

// deriveStatusPublic：service.deriveStatus 未导出，此处按响应需要的纯派生复刻（行必存在）。
func deriveStatusPublic(st models.ReadingState) string {
	if st.ManualStatus != nil {
		return *st.ManualStatus
	}
	if st.Finished {
		return "finished"
	}
	return "reading"
}

func (h *Handler) GetReadingState(c echo.Context) error {
	normalized, ok := h.validateTextPath(c, c.QueryParam("path"))
	if !ok {
		return nil
	}
	st, err := h.library.GetState(normalized)
	if err != nil {
		return respondInternalError(c, err)
	}
	return c.JSON(http.StatusOK, map[string]interface{}{"state": st}) // st 为 nil → "state":null
}

func (h *Handler) PutReadingStatus(c echo.Context) error {
	var req struct {
		Path   string  `json:"path"`
		Status *string `json:"status"`
	}
	if err := c.Bind(&req); err != nil {
		return respondError(c, http.StatusBadRequest, "invalid request body")
	}
	if req.Status != nil {
		switch *req.Status {
		case "unread", "reading", "finished":
		default:
			return respondError(c, http.StatusBadRequest, "invalid status")
		}
	}
	normalized, ok := h.validateTextPath(c, req.Path)
	if !ok {
		return nil
	}
	st, err := h.library.SetManualStatus(normalized, req.Status)
	if err != nil {
		return respondInternalError(c, err)
	}
	return c.JSON(http.StatusOK, map[string]string{"status": deriveStatusPublic(st)})
}

func (h *Handler) PostDecorations(c echo.Context) error {
	var req struct {
		Paths []string `json:"paths"`
	}
	if err := c.Bind(&req); err != nil {
		return respondError(c, http.StatusBadRequest, "invalid request body")
	}
	if len(req.Paths) > maxDecorationPaths {
		return respondError(c, http.StatusBadRequest, "too many paths")
	}
	normalizedToOriginal := map[string]string{} // normalized -> 首个原始形态
	normalizedList := make([]string, 0, len(req.Paths))
	seen := map[string]bool{}
	for _, raw := range req.Paths {
		norm, ok := h.validateAnyEntryPath(raw)
		if !ok || seen[norm] {
			continue // 无效/重复：静默跳过
		}
		seen[norm] = true
		normalizedToOriginal[norm] = raw
		normalizedList = append(normalizedList, norm)
	}
	res, err := h.library.BatchDecorations(normalizedList)
	if err != nil {
		return respondInternalError(c, err)
	}
	// key 保真：DB 形态 → 调用方原始字符串
	states := make(map[string]models.ReadingStateBadge, len(res.States))
	for norm, badge := range res.States {
		orig, ok := normalizedToOriginal[norm]
		if !ok {
			orig = norm
		}
		states[orig] = badge
	}
	favs := make([]string, 0, len(res.Favorites))
	for _, p := range res.Favorites {
		if orig, ok := normalizedToOriginal[p]; ok {
			favs = append(favs, orig)
		} else {
			favs = append(favs, p)
		}
	}
	return c.JSON(http.StatusOK, models.DecorationsResult{States: states, Favorites: favs})
}

func (h *Handler) ListFavorites(c echo.Context) error {
	list, err := h.library.ListFavorites()
	if err != nil {
		return respondInternalError(c, err)
	}
	return c.JSON(http.StatusOK, list)
}

func (h *Handler) AddFavorite(c echo.Context) error {
	var req models.FavoriteUpdate
	if err := c.Bind(&req); err != nil {
		return respondError(c, http.StatusBadRequest, "invalid request body")
	}
	if len(req.Snapshot) > 8192 {
		return respondError(c, http.StatusBadRequest, "snapshot too large")
	}
	normalized, ok := h.validateAnyEntryPath(req.Path)
	if !ok {
		return respondError(c, http.StatusBadRequest, "path not accessible")
	}
	req.Path = normalized
	if req.AddedAt == 0 {
		req.AddedAt = time.Now().UnixMilli()
	}
	if err := h.library.UpsertFavorite(req); err != nil {
		return respondInternalError(c, err)
	}
	return c.JSON(http.StatusOK, map[string]bool{"ok": true})
}

func (h *Handler) DeleteFavorite(c echo.Context) error {
	raw := c.QueryParam("path")
	normalized, ok := h.validateAnyEntryPath(raw)
	if !ok {
		return respondError(c, http.StatusBadRequest, "path not accessible")
	}
	if err := h.library.RemoveFavorite(normalized); err != nil {
		return respondInternalError(c, err)
	}
	return c.JSON(http.StatusOK, map[string]bool{"ok": true})
}
```

补 `"time"` import；删除未用的 `"strings"` import（若 handler.go 已有同 helper 命名冲突按编译器提示调整）。`respondError`/`respondInternalError` 已存在于 handler.go:115/127。

- [ ] **Step 5: server.go 接线**

```go
// Server struct（server.go:26-51）加字段（Tags 之后）：
Library *service.LibraryService

// New() 内 tagsService 之后（~line 79）：
libraryService, err := service.NewLibraryService(".data")
if err != nil {
    return nil, fmt.Errorf("failed to init library service: %w", err)
}
s.Library = libraryService

// handler.New 调用处（~line 128）：插入 libraryService 参数

// registerRoutes() tags 路由组之后（~line 362）：
lib := api.Group("/library", authMw)
lib.POST("/states", h.PostReadingState)
lib.GET("/states", h.GetReadingState)
lib.PUT("/states/status", h.PutReadingStatus)
lib.POST("/decorations", h.PostDecorations)
lib.GET("/favorites", h.ListFavorites)
lib.POST("/favorites", h.AddFavorite)
lib.DELETE("/favorites", h.DeleteFavorite)

// Stop()（~line 491，s.Tags.Close() 之后）：
if err := s.Library.Close(); err != nil {
    log.Printf("[WARN] Failed to close library DB: %v", err)
}
```

- [ ] **Step 6: 全量验证**

Run: `cd server && go build ./... && go test ./...`
Expected: 编译通过，全测试 PASS（含既有 tags/handler 测试未被 New 签名变更破坏）

- [ ] **Step 7: 更新 docs/INDEX.md API 端点表**（追加 `/api/v1/library/*` 7 行：方法/路径/鉴权 Bearer/用途一句话）

- [ ] **Step 8: Commit**

```bash
git add server/ docs/INDEX.md
git commit -m "feat(library): library REST endpoints wired with bearer auth and layered path validation (Phase 1)"
```

---

## Phase 2 — Web

### Task 6: library.js 纯函数（筛选矩阵 + 徽章 HTML + 上报 payload 计算）

**Files:**
- Create: `server/internal/web/library.js`
- Test: `server/internal/web/library.test.mjs`

**Interfaces:**
- Produces:
  - `applyListFilters(folders, files, decorations, { favoritesOnly, statusFilter })` → `{ folders, files }`
  - `badgeHtmlFor(status, percent)` → `string`（'' / 读过 / 已读完）
  - `computeReportPayload({ chapterIndex, paraIndex, chapterParaCount, totalChapters, atChapterEnd })` → `{ percent, finished }`
  - `runWithConcurrency(tasks, limit)` → `Promise`
  - decorations 形态：`{ states: { [path]: { status, percent, last_read_at } }, favorites: [path] }`

- [ ] **Step 1: 写失败测试**（`library.test.mjs`，纯函数无 jsdom）

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { applyListFilters, badgeHtmlFor, computeReportPayload } from './library.js';

const decos = {
    states: {
        '/m/a.txt': { status: 'reading', percent: 42.5, last_read_at: 1 },
        '/m/b.txt': { status: 'finished', percent: 100, last_read_at: 2 },
        '/m/c.txt': { status: 'unread', percent: 0, last_read_at: 3 },
    },
    favorites: ['/m/b.txt', '/m/comics'],
};
const folders = [{ path: '/m/comics', name: 'comics' }, { path: '/m/other', name: 'other' }];
const files = [
    { path: '/m/a.txt', name: 'a', media_type: 'text' },
    { path: '/m/b.txt', name: 'b', media_type: 'text' },
    { path: '/m/c.txt', name: 'c', media_type: 'text' },
    { path: '/m/d.txt', name: 'd', media_type: 'text' }, // 无行 = 未读
    { path: '/m/v.mp4', name: 'v', media_type: 'video' },
];

test('applyListFilters: no filter passes through', () => {
    const r = applyListFilters(folders, files, decos, { favoritesOnly: false, statusFilter: null });
    assert.equal(r.folders.length, 2);
    assert.equal(r.files.length, 5);
});

test('applyListFilters: favoritesOnly matches files and folders by path', () => {
    const r = applyListFilters(folders, files, decos, { favoritesOnly: true, statusFilter: null });
    assert.deepEqual(r.folders.map(f => f.path), ['/m/comics']);
    assert.deepEqual(r.files.map(f => f.path), ['/m/b.txt']);
});

test('applyListFilters: statusFilter keeps only text cards matching, hides folders/videos', () => {
    const r = applyListFilters(folders, files, decos, { favoritesOnly: false, statusFilter: 'reading' });
    assert.equal(r.folders.length, 0);
    assert.deepEqual(r.files.map(f => f.path), ['/m/a.txt']);
    const u = applyListFilters(folders, files, decos, { favoritesOnly: false, statusFilter: 'unread' });
    assert.deepEqual(u.files.map(f => f.path).sort(), ['/m/c.txt', '/m/d.txt']);
});

test('applyListFilters: both filters intersect', () => {
    const r = applyListFilters(folders, files, decos, { favoritesOnly: true, statusFilter: 'finished' });
    assert.deepEqual(r.files.map(f => f.path), ['/m/b.txt']);
    assert.equal(r.folders.length, 0);
});

test('applyListFilters: empty decorations tolerated', () => {
    const r = applyListFilters(folders, files, null, { favoritesOnly: true, statusFilter: null });
    assert.equal(r.folders.length + r.files.length, 0);
});

test('badgeHtmlFor', () => {
    assert.equal(badgeHtmlFor('unread', 0), '');
    assert.equal(badgeHtmlFor(null, 0), '');
    assert.ok(badgeHtmlFor('reading', 42.5).includes('读到 42.5%'));
    assert.ok(badgeHtmlFor('reading', 0).includes('>读过<'));
    assert.ok(badgeHtmlFor('finished', 100).includes('已读完'));
    assert.ok(badgeHtmlFor('finished', 100).includes('card-badge--finished'));
});

test('computeReportPayload', () => {
    const p = computeReportPayload({ chapterIndex: 2, paraIndex: 5, chapterParaCount: 10, totalChapters: 5, atChapterEnd: false });
    assert.equal(p.percent, 50); // (2 + 0.5) / 5
    assert.equal(p.finished, false);
    const f = computeReportPayload({ chapterIndex: 4, paraIndex: 9, chapterParaCount: 10, totalChapters: 5, atChapterEnd: true });
    assert.equal(f.finished, true); // 末章 + 章尾
    assert.equal(f.percent, 100);   // clamp
    const zero = computeReportPayload({ chapterIndex: 0, paraIndex: 0, chapterParaCount: 0, totalChapters: 0, atChapterEnd: false });
    assert.equal(zero.percent, 0);  // max(1,..) 防除零
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server/internal/web && node --test library.test.mjs`
Expected: FAIL（Cannot find module './library.js'）

- [ ] **Step 3: 实现 library.js 纯函数部分**（文件头，API 函数 Task 7 追加）

```js
// 阅读状态 + 收藏（library）模块：纯函数 + API + 列表装饰。
// 注意：不得在模块求值期访问 localStorage（jsdom 测试约束）。

export function applyListFilters(folders, files, decorations, { favoritesOnly, statusFilter }) {
    const favSet = new Set(decorations ? decorations.favorites : []);
    const states = (decorations && decorations.states) || {};
    let outFolders = folders;
    let outFiles = files;
    if (statusFilter != null) {
        // 状态筛选：仅小说；文件夹与非文本一律隐藏
        outFolders = [];
        outFiles = outFiles.filter(f => {
            if (f.media_type !== 'text') return false;
            const badge = states[f.path];
            const status = badge ? badge.status : 'unread';
            return status === statusFilter;
        });
    }
    if (favoritesOnly) {
        outFolders = outFolders.filter(f => favSet.has(f.path));
        outFiles = outFiles.filter(f => favSet.has(f.path));
    }
    return { folders: outFolders, files: outFiles };
}

export function badgeHtmlFor(status, percent) {
    if (status === 'finished') {
        return '<span class="card-badge card-badge--finished">✓ 已读完</span>'; // XSS-SAFE: 静态常量
    }
    if (status === 'reading') {
        const label = percent > 0 ? `读到 ${percent}%` : '读过';
        return `<span class="card-badge card-badge--reading">${label}</span>`; // XSS-SAFE: label 为受控文案+数字
    }
    return '';
}

// percent = clamp(((chapterIndex + intra) / max(1,totalChapters)) * 100, 0, 100)，1 位小数
export function computeReportPayload({ chapterIndex, paraIndex, chapterParaCount, totalChapters, atChapterEnd }) {
    const intra = chapterParaCount > 0 ? Math.min(1, paraIndex / chapterParaCount) : 0;
    const total = Math.max(1, totalChapters);
    let percent = ((chapterIndex + intra) / total) * 100;
    percent = Math.min(100, Math.max(0, percent));
    percent = Math.round(percent * 10) / 10;
    const finished = totalChapters > 0 && chapterIndex === totalChapters - 1 && atChapterEnd;
    return { percent, finished };
}

export function runWithConcurrency(taskFactories, limit) {
    return new Promise((resolve) => {
        let next = 0;
        let active = 0;
        const results = new Array(taskFactories.length);
        const launch = () => {
            if (next >= taskFactories.length && active === 0) return resolve(results);
            while (active < limit && next < taskFactories.length) {
                const i = next++;
                active++;
                Promise.resolve()
                    .then(taskFactories[i])
                    .catch(() => {})
                    .then(() => { active--; launch(); });
            }
        };
        launch();
    });
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server/internal/web && node --test library.test.mjs`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/internal/web/library.js server/internal/web/library.test.mjs
git commit -m "feat(library): web pure helpers for list filters, badges and report payload (Phase 2)"
```

---

### Task 7: library.js API 层 + decorations 缓存 + browserView 卡片改造

**Files:**
- Modify: `server/internal/web/library.js`（追加 API + 缓存 + decorate）
- Modify: `server/internal/web/browserView.js`（卡片模板 / 点击委托 / decorate 接线 / ICONS）
- Modify: `server/internal/web/css/views/browser.css`（badge/心形/chips 样式——本任务先加 badge+心形）
- Modify: `server/internal/web/index.html`（无——筛选 chips 属 Task 8）
- Test: `server/internal/web/library.test.mjs`（扩展缓存逻辑测试）

**Interfaces:**
- Consumes: `apiRequest`（api.js:6）、`state`（state.js）、`showToast`（toast.js）、`safeBtoa`（utils.js:19）
- Produces:
  - `initLibrary()`（创建状态菜单 DOM，app.js/setupBrowserListeners 调用一次）
  - `refreshDecorations()`（browserView 渲染后调用：缓存命中则同步 patch，否则异步拉取后 patch）
  - `decorateBrowserList(container)`（DOM 就地 Patch：徽章 + 心形 active 态）
  - `toggleFavorite(path, isDir, title, mediaType)`（乐观 UI 由调用方处理，本函数只调 API 并刷新缓存）
  - `markStatus(path, status)`（status ∈ 'unread'|'reading'|'finished'|null）
  - `fetchState(path)` → `{ state: {...} } | null`
  - `reportState(path, payload)`（静默失败）
  - `migrateLocalProgress()`（Task 11 用）
  - `getDecorations()`（供 applyListFilters 消费）
  - 全局状态存于 state.js 新增字段：`favoritesOnly: false`、`statusFilter: null`（session 级，不持久化）

- [ ] **Step 1: 写失败测试**（library.test.mjs 追加——缓存与 patch 用 jsdom）

```js
import { setupJsdom, teardownJsdom } from './_snapshot-helpers.mjs';
import { setDecorationsForTest, decorateBrowserList } from './library.js';

test('decorateBrowserList patches badge and heart state in place', () => {
    setupJsdom();
    try {
        document.body.innerHTML = `
        <div id="browser-list">
          <div class="media-card" data-path="/m/a.txt" data-media-type="text">
            <div class="card-actions-overlay">
              <button class="card-action-btn fav-btn" data-action="fav-toggle" data-path="/m/a.txt"></button>
            </div>
            <div class="card-details"><div class="card-meta"></div></div>
          </div>
          <div class="media-card" data-path="/m/b.txt" data-media-type="text">
            <div class="card-actions-overlay">
              <button class="card-action-btn fav-btn" data-action="fav-toggle" data-path="/m/b.txt"></button>
            </div>
            <div class="card-details"><div class="card-meta"><span>已有徽章位</span></div></div>
          </div>
        </div>`;
        setDecorationsForTest({
            states: { '/m/a.txt': { status: 'reading', percent: 30, last_read_at: 1 } },
            favorites: ['/m/b.txt'],
        });
        decorateBrowserList(document.getElementById('browser-list'));
        const a = document.querySelector('[data-path="/m/a.txt"]');
        assert.ok(a.querySelector('.card-badge--reading'));
        assert.ok(!a.querySelector('.fav-btn.active'));
        const b = document.querySelector('[data-path="/m/b.txt"]');
        assert.ok(b.querySelector('.fav-btn.active'));
        // 重复 patch 幂等（不叠加徽章）
        decorateBrowserList(document.getElementById('browser-list'));
        assert.equal(a.querySelectorAll('.card-badge--reading').length, 1);
    } finally {
        teardownJsdom();
    }
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server/internal/web && node --test library.test.mjs`
Expected: FAIL（setDecorationsForTest 未导出）

- [ ] **Step 3: 实现 API + 缓存 + patch**（library.js 追加）

```js
import { apiRequest } from './api.js';
import { state } from './state.js';
import { showToast } from './toast.js';

let decorations = null; // { key, states, favorites }
let decorationsKey = '';

export function setDecorationsForTest(d) { decorations = d; decorationsKey = 'test'; }

export function getDecorations() { return decorations; }

function chunk(arr, n) {
    const out = [];
    for (let i = 0; i < arr.length; i += n) out.push(arr.slice(i, i + n));
    return out;
}

async function fetchDecorationsFor(paths) {
    const states = {};
    const favorites = [];
    for (const part of chunk(paths, 500)) {
        const res = await apiRequest(`${state.apiBase}/api/v1/library/decorations`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ paths: part }),
        });
        Object.assign(states, res.states || {});
        favorites.push(...(res.favorites || []));
    }
    return { states, favorites };
}

// browserView 渲染后调用：缓存命中同步 patch；未命中异步拉取后 patch（若期间有筛选激活则触发回调重渲染）。
export async function refreshDecorations(onFilterableChange) {
    const paths = [
        ...state.currentFolders.map(f => f.path),
        ...state.currentFiles.map(f => f.path),
    ];
    const key = state.currentPath + '|' + paths.length;
    if (decorations && decorationsKey === key) {
        decorateBrowserList(document.getElementById('browser-list'));
        return;
    }
    try {
        const d = await fetchDecorationsFor(paths);
        decorations = d;
        decorationsKey = key;
        decorateBrowserList(document.getElementById('browser-list'));
        if (onFilterableChange && (state.favoritesOnly || state.statusFilter != null)) onFilterableChange();
    } catch (e) {
        /* 服务端不可达：徽章/心形降级缺席 */
    }
}

export function decorateBrowserList(container) {
    if (!container || !decorations) return;
    const favSet = new Set(decorations.favorites);
    container.querySelectorAll('.media-card[data-path]').forEach(card => {
        const path = card.dataset.path;
        const favBtn = card.querySelector('.fav-btn');
        if (favBtn) favBtn.classList.toggle('active', favSet.has(path));
        if (card.dataset.mediaType === 'text') {
            const meta = card.querySelector('.card-meta');
            if (!meta) return;
            const old = meta.querySelector('.card-badge--reading, .card-badge--finished');
            if (old) old.remove();
            const badge = decorations.states[path];
            const html = badgeHtmlFor(badge ? badge.status : 'unread', badge ? badge.percent : 0);
            if (html) meta.insertAdjacentHTML('beforeend', html); // XSS-SAFE: badgeHtmlFor 输出受控常量
        }
    });
}

export async function toggleFavorite(path, isDir, title, mediaType) {
    const favSet = new Set(decorations ? decorations.favorites : []);
    const wasFav = favSet.has(path);
    if (wasFav) favSet.delete(path); else favSet.add(path);
    decorations = { ...decorations, favorites: [...favSet] };
    decorateBrowserList(document.getElementById('browser-list'));
    try {
        if (wasFav) {
            await apiRequest(`${state.apiBase}/api/v1/library/favorites?path=${encodeURIComponent(path)}`, { method: 'DELETE' });
        } else {
            await apiRequest(`${state.apiBase}/api/v1/library/favorites`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ path, is_dir: isDir, is_system: state.isSystemBrowse,
                    title, media_type: mediaType, snapshot: { title } }),
            });
        }
        showToast(wasFav ? '已取消收藏' : '已收藏', 'success');
    } catch (e) {
        const rollback = new Set(decorations.favorites);
        if (wasFav) rollback.add(path); else rollback.delete(path);
        decorations = { ...decorations, favorites: [...rollback] };
        decorateBrowserList(document.getElementById('browser-list'));
        showToast(`收藏操作失败: ${e.message}`, 'error');
    }
}

export async function markStatus(path, status) {
    try {
        await apiRequest(`${state.apiBase}/api/v1/library/states/status`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path, status }),
        });
        decorationsKey = ''; // 强制下次 refresh 重新拉取
        await refreshDecorations();
    } catch (e) {
        showToast(`标记失败: ${e.message}`, 'error');
    }
}

export async function fetchState(path) {
    try {
        return await apiRequest(`${state.apiBase}/api/v1/library/states?path=${encodeURIComponent(path)}`);
    } catch (e) { return null; }
}

export function reportState(path, { chapterIndex, paraIndex, percent, finished, lastReadAt }) {
    apiRequest(`${state.apiBase}/api/v1/library/states`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ path, chapter_index: chapterIndex, para_index: paraIndex,
            percent, finished, last_read_at: lastReadAt }),
    }).catch(() => {}); // 静默：下次保存重试
}
```

- [ ] **Step 4: browserView 卡片模板改造**

(a) `ICONS`（browserView.js:20-29）追加：

```js
    heart: () => svgIcon('<path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/>', 16),
    dots: () => svgIcon('<circle cx="12" cy="5" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="12" cy="19" r="1"/>', 16),
```

(b) `renderBrowserList`（:231-363）三个模板分支统一改造：
- 文件夹卡片（:246-261）：`data-path` 已有 → 增加 `data-media-type="folder"`；overlay 内追加心形按钮：

```js
<div class="card-actions-overlay">
    ${state.enableDelete && !folder.is_root ? `<button class="card-action-btn delete-btn" title="删除文件夹" data-action="delete-folder" data-index="${index}">${ICONS.trash()}</button>` : ''}
    <button class="card-action-btn fav-btn" title="收藏" data-action="fav-toggle" data-path="${safePath}" data-is-dir="1" data-title="${safeName}" data-media-type="folder">${ICONS.heart()}</button>
</div>
```

- 文本卡片（:286-306）：外层 div 增加 `data-path="${safeBtoa(file.path).replace(/=/g, '')}"` **改为** `data-path="${escapeHtml(file.path)}"`、`data-media-type="text"`、`id="file-card-${safeBtoa(file.path).replace(/=/g, '')}"`；overlay 追加心形 + "⋮"（`data-action="status-menu"` `data-path="${escapeHtml(file.path)}"`）：

```js
<div class="card-actions-overlay">
    <button class="card-action-btn fav-btn" title="收藏" data-action="fav-toggle" data-path="${escapeHtml(file.path)}" data-is-dir="0" data-title="${safeName}" data-media-type="text">${ICONS.heart()}</button>
    <button class="card-action-btn dots-btn" title="阅读状态" data-action="status-menu" data-path="${escapeHtml(file.path)}">${ICONS.dots()}</button>
    ${state.enableDelete ? `<button class="card-action-btn delete-btn" title="删除文件" data-action="delete-file" data-index="${index}">${ICONS.trash()}</button>` : ''}
</div>
```

- 图片/视频卡片（:341-359）：已有 `id` → 增加 `data-path="${escapeHtml(file.path)}"`、`data-media-type="${isVideo ? 'video' : 'image'}"`；overlay 追加同款心形按钮（无 ⋮）。

(c) `renderBrowserList` 末尾（:362 innerHTML 赋值后）追加：

```js
    decorateBrowserList(elements.browserList); // 缓存命中同步补徽章/心形
    refreshDecorations(() => renderBrowserList()); // 未命中异步拉取；有筛选时重排
```

(d) `onBrowserListClick`（:172-198）追加两个 action 分支：

```js
    } else if (action === 'fav-toggle') {
        toggleFavorite(actionEl.dataset.path, actionEl.dataset.isDir === '1',
            actionEl.dataset.title || '', actionEl.dataset.mediaType || '');
    } else if (action === 'status-menu') {
        openStatusMenu(actionEl, actionEl.dataset.path);
```

(e) browserView.js 顶部 import：`import { refreshDecorations, decorateBrowserList, toggleFavorite, markStatus, openStatusMenu } from './library.js';`

(f) 状态菜单（library.js 内实现 `openStatusMenu(anchorEl, path)`）：单例 `div#card-status-menu`（首次调用时 createElement 挂 body），`position: fixed` 经 CSSOM `menu.style.left/top` 定位到 anchor 下方；四个按钮（标为已读完 / 标为读过 / 标为未读 / 清除手动标记）各调 `markStatus(path, 'finished'|'reading'|'unread'|null)` 后 `closeStatusMenu()`；`document` 捕获阶段点击外部关闭 + Escape 关闭。菜单 DOM 用 `textContent` 赋值（无 innerHTML），按钮类名 `status-menu__item`。

- [ ] **Step 5: CSS**（browser.css 追加，`card-badge--unsupported` 之后）

```css
.card-badge--reading { background-color: rgba(59, 130, 246, 0.18); color: #93c5fd; }
.card-badge--finished { background-color: rgba(34, 197, 94, 0.18); color: #86efac; }
.fav-btn.active { color: #f87171; }
.fav-btn.active svg { fill: currentColor; }
#card-status-menu {
    position: fixed; z-index: 200; min-width: 140px;
    background-color: var(--bg-secondary, #1e293b); border-radius: var(--radius-md, 8px);
    padding: 4px; box-shadow: 0 8px 24px rgba(0, 0, 0, 0.35);
    display: none; flex-direction: column; gap: 2px;
}
#card-status-menu.open { display: flex; }
#card-status-menu .status-menu__item {
    all: unset; cursor: pointer; padding: 8px 12px; border-radius: var(--radius-sm, 6px); font-size: 13px;
}
#card-status-menu .status-menu__item:hover { background-color: rgba(127, 127, 127, 0.15); }
```

主题变量名以 `themes.css` 实际值为准（`--bg-secondary` 不存在时用最近似 token，执行时核对）。

- [ ] **Step 6: 跑全部 web 测试**

Run: `cd server/internal/web && node --test`
Expected: PASS（含 snapshot-baseline 不回归）

- [ ] **Step 7: Commit**

```bash
git add server/internal/web/library.js server/internal/web/library.test.mjs server/internal/web/browserView.js server/internal/web/css/views/browser.css
git commit -m "feat(library): web list decorations with in-place DOM patch, hearts and status menu (Phase 2)"
```

---

### Task 8: 筛选 chips 工具栏

**Files:**
- Modify: `server/internal/web/index.html`（toolbar `.browser-controls` 内追加）
- Modify: `server/internal/web/browserView.js`（chips 接线 + 渲染接入 applyListFilters）
- Modify: `server/internal/web/state.js`（`favoritesOnly: false`、`statusFilter: null` 字段）
- Modify: `server/internal/web/css/views/browser.css`（chips 样式）

**Interfaces:**
- Consumes: `applyListFilters` / `getDecorations`（Task 6/7）
- Produces: `state.favoritesOnly: boolean`、`state.statusFilter: string|null`

- [ ] **Step 1: index.html toolbar**（`.browser-controls` 开头、`.sort-box` 之前插入）

```html
<div class="filter-chips" id="browser-filter-chips">
    <button class="filter-chip" id="chip-favorites" data-active="false" title="只显示当前目录中已收藏的内容">只看收藏</button>
    <span class="filter-chip-sep"></span>
    <button class="filter-chip filter-chip--status" data-status="" data-active="false">全部</button>
    <button class="filter-chip filter-chip--status" data-status="unread" data-active="false">未读</button>
    <button class="filter-chip filter-chip--status" data-status="reading" data-active="false">读过</button>
    <button class="filter-chip filter-chip--status" data-status="finished" data-active="false">已读完</button>
</div>
```

- [ ] **Step 2: browserView.js 接线**（`setupBrowserListeners` :433 内追加）

```js
    const chipsBox = document.getElementById('browser-filter-chips');
    if (chipsBox) {
        chipsBox.addEventListener('click', (e) => {
            const chip = e.target.closest('.filter-chip');
            if (!chip) return;
            if (chip.id === 'chip-favorites') {
                state.favoritesOnly = !state.favoritesOnly;
                chip.dataset.active = String(state.favoritesOnly);
            } else if (chip.classList.contains('filter-chip--status')) {
                const val = chip.dataset.status || null;
                state.statusFilter = state.statusFilter === val ? null : val;
                chipsBox.querySelectorAll('.filter-chip--status').forEach(c =>
                    c.dataset.active = String(state.statusFilter === (c.dataset.status || null)));
            }
            clearScrollMemory(state.currentPath); // 筛选变更清滚动记忆（Task 9 接口，先置空实现）
            renderBrowserList();
        });
    }
```

`renderBrowserList`（:231）排序之后、空判断之前插入过滤：

```js
    const filtered = applyListFilters(state.currentFolders, state.currentFiles, getDecorations(),
        { favoritesOnly: state.favoritesOnly, statusFilter: state.statusFilter });
    const shownFolders = filtered.folders;
    const shownFiles = filtered.files;
```

下方模板循环改用 `shownFolders` / `shownFiles`（`data-index` 随之指向过滤后数组——delete 分支用 `state.currentFolders[idx]` 取值需同步改为 `shownFolders[idx]`，`onBrowserListClick` 的 `delete-folder`/`delete-file`/`open`/`text-open` 分支同理）。空态判断用 `shownFolders.length === 0 && shownFiles.length === 0`，空态文案在有筛选时显示"当前筛选下无匹配内容"。

- [ ] **Step 3: state.js 字段**（sortOrder 之后追加）

```js
    favoritesOnly: false,
    statusFilter: null,
```

- [ ] **Step 4: CSS**（browser.css 追加）

```css
.filter-chips { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.filter-chip {
    border: 1px solid rgba(127, 127, 127, 0.3); background: transparent; color: inherit;
    border-radius: 999px; padding: 3px 10px; font-size: 12px; cursor: pointer;
}
.filter-chip[data-active="true"] {
    background-color: rgba(59, 130, 246, 0.25); border-color: rgba(59, 130, 246, 0.6);
}
.filter-chip-sep { width: 1px; height: 16px; background-color: rgba(127, 127, 127, 0.3); }
```

- [ ] **Step 5: 手动冒烟 + 测试**

Run: `cd server/internal/web && node --test`
Expected: PASS（无新增测试文件；过滤逻辑已在 Task 6 覆盖）
手动：`cd server && go build -o LocalMediaHub.exe ./cmd/server && ./LocalMediaHub.exe --headless` 打开 `http://localhost:8000/#/browser`，点 chips 验证过滤与空态文案。

- [ ] **Step 6: Commit**

```bash
git add server/internal/web/index.html server/internal/web/browserView.js server/internal/web/state.js server/internal/web/css/views/browser.css
git commit -m "feat(library): web filter chips for favorites-only and reading status (Phase 2)"
```

---

### Task 9: scrollMemory.js 滚动恢复

**Files:**
- Create: `server/internal/web/scrollMemory.js`
- Test: `server/internal/web/scrollMemory.test.mjs`
- Modify: `server/internal/web/browserView.js`（capture/restore/clear 接线）

**Interfaces:**
- Produces:
  - `captureScrollAnchor(cards, containerTop)` → `{ anchorPath, offset } | null`（纯函数：cards 为 `[{path, top, bottom}]` 升序）
  - `restoreScrollTop(el, container, offset)` → number（纯计算 `elTop - containerTop + container.scrollTop - offset`）
  - `initScrollMemory(container)`（挂节流 200ms scroll 捕获）
  - `restoreScrollMemory(container, dirPath)` → boolean
  - `clearScrollMemory(dirPath)`

- [ ] **Step 1: 写失败测试**（`scrollMemory.test.mjs`）

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { captureScrollAnchor, restoreScrollTop, rememberScroll, recallScroll, clearScrollMemory } from './scrollMemory.js';

test('captureScrollAnchor picks first card whose bottom passes container top', () => {
    const cards = [
        { path: '/a', top: -200, bottom: -50 },  // 完全滚出视口上方
        { path: '/b', top: -50, bottom: 120 },   // 部分可见 ← 锚点
        { path: '/c', top: 120, bottom: 300 },
    ];
    const anchor = captureScrollAnchor(cards, 0);
    assert.deepEqual(anchor, { anchorPath: '/b', offset: -50 });
});

test('captureScrollAnchor returns null when all cards above viewport', () => {
    assert.equal(captureScrollAnchor([{ path: '/a', top: -300, bottom: -100 }], 0), null);
});

test('restoreScrollTop computes target scrollTop', () => {
    // el 距容器内容顶 800，期望视口内位于 offset=-50：scrollTop = 800 - (-50) = 850
    assert.equal(restoreScrollTop({ offsetTop: 800 }, { scrollTop: 0 }, -50), 850);
});

test('remember/recall/clear roundtrip', () => {
    rememberScroll('/m/dir', { anchorPath: '/b', offset: -50 });
    assert.deepEqual(recallScroll('/m/dir'), { anchorPath: '/b', offset: -50 });
    clearScrollMemory('/m/dir');
    assert.equal(recallScroll('/m/dir'), null);
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server/internal/web && node --test scrollMemory.test.mjs`
Expected: FAIL

- [ ] **Step 3: 实现 scrollMemory.js**

```js
// 锚点式滚动记忆：按目录路径记住"首个可见卡片 + 视口内偏移"，重渲染后按锚点卡片复位。
import { state } from './state.js';

const memory = new Map(); // dirPath -> { anchorPath, offset }

export function captureScrollAnchor(cards, containerTop) {
    for (const c of cards) {
        if (c.bottom > containerTop) return { anchorPath: c.path, offset: Math.round(c.top - containerTop) };
    }
    return null;
}

export function restoreScrollTop(el, container, offset) {
    return el.offsetTop - offset;
}

export function rememberScroll(dirPath, anchor) {
    if (anchor) memory.set(dirPath, anchor);
}

export function recallScroll(dirPath) {
    return memory.get(dirPath) || null;
}

export function clearScrollMemory(dirPath) {
    if (dirPath) memory.delete(dirPath);
    else memory.clear();
}

let lastCapture = 0;
export function initScrollMemory(container) {
    if (!container) return;
    container.addEventListener('scroll', () => {
        const now = Date.now();
        if (now - lastCapture < 200) return;
        lastCapture = now;
        const cards = [...container.querySelectorAll('.media-card[data-path]')].map(el => {
            const r = el.getBoundingClientRect();
            return { path: el.dataset.path, top: r.top, bottom: r.bottom };
        });
        const anchor = captureScrollAnchor(cards, container.getBoundingClientRect().top);
        rememberScroll(state.currentPath, anchor);
    }, { passive: true });
}

export function restoreScrollMemory(container, dirPath) {
    const mem = recallScroll(dirPath);
    if (!mem || !container) return false;
    const el = container.querySelector(`.media-card[data-path="${CSS.escape(mem.anchorPath)}"]`);
    if (!el) return false; // 锚点消失（排序/筛选/内容变化）→ 安全放弃
    container.scrollTop = el.offsetTop - mem.offset;
    return true;
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server/internal/web && node --test scrollMemory.test.mjs`
Expected: PASS

- [ ] **Step 5: browserView 接线**

(a) import：`import { initScrollMemory, restoreScrollMemory, clearScrollMemory } from './scrollMemory.js';`
(b) `setupBrowserListeners`（:463 事件注册附近）追加：`initScrollMemory(document.querySelector('.view-container'));`
(c) `browsePath`（:143）：进入新目录时切换捕获键——`state.currentPath = path;` 之后加 `clearScrollMemory(path)` **不对**——目录首次进入无记忆即可，无需清；真正的清除时机是排序/筛选变更（Task 8 已加 `clearScrollMemory(state.currentPath)`；排序 select/order 两个 handler 内同样追加该行）。
(d) `renderBrowserList` 末尾（Task 7 追加的 decorate 调用之后）：

```js
    restoreScrollMemory(document.querySelector('.view-container'), state.currentPath);
```

- [ ] **Step 6: 全量 web 测试 + 手动冒烟**

Run: `cd server/internal/web && node --test`
手动：浏览器列表滚到中部 → 点开小说 → 返回 → 应落回原卡片附近；改排序 → 回顶部；筛选 → 回顶部。

- [ ] **Step 7: Commit**

```bash
git add server/internal/web/scrollMemory.js server/internal/web/scrollMemory.test.mjs server/internal/web/browserView.js
git commit -m "feat(library): web anchor-based scroll memory for browser list (Phase 2)"
```

---

### Task 10: textReader 进度上报 + 打开时择新

**Files:**
- Modify: `server/internal/web/textReader.js`
- Test: `server/internal/web/textReader.test.mjs`（扩展）

**Interfaces:**
- Consumes: `reportState` / `fetchState`（Task 7）、`computeReportPayload`（Task 6）
- Produces: 保存进度时同步上报 `{ percent, finished }`；打开书时 server-vs-local 择新

- [ ] **Step 1: 写失败测试**（textReader.test.mjs 追加；沿用现有 installEnv 模式，在 fetch stub 中拦截 `/library/states`）

```js
test('persistVisibleProgress reports percent and finished to server', async () => {
    installEnv(); // 现有 helper：stub fetch（在此扩展捕获 /library/states 的 POST body 列表）
    setupJsdom();
    try {
        const posted = [];
        global.fetch = async (url, opts) => {
            if (String(url).includes('/api/v1/library/states') && opts && opts.method === 'POST') {
                posted.push(JSON.parse(opts.body));
            }
            return { ok: true, status: 200, json: async () => ({}) };
        };
        // 渲染一本 3 章书，滚到第 2 章（chapterIndex=1）中段，触发 800ms 防抖……
        // （沿用现有 textReader.test.mjs 的书籍渲染与滚动触发手法，断言 posted 最后一条：）
        // assert.equal(posted.at(-1).chapter_index, 1);
        // assert.ok(posted.at(-1).percent > 0 && posted.at(-1).percent < 100);
        // assert.equal(typeof posted.at(-1).finished, 'boolean');
    } finally {
        teardownJsdom();
    }
});
```

执行者按现有测试文件的实际渲染手法补全渲染与滚动段（参照该文件中已有的 persistVisibleProgress 相关测试），断言以注释为契约。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server/internal/web && node --test textReader.test.mjs`
Expected: FAIL（无上报发生）

- [ ] **Step 3: 实现**（textReader.js）

(a) import：`import { reportState, fetchState } from './library.js'; import { computeReportPayload } from './library.js';`
(b) `persistVisibleProgress`（:508-518）保存后追加上报：

```js
    if (vis) {
        const prog = { chapterIndex: vis.chapterIndex, paraIndex: vis.paraIndex, lastReadAt: Date.now() };
        saveProgress(path, prog);
        // 上报：末章+末段可见或距底 <50px 判 finished
        const chapterParas = paragraphs.filter(p => p.chapterIndex === vis.chapterIndex);
        const lastPara = chapterParas.length > 0
            && chapterParas.every(p => p.bottom <= containerTop + els.content.clientHeight)
            && vis.paraIndex === Math.max(...chapterParas.map(p => p.paraIndex));
        const nearBottom = els.content.scrollHeight - els.content.scrollTop - els.content.clientHeight < 50;
        const payload = computeReportPayload({
            chapterIndex: vis.chapterIndex, paraIndex: vis.paraIndex,
            chapterParaCount: chapterParas.length, totalChapters: state.chapterCount,
            atChapterEnd: lastPara || nearBottom,
        });
        reportState(path, { chapterIndex: vis.chapterIndex, paraIndex: vis.paraIndex,
            percent: payload.percent, finished: payload.finished, lastReadAt: prog.lastReadAt });
    }
```

（`lastPara` 计算简化为：末段索引 == 首可见段索引 且 末段 bottom 已进入视口；实现时以最简可测表达为准。）注意 `els.content` 即滚动容器（现有代码 `collectVisibleParagraphs` 用 `els.content.getBoundingClientRect()`）。

(c) 打开时择新（:266 `const savedProgress = loadProgress(path);` 之后）：

```js
    // 服务端状态择新：URL 显式 chapter/para 时不参与
    if (chapterParam == null && paraParam == null) {
        fetchState(path).then((res) => {
            const srv = res && res.state;
            if (!srv || !srv.last_read_at) return;
            const local = loadProgress(path);
            if (!local || srv.last_read_at > (local.lastReadAt || 0)) {
                const merged = { chapterIndex: srv.chapter_index, paraIndex: srv.para_index, lastReadAt: srv.last_read_at };
                saveProgress(path, merged);
                if (merged.paraIndex > 0 || merged.chapterIndex !== state.currentIdx) {
                    scrollToParagraph(merged.paraIndex, merged.chapterIndex);
                }
            }
        }).catch(() => {});
    }
```

（`state.currentIdx` 为 reader-state 的当前章；`scrollToParagraph` 已有 1.5s 重试机制。）

- [ ] **Step 4: 跑测试确认通过 + 全量**

Run: `cd server/internal/web && node --test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/internal/web/textReader.js server/internal/web/textReader.test.mjs
git commit -m "feat(library): web reader reports progress and merges server state on open (Phase 2)"
```

---

### Task 11: app.js 本地进度迁移 + Phase 2 收口验证

**Files:**
- Modify: `server/internal/web/library.js`（`migrateLocalProgress`）
- Modify: `server/internal/web/app.js`（initApp 挂迁移）
- Test: `server/internal/web/library.test.mjs`（扩展）

**Interfaces:**
- Consumes: `runWithConcurrency`（Task 6）
- Produces: `migrateLocalProgress()`（幂等：`library_migrated_v1` 标记 + lastReadAt 服务端守卫双保险）

- [ ] **Step 1: 写失败测试**（library.test.mjs 追加）

```js
test('migrateLocalProgress uploads book_progress entries once', async () => {
    setupJsdom();
    try {
        const posted = [];
        global.fetch = async (url, opts) => {
            if (String(url).includes('/api/v1/library/states') && opts && opts.method === 'POST') {
                posted.push(JSON.parse(opts.body));
            }
            return { ok: true, status: 200, json: async () => ({}) };
        };
        localStorage.setItem('book_progress:/m/a.txt', JSON.stringify({ chapterIndex: 1, paraIndex: 2, lastReadAt: 100 }));
        localStorage.setItem('book_progress:/m/b.txt', JSON.stringify({ chapterIndex: 3, paraIndex: 0, lastReadAt: 200 }));
        const { migrateLocalProgress } = await import('./library.js');
        await migrateLocalProgress();
        assert.equal(posted.length, 2);
        assert.equal(localStorage.getItem('library_migrated_v1'), '1');
        posted.length = 0;
        await migrateLocalProgress(); // 二次调用幂等
        assert.equal(posted.length, 0);
    } finally {
        teardownJsdom();
    }
});
```

注意：`migrateLocalProgress` 内部直接用 `fetch`（绕过 apiRequest 的 state 依赖亦可，二选一，测试契约不变）。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server/internal/web && node --test library.test.mjs`
Expected: FAIL（migrateLocalProgress 未导出）

- [ ] **Step 3: 实现**（library.js 追加）

```js
export async function migrateLocalProgress() {
    try {
        if (localStorage.getItem('library_migrated_v1') === '1') return;
    } catch (e) { return; }
    const entries = [];
    for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i);
        if (!key || !key.startsWith('book_progress:')) continue;
        try {
            const prog = JSON.parse(localStorage.getItem(key));
            if (prog && typeof prog.lastReadAt === 'number') {
                entries.push({ path: key.slice('book_progress:'.length), prog });
            }
        } catch (e) { /* 跳过坏条目 */ }
    }
    await runWithConcurrency(entries.map(({ path, prog }) => async () => {
        // 服务端 lastReadAt 守卫保证陈旧/重复上报 no-op；percent 无法本地重构，传 0（下次阅读会更新）
        reportState(path, { chapterIndex: prog.chapterIndex || 0, paraIndex: prog.paraIndex || 0,
            percent: 0, finished: false, lastReadAt: prog.lastReadAt });
    }), 6);
    try { localStorage.setItem('library_migrated_v1', '1'); } catch (e) {}
}
```

（`reportState` 为 fire-and-forget；迁移的并发限制由 `runWithConcurrency` 的任务即 reportState 同步发起 fetch 实现——若需严格等待，把 reportState 改造为返回 promise 的内部版本 `reportStateAwait`。实现取后者：`reportState` 返回 promise，migrate 用 `.then` 收集。）

- [ ] **Step 4: app.js 挂载**（initApp 内 `await loadConfig();` 之后、`handleRoute(...)` 之前）

```js
    import { migrateLocalProgress } from './library.js'; // 文件顶部
    migrateLocalProgress(); // fire-and-forget，失败不影响启动
```

- [ ] **Step 5: Phase 2 收口验证**

Run: `cd server/internal/web && node --test && cd ../../tools/xsscheck && go run . ../../server/internal/web`
Expected: 全 PASS + xsscheck 零违规（新 innerHTML sink 均有 XSS-SAFE 注释）

- [ ] **Step 6: Commit**

```bash
git add server/internal/web/library.js server/internal/web/library.test.mjs server/internal/web/app.js
git commit -m "feat(library): web local progress migration with concurrency-limited upload (Phase 2)"
```

---

## Phase 3 — Android

### Task 12: ReadingStatus 模型 + ReadingMath

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/Models.kt`
- Create: `android/app/src/main/java/com/juziss/localmediahub/data/ReadingMath.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/data/ReadingMathTest.kt`

**Interfaces:**
- Produces:
  - `enum class ReadingStatus { UNREAD, READING, FINISHED }` + `ReadingStatus.fromRaw(s: String?): ReadingStatus?` + `ReadingStatus.toRaw(): String`
  - `data class LibraryDecoration(val path: String, val status: ReadingStatus, val percent: Double, @SerializedName("last_read_at") val lastReadAt: Long)`
  - `data class ReadingStateFull(...)` / `ReadingStateResponse(state)` / `DecorationBadge(status, percent, last_read_at)` / `DecorationsResponse(states, favorites)` / `ServerFavorite(path, is_dir, is_system, title, media_type, snapshot, added_at)`
  - `ReadingMath.percent(chapterIndex, blockIndex, chapterBlockCount, totalChapters): Double`、`ReadingMath.isFinished(chapterIndex, totalChapters, atChapterEnd): Boolean`

- [ ] **Step 1: 写失败测试**（ReadingMathTest.kt）

```kotlin
package com.juziss.localmediahub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingMathTest {
    @Test fun percentMidBook() {
        assertEquals(50.0, ReadingMath.percent(2, 5, 10, 5), 1e-9)
    }
    @Test fun percentZeroDivisorSafe() {
        assertEquals(0.0, ReadingMath.percent(0, 0, 0, 0), 1e-9)
    }
    @Test fun percentClampsAndRounds() {
        assertEquals(100.0, ReadingMath.percent(4, 10, 10, 5), 1e-9)
        assertEquals(33.3, ReadingMath.percent(1, 5, 15, 6), 1e-9)
    }
    @Test fun finishedOnlyAtLastChapterEnd() {
        assertTrue(ReadingMath.isFinished(4, 5, true))
        assertFalse(ReadingMath.isFinished(4, 5, false))
        assertFalse(ReadingMath.isFinished(3, 5, true))
        assertFalse(ReadingMath.isFinished(0, 0, true))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.data.ReadingMathTest"`
Expected: FAIL（unresolved reference）

- [ ] **Step 3: 实现**（Models.kt 追加 + ReadingMath.kt 新建）

```kotlin
// Models.kt 追加
enum class ReadingStatus {
    UNREAD, READING, FINISHED;
    companion object {
        fun fromRaw(s: String?): ReadingStatus? = when (s) {
            "unread" -> UNREAD; "reading" -> READING; "finished" -> FINISHED; else -> null
        }
    }
    fun toRaw(): String = name.lowercase()
}

data class LibraryDecoration(
    val path: String,
    val status: ReadingStatus,
    val percent: Double,
    @SerializedName("last_read_at") val lastReadAt: Long,
)

data class ReadingStateFull(
    @SerializedName("chapter_index") val chapterIndex: Int,
    @SerializedName("para_index") val paraIndex: Int,
    val percent: Double,
    val finished: Boolean,
    @SerializedName("manual_status") val manualStatus: String?,
    @SerializedName("last_read_at") val lastReadAt: Long,
)

data class ReadingStateResponse(val state: ReadingStateFull?)

data class DecorationBadge(
    val status: String,
    val percent: Double,
    @SerializedName("last_read_at") val lastReadAt: Long,
)

data class DecorationsResponse(
    val states: Map<String, DecorationBadge> = emptyMap(),
    val favorites: List<String> = emptyList(),
)

data class ServerFavorite(
    val path: String,
    @SerializedName("is_dir") val isDir: Boolean,
    @SerializedName("is_system") val isSystem: Boolean,
    val title: String,
    @SerializedName("media_type") val mediaType: String,
    val snapshot: FavoriteEntry?,
    @SerializedName("added_at") val addedAt: Long,
)
```

（`ServerFavorite.snapshot` 引用 FavoriteEntry——Task 14 定义；**本任务先建占位**：若编译顺序问题，把 ServerFavorite 移到 Task 14 一并提交，本任务只交付 ReadingStatus/LibraryDecoration/ReadingState*/Decoration*/DecorationsResponse。）

```kotlin
// data/ReadingMath.kt
package com.juziss.localmediahub.data

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ReadingMath {
    fun percent(chapterIndex: Int, blockIndex: Int, chapterBlockCount: Int, totalChapters: Int): Double {
        val intra = if (chapterBlockCount > 0) min(1.0, blockIndex.toDouble() / chapterBlockCount) else 0.0
        val total = max(1, totalChapters)
        val raw = ((chapterIndex + intra) / total) * 100.0
        return (min(100.0, max(0.0, raw)) * 10).roundToInt() / 10.0
    }

    fun isFinished(chapterIndex: Int, totalChapters: Int, atChapterEnd: Boolean): Boolean =
        totalChapters > 0 && chapterIndex == totalChapters - 1 && atChapterEnd
}
```

- [ ] **Step 4: 跑测试确认通过** → **Step 5: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/data/Models.kt android/app/src/main/java/com/juziss/localmediahub/data/ReadingMath.kt android/app/src/test/java/com/juziss/localmediahub/data/ReadingMathTest.kt
git commit -m "feat(library): reading status models and ReadingMath percent/finished helpers (Phase 3)"
```

---

### Task 13: MediaRepository library 端点

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt`

**Interfaces:**
- Consumes: 现有 `httpGet`/`httpPost`/`httpEmpty` helper（MediaRepository.kt:199/236/258）与 `encodePathSegments`（:931）
- Produces（全部 suspend，返回 NetworkResult，失败静默由调用方处理）:

```kotlin
suspend fun reportReadingState(path: String, chapterIndex: Int, paraIndex: Int, percent: Double, finished: Boolean, lastReadAt: Long): NetworkResult<Map<String, Any>>
suspend fun getReadingState(path: String): NetworkResult<ReadingStateResponse>
suspend fun setReadingStatus(path: String, status: String?): NetworkResult<Map<String, String>>
suspend fun fetchDecorations(paths: List<String>): NetworkResult<DecorationsResponse>
suspend fun listServerFavorites(): NetworkResult<List<ServerFavorite>>
suspend fun pushServerFavorite(rec: Map<String, Any?>): NetworkResult<Map<String, String>>
suspend fun removeServerFavorite(path: String): NetworkResult<Map<String, String>>
```

- [ ] **Step 1: 实现**（按既有 `httpPost`/`httpGet` 手法，无新增测试——薄封装，逻辑由 Task 15/18 的上游测试与冒烟覆盖）

```kotlin
suspend fun reportReadingState(path: String, chapterIndex: Int, paraIndex: Int,
                               percent: Double, finished: Boolean, lastReadAt: Long): NetworkResult<Map<String, Any>> =
    httpPost("$baseUrl/api/v1/library/states", gson.toJson(mapOf(
        "path" to path, "chapter_index" to chapterIndex, "para_index" to paraIndex,
        "percent" to percent, "finished" to finished, "last_read_at" to lastReadAt)),
        object : TypeToken<Map<String, Any>>() {}.type)

suspend fun getReadingState(path: String): NetworkResult<ReadingStateResponse> =
    httpGet("$baseUrl/api/v1/library/states?path=${URLEncoder.encode(path, "UTF-8")}",
        object : TypeToken<ReadingStateResponse>() {}.type)

suspend fun setReadingStatus(path: String, status: String?): NetworkResult<Map<String, String>> =
    httpEmpty("$baseUrl/api/v1/library/states/status", "PUT", gson.toJson(mapOf("path" to path, "status" to status)),
        object : TypeToken<Map<String, String>>() {}.type)
```

（`httpEmpty` 签名含返回体解析——按 :258 实际形态调整；PUT 若无对应 helper，用 `httpPost` 变体或直接 `httpEmpty(url, "PUT", body, type)`。`fetchDecorations` 用 `httpPost` POST `{paths:[...]}`（>500 时客户端分批循环合并为单个 DecorationsResponse）；favorites 三件套同法，DELETE 用 `httpEmpty(url, "DELETE", null, type)`，URL 带 `?path=`。）

- [ ] **Step 2: 编译验证** → **Step 3: Commit**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
```bash
git add android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt
git commit -m "feat(library): android repository endpoints for states, decorations and favorites (Phase 3)"
```

---

### Task 14: FavoriteEntry 三代兼容模型 + FavoritesStore 目录收藏

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/FavoritesStore.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/Models.kt`（补 `ServerFavorite`，若 Task 12 未含）
- Test: `android/app/src/test/java/com/juziss/localmediahub/data/FavoritesStoreTest.kt`（扩展）

**Interfaces:**
- Produces:
  - `data class FavoriteEntry(val file: MediaFile? = null, val folder: Folder? = null, val isSystemBrowse: Boolean = false, val addedAt: Long = 0L)`，派生 `val path: String`（file?.path ?: folder?.path ?: ""）、`val isDir: Boolean get() = folder != null`、`val identity: String get() = file?.relativePath ?: folder?.path ?: ""`
  - `decodeFavoriteEntryV2(gson, json): FavoriteEntry?`（三代：裸 MediaFile / {file,isSystemBrowse} / 新形态）
  - Store 新方法：`addFavoriteFolder(folder: Folder, isSystemBrowse: Boolean)`、`toggleFavoriteFolder(folder: Folder, isSystemBrowse: Boolean): Boolean`、`favoriteFolders: Flow<List<Folder>>`、`replaceAll(entries: List<FavoriteEntry>)`（同步用）、`isProgressMigrationDone(): Boolean` / `setProgressMigrationDone()`（DataStore boolean key `library_progress_synced`）
  - 既有流类型从 `FavoriteMediaEntry` 全面替换为 `FavoriteEntry`（`favoriteEntries`/`favorites`/`favoriteFiles`/`addFavorite`/`removeFavorite`/`toggleFavorite` 签名保持，仅类型换名）
  - `internal fun mergeFavoriteEntries(local: List<FavoriteEntry>, server: List<ServerFavorite>): List<FavoriteEntry>`（并集按 identity；冲突取 addedAt 大者、相等取 local；server 行 snapshot 为 null/file&folder 均 null 时跳过）

- [ ] **Step 1: 写失败测试**（FavoritesStoreTest.kt 扩展）

```kotlin
@Test fun decodeThreeGenerations() {
    val gson = Gson()
    // 第一代：裸 MediaFile
    val bare = """{"name":"a.txt","path":"/m/a.txt","relative_path":"a.txt","size":1,"modified_time":"2026-01-01","media_type":"text","extension":".txt"}"""
    val e1 = decodeFavoriteEntryV2(gson, bare)!!
    assertEquals("/m/a.txt", e1.path); assertFalse(e1.isDir); assertFalse(e1.isSystemBrowse)
    // 第二代：{file, isSystemBrowse}
    val old = """{"file":{"name":"a.txt","path":"/m/a.txt","relative_path":"a.txt","size":1,"modified_time":"2026-01-01","media_type":"text","extension":".txt"},"isSystemBrowse":true}"""
    val e2 = decodeFavoriteEntryV2(gson, old)!!
    assertTrue(e2.isSystemBrowse)
    // 第三代：folder
    val folder = """{"folder":{"name":"comics","path":"/m/comics","relative_path":"comics","is_root":false},"isSystemBrowse":false,"addedAt":123}"""
    val e3 = decodeFavoriteEntryV2(gson, folder)!!
    assertTrue(e3.isDir); assertEquals("/m/comics", e3.path); assertEquals(123L, e3.addedAt)
    assertEquals("comics 回退 identity 用 folder.path", "/m/comics", e3.identity)
}

@Test fun mergeFavoriteEntriesUnionByAddedAt() {
    val f = MediaFile("a.txt", "/m/a.txt", "a.txt", 1, "2026-01-01", "text", ".txt")
    val local = listOf(FavoriteEntry(file = f, isSystemBrowse = false, addedAt = 100))
    val folderJson = FavoriteEntry(folder = Folder("comics", "/m/comics", "comics"), addedAt = 300)
    val server = listOf(
        ServerFavorite("/m/a.txt", false, false, "a.txt", "text",
            FavoriteEntry(file = f, addedAt = 200), 200),
        ServerFavorite("/m/comics", true, false, "comics", "folder", folderJson, 300),
        ServerFavorite("/m/foreign", false, false, "x", "text", null, 50), // Web 来源快照 → 跳过
    )
    val merged = mergeFavoriteEntries(local, server)
    assertEquals(2, merged.size)
    val byId = merged.associateBy { it.identity }
    assertEquals(200L, byId["a.txt"]!!.addedAt)     // server 较新胜出
    assertNotNull(byId["/m/comics"])                // 目录收藏并入
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.data.FavoritesStoreTest"`
Expected: FAIL

- [ ] **Step 3: 实现**（FavoritesStore.kt 重构——保留 DataStore key 与 Gson 机制，换模型）

```kotlin
data class FavoriteEntry(
    val file: MediaFile? = null,
    val folder: Folder? = null,
    val isSystemBrowse: Boolean = false,
    val addedAt: Long = 0L,
) {
    val path: String get() = file?.path ?: folder?.path ?: ""
    val isDir: Boolean get() = folder != null
    val identity: String get() = file?.relativePath ?: folder?.path ?: ""
}

internal fun decodeFavoriteEntryV2(gson: Gson, json: String): FavoriteEntry? = try {
    val obj = gson.fromJson(json, JsonObject::class.java) ?: return null
    when {
        obj.has("folder") || obj.has("file") || obj.has("addedAt") ->
            gson.fromJson(json, FavoriteEntry::class.java)?.takeIf { it.file != null || it.folder != null }
        else -> FavoriteEntry(file = gson.fromJson(json, MediaFile::class.java)) // 第一代裸 MediaFile
    }
} catch (e: Exception) { null }

internal fun mergeFavoriteEntries(local: List<FavoriteEntry>, server: List<ServerFavorite>): List<FavoriteEntry> {
    val byId = LinkedHashMap<String, FavoriteEntry>()
    local.forEach { byId[it.identity] = it }
    for (rec in server) {
        val snap = rec.snapshot ?: continue
        if (snap.file == null && snap.folder == null) continue // 异端（Web）来源，Android 无法渲染
        val entry = snap.copy(
            isSystemBrowse = rec.isSystem || snap.isSystemBrowse,
            addedAt = rec.addedAt,
        )
        val existing = byId[entry.identity]
        if (existing == null || entry.addedAt > existing.addedAt) byId[entry.identity] = entry
    }
    return byId.values.toList()
}
```

Store 侧：`addFavorite` 追加 `addedAt = System.currentTimeMillis()`；`addFavoriteFolder`/`toggleFavoriteFolder` 以 `folder.path` 为 identity 去重；`replaceAll` 事务性重写 StringSet；进度迁移 flag 用 `booleanPreferencesKey("library_progress_synced")`。`favoriteEntriesToPaths` 改用 `identity`，`favoriteEntriesToFiles` 过滤 `file != null`，新增 `favoriteEntriesToFolders`。

- [ ] **Step 4: 修复编译面**（全仓 `FavoriteMediaEntry` 引用改 `FavoriteEntry`：`FavoritesController`/`BrowseScreen`/`MediaItems`/`HomeViewModel` 等按编译器指引）→ **Step 5: 跑测试** → **Step 6: Commit**

```bash
git add android/app/src
git commit -m "feat(library): FavoriteEntry with folder support and 3-generation Gson compat (Phase 3)"
```

---

### Task 15: LibrarySyncManager 双向同步

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/data/LibrarySyncManager.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/HomeViewModel.kt`（init 触发）
- Test: `android/app/src/test/java/com/juziss/localmediahub/data/LibrarySyncManagerTest.kt`

**Interfaces:**
- Consumes: Task 13 repository 方法、Task 14 store 方法、`RecentActivityStore.getAllBookProgressFlow()`（RecentActivityStore.kt:205）
- Produces: `@Singleton class LibrarySyncManager @Inject constructor(favoritesStore, recentActivityStore, repository, @ApplicationScope scope)`，唯一公共方法 `fun ensureStarted()`（每进程一次；内部 launch `syncOnce()`）

- [ ] **Step 1: 写失败测试**（merge 已在 Task 14 覆盖；本任务测 store 落库与幂等 flag，用 Robolectric + 真实 store + 无网络 repository（NetworkResult.Error 路径））

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibrarySyncManagerTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var favoritesStore: FavoritesStore
    private lateinit var recentActivityStore: RecentActivityStore

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        favoritesStore = FavoritesStore(context, CoroutineScope(Dispatchers.Unconfined))
        recentActivityStore = RecentActivityStore(context)
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun ensureStartedIsIdempotentPerProcess() = runTest(dispatcher) {
        val repo = MediaRepository(OkHttpClient(), ServerConfig(), TestBleFixtures.disabledBleController(), BleTransportFallback())
        val mgr = LibrarySyncManager(favoritesStore, recentActivityStore, repo, CoroutineScope(dispatcher))
        mgr.ensureStarted()
        mgr.ensureStarted() // 二次调用不重复（AtomicBoolean）
        advanceUntilIdle()
        assertFalse(favoritesStore.isProgressMigrationDone()) // 无网络：迁移未完成、不崩溃
    }
}
```

（`TestBleFixtures.disabledBleController()` 的实际包路径按 HomeViewModelTest 现有 import 抄。）

- [ ] **Step 2: 跑测试确认失败** → **Step 3: 实现**

```kotlin
@Singleton
class LibrarySyncManager @Inject constructor(
    private val favoritesStore: FavoritesStore,
    private val recentActivityStore: RecentActivityStore,
    private val repository: MediaRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { runCatching { syncOnce() } }
    }

    private suspend fun syncOnce() {
        // 1. 进度一次性迁移上报（服务端 lastReadAt 守卫幂等）
        if (!favoritesStore.isProgressMigrationDone()) {
            val all = recentActivityStore.getAllBookProgressFlow().first()
            all.forEach { p ->
                repository.reportReadingState(p.path, p.chapterIndex, p.blockIndex,
                    percent = 0.0, finished = false, lastReadAt = p.lastReadAt)
            }
            if (all.isEmpty() || all.all { reportOk(p = it) /* 见下 */ }) favoritesStore.setProgressMigrationDone()
        }
        // 2. 收藏：全量推送（幂等）+ 全量拉回合并
        val local = favoritesStore.favoriteEntries.first()
        local.forEach { entry ->
            repository.pushServerFavorite(buildFavoriteBody(entry))
        }
        val remote = repository.listServerFavorites()
        if (remote is NetworkResult.Success) {
            val merged = mergeFavoriteEntries(local, remote.data)
            favoritesStore.replaceAll(merged)
        }
    }

    private fun buildFavoriteBody(entry: FavoriteEntry): Map<String, Any?> = mapOf(
        "path" to entry.path, "is_dir" to entry.isDir, "is_system" to entry.isSystemBrowse,
        "title" to (entry.file?.name ?: entry.folder?.name ?: ""),
        "media_type" to (if (entry.isDir) "folder" else entry.file?.mediaType.orEmpty()),
        "snapshot" to entry, "added_at" to entry.addedAt,
    )
}
```

（迁移 flag 语义：**任一上报成功路径即置位**最简——实现取 `all.isEmpty() || all.any { reportReadingState(...) is NetworkResult.Success }`，逐条判断；简化后测试断言同步调整。）

- [ ] **Step 4: HomeViewModel 接线**（init 的 serverUrl collect 内，`refresh()` 旁）

```kotlin
// 注入 LibrarySyncManager（构造参数追加），init 中：
serverConfigStore.serverUrl.collect { url ->
    if (url.isBlank()) return@collect
    ensureClientInitialized(url)
    librarySyncManager.ensureStarted()
    refresh()
}
```

- [ ] **Step 5: 跑测试** → **Step 6: Commit**

```bash
git add android/app/src
git commit -m "feat(library): bidirectional favorites and progress sync on connect (Phase 3)"
```

---

### Task 16: LibraryController + 徽章 + 目录心形 + 筛选（数据/UI 层）

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/LibraryController.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseSharedState.kt`（`libraryStates` + `statusFilter`）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt`（delegate + 暴露）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/MediaItems.kt`（TextCard 徽章 / FolderCard 心形）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/BrowseContent.kt`（参数透传）
- Modify: `android/app/src/main/res/values/strings.xml`
- Test: `android/app/src/test/java/com/juziss/localmediahub/viewmodel/BrowseLibraryFiltersTest.kt`

**Interfaces:**
- Produces:
  - `BrowseSharedState.libraryStates: MutableStateFlow<Map<String, LibraryDecoration>>`、`statusFilter: MutableStateFlow<ReadingStatus?>`
  - `internal class LibraryController(repository, sharedState)`：`startCollecting(scope)`（combine rawFolders+rawFiles，debounce 300ms → fetchDecorations → libraryStates）、`suspend fun setStatus(path: String, status: ReadingStatus?)`、`fun setStatusFilter(s: ReadingStatus?)`
  - 纯函数（ui/component/browse/`BrowseListFilters.kt`）：`applyBrowseFilters(folders, files, favorites: Set<String>, favoritesOnly: Boolean, statusFilter: ReadingStatus?, states: Map<String, LibraryDecoration>): Pair<List<Folder>, List<MediaFile>>`
  - `TextCard(..., readingStatus: ReadingStatus? = null, percent: Double = 0.0)`、`FolderCard(..., isFavorite: Boolean = false, onToggleFavorite: (() -> Unit)? = null)`

- [ ] **Step 1: 写失败测试**（BrowseLibraryFiltersTest.kt——applyBrowseFilters 语义与 Web applyListFilters 对齐）

```kotlin
package com.juziss.localmediahub.viewmodel

import com.juziss.localmediahub.data.*
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseLibraryFiltersTest {
    private val folders = listOf(Folder("comics", "/m/comics", "comics"), Folder("other", "/m/other", "other"))
    private val files = listOf(
        MediaFile("a.txt", "/m/a.txt", "a.txt", 1, "", "text", ".txt"),
        MediaFile("b.txt", "/m/b.txt", "b.txt", 1, "", "text", ".txt"),
        MediaFile("c.txt", "/m/c.txt", "c.txt", 1, "", "text", ".txt"),
        MediaFile("d.txt", "/m/d.txt", "d.txt", 1, "", "text", ".txt"),
        MediaFile("v.mp4", "/m/v.mp4", "v.mp4", 1, "", "video", ".mp4"),
    )
    private val favorites = setOf("b.txt", "/m/comics") // 文件 identity=relativePath，目录 identity=path
    private val states = mapOf(
        "/m/a.txt" to LibraryDecoration("/m/a.txt", ReadingStatus.READING, 42.5, 1),
        "/m/b.txt" to LibraryDecoration("/m/b.txt", ReadingStatus.FINISHED, 100.0, 2),
        "/m/c.txt" to LibraryDecoration("/m/c.txt", ReadingStatus.UNREAD, 0.0, 3),
    )

    @Test fun favoritesOnlyMatchesCurrentListing() {
        val (f, l) = applyBrowseFilters(folders, files, favorites, true, null, states)
        assertEquals(listOf("/m/comics"), f.map { it.path })
        assertEquals(listOf("/m/b.txt"), l.map { it.path })
    }
    @Test fun statusFilterHidesFoldersAndNonText() {
        val (f, l) = applyBrowseFilters(folders, files, emptySet(), false, ReadingStatus.READING, states)
        assertEquals(0, f.size)
        assertEquals(listOf("/m/a.txt"), l.map { it.path })
    }
    @Test fun unreadIncludesMissingState() {
        val (f, l) = applyBrowseFilters(folders, files, emptySet(), false, ReadingStatus.UNREAD, states)
        assertEquals(listOf("c.txt", "d.txt").sorted(), l.map { it.relativePath }.sorted())
    }
    @Test fun combinedIntersect() {
        val (f, l) = applyBrowseFilters(folders, files, favorites, true, ReadingStatus.FINISHED, states)
        assertEquals(0, f.size)
        assertEquals(listOf("/m/b.txt"), l.map { it.path })
    }
}
```

- [ ] **Step 2: 跑测试确认失败** → **Step 3: 实现纯函数 + Controller + SharedState + VM**

`applyBrowseFilters`（放 `viewmodel/BrowseListFilters.kt`，internal 顶层函数）：

```kotlin
internal fun applyBrowseFilters(
    folders: List<Folder>, files: List<MediaFile>,
    favorites: Set<String>, favoritesOnly: Boolean,
    statusFilter: ReadingStatus?, states: Map<String, LibraryDecoration>,
): Pair<List<Folder>, List<MediaFile>> {
    var outFolders = folders
    var outFiles = files
    if (statusFilter != null) {
        outFolders = emptyList()
        outFiles = outFiles.filter { f ->
            f.mediaType == "text" && (states[f.path]?.status ?: ReadingStatus.UNREAD) == statusFilter
        }
    }
    if (favoritesOnly) {
        outFolders = outFolders.filter { it.path in favorites }
        outFiles = outFiles.filter { it.relativePath in favorites }
    }
    return outFolders to outFiles
}
```

`LibraryController`（debounce 300ms；文本文件 + 目录都请求——收藏心形需要目录 favorites 由本地 store 提供，不依赖 decorations；decorations 只为状态徽章，故仅 text 文件路径即可，但顺带查目录状态无害——**实现只发 text 文件路径 + 目录路径**）：

```kotlin
internal class LibraryController(
    private val repository: MediaRepository,
    private val sharedState: BrowseSharedState,
) {
    fun startCollecting(scope: CoroutineScope) {
        scope.launch {
            combine(sharedState.rawFolders, sharedState.rawFiles) { f, l -> f to l }
                .debounce(300)
                .collect { (folders, files) -> loadDecorations(folders, files) }
        }
    }

    suspend fun loadDecorations(folders: List<Folder>, files: List<MediaFile>) {
        val paths = folders.map { it.path } + files.filter { it.mediaType == "text" }.map { it.path }
        if (paths.isEmpty()) { sharedState.libraryStates.value = emptyMap(); return }
        when (val r = repository.fetchDecorations(paths)) {
            is NetworkResult.Success -> sharedState.libraryStates.value = r.data.states.entries
                .mapNotNull { (p, b) -> ReadingStatus.fromRaw(b.status)?.let { p to LibraryDecoration(p, it, b.percent, b.lastReadAt) } }
                .toMap()
            else -> {} // 静默降级：无徽章
        }
    }

    suspend fun setStatus(path: String, status: ReadingStatus?) {
        repository.setReadingStatus(path, status?.toRaw())
        sharedState.rawFolders.value.toList() to sharedState.rawFiles.value.toList()
        loadDecorations(sharedState.rawFolders.value, sharedState.rawFiles.value) // 刷新徽章
    }

    fun setStatusFilter(s: ReadingStatus?) { sharedState.statusFilter.value = s }
}
```

（`debounce` 需要 `@OptIn(FlowPreview::class)`。）`BrowseViewModel`：构造函数追加 `libraryController`（或内部自建——按既有 delegate 风格在 VM 内直接 `LibraryController(repository, sharedState)` 实例化，`init` 里 `startCollecting(viewModelScope)`），暴露 `fun setStatus(path, status)`、`val libraryStates`、`fun setStatusFilter(...)`、`fun decorationFor(file: MediaFile): LibraryDecoration?`。

- [ ] **Step 4: UI——TextCard 徽章 / FolderCard 心形 / strings**

TextCard（MediaItems.kt:373）追加参数与角标（`card-details` 顶部 `Row` 内 `Surface` 胶囊）：

```kotlin
if (readingStatus == ReadingStatus.FINISHED) {
    StatusBadge(text = "✓ 已读完", containerColor = MaterialTheme.colorScheme.tertiaryContainer)
} else if (readingStatus == ReadingStatus.READING) {
    StatusBadge(text = if (percent > 0) "读到 ${percent.roundToInt()}%" else "读过",
        containerColor = MaterialTheme.colorScheme.secondaryContainer)
}

@Composable private fun StatusBadge(text: String, containerColor: Color) {
    Surface(color = containerColor, shape = RoundedCornerShape(999.dp)) {
        Text(text = text, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}
```

FolderCard（MediaItems.kt:60）追加 `isFavorite: Boolean = false, onToggleFavorite: (() -> Unit)? = null`；`Box` 包裹内容，右上角 `FavoriteToggleIcon(isFavorite, onToggleFavorite ?: {}, Modifier.align(Alignment.TopEnd))`。

BrowseContent（:362-405）：`items(folders...)` 里 `FolderCard(..., isFavorite = isFavorite(folder.path), onToggleFavorite = { onFolderToggleFavorite(folder) })`；`"text"` 分支 `TextCard(..., readingStatus = decorationFor(file)?.status, percent = decorationFor(file)?.percent ?: 0.0)`——`BrowseContent` 新增参数 `decorationFor: (MediaFile) -> LibraryDecoration?`、`onFolderToggleFavorite: (Folder) -> Unit`（BrowseScreen 传入）。

strings.xml 追加：

```xml
<string name="reading_badge_finished">已读完</string>
<string name="reading_badge_reading">读到 %1$d%%</string>
<string name="reading_badge_reading_plain">读过</string>
<string name="browse_filter_status">阅读状态</string>
<string name="browse_status_all">全部</string>
<string name="browse_status_unread">未读</string>
<string name="browse_status_reading">读过</string>
<string name="browse_status_finished">已读完</string>
<string name="browse_action_mark_finished">标为已读完</string>
<string name="browse_action_mark_reading">标为读过</string>
<string name="browse_action_mark_unread">标为未读</string>
<string name="browse_action_clear_manual">清除手动标记</string>
```

- [ ] **Step 5: 跑测试 + 编译** → **Step 6: Commit**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.viewmodel.BrowseLibraryFiltersTest" :app:compileDebugKotlin`
```bash
git add android/app/src
git commit -m "feat(library): android browse badges, folder hearts and LibraryController (Phase 3)"
```

---

### Task 17: 筛选 chips + QuickActions 状态标记 + 收藏视图改当前目录过滤

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseFilterChipsRow.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt`（chips 挂载 + 过滤接线 + 删除 BrowseFavoritesView 分支）
- Delete: `android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseFavoritesView.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/QuickActionsDialog.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseTopBar.kt`（收藏图标条件放宽）

**Interfaces:**
- Consumes: `applyBrowseFilters` / `LibraryController.setStatus`（Task 16）、`FavoritesController` 流、`QuickActionsDialog`（item: Any 模式）
- Produces: `BrowseFilterChipsRow(statusFilter: ReadingStatus?, onSelect: (ReadingStatus?) -> Unit)`；`QuickActionsDialog(..., onMarkStatus: ((MediaFile, ReadingStatus?) -> Unit)? = null)`（仅 text 文件显示四个状态按钮）

- [ ] **Step 1: BrowseFilterChipsRow**

```kotlin
@Composable
internal fun BrowseFilterChipsRow(statusFilter: ReadingStatus?, onSelect: (ReadingStatus?) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val options = listOf(
            null to stringResource(R.string.browse_status_all),
            ReadingStatus.UNREAD to stringResource(R.string.browse_status_unread),
            ReadingStatus.READING to stringResource(R.string.browse_status_reading),
            ReadingStatus.FINISHED to stringResource(R.string.browse_status_finished),
        )
        options.forEach { (value, label) ->
            FilterChip(selected = statusFilter == value, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}
```

- [ ] **Step 2: BrowseScreen 接线**

(a) Scaffold 内 topBar 与 content 之间挂：

```kotlin
if (!isSearchMode && !isCollectionView) {
    BrowseFilterChipsRow(statusFilter = statusFilter, onSelect = { viewModel.setStatusFilter(it); viewModel.saveScrollPosition(currentPath, 0) })
}
```

（筛选变更清滚动位置——`saveScrollPosition(path, 0)` 因 `index > 0` 守卫实际不写入旧值，等效清除；确认 `BrowseNavigator` 无显式 clear API 时用此法并在代码注释说明。）

(b) `else -> BrowseStateContent(...)` 分支（:518-550）的 folders/files 输入改为过滤后列表：

```kotlin
val favorites by viewModel.favorites.collectAsState()
val libraryStates by viewModel.libraryStates.collectAsState()
val (filteredFolders, filteredFiles) = applyBrowseFilters(
    folders, files, favorites, showFavoritesOnly, statusFilter, libraryStates)
// BrowseStateContent(folders = filteredFolders, files = filteredFiles, ...)
```

（`folders`/`files` 取既有排序后集合的变量名——执行时核对 BrowseScreen 实际变量。）

(c) 删除 `showFavoritesOnly -> BrowseFavoritesView(...)` 分支（:501-517）与该文件；`showFavoritesOnly` 回退逻辑（:156/:231）保留（退开关）；`onFavoriteVideoClick` 等仅供该视图的回调若他处未用则一并清理。
(d) `BrowseTopBar` 收藏图标显示条件（BrowseScreen:236 `showLibraryActions = currentPath.isEmpty() && ...`）改为 `!showFavoritesOnly && !isCollectionView`，使深层目录也能开关收藏过滤。

- [ ] **Step 3: QuickActionsDialog 状态标记**

```kotlin
// 签名追加
onMarkStatus: ((MediaFile, ReadingStatus?) -> Unit)? = null,

// item is MediaFile && item.mediaType == "text" 分支，Edit tags 按钮之前：
if (onMarkStatus != null) {
    TextButton(onClick = { onMarkStatus(item, ReadingStatus.FINISHED); onDismiss() }) {
        Text(stringResource(R.string.browse_action_mark_finished))
    }
    TextButton(onClick = { onMarkStatus(item, ReadingStatus.READING); onDismiss() }) {
        Text(stringResource(R.string.browse_action_mark_reading))
    }
    TextButton(onClick = { onMarkStatus(item, ReadingStatus.UNREAD); onDismiss() }) {
        Text(stringResource(R.string.browse_action_mark_unread))
    }
}
```

BrowseScreen 的 `QuickActionsDialog(...)` 调用处（:398-427）传 `onMarkStatus = { file, status -> scope.launch { viewModel.setStatus(file.path, status) } }`（仅 text 传入，非 text 传 null）。

- [ ] **Step 4: 编译 + 全量单测** → **Step 5: Commit**

Run: `cd android && ./gradlew :app:compileDebugKotlin testDebugUnitTest`
```bash
git add android/app/src
git commit -m "feat(library): android status filter chips, quick-action marking and per-directory favorites filter (Phase 3)"
```

---

### Task 18: TextReaderViewModel 写透上报 + 打开择新

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/TextReaderViewModel.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/viewmodel/TextReaderViewModelReaderTest.kt`（扩展）

**Interfaces:**
- Consumes: `repo.reportReadingState` / `repo.getReadingState`（Task 13）、`ReadingMath`（Task 12）
- Produces: `persistScrollProgress(chapterIndex, blockIndex, scrollOffsetPx, chapterBlockCount: Int = 0, atChapterEnd: Boolean = false)`（新增两个默认参数，既有调用不破坏）

- [ ] **Step 1: 写失败测试**（TextReaderViewModelReaderTest 扩展——现有测试基建；断言两条：percent 公式进入上报参数、server 较新进度覆盖本地 pendingResume。按该文件现有 fake/stub 手法，repo 为真实 MediaRepository 无网络 → 上报走 Error 路径静默；择新逻辑通过注入返回 Success 的 fake 不可行（repo 具体类）——**改为把择新决策提为纯函数 `pickEffectiveProgress(local: BookProgress?, serverLastReadAt: Long?, serverChapter: Int, serverPara: Int, path: String): BookProgress?` 放 VM 伴生对象并单测**）

```kotlin
@Test fun pickEffectiveProgressPrefersNewerServer() {
    val local = BookProgress("/b", 1, 2, 0, 1000)
    val picked = TextReaderViewModel.pickEffectiveProgress(local, 2000L, 5, 3, "/b")
    assertEquals(5, picked!!.chapterIndex)
    assertEquals(3, picked.blockIndex)
    assertEquals(2000L, picked.lastReadAt)
    // 本地更新 → 保持本地
    assertSame(local, TextReaderViewModel.pickEffectiveProgress(local, 500L, 5, 3, "/b"))
    // 双空
    assertNull(TextReaderViewModel.pickEffectiveProgress(null, null, 0, 0, "/b"))
}
```

- [ ] **Step 2: 跑测试确认失败** → **Step 3: 实现**

(a) VM 伴生对象：

```kotlin
companion object {
    fun pickEffectiveProgress(local: BookProgress?, serverLastReadAt: Long?,
                              serverChapter: Int, serverPara: Int, path: String): BookProgress? {
        if (serverLastReadAt == null) return local
        val server = BookProgress(path, serverChapter, serverPara, 0, serverLastReadAt)
        if (local == null || serverLastReadAt > local.lastReadAt) return server
        return local
    }
}
```

(b) `processBookLoaded`（:208 `val saved = store.getBookProgress(b.path)` 之后）：

```kotlin
        val srvState = (repo.getReadingState(b.path) as? NetworkResult.Success)?.data?.state
        val effective = pickEffectiveProgress(saved, srvState?.lastReadAt,
            srvState?.chapterIndex ?: 0, srvState?.paraIndex ?: 0, b.path)
        if (effective != null && effective !== saved) store.saveBookProgress(effective)
        // 后续 idx/pendingResume 逻辑从 effective 出发（替换原 saved 引用）
```

(c) `persistScrollProgress`（:493-506）签名扩展 + 写透（本地上保存照旧；上报条件：chapter/block 变化或 finished 置位或 percent 增量 ≥1）：

```kotlin
    fun persistScrollProgress(chapterIndex: Int, blockIndex: Int, scrollOffsetPx: Int,
                              chapterBlockCount: Int = 0, atChapterEnd: Boolean = false) {
        viewModelScope.launch {
            val total = book?.chapters?.size ?: 1
            val percent = ReadingMath.percent(chapterIndex, blockIndex, chapterBlockCount, total)
            val finished = ReadingMath.isFinished(chapterIndex, total, atChapterEnd)
            val now = System.currentTimeMillis()
            store.saveBookProgress(BookProgress(book?.path ?: return@launch, chapterIndex, blockIndex, scrollOffsetPx, now))
            val key = "$chapterIndex:$blockIndex:$finished"
            if (key != lastPushKey) {
                lastPushKey = key
                repo.reportReadingState(book!!.path, chapterIndex, blockIndex, percent, finished, now)
            }
        }
    }
    private var lastPushKey = ""
```

(d) TextReaderScreen 防抖 collect（:326-346）两个分支补参：

```kotlin
            if (isScrollMode) {
                val (chIdx, blockIdx) = ReaderListLayout.scrollChapterBlock(viewModel.scrollChapters.value, itemIdx)
                if (chIdx >= 0) {
                    val cnt = viewModel.scrollChapters.value[chIdx].blocks.size
                    val atEnd = blockIdx >= cnt - 1
                    viewModel.persistScrollProgress(chIdx, blockIdx, offset, cnt, atEnd)
                }
            } else {
                val lastBlock = (blocks.size - 1).coerceAtLeast(0)
                val blockIdx = (itemIdx - ReaderListLayout.CHAPTER_MODE_HEADER_ITEMS).coerceIn(0, lastBlock)
                val atEnd = itemIdx >= blocks.size + 1 // ❖ 末尾项可见
                viewModel.persistScrollProgress(idx, blockIdx, offset, blocks.size, atEnd)
            }
```

- [ ] **Step 4: 跑测试 + 编译** → **Step 5: Commit**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.viewmodel.TextReaderViewModelReaderTest" :app:compileDebugKotlin`
```bash
git add android/app/src
git commit -m "feat(library): android reader write-through reporting and server-progress resume (Phase 3)"
```

---

### Task 19: BrowseContent 滚动修复（imagePreview 往返）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/BrowseContent.kt:256-257`

**Interfaces:**
- Consumes: `getScrollPosition(currentPath): Int`（现有参数）

- [ ] **Step 1: 实现**（双保险：initial index 绑定 + 既有 restore effect 保留）

```kotlin
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = getScrollPosition(currentPath).coerceAtLeast(0))
    val staggeredState = rememberLazyStaggeredGridState(
        initialFirstVisibleItemIndex = getScrollPosition(currentPath).coerceAtLeast(0))
```

`currentPath` 在首次组合时取自 `state.currentPath`（参数已有）；NavHost 往返重建时 `rememberLazyGridState` 内部 `rememberSaveable` 优先恢复保存值（与 initial 参数不冲突——恢复值胜出），全新进程/首进目录用 initial（无记忆时为 0）。既有 `LaunchedEffect(restorePath)` 恢复链路（:286-298）原样保留，覆盖目录级返回。

- [ ] **Step 2: 模拟器验证**（用 android-emulator 技能）

1. `android_build_and_run` 装起 app → 连接 server（本机 `10.x.x.x:8000`）
2. 进入浏览页滚到第 5 行 → 点开一张图片 → 系统返回 → 断言列表仍停在第 5 行附近
3. 点开小说（TextReaderActivity）→ 返回 → 断言位置保持
4. 目录间前进/返回 → 断言既有恢复逻辑无回归

Run: 模拟器手动/自动化冒烟（截图对比滚动位置）

- [ ] **Step 3: 全量测试** → **Step 4: Commit**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
```bash
git add android/app/src
git commit -m "fix(library): restore browse grid scroll position after imagePreview round-trip (Phase 3)"
```

---

### Task 20: 文档更新 + 端到端手工冒烟

**Files:**
- Modify: `AGENTS.md`（模块地图：`library.go` service / web `library.js`+`scrollMemory.js` / android `LibraryController`+`LibrarySyncManager`+`ReadingMath`+FavoriteEntry；安全约定：library 路由组鉴权）
- Modify: `docs/INDEX.md`（Phase 1 已加 API 表；补关键文件指针与 spec/plan 链接）

- [ ] **Step 1: 文档更新**（按上述清单；遵循两文件现有格式）
- [ ] **Step 2: 端到端冒烟清单**（spec「验证」节四条：状态跨端可见 / 两端滚动恢复 / 收藏跨端同步+目录点入 / 双筛选语义）
- [ ] **Step 3: Commit**

```bash
git add AGENTS.md docs/INDEX.md
git commit -m "docs(library): module map and index updates for reading status, favorites and scroll restore"
```

---

## 任务依赖图

```
Task 1 → Task 2 → Task 3 → Task 4 → Task 5（Phase 1 完成后 Phase 2/3 可并行）
Phase 2: Task 6 → Task 7 → Task 8（依赖 7 的 clearScrollMemory 引用）；Task 9 依赖 8；Task 10 依赖 7；Task 11 依赖 10
Phase 3: Task 12 → Task 13 → Task 14 → Task 15；Task 16 → Task 17；Task 18 依赖 13；Task 19 独立；Task 20 收尾
```

## Self-Review 结论

- **Spec 覆盖**：G1→Task 1-5；G2→Task 7/16；G3→Task 1/2（服务端判定）+ Task 10/18（客户端判定）；G4→Task 9/19；G5→Task 3/5/7/14/15/16；G6→Task 8/17；迁移→Task 11/15；路径校验分层→Task 5；key 保真→Task 4/5。无遗漏。
- **类型一致性**：`applyListFilters`（web）与 `applyBrowseFilters`（android）语义对齐已核对；`FavoriteEntry` 在 Task 14 定义、Task 12 的 `ServerFavorite.snapshot` 引用已加注编译顺序说明；`persistScrollProgress` 新参数带默认值不破坏既有调用。
- **已知取舍**：迁移时本地无法重构 percent 传 0（下次阅读自愈）；Android 上报失败静默最终一致；Web 筛选首次渲染短暂显示全量（decorations 到达后重排）。
