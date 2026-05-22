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

type DeleteRequest struct {
	Path      string `json:"path"`
	Recursive bool   `json:"recursive"`
}

func (h *Handler) isAllowedToDelete(pathStr string) error {
	absPath, err := service.NormalizePath(pathStr)
	if err != nil {
		return err
	}

	lowerPath := strings.ToLower(absPath)
	blockedPaths := []string{
		"windows", "winnt", "system32", "syswow64", "$recycle.bin", "system volume information",
		"program files", "program files (x86)", "users", "boot",
	}
	for _, blocked := range blockedPaths {
		sep := string(filepath.Separator)
		if strings.Contains(lowerPath, sep+blocked+sep) || strings.Contains(lowerPath, sep+blocked) {
			return fmt.Errorf("access denied: restricted directory")
		}
	}

	scanRoots := h.cfg.Scan.GetRoots()
	allowedRoots := h.cfg.GetSystemAllowedRoots()

	var allRoots []string
	allRoots = append(allRoots, scanRoots...)
	allRoots = append(allRoots, allowedRoots...)

	isWithin := false
	for _, root := range allRoots {
		absRoot, err := service.NormalizePath(root)
		if err != nil {
			continue
		}

		rel, err := filepath.Rel(absRoot, absPath)
		if err != nil {
			continue
		}

		if rel == "." {
			return fmt.Errorf("access denied: cannot delete a root directory")
		}

		if rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
			isWithin = true
		}
	}

	if !isWithin {
		return fmt.Errorf("access denied: path outside allowed directories")
	}

	return nil
}

func (h *Handler) DeletePath(c echo.Context) error {
	if !h.cfg.System.EnableDelete {
		return c.JSON(http.StatusForbidden, map[string]string{"error": "remote deletion is disabled on the server"})
	}

	var req DeleteRequest
	if err := c.Bind(&req); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid request body"})
	}

	if req.Path == "" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "path required"})
	}

	absPath, err := service.NormalizePath(req.Path)
	if err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": err.Error()})
	}

	if err := h.isAllowedToDelete(absPath); err != nil {
		return c.JSON(http.StatusForbidden, map[string]string{"error": err.Error()})
	}

	fi, err := os.Stat(absPath)
	if err != nil {
		if os.IsNotExist(err) {
			return c.JSON(http.StatusNotFound, map[string]string{"error": "path not found"})
		}
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}

	if fi.IsDir() {
		if !req.Recursive {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "cannot delete a non-empty directory without recursive flag"})
		}
		if err := os.RemoveAll(absPath); err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": fmt.Sprintf("failed to delete directory: %v", err)})
		}
	} else {
		if err := os.Remove(absPath); err != nil {
			return c.JSON(http.StatusInternalServerError, map[string]string{"error": fmt.Sprintf("failed to delete file: %v", err)})
		}
	}

	_ = h.tags.CleanDeletedPath(absPath)
	h.scanner.InvalidateCache()

	return c.JSON(http.StatusOK, map[string]string{"message": "deleted successfully"})
}
