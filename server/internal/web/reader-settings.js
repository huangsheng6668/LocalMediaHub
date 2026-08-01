// 阅读设置 dialog（Task 7）：主题/字体/字号/行距/宽度/沉浸/滚动模式。
// 从 textReader.js 提取。dialog 由 <dialog> 元素承载，change 事件冒泡统一处理。
// 设计要点：
//   - 读当前值用 state.settings（主模块 render 时填充），避免每次 getSettings() 反序列化。
//   - 写入走 readerPrefs.saveSettings（localStorage 持久化 + window storage event），
//     随后 emit(EVT.SETTINGS_CHANGED, { settings }) 让其它模块（autoscroll/progress/主 UI）反应。
//   - 返回 { open, dispose }：open 调 showModal；dispose 解绑 listener 并从 DOM 移除 dialog。
//   - 所有 innerHTML 模板均为纯字面量 + 硬编码 enum map（FONT_FAMILIES 集合等），无用户数据插值。
import { state } from './reader-state.js';
import * as readerPrefs from './readerPrefs.js';
import { emit, EVT } from './bus.js';

// 字体 radio 选项（FONT_FAMILIES 的 key 子集，标签本地化）。
const FONT_OPTIONS = [
    ['SYSTEM', '无衬线'],
    ['SERIF', '宋体'],
    ['KAITI', '文楷'],
    ['HEITI', '黑体'],
    ['MONO', '等宽'],
];

// 主题 radio 选项（THEME_PRESETS 的 key + AUTO 跟随系统）。
const THEME_OPTIONS = [
    ['DAY', '日间·纸白'],
    ['DAY_BRIGHT', '日间·亮白'],
    ['EYE_CARE', '护眼·米黄'],
    ['EYE_CARE_GREEN', '护眼·豆沙绿'],
    ['PARCHMENT', '羊皮纸'],
    ['NIGHT', '夜间·深空'],
    ['NIGHT_BLACK', '夜间·纯黑'],
    ['AUTO', '跟随系统'],
    ['CUSTOM', '自定义'],
];

const READING_MODE_OPTIONS = [
    ['chapter', '分章'],
    ['scroll', '全文滚动'],
];

// 翻页动画 radio 选项（PAGE_TURN_STYLES 的 key 子集，标签本地化）。
// 仅 chapter 模式生效；scroll 模式下置灰。
const PAGE_TURN_OPTIONS = [
    ['NONE', '无'],
    ['COVER', '覆盖'],
    ['SIMULATION', '仿真'],
    ['DRAG', '拖动'],
];

// 渲染设置 dialog 到 container，返回控制器 { open, dispose }。
// settings 变更后 emit SETTINGS_CHANGED，payload 为最新 settings 快照。
export function renderSettings(container) {
    const dialog = document.createElement('dialog');
    dialog.id = 'reader-settings-dialog';
    // XSS-SAFE: pure-literal template; the ${[...].map(...)} blocks emit only hardcoded enum values
    dialog.innerHTML = `
        <form method="dialog">
            <header class="reader-settings__header">
                <h3>阅读设置</h3>
                <button type="submit" class="reader-settings__close" aria-label="关闭">×</button>
            </header>
            <div class="reader-settings__body">

                <section class="reader-settings__group">
                    <h4>外观</h4>

                    <div class="reader-settings__row">
                        <span>字体</span>
                        <div class="reader-settings__font-row">
                            ${FONT_OPTIONS.map(([v, label]) =>
                                `<label><input type="radio" name="fontFamily" value="${v}"> ${label}</label>`
                            ).join('')}
                        </div>
                    </div>

                    <div class="reader-settings__theme-grid">
                        ${THEME_OPTIONS.map(([v, label]) =>
                            `<label class="reader-settings__theme-opt">
                                <input type="radio" name="theme" value="${v}">
                                <span class="reader-settings__theme-swatch" data-theme="${v}"></span>
                                <span class="reader-settings__theme-label">${label}</span>
                            </label>`
                        ).join('')}
                    </div>
                </section>

                <section class="reader-settings__group">
                    <h4>字号与行距</h4>
                    <label class="reader-settings__slider-row">
                        <span>字号</span>
                        <input type="range" name="fontSizeSlider" min="12" max="28" step="1" value="16">
                        <output data-bind="fontSizeLabel">16 px</output>
                    </label>
                    <label class="reader-settings__slider-row">
                        <span>行距</span>
                        <input type="range" name="lineHeightSlider" min="1.3" max="2.5" step="0.1" value="1.8">
                        <output data-bind="lineHeightLabel">1.8</output>
                    </label>
                    <label class="reader-settings__slider-row">
                        <span>宽度</span>
                        <input type="range" name="contentWidthSlider" min="600" max="1400" step="10" value="720">
                        <output data-bind="contentWidthLabel">720 px</output>
                    </label>
                    <label class="reader-settings__slider-row">
                        <span>字间距</span>
                        <input type="range" name="letterSpacingSlider" min="0" max="1" step="0.05" value="0">
                        <output data-bind="letterSpacingLabel">0.00 em</output>
                    </label>
                </section>

                <section class="reader-settings__group">
                    <h4>段落</h4>
                    <label class="reader-settings__toggle-row">
                        <span>首行缩进</span>
                        <input type="checkbox" name="firstLineIndent" checked>
                    </label>
                    <label class="reader-settings__toggle-row">
                        <span>段间距</span>
                        <input type="checkbox" name="paragraphSpacing">
                    </label>
                </section>

                <section class="reader-settings__group">
                    <h4>行为</h4>
                    <div class="reader-settings__row" style="margin-bottom: 8px;">
                        <span>阅读模式</span>
                        <div class="reader-settings__font-row">
                            ${READING_MODE_OPTIONS.map(([v, label]) =>
                                `<label><input type="radio" name="readingMode" value="${v}"> ${label}</label>`
                            ).join('')}
                        </div>
                    </div>
                    <div class="reader-settings__row" style="margin-bottom: 8px;">
                        <span>翻页动画</span>
                        <div class="reader-settings__font-row" id="pageTurnRow">
                            ${PAGE_TURN_OPTIONS.map(([v, label]) =>
                                `<label><input type="radio" name="pageTurnStyle" value="${v}"> ${label}</label>`
                            ).join('')}
                        </div>
                    </div>
                    <label class="reader-settings__toggle-row">
                        <span>沉浸模式</span>
                        <input type="checkbox" name="immersiveMode">
                    </label>
                    <label class="reader-settings__slider-row">
                        <span>自动滚动速度</span>
                        <input type="range" name="autoScrollSpeed" min="1" max="10" value="5">
                        <output data-bind="speedLabel">5</output>
                    </label>
                </section>

                <section class="reader-settings__group reader-settings__custom-colors" hidden>
                    <h4>自定义颜色</h4>
                    <label class="reader-settings__color-row">
                        <span>背景</span>
                        <input type="color" name="customBg" value="#FAF8F3">
                        <output data-bind="customBgLabel">#FAF8F3</output>
                    </label>
                    <label class="reader-settings__color-row">
                        <span>正文</span>
                        <input type="color" name="customFg" value="#2B2B2B">
                        <output data-bind="customFgLabel">#2B2B2B</output>
                    </label>
                    <label class="reader-settings__color-row">
                        <span>次要</span>
                        <input type="color" name="customMuted" value="#7A7A78">
                        <output data-bind="customMutedLabel">#7A7A78</output>
                    </label>
                </section>

            </div>
        </form>
    `;
    container.appendChild(dialog);

    // 把当前 settings 同步到 dialog 控件（radio/checkbox/slider 的 checked/value）。
    function syncControlsFromSettings() {
        const s = state.settings || readerPrefs.getSettings();
        const setRadio = (name, val) => {
            const el = dialog.querySelector(`input[name="${name}"][value="${val}"]`);
            if (el) el.checked = true;
        };
        setRadio('fontFamily', s.fontFamily);
        setRadio('theme', s.theme);
        setRadio('readingMode', s.readingMode);

        const fontSizeSlider = dialog.querySelector('input[name="fontSizeSlider"]');
        if (fontSizeSlider) fontSizeSlider.value = s.fontSize;
        const fontSizeLabel = dialog.querySelector('[data-bind="fontSizeLabel"]');
        if (fontSizeLabel) fontSizeLabel.textContent = s.fontSize + ' px';

        const lhSlider = dialog.querySelector('input[name="lineHeightSlider"]');
        if (lhSlider) lhSlider.value = s.lineHeight;
        const lhLabel = dialog.querySelector('[data-bind="lineHeightLabel"]');
        if (lhLabel) lhLabel.textContent = s.lineHeight.toFixed(1);

        const cwSlider = dialog.querySelector('input[name="contentWidthSlider"]');
        if (cwSlider) cwSlider.value = s.contentWidth;
        const cwLabel = dialog.querySelector('[data-bind="contentWidthLabel"]');
        if (cwLabel) cwLabel.textContent = s.contentWidth + ' px';

        const indent = dialog.querySelector('input[name="firstLineIndent"]');
        if (indent) indent.checked = s.firstLineIndent;
        const gap = dialog.querySelector('input[name="paragraphSpacing"]');
        if (gap) gap.checked = s.paragraphSpacing;
        const immersive = dialog.querySelector('input[name="immersiveMode"]');
        if (immersive) immersive.checked = s.immersiveMode;

        const speedSlider = dialog.querySelector('input[name="autoScrollSpeed"]');
        if (speedSlider) speedSlider.value = s.autoScrollSpeed;
        const speedLabel = dialog.querySelector('[data-bind="speedLabel"]');
        if (speedLabel) speedLabel.textContent = s.autoScrollSpeed;

        const lsSlider = dialog.querySelector('input[name="letterSpacingSlider"]');
        if (lsSlider) lsSlider.value = String(s.letterSpacing);
        const lsLabel = dialog.querySelector('[data-bind="letterSpacingLabel"]');
        if (lsLabel) lsLabel.textContent = s.letterSpacing.toFixed(2) + ' em';
        ['customBg', 'customFg', 'customMuted'].forEach((name) => {
            const input = dialog.querySelector(`input[name="${name}"]`);
            if (input) input.value = s[name] || '#000000';
            const out = dialog.querySelector(`[data-bind="${name}Label"]`);
            if (out) out.textContent = s[name] || '未设置';
        });
        const customSection = dialog.querySelector('.reader-settings__custom-colors');
        if (customSection) customSection.hidden = s.theme !== 'CUSTOM';

        // 翻页动画仅 chapter 模式可用；scroll 模式置灰（无翻页语义）。
        setRadio('pageTurnStyle', s.pageTurnStyle);
        const isScroll = s.readingMode === 'scroll';
        dialog.querySelectorAll('input[name="pageTurnStyle"]').forEach((r) => { r.disabled = isScroll; });
    }
    syncControlsFromSettings();

    // 持久化 + 广播。所有 change 事件经此路径：写 localStorage，更新 state.settings 缓存，
    // 再 emit SETTINGS_CHANGED 让 autoscroll/progress/主 UI 自更新。
    function saveAndEmit(partial) {
        readerPrefs.saveSettings(partial);
        state.settings = readerPrefs.getSettings();
        emit(EVT.SETTINGS_CHANGED, { settings: state.settings });
    }

    // change 事件冒泡到 dialog 统一处理，避免给每个 input 单独绑 listener。
    function onChange(e) {
        const t = e.target;
        if (!t.name) return;
        if (t.name === 'fontSizeSlider') {
            saveAndEmit({ fontSize: parseInt(t.value, 10) });
        } else if (t.name === 'lineHeightSlider') {
            saveAndEmit({ lineHeight: parseFloat(t.value) });
        } else if (t.name === 'contentWidthSlider') {
            saveAndEmit({ contentWidth: parseInt(t.value, 10) });
        } else if (t.name === 'firstLineIndent' || t.name === 'paragraphSpacing' || t.name === 'immersiveMode') {
            saveAndEmit({ [t.name]: t.checked });
        } else if (t.name === 'fontFamily') {
            saveAndEmit({ fontFamily: t.value });
        } else if (t.name === 'autoScrollSpeed') {
            saveAndEmit({ autoScrollSpeed: parseInt(t.value, 10) });
        } else if (t.name === 'letterSpacingSlider') {
            saveAndEmit({ letterSpacing: parseFloat(t.value) });
        } else if (t.name === 'theme') {
            saveAndEmit({ theme: t.value });
            const customSection = dialog.querySelector('.reader-settings__custom-colors');
            if (customSection) customSection.hidden = t.value !== 'CUSTOM';
        } else if (t.name === 'readingMode') {
            saveAndEmit({ readingMode: t.value });
            const isScroll = t.value === 'scroll';
            dialog.querySelectorAll('input[name="pageTurnStyle"]').forEach((r) => { r.disabled = isScroll; });
        } else {
            saveAndEmit({ [t.name]: t.value });
        }
    }
    dialog.addEventListener('change', onChange);

    function open() {
        syncControlsFromSettings();
        if (typeof dialog.showModal === 'function') {
            dialog.showModal();
        } else {
            dialog.open = true;
        }
    }

    function dispose() {
        dialog.removeEventListener('change', onChange);
        if (dialog.parentNode) dialog.parentNode.removeChild(dialog);
    }

    return { open, dispose };
}
