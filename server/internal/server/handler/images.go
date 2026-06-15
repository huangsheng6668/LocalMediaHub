package handler

import (
	"net/http"
	"os"
	"strconv"
	"strings"

	"github.com/labstack/echo/v4"

	"github.com/localmediahub/server/internal/models"
	"github.com/localmediahub/server/internal/service"
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

	files, err := h.scanner.GetCached(c.Request().Context(), h.cfg.Scan.GetRoots())
	if err != nil {
		return respondInternalError(c, err)
	}

	images := h.scanner.FilterByType(files, "image")
	start, end := paginateBounds(len(images), page, pageSize)

	return c.JSON(http.StatusOK, models.PaginatedMediaFiles{
		Items:    images[start:end],
		Total:    len(images),
		Page:     page,
		PageSize: pageSize,
		HasMore:  end < len(images),
	})
}

func (h *Handler) GetImageAsset(c echo.Context) error {
	rawPath := c.Param("*")
	if strings.HasSuffix(rawPath, "/original") {
		return h.GetOriginal(c)
	}
	return h.GetThumbnail(c)
}

func (h *Handler) GetThumbnail(c echo.Context) error {
	pathStr, err := decodeWildcardPath(c.Param("*"), "/thumbnail")
	if err != nil {
		return respondError(c, http.StatusBadRequest, err.Error())
	}

	if err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.ImageExtensions); err != nil {
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

func (h *Handler) GetOriginal(c echo.Context) error {
	pathStr, err := decodeWildcardPath(c.Param("*"), "/original")
	if err != nil {
		return respondError(c, http.StatusBadRequest, err.Error())
	}

	if err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.ImageExtensions); err != nil {
		return respondError(c, http.StatusForbidden, err.Error())
	}

	return c.File(pathStr)
}
