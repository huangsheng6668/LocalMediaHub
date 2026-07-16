// Browser-view feature module: extracted from app.js
// (loadRoots / loadSystemDrives / browsePath / onBrowserListClick /
//  renderBrowserList / onBreadcrumbsClick / renderBreadcrumbs /
//  triggerBrowserSearch + 5 listeners).
import { state } from './state.js';
import { showToast } from './toast.js';
import { apiRequest, escapeHtml } from './api.js';
import { elements } from './dom.js';
import { formatSize, encodeRoutePath, safeBtoa } from './utils.js';
import { openMedia } from './lightbox.js';
import { openTaggingDialog } from './tagsView.js';
import { deleteMediaFile, deleteFolder } from './delete.js';

// Load Root directories in file browser
export function loadRoots() {
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
// bypassCache: append a cache-busting query param so the browser re-fetches
// the list after mutations (delete/add) instead of serving a stale cached
// response. Required because server-side JSON list endpoints are served with
// Cache-Control: max-age=N for bandwidth savings.
export async function browsePath(path, bypassCache = false) {
    state.currentPath = path;

    let url = `${state.apiBase}/api/v1/folders/${encodeRoutePath(path)}/browse`;
    if (state.isSystemBrowse) {
        url = `${state.apiBase}/api/v1/system/browse?path=${encodeURIComponent(path)}`;
    }
    if (bypassCache) {
        url += `${url.includes('?') ? '&' : '?'}_t=${Date.now()}`;
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

// Set up browser-view event listeners
export function setupBrowserListeners(elements) {
    // Search Box Listener
    elements.btnBrowserSearch.addEventListener('click', triggerBrowserSearch);
    elements.browserSearchInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') triggerBrowserSearch();
    });

    // Delegated click handling for browser list (folders, files, roots, drives)
    elements.browserList.addEventListener('click', onBrowserListClick);

    // Delegated click handling for breadcrumbs
    elements.browserBreadcrumbs.addEventListener('click', onBreadcrumbsClick);

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
}
