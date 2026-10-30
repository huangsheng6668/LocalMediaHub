# Web 原画直连与 HLS 回退（播放体感优化 Phase 1）— Design

日期：2026-10-30
状态：Phase 1 实施中
前置：`2026-09-03-hls-transcode-b2-web-design.md` ／ `2026-09-06-hls-seek-restart-design.md`

## 问题

web 端当前所有视频无条件走 HLS 转码（`videoPlayer.js` 的 `state.useTranscode = true`，
801b276 移除了原画切换），带来两类体感问题：

1. **拖动进度条黑屏数秒**：目标位置超出当前转码窗口时必须重建会话 —— 前端立即
   `destroyHls()`（画面当场变黑），服务端 playlist 请求同步等待 ffmpeg 产出首个
   playlist + segment（最长 8s），然后 hls.js 重建 MSE 再解码，几段延迟全串行。
2. **播放中间歇卡顿**：MP4(H.264/AAC) 本可浏览器原生播放，却要实时转码 ——
   画质损失、占用 CPU/GPU；编码吞吐不足时会在转码前沿出现周期性等段。

ffmpeg 当前未设置关键帧间隔，默认 GOP=250 帧（~8.3s），`-hls_time 4` 实际切段
仍是 8.3s。本机 1080p30 实测首段就绪 0.83–0.95s；`-g 120` 后可降至 0.54–0.60s，
段长 8.36s→4.02s。该优化归入 Phase 2。

## Phase 1 方案：原画直连 + 自动回退

### 直连判定

- allowlist：`.mp4` / `.m4v` / `.mov` / `.webm`（`shouldTryDirectPlay`，大小写不敏感）
- URL：`GET /api/v1/media/stream?path=...`（`ValidateAccessibleMediaPath` 同时覆盖
  扫描根与 `system.allowed_roots`，浏览模式无需分支）
- `<video>` 无法携带 Authorization header，token 由 `withMediaAuthToken` 以
  `?token=` query 注入（既有机制）
- 直连时 `state.useTranscode = false`、`state.transcodeStartOffset = 0`；进度保存
  仍走绝对时间轴公式，`timeupdate` / `saveProgress` 数学不变
- 续播：`loadedmetadata` 后原生 `currentTime = savedSec`（HTTP Range seek，毫秒级）
- 直连成功路径**不加载 hls.js**（415 KB vendor 脚本继续按需加载）

### 自动回退

- `<video>` 触发 `error` 且 `MediaError.code` 为 3（DECODE）或 4（SRC_NOT_SUPPORTED）
  → 以 `max(当前 currentTime, 打开时续播点)` 向下取整为锚点切换 HLS
- 回退后把该 `file.path` 记入模块级 `directPlayBlocked`；同一页面会话内再次打开
  直接走 HLS，不再重试直连
- 网络错误（code 2）与 401/403 不触发回退：避免掩盖 token / 网络问题
- 回退与并发保护：`directFallbackPending` 防止 error 事件重复触发
- 静默失败：视频轨无法解码但音轨可播时浏览器**不派发 error**，只出黑屏
  （E2E 用 MPEG-4 Part 2 `.mp4` 复现）。`loadedmetadata` 时 `videoWidth === 0`
  同样判定为直连失败并自动回退，避免用户停在黑屏上

### 手动切换

- 控件区恢复「原画 / 转码」文本按钮，显示当前模式，title 说明点击行为
- 切换时以 `state.transcodeStartOffset + currentTime` 为绝对位置重建对应源：
  - → 原画：`transcodeStartOffset = 0`，原生 seek
  - → 转码：`buildHlsPlaylistUrl(..., floor(pos))` 重新锚定（沿用 2026-09-06 契约）

### 修复

- `resolveHlsStrategy` 返回 `'none'`（hls.js 加载失败且浏览器无原生 HLS）时，
  旧代码把 m3u8 塞给 `<video>`（必然黑屏）；改为走 legacy 单路转码
  `/api/v1/media/stream?transcode=true&start=N`
- 进度条 `change` 在直连模式下直接 `currentTime = target`，不得触发 HLS 重锚
- 关闭弹窗时先清 `state.playingFile` 再卸载媒体，避免 `error` 误触发回退

## 不做（Phase 2）

- ffmpeg `-g 120`（4s 关键帧）与首段产出提速
- 拖动时保留旧画面、预热新会话后再切换
- 「定位中 / 缓冲中」overlay

## 影响面

- `server/internal/web/hlsCompat.js`：新增 4 个纯函数
- `server/internal/web/videoPlayer.js`：源选择、自动回退、手动切换
- `server/internal/web/index.html` / `dom.js` / `css/views/video.css`：切换按钮
- 服务端无改动（复用既有 `/api/v1/media/stream` 的 Range 路径）

## 验证

- `cd server/internal/web && node --test`（新增纯函数用例 + 全量回归）
- `cd server && go build ./cmd/server`（web 静态资源 embed 可编译）
- 浏览器 E2E：临时 media root 放 MP4 / MKV / ProRes MOV
  - MP4：直连播放 + 任意拖动即时，无 ffmpeg 会话
  - MKV：仍走 HLS，播放正常
  - ProRes MOV：直连报错后自动切 HLS
  - 手动「原画/转码」双向切换后播放位置连续

## 风险

- MP4 内封 AC-3/E-AC-3 等 Chrome 不支持的音频：通常会触发 `error` 并自动回退；
  若个别浏览器只静音播放（`videoWidth > 0` 不满足静默黑屏判定），用户可用
  「原画/转码」按钮手动切回转码（与 Android 既有策略一致）
- 高码率原盘在弱 Wi-Fi 下直连可能带宽不足：同样由手动切换兜底
