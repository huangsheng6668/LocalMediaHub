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
	if err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), allowedExts); err != nil {
		return respondError(c, http.StatusForbidden, err.Error())
	}

	thumbPath, err := h.thumbnail.GenerateThumbnail(pathStr)
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

	if err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.ImageExtensions); err != nil {
		return respondError(c, http.StatusForbidden, err.Error())
	}

	return c.File(pathStr)
}

func (h *Handler) MediaStream(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}

	if err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.VideoExtensions); err != nil {
		return respondError(c, http.StatusForbidden, err.Error())
	}

	if err := h.streaming.ServeFile(c.Response().Writer, c.Request(), pathStr); err != nil {
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

	if err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.VideoExtensions); err != nil {
		return respondError(c, http.StatusForbidden, err.Error())
	}

	duration, err := h.streaming.GetVideoDuration(pathStr)
	if err != nil {
		return respondInternalError(c, err)
	}

	return c.JSON(http.StatusOK, map[string]interface{}{
		"duration": duration,
	})
}

