package handler

import (
	"net/http"
	"os"

	"github.com/labstack/echo/v4"

	"github.com/localmediahub/server/internal/service"
)

func (h *Handler) MediaThumbnail(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}

	allowedExts := append(h.cfg.Scan.ImageExtensions, h.cfg.Scan.VideoExtensions...)
	resolved, err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), allowedExts)
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	thumbBytes, err := h.thumbnail.GenerateThumbnailBytes(resolved)
	if err != nil {
		if os.IsNotExist(err) {
			return respondNotFound(c, "file not found")
		}
		return respondInternalError(c, err)
	}

	setMediaCacheHeaders(c)
	return c.Blob(http.StatusOK, "image/jpeg", thumbBytes)
}

func (h *Handler) MediaOriginal(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}

	// Text extensions added in Task 8 so /api/v1/media/original can serve raw
	// .txt / .epub bytes for client-side download (the /api/v1/books chapter
	// endpoint is for rendering, this one is for "save as" / share flows).
	allowedExts := make([]string, 0, len(h.cfg.Scan.ImageExtensions)+len(h.cfg.Scan.TextExtensions))
	allowedExts = append(allowedExts, h.cfg.Scan.ImageExtensions...)
	allowedExts = append(allowedExts, h.cfg.Scan.TextExtensions...)
	resolved, err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), allowedExts)
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	setMediaCacheHeaders(c)
	return c.File(resolved)
}

func (h *Handler) MediaStream(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}

	resolved, err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.VideoExtensions)
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	if err := h.streaming.ServeFile(c.Response().Writer, c.Request(), resolved); err != nil {
		if os.IsNotExist(err) {
			return respondNotFound(c, "file not found")
		}
		return respondInternalError(c, err)
	}
	return nil
}

func (h *Handler) MediaDuration(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}

	resolved, err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.VideoExtensions)
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	// 优先从缩略图服务的时长缓存查询（共享 durations.json，避免重复 fork ffprobe）。
	// Cache miss 时 fallback 到 streaming.GetVideoDuration（会 fork ffprobe 并返回
	// error 表示失败，与历史行为一致）。
	duration, ok := h.thumbnail.VideoDuration(resolved)
	if !ok {
		var err error
		duration, err = h.streaming.GetVideoDuration(resolved)
		if err != nil {
			return respondInternalError(c, err)
		}
	}

	setJsonCacheStandard(c)
	return c.JSON(http.StatusOK, map[string]interface{}{
		"duration": duration,
	})
}

