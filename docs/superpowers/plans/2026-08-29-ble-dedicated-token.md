# BLE Dedicated Token Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the BLE control channel work while the server runs in open LAN auth mode, by introducing an independent BLE handshake key (`ble.token`) with `server.token` as backward-compatible fallback.

**Architecture:** Server resolves an effective BLE secret (`ble.token` → `server.token`) and feeds it to `Central.SetAuthToken()` plus the `/api/v1/ble/*` gate; Android mirrors the same precedence (`bleToken` → `authToken`) in the provider handed to `BleController`, with a new encrypted DataStore field, a BLE settings key input, and pairing (`POST /api/v1/pair`) distributing the dedicated key.

**Tech Stack:** Go (Echo v4), Kotlin (Compose, Hilt, DataStore), JUnit4 + Go test.

**Spec:** `docs/superpowers/specs/2026-08-29-ble-dedicated-token-design.md`

## Global Constraints

- Effective-secret rule is identical on both ends: `ble.token`（非空优先）→ `server.token`；Android: `bleToken`（非空优先）→ `authToken`。
- `ble.token` 属机密：不进 `ConfigPublic`、不进 access log、`config.example.yaml` 只放空占位（Mimosa 约束：示例/源码/测试不得含可用凭据字面量）。
- 既有 token 模式零回归：`ble.token` 为空时行为与现状完全一致。
- Commit 风格 Conventional Commits，scope 用 `ble` / `android` / `server` / `docs`。
- ⚠️ 工作区含用户未提交的 BLE WIP（`server.go`、`internal/ble/*` 等）——执行时只 `git add` 本计划明确列出的文件，禁止 `git add -A`；若本计划文件与 WIP 冲突，先停下向用户确认。

---

### Task 1: Server config — `ble.token` + `EffectiveToken`

**Files:**
- Modify: `server/internal/config/config.go`（`ServerConfig` 之后新增 `BLEConfig`；`Config` struct 增加 `BLE BLEConfig`；新增方法）
- Modify: `server/config.example.yaml`（新增 `ble:` 段）
- Test: `server/internal/config/config_test.go`

**Interfaces:**
- Produces: `type BLEConfig struct { Token string }`；`func (b BLEConfig) EffectiveToken(serverToken string) string`；`Config.BLE` 字段（yaml key `ble`）。

- [ ] **Step 1: 写失败测试** — 在 `config_test.go` 追加：

```go
func TestBLEEffectiveToken(t *testing.T) {
	cases := []struct{ ble, server, want string }{
		{"", "", ""},
		{"", "srv", "srv"},   // 回退：现状行为
		{"bt", "srv", "bt"},  // ble.token 优先
		{"bt", "", "bt"},     // 开放模式 + 专属 BLE 密钥
	}
	for _, c := range cases {
		if got := (BLEConfig{Token: c.ble}).EffectiveToken(c.server); got != c.want {
			t.Errorf("EffectiveToken(ble=%q, server=%q) = %q, want %q", c.ble, c.server, got, c.want)
		}
	}
}

func TestLoadConfigParsesBLESection(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "c.yaml")
	yaml := "server:\n  token: \"\"\nble:\n  token: abc123\n"
	if err := os.WriteFile(path, []byte(yaml), 0o644); err != nil {
		t.Fatal(err)
	}
	cfg, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.BLE.Token != "abc123" {
		t.Fatalf("cfg.BLE.Token = %q, want %q", cfg.BLE.Token, "abc123")
	}
	if got := cfg.BLE.EffectiveToken(cfg.Server.Token); got != "abc123" {
		t.Fatalf("effective = %q, want abc123", got)
	}
}
```

- [ ] **Step 2: 跑测试确认失败** — `cd server && go test ./internal/config/ -run 'TestBLE' -v`（编译失败：BLEConfig 未定义；`Load` 若为私有请改用实际加载函数名，以 `config.go` 现有导出为准）

- [ ] **Step 3: 实现** — `config.go` 在 `ServerConfig` 定义后加：

```go
// BLEConfig holds the optional dedicated BLE handshake key. When Token is
// empty the BLE channel falls back to ServerConfig.Token (pre-existing
// behavior); when ServerConfig.Token is also empty the BLE channel is
// disabled (Phase 9 / H-1a: no key material → no authenticated channel).
type BLEConfig struct {
	Token string `yaml:"token,omitempty" json:"token,omitempty"`
}

// EffectiveToken resolves the BLE handshake key source: ble.token first,
// server.token as fallback. Both ends (server Central + Android BleProtocol)
// must apply the same precedence.
func (b BLEConfig) EffectiveToken(serverToken string) string {
	if b.Token != "" {
		return b.Token
	}
	return serverToken
}
```

并在 `Config` struct 中（`Server ServerConfig` 邻近处）加 `BLE BLEConfig \`yaml:"ble" json:"ble"\``（struct tag 以现有字段风格为准）。

- [ ] **Step 4: 跑测试确认通过**

- [ ] **Step 5: `config.example.yaml` 加段**（空占位 + 注释说明生成强随机值，如 `openssl rand -hex 32`）

- [ ] **Step 6: Commit** — `git add server/internal/config/config.go server/internal/config/config_test.go server/config.example.yaml && git commit -m "feat(ble): add ble.token config with server.token fallback"`

---

### Task 2: Server gate + wiring — requireBleToken 三态 + SetAuthToken 源

**Files:**
- Modify: `server/internal/server/server.go:143`（`SetAuthToken(cfg.Server.Token)` → 有效密钥）
- Modify: `server/internal/server/handler/ble.go`（`requireBleToken`）
- Test: `server/internal/server/handler/ble_test.go`

**Interfaces:**
- Consumes: `cfg.BLE.EffectiveToken(cfg.Server.Token)`（Task 1）
- Produces: gate 拒绝条件从 `cfg.Server.Token == ""` 改为 `cfg.BLE.EffectiveToken(cfg.Server.Token) == ""`；拒绝文案改为 `"ble unavailable: set ble.token (or server.token) to enable the BLE channel"`。

- [ ] **Step 1: 写失败测试** — `ble_test.go` 追加（沿用现有 handler 测试的构造方式，若无现成 harness 则直接构造 `Handler{cfg: &config.Config{...}}`）：

```go
func TestRequireBleTokenStates(t *testing.T) {
	cases := []struct {
		name       string
		ble, srv   string
		wantRefuse bool
	}{
		{"open auth no ble token", "", "", true},
		{"token mode fallback", "", "srv", false},
		{"dedicated ble token", "bt", "", false},
		{"both set uses ble", "bt", "srv", false},
	}
	for _, c := range cases {
		h := &Handler{cfg: &config.Config{
			Server: config.ServerConfig{Token: c.srv},
			BLE:    config.BLEConfig{Token: c.ble},
		}}
		err := h.requireBleToken(echo.New().NewContext(nil, nil))
		if (err != nil) != c.wantRefuse {
			t.Errorf("%s: refuse=%v, want %v", c.name, err != nil, c.wantRefuse)
		}
	}
}
```

- [ ] **Step 2: 确认失败**（ble="" srv="" 现在会被旧 gate 拒绝 ✓ 但 "dedicated ble token" 用例会失败——旧实现只看 server.token）

- [ ] **Step 3: 实现** — `ble.go` 的 `requireBleToken`：

```go
func (h *Handler) requireBleToken(c echo.Context) error {
	if h.cfg == nil || h.cfg.BLE.EffectiveToken(h.cfg.Server.Token) == "" {
		slog.Warn("BLE request refused: no BLE key configured (ble.token and server.token both empty); BLE channel disabled")
		return echo.NewHTTPError(http.StatusBadRequest,
			"ble unavailable: set ble.token (or server.token) to enable the BLE channel")
	}
	return nil
}
```

同步更新 `bleOpenAuthModeMessage` 常量与注释。`server.go` 的 `SetAuthToken(cfg.Server.Token)` 改为 `SetAuthToken(cfg.BLE.EffectiveToken(cfg.Server.Token))`。

- [ ] **Step 4: `cd server && go test ./internal/server/... ./internal/config/` 全绿**

- [ ] **Step 5: Commit** — `git add server/internal/server/handler/ble.go server/internal/server/handler/ble_test.go`（⚠️ `server.go` 含用户 WIP：只 `git add -p server/internal/server/server.go` 挑选本 hunk，或与用户确认后一并提交）

---

### Task 3: Server pairing — 开放模式分发 ble_token

**Files:**
- Modify: `server/internal/server/handler/pair.go`
- Test: `server/internal/server/handler/pair_test.go`

**Interfaces:**
- Consumes: `EffectiveToken`（Task 1）
- Produces: 响应 `{"token": string, "ble_token": string}`（字段存在才输出）；开放模式 + ble.token 时返回 200 只带 `ble_token`；两者皆空保持 400。

- [ ] **Step 1: 写失败测试** — 追加用例：`LanPairing=true, Server.Token="s", BLE.Token="b"` → 200 且 `token=="s"`、`ble_token=="b"`；`Server.Token="" , BLE.Token="b"` → 200 且仅 `ble_token`；两者皆空 → 400。（沿用 pair_test.go 现有构造）

- [ ] **Step 2: 确认失败**（第二、三场景）

- [ ] **Step 3: 实现**：

```go
func (h *Handler) Pair(c echo.Context) error {
	if !h.cfg.Server.LanPairing { /* 现状 403 不变 */ }
	eff := h.cfg.BLE.EffectiveToken(h.cfg.Server.Token)
	if h.cfg.Server.Token == "" && eff == "" {
		return c.JSON(http.StatusBadRequest,
			map[string]string{"error": "no token to distribute (server.token and ble.token both empty)"})
	}
	slog.Warn("LAN PAIRING: token material granted to a LAN requester — disable server.lan_pairing after your devices are paired",
		"remote_ip", c.RealIP())
	resp := map[string]string{}
	if h.cfg.Server.Token != "" {
		resp["token"] = h.cfg.Server.Token
	}
	if eff != h.cfg.Server.Token {
		resp["ble_token"] = eff
	}
	return c.JSON(http.StatusOK, resp)
}
```

- [ ] **Step 4: `go test ./internal/server/handler/` 全绿（含既有 pair 用例零回归）**

- [ ] **Step 5: Commit** — `git commit -m "feat(ble): distribute dedicated BLE key via LAN pairing"`

---

### Task 4: Android storage — `ServerConfigStore.bleToken`（加密）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/ServerConfigStore.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/data/ServerConfigStoreBleTokenTest.kt`（新建，参照 `ServerConfigStoreAuthTokenTest.kt` 的写法）

**Interfaces:**
- Produces: `val bleToken: Flow<String>`；`suspend fun saveBleToken(token: String)`（trim 后存储；空串清除；`TokenCrypto` 加密同 `authToken`）。

- [ ] **Step 1: 写失败测试**（round-trip：save→read 相等；空串保存后为 ""；与 authToken 互不污染）
- [ ] **Step 2: 确认失败**（编译错：无 bleToken）
- [ ] **Step 3: 实现** — `KEY_BLE_TOKEN = stringPreferencesKey("ble_token")`；`bleToken` Flow 与 `saveBleToken` 复制 authToken 的加密/解密模式。
- [ ] **Step 4: `./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.data.ServerConfigStore*"` 全绿**
- [ ] **Step 5: Commit** — `git commit -m "feat(android): persist dedicated BLE token in ServerConfigStore"`

---

### Task 5: Android key resolution — `bleKeyProvider`（bleToken 优先）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ble/BleController.kt`（参数 `authTokenProvider` → `bleKeyProvider`，语义注释更新，逻辑不动）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/di/BleModule.kt`（双缓存 + 优先级解析）
- Modify（机械改名）: `android/app/src/test/java/com/juziss/localmediahub/ble/BleControllerTest.kt`、`ble/TestBleFixtures.kt`、`data/MediaRepositoryFailoverTest.kt`、`viewmodel/BleSettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `ServerConfigStore.bleToken`（Task 4）
- Produces: `BleController(bleKeyProvider: () -> String, ...)`；`BleModule` 提供 `{ if (latestBleToken.value.isNotBlank()) latestBleToken.value else latestAuthToken.value }`。

- [ ] **Step 1: 写失败测试** — `BleControllerTest` 追加：`bleKeyProvider = { "bt" }` 时握手用 bt；`bleKeyProvider = { "" }` 时 fail-closed（既有行为）。解析优先级本身在 BleModule（Hilt 生成，不可单测），把规则写成顶层纯函数放入 `BleController.kt` 伴生对象以便测试：

```kotlin
companion object {
    /** Mirror of server BLEConfig.EffectiveToken: bleToken first, authToken fallback. */
    fun resolveBleKey(bleToken: String, authToken: String): String =
        if (bleToken.isNotBlank()) bleToken else authToken
}
```

测试：`resolveBleKey("bt","at")=="bt"`、`resolveBleKey("","at")=="at"`、`resolveBleKey(" ","at")=="at"`、`resolveBleKey("","")==""`。

- [ ] **Step 2: 确认失败 → Step 3: 实现**（改名 + BleModule 双 StateFlow 缓存：`store.bleToken.collect { latestBleToken.value = it }`，provider 传 `{ resolveBleKey(latestBleToken.value, latestAuthToken.value) }`）
- [ ] **Step 4: `./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.ble.*" --tests "com.juziss.localmediahub.data.*" --tests "com.juziss.localmediahub.viewmodel.BleSettingsViewModelTest"` 全绿**
- [ ] **Step 5: Commit** — `git commit -m "feat(android): resolve BLE handshake key from bleToken with authToken fallback"`

---

### Task 6: Android pairing consumer — 保存 ble_token

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt`（`tryLanPairing(): String?` → `LanPairGrant(token: String?, bleToken: String?)`）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/ConnectionViewModel.kt`（`tryPairIfTokenless` 保存两者）
- Test: `android/app/src/test/java/com/juziss/localmediahub/viewmodel/ConnectionViewModelPairingTest.kt`（若无既有配对测试则新建，参照 `ConnectionViewModel` 现有测试基建；无法构造时改为对 `LanPairGrant` 解析逻辑的纯函数测试）

**Interfaces:**
- Consumes: server 响应 `{"token"?, "ble_token"?}`（Task 3）、`saveBleToken`（Task 4）
- Produces: `data class LanPairGrant(val token: String?, val bleToken: String?)`（`MediaRepository.kt` 内）；解析私有纯函数 `parsePairResponse(body: String): LanPairGrant?` 可测。

- [ ] **Step 1: 写失败测试**（`parsePairResponse("{\"token\":\"s\",\"ble_token\":\"b\"}")` → 两字段；只有 token；只有 ble_token；空 body → null）
- [ ] **Step 2-4: 红绿实现** — `tryLanPairing` 返回 `LanPairGrant`；`tryPairIfTokenless`：

```kotlin
val granted = repository.tryLanPairing() ?: return
granted.token?.takeIf { it.isNotBlank() }?.let {
    serverConfigStore.saveAuthToken(it); serverConfig.setToken(it)
}
granted.bleToken?.takeIf { it.isNotBlank() }?.let { serverConfigStore.saveBleToken(it) }
```

（原"stored token 为空才配对"的前提同步调整为：token 与 bleToken 均为空才请求）

- [ ] **Step 5: 全绿 + Commit** — `git commit -m "feat(android): save dedicated BLE key from LAN pairing response"`

---

### Task 7: Android UI — BLE 设置卡内"BLE 密钥"输入

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModel.kt`（`bleToken: StateFlow<String>` 初始值从 store 读 + `saveBleToken`）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/BleChannelSection.kt`（`BleDeviceScanCard` 上方加可折叠密钥输入卡，参照 ConnectionScreen 高级选项的 `showAdvanced` 模式）
- Modify: `android/app/src/main/res/values/strings.xml`（`ble_key_title` / `ble_key_hint` / `ble_key_toggle`，硬编码中文文案与现有 BLE 文案风格一致）
- Test: `BleSettingsViewModelTest.kt` 追加 saveBleToken 用例（若 VM 测试基建可注入 store）

**Interfaces:**
- Consumes: `ServerConfigStore.bleToken` / `saveBleToken`（Task 4）
- Produces: UI 仅编辑显示值；实际握手密钥由 Task 5 的 provider 决定，UI 不参与解析。

- [ ] **Step 1-4: 红绿实现**（OutlinedTextField + "留空则使用访问令牌" hint + 保存按钮或 onFocusLost 保存——采用显式保存按钮，避免隐式写入；文案：标题 "BLE 密钥（可选）"，hint "与 server 的 ble.token 一致；留空则使用高级选项中的访问令牌"）
- [ ] **Step 5: `./gradlew testDebugUnitTest` 全量 + Commit** — `git commit -m "feat(android): optional BLE key input in BLE settings card"`

---

### Task 8: Docs + 全量验证

**Files:**
- Modify: `AGENTS.md`（模块图 BLE 段 + 安全约定"BLE 帧认证"小节：密钥源改为 `ble.token`（优先）→ `server.token`；开放 LAN 模式组合表）
- Modify: `docs/INDEX.md`（若维护 spec 索引则补两行）

- [ ] **Step 1: 更新 AGENTS.md 两处**
- [ ] **Step 2: `cd server && go test ./...` 全绿**
- [ ] **Step 3: `cd android && ./gradlew testDebugUnitTest` 全绿**
- [ ] **Step 4: 手工冒烟指引写入交付说明**（config.yaml 设 ble.token → 重启 → Android 填 BLE 密钥 → scan/connect/echo）
- [ ] **Step 5: Commit** — `git commit -m "docs: BLE dedicated token usage and auth matrix"`

## Self-Review 结论

- Spec 覆盖：2.1 规则 → Task 1/5；2.2 server 四点 → Task 1/2/3；2.3 android 四点 → Task 4/5/6/7；2.4 不进公共面 → Task 1（example 占位）+ gate 文案无密钥值；§3 兼容矩阵 → Task 2 测试四态；§4 验证 → 各任务 + Task 8。无缺口。
- 类型一致性：`EffectiveToken(string) string` ↔ `resolveBleKey(String, String): String`；`LanPairGrant` 字段与 server JSON key（`token`/`ble_token`）对齐。
