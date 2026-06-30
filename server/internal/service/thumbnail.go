package service

import (
	"context"
	"crypto/md5"
	"fmt"
	"image"
	"image/jpeg"
	_ "image/png"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/disintegration/imaging"
	"github.com/localmediahub/server/internal/models"
)

type ThumbnailService struct {
	cacheDir   string
	maxSize    int
	format     string
	sem        chan struct{}
	ffmpegPath string
}

func NewThumbnailService(cacheDir string, maxSize int, format string, ffmpegPath string) (*ThumbnailService, error) {
	if err := os.MkdirAll(cacheDir, 0755); err != nil {
		return nil, err
	}
	return &ThumbnailService{
		cacheDir:   cacheDir,
		maxSize:    maxSize,
		format:     format,
		sem:        make(chan struct{}, runtime.NumCPU()),
		ffmpegPath: ffmpegPath,
	}, nil
}

func (s *ThumbnailService) getFFmpegCmd() string {
	if s.ffmpegPath != "" {
		return s.ffmpegPath
	}
	return "ffmpeg"
}

func (s *ThumbnailService) HasFFmpeg() bool {
	cmdName := s.getFFmpegCmd()
	_, err := exec.LookPath(cmdName)
	return err == nil
}

func (s *ThumbnailService) getFFprobeCmd() string {
	if s.ffmpegPath != "" {
		return ffprobeSibling(s.ffmpegPath)
	}
	return "ffprobe"
}

func (s *ThumbnailService) HasFFprobe() bool {
	_, err := exec.LookPath(s.getFFprobeCmd())
	return err == nil
}

// videoDuration returns the file's duration in seconds via ffprobe, or
// (0, false) if ffprobe is unavailable or the probe fails.
func (s *ThumbnailService) videoDuration(sourcePath string) (float64, bool) {
	cmd := exec.Command(s.getFFprobeCmd(),
		"-v", "error",
		"-show_entries", "format=duration",
		"-of", "default=noprint_wrappers=1:nokey=1",
		sourcePath)
	out, err := cmd.Output()
	if err != nil {
		return 0, false
	}
	return parseFFprobeDuration(string(out))
}

func isVideoFile(filePath string) bool {
	ext := strings.ToLower(filepath.Ext(filePath))
	switch ext {
	case ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".ts", ".webm", ".m4v", ".3gp":
		return true
	}
	return false
}

func (s *ThumbnailService) GetThumbnailPath(sourcePath string, modTime time.Time) string {
	key := sourcePath + "|" + modTime.Format(time.RFC3339Nano)
	hash := fmt.Sprintf("%x", md5.Sum([]byte(key)))
	return filepath.Join(s.cacheDir, hash+".jpg")
}

func (s *ThumbnailService) generateThumbnailFromFile(sourcePath string, cachePath string) (string, error) {
	if isVideoFile(sourcePath) {
		if !s.HasFFmpeg() {
			return "", fmt.Errorf("ffmpeg not found, cannot generate video thumbnail")
		}

		// Create a temporary JPG file
		tempFile, err := os.CreateTemp("", "videothumb-*.jpg")
		if err != nil {
			return "", err
		}
		tempPath := tempFile.Name()
		tempFile.Close()
		defer os.Remove(tempPath)

		// Seek to a representative frame (video midpoint when ffprobe is
		// available, else the prior default of 5s). Fall back to 0s on failure.
		ffmpegCmd := s.getFFmpegCmd()
		seek := midpointSeek(s.videoDuration(sourcePath))
		cmd := exec.Command(ffmpegCmd, "-y", "-ss", seek, "-i", sourcePath, "-vframes", "1", "-f", "image2", tempPath)
		if err := cmd.Run(); err != nil {
			// 2. If it fails (e.g. video is too short), fallback to 0 seconds
			cmdFallback := exec.Command(ffmpegCmd, "-y", "-ss", "0", "-i", sourcePath, "-vframes", "1", "-f", "image2", tempPath)
			if err := cmdFallback.Run(); err != nil {
				return "", fmt.Errorf("failed to extract video frame: %w", err)
			}
		}

		// Open the extracted image
		src, err := imaging.Open(tempPath)
		if err != nil {
			return "", fmt.Errorf("failed to open extracted video frame: %w", err)
		}

		// Generate the thumbnail
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

	return s.generateThumbnailFromFile(sourcePath, cachePath)
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

	return s.generateThumbnailFromFile(sourcePath, cachePath)
}

func (s *ThumbnailService) PreGenerateThumbnails(files []models.MediaFile, ctx context.Context) {
	hasFFmpeg := s.HasFFmpeg()
	var queue []models.MediaFile
	for _, f := range files {
		switch f.MediaType {
		case "image":
			queue = append(queue, f)
		case "video":
			if hasFFmpeg {
				queue = append(queue, f)
			}
		}
	}

	if len(queue) == 0 {
		return
	}

	numWorkers := runtime.NumCPU() / 2
	if numWorkers < 1 {
		numWorkers = 1
	}

	jobs := make(chan models.MediaFile, len(queue))
	for _, img := range queue {
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

// ffprobeSibling derives the ffprobe path from an ffmpeg path: same directory
// and extension with the basename ffmpeg -> ffprobe. If the basename does not
// contain "ffmpeg", it returns the bare "ffprobe" (relying on PATH lookup).
func ffprobeSibling(ffmpegPath string) string {
	base := strings.ToLower(filepath.Base(ffmpegPath))
	if !strings.Contains(base, "ffmpeg") {
		return "ffprobe"
	}
	ext := filepath.Ext(ffmpegPath)
	return filepath.Join(filepath.Dir(ffmpegPath), "ffprobe"+ext)
}

// parseFFprobeDuration parses ffprobe's duration output (seconds, decimal).
// Returns false on empty / "N/A" / non-numeric / non-positive input.
func parseFFprobeDuration(out string) (float64, bool) {
	out = strings.TrimSpace(out)
	if out == "" || out == "N/A" {
		return 0, false
	}
	d, err := strconv.ParseFloat(out, 64)
	if err != nil || d <= 0 {
		return 0, false
	}
	return d, true
}

// midpointSeek returns the seek offset (seconds, 2 decimals) at half the video
// duration for a representative frame; falls back to "5" when the duration is
// unknown (preserving the prior hardcoded -ss 5 behavior).
func midpointSeek(duration float64, ok bool) string {
	if !ok || duration <= 0 {
		return "5"
	}
	return strconv.FormatFloat(duration/2, 'f', 2, 64)
}
