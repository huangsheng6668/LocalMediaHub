package handler

import (
	"net/http"
	"strings"
	"time"

	"github.com/labstack/echo/v4"

	"github.com/localmediahub/server/internal/models"
	"github.com/localmediahub/server/internal/service"
)

const maxDecorationPaths = 500

// validateTextPath: 阅读状态对象必须是扫描/系统根内的文本媒体文件（ValidateAccessibleMediaPath 拒目录）。
func (h *Handler) validateTextPath(c echo.Context, rawPath string) (string, bool) {
	if rawPath == "" {
		_ = respondError(c, http.StatusBadRequest, "path is required")
		return "", false
	}
	normalized, err := service.ValidateAccessibleMediaPath(rawPath,
		h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.TextExtensions)
	if err != nil {
		_ = respondError(c, http.StatusBadRequest, "path not accessible")
		return "", false
	}
	return normalized, true
}

// validateAnyEntryPath: 收藏/批量装饰对象可为文件或目录——边界 roots + 敏感段阻断。
func (h *Handler) validateAnyEntryPath(rawPath string) (string, bool) {
	if rawPath == "" {
		return "", false
	}
	normalized, err := service.NormalizePath(rawPath)
	if err != nil {
		return "", false
	}
	allRoots := append(append([]string{}, h.cfg.Scan.GetRoots()...), h.cfg.GetSystemAllowedRoots()...)
	ok, err := service.IsPathWithinRoots(normalized, allRoots)
	if err != nil || !ok || service.IsBlockedRoot(normalized) {
		return "", false
	}
	return normalized, true
}

func (h *Handler) PostReadingState(c echo.Context) error {
	var req models.ProgressUpdate
	if err := c.Bind(&req); err != nil {
		return respondError(c, http.StatusBadRequest, "invalid request body")
	}
	normalized, ok := h.validateTextPath(c, req.Path)
	if !ok {
		return nil
	}
	req.Path = normalized
	st, err := h.library.UpsertProgress(req)
	if err != nil {
		return respondInternalError(c, err)
	}
	status := deriveStatusPublic(st)
	return c.JSON(http.StatusOK, map[string]interface{}{
		"status": status, "updated_at": st.UpdatedAt,
	})
}

// deriveStatusPublic: service.deriveStatus 未导出，此处按响应需要的纯派生复刻。
func deriveStatusPublic(st models.ReadingState) string {
	if st.ManualStatus != nil {
		return *st.ManualStatus
	}
	if st.Finished {
		return "finished"
	}
	if st.UpdatedAt == 0 && st.LastReadAt == 0 {
		return "unread"
	}
	return "reading"
}

func (h *Handler) GetReadingState(c echo.Context) error {
	normalized, ok := h.validateTextPath(c, c.QueryParam("path"))
	if !ok {
		return nil
	}
	st, err := h.library.GetState(normalized)
	if err != nil {
		return respondInternalError(c, err)
	}
	return c.JSON(http.StatusOK, map[string]interface{}{"state": st}) // st 为 nil → "state":null
}

func (h *Handler) PutReadingStatus(c echo.Context) error {
	var req struct {
		Path   string  `json:"path"`
		Status *string `json:"status"`
	}
	if err := c.Bind(&req); err != nil {
		return respondError(c, http.StatusBadRequest, "invalid request body")
	}
	if req.Status != nil {
		switch *req.Status {
		case "unread", "reading", "finished":
		default:
			return respondError(c, http.StatusBadRequest, "invalid status")
		}
	}
	normalized, ok := h.validateTextPath(c, req.Path)
	if !ok {
		return nil
	}
	st, err := h.library.SetManualStatus(normalized, req.Status)
	if err != nil {
		return respondInternalError(c, err)
	}
	return c.JSON(http.StatusOK, map[string]string{"status": deriveStatusPublic(st)})
}

func (h *Handler) PostDecorations(c echo.Context) error {
	var req struct {
		Paths []string `json:"paths"`
	}
	if err := c.Bind(&req); err != nil {
		return respondError(c, http.StatusBadRequest, "invalid request body")
	}
	if len(req.Paths) > maxDecorationPaths {
		return respondError(c, http.StatusBadRequest, "too many paths")
	}
	normalizedToOriginal := map[string]string{} // normalized -> 首个原始形态
	normalizedList := make([]string, 0, len(req.Paths))
	seen := map[string]bool{}
	for _, raw := range req.Paths {
		norm, ok := h.validateAnyEntryPath(raw)
		if !ok || seen[norm] {
			continue // 无效/重复：静默跳过
		}
		seen[norm] = true
		normalizedToOriginal[norm] = raw
		normalizedList = append(normalizedList, norm)
	}
	res, err := h.library.BatchDecorations(normalizedList)
	if err != nil {
		return respondInternalError(c, err)
	}
	// key 保真：DB 形态 → 调用方原始字符串
	states := make(map[string]models.ReadingStateBadge, len(res.States))
	for dbPath, badge := range res.States {
		orig, ok := normalizedToOriginal[dbPath]
		if !ok {
			for n, o := range normalizedToOriginal {
				if strings.EqualFold(n, dbPath) {
					orig = o
					ok = true
					break
				}
			}
		}
		if !ok {
			orig = dbPath
		}
		states[orig] = badge
	}
	favs := make([]string, 0, len(res.Favorites))
	for _, p := range res.Favorites {
		if orig, ok := normalizedToOriginal[p]; ok {
			favs = append(favs, orig)
		} else {
			matched := false
			for n, o := range normalizedToOriginal {
				if strings.EqualFold(n, p) {
					favs = append(favs, o)
					matched = true
					break
				}
			}
			if !matched {
				favs = append(favs, p)
			}
		}
	}
	return c.JSON(http.StatusOK, models.DecorationsResult{States: states, Favorites: favs})
}

func (h *Handler) ListFavorites(c echo.Context) error {
	list, err := h.library.ListFavorites()
	if err != nil {
		return respondInternalError(c, err)
	}
	return c.JSON(http.StatusOK, list)
}

func (h *Handler) AddFavorite(c echo.Context) error {
	var req models.FavoriteUpdate
	if err := c.Bind(&req); err != nil {
		return respondError(c, http.StatusBadRequest, "invalid request body")
	}
	if len(req.Snapshot) > 8192 {
		return respondError(c, http.StatusBadRequest, "snapshot too large")
	}
	normalized, ok := h.validateAnyEntryPath(req.Path)
	if !ok {
		return respondError(c, http.StatusBadRequest, "path not accessible")
	}
	req.Path = normalized
	if req.AddedAt == 0 {
		req.AddedAt = time.Now().UnixMilli()
	}
	if err := h.library.UpsertFavorite(req); err != nil {
		return respondInternalError(c, err)
	}
	return c.JSON(http.StatusOK, map[string]bool{"ok": true})
}

func (h *Handler) DeleteFavorite(c echo.Context) error {
	raw := c.QueryParam("path")
	normalized, ok := h.validateAnyEntryPath(raw)
	if !ok {
		return respondError(c, http.StatusBadRequest, "path not accessible")
	}
	if err := h.library.RemoveFavorite(normalized); err != nil {
		return respondInternalError(c, err)
	}
	return c.JSON(http.StatusOK, map[string]bool{"ok": true})
}
