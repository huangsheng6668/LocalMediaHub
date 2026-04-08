package handler

import (
	"strings"

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
