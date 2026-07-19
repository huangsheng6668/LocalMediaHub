package handler

import (
	"net/http"
	"strconv"

	"github.com/labstack/echo/v4"

	"github.com/localmediahub/server/internal/models"
)

func (h *Handler) GetTexts(c echo.Context) error {
	page, _ := strconv.Atoi(c.QueryParam("page"))
	if page < 1 {
		page = 1
	}
	pageSize, _ := strconv.Atoi(c.QueryParam("page_size"))
	if pageSize < 1 {
		pageSize = 50
	}

	texts, err := h.scanner.GetCachedByType(c.Request().Context(), h.cfg.Scan.GetRoots(), "text")
	if err != nil {
		return respondInternalError(c, err)
	}

	start, end := paginateBounds(len(texts), page, pageSize)

	setJsonCacheStandard(c)
	return c.JSON(http.StatusOK, models.PaginatedMediaFiles{
		Items:    texts[start:end],
		Total:    len(texts),
		Page:     page,
		PageSize: pageSize,
		HasMore:  end < len(texts),
	})
}
