package handler

import (
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/labstack/echo/v4"
	"github.com/localmediahub/server/internal/models"
	"github.com/localmediahub/server/internal/service"
)

func (h *Handler) GetDrives(c echo.Context) error {
	roots := h.cfg.GetSystemAllowedRoots()
	if roots == nil {
		roots = []string{}
	}
	return c.JSON(http.StatusOK, roots)
}

func (h *Handler) SystemBrowse(c echo.Context) error {
	pathStr := c.QueryParam("path")

	if pathStr == "" {
		roots := h.cfg.GetSystemAllowedRoots()
		folders := make([]models.Folder, 0, len(roots))
		for _, root := range roots {
			fi, err := os.Stat(root)
			if err != nil {
				continue
			}
			if fi.IsDir() {
				folders = append(folders, models.Folder{
					Name:         filepath.Base(root),
					Path:         root,
					RelativePath: root,
					IsRoot:       true,
					ModifiedTime: fi.ModTime(),
				})
			}
		}
		return c.JSON(http.StatusOK, models.BrowseResult{
			CurrentPath: "",
			Folders:     folders,
			Files:       []models.MediaFile{},
		})
	}

	// Validate path is under an allowed root
	if err := service.ValidateSystemBrowseAllowed(pathStr, h.cfg.GetSystemAllowedRoots()); err != nil {
		return c.JSON(http.StatusForbidden, map[string]string{"error": err.Error()})
	}
	if err := service.ValidateSystemBrowsePath(pathStr); err != nil {
		return c.JSON(http.StatusForbidden, map[string]string{"error": err.Error()})
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

	folders := make([]models.Folder, 0)
	files := make([]models.MediaFile, 0)
	for _, entry := range entries {
		fullPath := filepath.Join(pathStr, entry.Name())
		if entry.IsDir() {
			info, _ := entry.Info()
			var modTime time.Time
			if info != nil {
				modTime = info.ModTime()
			}
			folders = append(folders, models.Folder{
				Name:         entry.Name(),
				Path:         fullPath,
				RelativePath: strings.TrimPrefix(fullPath, filepath.VolumeName(fullPath)),
				ModifiedTime: modTime,
			})
		} else {
			ext := strings.ToLower(filepath.Ext(entry.Name()))
			if h.isMediaExt(ext) {
				info, _ := entry.Info()
				var size int64
				var modTime time.Time
				if info != nil {
					size = info.Size()
					modTime = info.ModTime()
				}
				mediaType := "video"
				for _, imgExt := range h.cfg.Scan.ImageExtensions {
					if strings.EqualFold(ext, imgExt) {
						mediaType = "image"
						break
					}
				}
				files = append(files, models.MediaFile{
					Name:         entry.Name(),
					Path:         fullPath,
					RelativePath: fullPath,
					Size:         size,
					ModifiedTime: modTime,
					MediaType:    mediaType,
					Extension:    ext,
				})
			}
		}
	}

	return c.JSON(http.StatusOK, models.BrowseResult{
		CurrentPath: pathStr,
		Folders:     folders,
		Files:       files,
	})
}

func (h *Handler) SystemThumbnail(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "path required"})
	}

	if err := service.ValidateSystemPath(pathStr, h.mediaExtensions()); err != nil {
		return c.JSON(http.StatusForbidden, map[string]string{"error": err.Error()})
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

	if err := service.ValidateSystemPath(pathStr, h.mediaExtensions()); err != nil {
		return c.JSON(http.StatusForbidden, map[string]string{"error": err.Error()})
	}

	return c.File(pathStr)
}

func (h *Handler) SystemStream(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "path required"})
	}

	if err := service.ValidateSystemPath(pathStr, h.mediaExtensions()); err != nil {
		return c.JSON(http.StatusForbidden, map[string]string{"error": err.Error()})
	}

	if err := h.streaming.ServeFile(c.Response().Writer, c.Request(), pathStr); err != nil {
		if os.IsNotExist(err) {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "file not found"})
		}
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	return nil
}
