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

// Transcode status display helper (P4, 2026-09-03). Pure function: maps the
// GET /api/v1/admin/transcode/status payload into display strings for the
// dashboard 服务信息 card. null/undefined payload (request rejected) maps to
// a degraded placeholder rather than throwing.
const TRANSCODE_ENCODER_LABELS = {
    h264_nvenc: 'NVIDIA NVENC 硬编',
    h264_qsv: 'Intel QSV 硬编',
    h264_amf: 'AMD AMF 硬编',
    libx264: '软件编码 (libx264)',
};

export function formatTranscodeStatus(payload) {
    if (!payload) return { encoder: '状态不可用', sessions: '—' };
    const auto = (payload.probe && payload.probe.auto) || '';
    const encoder = !auto
        ? '未探测（首次转码时自动探测）'
        : (TRANSCODE_ENCODER_LABELS[auto] || auto);
    const active = typeof payload.active === 'number' ? payload.active : 0;
    const max = typeof payload.max_sessions === 'number' ? payload.max_sessions : -1;
    const sessions = max < 0 ? `${active} / 不限` : `${active} / ${max}`;
    return { encoder, sessions };
}
