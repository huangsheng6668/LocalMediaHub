package handler

import (
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/labstack/echo/v4"

	"github.com/localmediahub/server/internal/models"
	"github.com/localmediahub/server/internal/service"
)

func (h *Handler) Search(c echo.Context) error {
	query := strings.TrimSpace(c.QueryParam("q"))
	if query == "" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "query required"})
	}

	limit, _ := strconv.Atoi(c.QueryParam("limit"))
	if limit < 1 {
		limit = 50
	}

	searchPath := strings.TrimSpace(c.QueryParam("path"))
	if searchPath != "" {
		normalizedPath, err := service.NormalizePath(searchPath)
		if err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": err.Error()})
		}

		valid, err := service.IsPathWithinRoots(normalizedPath, h.cfg.Scan.GetRoots())
		if err != nil {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": err.Error()})
		}
		if !valid {
			return c.JSON(http.StatusForbidden, map[string]string{"error": "path outside roots"})
		}

		info, err := os.Stat(normalizedPath)
		if err != nil {
			if os.IsNotExist(err) {
				return c.JSON(http.StatusNotFound, map[string]string{"error": "path not found"})
			}
			return c.JSON(http.StatusBadRequest, map[string]string{"error": err.Error()})
		}
		if !info.IsDir() {
			return c.JSON(http.StatusBadRequest, map[string]string{"error": "path must be a directory"})
		}

		searchPath = normalizedPath
	}

	files, err := h.scanner.GetCached(h.cfg.Scan.GetRoots())
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}

	matchedFolders, err := h.searchFolders(searchPath, query, limit)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}

	matchedFiles := h.searchFiles(files, searchPath, query, limit)

	return c.JSON(http.StatusOK, models.SearchResult{
		Query:   query,
		Folders: matchedFolders,
		Files:   matchedFiles,
	})
}

func (h *Handler) searchFiles(files []models.MediaFile, scopedPath, query string, limit int) []models.MediaFile {
	lowerQuery := strings.ToLower(query)
	matchedFiles := make([]models.MediaFile, 0, limit)

	for _, file := range files {
		if scopedPath != "" {
			ok, err := service.IsPathWithinRoots(file.Path, []string{scopedPath})
			if err != nil || !ok {
				continue
			}
		}

		if !strings.Contains(strings.ToLower(file.Name), lowerQuery) {
			continue
		}

		matched := file
		matched.RelativePath = file.Path
		matchedFiles = append(matchedFiles, matched)
		if len(matchedFiles) >= limit {
			break
		}
	}

	return matchedFiles
}

func (h *Handler) searchFolders(scopedPath, query string, limit int) ([]models.Folder, error) {
	searchRoots := h.cfg.Scan.GetRoots()
	if scopedPath != "" {
		searchRoots = []string{scopedPath}
	}

	lowerQuery := strings.ToLower(query)
	matchedFolders := make([]models.Folder, 0, limit)

	for _, root := range searchRoots {
		err := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
			if err != nil {
				return nil
			}
			if len(matchedFolders) >= limit {
				return filepath.SkipAll
			}
			if !d.IsDir() || path == root {
				return nil
			}
			if !strings.Contains(strings.ToLower(d.Name()), lowerQuery) {
				return nil
			}

			info, err := d.Info()
			if err != nil {
				return nil
			}

			matchedFolders = append(matchedFolders, models.Folder{
				Name:         d.Name(),
				Path:         path,
				RelativePath: path,
				IsRoot:       false,
				ModifiedTime: info.ModTime(),
			})
			return nil
		})
		if err != nil && err != filepath.SkipAll {
			return nil, err
		}
		if len(matchedFolders) >= limit {
			break
		}
	}

	return matchedFolders, nil
}
