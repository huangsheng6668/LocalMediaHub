// 事件总线：textReader 各子模块间解耦通信。零依赖。
// 设计要点：
//   - on() 返回 unsub 函数，便于主模块 cleanup 统一取消订阅。
//   - emit() 对每个 handler try/catch，单个 handler 抛异常不影响其余。
//   - EVT 常量集中定义事件名，避免拼写错误。
const handlers = new Map();

// 订阅 event，返回取消订阅函数。
export function on(event, handler) {
    if (!handlers.has(event)) handlers.set(event, new Set());
    handlers.get(event).add(handler);
    return () => {
        const set = handlers.get(event);
        if (set) {
            set.delete(handler);
            if (set.size === 0) handlers.delete(event);
        }
    };
}

// 移除指定 handler。
export function off(event, handler) {
    const set = handlers.get(event);
    if (set) {
        set.delete(handler);
        if (set.size === 0) handlers.delete(event);
    }
}

// 发布 event。handler 抛异常被捕获并 console.error，不影响其余订阅者。
export function emit(event, payload) {
    const set = handlers.get(event);
    if (!set) return;
    for (const h of set) {
        try {
            h(payload);
        } catch (e) {
            console.error('[bus] handler error for', event, e);
        }
    }
}

// 事件类型常量。
export const EVT = {
    CHAPTER_CHANGED: 'chapter:changed',
    BOOKMARKS_CHANGED: 'bookmarks:changed',
    SETTINGS_CHANGED: 'settings:changed',
};
