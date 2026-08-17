### Task 8: BLE v2 帧 + HMAC 互挑战握手（Go 端）（H-1a）

**Files:**
- Modify: `server/internal/ble/protocol.go` + `server/internal/ble/protocol_test.go`
- Modify: `server/internal/ble/central.go` + `server/internal/ble/central_test.go`

**Interfaces:**
- Produces（Go 侧，Kotlin 侧 Task 9 逐字节对称）：
  - 常量：`FrameVersion2 byte = 0x02`；`CmdAuthChallenge CmdID = 0x20`；`CmdAuthResponse CmdID = 0x21`；`AuthDirCentralToPeripheral byte = 0x01`、`AuthDirPeripheralToCentral byte = 0x02`
  - v2 帧线格式：`[0x02][len 2B BE][payload ≤220B][seq 8B BE][hmac 16B]`，HMAC 覆盖 `[0 : 3+len+8]`，密钥 `DeriveBleAuthKey(token)`；seq 每方向严格递增，接收端拒绝 ≤ 已见最大 seq
  - `func DeriveBleAuthKey(token string) []byte` = `sha256("lmh-ble-v1:" + token)`
  - `func EncodeAuthedFrame(payload []byte, seq uint64, key []byte) []byte` / `func DecodeAuthedFrame(data, key []byte) (payload []byte, seq uint64, err error)`（新错误 `ErrBadMAC`、`ErrReplaySeq`）
  - 握手 payload：Challenge `[CmdID][dir 1B][nonce 8B]`；Response `[CmdID][nonce 8B][mac 16B]`，`mac = HMAC-SHA256(key, nonce || dir)[:16]`
  - `Central` struct 新增 `authenticated bool` / `localSeq, remoteSeq uint64` / `authKey []byte`；连接建立（CCCD 订阅完成）后 5s 内完成双方挑战，失败即断开；握手后只收 v2 帧
  - token 为空时 BLE 连接请求直接失败（handler 层拒绝，日志说明开放模式下 BLE 不可用）

- [ ] **Step 1: 写失败测试（protocol 层）**

```go
func TestAuthedFrameRoundTripAndTamper(t *testing.T) {
	key := DeriveBleAuthKey("sekrit")
	frame := EncodeAuthedFrame([]byte{byte(CmdEcho), 1, 2}, 7, key)
	payload, seq, err := DecodeAuthedFrame(frame, key)
	if err != nil || seq != 7 || payload[0] != byte(CmdEcho) {
		t.Fatalf("round trip failed: %v %d", err, seq)
	}
	frame[len(frame)-1] ^= 0xFF
	if _, _, err := DecodeAuthedFrame(frame, key); err != ErrBadMAC {
		t.Fatalf("tampered frame must fail with ErrBadMAC, got %v", err)
	}
	wrong := DecodeAuthedFrame // nil key path
	if _, _, err := DecodeAuthedFrame(frame, DeriveBleAuthKey("other")); err != ErrBadMAC {
		t.Fatalf("wrong key must fail, got %v", err)
	}
	_ = wrong
}

func TestAuthChallengeResponsePayload(t *testing.T) {
	key := DeriveBleAuthKey("sekrit")
	nonce := make([]byte, 8)
	rand.Read(nonce)
	ch := EncodeAuthChallengePayload(AuthDirCentralToPeripheral, nonce)
	dir, gotNonce, err := DecodeAuthChallengePayload(ch)
	if err != nil || dir != AuthDirCentralToPeripheral || !bytes.Equal(gotNonce, nonce) {
		t.Fatalf("challenge payload broken: %v", err)
	}
	mac := AuthResponseMAC(key, nonce, AuthDirCentralToPeripheral)
	resp := EncodeAuthResponsePayload(nonce, mac)
	rn, rm, err := DecodeAuthResponsePayload(resp)
	if err != nil || !bytes.Equal(rn, nonce) || !hmac.Equal(rm, mac) {
		t.Fatalf("response payload broken: %v", err)
	}
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd server && go test ./internal/ble/ -run 'TestAuthedFrame|TestAuthChallenge' -v`
Expected: FAIL（编译错误）

- [ ] **Step 3: 实现 protocol.go**

新增常量与函数（错误 `ErrBadMAC` / `ErrReplaySeq` 加入既有 errors 块；import `crypto/hmac`、`crypto/sha256`、`crypto/rand`、`crypto/subtle`）：

```go
const FrameVersion2 byte = 0x02
const authedOverhead = 8 + 16          // seq + truncated HMAC
const maxAuthedPayloadLen = maxPayloadLen - authedOverhead // 220

const (
	CmdAuthChallenge CmdID = 0x20
	CmdAuthResponse  CmdID = 0x21
)
const (
	AuthDirCentralToPeripheral byte = 0x01 // PC 验证手机
	AuthDirPeripheralToCentral byte = 0x02 // 手机验证 PC
)

func DeriveBleAuthKey(token string) []byte {
	h := sha256.Sum256([]byte("lmh-ble-v1:" + token))
	return h[:]
}

func EncodeAuthedFrame(payload []byte, seq uint64, key []byte) []byte {
	if len(payload) > maxAuthedPayloadLen {
		payload = payload[:maxAuthedPayloadLen] // 调用方 ChunkJsonBytes 已限 200B，防御性截断
	}
	buf := make([]byte, 3+len(payload)+authedOverhead)
	buf[0] = FrameVersion2
	binary.BigEndian.PutUint16(buf[1:3], uint16(len(payload)))
	copy(buf[3:], payload)
	binary.BigEndian.PutUint64(buf[3+len(payload):], seq)
	mac := hmac.New(sha256.New, key)
	mac.Write(buf[:3+len(payload)+8])
	copy(buf[3+len(payload)+8:], mac.Sum(nil)[:16])
	return buf
}

func DecodeAuthedFrame(data, key []byte) ([]byte, uint64, error) {
	if len(data) < 3+authedOverhead {
		return nil, 0, ErrTruncated
	}
	length := int(binary.BigEndian.Uint16(data[1:3]))
	if length > maxAuthedPayloadLen || len(data) < 3+length+authedOverhead {
		return nil, 0, ErrTooLarge
	}
	mac := hmac.New(sha256.New, key)
	mac.Write(data[:3+length+8])
	want := mac.Sum(nil)[:16]
	if subtle.ConstantTimeCompare(data[3+length+8:3+length+24], want) != 1 {
		return nil, 0, ErrBadMAC
	}
	seq := binary.BigEndian.Uint64(data[3+length : 3+length+8])
	payload := append([]byte(nil), data[3:3+length]...)
	return payload, seq, nil
}

func EncodeAuthChallengePayload(dir byte, nonce []byte) []byte {
	out := make([]byte, 10)
	out[0] = byte(CmdAuthChallenge)
	out[1] = dir
	copy(out[2:], nonce)
	return out
}

func DecodeAuthChallengePayload(p []byte) (byte, []byte, error) {
	if len(p) < 10 || CmdID(p[0]) != CmdAuthChallenge {
		return 0, nil, ErrTruncated
	}
	return p[1], append([]byte(nil), p[2:10]...), nil
}

func AuthResponseMAC(key, nonce []byte, dir byte) []byte {
	m := hmac.New(sha256.New, key)
	m.Write(nonce)
	m.Write([]byte{dir})
	return m.Sum(nil)[:16]
}

func EncodeAuthResponsePayload(nonce, mac16 []byte) []byte {
	out := make([]byte, 25)
	out[0] = byte(CmdAuthResponse)
	copy(out[1:9], nonce)
	copy(out[9:], mac16)
	return out
}

func DecodeAuthResponsePayload(p []byte) ([]byte, []byte, error) {
	if len(p) < 25 || CmdID(p[0]) != CmdAuthResponse {
		return nil, nil, ErrTruncated
	}
	return append([]byte(nil), p[1:9]...), append([]byte(nil), p[9:25]...), nil
}
```

握手帧本身以 **v1 帧**承载（`EncodeFrame` 包装 payload），认证成功后数据帧全部 v2。

- [ ] **Step 4: 实现 central.go 握手状态机**

`Central` 增加字段与流程（示意核心；接入点在既有连接成功/CCCD 订阅完成处与 `RunApiListener` 收帧处）：

```go
// 握手流程（连接后 5s 超时）：
// 1. PC 发 v1 CmdAuthChallenge(dir=CentralToPeripheral, nonce1)
// 2. 手机回 v1 CmdAuthResponse(nonce1, mac1)，PC 验证 mac1
// 3. 手机发 v1 CmdAuthChallenge(dir=PeripheralToCentral, nonce2)
// 4. PC 回 v1 CmdAuthResponse(nonce2, mac2)
// 5. 双方 authenticated=true；此后收帧仅接受 v2（DecodeAuthedFrame，
//    remoteSeq 严格递增校验，回退/重复返回 ErrReplaySeq 并断开），
//    发帧一律 EncodeAuthedFrame(localSeq++)。
// token 为空：ble handler（internal/server/handler/ble.go）对 scan/connect
// 请求直接返回 400，message 说明开放模式下 BLE 不可用。
```

`RunApiListener` 收到帧后先 `DecodeFrame`（v1 路径仅放行两个 AUTH 命令且要求 `!authenticated`）；`authenticated` 后改走 `DecodeAuthedFrame`。发送 `CMD_JSON_CHUNK` 的既有调用点改为 authed 编码。`central_test.go` 用既有 fake adapter 驱动：新增"正确密钥完成握手并传输"/"错误密钥握手失败断开"/"v2 重放 seq 被拒"三个用例。

- [ ] **Step 5: 跑测试确认通过**

Run: `cd server && go test ./internal/ble/ -v && go test ./...`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add server/internal/ble/protocol.go server/internal/ble/protocol_test.go server/internal/ble/central.go server/internal/ble/central_test.go server/internal/server/handler/ble.go
git commit -m "feat(ble): authed v2 frames with HMAC handshake on server (Phase 9)"
```

---

