// State Management
const state = {
    activeTab: 'dashboard',
    folders: [], // Config roots
    allowedRoots: [], // System allowed roots
    videoExts: [],
    imageExts: [],
    
    // Browser variables
    currentPath: '',
    pathHistory: [],
    currentFiles: [], // Current browser view media files
    currentFolders: [], // Current browser view directories
    isSystemBrowse: false,
    
    // Tags variables
    tags: [],
    fileTagsMap: {},
    
    // Lightbox variables
    lightboxFiles: [],
    lightboxIndex: -1,
    
    // Selected file for tag mapping
    taggingFile: null,
    
    // Transcode flag for video player
    useTranscode: false,
    playingFile: null
};

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
    btnCloseVideoModal: document.getElementById('btn-close-video-modal'),
    
    modalImagePreview: document.getElementById('modal-image-preview'),
    lightboxImg: document.getElementById('lightbox-img'),
    lightboxCaption: document.getElementById('lightbox-caption'),
    btnImagePrev: document.getElementById('btn-image-prev'),
    btnImageNext: document.getElementById('btn-image-next'),
    btnCloseImageModal: document.getElementById('btn-close-image-modal'),
    
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
    handleRoute();
}

// Router
window.addEventListener('hashchange', handleRoute);

function handleRoute() {
    const hash = window.location.hash || '#/dashboard';
    
    // De-activate all tabs and menu selections
    [elements.viewDashboard, elements.viewBrowser, elements.viewTags, elements.viewSettings].forEach(v => v.classList.remove('active'));
    [elements.menuDashboard, elements.menuBrowser, elements.menuTags, elements.menuSettings].forEach(m => m.classList.remove('active'));
    
    if (hash.startsWith('#/dashboard')) {
        state.activeTab = 'dashboard';
        elements.pageTitle.textContent = '仪表盘';
        elements.menuDashboard.classList.add('active');
        elements.viewDashboard.classList.add('active');
        renderDashboard();
    } else if (hash.startsWith('#/browser')) {
        state.activeTab = 'browser';
        elements.pageTitle.textContent = '媒体共享库';
        elements.menuBrowser.classList.add('active');
        elements.viewBrowser.classList.add('active');
        
        // Check if we need to load a specific subfolder based on state, otherwise start at root
        if (!state.currentPath) {
            loadRoots();
        } else {
            browsePath(state.currentPath);
        }
    } else if (hash.startsWith('#/tags')) {
        state.activeTab = 'tags';
        elements.pageTitle.textContent = '标签管理';
        elements.menuTags.classList.add('active');
        elements.viewTags.classList.add('active');
        renderTagsManager();
    } else if (hash.startsWith('#/settings')) {
        state.activeTab = 'settings';
        elements.pageTitle.textContent = '系统设置';
        elements.menuSettings.classList.add('active');
        elements.viewSettings.classList.add('active');
        renderSettings();
    }
}

// Set up Event Listeners
function setupEventListeners() {
    // Scan Trigger
    elements.btnTriggerScan.addEventListener('click', async () => {
        try {
            const res = await fetch(`${state.apiBase}/api/v1/admin/scan/trigger`, { method: 'POST' });
            const data = await res.json();
            if (res.ok) {
                showToast('🚀 已成功在后台触发全量媒体重扫描！', 'success');
            } else {
                showToast(`扫描启动失败: ${data.error}`, 'error');
            }
        } catch (e) {
            showToast('扫描接口请求超时，请检查服务状态', 'error');
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
            const res = await fetch(`${state.apiBase}/api/v1/tags`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name, color })
            });
            const data = await res.json();
            if (res.ok) {
                showToast(`成功创建标签 [${name}]`, 'success');
                elements.tagNameInput.value = '';
                await loadTags();
                renderTagsManager();
            } else {
                showToast(`标签创建失败: ${data.error}`, 'error');
            }
        } catch (e) {
            showToast('连接服务端接口错误', 'error');
        }
    });

    // Save Settings
    elements.btnSaveSettings.addEventListener('click', async () => {
        const rootsText = elements.settingsRoots.value.trim();
        const roots = rootsText ? rootsText.split('\n').map(r => r.trim()).filter(r => r !== '') : [];

        try {
            const res = await fetch(`${state.apiBase}/api/v1/admin/config`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ roots })
            });
            const data = await res.json();
            if (res.ok) {
                showToast('💾 系统路径配置更新保存成功！', 'success');
                await loadConfig();
                renderSettings();
            } else {
                showToast(`配置保存失败: ${data.error}`, 'error');
            }
        } catch (e) {
            showToast('连接配置更新接口错误', 'error');
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
    });

    // Transcode Video toggle inside video modal
    elements.btnVideoTranscode.addEventListener('click', () => {
        if (!state.playingFile) return;
        state.useTranscode = !state.useTranscode;
        
        const currentPos = elements.videoPlayer.currentTime;
        let url = `${state.apiBase}/api/v1/media/stream?path=${encodeURIComponent(state.playingFile.path)}`;
        
        if (state.useTranscode) {
            url += `&transcode=true&start=${Math.floor(currentPos)}`;
            elements.btnVideoTranscode.classList.add('active');
            elements.btnVideoTranscode.textContent = '转码中';
            showToast('🔧 已切换至 H.264 兼容性实时转码输出', 'success');
        } else {
            // Native stream
            elements.btnVideoTranscode.classList.remove('active');
            elements.btnVideoTranscode.textContent = '原画';
            showToast('🚀 已切换至极速原文件直通流', 'success');
        }
        
        elements.videoPlayer.src = url;
        elements.videoPlayer.load();
        elements.videoPlayer.play();
    });

    // Close Image Modal (Lightbox)
    elements.btnCloseImageModal.addEventListener('click', () => {
        elements.modalImagePreview.classList.remove('active');
        elements.lightboxImg.src = '';
    });

    // Lightbox navigation
    elements.btnImagePrev.addEventListener('click', () => navigateLightbox(-1));
    elements.btnImageNext.addEventListener('click', () => navigateLightbox(1));
    document.addEventListener('keydown', (e) => {
        if (!elements.modalImagePreview.classList.contains('active')) return;
        if (e.key === 'ArrowLeft') navigateLightbox(-1);
        if (e.key === 'ArrowRight') navigateLightbox(1);
        if (e.key === 'Escape') {
            elements.modalImagePreview.classList.remove('active');
            elements.lightboxImg.src = '';
        }
    });

    // Close Tag modal dialog
    elements.btnCloseFileTagsModal.addEventListener('click', () => {
        elements.modalFileTags.classList.remove('active');
        state.taggingFile = null;
    });
}

// Toast Notification
function showToast(message, type = 'info') {
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.textContent = message;
    elements.toastContainer.appendChild(toast);
    
    setTimeout(() => {
        toast.style.opacity = '0';
        toast.style.transform = 'translateY(16px)';
        setTimeout(() => toast.remove(), 300);
    }, 3000);
}

// Fetch configs
async function loadConfig() {
    try {
        const res = await fetch(`${state.apiBase}/api/v1/admin/config`);
        const data = await res.json();
        if (res.ok) {
            state.folders = data.scan.roots || [];
            state.videoExts = data.scan.video_extensions || [];
            state.imageExts = data.scan.image_extensions || [];
            state.allowedRoots = (data.system && data.system.allowed_roots) || [];
            state.enableDelete = (data.system && data.system.enable_delete) || false;
            state.thumbMax = (data.thumbnail && data.thumbnail.max_size) || 300;
            
            elements.infoScanRoots.textContent = state.folders.join(', ') || '全盘自动检测';
        }
    } catch (e) {
        console.error('loadConfig error:', e);
        showToast('无法从后端获取系统配置: ' + e.message, 'error');
    }
}

// Fetch Tags
async function loadTags() {
    try {
        const res = await fetch(`${state.apiBase}/api/v1/tags`);
        const tags = await res.json();
        if (res.ok) {
            state.tags = tags || [];
            
            // Fetch file tags mapping in parallel
            const mappingRes = await fetch(`${state.apiBase}/api/v1/tags/file-tags`);
            if (mappingRes.ok) {
                state.fileTagsMap = await mappingRes.json() || {};
            }
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
        const resVideos = await fetch(`${state.apiBase}/api/v1/videos?page=1&page_size=1`);
        const videosData = await resVideos.json();
        const totalVideos = resVideos.ok ? videosData.total : 0;
        
        const resImages = await fetch(`${state.apiBase}/api/v1/images?page=1&page_size=1`);
        const imagesData = await resImages.json();
        const totalImages = resImages.ok ? imagesData.total : 0;
        
        elements.statRoots.textContent = state.folders.length || '全盘';
        elements.statVideos.textContent = totalVideos;
        elements.statImages.textContent = totalImages;
        elements.statTags.textContent = state.tags.length;
        
        // 2. Mock a list of files or load first page of videos/images for recent preview
        const recentRes = await fetch(`${state.apiBase}/api/v1/videos?page=1&page_size=3`);
        if (recentRes.ok) {
            const data = await recentRes.json();
            const items = data.items || [];
            
            if (items.length === 0) {
                elements.dashboardRecent.innerHTML = '<div class="empty-state">暂无最近媒体数据</div>';
                elements.dashboardRecent.classList.add('empty-state');
            } else {
                elements.dashboardRecent.classList.remove('empty-state');
                elements.dashboardRecent.innerHTML = items.map(file => {
                    return `
                        <div class="info-item" style="cursor:pointer;" onclick="openVideoPlayer(${JSON.stringify(file).replace(/"/g, '&quot;')})">
                            <span class="info-label">🎬 ${file.name}</span>
                            <span class="info-value" style="font-size:11px;">${formatSize(file.size)}</span>
                        </div>
                    `;
                }).join('');
            }
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
                    <div class="card-title" title="${name}">${name}</div>
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
        const res = await fetch(`${state.apiBase}/api/v1/system/drives`);
        const drives = await res.json();
        if (res.ok && drives.length > 0) {
            elements.browserList.innerHTML = drives.map(drive => {
                return `
                    <div class="media-card" onclick="browsePath('${drive.replace(/\\/g, '/')}')">
                        <div class="card-preview">
                            <span class="card-preview-icon">💾</span>
                        </div>
                        <div class="card-details">
                            <div class="card-title">${drive}</div>
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
        showToast('获取系统磁盘盘符失败', 'error');
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
        const res = await fetch(url);
        const data = await res.json();
        
        if (!res.ok) {
            showToast(`浏览失败: ${data.error || '权限限制'}`, 'error');
            loadRoots();
            return;
        }
        
        state.currentFolders = data.folders || [];
        state.currentFiles = data.files || [];
        
        renderBrowserList();
        renderBreadcrumbs(path);
    } catch (e) {
        showToast('读取共享目录结构异常', 'error');
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
            <div class="media-card" onclick="browsePath('${folder.path.replace(/\\/g, '/')}')">
                <div class="card-preview">
                    <span class="card-preview-icon">📁</span>
                </div>
                <div class="card-details">
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
        const res = await fetch(url);
        const data = await res.json();
        
        if (res.ok) {
            state.currentFolders = data.folders || [];
            state.currentFiles = data.files || [];
            renderBrowserList();
            
            elements.browserBreadcrumbs.innerHTML = `
                <span class="crumb" onclick="browsePath('${state.currentPath}')">返回上级目录</span>
                <span class="crumb active">关于 "${query}" 的结果</span>
            `;
        } else {
            showToast(`搜索失败: ${data.error}`, 'error');
        }
    } catch (e) {
        showToast('搜索查询接口故障', 'error');
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
function openVideoPlayer(file) {
    state.playingFile = file;
    state.useTranscode = false; // Start with native stream
    
    elements.videoModalTitle.textContent = file.name;
    elements.btnVideoTranscode.classList.remove('active');
    elements.btnVideoTranscode.textContent = '原画';
    
    // Set video src URL
    let url = `${state.apiBase}/api/v1/videos/${encodeRoutePath(file.relative_path)}/stream`;
    if (state.isSystemBrowse) {
        url = `${state.apiBase}/api/v1/system/stream?path=${encodeURIComponent(file.path)}`;
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
    state.lightboxIndex = state.lightboxFiles.findIndex(f => f.path === file.path);
    
    renderLightboxImage();
    elements.modalImagePreview.classList.add('active');
}

// Show image in lightbox
function renderLightboxImage() {
    if (state.lightboxIndex < 0 || state.lightboxIndex >= state.lightboxFiles.length) return;
    const file = state.lightboxFiles[state.lightboxIndex];
    
    let url = `${state.apiBase}/api/v1/images/${encodeRoutePath(file.relative_path)}/original`;
    if (state.isSystemBrowse) {
        url = `${state.apiBase}/api/v1/system/original?path=${encodeURIComponent(file.path)}`;
    }
    
    elements.lightboxImg.src = url;
    elements.lightboxCaption.textContent = `${file.name} (${state.lightboxIndex + 1}/${state.lightboxFiles.length})`;
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
                    <span style="width:12px; height:12px; border-radius:50%; background-color:${tag.color};"></span>
                    <span>${tag.name}</span>
                </span>
                <input type="checkbox" data-tag-id="${tag.id}" ${checked} onchange="toggleFileTagAssociation(this, '${tag.id}', '${file.path.replace(/\\/g, '\\\\')}')">
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
        const res = await fetch(url, {
            method: isAssociate ? 'POST' : 'DELETE'
        });
        
        if (res.ok) {
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
            }
        } else {
            checkbox.checked = !isAssociate; // Revert checkbox state on API error
            const data = await res.json();
            showToast(`操作失败: ${data.error}`, 'error');
        }
    } catch (e) {
        checkbox.checked = !isAssociate; // Revert
        showToast('连接标签管理服务异常', 'error');
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
            <div class="tag-chip" style="background-color: ${tag.color}33; border-color: ${tag.color};">
                <span style="display:inline-block; width:10px; height:10px; border-radius:50%; background-color:${tag.color};"></span>
                <span>${tag.name}</span>
                <button class="btn-tag-delete" title="删除分类标签" onclick="deleteTag('${tag.id}', '${tag.name}')">✕</button>
            </div>
        `;
    }).join('');
}

// Delete tag definition
async function deleteTag(tagId, name) {
    if (!confirm(`确定要彻底删除标签 [${name}] 吗？\n所有关联文件的分类记录也会一并清除。`)) return;
    
    try {
        const res = await fetch(`${state.apiBase}/api/v1/tags/${tagId}`, {
            method: 'DELETE'
        });
        
        if (res.ok) {
            showToast(`已成功删除标签 [${name}]`, 'success');
            await loadTags();
            renderTagsManager();
        } else {
            const data = await res.json();
            showToast(`删除失败: ${data.error}`, 'error');
        }
    } catch (e) {
        showToast('连接服务端接口错误', 'error');
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
