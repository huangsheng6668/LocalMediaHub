package service

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestHlsSessionKeyStability(t *testing.T) {
	mt := time.Date(2026, 9, 3, 10, 0, 0, 0, time.UTC)
	k1 := hlsSessionKey(`D:\Media\a.mp4`, mt, "h264_nvenc")
	k2 := hlsSessionKey(`D:\Media\a.mp4`, mt, "h264_nvenc")
	assert.Equal(t, k1, k2)
	assert.Len(t, k1, 16)
	assert.NotEqual(t, k1, hlsSessionKey(`D:\Media\a.mp4`, mt.Add(time.Second), "h264_nvenc"), "modtime change must rotate the key")
	assert.NotEqual(t, k1, hlsSessionKey(`D:\Media\b.mp4`, mt, "h264_nvenc"))
	assert.NotEqual(t, k1, hlsSessionKey(`D:\Media\a.mp4`, mt, "libx264"))
}

func TestValidHlsSegmentName(t *testing.T) {
	valid := []string{"seg00000.ts", "seg00001.ts", "seg99999.ts"}
	invalid := []string{
		"", "seg1.ts", "seg000001.ts", "SEG00001.TS", "seg00001.ts.exe",
		"../seg00001.ts", "..\\seg00001.ts", "/abs/seg00001.ts", "seg00001.ts\n",
		"index.m3u8", "segs/seg00001.ts", "seg0000a.ts",
	}
	for _, v := range valid {
		assert.True(t, validHlsSegmentName(v), v)
	}
	for _, v := range invalid {
		assert.False(t, validHlsSegmentName(v), v)
	}
}

// TestHlsGetOrCreateRealFFmpeg covers the full happy path with real ffmpeg:
// session dedup, playlist + segment production, natural completion, and the
// registry keeping the finished session as cache. Skipped when ffmpeg is
// not on PATH (CI).
func TestHlsGetOrCreateRealFFmpeg(t *testing.T) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg not on PATH")
	}
	tmp := t.TempDir()
	src := filepath.Join(tmp, "in.mp4")
	gen := exec.Command("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
		"-f", "lavfi", "-i", "testsrc=duration=2:size=320x240:rate=10",
		"-f", "lavfi", "-i", "sine=frequency=440:duration=2",
		"-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", "-shortest", src)
	if out, err := gen.CombinedOutput(); err != nil {
		t.Skipf("cannot generate test video: %v %s", err, out)
	}
	fi, err := os.Stat(src)
	require.NoError(t, err)

	s := NewStreamingService("", []string{"h264_nvenc", "h264_qsv", "h264_amf"}, -1)
	s.hlsDir = filepath.Join(tmp, "hls")
	defer s.CloseHLS()

	sess1, err := s.GetOrCreateHlsSession(src, fi.ModTime())
	require.NoError(t, err)

	// Dedup: a second call returns the same session (no new ffmpeg).
	sess2, err := s.GetOrCreateHlsSession(src, fi.ModTime())
	require.NoError(t, err)
	assert.Same(t, sess1, sess2)

	// Wait for natural completion (2s video transcodes within seconds).
	select {
	case <-sess1.done:
	case <-time.After(90 * time.Second):
		t.Fatal("transcode did not finish within 90s")
	}
	require.NoError(t, sess1.Err())

	data, err := os.ReadFile(sess1.playlist)
	require.NoError(t, err)
	assert.Contains(t, string(data), "#EXTM3U")
	segPath, ok := sess1.SegmentPath("seg00000.ts")
	require.True(t, ok)
	segData, err := os.ReadFile(segPath)
	require.NoError(t, err)
	assert.NotEmpty(t, segData)

	// Completed session stays registered as disk cache.
	running, total := s.HlsStatus()
	assert.Equal(t, 0, running)
	assert.Equal(t, 1, total)
}

func TestHlsReaperKillsIdleAndEvictsOverCap(t *testing.T) {
	s := NewStreamingService("", nil, -1)
	tmp := t.TempDir()
	s.hlsDir = filepath.Join(tmp, "hls")
	s.hlsDiskCap = 1 // force eviction of every completed session

	ctx, cancel := context.WithCancel(context.Background())
	idle := &hlsSession{
		key: "idle", dir: filepath.Join(s.hlsDir, "idle"),
		lastAccess: time.Now().Add(-10 * time.Minute),
		running:    true, cancel: cancel, done: make(chan struct{}),
	}
	s.hlsSessions["idle"] = idle

	done := filepath.Join(s.hlsDir, "done")
	require.NoError(t, os.MkdirAll(done, 0o755))
	require.NoError(t, os.WriteFile(filepath.Join(done, "seg00000.ts"), make([]byte, 4096), 0o644))
	completed := &hlsSession{
		key: "done", dir: done,
		lastAccess: time.Now().Add(-time.Hour),
		running:    false, size: 4096, done: make(chan struct{}),
	}
	close(completed.done)
	s.hlsSessions["done"] = completed

	s.reapHlsSessions()

	select {
	case <-ctx.Done():
	default:
		t.Fatal("idle running session was not cancelled")
	}
	// The synthetic idle session has no waiter goroutine, so it stays in the
	// map (in production the waiter drops it after cancel); the over-cap
	// completed session must be evicted from map AND disk.
	_, total := s.HlsStatus()
	assert.Equal(t, 1, total)
	assert.NoDirExists(t, done)
}
