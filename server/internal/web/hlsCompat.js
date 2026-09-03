// HLS playback compatibility helpers (spec 2026-09-03-hls-transcode-b2).
// Pure functions: no DOM side effects, fully unit-testable under jsdom.

export const HLS_MIME = 'application/vnd.apple.mpegurl';

// File extensions whose CONTAINERS browsers cannot demux natively even when
// the codec is fine — the web player auto-routes these to transcoded output.
const NEEDS_TRANSCODE_EXTS = ['.ts', '.mkv', '.avi', '.wmv', '.flv'];

export function needsTranscodeExt(fileName) {
    const dot = fileName.lastIndexOf('.');
    if (dot < 0) return false;
    return NEEDS_TRANSCODE_EXTS.includes(fileName.substring(dot).toLowerCase());
}

export function buildHlsPlaylistUrl(apiBase, path) {
    return `${apiBase}/api/v1/media/hls/playlist?path=${encodeURIComponent(path)}`;
}

// resolveHlsStrategy picks the HLS playback route:
//   'hlsjs'  — hls.js over MSE (best: xhrSetup can inject auth headers on
//              every request, including segments)
//   'native' — browser-native HLS (Safari); segments cannot carry auth
//              headers, so token mode may 401 on segments (known edge,
//              documented in the spec)
//   'none'   — no HLS route available; caller falls back to the legacy
//              single-pipe fMP4 transcode path (zero regression)
export function resolveHlsStrategy(video) {
    if (typeof window !== 'undefined' &&
        window.Hls && typeof window.Hls.isSupported === 'function' &&
        window.Hls.isSupported()) {
        return 'hlsjs';
    }
    if (video && typeof video.canPlayType === 'function' && video.canPlayType(HLS_MIME)) {
        return 'native';
    }
    return 'none';
}
