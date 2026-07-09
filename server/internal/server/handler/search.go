package handler

import (
	"context"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
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

	matchedFolders, err := h.searchFoldersCached(c.Request().Context(), searchPath, query, limit)
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

// searchFoldersCached 从 scanner cache 中按 scope + query 过滤目录名。
// 替代原 searchFoldersCtx 的 WalkDir，从磁盘 IO 改为内存扫。
func (h *Handler) searchFoldersCached(ctx context.Context, scopedPath, query string, limit int) ([]models.Folder, error) {
	roots := h.cfg.Scan.GetRoots()
	scope := scopedPath
	if scope != "" && !strings.HasSuffix(scope, string(filepath.Separator)) {
		scope += string(filepath.Separator)
	}

	dirs, mtimes, err := h.scanner.GetCachedDirs(ctx, roots, scope)
	if err != nil {
		return nil, err
	}
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}

	lowerQuery := strings.ToLower(query)
	out := make([]models.Folder, 0, limit)
	isWindows := runtime.GOOS == "windows"
	for _, dir := range dirs {
		if ctx.Err() != nil {
			break
		}
		// 排除 scope 根自身（与原 WalkDir 在 path == root 时跳过一致）
		if scopedPath != "" {
			isRootSelf := false
			if isWindows {
				isRootSelf = strings.EqualFold(filepath.Clean(dir), filepath.Clean(scopedPath))
			} else {
				isRootSelf = filepath.Clean(dir) == filepath.Clean(scopedPath)
			}
			if isRootSelf {
				continue
			}
		}
		name := filepath.Base(dir)
		if !strings.Contains(strings.ToLower(name), lowerQuery) {
			continue
		}
		out = append(out, models.Folder{
			Name:         name,
			Path:         dir,
			RelativePath: dir,
			IsRoot:       false,
			ModifiedTime: mtimes[dir],
		})
		if len(out) >= limit {
			break
		}
	}
	return out, nil
}
