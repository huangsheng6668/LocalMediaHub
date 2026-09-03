package handler

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/labstack/echo/v4"
	"github.com/localmediahub/server/internal/config"
)

// newPairTestHandler builds a minimal real Handler around a temp config.
// Pair only consults cfg, so no services are needed.
func newPairTestHandler(token string, lanPairing bool) *Handler {
	return newPairTestHandlerBLE(token, "", lanPairing)
}

func newPairTestHandlerBLE(token, bleToken string, lanPairing bool) *Handler {
	cfg := &config.Config{}
	cfg.Server.Host = "127.0.0.1"
	cfg.Server.Token = token
	cfg.BLE.Token = bleToken
	cfg.Server.LanPairing = lanPairing
	return &Handler{cfg: cfg}
}

func doPair(h *Handler) *httptest.ResponseRecorder {
	e := echo.New()
	e.POST("/api/v1/pair", h.Pair)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/pair", nil)
	rec := httptest.NewRecorder()
	e.ServeHTTP(rec, req)
	return rec
}

func TestPairDisabledByDefault(t *testing.T) {
	rec := doPair(newPairTestHandler("sekrit", false))
	if rec.Code != http.StatusForbidden {
		t.Fatalf("pair with lan_pairing=false = %d, want 403: %s", rec.Code, rec.Body.String())
	}
}

func TestPairGrantsTokenWhenEnabled(t *testing.T) {
	rec := doPair(newPairTestHandler("sekrit", true))
	if rec.Code != http.StatusOK {
		t.Fatalf("pair with lan_pairing=true = %d, want 200: %s", rec.Code, rec.Body.String())
	}
	var body map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if body["token"] != "sekrit" {
		t.Fatalf("token=%q want sekrit", body["token"])
	}
}

func TestPairRejectedInOpenAuthMode(t *testing.T) {
	rec := doPair(newPairTestHandler("", true))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("pair with empty token = %d, want 400: %s", rec.Code, rec.Body.String())
	}
}

func TestPairGrantsBothTokensWhenDedicatedBLEKeySet(t *testing.T) {
	rec := doPair(newPairTestHandlerBLE("sekrit", "blekey", true))
	if rec.Code != http.StatusOK {
		t.Fatalf("pair = %d, want 200: %s", rec.Code, rec.Body.String())
	}
	var body map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if body["token"] != "sekrit" || body["ble_token"] != "blekey" {
		t.Fatalf("token=%q ble_token=%q, want sekrit/blekey", body["token"], body["ble_token"])
	}
}

// Open-auth mode + dedicated ble.token: nothing to distribute for HTTP, but
// the BLE key is grantable — the pairing must not 400.
func TestPairGrantsBleTokenInOpenAuthMode(t *testing.T) {
	rec := doPair(newPairTestHandlerBLE("", "blekey", true))
	if rec.Code != http.StatusOK {
		t.Fatalf("pair = %d, want 200: %s", rec.Code, rec.Body.String())
	}
	var body map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if _, ok := body["token"]; ok {
		t.Fatalf("token field must be absent in open-auth mode, got %q", body["token"])
	}
	if body["ble_token"] != "blekey" {
		t.Fatalf("ble_token=%q, want blekey", body["ble_token"])
	}
}

// Token mode without a dedicated key: no ble_token field — the fallback
// (server.token) needs no separate distribution.
func TestPairOmitsBleTokenWhenFallback(t *testing.T) {
	rec := doPair(newPairTestHandlerBLE("sekrit", "", true))
	if rec.Code != http.StatusOK {
		t.Fatalf("pair = %d, want 200: %s", rec.Code, rec.Body.String())
	}
	var body map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if _, ok := body["ble_token"]; ok {
		t.Fatalf("ble_token must be omitted when server.token is the key, got %q", body["ble_token"])
	}
}
