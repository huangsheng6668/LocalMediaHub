package service

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/localmediahub/server/internal/models"
)

// Spec 2026-09-03-scan-snapshot-persistence: scan results are persisted to
// disk after each successful Scan and hydrated back into the in-memory
// cache at startup, so the first browse request after a restart no longer
// blocks on a full filesystem walk. Every failure mode here degrades to
// "snapshot not usable" and the scanner falls back to a normal sync scan.

// scanSnapshotVersion bumps when the on-disk format changes in a way old
// readers cannot honor; loaders reject unknown versions.
const scanSnapshotVersion = 1

// DefaultScanSnapshotPath is where server.New wires the scanner snapshot
// (same .data dir as the tags SQLite DB; relative to the server CWD).
const DefaultScanSnapshotPath = ".data/scan_snapshot.json"

// scanSnapshotFile is the persisted scan result. Files is the "all" cache
// list (already sorted by Path inside Scan); Dirs is cacheDirMap verbatim
// (directory mtimes cannot be derived from Files).
type scanSnapshotFile struct {
	Version   int                  `json:"version"`
	Roots     []string             `json:"roots"`
	VideoExts []string             `json:"video_exts"`
	ImageExts []string             `json:"image_exts"`
	TextExts  []string             `json:"text_exts"`
	SavedAt   time.Time            `json:"saved_at"`
	Files     []models.MediaFile   `json:"files"`
	Dirs      map[string]time.Time `json:"dirs"`
}

// scanIdentity is the set of configuration facts a snapshot must match to
// be usable: the scan roots and all three extension sets. Values are
// normalized (cleaned, lowercased, sorted, deduped) at build time so
// comparison is stable regardless of input ordering or separators.
type scanIdentity struct {
	roots     []string
	videoExts []string
	imageExts []string
	textExts  []string
}

func buildScanIdentity(roots, videoExts, imageExts, textExts []string) scanIdentity {
	return scanIdentity{
		roots:     normalizeIdentityList(roots),
		videoExts: normalizeIdentityList(videoExts),
		imageExts: normalizeIdentityList(imageExts),
		textExts:  normalizeIdentityList(textExts),
	}
}

func normalizeIdentityList(in []string) []string {
	out := make([]string, 0, len(in))
	for _, v := range in {
		out = append(out, strings.ToLower(filepath.Clean(v)))
	}
	sort.Strings(out)
	deduped := make([]string, 0, len(out))
	for i, v := range out {
		if i == 0 || v != out[i-1] {
			deduped = append(deduped, v)
		}
	}
	return deduped
}

func (id scanIdentity) sameAs(other scanIdentity) bool {
	return equalIdentityStrings(id.roots, other.roots) &&
		equalIdentityStrings(id.videoExts, other.videoExts) &&
		equalIdentityStrings(id.imageExts, other.imageExts) &&
		equalIdentityStrings(id.textExts, other.textExts)
}

func equalIdentityStrings(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

// identity returns the identity recorded inside a snapshot file.
func (snap *scanSnapshotFile) identity() scanIdentity {
	return buildScanIdentity(snap.Roots, snap.VideoExts, snap.ImageExts, snap.TextExts)
}

// saveScanSnapshot atomically writes the snapshot: marshal, temp file in
// the same dir, fsync, rename over the target. A crash mid-write cannot
// corrupt the previous snapshot (same pattern as config.Save; on Windows
// os.Rename uses MoveFileEx with REPLACE_EXISTING).
func saveScanSnapshot(path string, snap *scanSnapshotFile) error {
	data, err := json.Marshal(snap)
	if err != nil {
		return err
	}
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, ".scan-snapshot-*.json.tmp")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName) // no-op once rename succeeds
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmpName, path)
}

// loadScanSnapshot reads and validates a snapshot against the expected
// identity. Returns (snap, true, nil) when usable. ok=false with nil err
// means a benign skip (first boot: file absent; stale identity: roots or
// extensions changed). ok=false with err means corruption the caller may
// want to log. Callers fall back to a normal scan in every ok=false case.
func loadScanSnapshot(path string, want scanIdentity) (snap *scanSnapshotFile, ok bool, err error) {
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, false, nil
		}
		return nil, false, err
	}
	if err := json.Unmarshal(data, &snap); err != nil {
		return nil, false, err
	}
	if snap.Version != scanSnapshotVersion {
		return nil, false, fmt.Errorf("scan snapshot version %d != supported %d", snap.Version, scanSnapshotVersion)
	}
	if !snap.identity().sameAs(want) {
		return nil, false, nil
	}
	return snap, true, nil
}
