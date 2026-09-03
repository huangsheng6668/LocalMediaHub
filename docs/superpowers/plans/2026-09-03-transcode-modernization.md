# Transcode Modernization Implementation Plan

> **For agentic workers:** Implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Spec: `docs/superpowers/specs/2026-09-03-transcode-modernization-design.md`.

**Goal:** Replace the fixed `libx264 -preset ultrafast` transcode path with a two-level probed hardware encoder chain (NVENC → QSV → AMF → libx264 fallback), add a configurable transcode session cap, expose transcode status via admin API — with the wire contract (`transcode/start/vcodec` query params) fully backward compatible, zero client changes.

**Architecture:** `StreamingService` gains an `encoderProber` (lazy `sync.Once`, static `ffmpeg -encoders` parse + runtime testsrc micro-encode) and a `transcodeSem` channel cap. Arg construction is extracted into a pure `buildTranscodeArgs` function driven by an allowlist-mapped `vcodec` param. Config flows via a new `transcode:` YAML section.

**Tech Stack:** Go 1.25 (stdlib `os/exec`, `sync`, `context`), Echo v4 (one new admin route), `log/slog` structured logging.

**Empirical baseline (dev machine, ffmpeg 8.1.1-full, 2026-09-03):**
- Static `-encoders` lists h264_nvenc / h264_qsv / h264_amf / libx264 — **all four**.
- Runtime validation: `h264_nvenc -preset p4 -tune hq -rc vbr -cq 23` → exit 0 ✅; h264_qsv / h264_amf → fail ("Nothing was written into output file").
- This is exactly why the two-level probe exists: static presence ≠ usable driver.

## Global Constraints

- `vcodec` client values are allowlist lookup keys only — never interpolated into argv (CWE-78 posture, same as `sanitizeMediaArg`).
- libx264 fallback args stay byte-identical to today (`-vcodec libx264 -preset ultrafast`) — zero regression surface.
- No client (Android/Web) changes; existing `vcodec=copy` and absent-param behavior preserved.
- Spec amendment (locked here): `transcode.max_sessions` semantics — absent/`0` = default 3; **`-1` = unlimited** (sentinel, since YAML int cannot distinguish absent-0 from explicit-0 after unmarshal).
- Tests: `cd server && go test ./...` must pass after every task.

---

### Task 1: `TranscodeConfig` in config layer

**Files:**
- Modify: `server/internal/config/config.go`
- Modify: `server/config.example.yaml`
- Modify: `server/internal/config/config_test.go`

**Interfaces:**
- `TranscodeConfig{ EncoderPreference []string; MaxSessions int }` on `Config.Transcode` (`yaml:"transcode,omitempty"`)
- Defaults applied in `LoadFromBytes`: preference → `[h264_nvenc, h264_qsv, h264_amf]`, MaxSessions 0 → 3
- `ConfigPublic.Transcode` projection (no secrets)

- [ ] Step 1: failing tests — defaults when section absent; explicit values preserved; `Public()` projects transcode
- [ ] Step 2: implement struct + defaults + projection
- [ ] Step 3: document section in `config.example.yaml`

Commit: `feat(server): transcode config section — encoder preference + max sessions`

### Task 2: encoder probe module

**Files:**
- Create: `server/internal/service/transcode_encoder.go`
- Create: `server/internal/service/transcode_encoder_test.go`

**Interfaces:**
- `parseEncodersOutput(out string) map[string]bool` — pure parser
- `encoderProber`: `newEncoderProber(preference []string)`; `resolve() resolvedEncoder` (sync.Once, cached); `resolvedEncoder{Name string}`
- Probe = static parse + runtime testsrc micro-encode (5 frames, 10s timeout); first usable candidate in preference order wins; none → libx264
- Quality args table: nvenc `-preset p4 -tune hq -rc vbr -cq 23`; qsv `-global_quality 23`; amf `-quality balanced`; libx264 `-preset ultrafast`

- [ ] Step 1: failing tests — parser (canned output), resolve() ordering with injected fake validators
- [ ] Step 2: implement probe (validator injectable for tests, real exec.Command in prod path)
- [ ] Step 3: verify runtime probe against local ffmpeg in a `testing.Short()`-skipped integration test

Commit: `feat(server): two-level hardware encoder probe with runtime validation`

### Task 3: `buildTranscodeArgs` extraction + vcodec allowlist

**Files:**
- Modify: `server/internal/service/streaming.go`
- Modify: `server/internal/service/transcode_encoder_test.go` (shared table tests)

**Interfaces:**
- `buildTranscodeArgs(srcPath string, startSec float64, vcodecParam string, auto resolvedEncoder) []string` — pure
- Contract table (spec §3.2): copy / auto / explicit-allowlisted / invalid → fallback auto
- `resolveVCodecParam(param string, usable func(string) bool, auto resolvedEncoder) resolvedEncoder` — pure, table-testable without ffmpeg

- [ ] Step 1: failing table tests — param × encoder matrix incl. injection attempt `vcodec=h264_nvenc -x`
- [ ] Step 2: extract + rewire `serveTranscoded`; start/end session slog lines
- [ ] Step 3: confirm byte-identical libx264 fallback vs old args

Commit: `refactor(server): extract buildTranscodeArgs with allowlist vcodec mapping`

### Task 4: session concurrency cap

**Files:**
- Modify: `server/internal/service/streaming.go`
- Modify: `server/internal/server/server.go` (constructor wiring)
- Modify: `server/internal/server/server_test.go` (constructor call site)
- Create/Modify: `server/internal/service/streaming_test.go`

**Interfaces:**
- `NewStreamingService(ffmpegPath string, encoderPreference []string, maxSessions int)` (maxSessions < 0 = unlimited → nil sem)
- Acquisition honors `r.Context()` — queued client disconnect spawns no ffmpeg
- `transcodeActiveSessions() int64` for status

- [ ] Step 1: failing tests — cap 1: second session queues, ctx cancel releases
- [ ] Step 2: implement sem + atomic counter + wire constructor
- [ ] Step 3: `go test ./...` green

Commit: `feat(server): transcode session concurrency cap honoring client disconnect`

### Task 5: admin status endpoint

**Files:**
- Modify: `server/internal/server/handler/handler.go` (if Handler struct needs the service ref — verify)
- Modify: `server/internal/server/handler/admin.go`
- Modify: `server/internal/server/server.go` (route)
- Modify: `server/internal/server/handler/admin_test.go`

**Interfaces:**
- `GET /api/v1/admin/transcode/status` → `{active, max_sessions, resolved_encoder, preference}` (resolved lazily; probe status without forcing it)
- [ ] Step 1: failing handler test
- [ ] Step 2: implement + register under existing admin group (inherits BearerToken)

Commit: `feat(server): admin transcode status endpoint`

### Task 6: docs + cleanup

**Files:**
- Modify: `docs/superpowers/specs/2026-09-03-transcode-modernization-design.md` (max_sessions -1 amendment)
- Modify: `docs/INDEX.md`, `AGENTS.md` (streaming.go bullet)
- Delete: `server/.tmp_encoder_probe.ps1` (scratch)

Commit: `docs(server): update index and handbook for transcode modernization`

### Final verification

- [ ] `cd server && go test ./...`
- [ ] `cd server/internal/web && node --test` (untouched-area sanity)
- [ ] Manual smoke on dev machine: stream a real video with transcode=true → confirm log shows h264_nvenc; `vcodec=copy` regression
