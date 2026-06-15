import { state } from './state.js';

export function handleRoute(elements, renderDashboard, loadRoots, browsePath, renderTagsManager, renderSettings) {
    const hash = window.location.hash || '#/dashboard';
    
    // De-activate all tabs and menu selections
    [elements.viewDashboard, elements.viewBrowser, elements.viewTags, elements.viewSettings].forEach(v => {
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
    }
}
