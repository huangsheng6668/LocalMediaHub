# Security Round 29 — Phase 3: Config Default Safety Design

> **日期**：2026-07-10
> **范围**：Go 服务端配置层默认值与启动校验
> **威胁模型**：局域网半可信（继承 Round 29 主 spec）
> **依赖**：Phase 1（Bearer Token 鉴权层，已合并于 commit `76b6d51`）
> **审计轮次**：Round 29 Phase 3（继 Phase 1 之后的第二段子任务）
> **源 spec**：`docs/superpowers/specs/2026-07-10-security-audit-design.md`（section 5.3）

---

## 0. 摘要

Phase 3 落实 spec 第 5.3 节的"配置默认安全"修复，针对 Phase 1 之后的剩余风险：

- **T3-01a（CVSS 6.1 Medium）**：`scan.roots` 未配置时自动探测 `A-Z` 全盘 → 任意 LAN 主机可读全部盘符媒体（即便 Phase 1 已加 token，"用户不开 token"的真实场景下仍全盘暴露）。
- **T3-02（CVSS 7.6 High）**：`enable_delete:true` + 自动盘符 → 任意 LAN 主机可删任意盘任意媒体。
- **T7-04（CVSS 7.6 High）**：用户 `config.yaml` 默认显式开启 `enable_delete:true`，无任何告警。
- **T8-12（CVSS Low）**：`config.yaml` 默认 0644 文件权限，多用户系统下可被其他本地用户读取。

**核心改动**：
1. **强制显式 roots**：`scan.roots` 空 + `scan.auto_detect_roots: false` + 无 `system.allowed_roots` → 拒绝启动。
2. **`--auto-detect-roots` 逃生口**：config.yaml 字段（持久 opt-in）+ 命令行 flag（force on，临时 override）。
3. **`enable_delete` 启动告警**：检测到 `true` 时打印显眼 slog.Warn 横幅。
4. **Phase 1 token 告警迁移**：统一到新的 `LogSecurityWarnings(cfg, autoFlag)` helper。
5. **`config.yaml` 权限收紧**：启动时 `os.Chmod("config.yaml", 0600)`，失败仅告警。

**Why now**：Phase 1 的鉴权层在用户开启 token 时已堵住大部分 LAN 攻击；但个人项目的现实是"用户图省事不开 token"。Phase 3 把"未开 token 时的最坏情况"也限制住——至少强制用户显式声明攻击面。

---

## 1. 范围与方法论

### 1.1 范围

仅服务端配置层。Android 客户端、Web UI（除设置面板警告文案）不动。

### 1.2 方法论

继承 Round 29 主 spec 的"威胁建模 → 代码验证 → 修复"流程。本 spec 只覆盖 Phase 3 的实施细节；威胁清单与攻击链见主 spec。

### 1.3 局限性

本 spec 不覆盖：
- `scan.roots` 的路径校验强化（T8-01 PUT 接受 `C:\Windows`）—— 留给 Phase 8 杂项
- `blockedSegments` 列表扩充（T3-01c `ProgramData` 等）—— 留给 Phase 8
- `config.yaml` 加密或 secrets 管理 —— 个人项目不需要

---

## 2. 威胁与缓解对照

| 发现 ID | CVSS | 描述 | Phase 3 缓解 |
|---|---|---|---|
| **T3-01a** | 6.1 Medium | `scan.roots` 空 → 自动探测 A-Z 全盘可读 | 强制显式 roots；自动探测需 opt-in |
| **T3-02** | 7.6 High | `enable_delete:true` + 自动盘符 → 删任意盘 | 强制显式 roots 限制删除范围；启动告警 |
| **T7-04** | 7.6 High | `config.yaml` 默认 `enable_delete:true` | 启动 slog.Warn 横幅 |
| **T8-12** | Low | `config.yaml` 默认 0644 | 启动时 `os.Chmod 0600` |

**攻击链缓解**：
- **Chain-A**（CVSS 6.1 Medium）：T3-01a 自动盘符 → LAN 读全部盘符媒体。Phase 3 强制 roots 后，Chain-A 前置条件消失。
- **Chain-B**（CVSS 7.6 High）：T1-02a/b 无鉴权 + T7-04 `enable_delete:true`。Phase 3 不直接阻断（删除仍可在 allowed_roots 内），但配合"显式 roots"把损害面收敛到用户声明的目录。

---

## 3. 设计决策

### 3.1 已确认决策（来自 brainstorming）

| 决策 | 选择 | 理由 |
|---|---|---|
| 空 roots 启动策略 | **拒绝启动 + 明确错误** | 强制用户显式声明攻击面；最安全 |
| `--auto-detect-roots` 形态 | **config.yaml 字段 + 命令行 flag override** | GUI 模式（双击）也能用；flag 便于 CI/调试 |
| `enable_delete` 处理 | **启动日志高亮 WARNING** | 与 Phase 1 token 告警模式一致；零破坏老用户配置 |
| `LogSecurityWarnings` 调用位置 | **main.go 外部调用**（不在 `server.New` 内） | config 层只管数据，slog 是副作用；main.go 控制时机 |
| flag 优先级 | **flag 是 "force on"**（`effective = cfg.Scan.AutoDetectRoots \|\| autoFlag`） | flag 用于临时调试；用户想关掉就去 config.yaml 改字段 |
| Phase 1 token 告警迁移 | **是，统一到 LogSecurityWarnings** | 一致性；单一 helper 便于测试与未来扩展 |

### 3.2 兼容性矩阵

| 现状 | Phase 3 后行为 | 破坏性 |
|---|---|---|
| 用户已配 `scan.roots` | 直接用配置 roots（不变） | 无 |
| 用户已配 `system.allowed_roots` 但未配 `scan.roots` | 走现有 fallback（`Roots = AllowedRoots`），不变 | 无 |
| 用户两者都没配，依赖自动盘符 | **拒绝启动**，需加 `auto_detect_roots: true` 或显式 roots | **有**（已记入 README 迁移指引） |
| 用户已开 `enable_delete: true` | 仍生效，但启动打印告警横幅 | 无（仅 awareness） |
| 用户 config.yaml 权限 0644 | 启动后自动改为 0600 | 无（仅收紧） |

---

## 4. 修改清单

### 4.1 服务端代码

| 文件 | 改动 | Task |
|---|---|---|
| `server/internal/config/config.go` | 加 `ScanConfig.AutoDetectRoots bool`；`Load` 末尾加强制 roots 校验；新增 `LogSecurityWarnings(*Config, bool)` 函数 | 1, 2 |
| `server/internal/config/config_test.go` | 加 `TestLoadRejectsEmptyRoots`；加 `TestLogSecurityWarnings` 三场景 | 1, 2 |
| `server/cmd/server/main.go` | 加 `--auto-detect-roots` flag；调 `config.LogSecurityWarnings(cfg, autoFlag)`；`os.Chmod("config.yaml", 0600)` | 3 |
| `server/internal/server/server.go` | **删除** Phase 1 写在 `New()` 里的 token 告警（迁移到 `LogSecurityWarnings`） | 3 |

### 4.2 配置与文档

| 文件 | 改动 | Task |
|---|---|---|
| `server/config.example.yaml` | 加 `scan.auto_detect_roots: false` 注释；`system.enable_delete` 注释强化为"红色警告" | 4 |
| `README.md` | 加"Phase 3 升级迁移"小节：旧用户若依赖自动盘符，需显式加 `auto_detect_roots: true` | 4 |

### 4.3 Web UI

| 文件 | 改动 | Task |
|---|---|---|
| `server/internal/web/settings.js`（或对应渲染 `enable_delete` 的文件） | `enable_delete` toggle 旁加红色警告文案（仅当开启时显示） | 4 |

**注**：Task 4 涉及 Web UI 文件——若现有 settings.js 未渲染 `enable_delete`（只在 config.yaml 配置），则跳过 Web UI 改动，仅在 config.example.yaml 加警告。

---

## 5. 实施细节

### 5.1 `config.go` 改动

#### 5.1.1 `ScanConfig` 新字段

```go
type ScanConfig struct {
    Roots           []string `yaml:"roots,omitempty" json:"roots,omitempty"`
    VideoExtensions []string `yaml:"video_extensions" json:"video_extensions"`
    ImageExtensions []string `yaml:"image_extensions" json:"image_extensions"`
    AutoDetectRoots bool     `yaml:"auto_detect_roots,omitempty" json:"auto_detect_roots,omitempty"`

    // cached result of auto-detected drives (existing, unchanged)
    autoRoots     []string
    autoRootsOnce sync.Once
}
```

#### 5.1.2 `Load` 校验

在现有 `Load` 末尾、`return &cfg, nil` 之前插入：

```go
// Phase 3 安全默认：若既未配 roots 也未配 allowed_roots，且未显式 opt-in
// auto-detect，则拒绝启动。强制用户声明攻击面。
if len(cfg.Scan.Roots) == 0 && len(cfg.System.AllowedRoots) == 0 && !cfg.Scan.AutoDetectRoots {
    return nil, fmt.Errorf(
        "refusing to start: no scan.roots or system.allowed_roots configured and " +
            "scan.auto_detect_roots is false.\n" +
            "To serve media, either:\n" +
            "  1. List explicit roots under 'scan.roots' in config.yaml, or\n" +
            "  2. Configure 'system.allowed_roots' (also serves as scan roots fallback), or\n" +
            "  3. Set 'scan.auto_detect_roots: true' in config.yaml (serves ALL drives — " +
            "review your threat model first), or\n" +
            "  4. Run with --auto-detect-roots flag (one-shot override)")
}
```

**注**：现有 fallback（`Roots = AllowedRoots`，当 `Roots` 空 + `AllowedRoots` 非空时）保留不变。新校验只在**两者都空**时触发。

#### 5.1.3 `LogSecurityWarnings` 新函数

```go
// LogSecurityWarnings prints slog.Warn banners for risky configuration.
// Called from main.go AFTER config.Load succeeds, BEFORE server.New.
//
// Centralizing here (instead of inside server.New) keeps config layer
// ownership of "what is risky" and lets main.go choose the timing
// (e.g. before mDNS registration). server.New stays focused on wiring.
//
// autoFromFlag is the --auto-detect-roots flag value; it ORs with
// cfg.Scan.AutoDetectRoots to determine the effective auto-detect state.
func LogSecurityWarnings(cfg *Config, autoFromFlag bool) {
    if cfg.Server.Token == "" {
        slog.Warn("==============================================================")
        slog.Warn(" SERVER IS RUNNING IN OPEN AUTH MODE (no token configured).")
        slog.Warn(" Any host on the LAN can call admin/system/media endpoints.")
        slog.Warn(" Set 'server.token' in config.yaml to enable authentication.")
        slog.Warn("==============================================================")
    } else {
        slog.Info("Auth: token-based authentication enabled for admin/system/media routes")
    }

    if cfg.System.EnableDelete {
        slog.Warn("==============================================================")
        slog.Warn(" REMOTE DELETE IS ENABLED (system.enable_delete: true).")
        slog.Warn(" Any authenticated client (or any LAN host if token is empty)")
        slog.Warn(" can delete files under system.allowed_roots.")
        slog.Warn(" Disable 'system.enable_delete' in config.yaml unless you")
        slog.Warn(" genuinely need this feature.")
        slog.Warn("==============================================================")
    }

    if cfg.Scan.AutoDetectRoots || autoFromFlag {
        slog.Warn("==============================================================")
        slog.Warn(" AUTO-DETECT ROOTS IS ENABLED.")
        if autoFromFlag && !cfg.Scan.AutoDetectRoots {
            slog.Warn(" (triggered by --auto-detect-roots flag, not config.yaml)")
        }
        slog.Warn(" Server will serve media from ALL detected drives (A-Z).")
        slog.Warn(" For production, configure 'scan.roots' explicitly instead.")
        slog.Warn("==============================================================")
    }
}
```

### 5.2 `main.go` 改动

```go
var (
    headless        bool
    autoDetectRoots bool
)

func main() {
    flag.BoolVar(&headless, "headless", false, "Run without GUI (system tray)")
    flag.BoolVar(&autoDetectRoots, "auto-detect-roots", false,
        "Force-enable auto-detection of all drives as scan roots (one-shot override; "+
            "also achievable via scan.auto_detect_roots in config.yaml)")
    flag.Parse()

    cfg, err := config.Load("config.yaml")
    if err != nil {
        slog.Error("Failed to load config", "error", err)
        os.Exit(1)
    }

    // Phase 3: log security warnings BEFORE any side effects (mDNS, server).
    config.LogSecurityWarnings(cfg, autoDetectRoots)

    // Phase 3: tighten config.yaml file permissions to owner-only.
    // Failure is non-fatal (read-only fs, Windows ACL quirks) — warn and continue.
    if err := os.Chmod("config.yaml", 0600); err != nil {
        slog.Warn("Failed to tighten config.yaml permissions to 0600", "error", err)
    }

    // ... rest of main (mDNS, headless/gui) unchanged ...
}
```

### 5.3 `server.go` 改动（Phase 1 告警迁移）

**删除** `server.New()` 中的 token 告警块（commit `2083e47` 加的，约第 89-97 行）：

```go
// DELETE THIS BLOCK from server.New():
if cfg.Server.Token == "" {
    slog.Warn("==============================================================")
    slog.Warn(" SERVER IS RUNNING IN OPEN AUTH MODE (no token configured).")
    // ...
} else {
    slog.Info("Auth: token-based authentication enabled for admin/system/media routes")
}
```

**理由**：迁移到 `config.LogSecurityWarnings`（main.go 调用）。行为等价——`server.New` 仍在 main.go 之后调用，告警在 server.New 之前打印，用户看到的输出顺序不变。

**回归风险**：`server_auth_test.go` 中的 `TestServerRejectsAdminWithoutToken` 等测试不依赖告警文本，只依赖路由行为。但若有测试断言告警输出，需迁移到 `TestLogSecurityWarnings`。

### 5.4 `config.example.yaml` 改动

```yaml
scan:
  # Media roots. If empty AND system.allowed_roots is empty AND auto_detect_roots
  # is false, the server refuses to start (Phase 3 safety default).
  # Explicit roots are strongly recommended for production.
  roots: []
    # - "D:/Movies"
    # - "E:/Photos"
  video_extensions:
    # ... existing ...
  image_extensions:
    # ... existing ...
  # OPT-IN auto-detection: when true, server scans all detected drives (A-Z).
  # Convenience feature with elevated risk — any LAN host (or any authenticated
  # client) can browse media on ALL drives. Default false.
  auto_detect_roots: false

# ... existing thumbnail block ...

system:
  # CAUTION: when true, authenticated clients can delete files under allowed_roots
  # via POST /api/v1/system/delete. The server prints a warning at startup when
  # this is enabled. Default false.
  enable_delete: false
  allowed_roots: []
    # - "D:/Media"
```

---

## 6. 测试方案

### 6.1 单元测试

| 测试 | 覆盖 | 文件 |
|---|---|---|
| `TestLoadRejectsEmptyRoots` | 空 roots + 空 allowed_roots + auto=false → error；空 roots + 空 allowed_roots + auto=true → OK；有 roots → OK；空 roots + 有 allowed_roots → OK（fallback） | `config_test.go` |
| `TestLogSecurityWarnings` | capture slog 输出，断言：(a) 空 token → 含"OPEN AUTH MODE"；(b) enable_delete=true → 含"REMOTE DELETE IS ENABLED"；(c) auto_detect=true → 含"AUTO-DETECT ROOTS IS ENABLED"；(d) 全部安全 → 无 Warn 仅 Info | `config_test.go` |

#### 6.1.1 slog 输出捕获模式

Go 1.21+ `slog` 允许自定义 handler。测试用：

```go
func captureSlogOutput(fn func()) string {
    var buf bytes.Buffer
    old := slog.Default()
    defer slog.SetDefault(old)
    logger := slog.New(slog.NewTextHandler(&buf, &slog.HandlerOptions{Level: slog.LevelDebug}))
    slog.SetDefault(logger)
    fn()
    return buf.String()
}
```

测试断言用 `strings.Contains(output, "REMOTE DELETE IS ENABLED")` 等。

### 6.2 集成测试（main.go flag）

| 测试 | 步骤 |
|---|---|
| 启动拒绝 | 空 config.yaml → `./LocalMediaHub.exe` → exit code 1，stderr 含"refusing to start" |
| flag override | 空 config.yaml + `--auto-detect-roots` → 启动成功，stdout 含"AUTO-DETECT ROOTS IS ENABLED" + "(triggered by --auto-detect-roots flag)" |
| config 字段 opt-in | 空 config.yaml + `auto_detect_roots: true` → 启动成功，stdout 含"AUTO-DETECT ROOTS IS ENABLED"（无 flag 注释） |

这些是端到端测试，可在 Task 3 或 Task 4 手动执行（构建后跑二进制），或写为 `main_test.go`（用 `os/exec`）。

### 6.3 回归测试

- 现有 `server_auth_test.go`（Phase 1）必须仍 green——告警迁移不应破坏路由行为。
- 现有 `config_test.go`（`TestServerConfigTokenRoundTrip` 等）必须仍 green。

---

## 7. 风险与权衡

| 风险 | 影响 | 缓解 |
|---|---|---|
| 老用户首次升级后服务拒绝启动 | 中（破坏性） | README 加迁移指引；`config.example.yaml` 注释明确；启动错误消息列出 4 种解决方式 |
| Phase 1 token 告警迁移破坏 `server_auth_test.go` | 低 | 测试不依赖告警文本（Phase 1 测试只断言路由行为）；新增 `TestLogSecurityWarnings` 覆盖告警 |
| `os.Chmod` 在 Windows 行为差异 | 低 | Windows 的 0600 实际只移除"Everyone"组写权限；Unix 行为标准；失败仅告警不阻断 |
| 用户长期依赖 `--auto-detect-roots` 跑生产 | 中 | flag 仅"force on"，不能"force off"；每次启动都告警；config.yaml 字段是"持久 opt-in"，更透明 |
| 用户配 `auto_detect_roots: true` 后忘了 | 中 | 每次启动 slog.Warn 横幅提醒；Web UI 仪表盘（未来）可显示当前 roots 来源 |

---

## 8. 验证完成标准

- ✅ `TestLoadRejectsEmptyRoots` 通过（4 子用例）
- ✅ `TestLogSecurityWarnings` 通过（4 子用例）
- ✅ 现有 `config_test.go` + `server_auth_test.go` 全部 green（回归）
- ✅ `cd server && go test ./...` 全 green
- ✅ 手动：空 config + 无 flag → 拒绝启动（exit 1）
- ✅ 手动：空 config + `--auto-detect-roots` → 启动 + 告警
- ✅ 手动：`enable_delete: true` 启动 → 红色告警横幅
- ✅ `config.yaml` 文件权限启动后为 0600（Unix）/ 加固（Windows）

---

## 9. 后续 Phase 衔接

Phase 3 完成后，剩余 Phase（按主 spec 优先级）：

| Phase | 内容 | 备注 |
|---|---|---|
| Phase 2 | libffmpeg.so 审计 + SBOM | 阻断 Chain-D（唯一 RCE 链） |
| Phase 4 | HTTP 加固（安全头 + 可选 TLS） | 独立 |
| Phase 5 | Web UI XSS 整改（残余风险低） | 主 spec 已审计为"当前字段已覆盖" |
| Phase 6 | 供应链扫描 + 依赖升级 | 独立 |
| Phase 7 | APK 签名加固 | 独立 |
| Phase 8 | 杂项 P2（rate limit / blockedSegments 扩充 / PUT 校验加强等） | 独立 |

---

## 文档信息

- **创建日期**：2026-07-10
- **审计轮次**：Round 29 Phase 3
- **依赖**：Phase 1（commit `76b6d51`）
- **方法论**：brainstorming skill（澄清 → 方案 → 设计）
- **下一步**：经用户审核后，调用 writing-plans skill 转为实施计划
