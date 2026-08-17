# Task 11 Report: BLE 选路/日志/路径加固（H-1d / M-6 / M-8）

**Commit**: `e359a13` — `fix(ble): exact UUID match, log redaction and hardened browse path (Phase 9)`
**Branch**: master（直接提交）
**Date**: 2026-08-17

---

## 做了什么

### 1. UUID 精确匹配（H-1d）

**UUID 匹配函数落点**：新文件 `server/internal/ble/uuid_match.go`（**无 build tag**，两种构建共享），
包含 brief Step 3 原文的两个函数：

- `normalizeUUIDString(s string) string` — 去 `-` + lowercase
- `hasServiceUUIDMatch(uuids []string) bool` — 仅接受 ≥32 hex 字符且归一化后与 `ServiceUUID` 完全相等的完整 128-bit UUID；16-bit 短 UUID（`"ffff"`）与前缀形态（`"fa6a3001-8b2c"`）一律不匹配

`central_adapter.go`（bluetooth tag）改动：

- **Scan 回调**（原 :96-102 区域）：删除旧 `hasServiceUUIDMatch(d bluetooth.ScanResult, targetUUID, serviceUUIDStr)`
  （含 exact / 4-char short / 8-char prefix 三分支的宽松版），改为
  `hasUUID := d.HasServiceUUID(uuid) || hasServiceUUIDMatch(uuidStrs)`。
  保留 `d.HasServiceUUID` 的依据：查证 vendored fork（`server/third_party/bluetooth`，`replace` 指向本地），
  `advertisementFields.HasServiceUUID`（gap.go:227，Windows 走此路径）是 `u == uuid` 的 16 字节结构体全等比较，
  `rawAdvertisementPayload.HasServiceUUID`（gap.go:309，HCI 路径）是 128-bit 字节精确比较——
  两者都只可能精确命中，与"仅完整 128-bit 精确相等"语义一致，且对 `UUID.String()` 平台格式差异免疫。
- **`matchUUIDPrefix`**（GATT service/char 发现，connectLocked 内 3 处调用点，原 :367-377/:400-411）：
  删除 8 字符前缀分支，改为 `normalizeUUIDString(u.String()) == normalizeUUIDString(targetUUIDStr)`
  （归一化精确相等）。函数名保留以减小 churn，注释已说明历史名与收紧原因。
- 移除不再使用的 `strings` import。

### 2. BLE 日志脱敏（M-6）

`central_adapter.go` 三处：

- **扫描逐条日志**（原 :96-102）：原对每个附近设备输出 `addr/name/rssi/hasServiceUUID/serviceUUIDs`
  （路人设备的名称/RSSI/完整 UUID 列表 = 旁观者隐私数据）。现：未命中设备完全不记日志；
  命中设备仅记 `slog.Info("BLE scan hit", "addr", ...)`（只输出地址）。
- **扫描结束日志**（timeout / err 两条路径）：在原有 `matched` 命中数之外补充 `matchedAddrs`
  （命中设备地址列表）——满足"只输出命中数与命中设备地址"。
- **WaitNotify 回调日志**（原 :319 附近，实际在 :353）：`data=%x` 原始十六进制转储删除
  （帧内容携带 auth nonce/MAC），改为 `len=%d frameType=%#02x`（帧首字节 = 版本 0x01 v1 / 0x02 v2），
  len=0 时单独记 `len=0` 防越界。

### 3. BLE browse 路径校验对齐 HTTP 端（M-8）

`server/internal/ble/api_provider.go` `BrowseFolderData`（原 :162-177）：

- 原 `service.NormalizePath` + `service.IsPathWithinRoots`（纯词法校验）→
  `resolved, err := service.ResolveBrowsePath(path, cfg.Scan.GetRoots())`，错误即拒绝，后续 `os.Stat`/`os.ReadDir`
  均用 `resolved`。
- 与 HTTP `BrowseFolder` handler（`server/internal/server/handler/folders.go:105`）同一安全边界：
  UNC 拒绝 + root 边界内 + root 以下任何 reparse point（junction/symlink）拒绝。
  修复了词法校验放行后 `os.ReadDir` 跟随 junction 泄漏库外目录列表的缺口。
- 文档注释同步更新。

### 4. Android 选路 fail-closed（H-1d）

`android/app/src/main/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModel.kt`：

- :257（实际行号）`selectBestDevice(...) ?: discovered.first()` → 去掉 `?: discovered.first()` 兜底；
  null 时 `controller.markDisconnected()` +（非 silent）`_errorText.value = s(R.string.ble_err_no_match)`
  + `return false`，不发起 `api.connect`。
- 三级匹配器（历史 MAC → 设备名 → RSSI 最强）保留；`selectBestDevice` 内部冗余的
  `?: discovered.firstOrNull()`（`maxByOrNull` 对非空列表必非空，纯死代码）一并移除。
- **RSSI 兜底 UUID 命中确认**（coordinator 消歧点）：Android 端设备列表来自 PC 的
  `/api/v1/ble/scan`，`selectBestDevice` 内无任何 UUID 过滤逻辑（确认过源码，只有 MAC/name/RSSI），
  UUID 过滤完全在 PC 端完成——Go 侧收紧为精确匹配后，扫描列表本身已只含 UUID 命中设备，
  RSSI 一档必然落在命中设备上，语义一致。
- 新增字符串资源 `ble_err_no_match` = "未找到匹配的 LocalMediaHub 设备"
  （`android/app/src/main/res/values/strings.xml`，沿用项目本地化约定而非 brief 示意的硬编码字面量；
  这是 brief add 清单之外唯一多出的文件）。

### 5. 测试

**Go**（新增/修改）：

- `server/internal/ble/uuid_match_test.go`（新，**无 tag**，两种构建都跑）：
  `TestUUIDMatchRequiresFullExactServiceUUID`。⚠️ 对 brief Step 1 原文有一处必要修正，见"偏差与决策"。
- `server/internal/ble/api_provider_test.go`：
  - `TestBleApiProviderRejectsBrowsePathOutsideRoots`：`root/../..` / `../../etc` / `\\server\share\media`
    三形态经 `HandleBleRequest(EndpointBrowseFolder)` 全部返回错误而非列表（构造参照既有
    `TestBleApiProviderRejectsBookPathOutsideRoots`，roots=t.TempDir()）。
  - `TestBrowseFolderDataRejectsJunction`：Windows junction（`cmd /c mklink /J`，免管理员权限，
    构造方式镜像 `service/path_test.go` 的 `TestResolveWithinRootsRejectsJunction`）：
    junction 本身与穿越 junction 的路径都必须被拒。**该测试在旧实现下行为级 FAIL（ReadDir 跟随
    junction 返回了库外列表），新实现下 PASS** —— 红绿两端都验证过。

**Android**（`BleSettingsViewModelTest.kt`）：

- `selectBestDevice_returnsNullForEmptyList` — 固化空列表→null 的 fail-closed 契约。
- `doAutoConnectOnce_noMatchingDevice_doesNotAttemptConnect` — 扫描成功但无可选目标：
  不发起任何 `api.connect`（FakeApi 新增 `connectCallCount`）、非静默报用户可读错误、状态保持 ADVERTISING。
- `doAutoConnectOnce_silentNoMatch_keepsErrorTextClean` — 静默路径同样不连接且不污染 errorText。
- FakeApi fixture 扩展：`overrideScanResult`（注入空列表成功响应）+ `connectCallCount`。

---

## 测试结果

| 验证命令 | 结果 |
|---|---|
| `cd server && go test ./internal/ble/ -v` | **PASS**（全部用例，含新增 3 个） |
| `cd server && go test -tags bluetooth ./internal/ble/` | **PASS**（9.4s，tagged 构建 + winrt adapter 测试） |
| `cd server && go build ./...` | OK |
| `cd server && go build -tags bluetooth ./...` | OK |
| `cd server && go vet ./...` + `go vet -tags bluetooth ./...` | OK（后者覆盖 tagged 测试文件编译） |
| `cd server && go test ./...` | 仅 bookparser `TestParseUserNovel` 失败 = **既有基线例外**（coordinator 已声明忽略），其余全绿 |
| `cd android && ./gradlew testDebugUnitTest` | **BUILD SUCCESSFUL**；BleSettingsViewModelTest 20 用例 0 失败（XML: tests=20 failures=0 errors=0 skipped=0） |

TDD 过程证据：Step 2 时 `TestBrowseFolderDataRejectsJunction` 对旧实现行为级 FAIL（"expected junction
under root to be rejected"）；`TestUUIDMatchRequiresFullExactServiceUUID` 以编译失败（undefined:
hasServiceUUIDMatch）进入红态。

---

## Self-Review / 偏差与决策

1. **brief Step 1 测试与 Step 3 实现存在内部矛盾（已按 Interfaces 语义裁决）**：
   brief 测试原文的 must-NOT-match 输入 `"FA6A3001-8B2C-4E6F-9988-123456789ABC"` 恰是 `ServiceUUID`
   的大小写变体；而 brief Interfaces 明文"归一化：去 `-`、lowercase 后与 `ServiceUUID` 比较"、
   Step 3 实现 snippet 也用 `strings.ToLower`——按此归一化该串**必须**命中。二者不可同时满足。
   裁决：以 Interfaces + 实现 snippet 为准（保留 lowercase 归一化，符合 RFC 4122 hex 大小写不敏感惯例；
   且匹配器输入实际来自 `UUID.String()` 恒为小写，大小写处理无安全影响），测试的该输入改为真正不匹配的
   `"FA6A3001-8B2C-4E6F-9988-123456789ABD"`（末位不同），并新增正向断言"大小写变体的精确 UUID
   归一化后必须命中"以钉死归一化语义。真正的前缀攻击输入 `"fa6a3001-8b2c"` 原样保留。
   测试文件内已附 NOTE 注明此裁决。
2. **UUID 匹配函数落点**：按 coordinator 提示，纯字符串版 `hasServiceUUIDMatch`/`normalizeUUIDString`
   放入新文件 `uuid_match.go`（无 build tag），测试放 `uuid_match_test.go`（无 tag）——而不是 brief
   字面的 `central_adapter_test.go`（该文件有 `//go:build bluetooth`，无 tag 构建下不会编译执行）。
   两种构建共享同一实现与测试；tagged 构建下 `go vet -tags bluetooth` + `go test -tags bluetooth` 均验证过。
3. **扫描回调保留 `d.HasServiceUUID(uuid)`**：fork 源码查证为 128-bit 精确比较（非宽松匹配），
   与收紧后语义一致；保留它作为平台格式差异下的位级精确快路径。若未来 fork 行为变化需复查。
4. **Android null 分支可达性**：`selectBestDevice` 三级匹配对非空列表恒非空（RSSI 档必命中），
   故调用点 null 守卫当前为防御性保险（为未来匹配器收紧兜底）。VM 级测试因此用"空扫描列表 → 不发起
   connect"覆盖 fail-closed 行为，`selectBestDevice_returnsNullForEmptyList` 固化契约；这与
   brief Interfaces"三级匹配保留 + 无匹配显示错误"的语义一致。
5. **`matchUUIDPrefix` 未改名**：brief 只要求删前缀分支；保留历史名（注释已更新为 exact-only 语义）
   以最小化 connectLocked 三处调用点 churn。名称略有误导是已知取舍。
6. **commit 追加文件**：brief Step 5 的 `git add` 清单不含 `strings.xml`，但 `R.string.ble_err_no_match`
   必需它（项目全部 BLE 错误文案都走资源本地化），已一并加入。
7. **未动认证协议代码**：`protocol.go` 等握手/鉴权代码零改动（uuid 匹配与日志均为外围改动）。
8. **gofmt/CRLF 噪声**：仓库 `core.autocrlf=true`，工作树 CRLF 是 checkout 伪影，git 提交时归一为 LF
   blob；新文件（uuid_match*.go）为 LF 且 gofmt-clean。`gofmt -l` 对 protocol.go 等未触碰文件报
   dirty 属 Go 1.19+ 注释格式的历史遗留，非本次引入。
9. **既有未跟踪文件**（`docs/superpowers/reviews/`、`tools/reformat_novels.py`）非本任务产物，未提交。

## 变更文件清单

- `server/internal/ble/uuid_match.go`（新）
- `server/internal/ble/uuid_match_test.go`（新）
- `server/internal/ble/central_adapter.go`
- `server/internal/ble/api_provider.go`
- `server/internal/ble/api_provider_test.go`
- `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModel.kt`
- `android/app/src/main/res/values/strings.xml`
- `android/app/src/test/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModelTest.kt`
