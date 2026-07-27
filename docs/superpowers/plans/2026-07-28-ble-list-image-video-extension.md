# BLE 降级扩展：列表 / 图片占位 / 视频禁用 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 BLE 降级从「仅章节」扩展到「文件夹列表 + 文件夹内容 + 书信息」三类列表 JSON；降级模式下图片走占位符、视频项置灰禁用。

**Architecture:** 把 wire 协议从 chapter 专用（`CMD_BOOK_CHAPTER_REQ/CHUNK`）泛化为通用 `CMD_API_REQ`（带 Endpoint 路由）+ `CMD_JSON_CHUNK`（任意 JSON 字节回传）。server 端 `ChapterProvider` 泛化为 `ApiProvider`，按 endpoint 路由到各业务方法。Android 端 `fetchChapterBlocks` 泛化为 `fetchJson(endpoint)`，`MediaRepository` 的 3 个列表接口走统一 `bleFetchOrHttp` failover；Coil 图片降级时短路占位；视频项 UI 置灰 + 点击拦截。

**Tech Stack:** Go (Echo v4), Kotlin (Coroutines / StateFlow / Jetpack Compose / Coil3), Android BLE GATT.

## Global Constraints

- **Spec source of truth:** `docs/superpowers/specs/2026-07-28-ble-list-image-video-extension-design.md`
- **物理帧头不变（3 字节，向下兼容）:** `[version 0x01][uint16 BE length][payload]`。
- **Wire 不兼容变更被接受:** `0x11` payload 第 2 字节从 ChapterIndex 高字节改为 Endpoint（功能同批发布，无旧客户端）。`0x12` 格式不变，`TotalBlocks` 字段语义改为 `TotalBytes`。
- **Per-chunk ≤ 200 字节** (spec §1.2)。所有多字节字段 BIG-ENDIAN。
- **零回归:** Wi-Fi 正常时 100% HTTP；BLE 仅 IOException 触发；`HttpStatusException` 不降级；BLE 未连接不降级。
- **不覆盖:** search / 标签接口；图片字节（缩略图/原图/章节图）；视频流；大列表分页。
- **Endpoint 枚举:** `0x01 BOOK_CHAPTER` / `0x02 FOLDERS` / `0x03 BROWSE_FOLDER` / `0x04 BOOK_INFO`。

---

### Task 1: Go 协议层通用化（CMD_API_REQ + Endpoint + CMD_JSON_CHUNK）

**Files:**
- Modify: `server/internal/ble/protocol.go`
- Modify: `server/internal/ble/protocol_test.go`

**Interfaces:**
- Consumes: 现有 `EncodeFrame`/`DecodeFrame`/`maxChunkBytes`/`maxPayloadLen`
- Produces: `CmdApiReq`/`CmdJsonChunk` (CmdID 常量重命名), `Endpoint*` 常量, `EncodeApiReqPayload(endpoint byte, path string, index int) ([]byte, error)`, `DecodeApiReqPayload(payload []byte) (endpoint byte, path string, index int, err error)`, `EncodeJsonChunkPayload(totalChunks, chunkIndex, totalBytes int, chunk []byte) []byte`, `DecodeJsonChunkPayload(payload []byte) (totalChunks, chunkIndex, totalBytes int, chunk []byte, err error)`, `ChunkJsonBytes(jsonBytes []byte) ([][]byte, int, error)`

- [ ] **Step 1: Write failing protocol unit test for CMD_API_REQ encode/decode across endpoints**

```go
// server/internal/ble/protocol_test.go
func TestApiReqFramingAllEndpoints(t *testing.T) {
	cases := []struct {
		name     string
		endpoint byte
		path     string
		index    int
	}{
		{"folders no path", EndpointFolders, "", 0},
		{"browse with path", EndpointBrowseFolder, "/books/novel.txt", 0},
		{"book chapter path+index", EndpointBookChapter, "/books/novel.txt", 42},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			payload, err := EncodeApiReqPayload(tc.endpoint, tc.path, tc.index)
			if err != nil {
				t.Fatalf("encode err=%v", err)
			}
			if payload[0] != byte(CmdApiReq) {
				t.Fatalf("cmdID=%x want %x", payload[0], CmdApiReq)
			}
			ep, path, idx, err := DecodeApiReqPayload(payload)
			if err != nil {
				t.Fatalf("decode err=%v", err)
			}
			if ep != tc.endpoint || path != tc.path || idx != tc.index {
				t.Fatalf("got ep=%x path=%s idx=%d", ep, path, idx)
			}
		})
	}
}

func TestApiReqRejectsPathLongerThan255(t *testing.T) {
	longPath := strings.Repeat("a", 256)
	_, err := EncodeApiReqPayload(EndpointBookInfo, longPath, 0)
	if err != ErrPathTooLong {
		t.Fatalf("expected ErrPathTooLong got %v", err)
	}
}

func TestJsonChunkRoundTripPreservesBytes(t *testing.T) {
	jsonBytes := []byte(`[{"type":"t"},{"type":"x"}]`)
	frames, totalBytes, err := ChunkJsonBytes(jsonBytes)
	if err != nil {
		t.Fatalf("chunk err=%v", err)
	}
	if totalBytes != len(jsonBytes) {
		t.Fatalf("totalBytes=%d want %d", totalBytes, len(jsonBytes))
	}
	var reassembled []byte
	for i, fr := range frames {
		tc, ci, tb, chunk, derr := DecodeJsonChunkPayload(fr[3:]) // strip physical header
		if derr != nil {
			t.Fatalf("frame %d decode err=%v", i, derr)
		}
		if ci != i || tb != totalBytes {
			t.Fatalf("frame %d ci=%d tb=%d", i, ci, tb)
		}
		_ = tc
		reassembled = append(reassembled, chunk...)
	}
	if !bytes.Equal(reassembled, jsonBytes) {
		t.Fatalf("reassembled mismatch")
	}
	for _, fr := range frames {
		if len(fr) > maxChunkBytes {
			t.Fatalf("frame exceeds 200B cap: %d", len(fr))
		}
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test ./internal/ble -run 'TestApiReq|TestJsonChunk'`
Expected: FAIL with undefined `CmdApiReq`/`EndpointFolders`/`EncodeApiReqPayload`/etc.

- [ ] **Step 3: Implement generalized protocol in protocol.go**

Rename existing constants and add new ones. Replace `CmdBookChapterReq`→`CmdApiReq`, `CmdBookChapterChunk`→`CmdJsonChunk`. Add Endpoint constants. Replace `EncodeBookChapterReqPayload`/`DecodeBookChapterReqPayload` with `EncodeApiReqPayload`/`DecodeApiReqPayload` (layout `[CmdID][Endpoint][PathLen][Path][Index 2B BE]`). Replace `EncodeBookChapterChunkPayload`/`DecodeBookChapterChunkPayload`/`ChunkChapterBlocks` with `EncodeJsonChunkPayload`/`DecodeJsonChunkPayload`/`ChunkJsonBytes` (rename `totalBlocks` param to `totalBytes`; chunk layout unchanged).

Exact payload layout for `EncodeApiReqPayload`:
- byte 0: `CmdApiReq (0x11)`
- byte 1: `endpoint`
- byte 2: `pathLen` (1 byte; `len(path) > 255` → `(nil, ErrPathTooLong)`)
- bytes 3..3+pathLen: path UTF-8
- bytes 3+pathLen..+2: `index` as uint16 BE

`DecodeApiReqPayload`: validate `payload[0] == CmdApiReq`, min length 5 (`1+1+1+0+2`), read fields; return `endpoint, path, index, err`.

`ChunkJsonBytes(jsonBytes []byte)`: identical algorithm to existing `ChunkChapterBlocks` but input is raw JSON bytes (not `[]bookparser.Block`), so `json.Marshal` step is removed — chunk the bytes directly. `maxChunk := min(maxPayloadLen, maxChunkBytes) - chunkFixedOverhead` (existing constant). Returns `(frames [][]byte, totalBytes int, err error)` where `totalBytes = len(jsonBytes)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && go test ./internal/ble -run 'TestApiReq|TestJsonChunk'`
Expected: PASS

- [ ] **Step 5: Update central.go references (compile fix only, no logic change yet)**

`central.go` still references old names (`CmdBookChapterReq`, `ServeChapterRequest`, `ChapterProvider`). These are refactored in Task 2. For now, add temporary aliases so the package compiles:
```go
// Deprecated aliases — removed in Task 2 after ApiProvider lands.
const CmdBookChapterReq = CmdApiReq
const CmdBookChapterChunk = CmdJsonChunk
```
Run: `cd server && go build ./internal/ble`
Expected: builds clean.

- [ ] **Step 6: Commit Task 1**

```bash
git add server/internal/ble/protocol.go server/internal/ble/protocol_test.go server/internal/ble/central.go
git commit -m "feat(ble): generalize wire protocol to CMD_API_REQ + Endpoint routing and CMD_JSON_CHUNK"
```

---

### Task 2: Go ApiProvider 接口 + server 端路由 + listener 改造

**Files:**
- Modify: `server/internal/ble/central.go`
- Create: `server/internal/ble/api_provider.go`
- Modify: `server/internal/server/server.go` (wiring)
- Test: `server/internal/ble/central_chapter_test.go` (extend) + `server/internal/ble/api_provider_test.go` (create)

**Interfaces:**
- Consumes: Task 1 `DecodeApiReqPayload`/`ChunkJsonBytes`/`Endpoint*`; existing `BookService.GetChapterBlocks(ctx, path, idx, ip)`; `service` package folder/book-info logic
- Produces: `type ApiProvider interface { HandleBleRequest(ctx context.Context, endpoint byte, path string, index int) ([]byte, error) }`, `(*Central).SetApiProvider(ApiProvider)`, `(*Central).RunApiListener(ctx)` (replaces RunChapterListener), `(*Central).ServeApiRequest(ctx, notifyPayload, clientIP) (int, error)`

- [ ] **Step 1: Write failing test for ApiProvider routing (stub) + ServeApiRequest dispatch**

```go
// server/internal/ble/api_provider_test.go
package ble

import (
	"context"
	"errors"
	"testing"
)

type stubApiProvider struct {
	calls    []struct{ ep byte; path string; idx int }
	response []byte
	err      error
}

func (s *stubApiProvider) HandleBleRequest(ctx context.Context, endpoint byte, path string, index int) ([]byte, error) {
	s.calls = append(s.calls, struct{ ep byte; path string; idx int }{endpoint, path, index})
	return s.response, s.err
}

func TestServeApiRequestRoutesEndpointToProvider(t *testing.T) {
	stub := &stubApiProvider{response: []byte(`[{"name":"x"}]`)}
	c := newCentralWithProvider(stub) // helper; uses scriptedScanner like existing tests
	payload, _ := EncodeApiReqPayload(EndpointFolders, "", 0)
	n, err := c.ServeApiRequest(context.Background(), payload, "127.0.0.1")
	if err != nil {
		t.Fatalf("err=%v", err)
	}
	if len(stub.calls) != 1 || stub.calls[0].ep != EndpointFolders {
		t.Fatalf("provider not routed: %+v", stub.calls)
	}
	if n == 0 {
		t.Fatalf("expected chunks written, got 0")
	}
}

func TestServeApiRequestProviderErrorIsSurfaced(t *testing.T) {
	stub := &stubApiProvider{err: errors.New("boom")}
	c := newCentralWithProvider(stub)
	payload, _ := EncodeApiReqPayload(EndpointBookInfo, "/p", 0)
	_, err := c.ServeApiRequest(context.Background(), payload, "127.0.0.1")
	if err == nil {
		t.Fatalf("expected error surfaced")
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test ./internal/ble -run TestServeApiRequest`
Expected: FAIL (undefined `ApiProvider`/`ServeApiRequest`/`newCentralWithProvider`).

- [ ] **Step 3: Implement ApiProvider interface and refactor central.go**

In `central.go`:
- Replace `type ChapterProvider interface{...}` with:
  ```go
  type ApiProvider interface {
      HandleBleRequest(ctx context.Context, endpoint byte, path string, index int) ([]byte, error)
  }
  ```
- Rename `ErrNoChapterProvider` → `ErrNoApiProvider` ("ble: API provider not configured").
- Rename `c.chapters ChapterProvider` field → `c.apiProvider ApiProvider`.
- Rename `SetChapterProvider` → `SetApiProvider(p ApiProvider)`.
- Replace `ServeChapterRequest` with `ServeApiRequest(ctx, notifyPayload, clientIP) (int, error)`: decode via `DecodeApiReqPayload` → call `c.apiProvider.HandleBleRequest(ctx, endpoint, path, index)` → `ChunkJsonBytes(jsonBytes)` → loop `WriteCommand(EncodeFrame(EncodeJsonChunkPayload(...)))`. Return chunk count. Provider error → return it (no chunks written).
- Rename `RunChapterListener` → `RunApiListener(ctx)` (keep existing backoff + ctx-cancel logic from prior fix; only the dispatch call changes to `ServeApiRequest`).
- Remove the deprecated alias block from Task 1 Step 5.

- [ ] **Step 4: Implement concrete ApiProvider in api_provider.go**

```go
// server/internal/ble/api_provider.go
package ble

import (
	"context"
	"encoding/json"

	"github.com/localmediahub/server/internal/config"
	"github.com/localmediahub/server/internal/models"
	"github.com/localmediahub/server/internal/service"
)

// bleApiProvider adapts existing service/handler data-assembly logic into the
// BLE ApiProvider contract. It returns each endpoint's payload as raw JSON
// bytes (no echo.Context dependency). Logic mirrors the echo handlers in
// server/internal/server/handler/ — intentionally duplicated as a thin BLE
// adapter rather than refactoring handlers out of echo (scope control).
type bleApiProvider struct {
	cfg    *config.Config
	books  *service.BookService
	client string // injected per-request would be cleaner; for BLE the IP is the Central's, passed by ServeApiRequest via ctx or a field. For MVP use the Central's own IP.
}

// Endpoint constants (spec §2.3).
const (
	EndpointBookChapter  byte = 0x01
	EndpointFolders      byte = 0x02
	EndpointBrowseFolder byte = 0x03
	EndpointBookInfo     byte = 0x04
)

func NewBleApiProvider(cfg *config.Config, books *service.BookService) ApiProvider {
	return &bleApiProvider{cfg: cfg, books: books}
}

func (p *bleApiProvider) HandleBleRequest(ctx context.Context, endpoint byte, path string, index int) ([]byte, error) {
	switch endpoint {
	case EndpointBookChapter:
		blocks, err := p.books.GetChapterBlocks(ctx, path, index, "")
		if err != nil {
			return nil, err
		}
		return json.Marshal(blocks)
	case EndpointFolders:
		folders := make([]models.Folder, 0)
		for _, root := range p.cfg.Scan.GetRoots() {
			folders = append(folders, models.Folder{
				Name: root, Path: root, RelativePath: root, IsRoot: true,
			})
		}
		return json.Marshal(folders)
	case EndpointBrowseFolder:
		// Delegate to a shared helper introduced below (BrowseFolderData).
		entries, err := BrowseFolderData(p.cfg, path)
		if err != nil {
			return nil, err
		}
		return json.Marshal(entries)
	case EndpointBookInfo:
		book, err := p.books.GetBook(path)
		if err != nil {
			return nil, err
		}
		return json.Marshal(book)
	default:
		return nil, ErrUnknownEndpoint
	}
}
```

Add `var ErrUnknownEndpoint = errors.New("ble: unknown endpoint")`.

Note on `BrowseFolderData`: the existing `BrowseFolder` echo handler does path validation + directory walking inline. For the MVP BLE adapter, introduce a minimal `BrowseFolderData(cfg, path)` helper in `api_provider.go` that does `filepath.Walk` one level deep under the resolved root+path, returning `[]models.MediaFile`. If replicating the full handler logic (tag enrichment, sorting) is large, implement the minimal version (name/path/type) and note it as a known simplification in the report — the BLE browse list just needs to show file names + types so video items can be greyed.

- [ ] **Step 5: Run test to verify it passes**

Run: `cd server && go test ./internal/ble -run TestServeApiRequest`
Expected: PASS

- [ ] **Step 6: Wire ApiProvider in server.go**

In `server/internal/server/server.go`, replace the existing `SetChapterProvider(...)` call with `bleCentral.SetApiProvider(ble.NewBleApiProvider(cfg, bookService))`. Keep `RunApiListener` goroutine launch + `bleListenerCancel` in `Stop()` (rename `RunChapterListener` → `RunApiListener`).

Run: `cd server && go build ./... && go vet ./...`
Expected: clean.

- [ ] **Step 7: Commit Task 2**

```bash
git add server/internal/ble/central.go server/internal/ble/api_provider.go server/internal/ble/api_provider_test.go server/internal/ble/central_chapter_test.go server/internal/server/server.go
git commit -m "feat(ble): add ApiProvider routing for folders/browse/bookInfo and rename listener"
```

---

### Task 3: Kotlin BleProtocol + BleController + BleTransportFallback 通用化

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ble/BleProtocol.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ble/BleController.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ble/BleTransportFallback.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/ble/BleControllerTest.kt` (extend), `BleTransportFallbackTest.kt` (extend)

**Interfaces:**
- Consumes: Task 1 wire format (Endpoint layout `[0x11][endpoint][pathLen][path][index 2B BE]`)
- Produces: `BleProtocol.CMD_API_REQ`/`CMD_JSON_CHUNK`/`ENDPOINT_*`, `BleController.requestApi(endpoint, path, index): Boolean`, `BleTransportFallback.fetchJson(endpoint, path, index): String?`

- [ ] **Step 1: Write failing test for requestApi wire layout (parity with Go)**

```kotlin
// android/app/src/test/java/com/juziss/localmediahub/ble/BleControllerTest.kt
@Test
fun requestApi_emitsGoSpecApiReqLayout() {
    val fake = SimulatingPeripheralManager(...)  // existing fake; captures notified bytes
    val controller = BleController(fake, BleTransportFallback(), ...)
    controller.requestApi(BleProtocol.ENDPOINT_BROWSE_FOLDER, "/books/n", 7)
    val sent = fake.lastNotifiedFrame  // raw frame bytes
    // [ver][len2][0x11][endpoint=0x03][pathLen=8][path][index 2B BE = 0x0007]
    assertEquals(0x11.toByte(), sent[3])
    assertEquals(BleProtocol.ENDPOINT_BROWSE_FOLDER, sent[4])
    assertEquals(8, sent[5].toInt() and 0xFF)
    assertEquals(0, sent[6 + 8].toInt() and 0xFF)      // index high
    assertEquals(7, sent[6 + 8 + 1].toInt() and 0xFF)  // index low
}

@Test
fun requestApi_rejectsPathLongerThan255() {
    val longPath = "a".repeat(256)
    val ok = controller.requestApi(BleProtocol.ENDPOINT_BOOK_INFO, longPath, 0)
    assertFalse(ok)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests "*BleControllerTest.requestApi*"`
Expected: FAIL (undefined `requestApi`/`ENDPOINT_BROWSE_FOLDER`).

- [ ] **Step 3: Generalize BleProtocol.kt**

Rename `CMD_BOOK_CHAPTER_REQ`→`CMD_API_REQ (0x11)`, `CMD_BOOK_CHAPTER_CHUNK`→`CMD_JSON_CHUNK (0x12)`. Add:
```kotlin
const val ENDPOINT_BOOK_CHAPTER: Byte = 0x01
const val ENDPOINT_FOLDERS: Byte = 0x02
const val ENDPOINT_BROWSE_FOLDER: Byte = 0x03
const val ENDPOINT_BOOK_INFO: Byte = 0x04
```

- [ ] **Step 4: Generalize BleController.requestApi**

Replace `requestChapter(path, index)` with:
```kotlin
fun requestApi(endpoint: Byte, path: String, index: Int): Boolean {
    val pathBytes = path.toByteArray(Charsets.UTF_8)
    if (pathBytes.size > 0xFF) return false
    val payload = ByteArray(1 + 1 + 1 + pathBytes.size + 2)
    var p = 0
    payload[p++] = BleProtocol.CMD_API_REQ
    payload[p++] = endpoint
    payload[p++] = (pathBytes.size and 0xFF).toByte()
    System.arraycopy(pathBytes, 0, payload, p, pathBytes.size); p += pathBytes.size
    payload[p++] = ((index shr 8) and 0xFF).toByte()
    payload[p++] = (index and 0xFF).toByte()
    return peripheralManager.notifyPayload(BleProtocol.encodeFrame(payload))
}
```
Update the `setOnPayloadReceived` routing check: `payload[0] == BleProtocol.CMD_JSON_CHUNK` (renamed).

- [ ] **Step 5: Generalize BleTransportFallback.fetchJson**

Replace `fetchChapterBlocks(path, index, dispatch)` with:
```kotlin
suspend fun fetchJson(endpoint: Byte, path: String = "", index: Int = 0): String? {
    // Same suspend-bridge as before: reset → register completionHook →
    // dispatch requestApi(endpoint, path, index) → await reassembled bytes
    // via withTimeoutOrNull → return UTF-8 string (or null on timeout).
    // completionHook completes the deferred with the reassembled ByteArray;
    // here we decode it as a UTF-8 String instead of Gson-deserializing
    // (the caller does Gson).
}
```
The existing `onFrameReceived` reassembly logic stays; only the entry point signature + return type change (ByteArray → String). Keep `synchronized(stateLock)` discipline.

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests "*BleControllerTest" --tests "*BleTransportFallbackTest"`
Expected: PASS. Update any existing tests that referenced old `fetchChapterBlocks`/`requestChapter` names to use the new API.

- [ ] **Step 7: Commit Task 3**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ble/ android/app/src/test/java/com/juziss/localmediahub/ble/
git commit -m "feat(ble): generalize Kotlin side to requestApi/fetchJson with endpoint routing"
```

---

### Task 4: MediaRepository 通用 failover（folders / browse / bookInfo / chapter）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/data/MediaRepositoryFailoverTest.kt` (extend)

**Interfaces:**
- Consumes: Task 3 `fetchJson(endpoint, path, index)`; `bleController.connectionState`
- Produces: `getFolders`/`browseFolder`/`getBookInfo`/`getBookChapter` all failover-capable via shared `bleFetchOrHttp`

- [ ] **Step 1: Write failing tests for list failover (CONNECTED→BLE-served; DISCONNECTED→error)**

```kotlin
// android/app/src/test/java/com/juziss/localmediahub/data/MediaRepositoryFailoverTest.kt
@Test
fun getFolders_fallsBackToBleWhenHttpFailsAndBleConnected() = runTest {
    // http throws IOException (port 1 unreachable); bleController CONNECTED;
    // SimulatingPeripheralManager posts JSON chunks for endpoint FOLDERS async.
    // Assert: result is Success<List<Folder>> + isBleDegraded true + 1 event.
}

@Test
fun browseFolder_fallsBackToBle() = runTest { /* endpoint BROWSE_FOLDER, path */ }

@Test
fun getBookInfo_fallsBackToBle() = runTest { /* endpoint BOOK_INFO, path */ }

@Test
fun getFolders_doesNotFailOverWhenBleDisconnected() = runTest { /* error, no degrade */ }
```
Reuse the existing async fake `SimulatingPeripheralManager` (posts chunks via `scope.launch { delay(10); cb(chunk) }`); extend it to build `CMD_API_REQ`/`CMD_JSON_CHUNK` frames for the requested endpoint, decoding the request via the Go-spec layout (parity).

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests "*MediaRepositoryFailoverTest.getFolders*"`
Expected: FAIL (no failover on getFolders yet).

- [ ] **Step 3: Implement bleFetchOrHttp and rewire 4 endpoints**

```kotlin
private suspend fun <T> bleFetchOrHttp(
    httpCall: suspend () -> T,
    endpoint: Byte,
    path: String = "",
    index: Int = 0,
    type: java.lang.reflect.Type,
): NetworkResult<T> = try {
    NetworkResult.Success(httpCall())
} catch (e: HttpStatusException) {
    NetworkResult.Error("Server returned ${e.code}", e.code)
} catch (e: IOException) {
    if (bleController.connectionState.value != BleConnState.CONNECTED) {
        NetworkResult.Error(e.toUserMessage())
    } else {
        val json = bleTransportFallback.fetchJson(endpoint, path, index)
        if (json == null) {
            NetworkResult.Error(e.toUserMessage())
        } else {
            try {
                val parsed = gson.fromJson(json, type)
                _isBleDegraded.value = true
                bleDegradedEvents.tryEmit(Unit)
                NetworkResult.Success(parsed)
            } catch (parseErr: Exception) {
                NetworkResult.Error(e.toUserMessage())
            }
        }
    }
}
```

Rewire:
- `getFolders`: `bleFetchOrHttp({ httpGetRaw(".../folders", type) }, ENDPOINT_FOLDERS, "", 0, foldersType)`
- `browseFolder`: `bleFetchOrHttp({ httpGetRaw(".../folders/$path/browse", type) }, ENDPOINT_BROWSE_FOLDER, path, 0, mediaListType)`
- `getBookInfo`: `bleFetchOrHttp({ httpGetRaw(".../books/info?path=$path", type) }, ENDPOINT_BOOK_INFO, path, 0, Book::class.java)`
- `getBookChapter`: `bleFetchOrHttp({ httpGetRaw(".../books/chapter?...", type) }, ENDPOINT_BOOK_CHAPTER, path, index, chapterType)` — the chapter-specific Block list handling stays inside via the same JSON path.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests "*MediaRepositoryFailoverTest"`
Expected: PASS (all failover cases).

- [ ] **Step 5: Commit Task 4**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt android/app/src/test/java/com/juziss/localmediahub/data/MediaRepositoryFailoverTest.kt
git commit -m "feat(repo): extend BLE failover to folders/browse/bookInfo via shared bleFetchOrHttp"
```

---

### Task 5: 图片占位（Coil 降级短路）+ 视频项置灰禁用

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/LocalMediaHubApplication.kt` (Coil interceptor)
- Modify: the media list item Composable that renders `MediaFile` rows (locate via grep for the video/image type check)
- Test: Compose test for video-item greyed + click intercepted (extend an existing list Compose test or add `BleVideoItemTest.kt`)

**Interfaces:**
- Consumes: `MediaRepository.isBleDegraded: StateFlow<Boolean>` (already exists); `MediaFile` type field
- Produces: Coil image loader short-circuits to placeholder when degraded; video list item `enabled = !isBleDegraded` + click shows Snackbar

- [ ] **Step 1: Locate the media-file list item Composable and MediaFile type field**

Run: `grep -rn "isVideo\|mediaType\|file.type\|MediaFile" android/app/src/main/java/com/juziss/localmediahub/ui/ | head`
Identify the Composable that renders a row and how it distinguishes video items. Record the file path + line.

- [ ] **Step 2: Write failing Compose test for video-item disabled in degraded mode**

```kotlin
// android/app/src/test/java/com/juziss/localmediahub/ui/.../VideoItemDegradedTest.kt
@Test
fun videoItem_isGreyedAndNonClickable_whenBleDegraded() {
    // Set repo.isBleDegraded = true; render the list item with a video MediaFile.
    // Assert: item alpha < 1f (or a greyed modifier is applied) and click does
    // not invoke the open callback (or invokes a "show snackbar" path).
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests "*VideoItemDegradedTest*"`
Expected: FAIL.

- [ ] **Step 4: Implement Coil placeholder short-circuit**

In `LocalMediaHubApplication.newImageLoader`, add an `Interceptor` (Coil3) that reads the global `isBleDegraded` state (expose it via a singleton holder or `MediaRepository.isBleDegraded` accessed through Hilt). When degraded, short-circuit the chain to return a placeholder drawable (`R.drawable.ble_placeholder` or a built-in resource) without executing the network fetch. When not degraded, proceed normally. Ensure the interceptor reads the current StateFlow value (not a stale snapshot).

- [ ] **Step 5: Implement video-item greyed + click interceptor**

In the list item Composable (from Step 1):
- Collect `isBleDegraded` (via the VM exposing `MediaRepository.isBleDegraded`).
- For video items: wrap the row in `Modifier.alpha(if (isBleDegraded) 0.4f else 1f)` and make the click handler: `if (isBleDegraded) showSnackbar("BLE 模式下暂不支持播放视频") else openVideo(file)`.
- Non-video items remain clickable.

- [ ] **Step 6: Run tests + full build**

Run: `cd android && ./gradlew testDebugUnitTest --tests "*VideoItemDegradedTest" && ./gradlew assembleDebug`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 7: Commit Task 5**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/LocalMediaHubApplication.kt android/app/src/main/java/com/juziss/localmediahub/ui/ android/app/src/test/java/com/juziss/localmediahub/ui/
git commit -m "feat(ui): grey out + disable video items and placeholder images in BLE degraded mode"
```

---

## Self-Review Notes

- **Spec coverage:** §1.1 endpoints → Task 1+2 (Go) + Task 3+4 (Kotlin). §1.3 UX (list, video grey, image placeholder) → Task 4+5. §2 protocol → Task 1. §3 components → Tasks 1-5. §4 error/timeout → reused from existing implementation (Task 4's `bleFetchOrHttp` mirrors existing chapter failover semantics). §5 testing → each task's tests.
- **Type consistency:** `Endpoint*` byte values (0x01-0x04) match across Go (Task 1 constants + Task 2 provider) and Kotlin (Task 3 BleProtocol). `fetchJson(endpoint, path, index)` signature matches between BleController.requestApi dispatch (Task 3) and MediaRepository caller (Task 4). `ApiProvider.HandleBleRequest(ctx, endpoint, path, index) ([]byte, error)` matches Task 2 impl + test stub.
- **Cross-side wire parity:** Task 1 Go encode + Task 3 Kotlin encode both build `[0x11][endpoint][pathLen][path][index 2B BE]`; both sides have a byte-parity test. This is the load-bearing invariant.
- **Known simplification flagged:** Task 2 Step 4 `BrowseFolderData` may implement a minimal browse (name/path/type only) vs the full echo handler; implementer notes it in the report if they reduce scope.
