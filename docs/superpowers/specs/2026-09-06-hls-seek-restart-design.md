# HLS seek-anchored transcode restart (进度条任意拖动) — Design

日期：2026-09-06
状态：实施中
前置：`2026-09-03-hls-transcode-design.md`（HLS 会话管理）／`2026-09-03-hls-transcode-b2-design.md`（web hls.js 接入）

## 问题

HLS 转码是渐进式增长播放列表：会话从 0 开始转码，4 小时视频以 ~15-30x 实时推进，
全量转完需 8-16 分钟。期间 web 播放器把进度条拖到**尚未转码到的位置**时，
hls.js 把 seek 钳制到直播边缘 —— 拖动被完全忽略（UI 时间不动、无报错），
用户感知为"拖动进度条没反应"。已完整缓存（看完过）的视频不受影响，
造成"有些视频能拖、有些不能"的随机观感。续播到远处位置同样被钳制。

复现（2026-09-06，Chrome + 6GB 真实 .ts）：拖到 7200s，转码窗口 0-225s，
currentTime 落在 227s，UI 显示 03:46。

## 方案：seek 锚定会话（server 端 `-ss` 重锚）

拖动目标超出当前会话可 seek 窗口时，不再原地 seek，而是以目标位置为新锚点
创建转码会话，旧会话即刻取消：

### Server

- `GetOrCreateHlsSession(path, modTime, startSec)`：
  - 会话 key 追加 `startSec`（同文件同锚点才复用）
  - `startSec > 0` 时 ffmpeg argv 前插 `-ss <startSec>`（输入端快进，秒级起播）
  - 创建新会话时取消同源文件其他 **running** 会话（completed 缓存保留），
    防止连续拖动堆积 ffmpeg
- `/api/v1/media/hls/playlist|segment` 接受 `?start=`（0..86400 校验，取整）；
  playlist 内改写出的 segment URL 同样携带 `&start=`，保证 segment 端点
  dedup 命中同一会话
- 时间轴约定：`-ss T` 会话的播放列表媒体时间从 0 开始，0 ≡ 绝对位置 T

### Web

- `hlsCompat.buildHlsPlaylistUrl(apiBase, path, startSec)` 追加 `&start=`
- 新纯函数 `needsHlsRestart(targetTime, offsetSec, seekableEnd)`：
  目标换算到会话相对时间后落在 `[−0.5, seekableEnd+5s]` 内 → 原生 seek；
  否则 → 重建源
- `videoPlayer.js`：
  - 进度条 change（HLS 分支）：需要重锚时 `applySource(buildHlsPlaylistUrl(..., startSec))`
    且 `state.transcodeStartOffset = startSec*1000`（沿用既有绝对时间轴字段，
    timeupdate/续播/toggle 的绝对位置数学不变）；窗口内则 `currentTime = target − offset`
  - 续播（HLS 模式）：不再 MANIFEST_PARSED 后 seek（会被钳制），
    直接以 `start=floor(续播位置)` 建会话
  - 转码开关切回 HLS：以当前绝对位置为新锚点

### 不做（YAGNI）

- segment URL 不携带 token（native HLS + token 模式的已知边缘，
  Chrome/Edge/Firefox 均走 hls.js xhrSetup，维持 b2 spec 结论）
- 不做"探测完整缓存后优先复用 start=0 会话"——多一次会话创建请求，
  磁盘 cap + 同源取消已兜底

## 影响面

- Android：playlist URL 的 `?start=` 透传（VideoPlayerScreen 的 /stream→/hls/playlist
  替换保留 query），服务端从忽略 start 变为支持，Android 续播同步受益
- 磁盘：连续重锚产生多个会话目录，completed 会话仍受 4GiB LRU cap 约束；
  被取消的 running 会话由 waiter 整目录删除

## 验证

- go test：key 含 start、argv 含/不含 `-ss`、ClientPlaylist 携带 `&start=`
- node --test：buildHlsPlaylistUrl start 参数、needsHlsRestart 边界
- 浏览器 E2E（真实 6GB .ts）：远拖落位起播、窗口内/回拖原生 seek、续播、连续拖动
