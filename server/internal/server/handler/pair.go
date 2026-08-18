package handler

import (
	"log/slog"
	"net/http"

	"github.com/labstack/echo/v4"
)

// Pair handles POST /api/v1/pair — LAN app pairing for zero-touch setup.
//
// While `server.lan_pairing: true` is set in config.yaml, an UNAUTHENTICATED
// LAN requester may obtain the bearer token once, so the Android client can
// auto-configure (HTTP auth + the BLE handshake's token-derived key) without
// the user hand-copying the token. This deliberately opens the token to the
// LAN — it is an opt-in convenience flag for trusted home networks and must
// be turned off again after the phone has paired. Every grant is logged at
// WARN with the requester IP. Open-auth mode (empty token) has nothing to
// distribute and is rejected.
func (h *Handler) Pair(c echo.Context) error {
	if !h.cfg.Server.LanPairing {
		return c.JSON(
			http.StatusForbidden,
			map[string]string{"error": "LAN pairing is disabled on the server (server.lan_pairing)"},
		)
	}
	if h.cfg.Server.Token == "" {
		return c.JSON(
			http.StatusBadRequest,
			map[string]string{"error": "open-auth mode has no token to distribute"},
		)
	}
	slog.Warn("LAN PAIRING: bearer token granted to a LAN requester — disable server.lan_pairing after your devices are paired",
		"remote_ip", c.RealIP())
	return c.JSON(http.StatusOK, map[string]string{"token": h.cfg.Server.Token})
}
