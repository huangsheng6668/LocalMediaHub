package service

import (
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/localmediahub/server/internal/models"
)

type Scanner struct {
	mu        sync.RWMutex
	cache     map[string][]models.MediaFile
	cacheTime time.Time
	cacheTTL  time.Duration
	videoExts map[string]bool
	imageExts map[string]bool
}

func NewScanner(videoExts, imageExts []string) *Scanner {
	vExts := make(map[string]bool)
	for _, e := range videoExts {
		vExts[strings.ToLower(e)] = true
	}
	iExts := make(map[string]bool)
	for _, e := range imageExts {
		iExts[strings.ToLower(e)] = true
	}
	return &Scanner{
		cache:     make(map[string][]models.MediaFile),
		cacheTTL:  60 * time.Second,
		videoExts: vExts,
		imageExts: iExts,
	}
}

// VideoExts returns the video extension map for handler use.
func (s *Scanner) VideoExts() map[string]bool {
	return s.videoExts
}

// ImageExts returns the image extension map for handler use.
func (s *Scanner) ImageExts() map[string]bool {
	return s.imageExts
}

func (s *Scanner) Scan(roots []string) ([]models.MediaFile, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	allFiles := make([]models.MediaFile, 0)
	for _, root := range roots {
		err := filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
			if err != nil {
				return nil
			}
			if info.IsDir() {
				return nil
			}
			ext := strings.ToLower(filepath.Ext(path))
			mediaType := ""
			if s.videoExts[ext] {
				mediaType = "video"
			} else if s.imageExts[ext] {
				mediaType = "image"
			} else {
				return nil
			}

			relPath := path
			if strings.HasPrefix(path, root) {
				relPath = strings.TrimPrefix(path, root)
				if !strings.HasPrefix(relPath, string(filepath.Separator)) {
					relPath = string(filepath.Separator) + relPath
				}
			}

			allFiles = append(allFiles, models.MediaFile{
				Name:         info.Name(),
				Path:         path,
				RelativePath: relPath,
				Size:         info.Size(),
				ModifiedTime: info.ModTime(),
				MediaType:    mediaType,
				Extension:    ext,
			})
			return nil
		})
		if err != nil {
			return nil, err
		}
	}

	s.cache["all"] = allFiles
	s.cacheTime = time.Now()
	return allFiles, nil
}

func (s *Scanner) GetCached(roots []string) ([]models.MediaFile, error) {
	s.mu.RLock()
	if time.Since(s.cacheTime) < s.cacheTTL {
		if files, ok := s.cache["all"]; ok {
			s.mu.RUnlock()
			return files, nil
		}
	}
	s.mu.RUnlock()
	return s.Scan(roots)
}

func (s *Scanner) InvalidateCache() {
	s.mu.Lock()
	s.cache = make(map[string][]models.MediaFile)
	s.cacheTime = time.Time{}
	s.mu.Unlock()
}

func (s *Scanner) FilterByType(files []models.MediaFile, mediaType string) []models.MediaFile {
	result := make([]models.MediaFile, 0)
	for _, f := range files {
		if f.MediaType == mediaType {
			result = append(result, f)
		}
	}
	return result
}

func (s *Scanner) Search(files []models.MediaFile, query string) []models.MediaFile {
	result := make([]models.MediaFile, 0)
	q := strings.ToLower(query)
	for _, f := range files {
		if strings.Contains(strings.ToLower(f.Name), q) {
			result = append(result, f)
		}
	}
	return result
}
