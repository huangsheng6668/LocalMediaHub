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

func TestScanBLEReturnsDevices(t *testing.T) {
	e := echo.New()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/ble/scan", nil)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	h := &Handler{BLECentral: &fakeCentral{scanDevices: []ble.Device{{ID: "AA:BB", Name: "Pixel", RSSI: -45}}}}
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
	h := &Handler{BLECentral: &fakeCentral{scanErr: errors.New("unavailable")}}
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

func TestConnectBLE(t *testing.T) {
	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/ble/connect", strings.NewReader(`{"id":"AA:BB"}`))
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	h := &Handler{BLECentral: &fakeCentral{}}
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
	h := &Handler{BLECentral: &fakeCentral{sendEcho: []byte("pong")}}
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
	h := &Handler{BLECentral: &fakeCentral{sendEcho: []byte("pong")}}
	err := h.SendBLE(c)
	if err == nil {
		t.Fatal("expected 400 for oversize payload")
	}
	he, ok := err.(*echo.HTTPError)
	if !ok || he.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %v", err)
	}
}
