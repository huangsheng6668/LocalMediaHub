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

func (h *Handler) GetVideos(c echo.Context) error {
	page, _ := strconv.Atoi(c.QueryParam("page"))
	if page < 1 {
		page = 1
	}
	pageSize, _ := strconv.Atoi(c.QueryParam("page_size"))
	if pageSize < 1 {
		pageSize = 50
	}

	videos, err := h.scanner.GetCachedByType(c.Request().Context(), h.cfg.Scan.GetRoots(), "video")
	if err != nil {
		return respondInternalError(c, err)
	}

	start, end := paginateBounds(len(videos), page, pageSize)

	return c.JSON(http.StatusOK, models.PaginatedMediaFiles{
		Items:    videos[start:end],
		Total:    len(videos),
		Page:     page,
		PageSize: pageSize,
		HasMore:  end < len(videos),
	})
}

func (h *Handler) GetVideoAsset(c echo.Context) error {
	rawPath := c.Param("*")
	if strings.HasSuffix(rawPath, "/thumbnail") {
		return h.GetVideoThumbnail(c)
	}
	return h.StreamVideo(c)
}

func (h *Handler) GetVideoThumbnail(c echo.Context) error {
	pathStr, err := decodeWildcardPath(c.Param("*"), "/thumbnail")
	if err != nil {
		return respondError(c, http.StatusBadRequest, err.Error())
	}

	resolved, err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.VideoExtensions)
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

	setMediaCacheHeaders(c)
	return c.File(thumbPath)
}

func (h *Handler) StreamVideo(c echo.Context) error {
	pathStr, err := decodeWildcardPath(c.Param("*"), "/stream")
	if err != nil {
		return respondError(c, http.StatusBadRequest, err.Error())
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
