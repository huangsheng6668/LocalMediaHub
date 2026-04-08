package handler

import (
	"net/http"

	"github.com/labstack/echo/v4"

	"github.com/localmediahub/server/internal/models"
)

func (h *Handler) Search(c echo.Context) error {
	query := c.QueryParam("q")
	if query == "" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "query required"})
	}

	files, err := h.scanner.GetCached(h.cfg.Scan.GetRoots())
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}

	matchedFiles := h.scanner.Search(files, query)

	return c.JSON(http.StatusOK, models.SearchResult{
		Query:   query,
		Folders: []models.Folder{},
		Files:   matchedFiles,
	})
}
