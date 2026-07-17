package handler

import (
	"errors"
	"net/http"
	"strconv"
	"strings"

	"github.com/labstack/echo/v4"

	"github.com/localmediahub/server/internal/service"
	"github.com/localmediahub/server/internal/service/bookparser"
)

// chapterResponse is the JSON body returned by GetBookChapter. The client
// renders Content directly (already decoded UTF-8 text for txt, or extracted
// XHTML text content for epub).
type chapterResponse struct {
	Title   string `json:"title"`
	Content string `json:"content"`
}

// GetBookInfo parses the file at ?path=... and returns its Book metadata
// (format, title, chapter list). Supported formats are the configured
// cfg.Scan.TextExtensions (.txt / .epub today; .mobi / .azw3 return a Book
// with Format="unsupported" so the UI can show a "cannot open" state).
//
// Path access is validated against scan roots + system allowed roots with the
// text-extension allow-list, mirroring MediaOriginal / MediaStream security.
func (h *Handler) GetBookInfo(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}
	allowedExts := append([]string{}, h.cfg.Scan.TextExtensions...)
	resolved, err := service.ValidateAccessibleMediaPath(
		pathStr,
		h.cfg.Scan.GetRoots(),
		h.cfg.GetSystemAllowedRoots(),
		allowedExts,
	)
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}
	if h.books == nil {
		return respondInternalError(c, errors.New("book service unavailable"))
	}
	b, err := h.books.GetBook(resolved)
	if err != nil {
		return mapBookError(c, err)
	}
	setJsonCacheBrief(c)
	return c.JSON(http.StatusOK, b)
}

// GetBookChapter returns the text of a single chapter by zero-based index.
// Re-reads the source file (BookService does not retain chapter text in the
// cache), so very large text files still cost one disk read per chapter view.
func (h *Handler) GetBookChapter(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}
	idx, err := strconv.Atoi(c.QueryParam("index"))
	if err != nil || idx < 0 {
		return respondError(c, http.StatusBadRequest, "invalid index")
	}
	allowedExts := append([]string{}, h.cfg.Scan.TextExtensions...)
	resolved, err := service.ValidateAccessibleMediaPath(
		pathStr,
		h.cfg.Scan.GetRoots(),
		h.cfg.GetSystemAllowedRoots(),
		allowedExts,
	)
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}
	if h.books == nil {
		return respondInternalError(c, errors.New("book service unavailable"))
	}
	b, err := h.books.GetBook(resolved)
	if err != nil {
		return mapBookError(c, err)
	}
	if idx >= len(b.Chapters) {
		return respondError(c, http.StatusBadRequest, "index out of range")
	}
	blocks, err := b.ChapterBlocks(idx)
	if err != nil {
		return mapBookError(c, err)
	}
	// Temporarily join blocks back into a single string so chapterResponse
	// keeps its current shape. Task 8 replaces this with the real blocks
	// response and the new image endpoint.
	var sb strings.Builder
	for _, blk := range blocks {
		if blk.Type == "text" {
			if sb.Len() > 0 {
				sb.WriteString("\n\n")
			}
			sb.WriteString(blk.Value)
		} else if blk.Type == "image" {
			if sb.Len() > 0 {
				sb.WriteString("\n\n")
			}
			sb.WriteString("[图片]")
		}
	}
	setJsonCacheBrief(c)
	return c.JSON(http.StatusOK, chapterResponse{Title: b.Chapters[idx].Title, Content: sb.String()})
}

// mapBookError translates bookparser error sentinels to HTTP status codes.
// ErrTooLarge → 413, ErrInvalidEpub / ErrEncrypted → 422, ErrIoFailure and
// anything else → 500 (real error is logged via respondInternalError).
func mapBookError(c echo.Context, err error) error {
	switch {
	case errors.Is(err, bookparser.ErrTooLarge):
		return respondError(c, http.StatusRequestEntityTooLarge, "file too large")
	case errors.Is(err, bookparser.ErrInvalidEpub), errors.Is(err, bookparser.ErrEncrypted):
		return respondError(c, http.StatusUnprocessableEntity, "invalid ebook")
	case errors.Is(err, bookparser.ErrIoFailure):
		return respondInternalError(c, err)
	default:
		return respondInternalError(c, err)
	}
}
