// Settings feature module: extracted from app.js (loadConfig / renderSettings + 2 button listeners).
import { state } from './state.js';
import { showToast } from './toast.js';
import { apiRequest, escapeHtml } from './api.js';
import { elements } from './dom.js';

import * as readerPrefs from './readerPrefs.js';

function getFolderPaths() {
    return (state.folders || []).map(f => typeof f === 'string' ? f : (f.path || f.name || '')).filter(Boolean);
}

// Fetch configs
export async function loadConfig() {
    try {
        const data = await apiRequest(`${state.apiBase}/api/v1/admin/config`);
        state.folders = data.scan.roots || [];
        state.videoExts = data.scan.video_extensions || [];
        state.imageExts = data.scan.image_extensions || [];
        state.textExts = data.scan.text_extensions || [];
        state.allowedRoots = (data.system && data.system.allowed_roots) || [];
        state.enableDelete = !!(data.system && data.system.enable_delete);
        state.thumbMax = (data.thumbnail && data.thumbnail.max_size) || 300;

        const paths = getFolderPaths();
        if (paths.length > 0) {
            // XSS-SAFE: paths escaped via escapeHtml()
            elements.infoScanRoots.innerHTML = `<div class="path-chip-group">${paths.map(p => `<span class="path-chip" title="${escapeHtml(p)}">${escapeHtml(p)}</span>`).join('')}</div>`;
        } else {
            elements.infoScanRoots.textContent = '全盘自动检测';
        }
    } catch (e) {
        console.error('loadConfig error:', e);
        showToast('无法从后端获取系统配置: ' + e.message, 'error');
    }
}

// Render Settings View (Tab 4)
export function renderSettings() {
    elements.settingsRoots.value = getFolderPaths().join('\n');
    elements.settingsVideoExts.textContent = state.videoExts.join(', ') || '未配置';
    elements.settingsImageExts.textContent = state.imageExts.join(', ') || '未配置';
    elements.settingsTextExts.textContent = state.textExts.join(', ') || '未配置';
    elements.settingsAllowedRoots.textContent = state.allowedRoots.join(', ') || '未限制/不可浏览系统';
    if (state.enableDelete) {
        elements.settingsEnableDelete.textContent = '⚠️ 已开启 (允许从客户端删除电脑媒体文件)';
        elements.settingsEnableDelete.style.color = 'var(--error)';
        elements.settingsEnableDelete.style.fontWeight = 'bold';
    } else {
        elements.settingsEnableDelete.textContent = '已禁用 (安全只读)';
        elements.settingsEnableDelete.style.color = '';
        elements.settingsEnableDelete.style.fontWeight = '';
    }
    elements.settingsThumbMax.textContent = `${state.thumbMax} px`;
    renderThemeGrid();
}

function renderThemeGrid() {
    const grid = document.getElementById('settings-global-theme-grid');
    if (!grid) return;
    const currentTheme = readerPrefs.getSettings().theme || 'DAY';
    // XSS-SAFE: key/label come from the frozen THEME_LABELS constant — not user input
    grid.innerHTML = Object.entries(readerPrefs.THEME_LABELS).map(([key, label]) => {
        const isChecked = currentTheme === key ? 'checked' : '';
        return `
            <label class="reader-settings__theme-card">
                <input type="radio" name="globalTheme" value="${key}" ${isChecked}>
                <span class="reader-settings__theme-swatch" data-theme="${key}"></span>
                <span class="reader-settings__theme-label">${label}</span>
            </label>
        `;
    }).join('');
}

// Set up Settings-related event listeners (btnTriggerScan + btnSaveSettings).
// `elements` is the shared DOM element map from app.js.
export function setupSettingsListeners(elements) {
    const grid = document.getElementById('settings-global-theme-grid');
    if (grid) {
        grid.addEventListener('change', (e) => {
            if (e.target && e.target.name === 'globalTheme') {
                const newTheme = e.target.value;
                readerPrefs.saveSettings({ theme: newTheme });
                const label = readerPrefs.THEME_LABELS[newTheme] || newTheme;
                showToast(`🎨 应用全局主题已切换为：${label}`, 'success');
            }
        });
    }

    // Scan Trigger
    elements.btnTriggerScan.addEventListener('click', async () => {
        try {
            await apiRequest(`${state.apiBase}/api/v1/admin/scan/trigger`, { method: 'POST' });
            showToast('🚀 已成功在后台触发全量媒体重扫描！', 'success');
        } catch (e) {
            showToast(`扫描启动失败: ${e.message}`, 'error');
        }
    });

    // Save Settings
    elements.btnSaveSettings.addEventListener('click', async () => {
        const rootsText = elements.settingsRoots.value.trim();
        const roots = rootsText ? rootsText.split('\n').map(r => r.trim()).filter(r => r !== '') : [];

        try {
            await apiRequest(`${state.apiBase}/api/v1/admin/config`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ roots })
            });
            showToast('💾 系统路径配置更新保存成功！', 'success');
            await loadConfig();
            renderSettings();
        } catch (e) {
            showToast(`配置保存失败: ${e.message}`, 'error');
        }
    });
}
