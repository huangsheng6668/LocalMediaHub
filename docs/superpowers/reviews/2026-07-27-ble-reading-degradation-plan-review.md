# Plan 审核报告：BLE 降级小说阅读与传输

**审核对象**: `docs/superpowers/plans/2026-07-27-ble-reading-degradation.md`
**对照 spec**: `docs/superpowers/specs/2026-07-27-ble-reading-degradation-design.md`
**审核日期**: 2026-07-27
**结论**: **不可直接开发。** 存在多处与现有代码冲突的硬伤，需先回 spec 层修订后再重写 plan。

---

## 致命问题（必须先解决）

### 问题 1：协议帧格式与现有实现完全冲突

**Plan/Spec 声称的帧头**（7 字节）：
```
[Version 1B][CmdID 1B][Length 2B][Payload NB]
```

**现有代码的真实帧格式**（`server/internal/ble/protocol.go:1-61`，3 字节）：
```
[0]    version (0x01)
[1:3]  uint16 payload length   (big-endian)
[3:]   payload bytes
```

**关键事实**：现有协议里 **没有 CmdID 字段**。整个 payload 被当作应用层逻辑透传——`Central.Send`（`central.go:64-82`）只是 Write 一个 payload、然后等单个 Notify 回显。不存在"按 CmdID 分发到不同 handler"的机制。

Plan Task 1 直接在 payload 里塞 `CmdBookChapterReq = 0x11` 并新增 `EncodeBookChapterReq`，但**没有定义 CmdID 在 wire 上的字节位置**，也没改 `EncodeFrame/DecodeFrame`。照此实施会与现有 BLE 控制信道（已调通的 echo 回路）破坏性不兼容。

---

### 问题 2：`Central.Send` / `WaitNotify` 只能收单帧，无法收多帧 Chunk 流

`server/internal/ble/central_adapter.go:212-239` 的 `WaitNotify` 实现：

```go
notifyCh := make(chan []byte, 1)   // 容量 1
handler := func(data []byte) { ... }
t.stateChar.EnableNotifications(handler)
select {
case data := <-notifyCh:           // 收到第 1 帧就返回
    return data, nil
case <-ctx.Done():
    return nil, ctx.Err()
}
```

收到**第 1 个 Notify 就立即返回**，channel 容量 1，后续帧被 `default` 丢弃。

但章节 Chunk 协议需要服务端连续 Notify 发送 N 个分片（spec §3.2 的 `TotalChunks`）。**plan 完全没有触及这个核心改造点**——服务端如何"一次 Write 请求 → 触发多帧 Notify 流"，客户端如何"等待 N 帧凑齐"。这是 BLE 降级传输的命门，plan 只字未提。

---

### 问题 3：方向角色搞反了

`BleController.kt:9-10` 注释明确：

> Android acts as the BLE **Peripheral** (advertising); the **Central** (PC server) scans + connects.

`BleController.init`（`BleController.kt:40-45`）只做**被动回显**：收到 payload → notify 回原样。Android 端**完全没有"主动 Write 一个请求并等待多帧响应"的能力**。

但 spec §2 架构图和 plan 描述的是：**Wi-Fi 断开时 Android 主动发 BLE 请求**——这是 Central 角色行为，与现有 Peripheral-only 实现矛盾。

这是需要先在 spec 层拍板的设计决策：
- **方案 A**：Android 升级为能扮演 Central（需要新增一套 Central 栈到 Android 端）。
- **方案 B**：维持 PC=Central / Android=Peripheral，但扩展为"PC 主动 Write 请求章节 → Android Peripheral 分片 Notify 回送"。

Plan 隐含走方案 A，但现有代码全是方案 B 的骨架。**不能在 plan 里跳过这个决策。**

---

## 严重问题（会导致实现错误）

### 问题 4：`MediaRepository` 未注入 `BleController`

Plan Task 3 Step 1 写道：

> If `bleController.connectionState.value == BleConnState.CONNECTED`, call `bleTransportFallback.fetchChapter(...)`

但 `MediaRepository`（`MediaRepository.kt:32` 起的构造参数）**当前根本不接受 `BleController`**，也不接受 `BleTransportFallback`。`BleController` 是 Hilt `@Singleton`，注入它需要：

1. 修改 `MediaRepository` 构造函数（新增 `bleController`、`bleTransportFallback` 参数）；
2. 修改对应的 Hilt `@Module`（提供 `MediaRepository` 的那个 module）。

Plan Task 3 的 **Files 清单里完全没有列出这两个 wiring 文件**。这是会导致编译失败/注入失败的硬缺口。

---

### 问题 5：`getBookChapter` 返回 Block 列表，不是纯文本

`MediaRepository.getBookChapter`（`MediaRepository.kt:266-270`）返回 `NetworkResult<BookChapterContent>`——一个章节的 **`Block` 列表**（文本块 + 图片块 URL）。

而 `BookService.GetChapterBlocks`（`server/internal/service/book.go:111-151`）做了重要工作：把 epub 内的相对图片路径重写成带 **HMAC 签名**的 `/api/v1/books/image?...&sig=...` URL。

Plan Task 2 的 `BleTransportFallback.fetchChapter(): String?` 只返回一个纯 `String`：

- 丢失了图片块（降级后图片章节直接坏掉）；
- 丢失了 `GetChapterBlocks` 的图片 URL 重写逻辑；
- spec §3.2 明明有 `TotalBlocks` 字段，但 plan 实现侧完全没处理 Block 结构。

要么 spec 收敛为"BLE 降级仅支持纯文本文档"，要么 plan 必须把 `fetchChapter` 改成返回 `BookChapterContent` 并在服务端按 Block 序列化。两者必须一致。

---

### 问题 6：spec 的"双重超时/重发"在 plan 中完全缺失

Spec §5 第 3 条明确要求：

> 单帧 > 3 秒自动重发该 Chunk；连续失败 3 次抛出"网络与蓝牙通道均不可用"提示。

Plan Task 2 的实现描述只有：

> buffer 累积、chunk index 排序、UTF-8 字符串拼装

**没有任何重发、超时、失败计数逻辑。** 这是 spec→plan 的需求遗漏。

---

### 问题 7：Task 3 测试覆盖不足

Task 3 Step 3 只跑：

```bash
./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.ble.*" assembleDebug
```

这**不覆盖** `MediaRepository` 的新 failover 分支（"HTTP 抛 IOException → 切到 BLE"）。而这恰恰是降级功能的核心路径。应当补 `MediaRepository` 的 fake/mock 单测，验证：

- HTTP 成功时 100% 走 HTTP（零回归）；
- HTTP 抛 `IOException`/`SocketTimeoutException` 且 BLE=CONNECTED 时切到 fallback；
- HTTP 失败但 BLE 未连接时正确返回错误。

---

## 单元测试本身的问题

### 问题 8：Task 2 的测试数据与 spec 的传输层脱节

Plan Task 2 的测试 chunk：
```kotlin
chunk1 = [0,2, 0,0, 0,5, 0,1, 'h','e','l','l','o']
//        TotalChunks=2 ChunkIndex=0 PayloadLen=5 TotalBlocks=1 data=hello
```

spec §3.2 规定每帧 payload 要被外层帧头（version/length）包裹。但测试数据里**没有任何帧头字节**，等于跳过了 `DecodeFrame`。

也就是说：测试测的是"纯 payload 字节数组的重组"，而**真正要测的"经 BLE 信道多帧传输 + DecodeFrame 解帧 + 重组"完全没覆盖**。这个测试即使通过，也不能证明 BLE 链路可用。

---

## 次要问题

### 问题 9：`notifyPayload` 返回 `Boolean`，服务端连续 Notify 没有错误处理

`BlePeripheralManager.notifyPayload`（`BlePeripheralManager.kt:20`）签名：

```kotlin
fun notifyPayload(payload: ByteArray): Boolean  // false = 无订阅者
```

Plan 说服务端"连续 Notify 发送 Chunk"，但没说遇到 `false`（Central 已断开）时如何中止、退避或重试。

---

### 问题 10：`CMD_BOOK_INFO_REQ (0x10)` 在 plan 中完全缺失

Spec §3.1 定义了两个命令：
- `0x10` `CMD_BOOK_INFO_REQ / RESP`
- `0x11` `CMD_BOOK_CHAPTER_REQ / RESP`

Plan **只实现 0x11，完全跳过 0x10**。如果 BLE 降级时连图书元数据（章节列表）都拿不到，客户端怎么知道要请求第几章？要么 spec 收敛掉 0x10，要么 plan 补全，二者必须一致。

---

### 问题 11：服务端 handler 改造未细化

Plan Task 1 列出 `server/internal/server/handler/ble.go` 要修改，但只描述了"调用 BookService 切分 Chunk"，没有说明：

- 如何把现有的 echo 处理（`Central.Send` 单次往返）改造成流式响应；
- `handler/ble.go` 与 `central.go` 之间的接口如何从"单 payload 回显"演化为"多帧 Notify 调度"。

这是服务端最大的改造缺口，plan 没有给方案。

---

## 建议的下一步

在回到代码之前，先在 **spec 层**钉死两个根本决策：

1. **角色决策**：BLE 降级传输时 Android 扮演 Central 还是 Peripheral？（问题 3）
2. **多帧流控决策**：帧格式里是否正式加入 CmdID？`WaitNotify` 如何演化为"等待 N 帧凑齐"？（问题 1、2）

建议流程：

1. `superpowers:brainstorming` → 修订 `2026-07-27-ble-reading-degradation-design.md`，把上述决策写死；
2. `superpowers:writing-plans` → 基于修订后的 spec 重写本 plan；
3. 修订后的 plan 再过一轮审核，通过后才进入 `superpowers:executing-plans`。

在那之前**不应进入开发**，否则会产出与现有 BLE 协议不兼容的代码，并破坏已调通的 echo 回路。
