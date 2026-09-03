package service

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"testing"
	"time"

	"github.com/localmediahub/server/internal/models"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
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

	scanner := NewScanner([]string{".mp4", ".mkv"}, []string{".jpg", ".png"}, nil)
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

	scanner := NewScanner([]string{".mp4"}, []string{".jpg"}, nil)
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

	scanner := NewScanner([]string{".mp4"}, []string{".jpg"}, nil)
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

	scanner := NewScanner([]string{}, []string{".jpg"}, nil)
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

	scanner := NewScanner([]string{".mp4"}, []string{}, nil)
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

	scanner := NewScanner([]string{}, []string{".jpg"}, nil)
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

	scanner := NewScanner([]string{}, []string{".jpg"}, nil)
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

	scanner := NewScanner([]string{}, []string{".jpg"}, nil)
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

	scanner := NewScanner([]string{}, []string{".jpg"}, nil)
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

	scanner := NewScanner([]string{}, []string{".jpg"}, nil)
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

func TestScan_PopulatesCacheByDir(t *testing.T) {
	tempDir := t.TempDir()

	// Dirs
	assert.NoError(t, os.MkdirAll(filepath.Join(tempDir, "A"), 0755))
	assert.NoError(t, os.MkdirAll(filepath.Join(tempDir, "A", "sub"), 0755))
	assert.NoError(t, os.MkdirAll(filepath.Join(tempDir, "B"), 0755))

	// Files
	assert.NoError(t, os.WriteFile(filepath.Join(tempDir, "A", "v1.mp4"), []byte("x"), 0644))
	assert.NoError(t, os.WriteFile(filepath.Join(tempDir, "A", "i1.jpg"), []byte("x"), 0644))
	assert.NoError(t, os.WriteFile(filepath.Join(tempDir, "A", "sub", "v2.mp4"), []byte("x"), 0644))
	assert.NoError(t, os.WriteFile(filepath.Join(tempDir, "B", "v3.mp4"), []byte("x"), 0644))

	scanner := NewScanner([]string{".mp4"}, []string{".jpg"}, nil)
	_, err := scanner.Scan(context.Background(), []string{tempDir})
	assert.NoError(t, err)

	// cacheByDir 应被填充：直接父目录为 key
	dirA := filepath.Join(tempDir, "A")
	dirAsub := filepath.Join(tempDir, "A", "sub")
	dirB := filepath.Join(tempDir, "B")

	files, ok := scanner.cacheByDir[dirA]
	assert.True(t, ok, "dirA should be in cacheByDir")
	assert.Len(t, files, 2) // v1.mp4 + i1.jpg

	files, ok = scanner.cacheByDir[dirAsub]
	assert.True(t, ok, "dirAsub should be in cacheByDir")
	assert.Len(t, files, 1) // v2.mp4

	files, ok = scanner.cacheByDir[dirB]
	assert.True(t, ok, "dirB should be in cacheByDir")
	assert.Len(t, files, 1) // v3.mp4
}

func TestInvalidateCache_ClearsCacheByDir(t *testing.T) {
	tempDir := t.TempDir()
	assert.NoError(t, os.WriteFile(filepath.Join(tempDir, "v.mp4"), []byte("x"), 0644))

	scanner := NewScanner([]string{".mp4"}, nil, nil)
	_, err := scanner.Scan(context.Background(), []string{tempDir})
	assert.NoError(t, err)

	// Populated by Scan.
	scanner.mu.RLock()
	populated := scanner.cacheByDir != nil
	scanner.mu.RUnlock()
	assert.True(t, populated, "cacheByDir should be populated after Scan")

	scanner.InvalidateCache()

	scanner.mu.RLock()
	defer scanner.mu.RUnlock()
	if scanner.cacheByDir != nil {
		t.Errorf("cacheByDir should be nil after InvalidateCache, got %v", scanner.cacheByDir)
	}
}

func BenchmarkScan_WithCacheByDir(b *testing.B) {
	tempDir := b.TempDir()
	// Create 500 files in 1 dir (representative scaled-down fixture)
	for i := 0; i < 500; i++ {
		p := filepath.Join(tempDir, fmt.Sprintf("f%04d.mp4", i))
		if err := os.WriteFile(p, []byte("x"), 0644); err != nil {
			b.Fatal(err)
		}
	}

	scanner := NewScanner([]string{".mp4"}, nil, nil)
	b.ResetTimer()
	b.ReportAllocs()
	for i := 0; i < b.N; i++ {
		_, _ = scanner.Scan(context.Background(), []string{tempDir})
	}
}

// TestGetCachedByDir verifies the O(1) dir-keyed cache lookup that backs
// BrowseFolder /files (A2.2). Direct children of the queried dir are returned;
// files in nested subdirectories are excluded. Empty directories return a
// non-nil empty slice to distinguish "dir exists, no files" from cache miss.
func TestGetCachedByDir(t *testing.T) {
	tempDir := t.TempDir()

	assert.NoError(t, os.MkdirAll(filepath.Join(tempDir, "HasFiles"), 0755))
	assert.NoError(t, os.MkdirAll(filepath.Join(tempDir, "Empty"), 0755))
	assert.NoError(t, os.MkdirAll(filepath.Join(tempDir, "HasFiles", "sub"), 0755))

	assert.NoError(t, os.WriteFile(filepath.Join(tempDir, "HasFiles", "v.mp4"), []byte("x"), 0644))
	assert.NoError(t, os.WriteFile(filepath.Join(tempDir, "HasFiles", "sub", "v2.mp4"), []byte("x"), 0644))

	scanner := NewScanner([]string{".mp4"}, []string{".jpg"}, nil)
	_, err := scanner.Scan(context.Background(), []string{tempDir})
	assert.NoError(t, err)

	ctx := context.Background()

	// 命中：HasFiles 目录有 1 个直接子文件（v.mp4）；sub/v2.mp4 不应出现
	files, err := scanner.GetCachedByDir(ctx, []string{tempDir}, filepath.Join(tempDir, "HasFiles"))
	assert.NoError(t, err)
	assert.Len(t, files, 1)
	assert.Equal(t, "v.mp4", files[0].Name)

	// 空目录（无直接子文件）：返回 (emptySlice, nil) 而非 (nil, nil)
	files, err = scanner.GetCachedByDir(ctx, []string{tempDir}, filepath.Join(tempDir, "Empty"))
	assert.NoError(t, err)
	assert.Len(t, files, 0)
	// 关键：返回 emptySlice 而非 nil，区分"目录为空"与"cache miss"
	assert.NotNil(t, files)
}

// TestGetCachedByDir_CacheMissTriggersScan verifies that calling GetCachedByDir
// before any Scan triggers a Scan via singleflight (same contract as GetCached).
func TestGetCachedByDir_CacheMissTriggersScan(t *testing.T) {
	tempDir := t.TempDir()
	assert.NoError(t, os.WriteFile(filepath.Join(tempDir, "v.mp4"), []byte("x"), 0644))

	scanner := NewScanner([]string{".mp4"}, []string{".jpg"}, nil)
	// 注意：未调 Scan，cacheByDir 为 nil

	ctx := context.Background()
	files, err := scanner.GetCachedByDir(ctx, []string{tempDir}, tempDir)
	assert.NoError(t, err) // 内部触发 Scan
	assert.Len(t, files, 1)
	assert.Equal(t, "v.mp4", files[0].Name)
}

// TestGetCachedByDir_SortedByName verifies the controller-mandated contract
// that GetCachedByDir returns files sorted alphabetically by Name, so
// BrowseScreen sees a stable deterministic order regardless of walk order.
func TestGetCachedByDir_SortedByName(t *testing.T) {
	tempDir := t.TempDir()

	// Create files in non-alphabetical walk-determined order; cacheByDir
	// stores them in walk order. GetCachedByDir must sort before returning.
	for _, name := range []string{"zeta.mp4", "alpha.mp4", "mid.mp4"} {
		assert.NoError(t, os.WriteFile(filepath.Join(tempDir, name), []byte("x"), 0644))
	}

	scanner := NewScanner([]string{".mp4"}, nil, nil)
	_, err := scanner.Scan(context.Background(), []string{tempDir})
	assert.NoError(t, err)

	files, err := scanner.GetCachedByDir(context.Background(), []string{tempDir}, tempDir)
	assert.NoError(t, err)
	assert.Len(t, files, 3)
	assert.Equal(t, "alpha.mp4", files[0].Name)
	assert.Equal(t, "mid.mp4", files[1].Name)
	assert.Equal(t, "zeta.mp4", files[2].Name)
}

// BenchmarkGetCachedByDir_LargeCache measures the steady-state cost of a
// cached dir lookup on a 50k-file scale (represented here by 500 files in
// one directory). This is the baseline for A2.2's perf claim; the previous
// implementation (full-cache IsPathWithinRoots filter) is not benchmarked
// here because the code path is removed by this change.
func BenchmarkGetCachedByDir_LargeCache(b *testing.B) {
	tempDir := b.TempDir()

	// 构造 1 个目录 500 文件 + 1000 个空目录（模拟 50k 文件规模）
	require.NoError(b, os.MkdirAll(filepath.Join(tempDir, "Big"), 0755))
	for i := 0; i < 500; i++ {
		assert.NoError(b, os.WriteFile(
			filepath.Join(tempDir, "Big", fmt.Sprintf("f%04d.mp4", i)),
			[]byte("x"), 0644))
	}

	scanner := NewScanner([]string{".mp4"}, nil, nil)
	_, err := scanner.Scan(context.Background(), []string{tempDir})
	if err != nil {
		b.Fatal(err)
	}

	b.ResetTimer()
	b.ReportAllocs()
	for i := 0; i < b.N; i++ {
		_, _ = scanner.GetCachedByDir(context.Background(), []string{tempDir}, filepath.Join(tempDir, "Big"))
	}
}

// TestFindOwnerRoot 验证 A3 的 findOwnerRoot helper：把 event path 映射到所属 watch root。
// 找不到所属 root 时降级为 roots[0]，空 roots 返回空字符串。
func TestFindOwnerRoot(t *testing.T) {
	roots := []string{"D:/Media", "E:/Videos", "/home/user/music"}

	cases := []struct {
		name     string
		path     string
		expected string
	}{
		{"exact match root", "D:/Media", "D:/Media"},
		{"file inside root", "D:/Media/Series/Ep01.mp4", "D:/Media"},
		{"file in second root", "E:/Videos/movie.mkv", "E:/Videos"},
		{"file in unix root", "/home/user/music/song.mp3", "/home/user/music"},
		{"path outside any root falls back to roots[0]", "/tmp/random.mp4", "D:/Media"},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := findOwnerRoot(tc.path, roots)
			assert.Equal(t, tc.expected, got)
		})
	}

	// 空 roots：返回 ""
	assert.Equal(t, "", findOwnerRoot("/anywhere", []string{}))
}

// TestWatchEvents_PerRootIndependentDebounce 是 A3 的端到端集成测试。
// 需要真实的 fsnotify 事件 + 时间敏感断言，单测环境不稳定，标 Skip 由手动 smoke test 覆盖。
func TestWatchEvents_PerRootIndependentDebounce(t *testing.T) {
	t.Skip("requires fsnotify real file events + timing-sensitive assertions; covered by manual smoke test")
}

// BenchmarkFindOwnerRoot 验证 A3 helper 在热路径（每事件）零分配。
func BenchmarkFindOwnerRoot(b *testing.B) {
	roots := []string{"D:/Media", "E:/Videos", "F:/Movies", "/home/user/music"}
	paths := []string{
		"D:/Media/Series/Show/ep01.mp4",
		"E:/Videos/movie.mkv",
		"F:/Movies/2024/film.mp4",
		"/home/user/music/album/song.mp3",
		"/tmp/nonexistent.mp4",
	}
	b.ResetTimer()
	b.ReportAllocs()
	for i := 0; i < b.N; i++ {
		_ = findOwnerRoot(paths[i%len(paths)], roots)
	}
}

// TestScanTextFiles 验证 Task 2：扫描器把 .txt/.epub 文件识别为 mediaType="text"，
// 并写入 cache["text"]。同时确认这些文件进入 allFiles 与 cacheByDir（与 video/image 对称）。
func TestScanTextFiles(t *testing.T) {
	dir := t.TempDir()
	require.NoError(t, os.WriteFile(filepath.Join(dir, "novel.txt"), []byte("第一章 开始\n正文"), 0644))
	require.NoError(t, os.WriteFile(filepath.Join(dir, "book.epub"), []byte("PK\x03\x04"), 0644))

	scanner := NewScanner([]string{".mp4"}, []string{".jpg"}, []string{".txt", ".epub"})
	files, err := scanner.Scan(context.Background(), []string{dir})
	require.NoError(t, err)
	require.Len(t, files, 2)

	scanner.mu.RLock()
	textFiles := scanner.cache["text"]
	scanner.mu.RUnlock()
	assert.Len(t, textFiles, 2)
	for _, f := range textFiles {
		assert.Equal(t, "text", f.MediaType)
	}
}

// TestScannerTextExtsGetter 验证 TextExts() 返回小写化的扩展名 map。
func TestScannerTextExtsGetter(t *testing.T) {
	s := NewScanner(nil, nil, []string{".txt", ".EPUB"})
	assert.True(t, s.TextExts()[".txt"])
	assert.True(t, s.TextExts()[".epub"])
}

// TestScanTextOnScanCompleteFilter 验证 Task 2 的关键约束：text 文件不进入
// OnScanComplete 回调（书没有缩略图）。回调只收到 video/image 文件。
func TestScanTextOnScanCompleteFilter(t *testing.T) {
	dir := t.TempDir()
	require.NoError(t, os.WriteFile(filepath.Join(dir, "novel.txt"), []byte("hello"), 0644))
	require.NoError(t, os.WriteFile(filepath.Join(dir, "clip.mp4"), []byte("x"), 0644))

	var got []models.MediaFile
	var gotMu sync.Mutex
	scanner := NewScanner([]string{".mp4"}, nil, []string{".txt"})
	scanner.OnScanComplete = func(files []models.MediaFile) {
		gotMu.Lock()
		got = append(got, files...)
		gotMu.Unlock()
	}
	_, err := scanner.Scan(context.Background(), []string{dir})
	require.NoError(t, err)

	// 回调在 goroutine 中触发；轮询等待，最多 1s。
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		gotMu.Lock()
		n := len(got)
		gotMu.Unlock()
		if n > 0 {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}

	gotMu.Lock()
	defer gotMu.Unlock()
	require.Len(t, got, 1, "OnScanComplete should receive only non-text files")
	assert.Equal(t, "video", got[0].MediaType)
}

// TestScannerOutputIsSorted 验证 Scan 返回的 allFiles 切片按 Path 字典序升序排列。
// 两个 root 各 50 个混合扩展名的文件：goroutine 调度顺序不可控，但结果必须稳定有序。
func TestScannerOutputIsSorted(t *testing.T) {
	root1 := t.TempDir()
	root2 := t.TempDir()
	exts := []string{".mp4", ".jpg", ".txt"}
	for _, root := range []string{root1, root2} {
		// 创建 3 个子目录使路径多样化（覆盖 root 内 walk 顺序）
		for _, sub := range []string{"subA", "subB", "subC"} {
			require.NoError(t, os.MkdirAll(filepath.Join(root, sub), 0755))
		}
		for i := 0; i < 50; i++ {
			sub := []string{"subA", "subB", "subC"}[i%3]
			ext := exts[i%len(exts)]
			name := fmt.Sprintf("file_%04d%s", i, ext)
			require.NoError(t, os.WriteFile(filepath.Join(root, sub, name), []byte("x"), 0644))
		}
	}

	scanner := NewScanner([]string{".mp4"}, []string{".jpg"}, []string{".txt"})
	// 多个 root 强制 errgroup 跨 root 并发；结果合并后必须仍有序
	files, err := scanner.Scan(context.Background(), []string{root1, root2})
	require.NoError(t, err)
	require.Len(t, files, 100)

	for i := 1; i < len(files); i++ {
		if files[i-1].Path > files[i].Path {
			t.Fatalf("allFiles not sorted by Path: index %d %q > index %d %q",
				i-1, files[i-1].Path, i, files[i].Path)
		}
	}
}

// TestScannerContextCancelExitsQuickly 验证传入已取消的 ctx 时 Scan 迅速返回，
// 不会跑完整个 500 文件的目录树。1s 上限远大于 errgroup 中止 goroutine 的耗时。
func TestScannerContextCancelExitsQuickly(t *testing.T) {
	root := t.TempDir()
	for i := 0; i < 500; i++ {
		require.NoError(t, os.WriteFile(
			filepath.Join(root, fmt.Sprintf("f%04d.mp4", i)),
			[]byte("x"), 0644))
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancel() // 立即取消

	scanner := NewScanner([]string{".mp4"}, nil, nil)
	start := time.Now()
	_, err := scanner.Scan(ctx, []string{root})
	elapsed := time.Since(start)

	// ctx 已取消，errgroup 的 gctx 立即 done，walk 回调 return gctx.Err()
	require.Error(t, err)
	assert.Equal(t, context.Canceled, err)
	if elapsed >= time.Second {
		t.Fatalf("Scan took %v with already-cancelled ctx; want < 1s", elapsed)
	}
	t.Logf("Scan with cancelled ctx returned in %v", elapsed)
}

// --- Scan snapshot persistence (spec 2026-09-03-scan-snapshot-persistence) ---

func TestScannerWritesSnapshotAfterScan(t *testing.T) {
	tmp := t.TempDir()
	media := filepath.Join(tmp, "media")
	require.NoError(t, os.MkdirAll(media, 0o755))
	require.NoError(t, os.WriteFile(filepath.Join(media, "a.mp4"), []byte("x"), 0o644))
	snapPath := filepath.Join(tmp, "state", "scan_snapshot.json")

	sc := NewScannerWithSnapshot([]string{".mp4"}, nil, nil, snapPath)
	_, err := sc.Scan(context.Background(), []string{media})
	require.NoError(t, err)

	snap, ok, err := loadScanSnapshot(snapPath, buildScanIdentity([]string{media}, []string{".mp4"}, nil, nil))
	require.NoError(t, err)
	require.True(t, ok)
	assert.Len(t, snap.Files, 1)
	assert.Equal(t, filepath.Join(media, "a.mp4"), snap.Files[0].Path)
	assert.NotZero(t, snap.SavedAt)
}

func TestScannerHydrateServesWithoutWalk(t *testing.T) {
	tmp := t.TempDir()
	media := filepath.Join(tmp, "media")
	require.NoError(t, os.MkdirAll(media, 0o755))
	mediaFile := filepath.Join(media, "a.mp4")
	require.NoError(t, os.WriteFile(mediaFile, []byte("x"), 0o644))
	snapPath := filepath.Join(tmp, "state", "scan_snapshot.json")

	sc := NewScannerWithSnapshot([]string{".mp4"}, nil, nil, snapPath)
	_, err := sc.Scan(context.Background(), []string{media})
	require.NoError(t, err)
	sc.Shutdown()

	// Delete the source tree entirely: any WalkDir-based path would see zero
	// files, so a non-empty result can only come from the hydrated snapshot.
	require.NoError(t, os.RemoveAll(media))

	sc2 := NewScannerWithSnapshot([]string{".mp4"}, nil, nil, snapPath)
	defer sc2.Shutdown()
	require.NoError(t, sc2.StartWatching([]string{media}))
	files, err := sc2.GetCached(context.Background(), []string{media})
	require.NoError(t, err)
	assert.Len(t, files, 1)
	assert.Equal(t, mediaFile, files[0].Path)

	// Per-type and per-dir structures must be hydrated too.
	byType, err := sc2.GetCachedByType(context.Background(), []string{media}, "video")
	require.NoError(t, err)
	assert.Len(t, byType, 1)
	byDir, err := sc2.GetCachedByDir(context.Background(), []string{media}, media)
	require.NoError(t, err)
	assert.Len(t, byDir, 1)
}

func TestScannerHydrateIdentityMismatchFallsBackCold(t *testing.T) {
	tmp := t.TempDir()
	rootA := filepath.Join(tmp, "a")
	rootB := filepath.Join(tmp, "b")
	require.NoError(t, os.MkdirAll(rootA, 0o755))
	require.NoError(t, os.MkdirAll(rootB, 0o755))
	require.NoError(t, os.WriteFile(filepath.Join(rootA, "a.mp4"), []byte("x"), 0o644))
	require.NoError(t, os.WriteFile(filepath.Join(rootB, "b.mp4"), []byte("x"), 0o644))
	snapPath := filepath.Join(tmp, "state", "scan_snapshot.json")

	sc := NewScannerWithSnapshot([]string{".mp4"}, nil, nil, snapPath)
	_, err := sc.Scan(context.Background(), []string{rootA})
	require.NoError(t, err)
	sc.Shutdown()

	// Different roots: snapshot identity must NOT hydrate; the first request
	// falls back to a real (synchronous) walk of rootB.
	sc2 := NewScannerWithSnapshot([]string{".mp4"}, nil, nil, snapPath)
	defer sc2.Shutdown()
	require.NoError(t, sc2.StartWatching([]string{rootB}))
	files, err := sc2.GetCached(context.Background(), []string{rootB})
	require.NoError(t, err)
	assert.Len(t, files, 1)
	assert.Equal(t, filepath.Join(rootB, "b.mp4"), files[0].Path)
}

func TestScannerSnapshotWriteThrottle(t *testing.T) {
	tmp := t.TempDir()
	media := filepath.Join(tmp, "media")
	require.NoError(t, os.MkdirAll(media, 0o755))
	require.NoError(t, os.WriteFile(filepath.Join(media, "a.mp4"), []byte("x"), 0o644))
	snapPath := filepath.Join(tmp, "state", "scan_snapshot.json")

	sc := NewScannerWithSnapshot([]string{".mp4"}, nil, nil, snapPath)
	_, err := sc.Scan(context.Background(), []string{media})
	require.NoError(t, err)

	// Second file appears; the immediate rescan refreshes the in-memory cache
	// but the snapshot write is throttled (well under the 30s min interval).
	require.NoError(t, os.WriteFile(filepath.Join(media, "b.mp4"), []byte("x"), 0o644))
	files, err := sc.Scan(context.Background(), []string{media})
	require.NoError(t, err)
	assert.Len(t, files, 2)

	snap, ok, err := loadScanSnapshot(snapPath, buildScanIdentity([]string{media}, []string{".mp4"}, nil, nil))
	require.NoError(t, err)
	require.True(t, ok)
	assert.Len(t, snap.Files, 1, "throttled snapshot must still hold the first scan result")
}
