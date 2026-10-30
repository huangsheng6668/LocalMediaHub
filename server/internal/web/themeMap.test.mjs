import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const read = (p) => readFileSync(join(here, p), 'utf8');

// The seven reader themes. Kept in sync with theme-parity.test.mjs's
// READER_PRESETS / reader-settings.js.
const READER_PRESETS = ['DAY', 'DAY_BRIGHT', 'EYE_CARE', 'EYE_CARE_GREEN', 'PARCHMENT', 'NIGHT', 'NIGHT_BLACK'];

test('every reader preset maps to a theme actually defined in themes.css', () => {
    const css = read('css/themes.css');
    const defined = new Set([...css.matchAll(/\[data-theme="([a-z_]+)"\]/g)].map((m) => m[1]));
    assert.ok(defined.size >= READER_PRESETS.length, `themes.css defines only ${defined.size} themes`);

    for (const preset of READER_PRESETS) {
        // The whole contract: the CSS theme name is the lower-cased key.
        assert.ok(
            defined.has(preset.toLowerCase()),
            `themes.css has no [data-theme="${preset.toLowerCase()}"] for preset ${preset}`,
        );
    }
});

test('themes.css keeps :root on the day palette so unknown themes degrade', () => {
    // boot.js/app.js now derive the theme name without validating it, which is
    // only safe because :root carries the default palette. If that ever stops
    // being true, an unknown data-theme would render an unstyled page.
    const css = read('css/themes.css');
    assert.match(css, /:root\s*,\s*\n\[data-theme="day"\]\s*\{/, ':root must share the day theme block');
});

test('boot.js and app.js no longer carry a duplicated theme map', () => {
    // Both used to hold a 7-entry {KEY: "css_name"} table that had to be kept in
    // sync by hand. They now derive the name with toLowerCase().
    for (const file of ['boot.js', 'app.js']) {
        const src = read(file);
        assert.ok(
            /toLowerCase\(\)/.test(src),
            `${file} should derive the CSS theme name via toLowerCase()`,
        );
        assert.doesNotMatch(
            src,
            /EYE_CARE_GREEN\s*:/,
            `${file} still contains a hard-coded theme map entry`,
        );
    }
});

test('boot.js stays a synchronous non-module script', () => {
    // It must run before first paint to prevent FOUC, so it cannot become an ES
    // module and it must not be deferred.
    const html = read('index.html');
    assert.match(html, /<script src="boot\.js"><\/script>/, 'boot.js must load synchronously in <head>');
    const boot = read('boot.js');
    assert.doesNotMatch(boot, /^\s*(import|export)\s/m, 'boot.js must not use module syntax');
});
