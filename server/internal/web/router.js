import { state } from './state.js';
import { renderTextReader } from './textReader.js';
import { render as renderBookshelf } from './bookshelf.js';

// hashParams: tiny helper to extract query params from a hash like
// #/read?path=foo%20bar. Returns an empty Map when there is no query string.
function hashParams(hash) {
    const qIdx = hash.indexOf('?');
    if (qIdx < 0) return new Map();
    const params = new Map();
    new URLSearchParams(hash.slice(qIdx + 1)).forEach((v, k) => params.set(k, v));
    return params;
}

export function handleRoute(elements, renderDashboard, loadRoots, browsePath, renderTagsManager, renderSettings) {
    const hash = window.location.hash || '#/dashboard';

    // De-activate all tabs and menu selections
    [elements.viewDashboard, elements.viewBrowser, elements.viewTags, elements.viewSettings, elements.viewReader].forEach(v => {
        if (v) v.classList.remove('active');
    });
    [elements.menuDashboard, elements.menuBrowser, elements.menuTags, elements.menuSettings].forEach(m => {
        if (m) m.classList.remove('active');
    });

    if (hash.startsWith('#/dashboard')) {
        state.activeTab = 'dashboard';
        if (elements.pageTitle) elements.pageTitle.textContent = '仪表盘';
        if (elements.menuDashboard) elements.menuDashboard.classList.add('active');
        if (elements.viewDashboard) elements.viewDashboard.classList.add('active');
        renderDashboard();
    } else if (hash.startsWith('#/browser')) {
        state.activeTab = 'browser';
        if (elements.pageTitle) elements.pageTitle.textContent = '媒体共享库';
        if (elements.menuBrowser) elements.menuBrowser.classList.add('active');
        if (elements.viewBrowser) elements.viewBrowser.classList.add('active');

        if (!state.currentPath) {
            loadRoots();
        } else {
            browsePath(state.currentPath);
        }
    } else if (hash.startsWith('#/tags')) {
        state.activeTab = 'tags';
        if (elements.pageTitle) elements.pageTitle.textContent = '标签管理';
        if (elements.menuTags) elements.menuTags.classList.add('active');
        if (elements.viewTags) elements.viewTags.classList.add('active');
        renderTagsManager();
    } else if (hash.startsWith('#/settings')) {
        state.activeTab = 'settings';
        if (elements.pageTitle) elements.pageTitle.textContent = '系统设置';
        if (elements.menuSettings) elements.menuSettings.classList.add('active');
        if (elements.viewSettings) elements.viewSettings.classList.add('active');
        renderSettings();
    } else if (hash.startsWith('#/read')) {
        // Text reader view (Task 15). No sidebar menu entry — entered by
        // clicking a .txt / .epub card in the browser. Bookshelf wiring (T16)
        // will also feed into this same view via #/read?path=...
        state.activeTab = 'read';
        if (elements.pageTitle) elements.pageTitle.textContent = '阅读';
        if (elements.viewReader) {
            elements.viewReader.classList.add('active');
            const params = hashParams(hash);
            const path = params.get('path') || '';
            renderTextReader(elements.viewReader, path);
        }
    } else if (hash.startsWith('#/bookshelf')) {
        // Bookshelf view (Task 16): scans localStorage for every book_progress
        // entry and renders a grid of clickable cards. Shares the same off-menu
        // #view-reader section that #/read uses.
        state.activeTab = 'bookshelf';
        if (elements.pageTitle) elements.pageTitle.textContent = '书架';
        if (elements.viewReader) {
            elements.viewReader.classList.add('active');
            renderBookshelf(elements.viewReader);
        }
    }
}
