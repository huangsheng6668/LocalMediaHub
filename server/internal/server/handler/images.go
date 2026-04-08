package handler

import (
	"net/http"
	"os"
	"strconv"
	"strings"

	"github.com/labstack/echo/v4"

	"github.com/localmediahub/server/internal/models"
)

func (h *Handler) GetImages(c echo.Context) error {
	page, _ := strconv.Atoi(c.QueryParam("page"))
	if page < 1 {
		page = 1
	}
	pageSize, _ := strconv.Atoi(c.QueryParam("page_size"))
	if pageSize < 1 {
		pageSize = 50
	}

	files, err := h.scanner.GetCached(h.cfg.Scan.GetRoots())
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}

	images := h.scanner.FilterByType(files, "image")
	total := len(images)
	start := (page - 1) * pageSize
	end := start + pageSize
	if start >= total {
		start = total
	}
	if end > total {
		end = total
	}

	return c.JSON(http.StatusOK, models.PaginatedMediaFiles{
		Items:    images[start:end],
		Total:    total,
		Page:     page,
		PageSize: pageSize,
		HasMore:  end < total,
	})
}

func (h *Handler) GetThumbnail(c echo.Context) error {
	pathStr := c.Param("*")
	pathStr = strings.ReplaceAll(pathStr, "%2F", "/")

	valid, err := h.thumbnail.ValidatePath(pathStr, h.cfg.Scan.GetRoots())
	if err != nil || !valid {
		return c.JSON(http.StatusForbidden, map[string]string{"error": "access denied"})
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

func (h *Handler) GetOriginal(c echo.Context) error {
	pathStr := c.Param("*")
	pathStr = strings.ReplaceAll(pathStr, "%2F", "/")

	valid, err := h.streaming.ValidatePath(pathStr, h.cfg.Scan.GetRoots())
	if err != nil || !valid {
		return c.JSON(http.StatusForbidden, map[string]string{"error": "access denied"})
	}

	return c.File(pathStr)
}
