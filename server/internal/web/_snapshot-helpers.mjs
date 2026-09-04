// 快照测试共享工具：jsdom setup + mock book + reader render 入口。
// 仅供 .test.mjs import；不进浏览器 bundle（以 _ 开头 + 不被 index.html 引用）。
import { JSDOM } from 'jsdom';

// 3 章 + 2 书签的固定 mock book，所有快照测试用它保证可复现。
export const mockBook = {
    title: '测试书',
    path: '/test/book.txt',
    format: 'txt',
    chapters: [
        { title: '第一章 开端', index: 0 },
        { title: '第二章 发展', index: 1 },
        { title: '第三章 结局', index: 2 },
    ],
};

// 在 jsdom 里构造 #view-reader 容器并 stub 掉浏览器 API（rAF / scrollIntoView）。
export function setupJsdom() {
    const dom = new JSDOM('<!DOCTYPE html><html><body><div id="view-reader"></div></body></html>', {
        url: 'http://localhost/',
        pretendToBeVisual: true,
    });
    const { window } = dom;
    // stub requestAnimationFrame（jsdom 不实现）
    window.requestAnimationFrame = (cb) => setTimeout(cb, 0);
    window.cancelAnimationFrame = (id) => clearTimeout(id);
    // stub scrollIntoView（jsdom 不实现）
    window.Element.prototype.scrollIntoView = function () {};
    // 暴露到 global 让模块代码用到的全局可用
    global.window = window;
    global.document = window.document;
    global.requestAnimationFrame = window.requestAnimationFrame;
    global.cancelAnimationFrame = window.cancelAnimationFrame;
    global.localStorage = (() => {
        const store = {};
        return {
            getItem: (k) => (k in store ? store[k] : null),
            setItem: (k, v) => { store[k] = String(v); },
            removeItem: (k) => { delete store[k]; },
            get length() { return Object.keys(store).length; },
            key: (i) => Object.keys(store)[i] ?? null,
            clear: () => { for (const k of Object.keys(store)) delete store[k]; },
        };
    })();
    global.sessionStorage = (() => {
        const store = {};
        return {
            getItem: (k) => (k in store ? store[k] : null),
            setItem: (k, v) => { store[k] = String(v); },
            removeItem: (k) => { delete store[k]; },
            get length() { return Object.keys(store).length; },
            key: (i) => Object.keys(store)[i] ?? null,
            clear: () => { for (const k of Object.keys(store)) delete store[k]; },
        };
    })();
    return { dom, window, document: window.document };
}

// 清理 global，避免测试间状态泄漏。
export function teardownJsdom() {
    delete global.window;
    delete global.document;
    delete global.requestAnimationFrame;
    delete global.cancelAnimationFrame;
    delete global.localStorage;
    delete global.sessionStorage;
}