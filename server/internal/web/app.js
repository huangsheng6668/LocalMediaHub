import { state } from './state.js';
import { showToast } from './toast.js';
import { apiRequest, escapeHtml } from './api.js';
import { handleRoute } from './router.js';
import { formatSize, formatTime, encodeRoutePath, safeBtoa } from './utils.js';
import { loadConfig, renderSettings, setupSettingsListeners } from './settings.js';
import {
    loadTags,
    openTaggingDialog,
    renderTagsManager,
    setupTagsListeners
} from './tagsView.js';
import { elements } from './dom.js';

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
    // Settings module listeners (Scan Trigger + Save Settings)
    setupSettingsListeners(elements);

    // Tags module listeners (Create Tag + Tag Manager + Tag Selector + Close Modal)
    setupTagsListeners(elements);

    // Tag Color Picker selection
    elements.colorDots.forEach(dot => {
        dot.addEventListener('click', (e) => {
            elements.colorDots.forEach(d => d.classList.remove('active'));
            dot.classList.add('active');
        });
    });

    // Search Box Listener
    elements.btnBrowserSearch.addEventListener('click', triggerBrowserSearch);
    elements.browserSearchInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') triggerBrowserSearch();
    });

    // Delegated click handling for browser list (folders, files, roots, drives)
    elements.browserList.addEventListener('click', onBrowserListClick);

    // Delegated click handling for breadcrumbs
    elements.browserBreadcrumbs.addEventListener('click', onBreadcrumbsClick);

    // Delegated click handling for dashboard recent items
    elements.dashboardRecent.addEventListener('click', onDashboardRecentClick);

    // Delegated thumbnail error fallback (img 'error' does not bubble -> capture phase)
    elements.browserList.addEventListener('error', (e) => {
        const img = e.target;
        if (img instanceof HTMLImageElement && img.classList.contains('card-thumb')) {
            const wrapper = img.closest('.card-preview');
            if (wrapper) {
                img.style.display = 'none';
                const fallback = document.createElement('span');
                fallback.className = 'card-preview-icon';
                fallback.textContent = wrapper.dataset.fallbackIcon || '🖼️';
                wrapper.appendChild(fallback);
            }
        }
    }, true);

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
}



// Delegated click dispatcher for dashboard recent items
function onDashboardRecentClick(e) {
    const actionEl = e.target.closest('[data-action]');
    if (!actionEl) return;
    if (actionEl.dataset.action === 'open-video') {
        const idx = Number(actionEl.dataset.index);
        if (state.dashboardRecentFiles[idx]) openVideoPlayer(state.dashboardRecentFiles[idx]);
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
            state.dashboardRecentFiles = items;

            if (items.length === 0) {
                elements.dashboardRecent.classList.add('empty-state');
                elements.dashboardRecent.innerHTML = '<div class="empty-state">暂无最近媒体数据</div>';
            } else {
                elements.dashboardRecent.classList.remove('empty-state');
                elements.dashboardRecent.innerHTML = items.map((file, index) => {
                    return `
                        <div class="info-item" style="cursor:pointer;" data-action="open-video" data-index="${index}">
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
        const safePath = escapeHtml(path.replace(/\\/g, '/'));
        const safeName = escapeHtml(name);
        return `
            <div class="media-card" data-action="browse" data-path="${safePath}">
                <div class="card-preview">
                    <span class="card-preview-icon">📁</span>
                </div>
                <div class="card-details">
                    <div class="card-title" title="${safeName}">${safeName}</div>
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
    
    elements.browserBreadcrumbs.innerHTML = '<span class="crumb" data-action="load-roots">根目录</span><span class="crumb active">磁盘盘符</span>';
    
    try {
        const drives = await apiRequest(`${state.apiBase}/api/v1/system/drives`);
        if (drives && drives.length > 0) {
            elements.browserList.innerHTML = drives.map(drive => {
                const safePath = escapeHtml(drive.replace(/\\/g, '/'));
                return `
                    <div class="media-card" data-action="browse" data-path="${safePath}">
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

// Delegated click dispatcher for the browser grid
function onBrowserListClick(e) {
    const actionEl = e.target.closest('[data-action]');
    if (!actionEl) return;
    const action = actionEl.dataset.action;
    const idx = Number(actionEl.dataset.index);

    if (action === 'browse') {
        browsePath(actionEl.dataset.path || '');
    } else if (action === 'open') {
        if (state.currentFiles[idx]) openMedia(state.currentFiles[idx]);
    } else if (action === 'tag') {
        if (state.currentFiles[idx]) openTaggingDialog(state.currentFiles[idx]);
    } else if (action === 'delete-folder') {
        if (state.currentFolders[idx]) deleteFolder(state.currentFolders[idx]);
    } else if (action === 'delete-file') {
        if (state.currentFiles[idx]) deleteMediaFile(state.currentFiles[idx]);
    }
}

// Render folder & file cards in browser grid
export function renderBrowserList() {
    if (state.currentFolders.length === 0 && state.currentFiles.length === 0) {
        elements.browserList.innerHTML = '<div style="grid-column: 1/-1; text-align:center; padding:48px; color: var(--text-muted);">📁 当前目录为空（无媒体文件）</div>';
        return;
    }

    let html = '';

    // 1. Folders
    state.currentFolders.forEach((folder, index) => {
        const safePath = escapeHtml(folder.path.replace(/\\/g, '/'));
        const safeName = escapeHtml(folder.name);
        html += `
            <div class="media-card" data-action="browse" data-path="${safePath}">
                <div class="card-preview">
                    <span class="card-preview-icon">📁</span>
                </div>
                <div class="card-actions-overlay">
                    ${state.enableDelete && !folder.is_root ? `<button class="card-action-btn delete-btn" title="删除文件夹" data-action="delete-folder" data-index="${index}">🗑️</button>` : ''}
                </div>
                <div class="card-details">
                    <div class="card-title" title="${safeName}">${safeName}</div>
                    <div class="card-meta">
                        <span>文件夹</span>
                    </div>
                </div>
            </div>
        `;
    });

    // 2. Media Files
    state.currentFiles.forEach((file, index) => {
        const isVideo = file.media_type === 'video';
        const fallbackIcon = isVideo ? '🎬' : '🖼️';
        let previewHtml = '';
        let playOverlay = '';

        let thumbUrl = `${state.apiBase}/api/v1/images/${encodeRoutePath(file.relative_path)}/thumbnail`;
        if (state.isSystemBrowse) {
            thumbUrl = `${state.apiBase}/api/v1/system/thumbnail?path=${encodeURIComponent(file.path)}`;
        }

        if (isVideo) {
            const videoThumbUrl = `${state.apiBase}/api/v1/videos/${encodeRoutePath(file.relative_path)}/thumbnail`;
            const videoUrl = state.isSystemBrowse ? thumbUrl : videoThumbUrl;
            previewHtml = `<img src="${escapeHtml(videoUrl)}" class="card-thumb" alt="${escapeHtml(file.name)}">`;
            playOverlay = `
                <div class="play-overlay">
                    <div class="play-button-circle">▶</div>
                </div>
            `;
        } else {
            previewHtml = `<img src="${escapeHtml(thumbUrl)}" class="card-thumb" alt="${escapeHtml(file.name)}">`;
        }

        const fileTags = state.fileTagsMap[file.path] || [];
        const isTagged = fileTags.length > 0;
        const tagDotHtml = fileTags.map(tag => `
            <span style="display:inline-block; width:8px; height:8px; border-radius:50%; background-color:${escapeHtml(tag.color)};" title="${escapeHtml(tag.name)}"></span>
        `).join('');

        const cardClass = `media-card ${isTagged ? 'tagged' : ''}`;
        const safeName = escapeHtml(file.name);
        const safeExt = escapeHtml(file.extension);

        html += `
            <div class="${escapeHtml(cardClass)}" id="file-card-${safeBtoa(file.path).replace(/=/g, '')}" data-action="open" data-index="${index}">
                <div class="card-preview" data-fallback-icon="${fallbackIcon}">
                    ${previewHtml}
                    ${playOverlay}
                </div>
                <div class="card-actions-overlay">
                    <button class="card-action-btn" title="分类标签" data-action="tag" data-index="${index}">🏷️</button>
                    ${state.enableDelete ? `<button class="card-action-btn delete-btn" title="删除文件" data-action="delete-file" data-index="${index}">🗑️</button>` : ''}
                </div>
                <div class="card-details">
                    <div class="card-title" title="${safeName}">${safeName}</div>
                    <div class="card-meta">
                        <span class="card-badge">${safeExt.toUpperCase()}</span>
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

// Delegated click dispatcher for breadcrumbs
function onBreadcrumbsClick(e) {
    const actionEl = e.target.closest('[data-action]');
    if (!actionEl) return;
    const action = actionEl.dataset.action;
    if (action === 'load-roots') {
        loadRoots();
    } else if (action === 'crumb') {
        browsePath(actionEl.dataset.path || '');
    }
}

// Render Breadcrumbs
function renderBreadcrumbs(path) {
    const isWin = path.includes(':');
    const segments = path.split(/[/\\]+/).filter(Boolean);

    let html = `<span class="crumb" data-action="load-roots">根目录</span>`;

    let currentAccumulated = '';
    segments.forEach((seg, index) => {
        if (index === 0 && isWin) {
            currentAccumulated = seg + '/';
        } else {
            currentAccumulated += (index === 0 ? '' : '/') + seg;
        }

        const isLast = index === segments.length - 1;
        if (isLast) {
            html += `<span class="crumb active">${escapeHtml(seg)}</span>`;
        } else {
            html += `<span class="crumb" data-action="crumb" data-path="${escapeHtml(currentAccumulated)}">${escapeHtml(seg)}</span>`;
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
            <span class="crumb" data-action="crumb" data-path="${escapeHtml(state.currentPath)}">返回上级目录</span>
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
                    <img src="${url}" alt="${escapeHtml(file.name)}" loading="lazy">
                    <div class="stitch-image-caption">${escapeHtml(file.name)} (${idx + 1}/${state.lightboxFiles.length})</div>
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