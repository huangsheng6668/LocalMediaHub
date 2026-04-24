package handler

import (
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"github.com/labstack/echo/v4"

	"github.com/localmediahub/server/internal/models"
)

type CreateTagRequest struct {
	Name  string `json:"name"`
	Color string `json:"color"`
}

func (h *Handler) GetTags(c echo.Context) error {
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
	tagID := c.Param("tag_id")
	if !h.tags.TagExists(tagID) {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "tag not found"})
	}
	if err := h.tags.DeleteTag(tagID); err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	return c.NoContent(http.StatusNoContent)
}

func (h *Handler) AssociateTag(c echo.Context) error {
	tagID := c.Param("tag_id")
	pathStr := c.Param("*")
	pathStr = strings.ReplaceAll(pathStr, "%2F", "/")

	if !h.tags.TagExists(tagID) {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "tag not found"})
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
	tagID := c.Param("tag_id")
	pathStr := c.Param("*")
	pathStr = strings.ReplaceAll(pathStr, "%2F", "/")

	if err := h.tags.DisassociateFile(tagID, pathStr); err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	return c.JSON(http.StatusOK, map[string]string{"detail": "Tag removed from file"})
}

func (h *Handler) GetTaggedFiles(c echo.Context) error {
	tagID := c.Param("tag_id")
	if !h.tags.TagExists(tagID) {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "tag not found"})
	}
	files := h.tags.GetFilesForTag(tagID)
	if files == nil {
		files = []string{}
	}
	return c.JSON(http.StatusOK, files)
}

func (h *Handler) GetTaggedMedia(c echo.Context) error {
	tagID := c.Param("tag_id")
	if !h.tags.TagExists(tagID) {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "tag not found"})
	}

	taggedPaths := h.tags.GetFilesForTag(tagID)
	if len(taggedPaths) == 0 {
		return c.JSON(http.StatusOK, []models.MediaFile{})
	}

	cachedFiles, err := h.scanner.GetCached(h.cfg.Scan.GetRoots())
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
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

	return c.JSON(http.StatusOK, result)
}

// ensure models import is used
var _ models.FileTag

// GetFileTags returns tags for all files, or specific files if paths are provided.
func (h *Handler) GetFileTags(c echo.Context) error {
	paths := c.QueryParams()["path"]
	if len(paths) == 0 {
		result := h.tags.GetAllFileTags()
		return c.JSON(http.StatusOK, result)
	}
	result := h.tags.GetTagsForFiles(paths)
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
