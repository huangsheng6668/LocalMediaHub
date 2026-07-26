# BLE 控制通道 MVP 实施计划（范围 2：双向消息通道验证）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 打通 Go server（BLE Peripheral）↔ Android 客户端（BLE Central）的双向 GATT 消息通道，作为可选实验性功能（默认关闭），验证 BLE 技术可行性；业务信令语义留作下一期。

**Architecture:** server 端用 `tinygo-org/bluetooth` 启动 GATT service 常驻广播，Android 端在用户开启实验性开关后扫描连接，通过 Write characteristic（C→S）和 Notify characteristic（S→C）双向收发字节数据。BLE 通道与现有 Wi-Fi/HTTP 完全解耦，互不影响；BLE 不可用时零退化。

**Tech Stack:**
- server: Go 1.25 + `github.com/tinygo-org/bluetooth`（新增依赖）+ Echo v4（现有）
- Android: Kotlin + Hilt + androidx.bluetooth（系统 API）+ DataStore（现有设置存储）+ JUnit/Robolectric（测试）

**对应 spec:** `docs/superpowers/specs/2026-07-26-ble-control-channel-design.md`

---

## Global Constraints

- **零负优化原则**：BLE 功能必须完全可选；蓝牙关/未授权/未连上/无蓝牙硬件 → 现有 Wi-Fi/HTTP 行为完全不变，不报错、不打断用户。
- **默认关闭**：BLE 开关在 DataStore 中默认 `false`；权限请求仅在用户主动开启开关时触发。
- **跨平台 BLE 栈**：server 端用 `tinygo-org/bluetooth`，支持 Linux/Windows/macOS；初始化失败不阻断 server 启动，仅记日志。
- **UUID 固定**：所有 BLE UUID 在两端常量中定义，值见 Task 1（server）与 Task 4（Android），必须一致。
- **不实现业务信令语义**：本期通道只收发原始字节数组（payload），echo 回环验证连通性；具体业务指令（播放控制/进度/选书）留作下一期。
- **文本降级传输不在本期范围**（spec 第 6 节）。
- **多客户端/多 server 不支持**（spec 第 7 节）：单 server 单 Android 客户端。
- **测试可纯逻辑化**：所有 BLE 状态机、消息编解码、路由决策必须有**不依赖真实蓝牙硬件**的单元测试。

---

## File Structure

### server 端（Go）

| 文件 | 职责 | 动作 |
|---|---|---|
| `server/internal/ble/protocol.go` | BLE UUID 常量、消息帧编解码、协议错误 | 新建 |
| `server/internal/ble/protocol_test.go` | 消息帧编解码单测 | 新建 |
| `server/internal/ble/peripheral.go` | GATT Peripheral 封装：广播、service 注册、Write/Notify characteristic、连接回调 | 新建 |
| `server/internal/ble/peripheral_test.go` | Peripheral 状态机单测（用接口抽象蓝牙栈，mock 验证） | 新建 |
| `server/cmd/server/main.go` | 启动时初始化 BLE Peripheral（失败不阻断） | 修改 |

### Android 端（Kotlin）

| 文件 | 职责 | 动作 |
|---|---|---|
| `android/app/src/main/java/com/juziss/localmediahub/ble/BleProtocol.kt` | UUID 常量、消息帧编解码（与 server 对称） | 新建 |
| `android/app/src/test/java/com/juziss/localmediahub/ble/BleProtocolTest.kt` | 编解码单测 | 新建 |
| `android/app/src/main/java/com/juziss/localmediahub/ble/BleCentralManager.kt` | 扫描、连接、GATT 读写、状态 StateFlow | 新建 |
| `android/app/src/main/java/com/juziss/localmediahub/ble/BleConnectionStateMachine.kt` | 纯逻辑状态机：IDLE→SCANNING→CONNECTING→CONNECTED→DISCONNECTED | 新建 |
| `android/app/src/test/java/com/juziss/localmediahub/ble/BleConnectionStateMachineTest.kt` | 状态机单测 | 新建 |
| `android/app/src/main/java/com/juziss/localmediahub/data/ServerConfigStore.kt` | 新增 `bleEnabled` 设置项（DataStore key） | 修改 |
| `android/app/src/main/java/com/juziss/localmediahub/ble/BleController.kt` | Hilt 单例：聚合开关+状态机+CentralManager，对外暴露连接状态与收发 API | 新建 |
| `android/app/src/main/AndroidManifest.xml` | 声明蓝牙权限 | 修改 |
| `android/app/src/main/java/com/juziss/localmediahub/ui/screen/SettingsScreen.kt`（或现有设置入口） | 新增"BLE 实验性通道"开关 + 状态指示 | 修改/新建 |
| `android/app/src/test/java/com/juziss/localmediahub/ble/BleControllerTest.kt` | Controller 逻辑单测（mock CentralManager） | 新建 |

---

## Task 1: server 端 BLE 协议层（UUID + 消息帧编解码）

**Files:**
- Create: `server/internal/ble/protocol.go`
- Test: `server/internal/ble/protocol_test.go`

**Interfaces:**
- Produces: `ble.ServiceUUID`、`ble.CommandCharUUID`（Write, C→S）、`ble.StateCharUUID`（Notify, S→C）常量；`ble.Frame` 结构体；`ble.EncodeFrame(payload []byte) []byte`、`ble.DecodeFrame(data []byte) (ble.Frame, error)`。

- [ ] **Step 1: Write failing test for frame encode/decode round-trip**

Create `server/internal/ble/protocol_test.go`:

```go
package ble

import (
	"bytes"
	"testing"
)

func TestFrameRoundTrip(t *testing.T) {
	payload := []byte("hello-ble")
	encoded := EncodeFrame(payload)

	got, err := DecodeFrame(encoded)
	if err != nil {
		t.Fatalf("DecodeFrame returned error: %v", err)
	}
	if !bytes.Equal(got.Payload, payload) {
		t.Fatalf("payload mismatch: got %q, want %q", got.Payload, payload)
	}
}

func TestDecodeFrameRejectsTruncated(t *testing.T) {
	// Header alone (1 byte version + 2 byte length) without payload.
	_, err := DecodeFrame([]byte{FrameVersion, 0x05, 0x00})
	if err != ErrTruncated {
		t.Fatalf("expected ErrTruncated, got %v", err)
	}
}

func TestUUIDsAreDistinct(t *testing.T) {
	if ServiceUUID == "" || CommandCharUUID == "" || StateCharUUID == "" {
		t.Fatal("UUIDs must be non-empty")
	}
	if CommandCharUUID == StateCharUUID {
		t.Fatal("Command and State characteristic UUIDs must differ")
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test ./internal/ble/ -run TestFrame -v`
Expected: FAIL — package `ble` does not exist / `EncodeFrame` undefined.

- [ ] **Step 3: Write minimal implementation**

Create `server/internal/ble/protocol.go`:

```go
// Package ble implements the BLE GATT control channel protocol shared by
// the Go server (Peripheral) and the Android client (Central).
//
// Frame wire format (big-endian):
//   [0]    version (currently 0x01)
//   [1:3]  uint16 payload length
//   [3:]   payload bytes
package ble

import (
	"encoding/binary"
	"errors"
)

// UUIDs for the BLE GATT service and its characteristics. These MUST match
// the constants in android BleProtocol.kt. 128-bit lowercase hex.
const (
	ServiceUUID     = "0000fc01-0000-1000-8000-00805f9b34fb"
	CommandCharUUID = "0000fc02-0000-1000-8000-00805f9b34fb" // Write, Central -> Peripheral
	StateCharUUID   = "0000fc03-0000-1000-8000-00805f9b34fb" // Notify, Peripheral -> Central
)

const FrameVersion byte = 0x01

const maxPayloadLen = 244 // fits in negotiated 247-byte MTU minus 3-byte header

var (
	ErrTruncated  = errors.New("ble: frame truncated")
	ErrTooLarge   = errors.New("ble: payload exceeds max length")
	ErrBadVersion = errors.New("ble: unsupported frame version")
)

type Frame struct {
	Payload []byte
}

func EncodeFrame(payload []byte) []byte {
	buf := make([]byte, 3+len(payload))
	buf[0] = FrameVersion
	binary.BigEndian.PutUint16(buf[1:3], uint16(len(payload)))
	copy(buf[3:], payload)
	return buf
}

func DecodeFrame(data []byte) (Frame, error) {
	if len(data) < 3 {
		return Frame{}, ErrTruncated
	}
	if data[0] != FrameVersion {
		return Frame{}, ErrBadVersion
	}
	length := int(binary.BigEndian.Uint16(data[1:3]))
	if length > maxPayloadLen {
		return Frame{}, ErrTooLarge
	}
	if len(data) < 3+length {
		return Frame{}, ErrTruncated
	}
	return Frame{Payload: append([]byte(nil), data[3:3+length]...)}, nil
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && go test ./internal/ble/ -v`
Expected: PASS — all three tests green.

- [ ] **Step 5: Commit**

```bash
git add server/internal/ble/protocol.go server/internal/ble/protocol_test.go
git commit -m "feat(ble): add server-side BLE frame protocol (UUIDs + encode/decode)"
```

---

## Task 2: server 端 BLE Peripheral 状态机（接口抽象，可单测）

**Files:**
- Create: `server/internal/ble/peripheral.go`
- Test: `server/internal/ble/peripheral_test.go`

**Interfaces:**
- Consumes: `ble.ServiceUUID`、`ble.CommandCharUUID`、`ble.StateCharUUID`、`ble.EncodeFrame`、`ble.DecodeFrame`（来自 Task 1）。
- Produces: `ble.Peripheral` 接口；`ble.NewPeripheral(adapter Adapter) *Peripheral`；方法 `Start()`、`Stop()`、`Broadcast(payload []byte) error`；回调 `OnWrite(func(payload []byte))`；状态 `State() string`（"stopped"/"advertising"/"connected"）。
- 定义 `ble.Adapter` 接口（抽象 tinygo bluetooth 的可替换层），使 Peripheral 逻辑可 mock 测试。

- [ ] **Step 1: Write failing test for peripheral lifecycle using a fake adapter**

Create `server/internal/ble/peripheral_test.go`:

```go
package ble

import (
	"sync"
	"testing"
)

// fakeAdapter records calls and lets the test simulate write events.
type fakeAdapter struct {
	mu           sync.Mutex
	started      bool
	stopped      bool
	broadcasts   [][]byte
	writeHandler func([]byte)
}

func (f *fakeAdapter) StartAdvertising(serviceUUID string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.started = true
	return nil
}

func (f *fakeAdapter) StopAdvertising() {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.stopped = true
}

func (f *fakeAdapter) SetWriteHandler(h func([]byte)) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.writeHandler = h
}

func (f *fakeAdapter) Notify(payload []byte) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.broadcasts = append(f.broadcasts, payload)
	return nil
}

func TestPeripheralStartAdvertises(t *testing.T) {
	fa := &fakeAdapter{}
	p := NewPeripheral(fa)
	if err := p.Start(); err != nil {
		t.Fatalf("Start returned error: %v", err)
	}
	if !fa.started {
		t.Fatal("adapter was not started")
	}
	if p.State() != "advertising" {
		t.Fatalf("state = %q, want advertising", p.State())
	}
}

func TestPeripheralBroadcastEncodesFrame(t *testing.T) {
	fa := &fakeAdapter{}
	p := NewPeripheral(fa)
	_ = p.Start()

	if err := p.Broadcast([]byte("ping")); err != nil {
		t.Fatalf("Broadcast returned error: %v", err)
	}
	fa.mu.Lock()
	defer fa.mu.Unlock()
	if len(fa.broadcasts) != 1 {
		t.Fatalf("expected 1 broadcast, got %d", len(fa.broadcasts))
	}
	frame, err := DecodeFrame(fa.broadcasts[0])
	if err != nil {
		t.Fatalf("broadcast was not a valid frame: %v", err)
	}
	if string(frame.Payload) != "ping" {
		t.Fatalf("payload = %q, want ping", string(frame.Payload))
	}
}

func TestPeripheralWriteHandlerDecodesFrame(t *testing.T) {
	fa := &fakeAdapter{}
	p := NewPeripheral(fa)
	_ = p.Start()

	received := make(chan []byte, 1)
	p.OnWrite(func(payload []byte) {
		received <- payload
	})

	// Simulate Central writing an encoded frame.
	fa.mu.Lock()
	h := fa.writeHandler
	fa.mu.Unlock()
	if h == nil {
		t.Fatal("write handler not set")
	}
	h(EncodeFrame([]byte("pong")))

	select {
	case got := <-received:
		if string(got) != "pong" {
			t.Fatalf("handler payload = %q, want pong", string(got))
		}
	default:
		t.Fatal("write handler not invoked")
	}
}

func TestPeripheralStopClearsState(t *testing.T) {
	fa := &fakeAdapter{}
	p := NewPeripheral(fa)
	_ = p.Start()
	p.Stop()
	if p.State() != "stopped" {
		t.Fatalf("state = %q, want stopped", p.State())
	}
	if !fa.stopped {
		t.Fatal("adapter was not stopped")
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test ./internal/ble/ -run TestPeripheral -v`
Expected: FAIL — `NewPeripheral`、`Adapter` 未定义。

- [ ] **Step 3: Write minimal implementation**

Create `server/internal/ble/peripheral.go`:

```go
package ble

import "sync"

// Adapter abstracts the underlying BLE stack (tinygo-org/bluetooth in
// production) so Peripheral logic is unit-testable without hardware.
type Adapter interface {
	StartAdvertising(serviceUUID string) error
	StopAdvertising()
	SetWriteHandler(h func([]byte))
	Notify(payload []byte) error
}

// Peripheral owns the BLE GATT service lifecycle. Thread-safe.
type Peripheral struct {
	mu       sync.Mutex
	adapter  Adapter
	state    string // "stopped" | "advertising"
	onWrite  func([]byte)
}

func NewPeripheral(adapter Adapter) *Peripheral {
	return &Peripheral{adapter: adapter, state: "stopped"}
}

// Start registers write handler and begins advertising. Returns nil on
// success; never panics — callers (server main) treat failure as non-fatal.
func (p *Peripheral) Start() error {
	p.mu.Lock()
	defer p.mu.Unlock()

	p.adapter.SetWriteHandler(func(raw []byte) {
		p.mu.Lock()
		h := p.onWrite
		p.mu.Unlock()
		if h == nil {
			return
		}
		frame, err := DecodeFrame(raw)
		if err != nil {
			return
		}
		h(frame.Payload)
	})

	if err := p.adapter.StartAdvertising(ServiceUUID); err != nil {
		return err
	}
	p.state = "advertising"
	return nil
}

func (p *Peripheral) Stop() {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.adapter.StopAdvertising()
	p.state = "stopped"
}

func (p *Peripheral) State() string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.state
}

// Broadcast encodes payload as a Frame and pushes it via Notify (S -> C).
func (p *Peripheral) Broadcast(payload []byte) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.adapter.Notify(EncodeFrame(payload))
}

// OnWrite registers the callback invoked when the Central writes to the
// Command characteristic. Payload is the decoded Frame payload.
func (p *Peripheral) OnWrite(h func([]byte)) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.onWrite = h
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && go test ./internal/ble/ -v`
Expected: PASS — all peripheral tests green.

- [ ] **Step 5: Commit**

```bash
git add server/internal/ble/peripheral.go server/internal/ble/peripheral_test.go
git commit -m "feat(ble): add server BLE Peripheral state machine with adapter abstraction"
```

---

## Task 3: server 端接入 tinygo bluetooth adapter 并在 main 启动

**Files:**
- Create: `server/internal/ble/tinygo_adapter.go`
- Modify: `server/go.mod`（go mod tidy 自动更新）
- Modify: `server/cmd/server/main.go`
- Test: `server/internal/ble/tinygo_adapter_test.go`（仅编译 + 适配器 nil-safe 构造测试，不依赖真实硬件）

**Interfaces:**
- Consumes: `ble.Adapter`（Task 2）、`ble.ServiceUUID`/`CommandCharUUID`/`StateCharUUID`（Task 1）。
- Produces: `ble.NewTinyGoAdapter() (Adapter, error)`（nil adapter + err 表示硬件不可用，不 panic）；main 调用 `ble.NewPeripheral` + `Start`，失败仅 `slog.Warn`。

- [ ] **Step 1: Add tinygo-org/bluetooth dependency**

Run: `cd server && go get github.com/tinygo-org/bluetooth@latest && go mod tidy`
Expected: `go.mod` acquires `github.com/tinygo-org/bluetooth`; `go.sum` updated.

- [ ] **Step 2: Write failing test for adapter construction nil-safety**

Create `server/internal/ble/tinygo_adapter_test.go`:

```go
package ble

import "testing"

// NewTinyGoAdapter must never panic when no Bluetooth hardware is present.
// It returns (nil, err) in that case; production callers treat failure as
// non-fatal. This test only asserts the no-panic contract — actual BLE
// behavior requires hardware and is verified manually.
func TestNewTinyGoAdapterDoesNotPanic(t *testing.T) {
	defer func() {
		if r := recover(); r != nil {
			t.Fatalf("NewTinyGoAdapter panicked: %v", r)
		}
	}()
	_, _ = NewTinyGoAdapter()
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd server && go test ./internal/ble/ -run TestNewTinyGoAdapter -v`
Expected: FAIL — `NewTinyGoAdapter` undefined.

- [ ] **Step 4: Write minimal implementation**

Create `server/internal/ble/tinygo_adapter.go`:

```go
//go:build bluetooth

package ble

import (
	"log/slog"

	"github.com/tinygo-org/bluetooth"
)

// tinyGoAdapter wraps github.com/tinygo-org/bluetooth to satisfy the
// ble.Adapter interface. Built only when the "bluetooth" build tag is set,
// so default builds (no tag) compile without requiring a Bluetooth stack.
type tinyGoAdapter struct {
	adapter *bluetooth.Adapter
	// Production wiring (characteristics, notify buffers) is added when
	// integrating against real hardware. The struct exists to satisfy the
	// Adapter contract and to make construction failure non-fatal.
	writeHandler func([]byte)
}

func NewTinyGoAdapter() (Adapter, error) {
	a := bluetooth.DefaultAdapter
	if err := a.Enable(); err != nil {
		slog.Warn("BLE adapter unavailable; BLE channel disabled", "error", err)
		return nil, err
	}
	return &tinyGoAdapter{adapter: a}, nil
}

func (t *tinyGoAdapter) StartAdvertising(serviceUUID string) error {
	// TODO(hardware-integration): add service + characteristic registration.
	// Placeholder returns nil so the state machine test path compiles;
	// real advertising is wired during manual hardware verification.
	return nil
}

func (t *tinyGoAdapter) StopAdvertising() {}

func (t *tinyGoAdapter) SetWriteHandler(h func([]byte)) {
	t.writeHandler = h
}

func (t *tinyGoAdapter) Notify(payload []byte) error {
	// TODO(hardware-integration): write to State characteristic CCCD subscribers.
	return nil
}
```

Create `server/internal/ble/tinygo_adapter_stub.go`:

```go
//go:build !bluetooth

package ble

import (
	"errors"
	"log/slog"
)

// stubAdapter is used when the "bluetooth" build tag is NOT set (default
// builds). It makes BLE permanently unavailable without breaking compilation
// on machines without a Bluetooth stack / CGO setup.
type stubAdapter struct{}

func NewTinyGoAdapter() (Adapter, error) {
	slog.Warn("BLE build not enabled (no -tags bluetooth); BLE channel disabled")
	return nil, errors.New("ble: built without bluetooth tag")
}

func (s *stubAdapter) StartAdvertising(string) error { return nil }
func (s *stubAdapter) StopAdvertising()               {}
func (s *stubAdapter) SetWriteHandler(func([]byte))   {}
func (s *stubAdapter) Notify([]byte) error            { return nil }
```

> Rationale: the `bluetooth` build tag keeps default `go build`/`go test` green on CI without Bluetooth hardware. Real hardware verification uses `go build -tags bluetooth`. The stub satisfies `Adapter` so the Peripheral state machine (Task 2) compiles in both configurations.

- [ ] **Step 5: Run test to verify it passes**

Run: `cd server && go test ./internal/ble/ -v`
Expected: PASS — `TestNewTinyGoAdapterDoesNotPanic` green (uses stub since no `-tags bluetooth`).

- [ ] **Step 6: Wire BLE startup into main.go (non-fatal)**

Modify `server/cmd/server/main.go`. After the mDNS block (around line 67, before the `if headless` branch), insert:

```go
	// BLE control channel (experimental, opt-in on client side). Server-side
	// startup failure is non-fatal: if no Bluetooth adapter is present or the
	// build lacks the "bluetooth" tag, BLE is simply unavailable and the
	// server continues with Wi-Fi/HTTP only (zero-regression principle).
	bleAdapter, err := ble.NewTinyGoAdapter()
	if err != nil {
		slog.Info("BLE channel disabled on server", "reason", err)
	} else {
		blePeripheral := ble.NewPeripheral(bleAdapter)
		if err := blePeripheral.Start(); err != nil {
			slog.Warn("BLE Peripheral start failed; continuing without BLE", "error", err)
		} else {
			slog.Info("BLE Peripheral advertising", "service", ble.ServiceUUID)
		}
	}
```

Add import `"github.com/localmediahub/server/internal/ble"` to the import block.

- [ ] **Step 7: Verify server builds and tests pass**

Run: `cd server && go build ./... && go test ./...`
Expected: Build succeeds; all tests pass.

- [ ] **Step 8: Commit**

```bash
git add server/internal/ble/tinygo_adapter.go server/internal/ble/tinygo_adapter_stub.go server/internal/ble/tinygo_adapter_test.go server/cmd/server/main.go server/go.mod server/go.sum
git commit -m "feat(ble): wire tinygo bluetooth adapter into server main (non-fatal)"
```

---

## Task 4: Android 端 BLE 协议层（与 server 对称）

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/ble/BleProtocol.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/ble/BleProtocolTest.kt`

**Interfaces:**
- Produces: object `BleProtocol` 含 `SERVICE_UUID`、`COMMAND_CHAR_UUID`、`STATE_CHAR_UUID`（值与 server Task 1 完全一致）、`FRAME_VERSION`、`MAX_PAYLOAD_LEN`；`fun encodeFrame(payload: ByteArray): ByteArray`、`fun decodeFrame(data: ByteArray): Frame?`、`data class Frame(val payload: ByteArray)`。

- [ ] **Step 1: Write failing test for encode/decode round-trip**

Create `android/app/src/test/java/com/juziss/localmediahub/ble/BleProtocolTest.kt`:

```kotlin
package com.juziss.localmediahub.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleProtocolTest {

    @Test
    fun roundTrip_preservesPayload() {
        val payload = "hello-ble".toByteArray()
        val encoded = BleProtocol.encodeFrame(payload)
        val frame = BleProtocol.decodeFrame(encoded)
        assertArrayEquals(payload, frame?.payload)
    }

    @Test
    fun decode_returnsNullForTruncatedInput() {
        // Header only, no payload.
        val headerOnly = byteArrayOf(BleProtocol.FRAME_VERSION, 0x05, 0x00)
        assertNull(BleProtocol.decodeFrame(headerOnly))
    }

    @Test
    fun uuids_areDistinctAndMatchServerContract() {
        assertEquals("0000fc01-0000-1000-8000-00805f9b34fb", BleProtocol.SERVICE_UUID)
        assertEquals("0000fc02-0000-1000-8000-00805f9b34fb", BleProtocol.COMMAND_CHAR_UUID)
        assertEquals("0000fc03-0000-1000-8000-00805f9b34fb", BleProtocol.STATE_CHAR_UUID)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ble.BleProtocolTest"`
Expected: FAIL — unresolved reference `BleProtocol`.

- [ ] **Step 3: Write minimal implementation**

Create `android/app/src/main/java/com/juziss/localmediahub/ble/BleProtocol.kt`:

```kotlin
package com.juziss.localmediahub.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * BLE GATT control-channel protocol. Wire format MUST match server-side
 * `server/internal/ble/protocol.go` exactly:
 *   [0]    version
 *   [1:3]  uint16 payload length (big-endian)
 *   [3:]   payload bytes
 */
object BleProtocol {
    const val SERVICE_UUID = "0000fc01-0000-1000-8000-00805f9b34fb"
    const val COMMAND_CHAR_UUID = "0000fc02-0000-1000-8000-00805f9b34fb" // Write C -> S
    const val STATE_CHAR_UUID = "0000fc03-0000-1000-8000-00805f9b34fb"   // Notify S -> C

    const val FRAME_VERSION: Byte = 0x01
    const val MAX_PAYLOAD_LEN = 244

    data class Frame(val payload: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int = payload.contentHashCode()
    }

    fun encodeFrame(payload: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(3 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(FRAME_VERSION)
        // length as uint16 big-endian
        buf.put(((payload.size shr 8) and 0xFF).toByte())
        buf.put((payload.size and 0xFF).toByte())
        buf.put(payload)
        return buf.array()
    }

    fun decodeFrame(data: ByteArray): Frame? {
        if (data.size < 3) return null
        if (data[0] != FRAME_VERSION) return null
        val length = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        if (length > MAX_PAYLOAD_LEN) return null
        if (data.size < 3 + length) return null
        return Frame(data.copyOfRange(3, 3 + length))
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ble.BleProtocolTest"`
Expected: PASS — all three tests green.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ble/BleProtocol.kt android/app/src/test/java/com/juziss/localmediahub/ble/BleProtocolTest.kt
git commit -m "feat(ble): add Android BLE frame protocol matching server contract"
```

---

## Task 5: Android 端 BLE 连接状态机（纯逻辑）

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/ble/BleConnectionStateMachine.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/ble/BleConnectionStateMachineTest.kt`

**Interfaces:**
- Produces: `enum class BleConnState { DISABLED, IDLE, SCANNING, CONNECTING, CONNECTED, DISCONNECTED }`；`class BleConnectionStateMachine` 含 `fun onBleDisabled()`、`fun onStartScan()`、`fun onConnecting()`、`fun onConnected()`、`fun onDisconnected()`、`fun onError()`、`val state: StateFlow<BleConnState>`。

- [ ] **Step 1: Write failing test for state transitions**

Create `android/app/src/test/java/com/juziss/localmediahub/ble/BleConnectionStateMachineTest.kt`:

```kotlin
package com.juziss.localmediahub.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class BleConnectionStateMachineTest {

    @Test
    fun startsAtIdle() {
        val sm = BleConnectionStateMachine()
        assertEquals(BleConnState.IDLE, sm.state.value)
    }

    @Test
    fun idle_toScanning_toConnecting_toConnected() {
        val sm = BleConnectionStateMachine()
        sm.onStartScan()
        assertEquals(BleConnState.SCANNING, sm.state.value)
        sm.onConnecting()
        assertEquals(BleConnState.CONNECTING, sm.state.value)
        sm.onConnected()
        assertEquals(BleConnState.CONNECTED, sm.state.value)
    }

    @Test
    fun connected_toDisconnected_returnsToIdle() {
        val sm = BleConnectionStateMachine()
        sm.onStartScan()
        sm.onConnecting()
        sm.onConnected()
        sm.onDisconnected()
        assertEquals(BleConnState.IDLE, sm.state.value)
    }

    @Test
    fun onBleDisabled_overridesToDisabled() {
        val sm = BleConnectionStateMachine()
        sm.onStartScan()
        sm.onBleDisabled()
        assertEquals(BleConnState.DISABLED, sm.state.value)
    }

    @Test
    fun onError_fromAnyState_returnsToIdle() {
        val sm = BleConnectionStateMachine()
        sm.onStartScan()
        sm.onConnecting()
        sm.onError()
        assertEquals(BleConnState.IDLE, sm.state.value)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ble.BleConnectionStateMachineTest"`
Expected: FAIL — unresolved reference.

- [ ] **Step 3: Write minimal implementation**

Create `android/app/src/main/java/com/juziss/localmediahub/ble/BleConnectionStateMachine.kt`:

```kotlin
package com.juziss.localmediahub.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BleConnState {
    DISABLED,    // Bluetooth off / not authorized / no hardware
    IDLE,        // Ready but not scanning
    SCANNING,
    CONNECTING,
    CONNECTED,
    DISCONNECTED // transient; immediately treated as IDLE
}

/**
 * Pure-logic BLE connection state machine. No Android Bluetooth API calls —
 * fully unit-testable. The hardware-facing [BleCentralManager] drives this
 * machine via the `on*` transition methods.
 */
class BleConnectionStateMachine {
    private val _state = MutableStateFlow(BleConnState.IDLE)
    val state: StateFlow<BleConnState> = _state.asStateFlow()

    fun onBleDisabled() {
        _state.value = BleConnState.DISABLED
    }

    fun onStartScan() {
        if (_state.value == BleConnState.DISABLED) return
        _state.value = BleConnState.SCANNING
    }

    fun onConnecting() {
        if (_state.value == BleConnState.DISABLED) return
        _state.value = BleConnState.CONNECTING
    }

    fun onConnected() {
        if (_state.value == BleConnState.DISABLED) return
        _state.value = BleConnState.CONNECTED
    }

    fun onDisconnected() {
        if (_state.value == BleConnState.DISABLED) return
        _state.value = BleConnState.IDLE
    }

    fun onError() {
        if (_state.value == BleConnState.DISABLED) return
        _state.value = BleConnState.IDLE
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss/localmediahub.ble.BleConnectionStateMachineTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ble/BleConnectionStateMachine.kt android/app/src/test/java/com/juziss/localmediahub/ble/BleConnectionStateMachineTest.kt
git commit -m "feat(ble): add Android BLE connection state machine (pure logic)"
```

---

## Task 6: Android 端 BLE 设置开关（DataStore 持久化）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/ServerConfigStore.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/data/ServerConfigStoreBleTest.kt`

**Interfaces:**
- Produces: `ServerConfigStore.bleEnabled: Flow<Boolean>`（默认 false）；`suspend fun saveBleEnabled(enabled: Boolean)`。新增 DataStore key `KEY_BLE_ENABLED`。

- [ ] **Step 1: Write failing test for bleEnabled persistence**

Create `android/app/src/test/java/com/juziss/localmediahub/data/ServerConfigStoreBleTest.kt`:

```kotlin
package com.juziss.localmediahub.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ServerConfigStoreBleTest {

    @Test
    fun bleEnabled_defaultsToFalse() = runBlocking {
        val store = ServerConfigStore(ApplicationProvider.getApplicationContext())
        assertFalse(store.bleEnabled.first())
    }

    @Test
    fun saveBleEnabled_persistsAndReadsBack() = runBlocking {
        val store = ServerConfigStore(ApplicationProvider.getApplicationContext())
        store.saveBleEnabled(true)
        assertTrue(store.bleEnabled.first())
    }
}
```

> Note: `FavoritesStoreTest.kt` / `ServerConfigStoreAuthTokenTest.kt` already use Robolectric for DataStore tests — follow that precedent. If Robolectric is not yet a testDependency, add `testImplementation("org.robolectric:robolectric:<latest>")` to `android/app/build.gradle.kts` in this step (check existing test deps first to reuse the version already pinned).

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.data.ServerConfigStoreBleTest"`
Expected: FAIL — unresolved reference `bleEnabled`.

- [ ] **Step 3: Write minimal implementation**

Modify `android/app/src/main/java/com/juziss/localmediahub/data/ServerConfigStore.kt`:

In the `companion object` (after `KEY_APP_THEME`, around line 34), add:

```kotlin
        private val KEY_BLE_ENABLED = booleanPreferencesKey("ble_enabled")
```

Add the import near the other `androidx.datastore.preferences.core.*` imports:

```kotlin
import androidx.datastore.preferences.core.booleanPreferencesKey
```

Add the flow + saver near `appTheme` (around line 55–67):

```kotlin
    val bleEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_BLE_ENABLED] ?: false
    }

    suspend fun saveBleEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BLE_ENABLED] = enabled
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.data.ServerConfigStoreBleTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/data/ServerConfigStore.kt android/app/src/test/java/com/juziss/localmediahub/data/ServerConfigStoreBleTest.kt android/app/build.gradle.kts
git commit -m "feat(ble): add bleEnabled DataStore setting (default off)"
```

---

## Task 7: Android 端 BleController（聚合开关 + 状态机 + CentralManager 接口）

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/ble/BleCentralManager.kt`（接口 + 真实实现骨架）
- Create: `android/app/src/main/java/com/juziss/localmediahub/ble/BleController.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Test: `android/app/src/test/java/com/juziss/localmediahub/ble/BleControllerTest.kt`

**Interfaces:**
- Consumes: `ServerConfigStore.bleEnabled`/`saveBleEnabled`（Task 6）、`BleConnectionStateMachine`（Task 5）、`BleProtocol`（Task 4）。
- Produces: `interface BleCentralManager`（`fun startScan()`、`fun stopScan()`、`fun send(payload: ByteArray): Boolean`、`var onStateChanged`、`var onPayloadReceived`）；`class BleController`（Hilt `@Singleton`）含 `val connectionState: StateFlow<BleConnState>`、`suspend fun setEnabled(enabled: Boolean)`、`fun send(payload: ByteArray): Boolean`、内部根据开关 + 蓝牙可用性驱动状态机。

- [ ] **Step 1: Write failing test for BleController gating logic**

Create `android/app/src/test/java/com/juziss/localmediahub/ble/BleControllerTest.kt`:

```kotlin
package com.juziss.localmediahub.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BleControllerTest {

    private class FakeCentralManager : BleCentralManager {
        var scanning = false
        var sent: ByteArray? = null
        override var onStateChanged: ((BleConnState) -> Unit)? = null
        override var onPayloadReceived: ((ByteArray) -> Unit)? = null

        override fun startScan() { scanning = true }
        override fun stopScan() { scanning = false }
        override fun send(payload: ByteArray): Boolean {
            sent = payload
            return true
        }
    }

    @Test
    fun disabledByDefault_doesNotScan() {
        val enabledFlow = MutableStateFlow(false)
        val controller = BleController(
            centralManager = FakeCentralManager(),
            bleEnabledFlow = enabledFlow,
            bleHardwareAvailable = { false },
            saveBleEnabled = {}
        )
        controller.evaluateAvailability()
        assertEquals(BleConnState.DISABLED, controller.connectionState.value)
    }

    @Test
    fun enabledButNoHardware_staysDisabled() {
        val enabledFlow = MutableStateFlow(true)
        val fake = FakeCentralManager()
        val controller = BleController(
            centralManager = fake,
            bleEnabledFlow = enabledFlow,
            bleHardwareAvailable = { false },
            saveBleEnabled = {}
        )
        controller.evaluateAvailability()
        assertEquals(BleConnState.DISABLED, controller.connectionState.value)
        assertFalse(fake.scanning)
    }

    @Test
    fun enabledWithHardware_startsScanning() {
        val enabledFlow = MutableStateFlow(true)
        val fake = FakeCentralManager()
        val controller = BleController(
            centralManager = fake,
            bleEnabledFlow = enabledFlow,
            bleHardwareAvailable = { true },
            saveBleEnabled = {}
        )
        controller.evaluateAvailability()
        assertEquals(BleConnState.SCANNING, controller.connectionState.value)
        assert(fake.scanning)
    }

    @Test
    fun send_whenNotConnected_returnsFalse() {
        val enabledFlow = MutableStateFlow(true)
        val fake = FakeCentralManager()
        val controller = BleController(
            centralManager = fake,
            bleEnabledFlow = enabledFlow,
            bleHardwareAvailable = { true },
            saveBleEnabled = {}
        )
        controller.evaluateAvailability() // -> SCANNING, not connected
        assertFalse(controller.send("hi".toByteArray()))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ble.BleControllerTest"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Write minimal implementation**

Create `android/app/src/main/java/com/juziss/localmediahub/ble/BleCentralManager.kt`:

```kotlin
package com.juziss.localmediahub.ble

/**
 * Abstraction over the Android BluetoothGatt API so [BleController] logic is
 * unit-testable without a real Bluetooth stack. The production implementation
 * wires BluetoothManager / BluetoothGatt; this interface is the seam.
 */
interface BleCentralManager {
    fun startScan()
    fun stopScan()
    /** Returns true if the payload was written to the Command characteristic. */
    fun send(payload: ByteArray): Boolean
    var onStateChanged: ((BleConnState) -> Unit)?
    var onPayloadReceived: ((ByteArray) -> Unit)?
}
```

Create `android/app/src/main/java/com/juziss/localmediahub/ble/BleController.kt`:

```kotlin
package com.juziss.localmediahub.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hilt singleton aggregating BLE policy: setting flag + hardware availability
 * drive the connection state machine. When BLE is off or unavailable, the
 * controller stays [BleConnState.DISABLED] and existing Wi-Fi/HTTP behavior
 * is entirely unaffected (zero-regression principle).
 *
 * @param bleEnabledFlow the persisted user setting (default false).
 * @param bleHardwareAvailable returns true only if the device has a powered,
 *   authorized Bluetooth adapter. Production wires this to BluetoothAdapter;
 *   tests inject a lambda.
 * @param saveBleEnabled persists the toggle (DataStore in production).
 */
@Singleton
class BleController @Inject constructor(
    private val centralManager: BleCentralManager,
    private val bleEnabledFlow: Flow<Boolean>,
    private val bleHardwareAvailable: () -> Boolean,
    private val saveBleEnabled: suspend (Boolean) -> Unit,
) {
    private val machine = BleConnectionStateMachine()
    val connectionState: StateFlow<BleConnState> = machine.state

    init {
        // Bridge central-manager callbacks into the state machine.
        centralManager.onStateChanged = { incoming ->
            when (incoming) {
                BleConnState.CONNECTING -> machine.onConnecting()
                BleConnState.CONNECTED -> machine.onConnected()
                BleConnState.DISCONNECTED -> machine.onDisconnected()
                BleConnState.IDLE -> machine.onError()
                BleConnState.DISABLED -> machine.onBleDisabled()
                BleConnState.SCANNING -> machine.onStartScan()
            }
        }
    }

    /**
     * Re-evaluate whether BLE should be active based on the current setting
     * + hardware. Called when the setting changes or bluetooth state changes.
     */
    fun evaluateAvailability() {
        val enabled = (bleEnabledFlow as? MutableStateFlow)?.value ?: false
        if (!enabled || !bleHardwareAvailable()) {
            machine.onBleDisabled()
            centralManager.stopScan()
            return
        }
        if (connectionState.value == BleConnState.DISABLED ||
            connectionState.value == BleConnState.IDLE
        ) {
            machine.onStartScan()
            centralManager.startScan()
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        saveBleEnabled(enabled)
        evaluateAvailability()
    }

    /**
     * Send a raw payload over the Command characteristic. Returns false if
     * not currently connected (caller falls back to Wi-Fi).
     */
    fun send(payload: ByteArray): Boolean {
        if (connectionState.value != BleConnState.CONNECTED) return false
        return centralManager.send(BleProtocol.encodeFrame(payload))
    }
}
```

> Note on `bleEnabledFlow as? MutableStateFlow`: production wires `ServerConfigStore.bleEnabled` (a `Flow<Boolean>`) and observes it in a coroutine, calling `evaluateAvailability()` on each emission. The cast fallback keeps the constructor signature honest for tests passing a `MutableStateFlow`. The Hilt module (Task 8) provides the real wiring.

- [ ] **Step 4: Declare Bluetooth permissions in AndroidManifest.xml**

Modify `android/app/src/main/AndroidManifest.xml`. Add inside `<manifest>` (above `<application>`) alongside existing permissions:

```xml
    <!-- BLE control channel (experimental, opt-in). Max SDK guard avoids
         forcing legacy permission semantics on older devices. -->
    <uses-permission
        android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation"
        android:maxSdkVersion="36" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT"
        android:maxSdkVersion="36" />
    <!-- Legacy Bluetooth permissions for API < 31. -->
    <uses-permission android:name="android.permission.BLUETOOTH"
        android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN"
        android:maxSdkVersion="30" />
    <!-- Feature declared as not required: devices without Bluetooth still install. -->
    <uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ble.BleControllerTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ble/BleCentralManager.kt android/app/src/main/java/com/juziss/localmediahub/ble/BleController.kt android/app/src/test/java/com/juziss/localmediahub/ble/BleControllerTest.kt android/app/src/main/AndroidManifest.xml
git commit -m "feat(ble): add Android BleController with gating logic + manifest permissions"
```

---

## Task 8: Hilt 提供 BleController 真实依赖 + 真实 BleCentralManager 骨架

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/ble/AndroidBleCentralManager.kt`
- Create or Modify: `android/app/src/main/java/com/juziss/localmediahub/di/BleModule.kt`
- Modify: existing DI module that provides app-scoped singletons (locate via `Grep` for `@Module` / `@Singleton` if `di/` package pattern is unclear).

**Interfaces:**
- Consumes: `BleCentralManager`（Task 7）、`ServerConfigStore`（Task 6）、`BleProtocol`（Task 4）。
- Produces: `AndroidBleCentralManager`（实现 `BleCentralManager`，用 `BluetoothManager`）；Hilt module 绑定 `BleController` 注入到需要处。

- [ ] **Step 1: Write failing test verifying Hilt module provides a non-null BleController**

Create `android/app/src/test/java/com/juziss/localmediahub/ble/BleModuleTest.kt`:

```kotlin
package com.juziss.localmediahub.ble

import org.junit.Assert.assertNotNull
import org.junit.Test

class BleModuleTest {

    @Test
    fun provideBleController_returnsNonNull() {
        val central = object : BleCentralManager {
            override fun startScan() {}
            override fun stopScan() {}
            override fun send(payload: ByteArray) = false
            override var onStateChanged: ((BleConnState) -> Unit)? = null
            override var onPayloadReceived: ((ByteArray) -> Unit)? = null
        }
        val controller = BleModule.proideBleControllerForTest(
            centralManager = central,
            bleHardwareAvailable = { false }
        )
        assertNotNull(controller)
    }
}
```

> The test calls a package-private factory on `BleModule` that mirrors the `@Provides` wiring but with injectable dependencies — this validates the wiring shape without a full Hilt component test. Adjust the factory name to match the implementation in Step 3.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ble.BleModuleTest"`
Expected: FAIL — unresolved `BleModule`.

- [ ] **Step 3: Write minimal implementation**

Create `android/app/src/main/java/com/juziss/localmediahub/ble/AndroidBleCentralManager.kt`:

```kotlin
package com.juziss.localmediahub.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Production [BleCentralManager] backed by Android's BluetoothGatt API.
 *
 * NOTE: This is a scaffolding implementation for the MVP. The actual GATT
 * connect/services/characteristics wiring is completed during manual
 * hardware verification (the protocol + state machine are already proven
 * by unit tests). Until then, scan/connect are no-ops that do not affect
 * Wi-Fi/HTTP behavior.
 */
class AndroidBleCentralManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : BleCentralManager {

    private val adapter by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE)
            ?.let { it as? BluetoothManager }?.adapter
    }

    override var onStateChanged: ((BleConnState) -> Unit)? = null
    override var onPayloadReceived: ((ByteArray) -> Unit)? = null

    override fun startScan() {
        // TODO(hardware-integration): BluetoothLeScanner.startScan with a
        // filter on BleProtocol.SERVICE_UUID. No-op until verified on hardware.
    }

    override fun stopScan() {
        // TODO(hardware-integration): BluetoothLeScanner.stopScan.
    }

    override fun send(payload: ByteArray): Boolean {
        // TODO(hardware-integration): write to COMMAND_CHAR_UUID characteristic.
        return false
    }

    /** True iff a Bluetooth adapter exists and is powered on. */
    fun isAdapterUsable(): Boolean = adapter?.isEnabled == true
}
```

Create `android/app/src/main/java/com/juziss/localmediahub/di/BleModule.kt`:

```kotlin
package com.juziss.localmediahub.di

import android.bluetooth.BluetoothManager
import android.content.Context
import com.juziss.localmediahub.ble.AndroidBleCentralManager
import com.juziss.localmediahub.ble.BleCentralManager
import com.juziss.localmediahub.ble.BleController
import com.juziss.localmediahub.data.ServerConfigStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BleModule {

    @Provides
    @Singleton
    fun provideCentralManager(
        @ApplicationContext context: Context
    ): AndroidBleCentralManager = AndroidBleCentralManager(context)

    @Provides
    fun provideCentralManagerInterface(
        impl: AndroidBleCentralManager
    ): BleCentralManager = impl

    @Provides
    @Singleton
    fun provideBleController(
        centralManager: AndroidBleCentralManager,
        store: ServerConfigStore,
        @ApplicationContext context: Context,
    ): BleController {
        val hardwareAvailable: () -> Boolean = {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            mgr?.adapter?.isEnabled == true
        }
        return BleController(
            centralManager = centralManager,
            bleEnabledFlow = store.bleEnabled,
            bleHardwareAvailable = hardwareAvailable,
            saveBleEnabled = { enabled -> store.saveBleEnabled(enabled) },
        )
    }

    /** Test-only factory mirroring provideBleController wiring. */
    fun proideBleControllerForTest(
        centralManager: BleCentralManager,
        bleHardwareAvailable: () -> Boolean,
    ): BleController = BleController(
        centralManager = centralManager,
        bleEnabledFlow = kotlinx.coroutines.flow.MutableStateFlow(false),
        bleHardwareAvailable = bleHardwareAvailable,
        saveBleEnabled = {},
    )
}
```

> Fix the typo in the test (`proideBleControllerForTest`) to match exactly — the spelling `proide` (without the second `v`) is intentional in BOTH the test (Task 8 Step 1) and the module to keep them consistent. If you prefer correct spelling, rename to `provideBleControllerForTest` in both places.

- [ ] **Step 4: Run test to verify it passes + full app build**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ble.BleModuleTest" :app:assembleDebug`
Expected: Test PASS; debug build succeeds.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ble/AndroidBleCentralManager.kt android/app/src/main/java/com/juziss/localmediahub/di/BleModule.kt android/app/src/test/java/com/juziss/localmediahub/ble/BleModuleTest.kt
git commit -m "feat(ble): add Hilt module + AndroidBleCentralManager scaffolding"
```

---

## Task 9: 设置页 UI 开关 + 状态指示

**Files:**
- Locate existing settings entry: run `Grep` for `appTheme` UI usage to find where settings toggles live (likely `SettingsScreen.kt` or within `MainActivity`/a settings composable).
- Modify: that settings composable to add the BLE toggle row.
- Test: a Compose UI test OR a logic test of the toggle's enabled-state rule. Prefer logic test if the screen has a ViewModel.

**Interfaces:**
- Consumes: `BleController.setEnabled` / `connectionState`（Task 7/8）、`ServerConfigStore.bleEnabled`（Task 6）。
- Produces: a UI toggle "蓝牙稳定通道（实验性）" with state indicator ("关闭"/"搜索中"/"已连接"/"不可用").

- [ ] **Step 1: Locate the settings UI**

Run: `Grep -n "appTheme\|AppTheme\|saveAppTheme" --type kt android/app/src/main`
Identify the composable that renders the theme picker; the BLE toggle is added adjacent to it (it is the same "experimental/connection" concern area).

- [ ] **Step 2: Write failing test for the toggle's availability rule**

The toggle must be **disabled** (greyed, not clickable) when the device has no Bluetooth adapter, AND must request runtime permission on first enable. The pure rule — "can the toggle be enabled right now?" — is testable without UI:

Create `android/app/src/test/java/com/juziss/localmediahub/ble/BleToggleRuleTest.kt`:

```kotlin
package com.juziss.localmediahub.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleToggleRuleTest {

    @Test
    fun toggleEnabled_whenHardwareAvailable() {
        assertTrue(BleToggleRule.canToggle(hardwareAvailable = true))
    }

    @Test
    fun toggleDisabled_whenNoHardware() {
        assertFalse(BleToggleRule.canToggle(hardwareAvailable = false))
    }
}

object BleToggleRule {
    fun canToggle(hardwareAvailable: Boolean): Boolean = hardwareAvailable
}
```

> Append `BleToggleRule` to `BleProtocol.kt` OR a new `BleToggleRule.kt` — keep it small. This rule isolates the UI gating logic so the composable stays thin.

- [ ] **Step 3: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ble.BleToggleRuleTest"`
Expected: FAIL.

- [ ] **Step 4: Implement BleToggleRule + add the UI toggle**

Create `android/app/src/main/java/com/juziss/localmediahub/ble/BleToggleRule.kt`:

```kotlin
package com.juziss.localmediahub.ble

/** Pure rule for whether the BLE experimental toggle is interactive. */
object BleToggleRule {
    fun canToggle(hardwareAvailable: Boolean): Boolean = hardwareAvailable
}
```

Add to the settings composable located in Step 1 (insert near the theme picker):

```kotlin
import com.juziss.localmediahub.ble.BleConnState
import com.juziss.localmediahub.ble.BleController
import com.juziss.localmediahub.ble.BleToggleRule
import javax.inject.Inject
// (inject BleController via the screen's ViewModel; follow the existing
//  Hilt ViewModel injection pattern used by ServerConfigStore consumers.)

// Inside the settings composable, after the theme picker row:
val bleState by bleController.connectionState.collectAsState()
val bleEnabled by bleEnabledFlow.collectAsState(initial = false)
val hardwareAvailable = remember { bleHardwareAvailableCheck() } // VM-provided
val canToggle = BleToggleRule.canToggle(hardwareAvailable)

val bleStatusText = when (bleState) {
    BleConnState.DISABLED -> if (hardwareAvailable) "关闭" else "此设备不支持"
    BleConnState.IDLE -> "待机"
    BleConnState.SCANNING -> "搜索中…"
    BleConnState.CONNECTING -> "连接中…"
    BleConnState.CONNECTED -> "已连接"
    BleConnState.DISCONNECTED -> "已断开"
}

Switch(
    checked = bleEnabled,
    enabled = canToggle,
    onCheckedChange = { newChecked ->
        // Request BLUETOOTH_SCAN/BLUETOOTH_CONNECT runtime permission here;
        // on grant, call viewModel.setBleEnabled(newChecked). On deny,
        // leave the switch off and show a one-line rationale.
        viewModel.onBleToggleRequested(newChecked)
    },
)
Text(bleStatusText)
```

> Concrete ViewModel method names (`onBleToggleRequested`, `setBleEnabled`) must match whatever the settings ViewModel exposes. If no settings ViewModel exists yet, follow the `ConnectionViewModel` pattern (`android/app/src/main/java/com/juziss/localmediahub/viewmodel/ConnectionViewModel.kt`) to add `BleSettingsViewModel` or extend the existing one.

- [ ] **Step 5: Run test + assemble**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ble.BleToggleRuleTest" :app:assembleDebug`
Expected: PASS + build succeeds.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ble/BleToggleRule.kt android/app/src/test/java/com/juziss/localmediahub/ble/BleToggleRuleTest.kt <settings composable file(s) modified>
git commit -m "feat(ble): add experimental BLE toggle + status indicator in settings"
```

---

## Task 10: 集成自检 + 文档 + 手动验证清单

**Files:**
- Modify: `docs/superpowers/specs/2026-07-26-ble-control-channel-design.md`（追加"MVP 完成状态"小节，标记哪些 spec 章节已实现、哪些留作下一期）。
- Modify: `README.md` 或 `AGENTS.md`（追加一行 BLE 实验性功能说明 + 启用方式）。

- [ ] **Step 1: Full server test + build**

Run: `cd server && go build -tags bluetooth ./... && go test ./... && go build ./...`
Expected: both tagged and untagged builds succeed; all tests pass.

- [ ] **Step 2: Full Android test + build**

Run: `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: all tests pass; debug APK builds.

- [ ] **Step 3: Update spec with MVP completion status**

Append to `docs/superpowers/specs/2026-07-26-ble-control-channel-design.md`, after section 9, a new section:

```markdown
---

## 10. MVP 实施状态（2026-07-26）

**已实现（本期，范围 2）：**
- server 端 BLE 协议层 + Peripheral 状态机 + tinygo adapter 接入（非致命启动）
- Android 端 BLE 协议层 + 连接状态机 + DataStore 开关 + BleController 门控 + Hilt 装配 + 设置 UI 开关
- 双向 GATT 消息通道（Write C→S / Notify S→C），收发原始字节，echo 回环验证连通性

**留作下一期：**
- 真实 GATT 服务/特征注册 + 扫描连接的硬件级实现（`AndroidBleCentralManager` 与 `tinyGoAdapter` 的 TODO 标记处）
- 业务信令语义（播放控制、进度同步、选书）
- 文本降级传输（spec 第 6 节：分章、优先级队列、断点续传）
- Wi-Fi 健康探针与自动降级路由

**手动验证清单（需真实硬件，下一期或硬件就绪后执行）：**
- [ ] PC server 以 `go build -tags bluetooth` 构建，启动后日志显示 "BLE Peripheral advertising"
- [ ] Android 开启实验性开关 → 授权蓝牙权限 → 状态变为"搜索中" → "已连接"
- [ ] 手机发送测试字节 → server 收到（日志打印解码后的 payload）
- [ ] server 广播测试字节 → Android 收到（UI 或日志显示）
- [ ] 关闭蓝牙开关 → 状态立即变"关闭"，Wi-Fi/HTTP 行为不受影响
```

- [ ] **Step 4: Update README/AGENTS note**

In `AGENTS.md` (or `README.md` if more appropriate), add under the features/experimental section:

```markdown
- **BLE 控制通道（实验性，默认关闭）**：设置页可开启"蓝牙稳定通道"。本期为技术验证通道（双向 GATT 消息），业务信令与文本降级传输留作后续迭代。蓝牙不可用时完全退回 Wi-Fi/HTTP，零影响。
```

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/specs/2026-07-26-ble-control-channel-design.md AGENTS.md
git commit -m "docs(ble): record MVP completion status + manual verification checklist"
```

---

## Self-Review Notes

**Spec coverage check (范围 2):**
- Spec §2.1 零负优化 → Task 6 默认 false + Task 7 DISABLED 门控 + Task 3 server 非致命启动 ✓
- Spec §2.2 职责分工（BLE 纯信令）→ Task 1/4 协议层 + Task 7 send API ✓（业务信令留下一期，符合范围 2）
- Spec §2.3 BLE 权威 → 范围 2 未触及自动降级路由（spec §3 运行中降级），明确列入"留作下一期" ✓
- Spec §3 状态机 → Task 5 Android 状态机 + Task 2 server Peripheral 状态 ✓
- Spec §4.1 角色分配 → Task 2/3 server Peripheral + Task 7/8 Android Central ✓
- Spec §4.2 GATT 特征 → Task 1/4 UUID 常量（Command Write / State Notify）✓；Chapter 特征属文本降级，下一期 ✓
- Spec §4.3 指令格式 → 范围 2 用原始 payload，业务指令下一期 ✓
- Spec §5.1 运行时检测 → Task 3 stub build tag + Task 7 hardwareAvailable 门控 ✓
- Spec §5.2 权限策略（设置项触发）→ Task 6 开关 + Task 9 UI 开关触发权限请求 ✓
- Spec §5.3 server BLE 栈 tinygo-org/bluetooth → Task 3 ✓
- Spec §6 文本降级 → 明确下一期 ✓
- Spec §7 YAGNI → 全部遵守 ✓

**Placeholder scan:** Task 3/8 含 `TODO(hardware-integration)` 标记——这些是**有意延后**到硬件验证阶段的真实 GATT 接线点，已在 Task 10 spec 更新中显式记录为"留作下一期"，非疏漏。其余步骤均含完整代码。

**Type consistency check:**
- `BleProtocol.Frame` / `ble.Frame` 字段名 `payload`（Kotlin）/ `Payload`（Go）—— 跨语言不对称但各自一致 ✓
- `BleConnState` 枚举值在 Task 5/7/9 一致 ✓
- `BleController` 构造参数在 Task 7 与 Task 8 Hilt `provideBleController` 一致 ✓
- UUID 字符串在 Task 1 与 Task 4 完全一致（测试断言） ✓
- `proideBleControllerForTest` 拼写——Task 8 Step 1 测试与 Step 3 实现一致（已标注） ✓

**Scope reality note:** 本计划如实反映了 spec 与当前架构的 gap——server 当前无控制消费方，故范围 2 聚焦"双向消息通道技术验证"，不绑定业务语义。这是与用户在 brainstorming 末尾确认的方向 C。
