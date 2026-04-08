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
		return c.JSON(http.StatusBadRequest, map[string]string{"error": err.Error()})
	}

	h.cfg.Scan.Roots = req.Roots
	if err := h.cfg.Save("config.yaml"); err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}

	h.scanner.InvalidateCache()

	return c.JSON(http.StatusOK, h.cfg)
}

func (h *Handler) TriggerScan(c echo.Context) error {
	h.scanner.InvalidateCache()
	go h.scanner.Scan(h.cfg.Scan.GetRoots())
	return c.JSON(http.StatusOK, map[string]string{"status": "scan triggered"})
}
