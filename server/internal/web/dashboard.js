// Dashboard feature module: extracted from app.js
// (renderDashboard + onDashboardRecentClick + listeners).
import { state } from './state.js';
import { apiRequest, withMediaAuthToken } from './api.js';
import { elements } from './dom.js';
import { formatSize, encodeRoutePath, formatTranscodeStatus, escapeHtml } from './utils.js';
import { openVideoPlayer } from './videoPlayer.js';
import { renderSection as renderBookshelfSection } from './bookshelf.js';

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
export async function renderDashboard() {
    // 0. Bookshelf section (Task 16): reads localStorage synchronously, so we
    // can paint it before the first network request resolves. renderSection
    // is a no-op when no book_progress:* entries exist.
    if (elements.dashboardBookshelf) {
        renderBookshelfSection(elements.dashboardBookshelf);
    }

    // 1. Fetch totals + recent videos in parallel. Previously these were
    // awaited serially, so first paint waited ~4 RTTs; Promise.allSettled
    // collapses them into one round-trip batch with per-request fallbacks.
    //
    // 2026-09-30: /videos 此前被请求两次 —— 一次 page_size=1 只为取 total，
    // 一次 page_size=3 取最近列表。该端点响应同时带 { total, items }，所以
    // 一次 page_size=3 就够，请求数 5 → 4。
    const [textsRes, imagesRes, videosRes, transcodeRes] = await Promise.allSettled([
        apiRequest(`${state.apiBase}/api/v1/texts?page=1&page_size=1`),
        apiRequest(`${state.apiBase}/api/v1/images?page=1&page_size=1`),
        apiRequest(`${state.apiBase}/api/v1/videos?page=1&page_size=3`),
        // P4 (2026-09-03): transcode path observability (encoder chain +
        // session cap). Bearer injection + 401 handling ride apiRequest.
        apiRequest(`${state.apiBase}/api/v1/admin/transcode/status`),
    ]);

    if (textsRes.status === 'rejected') console.error('Fetch text count error:', textsRes.reason);
    if (imagesRes.status === 'rejected') console.error('Fetch image count error:', imagesRes.reason);
    if (videosRes.status === 'rejected') console.error('Fetch videos (count + recent) error:', videosRes.reason);

    elements.statTexts.textContent = textsRes.status === 'fulfilled' ? (textsRes.value.total || 0) : 0;
    elements.statVideos.textContent = videosRes.status === 'fulfilled' ? (videosRes.value.total || 0) : 0;
    elements.statImages.textContent = imagesRes.status === 'fulfilled' ? (imagesRes.value.total || 0) : 0;

    // textContent only — no innerHTML, CSP/XSS-safe by construction.
    if (elements.infoTranscodeEncoder && elements.infoTranscodeSessions) {
        const t = formatTranscodeStatus(transcodeRes.status === 'fulfilled' ? transcodeRes.value : null);
        elements.infoTranscodeEncoder.textContent = t.encoder;
        elements.infoTranscodeSessions.textContent = t.sessions;
    }

    if (videosRes.status === 'fulfilled') {
        const items = videosRes.value.items || [];
        state.dashboardRecentFiles = items;

        if (items.length === 0) {
            // #dashboard-recent 自身即 .empty-state 载体（index.html 的初始
            // class 也是 "recent-list empty-state"）。此前在容器加类的同时
            // 又插入一层 <div class="empty-state">，两层 flex 居中 + 两层
            // padding: 64px 24px 叠加，空状态被推到异常位置。改用
            // textContent 直接落在容器上。
            elements.dashboardRecent.classList.add('empty-state');
            elements.dashboardRecent.textContent = '暂无最近媒体数据';
        } else {
            elements.dashboardRecent.classList.remove('empty-state');
            // XSS-SAFE: dynamic fields (thumb url / name / size) all pass through escapeHtml()/formatSize()
            elements.dashboardRecent.innerHTML = items.map((file, index) => {
                const thumb = withMediaAuthToken(`${state.apiBase}/api/v1/videos/${encodeRoutePath(file.path)}/thumbnail`);
                return `
                    <div class="recent-item dashboard-recent-item" data-action="open-video" data-index="${index}">
                        <img class="recent-item__thumb" src="${escapeHtml(thumb)}" alt="" loading="lazy" decoding="async">
                        <span class="recent-item__name">${escapeHtml(file.name)}</span>
                        <span class="recent-item__size num-tabular">${formatSize(file.size)}</span>
                    </div>
                `;
            }).join('');
        }
    } else {
        // 同上：容器承载 .empty-state，不再嵌套第二层。
        elements.dashboardRecent.classList.add('empty-state');
        elements.dashboardRecent.textContent = '连接服务端接口失败';
    }
}

// All dashboard-related event listener registrations (moved from setupEventListeners).
export function setupDashboardListeners(elements) {
    // Delegated click handling for dashboard recent items
    elements.dashboardRecent.addEventListener('click', onDashboardRecentClick);

    // Delegated thumbnail error fallback (img 'error' does not bubble -> capture phase;
    // CSP forbids inline onerror attributes). Mirrors the browserView.js pattern.
    elements.dashboardRecent.addEventListener('error', (e) => {
        const img = e.target;
        if (img instanceof HTMLImageElement && img.classList.contains('recent-item__thumb')) {
            const fb = document.createElement('div');
            fb.className = 'recent-item__thumb recent-item__thumb--fallback';
            fb.innerHTML = '<svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor" stroke="none" aria-hidden="true"><path d="M8 5v14l11-7z"/></svg>'; // XSS-SAFE: 纯字面量
            img.replaceWith(fb);
        }
    }, true);
}
