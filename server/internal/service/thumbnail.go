package service

import (
	"context"
	"crypto/md5"
	"fmt"
	"image"
	"image/jpeg"
	_ "image/png"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"time"

	"github.com/disintegration/imaging"
	"github.com/localmediahub/server/internal/models"
)

type ThumbnailService struct {
	cacheDir string
	maxSize  int
	format   string
	sem      chan struct{}
}

func NewThumbnailService(cacheDir string, maxSize int, format string) (*ThumbnailService, error) {
	if err := os.MkdirAll(cacheDir, 0755); err != nil {
		return nil, err
	}
	return &ThumbnailService{
		cacheDir: cacheDir,
		maxSize:  maxSize,
		format:   format,
		sem:      make(chan struct{}, runtime.NumCPU()),
	}, nil
}

func (s *ThumbnailService) GetThumbnailPath(sourcePath string, modTime time.Time) string {
	key := sourcePath + "|" + modTime.Format(time.RFC3339Nano)
	hash := fmt.Sprintf("%x", md5.Sum([]byte(key)))
	return filepath.Join(s.cacheDir, hash+".jpg")
}

func (s *ThumbnailService) GenerateThumbnail(sourcePath string) (string, error) {
	// Get file info for modTime-based cache key
	fi, err := os.Stat(sourcePath)
	if err != nil {
		return "", err
	}

	cachePath := s.GetThumbnailPath(sourcePath, fi.ModTime())
	if _, err := os.Stat(cachePath); err == nil {
		return cachePath, nil
	}

	// Acquire semaphore slot for CPU-intensive rendering
	s.sem <- struct{}{}
	defer func() { <-s.sem }()

	// Double-check cache in case it was generated while waiting
	if _, err := os.Stat(cachePath); err == nil {
		return cachePath, nil
	}

	src, err := imaging.Open(sourcePath)
	if err != nil {
		return "", err
	}

	thumb := imaging.Thumbnail(src, s.maxSize, s.maxSize, imaging.Box)

	out, err := os.Create(cachePath)
	if err != nil {
		return "", err
	}
	defer out.Close()

	if err := jpeg.Encode(out, thumb, &jpeg.Options{Quality: 85}); err != nil {
		return "", err
	}

	return cachePath, nil
}

func (s *ThumbnailService) GenerateSystemThumbnail(sourcePath string) (string, error) {
	// Get file info for modTime-based cache key
	fi, err := os.Stat(sourcePath)
	if err != nil {
		return "", err
	}

	systemCacheDir := filepath.Join(s.cacheDir, "system")
	if err := os.MkdirAll(systemCacheDir, 0755); err != nil {
		return "", err
	}

	key := sourcePath + "|" + fi.ModTime().Format(time.RFC3339Nano)
	hash := fmt.Sprintf("%x", md5.Sum([]byte(key)))
	cachePath := filepath.Join(systemCacheDir, hash+".jpg")

	if _, err := os.Stat(cachePath); err == nil {
		return cachePath, nil
	}

	// Acquire semaphore slot for CPU-intensive rendering
	s.sem <- struct{}{}
	defer func() { <-s.sem }()

	// Double-check cache in case it was generated while waiting
	if _, err := os.Stat(cachePath); err == nil {
		return cachePath, nil
	}

	src, err := imaging.Open(sourcePath)
	if err != nil {
		return "", err
	}

	thumb := imaging.Thumbnail(src, s.maxSize, s.maxSize, imaging.Box)

	out, err := os.Create(cachePath)
	if err != nil {
		return "", err
	}
	defer out.Close()

	if err := jpeg.Encode(out, thumb, &jpeg.Options{Quality: 85}); err != nil {
		return "", err
	}

	return cachePath, nil
}

func (s *ThumbnailService) ValidatePath(filePath string, roots []string) (bool, error) {
	return IsPathWithinRoots(filePath, roots)
}

func (s *ThumbnailService) PreGenerateThumbnails(files []models.MediaFile, ctx context.Context) {
	var images []models.MediaFile
	for _, f := range files {
		if f.MediaType == "image" {
			images = append(images, f)
		}
	}

	if len(images) == 0 {
		return
	}

	numWorkers := runtime.NumCPU() / 2
	if numWorkers < 1 {
		numWorkers = 1
	}

	jobs := make(chan models.MediaFile, len(images))
	for _, img := range images {
		jobs <- img
	}
	close(jobs)

	var wg sync.WaitGroup
	for i := 0; i < numWorkers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				select {
				case <-ctx.Done():
					return
				case img, ok := <-jobs:
					if !ok {
						return
					}
					// Quick check cache
					fi, err := os.Stat(img.Path)
					if err != nil {
						continue
					}
					cachePath := s.GetThumbnailPath(img.Path, fi.ModTime())
					if _, err := os.Stat(cachePath); err == nil {
						continue
					}

					// Let's also check context before taking the semaphore
					select {
					case <-ctx.Done():
						return
					default:
					}

					_, _ = s.GenerateThumbnail(img.Path)
				}
			}
		}()
	}
	wg.Wait()
}

// DecodeImage decodes an image file and returns the Go image object.
func DecodeImage(path string) (image.Image, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()

	img, _, err := image.Decode(f)
	if err != nil {
		return nil, err
	}
	return img, nil
}
