package service

import (
	"context"
	"io/fs"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"golang.org/x/sync/errgroup"
	"golang.org/x/sync/singleflight"

	"github.com/localmediahub/server/internal/models"
)

type Scanner struct {
	mu             sync.RWMutex
	cache          map[string][]models.MediaFile
	cacheTime      time.Time
	cacheTTL       time.Duration
	videoExts      map[string]bool
	imageExts      map[string]bool
	sf             singleflight.Group
	OnScanComplete func(files []models.MediaFile)
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
	// Use errgroup for concurrent scanning
	g, ctx := errgroup.WithContext(context.Background())

	// Slice of slices to collect results without lock contention during walk
	results := make([][]models.MediaFile, len(roots))

	for i, root := range roots {
		i, root := i, root
		g.Go(func() error {
			var localFiles []models.MediaFile
			err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
				if err != nil {
					return nil
				}
				select {
				case <-ctx.Done():
					return ctx.Err()
				default:
				}
				if d.IsDir() {
					return nil
				}
				ext := strings.ToLower(filepath.Ext(path))
				mediaType := ""
				s.mu.RLock()
				isVideo := s.videoExts[ext]
				isImage := s.imageExts[ext]
				s.mu.RUnlock()

				if isVideo {
					mediaType = "video"
				} else if isImage {
					mediaType = "image"
				} else {
					return nil
				}

				// Only stat media files (not directories or non-media files)
				info, err := d.Info()
				if err != nil {
					return nil
				}

				relPath := path
				if strings.HasPrefix(path, root) {
					relPath = strings.TrimPrefix(path, root)
					if !strings.HasPrefix(relPath, string(filepath.Separator)) {
						relPath = string(filepath.Separator) + relPath
					}
				}

				localFiles = append(localFiles, models.MediaFile{
					Name:         d.Name(),
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
				return err
			}
			results[i] = localFiles
			return nil
		})
	}

	if err := g.Wait(); err != nil {
		return nil, err
	}

	// Merge slices
	allFiles := make([]models.MediaFile, 0)
	for _, subList := range results {
		allFiles = append(allFiles, subList...)
	}

	s.mu.Lock()
	s.cache["all"] = allFiles
	s.cacheTime = time.Now()
	callback := s.OnScanComplete
	s.mu.Unlock()

	if callback != nil {
		go callback(allFiles)
	}

	return allFiles, nil
}

func (s *Scanner) GetCached(roots []string) ([]models.MediaFile, error) {
	s.mu.RLock()
	cachedValid := time.Since(s.cacheTime) < s.cacheTTL
	if cachedValid {
		if files, ok := s.cache["all"]; ok {
			s.mu.RUnlock()
			return files, nil
		}
	}
	s.mu.RUnlock()

	// Use singleflight to prevent cache stampede
	val, err, _ := s.sf.Do("scan", func() (interface{}, error) {
		return s.Scan(roots)
	})
	if err != nil {
		return nil, err
	}
	return val.([]models.MediaFile), nil
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
