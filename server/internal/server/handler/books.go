package handler

import (
	"errors"
	"log/slog"
	"net/http"
	"net/url"
	"strconv"

	"github.com/labstack/echo/v4"

	"github.com/localmediahub/server/internal/service"
	"github.com/localmediahub/server/internal/service/bookparser"
)

// chapterResponse is the JSON body returned by GetBookChapter. The client
// renders Blocks in order: text blocks render as paragraphs, image blocks
// render as <img> tags whose Src is either a data: URI, an absolute http(s)
// URL, or a /api/v1/books/image endpoint URL (rewritten by BookService).
type chapterResponse struct {
	Title  string             `json:"title"`
	Blocks []bookparser.Block `json:"blocks"`
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
	blocks, err := h.books.GetChapterBlocks(c.Request().Context(), resolved, idx, c.RealIP())
	if err != nil {
		return mapBookError(c, err)
	}
	setJsonCacheBrief(c)
	return c.JSON(http.StatusOK, chapterResponse{
		Title:  b.Chapters[idx].Title,
		Blocks: blocks,
	})
}

// GetBookImage returns the raw bytes of a single image resource inside an
// epub, identified by its manifest id. Path is validated against scan roots
// + system allowed roots with the text-extension allow-list (epub is a text
// extension). Bytes are served with a 1-day browser cache via
// setMediaCacheHeaders. Used by reader clients rendering <img> tags whose
// Src was rewritten to /api/v1/books/image by BookService.GetChapterBlocks.
//
// Authentication (Round 32 Task 5): the preferred path is a ?sig= query
// parameter — an HMAC-SHA256 of (clientIP, path, manifestID) computed by
// BookSigner.SignImage, bound to this process's per-boot secret. The
// deprecated ?token=<bearer> query fallback remains for any client that has
// not yet migrated; hitting that path emits a slog.Warning so the operator
// can track migration. When the handler's bookSigner is nil (tests / open
// mode), neither check runs and the endpoint is gated solely by the
// BearerToken middleware wrapping the /books group.
func (h *Handler) GetBookImage(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}
	manifestID := c.QueryParam("manifest")
	if manifestID == "" {
		return respondError(c, http.StatusBadRequest, "manifest required")
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
	// Signature gate. The ?sig= path is preferred because it does not leak
	// the bearer token into logs/history/referer. The ?token= path is the
	// pre-Round-32 fallback kept for migration; it is logged so operators
	// can see when all clients have moved over.
	if h.bookSigner != nil {
		sig := c.QueryParam("sig")
		switch {
		case sig != "":
			if !h.bookSigner.VerifyImage(c.RealIP(), resolved, manifestID, sig) {
				return respondError(c, http.StatusUnauthorized, "invalid signature")
			}
		case c.QueryParam("token") != "":
			slog.Warn("[DEPRECATED] /books/image called with ?token=",
				"path", resolved,
				"manifest", manifestID,
			)
		default:
			return respondError(c, http.StatusUnauthorized, "signature required")
		}
	}
	if h.books == nil {
		return respondInternalError(c, errors.New("book service unavailable"))
	}
	data, contentType, err := h.books.ReadImageBytes(resolved, manifestID)
	if err != nil {
		return mapBookError(c, err)
	}
	setMediaCacheHeaders(c)
	return c.Blob(http.StatusOK, contentType, data)
}

// SignImage handles GET /api/v1/books/sign-image?path=...&manifest=... and
// returns {"src": "<signed url>"} for the given image resource. The signed
// URL embeds an HMAC bound to the requester's clientIP and is consumable by
// <img> tags that cannot set Authorization headers. Authenticated via the
// BearerToken middleware wrapping the /books group (header or ?token=
// fallback), so the signer itself never appears on the unauthenticated path.
//
// When the handler's bookSigner is nil (tests / open mode), the endpoint
// returns the unsigned URL — mirroring GetChapterBlocks' behaviour when no
// signer is wired.
func (h *Handler) SignImage(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}
	manifestID := c.QueryParam("manifest")
	if manifestID == "" {
		return respondError(c, http.StatusBadRequest, "manifest required")
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
	src := "/api/v1/books/image?path=" + url.QueryEscape(resolved) + "&manifest=" + url.QueryEscape(manifestID)
	if h.bookSigner != nil {
		sig := h.bookSigner.SignImage(c.RealIP(), resolved, manifestID)
		src += "&sig=" + sig
	}
	setJsonCacheBrief(c)
	return c.JSON(http.StatusOK, map[string]string{"src": src})
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
