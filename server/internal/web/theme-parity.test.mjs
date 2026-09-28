// 跨端主题对称守护（spec 2026-09-28-ui-polish-alignment §3/§5）：
// 同时解析 Web 源与 Android 源，断言逐字对称。任一端漂移 → 本文件红。
// 解析失败（源文件格式变化）→ fail 并给出可操作信息，绝不静默 skip。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import * as readerPrefs from './readerPrefs.js';

const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8');
const READER_SETTINGS_KT = '../../../android/app/src/main/java/com/juziss/localmediahub/data/ReaderSettings.kt';
const THEME_KT = '../../../android/app/src/main/java/com/juziss/localmediahub/ui/theme/Theme.kt';
const COLOR_TOKENS_KT = '../../../android/app/src/main/java/com/juziss/localmediahub/ui/theme/ColorTokens.kt';
const THEMES_CSS = './css/themes.css';

const hexOf = (kotlinColor) => '#' + kotlinColor.toUpperCase(); // '0xFFxxxxxx' → '#XXXXXX'

/** 解析 ReaderSettings.kt 的 ReaderTheme enum：NAME(bg = Color(0x..), ...)。
 * 末位 enum entry 以 `;` 收尾（`);`），其余为 `),` — 两种都要吃。 */
function parseKotlinReaderThemes(src) {
    const themes = {};
    const re = /^ {4}([A-Z_]+)\($([\s\S]*?)^ {4}\)[;,]?$/gm;
    let m;
    while ((m = re.exec(src)) !== null) {
        const body = m[2];
        const get = (f) => {
            const fm = body.match(new RegExp(`${f}\\s*=\\s*Color\\(0x([0-9A-Fa-f]{8})\\)`));
            return fm ? hexOf(fm[1].slice(2)) : null; // null = Transparent（AUTO/CUSTOM）
        };
        themes[m[1]] = { bg: get('bg'), fg: get('fg'), chromeBg: get('chromeBg'),
            chromeFg: get('chromeFg'), muted: get('muted'), border: get('border') };
    }
    return themes;
}

/** 解析 Theme.kt：val XColorScheme = lightColorScheme(field = Color(0x..), ...) */
function parseSchemes(src) {
    const schemes = {};
    const re = /val (\w+ColorScheme) = (?:dark|light)ColorScheme\(([\s\S]*?)\n\)/g;
    let m;
    while ((m = re.exec(src)) !== null) {
        const fields = {};
        let f;
        const fr = /(\w+)\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)/g;
        while ((f = fr.exec(m[2])) !== null) fields[f[1]] = hexOf(f[2].slice(2));
        schemes[m[1]] = fields;
    }
    return schemes;
}

/** 解析 themes.css：[data-theme="x"] { --token: #hex; } */
function parseCssThemes(src) {
    const out = {};
    const re = /\[data-theme="(\w+)"\]\s*\{([^}]*)\}/g;
    let m;
    while ((m = re.exec(src)) !== null) {
        const vars = {};
        let v;
        const vr = /--([\w-]+):\s*(#[0-9A-Fa-f]{6})\b/g;
        while ((v = vr.exec(m[2])) !== null) vars[v[1]] = v[2].toUpperCase();
        out[m[1]] = vars;
    }
    return out;
}

/** 解析 ColorTokens.kt object X { val Name: Color = Color(0x..) } */
function parseTokenObject(src, objectName) {
    const start = src.indexOf(`object ${objectName}`);
    assert.ok(start >= 0, `${objectName} not found in ColorTokens.kt`);
    const body = src.slice(start, src.indexOf('}', start));
    const out = {};
    let m;
    const re = /val (\w+):\s*Color\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)/g;
    while ((m = re.exec(body)) !== null) out[m[1]] = hexOf(m[2].slice(2));
    return out;
}

const READER_PRESETS = ['DAY', 'DAY_BRIGHT', 'EYE_CARE', 'EYE_CARE_GREEN', 'PARCHMENT', 'NIGHT', 'NIGHT_BLACK'];

test('reader theme hex: web THEME_PRESETS === android ReaderTheme (7 presets x 6 fields)', () => {
    const android = parseKotlinReaderThemes(read(READER_SETTINGS_KT));
    for (const k of [...READER_PRESETS, 'AUTO', 'CUSTOM']) {
        assert.ok(android[k], `ReaderTheme enum entry ${k} not parsed — check ReaderSettings.kt formatting`);
    }
    for (const k of READER_PRESETS) {
        for (const f of ['bg', 'fg', 'chromeBg', 'chromeFg', 'muted', 'border']) {
            assert.equal(android[k][f], readerPrefs.THEME_PRESETS[k][f],
                `reader ${k}.${f}: android=${android[k][f]} web=${readerPrefs.THEME_PRESETS[k][f]}`);
        }
    }
});

const SCHEME_TO_THEME = {
    LightColorScheme: 'day', DayBrightColorScheme: 'day_bright',
    EyeCareColorScheme: 'eye_care', EyeCareGreenColorScheme: 'eye_care_green',
    ParchmentColorScheme: 'parchment', DarkColorScheme: 'night', NightBlackColorScheme: 'night_black',
};
const FIELD_TO_TOKEN = {
    primary: 'action-ink', secondary: 'accent', tertiary: 'accent-hover', error: 'error',
    background: 'surface-app', surface: 'surface-card', outline: 'border-subtle', onBackground: 'text-primary',
};

test('chrome token: android ColorScheme === web [data-theme] (8 fields x 7 themes)', () => {
    const schemes = parseSchemes(read(THEME_KT));
    const css = parseCssThemes(read(THEMES_CSS));
    for (const [scheme, theme] of Object.entries(SCHEME_TO_THEME)) {
        assert.ok(schemes[scheme], `${scheme} not parsed — check Theme.kt formatting`);
        assert.ok(css[theme], `[data-theme="${theme}"] not parsed from themes.css`);
        for (const [field, token] of Object.entries(FIELD_TO_TOKEN)) {
            assert.ok(schemes[scheme][field], `${scheme}.${field} missing`);
            assert.ok(css[theme][token], `[data-theme="${theme}"] --${token} missing`);
            assert.equal(schemes[scheme][field], css[theme][token],
                `${scheme}.${field} (${schemes[scheme][field]}) != web --${token} (${css[theme][token]})`);
        }
    }
});

test('chrome token: PrimaryText === web --accent-text', () => {
    const pt = parseTokenObject(read(COLOR_TOKENS_KT), 'PrimaryText');
    const css = parseCssThemes(read(THEMES_CSS));
    const nameToTheme = { Light: 'day', DayBright: 'day_bright', EyeCare: 'eye_care',
        EyeCareGreen: 'eye_care_green', Parchment: 'parchment', Dark: 'night', NightBlack: 'night_black' };
    for (const [name, theme] of Object.entries(nameToTheme)) {
        assert.ok(pt[name], `PrimaryText.${name} missing`);
        assert.equal(pt[name], css[theme]['accent-text'], `PrimaryText.${name} != ${theme} --accent-text`);
    }
});

test('theme labels: web THEME_LABELS / THEME_OPTIONS === android ReaderTheme.label', () => {
    const src = read(READER_SETTINGS_KT);
    const labels = {};
    let m;
    const re = /^ {4}([A-Z_]+)\($([\s\S]*?)^ {4}\)[;,]$/gm;
    while ((m = re.exec(src)) !== null) {
        const lm = m[2].match(/label\s*=\s*"([^"]+)"/);
        if (lm) labels[m[1]] = lm[1];
    }
    assert.equal(Object.keys(labels).length, 9, 'expected 9 ReaderTheme entries with labels');

    // Web chrome 网格标签（settings.js）：8 个共有 key 必须与 Android 逐字一致。
    for (const [k, v] of Object.entries(readerPrefs.THEME_LABELS)) {
        assert.equal(labels[k], v, `THEME_LABELS.${k}: android=${labels[k]} web=${v}`);
    }
    assert.ok(!('CUSTOM' in readerPrefs.THEME_LABELS),
        'chrome grid has no CUSTOM theme — keep it out of THEME_LABELS (spec §4)');

    // reader-settings.js 的阅读器主题表（含 CUSTOM）：全部 9 项与 Android 一致。
    const optsSrc = read('./reader-settings.js');
    const opts = {};
    let o;
    const ore = /\['([A-Z_]+)',\s*'([^']+)'\]/g;
    while ((o = ore.exec(optsSrc)) !== null) opts[o[1]] = o[2];
    for (const k of Object.keys(labels)) {
        assert.equal(opts[k], labels[k], `reader-settings THEME_OPTIONS.${k}: android=${labels[k]} options=${opts[k]}`);
    }
});

test('web sources contain no inline style attributes (CSP style-src self)', () => {
    // 分隔符归一：recursive readdir 在 win32 返回反斜杠路径，vendor/node_modules 排除必须双兼容。
    const norm = (f) => f.replace(/\\/g, '/');
    const skip = (f) => f.endsWith('.test.mjs') || norm(f).includes('vendor/')
        || norm(f).includes('node_modules/') || f.includes('_snapshot-helpers');
    const files = readdirSync(new URL('./', import.meta.url), { recursive: true, encoding: 'utf8' })
        .filter((f) => /\.(js|html)$/.test(f) && !skip(f));
    assert.ok(files.length > 20, `scan found suspiciously few files (${files.length}) — check directory`);
    for (const f of files) {
        const src = read('./' + norm(f));
        assert.ok(!/style="/.test(src), `${f} contains inline style=" — move to a CSS class (CSP)`);
    }
});
