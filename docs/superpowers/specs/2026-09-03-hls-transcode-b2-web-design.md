# HLS Transcode B2: Web Client Design（Web 端接入 HLS）

**Date:** 2026-09-03
**Topic:** HLS spec（2026-09-03-hls-transcode）Phase B1 的后续 **B2**：Web 播放器把转码路径从单管道 fMP4 + seek 重启切到 B1 的 HLS playlist，获得原生随机 seek；hls.js 以自托管 vendor 形式引入，保持 CSP 与零构建流程。

## 1. Design

### 1.1 Vendor 引入

- `server/internal/web/vendor/hls.min.js`（hls.js **1.5.20**，Apache-2.0，415KB）+ `hls.min.js.sha256` 校验和文件（同 libffmpeg.so 先例）。
- `web.go` embed 模式追加 `vendor/hls.min.js`；`index.html` 在 `app.js` 之前以 `<script defer>` 加载（defer 经典脚本先于 module 执行），暴露全局 `Hls`。
- 不走 CDN：CSP script-src self 禁止外源；LAN 场景 415KB 一次性加载可忽略。

### 1.2 三级播放策略（hlsCompat.js 纯函数，可单测）

"```"
resolveHlsStrategy(video):
  1. window.Hls && Hls.isSupported()  -> "hlsjs"   （Chrome/Firefox/Edge，MSE）
  2. video.canPlayType(mpegurl)       -> "native"  （Safari）
  3. else                             -> "none"    -> 完整回退旧 fMP4 管道路径（零退化）
"```"

优先 hls.js 而非原生：token 模式下 hls.js 可经 xhrSetup 给所有请求（playlist + 分段）注入 Bearer header；Safari 原生路径的分段 URL 无法带凭据（token 查询参数只挂在 playlist 上，分段 URL 相对解析不带参数）——开放 LAN 模式不受影响，token 模式 + 仅原生可用的浏览器为已知边界（代码注释记录）。

### 1.3 videoPlayer.js 改造

- 转码模式 URL 统一为 `/api/v1/media/hls/playlist?path=`（`ValidateAccessibleMediaPath` 覆盖扫描根 + system roots，两种浏览模式合一，system/stream 转码分支消失）。
- HLS 模式：三处 URL-rebuild 全部删除——播放器构建（续播改为 MANIFEST_PARSED/loadedmetadata 后 currentTime 定位）、进度条 seek（原生 currentTime）、toggle（原画与转码两态，copy/libx264 三态循环仅在 legacy 回退时保留）。`transcodeStartOffset` 在 HLS 模式恒 0，进度保存公式不变。
- 生命周期：模块级 hls 实例，关闭弹窗 / 切回原画 / 重新打开时 destroy() 防泄漏。

## 2. Non-Goals

- HLS 段的 copy-remux 模式（v1 恒走 auto 编码链；copy-remux 化 HLS 留作后续优化）。
- Web 端 codec 预探测 UI。

## 3. Testing

- `hlsCompat.test.mjs`：URL 构建（encode/apiBase）、扩展名判定、策略解析（伪造 canPlayType / jsdom 无 MSE 环境）。
- `node --test` + `xsscheck` 回归；E2E：server 静态服务 vendor 文件 200 + playlist 流程复用 B1 验证。

## 4. Rollout

- hls.js 加载失败（vendor 损坏）不崩溃：策略解析返回 none → 旧管道兜底。
- token 模式 + Safari：原生路径分段可能 401，用户可切回旧管道按钮路径（legacy toggle 保留）。
