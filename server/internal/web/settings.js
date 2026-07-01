// Settings feature module: extracted from app.js (loadConfig / renderSettings + 2 button listeners).
import { state } from './state.js';
import { showToast } from './toast.js';
import { apiRequest } from './api.js';
import { elements } from './dom.js';

// Fetch configs
export async function loadConfig() {
    try {
        const data = await apiRequest(`${state.apiBase}/api/v1/admin/config`);
        state.folders = data.scan.roots || [];
        state.videoExts = data.scan.video_extensions || [];
        state.imageExts = data.scan.image_extensions || [];
        state.allowedRoots = (data.system && data.system.allowed_roots) || [];
        state.enableDelete = (data.system && data.system.enable_delete) || false;
        state.thumbMax = (data.thumbnail && data.thumbnail.max_size) || 300;

        elements.infoScanRoots.textContent = state.folders.join(', ') || '全盘自动检测';
    } catch (e) {
        console.error('loadConfig error:', e);
        showToast('无法从后端获取系统配置: ' + e.message, 'error');
    }
}

// Render Settings View (Tab 4)
export function renderSettings() {
    elements.settingsRoots.value = state.folders.join('\n');
    elements.settingsVideoExts.textContent = state.videoExts.join(', ') || '未配置';
    elements.settingsImageExts.textContent = state.imageExts.join(', ') || '未配置';
    elements.settingsAllowedRoots.textContent = state.allowedRoots.join(', ') || '未限制/不可浏览系统';
    elements.settingsEnableDelete.textContent = state.enableDelete ? '已开启 (运行在客户端删除 PC 文件)' : '已禁用 (安全只读)';
    elements.settingsThumbMax.textContent = `${state.thumbMax} px`;
}

// Set up Settings-related event listeners (btnTriggerScan + btnSaveSettings).
// `elements` is the shared DOM element map from app.js.
export function setupSettingsListeners(elements) {
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
