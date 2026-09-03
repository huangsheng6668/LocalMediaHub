# Android Auto Transcode Fallback Design（播放失败自动转码重试）

**Date:** 2026-09-03
**Topic:** 转码现代化 spec（2026-09-03）预留的 **Phase C** / 性能评估 **P3**：ExoPlayer 因 codec 不支持播放失败时，自动以 `transcode=true` 一次性重试，取代"用户手动开转码"的记忆负担。服务端 P0（硬编探测链）已让转码重试的 CPU 代价降到可接受。

## 1. Background & Motivation

- 现状（`VideoPlayerScreen.kt`）：转码与否由上游传入的 URL 是否含 `transcode=true` 决定（`isTranscodingEnabled` L414），用户需自己知道"这台手机播不动 HEVC 要开转码"。
- 全代码库 **没有任何 `onPlayerError` 处理**（grep 证实）——codec 失败直接黑屏卡死。
- Web 端早已对非原生格式自动转码（videoPlayer.js L85）；Android 是唯一靠手动的端。
- 典型触发：HEVC/AV1 4K 片源在无相应硬解的设备上 `ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES` / `FORMAT_UNSUPPORTED`，或容器不支持 `PARSING_CONTAINER_UNSUPPORTED`——全部可由服务端转码为 H.264/fMP4 解决。

## 2. Design

### 2.1 纯决策函数（可单测）

```
internal fun shouldAutoFallbackToTranscode(
    isTranscoding: Boolean,      // 已在转码 → false（转码流自身失败说明问题不在 codec）
    alreadyAttempted: Boolean,   // 已自动重试过一次 → false（防循环）
    isLocalUri: Boolean,         // file:// / content:// 本地文件无服务端可转 → false
    errorCode: Int,              // PlaybackException.errorCode
): Boolean
```

命中的错误码（media3 `PlaybackException` 常量，按名引用不硬编码数字）：

- `ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES`（能力不足，最常见）
- `ERROR_CODE_DECODING_FORMAT_UNSUPPORTED`（无对应解码器）
- `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`（容器不支持，转码重封装为 fMP4 可解）

其余错误码（网络 / IO / 远程错误）不重试——转码解决不了。

### 2.2 接线（VideoPlayerScreen.kt）

- `Player.Listener` 增加 `onPlayerError`：满足决策条件 → 记录 `savedPositionMs = currentPosition`（失败点续播）→ `isTranscodingEnabled = true` → `buildStreamUrl(streamUrl, true, posSec)` 剥离旧参数重建 → 原地 `setMediaItem + prepare + play`（与既有 seek 路径 L443 完全同模式，且后续手势 seek / 重启 chip 的重建逻辑自动保持 transcode=true）。
- `autoTranscodeFallbackAttempted` 状态 `remember(streamUrl)` 键控——切换视频自动重置。
- 触发时 `Toast` 提示用户已自动切换（新增 strings.xml 资源），避免"突然能播了"的困惑。

## 3. Non-Goals

- 播放前的 codec 预探测 UI（Web 端 unsupported badge 的对应物）——错误驱动重试已覆盖主场景，预探测需要 server 侧元数据另做。
- 码率/分辨率协商（转码固定输出参数，见 server 端 spec）。
- 转码流自身失败后的二次策略。

## 4. Testing

- `VideoPlayerFallbackTest.kt`（纯 JUnit，无 Robolectric）：决策函数全分支——已在转码 / 已重试 / 本地 URI / 三个命中码 / 网络类错误码。
- `./gradlew testDebugUnitTest` 全量回归。

## 5. Rollout

- 纯客户端变更，配合任意版本 server（转码端点长期存在）；与 P0 硬编 server 配合时重试的 CPU 代价最低。
