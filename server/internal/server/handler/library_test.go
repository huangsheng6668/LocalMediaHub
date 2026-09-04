package handler

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"github.com/labstack/echo/v4"
	"github.com/stretchr/testify/assert"

	"github.com/localmediahub/server/internal/config"
	"github.com/localmediahub/server/internal/service"
)

func newLibraryTestHandler(t *testing.T) (*Handler, string) {
	t.Helper()
	root := t.TempDir()
	mediaDir := filepath.Join(root, "novels")
	assert.NoError(t, os.MkdirAll(mediaDir, 0755))
	mediaPath := filepath.Join(mediaDir, "a.txt")
	assert.NoError(t, os.WriteFile(mediaPath, []byte("hello"), 0644))
	cfg := &config.Config{
		Scan:   config.ScanConfig{Roots: []string{root}, TextExtensions: []string{".txt", ".epub"}},
		System: config.SystemConfig{},
	}
	libSvc, err := service.NewLibraryService(t.TempDir())
	assert.NoError(t, err)
	t.Cleanup(func() { _ = libSvc.Close() })
	return New(cfg, nil, nil, nil, nil, libSvc, nil, nil), mediaPath
}

func postJSON(t *testing.T, h *Handler, method, target, body string) *httptest.ResponseRecorder {
	t.Helper()
	e := echo.New()
	req := httptest.NewRequest(method, target, strings.NewReader(body))
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	rec := httptest.NewRecorder()
	assert.NoError(t, h.PostReadingState(e.NewContext(req, rec)))
	return rec
}

// 目录放行是分层校验的核心设计决策（spec §4）：收藏与批量装饰端点必须接受
// 目录路径——ValidateAccessibleMediaPath 强制 !IsDir() 会拒目录，若日后有人把
// 校验"收紧"回去，目录收藏将静默 400。本测试锁定该行为。
func TestDecorationsAndFavoritesAcceptDirectoryPaths(t *testing.T) {
	h, mediaPath := newLibraryTestHandler(t)
	dir := filepath.Dir(mediaPath)

	postDecorations := func() *httptest.ResponseRecorder {
		e := echo.New()
		req := httptest.NewRequest(http.MethodPost, "/api/v1/library/decorations",
			strings.NewReader(`{"paths":[`+strconv.Quote(dir)+`]}`))
		req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
		rec := httptest.NewRecorder()
		assert.NoError(t, h.PostDecorations(e.NewContext(req, rec)))
		return rec
	}

	// decorations 含目录路径 → 200（而非 400）
	rec := postDecorations()
	assert.Equal(t, http.StatusOK, rec.Code)

	// favorites POST 目录路径 → 200
	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/library/favorites",
		strings.NewReader(`{"path":`+strconv.Quote(dir)+`,"is_dir":true,"title":"novels","media_type":"folder","snapshot":{},"added_at":1000}`))
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	rec2 := httptest.NewRecorder()
	assert.NoError(t, h.AddFavorite(e.NewContext(req, rec2)))
	assert.Equal(t, http.StatusOK, rec2.Code)

	// 收藏后再查 decorations：目录以请求原始 key 形态出现在 favorites 回显中
	rec3 := postDecorations()
	assert.Equal(t, http.StatusOK, rec3.Code)
	assert.Contains(t, rec3.Body.String(), strconv.Quote(dir))
}

func TestPostReadingStateRoundtrip(t *testing.T) {
	h, mediaPath := newLibraryTestHandler(t)
	rec := postJSON(t, h, http.MethodPost, "/api/v1/library/states",
		`{"path":`+strconv.Quote(mediaPath)+`,"chapter_index":2,"para_index":1,"percent":25.5,"finished":false,"last_read_at":1000}`)
	assert.Equal(t, http.StatusOK, rec.Code)
	assert.Contains(t, rec.Body.String(), `"status":"reading"`)

	// GET single state
	e := echo.New()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/library/states?path="+mediaPath, nil)
	rec2 := httptest.NewRecorder()
	assert.NoError(t, h.GetReadingState(e.NewContext(req, rec2)))
	assert.Equal(t, http.StatusOK, rec2.Code)
	assert.Contains(t, rec2.Body.String(), `"chapter_index":2`)
	assert.Contains(t, rec2.Body.String(), `"percent":25.5`)

	// GET unrecorded file returns "state":null
	otherFile := filepath.Join(filepath.Dir(mediaPath), "other.txt")
	assert.NoError(t, os.WriteFile(otherFile, []byte("world"), 0644))
	reqOther := httptest.NewRequest(http.MethodGet, "/api/v1/library/states?path="+otherFile, nil)
	recOther := httptest.NewRecorder()
	assert.NoError(t, h.GetReadingState(e.NewContext(reqOther, recOther)))
	assert.Equal(t, http.StatusOK, recOther.Code)
	assert.Contains(t, recOther.Body.String(), `"state":null`)
}

func TestPostReadingStateRejectsOutsideRoots(t *testing.T) {
	h, _ := newLibraryTestHandler(t)
	rec := postJSON(t, h, http.MethodPost, "/api/v1/library/states",
		`{"path":"Z:\\elsewhere\\x.txt","chapter_index":0,"para_index":0,"percent":0,"finished":false,"last_read_at":1}`)
	assert.Equal(t, http.StatusBadRequest, rec.Code)
}

func TestPostDecorationsKeyFidelity(t *testing.T) {
	h, mediaPath := newLibraryTestHandler(t)
	rec := postJSON(t, h, http.MethodPost, "/api/v1/library/states",
		`{"path":`+strconv.Quote(mediaPath)+`,"chapter_index":0,"para_index":0,"percent":5,"finished":false,"last_read_at":1}`)
	assert.Equal(t, http.StatusOK, rec.Code)

	// Case variation of the requested path must preserve original key in response
	variant := strings.ToUpper(mediaPath)
	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/library/decorations",
		strings.NewReader(`{"paths":[`+strconv.Quote(variant)+`]}`))
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	rec2 := httptest.NewRecorder()
	assert.NoError(t, h.PostDecorations(e.NewContext(req, rec2)))
	assert.Equal(t, http.StatusOK, rec2.Code)
	assert.Contains(t, rec2.Body.String(), strconv.Quote(variant))
}

func TestPostDecorationsRejectsOversize(t *testing.T) {
	h, _ := newLibraryTestHandler(t)
	paths := make([]string, 501)
	for i := range paths {
		paths[i] = "x"
	}
	body := `{"paths":["` + strings.Join(paths, `","`) + `"]}`
	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/library/decorations", strings.NewReader(body))
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	rec := httptest.NewRecorder()
	assert.NoError(t, h.PostDecorations(e.NewContext(req, rec)))
	assert.Equal(t, http.StatusBadRequest, rec.Code)
}

func TestPutReadingStatus(t *testing.T) {
	h, mediaPath := newLibraryTestHandler(t)

	// Put invalid status -> 400
	e := echo.New()
	req := httptest.NewRequest(http.MethodPut, "/api/v1/library/states/status",
		strings.NewReader(`{"path":`+strconv.Quote(mediaPath)+`,"status":"invalid"}`))
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	rec := httptest.NewRecorder()
	assert.NoError(t, h.PutReadingStatus(e.NewContext(req, rec)))
	assert.Equal(t, http.StatusBadRequest, rec.Code)

	// Put "reading" -> 200 {"status":"reading"}
	req = httptest.NewRequest(http.MethodPut, "/api/v1/library/states/status",
		strings.NewReader(`{"path":`+strconv.Quote(mediaPath)+`,"status":"reading"}`))
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	rec = httptest.NewRecorder()
	assert.NoError(t, h.PutReadingStatus(e.NewContext(req, rec)))
	assert.Equal(t, http.StatusOK, rec.Code)
	assert.Contains(t, rec.Body.String(), `"status":"reading"`)

	// Put "finished" -> 200 {"status":"finished"}
	req = httptest.NewRequest(http.MethodPut, "/api/v1/library/states/status",
		strings.NewReader(`{"path":`+strconv.Quote(mediaPath)+`,"status":"finished"}`))
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	rec = httptest.NewRecorder()
	assert.NoError(t, h.PutReadingStatus(e.NewContext(req, rec)))
	assert.Equal(t, http.StatusOK, rec.Code)
	assert.Contains(t, rec.Body.String(), `"status":"finished"`)

	// Put "unread" -> 200 {"status":"unread"}
	req = httptest.NewRequest(http.MethodPut, "/api/v1/library/states/status",
		strings.NewReader(`{"path":`+strconv.Quote(mediaPath)+`,"status":"unread"}`))
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	rec = httptest.NewRecorder()
	assert.NoError(t, h.PutReadingStatus(e.NewContext(req, rec)))
	assert.Equal(t, http.StatusOK, rec.Code)
	assert.Contains(t, rec.Body.String(), `"status":"unread"`)

	// Put null -> clears manual status
	req = httptest.NewRequest(http.MethodPut, "/api/v1/library/states/status",
		strings.NewReader(`{"path":`+strconv.Quote(mediaPath)+`,"status":null}`))
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	rec = httptest.NewRecorder()
	assert.NoError(t, h.PutReadingStatus(e.NewContext(req, rec)))
	assert.Equal(t, http.StatusOK, rec.Code)
}

func TestFavoritesCRUD(t *testing.T) {
	h, mediaPath := newLibraryTestHandler(t)
	e := echo.New()

	// List initial empty favorites
	req := httptest.NewRequest(http.MethodGet, "/api/v1/library/favorites", nil)
	rec := httptest.NewRecorder()
	assert.NoError(t, h.ListFavorites(e.NewContext(req, rec)))
	assert.Equal(t, http.StatusOK, rec.Code)
	assert.JSONEq(t, `[]`, rec.Body.String())

	// Add favorite
	addBody := `{"path":` + strconv.Quote(mediaPath) + `,"is_dir":false,"is_system":false,"title":"My Book","media_type":"text","snapshot":{"progress":10}}`
	req = httptest.NewRequest(http.MethodPost, "/api/v1/library/favorites", strings.NewReader(addBody))
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	rec = httptest.NewRecorder()
	assert.NoError(t, h.AddFavorite(e.NewContext(req, rec)))
	assert.Equal(t, http.StatusOK, rec.Code)
	assert.Contains(t, rec.Body.String(), `"ok":true`)

	// List favorites should contain the added entry
	req = httptest.NewRequest(http.MethodGet, "/api/v1/library/favorites", nil)
	rec = httptest.NewRecorder()
	assert.NoError(t, h.ListFavorites(e.NewContext(req, rec)))
	assert.Equal(t, http.StatusOK, rec.Code)
	assert.Contains(t, rec.Body.String(), "My Book")

	// Add favorite rejecting oversized snapshot (> 8192 bytes)
	hugeSnapshot := strings.Repeat("x", 8200)
	oversizeBody := `{"path":` + strconv.Quote(mediaPath) + `,"title":"Too big","snapshot":` + strconv.Quote(hugeSnapshot) + `}`
	req = httptest.NewRequest(http.MethodPost, "/api/v1/library/favorites", strings.NewReader(oversizeBody))
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	rec = httptest.NewRecorder()
	assert.NoError(t, h.AddFavorite(e.NewContext(req, rec)))
	assert.Equal(t, http.StatusBadRequest, rec.Code)

	// Add favorite outside roots -> 400
	outsideBody := `{"path":"Z:\\outside\\novel.txt","title":"Outside"}`
	req = httptest.NewRequest(http.MethodPost, "/api/v1/library/favorites", strings.NewReader(outsideBody))
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	rec = httptest.NewRecorder()
	assert.NoError(t, h.AddFavorite(e.NewContext(req, rec)))
	assert.Equal(t, http.StatusBadRequest, rec.Code)

	// Delete favorite
	req = httptest.NewRequest(http.MethodDelete, "/api/v1/library/favorites?path="+mediaPath, nil)
	rec = httptest.NewRecorder()
	assert.NoError(t, h.DeleteFavorite(e.NewContext(req, rec)))
	assert.Equal(t, http.StatusOK, rec.Code)
	assert.Contains(t, rec.Body.String(), `"ok":true`)

	// List favorites again -> empty
	req = httptest.NewRequest(http.MethodGet, "/api/v1/library/favorites", nil)
	rec = httptest.NewRecorder()
	assert.NoError(t, h.ListFavorites(e.NewContext(req, rec)))
	assert.Equal(t, http.StatusOK, rec.Code)
	assert.JSONEq(t, `[]`, rec.Body.String())

	// Delete with invalid path outside roots -> 400
	req = httptest.NewRequest(http.MethodDelete, "/api/v1/library/favorites?path=Z:\\outside\\novel.txt", nil)
	rec = httptest.NewRecorder()
	assert.NoError(t, h.DeleteFavorite(e.NewContext(req, rec)))
	assert.Equal(t, http.StatusBadRequest, rec.Code)
}
