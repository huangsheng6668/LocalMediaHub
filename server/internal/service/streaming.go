package service

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"math"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
)

// largeStreamBuffer is the read/write buffer size for direct (non-transcoded)
// video streaming. Go's http.ServeContent defaults to a 32KB io.Copy buffer,
// which causes ExoPlayer to see data in tiny bursts → periodic stutter every
// few seconds as each small chunk arrives and the next one is requested.
// A 128KB buffer lets the server read a reasonable slab from disk and push it to the
// TCP socket in one shot, keeping the network pipe full, while staying highly
// responsive to client seeks and keeping transcoding startup latency low.
const largeStreamBuffer = 128 * 1024 // 128 KiB

// BufferedReadSeeker wraps an os.File and a bufio.Reader to provide
// seekable buffered reads. This reduces Windows ReadFile system call overhead
// during range requests by http.ServeContent.
type BufferedReadSeeker struct {
	file   *os.File
	reader *bufio.Reader
}

func NewBufferedReadSeeker(f *os.File, size int) *BufferedReadSeeker {
	return &BufferedReadSeeker{
		file:   f,
		reader: bufio.NewReaderSize(f, size),
	}
}

func (b *BufferedReadSeeker) Read(p []byte) (int, error) {
	return b.reader.Read(p)
}

func (b *BufferedReadSeeker) Seek(offset int64, whence int) (int64, error) {
	realOffset := offset
	if whence == io.SeekCurrent {
		realOffset -= int64(b.reader.Buffered())
	}
	pos, err := b.file.Seek(realOffset, whence)
	if err != nil {
		return pos, err
	}
	b.reader.Reset(b.file)
	return pos, nil
}

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

// ServeFile streams a file. For direct (non-transcoded) mode it uses a custom
// Range handler with a 1 MB buffer + explicit flushing instead of
// http.ServeContent's 32 KB default, eliminating periodic stutter on video
// playback.
// ServeFile streams a file. For direct (non-transcoded) mode it uses Go's highly
// optimized, standard library http.ServeContent which handles RFC 7233 Range requests
// (including multi-range, conditional requests) natively, has zero heap allocations,
// and immediately exits on client disconnections.
func (s *StreamingService) ServeFile(w http.ResponseWriter, r *http.Request, filePath string) error {
	if r.URL.Query().Get("transcode") == "true" {
		return s.serveTranscoded(w, r, filePath)
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

	contentType := contentTypeFromExt(filePath)
	w.Header().Set("Content-Type", contentType)

	// Wrap in a 256 KB BufferedReadSeeker to minimize system calls on Windows
	bufferedFile := NewBufferedReadSeeker(f, 256*1024)

	http.ServeContent(w, r, fi.Name(), fi.ModTime(), bufferedFile)
	return nil
}

// serveTranscoded pipes ffmpeg stdout to the response writer.
func (s *StreamingService) serveTranscoded(w http.ResponseWriter, r *http.Request, filePath string) error {
	ffmpegCmd := s.ffmpegPath
	if ffmpegCmd == "" {
		ffmpegCmd = "ffmpeg"
	}

	if _, err := exec.LookPath(ffmpegCmd); err != nil {
		return fmt.Errorf("ffmpeg not found, cannot transcode")
	}

	startSecStr := r.URL.Query().Get("start")
	var startSec float64 = 0
	if startSecStr != "" {
		parsed, parseErr := strconv.ParseFloat(startSecStr, 64)
		if parseErr != nil || math.IsNaN(parsed) || parsed < 0 || parsed > 86400 {
			return fmt.Errorf("invalid start parameter")
		}
		startSec = parsed
	}

	w.Header().Set("Content-Type", "video/mp4")
	w.Header().Set("Transfer-Encoding", "chunked")
	w.Header().Set("Accept-Ranges", "none")

	args := []string{}
	if startSec != 0 {
		args = append(args, "-ss", strconv.FormatFloat(startSec, 'f', 3, 64))
	}
	args = append(args, "-i", filePath)

	vcodec := r.URL.Query().Get("vcodec")
	if vcodec == "copy" {
		args = append(args, "-vcodec", "copy")
	} else {
		args = append(args, "-vcodec", "libx264", "-preset", "ultrafast")
	}

	args = append(args,
		"-acodec", "aac",
		"-f", "mp4",
		"-movflags", "frag_keyframe+empty_moov",
		"pipe:1",
	)

	// Phase 8 T5-02: bind ffmpeg lifetime to the client's request context.
	// When the client disconnects (or the server shuts down), r.Context()
	// is cancelled, which kills ffmpeg — preventing orphaned processes
	// that would keep transcode CPU/disk long after the client is gone.
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()

	cmd := exec.CommandContext(ctx, ffmpegCmd, args...)
	// Windows ffmpeg subprocess may not respond to CTRL_BREAK_EVENT that
	// Go's default CommandContext sends. Force kill on context cancellation.
	cmd.Cancel = func() error {
		if cmd.Process != nil {
			return cmd.Process.Kill()
		}
		return os.ErrProcessDone
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}

	if err := cmd.Start(); err != nil {
		return err
	}

	// killOnce guarantees cmd.Process.Kill() is called at most once and only
	// when cmd.Process is non-nil, eliminating the double-kill risk between
	// cmd.Cancel (fired by CommandContext) and the write-fail branch in the
	// main loop below.
	var killOnce sync.Once
	killCmd := func() {
		killOnce.Do(func() {
			if cmd.Process != nil {
				cmd.Process.Kill()
			}
		})
	}

	// Use a large buffer + explicit flush for transcoded streams too.
	bufReader := bufio.NewReaderSize(stdout, largeStreamBuffer)
	flusher, _ := w.(http.Flusher)
	buf := make([]byte, largeStreamBuffer)

	for {
		n, readErr := bufReader.Read(buf)
		if n > 0 {
			if _, wErr := w.Write(buf[:n]); wErr != nil {
				killCmd()
				_ = cmd.Wait()
				return nil
			}
			if flusher != nil {
				flusher.Flush()
			}
		}
		if readErr != nil {
			break
		}
	}

	waitErr := cmd.Wait()
	if r.Context().Err() != nil {
		return nil
	}
	return waitErr
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
