package service

import (
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
