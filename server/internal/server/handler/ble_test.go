package handler

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/labstack/echo/v4"

	"github.com/localmediahub/server/internal/ble"
	"github.com/localmediahub/server/internal/config"
)

// fakeCentral satisfies the BLECentralBackend interface so handler tests can
// exercise the scan/connect/send paths without a real ble.Central (which
// requires a CentralScanner backed by Bluetooth hardware).
type fakeCentral struct {
	scanDevices []ble.Device
	scanErr     error
	connectErr  error
	sendEcho    []byte
	sendErr     error
	state       string
}

func (f *fakeCentral) Scan(ctx context.Context) ([]ble.Device, error) {
	return f.scanDevices, f.scanErr
}
func (f *fakeCentral) Connect(ctx context.Context, id string) error {
	return f.connectErr
}
func (f *fakeCentral) Send(ctx context.Context, payload []byte) ([]byte, error) {
	return f.sendEcho, f.sendErr
}
func (f *fakeCentral) Disconnect() {}
func (f *fakeCentral) State() string {
	if f.state == "" {
		return "disconnected"
	}
	return f.state
}

// newBLEHandler builds a Handler around the given BLE backend with a tokened
// config, mirroring production (handler.New always carries a cfg). Tests for
// the open-token posture (both tokens empty) use newBLEOpenAuthHandler instead.
func newBLEHandler(central BLECentralBackend) *Handler {
	cfg := &config.Config{}
	cfg.Server.Token = "unit-test-token"
	return &Handler{cfg: cfg, BLECentral: central}
}

// newBLEOpenAuthHandler builds a Handler whose config has NO server.token —
// open-auth mode: the BLE endpoints serve like every other route (2026-08-30).
func newBLEOpenAuthHandler(central BLECentralBackend) *Handler {
	return &Handler{cfg: &config.Config{}, BLECentral: central}
}

func TestScanBLEReturnsDevices(t *testing.T) {
	e := echo.New()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/ble/scan", nil)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	h := newBLEHandler(&fakeCentral{scanDevices: []ble.Device{{ID: "AA:BB", Name: "Pixel", RSSI: -45}}})
	if err := h.ScanBLE(c); err != nil {
		t.Fatalf("ScanBLE error: %v", err)
	}
	var resp struct {
		Devices []ble.Device `json:"devices"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("bad json: %v", err)
	}
	if len(resp.Devices) != 1 || resp.Devices[0].ID != "AA:BB" {
		t.Fatalf("got %+v", resp.Devices)
	}
}

func TestScanBLEUnavailableReturnsEmpty(t *testing.T) {
	e := echo.New()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/ble/scan", nil)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	h := newBLEHandler(&fakeCentral{scanErr: errors.New("unavailable")})
	if err := h.ScanBLE(c); err != nil {
		t.Fatalf("ScanBLE error: %v", err)
	}
	var resp struct {
		Devices []ble.Device `json:"devices"`
		Error   string       `json:"error"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &resp)
	if len(resp.Devices) != 0 {
		t.Fatalf("expected empty devices, got %+v", resp.Devices)
	}
}

// TestScanBLEOpenModeAllowed: with both tokens empty the scan endpoint is
// open like every other route (2026-08-30 spec) — devices come back, no 400.
func TestScanBLEOpenModeAllowed(t *testing.T) {
	e := echo.New()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/ble/scan", nil)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	h := newBLEOpenAuthHandler(&fakeCentral{scanDevices: []ble.Device{{ID: "AA:BB", Name: "Pixel", RSSI: -45}}})
	if err := h.ScanBLE(c); err != nil {
		t.Fatalf("ScanBLE error: %v", err)
	}
	if rec.Code != http.StatusOK {
		t.Fatalf("status=%d want 200", rec.Code)
	}
	var resp struct {
		Devices []ble.Device `json:"devices"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("bad json: %v", err)
	}
	if len(resp.Devices) != 1 || resp.Devices[0].ID != "AA:BB" {
		t.Fatalf("got %+v", resp.Devices)
	}
}

// TestConnectBLEOpenModeAllowed: the connect half of the same posture —
// no key configured, still a normal connect attempt (the Central itself
// runs open mode per Task 1).
func TestConnectBLEOpenModeAllowed(t *testing.T) {
	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/ble/connect", strings.NewReader(`{"id":"AA:BB"}`))
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	h := newBLEOpenAuthHandler(&fakeCentral{})
	if err := h.ConnectBLE(c); err != nil {
		t.Fatalf("ConnectBLE error: %v", err)
	}
	if rec.Code != http.StatusOK {
		t.Fatalf("status=%d want 200", rec.Code)
	}
	var resp struct {
		Connected bool `json:"connected"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("bad json: %v", err)
	}
	if !resp.Connected {
		t.Fatalf("expected connected=true, body=%s", rec.Body.String())
	}
}

// TestScanBLEDedicatedTokenAllowed: ble.token alone (open-auth HTTP) must
// unlock the BLE channel — the route-2 combination.
func TestScanBLEDedicatedTokenAllowed(t *testing.T) {
	e := echo.New()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/ble/scan", nil)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	cfg := &config.Config{}
	cfg.BLE.Token = "dedicated-ble-key"
	h := &Handler{cfg: cfg, BLECentral: &fakeCentral{
		scanDevices: []ble.Device{{ID: "AA:BB", Name: "Pixel", RSSI: -45}},
	}}
	if err := h.ScanBLE(c); err != nil {
		t.Fatalf("ScanBLE error: %v", err)
	}
	var resp struct {
		Devices []ble.Device `json:"devices"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("bad json: %v", err)
	}
	if len(resp.Devices) != 1 {
		t.Fatalf("expected scan to run with dedicated ble.token, got %+v", resp.Devices)
	}
}

func TestConnectBLE(t *testing.T) {
	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/ble/connect", strings.NewReader(`{"id":"AA:BB"}`))
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	h := newBLEHandler(&fakeCentral{})
	if err := h.ConnectBLE(c); err != nil {
		t.Fatalf("ConnectBLE error: %v", err)
	}
	var resp struct {
		Connected bool `json:"connected"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &resp)
	if !resp.Connected {
		t.Fatal("expected connected=true")
	}
}

func TestSendBLEReturnsEcho(t *testing.T) {
	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/ble/send", strings.NewReader(`{"payload":"ping"}`))
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	h := newBLEHandler(&fakeCentral{sendEcho: []byte("pong")})
	if err := h.SendBLE(c); err != nil {
		t.Fatalf("SendBLE error: %v", err)
	}
	var resp struct {
		Echo string `json:"echo"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &resp)
	if resp.Echo != "pong" {
		t.Fatalf("echo=%q want pong", resp.Echo)
	}
}

func TestSendBLERejectsOversizePayload(t *testing.T) {
	e := echo.New()
	big := strings.Repeat("x", 245) // > MAX_PAYLOAD_LEN 244
	req := httptest.NewRequest(http.MethodPost, "/api/v1/ble/send", strings.NewReader(`{"payload":"`+big+`"}`))
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	h := newBLEHandler(&fakeCentral{sendEcho: []byte("pong")})
	err := h.SendBLE(c)
	if err == nil {
		t.Fatal("expected 400 for oversize payload")
	}
	he, ok := err.(*echo.HTTPError)
	if !ok || he.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %v", err)
	}
}
