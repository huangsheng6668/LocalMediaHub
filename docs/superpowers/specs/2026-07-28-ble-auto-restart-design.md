# BLE 卡死自动重启 server 方案设计

**日期**: 2026-07-28
**范围**: `server/` (Go)
**目标**: tinygo/bluetooth v0.15.0 在 Windows 上建立 GATT 连接后无法在进程内安全释放（COM 对象悬垂，任何清理方法都 fault 崩进程；`MaintainConnection` 与自动 Disconnect 均已在 third_party patch 中规避，但残留 session 仍阻塞后续 Connect 的 DiscoverServices）。唯一可靠清理方式是重启进程。本设计让 server 检测到 BLE 卡死后自动重启自己，恢复降级通道，无需用户手动干预。

**前置**: `server/third_party/bluetooth/`（tinygo/bluetooth v0.15.0 本地 fork，go.mod replace 指向）已 patch 掉自动 Disconnect 与 SetMaintainConnection(true)。重启进程后 WinRT 状态全新，BLE 能干净重连（已真机验证：手动重启 server 后连接恢复）。

---

## 1. 触发条件

### 1.1 卡死判定（连续 2 轮 Connect 失败）
- `connectLocked` 每次被调用（一轮 = 内部最多 3 次 adapter.Connect+DiscoverServices 重试）结束时，上报「本轮是否成功」给 `bleHealthMonitor`。
- **连续 2 轮 Connect 全部失败**（累计最多 6 次 DiscoverServices 失败）→ 判定卡死，触发自动重启。
- 任何一轮 Connect 成功 → 失败计数清零。
- 「失败」定义：`connectLocked` 返回非 nil error（含 adapter.Connect 失败、DiscoverServices 失败、特征未找到等）。

### 1.2 不触发重启的情况
- 首次连接失败（计数才到 1）——可能是瞬时问题，靠 Android 自动重连兜底。
- scan 失败 / scan 无结果——不经过 connectLocked，不算 Connect 轮次。

---

## 2. 自我重启机制

### 2.1 流程
`bleHealthMonitor` 判定卡死后：
1. 起新 detached 子进程：`exec.Command(os.Executable(), os.Args[1:]...)`，Windows `SysProcAttr.CreationFlags = DETACHED_PROCESS (0x00000008)`，`Stdout/Stderr` 重定向到继承的句柄或丢弃，子进程脱离父进程独立运行。
2. 通过子进程环境变量 `LMH_BLE_RESTART_TS=<unix-ts>` 传递「本次重启时间」，供新进程做冷却判断。
3. 当前进程优雅 `Server.Stop()`（最多排空 3 秒，复用现有 Stop 逻辑）。
4. 当前进程 `os.Exit(0)`。

### 2.2 优雅排空（3 秒）
- `Server.Stop()` 内部已有的 graceful shutdown（关闭 HTTP listener + 等待在途请求）沿用；额外加一个 3 秒硬上限，超时强制退出，避免卡死期间被在途请求拖住。
- 重启空窗约 1-3 秒：期间 HTTP 客户端（Wi-Fi）短暂断开，可接受。

---

## 3. 冷却（防重启循环）

### 3.1 60 秒冷却
- 新进程启动时读环境变量 `LMH_BLE_RESTART_TS`。若存在且距上次重启 < 60 秒 → 本会话进入「冷却」：**禁用自动重启**，BLE 卡死只记 ERROR 日志 + Connect 照常返回错误，不重启。
- 冷却意图：重启后若 Android 仍在疯狂重连导致新进程又卡死，60 秒内不再重启，避免无限重启循环；过冷却期后允许再次触发。
- `LMH_BLE_RESTART_TS` 仅用于启动时一次性判断；当前会话是否处于冷却由进程内布尔标志持有。

### 3.2 正常启动（无该环境变量）
- 用户双击启动 / 首次启动：无 `LMH_BLE_RESTART_TS`，不冷却，自动重启功能可用。

---

## 4. 范围与约束

- **仅 `-tags bluetooth` 构建**生效：无 tag 的 `central_adapter_stub.go` 不涉及 BLE，不需要健康监控。监控代码放在 bluetooth tag 文件或用 tag 守卫。
- **不触碰 third_party patch**：本设计依赖 patch 已生效（自动 Disconnect 已删、MaintainConnection 不设），不修改 patch。
- **零 Wi-Fi 回归**：重启仅由 BLE 卡死触发；Wi-Fi 正常时 BLE 不会卡死，不会重启。
- **自我重启仅 Windows**：DETACHED_PROCESS 是 Windows flag。`ble_health.go` 用 `//go:build windows && bluetooth` 守卫；非 Windows 暂不支持自动重启（项目仅面向 Windows BLE Central）。

---

## 5. 组件

### 5.1 `server/internal/ble/ble_health.go`（新，`//go:build windows && bluetooth`）
- `type bleHealthMonitor struct { consecutiveFailures int; coolDown bool; restart func() }`
- `func (m *bleHealthMonitor) recordConnect(ok bool)`：ok 则清零；失败则 consecutiveFailures++；连续 2 轮失败且 !coolDown → 调 restart()。
- `func newBleHealthMonitor(coolDown bool, restartFn func()) *bleHealthMonitor`
- 注入到 `tinyGoCentralScanner`，`connectLocked` 结束时调 `recordConnect`。

### 5.2 重启函数（main 或 server 包）
- 读取 `os.Executable()` + `os.Args[1:]` + 设 `LMH_BLE_RESTART_TS` 环境变量 → DETACHED 子进程 → Stop(3s) → os.Exit(0)。
- 通过依赖注入传给 `bleHealthMonitor`，便于测试用 fake restart。

### 5.3 冷却判定（main.go 启动时）
- 读 `LMH_BLE_RESTART_TS`，距 now < 60s → coolDown=true，记 INFO 日志「BLE auto-restart cooling down for 60s」。
- coolDown 传入 `newBleHealthMonitor`。

---

## 6. 测试策略

1. **ble_health 单测**（`//go:build windows && bluetooth`，但纯逻辑用 fake restart）：
   - 1 次失败不重启；2 次连续失败 → restart 被调一次。
   - 中间成功 → 计数清零，再 2 次失败才重启。
   - coolDown=true 时，2 次失败也不重启。
2. **重启函数**：不在单测里真重启；用接口注入 fake，断言 restart 被调用（不真 exec）。
3. **冷却解析**：单测覆盖 `LMH_BLE_RESTART_TS` 解析（<60s 冷却、>60s 不冷却、无变量不冷却）。
4. **真机**：连上→杀 App→server 自动重启→App 重连成功（日志确认「BLE auto-restart triggered」+ 新进程 Connect success）。
