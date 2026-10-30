// 模态框焦点管理（2026-09-30）。
//
// 背景：三个模态框（视频播放器 #modal-video-player、图片灯箱
// #modal-image-preview、令牌输入 #auth-modal）都声明了 role="dialog"
// aria-modal="true"，但此前既不把焦点移入、也不限制 Tab 逃逸、关闭后也不
// 归还焦点 —— 键盘用户打开后 Tab 会跑到背后的侧边栏和主内容区，关闭后焦点
// 丢在 body 上，只能从头重新 Tab 一遍。
//
// 用 inert 而不是手写 focus trap：inert 让整棵背景子树不可聚焦、不可点击、
// 且对辅助技术隐藏，是浏览器原生实现，比逐元素拦截 Tab 更可靠（不必枚举
// 可聚焦元素、也不会漏掉动态插入的节点）。旧浏览器不支持时该赋值静默无效，
// 行为退回修改前的状态，不会更差。
//
// 注意：inert 只加在 .app-container 上。所有 .overlay-modal 都是它的兄弟
// 节点（见 index.html），因此模态框自身不受影响。
//
// 两组 API：
//   openModalFocus / closeModalFocus —— 供用 .active class 切换显示的两个
//     overlay-modal 使用（一站式：记录焦点 + 冻结 + 显示 + 移入 / 反向）。
//   rememberFocus / restoreFocus / freezeBackground / unfreezeBackground ——
//     供 #auth-modal 使用，它的显示由 .hidden class 控制（与 .active 无关），
//     需要自己组合。

let lastFocused = null;

function backgroundRoot() {
    return document.querySelector('.app-container');
}

// 记录当前焦点，供关闭时归还。
export function rememberFocus() {
    lastFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null;
}

// 把焦点还给打开模态框的元素。触发元素可能已被重渲染（例如删除文件后网格
// 重建），所以先确认它仍在文档中。
export function restoreFocus() {
    if (lastFocused && document.contains(lastFocused)) {
        lastFocused.focus();
    }
    lastFocused = null;
}

export function freezeBackground() {
    const bg = backgroundRoot();
    if (bg) bg.inert = true;
}

export function unfreezeBackground() {
    const bg = backgroundRoot();
    if (bg) bg.inert = false;
}

const FOCUSABLE = 'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])';

// 打开模态框：记录来源焦点 → 冻结背景 → 显示 → 移入焦点。
// initialFocusSelector 缺省时取模态框内第一个可聚焦元素。
export function openModalFocus(modal, initialFocusSelector) {
    if (!modal) return;
    rememberFocus();
    freezeBackground();
    modal.classList.add('active');

    const target = (initialFocusSelector && modal.querySelector(initialFocusSelector))
        || modal.querySelector(FOCUSABLE);
    if (target && typeof target.focus === 'function') {
        target.focus();
    }
}

// 关闭模态框：解除背景冻结 → 隐藏 → 把焦点还给打开它的元素。
export function closeModalFocus(modal) {
    if (!modal) return;
    modal.classList.remove('active');
    unfreezeBackground();
    restoreFocus();
}
