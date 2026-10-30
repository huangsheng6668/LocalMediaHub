// Video player feature module: extracted from app.js (openVideoPlayer + listeners).
import { state } from './state.js';
import { apiRequest, getAuthToken, withMediaAuthToken } from './api.js';
import { showToast } from './toast.js';
import { elements } from './dom.js';
import { formatTime } from './utils.js';
import { deleteMediaFile } from './delete.js';
import { wheelToVolume } from './videoHelpers.js';
import { saveProgress, loadProgress, clearProgress, isCompleted } from './videoProgress.js';
import {
    buildHlsPlaylistUrl, buildDirectStreamUrl, buildTranscodeStreamUrl,
    resolveHlsStrategy, needsHlsRestart, shouldTryDirectPlay, isUnsupportedMediaError,
} from './hlsCompat.js';
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

// Direct-play state (spec 2026-10-30-web-direct-play): paths whose native
// decode failed in this page session (skip the doomed direct attempt on the
// next open), the absolute position the current direct attempt started at
// (fallback target when the error fires before metadata), and a re-entry
// guard for the error handler.
const directPlayBlocked = new Set();
let directPlayStartSec = 0;
let directFallbackPending = false;

// Seek prewarm state (spec 2026-10-30 Phase 2): reanchorSeq invalidates an
// in-flight prewarm when the user seeks again or closes the modal, and
// reanchorPending keeps buffering listeners from clearing the 定位中 overlay.
let reanchorPending = false;
let reanchorSeq = 0;
let bufferingTimer = null;
let prewarmController = null;

function abortPrewarm() {
    if (prewarmController) {
        prewarmController.abort();
        prewarmController = null;
    }
}

function destroyHls() {
    if (hls) {
        try { hls.destroy(); } catch (e) { /* already torn down */ }
        hls = null;
    }
}

// Status overlay: "定位中…" while an anchored HLS session warms up and
// "缓冲中…" when playback stalls. Re-anchoring pauses the element instead of
// destroying it, so the last decoded frame stays on screen underneath.
function showVideoStatus(text) {
    if (!elements.videoStatusOverlay) return;
    elements.videoStatusLabel.textContent = text;
    elements.videoStatusOverlay.hidden = false;
}

function hideVideoStatus() {
    if (!elements.videoStatusOverlay) return;
    elements.videoStatusOverlay.hidden = true;
}

function clearBufferingTimer() {
    clearTimeout(bufferingTimer);
    bufferingTimer = null;
}

function scheduleBufferingOverlay() {
    if (!state.playingFile || reanchorPending) return;
    clearBufferingTimer();
    bufferingTimer = setTimeout(() => {
        bufferingTimer = null;
        if (state.playingFile && !reanchorPending && elements.videoPlayer.readyState < 3) {
            showVideoStatus('缓冲中…');
        }
    }, 300);
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
                        hideVideoStatus();
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

// updateSourceButtonState mirrors the active source mode on the control
// button: 原画 = direct HTTP Range playback, 转码 = HLS (or legacy pipe).
function updateSourceButtonState() {
    if (!elements.btnVideoSource) return;
    elements.btnVideoSource.textContent = state.useTranscode ? '转码' : '原画';
    elements.btnVideoSource.title = state.useTranscode
        ? '当前：服务端转码（兼容所有格式），点击切回原画直连'
        : '当前：原画直连（无转码），点击切换服务端转码';
}

// startDirectPlayback attaches the original file over HTTP Range. The browser
// demuxes/decodes natively, so seeking is instant and no ffmpeg session is
// created; hls.js is not even loaded on this path (spec 2026-10-30).
function startDirectPlayback(file, seekSec) {
    const startSec = seekSec > 0 ? seekSec : 0;
    state.useTranscode = false;
    state.transcodeStartOffset = 0;
    hlsStrategy = 'none';
    directPlayStartSec = startSec;
    directFallbackPending = false;
    updateSourceButtonState();
    applySource(buildDirectStreamUrl(state.apiBase, file.path), 'direct', startSec);
}

// startHlsPlayback is the universal fallback: hls.js over MSE when available,
// browser-native HLS on Safari, and the legacy single-pipe transcode when
// neither exists (hls.js failed to load). startSec anchors the transcode at
// the absolute position so playback resumes without clamping.
async function startHlsPlayback(file, seekSec) {
    const startSec = seekSec > 0 ? seekSec : 0;
    await loadHls();
    hlsStrategy = resolveHlsStrategy(elements.videoPlayer);
    state.useTranscode = true;
    state.transcodeStartOffset = startSec;
    updateSourceButtonState();
    if (hlsStrategy === 'none') {
        // No MSE and no native HLS: the m3u8 URL is unplayable here. Use the
        // legacy fMP4 transcode pipe instead of handing the browser a source
        // it cannot demux.
        applySource(buildTranscodeStreamUrl(state.apiBase, file.path, startSec), 'none', 0);
        return;
    }
    applySource(buildHlsPlaylistUrl(state.apiBase, file.path, startSec), hlsStrategy, 0);
}

// prewarmHlsPlaylist blocks until the anchored session has its first segment
// ready (the server holds the playlist request until ffmpeg publishes it).
// Resolving here means the subsequent hls.js attach starts with data already
// on disk, so the old frame only disappears for the MSE handover instead of
// for the whole transcode startup.
async function prewarmHlsPlaylist(file, startSec) {
    const token = getAuthToken();
    const controller = new AbortController();
    prewarmController = controller;
    const timer = setTimeout(() => controller.abort(), 10000);
    try {
        const res = await fetch(buildHlsPlaylistUrl(state.apiBase, file.path, startSec), {
            headers: token ? { Authorization: 'Bearer ' + token } : {},
            cache: 'no-store',
            signal: controller.signal,
        });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        await res.text();
    } finally {
        clearTimeout(timer);
        if (prewarmController === controller) prewarmController = null;
    }
}

// reanchorHls moves an HLS session to a new absolute position. The current
// frame keeps showing while the anchored session warms up; a newer seek or a
// modal close invalidates the in-flight pass via reanchorSeq.
async function reanchorHls(file, targetTime) {
    const startSec = Math.max(0, Math.floor(targetTime));
    const seq = ++reanchorSeq;
    const isStale = () => seq !== reanchorSeq || state.playingFile !== file;
    abortPrewarm();
    reanchorPending = true;
    clearBufferingTimer();
    elements.videoPlayer.pause();
    showVideoStatus('定位中…');

    const swap = () => {
        state.transcodeStartOffset = startSec;
        applySource(buildHlsPlaylistUrl(state.apiBase, file.path, startSec), hlsStrategy, 0);
        showVideoStatus('缓冲中…');
        elements.videoPlayer.play().catch(() => {});
    };

    try {
        if (hlsStrategy === 'hlsjs') {
            await prewarmHlsPlaylist(file, startSec);
        }
        if (isStale()) return;
        swap();
    } catch (err) {
        if (isStale()) return;
        // Prewarm timed out or the server refused a slot: fall back to the old
        // behaviour and let hls.js retry the source itself.
        console.warn('HLS prewarm failed, switching source anyway:', err);
        swap();
    } finally {
        if (seq === reanchorSeq) reanchorPending = false;
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
    directPlayStartSec = 0;
    directFallbackPending = false;
    reanchorSeq++;
    reanchorPending = false;
    abortPrewarm();
    clearBufferingTimer();
    hideVideoStatus();

    // Initialize custom controls
    elements.videoVolume.value = elements.videoPlayer.volume;
    elements.videoProgress.value = 0;
    elements.videoProgress.max = 100;
    elements.videoTimeDisplay.textContent = '00:00 / 加载中...';

    elements.videoModalTitle.textContent = file.name;
    elements.btnVideoDelete.style.display = state.enableDelete ? 'block' : 'none';

    // 续播：读取上次进度，决定起点（优先使用本地记录中的 duration 快速展示）。
    // 直连与 HLS 共用同一个绝对时间轴；起点如何应用由 startDirectPlayback /
    // startHlsPlayback 分别处理（原生 seek vs 转码锚点）。
    const saved = loadProgress(file.relative_path);
    let startSec = 0;
    if (saved && !isCompleted(saved.positionMs, saved.durationMs)) {
        startSec = Math.floor(saved.positionMs / 1000);
        if (saved.durationMs > 0) {
            state.videoDuration = saved.durationMs / 1000;
            elements.videoProgress.max = state.videoDuration;
            elements.videoTimeDisplay.textContent = `${formatTime(startSec)} / ${formatTime(state.videoDuration)}`;
        }
        showToast(`已从 ${formatTime(startSec)} 继续`, 'info');
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

    // 播放源选择（spec 2026-10-30）：浏览器原生容器优先原画直连（HTTP Range，
    // seek 即时且无转码）；解码失败由 error 监听自动回退 HLS；其余格式走 HLS。
    // 直连路径不加载 hls.js（415 KB vendor 脚本只在需要转码时按需注入）。
    if (shouldTryDirectPlay(file.name) && !directPlayBlocked.has(file.path)) {
        startDirectPlayback(file, startSec);
    } else {
        await startHlsPlayback(file, startSec);
    }
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

// fallbackToTranscode switches an in-progress direct-play session to HLS at the
// current absolute position and remembers the path so reopening skips direct
// play. Shared by the error and silent-failure listeners.
function fallbackToTranscode(message) {
    const file = state.playingFile;
    if (!file || directFallbackPending) return;
    directFallbackPending = true;
    directPlayBlocked.add(file.path);
    const pos = Math.max(directPlayStartSec, elements.videoPlayer.currentTime || 0);
    const seekSec = Math.max(0, Math.floor(pos));
    showToast(message, 'info');
    startHlsPlayback(file, seekSec)
        .then(() => {
            directFallbackPending = false;
            elements.videoPlayer.play().catch(() => {});
        })
        .catch(err => {
            directFallbackPending = false;
            console.error('HLS fallback failed:', err);
            showToast('转码回退失败，请稍后重试', 'error');
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
        // 先清 playingFile 再卸载媒体源：removeAttribute + load() 可能派发
        // 异步 error，避免直连回退监听把它当成播放失败。
        state.playingFile = null;
        directPlayStartSec = 0;
        directFallbackPending = false;
        reanchorSeq++;
        reanchorPending = false;
        abortPrewarm();
        clearBufferingTimer();
        hideVideoStatus();
        // src = '' 会让部分浏览器把当前文档 URL 当作媒体源再发一次请求；
        // 正确做法是移除属性再 load() 以真正释放媒体资源。
        elements.videoPlayer.removeAttribute('src');
        elements.videoPlayer.load();
        closeModalFocus(elements.modalVideoPlayer);
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

    // 原画 / 转码切换（spec 2026-10-30）：保持绝对播放位置，源切换后继续播放。
    elements.btnVideoSource.addEventListener('click', () => {
        const file = state.playingFile;
        if (!file || reanchorPending) return;
        const wasPlaying = !elements.videoPlayer.paused;
        const absolutePos = state.transcodeStartOffset + (elements.videoPlayer.currentTime || 0);
        const seekSec = Math.max(0, Math.floor(absolutePos));
        if (state.useTranscode) {
            startDirectPlayback(file, seekSec);
        } else {
            startHlsPlayback(file, seekSec).catch(err => {
                console.error('switch to transcode failed:', err);
                showToast('切换转码失败，请稍后重试', 'error');
            });
        }
        if (wasPlaying) elements.videoPlayer.play().catch(() => {});
    });

    // Direct-play fallback (spec 2026-10-30): a native <video> source the
    // browser cannot demux/decode (e.g. HEVC-bearing MP4, ProRes MOV) surfaces
    // as a media error. Switch to HLS at the same absolute position and remember
    // the path so the next open skips straight to HLS. Network/abort errors are
    // left alone — they must not mask token or connectivity problems.
    elements.videoPlayer.addEventListener('error', () => {
        const file = state.playingFile;
        if (!file || state.useTranscode || directFallbackPending) return;
        if (!elements.videoPlayer.currentSrc) return;
        const mediaErr = elements.videoPlayer.error;
        if (!mediaErr || !isUnsupportedMediaError(mediaErr.code)) return;
        fallbackToTranscode('浏览器无法直连该视频，已自动切换转码');
    });

    // Silent failure: when only the audio track is decodable the browser plays
    // sound over a black frame and never fires error. videoWidth staying 0 at
    // loadedmetadata proves no picture was decoded, so treat it like an
    // unsupported source instead of leaving the user on a black screen.
    elements.videoPlayer.addEventListener('loadedmetadata', () => {
        const file = state.playingFile;
        if (!file || state.useTranscode || directFallbackPending) return;
        if (!elements.videoPlayer.currentSrc) return;
        if (elements.videoPlayer.videoWidth > 0) return;
        fallbackToTranscode('浏览器无法解码该视频画面，已自动切换转码');
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
        state.isDraggingProgress = false;
        if (!state.playingFile) return;
        if (!state.useTranscode) {
            // Direct playback: the browser owns seeking over HTTP Range.
            elements.videoPlayer.currentTime = Math.max(0, targetTime);
            return;
        }
        const offsetSec = state.transcodeStartOffset;
        const seekable = elements.videoPlayer.seekable;
        const seekableEnd = seekable.length ? seekable.end(seekable.length - 1) : null;
        if (needsHlsRestart(targetTime, offsetSec, seekableEnd)) {
            reanchorHls(state.playingFile, targetTime);
        } else {
            elements.videoPlayer.currentTime = Math.max(0, targetTime - offsetSec);
        }
    });

    // Buffering feedback: only surface stalls longer than 300ms so ordinary
    // short seeks do not flash the overlay.
    elements.videoPlayer.addEventListener('waiting', scheduleBufferingOverlay);
    elements.videoPlayer.addEventListener('playing', () => {
        clearBufferingTimer();
        hideVideoStatus();
    });
    elements.videoPlayer.addEventListener('canplay', () => {
        if (reanchorPending) return;
        clearBufferingTimer();
        hideVideoStatus();
    });
    elements.videoPlayer.addEventListener('seeked', () => {
        if (reanchorPending) return;
        clearBufferingTimer();
        hideVideoStatus();
    });
    elements.videoPlayer.addEventListener('pause', () => {
        if (reanchorPending) return;
        hideVideoStatus();
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
