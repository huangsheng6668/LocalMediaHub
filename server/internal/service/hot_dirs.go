package service

import (
	"encoding/json"
	"log/slog"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

// hotDirTracker tracks per-directory access counts in an in-memory map with an
// LRU-style cap based on lastSeen timestamps. It persists its state to
// hot_directories.json every 5 minutes and on Shutdown, and loads that file on
// construction as a seed.
//
// This is distinct from ThumbnailService.hotTracker: hotTracker tracks the last
// 200 *file paths* requested interactively, while hotDirTracker aggregates
// access counts per *directory* (cap 256). The aggregation level differs
// because PreGenerateThumbnails now reasons about directory tiers rather than
// individual files.
type hotDirTracker struct {
	mu       sync.Mutex
	counts   map[string]int
	lastSeen map[string]time.Time
	maxLen   int

	cacheDir string
	stopCh   chan struct{}
	doneCh   chan struct{}
}

// hotDirEntry is the JSON record persisted per directory.
type hotDirEntry struct {
	Count    int       `json:"count"`
	LastSeen time.Time `json:"lastSeen"`
}

// newHotDirTracker creates a tracker capped at maxLen directories. It seeds
// itself from cacheDir/hot_directories.json if present (corrupt JSON is logged
// and ignored — the file is left in place so an operator can inspect it). The
// returned tracker has a flushLoop already running; call Shutdown to stop it
// and force a final persist.
func newHotDirTracker(cacheDir string, maxLen int) *hotDirTracker {
	if maxLen < 1 {
		maxLen = 1
	}
	h := &hotDirTracker{
		counts:   make(map[string]int),
		lastSeen: make(map[string]time.Time),
		maxLen:   maxLen,
		cacheDir: cacheDir,
		stopCh:   make(chan struct{}),
		doneCh:   make(chan struct{}),
	}
	h.loadFromDisk()
	go h.flushLoop()
	return h
}

// Record increments the access count for dirPath and updates its lastSeen. If
// the map exceeds maxLen after the update, the entry with the oldest lastSeen
// is evicted. dirPath is filepath.Cleaned so callers can pass either raw or
// cleaned paths.
func (h *hotDirTracker) Record(dirPath string) {
	dirPath = filepath.Clean(dirPath)
	now := time.Now()

	h.mu.Lock()
	defer h.mu.Unlock()

	h.counts[dirPath]++
	h.lastSeen[dirPath] = now

	if len(h.counts) > h.maxLen {
		// Evict the entry with the oldest lastSeen. Linear scan is fine at
		// this scale (cap 256); an ordered structure would only pay off at
		// much larger sizes.
		var oldestKey string
		var oldestSeen time.Time
		first := true
		for k, ts := range h.lastSeen {
			if first || ts.Before(oldestSeen) {
				oldestKey = k
				oldestSeen = ts
				first = false
			}
		}
		delete(h.counts, oldestKey)
		delete(h.lastSeen, oldestKey)
	}
}

// Top returns a set of the top-n directories by access count. If n <= 0 or
// n >= len(counts), all tracked directories are returned. The returned map is
// a fresh copy; callers may mutate it freely.
func (h *hotDirTracker) Top(n int) map[string]struct{} {
	h.mu.Lock()
	defer h.mu.Unlock()

	if n <= 0 || n >= len(h.counts) {
		out := make(map[string]struct{}, len(h.counts))
		for k := range h.counts {
			out[k] = struct{}{}
		}
		return out
	}

	type kv struct {
		key   string
		count int
	}
	entries := make([]kv, 0, len(h.counts))
	for k, c := range h.counts {
		entries = append(entries, kv{k, c})
	}
	// Sort descending by count; ties broken by key for determinism.
	sort.Slice(entries, func(i, j int) bool {
		if entries[i].count != entries[j].count {
			return entries[i].count > entries[j].count
		}
		return entries[i].key < entries[j].key
	})

	out := make(map[string]struct{}, n)
	for i := 0; i < n && i < len(entries); i++ {
		out[entries[i].key] = struct{}{}
	}
	return out
}

// Shutdown stops the flushLoop and performs a final synchronous persist. Safe
// to call multiple times — the second and subsequent calls are no-ops (stopCh
// is already closed, doneCh already closed).
func (h *hotDirTracker) Shutdown() {
	select {
	case <-h.stopCh:
		// Already shut down; nothing to do.
		return
	default:
		close(h.stopCh)
	}
	<-h.doneCh
}

// flushLoop persists state every 5 minutes until stopCh is closed, at which
// point it does one final persist and signals doneCh.
func (h *hotDirTracker) flushLoop() {
	defer close(h.doneCh)
	ticker := time.NewTicker(5 * time.Minute)
	defer ticker.Stop()
	for {
		select {
		case <-h.stopCh:
			h.persist()
			return
		case <-ticker.C:
			h.persist()
		}
	}
}

// persist writes the current state to hot_directories.json atomically (temp
// file + rename), mirroring the style used by encodeThumbnailToCache and
// persistDurationCache. Marshal happens under the lock; disk I/O happens
// outside the lock.
func (h *hotDirTracker) persist() {
	h.mu.Lock()
	if len(h.counts) == 0 {
		h.mu.Unlock()
		return
	}
	records := make(map[string]hotDirEntry, len(h.counts))
	for k, c := range h.counts {
		records[k] = hotDirEntry{Count: c, LastSeen: h.lastSeen[k]}
	}
	h.mu.Unlock()

	data, err := json.Marshal(records)
	if err != nil {
		slog.Warn("Failed to marshal hot_directories.json", "error", err)
		return
	}

	target := filepath.Join(h.cacheDir, "hot_directories.json")
	tmp, err := os.CreateTemp(h.cacheDir, "hot-dirs-tmp-*.json")
	if err != nil {
		slog.Warn("Failed to create temp file for hot_directories.json", "error", err)
		return
	}
	tmpPath := tmp.Name()
	defer os.Remove(tmpPath) // no-op on success path (file was renamed)

	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		slog.Warn("Failed to write hot_directories.json", "error", err)
		return
	}
	if err := tmp.Close(); err != nil {
		slog.Warn("Failed to close temp hot_directories.json", "error", err)
		return
	}
	if err := os.Rename(tmpPath, target); err != nil {
		slog.Warn("Failed to rename hot_directories.json", "error", err)
		return
	}
}

// loadFromDisk seeds counts/lastSeen from cacheDir/hot_directories.json. A
// missing file is the normal first-start case (silent). A corrupt file is
// logged and ignored — the file is NOT deleted so an operator can inspect it,
// mirroring loadDurationCache's behavior.
func (h *hotDirTracker) loadFromDisk() {
	data, err := os.ReadFile(filepath.Join(h.cacheDir, "hot_directories.json"))
	if err != nil {
		// Missing file: nothing to seed, this is normal on first start.
		return
	}
	var records map[string]hotDirEntry
	if err := json.Unmarshal(data, &records); err != nil {
		slog.Warn("Failed to unmarshal hot_directories.json, starting empty", "error", err)
		return
	}
	for k, e := range records {
		// Skip zero-count entries defensively; they add no priority signal.
		if e.Count <= 0 {
			continue
		}
		h.counts[k] = e.Count
		// Preserve original lastSeen so eviction order survives a restart.
		seen := e.LastSeen
		if seen.IsZero() {
			seen = time.Now()
		}
		h.lastSeen[k] = seen
	}
	// If the loaded set exceeds maxLen (e.g. maxLen was lowered), trim to the
	// most-recently-seen entries.
	for len(h.counts) > h.maxLen {
		var oldestKey string
		var oldestSeen time.Time
		first := true
		for k, ts := range h.lastSeen {
			if first || ts.Before(oldestSeen) {
				oldestKey = k
				oldestSeen = ts
				first = false
			}
		}
		delete(h.counts, oldestKey)
		delete(h.lastSeen, oldestKey)
	}
}
