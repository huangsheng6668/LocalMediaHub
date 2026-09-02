// FOUC prevention: set <html data-theme> BEFORE stylesheets apply.
// Reads reader_settings.theme — the same key readerPrefs.js /
// app.js applyGlobalAppTheme consume — so the pre-paint theme always
// matches the post-boot theme. (The legacy chrome_theme key was never
// written by any code and ignored the user's chosen theme.)
// Non-module script: carries a minimal copy of app.js's theme map.
(function () {
    var MAP = {
        DAY: 'day', DAY_BRIGHT: 'day_bright', EYE_CARE: 'eye_care',
        EYE_CARE_GREEN: 'eye_care_green', PARCHMENT: 'parchment',
        NIGHT: 'night', NIGHT_BLACK: 'night_black'
    };
    function resolve() {
        try {
            var raw = localStorage.getItem('reader_settings');
            var key = raw ? (JSON.parse(raw).theme || 'DAY') : 'DAY';
            if (key === 'AUTO') {
                key = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'NIGHT' : 'DAY';
            }
            return MAP[key] || 'day';
        } catch (_) {
            return 'day';
        }
    }
    document.documentElement.dataset.theme = resolve();
})();
