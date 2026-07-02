import { state } from './state.js';
import { handleRoute } from './router.js';
import { loadConfig, renderSettings, setupSettingsListeners } from './settings.js';
import {
    loadTags,
    renderTagsManager,
    setupTagsListeners
} from './tagsView.js';
import { elements } from './dom.js';
import { setupVideoPlayerListeners } from './videoPlayer.js';
import { setupLightboxListeners } from './lightbox.js';
import { renderDashboard, setupDashboardListeners } from './dashboard.js';
import { loadRoots, browsePath, setupBrowserListeners } from './browserView.js';

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
        dot.addEventListener('click', () => {
            elements.colorDots.forEach(d => d.classList.remove('active'));
            dot.classList.add('active');
        });
    });

    // Browser-view module listeners (search, grid clicks, breadcrumbs, thumbnail fallback)
    setupBrowserListeners(elements);

    // Dashboard module listeners (recent items click)
    setupDashboardListeners(elements);

    // Video player module listeners (modal open/close, controls, keyboard shortcuts)
    setupVideoPlayerListeners(elements);

    // Lightbox module listeners (image modal close/nav, stitch mode, keyboard shortcuts)
    setupLightboxListeners(elements);
}
