// Quick scroll navigation (Top & Bottom FAB) module.
// Computes visibility states and provides smooth scrolling for long lists and books.

/**
 * Pure helper to compute FAB visibility based on scroll metrics.
 * @param {number} scrollTop
 * @param {number} clientHeight
 * @param {number} scrollHeight
 * @param {number} threshold
 * @returns {{ showTop: boolean, showBottom: boolean }}
 */
export function computeFabVisibility(scrollTop, clientHeight, scrollHeight, threshold = 120) {
    const maxScroll = (scrollHeight || 0) - (clientHeight || 0);
    if (maxScroll <= 100) {
        return { showTop: false, showBottom: false };
    }
    const top = scrollTop || 0;
    return {
        showTop: top > threshold,
        showBottom: top < (maxScroll - threshold)
    };
}

/**
 * Resolves the active scrollable container based on current route/view.
 * @param {Document|Element} root
 * @returns {Element|null}
 */
export function resolveScrollContainer(root = typeof document !== 'undefined' ? document : null) {
    if (!root) return null;
    const body = root.body || root;
    if (body.dataset && body.dataset.activeTab === 'read') {
        const readerContent = root.querySelector('.text-reader__content');
        if (readerContent) return readerContent;
    }
    return root.querySelector('.view-container');
}

/**
 * Updates CSS classes on top/bottom buttons based on container scroll position.
 * @param {{ btnTop: Element|null, btnBottom: Element|null }} buttons
 * @param {Element|null} container
 * @param {number} threshold
 */
export function updateScrollFabVisibility(buttons, container, threshold = 120) {
    if (!buttons || !container) {
        if (buttons?.btnTop) buttons.btnTop.classList.remove('scroll-fab-btn--visible');
        if (buttons?.btnBottom) buttons.btnBottom.classList.remove('scroll-fab-btn--visible');
        return;
    }
    const { scrollTop, clientHeight, scrollHeight } = container;
    const { showTop, showBottom } = computeFabVisibility(scrollTop, clientHeight, scrollHeight, threshold);

    if (buttons.btnTop) {
        buttons.btnTop.classList.toggle('scroll-fab-btn--visible', showTop);
    }
    if (buttons.btnBottom) {
        buttons.btnBottom.classList.toggle('scroll-fab-btn--visible', showBottom);
    }
}
