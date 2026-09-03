package service

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"sync"
	"time"
)

// Spec 2026-09-03-hls-transcode (Phase B1): transcoded output is written as
// HLS segments under .cache/hls/<session-key>/ so clients get native random
// seeking and completed transcodes double as a disk cache. The legacy
// single-pipe fMP4 path (serveTranscoded) stays untouched for the web
// client until Phase B2.

// DefaultHlsCacheDir hosts HLS transcode sessions (relative to server CWD).
const DefaultHlsCacheDir = ".cache/hls"

// DefaultHlsDiskCapBytes bounds the total on-disk HLS cache. Completed
// sessions beyond the cap are evicted oldest-first; same posture as the
// thumbnail disk cap (Phase 9 M-3) — a constant, not config (YAGNI).
const DefaultHlsDiskCapBytes int64 = 4 << 30 // 4 GiB

// hlsIdleTimeout kills a still-running session whose last playlist/segment
// fetch is older than this (client gone mid-transcode).
const hlsIdleTimeout = 3 * time.Minute

// hlsPlaylistWait bounds how long GetOrCreateHlsSession waits for ffmpeg
// to emit the first playlist + segment before declaring the session failed.
const hlsPlaylistWait = 8 * time.Second

const hlsPlaylistName = "index.m3u8"

// hlsSegmentNameRe is the strict whitelist for segment file names. ffmpeg
// writes seg%05d.ts; anything else (traversal, extensions, case games) is
// rejected before it ever reaches a path join.
var hlsSegmentNameRe = regexp.MustCompile(`^seg[0-9]{5}\.ts$`)

func validHlsSegmentName(name string) bool {
	return hlsSegmentNameRe.MatchString(name)
}

// hlsSessionKey derives the on-disk session identity: same file + same
// modtime + same encoder reuses the session; a changed file naturally
// rotates to a fresh key.
func hlsSessionKey(cleanPath string, modTime time.Time, encoder string) string {
	sum := sha256.Sum256([]byte(cleanPath + "|" + fmt.Sprintf("%d", modTime.UnixNano()) + "|" + encoder))
	return hex.EncodeToString(sum[:])[:16]
}

// hlsSession is one ffmpeg-backed HLS transcode (running) or its completed
// on-disk artifact set (cache hit).
type hlsSession struct {
	key      string
	dir      string
	playlist string
	done     chan struct{} // closed when ffmpeg exited
	cancel   context.CancelFunc

	mu         sync.Mutex
	running    bool
	err        error
	lastAccess time.Time
	size       int64 // total segment bytes; computed once when ffmpeg exits
}

func (s *hlsSession) Touch() {
	s.mu.Lock()
	s.lastAccess = time.Now()
	s.mu.Unlock()
}

func (s *hlsSession) LastAccess() time.Time {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.lastAccess
}

func (s *hlsSession) Running() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.running
}

func (s *hlsSession) Err() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.err
}

// SegmentPath returns the absolute path for a validated segment name.
// Callers must still handle os.Stat misses (segment not yet written).
func (s *hlsSession) SegmentPath(name string) (string, bool) {
	if !validHlsSegmentName(name) {
		return "", false
	}
	return filepath.Join(s.dir, name), true
}

// GetOrCreateHlsSession returns the HLS session for the given source file,
// starting ffmpeg on first request. modTime must come from the caller stat
// of the already-validated path and pins the session identity.
func (s *StreamingService) GetOrCreateHlsSession(path string, modTime time.Time) (*hlsSession, error) {
	enc := s.prober.resolve()
	key := hlsSessionKey(filepath.Clean(path), modTime, enc.Name)

	s.hlsMu.Lock()
	if sess, ok := s.hlsSessions[key]; ok {
		s.hlsMu.Unlock()
		sess.Touch()
		return sess, nil
	}
	sess := &hlsSession{
		key:        key,
		dir:        filepath.Join(s.hlsDir, key),
		playlist:   filepath.Join(s.hlsDir, key, hlsPlaylistName),
		lastAccess: time.Now(),
		done:       make(chan struct{}),
		running:    true,
	}
	s.hlsSessions[key] = sess
	s.hlsMu.Unlock()

	if err := s.startHlsFFmpeg(sess, path, enc); err != nil {
		return nil, err
	}
	s.startHlsReaper()
	return sess, nil
}

// startHlsFFmpeg spawns the transcode and blocks until the playlist is
// visible or the session fails. All cleanup (dir removal on failure, map
// eviction) happens in the waiter goroutine so kill paths cannot race.
func (s *StreamingService) startHlsFFmpeg(sess *hlsSession, path string, enc resolvedEncoder) error {
	safePath, err := sanitizeMediaArg(path)
	if err != nil {
		s.dropHlsSession(sess)
		return err
	}
	if err := os.MkdirAll(sess.dir, 0o755); err != nil {
		s.dropHlsSession(sess)
		return err
	}

	args := []string{"-y", "-i", safePath}
	args = append(args, "-vcodec", enc.Name)
	args = append(args, knownEncoderArgs[enc.Name]...)
	args = append(args,
		"-acodec", "aac",
		"-f", "hls",
		"-hls_time", "4",
		"-hls_list_size", "0",
		"-hls_flags", "independent_segments",
		"-hls_segment_filename", filepath.Join(sess.dir, "seg%05d.ts"),
		sess.playlist,
	)

	// Occupy the shared transcode slot for the lifetime of ffmpeg; queued
	// acquisition is not used here (session creation is already deduped and
	// reaper-bounded, and blocking GetOrCreate on the slot would stall the
	// HTTP handler past its own timeouts).
	release, acquired := s.acquireTranscodeSlot(context.Background())
	if !acquired {
		os.RemoveAll(sess.dir)
		s.dropHlsSession(sess)
		return fmt.Errorf("transcode capacity unavailable")
	}

	ctx, cancel := context.WithCancel(context.Background())
	cmd := exec.CommandContext(ctx, ffmpegBin, args...)
	cmd.Cancel = func() error {
		if cmd.Process != nil {
			return cmd.Process.Kill()
		}
		return os.ErrProcessDone
	}
	if err := cmd.Start(); err != nil {
		cancel()
		release()
		os.RemoveAll(sess.dir)
		s.dropHlsSession(sess)
		return err
	}
	sess.cancel = cancel
	slog.Info("hls transcode session started", "key", sess.key, "encoder", enc.Name, "file", filepath.Base(path))

	go func() {
		defer close(sess.done)
		waitErr := cmd.Wait()
		cancel()
		release()
		sess.mu.Lock()
		sess.running = false
		sess.err = waitErr
		sess.mu.Unlock()
		if waitErr != nil {
			// Any failed transcode is dropped entirely: a truncated playlist
			// that ends mid-episode is a worse trap than a clean restart.
			os.RemoveAll(sess.dir)
			s.dropHlsSession(sess)
			slog.Warn("hls transcode session failed, dropped", "key", sess.key, "error", waitErr)
			return
		}
		sess.mu.Lock()
		sess.size = hlsDirSize(sess.dir)
		sess.mu.Unlock()
		slog.Info("hls transcode session complete", "key", sess.key, "bytes", sess.size)
	}()

	// Wait (bounded) for the first playlist + segment so the initial client
	// request can be served synchronously.
	deadline := time.Now().Add(hlsPlaylistWait)
	for time.Now().Before(deadline) {
		if hlsSessionUsable(sess.dir) {
			return nil
		}
		select {
		case <-sess.done:
			// Short sources can finish before the poll notices the playlist:
			// a clean exit with a usable artifact is SUCCESS, not failure.
			if sess.Err() == nil && hlsSessionUsable(sess.dir) {
				return nil
			}
			return fmt.Errorf("transcode failed: %v", sess.Err())
		case <-time.After(200 * time.Millisecond):
		}
	}
	// Still no playlist after the deadline: kill and drop. The waiter
	// goroutine performs the actual cleanup once cmd.Wait returns.
	cancel()
	<-sess.done
	return fmt.Errorf("transcode session timed out waiting for playlist")
}

// dropHlsSession removes a session from the registry (idempotent).
func (s *StreamingService) dropHlsSession(sess *hlsSession) {
	s.hlsMu.Lock()
	delete(s.hlsSessions, sess.key)
	s.hlsMu.Unlock()
}

func hlsSessionUsable(dir string) bool {
	if _, err := os.Stat(filepath.Join(dir, hlsPlaylistName)); err != nil {
		return false
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return false
	}
	for _, e := range entries {
		if validHlsSegmentName(e.Name()) {
			return true
		}
	}
	return false
}

func hlsDirSize(dir string) int64 {
	var total int64
	filepath.WalkDir(dir, func(_ string, d os.DirEntry, err error) error {
		if err == nil && !d.IsDir() {
			if info, statErr := d.Info(); statErr == nil {
				total += info.Size()
			}
		}
		return nil
	})
	return total
}

// startHlsReaper lazily starts the single background reaper loop.
func (s *StreamingService) startHlsReaper() {
	s.hlsReaperOnce.Do(func() {
		go func() {
			ticker := time.NewTicker(time.Minute)
			defer ticker.Stop()
			for {
				select {
				case <-s.hlsStop:
					return
				case <-ticker.C:
					s.reapHlsSessions()
				}
			}
		}()
	})
}

// reapHlsSessions kills idle running sessions (client gone mid-transcode:
// delete entirely per spec — incomplete sessions never become cache) and
// evicts oldest completed sessions past the disk cap.
func (s *StreamingService) reapHlsSessions() {
	now := time.Now()
	s.hlsMu.Lock()
	defer s.hlsMu.Unlock()

	var completed []*hlsSession
	var total int64
	for _, sess := range s.hlsSessions {
		if sess.Running() {
			if now.Sub(sess.LastAccess()) > hlsIdleTimeout && sess.cancel != nil {
				slog.Warn("hls transcode session idle, killing", "key", sess.key)
				sess.cancel() // waiter goroutine drops the session + dir
			}
			continue
		}
		completed = append(completed, sess)
		total += sess.size
	}
	if total <= s.hlsDiskCap {
		return
	}
	sort.Slice(completed, func(i, j int) bool {
		return completed[i].LastAccess().Before(completed[j].LastAccess())
	})
	for _, sess := range completed {
		if total <= s.hlsDiskCap {
			break
		}
		total -= sess.size
		os.RemoveAll(sess.dir)
		delete(s.hlsSessions, sess.key)
		slog.Info("hls cache session evicted", "key", sess.key, "bytes", sess.size)
	}
}

// CloseHLS stops the reaper and kills every running session; completed
// sessions stay on disk as cache for the next process. Wired into
// Server.Stop.
func (s *StreamingService) CloseHLS() {
	select {
	case <-s.hlsStop:
		return // already closed
	default:
		close(s.hlsStop)
	}
	s.hlsMu.Lock()
	defer s.hlsMu.Unlock()
	for _, sess := range s.hlsSessions {
		if sess.Running() && sess.cancel != nil {
			sess.cancel()
		}
	}
}

// HlsStatus reports session counts for the admin status endpoint.
func (s *StreamingService) HlsStatus() (running, total int) {
	s.hlsMu.Lock()
	defer s.hlsMu.Unlock()
	total = len(s.hlsSessions)
	for _, sess := range s.hlsSessions {
		if sess.Running() {
			running++
		}
	}
	return running, total
}
