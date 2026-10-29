package service

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"log/slog"
	"math"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// largeStreamBuffer is the read/write buffer size for direct (non-transcoded)
// video streaming. Go's http.ServeContent defaults to a 32KB io.Copy buffer,
// which causes ExoPlayer to see data in tiny bursts → periodic stutter every
// few seconds as each small chunk arrives and the next one is requested.
// A 128KB buffer lets the server read a reasonable slab from disk and push it to the
// TCP socket in one shot, keeping the network pipe full, while staying highly
// responsive to client seeks and keeping transcoding startup latency low.
const largeStreamBuffer = 128 * 1024 // 128 KiB

// streamReadBufferSize is the read-ahead buffer handed to http.ServeContent for
// direct playback. Larger than largeStreamBuffer because ServeContent issues
// 32 KB io.CopyN reads for Range responses — with a smaller buffer each of
// those would become its own ReadFile syscall.
const streamReadBufferSize = 256 * 1024 // 256 KiB

// streamReaderPool reuses BufferedReadSeeker instances (and their 256 KiB bufio
// buffers) across requests. Without it every stream request — including each
// Range request a player issues while seeking — allocated a fresh 256 KiB
// buffer, so N concurrent viewers produced N × 256 KiB of short-lived garbage.
var streamReaderPool = sync.Pool{
	New: func() any {
		return &BufferedReadSeeker{reader: bufio.NewReaderSize(nil, streamReadBufferSize)}
	},
}

// BufferedReadSeeker wraps an os.File and a bufio.Reader to provide
// seekable buffered reads. This reduces Windows ReadFile system call overhead
// during range requests by http.ServeContent.
type BufferedReadSeeker struct {
	file   *os.File
	reader *bufio.Reader
}

// NewBufferedReadSeeker allocates a standalone BufferedReadSeeker. ServeFile
// does not use this — it takes an instance from streamReaderPool instead.
func NewBufferedReadSeeker(f *os.File, size int) *BufferedReadSeeker {
	return &BufferedReadSeeker{
		file:   f,
		reader: bufio.NewReaderSize(f, size),
	}
}

// Reset rebinds the reader to f, discarding any data buffered from the previous
// use. Passing nil detaches the reader entirely so a pooled instance never
// keeps a closed *os.File (or its 256 KiB buffer) reachable — note the explicit
// nil branch: passing a typed nil *os.File straight to bufio's io.Reader
// parameter would box it into a non-nil interface.
func (b *BufferedReadSeeker) Reset(f *os.File) {
	b.file = f
	if f == nil {
		b.reader.Reset(nil)
		return
	}
	b.reader.Reset(f)
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
	// prober resolves the transcode video encoder (two-level hardware probe,
	// libx264 fallback). Empty preference → resolve() → libx264 directly,
	// no subprocess.
	prober *encoderProber
	// transcodeSem caps concurrent transcode sessions (spec 3.3); nil = no cap.
	transcodeSem chan struct{}
	// activeSessions counts sessions between acquire and release, exposed
	// via TranscodeStatus for the admin endpoint.
	activeSessions atomic.Int64

	// ---- HLS transcode sessions (spec 2026-09-03-hls-transcode) ----
	hlsDir        string
	hlsMu         sync.Mutex
	hlsSessions   map[string]*hlsSession
	hlsReaperOnce sync.Once
	hlsStop       chan struct{}
	hlsDiskCap    int64
}

// NewStreamingService wires the transcode path. encoderPreference comes
// from transcode.encoder_preference (config normalizes the default chain).
// maxSessions > 0 caps concurrent transcode sessions; anything else means
// unlimited (config layer normalizes absent/0 to the default cap, so the
// only way to reach unlimited here is an explicit negative value).
func NewStreamingService(ffmpegPath string, encoderPreference []string, maxSessions int) *StreamingService {
	// 自定义 ffmpeg 目录前插 PATH：exec 调用点统一使用字面量二进制名
	activateToolDir(ffmpegPath)
	var sem chan struct{}
	if maxSessions > 0 {
		sem = make(chan struct{}, maxSessions)
	}
	return &StreamingService{
		ffmpegPath:   ffmpegPath,
		prober:       newEncoderProber(encoderPreference),
		transcodeSem: sem,
		hlsDir:       DefaultHlsCacheDir,
		hlsSessions:  make(map[string]*hlsSession),
		hlsStop:      make(chan struct{}),
		hlsDiskCap:   DefaultHlsDiskCapBytes,
	}
}

// acquireTranscodeSlot implements the session cap (spec 3.3). It queues
// silently — no 429 — and abandons acquisition as soon as the client
// disconnects, so a queued request spawns no ffmpeg at all.
func (s *StreamingService) acquireTranscodeSlot(ctx context.Context) (release func(), acquired bool) {
	if s.transcodeSem == nil {
		s.activeSessions.Add(1)
		return func() { s.activeSessions.Add(-1) }, true
	}
	select {
	case s.transcodeSem <- struct{}{}:
		s.activeSessions.Add(1)
		return func() {
			s.activeSessions.Add(-1)
			<-s.transcodeSem
		}, true
	case <-ctx.Done():
		return nil, false
	}
}

// TranscodeStatus is the admin-facing snapshot of the transcode path.
type TranscodeStatus struct {
	Active      int                  `json:"active"`
	MaxSessions int                  `json:"max_sessions"` // -1 = unlimited
	Probe       transcodeProbeStatus `json:"probe"`
	Hls         HlsStatusInfo        `json:"hls"`
}

// HlsStatusInfo reports HLS session counts (spec 2026-09-03-hls-transcode).
type HlsStatusInfo struct {
	Running int `json:"running"`
	Total   int `json:"total"`
}

func (s *StreamingService) TranscodeStatus() TranscodeStatus {
	maxSessions := -1
	if s.transcodeSem != nil {
		maxSessions = cap(s.transcodeSem)
	}
	running, total := s.HlsStatus()
	return TranscodeStatus{
		Active:      int(s.activeSessions.Load()),
		MaxSessions: maxSessions,
		Probe:       s.prober.status(),
		Hls:         HlsStatusInfo{Running: running, Total: total},
	}
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

// ServeFile streams a file.
//
// Direct (non-transcoded) mode uses the standard library's http.ServeContent,
// which handles RFC 7233 Range requests (including multi-range and conditional
// requests) natively and returns as soon as the client disconnects. The reader
// it is given is a pooled 256 KB BufferedReadSeeker, because ServeContent's own
// 32 KB io.Copy buffer would otherwise turn playback into many small ReadFile
// calls (visible as periodic stutter on Windows). Transcoded requests
// (?transcode=true) take the separate serveTranscoded path.
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

	// Wrap in a pooled 256 KB BufferedReadSeeker to minimize system calls on
	// Windows. http.ServeContent only ever Seeks/Reads this reader, and Seek
	// resets the bufio buffer, so a recycled instance is safe to hand straight
	// in. Returned to the pool on the way out — ServeContent is synchronous, so
	// by then nothing holds a reference to it.
	brs := streamReaderPool.Get().(*BufferedReadSeeker)
	defer func() {
		brs.Reset(nil)
		streamReaderPool.Put(brs)
	}()
	brs.Reset(f)

	http.ServeContent(w, r, fi.Name(), fi.ModTime(), brs)
	return nil
}

// serveTranscoded pipes ffmpeg stdout to the response writer.
func (s *StreamingService) serveTranscoded(w http.ResponseWriter, r *http.Request, filePath string) error {
	// 进入子进程 argv 前净化工具路径与媒体路径（CWE-78 边界）
	filePath, ferr := sanitizeMediaArg(filePath)
	if ferr != nil {
		return ferr
	}

	if _, err := exec.LookPath(ffmpegBin); err != nil {
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

	// Frozen wire contract (spec 3.2): the client vcodec value is an
	// allowlist lookup key only — never interpolated into argv.
	enc := resolveVCodecParam(r.URL.Query().Get("vcodec"), s.prober.isUsable, s.prober.resolve())
	args := buildTranscodeArgs(filePath, startSec, enc)

	// Session cap (spec 3.3): queue silently; a queued request whose client
	// disconnects spawns no ffmpeg at all.
	release, acquired := s.acquireTranscodeSlot(r.Context())
	if !acquired {
		slog.Info("transcode session abandoned while queued", "file", filepath.Base(filePath))
		return nil
	}
	defer release()

	slog.Info("transcode session started", "encoder", enc.Name, "file", filepath.Base(filePath), "startSec", startSec)
	started := time.Now()
	var written int64

	// Phase 8 T5-02: bind ffmpeg lifetime to the client's request context.
	// When the client disconnects (or the server shuts down), r.Context()
	// is cancelled, which kills ffmpeg — preventing orphaned processes
	// that would keep transcode CPU/disk long after the client is gone.
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()

	cmd := exec.CommandContext(ctx, "ffmpeg", args...)
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
			written += int64(n)
			if _, wErr := w.Write(buf[:n]); wErr != nil {
				killCmd()
				_ = cmd.Wait()
				slog.Info("transcode session ended", "encoder", enc.Name, "bytes", written, "elapsed", time.Since(started).Round(time.Millisecond).String(), "reason", "client disconnect")
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
	elapsed := time.Since(started).Round(time.Millisecond).String()
	if r.Context().Err() != nil {
		slog.Info("transcode session ended", "encoder", enc.Name, "bytes", written, "elapsed", elapsed, "reason", "client disconnect")
		return nil
	}
	if waitErr != nil {
		slog.Warn("transcode session failed", "encoder", enc.Name, "bytes", written, "elapsed", elapsed, "error", waitErr)
		return waitErr
	}
	slog.Info("transcode session ended", "encoder", enc.Name, "bytes", written, "elapsed", elapsed, "reason", "eof")
	return nil
}

func (s *StreamingService) GetVideoDuration(filePath string) (float64, error) {
	// 进入子进程 argv 前净化媒体路径（CWE-78 边界）
	filePath, ferr := sanitizeMediaArg(filePath)
	if ferr != nil {
		return 0, ferr
	}

	args := []string{
		"-v", "error",
		"-show_entries", "format=duration",
		"-of", "default=noprint_wrappers=1:nokey=1",
		filePath,
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "ffprobe", args...)
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
