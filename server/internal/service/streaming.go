package service

import (
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

type StreamingService struct{}

func NewStreamingService() *StreamingService {
	return &StreamingService{}
}

// contentTypeFromExt returns a MIME type based on file extension.
func contentTypeFromExt(filePath string) string {
	ext := strings.ToLower(filepath.Ext(filePath))
	switch ext {
	case ".mp4":
		return "video/mp4"
	case ".mkv":
		return "video/x-matroska"
	case ".avi":
		return "video/x-msvideo"
	case ".mov":
		return "video/quicktime"
	case ".wmv":
		return "video/x-ms-wmv"
	case ".flv":
		return "video/x-flv"
	case ".webm":
		return "video/webm"
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".png":
		return "image/png"
	case ".gif":
		return "image/gif"
	case ".webp":
		return "image/webp"
	case ".bmp":
		return "image/bmp"
	default:
		return "application/octet-stream"
	}
}

// ServeFile streams a file using http.ServeContent for proper Range, ETag,
// If-Range, and Content-Type handling.
func (s *StreamingService) ServeFile(w http.ResponseWriter, r *http.Request, filePath string) error {
	f, err := os.Open(filePath)
	if err != nil {
		return err
	}
	defer f.Close()

	fi, err := f.Stat()
	if err != nil {
		return err
	}

	if fi.IsDir() {
		return os.ErrNotExist
	}

	// Set Content-Type explicitly so formats like .mkv and .webp work
	// correctly on all platforms, regardless of the OS MIME registry.
	w.Header().Set("Content-Type", contentTypeFromExt(filePath))

	http.ServeContent(w, r, filepath.Base(filePath), fi.ModTime(), f)
	return nil
}

func (s *StreamingService) ValidatePath(filePath string, roots []string) (bool, error) {
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
