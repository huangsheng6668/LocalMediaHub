package handler

import (
	"net/http"
	"os"
	"strconv"

	"github.com/labstack/echo/v4"

	"github.com/localmediahub/server/internal/models"
)

func (h *Handler) GetVideos(c echo.Context) error {
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

	videos := h.scanner.FilterByType(files, "video")
	total := len(videos)
	start := (page - 1) * pageSize
	end := start + pageSize
	if start >= total {
		start = total
	}
	if end > total {
		end = total
	}

	return c.JSON(http.StatusOK, models.PaginatedMediaFiles{
		Items:    videos[start:end],
		Total:    total,
		Page:     page,
		PageSize: pageSize,
		HasMore:  end < total,
	})
}

func (h *Handler) StreamVideo(c echo.Context) error {
	pathStr, err := decodeWildcardPath(c.Param("*"), "/stream")
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": err.Error()})
	}

	valid, err := h.streaming.ValidatePath(pathStr, h.cfg.Scan.GetRoots())
	if err != nil || !valid {
		return c.JSON(http.StatusForbidden, map[string]string{"error": "access denied"})
	}

	if err := h.streaming.ServeFile(c.Response().Writer, c.Request(), pathStr); err != nil {
		if os.IsNotExist(err) {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "file not found"})
		}
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	return nil
}
