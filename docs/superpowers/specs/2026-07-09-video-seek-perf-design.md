# 视频快进卡顿优化设计（Round 27）

日期：2026-07-09
分支：待创建（建议 `round-27-seek-perf`）

## 背景

用户反馈视频播放快进后严重卡顿，覆盖四种 seek 入口（水平手势、SeekBar 拖动、点击进度条、双击快进），表现为：

- 拖动时画面不跟随，松手后才跳到目标位置
- 松手后画面冻结 1-3 秒才恢复播放

所有视频格式（.mp4 / .mkv / .avi / .flv）均有此问题，排除容器/转码因素，根因在 ExoPlayer seek 管线与 UI 反馈层。

## 根因分析

### 1. `ForwardingPlayer.seekTo` 防抖逻辑有 bug（`VideoPlayerScreen.kt:195-216`）

```kotlin
override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
    val now = SystemClock.elapsedRealtime()
    if (now - lastSeekTime > 500L) {
        super.seekTo(...)  // 冷启动后第一次立即执行
    } else {
        seekJob?.cancel()
        seekJob = coroutineScope.launch {
            delay(200L)  // 200ms 防抖
            super.seekTo(...)
        }
    }
}
```

问题：连续 `seekTo` 调用会不断 cancel 旧 job、起新 job，形成"取消-延迟-取消"循环。SeekBar 拖动时若触发多次 `seekTo`，实际 seek 永远在 200ms 延迟中，松手后才执行最后一次 → 表现为「画面不跟随」+「松手后冻结」。

### 2. 手势路径本已"松手才 seek"，但 PlayerView 绑定 ForwardingPlayer 导致状态同步混乱

`PlayerGestureDetector.ACTION_MOVE` 只更新 `seekState.offsetMs`（UI 指示器），**不调 seekTo**。实际 seek 在 `VideoPlayerScreen.kt:405` 的 `LaunchedEffect(seekState.isSeeking)` 中，ACTION_UP 后调 `exoPlayer.seekTo(newPos)`（底层实例，不经过 200ms 防抖）。

问题在于 `PlayerView.player` 绑定的是 `forwardingPlayer`，而手势路径直接调底层 `exoPlayer.seekTo` —— **两条 seek 路径作用于不同对象**，PlayerView 的 UI 状态（进度条位置、buffering 指示）通过 ForwardingPlayer 观察，可能与底层实际播放位置出现短暂不同步，表现为进度条/画面反馈滞后。删除 ForwardingPlayer 后，PlayerView 与底层 player 统一，状态同步清晰。

### 3. LoadControl 配置偏保守（`VideoPlayerScreen.kt:141-149`）

- `bufferForPlaybackAfterRebufferMs=1000`：seek 后要凑够 1s 才恢复播放，叠加任何延迟会突破 0.5s 目标
- `maxBufferMs=15000`：大跨度 seek 后旧 buffer 全废，新位置要重新拉 15s 数据

### 4. `SeekParameters.CLOSEST_SYNC`（`VideoPlayerScreen.kt:175`）

向最近关键帧对齐（可能前或后），拖动时画面"来回跳"的视觉卡顿。

## 目标与非目标

### 目标

1. 水平手势拖动期间画面冻结在拖动前的帧，**不触发任何 seek**；只有时间偏移指示器（"+5秒"）实时更新
2. 松手后**立即** seek 一次，LAN 下恢复时间 ≤0.5s
3. LoadControl 调优，让大跨度 seek 后更快凑够起播 buffer
4. SeekBar 和点击进度条跳转的行为通过删除 ForwardingPlayer 防抖一并修复

### 非目标

- 不做 seek 实时预览缩略图（后续 Round）
- 不做 seek 遮罩 loading（待本轮验证后评估）
- 不改服务端 streaming（`http.ServeContent` 已是 Range 原生支持）
- 不动转码路径（Android 客户端 URL 都不带 `transcode=true`）
- 不改 OkHttp dispatcher/pool（Round 24 已调优）

### 验收标准

- 拖动水平手势时，画面保持拖动前的最后一帧不动，中央"+X秒"指示器跟随手指更新
- 松手后 ≤0.5s 画面跳到目标位置并恢复播放（LAN 环境，常规跳转）
- 大跨度跳转（如 1 小时跨度）≤1s 恢复
- 拖动过程中无 loading 圈闪烁、无画面来回跳
- SeekBar 拖动行为与改动前一致或更好
- 所有格式（.mp4 / .mkv / .avi / .flv）均受益

## 改动点

### 改动 1：删除 `ForwardingPlayer`（`VideoPlayerScreen.kt:195-225`）

删除整个 `forwardingPlayer` remember 块及相关的 `MediaSession` 绑定调整：

- 删除 `forwardingPlayer` remember 块（`VideoPlayerScreen.kt:195-216`）
- `PlayerView.factory` 中 `player = forwardingPlayer` → `player = exoPlayer`（`VideoPlayerScreen.kt:430`）
- `PlayerView.update` 中 `view.player = forwardingPlayer` → `view.player = exoPlayer`（`VideoPlayerScreen.kt:440-441`）
- `MediaSession.Builder(context, forwardingPlayer)` → `MediaSession.Builder(context, exoPlayer)`（`VideoPlayerScreen.kt:219`）
- 删除 `coroutineScope` 变量（仅 ForwardingPlayer 使用，`VideoPlayerScreen.kt:124`）及 `kotlinx.coroutines.launch` 导入（`VideoPlayerScreen.kt:63`）

**理由**：防抖逻辑有 bug 且已无必要 —— 手势路径用 `seekState` 机制隔离，已不连续 seek；SeekBar 本身是松手才 seek；MediaSession 转发的 seek 走 ExoPlayer 原生更直接。

### 改动 2：LoadControl 调优（`VideoPlayerScreen.kt:141-149`）

```kotlin
.setBufferDurationsMs(
    5000,   // minBufferMs（不动）
    30000,  // maxBufferMs：15000 → 30000，预取更多，大跨度 seek 后命中率提高
    250,    // bufferForPlaybackMs（不动）
    300,    // bufferForPlaybackAfterRebufferMs：1000 → 300，seek 后恢复更快
)
```

**内存影响**：视频码率 5Mbps × 30s ≈ 18MB，可接受；`prioritizeTimeOverSizeThresholds=true` 仍生效。

### 改动 3：SeekParameters（`VideoPlayerScreen.kt:175`）

```kotlin
setSeekParameters(SeekParameters.DEFAULT)  // 原 CLOSEST_SYNC
```

`DEFAULT` = `PRIOR_APPROACH_SYNC`，向后对齐到最近关键帧，避免画面来回跳。

### 改动 4：手势路径确认（无代码改动）

确认 `LaunchedEffect(seekState.isSeeking)`（`VideoPlayerScreen.kt:386-409`）在 ACTION_UP 后立即触发 `exoPlayer.seekTo(newPos)`，且 `exoPlayer` 是底层实例，无延迟。改动 1 删除 ForwardingPlayer 后，此路径行为不变。

## 数据流（改动后）

```
用户拖动水平手势
  ↓
PlayerGestureDetector.ACTION_MOVE
  ↓ 更新 seekState { isSeeking=true, offsetMs, basePositionMs }
  ↓ onSeekStateChange(seekState) → 驱动 UI 指示器（"+5秒"）
  ↓ 【不调 seekTo】画面冻结在拖动前的帧
  ↓
用户松手 ACTION_UP
  ↓ seekState.copy(isSeeking=false) → onSeekStateChange
  ↓
VideoPlayerScreen.LaunchedEffect(seekState.isSeeking) 检测 true→false
  ↓ 计算 newPos = (basePos + offsetMs).coerceIn(0, duration)
  ↓ exoPlayer.seekTo(newPos)  ← 立即执行，无延迟
  ↓
ExoPlayer 内部：
  ↓ 丢弃旧 buffer（maxBufferMs=30s 内的旧数据）
  ↓ 发起新 Range 请求到目标位置
  ↓ 凑够 bufferForPlaybackAfterRebufferMs=300ms 后恢复播放
  ↓ SeekParameters.DEFAULT 向后对齐到最近关键帧
```

## 错误处理

无需新增错误处理。改动是「删除有问题的防抖」+「调参数」，不引入新的失败路径。现有错误处理已覆盖：

- seek 目标越界：`coerceIn(0L, duration)`（`VideoPlayerScreen.kt:392-393`）已有
- Range 请求失败：OkHttp + ExoPlayer 原生重试/报错
- 转码流分支（`isTranscodingEnabled`）：走 `setMediaItem` + `prepare`，本次不动

## 回归风险

| 风险 | 缓解 |
|------|------|
| 删除 ForwardingPlayer 后 SeekBar 拖动反而更卡 | SeekBar 本就是松手才 seek（PlayerView 默认行为），防抖本就多余；手工验证清单覆盖 |
| MediaSession 绑定底层 player 后，系统媒体控制 seek 行为变化 | MediaSession 转发的 seek 走 ExoPlayer 原生，行为更直接，无延迟 |
| `maxBufferMs=30000` 增加内存占用 | 视频码率 5Mbps × 30s ≈ 18MB，可接受；`prioritizeTimeOverSizeThresholds=true` 仍生效 |

## 测试策略

### 单元测试

无需新增/改动。`PlayerGestureDetector` 和 `VideoPlayerScreen` 依赖 Compose + ExoPlayer，由手工集成测试覆盖。ForwardingPlayer 删除后，`VideoPlayerIntentBuilderTest` / `PipControllerTest` 不受影响（不测 seek）。

### 手工验证清单

在真机（LAN 环境）上逐项验证：

**手势路径**
1. 播放视频，水平拖动中间区域 → 画面冻结在拖动前帧，"+X秒"指示器跟随手指实时更新
2. 拖动中观察 loading 圈 → **不应出现**
3. 松手 → ≤0.5s 内画面跳到目标位置并恢复播放
4. 连续快速拖动（来回刷）→ 松手后只 seek 一次到最终位置，无画面来回跳
5. 拖到接近结尾（>duration）→ coerceIn 生效
6. 拖到开头（offset 为大负数）→ seek 到 0

**SeekBar 路径（回归验证）**
7. 拖动 SeekBar → 松手后跳转，行为与改动前一致或更好
8. 点击 SeekBar 某位置 → 直接跳转

**大跨度 seek**
9. 从 00:01 跳到 01:00:00 → ≤1s 恢复
10. 连续多次大跨度跳转 → 无累积卡顿

**格式覆盖**
11. .mp4（faststart，baseline）
12. .mkv（索引在尾部）
13. .avi / .flv

**PiP / 生命周期回归**
14. seek 后立即进 PiP → 播放正常
15. seek 后按 Home 切后台 → 回前台后从 seek 位置继续
16. seek 后旋转屏幕 → savedPositionMs 恢复正确

## 受影响文件

| 文件 | 改动 |
|------|------|
| `android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt` | 删除 ForwardingPlayer remember 块；PlayerView 绑定回 exoPlayer；MediaSession 绑定 exoPlayer；LoadControl 参数调整；SeekParameters 改 DEFAULT；删除 coroutineScope 变量及相关导入 |

仅 1 个文件改动。
