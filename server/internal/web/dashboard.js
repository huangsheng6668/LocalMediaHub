// Dashboard feature module: extracted from app.js
// (renderDashboard + onDashboardRecentClick + listeners).
import { state } from './state.js';
import { apiRequest, escapeHtml } from './api.js';
import { elements } from './dom.js';
import { formatSize } from './utils.js';
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
    const [textsRes, videosRes, imagesRes, recentRes] = await Promise.allSettled([
        apiRequest(`${state.apiBase}/api/v1/texts?page=1&page_size=1`),
        apiRequest(`${state.apiBase}/api/v1/videos?page=1&page_size=1`),
        apiRequest(`${state.apiBase}/api/v1/images?page=1&page_size=1`),
        apiRequest(`${state.apiBase}/api/v1/videos?page=1&page_size=3`),
    ]);

    if (textsRes.status === 'rejected') console.error('Fetch text count error:', textsRes.reason);
    if (videosRes.status === 'rejected') console.error('Fetch video count error:', videosRes.reason);
    if (imagesRes.status === 'rejected') console.error('Fetch image count error:', imagesRes.reason);
    if (recentRes.status === 'rejected') console.error('Fetch recent videos error:', recentRes.reason);

    elements.statTexts.textContent = textsRes.status === 'fulfilled' ? (textsRes.value.total || 0) : 0;
    elements.statVideos.textContent = videosRes.status === 'fulfilled' ? (videosRes.value.total || 0) : 0;
    elements.statImages.textContent = imagesRes.status === 'fulfilled' ? (imagesRes.value.total || 0) : 0;

    if (recentRes.status === 'fulfilled') {
        const items = recentRes.value.items || [];
        state.dashboardRecentFiles = items;

        if (items.length === 0) {
            elements.dashboardRecent.classList.add('empty-state');
            elements.dashboardRecent.innerHTML = '<div class="empty-state">暂无最近媒体数据</div>'; // XSS-SAFE: hardcoded literal
        } else {
            elements.dashboardRecent.classList.remove('empty-state');
            // XSS-SAFE: map callback returns a template whose only dynamic field (file.name) is wrapped in escapeHtml()
            elements.dashboardRecent.innerHTML = items.map((file, index) => {
                return `
                    <div class="info-item dashboard-recent-item" data-action="open-video" data-index="${index}">
                        <span class="info-label">🎬 ${escapeHtml(file.name)}</span>
                        <span class="info-value dashboard-recent-size">${formatSize(file.size)}</span>
                    </div>
                `;
            }).join('');
        }
    } else {
        elements.dashboardRecent.classList.add('empty-state');
        elements.dashboardRecent.innerHTML = '<div class="empty-state">连接服务端接口失败</div>'; // XSS-SAFE: hardcoded literal
    }
}

// All dashboard-related event listener registrations (moved from setupEventListeners).
export function setupDashboardListeners(elements) {
    // Delegated click handling for dashboard recent items
    elements.dashboardRecent.addEventListener('click', onDashboardRecentClick);
}
