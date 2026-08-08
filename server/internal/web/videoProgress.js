// 视频播放进度记忆数据层。纯函数 + localStorage。
// 与 reader 的 book_progress: 范式一致；key 用 relative_path（不随服务端目录变化）。
// 所有函数 try/catch：隐私模式 / 配额满 / JSON 损坏时静默，绝不影响播放。

const PREFIX = 'video_progress:';

function key(relPath) {
    return PREFIX + relPath;
}

// 是否看完：position/duration >= 0.95 且 duration 有效。
export function isCompleted(positionMs, durationMs) {
    if (!durationMs || durationMs <= 0) return false;
    return positionMs / durationMs >= 0.95;
}

export function saveProgress(relPath, { positionMs, durationMs }) {
    try {
        localStorage.setItem(key(relPath), JSON.stringify({
            positionMs,
            durationMs,
            updatedAt: Date.now(),
        }));
    } catch (e) {
        // 隐私模式 / 配额满：静默失败。
    }
}

export function loadProgress(relPath) {
    try {
        const raw = localStorage.getItem(key(relPath));
        if (!raw) return null;
        const parsed = JSON.parse(raw);
        if (!parsed || typeof parsed.positionMs !== 'number') return null;
        return parsed;
    } catch (e) {
        return null;
    }
}

export function clearProgress(relPath) {
    try {
        localStorage.removeItem(key(relPath));
    } catch (e) {
        // 静默
    }
}
