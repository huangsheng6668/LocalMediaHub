package handler

import (
	"context"
	"log"
	"log/slog"
	"net/http"
	"time"

	"github.com/labstack/echo/v4"

	"github.com/localmediahub/server/internal/ble"
)

// BLECentralBackend is the handler-level seam over ble.Central, allowing
// handler tests to inject a fake without depending on the concrete *ble.Central
// (which requires a CentralScanner backed by Bluetooth hardware). The concrete
// *ble.Central satisfies this interface.
type BLECentralBackend interface {
	Scan(ctx context.Context) ([]ble.Device, error)
	Connect(ctx context.Context, id string) error
	Send(ctx context.Context, payload []byte) ([]byte, error)
	Disconnect()
	State() string
}

// maxBLEPayload is the BLE GATT payload ceiling (MTU 23 header math). The
// Android Peripheral reads + re-notifies at most this many bytes; anything
// larger would fragment and is rejected with HTTP 400 instead.
const maxBLEPayload = 244

// bleOpenAuthModeMessage explains why scan/connect are refused when the
// server runs without a token: the BLE channel authenticates every frame with
// a key derived from server.token (Phase 9 / H-1a), and with no token there
// is no key — an "authenticated" channel would be forgeable by any LAN
// device. 400 (not 200-with-error) so callers treat it as a hard refusal.
const bleOpenAuthModeMessage = "ble unavailable in open-auth mode: set server.token to enable the BLE channel"

// requireBleToken enforces that gate. Returns nil when a token is configured.
func (h *Handler) requireBleToken(c echo.Context) error {
	if h.cfg == nil || h.cfg.Server.Token == "" {
		slog.Warn("BLE request refused: server.token is empty (open-auth mode); BLE channel disabled")
		return echo.NewHTTPError(http.StatusBadRequest, bleOpenAuthModeMessage)
	}
	return nil
}

// ScanBLE handles GET /api/v1/ble/scan. Triggers a Central scan for peripherals
// advertising SERVICE_UUID and returns the discovered devices. When BLE is
// unavailable (nil backend) or the scan errors, returns HTTP 200 with an empty
// device list and an "error" field — never 500, never a crash (zero-regression).
// In open-auth mode (no server.token) the request is refused with 400: the BLE
// channel cannot be authenticated without a key (Phase 9 / H-1a).
func (h *Handler) ScanBLE(c echo.Context) error {
	if h.BLECentral == nil {
		return c.JSON(http.StatusOK, map[string]any{"devices": []any{}, "error": "ble unavailable"})
	}
	if err := h.requireBleToken(c); err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(c.Request().Context(), 4*time.Second)
	defer cancel()
	devices, err := h.BLECentral.Scan(ctx)
	if err != nil {
		return c.JSON(http.StatusOK, map[string]any{"devices": []any{}, "error": err.Error()})
	}
	return c.JSON(http.StatusOK, map[string]any{"devices": devices})
}

// ConnectBLE handles POST /api/v1/ble/connect {"id":"..."}. Asks the Central to
// establish a GATT connection to the named device and complete the Phase 9
// mutual-challenge handshake. Returns {"connected":bool} plus an "error" field
// on failure (HTTP 200, zero-regression). In open-auth mode (no server.token)
// the request is refused with 400 — same rationale as ScanBLE.
func (h *Handler) ConnectBLE(c echo.Context) error {
	if h.BLECentral == nil {
		return c.JSON(http.StatusOK, map[string]any{"connected": false, "error": "ble unavailable"})
	}
	if err := h.requireBleToken(c); err != nil {
		return err
	}
	var req struct {
		ID string `json:"id"`
	}
	if err := c.Bind(&req); err != nil {
		return echo.NewHTTPError(http.StatusBadRequest, err.Error())
	}
	ctx, cancel := context.WithTimeout(c.Request().Context(), 11*time.Second)
	defer cancel()
	if err := h.BLECentral.Connect(ctx, req.ID); err != nil {
		return c.JSON(http.StatusOK, map[string]any{"connected": false, "error": err.Error()})
	}
	return c.JSON(http.StatusOK, map[string]any{"connected": true})
}

// SendBLE handles POST /api/v1/ble/send {"payload":"..."}. Writes the payload
// to the Command characteristic and waits for the Notify echo. Payloads larger
// than 244 bytes are rejected with HTTP 400. Returns {"echo":string} on success
// or {"echo":null,"error":"..."} on failure (HTTP 200, zero-regression).
func (h *Handler) SendBLE(c echo.Context) error {
	if h.BLECentral == nil {
		return c.JSON(http.StatusOK, map[string]any{"echo": nil, "error": "ble unavailable"})
	}
	var req struct {
		Payload string `json:"payload"`
	}
	if err := c.Bind(&req); err != nil {
		return echo.NewHTTPError(http.StatusBadRequest, err.Error())
	}
	if len(req.Payload) > maxBLEPayload {
		return echo.NewHTTPError(http.StatusBadRequest, "payload exceeds 244 bytes")
	}
	ctx, cancel := context.WithTimeout(c.Request().Context(), 12*time.Second)
	defer cancel()
	echoPayload, err := h.BLECentral.Send(ctx, []byte(req.Payload))
	if err != nil {
		log.Printf("BLE Send error: %v", err)
		return c.JSON(http.StatusOK, map[string]any{"echo": nil, "error": err.Error()})
	}
	log.Printf("BLE Send success echo=%s", string(echoPayload))
	return c.JSON(http.StatusOK, map[string]any{"echo": string(echoPayload)})
}
