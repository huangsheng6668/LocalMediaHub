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
	k1 := hlsSessionKey(`D:\Media\a.mp4`, mt, "h264_nvenc", 0)
	k2 := hlsSessionKey(`D:\Media\a.mp4`, mt, "h264_nvenc", 0)
	assert.Equal(t, k1, k2)
	assert.Len(t, k1, 16)
	assert.NotEqual(t, k1, hlsSessionKey(`D:\Media\a.mp4`, mt.Add(time.Second), "h264_nvenc", 0), "modtime change must rotate the key")
	assert.NotEqual(t, k1, hlsSessionKey(`D:\Media\b.mp4`, mt, "h264_nvenc", 0))
	assert.NotEqual(t, k1, hlsSessionKey(`D:\Media\a.mp4`, mt, "libx264", 0))
	assert.NotEqual(t, k1, hlsSessionKey(`D:\Media\a.mp4`, mt, "h264_nvenc", 3600),
		"different seek anchor must rotate the key (spec 2026-09-06-hls-seek-restart)")
}

// TestBuildHlsArgsSeekAnchor pins the argv shape: a start anchor becomes an
// input-side -ss BEFORE -i (fast demuxer seek), no anchor omits it entirely.
func TestBuildHlsArgsSeekAnchor(t *testing.T) {
	enc := resolvedEncoder{Name: "libx264"}
	base := buildHlsArgs(`D:\m.mp4`, 0, enc, `C:\seg`, `C:\seg\index.m3u8`)
	assert.Equal(t, "-y", base[0])
	assert.Equal(t, "-i", base[1], "zero anchor must not insert -ss")

	anchored := buildHlsArgs(`D:\m.mp4`, 3600, enc, `C:\seg`, `C:\seg\index.m3u8`)
	assert.Equal(t, []string{"-y", "-ss", "3600", "-i", `D:\m.mp4`}, anchored[:5],
		"-ss must precede -i for input-side seeking")
	assert.Contains(t, base, "-hls_playlist_type")
	assert.Contains(t, anchored, "-hls_playlist_type")
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

// TestHlsClientPlaylistRewritesSegmentURIs pins the on-the-wire playlist
// contract: ffmpeg writes bare segment filenames, but the HTTP endpoint that
// serves segments requires path+name query params — and a relative URI would
// resolve against the playlist's own URL (/api/v1/media/hls/), 404ing every
// segment. ClientPlaylist must rewrite whitelisted segment lines to absolute
// endpoint paths (origin-relative, so both hls.js and ExoPlayer resolve them
// correctly) and leave every other line untouched.
func TestHlsClientPlaylistRewritesSegmentURIs(t *testing.T) {
	tmp := t.TempDir()
	dir := filepath.Join(tmp, "sess")
	require.NoError(t, os.MkdirAll(dir, 0o755))
	raw := "#EXTM3U\n" +
		"#EXT-X-VERSION:3\n" +
		"#EXT-X-TARGETDURATION:4\n" +
		"#EXTINF:4.000000,\n" +
		"seg00000.ts\n" +
		"#EXTINF:4.000000,\n" +
		"seg00001.ts\n" +
		"#EXT-X-ENDLIST\n"
	require.NoError(t, os.WriteFile(filepath.Join(dir, "index.m3u8"), []byte(raw), 0o644))

	sess := &hlsSession{dir: dir, playlist: filepath.Join(dir, "index.m3u8")}
	body, err := sess.ClientPlaylist(`D:\Media\My Movie (2026).mkv`, 0)
	require.NoError(t, err)

	out := string(body)
	assert.Contains(t, out, "/api/v1/media/hls/segment?path=D%3A%5CMedia%5CMy+Movie+%282026%29.mkv&name=seg00000.ts\n")
	assert.Contains(t, out, "/api/v1/media/hls/segment?path=D%3A%5CMedia%5CMy+Movie+%282026%29.mkv&name=seg00001.ts\n")
	assert.NotContains(t, out, "\nseg00000.ts\n", "bare segment line must not survive")
	assert.Contains(t, out, "#EXT-X-TARGETDURATION:4")
	assert.Contains(t, out, "#EXT-X-ENDLIST")

	// Anchored sessions carry &start= so the segment endpoint dedups onto
	// the same session (spec 2026-09-06-hls-seek-restart); zero anchors
	// omit it to keep URLs stable with pre-spec clients.
	anchored, err := sess.ClientPlaylist(`D:\Media\My Movie (2026).mkv`, 7200)
	require.NoError(t, err)
	assert.Contains(t, string(anchored), "&name=seg00000.ts&start=7200\n")
}

// TestCancelSiblingSessionsOnReanchor covers the scrub-protection contract:
// creating a session at a new anchor kills still-running sessions for the
// same source file, while completed cache sessions survive untouched.
func TestCancelSiblingSessionsOnReanchor(t *testing.T) {
	s := NewStreamingService("", nil, -1)
	s.hlsDir = t.TempDir()

	oldCtx, oldCancel := context.WithCancel(context.Background())
	old := &hlsSession{
		key: "old", dir: filepath.Join(s.hlsDir, "old"),
		srcPath: `D:\Media\a.mp4`, startSec: 0,
		lastAccess: time.Now(), running: true,
		cancel: oldCancel, done: make(chan struct{}),
	}
	s.hlsSessions["old"] = old

	doneCtx, doneCancel := context.WithCancel(context.Background())
	cached := &hlsSession{
		key: "cached", dir: filepath.Join(s.hlsDir, "cached"),
		srcPath: `D:\Media\a.mp4`, startSec: 0,
		lastAccess: time.Now().Add(-time.Hour), running: false, size: 4096,
		cancel: doneCancel, done: make(chan struct{}),
	}
	close(cached.done)
	s.hlsSessions["cached"] = cached

	otherCtx, otherCancel := context.WithCancel(context.Background())
	otherFile := &hlsSession{
		key: "other", dir: filepath.Join(s.hlsDir, "other"),
		srcPath: `D:\Media\b.mp4`, startSec: 0,
		lastAccess: time.Now(), running: true,
		cancel: otherCancel, done: make(chan struct{}),
	}
	s.hlsSessions["other"] = otherFile

	fresh := &hlsSession{key: "fresh", srcPath: `D:\Media\a.mp4`, startSec: 3600, running: true}
	s.cancelSiblingSessions(fresh)

	select {
	case <-oldCtx.Done():
	default:
		t.Fatal("running sibling for the same source was not cancelled")
	}
	select {
	case <-doneCtx.Done():
		t.Fatal("completed cache session must NOT be cancelled")
	default:
	}
	select {
	case <-otherCtx.Done():
		t.Fatal("running session for a different source must NOT be cancelled")
	default:
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

	sess1, err := s.GetOrCreateHlsSession(src, fi.ModTime(), 0)
	require.NoError(t, err)

	// Dedup: a second call returns the same session (no new ffmpeg).
	sess2, err := s.GetOrCreateHlsSession(src, fi.ModTime(), 0)
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
	assert.Contains(t, string(data), "#EXT-X-PLAYLIST-TYPE:EVENT")
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
