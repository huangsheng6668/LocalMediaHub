package handler

import (
	"fmt"
	"testing"
	"time"

	"github.com/stretchr/testify/require"

	"github.com/localmediahub/server/internal/models"
	"github.com/localmediahub/server/internal/service"
)

func testDirModTime() time.Time {
	return time.Date(2026, 10, 30, 12, 0, 0, 0, time.UTC)
}

func TestBrowseCacheHitWithinTTL(t *testing.T) {
	c := newBrowseCache()
	dirKey := "c:\\media\\a"
	key := browseCacheKey(dirKey, "name", "asc")
	folders := []models.Folder{{Name: "sub"}}
	files := []models.MediaFile{{Name: "a.mp4"}}

	now := time.Now()
	c.put(key, dirKey, testDirModTime(), now, folders, files)

	got, ok := c.get(key, dirKey, testDirModTime(), now.Add(time.Second))
	require.True(t, ok)
	require.Len(t, got.folders, 1)
	require.Len(t, got.files, 1)
	require.Equal(t, "a.mp4", got.files[0].Name)
}

func TestBrowseCacheMissOnDirModTimeChange(t *testing.T) {
	c := newBrowseCache()
	dirKey := "c:\\media\\a"
	key := browseCacheKey(dirKey, "name", "asc")
	now := time.Now()
	c.put(key, dirKey, testDirModTime(), now, nil, []models.MediaFile{{Name: "a.mp4"}})

	_, ok := c.get(key, dirKey, testDirModTime().Add(time.Second), now)
	require.False(t, ok)
	require.Equal(t, 0, c.len())
}

func TestBrowseCacheExpiresAfterTTL(t *testing.T) {
	c := newBrowseCache()
	dirKey := "c:\\media\\a"
	key := browseCacheKey(dirKey, "name", "asc")
	now := time.Now()
	c.put(key, dirKey, testDirModTime(), now, nil, nil)

	_, ok := c.get(key, dirKey, testDirModTime(), now.Add(browseCacheTTL))
	require.False(t, ok)
	require.Equal(t, 0, c.len())
}

func TestBrowseCacheEvictsOldestDir(t *testing.T) {
	c := newBrowseCache()
	now := time.Now()
	for i := 0; i < browseCacheMaxDirs+1; i++ {
		dirKey := fmt.Sprintf("c:\\media\\dir%d", i)
		c.put(browseCacheKey(dirKey, "name", "asc"), dirKey, testDirModTime(), now, nil, nil)
	}

	require.Equal(t, browseCacheMaxDirs, c.len())
	_, ok := c.get(browseCacheKey("c:\\media\\dir0", "name", "asc"), "c:\\media\\dir0", testDirModTime(), now)
	require.False(t, ok, "oldest entry should have been evicted")
}

func TestBrowseCacheEvictsWhenFileBudgetExceeded(t *testing.T) {
	c := newBrowseCache()
	now := time.Now()
	bigFiles := make([]models.MediaFile, browseCacheMaxFiles)
	dirA := "c:\\media\\big"
	c.put(browseCacheKey(dirA, "name", "asc"), dirA, testDirModTime(), now, nil, bigFiles)

	smallFiles := make([]models.MediaFile, 10)
	dirB := "c:\\media\\small"
	c.put(browseCacheKey(dirB, "name", "asc"), dirB, testDirModTime(), now, nil, smallFiles)

	require.Equal(t, 1, c.len())
	_, ok := c.get(browseCacheKey(dirA, "name", "asc"), dirA, testDirModTime(), now)
	require.False(t, ok, "entry exceeding the file budget should be evicted first")
}

func TestBrowseCacheInvalidateDirRemovesAllSortVariants(t *testing.T) {
	c := newBrowseCache()
	dirKey := "c:\\media\\a"
	now := time.Now()
	c.put(browseCacheKey(dirKey, "name", "asc"), dirKey, testDirModTime(), now, nil, nil)
	c.put(browseCacheKey(dirKey, "size", "desc"), dirKey, testDirModTime(), now, nil, nil)

	c.InvalidateDir("C:\\Media\\A")

	require.Equal(t, 0, c.len())
}

func TestBrowseCacheInvalidateTreeRemovesDescendantsOnly(t *testing.T) {
	c := newBrowseCache()
	now := time.Now()
	for _, dir := range []string{
		`C:\media`,
		`C:\media\a`,
		`C:\media\a\deep`,
		`C:\mediab`,
		`C:\other`,
	} {
		dirKey := service.PathKey(dir)
		c.put(browseCacheKey(dirKey, "name", "asc"), dirKey, testDirModTime(), now, nil, nil)
	}

	c.InvalidateTree(`C:\media`)

	require.Equal(t, 2, c.len())
	for _, dir := range []string{`C:\mediab`, `C:\other`} {
		dirKey := service.PathKey(dir)
		_, ok := c.get(browseCacheKey(dirKey, "name", "asc"), dirKey, testDirModTime(), now)
		require.True(t, ok, "sibling %q must survive InvalidateTree", dir)
	}
}

func TestBrowseCacheNilReceiverIsSafe(t *testing.T) {
	var c *browseCache
	_, ok := c.get("k", "d", testDirModTime(), time.Now())
	require.False(t, ok)
	c.put("k", "d", testDirModTime(), time.Now(), nil, nil)
	c.InvalidateDir("d")
	c.InvalidateTree("d")
	require.Equal(t, 0, c.len())
	listing, err := c.load("k", func() (browseListing, error) {
		return browseListing{files: []models.MediaFile{{Name: "x"}}}, nil
	})
	require.NoError(t, err)
	require.Len(t, listing.files, 1)
}
