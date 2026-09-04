package service

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"runtime"
	"strings"
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

// SetManualStatus 手动覆盖：unread 重置 finished/percent；finished 置 finished/100；reading/nil 保持进度；status 为 nil 且行不存在时返回 zero ReadingState（不插入空行）。
func (s *LibraryService) SetManualStatus(path string, status *string) (models.ReadingState, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if status == nil {
		st, err := s.getStateLocked(path)
		if err != nil {
			return models.ReadingState{}, err
		}
		if st == nil {
			return models.ReadingState{Path: path}, nil
		}
		now := nowMillis()
		_, err = s.db.Exec(`UPDATE reading_states SET manual_status = NULL, updated_at = ? WHERE path = ?`, now, path)
		if err != nil {
			return models.ReadingState{}, err
		}
		refreshed, err := s.getStateLocked(path)
		if err != nil {
			return models.ReadingState{}, err
		}
		if refreshed == nil {
			return models.ReadingState{Path: path}, nil
		}
		return *refreshed, nil
	}

	finInit, pctInit := 0, 0.0
	if *status == "finished" {
		finInit, pctInit = 1, 100.0
	}
	now := nowMillis()
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
		path, pctInit, finInit, status, now)
	if err != nil {
		return models.ReadingState{}, err
	}
	st, err := s.getStateLocked(path)
	if err != nil {
		return models.ReadingState{}, err
	}
	if st == nil {
		return models.ReadingState{}, fmt.Errorf("failed to retrieve state after setting manual status: %s", path)
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
			is_dir = excluded.is_dir,
			is_system = excluded.is_system,
			title = excluded.title,
			media_type = excluded.media_type,
			snapshot = CASE
				WHEN excluded.snapshot != favorites.snapshot AND excluded.added_at > favorites.added_at THEN excluded.snapshot
				ELSE favorites.snapshot
			END,
			added_at = CASE
				WHEN excluded.snapshot != favorites.snapshot AND excluded.added_at > favorites.added_at THEN excluded.added_at
				ELSE favorites.added_at
			END`,
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
	if len(paths) == 0 {
		return make([]string, 0), nil
	}
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
			found[strings.ToLower(p)] = true
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return nil, err
		}
	}
	out := make([]string, 0)
	for _, p := range paths {
		if found[strings.ToLower(p)] {
			out = append(out, p)
		}
	}
	return out, nil
}

