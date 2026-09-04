package service

import (
	"encoding/json"
	"fmt"
	"testing"

	"github.com/stretchr/testify/assert"

	"github.com/localmediahub/server/internal/models"
)

func newTestLibraryService(t *testing.T) *LibraryService {
	t.Helper()
	svc, err := NewLibraryService(t.TempDir())
	assert.NoError(t, err)
	t.Cleanup(func() { _ = svc.Close() })
	return svc
}

func mkUpdate(path string, ch, para int, pct float64, finished bool, lastReadAt int64) models.ProgressUpdate {
	return models.ProgressUpdate{Path: path, ChapterIndex: ch, ParaIndex: para,
		Percent: pct, Finished: finished, LastReadAt: lastReadAt}
}

func TestUpsertProgressInsert(t *testing.T) {
	svc := newTestLibraryService(t)
	st, err := svc.UpsertProgress(mkUpdate("/media/a.txt", 1, 2, 10.5, false, 1000))
	assert.NoError(t, err)
	assert.Equal(t, "reading", deriveStatus(st.Finished, st.ManualStatus, true))
	assert.InEpsilon(t, 10.5, st.Percent, 1e-9)

	got, err := svc.GetState("/media/a.txt")
	assert.NoError(t, err)
	assert.NotNil(t, got)
	assert.Equal(t, 1, got.ChapterIndex)
	assert.Equal(t, int64(1000), got.LastReadAt)
}

func TestUpsertProgressMerge(t *testing.T) {
	svc := newTestLibraryService(t)
	// 首次读完结
	_, err := svc.UpsertProgress(mkUpdate("/media/b.txt", 9, 5, 99.0, true, 1000))
	assert.NoError(t, err)
	// 更新的重读进度（lastReadAt 更新，finished=false）：finished 粘滞 + manual unread 自动清除
	_, err = svc.UpsertProgress(mkUpdate("/media/b.txt", 0, 0, 0, false, 2000))
	assert.NoError(t, err)
	got, err := svc.GetState("/media/b.txt")
	assert.NoError(t, err)
	assert.NotNil(t, got)
	assert.True(t, got.Finished)         // 粘滞
	assert.Equal(t, 0, got.ChapterIndex) // 进度更新为新值
	// 陈旧上报 no-op
	st, err := svc.UpsertProgress(mkUpdate("/media/b.txt", 5, 0, 50, false, 1500))
	assert.NoError(t, err)
	assert.Equal(t, 0, st.ChapterIndex) // 仍是 0
}

func TestUpsertProgressAutoClearsManualUnread(t *testing.T) {
	svc := newTestLibraryService(t)
	_, err := svc.UpsertProgress(mkUpdate("/media/c.txt", 1, 0, 5, false, 1000))
	assert.NoError(t, err)
	// 手动标未读（Task 2 实现 SetManualStatus；此处先直接 SQL 置位以锁定 upsert 行为）
	_, err = svc.db.Exec(`UPDATE reading_states SET manual_status='unread' WHERE path=?`, "/media/c.txt")
	assert.NoError(t, err)
	_, err = svc.UpsertProgress(mkUpdate("/media/c.txt", 2, 0, 8, false, 3000))
	assert.NoError(t, err)
	got, err := svc.GetState("/media/c.txt")
	assert.NoError(t, err)
	assert.NotNil(t, got)
	assert.Nil(t, got.ManualStatus) // 自动清除
}

func TestUpsertProgressCaseInsensitivePath(t *testing.T) {
	svc := newTestLibraryService(t)
	_, err := svc.UpsertProgress(mkUpdate("/Media/A.TXT", 1, 0, 5, false, 1000))
	assert.NoError(t, err)
	got, err := svc.GetState("/media/a.txt")
	assert.NoError(t, err)
	assert.NotNil(t, got) // COLLATE NOCASE 命中同一行
}

func TestGetStateNonExistent(t *testing.T) {
	svc := newTestLibraryService(t)
	got, err := svc.GetState("/media/nonexistent.txt")
	assert.NoError(t, err)
	assert.Nil(t, got)
}

func TestDeriveStatus(t *testing.T) {
	assert.Equal(t, "unread", deriveStatus(false, nil, false))
	assert.Equal(t, "reading", deriveStatus(false, nil, true))
	assert.Equal(t, "finished", deriveStatus(true, nil, true))
	manual := "unread"
	assert.Equal(t, "unread", deriveStatus(true, &manual, true)) // manual 优先
}

func TestSetManualStatus(t *testing.T) {
	svc := newTestLibraryService(t)
	_, _ = svc.UpsertProgress(mkUpdate("/media/d.txt", 3, 0, 30, true, 1000))

	fin := "finished"
	st, err := svc.SetManualStatus("/media/d.txt", &fin)
	assert.NoError(t, err)
	assert.Equal(t, "finished", deriveStatus(st.Finished, st.ManualStatus, true))

	unread := "unread"
	st, err = svc.SetManualStatus("/media/d.txt", &unread)
	assert.NoError(t, err)
	assert.False(t, st.Finished)             // 手动未读重置 finished
	assert.InDelta(t, 0.0, st.Percent, 1e-9) // 且重置 percent
	assert.Equal(t, "unread", deriveStatus(st.Finished, st.ManualStatus, true))

	// 清除覆盖 → 回到自动（行仍在，percent 已被重置为 0）
	st, err = svc.SetManualStatus("/media/d.txt", nil)
	assert.NoError(t, err)
	assert.Nil(t, st.ManualStatus)
	assert.Equal(t, "reading", deriveStatus(st.Finished, st.ManualStatus, true))
}

func TestSetManualStatusInsertsRow(t *testing.T) {
	svc := newTestLibraryService(t)
	fin := "finished"
	st, err := svc.SetManualStatus("/media/new.txt", &fin) // 无进度行也可手动标记
	assert.NoError(t, err)
	assert.True(t, st.Finished)
	assert.InDelta(t, 100.0, st.Percent, 1e-9)
	got, _ := svc.GetState("/media/new.txt")
	assert.NotNil(t, got)
}

func TestSetManualStatusFinishedSetsPercent(t *testing.T) {
	svc := newTestLibraryService(t)
	_, _ = svc.UpsertProgress(mkUpdate("/media/e.txt", 2, 0, 20, false, 1000))
	fin := "finished"
	st, _ := svc.SetManualStatus("/media/e.txt", &fin)
	assert.InDelta(t, 100.0, st.Percent, 1e-9)
	assert.True(t, st.Finished)
}

func TestSetManualStatusNilNonExistentDoesNotInsert(t *testing.T) {
	svc := newTestLibraryService(t)
	st, err := svc.SetManualStatus("/media/nonexistent.txt", nil)
	assert.NoError(t, err)
	assert.Equal(t, "/media/nonexistent.txt", st.Path)
	assert.False(t, st.Finished)
	assert.InDelta(t, 0.0, st.Percent, 1e-9)
	assert.Nil(t, st.ManualStatus)
	assert.Equal(t, "unread", deriveStatus(st.Finished, st.ManualStatus, false))

	got, err := svc.GetState("/media/nonexistent.txt")
	assert.NoError(t, err)
	assert.Nil(t, got) // 确认没有插入行
}

func TestSetManualStatusReadingRetainsProgress(t *testing.T) {
	svc := newTestLibraryService(t)
	_, _ = svc.UpsertProgress(mkUpdate("/media/r.txt", 5, 2, 45.5, false, 1000))
	reading := "reading"
	st, err := svc.SetManualStatus("/media/r.txt", &reading)
	assert.NoError(t, err)
	assert.NotNil(t, st.ManualStatus)
	assert.Equal(t, "reading", *st.ManualStatus)
	assert.InDelta(t, 45.5, st.Percent, 1e-9)
	assert.False(t, st.Finished)
}

func mkFav(path, title string, isDir bool, addedAt int64) models.FavoriteUpdate {
	return models.FavoriteUpdate{
		Path:      path,
		Title:     title,
		IsDir:     isDir,
		MediaType: map[bool]string{true: "folder", false: "text"}[isDir],
		Snapshot:  json.RawMessage(`{"file":{"name":"` + title + `"}}`),
		AddedAt:   addedAt,
	}
}

func TestFavoritesUpsertIdempotentAndMerge(t *testing.T) {
	svc := newTestLibraryService(t)
	assert.NoError(t, svc.UpsertFavorite(mkFav("/media/novel", "novel", true, 1000)))
	// 重复收藏：幂等保 added_at
	assert.NoError(t, svc.UpsertFavorite(mkFav("/media/novel", "novel", true, 9999)))
	list, err := svc.ListFavorites()
	assert.NoError(t, err)
	assert.Len(t, list, 1)
	assert.Equal(t, int64(1000), list[0].AddedAt)
	// snapshot 随 added_at 胜者覆盖：更晚 added_at 的新 snapshot 生效
	assert.NoError(t, svc.UpsertFavorite(models.FavoriteUpdate{
		Path:      "/media/novel",
		IsDir:     true,
		MediaType: "folder",
		Snapshot:  json.RawMessage(`{"folder":{"name":"v2"}}`),
		AddedAt:   2000,
	}))
	list, _ = svc.ListFavorites()
	assert.Equal(t, int64(2000), list[0].AddedAt)
	assert.JSONEq(t, `{"folder":{"name":"v2"}}`, string(list[0].Snapshot))

	// 更早 added_at 的新 snapshot 不覆盖
	assert.NoError(t, svc.UpsertFavorite(models.FavoriteUpdate{
		Path:      "/media/novel",
		IsDir:     true,
		MediaType: "folder",
		Snapshot:  json.RawMessage(`{"folder":{"name":"v0"}}`),
		AddedAt:   500,
	}))
	list, _ = svc.ListFavorites()
	assert.Equal(t, int64(2000), list[0].AddedAt)
	assert.JSONEq(t, `{"folder":{"name":"v2"}}`, string(list[0].Snapshot))
}

func TestFavoritesRemoveAndList(t *testing.T) {
	svc := newTestLibraryService(t)
	// 空表返回空切片（非 nil）
	emptyList, err := svc.ListFavorites()
	assert.NoError(t, err)
	assert.NotNil(t, emptyList)
	assert.Empty(t, emptyList)

	assert.NoError(t, svc.UpsertFavorite(mkFav("/a.txt", "a", false, 1)))
	assert.NoError(t, svc.UpsertFavorite(mkFav("/b.txt", "b", false, 2)))
	assert.NoError(t, svc.RemoveFavorite("/a.txt"))
	list, err := svc.ListFavorites()
	assert.NoError(t, err)
	assert.Len(t, list, 1)
	assert.Equal(t, "/b.txt", list[0].Path)
	assert.NoError(t, svc.RemoveFavorite("/missing.txt")) // 删除不存在不报错
}

func TestFavoritePaths(t *testing.T) {
	svc := newTestLibraryService(t)
	// 空输入
	emptyPaths, err := svc.FavoritePaths([]string{})
	assert.NoError(t, err)
	assert.NotNil(t, emptyPaths)
	assert.Empty(t, emptyPaths)

	assert.NoError(t, svc.UpsertFavorite(mkFav("/media/x.txt", "x", false, 1)))
	assert.NoError(t, svc.UpsertFavorite(mkFav("/media/z.txt", "z", false, 2)))
	got, err := svc.FavoritePaths([]string{"/media/y.txt", "/media/z.txt", "/MEDIA/X.TXT"})
	assert.NoError(t, err)
	assert.Equal(t, []string{"/media/z.txt", "/MEDIA/X.TXT"}, got) // 输入原序 + 大小写不敏感命中
}

func TestFavoriteSnapshotTooLarge(t *testing.T) {
	svc := newTestLibraryService(t)
	bigSnapshot := make([]byte, 8193)
	for i := range bigSnapshot {
		bigSnapshot[i] = 'a'
	}
	err := svc.UpsertFavorite(models.FavoriteUpdate{
		Path:     "/media/big.txt",
		Snapshot: bigSnapshot,
		AddedAt:  1000,
	})
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "snapshot too large")
}

func strPtr(s string) *string { return &s }

func TestBatchDecorations(t *testing.T) {
	svc := newTestLibraryService(t)
	_, _ = svc.UpsertProgress(mkUpdate("/m/a.txt", 5, 0, 50, false, 1000))
	_, _ = svc.UpsertProgress(mkUpdate("/m/b.txt", 9, 9, 100, true, 1000))
	_, _ = svc.UpsertProgress(mkUpdate("/m/c.txt", 1, 0, 5, false, 1000))
	_, _ = svc.SetManualStatus("/m/c.txt", strPtr("unread"))
	assert.NoError(t, svc.UpsertFavorite(mkFav("/m/b.txt", "b", false, 1)))

	res, err := svc.BatchDecorations([]string{"/m/a.txt", "/m/b.txt", "/m/c.txt", "/m/d.txt"})
	assert.NoError(t, err)
	assert.Len(t, res.States, 3) // d.txt 无行省略
	assert.Equal(t, "reading", res.States["/m/a.txt"].Status)
	assert.InDelta(t, 50.0, res.States["/m/a.txt"].Percent, 1e-9)
	assert.Equal(t, "finished", res.States["/m/b.txt"].Status)
	assert.Equal(t, "unread", res.States["/m/c.txt"].Status) // manual unread 显式返回
	assert.Equal(t, []string{"/m/b.txt"}, res.Favorites)
}

func TestBatchDecorationsEmpty(t *testing.T) {
	svc := newTestLibraryService(t)
	res, err := svc.BatchDecorations([]string{})
	assert.NoError(t, err)
	assert.NotNil(t, res.States)
	assert.Empty(t, res.States)
	assert.NotNil(t, res.Favorites)
	assert.Empty(t, res.Favorites)
}

func TestBatchDecorationsCaseInsensitiveAndDeduplication(t *testing.T) {
	svc := newTestLibraryService(t)
	assert.NoError(t, svc.UpsertFavorite(mkFav("/media/x.txt", "x", false, 1)))
	assert.NoError(t, svc.UpsertFavorite(mkFav("/media/z.txt", "z", false, 2)))

	res, err := svc.BatchDecorations([]string{
		"/media/z.txt",
		"/MEDIA/X.TXT",
		"/media/x.txt",
		"/MEDIA/Z.TXT",
		"/media/other.txt",
	})
	assert.NoError(t, err)
	// Deduplicated favorites preserving caller's order and casing of the first match
	assert.Equal(t, []string{"/media/z.txt", "/MEDIA/X.TXT"}, res.Favorites)
}

func TestBatchDecorationsLargeBatch(t *testing.T) {
	svc := newTestLibraryService(t)
	assert.NoError(t, svc.UpsertFavorite(mkFav("/media/item-502.txt", "502", false, 1)))
	_, err := svc.UpsertProgress(mkUpdate("/media/item-502.txt", 1, 0, 25.0, false, 1000))
	assert.NoError(t, err)

	paths := make([]string, 520)
	for i := 0; i < 520; i++ {
		paths[i] = fmt.Sprintf("/media/item-%d.txt", i)
	}

	res, err := svc.BatchDecorations(paths)
	assert.NoError(t, err)
	assert.Len(t, res.States, 1)
	assert.Equal(t, "reading", res.States["/media/item-502.txt"].Status)
	assert.Equal(t, []string{"/media/item-502.txt"}, res.Favorites)
}

