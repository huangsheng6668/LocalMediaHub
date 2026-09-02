// Video player feature module: extracted from app.js (openVideoPlayer + listeners).
import { state } from './state.js';
import { apiRequest, getAuthToken } from './api.js';
import { showToast } from './toast.js';
import { elements } from './dom.js';
import { formatTime, encodeRoutePath } from './utils.js';
import { deleteMediaFile } from './delete.js';
import { wheelToVolume } from './videoHelpers.js';
import { saveProgress, loadProgress, clearProgress, isCompleted } from './videoProgress.js';

// <video> tags cannot send Authorization headers, so auth-gated stream URLs
// (/api/v1/media/stream, /api/v1/system/stream) carry the bearer token as a
// ?token= query parameter (server redacts it from access logs; the page's
// Referrer-Policy: no-referrer keeps it out of referers). The public
// /api/v1/videos/*/stream route needs no token.
function withVideoAuthToken(url) {
    if (!/\/api\/v1\/(media|system)\/stream/.test(url)) return url;
    const token = getAuthToken();
    if (!token) return url;
    return url + (url.includes('?') ? '&' : '?') + 'token=' + encodeURIComponent(token);
}

// Module-scoped player state (shared across the player's internal helpers).
let controlsTimeout;
const PLAYBACK_SPEEDS = [0.75, 1, 1.25, 1.5, 2, 3];
let lastProgressSaveMs = 0;

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

// Helper for seeking via keyboard hotkeys
function seekTo(targetTime) {
    elements.videoProgress.value = targetTime;
    elements.videoProgress.dispatchEvent(new Event('input'));
    elements.videoProgress.dispatchEvent(new Event('change'));
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
    state.playingFile = file;
    state.transcodeStartOffset = 0;
    state.videoDuration = 0;

    // Initialize custom controls
    elements.videoVolume.value = elements.videoPlayer.volume;
    elements.videoProgress.value = 0;
    elements.videoProgress.max = 100;
    elements.videoTimeDisplay.textContent = '00:00 / 加载中...';

    // Auto transcode non-native formats (e.g. .ts, .mkv, .avi, .wmv, .flv)
    const ext = file.name.substring(file.name.lastIndexOf('.')).toLowerCase();
    const needsTranscode = ['.ts', '.mkv', '.avi', '.wmv', '.flv'].includes(ext);
    state.useTranscode = needsTranscode;
    state.vcodecMode = 'copy'; // default to fast remux (copy mode) to save server CPU!

    elements.videoModalTitle.textContent = file.name;
    elements.btnVideoDelete.style.display = state.enableDelete ? 'block' : 'none';
    if (state.useTranscode) {
        elements.btnVideoTranscode.classList.add('active');
        elements.btnVideoTranscode.textContent = '快速流';
    } else {
        elements.btnVideoTranscode.classList.remove('active');
        elements.btnVideoTranscode.textContent = '原画';
    }

    // Fetch video duration
    try {
        const durationUrl = `${state.apiBase}/api/v1/media/duration?path=${encodeURIComponent(file.path)}`;
        const data = await apiRequest(durationUrl);
        state.videoDuration = data.duration;
        elements.videoProgress.max = state.videoDuration;
        elements.videoTimeDisplay.textContent = `00:00 / ${formatTime(state.videoDuration)}`;
    } catch (e) {
        console.error('Error fetching duration:', e);
    }

    // Set video src URL
    let url = `${state.apiBase}/api/v1/videos/${encodeRoutePath(file.path)}/stream`;
    if (state.isSystemBrowse) {
        url = `${state.apiBase}/api/v1/system/stream?path=${encodeURIComponent(file.path)}`;
    }

    if (state.useTranscode) {
        url = `${state.apiBase}/api/v1/media/stream?path=${encodeURIComponent(file.path)}&transcode=true&start=0&vcodec=${state.vcodecMode}`;
        if (state.isSystemBrowse) {
            url = `${state.apiBase}/api/v1/system/stream?path=${encodeURIComponent(file.path)}&transcode=true&start=0&vcodec=${state.vcodecMode}`;
        }
    }

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

    // 重置倍速到 1x + 关闭菜单 + 高亮 1x（每次打开新视频）
    elements.videoPlayer.playbackRate = 1;
    elements.btnVideoSpeed.textContent = '1x';
    elements.videoSpeedMenu.hidden = true;
    updateSpeedMenuActive(1);

    elements.videoPlayer.src = withVideoAuthToken(url);
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

// All video-player-related event listener registrations (moved from setupEventListeners).
export function setupVideoPlayerListeners(elements) {
    // Close Video Modal
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

    // Delete Video inside Video Modal
    elements.btnVideoDelete.addEventListener('click', () => {
        if (state.playingFile) {
            deleteMediaFile(state.playingFile);
        }
    });

    // Transcode Video toggle inside video modal (cycles: 原画 -> 快速流/Remux -> 兼容转码 -> 原画)
    elements.btnVideoTranscode.addEventListener('click', () => {
        if (!state.playingFile) return;

        if (!state.useTranscode) {
            // direct -> copy (Remux)
            state.useTranscode = true;
            state.vcodecMode = 'copy';
            showToast('🔧 已切换至极速容器封装流（CPU占用极低）', 'success');
        } else if (state.vcodecMode === 'copy') {
            // copy -> libx264 (Full transcode)
            state.useTranscode = true;
            state.vcodecMode = 'libx264';
            showToast('🔧 已切换至 H.264 兼容性实时转码输出（CPU占用较高）', 'success');
        } else {
            // libx264 -> direct (Original)
            state.useTranscode = false;
            showToast('🚀 已切换至极速原文件直通流', 'success');
            const ext = state.playingFile.name.substring(state.playingFile.name.lastIndexOf('.')).toLowerCase();
            if (['.ts', '.mkv', '.avi', '.wmv', '.flv'].includes(ext)) {
                showToast('⚠️ 当前视频格式 (' + ext + ') 缺乏浏览器原生解码支持，如画面黑屏请切回快速流', 'info');
            }
        }

        const absolutePos = state.transcodeStartOffset + elements.videoPlayer.currentTime;

        let url = `${state.apiBase}/api/v1/media/stream?path=${encodeURIComponent(state.playingFile.path)}`;
        if (state.isSystemBrowse) {
            url = `${state.apiBase}/api/v1/system/stream?path=${encodeURIComponent(state.playingFile.path)}`;
        }

        if (state.useTranscode) {
            state.transcodeStartOffset = Math.floor(absolutePos);
            url += `&transcode=true&start=${state.transcodeStartOffset}&vcodec=${state.vcodecMode}`;
            elements.btnVideoTranscode.classList.add('active');
            if (state.vcodecMode === 'copy') {
                elements.btnVideoTranscode.textContent = '快速流';
            } else {
                elements.btnVideoTranscode.textContent = '转码中';
            }
        } else {
            state.transcodeStartOffset = 0;
            elements.btnVideoTranscode.classList.remove('active');
            elements.btnVideoTranscode.textContent = '原画';
        }

        elements.videoPlayer.src = withVideoAuthToken(url);
        elements.videoPlayer.load();

        if (!state.useTranscode) {
            // Seek native player after loadedmetadata
            elements.videoPlayer.addEventListener('loadedmetadata', function seekOnLoad() {
                elements.videoPlayer.currentTime = absolutePos;
            }, { once: true });
        }

        elements.videoPlayer.play();
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
        if (state.useTranscode) {
            state.transcodeStartOffset = Math.floor(targetTime);
            let url = `${state.apiBase}/api/v1/media/stream?path=${encodeURIComponent(state.playingFile.path)}&transcode=true&start=${state.transcodeStartOffset}&vcodec=${state.vcodecMode}`;
            if (state.isSystemBrowse) {
                url = `${state.apiBase}/api/v1/system/stream?path=${encodeURIComponent(state.playingFile.path)}&transcode=true&start=${state.transcodeStartOffset}&vcodec=${state.vcodecMode}`;
            }
            elements.videoPlayer.src = withVideoAuthToken(url);
            elements.videoPlayer.load();
            elements.videoPlayer.play();
        } else {
            elements.videoPlayer.currentTime = targetTime;
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
            const currentAbsoluteTime = state.transcodeStartOffset + elements.videoPlayer.currentTime;
            let targetTime = Math.max(0, currentAbsoluteTime - 5);
            seekTo(targetTime);
        }
        // ArrowRight: Quick seek forward (5 seconds)
        else if (e.key === 'ArrowRight') {
            e.preventDefault();
            const currentAbsoluteTime = state.transcodeStartOffset + elements.videoPlayer.currentTime;
            let targetTime = Math.min(state.videoDuration, currentAbsoluteTime + 5);
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
