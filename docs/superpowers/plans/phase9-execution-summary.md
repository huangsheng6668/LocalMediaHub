# Security Phase 9 执行总结（三端安全审计修复）

**Spec**: `docs/superpowers/specs/2026-08-17-security-phase9-tri-end-audit-design.md`
**Plan**: `docs/superpowers/plans/2026-08-17-security-phase9-tri-end-audit.md`
**执行方式**: subagent-driven（每任务独立实施代理 + 任务级双维审查；Task 8 经历 1 轮 fix loop；整分支最终审查 + 1 轮修复波 + scoped 复审）
**执行台账**: `phase9-progress.md`（本目录）

## 提交清单（master 直线历史）

| Commit | 内容 |
|---|---|
| f245136 | docs: Phase 9 spec + plan |
| 4e68a5a | T1 fix(security): 访问日志 RequestURI 脱敏 (H-3) |
| 39bfe82 | T2 fix(security): ValidateDeletion 大小写不敏感 (M-4) |
| 0623fc7 | T3 fix(web): THEME_LABELS 冻结常量 (M-10) |
| 0b99fe7 | T4 feat(security): 媒体读端点并入认证 (H-2) |
| 62ad74b | T5 feat(security): 全局 BodyLimit 4M (M-1) |
| 845c129 | T6 feat(security): 认证失败限速 10/min/IP (M-2) |
| 9bb6e97 | T7 feat(security): 缩略图磁盘缓存 512MB LRU + 图片限速 (M-3) |
| 31edce3 | T8 feat(ble): BLE v2 帧 + HMAC 握手 Go 端 (H-1a) |
| eb252fd | T8-fix: sendMu 串行化写序 + 三条安全门测试（fix round 1） |
| 752fa9d | T9 feat(ble): Android 对称实现 + 重组 1MB 上限 (H-1b/M-9) |
| 77bcc7a | T10 feat(ble): GATT 加密特征 + bond/CCCD/offset 守卫 (H-1c) |
| e359a13 | T11 fix(ble): UUID 精确匹配/日志脱敏/browse 路径对齐 (H-1d/M-6/M-8) |
| 3a2bfd6 | T12 fix(android): PiP NOT_EXPORTED/路径段编码/zip 上限 (M-7/L-6/L-7) |
| 4bcfee0 | T13 fix(security): pprof 门禁/LIKE 转义/BLE 重启退避 (L-2/L-4/L-5) |
| 99a8132 | T14 fix(web): lightbox 转义/CSP 收紧/EPUB 外链剥离 (L-11/L-12) |
| c546a19 | T15 docs(security): INDEX/AGENTS/config.example 收尾 |
| 2781945 | 修复波 fix(ble): auth reset 移至 GATT 连接事件（C-1） |
| a2a5e62 | 修复波 feat(security): /tags 读端点挂 auth（I-3） |
| 6230467 | 修复波 fix(android): taskGraph.whenReady 签名守卫（I-1/L-8） |
| a750b25 | 修复波 docs(security): INDEX Token 列 + spec 60/min 修订（I-2/M-B） |

## 最终审查与修复波

整分支最终审查（f12b6f3..c546a19）发现 1 Critical + 3 Important，全部经唯一修复波处理并复审 ALL ADDRESSED：

- **C-1（Critical，跨任务集成缺陷）**：Android `markConnected()` 在 PC 已于 HTTP 往返内同步完成握手后才被调用，其无条件 `resetAuthLocked()` 抹除手机侧认证 → 生产时序下 BLE 数据通道不可用。修复：auth reset 移至 GATT 连接事件 seam（`setOnPeerConnected/Disconnected`），`markConnected` 只做状态机迁移；两侧时序对齐集成测试锁定。**此类缺陷只有整分支门能拦（两侧单测各自编码了相反顺序）——该经验已验证了最终审查环节的必要性。**
- **I-3**：/tags 四个读端点泄露全库文件路径，补挂 authMw。
- **I-1/L-8**：release 签名守卫改 `gradle.taskGraph.whenReady`（GUI 构建不再绕过）+ debug 密钥兜底仅显式 opt-in。
- **I-2/M-B**：INDEX.md 需Token 列与 Phase 9 事实同步；spec 记录缩略图限速 60/min 修订。

## 递延项（分诊裁决：全部递延，理由见各任务报告）

代表性条目：RequestURI 解码路径日志保真度、thumb-tmp 误删窗口、双 walk 保守记账、BLE seq 种子未绑定 nonce 的跨连接重放理论窗口、CCCD 写值语义、`PipControllerStore` 死代码、authErrorText 无 UI 消费。完整清单见 `phase9-progress.md` 与各任务审查记录。

## 真机联调清单（无蓝牙环境的 Windows 无法覆盖，后续实机验证）

1. LE Just Works 配对弹窗（首个加密访问触发，一次即可）
2. bond 后 CCCD 订阅 → 完整 HMAC 握手 → v2 数据帧往返
3. MTU 协商值确认（看 `BLE negotiated ATT MTU=` 日志；若停留 23 走短帧解码兜底）
4. `markConnected` 迟到时序实测（C-1 修复后的真实顺序）
5. BLE 重启指数退避的进程内/跨进程行为

## 已知事项

- `bookparser TestParseUserNovel` 为 Phase 9 之前的既有基线失败（干净 master 复现，与本 Phase 零交集）
- 执行期间实施代理验证签名守卫时误覆盖 `android/keystore.properties`（gitignored）：**密钥本体 `localmediahub.keystore` 完好**，文件已留恢复模板，发布 release 前需填回真实 store/key 密码
