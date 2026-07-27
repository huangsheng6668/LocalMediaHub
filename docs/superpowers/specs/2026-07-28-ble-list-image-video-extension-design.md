# BLE 降级扩展：列表 / 图片占位 / 视频禁用 方案设计

**日期**: 2026-07-28
**范围**: `server/` (Go) + `android/` (Kotlin)
**目标**: 在现有 BLE 章节降级通道（已验证可用）基础上，把降级覆盖范围从「仅章节」扩展到「文件夹列表 + 文件夹内容 + 书信息」三类列表 JSON 请求；Wi-Fi 断开时图片走统一占位符、视频项置灰且禁用打开。

**前置**: 现有 BLE 降级链路已完成（`docs/superpowers/specs/2026-07-27-ble-reading-degradation-design.md`），PC=Central / Android=Peripheral 拓扑、3 字节物理帧头、chunk 切片回传机制均已落地并真机验证通过。

---

## 1. 需求边界

### 1.1 覆盖的列表接口（BLE 降级）
| 接口 | endpoint 标识 | HTTP 路径 | BLE 用到的参数 |
|---|---|---|---|
| 顶层文件夹列表 | `FOLDERS` | `GET /api/v1/folders` | 无 |
| 文件夹内容浏览 | `BROWSE_FOLDER` | `GET /api/v1/folders/{path}/browse` | path |
| 书信息 | `BOOK_INFO` | `GET /api/v1/books/info?path=` | path |
| 章节内容（已有） | `BOOK_CHAPTER` | `GET /api/v1/books/chapter?path=&index=` | path + index |

### 1.2 不覆盖（YAGNI）
- `search`、标签相关接口（`getTags`、`getTaggedMedia` 等）—— 本次不做。
- 图片字节传输（缩略图、原图、章节内 `<img>`）—— BLE 带宽（~3-5 KB/s）不支持，走占位符。
- 视频流传输 —— 不可能，走禁用态。
- 大列表分页 / 截断 —— 不做，带宽是硬限制，列表 loading 态沿用现有 UI。

### 1.3 用户体验（Wi-Fi 断开 + BLE 已连接时）
- 文件夹/小说/视频**列表文字**正常加载（图片位置显示统一占位图）。
- 小说可进入阅读，章节走现有降级。
- **视频项置灰**（alpha 降低），点击弹提示「BLE 模式下暂不支持播放视频」。
- 所有图片位置（缩略图、原图）显示占位图，不发起注定失败的 HTTP 请求。

---

## 2. 协议层设计（通用化）

### 2.1 物理帧头（不变）
```text
[0]    version (0x01)
[1:3]  uint16 payload length (big-endian)
[3:]   payload bytes
```

### 2.2 应用层命令

| CmdID | 名称 | 方向 | payload 格式 |
|---|---|---|---|
| `0x11` | `CMD_API_REQ` | Android → PC (Notify) | `[CmdID 1B][Endpoint 1B][PathLen 1B][Path bytes][Index 2B BE]` |
| `0x12` | `CMD_JSON_CHUNK` | PC → Android (Write) | `[CmdID 1B][TotalChunks 2B BE][ChunkIndex 2B BE][TotalBytes 2B BE][ChunkLen 2B BE][Chunk bytes]` |

**与现有实现的差异**:
- `0x11` 的 CmdID 值不变，但 payload 第 2 字节从「ChapterIndex 高字节」改为 **Endpoint**。原 `[CmdID][ChapterIndex 2B][PathLen][Path]` → 新 `[CmdID][Endpoint 1B][PathLen 1B][Path][Index 2B BE]`。
- `0x12` 的 CmdID 值与格式**完全不变**，仅语义从「章节 Block JSON」泛化为「任意 JSON 字节」。原 `TotalBlocks` 字段改名为 `TotalBytes`（值含义：重组后完整 JSON 的总字节数，用于校验）。

**Wire 兼容性**: 这是 wire 层的不兼容变更。由于整个 BLE 降级功能是 2026-07-27 批次新增、两端同批发布，不存在旧客户端兼容问题。物理帧头保持兼容（零破环）。

### 2.3 Endpoint 枚举（payload[1]，1 字节）
| 值 | 名称 | server 端业务方法 |
|---|---|---|
| `0x01` | `BOOK_CHAPTER` | `BookService.GetChapterBlocks(ctx, path, idx, ip)` → JSON |
| `0x02` | `FOLDERS` | 文件夹列表业务逻辑 → JSON |
| `0x03` | `BROWSE_FOLDER` | 文件夹内容业务逻辑（path）→ JSON |
| `0x04` | `BOOK_INFO` | 书信息业务逻辑（path）→ JSON |

**Path/Index 约定**: `PathLen == 0` 表示该请求无 path（如 FOLDERS）；`Index` 字段对不需要 index 的 endpoint 无意义（填 0）。server 按 endpoint 决定读哪些字段。

---

## 3. 关键组件与职责

### 3.1 Server 端 (Go)

**`server/internal/ble/protocol.go`**:
- `CmdID` 常量重命名/泛化：`CmdBookChapterReq → CmdApiReq (0x11)`，`CmdBookChapterChunk → CmdJsonChunk (0x12)`。
- Endpoint 常量：`EndpointBookChapter = 0x01` 等。
- 新编解码：`EncodeApiReqPayload(endpoint, path, index) ([]byte, error)`、`DecodeApiReqPayload(payload) (endpoint, path, index, error)`。PathLen 仍为 1 字节（>255 返回 `ErrPathTooLong`，复用现有错误）。
- chunk 编解码：`EncodeJsonChunkPayload` / `DecodeJsonChunkPayload`（原 chapter chunk 函数改名，`TotalBlocks` 字段语义改为 `TotalBytes`，布局不变）。`ChunkJsonBytes(jsonBytes)` 切片（复用现有 `ChunkChapterBlocks` 逻辑，200B 上限不变）。

**`server/internal/ble/central.go`**:
- `ChapterProvider` 接口泛化为 `ApiProvider`：
  ```go
  type ApiProvider interface {
      HandleBleRequest(ctx context.Context, endpoint byte, path string, index int) ([]byte, error)
  }
  ```
- `SetChapterProvider` → `SetApiProvider`。
- `RunChapterListener`（可改名 `RunApiListener`）收到 `0x11` 后：解码 endpoint/path/index → 调 `apiProvider.HandleBleRequest` → 切 chunk → `WriteCommand` 连续回发。
- `ServeChapterRequest` 泛化为 `ServeApiRequest(notifyPayload)`。

**`server/internal/service/`**: `BookService`（或新增薄封装 `BleApiProvider`）实现 `ApiProvider.HandleBleRequest`，按 endpoint 路由到现有业务方法（`GetChapterBlocks` / 文件夹列表 / 文件夹内容 / 书信息），把结果序列化为 JSON 字节返回。直接复用 service 层方法，**不经 echo handler**，避免 HTTP 依赖与重复鉴权。

**`server/internal/server/server.go`**: 启动时 `SetApiProvider(...)`（替换原 `SetChapterProvider`）。

### 3.2 Android 端 (Kotlin)

**`android/.../ble/BleProtocol.kt`**:
- 常量重命名：`CMD_BOOK_CHAPTER_REQ → CMD_API_REQ (0x11)`，`CMD_BOOK_CHAPTER_CHUNK → CMD_JSON_CHUNK (0x12)`。
- 新增 Endpoint 常量：`ENDPOINT_BOOK_CHAPTER = 0x01` 等。

**`android/.../ble/BleController.kt`**:
- `requestChapter(path, index)` → `requestApi(endpoint, path, index): Boolean`，构建 `[0x11][endpoint][pathLen][path][index 2B BE]`（>255 path 仍返回 false）。

**`android/.../ble/BleTransportFallback.kt`**:
- `fetchChapterBlocks(path, index)` → `fetchJson(endpoint, path, index): String?`（返回拼接后的原始 JSON 字符串）。内部 completion hook / synchronized / 异步 await 逻辑不变，dispatch 改为调 `requestApi`。

**`android/.../data/MediaRepository.kt`**:
- 把 `tryBleFailover` 泛化为通用私有方法：
  ```kotlin
  private suspend fun <T> bleFetchOrHttp(
      httpCall: suspend () -> T,
      endpoint: Byte,
      path: String = "",
      index: Int = 0,
      type: java.lang.reflect.Type,
  ): NetworkResult<T>
  ```
  逻辑：先 HTTP；`IOException`/`SocketTimeoutException` + `connectionState == CONNECTED` → `bleTransportFallback.fetchJson(endpoint, path, index)` → Gson 反序列化 → 成功置 `isBleDegraded=true` + 发 `bleDegradedEvents`。`HttpStatusException` 不降级。
- `getFolders` / `browseFolder` / `getBookInfo` / `getBookChapter` 改为调 `bleFetchOrHttp`，传各自 endpoint + type。

**图片占位（Coil）**:
- 在 `LocalMediaHubApplication.newImageLoader` 或图片请求处增加条件：当 `isBleDegraded == true` 时，图片请求**不发起 HTTP**，直接返回统一占位 drawable。
- 实现：一个 Coil `Interceptor`（或 `fetcher` 分支），读取全局 `isBleDegraded` 状态（通过 `MediaRepository.isBleDegraded` StateFlow），降级时短路返回占位图，避免每个图片触发失败连接 + 超时。

**视频禁用态（UI）**:
- `browseFolder` 返回的 `MediaFile` 有类型字段。列表项 Composable 读 `isBleDegraded` + 类型 == 视频 → 该项 `alpha` 降低 + 点击拦截，弹 Snackbar「BLE 模式下暂不支持播放视频」。
- 小说/图片项正常可点（小说进阅读走降级；图片原图 BLE 模式不可用，但列表项本身可点，点进去图片走占位）。

---

## 4. 错误处理 / 超时 / 边界

### 4.1 超时与重试
- `fetchJson` 沿用现有超时预算（单帧超时 × 3 次重试）。列表 JSON 比章节大、chunk 更多，总耗时更长但不会无限等待。
- 超时 / 重组失败 → `fetchJson` 返回 null → `bleFetchOrHttp` 返回原始 HTTP 错误，`isBleDegraded` 不置 true（BLE 未真正承载数据则不显示降级态）。

### 4.2 大列表边界
- BLE ~3-5 KB/s：10KB ≈ 3-4 秒，50KB ≈ 15 秒。不设硬截断；列表 loading 沿用现有 UI。
- 不做分页（YAGNI），将来体验差再考虑。

### 4.3 零回归
- Wi-Fi 正常时 100% HTTP，BLE 分支仅 IOException 触发。
- `HttpStatusException`（4xx/5xx）不降级。
- BLE 未连接不降级，返回 HTTP 错误。

---

## 5. 测试策略

1. **Go 协议/路由单测** (`server/internal/ble/`):
   - `CMD_API_REQ` 编解码（各 endpoint + 无 path / 有 path / path+index 组合）。
   - `ApiProvider.HandleBleRequest` 各 endpoint 返回正确 JSON（stub provider）。
   - chunk 切片 + 回传重组（复用现有 chunk 测试框架，验证 `TotalBytes` 校验）。
   - 跨端 wire 字节一致性（Go 编码 ↔ Kotlin 解码）。
2. **Kotlin 重组 / failover 单测** (`android/.../ble/`, `android/.../data/`):
   - `fetchJson` 各 endpoint（fake 异步 chunk 投递，复用现有 `MediaRepositoryFailoverTest` 异步 fake 模式）。
   - `bleFetchOrHttp` 对 folders / browse / bookInfo 三个接口的 failover（CONNECTED→BLE / DISCONNECTED→错误 / 超时→原错误）。
   - 视频项置灰 + 点击拦截的 Compose 测试。
3. **真机验证**: 断 Wi-Fi → 浏览目录（列表加载）→ 进小说阅读（章节）→ 视频项置灰点不动 → 图片占位。
