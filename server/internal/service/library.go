package service

import (
	"database/sql"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"time"

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
	if s == nil {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.db == nil {
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
	st, err := s.getStateLocked(u.Path)
	if err != nil {
		return models.ReadingState{}, err
	}
	if st == nil {
		return models.ReadingState{}, fmt.Errorf("failed to retrieve state after upsert: %s", u.Path)
	}
	return *st, nil
}

func (s *LibraryService) GetState(path string) (*models.ReadingState, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.getStateLocked(path)
}

func (s *LibraryService) getStateLocked(path string) (*models.ReadingState, error) {
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
