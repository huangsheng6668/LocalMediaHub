package service

import (
	"context"
	"os"
	"path/filepath"
	"sort"
	"testing"
	"time"

	"github.com/localmediahub/server/internal/models"
	"github.com/stretchr/testify/assert"
)

func TestScanner(t *testing.T) {
	tempDir := t.TempDir()
	
	// Create subdirs and media files
	err := os.MkdirAll(filepath.Join(tempDir, "FolderA"), 0755)
	assert.NoError(t, err)
	err = os.MkdirAll(filepath.Join(tempDir, "FolderB"), 0755)
	assert.NoError(t, err)
	
	err = os.WriteFile(filepath.Join(tempDir, "FolderA", "video1.mp4"), []byte("dummy"), 0644)
	assert.NoError(t, err)
	err = os.WriteFile(filepath.Join(tempDir, "FolderA", "image1.jpg"), []byte("dummy"), 0644)
	assert.NoError(t, err)
	err = os.WriteFile(filepath.Join(tempDir, "FolderB", "document.txt"), []byte("dummy"), 0644)
	assert.NoError(t, err)
	err = os.WriteFile(filepath.Join(tempDir, "video2.mkv"), []byte("dummy"), 0644)
	assert.NoError(t, err)
	
	scanner := NewScanner([]string{".mp4", ".mkv"}, []string{".jpg", ".png"})
	assert.NotNil(t, scanner)
	
	// Check configured extensions
	assert.True(t, scanner.VideoExts()[".mp4"])
	assert.True(t, scanner.VideoExts()[".mkv"])
	assert.False(t, scanner.VideoExts()[".avi"])
	assert.True(t, scanner.ImageExts()[".jpg"])
	assert.False(t, scanner.ImageExts()[".txt"])
	
	// Scan
	ctx := context.Background()
	files, err := scanner.Scan(ctx, []string{tempDir})
	assert.NoError(t, err)
	
	// Expecting 3 media files (video1.mp4, image1.jpg, video2.mkv). document.txt is ignored.
	assert.Len(t, files, 3)
	
	var mp4Count, jpgCount, mkvCount int
	for _, f := range files {
		switch f.Extension {
		case ".mp4":
			mp4Count++
			assert.Equal(t, "video", f.MediaType)
		case ".jpg":
			jpgCount++
			assert.Equal(t, "image", f.MediaType)
		case ".mkv":
			mkvCount++
			assert.Equal(t, "video", f.MediaType)
		}
	}
	assert.Equal(t, 1, mp4Count)
	assert.Equal(t, 1, jpgCount)
	assert.Equal(t, 1, mkvCount)
	
	// Test caching
	cachedFiles, err := scanner.GetCached(ctx, []string{tempDir})
	assert.NoError(t, err)
	assert.Len(t, cachedFiles, 3)
	
	// Test TriggerScan and callback
	callbackCalled := make(chan bool, 1)
	scanner.OnScanComplete = func(completeFiles []models.MediaFile) {
		assert.Len(t, completeFiles, 3)
		callbackCalled <- true
	}
	scanner.TriggerScan([]string{tempDir})
	
	select {
	case <-callbackCalled:
		// Success
	case <-time.After(2 * time.Second):
		t.Fatal("Scan complete callback was not triggered in time")
	}
	
	// Test cancellation
	cancelCtx, cancelFunc := context.WithCancel(context.Background())
	cancelFunc() // Cancel immediately
	
	_, err = scanner.Scan(cancelCtx, []string{tempDir})
	// Should return context.Canceled error
	assert.Error(t, err)
	assert.Equal(t, context.Canceled, err)
	
	// Test shutdown
	scanner.Shutdown()
}

func TestScanCachesPerType(t *testing.T) {
	tempDir := t.TempDir()
	assert.NoError(t, os.WriteFile(filepath.Join(tempDir, "v.mp4"), []byte("v"), 0644))
	assert.NoError(t, os.WriteFile(filepath.Join(tempDir, "i.jpg"), []byte("i"), 0644))
	assert.NoError(t, os.WriteFile(filepath.Join(tempDir, "skip.txt"), []byte("x"), 0644))

	scanner := NewScanner([]string{".mp4"}, []string{".jpg"})
	files, err := scanner.Scan(context.Background(), []string{tempDir})
	assert.NoError(t, err)
	assert.Len(t, files, 2)

	// Scan 应按类型分流缓存
	assert.Len(t, scanner.cache["video"], 1)
	assert.Len(t, scanner.cache["image"], 1)

	// GetCachedByType 返回对应子集（缓存新鲜）
	vids, err := scanner.GetCachedByType(context.Background(), []string{tempDir}, "video")
	assert.NoError(t, err)
	assert.Len(t, vids, 1)
	assert.Equal(t, "video", vids[0].MediaType)

	imgs, err := scanner.GetCachedByType(context.Background(), []string{tempDir}, "image")
	assert.NoError(t, err)
	assert.Len(t, imgs, 1)
	assert.Equal(t, "image", imgs[0].MediaType)
}

// TestScan_PopulatesCacheDirs 验证 Scan 后 cacheDirs 包含媒体文件父目录，
// 不含空目录（scanner 只在有媒体文件时收集父目录）。
func TestScan_PopulatesCacheDirs(t *testing.T) {
	root := t.TempDir()
	// 构造目录树：
	//   root/
	//     subdir_with_media/
	//       video.mp4    <- 媒体文件，subdir_with_media 应被收集
	//     empty_subdir/  <- 空目录，不应被收集
	//     top.jpg        <- root 下的媒体文件，root 自身不应被收集（边界）
	subDir := filepath.Join(root, "subdir_with_media")
	emptyDir := filepath.Join(root, "empty_subdir")
	if err := os.MkdirAll(subDir, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(emptyDir, 0755); err != nil {
		t.Fatal(err)
	}
	// 创建空 mp4 和 jpg（scanner 不读内容，只看扩展名 + Stat）
	if err := os.WriteFile(filepath.Join(subDir, "video.mp4"), []byte("fake"), 0644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "top.jpg"), []byte("fake"), 0644); err != nil {
		t.Fatal(err)
	}

	scanner := NewScanner([]string{".mp4"}, []string{".jpg"})
	if _, err := scanner.Scan(context.Background(), []string{root}); err != nil {
		t.Fatalf("Scan failed: %v", err)
	}

	scanner.mu.RLock()
	dirs := scanner.cacheDirs
	scanner.mu.RUnlock()

	cleanRoot := filepath.Clean(root)
	for _, d := range dirs {
		if d == cleanRoot {
			t.Errorf("root itself should not be in cacheDirs, got %q", d)
		}
		if d == emptyDir {
			t.Errorf("empty dir should not be in cacheDirs, got %q", d)
		}
	}

	found := false
	for _, d := range dirs {
		if d == subDir {
			found = true
			break
		}
	}
	if !found {
		t.Errorf("subDir %q not found in cacheDirs: %v", subDir, dirs)
	}
}

// TestScan_CacheDirsSorted 验证 cacheDirs 按字典序排序。
func TestScan_CacheDirsSorted(t *testing.T) {
	root := t.TempDir()
	// 创建多个子目录使排序可验证
	for _, name := range []string{"z_dir", "a_dir", "m_dir"} {
		dir := filepath.Join(root, name)
		if err := os.MkdirAll(dir, 0755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, "x.jpg"), []byte("fake"), 0644); err != nil {
			t.Fatal(err)
		}
	}

	scanner := NewScanner([]string{}, []string{".jpg"})
	if _, err := scanner.Scan(context.Background(), []string{root}); err != nil {
		t.Fatalf("Scan failed: %v", err)
	}

	scanner.mu.RLock()
	dirs := scanner.cacheDirs
	scanner.mu.RUnlock()

	if !sort.StringsAreSorted(dirs) {
		t.Errorf("cacheDirs not sorted: %v", dirs)
	}
}

// TestScan_CollectsAncestorDirs 验证递归收集祖先目录：
// 多层嵌套的中间目录（自身无媒体文件，但子目录有）也应被收集。
func TestScan_CollectsAncestorDirs(t *testing.T) {
	root := t.TempDir()
	// root/parent/child/video.mp4
	// parent 自身无媒体文件，但应被收集（祖孙关系）
	parent := filepath.Join(root, "parent")
	child := filepath.Join(parent, "child")
	if err := os.MkdirAll(child, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(child, "video.mp4"), []byte("fake"), 0644); err != nil {
		t.Fatal(err)
	}

	scanner := NewScanner([]string{".mp4"}, []string{})
	if _, err := scanner.Scan(context.Background(), []string{root}); err != nil {
		t.Fatalf("Scan failed: %v", err)
	}

	scanner.mu.RLock()
	dirs := scanner.cacheDirs
	scanner.mu.RUnlock()

	foundParent := false
	foundChild := false
	for _, d := range dirs {
		if d == parent {
			foundParent = true
		}
		if d == child {
			foundChild = true
		}
	}
	if !foundParent {
		t.Errorf("ancestor parent dir %q not collected: %v", parent, dirs)
	}
	if !foundChild {
		t.Errorf("direct parent child dir %q not collected: %v", child, dirs)
	}
}

// TestGetCachedDirs_ReturnsAllOnEmptyScope 验证 scope="" 返回全部目录。
func TestGetCachedDirs_ReturnsAllOnEmptyScope(t *testing.T) {
	root := t.TempDir()
	for _, name := range []string{"dir_a", "dir_b"} {
		dir := filepath.Join(root, name)
		os.MkdirAll(dir, 0755)
		os.WriteFile(filepath.Join(dir, "x.jpg"), []byte("fake"), 0644)
	}

	scanner := NewScanner([]string{}, []string{".jpg"})
	if _, err := scanner.Scan(context.Background(), []string{root}); err != nil {
		t.Fatal(err)
	}

	dirs, _, err := scanner.GetCachedDirs(context.Background(), []string{root}, "")
	if err != nil {
		t.Fatalf("GetCachedDirs failed: %v", err)
	}
	if len(dirs) != 2 {
		t.Errorf("scope=\"\" returned %d dirs, want 2: %v", len(dirs), dirs)
	}
}

// TestGetCachedDirs_ScopeFilter 验证 scope 前缀过滤。
func TestGetCachedDirs_ScopeFilter(t *testing.T) {
	root := t.TempDir()
	dirA := filepath.Join(root, "dir_a")
	dirB := filepath.Join(root, "dir_b")
	for _, dir := range []string{dirA, dirB} {
		os.MkdirAll(dir, 0755)
		os.WriteFile(filepath.Join(dir, "x.jpg"), []byte("fake"), 0644)
	}

	scanner := NewScanner([]string{}, []string{".jpg"})
	if _, err := scanner.Scan(context.Background(), []string{root}); err != nil {
		t.Fatal(err)
	}

	dirs, _, err := scanner.GetCachedDirs(context.Background(), []string{root}, dirA)
	if err != nil {
		t.Fatalf("GetCachedDirs failed: %v", err)
	}
	if len(dirs) != 1 {
		t.Errorf("scope=%q returned %d dirs, want 1: %v", dirA, len(dirs), dirs)
	}
}

// TestGetCachedDirs_ExcludesScopeRoot 验证 scope 根自身不在结果内。
func TestGetCachedDirs_ExcludesScopeRoot(t *testing.T) {
	root := t.TempDir()
	sub := filepath.Join(root, "sub")
	os.MkdirAll(sub, 0755)
	os.WriteFile(filepath.Join(sub, "x.jpg"), []byte("fake"), 0644)

	scanner := NewScanner([]string{}, []string{".jpg"})
	if _, err := scanner.Scan(context.Background(), []string{root}); err != nil {
		t.Fatal(err)
	}

	// scope = root 自身（root 下有 sub，sub 下有媒体）
	// 注意：root 自身不会被收集（边界），但 sub 应在结果内
	dirs, _, err := scanner.GetCachedDirs(context.Background(), []string{root}, root)
	if err != nil {
		t.Fatalf("GetCachedDirs failed: %v", err)
	}
	cleanRoot := filepath.Clean(root)
	for _, d := range dirs {
		if d == cleanRoot {
			t.Errorf("scope root itself should not be in result, got %q", d)
		}
	}
	if len(dirs) == 0 {
		t.Errorf("expected sub dir in result, got empty")
	}
}

// TestGetCachedDirs_MtimesPopulated 验证返回的 mtimes map 含每个目录的 mtime。
func TestGetCachedDirs_MtimesPopulated(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "dir_a")
	os.MkdirAll(dir, 0755)
	os.WriteFile(filepath.Join(dir, "x.jpg"), []byte("fake"), 0644)

	scanner := NewScanner([]string{}, []string{".jpg"})
	if _, err := scanner.Scan(context.Background(), []string{root}); err != nil {
		t.Fatal(err)
	}

	dirs, mtimes, err := scanner.GetCachedDirs(context.Background(), []string{root}, "")
	if err != nil {
		t.Fatalf("GetCachedDirs failed: %v", err)
	}
	if len(mtimes) != len(dirs) {
		t.Errorf("mtimes len = %d, dirs len = %d, should match", len(mtimes), len(dirs))
	}
	for _, d := range dirs {
		if _, ok := mtimes[d]; !ok {
			t.Errorf("mtimes missing entry for %q", d)
		}
	}
}

// TestInvalidateCache_ClearsCacheDirs 验证 InvalidateCache 清空 cacheDirs。
func TestInvalidateCache_ClearsCacheDirs(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "dir_a")
	os.MkdirAll(dir, 0755)
	os.WriteFile(filepath.Join(dir, "x.jpg"), []byte("fake"), 0644)

	scanner := NewScanner([]string{}, []string{".jpg"})
	if _, err := scanner.Scan(context.Background(), []string{root}); err != nil {
		t.Fatal(err)
	}

	scanner.InvalidateCache()

	scanner.mu.RLock()
	defer scanner.mu.RUnlock()
	if scanner.cacheDirs != nil {
		t.Errorf("cacheDirs should be nil after InvalidateCache, got %v", scanner.cacheDirs)
	}
	if scanner.cacheDirMap != nil {
		t.Errorf("cacheDirMap should be nil after InvalidateCache, got %v", scanner.cacheDirMap)
	}
}
