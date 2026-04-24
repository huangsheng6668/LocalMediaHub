package service

import (
	"crypto/md5"
	"fmt"
	"image"
	"image/jpeg"
	_ "image/png"
	"os"
	"path/filepath"
	"time"

	"github.com/disintegration/imaging"
)

type ThumbnailService struct {
	cacheDir string
	maxSize  int
	format   string
}

func NewThumbnailService(cacheDir string, maxSize int, format string) (*ThumbnailService, error) {
	if err := os.MkdirAll(cacheDir, 0755); err != nil {
		return nil, err
	}
	return &ThumbnailService{
		cacheDir: cacheDir,
		maxSize:  maxSize,
		format:   format,
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
