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

	thumbPath, err := h.thumbnail.GenerateThumbnail(resolved)
	if err != nil {
		if os.IsNotExist(err) {
			return respondNotFound(c, "file not found")
		}
		return respondInternalError(c, err)
	}

	return c.File(thumbPath)
}

func (h *Handler) MediaOriginal(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}

	resolved, err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.ImageExtensions)
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

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

	duration, err := h.streaming.GetVideoDuration(resolved)
	if err != nil {
		return respondInternalError(c, err)
	}

	return c.JSON(http.StatusOK, map[string]interface{}{
		"duration": duration,
	})
}

