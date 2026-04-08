package handler

import (
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"github.com/labstack/echo/v4"

	"github.com/localmediahub/server/internal/models"
)

func (h *Handler) GetFolders(c echo.Context) error {
	folders := make([]models.Folder, 0)
	for _, root := range h.cfg.Scan.GetRoots() {
		fi, err := os.Stat(root)
		if err != nil {
			continue
		}
		folders = append(folders, models.Folder{
			Name:         fi.Name(),
			Path:         root,
			RelativePath: string(filepath.Separator),
			IsRoot:       true,
			ModifiedTime: fi.ModTime(),
		})
	}
	return c.JSON(http.StatusOK, folders)
}

func (h *Handler) BrowseFolder(c echo.Context) error {
	pathStr := c.Param("*")
	if pathStr == "" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "path required"})
	}

	pathStr = strings.ReplaceAll(pathStr, "%2F", "/")

	valid := false
	for _, root := range h.cfg.Scan.GetRoots() {
		rel, err := filepath.Rel(root, pathStr)
		if err != nil {
			continue
		}
		if !strings.HasPrefix(rel, "..") {
			valid = true
			break
		}
	}
	if !valid {
		return c.JSON(http.StatusForbidden, map[string]string{"error": "path outside roots"})
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

	videoExts := h.scanner.VideoExts()
	imageExts := h.scanner.ImageExts()

	var folders []models.Folder
	var files []models.MediaFile

	for _, entry := range entries {
		fullPath := filepath.Join(pathStr, entry.Name())
		info, err := entry.Info()
		if err != nil {
			continue
		}

		if entry.IsDir() {
			folders = append(folders, models.Folder{
				Name:         entry.Name(),
				Path:         fullPath,
				RelativePath: strings.TrimPrefix(fullPath, filepath.Dir(pathStr)),
				IsRoot:       false,
				ModifiedTime: info.ModTime(),
			})
		} else {
			ext := strings.ToLower(filepath.Ext(entry.Name()))
			mediaType := ""
			if videoExts[ext] {
				mediaType = "video"
			} else if imageExts[ext] {
				mediaType = "image"
			} else {
				continue
			}

			files = append(files, models.MediaFile{
				Name:         entry.Name(),
				Path:         fullPath,
				RelativePath: strings.TrimPrefix(fullPath, filepath.Dir(pathStr)),
				Size:         info.Size(),
				ModifiedTime: info.ModTime(),
				MediaType:    mediaType,
				Extension:    ext,
			})
		}
	}

	return c.JSON(http.StatusOK, models.BrowseResult{
		CurrentPath: pathStr,
		Folders:     folders,
		Files:       files,
	})
}
