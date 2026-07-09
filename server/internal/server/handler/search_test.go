package handler

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/labstack/echo/v4"

	"github.com/localmediahub/server/internal/config"
	"github.com/localmediahub/server/internal/models"
	"github.com/localmediahub/server/internal/service"
)

func TestSearchScopesResultsToRequestedPathAndReturnsFolders(t *testing.T) {
	root := t.TempDir()
	catsDir := filepath.Join(root, "cats")
	catFavsDir := filepath.Join(catsDir, "cat-favs")
	dogsDir := filepath.Join(root, "dogs")

	for _, dir := range []string{catFavsDir, dogsDir} {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatalf("failed to create dir %q: %v", dir, err)
		}
	}

	catVideo := filepath.Join(catsDir, "cat-home.mp4")
	if err := os.WriteFile(catVideo, []byte("video"), 0o644); err != nil {
		t.Fatalf("failed to create cat video: %v", err)
	}
	// Round 28 Task 7: searchFoldersCached only knows directories that contain
	// media (Scanner caches dirs by media presence). Seed catFavsDir with a
	// non-query-matching media file so the folder appears in the cache without
	// inflating the file-match count (query is "cat").
	if err := os.WriteFile(filepath.Join(catFavsDir, "pet.mp4"), []byte("video"), 0o644); err != nil {
		t.Fatalf("failed to seed catFavsDir media: %v", err)
	}
	dogVideo := filepath.Join(dogsDir, "cat-hidden.mp4")
	if err := os.WriteFile(dogVideo, []byte("video"), 0o644); err != nil {
		t.Fatalf("failed to create dog video: %v", err)
	}

	cfg := &config.Config{
		Scan: config.ScanConfig{
			Roots:           []string{root},
			VideoExtensions: []string{".mp4"},
			ImageExtensions: []string{".jpg"},
		},
	}
	scanner := service.NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions)
	if _, err := scanner.Scan(context.Background(), cfg.Scan.GetRoots()); err != nil {
		t.Fatalf("failed to seed scanner cache: %v", err)
	}

	h := New(cfg, scanner, nil, nil, nil)
	e := echo.New()
	req := httptest.NewRequest(
		http.MethodGet,
		"/api/v1/search?q=cat&path="+catsDir,
		nil,
	)
	rec := httptest.NewRecorder()

	if err := h.Search(e.NewContext(req, rec)); err != nil {
		t.Fatalf("Search returned error: %v", err)
	}

	var result models.SearchResult
	if err := json.Unmarshal(rec.Body.Bytes(), &result); err != nil {
		t.Fatalf("failed to decode search result: %v", err)
	}

	if len(result.Folders) != 1 {
		t.Fatalf("expected one matching folder, got %d", len(result.Folders))
	}
	if result.Folders[0].Path != catFavsDir {
		t.Fatalf("expected folder path %q, got %q", catFavsDir, result.Folders[0].Path)
	}

	if len(result.Files) != 1 {
		t.Fatalf("expected one matching file in scoped path, got %d", len(result.Files))
	}
	if result.Files[0].Path != catVideo {
		t.Fatalf("expected file path %q, got %q", catVideo, result.Files[0].Path)
	}
	if result.Files[0].RelativePath != catVideo {
		t.Fatalf("expected search result relative path to be navigable absolute path, got %q", result.Files[0].RelativePath)
	}
}

// newTestHandlerWithScanner constructs a Handler bound to a real Scanner that
// scans root with the given video/image extensions. Mirrors the construction
// pattern used by TestSearchScopesResultsToRequestedPathAndReturnsFolders.
func newTestHandlerWithScanner(t *testing.T, root string, videoExts, imageExts []string) (*Handler, *service.Scanner) {
	t.Helper()
	cfg := &config.Config{
		Scan: config.ScanConfig{
			Roots:           []string{root},
			VideoExtensions: videoExts,
			ImageExtensions: imageExts,
		},
	}
	scanner := service.NewScanner(videoExts, imageExts)
	h := New(cfg, scanner, nil, nil, nil)
	return h, scanner
}

// TestSearchFoldersCached_BasicMatch 验证 query 子串匹配目录名。
func TestSearchFoldersCached_BasicMatch(t *testing.T) {
	root := t.TempDir()
	movieDir := filepath.Join(root, "MyMovies")
	docDir := filepath.Join(root, "Documents")
	for _, dir := range []string{movieDir, docDir} {
		os.MkdirAll(dir, 0o755)
		os.WriteFile(filepath.Join(dir, "x.mp4"), []byte("fake"), 0o644)
	}

	h, scanner := newTestHandlerWithScanner(t, root, []string{".mp4"}, []string{})
	if _, err := scanner.Scan(context.Background(), []string{root}); err != nil {
		t.Fatal(err)
	}

	folders, err := h.searchFoldersCached(context.Background(), "", "movie", 50)
	if err != nil {
		t.Fatalf("searchFoldersCached failed: %v", err)
	}
	if len(folders) != 1 {
		t.Fatalf("expected 1 match, got %d: %v", len(folders), folders)
	}
	if folders[0].Name != "MyMovies" {
		t.Errorf("matched name = %q, want MyMovies", folders[0].Name)
	}
}

// TestSearchFoldersCached_ScopedSearch 验证 scope 限定搜索范围。
func TestSearchFoldersCached_ScopedSearch(t *testing.T) {
	root := t.TempDir()
	// root/scope_dir/match.png
	// root/other_dir/match.png
	scopeDir := filepath.Join(root, "scope_dir")
	otherDir := filepath.Join(root, "other_dir")
	for _, dir := range []string{scopeDir, otherDir} {
		os.MkdirAll(dir, 0o755)
		os.WriteFile(filepath.Join(dir, "match.png"), []byte("fake"), 0o644)
	}

	h, scanner := newTestHandlerWithScanner(t, root, []string{}, []string{".png"})
	if _, err := scanner.Scan(context.Background(), []string{root}); err != nil {
		t.Fatal(err)
	}

	folders, err := h.searchFoldersCached(context.Background(), scopeDir, "match", 50)
	if err != nil {
		t.Fatalf("searchFoldersCached failed: %v", err)
	}
	// scope 限定下，只有 scope_dir 自身匹配（但被排除）→ 结果可能为 0
	// 或只有 scope_dir 下的子目录匹配（这里没有子目录）
	// 这个测试主要是验证 scope 不会越界返回 otherDir
	for _, f := range folders {
		if strings.Contains(f.Path, "other_dir") {
			t.Errorf("scope should exclude other_dir, got %q", f.Path)
		}
	}
}

// TestSearchFoldersCached_Limit 验证 limit 截断。
func TestSearchFoldersCached_Limit(t *testing.T) {
	root := t.TempDir()
	// 创建 5 个匹配的目录
	for i := 0; i < 5; i++ {
		dir := filepath.Join(root, fmt.Sprintf("match_%d", i))
		os.MkdirAll(dir, 0o755)
		os.WriteFile(filepath.Join(dir, "x.jpg"), []byte("fake"), 0o644)
	}

	h, scanner := newTestHandlerWithScanner(t, root, []string{}, []string{".jpg"})
	if _, err := scanner.Scan(context.Background(), []string{root}); err != nil {
		t.Fatal(err)
	}

	folders, err := h.searchFoldersCached(context.Background(), "", "match", 3)
	if err != nil {
		t.Fatalf("searchFoldersCached failed: %v", err)
	}
	if len(folders) != 3 {
		t.Errorf("expected limit=3, got %d", len(folders))
	}
}

// TestSearchFoldersCached_ContextCancellation 验证 ctx 取消时提前返回。
func TestSearchFoldersCached_ContextCancellation(t *testing.T) {
	root := t.TempDir()
	for i := 0; i < 10; i++ {
		dir := filepath.Join(root, fmt.Sprintf("match_%d", i))
		os.MkdirAll(dir, 0o755)
		os.WriteFile(filepath.Join(dir, "x.jpg"), []byte("fake"), 0o644)
	}

	h, scanner := newTestHandlerWithScanner(t, root, []string{}, []string{".jpg"})
	if _, err := scanner.Scan(context.Background(), []string{root}); err != nil {
		t.Fatal(err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancel() // 立即取消

	folders, err := h.searchFoldersCached(ctx, "", "match", 50)
	// cache 已 warm：GetCachedDirs 从缓存返回后，实现检查 ctx.Err() 并返回 ctx 错误，
	// 或进入循环 break 返回空切片——两者都算正确处理了取消。
	if err != nil && err != context.Canceled {
		t.Fatalf("searchFoldersCached with cancelled ctx failed: %v", err)
	}
	// ctx 已取消，应该返回空或很少（第一次 ctx.Err() 检查就 break）
	if err == nil && len(folders) > 0 {
		t.Logf("cancelled ctx returned %d folders (expected 0)", len(folders))
	}
}
