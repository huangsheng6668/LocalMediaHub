# BLE 降级小说阅读与传输方案设计（修订版）

**日期**: 2026-07-27
**范围**: `server/` (Go) + `android/` (Kotlin)
**目标**: 基于现有 **PC=Central / Android=Peripheral** 拓扑，实现 Wi-Fi 断开时无缝降级到 BLE 物理信道，通过二进制 Chunk 拼包传输小说章节 Blocks，保障阅读体验零中断。

---

## 1. 背景与角色定调

### 1.1 拓扑约束（保持既有角色）
- **PC server = BLE Central** (持有蓝牙连接，监听 Android 的 GATT Notify 特征，向 Android 的 GATT Write 特征发包)
- **Android app = BLE Peripheral** (广播 SERVICE_UUID，提供 GATT Command 特征和 State 特征)

### 1.2 降级信道交互机制 (Wi-Fi 掉线时)
当 Wi-Fi 网络断开且用户在 Android 手机上翻页/请求章节时：
1. **Android (Peripheral)**：通过 GATT State 特征发出 **Notify 报文**：`CMD_CHAPTER_REQ (path, index)`。
2. **PC (Central)**：通过 GATT Notification 回调监听到章节请求，读取本地文件并调用 `BookService.GetChapterBlocks` 生成 Block 结构。
3. **PC (Central)**：将 Blocks JSON 切分为 N 个 Chunk 包（每帧 ≤ 200 字节），通过 GATT **Write 报文** 连续发往 Android 的 Command 特征。
4. **Android (Peripheral)**：在 `onCharacteristicWriteRequest` 中接收并累积 Chunk 分片，拼包完成后反序列化为 `BookChapterContent`（包含文本 Block 与图片 Block 签名 URL），提交给阅读器 UI，并弹出 `[⚡ BLE 降级传输中]` Chip 提示，**展示 3 秒后自动淡出**。

---

## 2. 帧格式与 Wire 协议向下兼容

### 2.1 物理帧头（保持 3 字节，零破环兼容）
```text
[0]    version (0x01)
[1:3]  uint16 payload length (big-endian)
[3:]   payload bytes
```

### 2.2 Payload 应用层协议格式
在 `payload bytes` 内，**第 0 字节固定为 `CmdID`**：

| CmdID | 名称 | 方向 | 格式 |
|---|---|---|---|
| `0x01` | `CMD_ECHO` | 双向 | `[CmdID 1B][Echo Payload]` (现有 Ping/Pong 测试) |
| `0x10` | `CMD_BOOK_INFO_REQ` | Android ➔ PC (Notify) | `[CmdID 1B][PathLen 1B][Path Bytes]` |
| `0x11` | `CMD_BOOK_CHAPTER_REQ` | Android ➔ PC (Notify) | `[CmdID 1B][ChapterIndex 2B][PathLen 1B][Path Bytes]` |
| `0x12` | `CMD_BOOK_CHAPTER_CHUNK`| PC ➔ Android (Write) | `[CmdID 1B][TotalChunks 2B][ChunkIndex 2B][TotalBlocks 2B][ChunkLen 2B][Chunk Bytes]` |

---

## 3. 关键组件与职责分工

### 3.1 Server 端 (Go)
- `server/internal/ble/protocol.go`:
  - 定义 `CmdID` 枚举与 `CMD_BOOK_CHAPTER_REQ / CHUNK` 编解码器。
- `server/internal/ble/central_adapter.go` & `central.go`:
  - 增加长效 `EnableNotifications` 事件监听。收到 `CMD_BOOK_CHAPTER_REQ` 后，开启 goroutine 异步切分 Chunk，并通过 `WriteCommand` 连续发送 Chunk。

### 3.2 Android 端 (Kotlin)
- `ble/BleTransportFallback.kt`:
  - 封装多分片缓冲器与超时控制（单帧 > 3 秒超时重发，连续 3 次失败提示异常）。
- `data/MediaRepository.kt`:
  - 构造函数新增 `bleController: BleController`, `bleTransportFallback: BleTransportFallback` 依赖注入。
  - 在 `getBookChapter` 中捕捉 `IOException` / `SocketTimeoutException`；当 BLE 状态为 `CONNECTED` 时自动路由到 `BleTransportFallback.fetchChapterBlocks`，解出 `BookChapterContent`。
- `ui/screen/TextReaderScreen.kt`:
  - 监听 `isBleDegraded` 状态，浮动弹出 **`[⚡ BLE 降级传输中]`** 提示 Chip，并在 **3 秒后自动淡出**。

---

## 4. 测试策略与验证

1. **协议编解码单测** (`server/internal/ble/protocol_test.go`): 验证含 3 字节帧头与 `CmdID` 的分包完整性。
2. **重组分包单测** (`android/.../ble/BleTransportFallbackTest.kt`): 模拟完整的帧头解包、Chunk 乱序/缺失、超时重试与 Block JSON 还原。
3. **Repository 降级单测** (`android/.../data/MediaRepositoryTest.kt`): 模拟 HTTP 异常时无缝路由到 BLE。
4. **真机链路测试**：断开 Wi-Fi 翻页测试。

