package handler

import (
	"archive/zip"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
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
	setJsonCacheBrief(c)
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
		return respondError(c, http.StatusBadRequest, "path required")
	}

	if strings.HasSuffix(rawPath, "/download") {
		return h.DownloadFolderZip(c)
	}

	if strings.HasSuffix(rawPath, "/files") {
		pathStr, err := decodeWildcardPath(rawPath, "/files")
		if err != nil {
			return respondError(c, http.StatusBadRequest, err.Error())
		}

		pathStr, err = service.ResolveBrowsePath(pathStr, h.cfg.Scan.GetRoots())
		if err != nil {
			return respondError(c, http.StatusForbidden, "access denied")
		}

		// A2.2: 从 scanner cacheByDir 直接查目标目录的直接子文件。
		// 替代原 GetCached + 全量遍历 + IsPathWithinRoots 过滤（50k 文件 ~5ms）。
		// 行为变化：原实现返回递归子目录文件，新实现只返回直接子文件
		// （客户端 BrowseScreen 进入目录后期望只看当前目录的文件）。
		// GetCachedByDir 内部对结果按 Name 字典序排序，给前端稳定视图。
		matchedFiles, err := h.scanner.GetCachedByDir(
			c.Request().Context(),
			h.cfg.Scan.GetRoots(),
			pathStr,
		)
		if err != nil {
			return respondInternalError(c, err)
		}

		// Optional pagination (page_size <= 0 = legacy full return; the
		// deterministic Name ordering makes paging stable across requests).
		if pageSize, _ := strconv.Atoi(c.QueryParam("page_size")); pageSize > 0 {
			page, _ := strconv.Atoi(c.QueryParam("page"))
			if page < 1 {
				page = 1
			}
			start, end := paginateBounds(len(matchedFiles), page, pageSize)
			matchedFiles = matchedFiles[start:end]
		}

		setJsonCacheBrief(c)
		return c.JSON(http.StatusOK, matchedFiles)
	}

	pathStr, err := decodeWildcardPath(rawPath, "/browse")
	if err != nil {
		return respondError(c, http.StatusBadRequest, err.Error())
	}

	// ResolveBrowsePath enforces the within-roots boundary AND rejects reparse
	// points (junctions/symlinks) below the root, so a link planted inside a
	// library cannot escape the configured roots for directory listing.
	pathStr, err = service.ResolveBrowsePath(pathStr, h.cfg.Scan.GetRoots())
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	fi, err := os.Stat(pathStr)
	if err != nil {
		if os.IsNotExist(err) {
			return respondNotFound(c, "path not found")
		}
		// Do not echo the raw OS error: it embeds the absolute path.
		return respondError(c, http.StatusBadRequest, "path not accessible")
	}

	if !fi.IsDir() {
		return respondError(c, http.StatusBadRequest, "not a directory")
	}

	entries, err := os.ReadDir(pathStr)
	if err != nil {
		return respondInternalError(c, err)
	}

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
			mediaType := h.classifyMediaType(ext)
			if mediaType == "" {
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

	// Server-side sorting mirrors the Android client's BrowseSorter so that
	// pagination is stable and consecutive pages compose into the exact order
	// the client used to compute locally. Defaults (sort=name, order=asc)
	// match the natural readdir order, keeping legacy behavior unchanged.
	sortField := strings.ToLower(c.QueryParam("sort"))
	order := strings.ToLower(c.QueryParam("order"))
	service.SortMediaFiles(files, sortField, order)

	// Optional pagination on the files slice (folders are always returned in
	// full — they are few and drive navigation). page_size <= 0 = legacy full
	// return.
	totalFiles := len(files)
	page, _ := strconv.Atoi(c.QueryParam("page"))
	if page < 1 {
		page = 1
	}
	pageSize, _ := strconv.Atoi(c.QueryParam("page_size"))
	if pageSize > 0 {
		start, end := paginateBounds(len(files), page, pageSize)
		files = files[start:end]
	}

	setJsonCacheBrief(c)
	return c.JSON(http.StatusOK, models.BrowseResult{
		CurrentPath: pathStr,
		Folders:     folders,
		Files:       files,
		HasMore:     pageSize > 0 && page*pageSize < totalFiles,
	})
}

func (h *Handler) DownloadFolderZip(c echo.Context) error {
	rawPath := c.Param("*")
	pathStr, err := decodeWildcardPath(rawPath, "/download")
	if err != nil {
		return respondError(c, http.StatusBadRequest, err.Error())
	}

	// ResolveBrowsePath: within-roots + no reparse points below the root
	// boundary, so a junction inside the library cannot be zipped to leak
	// out-of-library content.
	pathStr, err = service.ResolveBrowsePath(pathStr, h.cfg.Scan.GetRoots())
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	fi, err := os.Stat(pathStr)
	if err != nil {
		if os.IsNotExist(err) {
			return respondNotFound(c, "path not found")
		}
		// Do not echo the raw OS error: it embeds the absolute path.
		return respondError(c, http.StatusBadRequest, "path not accessible")
	}

	if !fi.IsDir() {
		return respondError(c, http.StatusBadRequest, "not a directory")
	}

	c.Response().Header().Set(echo.HeaderContentType, "application/zip")
	c.Response().Header().Set(echo.HeaderContentDisposition, fmt.Sprintf("attachment; filename=%q.zip", filepath.Base(pathStr)))
	c.Response().WriteHeader(http.StatusOK)

	zipWriter := zip.NewWriter(c.Response().Writer)
	defer zipWriter.Close()

	err = filepath.Walk(pathStr, func(filePath string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() {
			return nil
		}

		ext := strings.ToLower(filepath.Ext(info.Name()))
		if h.classifyMediaType(ext) == "" {
			return nil
		}

		relPath, err := filepath.Rel(filepath.Dir(pathStr), filePath)
		if err != nil {
			return err
		}
		relPath = filepath.ToSlash(relPath)

		// Per-file anonymous scope: defer Close runs after each file's copy,
		// not accumulated until the whole Walk ends (which exhausted FDs on
		// large folders). Method stays Store (media is already compressed).
		return func() error {
			fileToZip, err := os.Open(filePath)
			if err != nil {
				return err
			}
			defer fileToZip.Close()

			header := &zip.FileHeader{
				Name:     relPath,
				Method:   zip.Store,
				Modified: info.ModTime(),
			}
			writer, err := zipWriter.CreateHeader(header)
			if err != nil {
				return err
			}

			_, err = io.Copy(writer, fileToZip)
			return err
		}()
	})

	// Response already started (status + headers written) above, so we can no
	// longer change the status code or write a JSON error body. Log the failure
	// and return nil so Echo doesn't try to (re)write an error response onto an
	// already-committed stream, which would corrupt the partial ZIP output.
	if err != nil {
		slog.Error("Zip download failed", "method", c.Request().Method, "path", c.Path(), "dir", pathStr, "error", err)
	}
	return nil
}

// classifyMediaType returns "video" / "image" / "text" for a lowercase file
// extension (with or without a leading dot — callers pass the result of
// filepath.Ext which always includes the dot, and the scanner maps store
// extensions in the same form). Returns "" for non-media files, which
// BrowseFolder / DownloadFolderZip treat as "skip this entry".
//
// The map lookups reuse the scanner's already-built extension sets so there
// is no per-entry allocation on the hot path.
func (h *Handler) classifyMediaType(ext string) string {
	ext = strings.ToLower(ext)
	if h.scanner.VideoExts()[ext] {
		return "video"
	}
	if h.scanner.ImageExts()[ext] {
		return "image"
	}
	if h.scanner.TextExts()[ext] {
		return "text"
	}
	return ""
}
