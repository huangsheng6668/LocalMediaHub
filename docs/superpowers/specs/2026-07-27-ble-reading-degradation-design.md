# BLE 降级小说阅读与传输方案设计

**日期**: 2026-07-27
**范围**: `server/` (Go) + `android/` (Kotlin)
**目标**: 实现 Wi-Fi 断开时无缝降级到 BLE 物理信道，通过二进制分片协议分章节传输/下载小说文本，保障阅读体验零中断。

---

## 1. 背景与核心定位

LocalMediaHub 的 BLE 通道已成功调通双向 GATT 控制信道（PC Central ↔ Android Peripheral，往返延迟 ~200ms）。
本方案解决 **Wi-Fi 掉线/不稳定时的阅读连续性问题**：
- 当 Wi-Fi 网络连通时，小说章节与图书元数据通过标准的 HTTP/Wi-Fi 高速获取。
- 当 Wi-Fi 断开（例如网络切换、移动到边缘信号区）时，客户端网络层自动触发 **`BleTransportFallback` 降级路由**。
- 请求转由 BLE 物理通道发送，服务端将章节文本以二进制 Chunk 分片（每帧 ≤ 200 字节）并发/串行发送，客户端重组拼包后呈现给阅读器 UI，画面标注“BLE 降级模式”。

---

## 2. 整体架构与分层

```
+-----------------------------------------------------------------------+
|                         TextReaderScreen (UI)                         |
+-----------------------------------------------------------------------+
                                   |
                                   v
+-----------------------------------------------------------------------+
|                         TextReaderViewModel                           |
+-----------------------------------------------------------------------+
                                   |
                                   v
+-----------------------------------------------------------------------+
|                            MediaRepository                            |
|             (HTTP First -> Failover to BleTransportFallback)          |
+-----------------------------------------------------------------------+
                 /                                         \
                / (Wi-Fi Online)                            \ (Wi-Fi Drop & BLE Connected)
               v                                             v
  +------------------------+                     +------------------------+
  |  Retrofit (Wi-Fi HTTP) |                     |  BleTransportFallback  |
  +------------------------+                     +------------------------+
               |                                             | (GATT Write/Notify Chunks)
               v                                             v
  +------------------------+                     +------------------------+
  |    PC Server (Echo)    |                     |   PC Server (BLE)      |
  +------------------------+                     +------------------------+
```

---

## 3. BLE 传输分帧协议 (Frame & Chunk Spec)

### 3.1 扩展 Command ID
基于现有的 7 字节帧头部 (`[Version 1B][CmdID 1B][Length 2B][Payload NB]`)：
- `0x10` (`CMD_BOOK_INFO_REQ / RESP`): 获取图书元数据 (格式、标题、章节列表、总字数)
- `0x11` (`CMD_BOOK_CHAPTER_REQ / RESP`): 获取单章内容 Chunk 分片

### 3.2 章节请求与分片格式

#### 请求包 Payload (`CMD_BOOK_CHAPTER_REQ`, CmdID = 0x11)
```
[PathLength 1B][Path Bytes (UTF-8)][ChapterIndex 2B (BigEndian)]
```

#### 响应包 Payload (`CMD_BOOK_CHAPTER_RESP`, CmdID = 0x11)
每帧 Payload 头部包含 Chunk 序号元数据：
```
+-------------------+-------------------+-------------------+-------------------+------------------+
| TotalChunks (2B)  |  ChunkIndex (2B)  |  PayloadLen (2B)  | TotalBlocks (2B)  | Chunk Data Bytes |
+-------------------+-------------------+-------------------+-------------------+------------------+
```
- `TotalChunks`: 本章总分片数
- `ChunkIndex`: 当前分片索引 (0-based)
- `TotalBlocks`: 本章文本 Block 块总数
- `Chunk Data Bytes`: UTF-8 文本分片数据 (单个 Chunk Payload ≤ 200 字节，避免超过 BLE MTU 上限)

---

## 4. 关键组件改动与职责

### 4.1 Server 端 (Go)
1. `server/internal/ble/protocol.go`:
   - 增加 `CmdBookInfoReq (0x10)`, `CmdBookChapterReq (0x11)` 命令字定义。
   - 增加 Chunk 拆包/组包 helper 函数。
2. `server/internal/ble/central.go` & `handler/ble.go`:
   - 处理 BLE 接收到的 `CMD_BOOK_INFO_REQ` 和 `CMD_BOOK_CHAPTER_REQ` 命令。
   - 调用 `BookService` 读取对应章节文本，将其切分为 ≤ 200 字节的 Chunk 列表，通过 GATT Notify 连续发送回客户端。

### 4.2 Android 端 (Kotlin)
1. `data/BleTransportFallback.kt`:
   - 封装 BLE 降级数据传输器，管理 Chunk 接收状态机与组包 Timer/超时（单帧 3s，全章 15s）。
   - 按 `ChunkIndex` 整理填充 ByteArray 缓冲区，接收完成后解出 UTF-8 字符串与 JSON Blocks 列表。
2. `data/MediaRepository.kt`:
   - 在 `getBookInfo` 和 `getBookChapter` 中加入降级逻辑：HTTP 抛出 `IOException` / `SocketTimeoutException` 且 `BleController.isConnected == true` 时，自动切入 `BleTransportFallback`。
3. `ui/screen/TextReaderScreen.kt`:
   - 顶部状态栏增加 **`[⚡ BLE 降级传输中]`** 提示 Tag，用户体验无缝透明。

---

## 5. 错误处理与降级路由规则

1. **Wi-Fi 优先**：凡是 Wi-Fi 连通状态，100% 走 HTTP。
2. **断网切换**：若 HTTP 发生网络超时或断开，且 BLE 通道状态为 `CONNECTED`，即刻无缝切入 BLE。
3. **BLE 双重超时保护**：若 BLE 分片传输过程中丢包或超时（单帧 > 3 秒），自动重发该 Chunk 请求；连续失败 3 次抛出“网络与蓝牙通道均不可用”提示。

---

## 6. 测试与验证计划

### 6.1 单元测试
- **Go 协议单元测试** (`server/internal/ble/protocol_test.go`): 验证 Chunk 切分与拼包一致性。
- **Android 降级单元测试** (`android/.../ble/BleTransportFallbackTest.kt`): Mock 模拟分片乱序/缺失接收与重组拼包。

### 6.2 真机链路验证
- **在线切换验证**：手机打开小说阅读 ➔ 断开 Wi-Fi ➔ 点击翻下一页 ➔ 验证文字在 3-5 秒内通过 BLE 降级加载完成，界面显示 `[⚡ BLE 降级传输中]` 标识。

