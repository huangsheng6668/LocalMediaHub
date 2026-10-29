import { state, setAuthToken } from './state.js';
import { handleRoute } from './router.js';
import { loadConfig, renderSettings, setupSettingsListeners } from './settings.js';
import { migrateLocalProgress } from './library.js';

import { elements } from './dom.js';
import { setupVideoPlayerListeners } from './videoPlayer.js';
import { setupLightboxListeners } from './lightbox.js';
import { renderDashboard, setupDashboardListeners } from './dashboard.js';
import { loadRoots, browsePath, setupBrowserListeners } from './browserView.js';
import { renderBookmarks } from './bookmarksView.js';
import { AUTH_REQUIRED_EVENT } from './api.js';
import * as readerPrefs from './readerPrefs.js';
import { initScrollNav } from './scrollNav.js';
import { rememberFocus, restoreFocus, freezeBackground, unfreezeBackground } from './modalFocus.js';

// Auth modal — module-scoped so it persists across show/hide.
let lastFailedUrl = null;

export function applyGlobalAppTheme() {
    const s = readerPrefs.getSettings();
    let themeKey = s.theme || 'DAY';
    if (themeKey === 'AUTO') {
        const isDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
        themeKey = isDark ? 'NIGHT' : 'DAY';
    }
    // The CSS theme name is the lower-cased preference key. boot.js (the
    // pre-paint script that prevents FOUC) derives it the same way, so the two
    // can no longer drift apart — they previously each held a 7-entry map that
    // had to be kept in sync by hand. See boot.js for the graceful-degradation
    // note on unknown keys.
    const cssTheme = String(themeKey).toLowerCase();
    document.documentElement.dataset.theme = cssTheme;
    // data-reader-theme is only ever matched as a presence selector in CSS
    // (body[data-reader-theme][data-active-tab="read"] …), so the original
    // upper-case key is kept here for readability.
    document.body.dataset.readerTheme = themeKey;
    updateThemeToggleIcon(themeKey === 'NIGHT' || themeKey === 'NIGHT_BLACK' ? 'night' : 'day');
}

function showAuthModal(url) {
    lastFailedUrl = url;
    // 令牌弹窗用 .hidden 控制显示（overlay-modal 用的是 .active），所以这里
    // 手动组合焦点管理原语：记录来源焦点 + 冻结背景 + 把焦点移入输入框。
    rememberFocus();
    freezeBackground();
    elements.authModal.classList.remove('hidden');
    elements.authTokenInput.focus();
}

function hideAuthModal() {
    elements.authModal.classList.add('hidden');
    elements.authTokenInput.value = '';
    unfreezeBackground();
    restoreFocus();
}

async function saveAuthAndRetry() {
    const token = elements.authTokenInput.value.trim();
    if (!token) return;
    setAuthToken(token);
    hideAuthModal();
    // Re-drive the current view with the new token instead of a full page
    // reload: reloading lost the browse position / reader DOM and cost a
    // second full app bootstrap. loadConfig now succeeds with the header
    // (delete controls etc. recompute), and handleRoute re-fetches the
    // current view's data with the token attached.
    if (lastFailedUrl) {
        await loadConfig();
        handleRoute(elements, renderDashboard, loadRoots, browsePath, renderBookmarks, renderSettings);
    }
}

// Initial Setup
document.addEventListener('DOMContentLoaded', () => {
    initApp();
    setupEventListeners();
});

// Initialize Application
async function initApp() {
    // Apply global app theme
    applyGlobalAppTheme();

    // Determine internal host IP from current window location
    const protocol = window.location.protocol;
    const host = window.location.host;
    state.apiBase = `${protocol}//${host}`;

    elements.infoHost.textContent = host;
    elements.infoIp.textContent = window.location.hostname;

    // Load config and initial data
    await loadConfig();

    migrateLocalProgress(); // fire-and-forget，失败不影响启动

    // Parse Hash Routing on page load
    handleRoute(elements, renderDashboard, loadRoots, browsePath, renderBookmarks, renderSettings);
}

// Router
window.addEventListener('hashchange', () => {
    handleRoute(elements, renderDashboard, loadRoots, browsePath, renderBookmarks, renderSettings);
    // Round 16 C1: 移动端路由切换后关闭 sidebar（桌面端 sidebar 无 .open class，不受影响）
    if (elements.sidebar && elements.sidebar.classList.contains('open')) {
        elements.sidebar.classList.remove('open');
        elements.hamburgerBtn?.setAttribute('aria-expanded', 'false');
        if (elements.sidebarBackdrop) elements.sidebarBackdrop.hidden = true;
    }
});

// Set up Event Listeners
function setupEventListeners() {
    // 各视图模块的监听器注册互相独立：任一模块因元素缺失或异常抛错，不应中断
    // 其余模块。此前的表现是「某些按钮完全没反应」，而控制台只有一条错误、
    // 很难定位是哪个模块挂的（settings.js / lightbox.js / dashboard.js 里的
    // .addEventListener 调用都不做空值检查）。这里逐个 try/catch 并标出模块名。
    const safeSetup = (name, fn) => {
        try {
            fn();
        } catch (e) {
            console.error(`[setup] ${name} 监听器注册失败，该模块功能不可用:`, e);
        }
    };

    // Settings module listeners (Scan Trigger + Save Settings)
    safeSetup('settings', () => setupSettingsListeners(elements));

    // Quick scroll navigation FAB (Top & Bottom)
    safeSetup('scrollNav', () => initScrollNav());

    // Browser-view module listeners (search, grid clicks, breadcrumbs, thumbnail fallback)
    safeSetup('browserView', () => setupBrowserListeners(elements));

    // Dashboard module listeners (recent items click)
    safeSetup('dashboard', () => setupDashboardListeners(elements));

    // Video player module listeners (modal open/close, controls, keyboard shortcuts)
    safeSetup('videoPlayer', () => setupVideoPlayerListeners(elements));

    // Lightbox module listeners (image modal close/nav, stitch mode, keyboard shortcuts)
    safeSetup('lightbox', () => setupLightboxListeners(elements));

    // Round 16 C1: Hamburger toggle for tablet/mobile sidebar drawer
    if (elements.hamburgerBtn && elements.sidebar) {
        elements.hamburgerBtn.addEventListener('click', () => {
            const expanded = elements.sidebar.classList.toggle('open');
            elements.hamburgerBtn.setAttribute('aria-expanded', String(expanded));
            if (elements.sidebarBackdrop) elements.sidebarBackdrop.hidden = !expanded;
        });
    }
    if (elements.sidebarBackdrop) {
        elements.sidebarBackdrop.addEventListener('click', () => {
            elements.sidebar.classList.remove('open');
            elements.hamburgerBtn?.setAttribute('aria-expanded', 'false');
            elements.sidebarBackdrop.hidden = true;
        });
    }

    // Auth modal — show on AUTH_REQUIRED_EVENT (401), save-and-retry or cancel.
    window.addEventListener(AUTH_REQUIRED_EVENT, (e) => {
        showAuthModal(e.detail?.url);
    });
    if (elements.authSaveBtn) {
        elements.authSaveBtn.addEventListener('click', saveAuthAndRetry);
    }
    if (elements.authCancelBtn) {
        elements.authCancelBtn.addEventListener('click', hideAuthModal);
    }
    if (elements.authTokenInput) {
        elements.authTokenInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') saveAuthAndRetry();
            if (e.key === 'Escape') hideAuthModal();
        });
    }

    // Header day/night toggle: flips the persisted theme key; the
    // reader-prefs-changed event re-runs applyGlobalAppTheme, so the
    // chrome updates without a reload.
    if (elements.btnThemeToggle) {
        elements.btnThemeToggle.addEventListener('click', () => {
            const s = readerPrefs.getSettings();
            const resolved = s.theme === 'AUTO'
                ? (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'NIGHT' : 'DAY')
                : (s.theme || 'DAY');
            const nextTheme = (resolved === 'NIGHT' || resolved === 'NIGHT_BLACK') ? 'DAY' : 'NIGHT';
            readerPrefs.saveSettings({ theme: nextTheme });
        });
    }

    // Global App Theme synchronization
    window.addEventListener('reader-prefs-changed', applyGlobalAppTheme);
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', applyGlobalAppTheme);
}

// Update the sun/moon icon visibility based on the current chrome theme.
// Module-level (referenced by applyGlobalAppTheme).
function updateThemeToggleIcon(theme) {
    document.querySelectorAll('.theme-toggle-icon').forEach(el => {
        el.hidden = (el.dataset.icon !== (theme === 'night' ? 'moon' : 'sun'));
    });
}
