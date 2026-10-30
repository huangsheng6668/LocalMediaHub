# Web 原画直连与 HLS 回退 — 实施计划（Phase 1）

前置 spec：`docs/superpowers/specs/2026-10-30-web-direct-play-design.md`
约定：每个任务结束跑一次对应验证；纯函数严格 RED → GREEN；UI 接线以浏览器验证为准。

### 任务 1：直连纯函数（RED）

- 文件：`server/internal/web/hlsCompat.test.mjs`
- 在 import 中加入 `shouldTryDirectPlay, buildDirectStreamUrl, buildTranscodeStreamUrl, isUnsupportedMediaError`
- 追加测试：

```js
test('shouldTryDirectPlay allows only browser-native containers', () => {
    assert.equal(shouldTryDirectPlay('movie.mp4'), true);
    assert.equal(shouldTryDirectPlay('movie.MOV'), true);
    assert.equal(shouldTryDirectPlay('movie.m4v'), true);
    assert.equal(shouldTryDirectPlay('movie.webm'), true);
    assert.equal(shouldTryDirectPlay('movie.mkv'), false);
    assert.equal(shouldTryDirectPlay('movie.ts'), false);
    assert.equal(shouldTryDirectPlay('no-extension'), false);
    assert.equal(shouldTryDirectPlay(''), false);
});

test('buildDirectStreamUrl encodes the path', () => {
    assert.equal(
        buildDirectStreamUrl('http://h:1', 'D:\\Media\\a b.mp4'),
        'http://h:1/api/v1/media/stream?path=D%3A%5CMedia%5Ca%20b.mp4'
    );
});

test('buildTranscodeStreamUrl adds transcode and floors the start anchor', () => {
    assert.equal(
        buildTranscodeStreamUrl('http://h:1', 'a.mkv', 0),
        'http://h:1/api/v1/media/stream?path=a.mkv&transcode=true'
    );
    assert.equal(
        buildTranscodeStreamUrl('http://h:1', 'a.mkv', 90.9),
        'http://h:1/api/v1/media/stream?path=a.mkv&transcode=true&start=90'
    );
});

test('isUnsupportedMediaError matches decode and src-not-supported only', () => {
    assert.equal(isUnsupportedMediaError(3), true);
    assert.equal(isUnsupportedMediaError(4), true);
    assert.equal(isUnsupportedMediaError(2), false);
    assert.equal(isUnsupportedMediaError(1), false);
    assert.equal(isUnsupportedMediaError(null), false);
});
```

- 验证：`cd server/internal/web && node --test hlsCompat.test.mjs` 失败（导出不存在）

### 任务 2：直连纯函数（GREEN）

- 文件：`server/internal/web/hlsCompat.js`
- 追加：

```js
// Direct-play allowlist: containers browsers demux natively. These are served
// over HTTP Range by /api/v1/media/stream, so seeking is handled by the
// browser itself — no ffmpeg session, no re-encode. Everything else (mkv/ts/
// avi/...) keeps the HLS transcode route.
const DIRECT_PLAY_EXTS = ['.mp4', '.m4v', '.mov', '.webm'];

export function shouldTryDirectPlay(fileName) {
    if (!fileName) return false;
    const dot = fileName.lastIndexOf('.');
    if (dot < 0) return false;
    return DIRECT_PLAY_EXTS.includes(fileName.substring(dot).toLowerCase());
}

export function buildDirectStreamUrl(apiBase, path) {
    return `${apiBase}/api/v1/media/stream?path=${encodeURIComponent(path)}`;
}

// Legacy single-pipe transcode fallback (only used when neither hls.js nor
// native HLS exists). startSec anchors ffmpeg input-side, same as the HLS
// playlist's ?start= contract.
export function buildTranscodeStreamUrl(apiBase, path, startSec) {
    let url = `${apiBase}/api/v1/media/stream?path=${encodeURIComponent(path)}&transcode=true`;
    if (Number.isFinite(startSec) && startSec > 0) {
        url += `&start=${Math.floor(startSec)}`;
    }
    return url;
}

// 3 = MEDIA_ERR_DECODE, 4 = MEDIA_ERR_SRC_NOT_SUPPORTED. Network (2) and
// abort (1) must NOT trigger the transcode fallback.
export function isUnsupportedMediaError(code) {
    return code === 3 || code === 4;
}
```

- 验证：`node --test hlsCompat.test.mjs` 全绿；`node --test` 全量绿

### 任务 3：原画/转码按钮 UI

- `server/internal/web/index.html`：`controls-right` 内、`.video-speed-wrap` 之前插入

```html
<button class="video-control-btn video-control-btn--text" id="btn-video-source" title="当前播放源">原画</button>
```

- `server/internal/web/dom.js`：`btnVideoSpeed` 之后加

```js
btnVideoSource: document.getElementById('btn-video-source'),
```

- `server/internal/web/css/views/video.css`：`.video-control-btn:active` 之后加

```css
/* Text variant: icon buttons are fixed 36x36 circles, mode labels need width. */
.video-control-btn--text {
    width: auto;
    min-width: 36px;
    padding: 0 10px;
    border-radius: 18px;
    font-size: 12px;
}
```

- 验证：`node --test` 不受影响；浏览器打开视频弹窗可见按钮

### 任务 4：videoPlayer 源选择（直连优先 + 惰性 HLS）

- 文件：`server/internal/web/videoPlayer.js`
- import 改为：

```js
import {
    buildHlsPlaylistUrl, buildDirectStreamUrl, buildTranscodeStreamUrl,
    resolveHlsStrategy, needsHlsRestart, shouldTryDirectPlay, isUnsupportedMediaError,
} from './hlsCompat.js';
```

- 模块状态追加：

```js
// Phase 1 direct-play state: paths whose native decode failed this session,
// the absolute position the current direct attempt started at, and a re-entry
// guard for the error handler.
const directPlayBlocked = new Set();
let directPlayStartSec = 0;
let directFallbackPending = false;
```

- 新增 `updateSourceButtonState()` / `startDirectPlayback()` / `startHlsPlayback()`（完整代码见实现）
- `openVideoPlayer`：删除开头的 `await loadHls()` + `hlsStrategy = resolve...` +
  `state.useTranscode = true`；续播变量 `hlsStartSec` 改名 `startSec`（直连与 HLS 共用）；
  起播段替换为：

```js
if (shouldTryDirectPlay(file.name) && !directPlayBlocked.has(file.path)) {
    startDirectPlayback(file, startSec);
} else {
    await startHlsPlayback(file, startSec);
}
elements.videoPlayer.play().catch(() => {});
```

- 验证：`node --test`；浏览器 MP4 直连出画，Network 无 hls.min.js / 无 hls playlist 请求

### 任务 5：错误自动回退 + `none` 策略修复

- `videoPlayer.js` 的 `setupVideoPlayerListeners` 内新增 `error` 监听（完整代码见实现）：
  code 3/4 → `directPlayBlocked.add` → `startHlsPlayback(file, floor(pos))` → `play()`
- E2E 补充：视频轨不可解码但音轨可播时浏览器不抛 `error`（MPEG-4 Part 2 `.mp4`
  实测为黑屏 + 有声音），新增 `loadedmetadata` 且 `videoWidth === 0` 的静默失败判定，
  与 `error` 共用 `fallbackToTranscode()`
- `startHlsPlayback` 中 `hlsStrategy === 'none'` 时使用 `buildTranscodeStreamUrl`（legacy 管道），
  不再把 m3u8 交给不支持 HLS 的浏览器
- 关闭弹窗：在 `removeAttribute('src')` 之前置 `state.playingFile = null`，避免 error 误判
- 验证：不可解码样本打开后自动进入 HLS 转码并播放（含 toast）

### 任务 6：直连进度条 + 手动切换

- `change` 处理器开头加直连分支：

```js
if (!state.playingFile) { state.isDraggingProgress = false; return; }
if (!state.useTranscode) {
    elements.videoPlayer.currentTime = Math.max(0, targetTime);
    state.isDraggingProgress = false;
    return;
}
```

- `btnVideoSource` click：按 `transcodeStartOffset + currentTime` 的绝对位置双向切换，
  切换前记录 `wasPlaying`，切换后恢复播放
- 验证：MP4 拖动即时；原画 ↔ 转码切换后时间连续

### 任务 7：单测、构建、浏览器 E2E

- `cd server/internal/web && node --test`
- `cd server && go build ./cmd/server`
- 临时目录建 `config.yaml`（port 18080，roots 指向测试媒体），跑 `--headless`
- 测试素材：`src.mp4`（H.264/AAC）、`src.mkv`（copy 自 mp4）、`prores.mov`（5s）
- Tabbit/浏览器操作：MP4 播放与拖动、MKV 播放、ProRes 回退、按钮切换

### 任务 8：文档

- `AGENTS.md` Web 管理界面段落补一句播放源策略
- `README.md` 视频功能条目补「原画直连，失败自动回退转码」
