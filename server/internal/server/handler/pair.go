package handler

import (
	"log/slog"
	"net/http"

	"github.com/labstack/echo/v4"
)

// Pair handles POST /api/v1/pair — LAN app pairing for zero-touch setup.
//
// While `server.lan_pairing: true` is set in config.yaml, an UNAUTHENTICATED
// LAN requester may obtain the token material once, so the Android client can
// auto-configure (HTTP auth + the BLE handshake key) without the user
// hand-copying secrets. This deliberately opens the tokens to the LAN — it is
// an opt-in convenience flag for trusted home networks and must be turned off
// again after the phone has paired. Every grant is logged at WARN with the
// requester IP.
//
// Response: {"token": server.token} when token auth is on, plus
// {"ble_token": eff} when a DEDICATED BLE key exists (ble.token set — i.e.
// the effective key differs from server.token). Open-auth mode with a
// dedicated ble.token pairs fine (BLE-only grant); with no key material at
// all there is nothing to distribute and the request 400s.
func (h *Handler) Pair(c echo.Context) error {
	if !h.cfg.Server.LanPairing {
		return c.JSON(
			http.StatusForbidden,
			map[string]string{"error": "LAN pairing is disabled on the server (server.lan_pairing)"},
		)
	}
	eff := h.cfg.BLE.EffectiveToken(h.cfg.Server.Token)
	if h.cfg.Server.Token == "" && eff == "" {
		return c.JSON(
			http.StatusBadRequest,
			map[string]string{"error": "no token to distribute (server.token and ble.token both empty)"},
		)
	}
	slog.Warn("LAN PAIRING: token material granted to a LAN requester — disable server.lan_pairing after your devices are paired",
		"remote_ip", c.RealIP())
	resp := map[string]string{}
	if h.cfg.Server.Token != "" {
		resp["token"] = h.cfg.Server.Token
	}
	if eff != h.cfg.Server.Token {
		resp["ble_token"] = eff
	}
	return c.JSON(http.StatusOK, resp)
}
