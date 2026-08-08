# Web 视频播放器增强实施计划（倍速 / 滚轮 / 进度记忆）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Web 端 `videoPlayer.js` 原生实现倍速播放、鼠标滚轮调音量、localStorage 跨会话进度记忆三个功能。

**Architecture:** 可测纯逻辑独立成模块（`videoProgress.js` 进度持久化、`videoHelpers.js` 倍速/滚轮纯函数），UI 接入内联 `videoPlayer.js`。沿用项目 `node:test` + jsdom 测试范式，纯函数 TDD，UI 接入手动验证。

**Tech Stack:** 原生 ES Modules（`import`）、HTML5 `<video>` API（`playbackRate`/`volume`）、`localStorage`、`node:test` + `node:assert/strict` + `jsdom` 25.0.1。

**Spec:** `docs/superpowers/specs/2026-08-08-web-video-player-enhancements-design.md`

## Global Constraints

- 测试运行：`cd server/internal/web && node --test <file>.test.mjs`（或 `npm test` 跑全部）
- 测试框架：`node:test` + `node:assert/strict`；测试文件命名 `*.test.mjs`（ESM）
- localStorage key：`video_progress:` + `relative_path`
- 倍速档位：`[0.75, 1, 1.25, 1.5, 2, 3]`，循环顺序 `1→1.25→1.5→2→3→0.75→1`
- 滚轮音量步进：`0.05`（与 ↑/↓ 键一致）
- 看完阈值：`position/duration ≥ 0.95`
- 不碰 Android、不碰服务端、不引入外部库
- DOM 元素集中注册于 `dom.js`（`getElementById`），控制条 HTML 在 `index.html`
- 提交惯例：英文 conventional commit subject + 中文 body + trailer `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
- 所有命令在仓库根 `E:\github_project\LocalMediaHub` 运行（shell 为 bash）

## File Structure

| 文件 | 职责 | 动作 |
|---|---|---|
| `server/internal/web/videoProgress.js` | 进度记忆数据层：`save/load/clear/isCompleted`（纯函数 + localStorage） | 新建 |
| `server/internal/web/videoProgress.test.mjs` | `videoProgress` 单测（纯函数 + jsdom localStorage） | 新建 |
| `server/internal/web/videoHelpers.js` | 倍速/滚轮纯函数：`nextSpeed`、`wheelToVolume` | 新建 |
| `server/internal/web/videoHelpers.test.mjs` | `videoHelpers` 单测（纯函数，无 DOM） | 新建 |
| `server/internal/web/videoPlayer.js` | UI 接入：倍速按钮、滚轮监听、进度读写 | 修改 |
| `server/internal/web/index.html` | 控制条 `controls-right` 加倍速按钮 | 修改 |
| `server/internal/web/dom.js` | 注册 `btnVideoSpeed` | 修改 |

**分解理由**：`videoProgress.js` 是数据持久化（与 `progress.js` 范式一致）；`videoHelpers.js` 是无 DOM 依赖的纯逻辑（与 `readerScrubber.js` 的 `progressToChapterIndex` 范式一致，纯函数可零成本单测）；倍速/滚轮的 UI 接入（事件绑定）内联 `videoPlayer.js`（与 `videoPlayer.js` 现有事件绑定同处）。`videoPlayer.js` 当前无自动化测试传统，UI 接入靠手动验证。

---

## Task 1: `videoProgress.js` 数据层（TDD）

**Files:**
- Create: `server/internal/web/videoProgress.js`
- Test: `server/internal/web/videoProgress.test.mjs`

**Interfaces:**
- Produces:
  - `isCompleted(positionMs: number, durationMs: number) -> boolean`
  - `saveProgress(relPath: string, { positionMs: number, durationMs: number }) -> void`
  - `loadProgress(relPath: string) -> { positionMs: number, durationMs: number } | null`
  - `clearProgress(relPath: string) -> void`

- [ ] **Step 1: 写失败测试**

Create `server/internal/web/videoProgress.test.mjs`:

```js
import { test, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';
import { saveProgress, loadProgress, clearProgress, isCompleted } from './videoProgress.js';

// ---- isCompleted: 纯函数，无需 DOM ----
test('isCompleted: ratio >= 0.95 → true', () => {
    assert.equal(isCompleted(95, 100), true);
    assert.equal(isCompleted(99, 100), true);
    assert.equal(isCompleted(100, 100), true);
});
test('isCompleted: ratio < 0.95 → false', () => {
    assert.equal(isCompleted(94.9, 100), false);
    assert.equal(isCompleted(0, 100), false);
});
test('isCompleted: durationMs <= 0 → false', () => {
    assert.equal(isCompleted(100, 0), false);
    assert.equal(isCompleted(100, -1), false);
});

// ---- localStorage 函数：需 jsdom 暴露 global.localStorage ----
let dom;
beforeEach(() => {
    dom = new JSDOM('<!DOCTYPE html>', { url: 'http://localhost/' });
    global.window = dom.window;
    global.localStorage = dom.window.localStorage;
});
afterEach(() => {
    delete global.localStorage;
    delete global.window;
});

test('saveProgress/loadProgress: 往返', () => {
    saveProgress('movies/a.mkv', { positionMs: 5000, durationMs: 10000 });
    const p = loadProgress('movies/a.mkv');
    assert.ok(p);
    assert.equal(p.positionMs, 5000);
    assert.equal(p.durationMs, 10000);
});

test('loadProgress: 无记录 → null', () => {
    assert.equal(loadProgress('nope.mp4'), null);
});

test('loadProgress: 不同文件 key 隔离', () => {
    saveProgress('a.mp4', { positionMs: 1, durationMs: 10 });
    saveProgress('b.mp4', { positionMs: 2, durationMs: 20 });
    assert.equal(loadProgress('a.mp4').positionMs, 1);
    assert.equal(loadProgress('b.mp4').positionMs, 2);
});

test('clearProgress: 删除后 load → null', () => {
    saveProgress('c.mp4', { positionMs: 9, durationMs: 10 });
    clearProgress('c.mp4');
    assert.equal(loadProgress('c.mp4'), null);
});

test('saveProgress: 写入 video_progress: 前缀且含 updatedAt', () => {
    saveProgress('d.mp4', { positionMs: 5, durationMs: 10 });
    const raw = global.localStorage.getItem('video_progress:d.mp4');
    assert.ok(raw);
    const parsed = JSON.parse(raw);
    assert.equal(parsed.positionMs, 5);
    assert.equal(parsed.durationMs, 10);
    assert.equal(typeof parsed.updatedAt, 'number');
});

test('loadProgress: JSON 损坏 → null', () => {
    global.localStorage.setItem('video_progress:e.mp4', '{not json');
    assert.equal(loadProgress('e.mp4'), null);
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server/internal/web && node --test videoProgress.test.mjs`
Expected: FAIL（`Cannot find module './videoProgress.js'`）

- [ ] **Step 3: 写最小实现**

Create `server/internal/web/videoProgress.js`:

```js
// 视频播放进度记忆数据层。纯函数 + localStorage。
// 与 reader 的 book_progress: 范式一致；key 用 relative_path（不随服务端目录变化）。
// 所有函数 try/catch：隐私模式 / 配额满 / JSON 损坏时静默，绝不影响播放。

const PREFIX = 'video_progress:';

function key(relPath) {
    return PREFIX + relPath;
}

// 是否看完：position/duration >= 0.95 且 duration 有效。
export function isCompleted(positionMs, durationMs) {
    if (!durationMs || durationMs <= 0) return false;
    return positionMs / durationMs >= 0.95;
}

export function saveProgress(relPath, { positionMs, durationMs }) {
    try {
        localStorage.setItem(key(relPath), JSON.stringify({
            positionMs,
            durationMs,
            updatedAt: Date.now(),
        }));
    } catch (e) {
        // 隐私模式 / 配额满：静默失败。
    }
}

export function loadProgress(relPath) {
    try {
        const raw = localStorage.getItem(key(relPath));
        if (!raw) return null;
        return JSON.parse(raw);
    } catch (e) {
        return null;
    }
}

export function clearProgress(relPath) {
    try {
        localStorage.removeItem(key(relPath));
    } catch (e) {
        // 静默
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server/internal/web && node --test videoProgress.test.mjs`
Expected: PASS（全部 tests pass）

- [ ] **Step 5: Commit**

```bash
git add server/internal/web/videoProgress.js server/internal/web/videoProgress.test.mjs
git commit -m "$(cat <<'EOF'
feat(web): add videoProgress data layer for playback resume

视频进度记忆数据层：saveProgress/loadProgress/clearProgress/isCompleted，
localStorage + video_progress: 前缀，key 用 relative_path。纯函数 + jsdom 单测。

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `videoHelpers.js` 纯函数（TDD）

**Files:**
- Create: `server/internal/web/videoHelpers.js`
- Test: `server/internal/web/videoHelpers.test.mjs`

**Interfaces:**
- Produces:
  - `nextSpeed(currentRate: number, speeds: number[]) -> number`（循环到下一档，末档回首档）
  - `wheelToVolume(currentVolume: number, deltaY: number, step: number) -> number`（滚轮→音量，钳制 [0,1]）

- [ ] **Step 1: 写失败测试**

Create `server/internal/web/videoHelpers.test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { nextSpeed, wheelToVolume } from './videoHelpers.js';

const SPEEDS = [0.75, 1, 1.25, 1.5, 2, 3];

test('nextSpeed: 1 → 1.25', () => {
    assert.equal(nextSpeed(1, SPEEDS), 1.25);
});
test('nextSpeed: 末档 3 循环回首档 0.75', () => {
    assert.equal(nextSpeed(3, SPEEDS), 0.75);
});
test('nextSpeed: 0.75 → 1', () => {
    assert.equal(nextSpeed(0.75, SPEEDS), 1);
});
test('nextSpeed: 当前值不在档位 → 从 1x 的下一档开始（容错）', () => {
    // indexOf(1)=1, (1+1)%6=2 → SPEEDS[2]=1.25
    assert.equal(nextSpeed(1.7, SPEEDS), 1.25);
});

test('wheelToVolume: 向上滚（deltaY<0）→ +step', () => {
    assert.equal(wheelToVolume(0.5, -100, 0.05), 0.55);
});
test('wheelToVolume: 向下滚（deltaY>0）→ -step', () => {
    assert.equal(wheelToVolume(0.5, 100, 0.05), 0.45);
});
test('wheelToVolume: 上限钳制 1', () => {
    assert.equal(wheelToVolume(0.98, -100, 0.05), 1);
});
test('wheelToVolume: 下限钳制 0', () => {
    assert.equal(wheelToVolume(0.02, 100, 0.05), 0);
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server/internal/web && node --test videoHelpers.test.mjs`
Expected: FAIL（`Cannot find module './videoHelpers.js'`）

- [ ] **Step 3: 写最小实现**

Create `server/internal/web/videoHelpers.js`:

```js
// 视频播放控制纯函数：倍速循环、滚轮→音量。无 DOM 依赖，便于单测。

// 返回档位列表中当前值的下一档；末档循环回首档。
// 当前值不在档位中时，从默认 1x 的下一档开始（容错）。
export function nextSpeed(currentRate, speeds) {
    const i = speeds.indexOf(currentRate);
    if (i === -1) {
        const def = speeds.indexOf(1);
        return speeds[(def + 1) % speeds.length];
    }
    return speeds[(i + 1) % speeds.length];
}

// 滚轮 deltaY → 新音量，钳制到 [0, 1]。
// deltaY < 0（向上滚）音量 +step；deltaY > 0（向下滚）音量 -step。
export function wheelToVolume(currentVolume, deltaY, step) {
    const next = deltaY < 0 ? currentVolume + step : currentVolume - step;
    return Math.min(1, Math.max(0, next));
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server/internal/web && node --test videoHelpers.test.mjs`
Expected: PASS（全部）

- [ ] **Step 5: Commit**

```bash
git add server/internal/web/videoHelpers.js server/internal/web/videoHelpers.test.mjs
git commit -m "$(cat <<'EOF'
feat(web): add videoHelpers pure fns (nextSpeed, wheelToVolume)

倍速循环档位与滚轮→音量纯函数，无 DOM 依赖，便于单测。
供 videoPlayer.js 倍速按钮与滚轮监听复用。

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: 倍速 UI 接入（DOM + 控制条按钮）

**Files:**
- Modify: `server/internal/web/index.html`（`controls-right` 区块，约 line 290-296）
- Modify: `server/internal/web/dom.js`（控制条区块，约 line 78 `btnVideoFullscreen` 后）
- Modify: `server/internal/web/videoPlayer.js`（顶部 import + 模块常量；`openVideoPlayer` 约	line 93；`setupVideoPlayerListeners` 约	line 271 后）

**Interfaces:**
- Consumes: `nextSpeed` from `./videoHelpers.js`
- Produces: `#btn-video-speed` 按钮的 DOM + 点击循环行为；`openVideoPlayer` 重置倍速

- [ ] **Step 1: `index.html` 加倍速按钮（全屏按钮前）**

在 `index.html` 找到现有 `controls-right`（约 line 290-296）：

```html
                            <div class="controls-right">
                                <div class="video-volume-container">
                                    <button class="video-control-btn" id="btn-video-mute">🔊</button>
                                    <input type="range" class="video-volume-bar" id="video-volume" min="0" max="1" value="1" step="0.05">
                                </div>
                                <button class="video-control-btn" id="btn-video-fullscreen">⛶</button>
                            </div>
```

改为（在 `btn-video-fullscreen` 前插入倍速按钮）：

```html
                            <div class="controls-right">
                                <div class="video-volume-container">
                                    <button class="video-control-btn" id="btn-video-mute">🔊</button>
                                    <input type="range" class="video-volume-bar" id="video-volume" min="0" max="1" value="1" step="0.05">
                                </div>
                                <button class="video-control-btn" id="btn-video-speed" title="倍速">1x</button>
                                <button class="video-control-btn" id="btn-video-fullscreen">⛶</button>
                            </div>
```

- [ ] **Step 2: `dom.js` 注册 `btnVideoSpeed`**

在 `dom.js` 控制条区块，`btnVideoFullscreen` 行（约 line 78）之后加一行：

```js
    btnVideoFullscreen: document.getElementById('btn-video-fullscreen'),
    btnVideoSpeed: document.getElementById('btn-video-speed'),
```

- [ ] **Step 3: `videoPlayer.js` 顶部 import + 倍速档位常量**

在 `videoPlayer.js` 顶部 import 区（line 1-7 之后）加：

```js
import { nextSpeed } from './videoHelpers.js';
```

在模块作用域（line 10 `let controlsTimeout;` 附近）加倍速档位常量：

```js
let controlsTimeout;
const PLAYBACK_SPEEDS = [0.75, 1, 1.25, 1.5, 2, 3];
```

- [ ] **Step 4: `openVideoPlayer` 重置倍速到 1x**

在 `openVideoPlayer` 内，`elements.videoPlayer.src = url;`（约 line 93）之前插入：

```js
    // 重置倍速到 1x（每次打开新视频）
    elements.videoPlayer.playbackRate = 1;
    elements.btnVideoSpeed.textContent = '1x';

    elements.videoPlayer.src = url;
```

- [ ] **Step 5: `setupVideoPlayerListeners` 加倍速按钮 click**

在 `setupVideoPlayerListeners` 内，`btnVideoFullscreen` 监听（约 line 262-271）之后插入：

```js
    // 倍速按钮：循环档位 1→1.25→1.5→2→3→0.75→1
    elements.btnVideoSpeed.addEventListener('click', () => {
        const next = nextSpeed(elements.videoPlayer.playbackRate, PLAYBACK_SPEEDS);
        elements.videoPlayer.playbackRate = next;
        elements.btnVideoSpeed.textContent = next + 'x';
    });
```

- [ ] **Step 6: 手动验证**

启动服务器，浏览器打开任意视频，确认：
1. 控制条出现 `1x` 按钮（全屏键左边）
2. 点击循环显示：`1x → 1.25x → 1.5x → 2x → 3x → 0.75x → 1x`
3. 每次点击播放速度实际改变（听声音/看画面节奏）
4. 关闭后打开另一个视频，倍速重置为 `1x`

- [ ] **Step 7: Commit**

```bash
git add server/internal/web/index.html server/internal/web/dom.js server/internal/web/videoPlayer.js
git commit -m "$(cat <<'EOF'
feat(web): add playback speed control to video player (0.75x–3x cycle)

控制条加倍速按钮，点击循环 [0.75,1,1.25,1.5,2,3]；打开新视频重置为 1x。
倍速档位用 videoHelpers.nextSpeed 纯函数。

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: 鼠标滚轮调音量 UI 接入

**Files:**
- Modify: `server/internal/web/videoPlayer.js`（顶部 import；`setupVideoPlayerListeners` 末尾约	line 321 后）

**Interfaces:**
- Consumes: `wheelToVolume` from `./videoHelpers.js`

- [ ] **Step 1: `videoPlayer.js` import 补 `wheelToVolume`**

修改 Task 3 Step 3 加的 import 行，改为：

```js
import { nextSpeed, wheelToVolume } from './videoHelpers.js';
```

- [ ] **Step 2: `setupVideoPlayerListeners` 加滚轮监听**

在 `setupVideoPlayerListeners` 函数末尾，现有这两行（约 line 320-321，复用此处已声明的 `wrapper`）：

```js
    wrapper.addEventListener('mousemove', resetControlsTimer);
    elements.videoPlayer.addEventListener('play', resetControlsTimer);
```

之后追加（复用同作用域已声明的 `wrapper`，它在约 line 319 `const wrapper = elements.videoPlayer.parentElement;`）：

```js
    // 鼠标滚轮调音量（仅在视频区域内；进度条上的滚轮交给浏览器默认）
    wrapper.addEventListener('wheel', (e) => {
        e.preventDefault();
        const vol = wheelToVolume(elements.videoPlayer.volume, e.deltaY, 0.05);
        elements.videoPlayer.volume = vol;
        elements.videoPlayer.muted = (vol === 0);
        elements.videoVolume.value = vol;
        elements.btnVideoMute.textContent = vol === 0 ? '🔇' : '🔊';
    }, { passive: false });
```

- [ ] **Step 3: 手动验证**

浏览器打开视频，确认：
1. 在视频画面上滚轮向上 → 音量 +5%（音量条右移、声音变大）
2. 滚轮向下 → 音量 -5%
3. 静音按钮文字与音量条同步更新（`🔊`/`🔇`）
4. 连续向下滚到 0 → 静音；连续向上滚到 1 → 满
5. 滚动时页面本身不滚动（`preventDefault` 生效）
6. 鼠标在进度条（`#video-progress`）上滚轮 → 仍是浏览器默认行为（微调进度条），不被拦截

- [ ] **Step 4: Commit**

```bash
git add server/internal/web/videoPlayer.js
git commit -m "$(cat <<'EOF'
feat(web): mouse-wheel volume control in video player

视频区域内滚轮调音量（±0.05，与方向键一致），同步音量条与静音按钮。
音量增量用 videoHelpers.wheelToVolume 纯函数。

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: 进度记忆 UI 接入（读续播 / 节流写 / 结束清除）

**Files:**
- Modify: `server/internal/web/videoPlayer.js`（顶部 import + 模块状态；`openVideoPlayer` 约	line 80-96；`setupVideoPlayerListeners` 的 `timeupdate`/`btnCloseVideoModal`/`pause` 监听）

**Interfaces:**
- Consumes: `saveProgress`、`loadProgress`、`clearProgress`、`isCompleted` from `./videoProgress.js`

- [ ] **Step 1: `videoPlayer.js` import + 节流状态**

顶部 import 区加（与 Task 3/4 的 videoHelpers import 并列）：

```js
import { saveProgress, loadProgress, clearProgress, isCompleted } from './videoProgress.js';
```

模块作用域（`PLAYBACK_SPEEDS` 附近）加节流状态：

```js
let lastProgressSaveMs = 0;
```

- [ ] **Step 2: `openVideoPlayer` 末尾改为「续播读取 + 倍速重置」**

定位 `openVideoPlayer` 内的 URL 构建到播放段落（约 line 80-96），现有结尾是：

```js
    elements.videoPlayer.src = url;
    elements.modalVideoPlayer.classList.add('active');
    elements.videoPlayer.load();
    elements.videoPlayer.play();
}
```

把这段（含 Task 3 加的倍速重置）替换为下面完整版本（已包含倍速重置 + 续播判定 + 原画流 seek）：

```js
    // 续播：读取上次进度，决定起点
    const saved = loadProgress(file.relative_path);
    let resumePositionMs = 0;
    if (saved && !isCompleted(saved.positionMs, saved.durationMs)) {
        resumePositionMs = saved.positionMs;
        if (state.useTranscode) {
            // 转码流：把 URL 里的 start=0 替换为续播秒数（ffmpeg 从该点转码）
            const startSec = Math.floor(resumePositionMs / 1000);
            url = url.replace('start=0', 'start=' + startSec);
            state.transcodeStartOffset = startSec * 1000;
        }
        showToast(`已从 ${formatTime(resumePositionMs / 1000)} 继续`, 'info');
    } else if (saved && isCompleted(saved.positionMs, saved.durationMs)) {
        // 上次看完了：清除记录，这次从头播
        clearProgress(file.relative_path);
    }

    // 重置倍速到 1x（每次打开新视频）
    elements.videoPlayer.playbackRate = 1;
    elements.btnVideoSpeed.textContent = '1x';

    elements.videoPlayer.src = url;
    elements.modalVideoPlayer.classList.add('active');
    elements.videoPlayer.load();
    // 原画流续播：loadedmetadata 后 seekTo（转码流已用 start 参数）
    if (resumePositionMs > 0 && !state.useTranscode) {
        elements.videoPlayer.addEventListener('loadedmetadata', function resumeSeek() {
            elements.videoPlayer.currentTime = resumePositionMs / 1000;
        }, { once: true });
    }
    elements.videoPlayer.play();
}
```

- [ ] **Step 3: `timeupdate` 监听加节流写入**

定位 `setupVideoPlayerListeners` 的 `timeupdate` 监听（约 line 216-227），现有：

```js
    elements.videoPlayer.addEventListener('timeupdate', () => {
        if (!state.playingFile) return;
        const currentAbsoluteTime = state.transcodeStartOffset + elements.videoPlayer.currentTime;

        if (!state.isDraggingProgress) {
            elements.videoProgress.value = currentAbsoluteTime;
        }

        elements.videoTimeDisplay.textContent = `${formatTime(currentAbsoluteTime)} / ${formatTime(state.videoDuration)}`;
    });
```

改为（末尾加节流 save）：

```js
    elements.videoPlayer.addEventListener('timeupdate', () => {
        if (!state.playingFile) return;
        const currentAbsoluteTime = state.transcodeStartOffset + elements.videoPlayer.currentTime;

        if (!state.isDraggingProgress) {
            elements.videoProgress.value = currentAbsoluteTime;
        }

        elements.videoTimeDisplay.textContent = `${formatTime(currentAbsoluteTime)} / ${formatTime(state.videoDuration)}`;

        // 节流写进度：每 5s 存一次绝对位置
        const now = Date.now();
        if (state.videoDuration > 0 && now - lastProgressSaveMs >= 5000) {
            saveProgress(state.playingFile.relative_path, {
                positionMs: currentAbsoluteTime * 1000,
                durationMs: state.videoDuration * 1000,
            });
            lastProgressSaveMs = now;
        }
    });
```

- [ ] **Step 4: 关闭弹窗时 flush 进度**

定位 `btnCloseVideoModal` 监听（约 line 102-110），现有：

```js
    elements.btnCloseVideoModal.addEventListener('click', () => {
        elements.videoPlayer.pause();
        elements.videoPlayer.src = '';
        elements.modalVideoPlayer.classList.remove('active');
        state.playingFile = null;
        state.videoDuration = 0;
        state.transcodeStartOffset = 0;
        state.isDraggingProgress = false;
    });
```

改为（`pause()` 后、清 `state.playingFile` 前 flush）：

```js
    elements.btnCloseVideoModal.addEventListener('click', () => {
        elements.videoPlayer.pause();
        // 关闭前 flush 最终进度
        if (state.playingFile && state.videoDuration > 0) {
            const posMs = (state.transcodeStartOffset + elements.videoPlayer.currentTime) * 1000;
            saveProgress(state.playingFile.relative_path, {
                positionMs: posMs,
                durationMs: state.videoDuration * 1000,
            });
        }
        elements.videoPlayer.src = '';
        elements.modalVideoPlayer.classList.remove('active');
        state.playingFile = null;
        state.videoDuration = 0;
        state.transcodeStartOffset = 0;
        state.isDraggingProgress = false;
        lastProgressSaveMs = 0;
    });
```

- [ ] **Step 5: `pause` 事件 flush + `ended` 清除**

定位 `pause` 监听（约 line 186-188），现有：

```js
    elements.videoPlayer.addEventListener('pause', () => {
        elements.btnVideoPlayPause.textContent = '▶';
    });
```

改为（加 pause flush + 新增 ended 清除）：

```js
    elements.videoPlayer.addEventListener('pause', () => {
        elements.btnVideoPlayPause.textContent = '▶';
        // 暂停时 flush 进度
        if (state.playingFile && state.videoDuration > 0) {
            const posMs = (state.transcodeStartOffset + elements.videoPlayer.currentTime) * 1000;
            saveProgress(state.playingFile.relative_path, {
                positionMs: posMs,
                durationMs: state.videoDuration * 1000,
            });
            lastProgressSaveMs = Date.now();
        }
    });
    // 播放结束：清除进度记录（已看完）
    elements.videoPlayer.addEventListener('ended', () => {
        if (state.playingFile) {
            clearProgress(state.playingFile.relative_path);
        }
    });
```

- [ ] **Step 6: 手动验证**

浏览器验证（原画流 + 转码流各测一次）：
1. 打开一个原画视频（如 .mp4），播放到约 1 分钟处，关闭弹窗
2. 重新打开同一视频 → 自动从约 1 分钟续播，且弹出 toast「已从 01:00 继续」
3. 打开另一个视频 → 从头播，不受上一个影响（key 隔离）
4. 打开一个转码视频（如 .mkv），播放一段后关闭，重开 → 转码流也续播（URL 带 `start=`）
5. 把视频拖到接近结尾让其自然 `ended` → 重开该视频从头播（进度已清除）
6. 打开浏览器隐私模式重复 1-2 → 功能正常、无 console 报错（`saveProgress` 静默容错）

- [ ] **Step 7: Commit**

```bash
git add server/internal/web/videoPlayer.js
git commit -m "$(cat <<'EOF'
feat(web): add localStorage playback resume to video player

打开视频读取上次进度自动续播（原画流 seekTo / 转码流 start 参数）；
timeupdate 每 5s 节流写、关闭与暂停 flush、ended 清除；看完(≥95%)清记录。
接入 videoProgress 数据层。

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review 结论

- **Spec coverage**：倍速（Task 2+3）、滚轮音量（Task 2+4）、进度记忆（Task 1+5）、isCompleted≥0.95（Task 1）、续播 toast（Task 5）、ended/看完清除（Task 5）、4 个交互默认值全部落实。
- **Placeholder**：每个 step 含实际代码，无 TBD/TODO。
- **Type consistency**：`saveProgress(relPath,{positionMs,durationMs})`、`loadProgress→{positionMs,durationMs}|null`、`nextSpeed(rate,speeds)`、`wheelToVolume(vol,deltaY,step)` 在定义（Task 1/2）与使用（Task 3/4/5）处签名一致；`PLAYBACK_SPEEDS`、`lastProgressSaveMs`、`btnVideoSpeed` 命名贯穿一致。
- **依赖顺序**：Task 1→2（独立可并行）→ 3→4→5（3 先建 DOM/import，4 复用 import，5 复用 videoProgress）。Task 5 Step 2 的代码块已包含 Task 3 的倍速重置行，单独读 Task 5 也可实施。
