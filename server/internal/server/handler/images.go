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

	images, err := h.scanner.GetCachedByType(c.Request().Context(), h.cfg.Scan.GetRoots(), "image")
	if err != nil {
		return respondInternalError(c, err)
	}

	start, end := paginateBounds(len(images), page, pageSize)

	setJsonCacheStandard(c)
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

	resolved, err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.ImageExtensions)
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

func (h *Handler) GetOriginal(c echo.Context) error {
	pathStr, err := decodeWildcardPath(c.Param("*"), "/original")
	if err != nil {
		return respondError(c, http.StatusBadRequest, err.Error())
	}

	resolved, err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.ImageExtensions)
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	setMediaCacheHeaders(c)
	return c.File(resolved)
}
