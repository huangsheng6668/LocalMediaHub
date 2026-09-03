# Android Auto Transcode Fallback Implementation Plan

> Spec: `docs/superpowers/specs/2026-09-03-android-transcode-fallback-design.md`（本 plan 与 spec 同日合并提交，scope 紧凑）。

### Task 1: 决策函数 + 单测
- `VideoPlayerScreen.kt` 顶层 `internal fun shouldAutoFallbackToTranscode(...)` + `internal fun isLocalStreamUri(url)`
- `android/app/src/test/java/com/juziss/localmediahub/ui/screen/VideoPlayerFallbackTest.kt` 全分支
- Commit: `feat(android): transcode fallback decision function with unit tests`

### Task 2: 播放器接线 + Toast
- `onPlayerError` listener（沿用 L443 的原地重建模式）+ `autoTranscodeFallbackAttempted` remember(streamUrl) + strings.xml 资源
- Commit: `feat(android): auto transcode fallback on codec playback errors`

### Task 3: 验证 + 文档
- `./gradlew testDebugUnitTest`；INDEX spec 列表 + AGENTS Android 段更新
- Commit: `docs(android): ...`
