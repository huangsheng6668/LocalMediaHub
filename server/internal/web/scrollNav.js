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

/**
 * Initializes quick scroll navigation FABs (Top & Bottom buttons).
 * @param {{ root?: Document|Element, threshold?: number }} [opts]
 * @returns {{ update: () => void, cleanup: () => void }}
 */
export function initScrollNav(opts = {}) {
    const root = opts.root || (typeof document !== 'undefined' ? document : null);
    const threshold = typeof opts.threshold === 'number' ? opts.threshold : 120;

    if (!root) {
        return {
            update: () => {},
            cleanup: () => {}
        };
    }

    const btnTop = root.getElementById
        ? root.getElementById('btn-scroll-top')
        : (root.querySelector ? root.querySelector('#btn-scroll-top') : null);
    const btnBottom = root.getElementById
        ? root.getElementById('btn-scroll-bottom')
        : (root.querySelector ? root.querySelector('#btn-scroll-bottom') : null);
    const buttons = { btnTop, btnBottom };

    let currentContainer = null;
    let rafId = null;

    const requestRaf = typeof requestAnimationFrame === 'function'
        ? requestAnimationFrame
        : (fn) => setTimeout(fn, 16);
    const cancelRaf = typeof cancelAnimationFrame === 'function'
        ? cancelAnimationFrame
        : clearTimeout;

    function scheduleVisibilityUpdate() {
        if (rafId !== null) return;
        rafId = requestRaf(() => {
            rafId = null;
            updateScrollFabVisibility(buttons, currentContainer, threshold);
        });
    }

    const onScroll = () => {
        scheduleVisibilityUpdate();
    };

    function rebindContainer() {
        const nextContainer = resolveScrollContainer(root);
        if (nextContainer !== currentContainer) {
            if (currentContainer && typeof currentContainer.removeEventListener === 'function') {
                currentContainer.removeEventListener('scroll', onScroll);
            }
            currentContainer = nextContainer;
            if (currentContainer && typeof currentContainer.addEventListener === 'function') {
                currentContainer.addEventListener('scroll', onScroll, { passive: true });
            }
        }
        updateScrollFabVisibility(buttons, currentContainer, threshold);
    }

    const onTopClick = () => {
        const container = currentContainer || resolveScrollContainer(root);
        if (!container) return;
        if (typeof container.scrollTo === 'function') {
            container.scrollTo({ top: 0, behavior: 'smooth' });
        } else {
            container.scrollTop = 0;
        }
    };

    const onBottomClick = () => {
        const container = currentContainer || resolveScrollContainer(root);
        if (!container) return;
        const targetTop = typeof container.scrollHeight === 'number' ? container.scrollHeight : 0;
        if (typeof container.scrollTo === 'function') {
            container.scrollTo({ top: targetTop, behavior: 'smooth' });
        } else {
            container.scrollTop = targetTop;
        }
    };

    if (btnTop && typeof btnTop.addEventListener === 'function') {
        btnTop.addEventListener('click', onTopClick);
    }
    if (btnBottom && typeof btnBottom.addEventListener === 'function') {
        btnBottom.addEventListener('click', onBottomClick);
    }

    const onWindowChange = () => {
        rebindContainer();
    };

    if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
        window.addEventListener('hashchange', onWindowChange);
        window.addEventListener('resize', onWindowChange);
    }

    let observer = null;
    const Obs = typeof MutationObserver !== 'undefined' ? MutationObserver : null;
    const bodyEl = root.body || (root.nodeType === 1 ? root : null);
    if (Obs && bodyEl) {
        try {
            observer = new Obs((mutations) => {
                const isSelf = mutations.every(m =>
                    m.target === btnTop ||
                    m.target === btnBottom ||
                    (m.target && m.target.id === 'scroll-fab-group')
                );
                if (!isSelf) {
                    rebindContainer();
                }
            });
            observer.observe(bodyEl, {
                childList: true,
                subtree: true,
                attributes: true,
                attributeFilter: ['data-active-tab', 'class', 'hidden']
            });
        } catch {
            // Fallback if environment doesn't support DOM MutationObserver
        }
    }

    // Initial binding
    rebindContainer();

    function cleanup() {
        if (rafId !== null) {
            cancelRaf(rafId);
            rafId = null;
        }
        if (currentContainer && typeof currentContainer.removeEventListener === 'function') {
            currentContainer.removeEventListener('scroll', onScroll);
        }
        if (btnTop && typeof btnTop.removeEventListener === 'function') {
            btnTop.removeEventListener('click', onTopClick);
        }
        if (btnBottom && typeof btnBottom.removeEventListener === 'function') {
            btnBottom.removeEventListener('click', onBottomClick);
        }
        if (typeof window !== 'undefined' && typeof window.removeEventListener === 'function') {
            window.removeEventListener('hashchange', onWindowChange);
            window.removeEventListener('resize', onWindowChange);
        }
        if (observer && typeof observer.disconnect === 'function') {
            observer.disconnect();
            observer = null;
        }
        updateScrollFabVisibility(buttons, null);
    }

    return {
        update: rebindContainer,
        cleanup
    };
}

