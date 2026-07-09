package service

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
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

	db, err := sql.Open("sqlite", dbPath)
	if err != nil {
		return nil, err
	}

	// Optimize SQLite performance and connection behavior
	db.SetMaxOpenConns(1)

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

func (s *TagsService) GetTagsForFiles(filePaths []string) map[string][]models.FileTag {
	s.mu.RLock()
	defer s.mu.RUnlock()

	result := make(map[string][]models.FileTag)
	for _, fp := range filePaths {
		result[fp] = []models.FileTag{}
	}

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
			if _, exists := result[filePath]; exists {
				result[filePath] = append(result[filePath], t)
			}
		}
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
