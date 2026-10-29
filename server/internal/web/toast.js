// Toast Notification System
//
// 2026-09-30 修订：
//   - 数量上限。容器是 position:fixed; bottom:24px，连点「立即扫描媒体」
//     会瞬间堆出十几条并向上溢出视口。超出上限即移除最旧的一条。
//   - 退场改用 class（.toast--leaving）而非内联样式，与项目「动态样式走
//     CSSOM / class」的约定一致，也便于在 CSS 里统一调节动画。
//   - 无障碍由 index.html 的容器属性提供（role="status" aria-live="polite"）。
const MAX_VISIBLE = 3;

export function showToast(message, type = 'info') {
    const container = document.getElementById('toast-container');
    if (!container) return;

    while (container.children.length >= MAX_VISIBLE) {
        container.firstElementChild.remove();
    }

    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.textContent = message;
    container.appendChild(toast);

    setTimeout(() => {
        toast.classList.add('toast--leaving');
        setTimeout(() => toast.remove(), 300);
    }, 3000);
}
