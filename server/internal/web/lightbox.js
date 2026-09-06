// Lightbox (image preview) feature module: extracted from app.js
// (openMedia + openImageLightbox + renderLightboxImage + navigateLightbox + listeners).
import { state } from './state.js';
import { escapeHtml } from './api.js';
import { elements } from './dom.js';
import { encodeRoutePath } from './utils.js';
import { openVideoPlayer } from './videoPlayer.js';
import { showToast } from './toast.js';

// Open Video/Image assets with defense-in-depth text interceptor
export function openMedia(file) {
    if (!file) return;
    const ext = (file.extension || (file.path ? file.path.slice(file.path.lastIndexOf('.')) : '')).toLowerCase();
    const isText = file.media_type === 'text' || ['.txt', '.epub', '.mobi', '.azw3'].includes(ext);
    if (isText) {
        if (['.txt', '.epub'].includes(ext) || file.media_type === 'text') {
            window.location.hash = `#/read?path=${encodeURIComponent(file.path)}`;
        } else {
            showToast('暂不支持该格式（仅支持 .txt / .epub）', 'info');
        }
        return;
    }
    if (file.media_type === 'video') {
        openVideoPlayer(file);
    } else if (file.media_type === 'image') {
        openImageLightbox(file);
    }
}

// Image Lightbox popup
function openImageLightbox(file) {
    // Collect all image files in the current view to allow previous/next navigation
    state.lightboxFiles = state.currentFiles.filter(f => f.media_type === 'image');

    // Alphanumeric natural sort in ascending order (by name)
    state.lightboxFiles.sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' }));

    state.lightboxIndex = state.lightboxFiles.findIndex(f => f.path === file.path);

    renderLightboxImage();
    elements.modalImagePreview.classList.add('active');
}

// Show image in lightbox
function renderLightboxImage() {
    if (state.lightboxIndex < 0 || state.lightboxIndex >= state.lightboxFiles.length) return;

    if (state.lightboxStitchMode) {
        elements.btnImageModeToggle.classList.add('active');
        elements.btnImageModeToggle.textContent = '单张模式';

        elements.lightboxSingleView.style.display = 'none';
        elements.lightboxStitchView.style.display = 'flex';
        elements.btnImagePrev.style.display = 'none';
        elements.btnImageNext.style.display = 'none';

        // Render all files in stitch view if not already loaded/rendered
        if (elements.lightboxStitchView.children.length === 0) {
            elements.lightboxStitchView.innerHTML = ''; // XSS-SAFE: clearing, no dynamic content
            state.lightboxFiles.forEach((file, idx) => {
                let url = `${state.apiBase}/api/v1/images/${encodeRoutePath(file.path)}/original`;
                if (state.isSystemBrowse) {
                    url = `${state.apiBase}/api/v1/system/original?path=${encodeURIComponent(file.path)}`;
                }
                const imgContainer = document.createElement('div');
                imgContainer.className = 'stitch-image-item';
                imgContainer.id = `stitch-img-${idx}`;
                // URL is derived from user media-library file paths (not purely
                // server-controlled), so it needs the same escaping as file.name.
                // XSS-SAFE: file.name and URL both wrapped in escapeHtml()
                imgContainer.innerHTML = `
                    <img src="${escapeHtml(url)}" alt="${escapeHtml(file.name)}" loading="lazy">
                    <div class="stitch-image-caption">${escapeHtml(file.name)} (${idx + 1}/${state.lightboxFiles.length})</div>
                `;
                elements.lightboxStitchView.appendChild(imgContainer);
            });
        }

        // Scroll target image into view
        const targetImg = document.getElementById(`stitch-img-${state.lightboxIndex}`);
        if (targetImg) {
            setTimeout(() => {
                targetImg.scrollIntoView({ behavior: 'auto', block: 'start' });
            }, 50);
        }
    } else {
        elements.btnImageModeToggle.classList.remove('active');
        elements.btnImageModeToggle.textContent = '拼接模式';

        elements.lightboxSingleView.style.display = 'flex';
        elements.lightboxStitchView.style.display = 'none';
        elements.btnImagePrev.style.display = 'flex';
        elements.btnImageNext.style.display = 'flex';
        elements.lightboxStitchView.innerHTML = ''; // XSS-SAFE: clearing stitch view to free memory

        const file = state.lightboxFiles[state.lightboxIndex];

        let url = `${state.apiBase}/api/v1/images/${encodeRoutePath(file.path)}/original`;
        if (state.isSystemBrowse) {
            url = `${state.apiBase}/api/v1/system/original?path=${encodeURIComponent(file.path)}`;
        }

        elements.lightboxImg.src = url;
        elements.lightboxCaption.textContent = `${file.name} (${state.lightboxIndex + 1}/${state.lightboxFiles.length})`;
    }
}

// Navigate lightbox
function navigateLightbox(dir) {
    if (state.lightboxFiles.length <= 1) return;
    state.lightboxIndex += dir;

    // Wrap around boundaries
    if (state.lightboxIndex < 0) state.lightboxIndex = state.lightboxFiles.length - 1;
    if (state.lightboxIndex >= state.lightboxFiles.length) state.lightboxIndex = 0;

    renderLightboxImage();
}

// All lightbox-related event listener registrations (moved from setupEventListeners).
export function setupLightboxListeners(elements) {
    // Close Image Modal (Lightbox)
    elements.btnCloseImageModal.addEventListener('click', () => {
        elements.modalImagePreview.classList.remove('active');
        elements.lightboxImg.src = '';
        elements.lightboxStitchView.innerHTML = ''; // XSS-SAFE: clearing, no dynamic content
    });

    // Lightbox navigation
    elements.btnImagePrev.addEventListener('click', () => navigateLightbox(-1));
    elements.btnImageNext.addEventListener('click', () => navigateLightbox(1));

    // Toggle Stitch Mode
    elements.btnImageModeToggle.addEventListener('click', () => {
        state.lightboxStitchMode = !state.lightboxStitchMode;
        localStorage.setItem('lightboxStitchMode', state.lightboxStitchMode);
        renderLightboxImage();
    });

    // Stitch View Scroll listener to dynamically update the active index.
    // rAF-throttled: the handler walks every .stitch-image-item with
    // getBoundingClientRect, so more than once per frame is layout thrash.
    let stitchRafId = null;
    elements.lightboxStitchView.addEventListener('scroll', () => {
        if (!state.lightboxStitchMode || stitchRafId !== null) return;
        stitchRafId = requestAnimationFrame(() => {
            stitchRafId = null;
            const items = elements.lightboxStitchView.querySelectorAll('.stitch-image-item');
            const containerRect = elements.lightboxStitchView.getBoundingClientRect();

            let closestIndex = state.lightboxIndex;
            let minDistance = Infinity;

            items.forEach((item, idx) => {
                const rect = item.getBoundingClientRect();
                // Distance from item's top to container's top
                const distance = Math.abs(rect.top - containerRect.top);
                if (distance < minDistance) {
                    minDistance = distance;
                    closestIndex = idx;
                }
            });

            if (closestIndex !== state.lightboxIndex && closestIndex >= 0 && closestIndex < state.lightboxFiles.length) {
                state.lightboxIndex = closestIndex;
            }
        });
    });

    document.addEventListener('keydown', (e) => {
        if (!elements.modalImagePreview.classList.contains('active')) return;
        if (e.key === 'ArrowLeft') {
            if (state.lightboxStitchMode) {
                // In stitch mode, scrolling up or back is nice
                const prevIndex = Math.max(0, state.lightboxIndex - 1);
                const targetImg = document.getElementById(`stitch-img-${prevIndex}`);
                if (targetImg) targetImg.scrollIntoView({ behavior: 'smooth', block: 'start' });
            } else {
                navigateLightbox(-1);
            }
        }
        if (e.key === 'ArrowRight') {
            if (state.lightboxStitchMode) {
                const nextIndex = Math.min(state.lightboxFiles.length - 1, state.lightboxIndex + 1);
                const targetImg = document.getElementById(`stitch-img-${nextIndex}`);
                if (targetImg) targetImg.scrollIntoView({ behavior: 'smooth', block: 'start' });
            } else {
                navigateLightbox(1);
            }
        }
        if (e.key === 'Escape') {
            elements.modalImagePreview.classList.remove('active');
            elements.lightboxImg.src = '';
            elements.lightboxStitchView.innerHTML = ''; // XSS-SAFE: clearing, no dynamic content
        }
    });
}
