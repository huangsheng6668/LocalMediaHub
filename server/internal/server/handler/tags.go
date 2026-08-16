package handler

import (
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"github.com/labstack/echo/v4"

	"github.com/localmediahub/server/internal/models"
	"github.com/localmediahub/server/internal/service"
)

type CreateTagRequest struct {
	Name  string `json:"name"`
	Color string `json:"color"`
}

func (h *Handler) GetTags(c echo.Context) error {
	setJsonCacheStandard(c)
	return c.JSON(http.StatusOK, h.tags.GetAllTags())
}

func (h *Handler) CreateTag(c echo.Context) error {
	var req CreateTagRequest
	if err := c.Bind(&req); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": err.Error()})
	}

	tag, err := h.tags.CreateTag(req.Name, req.Color)
	if err != nil {
		return c.JSON(http.StatusConflict, map[string]string{"error": err.Error()})
	}
	return c.JSON(http.StatusCreated, tag)
}

func (h *Handler) DeleteTag(c echo.Context) error {
	tagIdentifier := c.Param("tag_id")
	tagID, exists := h.tags.ResolveTagID(tagIdentifier)
	if !exists {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "tag not found"})
	}
	if err := h.tags.DeleteTag(tagID); err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	return c.NoContent(http.StatusNoContent)
}

func (h *Handler) AssociateTag(c echo.Context) error {
	tagIdentifier := c.Param("tag_id")
	pathStr := c.Param("*")

	tagID, exists := h.tags.ResolveTagID(tagIdentifier)
	if !exists {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "tag not found"})
	}

	// Security hardening: never persist a path that lies outside the
	// configured scan roots / system allowed roots. Previously the raw route
	// param was written to the DB verbatim, letting any caller pollute the
	// tags store with arbitrary strings.
	if !h.isPathWithinConfiguredRoots(pathStr) {
		return respondError(c, http.StatusForbidden, "path outside allowed directories")
	}

	associated, err := h.tags.AssociateFile(tagID, pathStr)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}

	if associated {
		return c.JSON(http.StatusCreated, map[string]string{"detail": "File tagged"})
	}
	return c.JSON(http.StatusCreated, map[string]string{"detail": "Already tagged"})
}

func (h *Handler) DisassociateTag(c echo.Context) error {
	tagIdentifier := c.Param("tag_id")
	pathStr := c.Param("*")

	tagID, exists := h.tags.ResolveTagID(tagIdentifier)
	if !exists {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "tag not found"})
	}

	if !h.isPathWithinConfiguredRoots(pathStr) {
		return respondError(c, http.StatusForbidden, "path outside allowed directories")
	}

	if err := h.tags.DisassociateFile(tagID, pathStr); err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	return c.JSON(http.StatusOK, map[string]string{"detail": "Tag removed from file"})
}

// isPathWithinConfiguredRoots reports whether pathStr lies inside the scan
// roots or the system allowed roots (lexical check — this is a DB-write guard,
// not a file read, so reparse-point traversal is not applicable here).
func (h *Handler) isPathWithinConfiguredRoots(pathStr string) bool {
	allRoots := append(append([]string{}, h.cfg.Scan.GetRoots()...), h.cfg.GetSystemAllowedRoots()...)
	if len(allRoots) == 0 {
		return false
	}
	valid, err := service.IsPathWithinRoots(pathStr, allRoots)
	return err == nil && valid
}

func (h *Handler) GetTaggedFiles(c echo.Context) error {
	tagIdentifier := c.Param("tag_id")
	tagID, exists := h.tags.ResolveTagID(tagIdentifier)
	if !exists {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "tag not found"})
	}
	files := h.tags.GetFilesForTag(tagID)
	if files == nil {
		files = []string{}
	}
	setJsonCacheStandard(c)
	return c.JSON(http.StatusOK, files)
}

func (h *Handler) GetTaggedMedia(c echo.Context) error {
	tagIdentifier := c.Param("tag_id")
	tagID, exists := h.tags.ResolveTagID(tagIdentifier)
	if !exists {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "tag not found"})
	}

	taggedPaths := h.tags.GetFilesForTag(tagID)
	if len(taggedPaths) == 0 {
		setJsonCacheStandard(c)
		return c.JSON(http.StatusOK, []models.MediaFile{})
	}

	cachedFiles, err := h.scanner.GetCached(c.Request().Context(), h.cfg.Scan.GetRoots())
	if err != nil {
		return respondInternalError(c, err)
	}

	byPath := make(map[string]models.MediaFile, len(cachedFiles))
	for _, file := range cachedFiles {
		byPath[file.Path] = file
	}

	result := make([]models.MediaFile, 0, len(taggedPaths))
	for _, taggedPath := range taggedPaths {
		if cached, ok := byPath[taggedPath]; ok {
			cached.RelativePath = cached.Path
			result = append(result, cached)
			continue
		}

		fallback, ok := h.buildTaggedMediaFallback(taggedPath)
		if ok {
			result = append(result, fallback)
		}
	}

	setJsonCacheStandard(c)
	return c.JSON(http.StatusOK, result)
}

// GetFileTags returns tags for all files, or specific files if paths are provided.
func (h *Handler) GetFileTags(c echo.Context) error {
	paths := c.QueryParams()["path"]
	if len(paths) == 0 {
		result := h.tags.GetAllFileTags()
		setJsonCacheStandard(c)
		return c.JSON(http.StatusOK, result)
	}
	result := h.tags.GetTagsForFiles(paths)
	setJsonCacheStandard(c)
	return c.JSON(http.StatusOK, result)
}

func (h *Handler) buildTaggedMediaFallback(pathStr string) (models.MediaFile, bool) {
	info, err := os.Stat(pathStr)
	if err != nil || info.IsDir() {
		return models.MediaFile{}, false
	}

	ext := strings.ToLower(filepath.Ext(pathStr))
	mediaType := ""
	switch {
	case h.scanner.VideoExts()[ext]:
		mediaType = "video"
	case h.scanner.ImageExts()[ext]:
		mediaType = "image"
	case h.scanner.TextExts()[ext]:
		mediaType = "text"
	default:
		return models.MediaFile{}, false
	}

	return models.MediaFile{
		Name:         filepath.Base(pathStr),
		Path:         pathStr,
		RelativePath: pathStr,
		Size:         info.Size(),
		ModifiedTime: info.ModTime(),
		MediaType:    mediaType,
		Extension:    ext,
	}, true
}
