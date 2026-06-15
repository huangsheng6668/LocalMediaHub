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
type Handler struct {
	cfg       *config.Config
	scanner   *service.Scanner
	tags      *service.TagsService
	streaming *service.StreamingService
	thumbnail *service.ThumbnailService
}

// New creates a Handler with all required service dependencies.
func New(
	cfg *config.Config,
	scanner *service.Scanner,
	tags *service.TagsService,
	streaming *service.StreamingService,
	thumbnail *service.ThumbnailService,
) *Handler {
	return &Handler{
		cfg:       cfg,
		scanner:   scanner,
		tags:      tags,
		streaming: streaming,
		thumbnail: thumbnail,
	}
}

// isMediaExt checks if a file extension is a configured video or image format.
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
	return false
}

// mediaExtensions returns all allowed media extensions (video + image).
func (h *Handler) mediaExtensions() []string {
	exts := make([]string, 0, len(h.cfg.Scan.VideoExtensions)+len(h.cfg.Scan.ImageExtensions))
	exts = append(exts, h.cfg.Scan.VideoExtensions...)
	exts = append(exts, h.cfg.Scan.ImageExtensions...)
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
