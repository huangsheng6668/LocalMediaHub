# Open LAN Authentication Simplification Design

**Date:** 2026-08-25  
**Topic:** Default Open LAN Mode & Auth Friction Removal across Server, Web, and Android

## 1. Background & Motivation

LocalMediaHub is primarily used within a private, trusted local area network (LAN) with a few dedicated devices and no direct external network exposure. The previous mandatory requirement of configuring and inputting a Bearer Token creates unnecessary friction during daily usage (e.g. prompt modals on Web, required token field on Android connection screen, and artificial blocking of remote deletion in open-auth mode).

This design transitions the system to a clean, frictionless **Default Open LAN Mode** where devices connect seamlessly without token prompts, while retaining the underlying token authentication infrastructure for optional future use.

---

## 2. Architecture & Requirements

### 2.1 Server Requirements
1. **Default Configuration**: `server/config.yaml` sets `server.token: ""` to default to open authentication.
2. **Log Clarification**: `server/internal/config/config.go` logs a concise informational message when running in open mode (`slog.Info("Auth: running in open LAN mode (no token configured)")`) instead of multiline security warning banners.
3. **Unblock Deletion in Open Mode**: `server/internal/server/handler/system.go` allows deletion requests when `system.enable_delete: true` and the target path passes boundary checks (`ValidateDeletion`), removing the artificial `server.token != ""` precondition.

### 2.2 Web Management Interface Requirements
1. **Deletion Capability**: In `server/internal/web/settings.js`, evaluate `state.enableDelete` based solely on backend config `data.system.enable_delete`, removing the dependency on `state.authToken`.
2. **Frictionless Browsing**: Ensure Web UI operates smoothly with no token modal popup when server is running in open mode.

### 2.3 Android Client Requirements
1. **Simplified Connection UI**: In `ConnectionScreen.kt`, reorganize `ManualConnectionCard` so that only IP and Port are prominently displayed. The Token input is moved into an optional, expandable/collapsible section (e.g., "高级选项 / 访问令牌"), collapsed by default.
2. **Seamless Connection Flow**: Connecting via mDNS discovery, LAN HTTP scan, or manual IP/Port submission operates with zero token configuration required.

---

## 3. Detailed Component Changes

### 3.1 `server/config.yaml`
- Change `server.token` to `""`.

### 3.2 `server/internal/config/config.go`
- In `LogSecurityWarnings`: Replace the multi-line warning banner for empty token with a clean one-line info log.
- Remove the warning regarding `server.token is empty` for remote deletion.

### 3.3 `server/internal/server/handler/system.go`
- In `DeletePath`: Remove lines 231–233 requiring `h.cfg.Server.Token != ""`.

### 3.4 `server/internal/web/settings.js`
- Update `loadConfig()`: `state.enableDelete = !!(data.system && data.system.enable_delete)`.

### 3.5 `android/app/src/main/java/com/juziss/localmediahub/ui/screen/ConnectionScreen.kt`
- In `ManualConnectionCard`, introduce a toggleable `showAdvancedOptions` state.
- Render the `tokenInput` field inside an animated or conditional expandable section under a text button / subtle toggle ("高级选项" / "收起高级选项").
- Keep connection action clean and intuitive.

---

## 4. Verification Plan

### 4.1 Server Tests
- Run `go test ./...` in `server/`.
- Ensure all existing unit and integration tests pass with open auth and configured auth.

### 4.2 Web Tests & Lint
- Run `node --test` in `server/internal/web/`.
- Run XSS checker: `go run . ../../server/internal/web` in `tools/xsscheck/`.

### 4.3 Android Tests & Build
- Run `./gradlew testDebugUnitTest` in `android/`.
- Run `./gradlew assembleDebug` in `android/`.
