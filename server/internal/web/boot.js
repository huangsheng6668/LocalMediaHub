// FOUC prevention: set <html data-theme> BEFORE stylesheets apply.
// Reads reader_settings.theme — the same key readerPrefs.js /
// app.js applyGlobalAppTheme consume — so the pre-paint theme always
// matches the post-boot theme. (The legacy chrome_theme key was never
// written by any code and ignored the user's chosen theme.)
//
// This is a non-module script so it can run synchronously before first paint.
// It used to carry a copy of app.js's theme map; the map was pure redundancy
// because every CSS theme name is just the lower-cased preference key
// (NIGHT_BLACK -> night_black). Both sides now derive the name instead of
// repeating a 7-entry table that could silently drift when a theme is added.
// themes.css keeps `:root` on the day palette, so an unrecognised key degrades
// to the day colours rather than leaving the page unstyled.
(function () {
    function resolve() {
        try {
            var raw = localStorage.getItem('reader_settings');
            var key = raw ? (JSON.parse(raw).theme || 'DAY') : 'DAY';
            if (key === 'AUTO') {
                key = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'NIGHT' : 'DAY';
            }
            return String(key).toLowerCase();
        } catch (_) {
            return 'day';
        }
    }
    document.documentElement.dataset.theme = resolve();
})();
