# Task 15 Report — 文档收尾（H-4 引导 + 索引）

**Commit**: `c546a19872e69138596993aa16cd9a930cb40228` — `docs(security): index Phase 9 audit and harden token guidance (Phase 9)`
**Branch**: master（按 brief 直接提交）
**Files changed**: 3 files, +16 / -7（纯文档，零代码改动）

## 做了什么

### 1. `docs/INDEX.md` 安全加固表追加 Phase 9 行（Step 1）

- 在 "安全加固" 章节的 Phase 总览表 Phase 8 行之后追加 Phase 9 行（brief Step 1 原文）：
  `| 9 | 三端审计修复（媒体端点 auth / BodyLimit / 认证失败限速 / 缩略图缓存上限 / BLE HMAC 认证与 GATT 加固 / 杂项 P4） | docs/superpowers/specs/2026-08-17-security-phase9-tri-end-audit-design.md | 完成 |`
- **最小一致性同步**：表标题 `### Phase 1-8 总览` → `### Phase 1-9 总览`（否则 Phase 9 行会挂在 "1-8" 标题下自相矛盾）。

### 2. `server/config.example.yaml` token 注释强化（Step 2）

- 用 brief Step 2 原文替换 `server.token` 上方 4 行英文注释（保留既有 2 空格缩进，即 `server:` 块内格式）：
  - 留空 = 开放模式（同网段任何人可访问全部 API，仅删除接口受限）
  - 建议家用共享 Wi-Fi 下设置长随机串（`python -c "import secrets; print(secrets.token_urlsafe(32))"`）
  - **token 为空时 BLE 通道不可用（Phase 9 起握手需要 token 派生密钥）**——对应 `server/internal/server/server.go:144` 的 `bleCentral.SetAuthToken(cfg.Server.Token)`（nil key 时 Central 拒绝进入数据阶段）

### 3. `AGENTS.md` 安全约定节补两条（Step 3）

在 `### Bearer Token 认证` 之后新增两个小节（行文跟随既有 `###` 小节 + bullet 风格）：

- `### 认证覆盖（Phase 9）`：媒体读端点（folders / videos / images / texts / search）挂 Bearer auth；空 token 开放模式透传。
- `### BLE 帧认证（Phase 9）`：`server/internal/ble/protocol.go` v2 帧（seq+HMAC）与双 nonce 握手，密钥从 token 派生，两端对称（`BleProtocol.kt`）。

BLE 模块地图最小同步（按 brief 指示，不做大改）：

- Server 周边 ble 条目：`internal/server/handler/ble.go`（复用 Bearer Token）→ 追加 "GATT 数据链路 Phase 9 起为 v2 帧认证，密钥从 token 派生"。
- Android BLE 节无 "零鉴权" 类旧表述（`BleProtocol` "与 server 对称的帧 codec" 与 v2 实现不矛盾），未改动。
- 文末 "详细文档" 行：`安全 Phase 1-8 总览` → `安全 Phase 1-9 总览`（与 INDEX.md 标题同步）。

## 验证（Step 4）

| 命令 | 结果 |
|---|---|
| `cd server && go test ./...` | 全绿，唯一失败为 bookparser `TestParseUserNovel`（既有基线例外，与本次改动无关；末尾 `unlinkat` 报错是该失败测试二进制在 Windows 上的临时文件清理噪音，非额外失败） |
| `cd server/internal/web && node --test` | 88 pass / 0 fail |
| `cd android && ./gradlew testDebugUnitTest` | BUILD SUCCESSFUL（UP-TO-DATE——纯文档改动无代码输入变化，Gradle 缓存判定无需重跑，符合预期） |

实际执行命令与 brief Step 4 一致（Android 跑了全量 `testDebugUnitTest`，未降级到 ble.* 子集）。

## Concerns

- `docs/INDEX.md` 顶部 "媒体浏览与播放" API 端点表的 "需 Token" 列仍显示 folders/videos/images/texts/search/stream/thumbnail 为 "否"——Phase 9 后这些端点已挂 authMw（空 token 时透传）。该表不在 brief 的改动范围内，未动；建议后续顺手把这一列更新为条件式表述（"token 模式下需"），或注明 "Phase 9 起全部 API 在 token 模式下要求认证"。
