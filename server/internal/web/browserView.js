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
import { deleteMediaFile, deleteFolder } from './delete.js';

// Load Root directories in file browser
export async function loadRoots() {
    state.currentPath = '';
    state.pathHistory = [];
    state.isSystemBrowse = false;

    // Breadcrumbs
    elements.browserBreadcrumbs.innerHTML = '<span class="crumb active">根目录</span>';

    try {
        const folders = await apiRequest(`${state.apiBase}/api/v1/folders`);
        if (Array.isArray(folders)) {
            state.folders = folders;
        }
    } catch (e) {
        console.error('loadRoots error:', e);
    }

    // Grid list
    if (!state.folders || state.folders.length === 0) {
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

    elements.browserList.innerHTML = state.folders.map(folder => {
        const pathStr = typeof folder === 'string' ? folder : (folder.path || '');
        const nameStr = typeof folder === 'object' && folder.name ? folder.name : (pathStr.replace(/\\/g, '/').split('/').filter(Boolean).pop() || pathStr);
        const safePath = escapeHtml(pathStr.replace(/\\/g, '/'));
        const safeName = escapeHtml(nameStr);
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
    } else if (action === 'text-open') {
        // Text/ebook open (Task 15): route to the reader view. Tag/delete
        // buttons inside the card have their own data-action and stop
        // propagation implicitly via .closest() returning the inner button.
        const f = state.currentFiles[idx];
        if (f) {
            window.location.hash = `#/read?path=${encodeURIComponent(f.path)}`;
        }
    } else if (action === 'text-unsupported') {
        showToast('暂不支持该格式（仅支持 .txt / .epub）', 'info');

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
        const isText = file.media_type === 'text';

        // Text / ebook files (Task 15): render as a "document card" with a doc
        // icon instead of a thumbnail. .txt and .epub open the reader at
        // #/read?path=...; other text extensions (mobi/azw3) are surfaced by
        // the scanner under media_type=text but cannot be opened by the reader
        // — show a "暂不支持" badge and a toast on click so the user knows why
        // nothing happens.
        if (isText) {
            const ext = (file.extension || '').toLowerCase();
            const isUnsupportedText = !['.txt', '.epub'].includes(ext);
            const docIcon = ext === '.epub' ? '📘' : '📄';
            const safeName = escapeHtml(file.name);
            const safeExt = escapeHtml(file.extension);
            const unsupportedBadge = isUnsupportedText
                ? '<span class="card-badge" style="background-color: rgba(239,68,68,0.2); color: #fca5a5;">暂不支持</span>'
                : '';
            html += `
                <div class="media-card text-card ${isUnsupportedText ? 'text-card--unsupported' : ''}"
                     data-action="${isUnsupportedText ? 'text-unsupported' : 'text-open'}"
                     data-index="${index}">
                    <div class="card-preview">
                        <span class="card-preview-icon">${docIcon}</span>
                    </div>
                    <div class="card-actions-overlay">

                        ${state.enableDelete ? `<button class="card-action-btn delete-btn" title="删除文件" data-action="delete-file" data-index="${index}">🗑️</button>` : ''}
                    </div>
                    <div class="card-details">
                        <div class="card-title" title="${safeName}">${safeName}</div>
                        <div class="card-meta">
                            <span class="card-badge">${safeExt.toUpperCase()}</span>
                            ${unsupportedBadge}
                            <span>${formatSize(file.size)}</span>
                        </div>
                    </div>
                </div>
            `;
            return;
        }

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

        const cardClass = 'media-card';
        const safeName = escapeHtml(file.name);
        const safeExt = escapeHtml(file.extension);

        html += `
            <div class="${escapeHtml(cardClass)}" id="file-card-${safeBtoa(file.path).replace(/=/g, '')}" data-action="open" data-index="${index}">
                <div class="card-preview" data-fallback-icon="${fallbackIcon}">
                    ${previewHtml}
                    ${playOverlay}
                </div>
                <div class="card-actions-overlay">

                    ${state.enableDelete ? `<button class="card-action-btn delete-btn" title="删除文件" data-action="delete-file" data-index="${index}">🗑️</button>` : ''}
                </div>
                <div class="card-details">
                    <div class="card-title" title="${safeName}">${safeName}</div>
                    <div class="card-meta">
                        <span class="card-badge">${safeExt.toUpperCase()}</span>
                        <span>${formatSize(file.size)}</span>
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
        const url = `${state.apiBase}/api/v1/search?q=${encodeURIComponent(query)}&path=${encodeURIComponent(state.currentPath)}`;
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
