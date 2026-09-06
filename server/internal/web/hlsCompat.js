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

export function buildHlsPlaylistUrl(apiBase, path, startSec) {
    let url = `${apiBase}/api/v1/media/hls/playlist?path=${encodeURIComponent(path)}`;
    // Seek anchor (spec 2026-09-06-hls-seek-restart): the server re-anchors
    // the transcode at this offset so positions not yet transcoded play
    // immediately instead of clamping to the live edge.
    if (Number.isFinite(startSec) && startSec > 0) {
        url += `&start=${Math.floor(startSec)}`;
    }
    return url;
}

export const HLS_SEEK_MARGIN = 0;

// needsHlsRestart reports whether a progress-bar drag to targetTime (on the
// video's ABSOLUTE timeline) can be served by the current session, or needs
// the transcode re-anchored at the target. offsetSec is the current
// session's start anchor; seekableEnd is video.seekable's end (or null when
// unknown).
export function needsHlsRestart(targetTime, offsetSec, seekableEnd) {
    const rel = targetTime - (offsetSec || 0);
    // Before the current anchor (with 0.5s tolerance): definitely needs restart.
    if (rel < -0.5) return true;
    // Unknown seekable range: if not close to the anchor, re-anchor to prevent
    // setting video.currentTime into an unbuffered void.
    if (seekableEnd == null || !isFinite(seekableEnd) || seekableEnd <= 0) {
        return rel > 2;
    }
    // Beyond the current seekable edge: needs restart.
    return rel > seekableEnd;
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
