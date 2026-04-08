package service

import (
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

type StreamingService struct {
	chunkSize int64
}

func NewStreamingService() *StreamingService {
	return &StreamingService{chunkSize: 64 * 1024}
}

type Range struct {
	Start int64
	End   int64
}

func ParseRange(header string, fileSize int64) (Range, bool) {
	re := regexp.MustCompile(`bytes=(\d*)-(\d*)`)
	matches := re.FindStringSubmatch(header)
	if len(matches) == 0 {
		return Range{0, fileSize - 1}, false
	}

	var start, end int64
	if matches[1] == "" {
		start = 0
	} else {
		start, _ = strconv.ParseInt(matches[1], 10, 64)
	}
	if matches[2] == "" {
		end = fileSize - 1
	} else {
		end, _ = strconv.ParseInt(matches[2], 10, 64)
	}

	if start >= fileSize || end >= fileSize {
		return Range{0, fileSize - 1}, false
	}
	return Range{Start: start, End: end}, true
}

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
	fileSize := fi.Size()

	contentType := "application/octet-stream"
	ext := strings.ToLower(filepath.Ext(filePath))
	switch ext {
	case ".mp4":
		contentType = "video/mp4"
	case ".mkv":
		contentType = "video/x-matroska"
	case ".avi":
		contentType = "video/x-msvideo"
	case ".mov":
		contentType = "video/quicktime"
	case ".jpg", ".jpeg":
		contentType = "image/jpeg"
	case ".png":
		contentType = "image/png"
	case ".gif":
		contentType = "image/gif"
	case ".webp":
		contentType = "image/webp"
	}

	rangeHeader := r.Header.Get("Range")
	if rangeHeader == "" {
		w.Header().Set("Content-Type", contentType)
		w.Header().Set("Content-Length", strconv.FormatInt(fileSize, 10))
		w.Header().Set("Accept-Ranges", "bytes")
		io.CopyN(w, f, fileSize)
		return nil
	}

	rng, ok := ParseRange(rangeHeader, fileSize)
	if !ok {
		return fmt.Errorf("invalid range")
	}

	w.WriteHeader(http.StatusPartialContent)
	w.Header().Set("Content-Type", contentType)
	w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", rng.Start, rng.End, fileSize))
	w.Header().Set("Content-Length", strconv.FormatInt(rng.End-rng.Start+1, 10))
	w.Header().Set("Accept-Ranges", "bytes")

	_, err = f.Seek(rng.Start, io.SeekStart)
	if err != nil {
		return err
	}
	io.CopyN(w, f, rng.End-rng.Start+1)
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
