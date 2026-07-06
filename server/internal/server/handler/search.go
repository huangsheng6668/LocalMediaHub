package handler

import (
	"context"
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
		return respondError(c, http.StatusBadRequest, "query required")
	}

	limit, _ := strconv.Atoi(c.QueryParam("limit"))
	if limit < 1 {
		limit = 50
	}

	searchPath := strings.TrimSpace(c.QueryParam("path"))
	if searchPath != "" {
		normalizedPath, err := service.NormalizePath(searchPath)
		if err != nil {
			return respondError(c, http.StatusBadRequest, err.Error())
		}

		valid, err := service.IsPathWithinRoots(normalizedPath, h.cfg.Scan.GetRoots())
		if err != nil {
			return respondError(c, http.StatusBadRequest, err.Error())
		}
		if !valid {
			return respondError(c, http.StatusForbidden, "path outside roots")
		}

		info, err := os.Stat(normalizedPath)
		if err != nil {
			if os.IsNotExist(err) {
				return respondNotFound(c, "path not found")
			}
			return respondError(c, http.StatusBadRequest, err.Error())
		}
		if !info.IsDir() {
			return respondError(c, http.StatusBadRequest, "path must be a directory")
		}

		searchPath = normalizedPath
	}

	files, err := h.scanner.GetCached(c.Request().Context(), h.cfg.Scan.GetRoots())
	if err != nil {
		return respondInternalError(c, err)
	}

	matchedFolders, err := h.searchFoldersCtx(c.Request().Context(), searchPath, query, limit)
	if err != nil {
		return respondInternalError(c, err)
	}

	matchedFiles := h.searchFiles(files, searchPath, query, limit)

	setJsonCacheBrief(c)
	return c.JSON(http.StatusOK, models.SearchResult{
		Query:   query,
		Folders: matchedFolders,
		Files:   matchedFiles,
	})
}

func (h *Handler) searchFiles(files []models.MediaFile, scopedPath, query string, limit int) []models.MediaFile {
	lowerQuery := strings.ToLower(query)
	matchedFiles := make([]models.MediaFile, 0, limit)

	// scopedPath 已在 handler 归一化；预算前缀（仅当无尾分隔符才补，正确处理盘根 D:\），
	// 逐文件 HasPrefix 替代每文件 IsPathWithinRoots（消除双 NormalizePath + Rel）。
	var scopePrefix string
	if scopedPath != "" {
		scopePrefix = scopedPath
		if !strings.HasSuffix(scopePrefix, string(filepath.Separator)) {
			scopePrefix += string(filepath.Separator)
		}
	}

	for _, file := range files {
		if scopePrefix != "" && !strings.HasPrefix(file.Path, scopePrefix) {
			continue
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
	return h.searchFoldersCtx(context.Background(), scopedPath, query, limit)
}

// searchFoldersCtx walks the roots looking for folders whose name matches the
// query. The ctx lets the walk abort early when the request is cancelled, so a
// slow search doesn't keep eating IO after the client has disconnected.
func (h *Handler) searchFoldersCtx(ctx context.Context, scopedPath, query string, limit int) ([]models.Folder, error) {
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
			// Abort the walk as soon as the request context is cancelled.
			select {
			case <-ctx.Done():
				return filepath.SkipAll
			default:
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
		if ctx.Err() != nil {
			break
		}
	}

	return matchedFolders, nil
}
