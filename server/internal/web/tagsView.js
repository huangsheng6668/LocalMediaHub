// Tags feature module: extracted from app.js (loadTags + tag manager/tag selector + 4 listeners).
import { state } from './state.js';
import { apiRequest, escapeHtml } from './api.js';
import { showToast } from './toast.js';
import { elements } from './dom.js';
import { encodeRoutePath, safeBtoa } from './utils.js';
import { renderBrowserList } from './browserView.js';

// Fetch Tags
export async function loadTags() {
    try {
        const tags = await apiRequest(`${state.apiBase}/api/v1/tags`);
        state.tags = tags || [];

        // Fetch file tags mapping in parallel
        try {
            const fileTagsMap = await apiRequest(`${state.apiBase}/api/v1/tags/file-tags`);
            state.fileTagsMap = fileTagsMap || {};
        } catch (err) {
            console.error('load file-tags mapping error:', err);
        }
    } catch (e) {
        console.error('loadTags error:', e);
        showToast('加载标签失败: ' + e.message, 'error');
    }
}

// Open File tag editor mapping dialog
export function openTaggingDialog(file) {
    state.taggingFile = file;
    elements.tagModalFilePath.textContent = `文件：${file.path}`;

    const fileTags = state.fileTagsMap[file.path] || [];
    const mappedIds = fileTags.map(t => t.id);

    elements.tagSelectorCheckboxes.innerHTML = state.tags.map(tag => {
        const checked = mappedIds.includes(tag.id) ? 'checked' : '';
        return `
            <label class="tag-selector-item">
                <span style="display:flex; align-items:center; gap:8px;">
                    <span style="width:12px; height:12px; border-radius:50%; background-color:${escapeHtml(tag.color)};"></span>
                    <span>${escapeHtml(tag.name)}</span>
                </span>
                <input type="checkbox" data-tag-id="${escapeHtml(tag.id)}" ${checked}>
            </label>
        `;
    }).join('');

    if (state.tags.length === 0) {
        elements.tagSelectorCheckboxes.innerHTML = '<p style="color:var(--text-muted); font-size:13px;">请先去“标签管理”中新建分类标签。</p>';
    }

    elements.modalFileTags.classList.add('active');
}

// Toggle association between a file and a tag
async function toggleFileTagAssociation(checkbox, tagId, filePath) {
    const isAssociate = checkbox.checked;
    const url = `${state.apiBase}/api/v1/tags/${tagId}/files/${encodeRoutePath(filePath)}`;

    try {
        await apiRequest(url, {
            method: isAssociate ? 'POST' : 'DELETE'
        });

        showToast(isAssociate ? '🏷️ 标签关联成功！' : '🏷️ 标签已解除关联', 'success');
        await loadTags(); // Refresh tags mappings in memory

        // Re-render folder list cards to show/hide color dots
        if (state.activeTab === 'browser') {
            renderBrowserList();
        }

        // Re-render container card dot state
        const cleanCardId = `file-card-${safeBtoa(filePath).replace(/=/g, '')}`;
        const cardEl = document.getElementById(cleanCardId);
        if (cardEl) {
            // Determine if file has any tags left
            const fileTags = state.fileTagsMap[filePath] || [];
            if (fileTags.length > 0) cardEl.classList.add('tagged');
            else cardEl.classList.remove('tagged');

            // Re-render custom tags dots/chips on the item details
            const dotsEl = cardEl.querySelector('.tag-color-dots');
            if (dotsEl) {
                dotsEl.innerHTML = fileTags.map(tag => `
                    <span class="tag-dot" style="background-color: ${escapeHtml(tag.color)}" title="${escapeHtml(tag.name)}"></span>
                `).join('');
            }
        }
    } catch (e) {
        checkbox.checked = !isAssociate; // Revert
        showToast(`标签关联失败: ${e.message}`, 'error');
    }
}

// Delegated click dispatcher for the tags manager list
function onTagsManagerListClick(e) {
    const actionEl = e.target.closest('[data-action]');
    if (!actionEl) return;
    if (actionEl.dataset.action === 'delete-tag') {
        deleteTag(actionEl.dataset.id || '', actionEl.dataset.name || '');
    }
}

// Delegated change dispatcher for the file-tag selector dialog
function onTagSelectorChange(e) {
    const checkbox = e.target;
    if (checkbox.matches('input[type="checkbox"][data-tag-id]') && state.taggingFile) {
        toggleFileTagAssociation(checkbox, checkbox.dataset.tagId || '', state.taggingFile.path);
    }
}

// Render Tags Manager (Tab 3)
export function renderTagsManager() {
    if (state.tags.length === 0) {
        elements.tagsManagerList.innerHTML = '<p style="color:var(--text-muted); font-size:14px;">暂无标签分类，请在左侧新建。</p>';
        return;
    }

    elements.tagsManagerList.innerHTML = state.tags.map(tag => {
        const safeColor = escapeHtml(tag.color);
        const safeName = escapeHtml(tag.name);
        const safeId = escapeHtml(tag.id);
        return `
            <div class="tag-chip" style="background-color: ${safeColor}33; border-color: ${safeColor};">
                <span style="display:inline-block; width:10px; height:10px; border-radius:50%; background-color:${safeColor};"></span>
                <span>${safeName}</span>
                <button class="btn-tag-delete" title="删除分类标签" data-action="delete-tag" data-id="${safeId}" data-name="${safeName}">✕</button>
            </div>
        `;
    }).join('');
}

// Delete tag definition
async function deleteTag(tagId, name) {
    if (!confirm(`确定要彻底删除标签 [${name}] 吗？\n所有关联文件的分类记录也会一并清除。`)) return;

    try {
        await apiRequest(`${state.apiBase}/api/v1/tags/${tagId}`, {
            method: 'DELETE'
        });
        showToast(`已成功删除标签 [${name}]`, 'success');
        await loadTags();
        renderTagsManager();
    } catch (e) {
        showToast(`删除失败: ${e.message}`, 'error');
    }
}

// Set up Tags feature listeners (called from app.js setupEventListeners)
export function setupTagsListeners(elements) {
    // Create Tag Button
    elements.btnCreateTag.addEventListener('click', async () => {
        const name = elements.tagNameInput.value.trim();
        const activeColorDot = document.querySelector('.color-dot.active');
        const color = activeColorDot ? activeColorDot.getAttribute('data-color') : '#7c3aed';

        if (!name) {
            showToast('请输入标签分类名称', 'error');
            return;
        }

        try {
            await apiRequest(`${state.apiBase}/api/v1/tags`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name, color })
            });
            showToast(`成功创建标签 [${name}]`, 'success');
            elements.tagNameInput.value = '';
            await loadTags();
            renderTagsManager();
        } catch (e) {
            showToast(`标签创建失败: ${e.message}`, 'error');
        }
    });

    // Delegated click for tag manager (delete tag)
    elements.tagsManagerList.addEventListener('click', onTagsManagerListClick);

    // Delegated change for per-file tag selector checkboxes
    elements.tagSelectorCheckboxes.addEventListener('change', onTagSelectorChange);

    // Close Tag modal dialog
    elements.btnCloseFileTagsModal.addEventListener('click', () => {
        elements.modalFileTags.classList.remove('active');
        state.taggingFile = null;
    });
}
