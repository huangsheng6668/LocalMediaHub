package handler

import (
	"encoding/base64"
	"fmt"
	"math"
	"net/http"
	"os"
	"strconv"

	"github.com/labstack/echo/v4"

	"github.com/localmediahub/server/internal/service"
)

// maxBatchThumbnails caps the number of paths per batch thumbnail request,
// bounding both the server-side generation work and the response size
// (thumbnails are returned as base64 JPEG).
const maxBatchThumbnails = 64

// ThumbnailsRequest is the JSON body for POST /api/v1/media/thumbnails.
type ThumbnailsRequest struct {
	Paths []string `json:"paths"`
}

// ThumbnailItem is one per-path result: either a base64 JPEG thumbnail or an
// error string (per-item failures do not fail the whole batch).
type ThumbnailItem struct {
	Path      string `json:"path"`
	Thumbnail string `json:"thumbnail,omitempty"`
	Error     string `json:"error,omitempty"`
}

// MediaThumbnails serves thumbnails for up to maxBatchThumbnails media paths
// in a single round-trip, collapsing the N+1 request pattern of grid UIs
// (each card previously fetched its own /media/thumbnail). Thumbnails are
// returned as base64-encoded JPEG so the response stays a single JSON body.
// Per-path validation mirrors MediaThumbnail (ValidateAccessibleMediaPath).
func (h *Handler) MediaThumbnails(c echo.Context) error {
	var req ThumbnailsRequest
	if err := c.Bind(&req); err != nil {
		return respondError(c, http.StatusBadRequest, "invalid request body", err)
	}
	if len(req.Paths) == 0 {
		return respondError(c, http.StatusBadRequest, "paths required")
	}
	if len(req.Paths) > maxBatchThumbnails {
		return respondError(c, http.StatusBadRequest,
			fmt.Sprintf("at most %d paths per request", maxBatchThumbnails))
	}

	allowedExts := append(h.cfg.Scan.ImageExtensions, h.cfg.Scan.VideoExtensions...)
	items := make([]ThumbnailItem, 0, len(req.Paths))
	for _, p := range req.Paths {
		resolved, err := service.ValidateAccessibleMediaPath(p, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), allowedExts)
		if err != nil {
			items = append(items, ThumbnailItem{Path: p, Error: "access denied"})
			continue
		}
		thumbBytes, err := h.thumbnail.GenerateThumbnailBytes(resolved)
		if err != nil {
			if os.IsNotExist(err) {
				items = append(items, ThumbnailItem{Path: p, Error: "file not found"})
			} else {
				items = append(items, ThumbnailItem{Path: p, Error: "thumbnail failed"})
			}
			continue
		}
		items = append(items, ThumbnailItem{
			Path:      p,
			Thumbnail: base64.StdEncoding.EncodeToString(thumbBytes),
		})
	}
	return c.JSON(http.StatusOK, map[string]interface{}{"items": items})
}

func (h *Handler) MediaThumbnail(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}

	allowedExts := append(h.cfg.Scan.ImageExtensions, h.cfg.Scan.VideoExtensions...)
	resolved, err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), allowedExts)
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	thumbBytes, err := h.thumbnail.GenerateThumbnailBytes(resolved)
	if err != nil {
		if os.IsNotExist(err) {
			return respondNotFound(c, "file not found")
		}
		return respondInternalError(c, err)
	}

	return serveThumbnailBytes(c, resolved, thumbBytes)
}

func (h *Handler) MediaOriginal(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}

	// Text extensions added in Task 8 so /api/v1/media/original can serve raw
	// .txt / .epub bytes for client-side download (the /api/v1/books chapter
	// endpoint is for rendering, this one is for "save as" / share flows).
	allowedExts := make([]string, 0, len(h.cfg.Scan.ImageExtensions)+len(h.cfg.Scan.TextExtensions))
	allowedExts = append(allowedExts, h.cfg.Scan.ImageExtensions...)
	allowedExts = append(allowedExts, h.cfg.Scan.TextExtensions...)
	resolved, err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), allowedExts)
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	setMediaCacheHeaders(c)
	return c.File(resolved)
}

func (h *Handler) MediaStream(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}

	resolved, err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.VideoExtensions)
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	if err := h.streaming.ServeFile(c.Response().Writer, c.Request(), resolved); err != nil {
		if os.IsNotExist(err) {
			return respondNotFound(c, "file not found")
		}
		return respondInternalError(c, err)
	}
	return nil
}

// parseHlsStartSec reads the optional ?start= seek anchor shared by the HLS
// playlist and segment endpoints. Bounds mirror the legacy transcode pipe's
// start parameter (0..86400, NaN/parse errors rejected); fractional values
// floor to whole seconds — ffmpeg -ss anchors are second-granular and the
// client-side absolute-timeline math rounds the same way.
func parseHlsStartSec(c echo.Context) (int64, error) {
	s := c.QueryParam("start")
	if s == "" {
		return 0, nil
	}
	f, err := strconv.ParseFloat(s, 64)
	if err != nil || math.IsNaN(f) || f < 0 || f > 86400 {
		return 0, fmt.Errorf("invalid start parameter")
	}
	return int64(f), nil
}

// MediaHlsPlaylist starts (or joins) the HLS transcode session for the
// given video and serves its playlist (spec 2026-09-03-hls-transcode).
// The playlist grows until the transcode completes, so it is served
// no-cache; players refetch it as playback progresses. ?start= anchors
// the session at that seek offset (spec 2026-09-06-hls-seek-restart).
func (h *Handler) MediaHlsPlaylist(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}
	startSec, err := parseHlsStartSec(c)
	if err != nil {
		return respondError(c, http.StatusBadRequest, "invalid start parameter")
	}
	resolved, err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.VideoExtensions)
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}
	fi, err := os.Stat(resolved)
	if err != nil {
		if os.IsNotExist(err) {
			return respondNotFound(c, "file not found")
		}
		return respondInternalError(c, err)
	}
	sess, err := h.streaming.GetOrCreateHlsSession(resolved, fi.ModTime(), startSec)
	if err != nil {
		return respondError(c, http.StatusServiceUnavailable, "transcode session unavailable", err)
	}
	// Serve the client-facing playlist: bare ffmpeg segment names are
	// rewritten to absolute segment-endpoint URLs (see ClientPlaylist),
	// carrying the session's start anchor so segment fetches dedup here.
	body, err := sess.ClientPlaylist(resolved, startSec)
	if err != nil {
		if os.IsNotExist(err) {
			return respondNotFound(c, "playlist not found")
		}
		return respondInternalError(c, err)
	}
	c.Response().Header().Set("Cache-Control", "no-cache")
	return c.Blob(http.StatusOK, "application/vnd.apple.mpegurl", body)
}

// MediaHlsSegment serves one strictly-validated HLS segment from the
// session directory. The name whitelist (segNNNNN.ts) makes traversal
// out of the session dir impossible; segments are immutable once written
// and served with long-lived cache headers.
func (h *Handler) MediaHlsSegment(c echo.Context) error {
	pathStr := c.QueryParam("path")
	name := c.QueryParam("name")
	if pathStr == "" || name == "" {
		return respondError(c, http.StatusBadRequest, "path and name required")
	}
	startSec, err := parseHlsStartSec(c)
	if err != nil {
		return respondError(c, http.StatusBadRequest, "invalid start parameter")
	}
	resolved, err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.VideoExtensions)
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}
	fi, err := os.Stat(resolved)
	if err != nil {
		if os.IsNotExist(err) {
			return respondNotFound(c, "file not found")
		}
		return respondInternalError(c, err)
	}
	// Dedup hit in the common case (the client fetched the playlist first,
	// whose rewritten segment URLs carry the same ?start= anchor); also
	// refreshes lastAccess so active playback keeps the idle reaper from
	// killing a running transcode.
	sess, err := h.streaming.GetOrCreateHlsSession(resolved, fi.ModTime(), startSec)
	if err != nil {
		return respondError(c, http.StatusServiceUnavailable, "transcode session unavailable", err)
	}
	segPath, ok := sess.SegmentPath(name)
	if !ok {
		return respondError(c, http.StatusBadRequest, "invalid segment name")
	}
	if _, err := os.Stat(segPath); err != nil {
		return respondNotFound(c, "segment not found")
	}
	setMediaCacheHeaders(c)
	return c.File(segPath)
}

func (h *Handler) MediaDuration(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}

	resolved, err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.VideoExtensions)
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	// 优先从缩略图服务的时长缓存查询（共享 durations.json，避免重复 fork ffprobe）。
	// Cache miss 时 fallback 到 streaming.GetVideoDuration（会 fork ffprobe 并返回
	// error 表示失败，与历史行为一致）。
	duration, ok := h.thumbnail.VideoDuration(resolved)
	if !ok {
		var err error
		duration, err = h.streaming.GetVideoDuration(resolved)
		if err != nil {
			return respondInternalError(c, err)
		}
	}

	setJsonCacheStandard(c)
	return c.JSON(http.StatusOK, map[string]interface{}{
		"duration": duration,
	})
}
