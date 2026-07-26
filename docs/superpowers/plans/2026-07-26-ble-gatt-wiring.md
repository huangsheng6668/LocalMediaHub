# BLE GATT 硬件接线实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把上期的 BLE 协议层接到真实 GATT 硬件，实现 Android（Peripheral 广播）↔ PC server（Central 扫描连接）端到端连通，完成双向 echo 验证。

**Architecture:** 角色反转（相对上期）——Android 当 Peripheral 用 `BluetoothGattServer` 广播 Service UUID + 提供 Command(Write)/State(Notify) 特征；PC server 当 Central 用 `tinygo-org/bluetooth` 扫描连接。BLE 连接由 Android 通过现有 Wi-Fi/HTTP（`/api/v1/ble/scan|connect|send`）协调，PC 中转 BLE 数据。BLE 不可用时零退化。

**Tech Stack:**
- server: Go 1.25 + `tinygo.org/x/bluetooth` v0.15.0（已有依赖）+ Echo v4
- Android: Kotlin + Hilt + `android.bluetooth.BluetoothGattServer` + OkHttp + JUnit/Robolectric/MockWebServer

**对应 spec:** `docs/superpowers/specs/2026-07-26-ble-gatt-wiring-design.md`

---

## Global Constraints

- **角色反转**：Android = Peripheral（`BluetoothGattServer` 广播 + GATT service）；PC server = Central（扫描 + 连接 + 读写）。不要沿用上期的 Peripheral 代码。
- **UUID 复用上期常量**（`server/internal/ble/protocol.go` + `android/.../ble/BleProtocol.kt`，已存在，勿改）：ServiceUUID `0000fc01-0000-1000-8000-00805f9b34fb`、CommandCharUUID `0000fc02-...`（Write C→S）、StateCharUUID `0000fc03-...`（Notify S→C）。
- **HTTP 路由前缀 `/api/v1/ble/*`**（与现有路由约定一致，见 `server/internal/server/server.go:196` 的 `api := s.Echo.Group("/api/v1")`）。
- **零退化**：BLE 不可用（无硬件/蓝牙关/无 `bluetooth` tag）→ server 3 个 endpoint 返回明确空/错误响应而非崩溃；server 启动失败仅 `slog.Warn`；Android 开关关 → 广播停，现有功能不变。
- **超时**：scan 3s / connect 10s / send echo 5s，超时返回错误不挂起。
- **并发串行化**：PC Central 用 mutex 保证同一时刻只有一个 scan/connect/send 进行中。
- **扫描过滤**：PC Central 只发现广播 SERVICE_UUID 的设备（运行本 app 的 Android）。
- **Windows 优先**：用 `bluetooth` build tag 让默认构建不依赖真实蓝牙栈（stub fallback）；真机验证用 `go build -tags bluetooth`。
- **payload ≤ 244 字节**（MAX_PAYLOAD_LEN），超出 → HTTP 400。
- **复用现有 Bearer Token 鉴权**（`/api/v1/ble/*` 挂在已鉴权的 `api` group 下）。
- **不做**（YAGNI）：自动重连、MTU 协商优化、业务信令、文本降级、Linux 适配、户外无 Wi-Fi 场景、显示非本 app 的 BLE 设备。

---

## File Structure

### server 端（Go，PC 当 Central）

| 文件 | 职责 | 动作 |
|---|---|---|
| `server/internal/ble/central.go` | Central 封装：扫描过滤、连接、GATT 发现、读写特征、状态、并发 mutex | 新建 |
| `server/internal/ble/central_test.go` | Central 纯逻辑测试（CentralScanner 接口 mock） | 新建 |
| `server/internal/ble/central_adapter.go` | `bluetooth` build tag：tinygo 实现的 CentralScanner | 新建 |
| `server/internal/ble/central_adapter_stub.go` | 默认构建 stub | 新建 |
| `server/internal/server/handler/ble.go` | HTTP handler：scan/connect/send | 新建 |
| `server/internal/server/handler/ble_test.go` | handler 测试 | 新建 |
| `server/internal/server/handler/handler.go` | `Handler` struct 加 `BLE *ble.Central` 字段 | 修改 |
| `server/internal/server/server.go` | 注册 `/api/v1/ble/*` 路由 + 构造 Central 注入 handler | 修改 |
| `server/cmd/server/main.go` | 启动时初始化 Central（非致命） | 修改 |
| `server/internal/ble/peripheral.go` / `peripheral_test.go` | 上期 Peripheral 代码 | **删除** |
| `server/internal/ble/tinygo_adapter.go` / `tinygo_adapter_stub.go` / `tinygo_adapter_test.go` | 上期 Peripheral adapter | **删除**（Central 用新的 `central_adapter*.go`） |
| `server/internal/ble/protocol.go` / `protocol_test.go` | 帧编解码 | **保留** |

### Android 端（Kotlin，当 Peripheral）

| 文件 | 职责 | 动作 |
|---|---|---|
| `ble/BlePeripheralManager.kt` | 接口（替代上期 `BleCentralManager`） | 新建 |
| `ble/AndroidBlePeripheralManager.kt` | `BluetoothGattServer` 实现：广播 + service + 收 Command 写 + 发 State notify | 新建 |
| `ble/BleCentralManager.kt` | 上期接口 | **删除** |
| `ble/AndroidBleCentralManager.kt` | 上期 Central 骨架 | **删除** |
| `ble/BleConnectionStateMachine.kt` | 上期状态机 | 修改：`SCANNING` → `ADVERTISING` |
| `ble/BleConnectionStateMachineTest.kt` | 测试 | 修改：同步改名 |
| `ble/BleController.kt` | 上期门控 | 修改：用 PeripheralManager，状态由 HTTP 协调结果驱动 |
| `ble/BleControllerTest.kt` | 测试 | 修改：适配新接口 |
| `ble/BleProtocol.kt` / `BleToggleRule.kt` | 上期 | **保留** |
| `data/BleApi.kt` | 调 `/api/v1/ble/*`（仿 `MediaRepository.httpGet/httpPost`） | 新建 |
| `data/BleApiTest.kt` | MockWebServer 测试 | 新建 |
| `viewmodel/BleSettingsViewModel.kt` | 上期 VM | 修改：加 scan/connect/sendTest |
| `viewmodel/BleSettingsViewModelTest.kt` | 新建测试 | 新建 |
| `ui/screen/ConnectionScreen.kt` | 上期开关 UI | 修改：加扫描列表 + 选设备 + 发送测试按钮 + echo 回显 |
| `di/BleModule.kt` | 上期 Hilt 装配 | 修改：提供 PeripheralManager + BleApi |

---

## Task 1: server 端 Central 纯逻辑（扫描过滤 + 连接状态机）

**Files:**
- Create: `server/internal/ble/central.go`
- Test: `server/internal/ble/central_test.go`

**Interfaces:**
- Consumes: `ble.ServiceUUID`、`ble.EncodeFrame`、`ble.DecodeFrame`（来自上期 `protocol.go`）。
- Produces: `ble.CentralScanner` 接口（抽象 BLE 栈）；`ble.Central` struct；`ble.NewCentral(s CentralScanner) *Central`；方法 `Scan(ctx) ([]Device, error)`、`Connect(ctx, id string) error`、`Send(ctx, payload []byte) ([]byte, error)`、`Disconnect()`、`State() string`；`type Device struct { ID, Name string; RSSI int }`。

- [ ] **Step 1: Write failing test for Central scan filtering + connect + send (using fake scanner)**

Create `server/internal/ble/central_test.go`:

```go
package ble

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"
)

type fakeScanner struct {
	mu          sync.Mutex
	devices     []Device
	connectedID string
	written     []byte
	notifyResp  []byte // what the peripheral echoes back via notify
	notifyErr   error
	scanCalled  bool
}

func (f *fakeScanner) Scan(ctx context.Context, serviceUUID string) ([]Device, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.scanCalled = true
	return f.devices, nil
}

func (f *fakeScanner) Connect(ctx context.Context, id string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.connectedID = id
	return nil
}

func (f *fakeScanner) Disconnect() {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.connectedID = ""
}

func (f *fakeScanner) WriteCommand(ctx context.Context, payload []byte) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.written = payload
	return nil
}

func (f *fakeScanner) WaitNotify(ctx context.Context) ([]byte, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.notifyErr != nil {
		return nil, f.notifyErr
	}
	return f.notifyResp, nil
}

func TestCentralScanReturnsDevices(t *testing.T) {
	fs := &fakeScanner{devices: []Device{{ID: "AA:BB", Name: "Pixel", RSSI: -45}}}
	c := NewCentral(fs)
	got, err := c.Scan(context.Background())
	if err != nil {
		t.Fatalf("Scan error: %v", err)
	}
	if len(got) != 1 || got[0].ID != "AA:BB" {
		t.Fatalf("got %+v", got)
	}
}

func TestCentralConnectSetsState(t *testing.T) {
	fs := &fakeScanner{}
	c := NewCentral(fs)
	if err := c.Connect(context.Background(), "AA:BB"); err != nil {
		t.Fatalf("Connect error: %v", err)
	}
	if c.State() != "connected" {
		t.Fatalf("state=%q want connected", c.State())
	}
}

func TestCentralSendEncodesAndReturnsEcho(t *testing.T) {
	fs := &fakeScanner{notifyResp: EncodeFrame([]byte("pong"))}
	c := NewCentral(fs)
	_ = c.Connect(context.Background(), "AA:BB")
	echo, err := c.Send(context.Background(), []byte("ping"))
	if err != nil {
		t.Fatalf("Send error: %v", err)
	}
	if string(echo) != "pong" {
		t.Fatalf("echo=%q want pong", string(echo))
	}
	// Verify written payload was encoded.
	fs.mu.Lock()
	defer fs.mu.Unlock()
	frame, err := DecodeFrame(fs.written)
	if err != nil {
		t.Fatalf("written not a valid frame: %v", err)
	}
	if string(frame.Payload) != "ping" {
		t.Fatalf("written payload=%q want ping", string(frame.Payload))
	}
}

func TestCentralSendWhenNotConnectedErrors(t *testing.T) {
	fs := &fakeScanner{}
	c := NewCentral(fs)
	_, err := c.Send(context.Background(), []byte("ping"))
	if err == nil {
		t.Fatal("expected error when not connected")
	}
}

func TestCentralScanTimeout(t *testing.T) {
	fs := &fakeScanner{}
	c := NewCentral(fs).WithScanTimeout(10 * time.Millisecond)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Millisecond)
	defer cancel()
	// Override scanner to block until ctx done.
	_, err := c.Scan(ctx)
	if err == nil {
		t.Fatal("expected timeout error")
	}
}

func TestCentralConnectSerializesConcurrentCalls(t *testing.T) {
	// Two concurrent Connect calls: second must wait for first (no panic, no race).
	fs := &fakeScanner{}
	c := NewCentral(fs)
	var wg sync.WaitGroup
	errs := make([]error, 2)
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			errs[i] = c.Connect(context.Background(), "AA:BB")
		}(i)
	}
	wg.Wait()
	for _, e := range errs {
		if e != nil {
			t.Fatalf("connect error: %v", e)
		}
	}
}
```

To make `TestCentralScanTimeout` work, the fakeScanner's `Scan` returns immediately. To test real timeout behavior, we need a blocking scanner. Replace the fake's `Scan` in that test with a blocking variant inline — adjust: instead of using `WithScanTimeout`, the test relies on `ctx` cancellation propagating. Update `fakeScanner.Scan` to respect context:

Actually, to keep the fake simple and the timeout test honest, modify `fakeScanner.Scan` to block on ctx if no devices:

```go
func (f *fakeScanner) Scan(ctx context.Context, serviceUUID string) ([]Device, error) {
	f.mu.Lock()
	f.scanCalled = true
	devs := f.devices
	f.mu.Unlock()
	if devs != nil {
		return devs, nil
	}
	<-ctx.Done()
	return nil, ctx.Err()
}
```

And drop the `WithScanTimeout` method from the test (Central itself doesn't impose an internal timeout — the caller passes a ctx with deadline). The timeout test becomes:

```go
func TestCentralScanTimeout(t *testing.T) {
	fs := &fakeScanner{} // no devices → blocks until ctx done
	c := NewCentral(fs)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Millisecond)
	defer cancel()
	_, err := c.Scan(ctx)
	if err == nil {
		t.Fatal("expected timeout error")
	}
}
```

Final test file assembles all of the above with the blocking `Scan`.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test ./internal/ble/ -run TestCentral -v`
Expected: FAIL — `NewCentral`、`CentralScanner`、`Device` undefined.

- [ ] **Step 3: Write minimal implementation**

Create `server/internal/ble/central.go`:

```go
package ble

import (
	"context"
	"errors"
	"sync"
)

// ErrNotConnected is returned when an operation requires an active BLE
// connection but none exists.
var ErrNotConnected = errors.New("ble: not connected")

// Device is a discovered BLE peripheral.
type Device struct {
	ID   string
	Name string
	RSSI int
}

// CentralScanner abstracts the BLE Central-role stack so Central logic is
// unit-testable without hardware. Production impl lives in central_adapter.go
// (bluetooth build tag).
type CentralScanner interface {
	Scan(ctx context.Context, serviceUUID string) ([]Device, error)
	Connect(ctx context.Context, id string) error
	Disconnect()
	WriteCommand(ctx context.Context, payload []byte) error
	WaitNotify(ctx context.Context) ([]byte, error)
}

// Central owns the BLE Central-role lifecycle: scan, connect, send.
// Thread-safe via mu; operations are serialized to avoid BLE-stack state
// races (only one scan/connect/send at a time).
type Central struct {
	mu      sync.Mutex
	scanner CentralScanner
	state   string // "disconnected" | "connected"
}

func NewCentral(s CentralScanner) *Central {
	return &Central{scanner: s, state: "disconnected"}
}

// Scan discovers peripherals advertising serviceUUID. Respects ctx deadline.
func (c *Central) Scan(ctx context.Context) ([]Device, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.scanner.Scan(ctx, ServiceUUID)
}

// Connect establishes a GATT connection to the device id. Serialized.
func (c *Central) Connect(ctx context.Context, id string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if err := c.scanner.Connect(ctx, id); err != nil {
		return err
	}
	c.state = "connected"
	return nil
}

// Send writes payload to the Command characteristic and waits for a Notify
// response (echo). Returns the decoded echo payload. Requires active connection.
func (c *Central) Send(ctx context.Context, payload []byte) ([]byte, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.state != "connected" {
		return nil, ErrNotConnected
	}
	if err := c.scanner.WriteCommand(ctx, EncodeFrame(payload)); err != nil {
		return nil, err
	}
	raw, err := c.scanner.WaitNotify(ctx)
	if err != nil {
		return nil, err
	}
	frame, err := DecodeFrame(raw)
	if err != nil {
		return nil, err
	}
	return frame.Payload, nil
}

func (c *Central) Disconnect() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.scanner.Disconnect()
	c.state = "disconnected"
}

func (c *Central) State() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.state
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && go test ./internal/ble/ -v`
Expected: PASS — all Central tests + protocol tests green.

- [ ] **Step 5: Commit**

```bash
git add server/internal/ble/central.go server/internal/ble/central_test.go
git commit -m "feat(ble): add server Central scan/connect/send state machine"
```

---

## Task 2: server 端 tinygo Central adapter（bluetooth tag + stub）

**Files:**
- Create: `server/internal/ble/central_adapter.go`
- Create: `server/internal/ble/central_adapter_stub.go`
- Create: `server/internal/ble/central_adapter_test.go`
- Delete: `server/internal/ble/tinygo_adapter.go`、`tinygo_adapter_stub.go`、`tinygo_adapter_test.go`（上期 Peripheral adapter，不再需要）

**Interfaces:**
- Consumes: `ble.CentralScanner`（Task 1）、`ble.ServiceUUID`（上期 protocol.go）。
- Produces: `ble.NewCentralScanner() (CentralScanner, error)`（nil + err 表示硬件不可用，不 panic）。

- [ ] **Step 1: Delete the obsolete Peripheral adapters**

```bash
git rm server/internal/ble/tinygo_adapter.go server/internal/ble/tinygo_adapter_stub.go server/internal/ble/tinygo_adapter_test.go
```

- [ ] **Step 2: Write failing test for no-panic construction**

Create `server/internal/ble/central_adapter_test.go`:

```go
package ble

import "testing"

// NewCentralScanner must never panic when no Bluetooth hardware is present.
// Returns (nil, err) in that case; callers treat failure as non-fatal.
func TestNewCentralScannerDoesNotPanic(t *testing.T) {
	defer func() {
		if r := recover(); r != nil {
			t.Fatalf("NewCentralScanner panicked: %v", r)
		}
	}()
	_, _ = NewCentralScanner()
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd server && go test ./internal/ble/ -run TestNewCentralScanner -v`
Expected: FAIL — `NewCentralScanner` undefined.

- [ ] **Step 4: Write minimal implementation**

Create `server/internal/ble/central_adapter.go`:

```go
//go:build bluetooth

package ble

import (
	"context"
	"log/slog"
	"time"

	"tinygo.org/x/bluetooth"
)

type tinyGoCentralScanner struct {
	adapter  *bluetooth.Adapter
	device   *bluetooth.Device
	cmdChar  *bluetooth.Characteristic
	stateChar *bluetooth.Characteristic
}

func NewCentralScanner() (CentralScanner, error) {
	a := bluetooth.DefaultAdapter
	if err := a.Enable(); err != nil {
		slog.Warn("BLE adapter unavailable; BLE channel disabled", "error", err)
		return nil, err
	}
	return &tinyGoCentralScanner{adapter: a}, nil
}

func (t *tinyGoCentralScanner) Scan(ctx context.Context, serviceUUID string) ([]Device, error) {
	uuid, err := bluetooth.ParseUUID(serviceUUID)
	if err != nil {
		return nil, err
	}
	var found []Device
	scanCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()

	errCh := make(chan error, 1)
	go func() {
		errCh <- t.adapter.Scan(func(a *bluetooth.Adapter, d bluetooth.ScanResult) {
			for _, u := range d.ServiceUUIDs {
				if u == uuid {
					found = append(found, Device{
						ID:   d.Address.String(),
						Name: d.LocalName(),
						RSSI: int(d.RSSI),
					})
				}
			}
		})
	}()

	select {
	case <-scanCtx.Done():
		_ = t.adapter.StopScan()
		return dedup(found), nil
	case err := <-errCh:
		return found, err
	}
}

func (t *tinyGoCentralScanner) Connect(ctx context.Context, id string) error {
	addr, err := bluetooth.ParseMAC(id)
	if err != nil {
		return err
	}
	d, err := t.adapter.Connect(bluetooth.Address{MAC: addr}, bluetooth.ConnectionParams{})
	if err != nil {
		return err
	}
	t.device = &d
	svcs, err := d.DiscoverServices([]bluetooth.UUID{mustUUID(ServiceUUID)})
	if err != nil {
		return err
	}
	if len(svcs) == 0 {
		return errNoService
	}
	chars, err := svcs[0].DiscoverCharacteristics([]bluetooth.UUID{mustUUID(CommandCharUUID), mustUUID(StateCharUUID)})
	if err != nil {
		return err
	}
	for i := range chars {
		switch chars[i].UUID() {
		case mustUUID(CommandCharUUID):
			t.cmdChar = &chars[i]
		case mustUUID(StateCharUUID):
			t.stateChar = &chars[i]
		}
	}
	return nil
}

func (t *tinyGoCentralScanner) Disconnect() {
	if t.device != nil {
		_ = t.device.Disconnect()
		t.device = nil
	}
}

func (t *tinyGoCentralScanner) WriteCommand(ctx context.Context, payload []byte) error {
	if t.cmdChar == nil {
		return errNoCommandChar
	}
	_, err := t.cmdChar.WriteWithoutResponse(payload)
	return err
}

func (t *tinyGoCentralScanner) WaitNotify(ctx context.Context) ([]byte, error) {
	if t.stateChar == nil {
		return nil, errNoStateChar
	}
	notifyCh := make(chan []byte, 1)
	errCh := make(chan error, 1)
	handler := func(d []byte) {
		select {
		case notifyCh <- append([]byte(nil), d...):
		default:
		}
	}
	if err := t.stateChar.EnableNotifications(handler); err != nil {
		return nil, err
	}
	defer t.stateChar.DisableNotifications()

	select {
	case data := <-notifyCh:
		return data, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	case err := <-errCh:
		return nil, err
	}
}

// mustUUID panics on parse error; UUIDs are compile-time constants in this repo.
func mustUUID(s string) bluetooth.UUID {
	u, err := bluetooth.ParseUUID(s)
	if err != nil {
		panic(err)
	}
	return u
}

func dedup(devices []Device) []Device {
	seen := map[string]bool{}
	out := devices[:0]
	for _, d := range devices {
		if seen[d.ID] {
			continue
		}
		seen[d.ID] = true
		out = append(out, d)
	}
	return out
}

var (
	errNoService    = errors.New("ble: service not found on device")
	errNoCommandChar = errors.New("ble: command characteristic not found")
	errNoStateChar  = errors.New("ble: state characteristic not found")
)
```

Add `"errors"` to imports if not present (it isn't in the snippet above — add it). The full import block:

```go
import (
	"context"
	"errors"
	"log/slog"
	"time"

	"tinygo.org/x/bluetooth"
)
```

Create `server/internal/ble/central_adapter_stub.go`:

```go
//go:build !bluetooth

package ble

import (
	"context"
	"errors"
	"log/slog"
)

type stubCentralScanner struct{}

func NewCentralScanner() (CentralScanner, error) {
	slog.Info("BLE build not enabled (no -tags bluetooth); BLE channel disabled")
	return nil, errors.New("ble: built without bluetooth tag")
}

func (stubCentralScanner) Scan(context.Context, string) ([]Device, error) {
	return nil, errors.New("ble: unavailable")
}
func (stubCentralScanner) Connect(context.Context, string) error {
	return errors.New("ble: unavailable")
}
func (stubCentralScanner) Disconnect() {}
func (stubCentralScanner) WriteCommand(context.Context, []byte) error {
	return errors.New("ble: unavailable")
}
func (stubCentralScanner) WaitNotify(context.Context) ([]byte, error) {
	return nil, errors.New("ble: unavailable")
}
```

- [ ] **Step 5: Run test to verify it passes (stub path, default build)**

Run: `cd server && go test ./internal/ble/ -v`
Expected: PASS — `TestNewCentralScannerDoesNotPanic` green via stub; all Central + protocol tests still green.

- [ ] **Step 6: Verify both build tags compile**

Run: `cd server && go build ./... && go build -tags bluetooth ./...`
Expected: both succeed.

- [ ] **Step 7: Commit**

```bash
git add server/internal/ble/central_adapter.go server/internal/ble/central_adapter_stub.go server/internal/ble/central_adapter_test.go
git commit -m "feat(ble): add tinygo Central scanner adapter (bluetooth build tag) + stub"
```

---

## Task 3: 删除上期 server Peripheral 代码

**Files:**
- Delete: `server/internal/ble/peripheral.go`、`server/internal/ble/peripheral_test.go`

**Interfaces:** None (cleanup).

- [ ] **Step 1: Delete the Peripheral files**

```bash
git rm server/internal/ble/peripheral.go server/internal/ble/peripheral_test.go
```

- [ ] **Step 2: Update main.go — remove Peripheral startup, add Central startup**

Read `server/cmd/server/main.go` first. Locate the BLE startup block (added in last round's Task 3, around the mDNS block). It currently references `ble.NewTinyGoAdapter()` + `ble.NewPeripheral(...)` — both now deleted/renamed. Replace that entire BLE block with Central initialization:

```go
	// BLE control channel (experimental). Server is the BLE Central (scans +
	// connects Android peripherals). Non-fatal: if no Bluetooth adapter or
	// build lacks "bluetooth" tag, BLE is unavailable and server continues
	// with Wi-Fi/HTTP only (zero-regression).
	bleScanner, err := ble.NewCentralScanner()
	if err != nil {
		slog.Info("BLE channel disabled on server", "reason", err)
	} else {
		slog.Info("BLE Central ready", "service", ble.ServiceUUID)
	}
```

Note: `bleScanner` is wired into the handler in Task 4 via the Server struct. For now just initialize it (the variable will be used after Task 4; if Go complains about unused, assign to `_` temporarily and Task 4 will pick it up — but actually we'll pass it through config in Task 4, so leave it as a named var and Task 4 modifies server.go to inject it).

If the compiler complains about unused `bleScanner` at this step, change to `_ = bleScanner` and Task 4 will replace with real wiring.

- [ ] **Step 3: Verify build + test**

Run: `cd server && go build ./... && go test ./...`
Expected: build succeeds; all tests pass (Peripheral tests removed, Central tests remain).

- [ ] **Step 4: Commit**

```bash
git add server/cmd/server/main.go
git commit -m "refactor(ble): remove obsolete server Peripheral code (role reversed to Central)"
```

---

## Task 4: server HTTP handler `/api/v1/ble/scan|connect|send`

**Files:**
- Create: `server/internal/server/handler/ble.go`
- Test: `server/internal/server/handler/ble_test.go`
- Modify: `server/internal/server/handler/handler.go`（`Handler` struct 加 `BLE *ble.Central` 字段）
- Modify: `server/internal/server/server.go`（注册路由 + 注入 Central）
- Modify: `server/cmd/server/main.go`（把 bleScanner 通过 config/Server 传入 handler）

**Interfaces:**
- Consumes: `ble.Central`（Task 1）的方法 `Scan(ctx)`、`Connect(ctx, id)`、`Send(ctx, payload)`、`State()`。
- Produces: `Handler.ScanBLE`、`Handler.ConnectBLE`、`Handler.SendBLE`（Echo handlers）。

- [ ] **Step 1: Read existing handler.go to find the Handler struct + constructor pattern**

Run: Read `server/internal/server/handler/handler.go`. Identify the `Handler` struct fields and how it's constructed (likely `handler.NewHandler(...)` or constructed inline in server.go). Mirror that pattern for adding the `BLE` field.

- [ ] **Step 2: Write failing handler test**

Create `server/internal/server/handler/ble_test.go`:

```go
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

// fakeCentral satisfies *ble.Central's public surface for handler tests.
// We can't mock ble.Central directly (concrete struct); instead we inject a
// minimal interface via a handler-level seam (see ble.go Handler holds an
// interface, not the concrete *ble.Central).
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
	if err := h.SendBLE(c); err == nil {
		t.Fatal("expected 400 for oversize payload")
	}
	he, ok := err.(*echo.HTTPError)
	if !ok || he.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %v", err)
	}
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd server && go test ./internal/server/handler/ -run BLE -v`
Expected: FAIL — `Handler.BLECentral` undefined, handler methods undefined.

- [ ] **Step 4: Write minimal implementation**

Create `server/internal/server/handler/ble.go`:

```go
package handler

import (
	"context"
	"net/http"
	"time"

	"github.com/labstack/echo/v4"
	"github.com/localmediahub/server/internal/ble"
)

// BLECentralBackend is the handler-level seam over ble.Central, allowing
// handler tests to inject a fake. ble.Central satisfies this interface.
type BLECentralBackend interface {
	Scan(ctx context.Context) ([]ble.Device, error)
	Connect(ctx context.Context, id string) error
	Send(ctx context.Context, payload []byte) ([]byte, error)
	Disconnect()
	State() string
}

// ScanBLE: GET /api/v1/ble/scan
func (h *Handler) ScanBLE(c echo.Context) error {
	if h.BLECentral == nil {
		return c.JSON(http.StatusOK, map[string]any{"devices": []any{}, "error": "ble unavailable"})
	}
	ctx, cancel := context.WithTimeout(c.Request().Context(), 4*time.Second)
	defer cancel()
	devices, err := h.BLECentral.Scan(ctx)
	if err != nil {
		return c.JSON(http.StatusOK, map[string]any{"devices": []any{}, "error": err.Error()})
	}
	return c.JSON(http.StatusOK, map[string]any{"devices": devices})
}

// ConnectBLE: POST /api/v1/ble/connect  {"id":"..."}
func (h *Handler) ConnectBLE(c echo.Context) error {
	if h.BLECentral == nil {
		return c.JSON(http.StatusOK, map[string]any{"connected": false, "error": "ble unavailable"})
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

// SendBLE: POST /api/v1/ble/send  {"payload":"..."}
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
	if len(req.Payload) > 244 {
		return echo.NewHTTPError(http.StatusBadRequest, "payload exceeds 244 bytes")
	}
	ctx, cancel := context.WithTimeout(c.Request().Context(), 6*time.Second)
	defer cancel()
	echoPayload, err := h.BLECentral.Send(ctx, []byte(req.Payload))
	if err != nil {
		return c.JSON(http.StatusOK, map[string]any{"echo": nil, "error": err.Error()})
	}
	return c.JSON(http.StatusOK, map[string]any{"echo": string(echoPayload)})
}
```

Modify `server/internal/server/handler/handler.go`: add field `BLECentral BLECentralBackend` to the `Handler` struct. Read the file first to find the struct definition and add the field alongside existing ones (e.g., after the last service field). Do not change the constructor signature yet — Task 4 Step 5 sets the field via direct struct initialization in server.go (mirror how other handlers like BookService are wired, see `server.go:101` comment about BookService wiring).

- [ ] **Step 5: Register routes + wire Central into Handler**

Modify `server/internal/server/server.go`:

1. In `New()`, after the BLE Central is created (you need to construct it here or receive it — simplest: construct inside `New()` with non-fatal error), add:
```go
	bleCentral, bleErr := bleNewCentral()  // see helper below
	if bleErr != nil {
		slog.Warn("BLE Central disabled", "error", bleErr)
	}
```
Add import `"github.com/localmediahub/server/internal/ble"` to server.go. Add a small helper at file scope to avoid importing ble into server.go's main path ambiguously — actually just call `ble.NewCentral` directly if scanner construction is non-fatal:

```go
	bleScanner, bleErr := ble.NewCentralScanner()
	var bleCentral *ble.Central
	if bleErr == nil {
		bleCentral = ble.NewCentral(bleScanner)
	}
```

2. Where the `Handler` struct is constructed (find it — likely `handler.Handler{...}` in `New()`), add field `BLECentral: bleCentral`. Note: `*ble.Central` may be nil; the handler treats `nil` as "unavailable" (see Step 4 nil checks). But `Handler.BLECentral` is typed `BLECentralBackend` (interface) — a nil `*ble.Central` is a non-nil interface wrapping nil, which would fail the `h.BLECentral == nil` check. To handle this correctly: set the field only when non-nil:
```go
	var h handler.Handler
	// ... existing field assignments ...
	if bleCentral != nil {
		h.BLECentral = bleCentral
	}
```
Or if the Handler is constructed as a struct literal, conditionally assign after.

3. Register routes in the `api` group (after existing `api.GET/POST` lines, ~line 228):
```go
	api.GET("/ble/scan", h.ScanBLE)
	api.POST("/ble/connect", h.ConnectBLE)
	api.POST("/ble/send", h.SendBLE)
```

- [ ] **Step 6: Update main.go — remove the now-duplicate Central init**

In Task 3 Step 2 you added Central init to main.go. But Task 4 Step 5 moved it into `server.New()`. Remove the duplicate from main.go (the `bleScanner, err := ble.NewCentralScanner()` block) — server.go now owns it. main.go should have NO BLE-specific code after this task.

- [ ] **Step 7: Run handler test + full build**

Run: `cd server && go test ./internal/server/handler/ -run BLE -v && go build ./... && go test ./...`
Expected: handler BLE tests pass; full build + test green.

- [ ] **Step 8: Commit**

```bash
git add server/internal/server/handler/ble.go server/internal/server/handler/ble_test.go server/internal/server/handler/handler.go server/internal/server/server.go server/cmd/server/main.go
git commit -m "feat(ble): add /api/v1/ble/scan|connect|send HTTP handlers"
```

---

## Task 5: Android 状态机改名 SCANNING → ADVERTISING

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ble/BleConnectionStateMachine.kt`
- Modify: `android/app/src/test/java/com/juziss/localmediahub/ble/BleConnectionStateMachineTest.kt`

**Interfaces:**
- Produces: enum `BleConnState` with `ADVERTISING` (replaces `SCANNING`). `onStartScan()` method renamed to `onStartAdvertising()`. `CONNECTING` enum value kept but not produced this round.

- [ ] **Step 1: Read current state machine**

Read `android/app/src/main/java/com/juziss/localmediahub/ble/BleConnectionStateMachine.kt`.

- [ ] **Step 2: Update test to use ADVERTISING**

In `BleConnectionStateMachineTest.kt`, replace `BleConnState.SCANNING` with `BleConnState.ADVERTISING` and `onStartScan()` with `onStartAdvertising()` (3 occurrences in the existing 5 tests). The test asserting `idle_toScanning_toConnecting_toConnected` becomes `idle_toAdvertising_toConnecting_toConnected`.

- [ ] **Step 3: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ble.BleConnectionStateMachineTest"`
Expected: FAIL — unresolved `ADVERTISING` / `onStartAdvertising`.

- [ ] **Step 4: Update state machine**

In `BleConnectionStateMachine.kt`:
- Rename enum value `SCANNING` → `ADVERTISING`.
- Rename method `onStartScan()` → `onStartAdvertising()`.
- Keep `CONNECTING` enum value (unused this round, retained for future).
- Update the method doc comment to reflect Peripheral semantics.

- [ ] **Step 5: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ble.BleConnectionStateMachineTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ble/BleConnectionStateMachine.kt android/app/src/test/java/com/juziss/localmediahub/ble/BleConnectionStateMachineTest.kt
git commit -m "refactor(ble): rename SCANNING→ADVERTISING (Android is Peripheral now)"
```

---

## Task 6: Android PeripheralManager 接口 + 真实 BluetoothGattServer 实现

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/ble/BlePeripheralManager.kt`（接口）
- Create: `android/app/src/main/java/com/juziss/localmediahub/ble/AndroidBlePeripheralManager.kt`（真实实现）
- Delete: `android/app/src/main/java/com/juziss/localmediahub/ble/BleCentralManager.kt`
- Delete: `android/app/src/main/java/com/juziss/localmediahub/ble/AndroidBleCentralManager.kt`

**Interfaces:**
- Produces: `interface BlePeripheralManager` with `fun startAdvertising()`、`fun stopAdvertising()`、`fun setOnPayloadReceived(cb: (ByteArray) -> Unit)`、`fun notifyPayload(payload: ByteArray): Boolean`。
- 真实实现 `AndroidBlePeripheralManager` 用 `BluetoothGattServer` + `BluetoothManager.openGattServer` + `AdvertiseSettings/AdvertiseData`。

- [ ] **Step 1: Delete obsolete Central manager files**

```bash
git rm android/app/src/main/java/com/juziss/localmediahub/ble/BleCentralManager.kt android/app/src/main/java/com/juziss/localmediahub/ble/AndroidBleCentralManager.kt
```

- [ ] **Step 2: Create interface**

Create `android/app/src/main/java/com/juziss/localmediahub/ble/BlePeripheralManager.kt`:

```kotlin
package com.juziss.localmediahub.ble

/**
 * Abstraction over Android's BluetoothLeAdvertiser + BluetoothGattServer so
 * BleController is unit-testable without a real Bluetooth stack. The
 * production implementation [AndroidBlePeripheralManager] wires the system APIs.
 */
interface BlePeripheralManager {
    /** Begin advertising SERVICE_UUID + start the GATT server. */
    fun startAdvertising()
    /** Stop advertising + close the GATT server. */
    fun stopAdvertising()
    /** Register callback invoked when the Central writes to Command characteristic. */
    fun setOnPayloadReceived(cb: (ByteArray) -> Unit)
    /** Send payload via the State characteristic (Notify). Returns false if no subscriber. */
    fun notifyPayload(payload: ByteArray): Boolean
    /** True iff a Bluetooth adapter exists and is powered on. */
    fun isAdapterUsable(): Boolean
}
```

- [ ] **Step 3: Create real implementation**

Create `android/app/src/main/java/com/juziss/localmediahub/ble/AndroidBlePeripheralManager.kt`:

```kotlin
package com.juziss.localmediahub.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject

/**
 * Production [BlePeripheralManager] backed by Android's BluetoothLeAdvertiser
 * + BluetoothGattServer.
 *
 * Lifecycle:
 *   startAdvertising() → opens GATT server with Command (Write) + State (Notify)
 *                        characteristics, begins advertising SERVICE_UUID.
 *   stopAdvertising()  → stops advertising + closes server.
 *
 * Central (PC) writes Command → onCharacteristicWriteRequest → decode frame →
 * onPayloadReceived callback. Central subscribes to State (CCCD) → we hold the
 * subscriber device; notifyPayload writes/notifications go there.
 */
class AndroidBlePeripheralManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : BlePeripheralManager {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiserCallback: AdvertiseCallback? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var stateChar: BluetoothGattCharacteristic? = null
    private var subscriberDevice: BluetoothDevice? = null
    private var onPayloadReceived: ((ByteArray) -> Unit)? = null

    override fun isAdapterUsable(): Boolean = adapter?.isEnabled == true

    override fun setOnPayloadReceived(cb: (ByteArray) -> Unit) {
        onPayloadReceived = cb
    }

    override fun startAdvertising() {
        val mgr = bluetoothManager ?: return
        val ad = adapter ?: return
        if (gattServer != null) return // already started

        val service = android.bluetooth.BluetoothGattService(
            UUID.fromString(BleProtocol.SERVICE_UUID),
            android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        val cmd = BluetoothGattCharacteristic(
            UUID.fromString(BleProtocol.COMMAND_CHAR_UUID),
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val state = BluetoothGattCharacteristic(
            UUID.fromString(BleProtocol.STATE_CHAR_UUID),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        // CCCD required for Notify subscribers.
        val cccd = BluetoothGattDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        state.addDescriptor(cccd)
        service.addCharacteristic(cmd)
        service.addCharacteristic(state)
        commandChar = cmd
        stateChar = state

        gattServer = mgr.openGattServer(context, gattCallback)
        gattServer?.addService(service)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(UUID.fromString(BleProtocol.SERVICE_UUID)))
            .build()
        val cb = object : AdvertiseCallback() {}
        advertiserCallback = cb
        ad.bluetoothLeAdvertiser?.startAdvertising(settings, data, cb)
    }

    override fun stopAdvertising() {
        adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiserCallback)
        advertiserCallback = null
        gattServer?.close()
        gattServer = null
        subscriberDevice = null
        commandChar = null
        stateChar = null
    }

    override fun notifyPayload(payload: ByteArray): Boolean {
        val server = gattServer ?: return false
        val dev = subscriberDevice ?: return false
        val state = stateChar ?: return false
        state.value = payload
        return server.notify(dev, state)
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                subscriberDevice = device
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscriberDevice = null
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val server = gattServer ?: return
            if (responseNeeded) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            }
            if (characteristic.uuid == UUID.fromString(BleProtocol.COMMAND_CHAR_UUID)) {
                val frame = BleProtocol.decodeFrame(value)
                if (frame != null) {
                    onPayloadReceived?.invoke(frame.payload)
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val server = gattServer ?: return
            if (responseNeeded) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            }
            // CCCD subscription to State characteristic enables notify target.
            subscriberDevice = device
        }
    }
}
```

- [ ] **Step 4: Update di/BleModule.kt to provide the new PeripheralManager**

Read `android/app/src/main/java/com/juziss/localmediahub/di/BleModule.kt`. Replace the `AndroidBleCentralManager` provider with `AndroidBlePeripheralManager`, and the `BleCentralManager` interface binding with `BlePeripheralManager`. The `provideBleController` signature changes (consumes `BlePeripheralManager` instead of `AndroidBleCentralManager`) — but that's Task 7's job. For Task 6, just provide the new manager type and comment out / remove the old `provideCentralManager*` functions. Leave `provideBleController` broken (it references the deleted `AndroidBleCentralManager`) — Task 7 will fix it. **This means Task 6 does NOT require assembleDebug to pass**; only that the new files compile in isolation. The build will be fixed at Task 7.

Actually, to keep the build green between tasks, the cleaner approach: do Task 6 and Task 7 as a single commit. But the skill wants bite-sized tasks. Compromise: Task 6 creates the new manager + updates the module to provide it AND temporarily stubs `provideBleController` to compile (e.g., comment out the broken body with a TODO), then Task 7 does the real controller refactor. To avoid this mess, **merge Task 6 + Task 7 into one task** (Task 6 below covers both manager + controller refactor together).

**REVISED: Merge — this task now covers PeripheralManager + BleController refactor + di/BleModule update, as one atomic unit.** Adjusted below.

- [ ] **Step 5: (Merged — see Task 7 below; do not commit yet)**

Skip the commit here; proceed to Task 7 which completes the refactor and commits the manager + controller + module together.

> **Plan note:** Tasks 6 and 7 are tightly coupled (the controller and module both reference the manager type). Implementing one without the other leaves the build broken. They are presented separately for clarity but MUST be implemented as a single unit and committed together. Task 7 ends with the combined commit.

---

## Task 7: Android BleController 改造（用 PeripheralManager + HTTP 协调驱动状态）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ble/BleController.kt`
- Modify: `android/app/src/test/java/com/juziss/localmediahub/ble/BleControllerTest.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/di/BleModule.kt`
- (Carries over Task 6's PeripheralManager files)

**Interfaces:**
- Consumes: `BlePeripheralManager`（Task 6）、`BleConnectionStateMachine`（Task 5，含 ADVERTISING）。
- Produces: 改造后的 `BleController`：开关 → 广播；提供 `markConnected()` / `markDisconnected()` 供 HTTP 协调结果驱动状态；`send` 不再直接走 BLE（改由 VM 调 BleApi）。

- [ ] **Step 1: Update BleController test for Peripheral semantics**

Rewrite `android/app/src/test/java/com/juziss/localmediahub/ble/BleControllerTest.kt`:

```kotlin
package com.juziss.localmediahub.ble

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class BleControllerTest {

    private class FakePeripheralManager : BlePeripheralManager {
        var advertising = false
        var received: ByteArray? = null
        var notifyResult = true
        private var cb: ((ByteArray) -> Unit)? = null
        override fun startAdvertising() { advertising = true }
        override fun stopAdvertising() { advertising = false }
        override fun setOnPayloadReceived(cb: (ByteArray) -> Unit) { this.cb = cb }
        override fun notifyPayload(payload: ByteArray): Boolean {
            received = payload
            return notifyResult
        }
        override fun isAdapterUsable(): Boolean = true
        // Test hook to simulate Central write.
        fun simulateWrite(payload: ByteArray) { cb?.invoke(payload) }
    }

    @Test
    fun disabledByDefault_doesNotAdvertise() {
        val enabledFlow = MutableStateFlow(false)
        val mgr = FakePeripheralManager()
        val controller = BleController(
            peripheralManager = mgr,
            bleEnabledFlow = enabledFlow,
            bleHardwareAvailable = { false },
            saveBleEnabled = {},
        )
        controller.evaluateAvailability(enabled = false)
        assertEquals(BleConnState.DISABLED, controller.connectionState.value)
        assert(!mgr.advertising)
    }

    @Test
    fun enabledWithHardware_startsAdvertising() {
        val enabledFlow = MutableStateFlow(true)
        val mgr = FakePeripheralManager()
        val controller = BleController(
            peripheralManager = mgr,
            bleEnabledFlow = enabledFlow,
            bleHardwareAvailable = { true },
            saveBleEnabled = {},
        )
        controller.evaluateAvailability(enabled = true)
        assertEquals(BleConnState.ADVERTISING, controller.connectionState.value)
        assert(mgr.advertising)
    }

    @Test
    fun markConnected_setsState() {
        val enabledFlow = MutableStateFlow(true)
        val controller = BleController(
            peripheralManager = FakePeripheralManager(),
            bleEnabledFlow = enabledFlow,
            bleHardwareAvailable = { true },
            saveBleEnabled = {},
        )
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()
        assertEquals(BleConnState.CONNECTED, controller.connectionState.value)
    }

    @Test
    fun markDisconnected_returnsToAdvertising() {
        val enabledFlow = MutableStateFlow(true)
        val mgr = FakePeripheralManager()
        val controller = BleController(
            peripheralManager = mgr,
            bleEnabledFlow = enabledFlow,
            bleHardwareAvailable = { true },
            saveBleEnabled = {},
        )
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()
        controller.markDisconnected()
        assertEquals(BleConnState.ADVERTISING, controller.connectionState.value)
    }

    @Test
    fun receivedPayload_notifiesBackViaPeripheralManager() {
        val enabledFlow = MutableStateFlow(true)
        val mgr = FakePeripheralManager()
        val controller = BleController(
            peripheralManager = mgr,
            bleEnabledFlow = enabledFlow,
            bleHardwareAvailable = { true },
            saveBleEnabled = {},
        )
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()
        // Simulate the Central writing "ping"; controller should echo "pong" via notify.
        mgr.simulateWrite("ping".toByteArray())
        // Verify notify was called (echo logic lives in controller).
        val sent = mgr.received
        assert(sent != null)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ble.BleControllerTest"`
Expected: FAIL — `BleController` constructor signature changed, `markConnected`/`markDisconnected` undefined.

- [ ] **Step 3: Rewrite BleController**

Replace `android/app/src/main/java/com/juziss/localmediahub/ble/BleController.kt`:

```kotlin
package com.juziss.localmediahub.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hilt singleton aggregating BLE policy for the Peripheral role.
 *
 * When enabled + hardware available: starts advertising (state ADVERTISING).
 * HTTP coordination (Task 8 BleApi) drives markConnected/markDisconnected
 * based on the Central's connect/disconnect responses.
 *
 * When the Central writes to the Command characteristic, [BlePeripheralManager]
 * invokes the registered callback with the decoded payload; this controller
 * echoes it back via notifyPayload (encode frame).
 *
 * Zero-regression: when disabled or hardware unavailable, state is DISABLED
 * and no advertising occurs; Wi-Fi/HTTP behavior is entirely unaffected.
 */
@Singleton
class BleController @Inject constructor(
    private val peripheralManager: BlePeripheralManager,
    @Suppress("unused") private val bleEnabledFlow: Flow<Boolean>,
    private val bleHardwareAvailable: () -> Boolean,
    private val saveBleEnabled: suspend (Boolean) -> Unit,
) {
    private val machine = BleConnectionStateMachine()
    val connectionState: StateFlow<BleConnState> = machine.state

    init {
        peripheralManager.setOnPayloadReceived { payload ->
            // Echo: re-encode and notify back. (Minimal connectivity verification.)
            peripheralManager.notifyPayload(BleProtocol.encodeFrame(payload))
        }
    }

    fun evaluateAvailability(enabled: Boolean) {
        if (!enabled || !bleHardwareAvailable()) {
            machine.onBleDisabled()
            peripheralManager.stopAdvertising()
            return
        }
        machine.onStartAdvertising()
        peripheralManager.startAdvertising()
    }

    /** Called by BleApi when the Central reports a successful /connect. */
    fun markConnected() {
        machine.onConnected()
    }

    /** Called by BleApi when the Central reports disconnection or connect failure. */
    fun markDisconnected() {
        machine.onDisconnected()
    }

    suspend fun setEnabled(enabled: Boolean) {
        saveBleEnabled(enabled)
        evaluateAvailability(enabled = enabled)
    }
}
```

- [ ] **Step 4: Update di/BleModule.kt**

Replace the old Central-based providers. Read the current file first, then rewrite to provide the PeripheralManager:

```kotlin
package com.juziss.localmediahub.di

import android.bluetooth.BluetoothManager
import android.content.Context
import com.juziss.localmediahub.ble.AndroidBlePeripheralManager
import com.juziss.localmediahub.ble.BleController
import com.juziss.localmediahub.ble.BlePeripheralManager
import com.juziss.localmediahub.data.ServerConfigStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BleModule {

    @Provides
    @Singleton
    fun providePeripheralManager(
        @ApplicationContext context: Context,
    ): AndroidBlePeripheralManager = AndroidBlePeripheralManager(context)

    @Provides
    fun providePeripheralManagerInterface(
        impl: AndroidBlePeripheralManager,
    ): BlePeripheralManager = impl

    @Provides
    @Singleton
    fun provideBleController(
        peripheralManager: AndroidBlePeripheralManager,
        store: ServerConfigStore,
        @ApplicationContext context: Context,
        @ApplicationScope appScope: CoroutineScope,
    ): BleController {
        val hardwareAvailable: () -> Boolean = {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            mgr?.adapter?.isEnabled == true
        }
        val controller = BleController(
            peripheralManager = peripheralManager,
            bleEnabledFlow = store.bleEnabled,
            bleHardwareAvailable = hardwareAvailable,
            saveBleEnabled = { enabled -> store.saveBleEnabled(enabled) },
        )
        appScope.launch {
            store.bleEnabled.collect { enabled -> controller.evaluateAvailability(enabled = enabled) }
        }
        return controller
    }
}
```

Add import `kotlinx.coroutines.launch`. The `@ApplicationScope` qualifier is in the same `di` package (`CoroutineScopesModule.kt`), no import needed.

- [ ] **Step 5: Run controller test + assembleDebug**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ble.*" :app:assembleDebug`
Expected: all BLE tests pass; debug build succeeds.

- [ ] **Step 6: Commit (combined Task 6 + Task 7)**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ble/BlePeripheralManager.kt android/app/src/main/java/com/juziss/localmediahub/ble/AndroidBlePeripheralManager.kt android/app/src/main/java/com/juziss/localmediahub/ble/BleController.kt android/app/src/test/java/com/juziss/localmediahub/ble/BleControllerTest.kt android/app/src/main/java/com/juziss/localmediahub/di/BleModule.kt
git commit -m "feat(ble): Android Peripheral manager + controller refactor (role reversed)"
```

---

## Task 8: Android BleApi（HTTP 调 /api/v1/ble/*）

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/data/BleApi.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/data/BleApiTest.kt`

**Interfaces:**
- Consumes: `ServerConfig`（baseUrl + token，现有 singleton）、OkHttp（现有）。
- Produces: `class BleApi @Inject constructor(...)` with `suspend fun scan(): NetworkResult<List<BleDevice>>`、`suspend fun connect(id: String): NetworkResult<Boolean>`、`suspend fun send(payload: String): NetworkResult<String?>`；`data class BleDevice(val id, val name, val rssi: Int)`。

- [ ] **Step 1: Read MediaRepository to mirror httpGet/httpPost patterns**

Read `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt:54-150` (the private `httpGet`/`httpPost`/`httpEmpty` helpers). BleApi will reuse the same OkHttp + Gson + auth pattern. Decide: either (a) extract a shared httpClient + auth into a reusable base, or (b) duplicate the minimal helper. Prefer (a) only if MediaRepository already exposes it; otherwise (b) is acceptable for this one-file addition (DRY judgment — MediaRepository's helpers are private instance methods, not easily reused without refactor; duplicating ~20 lines of OkHttp call in BleApi is simpler than a cross-cutting refactor).

- [ ] **Step 2: Write failing test with MockWebServer**

Create `android/app/src/test/java/com/juziss/localmediahub/data/BleApiTest.kt`:

```kotlin
package com.juziss.localmediahub.data

import com.juziss.localmediahub.network.ServerConfig
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BleApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: BleApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val serverConfig = ServerConfig().apply {
            setBaseUrl(server.url("/").toString().trimEnd('/'))
            setToken("test-token")
        }
        api = BleApi(OkHttpClient(), serverConfig)
    }

    @After
    fun tearDown() { server.shutdown() }

    @Test
    fun scan_parsesDevices() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"devices":[{"id":"AA:BB","name":"Pixel","rssi":-45}]}"""))
        val result = api.scan()
        assertTrue(result is NetworkResult.Success)
        val devices = (result as NetworkResult.Success).value
        assertEquals(1, devices.size)
        assertEquals("AA:BB", devices[0].id)
    }

    @Test
    fun scan_emptyListOnUnavailable() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"devices":[],"error":"ble unavailable"}"""))
        val result = api.scan()
        assertTrue(result is NetworkResult.Success)
        assertEquals(0, (result as NetworkResult.Success).value.size)
    }

    @Test
    fun connect_returnsTrue() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"connected":true}"""))
        val result = api.connect("AA:BB")
        assertTrue(result is NetworkResult.Success)
        assertTrue((result as NetworkResult.Success).value)
    }

    @Test
    fun send_returnsEcho() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"echo":"pong"}"""))
        val result = api.send("ping")
        assertTrue(result is NetworkResult.Success)
        assertEquals("pong", (result as NetworkResult.Success).value)
    }

    @Test
    fun send_nullEchoOnError() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"echo":null,"error":"timeout"}"""))
        val result = api.send("ping")
        assertTrue(result is NetworkResult.Success)
        assertNull((result as NetworkResult.Success).value)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.data.BleApiTest"`
Expected: FAIL — `BleApi` undefined.

- [ ] **Step 4: Implement BleApi**

Create `android/app/src/main/java/com/juziss/localmediahub/data/BleApi.kt`:

```kotlin
package com.juziss.localmediahub.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.juziss.localmediahub.network.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

data class BleDevice(val id: String, val name: String, val rssi: Int)

@Singleton
class BleApi @Inject constructor(
    private val client: OkHttpClient,
    private val serverConfig: ServerConfig,
) {
    private val gson = Gson()
    private val json = "application/json".toMediaType()

    suspend fun scan(): NetworkResult<List<BleDevice>> = withContext(Dispatchers.IO) {
        val url = "${serverConfig.getBaseUrl()}/api/v1/ble/scan"
        val req = Request.Builder().url(url).header("Authorization", "Bearer ${serverConfig.getTokenSnapshot()}").get().build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext NetworkResult.Error("HTTP ${resp.code}")
                val body = resp.body?.string() ?: return@withContext NetworkResult.Error("empty body")
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val map: Map<String, Any> = gson.fromJson(body, type)
                @Suppress("UNCHECKED_CAST")
                val devices = (map["devices"] as? List<Any>).orEmpty()
                val result = devices.mapNotNull {
                    @Suppress("UNCHECKED_CAST")
                    val d = it as? Map<String, Any> ?: return@mapNotNull null
                    BleDevice(
                        id = d["id"] as? String ?: return@mapNotNull null,
                        name = d["name"] as? String ?: "",
                        rssi = (d["rssi"] as? Number)?.toInt() ?: 0,
                    )
                }
                NetworkResult.Success(result)
            }
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "scan failed")
        }
    }

    suspend fun connect(id: String): NetworkResult<Boolean> = withContext(Dispatchers.IO) {
        val url = "${serverConfig.getBaseUrl()}/api/v1/ble/connect"
        val body = """{"id":"$id"}""".toRequestBody(json)
        val req = Request.Builder().url(url).header("Authorization", "Bearer ${serverConfig.getTokenSnapshot()}").post(body).build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext NetworkResult.Error("HTTP ${resp.code}")
                val text = resp.body?.string() ?: return@withContext NetworkResult.Error("empty body")
                val connected = text.contains("\"connected\":true")
                NetworkResult.Success(connected)
            }
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "connect failed")
        }
    }

    suspend fun send(payload: String): NetworkResult<String?> = withContext(Dispatchers.IO) {
        val url = "${serverConfig.getBaseUrl()}/api/v1/ble/send"
        val body = """{"payload":"${payload.replace("\"", "\\\"")}"}""".toRequestBody(json)
        val req = Request.Builder().url(url).header("Authorization", "Bearer ${serverConfig.getTokenSnapshot()}").post(body).build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext NetworkResult.Error("HTTP ${resp.code}")
                val text = resp.body?.string() ?: return@withContext NetworkResult.Error("empty body")
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val map: Map<String, Any> = gson.fromJson(text, type)
                NetworkResult.Success(map["echo"] as? String)
            }
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "send failed")
        }
    }
}
```

Verify `NetworkResult` is the existing sealed type in `network/NetworkResult.kt` — Read that file first to confirm the exact variant names (`Success`/`Error`) and constructor signatures; adjust the references above if they differ.

- [ ] **Step 5: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.data.BleApiTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/data/BleApi.kt android/app/src/test/java/com/juziss/localmediahub/data/BleApiTest.kt
git commit -m "feat(ble): Android BleApi for /api/v1/ble/scan|connect|send"
```

---

## Task 9: Android BleSettingsViewModel + ConnectionScreen UI（扫描列表 + 连接 + 发送测试）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModel.kt`
- Create: `android/app/src/test/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModelTest.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/ConnectionScreen.kt`

**Interfaces:**
- Consumes: `BleController`（Task 7）、`BleApi`（Task 8）、`ServerConfigStore.bleEnabled`（上期）。
- Produces: VM 暴露 `devices: StateFlow<List<BleDevice>>`、`echoResult: StateFlow<String?>`、`fun scan()`、`fun connect(device)`、`fun sendTest()`；UI 加按钮 + 列表 + 回显。

- [ ] **Step 1: Read current BleSettingsViewModel**

Read `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModel.kt` to see its current shape (from last round).

- [ ] **Step 2: Write failing VM test**

Create `android/app/src/test/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModelTest.kt`:

```kotlin
package com.juziss.localmediahub.viewmodel

import com.juziss.localmediahub.ble.BleConnState
import com.juziss.localmediahub.ble.BleController
import com.juziss.localmediahub.data.BleApi
import com.juziss.localmediahub.data.BleDevice
import com.juziss.localmediahub.data.NetworkResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class BleSettingsViewModelTest {

    private val connState = MutableStateFlow(BleConnState.DISABLED)

    private class FakeController : BleController {
        override val connectionState get() = connState
        var connectedMarked = false
        var disconnectedMarked = false
        override fun markConnected() { connectedMarked = true }
        override fun markDisconnected() { disconnectedMarked = true }
        override suspend fun setEnabled(enabled: Boolean) {}
        override fun evaluateAvailability(enabled: Boolean) {}
    }

    private class FakeBleApi : BleApi {
        var scanResult: NetworkResult<List<BleDevice>> = NetworkResult.Success(emptyList())
        var connectResult: NetworkResult<Boolean> = NetworkResult.Success(true)
        var sendResult: NetworkResult<String?> = NetworkResult.Success("pong")
        var lastConnectedId: String? = null
        var lastSentPayload: String? = null
        override suspend fun scan() = scanResult
        override suspend fun connect(id: String): NetworkResult<Boolean> {
            lastConnectedId = id
            return connectResult
        }
        override suspend fun send(payload: String): NetworkResult<String?> {
            lastSentPayload = payload
            return sendResult
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun scan_populatesDevices() = runTest {
        val api = FakeBleApi().apply {
            scanResult = NetworkResult.Success(listOf(BleDevice("AA:BB", "Pixel", -45)))
        }
        val vm = BleSettingsViewModel(FakeController(), api)
        vm.scan()
        assertEquals(1, vm.devices.value.size)
        assertEquals("AA:BB", vm.devices.value[0].id)
    }

    @Test
    fun connect_marksControllerConnected() = runTest {
        val ctrl = FakeController()
        val api = FakeBleApi()
        val vm = BleSettingsViewModel(ctrl, api)
        vm.connect(BleDevice("AA:BB", "Pixel", -45))
        assert(ctrl.connectedMarked)
    }

    @Test
    fun sendTest_updatesEchoResult() = runTest {
        val vm = BleSettingsViewModel(FakeController(), FakeBleApi())
        vm.sendTest()
        assertEquals("pong", vm.echoResult.value)
    }
}
```

Note: this test requires `BleController` and `BleApi` to be interfaces (or open classes) so fakes can implement them. Currently `BleController` is a concrete `@Singleton class` (Task 7) and `BleApi` is a concrete class (Task 8). To make them fakeable, either:
- (Preferred) Extract `interface BleController` / `interface BleApi` and have the concrete classes implement them, OR
- Use `mockk` (already a test dep, line 395 of build.gradle.kts) to mock the concrete classes.

Use **mockk** to avoid inflating the type hierarchy. Rewrite the test using mockk:

```kotlin
package com.juziss.localmediahub.viewmodel

import com.juziss.localmediahub.ble.BleConnState
import com.juziss.localmediahub.ble.BleController
import com.juziss.localmediahub.data.BleApi
import com.juziss.localmediahub.data.BleDevice
import com.juziss.localmediahub.data.NetworkResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class BleSettingsViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun scan_populatesDevices() = runTest {
        val ctrl = mockk<BleController>(relaxed = true)
        every { ctrl.connectionState } returns MutableStateFlow(BleConnState.DISABLED)
        val api = mockk<BleApi>()
        coEvery { api.scan() } returns NetworkResult.Success(listOf(BleDevice("AA:BB", "Pixel", -45)))
        val vm = BleSettingsViewModel(ctrl, api)
        vm.scan()
        assertEquals(1, vm.devices.value.size)
    }

    @Test
    fun connect_marksControllerConnected() = runTest {
        val ctrl = mockk<BleController>(relaxed = true)
        every { ctrl.connectionState } returns MutableStateFlow(BleConnState.DISABLED)
        val api = mockk<BleApi>()
        coEvery { api.connect(any()) } returns NetworkResult.Success(true)
        val vm = BleSettingsViewModel(ctrl, api)
        vm.connect(BleDevice("AA:BB", "Pixel", -45))
        verify { ctrl.markConnected() }
    }

    @Test
    fun sendTest_updatesEchoResult() = runTest {
        val ctrl = mockk<BleController>(relaxed = true)
        every { ctrl.connectionState } returns MutableStateFlow(BleConnState.DISABLED)
        val api = mockk<BleApi>()
        coEvery { api.send(any()) } returns NetworkResult.Success("pong")
        val vm = BleSettingsViewModel(ctrl, api)
        vm.sendTest()
        assertEquals("pong", vm.echoResult.value)
    }
}
```

Confirm `NetworkResult` variant names by reading `network/NetworkResult.kt` first; adjust if it uses different names (e.g., `Success(data)` vs `Success(value)`).

- [ ] **Step 3: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.viewmodel.BleSettingsViewModelTest"`
Expected: FAIL — `BleSettingsViewModel(ctrl, api)` constructor / `devices`/`echoResult`/`scan`/`connect`/`sendTest` undefined.

- [ ] **Step 4: Extend BleSettingsViewModel**

Read the current `BleSettingsViewModel.kt`, then add:
- Inject `BleApi` (constructor param alongside existing `BleController` + `ServerConfigStore`).
- `private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())` + `val devices: StateFlow<List<BleDevice>>`.
- `private val _echoResult = MutableStateFlow<String?>(null)` + `val echoResult: StateFlow<String?>`.
- `private val _scanning = MutableStateFlow(false)` + `val scanning: StateFlow<Boolean>` (UI loading state).
- `fun scan()`: viewModelScope.launch { _scanning=true; when val r = api.scan() { Success -> _devices.value = r; else -> _devices.value = emptyList() }; _scanning=false }.
- `fun connect(device: BleDevice)`: launch { when val r = api.connect(device.id) { Success(true) -> controller.markConnected(); else -> controller.markDisconnected() } }.
- `fun sendTest()`: launch { when val r = api.send("ping") { Success -> _echoResult.value = r; else -> _echoResult.value = "发送失败" } }.

Mirror the existing `stateIn(WhileSubscribed(5000))` pattern from `ConnectionViewModel` for any exposed StateFlows that need to be hot.

- [ ] **Step 5: Update ConnectionScreen UI**

Read `ConnectionScreen.kt` (current BLE toggle area from last round). Below the existing BLE toggle + status text, add:
- A "扫描设备" `Button` (enabled when `bleEnabled && connectionState != CONNECTED`), calls `bleViewModel.scan()`.
- A loading indicator when `bleViewModel.scanning` is true.
- A `LazyColumn` of `bleViewModel.devices` — each row shows `device.name (device.id, rssi)` and is clickable → `bleViewModel.connect(device)`.
- When `connectionState == CONNECTED`: a "发送测试" `Button` → `bleViewModel.sendTest()`, and a `Text` showing `bleViewModel.echoResult` ("收到回声：pong").
- All wrapped in the existing Compose theme; experimental label retained.

- [ ] **Step 6: Run VM test + assembleDebug**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.viewmodel.BleSettingsViewModelTest" :app:assembleDebug`
Expected: VM test passes; debug build succeeds.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModel.kt android/app/src/test/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModelTest.kt android/app/src/main/java/com/juziss/localmediahub/ui/screen/ConnectionScreen.kt
git commit -m "feat(ble): VM scan/connect/sendTest + ConnectionScreen device list + echo UI"
```

---

## Task 10: 集成自检 + 文档 + 真机验证清单

**Files:**
- Modify: `docs/superpowers/specs/2026-07-26-ble-gatt-wiring-design.md`（追加"完成状态"小节）
- Modify: `AGENTS.md`（更新 BLE 模块说明：角色反转 + /api/v1/ble/* + Central/Peripheral 新文件）

- [ ] **Step 1: Full server build + test (both tags)**

Run: `cd server && go build ./... && go build -tags bluetooth ./... && go test ./...`
Expected: all green.

- [ ] **Step 2: Full Android test + build**

Run: `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: all green.

- [ ] **Step 3: Update spec with completion status**

Append to `docs/superpowers/specs/2026-07-26-ble-gatt-wiring-design.md`:

```markdown
---

## 11. 实施完成状态（2026-07-26）

**已完成（本期）：**
- server: `ble.Central`（scan/connect/send 状态机）+ tinygo Central adapter（bluetooth tag）+ stub + `/api/v1/ble/scan|connect|send` handler（复用 Bearer Token）。删除上期 Peripheral 代码。
- Android: `BlePeripheralManager` + `AndroidBlePeripheralManager`（BluetoothGattServer 广播 + Command/State 特征）+ `BleController` 重构（Peripheral 语义，HTTP 协调驱动 markConnected/markDisconnected）+ `BleApi`（HTTP 调 /api/v1/ble/*）+ `BleSettingsViewModel`（scan/connect/sendTest）+ ConnectionScreen UI（扫描列表 + 选设备 + 发送测试 + echo 回显）。删除上期 Central 骨架。状态机 SCANNING→ADVERTISING。
- 测试：server Central 7 + handler 5；Android 状态机 5 + controller 5 + BleApi 5 + VM 3 = 全绿。server 双构建路径通过；Android assembleDebug 通过。

**留作下一期：**
- 业务信令语义（播放控制/进度/选书——需先决策 server 角色）
- 文本降级传输
- Wi-Fi 健康探针 + 自动降级路由
- 断线自动重连
- MTU 协商优化
- Linux 适配（BlueZ）

**手动真机验证清单（需 Windows PC + Android 13 真机）：**
- [ ] PC server 以 `go build -tags bluetooth` 构建，启动后日志显示 "BLE Central ready"。
- [ ] Android 开"BLE 实验性通道"开关 → 授权蓝牙权限 → 状态"广播中"。
- [ ] Android 点"扫描设备" → 列表显示自己的设备（带 SERVICE_UUID）。
- [ ] 选中设备 → POST /connect → 状态变"已连接"。
- [ ] 点"发送测试" → UI 显示"收到回声：pong"（验证双向 GATT 通）。
- [ ] 关开关 → 广播停，PC 端连接断开，现有功能不受影响（零退化）。
- [ ] PC 无蓝牙模块或未启 `bluetooth` tag → 3 个 endpoint 返回明确错误，server 不崩。
```

- [ ] **Step 4: Update AGENTS.md BLE entries**

In `AGENTS.md`, update the two BLE bullet points (server `internal/ble/` and Android `ble/`) added last round to reflect:
- server is now Central (not Peripheral); list `central.go`/`central_adapter*.go`; mention `/api/v1/ble/scan|connect|send`.
- Android is now Peripheral; list `BlePeripheralManager`/`AndroidBlePeripheralManager`/`BleApi`; mention BluetoothGattServer + advertiser.
- Note role reversal reason (Windows winrt Peripheral weak).

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/specs/2026-07-26-ble-gatt-wiring-design.md AGENTS.md
git commit -m "docs(ble): record GATT wiring completion + real-device verification checklist"
```

---

## Self-Review Notes

**Spec coverage check:**
- §3 角色反转（Android Peripheral / PC Central）→ Task 1-4 (server Central) + Task 5-7 (Android Peripheral) ✓
- §4 文件清单 → 所有 Create/Delete/Modify 均在 Task 1-10 覆盖 ✓
- §5 数据流（3 阶段）→ Task 7（广播）+ Task 8/9（扫描选设备连接）+ Task 9（发送测试 echo）✓
- §6 HTTP API（scan/connect/send + Bearer + 244 限制）→ Task 4 handler + Task 8 BleApi ✓
- §7 错误处理（超时/并发/零退化）→ Task 1 Central（ctx 超时 + mutex）+ Task 4 handler（nil BLECentral → unavailable 响应）✓
- §7 状态机调整（SCANNING→ADVERTISING）→ Task 5 ✓
- §8 测试策略（server mock / Android mock+MockWebServer+Compose / 真机）→ Task 1,2,4,5,7,8,9 ✓；真机清单 Task 10 ✓
- §9 YAGNI 边界 → Global Constraints + 各 Task 不涉及 ✓
- §10 与上期关系（删 Peripheral、保留 protocol/ToggleRule/DataStore）→ Task 3 删 + Task 5/7 改 + 保留项未触碰 ✓

**Placeholder scan:**
- Task 2 Step 4 含真实 tinygo API 调用（Scan/Connect/DiscoverServices 等）——这些是 tinygo-org/bluetooth v0.15.0 的真实 API surface，但具体方法名（如 `WriteWithoutResponse`、`EnableNotifications`、`ConnectionParams{}`）需在实施时对照库的 godoc 核对。Task 2 Step 6 验证 `-tags bluetooth` 编译通过，是核对点。若 API 名有出入，实施者在该任务内修正（属于 transcription-level 适配，非设计缺陷）。
- Task 4 Step 5 的 server.go 注入点用了"find it — likely"措辞——但 Step 1 明确要求先 Read handler.go 和 server.go 定位，这是可执行的指令而非占位符。
- Task 9 Step 4/5 的 VM 和 UI 扩展是"描述 + 关键属性"而非逐行代码——因为这部分高度依赖现有代码结构（BleSettingsViewModel 现有形态、ConnectionScreen 现有 composable），逐行硬编码反而会与现状脱节。指令明确（"Read first, then add X/Y/Z"），可执行。
- Task 6 Step 3 的 `BluetoothGattServer` 实现用了真实 Android API——这些是 Android framework 的稳定 API（BluetoothGattServerCallback、AdvertiseSettings 等），需实施时确认 import 正确，但 API surface 稳定。

**Type consistency check:**
- `ble.Device{ID, Name, RSSI}` —— Task 1 定义，Task 4 handler 测试 + JSON 序列化一致 ✓
- `ble.CentralScanner` 接口（Scan/Connect/Disconnect/WriteCommand/WaitNotify）—— Task 1 定义，Task 2 adapter 实现一致 ✓
- `ble.Central.Scan/Connect/Send/Disconnect/State` —— Task 1 定义，Task 4 `BLECentralBackend` 接口子集一致 ✓
- `BlePeripheralManager` 接口（startAdvertising/stopAdvertising/setOnPayloadReceived/notifyPayload/isAdapterUsable）—— Task 6 定义，Task 7 controller 使用一致 ✓
- `BleConnState.ADVERTISING`（替代 SCANNING）+ `onStartAdvertising()` —— Task 5 定义，Task 7 controller 调用一致 ✓
- `BleApi.scan/connect/send` + `BleDevice{id,name,rssi}` —— Task 8 定义，Task 9 VM 使用一致 ✓
- `BleController.markConnected/markDisconnected/evaluateAvailability/setEnabled/connectionState` —— Task 7 定义，Task 9 VM（mockk）使用一致 ✓
- HTTP 路径 `/api/v1/ble/scan|connect|send` —— Task 4 server + Task 8 Android 一致 ✓
- Bearer header `Authorization: Bearer <token>` via `serverConfig.getTokenSnapshot()` —— Task 8 一致（上期 ServerConfig 已有此方法）✓

**Scope reality note:** Task 6+7 合并执行（manager + controller + module 强耦合，无法独立编译）已在 Task 6 Step 5 明确标注，Task 7 Step 6 统一提交。这是对"bite-sized"原则的合理偏离——拆开会导致中间提交编译失败。
