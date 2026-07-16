package handler

import (
	"log/slog"
	"net/http"
	"strings"

	"github.com/labstack/echo/v4"

	"github.com/localmediahub/server/internal/config"
	"github.com/localmediahub/server/internal/service"
)

// Handler holds references to all services needed by API handlers.
//
// books is added in Task 3 (text-reader) so handler.New can carry the
// BookService dependency. Task 3 only adds the slot; Task 7 constructs the
// actual BookService and Task 8 wires it into the /api/v1/books/* endpoints.
// Until then no code path dereferences h.books, keeping the build green even
// though every current caller passes nil.
type Handler struct {
	cfg       *config.Config
	scanner   *service.Scanner
	tags      *service.TagsService
	streaming *service.StreamingService
	thumbnail *service.ThumbnailService
	books     *service.BookService
}

// New creates a Handler with all required service dependencies.
//
// The books parameter is the 6th and last argument; production wiring lands in
// Task 8. Until then all callers (production server.go + every test) pass nil.
func New(
	cfg *config.Config,
	scanner *service.Scanner,
	tags *service.TagsService,
	streaming *service.StreamingService,
	thumbnail *service.ThumbnailService,
	books *service.BookService,
) *Handler {
	return &Handler{
		cfg:       cfg,
		scanner:   scanner,
		tags:      tags,
		streaming: streaming,
		thumbnail: thumbnail,
		books:     books,
	}
}

// isMediaExt checks if a file extension is a configured video, image, or text
// format. Text extensions were added in Task 3 (text-reader) so that browse,
// search, and download code paths treat .txt / .epub / .mobi / .azw3 files as
// media even though they have no thumbnail or streaming representation.
// Comparison is case-insensitive via strings.EqualFold, matching the existing
// video/image behavior.
func (h *Handler) isMediaExt(ext string) bool {
	for _, e := range h.cfg.Scan.VideoExtensions {
		if strings.EqualFold(ext, e) {
			return true
		}
	}
	for _, e := range h.cfg.Scan.ImageExtensions {
		if strings.EqualFold(ext, e) {
			return true
		}
	}
	for _, e := range h.cfg.Scan.TextExtensions {
		if strings.EqualFold(ext, e) {
			return true
		}
	}
	return false
}

// mediaExtensions returns all allowed media extensions
// (video + image + text). Text extensions were added in Task 3 so that folder
// listing and zip-download code paths pick up text files alongside video/image.
func (h *Handler) mediaExtensions() []string {
	exts := make([]string, 0, len(h.cfg.Scan.VideoExtensions)+len(h.cfg.Scan.ImageExtensions)+len(h.cfg.Scan.TextExtensions))
	exts = append(exts, h.cfg.Scan.VideoExtensions...)
	exts = append(exts, h.cfg.Scan.ImageExtensions...)
	exts = append(exts, h.cfg.Scan.TextExtensions...)
	return exts
}

// respondError replies with a JSON error envelope.
//
// For 4xx client errors `msg` is surfaced verbatim (it is an actionable,
// user-facing message such as "path required"). For 5xx server errors the
// caller should pass an internal error in `internalErr` — its detail is logged
// here and NOT sent to the client; the client only receives a generic message
// to avoid leaking filesystem paths or other server internals.
func respondError(c echo.Context, code int, msg string, internalErr ...error) error {
	if len(internalErr) > 0 && internalErr[0] != nil {
		slog.Error("Request failed", "method", c.Request().Method, "path", c.Path(), "status", code, "error", internalErr[0])
	}
	if code >= 500 {
		msg = "internal server error"
	}
	return c.JSON(code, map[string]string{"error": msg})
}

// respondInternalError is a convenience wrapper for 500s: it hides the real
// error from the client while logging it server-side.
func respondInternalError(c echo.Context, err error) error {
	return respondError(c, http.StatusInternalServerError, "internal server error", err)
}

// respondNotFound is a convenience wrapper for 404s.
func respondNotFound(c echo.Context, msg string) error {
	if msg == "" {
		msg = "not found"
	}
	return c.JSON(http.StatusNotFound, map[string]string{"error": msg})
}

// setMediaCacheHeaders marks a thumbnail/original response as browser-cacheable
// for one day. The thumbnail cache key includes the source file's modtime, so a
// changed source produces a new cache file with a different modtime and browsers
// revalidating via If-Modified-Since get a 200 — correct outside the max-age
// window. Not applied to stream endpoints (different Range semantics).
//
// Round 24 note: Coil 3.x on Android bypasses OkHttp's Cache and ignores these
// headers, but the embedded web gallery (browser <img> tags) still honors them.
// Kept for the web client's benefit.
func setMediaCacheHeaders(c echo.Context) {
	c.Response().Header().Set("Cache-Control", "public, max-age=86400")
}

// JSON Cache-Control policy tiers.
//
// JSON responses are 'private' (not for CDN/proxy caching) because they
// contain path / file metadata specific to this server's filesystem.
// Contrast with media endpoints which use 'public, max-age=86400' (Round 3).
const (
	// cacheBrief: 5s — endpoints that change when scan/add/delete files.
	// Kept short on purpose: clients re-fetch these URLs right after a
	// delete/add, and a long max-age made the list show stale entries
	// (deleted files still listed) until the cache expired.
	cacheBrief = "private, max-age=5"
	// cacheStandard: 300s — endpoints that change with tag operations / paging.
	cacheStandard = "private, max-age=300"
	// cacheStatic: 3600s — endpoints that almost never change.
	cacheStatic = "private, max-age=3600"
)

func setJsonCacheBrief(c echo.Context)    { c.Response().Header().Set("Cache-Control", cacheBrief) }
func setJsonCacheStandard(c echo.Context) { c.Response().Header().Set("Cache-Control", cacheStandard) }
func setJsonCacheStatic(c echo.Context)   { c.Response().Header().Set("Cache-Control", cacheStatic) }

// paginateBounds returns the [start, end) slice indices for a page-based
// pagination, clamped to [0, total]. Used by GetVideos / GetImages which
// previously duplicated this logic verbatim.
func paginateBounds(total, page, pageSize int) (start, end int) {
	start = (page - 1) * pageSize
	end = start + pageSize
	if start >= total {
		start = total
	}
	if end > total {
		end = total
	}
	return start, end
}
