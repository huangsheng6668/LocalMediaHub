### Task 15: 文档收尾（H-4 引导 + 索引）

**Files:**
- Modify: `docs/INDEX.md`（安全加固表加 Phase 9 行）
- Modify: `server/config.example.yaml`（token 字段注释强化）
- Modify: `AGENTS.md`（安全约定节补 Phase 9 要点）

**Interfaces:** 无代码。

- [ ] **Step 1: INDEX.md 安全加固表追加一行**

```markdown
| 9 | 三端审计修复（媒体端点 auth / BodyLimit / 认证失败限速 / 缩略图缓存上限 / BLE HMAC 认证与 GATT 加固 / 杂项 P4） | `docs/superpowers/specs/2026-08-17-security-phase9-tri-end-audit-design.md` | 完成 |
```

- [ ] **Step 2: config.example.yaml token 注释**

```yaml
# Bearer token：留空 = 开放模式（同网段任何人可访问全部 API，仅删除接口受限）。
# 强烈建议家用共享 Wi-Fi 下设置长随机串，例如：
#   python -c "import secrets; print(secrets.token_urlsafe(32))"
# 注意：token 为空时 BLE 通道不可用（Phase 9 起握手需要 token 派生密钥）。
token: ""
```

- [ ] **Step 3: AGENTS.md 安全约定节补两条**

- `### 认证覆盖（Phase 9）`：媒体读端点（folders/videos/images/texts/search）挂 Bearer auth；空 token 开放模式透传。
- BLE 一行：`server/internal/ble/protocol.go` v2 帧（seq+HMAC）与双 nonce 握手，密钥从 token 派生，两端对称（`BleProtocol.kt`）。

- [ ] **Step 4: 验证全仓测试**

Run: `cd server && go test ./... && cd internal/web && node --test && cd ../../../android && ./gradlew testDebugUnitTest`
Expected: 全绿

- [ ] **Step 5: 提交**

```bash
git add docs/INDEX.md server/config.example.yaml AGENTS.md
git commit -m "docs(security): index Phase 9 audit and harden token guidance (Phase 9)"
```

---

## Self-Review 记录

- **Spec 覆盖**：§5.1→Task 1/2/3；§5.2→Task 4；§5.3→Task 5/6/7（M-5 按 spec §7 不修）；§5.4→Task 8/9/10/11（stream ID 简化为字节上限，理由记录在 Task 9）；§5.5→Task 12/13/14；§8 验收分散在各 Task 的测试与最终 Task 15 Step 4。无缺口。
- **占位符扫描**：无 TBD/TODO；每个代码步骤均给出实现代码或精确改动点。
- **类型一致性**：`DeriveBleAuthKey`/`EncodeAuthedFrame`/`DecodeAuthedFrame`（Go）与 `deriveBleAuthKey`/`encodeAuthedFrame`/`decodeAuthedFrame`（Kotlin）命名按各自语言惯例，线格式常量值逐字节一致（0x02/0x20/0x21/0x01/0x02、HMAC 覆盖 `[0:3+len+8]`、MAC 取前 16B）。
