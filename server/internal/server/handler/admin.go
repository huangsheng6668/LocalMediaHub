package handler

import (
	"fmt"
	"net/http"
	"path/filepath"

	"github.com/labstack/echo/v4"

	"github.com/localmediahub/server/internal/config"
	"github.com/localmediahub/server/internal/service"
)

type ConfigUpdateRequest struct {
	Roots []string `json:"roots"`
}

func (h *Handler) GetConfig(c echo.Context) error {
	return c.JSON(http.StatusOK, h.cfg.Public())
}

func (h *Handler) UpdateConfig(c echo.Context) error {
	var req ConfigUpdateRequest
	if err := c.Bind(&req); err != nil {
		return respondError(c, http.StatusBadRequest, "invalid request body", err)
	}

	// Roots must be absolute so they don't resolve against the server CWD.
	// Existence is NOT required (an external drive may be unmounted).
	for _, r := range req.Roots {
		if !filepath.IsAbs(r) {
			return respondError(c, http.StatusBadRequest, "scan roots must be absolute paths")
		}
		// Phase 8 T8-01: reject sensitive system directories as roots so an
		// operator (or attacker with the admin token) cannot turn C:\Windows
		// / D:\$Recycle.Bin etc. into a browseable media library.
		if service.IsBlockedRoot(r) {
			return respondError(c, http.StatusBadRequest,
				fmt.Sprintf("scan root %q matches a restricted system directory", r))
		}
	}

	if len(req.Roots) > config.MaxScanRoots {
		return respondError(c, http.StatusBadRequest, fmt.Sprintf("too many scan roots (max %d)", config.MaxScanRoots))
	}
	// Validate on a shallow copy before mutating live state.
	candidate := &config.Config{
		Server:    h.cfg.Server,
		Scan:      config.ScanConfig{Roots: req.Roots, AutoDetectRoots: h.cfg.Scan.AutoDetectRoots},
		Thumbnail: h.cfg.Thumbnail,
		System:    h.cfg.System,
		Transcode: h.cfg.Transcode,
		Debug:     h.cfg.Debug,
		BLE:       h.cfg.BLE,
	}
	if err := candidate.Validate(false); err != nil {
		return respondError(c, http.StatusBadRequest, fmt.Sprintf("invalid configuration: %v", err))
	}
	if err := h.cfg.Scan.SetRoots(req.Roots); err != nil {
		return respondError(c, http.StatusBadRequest, err.Error())
	}
	if err := h.cfg.Save("config.yaml"); err != nil {
		return respondInternalError(c, err)
	}

	h.scanner.InvalidateCache()
	_ = h.scanner.StartWatching(h.cfg.Scan.GetRoots())

	return c.JSON(http.StatusOK, h.cfg.Public())
}

// TranscodeStatus reports the transcode path state: active sessions, the
// concurrency cap, and the resolved encoder probe chain. Mounted under the
// admin group (Bearer-gated in token mode). The probe status is reported
// WITHOUT forcing a probe — an empty auto/usable means "not probed yet".
func (h *Handler) TranscodeStatus(c echo.Context) error {
	setJsonCacheBrief(c)
	return c.JSON(http.StatusOK, h.streaming.TranscodeStatus())
}

func (h *Handler) TriggerScan(c echo.Context) error {
	h.scanner.InvalidateCache()
	// Use the scanner's own background context so the scan outlives this
	// request and is cancellable on shutdown or when superseded.
	h.scanner.TriggerScan(h.cfg.Scan.GetRoots())
	return c.JSON(http.StatusOK, map[string]string{"status": "scan triggered"})
}
