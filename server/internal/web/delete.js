// Delete feature module: extracted from app.js (deleteMediaFile / deleteFolder).
import { state } from './state.js';
import { apiRequest } from './api.js';
import { showToast } from './toast.js';
import { elements } from './dom.js';
import { browsePath } from './browserView.js';
import { renderDashboard } from './dashboard.js';

// Delete media file from filesystem
export async function deleteMediaFile(file) {
    if (!state.enableDelete) {
        showToast('服务端已禁用删除功能', 'error');
        return;
    }

    if (!confirm(`⚠️ 警告：确定要彻底删除该媒体文件吗？\n此操作不可逆！\n\n文件：${file.name}`)) {
        return;
    }

    try {
        await apiRequest(`${state.apiBase}/api/v1/system/delete`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                path: file.path,
                recursive: false
            })
        });

        showToast('文件删除成功', 'success');
        // If the video modal is open playing this file, close it
        if (state.playingFile && state.playingFile.path === file.path) {
            elements.btnCloseVideoModal.click();
        }
        // Reload folder contents (bypassCache: the server may have served the
        // previous list response with a short max-age, so a plain fetch could
        // hit the browser HTTP cache and re-show the just-deleted entry).
        if (state.activeTab === 'browser') {
            browsePath(state.currentPath, true);
        } else if (state.activeTab === 'dashboard') {
            renderDashboard();
        }
    } catch (e) {
        showToast(`删除失败: ${e.message}`, 'error');
    }
}

// Delete folder from filesystem recursively
export async function deleteFolder(folder) {
    if (!state.enableDelete) {
        showToast('服务端已禁用删除功能', 'error');
        return;
    }
    if (folder.is_root) {
        showToast('无法删除根共享目录', 'error');
        return;
    }

    if (!confirm(`⚠️ 警告：确定要彻底删除该文件夹及其中所有内容吗？\n此操作将递归删除文件夹下所有文件，且不可逆！\n\n文件夹：${folder.name}`)) {
        return;
    }

    try {
        await apiRequest(`${state.apiBase}/api/v1/system/delete`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                path: folder.path,
                recursive: true
            })
        });

        showToast('文件夹删除成功', 'success');
        // Reload folder contents (bypassCache — see deleteMediaFile for rationale).
        browsePath(state.currentPath, true);
    } catch (e) {
        showToast(`删除失败: ${e.message}`, 'error');
    }
}
