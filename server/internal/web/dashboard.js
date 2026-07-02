// Dashboard feature module: extracted from app.js
// (renderDashboard + onDashboardRecentClick + listeners).
import { state } from './state.js';
import { apiRequest, escapeHtml } from './api.js';
import { elements } from './dom.js';
import { formatSize } from './utils.js';
import { openVideoPlayer } from './videoPlayer.js';

// Delegated click dispatcher for dashboard recent items
export function onDashboardRecentClick(e) {
    const actionEl = e.target.closest('[data-action]');
    if (!actionEl) return;
    if (actionEl.dataset.action === 'open-video') {
        const idx = Number(actionEl.dataset.index);
        if (state.dashboardRecentFiles[idx]) openVideoPlayer(state.dashboardRecentFiles[idx]);
    }
}

// Render Dashboard (Tab 1)
export async function renderDashboard() {
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

// All dashboard-related event listener registrations (moved from setupEventListeners).
export function setupDashboardListeners(elements) {
    // Delegated click handling for dashboard recent items
    elements.dashboardRecent.addEventListener('click', onDashboardRecentClick);
}
