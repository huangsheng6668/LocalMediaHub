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
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "path required"})
	}

	if err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.ImageExtensions); err != nil {
		return c.JSON(http.StatusForbidden, map[string]string{"error": err.Error()})
	}

	thumbPath, err := h.thumbnail.GenerateThumbnail(pathStr)
	if err != nil {
		if os.IsNotExist(err) {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "file not found"})
		}
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}

	return c.File(thumbPath)
}

func (h *Handler) MediaOriginal(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "path required"})
	}

	if err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.ImageExtensions); err != nil {
		return c.JSON(http.StatusForbidden, map[string]string{"error": err.Error()})
	}

	return c.File(pathStr)
}

func (h *Handler) MediaStream(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "path required"})
	}

	if err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.VideoExtensions); err != nil {
		return c.JSON(http.StatusForbidden, map[string]string{"error": err.Error()})
	}

	if err := h.streaming.ServeFile(c.Response().Writer, c.Request(), pathStr); err != nil {
		if os.IsNotExist(err) {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "file not found"})
		}
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	return nil
}
