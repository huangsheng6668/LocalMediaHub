// 纯工具函数，从 app.js 抽出。

// Utility formatting functions
export function formatSize(bytes) {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}

// Encode path segments while keeping literal forward slashes (required for Echo wildcards)
export function encodeRoutePath(path) {
    if (!path) return '';
    return path.replace(/\\/g, '/').split('/').map(encodeURIComponent).join('/');
}

// Unicode-safe Base64 encoding for HTML element IDs
export function safeBtoa(str) {
    try {
        return btoa(unescape(encodeURIComponent(str)));
    } catch (e) {
        return str.replace(/[^a-zA-Z0-9]/g, '_');
    }
}

// Utility: Format seconds into HH:MM:SS or MM:SS
export function formatTime(seconds) {
    if (isNaN(seconds) || seconds === Infinity) return '00:00';
    const hrs = Math.floor(seconds / 3600);
    const mins = Math.floor((seconds % 3600) / 60);
    const secs = Math.floor(seconds % 60);
    
    const formattedMins = mins < 10 ? `0${mins}` : mins;
    const formattedSecs = secs < 10 ? `0${secs}` : secs;
    
    if (hrs > 0) {
        return `${hrs}:${formattedMins}:${formattedSecs}`;
    }
    return `${formattedMins}:${formattedSecs}`;
}
