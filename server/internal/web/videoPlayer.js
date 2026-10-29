// Video player feature module: extracted from app.js (openVideoPlayer + listeners).
import { state } from './state.js';
import { apiRequest, getAuthToken, withMediaAuthToken } from './api.js';
import { showToast } from './toast.js';
import { elements } from './dom.js';
import { formatTime } from './utils.js';
import { deleteMediaFile } from './delete.js';
import { wheelToVolume } from './videoHelpers.js';
import { saveProgress, loadProgress, clearProgress, isCompleted } from './videoProgress.js';
import { buildHlsPlaylistUrl, resolveHlsStrategy, needsHlsRestart } from './hlsCompat.js';
import { openModalFocus, closeModalFocus } from './modalFocus.js';

// <video> tags cannot send Authorization headers, so auth-gated stream URLs
// (/api/v1/media/stream, /api/v1/system/stream) carry the bearer token as a
// ?token= query parameter (server redacts it from access logs; the page's
// Referrer-Policy: no-referrer keeps it out of referers). The public
// /api/v1/videos/*/stream route needs no token.
function withVideoAuthToken(url) {
    return withMediaAuthToken(url);
}

// Module-scoped player state (shared across the player's internal helpers).
let controlsTimeout;
let lastProgressSaveMs = 0;
let keyboardSeekTimer = null;
let keyboardSeekTarget = null;

// HLS era (spec 2026-09-03-hls-transcode-b2): the active HLS route for the
// current video ('hlsjs' | 'native' | 'none') and the live hls.js instance.
let hlsStrategy = 'none';
let hls = null;

function destroyHls() {
    if (hls) {
        try { hls.destroy(); } catch (e) { /* already torn down */ }
        hls = null;
    }
}

// hls.js 惰性加载（2026-09-30）。
//
// vendor/hls.min.js 是 415 KB —— 比全部业务 JS（约 265 KB）还大 —— 原先由
// index.html 的 <script defer> 对所有会话无条件加载，包括只看图、读小说、
// 完全不播视频的会话。改为在 openVideoPlayer 里按需注入。
//
// resolveHlsStrategy 依赖 window.Hls 是否存在来判定 'hlsjs'，所以必须
// 「先加载、后判定」。加载失败时 resolve 为 null（而不是 reject）：调用方
// 无需 try/catch，resolveHlsStrategy 会自然降级到 'native'（Safari 原生
// HLS）或 'none'（回退传统单路转码），功能零退化。
//
// 模块级 promise 保证同一页面会话只加载一次；失败时清空以便下次重试。
let hlsLoaderPromise = null;

function loadHls() {
    if (typeof window === 'undefined' || typeof document === 'undefined') {
        return Promise.resolve(null);
    }
    if (window.Hls) return Promise.resolve(window.Hls);
    if (!hlsLoaderPromise) {
        hlsLoaderPromise = new Promise((resolve) => {
            const script = document.createElement('script');
            // 同源相对路径（文档在根路径下）—— 符合 CSP script-src 'self'。
            script.src = 'vendor/hls.min.js';
            script.onload = () => resolve(window.Hls || null);
            script.onerror = () => {
                console.warn('hls.js 加载失败，回退到原生 HLS / 传统转码路径');
                hlsLoaderPromise = null; // 允许下次打开视频时重试
                resolve(null);
            };
            document.head.appendChild(script);
        });
    }
    return hlsLoaderPromise;
}

// applySource wires the player to url. strategy 'hlsjs' routes through
// hls.js (auth headers injected on every request via xhrSetup, segments
// included); anything else is a plain src assignment (native HLS gets the
// token via query param, same as the legacy stream path). seekSec >= 0 is
// applied once the stream is positioned (MANIFEST_PARSED / loadedmetadata).
function applySource(url, strategy, seekSec) {
    const video = elements.videoPlayer;
    destroyHls();
    const targetSeek = (seekSec && seekSec > 0) ? seekSec : 0;
    // Always reset DOM element currentTime so previous playback position
    // does not linger and stall the newly attached media.
    video.currentTime = targetSeek;

    if (strategy === 'hlsjs') {
        const token = getAuthToken();
        hls = new window.Hls({
            startPosition: targetSeek,
            // LAN-optimized buffer tuning: larger forward buffer for smooth
            // playback, back buffer for instant short-distance backward seeks
            // (e.g. ArrowLeft -5s) without triggering a segment reload.
            maxBufferLength: 60,       // default 30s; LAN bandwidth is ample
            maxMaxBufferLength: 120,   // cap memory usage
            backBufferLength: 30,      // keep 30s behind playhead for native back-seek
            lowLatencyMode: false,     // not live streaming; avoid LL-HLS overhead
            xhrSetup: (xhr) => {
                if (token) xhr.setRequestHeader('Authorization', 'Bearer ' + token);
            },
        });
        hls.loadSource(url);
        hls.attachMedia(video);

        if (window.Hls.Events && window.Hls.Events.MANIFEST_PARSED) {
            hls.on(window.Hls.Events.MANIFEST_PARSED, () => {
                video.currentTime = targetSeek;
                video.play().catch(() => {});
            });
        }
        if (window.Hls.Events && window.Hls.Events.ERROR && window.Hls.ErrorTypes) {
            hls.on(window.Hls.Events.ERROR, (event, data) => {
                if (!data || !data.fatal) return;
                switch (data.type) {
                    case window.Hls.ErrorTypes.NETWORK_ERROR:
                        console.warn('HLS fatal network error, recovering...', data.details);
                        hls.startLoad();
                        break;
                    case window.Hls.ErrorTypes.MEDIA_ERROR:
                        console.warn('HLS fatal media error, recovering...', data.details);
                        hls.recoverMediaError();
                        break;
                    default:
                        console.error('HLS unrecoverable error:', data);
                        destroyHls();
                        break;
                }
            });
        }
        return;
    }
    video.src = withVideoAuthToken(url);
    video.load();
    if (targetSeek > 0) {
        video.addEventListener('loadedmetadata', function seekOnce() {
            video.currentTime = targetSeek;
        }, { once: true });
    }
}

// 更新倍速菜单高亮项（按当前 playbackRate）
function updateSpeedMenuActive(rate) {
    elements.videoSpeedMenu.querySelectorAll('.video-speed-item').forEach(item => {
        item.classList.toggle('video-speed-item--active', parseFloat(item.dataset.speed) === rate);
    });
}

// Dual-span icon state toggle (mirrors updateThemeToggleIcon in app.js):
// control buttons carry two <span data-icon="..."> wrappers (one hidden),
// e.g. play/pause and volume-on/volume-off; show exactly one by name.
function setControlIcon(button, name) {
    button.querySelectorAll('[data-icon]').forEach(el => {
        el.hidden = el.dataset.icon !== name;
    });
}

// Custom controls: Play / Pause toggle
function togglePlayPause() {
    if (elements.videoPlayer.paused) {
        elements.videoPlayer.play();
        setControlIcon(elements.btnVideoPlayPause, 'pause');
    } else {
        elements.videoPlayer.pause();
        setControlIcon(elements.btnVideoPlayPause, 'play');
    }
}

// Helper for seeking via keyboard hotkeys: updates UI instantly and debounces
// commits so rapid keypresses do not flood restarts or stall playback.
function seekTo(targetTime) {
    keyboardSeekTarget = targetTime;
    elements.videoProgress.value = targetTime;
    elements.videoProgress.dispatchEvent(new Event('input'));
    clearTimeout(keyboardSeekTimer);
    keyboardSeekTimer = setTimeout(() => {
        elements.videoProgress.dispatchEvent(new Event('change'));
        keyboardSeekTarget = null;
    }, 250);
}

// Auto-hide controls overlay on mouse inactivity
function resetControlsTimer() {
    elements.videoControlsOverlay.classList.add('active');
    clearTimeout(controlsTimeout);
    controlsTimeout = setTimeout(() => {
        if (!elements.videoPlayer.paused) {
            elements.videoControlsOverlay.classList.remove('active');
        }
    }, 2000);
}

// Video player setup & popup
export async function openVideoPlayer(file) {
    if (!file) return;
    const rawExt = (file.extension || (file.path ? file.path.slice(file.path.lastIndexOf('.')) : '')).toLowerCase();
    const ext = rawExt.startsWith('.') ? rawExt : (rawExt ? '.' + rawExt : '');
    const isText = file.media_type === 'text' || ['.txt', '.epub', '.mobi', '.azw3'].includes(ext);
    if (isText) {
        if (['.txt', '.epub'].includes(ext)) {
            window.location.hash = `#/read?path=${encodeURIComponent(file.path)}`;
        } else {
            showToast('暂不支持该格式（仅支持 .txt / .epub）', 'info');
        }
        return;
    }

    state.playingFile = file;
    state.transcodeStartOffset = 0;
    state.videoDuration = 0;

    // Initialize custom controls
    elements.videoVolume.value = elements.videoPlayer.volume;
    elements.videoProgress.value = 0;
    elements.videoProgress.max = 100;
    elements.videoTimeDisplay.textContent = '00:00 / 加载中...';

    // Web player uses HLS streaming for universal format support and seamless seeking.
    // 先惰性加载 hls.js 再判定策略：resolveHlsStrategy 依据 window.Hls 是否
    // 存在返回 'hlsjs'。加载失败时自然降级到 'native' / 'none'。
    await loadHls();
    hlsStrategy = resolveHlsStrategy(elements.videoPlayer);
    state.useTranscode = true;

    elements.videoModalTitle.textContent = file.name;
    elements.btnVideoDelete.style.display = state.enableDelete ? 'block' : 'none';

    // 续播：读取上次进度，决定起点（优先使用本地记录中的 duration 快速展示）
    const saved = loadProgress(file.relative_path);
    let hlsStartSec = 0;
    if (saved && !isCompleted(saved.positionMs, saved.durationMs)) {
        hlsStartSec = Math.floor(saved.positionMs / 1000);
        state.transcodeStartOffset = hlsStartSec;
        if (saved.durationMs > 0) {
            state.videoDuration = saved.durationMs / 1000;
            elements.videoProgress.max = state.videoDuration;
            elements.videoTimeDisplay.textContent = `${formatTime(hlsStartSec)} / ${formatTime(state.videoDuration)}`;
        }
        showToast(`已从 ${formatTime(hlsStartSec)} 继续`, 'info');
    } else if (saved && isCompleted(saved.positionMs, saved.durationMs)) {
        // 上次看完了：清除记录，这次从头播
        clearProgress(file.relative_path);
    }

    // 重置倍速到 1x + 关闭菜单 + 高亮 1x（每次打开新视频）
    elements.videoPlayer.playbackRate = 1;
    elements.btnVideoSpeed.textContent = '1x';
    elements.videoSpeedMenu.hidden = true;
    updateSpeedMenuActive(1);

    // 显示模态框 + 焦点移入 + 冻结背景（详见 modalFocus.js）。初始焦点给
    // 播放/暂停按钮；Space 的默认激活会被下方 document keydown 的
    // preventDefault 拦住，所以不会与全局播放/暂停快捷键重复触发。
    openModalFocus(elements.modalVideoPlayer, '#btn-video-play-pause');

    // 立即发起 HLS 流起播，不等待 duration 接口返回（提升首屏 1~2s 响应速度）
    const url = buildHlsPlaylistUrl(state.apiBase, file.path, hlsStartSec);
    applySource(url, hlsStrategy, 0);
    elements.videoPlayer.play().catch(() => {});

    // 并行异步获取权威时长，完成后平滑更新进度条与时间显示
    apiRequest(`${state.apiBase}/api/v1/media/duration?path=${encodeURIComponent(file.path)}`)
        .then(data => {
            if (state.playingFile && state.playingFile.path === file.path && data.duration > 0) {
                state.videoDuration = data.duration;
                elements.videoProgress.max = state.videoDuration;
                const cur = state.transcodeStartOffset + elements.videoPlayer.currentTime;
                elements.videoTimeDisplay.textContent = `${formatTime(cur)} / ${formatTime(state.videoDuration)}`;
            }
        })
        .catch(e => {
            console.error('Error fetching duration:', e);
        });
}

// All video-player-related event listener registrations (moved from setupEventListeners).
export function setupVideoPlayerListeners(elements) {
    // Close Video Modal
    elements.btnCloseVideoModal.addEventListener('click', () => {
        elements.videoPlayer.pause();
        clearTimeout(keyboardSeekTimer);
        keyboardSeekTimer = null;
        keyboardSeekTarget = null;
        destroyHls();
        hlsStrategy = 'none';
        // 关闭前 flush 最终进度
        if (state.playingFile && state.videoDuration > 0) {
            const posMs = (state.transcodeStartOffset + elements.videoPlayer.currentTime) * 1000;
            saveProgress(state.playingFile.relative_path, {
                positionMs: posMs,
                durationMs: state.videoDuration * 1000,
            });
        }
        // src = '' 会让部分浏览器把当前文档 URL 当作媒体源再发一次请求；
        // 正确做法是移除属性再 load() 以真正释放媒体资源。
        elements.videoPlayer.removeAttribute('src');
        elements.videoPlayer.load();
        closeModalFocus(elements.modalVideoPlayer);
        state.playingFile = null;
        state.videoDuration = 0;
        state.transcodeStartOffset = 0;
        state.isDraggingProgress = false;
        lastProgressSaveMs = 0;
    });

    // Delete Video inside Video Modal
    elements.btnVideoDelete.addEventListener('click', () => {
        if (state.playingFile) {
            deleteMediaFile(state.playingFile);
        }
    });

    // Custom controls: Play / Pause toggle
    elements.btnVideoPlayPause.addEventListener('click', togglePlayPause);
    elements.videoPlayer.addEventListener('click', togglePlayPause);

    // Sync play/pause state to button icon
    elements.videoPlayer.addEventListener('play', () => {
        setControlIcon(elements.btnVideoPlayPause, 'pause');
    });
    elements.videoPlayer.addEventListener('pause', () => {
        setControlIcon(elements.btnVideoPlayPause, 'play');
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

    // Custom controls: Progress Bar Seek (Update timeline text while dragging)
    elements.videoProgress.addEventListener('input', (e) => {
        state.isDraggingProgress = true;
        const targetTime = parseFloat(e.target.value);
        elements.videoTimeDisplay.textContent = `${formatTime(targetTime)} / ${formatTime(state.videoDuration)}`;
    });

    // Custom controls: Progress Bar Seek Release (Perform seek on release)
    elements.videoProgress.addEventListener('change', (e) => {
        const targetTime = parseFloat(e.target.value);
        const offsetSec = state.transcodeStartOffset;
        const seekable = elements.videoPlayer.seekable;
        const seekableEnd = seekable.length ? seekable.end(seekable.length - 1) : null;
        if (needsHlsRestart(targetTime, offsetSec, seekableEnd)) {
            const startSec = Math.max(0, Math.floor(targetTime));
            state.transcodeStartOffset = startSec;
            applySource(buildHlsPlaylistUrl(state.apiBase, state.playingFile.path, startSec), hlsStrategy, 0);
            elements.videoPlayer.play().catch(() => {});
        } else {
            elements.videoPlayer.currentTime = Math.max(0, targetTime - offsetSec);
        }
        state.isDraggingProgress = false;
    });

    // Custom controls: Timeupdate synchronization
    elements.videoPlayer.addEventListener('timeupdate', () => {
        if (!state.playingFile) return;
        const currentAbsoluteTime = state.transcodeStartOffset + elements.videoPlayer.currentTime;

        // Update progress bar value (unless user is dragging it)
        if (!state.isDraggingProgress) {
            elements.videoProgress.value = currentAbsoluteTime;
        }

        // Update time display text
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

    // Custom controls: Handle duration changes dynamically (especially for original play)
    elements.videoPlayer.addEventListener('durationchange', () => {
        if (!state.useTranscode && elements.videoPlayer.duration && !isNaN(elements.videoPlayer.duration) && elements.videoPlayer.duration !== Infinity) {
            state.videoDuration = elements.videoPlayer.duration;
            elements.videoProgress.max = state.videoDuration;
            if (!state.isDraggingProgress) {
                const currentAbsoluteTime = elements.videoPlayer.currentTime;
                elements.videoTimeDisplay.textContent = `${formatTime(currentAbsoluteTime)} / ${formatTime(state.videoDuration)}`;
            }
        }
    });

    // Custom controls: Volume Bar
    elements.videoVolume.addEventListener('input', (e) => {
        const vol = parseFloat(e.target.value);
        elements.videoPlayer.volume = vol;
        elements.videoPlayer.muted = (vol === 0);
        setControlIcon(elements.btnVideoMute, vol === 0 ? 'volume-off' : 'volume-on');
    });

    // Custom controls: Mute Button
    elements.btnVideoMute.addEventListener('click', () => {
        elements.videoPlayer.muted = !elements.videoPlayer.muted;
        if (elements.videoPlayer.muted) {
            setControlIcon(elements.btnVideoMute, 'volume-off');
            elements.videoVolume.value = 0;
        } else {
            setControlIcon(elements.btnVideoMute, 'volume-on');
            elements.videoVolume.value = elements.videoPlayer.volume;
        }
    });

    // Custom controls: Fullscreen Button
    elements.btnVideoFullscreen.addEventListener('click', () => {
        const container = elements.videoPlayer.parentElement; // .video-player-wrapper
        if (!document.fullscreenElement) {
            container.requestFullscreen().catch(err => {
                showToast('无法进入全屏模式', 'error');
            });
        } else {
            document.exitFullscreen();
        }
    });

    // 倍速按钮：toggle 菜单（stopPropagation 防冒泡到 document 立即关闭）
    elements.btnVideoSpeed.addEventListener('click', (e) => {
        e.stopPropagation();
        elements.videoSpeedMenu.hidden = !elements.videoSpeedMenu.hidden;
    });
    // 菜单项 click 委托：设速 + 高亮 + 关闭
    elements.videoSpeedMenu.addEventListener('click', (e) => {
        const item = e.target.closest('.video-speed-item');
        if (!item) return;
        e.stopPropagation();
        const rate = parseFloat(item.dataset.speed);
        elements.videoPlayer.playbackRate = rate;
        elements.btnVideoSpeed.textContent = item.textContent;
        updateSpeedMenuActive(rate);
        elements.videoSpeedMenu.hidden = true;
    });
    // 点菜单外部关闭
    document.addEventListener('click', (e) => {
        if (!elements.videoSpeedMenu.hidden && !e.target.closest('.video-speed-wrap')) {
            elements.videoSpeedMenu.hidden = true;
        }
    });

    // Keyboard controls for video playback
    document.addEventListener('keydown', (e) => {
        if (!elements.modalVideoPlayer.classList.contains('active')) return;

        // Spacebar: Play/Pause
        if (e.key === ' ' || e.code === 'Space') {
            e.preventDefault();
            togglePlayPause();
        }
        // ArrowLeft: Quick seek backward (5 seconds)
        else if (e.key === 'ArrowLeft') {
            e.preventDefault();
            const baseTime = keyboardSeekTarget !== null
                ? keyboardSeekTarget
                : (state.transcodeStartOffset + elements.videoPlayer.currentTime);
            const targetTime = Math.max(0, baseTime - 5);
            seekTo(targetTime);
        }
        // ArrowRight: Quick seek forward (5 seconds)
        else if (e.key === 'ArrowRight') {
            e.preventDefault();
            const baseTime = keyboardSeekTarget !== null
                ? keyboardSeekTarget
                : (state.transcodeStartOffset + elements.videoPlayer.currentTime);
            const targetTime = Math.min(state.videoDuration, baseTime + 5);
            seekTo(targetTime);
        }
        // ArrowUp: Volume up
        else if (e.key === 'ArrowUp') {
            e.preventDefault();
            const vol = Math.min(1, elements.videoPlayer.volume + 0.05);
            elements.videoPlayer.volume = vol;
            elements.videoVolume.value = vol;
            elements.videoPlayer.muted = false;
            setControlIcon(elements.btnVideoMute, 'volume-on');
        }
        // ArrowDown: Volume down
        else if (e.key === 'ArrowDown') {
            e.preventDefault();
            const vol = Math.max(0, elements.videoPlayer.volume - 0.05);
            elements.videoPlayer.volume = vol;
            elements.videoVolume.value = vol;
            if (vol === 0) {
                elements.videoPlayer.muted = true;
                setControlIcon(elements.btnVideoMute, 'volume-off');
            }
        }
    });

    // Auto-hide controls overlay on mouse inactivity
    const wrapper = elements.videoPlayer.parentElement; // .video-player-wrapper
    wrapper.addEventListener('mousemove', resetControlsTimer);
    elements.videoPlayer.addEventListener('play', resetControlsTimer);

    // 鼠标滚轮调音量（仅在视频区域内；进度条上的滚轮交给浏览器默认）
    wrapper.addEventListener('wheel', (e) => {
        if (e.target.closest('#video-progress')) return;
        e.preventDefault();
        const vol = wheelToVolume(elements.videoPlayer.volume, e.deltaY, 0.05);
        elements.videoPlayer.volume = vol;
        elements.videoPlayer.muted = (vol === 0);
        elements.videoVolume.value = vol;
        setControlIcon(elements.btnVideoMute, vol === 0 ? 'volume-off' : 'volume-on');
    }, { passive: false });
}
