package handler

import (
	"net/http"
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

// ensure models import is used
var _ models.FileTag
