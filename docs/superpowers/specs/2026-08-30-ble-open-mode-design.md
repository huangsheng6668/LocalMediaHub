# BLE Open Mode Design（无密钥 = 开放 BLE 通道）

**Date:** 2026-08-30
**Topic:** When no BLE key is configured (`ble.token` and `server.token` both empty), run the BLE channel in an explicit unauthenticated mode instead of disabling it — mirroring the "empty token = open HTTP" posture of the 2026-08-25 open LAN simplification.

## 1. Background & Motivation

After the 2026-08-29 dedicated-token work, the effective BLE key resolves as
`ble.token` → `server.token` → **disabled**. The both-empty case refuses the
whole channel: `/api/v1/ble/*` answers HTTP 400 and `Central.Connect` returns
`ErrNoAuthKey` before touching the radio. For a trusted home LAN — the exact
audience of open-auth mode — that means configuring and typing a secret just
to use BLE at all, while every HTTP endpoint is already open.

The user decision (2026-08-30): drop the friction. Both tokens empty ⇒ BLE
runs **open** (no handshake, no per-frame HMAC). Anyone wanting the
authenticated channel opts in by setting `ble.token`.

Threat-model honesty: open-auth mode already exposes the full API to the LAN.
Requiring HMAC only on the BLE link protects a vanishingly narrow slice (a
device within ~10 m BLE range but *not* on the LAN) at the cost of all the
configuration friction. Open BLE is consistent with the accepted open-LAN
posture; `ble.token` remains the opt-in lock.

## 2. Design

### 2.1 Rule（两端一致的判定）

```
effectiveBleKey = ble.token   if non-empty
                = server.token otherwise

effectiveBleKey == ""  ⇒  OPEN MODE   (no handshake, v1 data frames)
effectiveBleKey != ""  ⇒  AUTH MODE   (Phase 9 handshake, v2 frames — unchanged)
```

Both ends derive mode from their *own* effective key (server:
`config.BLE.EffectiveToken(config.Server.Token)`; Android:
`BleController.resolveBleKey`). v1 unauthenticated frames already exist in
the codec (they carry the handshake today), so open mode needs **no new frame
type** — data commands simply ride v1, and the v2/seq machinery is skipped.

### 2.2 Server changes (`internal/ble/` + `internal/server/handler/ble.go`)

1. **Central mode**: `openMode` is derived state — `len(authKey) == 0`
   (i.e. `SetAuthToken("")`, key stored nil). `Connect` no longer returns
   `ErrNoAuthKey`: in open mode it skips `handshakeLocked` entirely and goes
   straight to the data phase after the GATT connect (log
   `INFO "BLE channel open (no key configured)"` instead of the auth line).
   `ErrNoAuthKey` is retired.
2. **Receive gate** (`handleNotifyFrame`) becomes three-way:
   - `authenticated` (auth mode, post-handshake): v2 + strictly-increasing
     seq — unchanged.
   - open mode: v1 frames carrying data commands are admissible
     (`CmdApiReq` → dispatch, echo replies → `echoCh`); an AUTH
     challenge/response frame here is a protocol violation and drops the
     link (fail closed against confused peers).
   - neither (auth mode, pre-handshake): current policy — v1 AUTH-only,
     any other command drops the link.
3. **Send paths** (`Send`, `ServeApiRequest`): the gate becomes
   `authenticated || openMode`. Open mode encodes with `EncodeFrame` (v1,
   no seq bookkeeping) instead of `EncodeAuthedFrame`.
4. **HTTP gate**: `requireBleToken` and `bleOpenAuthModeMessage` are deleted;
   `/api/v1/ble/*` follows the route group's normal auth posture (Bearer
   when `server.token` is set, open otherwise) exactly like every other
   endpoint.
5. **Startup log**: when the BLE central initializes successfully and the
   effective key is empty, log one `WARN "BLE running in OPEN mode: any
   device in range can exchange data; set ble.token to require
   authentication"` — same deliberate-posture signal as the open-HTTP
   startup line.
6. **Pairing** (`handler/pair.go`): unchanged — both-empty still 400s (there
   is no key material to distribute); `ble_token` distribution for
   dedicated-key setups keeps working.

### 2.3 Android changes (`ble/`)

1. **Mode resolution**: `resolveBleKey` rule unchanged; a blank effective key
   now means open mode instead of "refuse when challenged".
2. **Open-mode data phase**: on GATT connection + CCCD armed with a blank
   local key, the data phase opens immediately — no challenge wait. Incoming
   v1 data commands (API responses, echo) are accepted; outgoing frames are
   v1.
3. **Mismatch handling** (fail loud, actionable messages, existing
   `fatalLocked` path — wording only):
   - local key blank but a `CmdAuthChallenge` arrives → "server requires BLE
     authentication — fill in the BLE key";
   - local key present but a v1 data command arrives pre-handshake → "server
     is in BLE open mode — clear the Android BLE key".
4. **UI**: no required changes. The BLE 密钥 card stays an optional advanced
   field (collapsed by default); in open mode the user never touches it.

### 2.4 Compatibility matrix（相对 2026-08-29 spec 的唯一变化）

| server.token | ble.token | HTTP auth | BLE channel |
|---|---|---|---|
| set | empty | Bearer required | AUTH (key = server.token)（不变）|
| empty | set | open LAN | AUTH (key = ble.token)（不变）|
| set | set | Bearer required | AUTH (key = ble.token)（不变）|
| empty | empty | open LAN | **OPEN — v1, no handshake（原：disabled/400）** |

The uncommitted `ble.token` implementation (2026-08-29 spec) lands as-is; this
spec only changes the both-empty cell.

## 3. Security posture（接受并记录的权衡）

- In open mode any device within BLE range (~10 m) can exchange data with the
  server (echo, API requests, chapter content) with no credentials and
  without joining the LAN.
- This matches the already-accepted open-LAN posture: the HTTP API is open to
  the LAN, so BLE-only authentication protects almost nothing while costing
  all the friction.
- Just Works link encryption remains available at the controller level
  (ade5da1) but is explicitly **not** treated as an auth boundary.
- Opting back into the authenticated channel is `ble.token` on the server +
  the key on Android (or `lan_pairing` distribution) — fully reversible,
  symmetric rules on both ends.

## 4. Docs & config touch-ups

- `AGENTS.md` "BLE 帧认证" section: both-empty now = open BLE (not 400);
  update the `/api/v1/ble/*` sentence.
- `server/config.example.yaml`: `ble.token` comment states "empty = open BLE
  channel (no handshake); set to require HMAC authentication".
- `docs/INDEX.md` spec listing gains this document.

## 5. Verification

- Server `cd server && go test ./...`:
  - `Central.Connect` with nil key: connects, no handshake, data phase live.
  - Open-mode `Send`/`ServeApiRequest` emit v1; `handleNotifyFrame` accepts
    v1 `CmdApiReq`/echo and drops AUTH frames and the link.
  - Auth-mode regression: handshake, v2-only gate, seq replay rejection
    unchanged.
  - Handler: `/api/v1/ble/scan|connect|send` succeed with both tokens empty
    (no 400); token-mode still Bearer-gated.
- Android `cd android && ./gradlew testDebugUnitTest`:
  - Blank key: connection reaches data phase without challenge; v1
    request/response round-trip.
  - Mismatch fatals produce the new actionable messages.
- Manual: both-empty config, no keys anywhere → scan / connect / echo /
  chapter reading all work over BLE; set `ble.token` only on server →
  Android gets the actionable "fill in the BLE key" error.
