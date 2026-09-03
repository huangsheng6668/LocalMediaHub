# BLE Open Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When no BLE key is configured (`ble.token` and `server.token` both empty), the BLE channel runs in an explicit unauthenticated mode (no Phase 9 handshake, plain v1 data frames) instead of being disabled — mirroring the "empty token = open HTTP" posture.

**Architecture:** Both ends derive mode from their own effective key (`ble.token` → `server.token` → empty). Empty key = open mode: server `Central` skips the handshake and gates data ops on `authenticated || openMode`; Android `BleController` opens the data phase on GATT peer-connect and speaks v1. Key present = existing Phase 9 behavior, byte-for-byte unchanged.

**Tech Stack:** Go (server, Echo v4) + Kotlin (Android, javax.inject singleton, no UI changes). Tests: `go test`, JUnit4 (`testDebugUnitTest`).

**Spec:** `docs/superpowers/specs/2026-08-30-ble-open-mode-design.md`

## Global Constraints

- **两端规则必须对称**：server `config.BLEConfig.EffectiveToken(server.token)` 与 Android `BleController.resolveBleKey(bleToken, authToken)` 是同一条规则；本计划不得改变密钥解析优先级，只改变"解析结果为空"的行为（禁用 → 开放）。
- **认证路径零回归**：密钥存在时的握手、v2 帧、seq 防重放行为与线上字节格式完全不变；现有测试（`TestCentralHandshakeSuccessAndChunkTransfer`、`authedV2DataPath_*` 等）必须全部原样通过。
- **Mimosa 提交闸门**：当前 Mimosa 预提交钩子因仓库既有误报（thumbnail.go MD5 缓存等）拦截一切 `git commit`。执行时：尝试提交，若被拦截则**跳过该 commit 步骤继续下一任务**，并在最终交付说明中列出全部待补提交；不要为绕过闸门改写无关安全代码，不要用 plumbing 绕过。
- **工作区已有 2026-08-29 `ble.token` WIP 未提交改动**（config.go / handler/ble.go / pair.go / server.go / BleController.kt 等已被修改）。本计划在其上叠加；`git add` 只加本任务点名的文件，绝不 `git add -A`。若闸门解除后提交，注意这些文件会同时携带 WIP hunks（它们是互补设计，预期一起落库）。
- 测试命令（Git Bash, Windows）：Go `cd server && go test ./internal/ble/...` 等；Android `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.ble.*"`。
- 错误文案用英文（与现有 `authErrorText` 字符串一致）；代码注释风格跟随各文件现有密度。
- Handler 不使用全局变量；Go 列表用 `make([]T, 0)` 初始化（本计划无新列表端点，仅提醒）。

---

### Task 1: Central open-mode Connect（跳过握手，退役 ErrNoAuthKey）

**Files:**
- Modify: `server/internal/ble/central.go:23-27`（删 `ErrNoAuthKey`）、`central.go:278-300`（`Connect`）、`central.go:116-119`（`authKey` 字段注释）、`central.go:170-183`（`SetAuthToken` 注释）、`central.go:240-263`（`Connect` 大段文档注释）
- Test: `server/internal/ble/central_test.go:472-484`（替换 `TestCentralConnectRequiresAuthKey`）

**Interfaces:**
- Consumes: 现有 `NewCentral(centralScanner)`、`newBlePeerFake(token)`、`peer.recordedWrites()`。
- Produces: `Connect` 在 `len(authKey)==0` 时成功返回并直接进入数据阶段；`ErrNoAuthKey` 从包中消失（Task 5 之前 handler 不引用它，删除安全；执行时先 `grep -rn ErrNoAuthKey server/` 确认仅 central.go 定义 + central_test.go 引用）。

- [ ] **Step 1: 把 gate 测试替换为开放模式测试**

删除 `TestCentralConnectRequiresAuthKey`（central_test.go:472-484），原地加入：

```go
// TestCentralConnectOpenModeSkipsHandshake verifies the central-side half of
// the 2026-08-30 open mode: with no key configured Connect opens the data
// phase immediately — no handshake frames ever hit the wire.
func TestCentralConnectOpenModeSkipsHandshake(t *testing.T) {
	peer := newBlePeerFake(centralTestToken) // peer COULD authenticate, but open mode never asks
	c := NewCentral(peer)                    // no SetAuthToken -> nil authKey -> open mode
	if err := c.Connect(context.Background(), "AA:BB"); err != nil {
		t.Fatalf("Connect error: %v", err)
	}
	if c.State() != "connected" {
		t.Fatalf("state=%q want connected", c.State())
	}
	if writes := peer.recordedWrites(); len(writes) != 0 {
		t.Fatalf("open mode must not write handshake frames, got %d writes", len(writes))
	}
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && go test ./internal/ble/ -run TestCentralConnectOpenMode -v`
Expected: FAIL — `Connect error: ble: auth key not configured...`（现状返回 `ErrNoAuthKey`）。

- [ ] **Step 3: 实现**

`central.go` 删除：

```go
// ErrNoAuthKey is returned by Connect when no auth key is configured (empty
// server token / open-auth mode). The BLE channel MUST NOT enter the data
// phase without a key: every frame would be "authenticated" with a publicly
// computable MAC (Phase 9 / H-1a).
var ErrNoAuthKey = errors.New("ble: auth key not configured; BLE data channel unavailable in open-auth mode")
```

`Connect` 开头（`c.resetAuthLocked()` 之后、`c.handshaking = true` 之前）插入开放分支：

```go
	c.resetAuthLocked()
	if len(c.authKey) == 0 {
		// Open mode (2026-08-30 spec): no key configured — skip the Phase 9
		// handshake entirely and open the data phase on plain v1 frames.
		// Deliberate posture, mirroring open-LAN HTTP; the server wiring
		// logs the OPEN-mode WARN at startup.
		if err := c.scanner.Connect(ctx, id); err != nil {
			return err
		}
		c.state = "connected"
		slog.Info("BLE channel open (no key configured; unauthenticated v1 frames)", "device", id)
		return nil
	}
	c.handshaking = true
```

同步更新三处文档注释（不改行为）：
1. `Connect` 大段注释中 "With no auth key (open-auth mode) Connect refuses before touching the radio." 一句替换为 "With no auth key (open mode, 2026-08-30) Connect skips the handshake and opens the data phase on unauthenticated v1 frames."
2. `Central` struct `authKey` 字段注释 `— nil in open-auth mode, which refuses the data phase entirely.` 替换为 `— nil in open mode (2026-08-30): the data phase runs on unauthenticated v1 frames, no handshake.`
3. `SetAuthToken` 注释 `open-auth mode keeps the key nil and Connect/data paths refuse to run.` 替换为 `open mode keeps the key nil: Connect skips the handshake and data frames ride v1 (2026-08-30).`

- [ ] **Step 4: 跑测试确认通过 + 包内回归**

Run: `cd server && go test ./internal/ble/ -v`
Expected: 全部 PASS（新测试通过；`TestCentralHandshake*`、`TestCentralV2ReplaySeqRejected`、`TestCentralV1FrameAfterAuthRejected`、`TestCentralListenerPreAuthApiReqDropsLink` 等认证路径测试不受影响）。

- [ ] **Step 5: Commit（若 Mimosa 拦截则跳过并记录）**

```bash
git add server/internal/ble/central.go server/internal/ble/central_test.go
git commit -m "feat(ble): central opens the data phase without a handshake when no key is configured"
```

---

### Task 2: 三态接收门控（handleNotifyFrame 开放路径）

**Files:**
- Modify: `server/internal/ble/central.go:664-760`（`handleNotifyFrame` 及其文档注释）
- Test: `server/internal/ble/central_test.go`（新增三个测试）

**Interfaces:**
- Consumes: Task 1 的开放模式 `Connect`；现有 `DecodeFrame` / `EncodeFrame` / `failConnection` / `echoCh` / `ServeApiRequest`。
- Produces: 开放模式下 v1 数据帧被受理（echo → `echoCh`，`CmdApiReq` → goroutine dispatch）；AUTH 命令在开放模式 fail-closed。Task 3 的 `Send` 依赖 echo 路由；Task 4 的 chunk 测试依赖 dispatch。

- [ ] **Step 1: 写失败测试**

在 central_test.go 追加：

```go
// TestCentralOpenModeAuthCommandDropsLink: an AUTH challenge/response in
// open mode means a confused peer (a keyed server/phone paired with a
// keyless one) — fail closed, drop the link.
func TestCentralOpenModeAuthCommandDropsLink(t *testing.T) {
	peer := newBlePeerFake("")
	c := NewCentral(peer)
	if err := c.Connect(context.Background(), "AA:BB"); err != nil {
		t.Fatalf("Connect: %v", err)
	}
	c.handleNotifyFrame(context.Background(), EncodeFrame(
		EncodeAuthChallengePayload(AuthDirCentralToPeripheral, make([]byte, 8))))
	if c.State() != "disconnected" {
		t.Fatalf("state=%q want disconnected", c.State())
	}
	if !peer.wasDisconnected() {
		t.Fatal("expected scanner Disconnect after AUTH command in open mode")
	}
}

// TestCentralOpenModeEchoRoutedToChannel: a v1 data frame that is not an
// API request is an echo reply for a waiting Send — routed to echoCh
// without touching the link state.
func TestCentralOpenModeEchoRoutedToChannel(t *testing.T) {
	peer := newBlePeerFake("")
	c := NewCentral(peer)
	if err := c.Connect(context.Background(), "AA:BB"); err != nil {
		t.Fatalf("Connect: %v", err)
	}
	c.handleNotifyFrame(context.Background(), EncodeFrame([]byte{0x7E}))
	select {
	case got := <-c.echoCh:
		if len(got) != 1 || got[0] != 0x7E {
			t.Fatalf("echo=%v want [0x7E]", got)
		}
	default:
		t.Fatal("open-mode echo frame was not routed to echoCh")
	}
	if c.State() != "connected" {
		t.Fatalf("an echo frame must not drop the link, state=%q", c.State())
	}
}

// TestCentralOpenModeUndecodableFrameDroppedSilently: garbage on the wire
// in open mode is dropped (Warn log) — parity with the pre-auth policy;
// the link stays up.
func TestCentralOpenModeUndecodableFrameDroppedSilently(t *testing.T) {
	peer := newBlePeerFake("")
	c := NewCentral(peer)
	if err := c.Connect(context.Background(), "AA:BB"); err != nil {
		t.Fatalf("Connect: %v", err)
	}
	c.handleNotifyFrame(context.Background(), []byte{0x09, 0xFF, 0xFF}) // bad version
	if c.State() != "connected" {
		t.Fatalf("undecodable frame must not drop the link, state=%q", c.State())
	}
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && go test ./internal/ble/ -run TestCentralOpenMode -v`
Expected: 前两个测试 FAIL — 现状开放模式下（未认证）走 pre-auth 分支：AUTH 帧被当作 raced handshake 帧静默丢弃（第一个测试 state 仍 connected）；0x7E 数据命令触发 failConnection（第二个测试 link 被误杀、echoCh 空）。第三个测试（坏版本字节）在实现前后都会 PASS——它是防"开放模式滥杀链接"的守卫，不属于红灯。

- [ ] **Step 3: 实现**

把 `handleNotifyFrame` 的两分支结构改为三态。认证分支（`if !authenticated { ... }` 之后的 v2 路径）整体保持不变，前置调整如下——在 `authenticated := c.authenticated` / `authKey := c.authKey` / `c.mu.Unlock()` 之后：

```go
	if authenticated {
		payload, seq, aerr := DecodeAuthedFrame(raw, authKey)
		// …整个既有 v2 路径原样搬入此 if 块，从 DecodeAuthedFrame 到
		// go func() { ServeApiRequest … }()，一行不改…
		return
	}

	if len(authKey) == 0 {
		// Open mode (2026-08-30): v1 data frames are admissible without a
		// handshake — CMD_API_REQ dispatches, anything else is an echo
		// reply for a waiting Send. AUTH commands mean a confused peer
		// (keyed remote vs keyless local config) — fail closed.
		frame, ferr := DecodeFrame(raw)
		if ferr != nil {
			slog.Warn("BLE API listener: dropped undecodable open-mode frame", "error", ferr)
			return
		}
		if len(frame.Payload) == 0 {
			return
		}
		switch cmd := CmdID(frame.Payload[0]); cmd {
		case CmdAuthChallenge, CmdAuthResponse:
			slog.Warn("BLE API listener: handshake command in open mode; dropping link", "cmd", byte(cmd))
			c.failConnection()
		case CmdApiReq:
			reqPayload := append([]byte(nil), frame.Payload...)
			go func() {
				n, sErr := c.ServeApiRequest(ctx, reqPayload)
				if sErr != nil {
					slog.Warn("BLE API listener: ServeApiRequest failed", "error", sErr, "written", n)
				}
			}()
		default:
			select {
			case c.echoCh <- frame.Payload:
			default:
			}
		}
		return
	}

	// …既有 pre-auth 分支（"!authenticated: only v1 AUTH commands…"）原样保留…
```

同时把 `handleNotifyFrame` 顶部文档注释的策略清单补一条（放在现有两条 bullet 之间）：

```go
//   - open mode (no key configured, 2026-08-30): v1 frames carrying data
//     commands are admissible — CMD_API_REQ dispatches, anything else is an
//     echo reply for a waiting Send; AUTH commands drop the link (a keyed
//     remote paired with a keyless local config must fail closed).
```

- [ ] **Step 4: 跑测试确认通过 + 回归**

Run: `cd server && go test ./internal/ble/ -v`
Expected: 全部 PASS（含 `TestCentralListenerPreAuthApiReqDropsLink`：有密钥未握手时数据命令仍杀链接）。

- [ ] **Step 5: Commit（若被拦截则跳过并记录）**

```bash
git add server/internal/ble/central.go server/internal/ble/central_test.go
git commit -m "feat(ble): listener accepts unauthenticated v1 data frames in open mode"
```

---

### Task 3: 开放模式 Send（v1 写 + echoCh 等待）

**Files:**
- Modify: `server/internal/ble/central.go:413-454`（`Send` 及其文档注释）
- Test: `server/internal/ble/central_test.go`（新增一个测试）

**Interfaces:**
- Consumes: Task 1 开放模式 Connect；Task 2 的 echo→`echoCh` 路由；现有 `sendMu` / `EncodeFrame`。
- Produces: `Send` 在开放模式返回 echo（依赖 Task 2 的 echoCh 路由才能收到回包）。

- [ ] **Step 1: 写失败测试**

```go
// TestCentralOpenModeSendEchoRoundTrip: in open mode Send writes ONE plain
// v1 frame and the listener-routed v1 echo comes back via echoCh.
func TestCentralOpenModeSendEchoRoundTrip(t *testing.T) {
	peer := newBlePeerFake("")
	c := NewCentral(peer)
	if err := c.Connect(context.Background(), "AA:BB"); err != nil {
		t.Fatalf("Connect: %v", err)
	}
	type result struct {
		payload []byte
		err     error
	}
	resCh := make(chan result, 1)
	go func() {
		p, err := c.Send(context.Background(), []byte("ping"))
		resCh <- result{payload: p, err: err}
	}()

	// Wait for the outbound write, then feed the peer's echo back through
	// the listener path (open mode routes non-API v1 payloads to echoCh).
	deadline := time.Now().Add(2 * time.Second)
	for len(peer.recordedWrites()) == 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	writes := peer.recordedWrites()
	if len(writes) != 1 {
		t.Fatalf("open-mode Send must write exactly one frame, got %d", len(writes))
	}
	if writes[0][0] != FrameVersion {
		t.Fatalf("open-mode Send must write v1, got version %#x", writes[0][0])
	}
	frame, derr := DecodeFrame(writes[0])
	if derr != nil {
		t.Fatalf("DecodeFrame: %v", derr)
	}
	c.handleNotifyFrame(context.Background(), EncodeFrame(frame.Payload))

	select {
	case r := <-resCh:
		if r.err != nil {
			t.Fatalf("Send: %v", r.err)
		}
		if string(r.payload) != "ping" {
			t.Fatalf("echo=%q want ping", r.payload)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("echo not delivered to Send")
	}
}
```

（`central_test.go` 已 import `time`；若没有则补。）

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && go test ./internal/ble/ -run TestCentralOpenModeSend -v`
Expected: FAIL — `Send: ble: channel not authenticated`（现状 `!c.authenticated` 即拒）。

- [ ] **Step 3: 实现**

`Send` 的门控与写路径改为：

```go
func (c *Central) Send(ctx context.Context, payload []byte) ([]byte, error) {
	c.mu.Lock()
	if c.state != "connected" {
		c.mu.Unlock()
		return nil, ErrNotConnected
	}
	openMode := len(c.authKey) == 0
	if !c.authenticated && !openMode {
		c.mu.Unlock()
		return nil, ErrNotAuthenticated
	}
	key := c.authKey
	scanner := c.scanner
	c.mu.Unlock()

	if openMode {
		// Open mode (2026-08-30): plain v1 frame, still serialized under
		// sendMu so a concurrent chunk stream cannot interleave writes on
		// the single GATT link.
		c.sendMu.Lock()
		werr := scanner.WriteCommand(ctx, EncodeFrame(payload))
		c.sendMu.Unlock()
		if werr != nil {
			return nil, werr
		}
	} else if err := c.sendAuthedFrame(ctx, scanner, payload, key); err != nil {
		return nil, err
	}
	// …既有 echoCh 等待 select 块原样保留…
```

`Send` 文档注释中 `Requires an active, AUTHENTICATED connection (Phase 9 / H-1a):` 一句改为 `Requires an active, AUTHENTICATED connection (Phase 9 / H-1a) — or an open-mode connection (2026-08-30, no key configured), which writes one plain v1 frame:`。

- [ ] **Step 4: 跑测试确认通过 + 回归**

Run: `cd server && go test ./internal/ble/ -v`
Expected: 全部 PASS（`TestCentralSendEcho*`、`TestCentralSendWhenNotConnectedErrors` 不变）。

- [ ] **Step 5: Commit（若被拦截则跳过并记录）**

```bash
git add server/internal/ble/central.go server/internal/ble/central_test.go
git commit -m "feat(ble): send echoes over a plain v1 frame in open mode"
```

---

### Task 4: 开放模式 ServeApiRequest（v1 chunk + 开销感知分片）

**Files:**
- Modify: `server/internal/ble/central.go:470-568`（`ServeApiRequest` 及其文档注释）
- Test: `server/internal/ble/central_test.go`（新增一个测试）

**Interfaces:**
- Consumes: Task 2 的 dispatch；现有 `jsonBlockProvider` / `ChunkJsonBytesSized` / `EncodeApiReqPayload` / `EndpointBookChapter`。
- Produces: 开放模式 API 响应以 v1 chunk 流出；`MTU` 分片按 v1 开销（省去 24B seq+HMAC）放宽。

- [ ] **Step 1: 写失败测试**

```go
// TestCentralOpenModeServeApiRequestChunksAsV1: open-mode API responses
// stream as plain v1 CMD_JSON_CHUNK frames (never v2), mirroring the
// authenticated transfer test.
func TestCentralOpenModeServeApiRequestChunksAsV1(t *testing.T) {
	peer := newBlePeerFake("")
	c := NewCentral(peer)
	body := make([]byte, 2048)
	for i := range body {
		body[i] = 'x'
	}
	c.SetApiProvider(&jsonBlockProvider{body: body})
	if err := c.Connect(context.Background(), "AA:BB"); err != nil {
		t.Fatalf("Connect: %v", err)
	}
	req, encErr := EncodeApiReqPayload(EndpointBookChapter, "/books/novel.txt", 0)
	if encErr != nil {
		t.Fatalf("EncodeApiReqPayload: %v", encErr)
	}
	written, err := c.ServeApiRequest(context.Background(), req)
	if err != nil {
		t.Fatalf("ServeApiRequest: %v", err)
	}
	if written < 2 {
		t.Fatalf("expected multiple chunks, got %d", written)
	}
	writes := peer.recordedWrites()
	if len(writes) != written {
		t.Fatalf("written=%d but peer recorded %d frames", written, len(writes))
	}
	for i, raw := range writes {
		if raw[0] != FrameVersion {
			t.Fatalf("frame %d must be v1 in open mode, version %#x", i, raw[0])
		}
		frame, derr := DecodeFrame(raw)
		if derr != nil {
			t.Fatalf("frame %d DecodeFrame: %v", i, derr)
		}
		if len(frame.Payload) == 0 || CmdID(frame.Payload[0]) != CmdJsonChunk {
			t.Fatalf("frame %d is not CMD_JSON_CHUNK", i)
		}
	}
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && go test ./internal/ble/ -run TestCentralOpenModeServeApiRequest -v`
Expected: FAIL — `ServeApiRequest: ble: channel not authenticated`。

- [ ] **Step 3: 实现**

`ServeApiRequest` 三处修改：

(a) 门控块（原 `authenticated := c.authenticated && len(c.authKey) > 0` 一行与 `if !authenticated` 块）：

```go
	c.mu.Lock()
	provider := c.apiProvider
	connected := c.state == "connected"
	openMode := len(c.authKey) == 0
	authenticated := c.authenticated && len(c.authKey) > 0
	authKey := c.authKey
	scanner := c.scanner
	c.mu.Unlock()

	if provider == nil {
		return 0, ErrNoApiProvider
	}
	if !connected {
		return 0, ErrNotConnected
	}
	if !authenticated && !openMode {
		return 0, ErrNotAuthenticated
	}
```

(b) 分片容量（原 `dataCap := scanner.MTU() - 3 - authedOverhead - chunkFixedOverhead` 处）：

```go
	// v2 frames carry seq + truncated HMAC (authedOverhead) on top of the
	// 3-byte v1 header; open-mode v1 frames carry neither, so chunks can
	// use the freed 24 bytes (2026-08-30).
	frameOverhead := authedOverhead
	if openMode {
		frameOverhead = 0
	}
	dataCap := scanner.MTU() - 3 - frameOverhead - chunkFixedOverhead
```

紧随其后的既有 fallback 块（`if dataCap < maxChunkBytes-chunkFixedOverhead { … }`）保持不变。

(c) chunk 写循环（原 `if werr := c.sendAuthedFrame(ctx, scanner, payload, authKey); werr != nil {` 处）：

```go
		var werr error
		if openMode {
			c.sendMu.Lock()
			werr = scanner.WriteCommand(ctx, EncodeFrame(payload))
			c.sendMu.Unlock()
		} else {
			werr = c.sendAuthedFrame(ctx, scanner, payload, authKey)
		}
		if werr != nil {
			slog.Warn("BLE JSON chunk write failed",
				"endpoint", endpoint, "path", path, "index", index,
				"written", written, "total", len(chunkPayloads), "error", werr)
			return written, werr
		}
```

文档注释同步：方法注释里 `as a v2 authed frame with a strictly-increasing localSeq (Phase 9 / H-1a).` 改为 `as a v2 authed frame with a strictly-increasing localSeq (Phase 9 / H-1a) — or, in open mode (2026-08-30), as a plain v1 frame sized without the authed overhead.`

- [ ] **Step 4: 跑测试确认通过 + 回归**

Run: `cd server && go test ./internal/ble/ -v && go test ./...`
Expected: 全部 PASS（认证路径 chunk 测试仍验证 v2-only 上链）。

- [ ] **Step 5: Commit（若被拦截则跳过并记录）**

```bash
git add server/internal/ble/central.go server/internal/ble/central_test.go
git commit -m "feat(ble): api responses stream as v1 chunks in open mode"
```

---

### Task 5: 删除 HTTP 层 requireBleToken 门控

**Files:**
- Modify: `server/internal/server/handler/ble.go:32-48`（删 `bleOpenAuthModeMessage` + `requireBleToken`）、`:54-62`（ScanBLE 调用与注释）、`:72-83`（ConnectBLE 调用与注释）；若删后 `log/slog` 不再被引用则一并删 import（先 `grep -n slog server/internal/server/handler/ble.go` 确认）
- Test: `server/internal/server/handler/ble_test.go:101-167`（删三个 gate 测试 + 改 helper 注释，新增两个 allowed 测试）

**Interfaces:**
- Consumes: Task 1-4 的开放模式 Central（handler 不感知模式，只删门控）。
- Produces: `/api/v1/ble/scan|connect|send` 在双空配置下正常服务（鉴权完全交给路由组的 `authMw`：有 token 走 Bearer，无 token 透传）。

- [ ] **Step 1: 写失败测试（先改测试）**

删除 `TestScanBLEOpenAuthModeRejected`（101-120）、`TestConnectBLEOpenAuthModeRejected`（122-139）、`TestRequireBleTokenStates`（141-167）。`newBLEOpenAuthHandler` 的注释（"the open-auth mode the BLE handlers must refuse"）改为：

```go
// newBLEOpenAuthHandler builds a Handler whose config has NO server.token —
// open-auth mode: the BLE endpoints serve like every other route (2026-08-30).
```

原位置新增：

```go
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
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && go test ./internal/server/handler/ -run "OpenModeAllowed" -v`
Expected: 编译通过（新测试只调 Handler 公开方法），运行期 FAIL — `TestScanBLEOpenModeAllowed` 因现状 `requireBleToken` 返回 400 HTTPError 触发 `ScanBLE error`；`TestConnectBLEOpenModeAllowed` 同理。

- [ ] **Step 3: 实现**

`handler/ble.go` 删除 `bleOpenAuthModeMessage` 常量块（32-38）与 `requireBleToken` 函数（40-48）；`ScanBLE` 中删除：

```go
	if err := h.requireBleToken(c); err != nil {
		return err
	}
```

`ConnectBLE` 中删除同样的三行。两个方法的文档注释各删一句：ScanBLE 的 `In open-auth mode (no server.token) the request is refused with 400: the BLE channel cannot be authenticated without a key (Phase 9 / H-1a).` 与 ConnectBLE 的 `In open-auth mode (no server.token) the request is refused with 400 — same rationale as ScanBLE.`。若 `log/slog` 因此无引用，删除该 import。

- [ ] **Step 4: 跑测试确认通过 + 回归**

Run: `cd server && go test ./internal/server/handler/ -v`
Expected: 全部 PASS（`TestScanBLEReturnsDevices`、`TestScanBLEDedicatedTokenAllowed`、`TestConnectBLE`、`TestSendBLE*` 不变）。

- [ ] **Step 5: Commit（若被拦截则跳过并记录）**

```bash
git add server/internal/server/handler/ble.go server/internal/server/handler/ble_test.go
git commit -m "feat(server): serve /api/v1/ble endpoints in open-auth mode instead of 400"
```

---

### Task 6: server 接线开放模式启动 WARN

**Files:**
- Modify: `server/internal/server/server.go:138-150`（SetAuthToken 调用处注释 + 新增 WARN；确认文件已 import `log/slog`，没有则补）

**Interfaces:**
- Consumes: Task 1-5。
- Produces: 启动日志一条姿态声明，与开放 HTTP 启动行并列。无代码接口产出。

- [ ] **Step 1: 实现**

`server.go` 中原：

```go
			// Phase 9 (H-1a): derive the BLE auth key from the effective BLE
			// secret — ble.token first, server.token fallback. The Central
			// refuses the data phase with a nil key (both empty), and the
			// /api/v1/ble/scan|connect handlers 400 on the same condition —
			// this wiring is what makes an authenticated handshake possible.
			bleCentral.SetAuthToken(cfg.BLE.EffectiveToken(cfg.Server.Token))
```

替换为：

```go
			// Phase 9 (H-1a) + 2026-08-30 open mode: derive the BLE auth key
			// from the effective BLE secret — ble.token first, server.token
			// fallback. Both empty = OPEN mode: no handshake, data frames
			// ride unauthenticated v1 (mirrors the open-LAN HTTP posture;
			// the WARN below states the accepted trade-off).
			effBleSecret := cfg.BLE.EffectiveToken(cfg.Server.Token)
			bleCentral.SetAuthToken(effBleSecret)
			if effBleSecret == "" {
				slog.Warn("BLE running in OPEN mode: any device in range can exchange data; set ble.token to require authentication")
			}
```

- [ ] **Step 2: 构建 + 包测试（日志行不单测，编译与回归为门）**

Run: `cd server && go build ./... && go test ./internal/server/...`
Expected: 构建成功，测试全 PASS。

- [ ] **Step 3: Commit（若被拦截则跳过并记录）**

```bash
git add server/internal/server/server.go
git commit -m "feat(server): warn once when the BLE channel starts in open mode"
```

---

### Task 7: Android BleController 开放模式（实现 + 测试同任务）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ble/BleController.kt`（类文档、`openMode` 字段、`resetAuthLocked`、`init` 的 `setOnPeerConnected`、`onCommandWrite`、新增 `handleOpenWriteLocked`、`notifyHandshakeFrameLocked` 改名 `notifyV1FrameLocked`、`requestApi`、两处握手空密钥文案、pre-auth else 文案）
- Test: `android/app/src/test/java/com/juziss/localmediahub/ble/BleControllerTest.kt`（新增 7 个测试；现有 `emptyTokenRefusesHandshake_sendsNothingAndDropsToDisconnected` 与 `preAuthNonHandshakeCommand_isFatal` 保持原样仍应通过）

**Interfaces:**
- Consumes: `BleProtocol.decodeFrame/encodeFrame/encodeJsonChunkPayload/CMD_*`、`BleTransportFallback.fetchJson(endpoint, path, index) { feeder }`、`FakePeripheralManager.simulatePeerConnected/simulateWrite/notified`、`newController(mgr, token=…, fallback=…)`。
- Produces: `BleController.openMode: Boolean`（public 只读，与 `authenticated` 同款 `@Volatile`）；开放模式收发全 v1。

- [ ] **Step 1: 写失败测试**

在 `BleControllerTest.kt` 的 `resolveBleKeyPrefersDedicatedBleToken` 前插入新分组：

```kotlin
    // ------------------------------------------------------------------
    // Open mode (2026-08-30): blank effective key = unauthenticated v1
    // data phase, mirroring Go Central's len(authKey) == 0.
    // ------------------------------------------------------------------

    @Test
    fun openMode_blankKey_entersDataPhaseOnPeerConnect() {
        val mgr = FakePeripheralManager()
        val controller = newController(mgr, token = "")
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()

        mgr.simulatePeerConnected()

        assertTrue("blank key must open the data phase (open mode)", controller.openMode)
        assertFalse(controller.authenticated)
        assertEquals(BleConnState.CONNECTED, controller.connectionState.value)
    }

    @Test
    fun peerConnect_withKeyConfigured_doesNotEnterOpenMode() {
        val mgr = FakePeripheralManager()
        val controller = newController(mgr) // token defaults to "sekrit"
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()

        mgr.simulatePeerConnected()

        assertFalse("a configured key must keep the Phase 9 handshake path", controller.openMode)
    }

    @Test
    fun openMode_echoRoundTrip_usesV1Frames() {
        val mgr = FakePeripheralManager()
        val controller = newController(mgr, token = "")
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()
        mgr.simulatePeerConnected()

        mgr.simulateWrite(BleProtocol.encodeFrame(byteArrayOf(0x7E)))

        assertEquals("open-mode echo must notify exactly one v1 frame", 1, mgr.notified.size)
        assertEquals(
            "open-mode frames are v1, never v2",
            0x01,
            mgr.notified[0][0].toInt(),
        )
        val echoed = BleProtocol.decodeFrame(mgr.notified[0])
        assertNotNull(echoed)
        assertEquals(0x7E.toByte(), echoed!!.payload[0])
    }

    @Test
    fun openMode_jsonChunk_reachesReassemblyEngine() = runTest {
        val mgr = FakePeripheralManager()
        val fallback = BleTransportFallback()
        val controller = newController(mgr, token = "", fallback = fallback)
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()
        mgr.simulatePeerConnected()

        val json = "{\"k\":1}".toByteArray(Charsets.UTF_8)
        val chunkPayload = BleProtocol.encodeJsonChunkPayload(
            totalChunks = 1, chunkIndex = 0, totalBytes = json.size, chunk = json,
        )
        val result = fallback.fetchJson(
            endpoint = BleProtocol.ENDPOINT_BOOK_CHAPTER,
            path = "/book.txt",
            index = 0,
        ) {
            controller.onCommandWrite(BleProtocol.encodeFrame(chunkPayload))
        }
        assertEquals("{\"k\":1}", result)
        assertTrue(controller.openMode)
    }

    @Test
    fun openMode_authCommand_isFatalWithActionableMessage() {
        val mgr = FakePeripheralManager()
        val controller = newController(mgr, token = "")
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()
        mgr.simulatePeerConnected()

        mgr.simulateWrite(
            BleProtocol.encodeFrame(
                BleProtocol.encodeAuthChallengePayload(
                    BleProtocol.AUTH_DIR_C2P, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
                ),
            ),
        )

        assertEquals("no frame may be sent on a key mismatch", 0, mgr.notified.size)
        assertFalse(controller.openMode)
        assertEquals(BleConnState.DISCONNECTED, controller.connectionState.value)
        assertNotNull(controller.authErrorText)
        assertTrue(
            "error must tell the user to fill in the BLE key: ${controller.authErrorText}",
            controller.authErrorText!!.contains("BLE key"),
        )
    }

    @Test
    fun openMode_requestApi_sendsV1CmdApiReq() {
        val mgr = FakePeripheralManager()
        val controller = newController(mgr, token = "")
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()
        mgr.simulatePeerConnected()

        val ok = controller.requestApi(BleProtocol.ENDPOINT_BOOK_CHAPTER, "/book.txt", 0)

        assertTrue("open mode must allow requestApi", ok)
        assertEquals(1, mgr.notified.size)
        val frame = BleProtocol.decodeFrame(mgr.notified[0])
        assertNotNull(frame)
        assertEquals(BleProtocol.CMD_API_REQ, frame!!.payload[0])
    }

    @Test
    fun preAuthDataCommand_withKeySet_fatalMentionsServerOpenMode() {
        val mgr = FakePeripheralManager()
        val controller = newController(mgr) // token set, NO handshake
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()
        mgr.simulatePeerConnected()

        mgr.simulateWrite(BleProtocol.encodeFrame(byteArrayOf(0x7E)))

        assertFalse(controller.authenticated)
        assertEquals(BleConnState.DISCONNECTED, controller.connectionState.value)
        assertNotNull(controller.authErrorText)
        assertTrue(
            "error must hint the server may be in open mode: ${controller.authErrorText}",
            controller.authErrorText!!.contains("open mode"),
        )
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.ble.BleControllerTest"`
Expected: 编译 FAIL — `controller.openMode` 不存在。

- [ ] **Step 3: 实现**

`BleController.kt` 修改（按出现顺序）：

(a) 类文档策略清单 `PRE-AUTH` bullet 之前插入一条：

```kotlin
 *  - OPEN MODE (2026-08-30, blank effective key at GATT connect): no
 *    handshake — every frame is v1. CMD_JSON_CHUNK routes to
 *    [bleTransportFallback], other data commands echo back as v1, and the
 *    two AUTH commands are fatal (keyed server vs keyless client — fail
 *    closed with an actionable message). Outbound [requestApi]/echo are v1.
```

(b) `authenticated` 字段后新增：

```kotlin
    /**
     * 2026-08-30 open mode: true while the current connection runs without
     * a key (blank effective key at GATT peer-connect) — data phase opens
     * immediately, all frames are v1. Mirrors Go Central's
     * `len(authKey) == 0`. @Volatile for lock-free observation; every
     * mutation happens under [authLock].
     */
    @Volatile
    var openMode: Boolean = false
        private set
```

(c) `init` 中 `setOnPeerConnected` 回调改为：

```kotlin
        peripheralManager.setOnPeerConnected {
            synchronized(authLock) {
                resetAuthLocked()
                // 2026-08-30 open mode: a blank effective key opens the
                // data phase immediately — no handshake, v1 frames. With a
                // key configured the Phase 9 handshake drives entry instead.
                if (bleKeyProvider().isBlank()) {
                    openMode = true
                }
            }
        }
```

(d) `onCommandWrite` 路由改三态：

```kotlin
    fun onCommandWrite(rawFrame: ByteArray) {
        synchronized(authLock) {
            when {
                authenticated -> handleAuthedWriteLocked(rawFrame)
                openMode -> handleOpenWriteLocked(rawFrame)
                else -> handlePreAuthWriteLocked(rawFrame)
            }
        }
    }
```

(e) `handlePreAuthWriteLocked` 的 else 分支文案改为（可keyed-客户端撞上开放 server 的失配提示）：

```kotlin
            else -> fatalLocked(
                "BLE protocol violation: data command 0x%02x before authentication (server in BLE open mode? clear the Android BLE key)".format(payload[0]),
            )
```

(f) `handleChallengeLocked` 与 `handleCentralResponseLocked` 中两处空密钥 fatal 文案统一改为（原 open-auth 说法已失效）：

```kotlin
            fatalLocked("BLE auth refused: no BLE key configured on this device")
```

(g) 新增 `handleOpenWriteLocked`（放在 `handlePreAuthWriteLocked` 之后）：

```kotlin
    /**
     * OPEN-mode policy (blank local key, 2026-08-30): every frame is v1.
     * CMD_JSON_CHUNK routes to the reassembly engine (the raw frame IS the
     * v1 encoding the engine expects — no re-wrap needed), other data
     * commands echo back as v1, and the two AUTH commands mean the SERVER
     * is configured with a key while this client has none — fatal with an
     * actionable message.
     */
    private fun handleOpenWriteLocked(rawFrame: ByteArray) {
        val frame = BleProtocol.decodeFrame(rawFrame) ?: return
        val payload = frame.payload
        if (payload.isEmpty()) return
        when (payload[0]) {
            BleProtocol.CMD_JSON_CHUNK ->
                bleTransportFallback.onFrameReceived(rawFrame)
            BleProtocol.CMD_AUTH_CHALLENGE,
            BleProtocol.CMD_AUTH_RESPONSE,
            -> fatalLocked("BLE auth refused: server requires a BLE key — fill it in (server ble.token)")
            else -> notifyV1FrameLocked(payload) // echo path (connectivity loop)
        }
    }
```

(h) `notifyHandshakeFrameLocked` 改名 `notifyV1FrameLocked`，文档首句改为 `Sends one payload as a v1 frame with a short bounded retry against the CCCD-race window (Go implementer note (a)): used by the Phase 9 handshake and by open-mode data echoes.`，两处调用点（`handleChallengeLocked` 内两个 `notifyHandshakeFrameLocked(`）同步改名。

(i) `requestApi` 末尾同步块改为：

```kotlin
        synchronized(authLock) {
            if (!authenticated && !openMode) return false
            return if (openMode) {
                notifyV1FrameLocked(payload)
            } else {
                notifyAuthedFrameLocked(payload)
            }
        }
```

其文档中 `refused (false) until the mutual handshake completes` 一句改为 `refused (false) until the mutual handshake completes OR the connection is in open mode (blank key, v1 frames — 2026-08-30)`。

(j) `resetAuthLocked` 追加一行：

```kotlin
        openMode = false
```

- [ ] **Step 4: 跑测试确认通过 + 全量 Android BLE 回归**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.ble.*"`
Expected: 全部 PASS——新增 7 个 + 既有握手/seq/chunk/requestApi/markConnected/resolveBleKey 测试（`emptyTokenRefusesHandshake_sendsNothingAndDropsToDisconnected` 在无 peer-connect 事件时仍走 pre-auth 挑战路径 → fatal，语义保持；`preAuthNonHandshakeCommand_isFatal` 只断言状态不断言文案，仍通过）。

- [ ] **Step 5: Commit（若被拦截则跳过并记录）**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ble/BleController.kt android/app/src/test/java/com/juziss/localmediahub/ble/BleControllerTest.kt
git commit -m "feat(android): ble open mode skips the handshake and speaks v1 frames"
```

---

### Task 8: 文档更新（AGENTS.md / config.example.yaml / INDEX.md）

**Files:**
- Modify: `AGENTS.md`（两处：Server 周边 BLE bullet 的"两者皆空 → 400 拒绝"句 + "### BLE 帧认证"节）
- Modify: `server/config.example.yaml:20-21`
- Modify: `docs/INDEX.md`（spec 表新增一行）

**Interfaces:** 无代码接口；纯文档。

- [ ] **Step 1: AGENTS.md 两处替换**

Server 周边 bullet 中：

```
（开放模式下 `/api/v1/ble/*` 透传，但要求有效 BLE 密钥——`ble.token` 优先、`server.token` 回退，见 [安全约定](#安全约定触碰前必读)；GATT 数据链路 Phase 9 起为 v2 帧认证）
```

替换为：

```
（`/api/v1/ble/*` 鉴权跟随路由组：有 `server.token` 走 Bearer，开放模式透传；GATT 数据链路 Phase 9 起为 v2 帧认证，密钥源 `ble.token` 优先、`server.token` 回退，**两者皆空 = 开放模式**——跳过握手、v1 无认证帧（2026-08-30），见 [安全约定](#安全约定触碰前必读)）
```

"### BLE 帧认证" 节中：

```
两者皆空 → `/api/v1/ble/*` 400 拒绝（无密钥 = 通道可伪造）
```

替换为：

```
两者皆空 → **开放模式**（2026-08-30）：跳过双 nonce 握手，数据帧走 v1 无认证（无 seq 防重放）；BLE 半径内任何设备可交换数据，与开放 HTTP 姿态一致，启动时打 WARN；配 `ble.token` 即恢复 v2 HMAC 认证
```

- [ ] **Step 2: config.example.yaml 与 INDEX.md**

`config.example.yaml` 的 `ble:` 块改为：

```yaml
ble:
  # Dedicated BLE handshake key. Empty = OPEN mode (2026-08-30): the BLE
  # channel runs without a handshake on unauthenticated v1 frames — anyone
  # in range can exchange data. Set a key to require HMAC authentication.
  # Fallback when empty but server.token is set: server.token.
  token: ""
```

`docs/INDEX.md` 的 spec 表（`2026-08-29-ble-dedicated-token` 行之后）追加：

```
| — | BLE 开放模式（无密钥 = v1 无认证开放，配 `ble.token` 恢复 v2 HMAC） | `docs/superpowers/specs/2026-08-30-ble-open-mode-design.md` | 完成 | |
```

（若表中该行的列数与相邻行不一致，以相邻行为准增删尾列。）

- [ ] **Step 3: 验证无断链 + 全量回归**

Run: `cd server && go test ./...`
Run: `cd android && ./gradlew testDebugUnitTest`
Expected: 两套全 PASS（文档改动不应影响任何测试；此步同时是交付前总回归）。

- [ ] **Step 4: Commit（若被拦截则跳过并记录）**

```bash
git add AGENTS.md server/config.example.yaml docs/INDEX.md
git commit -m "docs: document BLE open mode (no key = unauthenticated v1 channel)"
```

---

## 收尾核查（执行完所有任务后）

1. `grep -rn "ErrNoAuthKey\|requireBleToken\|bleOpenAuthModeMessage\|notifyHandshakeFrameLocked" server/ android/app/src` — 应零命中（全部退役）。
2. `grep -rn "open-auth mode has no BLE data channel" server/ android/` — 应零命中（旧文案清理干净）。
3. 若全程被 Mimosa 拦截：汇总各任务待补提交清单（文件 + commit message）交还用户；若闸门已解除：按任务逐个补提交。
4. 手动冒烟（可选，需真机）：双空配置启动 server（确认启动 WARN）→ Android 不填任何密钥开 BLE → scan/connect/echo/章节阅读全通。
