# HLS Transcode Design（转码流分段输出与随机 seek）

**Date:** 2026-09-03
**Topic:** 转码现代化 spec 预留的 **Phase B**。把"单管道 chunked fMP4、seek 需重启 ffmpeg"的转码路径升级为 **HLS 分段落盘**：转码流获得原生随机 seek、已完成的转码成为磁盘缓存（重看免费）、失败重试不重复付转码成本。**B1 = server + Android**（本轮）；**B2 = Web（hls.js 本地引入，Non-Goal 另立 spec）**。

## 1. Background & Motivation

现状（`streaming.go` serveTranscoded + 两端客户端）：

- 转码 = `ffmpeg … -f mp4 -movflags frag_keyframe+empty_moov pipe:1`，单管道无随机访问（`Accept-Ranges: none`）。
- **每次 seek = 杀 ffmpeg + 重建 URL `start=N` + 客户端重新 prepare**（Android `buildStreamUrl`/Round 20 分支；Web `transcodeStartOffset`）。频繁拖进度条 = 反复全量重启转码。
- 转码结果不可复用：同一集剧看完即弃，重看 / 续播全部重新付 CPU。
- Phase C（Android 自动 fallback）落地后，进入转码模式的概率显著上升，seek 体验短板被放大。

## 2. Goals / Non-Goals

**Goals (B1)**

1. server：HLS 会话管理器 —— 按需启动 ffmpeg 落盘分段（`.cache/hls/<key>/`），会话去重、空闲回收、磁盘总量上限淘汰。
2. 新端点（media 组，Bearer）：`GET /api/v1/media/hls/playlist?path=` 与 `GET /api/v1/media/hls/segment?path=&name=segNNNNN.ts`。
3. Android：转码模式 URL 切到 HLS playlist；**ExoPlayer 原生 HLS 支持 = 客户端 seek 特殊分支全部删除**（直接 `seekTo`）。
4. 复用 P0 资产：编码器探测链（auto 编码器）+ 会话信号量（ffmpeg 存活期占坑）。

**Non-Goals**

- B2：Web 端 HLS（需自托管 hls.js，CSP 兼容，另立 spec）；Web 维持现状 fMP4 管道路径不变。
- 码率自适应（多 master playlist）——单轨输出。
- 转码中途续写（被回收的未完成会话直接删除，重看从头转码）。vcodec 显式参数透传（HLS v1 恒用 auto 探测链）。

## 3. Design

### 3.1 会话管理（`server/internal/service/transcode_hls.go`，方法挂在 StreamingService 上，复用 prober + transcodeSem）

- **会话键**：`sha256(cleanPath + modTimeUnixNano + encoderName)[:16]` —— 同文件去重；文件变更自然换键。
- **ffmpeg 参数**：`-y -i <sanitize后的path> -vcodec <enc> <knownEncoderArgs…> -acodec aac -f hls -hls_time 4 -hls_list_size 0 -hls_flags independent_segments -hls_segment_filename <dir>/seg%05d.ts <dir>/index.m3u8`（全片转码，无 `-ss`）。`independent_segments` 保证任意段起播。
- **GetOrCreateHlsSession**：命中 → `Touch(lastAccess)` 返回；未命中 → mkdir + `acquireTranscodeSlot`（占 P0 信号量，ffmpeg 退出即释放）+ 后台跑 ffmpeg + 轮询等 `index.m3u8` 出现（200ms 步进，上限 8s；超时 503）。ffmpeg 退出码非 0 且分段时间 < 1 段 → 会话失败删除目录。
- **回收 reaper**（60s 周期，惰性启动）：运行中且 `lastAccess` > 3min → kill + **删除目录**（未完成会话不留半成品）；已完成会话计入磁盘总量，超过 **4GiB 常量上限**（同 thumbnail 512MB 先例的姿态，YAGNI 不进 config）按 lastAccess 最旧淘汰。`CloseHLS()`（Server.Stop 接线）kill 全部运行中会话，保留已完成缓存。

### 3.2 端点（handler/media.go + server.go 路由）

- `GET /api/v1/media/hls/playlist?path=`：`ValidateAccessibleMediaPath`（三件套）→ GetOrCreate → `c.File(playlist)`，`Content-Type: application/vnd.apple.mpegurl` + `Cache-Control: no-cache`（playlist 在完成前持续增长）。
- `GET /api/v1/media/hls/segment?path=&name=`：**段名严格校验** `^seg\d{5}\.ts$`（正则白名单，杜绝 traversal），经会话目录 Join 后 `http.ServeContent`（Range 免费）。
- 频率：playlist/segment 是常规播放流量（每几秒一次），**不挂** 5/min 的 transcode 限流（该限流匹配 `transcode=true` query，仅作用于旧 /stream 路由——天然不冲突）；ffmpeg 启动已被会话去重 + 信号量 + 空闲回收三重约束。
- 旧 `/stream?transcode=true` 管道路径**原样保留**（Web 继续用）。`/admin/transcode/status` 增加 `hls` 段（运行中 / 总会话数）。

### 3.3 Android（VideoPlayerScreen.kt）

- `buildStreamUrl(transcode=true)` 改为产出 HLS URL：剥离旧参数后把路径尾 `/stream` 替换为 `/hls/playlist`，**不再拼 `start=`**（全片转码）。转码入口只有 Phase C fallback 与本函数（已 grep 证实无其他构造点），改动面收敛。
- ExoPlayer `DefaultMediaSourceFactory` 按 URI 自动识别 m3u8 → 走 DefaultHlsMediaSource，OkHttp 数据源照常注入 Bearer。
- **删除转码 seek 特殊分支**：播放器构建时的 URL-rebuild + `seekTo(0)`、手势 seek 的 rebuild 分支、重启 chip 的 rebuild 分支——HLS 与直连统一为原生 `seekTo`。Phase C fallback 重试后 `seekTo(posMs)` 续播失败点。

## 4. Security Review

- 段名白名单正则 + 会话目录 Join —— traversal 不可能逃出 `.cache/hls/<key>/`。
- 源路径照走三件套校验后才进会话管理；argv 中的路径过 `sanitizeMediaArg`。
- 会话键含 modTime：TOCTOU（校验后文件被换）最坏导致转码旧内容，播放校验不受影响。
- 无新敏感面；端点挂 media 组 Bearer。

## 5. Testing

- Go 单测：会话键稳定性（同文件同键 / 变更换键）、段名校验（`../`、绝对路径、大小写、超长数字全拒）、GetOrCreate 去重、空闲回收（注入短 lastAccess）。
- 真实 ffmpeg 集成测试（LookPath skip 模式，同 TestEncoderProberRealFFmpeg 先例）：2s 测试视频 → GetOrCreate → playlist 出现 + 含 `#EXTM3U` + 段文件可读 + ffmpeg 自然退出。
- Android：`buildStreamUrl` 转 internal + HLS URL 变换单测（参数剥离 / /stream→/hls/playlist / 无 start 残留）。
- 回归：`go test ./...` + `./gradlew testDebugUnitTest`。

## 6. Rollout / Compatibility

- server 新增端点纯增量；旧管道 / Web 零变化。Android 与 server 同仓发版，无需兼容旧 server（个人项目）。
- 首次使用自动建 `.cache/hls/`；重启不清缓存（跨会话复用已完成的转码）。
