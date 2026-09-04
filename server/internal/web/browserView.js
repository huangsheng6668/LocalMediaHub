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
import { refreshDecorations, decorateBrowserList, toggleFavorite, markStatus, openStatusMenu } from './library.js';

// ── Inline SVG icon vocabulary (mirrors index.html / Task 6 icons) ──
// Monochrome currentColor stroke icons replace the former emoji glyphs in
// card templates. Sizes match the old emoji footprint: 32px in the card
// preview area, 16px inside chips/action buttons, 14px for the play glyph.
const svgIcon = (inner, size) =>
    `<svg viewBox="0 0 24 24" width="${size}" height="${size}" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${inner}</svg>`;

const ICONS = {
    folder: () => svgIcon('<path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>', 32),
    drive: () => svgIcon('<line x1="22" y1="12" x2="2" y2="12"/><path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/><line x1="6" y1="16" x2="6.01" y2="16"/><line x1="10" y1="16" x2="10.01" y2="16"/>', 32),
    doc: () => svgIcon('<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6M9 13h6M9 17h6"/>', 32),
    video: () => svgIcon('<rect x="2" y="5" width="14" height="14" rx="2"/><path d="m16 10 6-3v10l-6-3"/>', 32),
    image: () => svgIcon('<rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="9" cy="9" r="2"/><path d="m21 15-4.35-4.35a1 1 0 0 0-1.42 0L5 21"/>', 32),
    trash: () => svgIcon('<path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2m3 0v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/>', 16),
    heart: () => svgIcon('<path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/>', 16),
    dots: () => svgIcon('<circle cx="12" cy="5" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="12" cy="19" r="1"/>', 16),
    // Filled play triangle (same path as the video-controls play icon).
    play: () => `<svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor" stroke="none" aria-hidden="true"><path d="M8 5v14l11-7z"/></svg>`
};

// Reflect the current browse location in the URL hash via history.replaceState
// (fires no hashchange, so no re-render loop) — a refresh or a shared link
// then restores the same directory instead of resetting to the root.
function syncBrowserHash() {
    try {
        const base = '#/browser';
        const q = state.currentPath
            ? `${base}?path=${encodeURIComponent(state.currentPath)}${state.isSystemBrowse ? '&sys=1' : ''}`
            : base;
        history.replaceState(null, '', q);
    } catch (e) {
        // Restricted contexts (file://) — browsing works, just no URL sync.
    }
}

// Load Root directories in file browser
export async function loadRoots() {
    state.currentPath = '';
    state.pathHistory = [];
    state.isSystemBrowse = false;
    syncBrowserHash();

    // Breadcrumbs
    elements.browserBreadcrumbs.innerHTML = '<span class="crumb active">根目录</span>'; // XSS-SAFE: hardcoded literal

    try {
        const folders = await apiRequest(`${state.apiBase}/api/v1/folders`);
        if (Array.isArray(folders)) {
            state.folders = folders;
        }
    } catch (e) {
        console.error('loadRoots error:', e);
        state.folders = [];
    }

    // Grid list
    if (!state.folders || state.folders.length === 0) {
        // XSS-SAFE: pure-literal template, no interpolation
        elements.browserList.innerHTML = `
            <div class="browser-empty-grid">
                <h3>未配置扫描共享目录</h3>
                <p>将在下方列出自动发现的 Windows 磁盘分区</p>
                <button class="btn btn-primary btn-browse-drives" id="btn-browse-drives">浏览磁盘驱动器</button>
            </div>
        `;
        document.getElementById('btn-browse-drives')?.addEventListener('click', loadSystemDrives);
        return;
    }

    // XSS-SAFE: map returns template whose dynamic fields (path, name) are wrapped in escapeHtml()
    elements.browserList.innerHTML = state.folders.map(folder => {
        const pathStr = typeof folder === 'string' ? folder : (folder.path || '');
        const nameStr = typeof folder === 'object' && folder.name ? folder.name : (pathStr.replace(/\\/g, '/').split('/').filter(Boolean).pop() || pathStr);
        const safePath = escapeHtml(pathStr.replace(/\\/g, '/'));
        const safeName = escapeHtml(nameStr);
        return `
            <div class="media-card" data-action="browse" data-path="${safePath}">
                <div class="card-preview">
                    <span class="card-preview-icon">${ICONS.folder()}</span>
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
export async function loadSystemDrives() {
    state.isSystemBrowse = true;
    state.currentPath = '/system';
    syncBrowserHash();

    elements.browserBreadcrumbs.innerHTML = '<span class="crumb" data-action="load-roots">根目录</span><span class="crumb active">磁盘盘符</span>'; // XSS-SAFE: hardcoded literal

    try {
        const drives = await apiRequest(`${state.apiBase}/api/v1/system/drives`);
        if (drives && drives.length > 0) {
            // XSS-SAFE: map returns template whose dynamic field (drive) is wrapped in escapeHtml()
            elements.browserList.innerHTML = drives.map(drive => {
                const safePath = escapeHtml(drive.replace(/\\/g, '/'));
                return `
                    <div class="media-card" data-action="browse" data-path="${safePath}">
                        <div class="card-preview">
                            <span class="card-preview-icon">${ICONS.drive()}</span>
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
            elements.browserList.innerHTML = '<div class="browser-status-note">无法获取本地磁盘，可能未在 config.yaml 启用 system.allowed_roots</div>'; // XSS-SAFE: hardcoded literal
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
    syncBrowserHash();

    let url = `${state.apiBase}/api/v1/folders/${encodeRoutePath(path)}/browse`;
    if (state.isSystemBrowse) {
        url = `${state.apiBase}/api/v1/system/browse?path=${encodeURIComponent(path)}`;
    }
    if (bypassCache) {
        url += `${url.includes('?') ? '&' : '?'}_t=${Date.now()}`;
    }

    elements.browserList.innerHTML = '<div class="browser-status-note">正在读取目录结构...</div>'; // XSS-SAFE: hardcoded literal

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
    } else if (action === 'fav-toggle') {
        toggleFavorite(actionEl.dataset.path, actionEl.dataset.isDir === '1',
            actionEl.dataset.title || '', actionEl.dataset.mediaType || '');
    } else if (action === 'status-menu') {
        openStatusMenu(actionEl, actionEl.dataset.path);
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

function sortMediaItems(items, field, order, isFolder = false) {
    if (!Array.isArray(items)) return [];
    const mult = order === 'desc' ? -1 : 1;
    return [...items].sort((a, b) => {
        let cmp = 0;
        if (field === 'modified_time') {
            const timeA = a.modified_time ? new Date(a.modified_time).getTime() : 0;
            const timeB = b.modified_time ? new Date(b.modified_time).getTime() : 0;
            cmp = timeA - timeB;
        } else if (field === 'size') {
            const sizeA = isFolder ? 0 : (a.size || 0);
            const sizeB = isFolder ? 0 : (b.size || 0);
            cmp = sizeA - sizeB;
        } else if (field === 'extension') {
            const extA = isFolder ? '' : (a.extension || '').toLowerCase();
            const extB = isFolder ? '' : (b.extension || '').toLowerCase();
            cmp = extA.localeCompare(extB);
        }

        if (cmp === 0 || field === 'name') {
            const nameA = a.name || '';
            const nameB = b.name || '';
            const nameCmp = nameA.localeCompare(nameB, undefined, { numeric: true, sensitivity: 'base' });
            return mult * (field === 'name' ? nameCmp : (cmp !== 0 ? cmp : nameCmp));
        }

        return mult * cmp;
    });
}

// Render folder & file cards in browser grid
export function renderBrowserList() {
    state.currentFolders = sortMediaItems(state.currentFolders, state.sortField, state.sortOrder, true);
    state.currentFiles = sortMediaItems(state.currentFiles, state.sortField, state.sortOrder, false);

    if (state.currentFolders.length === 0 && state.currentFiles.length === 0) {
        elements.browserList.innerHTML = '<div class="browser-empty-grid">当前目录为空（无媒体文件）</div>'; // XSS-SAFE: hardcoded literal
        return;
    }

    let html = '';

    // 1. Folders
    state.currentFolders.forEach((folder, index) => {
        const safePath = escapeHtml(folder.path.replace(/\\/g, '/'));
        const safeName = escapeHtml(folder.name);
        html += `
            <div class="media-card" data-action="browse" data-path="${safePath}" data-media-type="folder">
                <div class="card-preview">
                    <span class="card-preview-icon">${ICONS.folder()}</span>
                </div>
                <div class="card-actions-overlay">
                    ${state.enableDelete && !folder.is_root ? `<button class="card-action-btn delete-btn" title="删除文件夹" data-action="delete-folder" data-index="${index}">${ICONS.trash()}</button>` : ''}
                    <button class="card-action-btn fav-btn" title="收藏" data-action="fav-toggle" data-path="${safePath}" data-is-dir="1" data-title="${safeName}" data-media-type="folder">${ICONS.heart()}</button>
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
            // Both .txt and .epub use the doc icon; the extension badge
            // (TXT/EPUB) in card-meta distinguishes the two formats.
            const docIcon = ICONS.doc();
            const safeName = escapeHtml(file.name);
            const safeExt = escapeHtml(file.extension);
            const unsupportedBadge = isUnsupportedText
                ? '<span class="card-badge card-badge--unsupported">暂不支持</span>'
                : '';
            html += `
                <div class="media-card text-card ${isUnsupportedText ? 'text-card--unsupported' : ''}"
                     id="file-card-${safeBtoa(file.path).replace(/=/g, '')}"
                     data-action="${isUnsupportedText ? 'text-unsupported' : 'text-open'}"
                     data-path="${escapeHtml(file.path)}"
                     data-media-type="text"
                     data-index="${index}">
                    <div class="card-preview">
                        <span class="card-preview-icon">${docIcon}</span>
                    </div>
                    <div class="card-actions-overlay">
                        <button class="card-action-btn fav-btn" title="收藏" data-action="fav-toggle" data-path="${escapeHtml(file.path)}" data-is-dir="0" data-title="${safeName}" data-media-type="text">${ICONS.heart()}</button>
                        <button class="card-action-btn dots-btn" title="阅读状态" data-action="status-menu" data-path="${escapeHtml(file.path)}">${ICONS.dots()}</button>
                        ${state.enableDelete ? `<button class="card-action-btn delete-btn" title="删除文件" data-action="delete-file" data-index="${index}">${ICONS.trash()}</button>` : ''}
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

        // Keyword consumed by the thumbnail error handler below (maps to the
        // SVG vocabulary — never interpolated raw into the DOM).
        const fallbackIcon = isVideo ? 'video' : 'image';
        let previewHtml = '';
        let playOverlay = '';

        let thumbUrl = `${state.apiBase}/api/v1/images/${encodeRoutePath(file.path)}/thumbnail`;
        if (state.isSystemBrowse) {
            thumbUrl = `${state.apiBase}/api/v1/system/thumbnail?path=${encodeURIComponent(file.path)}`;
        }

        if (isVideo) {
            const videoThumbUrl = `${state.apiBase}/api/v1/videos/${encodeRoutePath(file.path)}/thumbnail`;
            const videoUrl = state.isSystemBrowse ? thumbUrl : videoThumbUrl;
            // loading=lazy + decoding=async: off-screen cards defer their
            // thumbnail fetch/decode instead of storming the server with one
            // request per card the moment the grid is injected.
            previewHtml = `<img src="${escapeHtml(videoUrl)}" class="card-thumb" alt="${escapeHtml(file.name)}" loading="lazy" decoding="async">`;
            playOverlay = `
                <div class="play-overlay">
                    <div class="play-button-circle">${ICONS.play()}</div>
                </div>
            `;
        } else {
            previewHtml = `<img src="${escapeHtml(thumbUrl)}" class="card-thumb" alt="${escapeHtml(file.name)}" loading="lazy" decoding="async">`;
        }

        const cardClass = 'media-card';
        const safeName = escapeHtml(file.name);
        const safeExt = escapeHtml(file.extension);

        html += `
            <div class="${escapeHtml(cardClass)}" id="file-card-${safeBtoa(file.path).replace(/=/g, '')}" data-action="open" data-path="${escapeHtml(file.path)}" data-media-type="${isVideo ? 'video' : 'image'}" data-index="${index}">
                <div class="card-preview" data-fallback-icon="${fallbackIcon}">
                    ${previewHtml}
                    ${playOverlay}
                </div>
                <div class="card-actions-overlay">
                    <button class="card-action-btn fav-btn" title="收藏" data-action="fav-toggle" data-path="${escapeHtml(file.path)}" data-is-dir="0" data-title="${safeName}" data-media-type="${isVideo ? 'video' : 'image'}">${ICONS.heart()}</button>
                    ${state.enableDelete ? `<button class="card-action-btn delete-btn" title="删除文件" data-action="delete-file" data-index="${index}">${ICONS.trash()}</button>` : ''}
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

    elements.browserList.innerHTML = html; // XSS-SAFE: html built entirely from escapeHtml-wrapped folder/file fields above
    decorateBrowserList(elements.browserList);
    refreshDecorations(() => renderBrowserList());
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

    elements.browserBreadcrumbs.innerHTML = html; // XSS-SAFE: html built with escapeHtml-wrapped segment/path values above
}

// Filter current files recursively in Browser
async function triggerBrowserSearch() {
    const query = elements.browserSearchInput.value.trim();
    if (!query) {
        if (state.currentPath) browsePath(state.currentPath);
        else loadRoots();
        return;
    }

    elements.browserList.innerHTML = '<div class="browser-status-note">全局模糊匹配检索中...</div>'; // XSS-SAFE: hardcoded literal

    try {
        const url = `${state.apiBase}/api/v1/search?q=${encodeURIComponent(query)}&path=${encodeURIComponent(state.currentPath)}`;
        const data = await apiRequest(url);

        state.currentFolders = data.folders || [];
        state.currentFiles = data.files || [];
        renderBrowserList();

        // XSS-SAFE: dynamic fields (state.currentPath, query) wrapped in escapeHtml()
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
    // Sort controls listener & initial UI sync
    if (elements.browserSortSelect) {
        elements.browserSortSelect.value = state.sortField;
        elements.browserSortSelect.addEventListener('change', (e) => {
            state.sortField = e.target.value;
            localStorage.setItem('lmh_browser_sort_field', state.sortField);
            renderBrowserList();
        });
    }

    if (elements.btnBrowserSortOrder && elements.sortOrderIcon) {
        elements.sortOrderIcon.textContent = state.sortOrder === 'desc' ? '↓' : '↑';
        elements.btnBrowserSortOrder.setAttribute('title', state.sortOrder === 'desc' ? '降序' : '升序');
        elements.btnBrowserSortOrder.addEventListener('click', () => {
            state.sortOrder = state.sortOrder === 'asc' ? 'desc' : 'asc';
            localStorage.setItem('lmh_browser_sort_order', state.sortOrder);
            elements.sortOrderIcon.textContent = state.sortOrder === 'desc' ? '↓' : '↑';
            elements.btnBrowserSortOrder.setAttribute('title', state.sortOrder === 'desc' ? '降序' : '升序');
            renderBrowserList();
        });
    }

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
                // Fixed literal SVG from the ICONS map; the dataset keyword only
                // selects between two literals and is never interpolated.
                // XSS-SAFE: pure-literal icon markup, no dynamic interpolation
                fallback.innerHTML = wrapper.dataset.fallbackIcon === 'video' ? ICONS.video() : ICONS.image();
                wrapper.appendChild(fallback);
            }
        }
    }, true);
}
