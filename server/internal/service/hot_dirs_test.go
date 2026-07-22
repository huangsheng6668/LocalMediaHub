package service

import (
	"path/filepath"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// TestHotDirRecordAndTop verifies basic Record + Top semantics:
// recording /a x3, /b x1, /c x1 yields Top(2) = {/a, /b} (c excluded).
//
// 注意：Record 内部会 filepath.Clean 输入，在 Windows 上 "/a" 会被规范化为 "\a"，
// 因此断言使用 filepath.Clean 包装期望值，确保跨平台一致。
func TestHotDirRecordAndTop(t *testing.T) {
	cacheDir := t.TempDir()
	h := newHotDirTracker(cacheDir, 256)
	defer h.Shutdown()

	h.Record("/a")
	h.Record("/a")
	h.Record("/a")
	h.Record("/b")
	h.Record("/c")

	a := filepath.Clean("/a")
	b := filepath.Clean("/b")
	c := filepath.Clean("/c")

	top := h.Top(2)
	require.Len(t, top, 2, "Top(2) must return exactly 2 entries")
	assert.Contains(t, top, a, "/a (count 3) must be in top-2")
	assert.Contains(t, top, b, "/b (count 1, tied with /c but sorted ahead by key) must be in top-2")
	assert.NotContains(t, top, c, "/c must be excluded from top-2")
}

// TestHotDirEvictionWhenFull verifies that when the map exceeds maxLen, the
// entry with the oldest lastSeen is evicted. Uses small sleeps so the
// lastSeen timestamps are well-ordered despite millisecond resolution.
//
// 路径在 Windows 上会被 Clean 成反斜杠形式，断言统一用 filepath.Clean 包装。
func TestHotDirEvictionWhenFull(t *testing.T) {
	cacheDir := t.TempDir()
	h := newHotDirTracker(cacheDir, 3)
	defer h.Shutdown()

	h.Record("/old")
	smallSleep()
	h.Record("/mid")
	smallSleep()
	h.Record("/new")
	require.Equal(t, 3, mapLen(h), "precondition: exactly 3 entries before overflow")

	old := filepath.Clean("/old")
	mid := filepath.Clean("/mid")
	newk := filepath.Clean("/new")
	extra := filepath.Clean("/extra")

	// /extra overflows cap → /old (oldest lastSeen) evicted.
	h.Record("/extra")

	counts := snapshotCounts(h)
	assert.NotContains(t, counts, old, "/old must be evicted (oldest lastSeen)")
	assert.Contains(t, counts, mid, "/mid must remain")
	assert.Contains(t, counts, newk, "/new must remain")
	assert.Contains(t, counts, extra, "/extra must be present after overflow")
	assert.Equal(t, 3, len(counts), "post-overflow size must still equal maxLen")
}

// TestHotDirPersistAndReload verifies that state survives a Shutdown + restart
// cycle: records made on tracker1 are visible via Top() on tracker2 backed by
// the same cacheDir.
func TestHotDirPersistAndReload(t *testing.T) {
	cacheDir := t.TempDir()

	h1 := newHotDirTracker(cacheDir, 256)
	h1.Record("/hot1")
	h1.Record("/hot1")
	h1.Record("/hot2")
	h1.Record("/cold")
	// Shutdown triggers a synchronous persist.
	h1.Shutdown()

	hot1 := filepath.Clean("/hot1")
	hot2 := filepath.Clean("/hot2")
	cold := filepath.Clean("/cold")

	// New tracker reads the persisted file at construction.
	h2 := newHotDirTracker(cacheDir, 256)
	defer h2.Shutdown()

	// All three dirs should be tracked after reload.
	topAll := h2.Top(64)
	assert.Contains(t, topAll, hot1, "/hot1 must survive reload")
	assert.Contains(t, topAll, hot2, "/hot2 must survive reload")
	assert.Contains(t, topAll, cold, "/cold must survive reload")

	// And the top-1 must still be /hot1 (count 2 vs 1).
	top1 := h2.Top(1)
	require.Len(t, top1, 1, "Top(1) must return exactly 1 entry")
	assert.Contains(t, top1, hot1, "/hot1 must remain the highest-count dir after reload")
}

// TestHotDirRecordCleansPath verifies Record normalizes the path via
// filepath.Clean, so callers may pass either raw or cleaned forms without
// producing duplicate entries.
func TestHotDirRecordCleansPath(t *testing.T) {
	cacheDir := t.TempDir()
	h := newHotDirTracker(cacheDir, 256)
	defer h.Shutdown()

	h.Record("/foo/./bar")
	h.Record(filepath.Clean("/foo/./bar"))

	counts := snapshotCounts(h)
	require.Len(t, counts, 1, "both Record calls must collapse to one entry")
	assert.Equal(t, 2, counts[filepath.Clean("/foo/bar")], "count must reflect both calls after Clean")
}

// --- helpers ---

// smallSleep yields the scheduler and adds a small delay so successive
// lastSeen timestamps are strictly ordered even on platforms with coarse
// (millisecond) clock resolution. 5ms is well above Windows' default 15.6ms
// timer tick, so we sleep a bit more to be safe.
func smallSleep() {
	time.Sleep(20 * time.Millisecond)
}

// mapLen returns the current entry count under the lock.
func mapLen(h *hotDirTracker) int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.counts)
}

// snapshotCounts returns a copy of counts under the lock.
func snapshotCounts(h *hotDirTracker) map[string]int {
	h.mu.Lock()
	defer h.mu.Unlock()
	out := make(map[string]int, len(h.counts))
	for k, v := range h.counts {
		out[k] = v
	}
	return out
}
