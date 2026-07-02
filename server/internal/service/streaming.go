package service

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
)

// largeStreamBuffer is the read/write buffer size for direct (non-transcoded)
// video streaming. Go's http.ServeContent defaults to a 32KB io.Copy buffer,
// which causes ExoPlayer to see data in tiny bursts → periodic stutter every
// few seconds as each small chunk arrives and the next one is requested.
// A 1MB buffer lets the server read a large slab from disk and push it to the
// TCP socket in one shot, keeping the network pipe full.
const largeStreamBuffer = 1 << 20 // 1 MiB

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
	size := fi.Size()

	// Handle conditional requests (304).
	w.Header().Set("Last-Modified", fi.ModTime().UTC().Format(http.TimeFormat))
	w.Header().Set("Accept-Ranges", "bytes")
	w.Header().Set("Content-Type", contentType)

	etag := fmt.Sprintf(`"%x-%x"`, fi.ModTime().UnixNano(), size)
	w.Header().Set("ETag", etag)
	if match := r.Header.Get("If-None-Match"); match == etag || r.Header.Get("If-Modified-Since") != "" {
		if match == etag {
			w.WriteHeader(http.StatusNotModified)
			return nil
		}
	}

	// Parse Range header.
	rangeHeader := r.Header.Get("Range")
	var start, end int64
	if rangeHeader != "" {
		// Parse "bytes=START-END"
		const prefix = "bytes="
		if !strings.HasPrefix(rangeHeader, prefix) {
			w.Header().Del("Accept-Ranges")
			w.WriteHeader(http.StatusBadRequest)
			return nil
		}
		spec := strings.TrimPrefix(rangeHeader, prefix)
		parts := strings.SplitN(spec, "-", 2)
		if len(parts) != 2 {
			w.Header().Del("Accept-Ranges")
			w.WriteHeader(http.StatusBadRequest)
			return nil
		}
		start, err = strconv.ParseInt(parts[0], 10, 64)
		if err != nil {
			start = 0
		}
		if parts[1] != "" {
			end, err = strconv.ParseInt(parts[1], 10, 64)
			if err != nil {
				end = size - 1
			}
		} else {
			end = size - 1
		}
		if start < 0 || start >= size {
			w.Header().Set("Content-Range", fmt.Sprintf("bytes */%d", size))
			w.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
			return nil
		}
		if end >= size {
			end = size - 1
		}
	} else {
		start = 0
		end = size - 1
	}

	contentLength := end - start + 1

	if rangeHeader != "" {
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, size))
		w.WriteHeader(http.StatusPartialContent)
	} else {
		w.WriteHeader(http.StatusOK)
	}
	w.Header().Set("Content-Length", strconv.FormatInt(contentLength, 10))

	// Seek to start offset.
	if _, err := f.Seek(start, io.SeekStart); err != nil {
		return err
	}

	// Stream with a 1 MB buffer + explicit flush after each buffer-full.
	// This keeps the TCP pipe full instead of trickling 32 KB at a time.
	buf := make([]byte, largeStreamBuffer)
	flusher, _ := w.(http.Flusher)
	remaining := contentLength

	for remaining > 0 {
		toRead := int64(len(buf))
		if toRead > remaining {
			toRead = remaining
		}

		n, readErr := io.ReadFull(f, buf[:toRead])
		if n > 0 {
			if _, wErr := w.Write(buf[:n]); wErr != nil {
				return nil // client disconnected
			}
			if flusher != nil {
				flusher.Flush()
			}
			remaining -= int64(n)
		}
		if readErr != nil {
			if readErr == io.EOF || readErr == io.ErrUnexpectedEOF {
				break
			}
			return readErr
		}
	}

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

	startSec := r.URL.Query().Get("start")
	if startSec == "" {
		startSec = "0"
	}

	w.Header().Set("Content-Type", "video/mp4")
	w.Header().Set("Transfer-Encoding", "chunked")
	w.Header().Set("Accept-Ranges", "none")

	args := []string{}
	if startSec != "0" {
		args = append(args, "-ss", startSec)
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

	// Use a large buffer + explicit flush for transcoded streams too.
	bufReader := bufio.NewReaderSize(stdout, largeStreamBuffer)
	flusher, _ := w.(http.Flusher)
	buf := make([]byte, largeStreamBuffer)

	for {
		n, readErr := bufReader.Read(buf)
		if n > 0 {
			if _, wErr := w.Write(buf[:n]); wErr != nil {
				cmd.Process.Kill()
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
