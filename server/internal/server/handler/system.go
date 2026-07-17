package handler

import (
	"fmt"
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
	setJsonCacheStatic(c)
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

	// Resolve symlinks + enforce allowed_roots boundary in one step.
	resolved, err := service.ValidateSystemBrowse(pathStr, h.cfg.GetSystemAllowedRoots())
	if err != nil {
		if os.IsNotExist(err) {
			return respondNotFound(c, "path not found")
		}
		return respondError(c, http.StatusForbidden, "access denied")
	}

	fi, err := os.Stat(resolved)
	if err != nil {
		return respondInternalError(c, err)
	}

	if !fi.IsDir() {
		return respondError(c, http.StatusBadRequest, "not a directory")
	}

	entries, err := os.ReadDir(resolved)
	if err != nil {
		return respondInternalError(c, err)
	}

	folders := make([]models.Folder, 0)
	files := make([]models.MediaFile, 0)
	for _, entry := range entries {
		fullPath := filepath.Join(resolved, entry.Name())
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
				matched := false
				for _, imgExt := range h.cfg.Scan.ImageExtensions {
					if strings.EqualFold(ext, imgExt) {
						mediaType = "image"
						matched = true
						break
					}
				}
				if !matched {
					for _, txtExt := range h.cfg.Scan.TextExtensions {
						if strings.EqualFold(ext, txtExt) {
							mediaType = "text"
							break
						}
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
		CurrentPath: resolved,
		Folders:     folders,
		Files:       files,
	})
}

func (h *Handler) SystemThumbnail(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}

	resolved, err := service.ValidateSystemMediaAccess(pathStr, h.cfg.GetSystemAllowedRoots(), h.mediaExtensions())
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	thumbBytes, err := h.thumbnail.GenerateSystemThumbnailBytes(resolved)
	if err != nil {
		if os.IsNotExist(err) {
			return respondNotFound(c, "file not found")
		}
		return respondInternalError(c, err)
	}

	setMediaCacheHeaders(c)
	return c.Blob(http.StatusOK, "image/jpeg", thumbBytes)
}

func (h *Handler) SystemOriginal(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}

	resolved, err := service.ValidateSystemMediaAccess(pathStr, h.cfg.GetSystemAllowedRoots(), h.mediaExtensions())
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	setMediaCacheHeaders(c)
	return c.File(resolved)
}

func (h *Handler) SystemStream(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}

	resolved, err := service.ValidateSystemMediaAccess(pathStr, h.cfg.GetSystemAllowedRoots(), h.mediaExtensions())
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

type DeleteRequest struct {
	Path      string `json:"path"`
	Recursive bool   `json:"recursive"`
}

func (h *Handler) DeletePath(c echo.Context) error {
	if !h.cfg.System.EnableDelete {
		return respondError(c, http.StatusForbidden, "remote deletion is disabled")
	}

	var req DeleteRequest
	if err := c.Bind(&req); err != nil {
		return respondError(c, http.StatusBadRequest, "invalid request body", err)
	}

	if req.Path == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}

	allRoots := append(append([]string{}, h.cfg.Scan.GetRoots()...), h.cfg.GetSystemAllowedRoots()...)
	resolved, err := service.ValidateDeletion(req.Path, allRoots)
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	fi, err := os.Stat(resolved)
	if err != nil {
		if os.IsNotExist(err) {
			return respondNotFound(c, "path not found")
		}
		return respondInternalError(c, err)
	}

	if fi.IsDir() {
		if !req.Recursive {
			return respondError(c, http.StatusBadRequest, "cannot delete a non-empty directory without recursive flag")
		}
		if err := os.RemoveAll(resolved); err != nil {
			return respondInternalError(c, fmt.Errorf("failed to delete directory: %w", err))
		}
	} else {
		if err := os.Remove(resolved); err != nil {
			return respondInternalError(c, fmt.Errorf("failed to delete file: %w", err))
		}
	}

	_ = h.tags.CleanDeletedPath(resolved)
	h.scanner.InvalidateCache()

	return c.JSON(http.StatusOK, map[string]string{"message": "deleted successfully"})
}
