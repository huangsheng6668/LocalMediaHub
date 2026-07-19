// FOUC prevention: set <html data-theme> BEFORE stylesheets apply.
// Loaded via <script src="boot.js"> in <head>, ahead of <link rel="stylesheet">.
// Must NOT be inline — project CSP is script-src 'self' (no 'unsafe-inline').
(function () {
    try {
        var t = localStorage.getItem('chrome_theme');
        if (t !== 'day' && t !== 'night') {
            t = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'night' : 'day';
        }
        document.documentElement.dataset.theme = t;
    } catch (_) {
        document.documentElement.dataset.theme = 'day';
    }
})();
