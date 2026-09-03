# BLE Dedicated Token Design（开放 LAN 模式下启用 BLE 控制通道）

**Date:** 2026-08-29
**Topic:** Independent BLE handshake key (`ble.token`) so the open LAN auth mode and the BLE control channel can coexist

## 1. Background & Motivation

The BLE GATT channel authenticates every v2 frame with an HMAC key derived from
`server.token` (Phase 9 / H-1a): server `Central.SetAuthToken()` and Android
`BleProtocol.SetAuthToken()` derive symmetrically. With the 2026-08-25 open LAN
simplification, `server.token` defaults to `""`, which leaves no key material —
`/api/v1/ble/scan|connect` refuse with HTTP 400 ("ble unavailable in open-auth
mode"). Users must currently choose between the frictionless open LAN mode and
having BLE at all (re-adding `server.token` re-enables Bearer auth on every
admin/system/media route).

Goal: a dedicated BLE secret so **HTTP stays open while the GATT link remains
HMAC-authenticated**.

## 2. Design

### 2.1 Effective BLE secret（两端一致的解析规则）

```
effectiveBleSecret = ble.token   if non-empty
                   = server.token otherwise   (backward compatible)
```

- `ble.token: ""` + `server.token: "x"` → behavior identical to today (key from
  server.token). Token-mode instances need no config change.
- `ble.token: "y"` + `server.token: ""` → open LAN HTTP + authenticated BLE.
  This is the new route-2 combination.
- Both set → BLE uses `ble.token`, HTTP Bearer uses `server.token`. Android
  must fill the BLE key separately (advanced token field no longer matches);
  documented, and the Android UI makes the BLE key an explicit field.

### 2.2 Server changes

1. **Config** (`internal/config/config.go`): new `BLEConfig{Token string
   yaml:"token"}` under `ble:`; `Config.BLE`. Not projected into `ConfigPublic`
   (secret, same posture as `Server.Token`); the admin config PUT must preserve
   the existing `cfg.BLE` when absent from the payload. Helper
   `BLEConfig.EffectiveToken(serverToken string) string` encodes the rule.
2. **Wiring** (`internal/server/server.go`): `bleCentral.SetAuthToken(...)`
   now receives `cfg.BLE.EffectiveToken(cfg.Server.Token)`.
3. **Gate** (`internal/server/handler/ble.go`): `requireBleToken` refuses with
   400 only when the effective secret is empty. Message updated to mention
   `ble.token` first, `server.token` as fallback.
4. **Pairing** (`internal/server/handler/pair.go`): while `server.lan_pairing`
   is on, the response gains `"ble_token"` when the effective BLE secret
   differs from `server.token` (i.e. a dedicated key exists to distribute);
   open-auth + `ble.token` no longer 400s — pairing distributes the BLE key.
   Same deliberate-LAN-exposure posture and WARN logging as today.

### 2.3 Android changes

1. **Storage** (`data/ServerConfigStore.kt`): `bleToken` Flow + `saveBleToken`
   — encrypted at rest exactly like `authToken` (`TokenCrypto`), same legacy
   plaintext fallback.
2. **Key resolution** (`di/BleModule.kt`): cache the latest `bleToken` beside
   the latest `authToken`; the provider handed to `BleController` returns
   `bleToken` when non-blank, else `authToken` (mirror of the server rule).
   `BleController`'s constructor param is renamed `authTokenProvider` →
   `bleKeyProvider` to state the new semantics; handshake logic unchanged.
3. **UI** (`ui/component/BleChannelSection.kt` + `BleSettingsViewModel`):
   optional "BLE 密钥" input inside the BLE settings card (collapsed by
   default, placeholder explains "留空则使用高级选项中的访问令牌"). Saved via
   `saveBleToken`; clearing it restores the authToken fallback.
4. **Pairing consumer**: the LAN pairing flow that saves `authToken` also
   saves `ble_token` when the response carries it.

### 2.4 Security posture（接受并记录的权衡）

- `/api/v1/ble/*` HTTP coordination stays unauthenticated in open mode —
  consistent with the open LAN posture. It cannot yield a usable GATT channel
  without the key: `Connect` performs the Phase 9 mutual HMAC handshake and
  every data frame is v2 (seq + HMAC); a wrong/absent key fails the handshake.
- `ble.token` never appears in `ConfigPublic`, access logs, or `config.example.yaml`
  (empty placeholder + guidance only).
- When both tokens are set, BLE and HTTP use independent secrets — compromise
  of one does not expose the other.

## 3. Compatibility

| server.token | ble.token | HTTP auth | BLE channel |
|---|---|---|---|
| set | empty | Bearer required | key from server.token（现状不变）|
| empty | set | open LAN | key from ble.token（新增）|
| set | set | Bearer required | key from ble.token（需 Android 单独填 BLE 密钥）|
| empty | empty | open LAN | disabled（现状不变，400）|

## 4. Verification

- Server: `cd server && go test ./...` — new cases: config parse (ble.token),
  requireBleToken three states, EffectiveToken precedence, pairing payload.
- Android: `cd android && ./gradlew testDebugUnitTest` — new cases:
  ServerConfigStore bleToken round-trip (encryption fallback), BleModule key
  resolution precedence (bleToken > authToken), pairing saves ble_token.
- Manual: open-auth server + `ble.token`, Android fills BLE 密钥 → scan /
  connect / echo succeed; wrong key fails handshake.
