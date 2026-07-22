package service

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/google/uuid"
	_ "modernc.org/sqlite"

	"github.com/localmediahub/server/internal/models"
)

type TagsService struct {
	mu sync.RWMutex
	db *sql.DB
}

func NewTagsService(dataDir string) (*TagsService, error) {
	if err := os.MkdirAll(dataDir, 0755); err != nil {
		return nil, err
	}

	dbPath := filepath.Join(dataDir, "tags.db")
	jsonPath := filepath.Join(dataDir, "tags.json")

	// Check if JSON exists and DB does not exist to run migration
	jsonExists := false
	if _, err := os.Stat(jsonPath); err == nil {
		jsonExists = true
	}
	dbExists := false
	if _, err := os.Stat(dbPath); err == nil {
		dbExists = true
	}

	dsn := dbPath + "?_pragma=foreign_keys(1)&_pragma=journal_mode(WAL)&_pragma=synchronous(NORMAL)&_pragma=busy_timeout(5000)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}

	// Optimize SQLite performance and connection behavior
	db.SetMaxOpenConns(1)

	// A1.1: PRAGMA 优化（WAL + NORMAL + mmap + cache + foreign_keys）。
	// 单个 PRAGMA 失败仅 log.Warn 不阻断启动，用默认配置降级运行。
	pragmas := []string{
		"PRAGMA journal_mode=WAL",
		"PRAGMA synchronous=NORMAL",
		"PRAGMA mmap_size=268435456",   // 256MB
		"PRAGMA temp_store=MEMORY",
		"PRAGMA cache_size=-65536",     // 64MB page cache (KB 单位，负数表示 KB)
		"PRAGMA foreign_keys=ON",
		"PRAGMA busy_timeout=5000",
	}
	for _, p := range pragmas {
		if _, err := db.Exec(p); err != nil {
			slog.Warn("tags sqlite pragma failed", "pragma", p, "error", err)
		}
	}

	// Create tables
	_, err = db.Exec(`
		CREATE TABLE IF NOT EXISTS tags (
			id TEXT PRIMARY KEY,
			name TEXT UNIQUE,
			color TEXT
		);
		CREATE TABLE IF NOT EXISTS associations (
			tag_id TEXT,
			file_path TEXT,
			PRIMARY KEY (tag_id, file_path),
			FOREIGN KEY (tag_id) REFERENCES tags (id) ON DELETE CASCADE
		);
	`)
	if err != nil {
		db.Close()
		return nil, err
	}

	// A1.2: 索引（CREATE INDEX IF NOT EXISTS 幂等，老库升级安全）。
	// idx_tags_name_lower 是函数索引（modernc.org/sqlite 3.x 支持），CreateTag 的
	// WHERE LOWER(name) = LOWER(?) 命中此索引。
	indexes := []string{
		"CREATE INDEX IF NOT EXISTS idx_associations_tag_id ON associations(tag_id)",
		"CREATE INDEX IF NOT EXISTS idx_associations_file_path ON associations(file_path)",
		"CREATE INDEX IF NOT EXISTS idx_tags_name_lower ON tags(LOWER(name))",
	}
	for _, idx := range indexes {
		if _, err := db.Exec(idx); err != nil {
			db.Close()
			return nil, fmt.Errorf("failed to create index %q: %w", idx, err)
		}
	}

	s := &TagsService{db: db}

	// Migrate if JSON exists and DB didn't exist prior to initialization
	if jsonExists && !dbExists {
		if err := s.migrateFromJSON(jsonPath); err != nil {
			db.Close()
			return nil, fmt.Errorf("migration from JSON failed: %w", err)
		}
	}

	return s, nil
}

func (s *TagsService) migrateFromJSON(jsonPath string) error {
	data, err := os.ReadFile(jsonPath)
	if err != nil {
		return err
	}

	var td models.TagsData
	if err := json.Unmarshal(data, &td); err != nil {
		return err
	}

	// Run migration in a transaction
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	for _, tag := range td.Tags {
		_, err := tx.Exec("INSERT OR IGNORE INTO tags (id, name, color) VALUES (?, ?, ?)", tag.ID, tag.Name, tag.Color)
		if err != nil {
			return err
		}
	}

	for _, assoc := range td.Associations {
		_, err := tx.Exec("INSERT OR IGNORE INTO associations (tag_id, file_path) VALUES (?, ?)", assoc.TagID, assoc.FilePath)
		if err != nil {
			return err
		}
	}

	if err := tx.Commit(); err != nil {
		return err
	}

	// Backup tags.json
	bakPath := jsonPath + ".bak"
	_ = os.Rename(jsonPath, bakPath)

	return nil
}

func (s *TagsService) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.db != nil {
		return s.db.Close()
	}
	return nil
}

func (s *TagsService) GetAllTags() []models.FileTag {
	s.mu.RLock()
	defer s.mu.RUnlock()

	rows, err := s.db.Query("SELECT id, name, color FROM tags")
	if err != nil {
		return []models.FileTag{}
	}
	defer rows.Close()

	var tags []models.FileTag
	for rows.Next() {
		var t models.FileTag
		if err := rows.Scan(&t.ID, &t.Name, &t.Color); err == nil {
			tags = append(tags, t)
		}
	}
	if tags == nil {
		return []models.FileTag{}
	}
	return tags
}

func (s *TagsService) CreateTag(name, color string) (*models.FileTag, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	// Check if already exists
	var count int
	err := s.db.QueryRow("SELECT COUNT(*) FROM tags WHERE LOWER(name) = LOWER(?)", name).Scan(&count)
	if err != nil {
		return nil, err
	}
	if count > 0 {
		return nil, fmt.Errorf("tag already exists")
	}

	tag := models.FileTag{
		ID:    uuid.New().String(),
		Name:  name,
		Color: color,
	}

	_, err = s.db.Exec("INSERT INTO tags (id, name, color) VALUES (?, ?, ?)", tag.ID, tag.Name, tag.Color)
	if err != nil {
		return nil, err
	}
	return &tag, nil
}

func (s *TagsService) DeleteTag(tagID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	// Delete associations first
	_, err = tx.Exec("DELETE FROM associations WHERE tag_id = ?", tagID)
	if err != nil {
		return err
	}

	_, err = tx.Exec("DELETE FROM tags WHERE id = ?", tagID)
	if err != nil {
		return err
	}

	return tx.Commit()
}

func (s *TagsService) AssociateFile(tagID, filePath string) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	// Check if association exists
	var count int
	err := s.db.QueryRow("SELECT COUNT(*) FROM associations WHERE tag_id = ? AND file_path = ?", tagID, filePath).Scan(&count)
	if err != nil {
		return false, err
	}
	if count > 0 {
		return false, nil
	}

	_, err = s.db.Exec("INSERT INTO associations (tag_id, file_path) VALUES (?, ?)", tagID, filePath)
	if err != nil {
		return false, err
	}
	return true, nil
}

func (s *TagsService) DisassociateFile(tagID, filePath string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	_, err := s.db.Exec("DELETE FROM associations WHERE tag_id = ? AND file_path = ?", tagID, filePath)
	return err
}

func (s *TagsService) GetFilesForTag(tagID string) []string {
	s.mu.RLock()
	defer s.mu.RUnlock()

	rows, err := s.db.Query("SELECT file_path FROM associations WHERE tag_id = ?", tagID)
	if err != nil {
		return []string{}
	}
	defer rows.Close()

	var files []string
	for rows.Next() {
		var fp string
		if err := rows.Scan(&fp); err == nil {
			files = append(files, fp)
		}
	}
	if files == nil {
		return []string{}
	}
	return files
}

func (s *TagsService) TagExists(tagID string) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()

	var count int
	err := s.db.QueryRow("SELECT COUNT(*) FROM tags WHERE id = ?", tagID).Scan(&count)
	if err != nil {
		return false
	}
	return count > 0
}

func (s *TagsService) ResolveTagID(tagIdentifier string) (string, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	var id string
	err := s.db.QueryRow("SELECT id FROM tags WHERE id = ? OR name = ?", tagIdentifier, tagIdentifier).Scan(&id)
	if err != nil {
		return "", false
	}
	return id, true
}

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

	// A1.2: 批查（每批 500 个 placeholder）。
	// modernc.org/sqlite 默认 SQLITE_MAX_VARIABLE_NUMBER=32766，500 远低于上限。
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

func (s *TagsService) GetAllFileTags() map[string][]models.FileTag {
	s.mu.RLock()
	defer s.mu.RUnlock()

	result := make(map[string][]models.FileTag)

	rows, err := s.db.Query(`
		SELECT a.file_path, t.id, t.name, t.color 
		FROM associations a 
		JOIN tags t ON a.tag_id = t.id
	`)
	if err != nil {
		return result
	}
	defer rows.Close()

	for rows.Next() {
		var filePath string
		var t models.FileTag
		if err := rows.Scan(&filePath, &t.ID, &t.Name, &t.Color); err == nil {
			result[filePath] = append(result[filePath], t)
		}
	}
	return result
}

func (s *TagsService) CleanDeletedPath(path string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	normPath := filepath.Clean(path)
	prefix := normPath + string(filepath.Separator) + "%"

	_, err := s.db.Exec("DELETE FROM associations WHERE file_path = ? OR file_path LIKE ?", normPath, prefix)
	return err
}
