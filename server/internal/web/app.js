import { state } from './state.js';
import { showToast } from './toast.js';
import { apiRequest, escapeHtml } from './api.js';
import { handleRoute } from './router.js';

// DOM Elements
const elements = {
    menuDashboard: document.getElementById('menu-dashboard'),
    menuBrowser: document.getElementById('menu-browser'),
    menuTags: document.getElementById('menu-tags'),
    menuSettings: document.getElementById('menu-settings'),
    
    pageTitle: document.getElementById('page-title'),
    btnTriggerScan: document.getElementById('btn-trigger-scan'),
    toastContainer: document.getElementById('toast-container'),
    
    // Views
    viewDashboard: document.getElementById('view-dashboard'),
    viewBrowser: document.getElementById('view-browser'),
    viewTags: document.getElementById('view-tags'),
    viewSettings: document.getElementById('view-settings'),
    
    // Stats
    statRoots: document.getElementById('stat-roots'),
    statVideos: document.getElementById('stat-videos'),
    statImages: document.getElementById('stat-images'),
    statTags: document.getElementById('stat-tags'),
    dashboardRecent: document.getElementById('dashboard-recent'),
    infoIp: document.getElementById('info-ip'),
    infoHost: document.getElementById('info-host'),
    infoScanRoots: document.getElementById('info-scan-roots'),
    
    // Browser
    browserBreadcrumbs: document.getElementById('browser-breadcrumbs'),
    browserSearchInput: document.getElementById('browser-search-input'),
    btnBrowserSearch: document.getElementById('btn-browser-search'),
    browserList: document.getElementById('browser-list'),
    
    // Tag creator
    tagNameInput: document.getElementById('tag-name'),
    colorDots: document.querySelectorAll('.color-dot'),
    btnCreateTag: document.getElementById('btn-create-tag'),
    tagsManagerList: document.getElementById('tags-manager-list'),
    
    // Settings inputs
    settingsRoots: document.getElementById('settings-roots'),
    settingsVideoExts: document.getElementById('settings-video-exts'),
    settingsImageExts: document.getElementById('settings-image-exts'),
    settingsAllowedRoots: document.getElementById('settings-allowed-roots'),
    settingsEnableDelete: document.getElementById('settings-enable-delete'),
    settingsThumbMax: document.getElementById('settings-thumb-max'),
    btnSaveSettings: document.getElementById('btn-save-settings'),
    
    // Modals
    modalVideoPlayer: document.getElementById('modal-video-player'),
    videoPlayer: document.getElementById('html5-video-player'),
    videoModalTitle: document.getElementById('video-modal-title'),
    btnVideoTranscode: document.getElementById('btn-video-transcode'),
    btnVideoDelete: document.getElementById('btn-video-delete'),
    btnCloseVideoModal: document.getElementById('btn-close-video-modal'),
    
    // Custom controls
    videoControlsOverlay: document.getElementById('video-controls-overlay'),
    videoProgress: document.getElementById('video-progress'),
    btnVideoPlayPause: document.getElementById('btn-video-play-pause'),
    videoTimeDisplay: document.getElementById('video-time-display'),
    btnVideoMute: document.getElementById('btn-video-mute'),
    videoVolume: document.getElementById('video-volume'),
    btnVideoFullscreen: document.getElementById('btn-video-fullscreen'),
    
    modalImagePreview: document.getElementById('modal-image-preview'),
    lightboxImg: document.getElementById('lightbox-img'),
    lightboxCaption: document.getElementById('lightbox-caption'),
    btnImagePrev: document.getElementById('btn-image-prev'),
    btnImageNext: document.getElementById('btn-image-next'),
    btnCloseImageModal: document.getElementById('btn-close-image-modal'),
    btnImageModeToggle: document.getElementById('btn-image-mode-toggle'),
    lightboxSingleView: document.getElementById('lightbox-single-view'),
    lightboxStitchView: document.getElementById('lightbox-stitch-view'),
    
    modalFileTags: document.getElementById('modal-file-tags'),
    tagModalFilePath: document.getElementById('tag-modal-file-path'),
    tagSelectorCheckboxes: document.getElementById('tag-selector-checkboxes'),
    btnCloseFileTagsModal: document.getElementById('btn-close-file-tags-modal')
};

// Initial Setup
document.addEventListener('DOMContentLoaded', () => {
    initApp();
    setupEventListeners();
});

// Initialize Application
async function initApp() {
    // Determine internal host IP from current window location
    const protocol = window.location.protocol;
    const host = window.location.host;
    state.apiBase = `${protocol}//${host}`;
    
    elements.infoHost.textContent = host;
    elements.infoIp.textContent = window.location.hostname;
    
    // Load config and initial data
    await loadConfig();
    await loadTags();
    
    // Parse Hash Routing on page load
    handleRoute(elements, renderDashboard, loadRoots, browsePath, renderTagsManager, renderSettings);
}

// Router
window.addEventListener('hashchange', () => {
    handleRoute(elements, renderDashboard, loadRoots, browsePath, renderTagsManager, renderSettings);
});

// Set up Event Listeners
function setupEventListeners() {
    // Scan Trigger
    elements.btnTriggerScan.addEventListener('click', async () => {
        try {
            await apiRequest(`${state.apiBase}/api/v1/admin/scan/trigger`, { method: 'POST' });
            showToast('🚀 已成功在后台触发全量媒体重扫描！', 'success');
        } catch (e) {
            showToast(`扫描启动失败: ${e.message}`, 'error');
        }
    });

    // Tag Color Picker selection
    elements.colorDots.forEach(dot => {
        dot.addEventListener('click', (e) => {
            elements.colorDots.forEach(d => d.classList.remove('active'));
            dot.classList.add('active');
        });
    });

    // Create Tag Button
    elements.btnCreateTag.addEventListener('click', async () => {
        const name = elements.tagNameInput.value.trim();
        const activeColorDot = document.querySelector('.color-dot.active');
        const color = activeColorDot ? activeColorDot.getAttribute('data-color') : '#7c3aed';
        
        if (!name) {
            showToast('请输入标签分类名称', 'error');
            return;
        }

        try {
            await apiRequest(`${state.apiBase}/api/v1/tags`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name, color })
            });
            showToast(`成功创建标签 [${name}]`, 'success');
            elements.tagNameInput.value = '';
            await loadTags();
            renderTagsManager();
        } catch (e) {
            showToast(`标签创建失败: ${e.message}`, 'error');
        }
    });

    // Save Settings
    elements.btnSaveSettings.addEventListener('click', async () => {
        const rootsText = elements.settingsRoots.value.trim();
        const roots = rootsText ? rootsText.split('\n').map(r => r.trim()).filter(r => r !== '') : [];

        try {
            await apiRequest(`${state.apiBase}/api/v1/admin/config`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ roots })
            });
            showToast('💾 系统路径配置更新保存成功！', 'success');
            await loadConfig();
            renderSettings();
        } catch (e) {
            showToast(`配置保存失败: ${e.message}`, 'error');
        }
    });

    // Search Box Listener
    elements.btnBrowserSearch.addEventListener('click', triggerBrowserSearch);
    elements.browserSearchInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') triggerBrowserSearch();
    });

    // Close Video Modal
    elements.btnCloseVideoModal.addEventListener('click', () => {
        elements.videoPlayer.pause();
        elements.videoPlayer.src = '';
        elements.modalVideoPlayer.classList.remove('active');
        state.playingFile = null;
        state.videoDuration = 0;
        state.transcodeStartOffset = 0;
        state.isDraggingProgress = false;
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
        
        elements.videoPlayer.src = url;
        elements.videoPlayer.load();
        
        if (!state.useTranscode) {
            // Seek native player after loadedmetadata
            elements.videoPlayer.addEventListener('loadedmetadata', function seekOnLoad() {
                elements.videoPlayer.currentTime = absolutePos;
                elements.videoPlayer.removeEventListener('loadedmetadata', seekOnLoad);
            });
        }
        
        elements.videoPlayer.play();
    });

    // Custom controls: Play / Pause toggle
    const togglePlayPause = () => {
        if (elements.videoPlayer.paused) {
            elements.videoPlayer.play();
            elements.btnVideoPlayPause.textContent = '⏸';
        } else {
            elements.videoPlayer.pause();
            elements.btnVideoPlayPause.textContent = '▶';
        }
    };
    elements.btnVideoPlayPause.addEventListener('click', togglePlayPause);
    elements.videoPlayer.addEventListener('click', togglePlayPause);
    
    // Sync play/pause state to button text
    elements.videoPlayer.addEventListener('play', () => {
        elements.btnVideoPlayPause.textContent = '⏸';
    });
    elements.videoPlayer.addEventListener('pause', () => {
        elements.btnVideoPlayPause.textContent = '▶';
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
            elements.videoPlayer.src = url;
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
        elements.btnVideoMute.textContent = vol === 0 ? '🔇' : '🔊';
    });

    // Custom controls: Mute Button
    elements.btnVideoMute.addEventListener('click', () => {
        elements.videoPlayer.muted = !elements.videoPlayer.muted;
        if (elements.videoPlayer.muted) {
            elements.btnVideoMute.textContent = '🔇';
            elements.videoVolume.value = 0;
        } else {
            elements.btnVideoMute.textContent = '🔊';
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
            elements.btnVideoMute.textContent = '🔊';
        }
        // ArrowDown: Volume down
        else if (e.key === 'ArrowDown') {
            e.preventDefault();
            const vol = Math.max(0, elements.videoPlayer.volume - 0.05);
            elements.videoPlayer.volume = vol;
            elements.videoVolume.value = vol;
            if (vol === 0) {
                elements.videoPlayer.muted = true;
                elements.btnVideoMute.textContent = '🔇';
            }
        }
    });

    // Helper for seeking via keyboard hotkeys
    const seekTo = (targetTime) => {
        elements.videoProgress.value = targetTime;
        elements.videoProgress.dispatchEvent(new Event('input'));
        elements.videoProgress.dispatchEvent(new Event('change'));
    };

    // Auto-hide controls overlay on mouse inactivity
    let controlsTimeout;
    const wrapper = elements.videoPlayer.parentElement; // .video-player-wrapper
    const resetControlsTimer = () => {
        elements.videoControlsOverlay.classList.add('active');
        clearTimeout(controlsTimeout);
        controlsTimeout = setTimeout(() => {
            if (!elements.videoPlayer.paused) {
                elements.videoControlsOverlay.classList.remove('active');
            }
        }, 2000);
    };
    wrapper.addEventListener('mousemove', resetControlsTimer);
    elements.videoPlayer.addEventListener('play', resetControlsTimer);

    // Close Image Modal (Lightbox)
    elements.btnCloseImageModal.addEventListener('click', () => {
        elements.modalImagePreview.classList.remove('active');
        elements.lightboxImg.src = '';
        elements.lightboxStitchView.innerHTML = '';
    });

    // Lightbox navigation
    elements.btnImagePrev.addEventListener('click', () => navigateLightbox(-1));
    elements.btnImageNext.addEventListener('click', () => navigateLightbox(1));
    
    // Toggle Stitch Mode
    elements.btnImageModeToggle.addEventListener('click', () => {
        state.lightboxStitchMode = !state.lightboxStitchMode;
        localStorage.setItem('lightboxStitchMode', state.lightboxStitchMode);
        renderLightboxImage();
    });

    // Stitch View Scroll listener to dynamically update the active index
    elements.lightboxStitchView.addEventListener('scroll', () => {
        if (!state.lightboxStitchMode) return;
        const items = elements.lightboxStitchView.querySelectorAll('.stitch-image-item');
        const containerRect = elements.lightboxStitchView.getBoundingClientRect();
        
        let closestIndex = state.lightboxIndex;
        let minDistance = Infinity;
        
        items.forEach((item, idx) => {
            const rect = item.getBoundingClientRect();
            // Distance from item's top to container's top
            const distance = Math.abs(rect.top - containerRect.top);
            if (distance < minDistance) {
                minDistance = distance;
                closestIndex = idx;
            }
        });
        
        if (closestIndex !== state.lightboxIndex && closestIndex >= 0 && closestIndex < state.lightboxFiles.length) {
            state.lightboxIndex = closestIndex;
        }
    });

    document.addEventListener('keydown', (e) => {
        if (!elements.modalImagePreview.classList.contains('active')) return;
        if (e.key === 'ArrowLeft') {
            if (state.lightboxStitchMode) {
                // In stitch mode, scrolling up or back is nice
                const prevIndex = Math.max(0, state.lightboxIndex - 1);
                const targetImg = document.getElementById(`stitch-img-${prevIndex}`);
                if (targetImg) targetImg.scrollIntoView({ behavior: 'smooth', block: 'start' });
            } else {
                navigateLightbox(-1);
            }
        }
        if (e.key === 'ArrowRight') {
            if (state.lightboxStitchMode) {
                const nextIndex = Math.min(state.lightboxFiles.length - 1, state.lightboxIndex + 1);
                const targetImg = document.getElementById(`stitch-img-${nextIndex}`);
                if (targetImg) targetImg.scrollIntoView({ behavior: 'smooth', block: 'start' });
            } else {
                navigateLightbox(1);
            }
        }
        if (e.key === 'Escape') {
            elements.modalImagePreview.classList.remove('active');
            elements.lightboxImg.src = '';
            elements.lightboxStitchView.innerHTML = '';
        }
    });

    // Close Tag modal dialog
    elements.btnCloseFileTagsModal.addEventListener('click', () => {
        elements.modalFileTags.classList.remove('active');
        state.taggingFile = null;
    });
}



// Fetch configs
async function loadConfig() {
    try {
        const data = await apiRequest(`${state.apiBase}/api/v1/admin/config`);
        state.folders = data.scan.roots || [];
        state.videoExts = data.scan.video_extensions || [];
        state.imageExts = data.scan.image_extensions || [];
        state.allowedRoots = (data.system && data.system.allowed_roots) || [];
        state.enableDelete = (data.system && data.system.enable_delete) || false;
        state.thumbMax = (data.thumbnail && data.thumbnail.max_size) || 300;
        
        elements.infoScanRoots.textContent = state.folders.join(', ') || '全盘自动检测';
    } catch (e) {
        console.error('loadConfig error:', e);
        showToast('无法从后端获取系统配置: ' + e.message, 'error');
    }
}

// Fetch Tags
async function loadTags() {
    try {
        const tags = await apiRequest(`${state.apiBase}/api/v1/tags`);
        state.tags = tags || [];
        
        // Fetch file tags mapping in parallel
        try {
            const fileTagsMap = await apiRequest(`${state.apiBase}/api/v1/tags/file-tags`);
            state.fileTagsMap = fileTagsMap || {};
        } catch (err) {
            console.error('load file-tags mapping error:', err);
        }
    } catch (e) {
        console.error('loadTags error:', e);
        showToast('加载标签失败: ' + e.message, 'error');
    }
}

// Render Dashboard (Tab 1)
async function renderDashboard() {
    // 1. Fetch total files
    try {
        let totalVideos = 0;
        try {
            const videosData = await apiRequest(`${state.apiBase}/api/v1/videos?page=1&page_size=1`);
            totalVideos = videosData.total || 0;
        } catch (err) {
            console.error('Fetch video count error:', err);
        }
        
        let totalImages = 0;
        try {
            const imagesData = await apiRequest(`${state.apiBase}/api/v1/images?page=1&page_size=1`);
            totalImages = imagesData.total || 0;
        } catch (err) {
            console.error('Fetch image count error:', err);
        }
        
        elements.statRoots.textContent = state.folders.length || '全盘';
        elements.statVideos.textContent = totalVideos;
        elements.statImages.textContent = totalImages;
        elements.statTags.textContent = state.tags.length;
        
        // 2. Mock a list of files or load first page of videos/images for recent preview
        try {
            const data = await apiRequest(`${state.apiBase}/api/v1/videos?page=1&page_size=3`);
            const items = data.items || [];
            
            if (items.length === 0) {
                elements.dashboardRecent.innerHTML = '<div class="empty-state">暂无最近媒体数据</div>';
                elements.dashboardRecent.classList.add('empty-state');
            } else {
                elements.dashboardRecent.classList.remove('empty-state');
                elements.dashboardRecent.innerHTML = items.map(file => {
                    return `
                        <div class="info-item" style="cursor:pointer;" onclick="openVideoPlayer(${JSON.stringify(file).replace(/"/g, '&quot;')})">
                            <span class="info-label">🎬 ${escapeHtml(file.name)}</span>
                            <span class="info-value" style="font-size:11px;">${formatSize(file.size)}</span>
                        </div>
                    `;
                }).join('');
            }
        } catch (err) {
            elements.dashboardRecent.innerHTML = '<div class="empty-state">连接服务端接口失败</div>';
        }
    } catch (e) {
        elements.dashboardRecent.innerHTML = '<div class="empty-state">连接服务端接口失败</div>';
    }
}

// Load Root directories in file browser
function loadRoots() {
    state.currentPath = '';
    state.pathHistory = [];
    state.isSystemBrowse = false;
    
    // Breadcrumbs
    elements.browserBreadcrumbs.innerHTML = '<span class="crumb active">根目录</span>';
    
    // Grid list
    if (state.folders.length === 0) {
        elements.browserList.innerHTML = `
            <div style="grid-column: 1/-1; text-align: center; padding: 48px; color: var(--text-muted);">
                <h3>未配置扫描共享目录</h3>
                <p style="margin-top:8px;">将在下方列出自动发现的 Windows 磁盘分区</p>
                <button class="btn btn-primary" style="margin-top:16px;" id="btn-browse-drives">浏览磁盘驱动器</button>
            </div>
        `;
        document.getElementById('btn-browse-drives')?.addEventListener('click', loadSystemDrives);
        return;
    }
    
    elements.browserList.innerHTML = state.folders.map(path => {
        const name = path.replace(/\\/g, '/').split('/').filter(Boolean).pop() || path;
        return `
            <div class="media-card" onclick="browsePath('${path.replace(/\\/g, '/')}')">
                <div class="card-preview">
                    <span class="card-preview-icon">📁</span>
                </div>
                <div class="card-details">
                    <div class="card-title" title="${escapeHtml(name)}">${escapeHtml(name)}</div>
                    <div class="card-meta">
                        <span>共享库</span>
                    </div>
                </div>
            </div>
        `;
    }).join('');
}

// Browse arbitrary absolute paths (System folders)
async function loadSystemDrives() {
    state.isSystemBrowse = true;
    state.currentPath = '/system';
    
    elements.browserBreadcrumbs.innerHTML = '<span class="crumb" onclick="loadRoots()">根目录</span><span class="crumb active">磁盘盘符</span>';
    
    try {
        const drives = await apiRequest(`${state.apiBase}/api/v1/system/drives`);
        if (drives && drives.length > 0) {
            elements.browserList.innerHTML = drives.map(drive => {
                return `
                    <div class="media-card" onclick="browsePath('${drive.replace(/\\/g, '/')}')">
                        <div class="card-preview">
                            <span class="card-preview-icon">💾</span>
                        </div>
                        <div class="card-details">
                            <div class="card-title">${escapeHtml(drive)}</div>
                            <div class="card-meta">
                                <span>本地磁盘</span>
                            </div>
                        </div>
                    </div>
                `;
            }).join('');
        } else {
            elements.browserList.innerHTML = '<div style="grid-column:1/-1; text-align:center; padding:48px;">无法获取本地磁盘，可能未在 config.yaml 启用 system.allowed_roots</div>';
        }
    } catch (e) {
        showToast('获取系统磁盘盘符失败: ' + e.message, 'error');
    }
}

// Browse specific path
async function browsePath(path) {
    state.currentPath = path;
    
    let url = `${state.apiBase}/api/v1/folders/${encodeRoutePath(path)}/browse`;
    if (state.isSystemBrowse) {
        url = `${state.apiBase}/api/v1/system/browse?path=${encodeURIComponent(path)}`;
    }
    
    elements.browserList.innerHTML = '<div style="grid-column: 1/-1; text-align:center; padding:48px;">正在读取目录结构...</div>';
    
    try {
        const data = await apiRequest(url);
        
        state.currentFolders = data.folders || [];
        state.currentFiles = data.files || [];
        
        renderBrowserList();
        renderBreadcrumbs(path);
    } catch (e) {
        showToast(`浏览失败: ${e.message}`, 'error');
        loadRoots();
    }
}

// Render folder & file cards in browser grid
function renderBrowserList() {
    if (state.currentFolders.length === 0 && state.currentFiles.length === 0) {
        elements.browserList.innerHTML = '<div style="grid-column: 1/-1; text-align:center; padding:48px; color: var(--text-muted);">📁 当前目录为空（无媒体文件）</div>';
        return;
    }
    
    let html = '';
    
    // 1. Folders
    state.currentFolders.forEach(folder => {
        html += `
            <div class="media-card">
                <div class="card-preview" onclick="browsePath('${folder.path.replace(/\\/g, '/')}')">
                    <span class="card-preview-icon">📁</span>
                </div>
                
                <!-- Action Hover overlay icons for folder -->
                <div class="card-actions-overlay">
                    ${state.enableDelete && !folder.is_root ? `<button class="card-action-btn delete-btn" title="删除文件夹" onclick="event.stopPropagation(); deleteFolder(${JSON.stringify(folder).replace(/"/g, '&quot;')})">🗑️</button>` : ''}
                </div>
                
                <div class="card-details" onclick="browsePath('${folder.path.replace(/\\/g, '/')}')">
                    <div class="card-title" title="${folder.name}">${folder.name}</div>
                    <div class="card-meta">
                        <span>文件夹</span>
                    </div>
                </div>
            </div>
        `;
    });
    
    // 2. Media Files
    state.currentFiles.forEach(file => {
        const isVideo = file.media_type === 'video';
        let previewHtml = '';
        let playOverlay = '';
        
        // Build API URL for thumbnails
        let thumbUrl = `${state.apiBase}/api/v1/images/${encodeRoutePath(file.relative_path)}/thumbnail`;
        if (state.isSystemBrowse) {
            thumbUrl = `${state.apiBase}/api/v1/system/thumbnail?path=${encodeURIComponent(file.path)}`;
        }
        
        if (isVideo) {
            // Videos support the new FFmpeg dynamic keyframe thumbnail
            const videoThumbUrl = `${state.apiBase}/api/v1/videos/${encodeRoutePath(file.relative_path)}/thumbnail`;
            const videoUrl = state.isSystemBrowse ? thumbUrl : videoThumbUrl;
            
            previewHtml = `<img src="${videoUrl}" onerror="this.onerror=null; this.parentNode.innerHTML='<span class=\\'card-preview-icon\\'>🎬</span>'">`;
            
            playOverlay = `
                <div class="play-overlay">
                    <div class="play-button-circle">▶</div>
                </div>
            `;
        } else {
            // Images
            previewHtml = `<img src="${thumbUrl}" onerror="this.onerror=null; this.parentNode.innerHTML='<span class=\\'card-preview-icon\\'>🖼️</span>'">`;
        }
        
        // Tags on this file
        const fileTags = state.fileTagsMap[file.path] || [];
        const isTagged = fileTags.length > 0;
        const tagDotHtml = fileTags.map(tag => `
            <span style="display:inline-block; width:8px; height:8px; border-radius:50%; background-color:${tag.color};" title="${tag.name}"></span>
        `).join('');
        
        const cardClass = `media-card ${isTagged ? 'tagged' : ''}`;
        
        html += `
            <div class="${cardClass}" id="file-card-${safeBtoa(file.path).replace(/=/g, '')}">
                <div class="card-preview" onclick="openMedia(${JSON.stringify(file).replace(/"/g, '&quot;')})">
                    ${previewHtml}
                    ${playOverlay}
                </div>
                
                <!-- Action Hover overlay icons -->
                <div class="card-actions-overlay">
                    <button class="card-action-btn" title="分类标签" onclick="openTaggingDialog(${JSON.stringify(file).replace(/"/g, '&quot;')})">🏷️</button>
                    ${state.enableDelete ? `<button class="card-action-btn delete-btn" title="删除文件" onclick="deleteMediaFile(${JSON.stringify(file).replace(/"/g, '&quot;')})">🗑️</button>` : ''}
                </div>
                
                <div class="card-details" onclick="openMedia(${JSON.stringify(file).replace(/"/g, '&quot;')})">
                    <div class="card-title" title="${file.name}">${file.name}</div>
                    <div class="card-meta">
                        <span class="card-badge">${file.extension.toUpperCase()}</span>
                        <div style="display:flex; gap:3px; align-items:center;">
                            ${tagDotHtml}
                            <span>${formatSize(file.size)}</span>
                        </div>
                    </div>
                </div>
            </div>
        `;
    });
    
    elements.browserList.innerHTML = html;
}

// Render Breadcrumbs
function renderBreadcrumbs(path) {
    const isWin = path.includes(':');
    const separator = isWin ? '/' : '/';
    const segments = path.split(/[/\\]+/).filter(Boolean);
    
    let html = `<span class="crumb" onclick="loadRoots()">根目录</span>`;
    
    let currentAccumulated = '';
    segments.forEach((seg, index) => {
        // Handle Windows drive root segment, e.g. "D:" -> "D:/"
        if (index === 0 && isWin) {
            currentAccumulated = seg + '/';
        } else {
            currentAccumulated += (index === 0 ? '' : '/') + seg;
        }
        
        const isLast = index === segments.length - 1;
        if (isLast) {
            html += `<span class="crumb active">${seg}</span>`;
        } else {
            html += `<span class="crumb" onclick="browsePath('${currentAccumulated}')">${seg}</span>`;
        }
    });
    
    elements.browserBreadcrumbs.innerHTML = html;
}

// Filter current files recursively in Browser
async function triggerBrowserSearch() {
    const query = elements.browserSearchInput.value.trim();
    if (!query) {
        if (state.currentPath) browsePath(state.currentPath);
        else loadRoots();
        return;
    }
    
    elements.browserList.innerHTML = '<div style="grid-column: 1/-1; text-align:center; padding:48px;">全局模糊匹配检索中...</div>';
    
    try {
        const url = `${state.apiBase}/api/v1/search?query=${encodeURIComponent(query)}&path=${encodeURIComponent(state.currentPath)}`;
        const data = await apiRequest(url);
        
        state.currentFolders = data.folders || [];
        state.currentFiles = data.files || [];
        renderBrowserList();
        
        elements.browserBreadcrumbs.innerHTML = `
            <span class="crumb" onclick="browsePath('${state.currentPath}')">返回上级目录</span>
            <span class="crumb active">关于 "${escapeHtml(query)}" 的结果</span>
        `;
    } catch (e) {
        showToast(`搜索失败: ${e.message}`, 'error');
    }
}

// Open Video/Image assets
function openMedia(file) {
    if (file.media_type === 'video') {
        openVideoPlayer(file);
    } else if (file.media_type === 'image') {
        openImageLightbox(file);
    }
}

// Video player setup & popup
async function openVideoPlayer(file) {
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
    let url = `${state.apiBase}/api/v1/videos/${encodeRoutePath(file.relative_path)}/stream`;
    if (state.isSystemBrowse) {
        url = `${state.apiBase}/api/v1/system/stream?path=${encodeURIComponent(file.path)}`;
    }
    
    if (state.useTranscode) {
        url = `${state.apiBase}/api/v1/media/stream?path=${encodeURIComponent(file.path)}&transcode=true&start=0&vcodec=${state.vcodecMode}`;
        if (state.isSystemBrowse) {
            url = `${state.apiBase}/api/v1/system/stream?path=${encodeURIComponent(file.path)}&transcode=true&start=0&vcodec=${state.vcodecMode}`;
        }
    }
    
    elements.videoPlayer.src = url;
    elements.modalVideoPlayer.classList.add('active');
    elements.videoPlayer.load();
    elements.videoPlayer.play();
}

// Image Lightbox popup
function openImageLightbox(file) {
    // Collect all image files in the current view to allow previous/next navigation
    state.lightboxFiles = state.currentFiles.filter(f => f.media_type === 'image');
    
    // Alphanumeric natural sort in ascending order (by name)
    state.lightboxFiles.sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' }));
    
    state.lightboxIndex = state.lightboxFiles.findIndex(f => f.path === file.path);
    
    renderLightboxImage();
    elements.modalImagePreview.classList.add('active');
}

// Show image in lightbox
function renderLightboxImage() {
    if (state.lightboxIndex < 0 || state.lightboxIndex >= state.lightboxFiles.length) return;
    
    if (state.lightboxStitchMode) {
        elements.btnImageModeToggle.classList.add('active');
        elements.btnImageModeToggle.textContent = '📖 单张模式';
        
        elements.lightboxSingleView.style.display = 'none';
        elements.lightboxStitchView.style.display = 'flex';
        elements.btnImagePrev.style.display = 'none';
        elements.btnImageNext.style.display = 'none';
        
        // Render all files in stitch view if not already loaded/rendered
        if (elements.lightboxStitchView.children.length === 0) {
            elements.lightboxStitchView.innerHTML = '';
            state.lightboxFiles.forEach((file, idx) => {
                let url = `${state.apiBase}/api/v1/images/${encodeRoutePath(file.relative_path)}/original`;
                if (state.isSystemBrowse) {
                    url = `${state.apiBase}/api/v1/system/original?path=${encodeURIComponent(file.path)}`;
                }
                const imgContainer = document.createElement('div');
                imgContainer.className = 'stitch-image-item';
                imgContainer.id = `stitch-img-${idx}`;
                imgContainer.innerHTML = `
                    <img src="${url}" alt="${file.name}" loading="lazy">
                    <div class="stitch-image-caption">${file.name} (${idx + 1}/${state.lightboxFiles.length})</div>
                `;
                elements.lightboxStitchView.appendChild(imgContainer);
            });
        }
        
        // Scroll target image into view
        const targetImg = document.getElementById(`stitch-img-${state.lightboxIndex}`);
        if (targetImg) {
            setTimeout(() => {
                targetImg.scrollIntoView({ behavior: 'auto', block: 'start' });
            }, 50);
        }
    } else {
        elements.btnImageModeToggle.classList.remove('active');
        elements.btnImageModeToggle.textContent = '📖 拼接模式';
        
        elements.lightboxSingleView.style.display = 'flex';
        elements.lightboxStitchView.style.display = 'none';
        elements.btnImagePrev.style.display = 'flex';
        elements.btnImageNext.style.display = 'flex';
        elements.lightboxStitchView.innerHTML = ''; // Clean up stitch view content to free memory
        
        const file = state.lightboxFiles[state.lightboxIndex];
        
        let url = `${state.apiBase}/api/v1/images/${encodeRoutePath(file.relative_path)}/original`;
        if (state.isSystemBrowse) {
            url = `${state.apiBase}/api/v1/system/original?path=${encodeURIComponent(file.path)}`;
        }
        
        elements.lightboxImg.src = url;
        elements.lightboxCaption.textContent = `${file.name} (${state.lightboxIndex + 1}/${state.lightboxFiles.length})`;
    }
}

// Navigate lightbox
function navigateLightbox(dir) {
    if (state.lightboxFiles.length <= 1) return;
    state.lightboxIndex += dir;
    
    // Wrap around boundaries
    if (state.lightboxIndex < 0) state.lightboxIndex = state.lightboxFiles.length - 1;
    if (state.lightboxIndex >= state.lightboxFiles.length) state.lightboxIndex = 0;
    
    renderLightboxImage();
}

// Open File tag editor mapping dialog
function openTaggingDialog(file) {
    state.taggingFile = file;
    elements.tagModalFilePath.textContent = `文件：${file.path}`;
    
    const fileTags = state.fileTagsMap[file.path] || [];
    const mappedIds = fileTags.map(t => t.id);
    
    elements.tagSelectorCheckboxes.innerHTML = state.tags.map(tag => {
        const checked = mappedIds.includes(tag.id) ? 'checked' : '';
        return `
            <label class="tag-selector-item">
                <span style="display:flex; align-items:center; gap:8px;">
                    <span style="width:12px; height:12px; border-radius:50%; background-color:${escapeHtml(tag.color)};"></span>
                    <span>${escapeHtml(tag.name)}</span>
                </span>
                <input type="checkbox" data-tag-id="${escapeHtml(tag.id)}" ${checked} onchange="toggleFileTagAssociation(this, '${escapeHtml(tag.id)}', '${escapeHtml(file.path.replace(/\\/g, '\\\\'))}')">
            </label>
        `;
    }).join('');
    
    if (state.tags.length === 0) {
        elements.tagSelectorCheckboxes.innerHTML = '<p style="color:var(--text-muted); font-size:13px;">请先去“标签管理”中新建分类标签。</p>';
    }
    
    elements.modalFileTags.classList.add('active');
}

// Toggle association between a file and a tag
async function toggleFileTagAssociation(checkbox, tagId, filePath) {
    const isAssociate = checkbox.checked;
    const url = `${state.apiBase}/api/v1/tags/${tagId}/files/${encodeRoutePath(filePath)}`;
    
    try {
        await apiRequest(url, {
            method: isAssociate ? 'POST' : 'DELETE'
        });
        
        showToast(isAssociate ? '🏷️ 标签关联成功！' : '🏷️ 标签已解除关联', 'success');
        await loadTags(); // Refresh tags mappings in memory
        
        // Re-render folder list cards to show/hide color dots
        if (state.activeTab === 'browser') {
            renderBrowserList();
        }
        
        // Re-render container card dot state
        const cleanCardId = `file-card-${safeBtoa(filePath).replace(/=/g, '')}`;
        const cardEl = document.getElementById(cleanCardId);
        if (cardEl) {
            // Determine if file has any tags left
            const fileTags = state.fileTagsMap[filePath] || [];
            if (fileTags.length > 0) cardEl.classList.add('tagged');
            else cardEl.classList.remove('tagged');
            
            // Re-render custom tags dots/chips on the item details
            const dotsEl = cardEl.querySelector('.tag-color-dots');
            if (dotsEl) {
                dotsEl.innerHTML = fileTags.map(tag => `
                    <span class="tag-dot" style="background-color: ${escapeHtml(tag.color)}" title="${escapeHtml(tag.name)}"></span>
                `).join('');
            }
        }
    } catch (e) {
        checkbox.checked = !isAssociate; // Revert
        showToast(`标签关联失败: ${e.message}`, 'error');
    }
}

// Render Tags Manager (Tab 3)
function renderTagsManager() {
    if (state.tags.length === 0) {
        elements.tagsManagerList.innerHTML = '<p style="color:var(--text-muted); font-size:14px;">暂无标签分类，请在左侧新建。</p>';
        return;
    }
    
    elements.tagsManagerList.innerHTML = state.tags.map(tag => {
        return `
            <div class="tag-chip" style="background-color: ${escapeHtml(tag.color)}33; border-color: ${escapeHtml(tag.color)};">
                <span style="display:inline-block; width:10px; height:10px; border-radius:50%; background-color:${escapeHtml(tag.color)};"></span>
                <span>${escapeHtml(tag.name)}</span>
                <button class="btn-tag-delete" title="删除分类标签" onclick="deleteTag('${escapeHtml(tag.id)}', '${escapeHtml(tag.name)}')">✕</button>
            </div>
        `;
    }).join('');
}

// Delete tag definition
async function deleteTag(tagId, name) {
    if (!confirm(`确定要彻底删除标签 [${name}] 吗？\n所有关联文件的分类记录也会一并清除。`)) return;
    
    try {
        await apiRequest(`${state.apiBase}/api/v1/tags/${tagId}`, {
            method: 'DELETE'
        });
        showToast(`已成功删除标签 [${name}]`, 'success');
        await loadTags();
        renderTagsManager();
    } catch (e) {
        showToast(`删除失败: ${e.message}`, 'error');
    }
}

// Render Settings View (Tab 4)
function renderSettings() {
    elements.settingsRoots.value = state.folders.join('\n');
    elements.settingsVideoExts.textContent = state.videoExts.join(', ') || '未配置';
    elements.settingsImageExts.textContent = state.imageExts.join(', ') || '未配置';
    elements.settingsAllowedRoots.textContent = state.allowedRoots.join(', ') || '未限制/不可浏览系统';
    elements.settingsEnableDelete.textContent = state.enableDelete ? '已开启 (运行在客户端删除 PC 文件)' : '已禁用 (安全只读)';
    elements.settingsThumbMax.textContent = `${state.thumbMax} px`;
}

// Utility formatting functions
function formatSize(bytes) {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}

// Encode path segments while keeping literal forward slashes (required for Echo wildcards)
function encodeRoutePath(path) {
    if (!path) return '';
    return path.replace(/\\/g, '/').split('/').map(encodeURIComponent).join('/');
}

// Unicode-safe Base64 encoding for HTML element IDs
function safeBtoa(str) {
    try {
        return btoa(unescape(encodeURIComponent(str)));
    } catch (e) {
        return str.replace(/[^a-zA-Z0-9]/g, '_');
    }
}

// Utility: Format seconds into HH:MM:SS or MM:SS
function formatTime(seconds) {
    if (isNaN(seconds) || seconds === Infinity) return '00:00';
    const hrs = Math.floor(seconds / 3600);
    const mins = Math.floor((seconds % 3600) / 60);
    const secs = Math.floor(seconds % 60);
    
    const formattedMins = mins < 10 ? `0${mins}` : mins;
    const formattedSecs = secs < 10 ? `0${secs}` : secs;
    
    if (hrs > 0) {
        return `${hrs}:${formattedMins}:${formattedSecs}`;
    }
    return `${formattedMins}:${formattedSecs}`;
}

// Delete media file from filesystem
async function deleteMediaFile(file) {
    if (!state.enableDelete) {
        showToast('服务端已禁用删除功能', 'error');
        return;
    }
    
    if (!confirm(`⚠️ 警告：确定要彻底删除该媒体文件吗？\n此操作不可逆！\n\n文件：${file.name}`)) {
        return;
    }
    
    try {
        await apiRequest(`${state.apiBase}/api/v1/system/delete`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                path: file.path,
                recursive: false
            })
        });
        
        showToast('文件删除成功', 'success');
        // If the video modal is open playing this file, close it
        if (state.playingFile && state.playingFile.path === file.path) {
            elements.btnCloseVideoModal.click();
        }
        // Reload folder contents
        if (state.activeTab === 'browser') {
            browsePath(state.currentPath);
        } else if (state.activeTab === 'dashboard') {
            initDashboard();
        }
    } catch (e) {
        showToast(`删除失败: ${e.message}`, 'error');
    }
}

// Delete folder from filesystem recursively
async function deleteFolder(folder) {
    if (!state.enableDelete) {
        showToast('服务端已禁用删除功能', 'error');
        return;
    }
    if (folder.is_root) {
        showToast('无法删除根共享目录', 'error');
        return;
    }
    
    if (!confirm(`⚠️ 警告：确定要彻底删除该文件夹及其中所有内容吗？\n此操作将递归删除文件夹下所有文件，且不可逆！\n\n文件夹：${folder.name}`)) {
        return;
    }
    
    try {
        await apiRequest(`${state.apiBase}/api/v1/system/delete`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                path: folder.path,
                recursive: true
            })
        });
        
        showToast('文件夹删除成功', 'success');
        // Reload folder contents
        browsePath(state.currentPath);
    } catch (e) {
        showToast(`删除失败: ${e.message}`, 'error');
    }
}

// Expose module-scoped functions to global window object for legacy inline event handlers
window.browsePath = browsePath;
window.openMedia = openMedia;
window.openTaggingDialog = openTaggingDialog;
window.deleteMediaFile = deleteMediaFile;
window.deleteFolder = deleteFolder;
window.deleteTag = deleteTag;
window.openVideoPlayer = openVideoPlayer;
window.loadRoots = loadRoots;
window.toggleFileTagAssociation = toggleFileTagAssociation;
