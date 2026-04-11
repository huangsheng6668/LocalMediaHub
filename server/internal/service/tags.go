package service

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/google/uuid"

	"github.com/localmediahub/server/internal/models"
)

type TagsService struct {
	mu   sync.RWMutex
	path string
	data TagsData
}

type TagsData struct {
	Tags         []models.FileTag         `json:"tags"`
	Associations []models.FileAssociation `json:"associations"`
}

func NewTagsService(dataDir string) (*TagsService, error) {
	path := filepath.Join(dataDir, "tags.json")
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return &TagsService{
				path: path,
				data: TagsData{
					Tags:         []models.FileTag{},
					Associations: []models.FileAssociation{},
				},
			}, nil
		}
		return nil, err
	}
	var td TagsData
	if err := json.Unmarshal(data, &td); err != nil {
		return nil, err
	}
	return &TagsService{path: path, data: td}, nil
}

func (s *TagsService) Save() error {
	data, err := json.MarshalIndent(s.data, "", "  ")
	if err != nil {
		return err
	}
	dir := filepath.Dir(s.path)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return err
	}
	return os.WriteFile(s.path, data, 0644)
}

func (s *TagsService) GetAllTags() []models.FileTag {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.data.Tags == nil {
		return []models.FileTag{}
	}
	return s.data.Tags
}

func (s *TagsService) CreateTag(name, color string) (*models.FileTag, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	for _, t := range s.data.Tags {
		if strings.EqualFold(t.Name, name) {
			return nil, fmt.Errorf("tag already exists")
		}
	}

	tag := models.FileTag{
		ID:    uuid.New().String(),
		Name:  name,
		Color: color,
	}
	s.data.Tags = append(s.data.Tags, tag)
	if err := s.Save(); err != nil {
		return nil, err
	}
	return &tag, nil
}

func (s *TagsService) DeleteTag(tagID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	newTags := make([]models.FileTag, 0, len(s.data.Tags))
	for _, t := range s.data.Tags {
		if t.ID != tagID {
			newTags = append(newTags, t)
		}
	}
	s.data.Tags = newTags

	newAssocs := make([]models.FileAssociation, 0, len(s.data.Associations))
	for _, a := range s.data.Associations {
		if a.TagID != tagID {
			newAssocs = append(newAssocs, a)
		}
	}
	s.data.Associations = newAssocs

	return s.Save()
}

func (s *TagsService) AssociateFile(tagID, filePath string) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	for _, a := range s.data.Associations {
		if a.TagID == tagID && a.FilePath == filePath {
			return false, nil
		}
	}

	s.data.Associations = append(s.data.Associations, models.FileAssociation{
		TagID:    tagID,
		FilePath: filePath,
	})
	return true, s.Save()
}

func (s *TagsService) DisassociateFile(tagID, filePath string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	newAssocs := make([]models.FileAssociation, 0, len(s.data.Associations))
	for _, a := range s.data.Associations {
		if !(a.TagID == tagID && a.FilePath == filePath) {
			newAssocs = append(newAssocs, a)
		}
	}
	s.data.Associations = newAssocs
	return s.Save()
}

func (s *TagsService) GetFilesForTag(tagID string) []string {
	s.mu.RLock()
	defer s.mu.RUnlock()

	var files []string
	for _, a := range s.data.Associations {
		if a.TagID == tagID {
			files = append(files, a.FilePath)
		}
	}
	return files
}

func (s *TagsService) TagExists(tagID string) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, t := range s.data.Tags {
		if t.ID == tagID {
			return true
		}
	}
	return false
}

// GetTagsForFiles returns a map from file path to tags for the given file paths.
func (s *TagsService) GetTagsForFiles(filePaths []string) map[string][]models.FileTag {
	s.mu.RLock()
	defer s.mu.RUnlock()

	result := make(map[string][]models.FileTag)
	for _, fp := range filePaths {
		result[fp] = []models.FileTag{}
	}

	tagMap := make(map[string]models.FileTag)
	for _, t := range s.data.Tags {
		tagMap[t.ID] = t
	}

	for _, a := range s.data.Associations {
		tag, ok := tagMap[a.TagID]
		if !ok {
			continue
		}
		if _, exists := result[a.FilePath]; exists {
			result[a.FilePath] = append(result[a.FilePath], tag)
		}
	}
	return result
}

// GetAllFileTags returns a map from file path to tags for all tagged files.
func (s *TagsService) GetAllFileTags() map[string][]models.FileTag {
	s.mu.RLock()
	defer s.mu.RUnlock()

	result := make(map[string][]models.FileTag)
	tagMap := make(map[string]models.FileTag)
	for _, t := range s.data.Tags {
		tagMap[t.ID] = t
	}

	for _, a := range s.data.Associations {
		tag, ok := tagMap[a.TagID]
		if !ok {
			continue
		}
		result[a.FilePath] = append(result[a.FilePath], tag)
	}
	return result
}
