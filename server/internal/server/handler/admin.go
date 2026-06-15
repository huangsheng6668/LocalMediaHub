package handler

import (
	"net/http"

	"github.com/labstack/echo/v4"
)

type ConfigUpdateRequest struct {
	Roots []string `json:"roots"`
}

func (h *Handler) GetConfig(c echo.Context) error {
	return c.JSON(http.StatusOK, h.cfg)
}

func (h *Handler) UpdateConfig(c echo.Context) error {
	var req ConfigUpdateRequest
	if err := c.Bind(&req); err != nil {
		return respondError(c, http.StatusBadRequest, "invalid request body", err)
	}

	h.cfg.Scan.Roots = req.Roots
	// Roots changed: drop any cached auto-detected drive list so subsequent
	// GetRoots calls reflect the new configuration immediately.
	h.cfg.Scan.InvalidateRootsCache()
	if err := h.cfg.Save("config.yaml"); err != nil {
		return respondInternalError(c, err)
	}

	h.scanner.InvalidateCache()

	return c.JSON(http.StatusOK, h.cfg)
}

func (h *Handler) TriggerScan(c echo.Context) error {
	h.scanner.InvalidateCache()
	// Use the scanner's own background context so the scan outlives this
	// request and is cancellable on shutdown or when superseded.
	h.scanner.TriggerScan(h.cfg.Scan.GetRoots())
	return c.JSON(http.StatusOK, map[string]string{"status": "scan triggered"})
}
