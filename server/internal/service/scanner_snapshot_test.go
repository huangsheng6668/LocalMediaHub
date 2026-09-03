package service

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/localmediahub/server/internal/models"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func testSnapshot() *scanSnapshotFile {
	return &scanSnapshotFile{
		Version:   scanSnapshotVersion,
		Roots:     []string{"D:/Media"},
		VideoExts: []string{".mp4", ".mkv"},
		ImageExts: []string{".jpg"},
		TextExts:  nil,
		SavedAt:   time.Date(2026, 9, 3, 12, 0, 0, 0, time.UTC),
		Files: []models.MediaFile{
			{Name: "a.mp4", Path: "D:/Media/a.mp4", Size: 10, MediaType: "video", Extension: ".mp4"},
			{Name: "b.jpg", Path: "D:/Media/sub/b.jpg", Size: 20, MediaType: "image", Extension: ".jpg"},
		},
		Dirs: map[string]time.Time{
			"D:/Media":     time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC),
			"D:/Media/sub": time.Date(2026, 9, 2, 0, 0, 0, 0, time.UTC),
		},
	}
}

func testIdentity() scanIdentity {
	return buildScanIdentity([]string{"D:/Media"}, []string{".mp4", ".mkv"}, []string{".jpg"}, nil)
}

func TestScanSnapshotRoundtrip(t *testing.T) {
	path := filepath.Join(t.TempDir(), "snap.json")
	orig := testSnapshot()
	require.NoError(t, saveScanSnapshot(path, orig))

	loaded, ok, err := loadScanSnapshot(path, testIdentity())
	require.NoError(t, err)
	require.True(t, ok)
	assert.Equal(t, orig.SavedAt, loaded.SavedAt)
	assert.Len(t, loaded.Files, 2)
	assert.Equal(t, "D:/Media/a.mp4", loaded.Files[0].Path)
	assert.Equal(t, orig.Dirs["D:/Media/sub"], loaded.Dirs["D:/Media/sub"])
}

func TestScanSnapshotMissingFileIsBenign(t *testing.T) {
	_, ok, err := loadScanSnapshot(filepath.Join(t.TempDir(), "absent.json"), testIdentity())
	assert.NoError(t, err)
	assert.False(t, ok)
}

func TestScanSnapshotCorruptFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "snap.json")
	require.NoError(t, os.WriteFile(path, []byte("{not json"), 0o644))
	_, ok, err := loadScanSnapshot(path, testIdentity())
	assert.Error(t, err)
	assert.False(t, ok)
}

func TestScanSnapshotUnknownVersionRejected(t *testing.T) {
	path := filepath.Join(t.TempDir(), "snap.json")
	require.NoError(t, os.WriteFile(path, []byte("{\"version\":99}"), 0o644))
	_, ok, err := loadScanSnapshot(path, testIdentity())
	assert.Error(t, err)
	assert.False(t, ok)
}

func TestScanSnapshotIdentityRootsMismatch(t *testing.T) {
	path := filepath.Join(t.TempDir(), "snap.json")
	require.NoError(t, saveScanSnapshot(path, testSnapshot()))
	other := buildScanIdentity([]string{"E:/Other"}, []string{".mp4", ".mkv"}, []string{".jpg"}, nil)
	_, ok, err := loadScanSnapshot(path, other)
	assert.NoError(t, err) // benign: config changed, snapshot simply not usable
	assert.False(t, ok)
}

func TestScanSnapshotIdentityExtsMismatch(t *testing.T) {
	path := filepath.Join(t.TempDir(), "snap.json")
	require.NoError(t, saveScanSnapshot(path, testSnapshot()))
	other := buildScanIdentity([]string{"D:/Media"}, []string{".mp4"}, []string{".jpg", ".png"}, nil)
	_, ok, err := loadScanSnapshot(path, other)
	assert.NoError(t, err)
	assert.False(t, ok)
}

func TestScanSnapshotIdentityNormalization(t *testing.T) {
	// Same logical config expressed with different order/case/separators.
	a := buildScanIdentity([]string{"D:/B", "D:/A"}, []string{".MP4", ".mkv"}, nil, nil)
	b := buildScanIdentity([]string{"d:\\a", "D:\\B\\"}, []string{".mkv", ".mp4"}, nil, nil)
	assert.True(t, a.sameAs(b))
}

func TestScanSnapshotSaveOverwritesAtomically(t *testing.T) {
	path := filepath.Join(t.TempDir(), "snap.json")
	first := testSnapshot()
	require.NoError(t, saveScanSnapshot(path, first))
	second := testSnapshot()
	second.Files = []models.MediaFile{{Name: "c.mp4", Path: "D:/Media/c.mp4", MediaType: "video", Extension: ".mp4"}}
	require.NoError(t, saveScanSnapshot(path, second))
	loaded, ok, err := loadScanSnapshot(path, testIdentity())
	require.NoError(t, err)
	require.True(t, ok)
	assert.Len(t, loaded.Files, 1)
	assert.Equal(t, "D:/Media/c.mp4", loaded.Files[0].Path)
}
