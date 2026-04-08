package handler

import (
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"github.com/labstack/echo/v4"
)

func (h *Handler) GetDrives(c echo.Context) error {
	var drives []string
	for _, letter := range "ABCDEFGHIJKLMNOPQRSTUVWXYZ" {
		path := string(letter) + ":\\"
		if _, err := os.Stat(path); err == nil {
			drives = append(drives, path)
		}
	}
	return c.JSON(http.StatusOK, map[string]interface{}{
		"drives": drives,
	})
}

func (h *Handler) SystemBrowse(c echo.Context) error {
	pathStr := c.QueryParam("path")

	if pathStr == "" {
		var drives []string
		for _, letter := range "ABCDEFGHIJKLMNOPQRSTUVWXYZ" {
			path := string(letter) + ":\\"
			if _, err := os.Stat(path); err == nil {
				drives = append(drives, path)
			}
		}
		return c.JSON(http.StatusOK, map[string]interface{}{
			"drives":  drives,
			"folders": []string{},
			"files":   []string{},
		})
	}

	fi, err := os.Stat(pathStr)
	if err != nil {
		if os.IsNotExist(err) {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "path not found"})
		}
		return c.JSON(http.StatusBadRequest, map[string]string{"error": err.Error()})
	}

	if !fi.IsDir() {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "not a directory"})
	}

	entries, err := os.ReadDir(pathStr)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}

	folders := make([]string, 0)
	files := make([]string, 0)
	for _, entry := range entries {
		fullPath := filepath.Join(pathStr, entry.Name())
		if entry.IsDir() {
			folders = append(folders, fullPath)
		} else {
			ext := strings.ToLower(filepath.Ext(entry.Name()))
			if h.isMediaExt(ext) {
				files = append(files, fullPath)
			}
		}
	}

	return c.JSON(http.StatusOK, map[string]interface{}{
		"current_path": pathStr,
		"folders":      folders,
		"files":        files,
	})
}

func (h *Handler) SystemThumbnail(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "path required"})
	}

	thumbPath, err := h.thumbnail.GenerateSystemThumbnail(pathStr)
	if err != nil {
		if os.IsNotExist(err) {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "file not found"})
		}
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}

	return c.File(thumbPath)
}

func (h *Handler) SystemOriginal(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "path required"})
	}

	return c.File(pathStr)
}

func (h *Handler) SystemStream(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "path required"})
	}

	if err := h.streaming.ServeFile(c.Response().Writer, c.Request(), pathStr); err != nil {
		if os.IsNotExist(err) {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "file not found"})
		}
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	return nil
}
