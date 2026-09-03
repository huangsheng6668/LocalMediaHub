# Scan Snapshot Persistence Implementation Plan

> **For agentic workers:** Implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Spec: `docs/superpowers/specs/2026-09-03-scan-snapshot-persistence-design.md`.

**Goal:** Persist scan results to `.data/scan_snapshot.json` after each successful Scan and hydrate the in-memory cache at boot, so the first browse request after a restart no longer blocks on a full filesystem walk.

**Architecture:** New `scanner_snapshot.go` (atomic save / identity-checked load). `Scanner` gains an optional snapshot path; `Scan` writes at the tail (30s min-interval), `StartWatching` hydrates with `cacheTime = SavedAt` so the existing stale-while-revalidate path handles corrections.

**Tech Stack:** Go 1.25 stdlib (`encoding/json`, `os`, `path/filepath`, `sync`), testify (existing scanner_test.go conventions).

## Global Constraints

- Snapshot identity = roots + all three extension sets (sorted, cleaned, exact equality).
- Hydrate must NOT fire `OnScanComplete` (thumbnail disk cache persists across restarts).
- Every failure mode degrades silently to today behavior (sync scan on first request).
- Existing `NewScanner` signature stays (test call sites untouched); production uses `NewScannerWithSnapshot`.
- Tests: `cd server && go test ./...` green after every task.

---

### Task 1: snapshot module
**Files:** Create `server/internal/service/scanner_snapshot.go`, `server/internal/service/scanner_snapshot_test.go`
**Interfaces:** `saveScanSnapshot(path string, snap *scanSnapshotFile) error` (atomic); `loadScanSnapshot(path string, identity scanIdentity) (*scanSnapshotFile, bool)`; `scanIdentity{roots, videoExts, imageExts, textExts}` + `sameAs` / build helpers
- [ ] failing tests: roundtrip, corrupt JSON, roots/exts/version mismatch, missing file
- [ ] implement + green

Commit: `feat(scanner): scan snapshot save/load with identity validation`

### Task 2: scanner integration
**Files:** Modify `server/internal/service/scanner.go`, `server/internal/service/scanner_test.go`
**Interfaces:** `NewScannerWithSnapshot(video, image, text []string, snapshotPath string)`; Scan tail write (30s min interval, `lastSnapWrite` under mu); `StartWatching` tail hydrate (rebuild all cache structures, `cacheTime = SavedAt`, no callback)
- [ ] failing tests: Scan→file exists; hydrate→GetCached serves after source dir deleted (zero-walk proof); TTL semantics via SavedAt; 30s write debounce
- [ ] implement + green

Commit: `feat(scanner): hydrate scan cache from snapshot on startup`

### Task 3: wiring + docs
**Files:** Modify `server/internal/server/server.go`; `docs/INDEX.md`; `AGENTS.md`
- [ ] server.go: `NewScannerWithSnapshot(..., filepath.Join(".data", "scan_snapshot.json"))`
- [ ] INDEX key-files bullet + AGENTS scanner.go bullet update
- [ ] full `go test ./...` + commit

Commit: `feat(scanner): wire scan snapshot persistence in server startup` + `docs(scanner): ...`
