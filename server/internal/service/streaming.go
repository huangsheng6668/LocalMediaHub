package service

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

type StreamingService struct {
	ffmpegPath string
}

func NewStreamingService(ffmpegPath string) *StreamingService {
	return &StreamingService{ffmpegPath: ffmpegPath}
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
	if r.URL.Query().Get("transcode") == "true" {
		ffmpegCmd := s.ffmpegPath
		if ffmpegCmd == "" {
			ffmpegCmd = "ffmpeg"
		}

		if _, err := exec.LookPath(ffmpegCmd); err != nil {
			return fmt.Errorf("ffmpeg not found, cannot transcode")
		}

		startSec := r.URL.Query().Get("start")
		if startSec == "" {
			startSec = "0"
		}

		w.Header().Set("Content-Type", "video/mp4")
		w.Header().Set("Transfer-Encoding", "chunked")

		args := []string{}
		if startSec != "0" {
			args = append(args, "-ss", startSec)
		}
		args = append(args, "-i", filePath,
			"-vcodec", "libx264",
			"-preset", "ultrafast",
			"-acodec", "aac",
			"-f", "mp4",
			"-movflags", "frag_keyframe+empty_moov",
			"pipe:1",
		)

		cmd := exec.Command(ffmpegCmd, args...)
		stdout, err := cmd.StdoutPipe()
		if err != nil {
			return err
		}

		if err := cmd.Start(); err != nil {
			return err
		}

		ctx, cancel := context.WithCancel(r.Context())
		defer cancel()

		go func() {
			<-ctx.Done()
			if cmd.Process != nil {
				cmd.Process.Kill()
			}
		}()

		_, copyErr := io.Copy(w, stdout)
		waitErr := cmd.Wait()

		if r.Context().Err() != nil {
			return nil
		}

		if copyErr != nil {
			return copyErr
		}
		return waitErr
	}

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
	return IsPathWithinRoots(filePath, roots)
}

func (s *StreamingService) GetVideoDuration(filePath string) (float64, error) {
	ffprobeCmd := "ffprobe"
	if s.ffmpegPath != "" {
		dir := filepath.Dir(s.ffmpegPath)
		base := filepath.Base(s.ffmpegPath)
		if strings.Contains(base, "ffmpeg") {
			ffprobeName := strings.Replace(base, "ffmpeg", "ffprobe", 1)
			derived := filepath.Join(dir, ffprobeName)
			if _, err := exec.LookPath(derived); err == nil {
				ffprobeCmd = derived
			}
		}
	}

	args := []string{
		"-v", "error",
		"-show_entries", "format=duration",
		"-of", "default=noprint_wrappers=1:nokey=1",
		filePath,
	}

	cmd := exec.Command(ffprobeCmd, args...)
	out, err := cmd.Output()
	if err != nil {
		return 0, fmt.Errorf("ffprobe error: %w", err)
	}

	durationStr := strings.TrimSpace(string(out))
	var duration float64
	if _, err := fmt.Sscanf(durationStr, "%f", &duration); err != nil {
		return 0, fmt.Errorf("failed to parse duration %q: %w", durationStr, err)
	}

	return duration, nil
}

