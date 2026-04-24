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

func (h *Handler) GetFolders(c echo.Context) error {
	folders := make([]models.Folder, 0)
	for _, root := range h.cfg.Scan.GetRoots() {
		fi, err := os.Stat(root)
		if err != nil {
			continue
		}
		folders = append(folders, models.Folder{
			Name:         folderDisplayName(root, fi.Name()),
			Path:         root,
			RelativePath: root,
			IsRoot:       true,
			ModifiedTime: fi.ModTime(),
		})
	}
	return c.JSON(http.StatusOK, folders)
}

func folderDisplayName(path string, name string) string {
	trimmed := strings.TrimSpace(name)
	if trimmed == "" || trimmed == string(filepath.Separator) {
		return filepath.Clean(path)
	}
	return name
}

func (h *Handler) BrowseFolder(c echo.Context) error {
	rawPath := c.Param("*")
	if rawPath == "" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "path required"})
	}
	pathStr, err := decodeWildcardPath(rawPath, "/browse")
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": err.Error()})
	}

	pathStr, err = service.NormalizePath(pathStr)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": err.Error()})
	}

	valid, err := service.IsPathWithinRoots(pathStr, h.cfg.Scan.GetRoots())
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": err.Error()})
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

	folders := make([]models.Folder, 0)
	files := make([]models.MediaFile, 0)

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
				RelativePath: fullPath,
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
				RelativePath: fullPath,
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
