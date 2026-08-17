package service

import (
	"fmt"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
)

func TestTagsService(t *testing.T) {
	tempDir := t.TempDir()
	
	// 1. Create a new service
	svc, err := NewTagsService(tempDir)
	assert.NoError(t, err)
	assert.NotNil(t, svc)
	defer svc.Close()
	
	// Should be empty initially
	tags := svc.GetAllTags()
	assert.Len(t, tags, 0)
	
	// 2. Create tags
	t1, err := svc.CreateTag("Movie", "#FF0000")
	assert.NoError(t, err)
	assert.Equal(t, "Movie", t1.Name)
	assert.Equal(t, "#FF0000", t1.Color)
	assert.NotEmpty(t, t1.ID)
	
	t2, err := svc.CreateTag("Anime", "#00FF00")
	assert.NoError(t, err)
	
	// Duplicate name check
	_, err = svc.CreateTag("Movie", "#0000FF")
	assert.Error(t, err)
	
	tags = svc.GetAllTags()
	assert.Len(t, tags, 2)
	
	// 3. Associations
	filePath := "videos/test.mp4"
	_, err = svc.AssociateFile(t1.ID, filePath)
	assert.NoError(t, err)
	
	fileTagsMap := svc.GetTagsForFiles([]string{filePath})
	assert.Len(t, fileTagsMap[filePath], 1)
	assert.Equal(t, t1.ID, fileTagsMap[filePath][0].ID)
	
	// Duplicate association check
	_, err = svc.AssociateFile(t1.ID, filePath)
	assert.NoError(t, err) // Should handle gracefully
	assert.Len(t, svc.GetTagsForFiles([]string{filePath})[filePath], 1)
	
	// Add another tag to the same file
	_, err = svc.AssociateFile(t2.ID, filePath)
	assert.NoError(t, err)
	assert.Len(t, svc.GetTagsForFiles([]string{filePath})[filePath], 2)
	
	// Get associated files
	files := svc.GetFilesForTag(t1.ID)
	assert.Len(t, files, 1)
	assert.Equal(t, filePath, files[0])
	
	// Untag file
	err = svc.DisassociateFile(t2.ID, filePath)
	assert.NoError(t, err)
	assert.Len(t, svc.GetTagsForFiles([]string{filePath})[filePath], 1)
	
	// Delete tag
	err = svc.DeleteTag(t1.ID)
	assert.NoError(t, err)
	assert.Len(t, svc.GetAllTags(), 1)
	assert.Len(t, svc.GetTagsForFiles([]string{filePath})[filePath], 0)
	
	// Verify persistence (reload service)
	svcReloaded, err := NewTagsService(tempDir)
	assert.NoError(t, err)
	defer svcReloaded.Close()
	assert.Len(t, svcReloaded.GetAllTags(), 1)
	assert.Equal(t, t2.ID, svcReloaded.GetAllTags()[0].ID)
}

func TestTagsService_PragmasApplied(t *testing.T) {
	tempDir := t.TempDir()
	svc, err := NewTagsService(tempDir)
	assert.NoError(t, err)
	defer svc.Close()

	// journal_mode 应为 WAL
	var mode string
	err = svc.db.QueryRow("PRAGMA journal_mode").Scan(&mode)
	assert.NoError(t, err)
	assert.Equal(t, "wal", strings.ToLower(mode))

	// synchronous 应为 NORMAL (1)
	var sync int
	err = svc.db.QueryRow("PRAGMA synchronous").Scan(&sync)
	assert.NoError(t, err)
	assert.Equal(t, 1, sync)

	// foreign_keys 应为 ON (1)
	var fk int
	err = svc.db.QueryRow("PRAGMA foreign_keys").Scan(&fk)
	assert.NoError(t, err)
	assert.Equal(t, 1, fk)
}

func TestTagsService_IndexesCreated(t *testing.T) {
	tempDir := t.TempDir()
	svc, err := NewTagsService(tempDir)
	assert.NoError(t, err)
	defer svc.Close()

	// 查 sqlite_master 确认 3 个索引存在
	expectedIndexes := []string{
		"idx_associations_tag_id",
		"idx_associations_file_path",
		"idx_tags_name_lower",
	}
	rows, err := svc.db.Query("SELECT name FROM sqlite_master WHERE type='index' AND name LIKE 'idx_%'")
	assert.NoError(t, err)
	defer rows.Close()

	got := make(map[string]bool)
	for rows.Next() {
		var name string
		assert.NoError(t, rows.Scan(&name))
		got[name] = true
	}
	for _, idx := range expectedIndexes {
		assert.True(t, got[idx], "missing index: %s", idx)
	}
}

func BenchmarkTagsService_GetFilesForTag(b *testing.B) {
	tempDir := b.TempDir()
	svc, err := NewTagsService(tempDir)
	if err != nil {
		b.Fatal(err)
	}
	defer svc.Close()

	// 1 tag with 5000 associations to verify idx_associations_tag_id benefit
	tag, err := svc.CreateTag("Bench", "#FF0000")
	if err != nil {
		b.Fatal(err)
	}
	for i := 0; i < 5000; i++ {
		_, err := svc.AssociateFile(tag.ID, fmt.Sprintf("dir/file_%05d.mp4", i))
		if err != nil {
			b.Fatal(err)
		}
	}

	b.ResetTimer()
	b.ReportAllocs()
	for i := 0; i < b.N; i++ {
		_ = svc.GetFilesForTag(tag.ID)
	}
}

func TestGetTagsForFiles_BatchQuery(t *testing.T) {
	tempDir := t.TempDir()
	svc, err := NewTagsService(tempDir)
	assert.NoError(t, err)
	defer svc.Close()

	// 准备：3 个 tag，10 个文件
	tag1, err := svc.CreateTag("Tag1", "#FF0000")
	assert.NoError(t, err)
	tag2, err := svc.CreateTag("Tag2", "#00FF00")
	assert.NoError(t, err)
	tag3, err := svc.CreateTag("Tag3", "#0000FF")
	assert.NoError(t, err)

	paths := make([]string, 10)
	for i := 0; i < 10; i++ {
		paths[i] = "dir/file" + string(rune('A'+i)) + ".mp4"
	}
	// 文件 0,1 关联 tag1；文件 2,3 关联 tag1+tag2；其他无 tag
	for _, p := range paths[0:2] {
		_, err := svc.AssociateFile(tag1.ID, p)
		assert.NoError(t, err)
	}
	for _, p := range paths[2:4] {
		_, err := svc.AssociateFile(tag1.ID, p)
		assert.NoError(t, err)
		_, err = svc.AssociateFile(tag2.ID, p)
		assert.NoError(t, err)
	}

	// 查询所有 10 个文件（验证 IN (...) 批量查）
	result := svc.GetTagsForFiles(paths)
	assert.Len(t, result, 10)
	assert.Len(t, result[paths[0]], 1)
	assert.Equal(t, tag1.ID, result[paths[0]][0].ID)
	assert.Len(t, result[paths[2]], 2)
	assert.Len(t, result[paths[4]], 0)
	// 不在 paths 中的文件不应出现在 result
	for fp := range result {
		assert.Contains(t, paths, fp)
	}

	// 验证 tag3 未被任何文件关联
	assert.Len(t, result[paths[9]], 0)

	_ = tag3 // tag3 created but intentionally unused by any file
}

func TestGetTagsForFiles_LargeBatch(t *testing.T) {
	tempDir := t.TempDir()
	svc, err := NewTagsService(tempDir)
	assert.NoError(t, err)
	defer svc.Close()

	tag, err := svc.CreateTag("Large", "#FF0000")
	assert.NoError(t, err)

	// 构造 600 个路径（超过 batchSize=500，验证分批）
	paths := make([]string, 600)
	for i := 0; i < 600; i++ {
		paths[i] = "dir/file" + string(rune('A'+i%26)) + string(rune('A'+i/26)) + ".mp4"
	}
	// 给每 10 个文件关联 tag
	for i := 0; i < 600; i += 10 {
		_, err := svc.AssociateFile(tag.ID, paths[i])
		assert.NoError(t, err)
	}

	result := svc.GetTagsForFiles(paths)
	assert.Len(t, result, 600)
	// 每 10 个文件的第 0 个有 tag
	for i := 0; i < 600; i += 10 {
		assert.Len(t, result[paths[i]], 1, "expected tag at index %d", i)
	}
	// 其他文件无 tag
	for i := 1; i < 600; i += 10 {
		assert.Len(t, result[paths[i]], 0)
	}
}

func TestGetTagsForFiles_Empty(t *testing.T) {
	tempDir := t.TempDir()
	svc, err := NewTagsService(tempDir)
	assert.NoError(t, err)
	defer svc.Close()

	result := svc.GetTagsForFiles([]string{})
	assert.NotNil(t, result)
	assert.Len(t, result, 0)

	// 不存在的文件路径也应返回空 entry
	result = svc.GetTagsForFiles([]string{"nonexistent.mp4"})
	assert.Len(t, result, 1)
	assert.Len(t, result["nonexistent.mp4"], 0)
}

func BenchmarkGetTagsForFiles(b *testing.B) {
	tempDir := b.TempDir()
	svc, err := NewTagsService(tempDir)
	if err != nil {
		b.Fatal(err)
	}
	defer svc.Close()

	tag, err := svc.CreateTag("Bench", "#FF0000")
	if err != nil {
		b.Fatal(err)
	}

	// 50k 文件，每 100 个关联 1 个 tag
	paths := make([]string, 50000)
	for i := 0; i < 50000; i++ {
		paths[i] = fmt.Sprintf("dir/file_%05d.mp4", i)
	}
	for i := 0; i < 50000; i += 100 {
		if _, err := svc.AssociateFile(tag.ID, paths[i]); err != nil {
			b.Fatal(err)
		}
	}

	// 查询 50 个路径（典型浏览一页文件）
	queryPaths := make([]string, 50)
	for i := 0; i < 50; i++ {
		queryPaths[i] = paths[i*1000]
	}

	b.ResetTimer()
	b.ReportAllocs()
	for i := 0; i < b.N; i++ {
		_ = svc.GetTagsForFiles(queryPaths)
	}
}

// TestCleanDeletedPathEscapesLikeWildcards is the Phase 9 (L-4) gate:
// CleanDeletedPath builds a LIKE prefix pattern from a user-supplied path, so
// SQL wildcards inside directory names (% or _) must not broaden the match.
// Files "D:\Media\100%_great\a.mp4" and "D:\Media\100Xgreat\b.mp4": after
// CleanDeletedPath("D:\Media\100%_great") the first file's tag associations
// must be cleared while the bystander (which an UNESCAPED "100%_great" pattern
// would wrongly match: % → "X", _ → one char) keeps exactly one.
func TestCleanDeletedPathEscapesLikeWildcards(t *testing.T) {
	dir := t.TempDir()
	svc, err := NewTagsService(dir)
	if err != nil {
		t.Fatalf("NewTagsService: %v", err)
	}
	defer svc.Close()

	tag, err := svc.CreateTag("Wild", "#FF00FF")
	if err != nil {
		t.Fatalf("CreateTag: %v", err)
	}

	sep := string(filepath.Separator)
	target := filepath.Clean("D:" + sep + "Media" + sep + "100%_great" + sep + "a.mp4")
	bystander := filepath.Clean("D:" + sep + "Media" + sep + "100Xgreat" + sep + "b.mp4")
	for _, p := range []string{target, bystander} {
		if _, err := svc.AssociateFile(tag.ID, p); err != nil {
			t.Fatalf("AssociateFile(%q): %v", p, err)
		}
	}

	if err := svc.CleanDeletedPath("D:" + sep + "Media" + sep + "100%_great"); err != nil {
		t.Fatalf("CleanDeletedPath: %v", err)
	}

	countFor := func(p string) int {
		var n int
		if err := svc.db.QueryRow("SELECT COUNT(*) FROM associations WHERE file_path = ?", p).Scan(&n); err != nil {
			t.Fatalf("SELECT COUNT for %q: %v", p, err)
		}
		return n
	}
	if got := countFor(target); got != 0 {
		t.Fatalf("deleted-path file associations: got %d, want 0 (prefix match must clear them)", got)
	}
	if got := countFor(bystander); got != 1 {
		t.Fatalf("bystander file associations: got %d, want 1 (LIKE wildcards in the path must be escaped)", got)
	}
}

// TestTagsConcurrentReadWriteNoBlocking 验证 WAL + 连接池下，并发读不阻塞写。
// Round 32 P2: SetMaxOpenConns(1) + s.mu.Lock 会让此测试在写期间阻塞所有读。
func TestTagsConcurrentReadWriteNoBlocking(t *testing.T) {
	dir := t.TempDir()
	s, err := NewTagsService(dir)
	if err != nil {
		t.Fatalf("NewTagsService: %v", err)
	}
	defer s.Close()

	// 先插入一个 tag 作为读目标
	tag, err := s.CreateTag("readable", "#fff")
	if err != nil {
		t.Fatalf("CreateTag: %v", err)
	}

	// 启动持续写 goroutine
	writeDone := make(chan struct{})
	go func() {
		defer close(writeDone)
		for i := 0; i < 50; i++ {
			if _, err := s.CreateTag(fmt.Sprintf("w-%d", i), "#000"); err != nil {
				t.Errorf("CreateTag w-%d: %v", i, err)
				return
			}
		}
	}()

	// 主 goroutine 在写期间持续读，应能在 5s 内完成（非阻塞）
	readDone := make(chan struct{})
	go func() {
		defer close(readDone)
		for i := 0; i < 50; i++ {
			_ = s.GetFilesForTag(tag.ID)
		}
	}()

	select {
	case <-readDone:
		// 读在写期间完成 = WAL 并发读工作
	case <-time.After(5 * time.Second):
		t.Fatal("read blocked by write for 5s — WAL concurrent read not working")
	}
	<-writeDone
}

// TestTagsWalPragma 验证 WAL 模式生效。
// Round 32 P2: WAL 是连接池并发读的前置条件。
func TestTagsWalPragma(t *testing.T) {
	dir := t.TempDir()
	s, err := NewTagsService(dir)
	if err != nil {
		t.Fatalf("NewTagsService: %v", err)
	}
	defer s.Close()

	var mode string
	if err := s.db.QueryRow("PRAGMA journal_mode").Scan(&mode); err != nil {
		t.Fatalf("QueryRow PRAGMA journal_mode: %v", err)
	}
	if mode != "wal" {
		t.Fatalf("journal_mode = %q, want %q", mode, "wal")
	}
}

// TestTagsConcurrentWritesNoBusy 验证并发写不触发 SQLITE_BUSY。
// Round 32 P2: busy_timeout=5000 + WAL 写串行应让所有写事务完成。
func TestTagsConcurrentWritesNoBusy(t *testing.T) {
	dir := t.TempDir()
	s, err := NewTagsService(dir)
	if err != nil {
		t.Fatalf("NewTagsService: %v", err)
	}
	defer s.Close()

	const N = 20
	var wg sync.WaitGroup
	errs := make(chan error, N)
	for i := 0; i < N; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			_, err := s.CreateTag(fmt.Sprintf("t-%d", idx), "#abc")
			if err != nil {
				errs <- err
			}
		}(i)
	}
	wg.Wait()
	close(errs)

	for err := range errs {
		if strings.Contains(err.Error(), "SQLITE_BUSY") || strings.Contains(err.Error(), "database is locked") {
			t.Fatalf("got lock error: %v", err)
		}
	}

	all := s.GetAllTags()
	if len(all) != N {
		t.Fatalf("got %d tags, want %d", len(all), N)
	}
}
