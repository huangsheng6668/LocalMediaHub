# HLS Transcode Implementation Plan (B1)

> Spec: `docs/superpowers/specs/2026-09-03-hls-transcode-design.md`

### Task 1: 会话管理器 + 单测
- `transcode_hls.go`：hlsSession / GetOrCreateHlsSession / Touch / 段名校验 / reaper / CloseHLS / status 计数
- 测试：键稳定性、段名白名单、去重、注入式空闲回收；真实 ffmpeg 集成（skip 模式）
- Commit: `feat(server): hls transcode session manager`

### Task 2: 端点 + 路由 + 测试
- handler：MediaHlsPlaylist / MediaHlsSegment；server.go media 组注册；status 端点加 hls 段；Server.Stop 接 CloseHLS
- Commit: `feat(server): hls playlist and segment endpoints`

### Task 3: Android 切换 + seek 统一 + 测试
- buildStreamUrl → internal + HLS 变换；删除三处转码 seek rebuild 分支；fallback 改 seekTo(posMs)
- Commit: `feat(android): switch transcoded playback to hls with native seeking`

### Task 4: E2E 冒烟 + 文档
- 真实 server：hls/playlist → m3u8 内容 → 取段字节；INDEX/AGENTS 更新
- Commit: `docs(server): ...`
