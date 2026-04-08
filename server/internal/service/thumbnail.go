package service

import (
	"crypto/md5"
	"fmt"
	"image"
	"image/jpeg"
	_ "image/png"
	"os"
	"path/filepath"
	"strings"

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

func (s *ThumbnailService) GetThumbnailPath(sourcePath string) string {
	hash := fmt.Sprintf("%x", md5.Sum([]byte(sourcePath)))
	return filepath.Join(s.cacheDir, hash+".jpg")
}

func (s *ThumbnailService) GenerateThumbnail(sourcePath string) (string, error) {
	cachePath := s.GetThumbnailPath(sourcePath)

	if _, err := os.Stat(cachePath); err == nil {
		return cachePath, nil
	}

	src, err := imaging.Open(sourcePath)
	if err != nil {
		return "", err
	}

	thumb := imaging.Thumbnail(src, s.maxSize, s.maxSize, imaging.Lanczos)

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
	systemCacheDir := filepath.Join(s.cacheDir, "system")
	if err := os.MkdirAll(systemCacheDir, 0755); err != nil {
		return "", err
	}

	hash := fmt.Sprintf("%x", md5.Sum([]byte(sourcePath)))
	cachePath := filepath.Join(systemCacheDir, hash+".jpg")

	if _, err := os.Stat(cachePath); err == nil {
		return cachePath, nil
	}

	src, err := imaging.Open(sourcePath)
	if err != nil {
		return "", err
	}

	thumb := imaging.Thumbnail(src, s.maxSize, s.maxSize, imaging.Lanczos)

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
	absPath, err := filepath.Abs(filePath)
	if err != nil {
		return false, err
	}
	for _, root := range roots {
		absRoot, err := filepath.Abs(root)
		if err != nil {
			continue
		}
		rel, err := filepath.Rel(absRoot, absPath)
		if err != nil {
			continue
		}
		if !strings.HasPrefix(rel, "..") {
			return true, nil
		}
	}
	return false, nil
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
