# 双端 UI 打磨与对齐 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Ink Editorial 合并后的双端主题 token 收敛为逐字对称(Web 为真值),用 Web 单向守护测试钉住 reader hex / chrome token / label 三组对称性,并补齐 Web 关键控件 aria 与 inline-style 守护。

**Architecture:** Android `Theme.kt`/`ColorTokens.kt` 向 `themes.css` 对齐(含新增 `DayBrightColorScheme`);`server/internal/web/theme-parity.test.mjs`(node:test,纯文件解析)同时解析 Web JS/CSS 与 Android Kotlin 源文件断言一致,双向漂移都会红。aria 与标签为纯增量文案改动。

**Tech Stack:** Kotlin + Compose Material3 / 原生 CSS + ES module / node:test + jsdom / JUnit + Robolectric

**Spec:** `docs/superpowers/specs/2026-09-28-ui-polish-alignment-design.md`(数值真值表在其 §2.2/§2.3)

## Global Constraints

- 不改交互逻辑/数据流;色值真值 = `server/internal/web/css/themes.css` 现值,spec §2.2 表逐字引用
- 守护测试解析失败必须 fail(不 skip、不豁免静默);路径:测试文件位于 `server/internal/web/`,Android 源用 `../../../android/...` 相对 `import.meta.url`
- Web 规则:零 inline `style="` 属性;`innerHTML` 用法同行或上一行必须有 `// XSS-SAFE:` 注释或 `escapeHtml()`;测试文件用 `.test.mjs`,import 路径带 `.js` 扩展名
- 每 task 一 commit,Conventional Commits(scope: `android` / `web` / `docs`)
- 验收命令(最终):`cd server/internal/web && node --test`;`cd tools/xsscheck && go run . ../../server/internal/web`;`cd android && ./gradlew testDebugUnitTest assembleDebug`
- shell 为 bash(Unix 语法);测试从 `server/internal/web` 目录启动时相对路径才可靠,plan 中命令均带 `cd`

---

### Task 1: Android 色值收敛 + DayBright + 跨端守护测试(reader hex / chrome token)

**Files:**
- Create: `server/internal/web/theme-parity.test.mjs`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/theme/Theme.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/theme/ColorTokens.kt`
- Modify: `android/app/src/test/java/com/juziss/localmediahub/ui/theme/ThemeColorSchemeTest.kt`
- Modify: `android/app/src/test/java/com/juziss/localmediahub/ui/theme/PrimaryTextTokenTest.kt`(执行时打开核对,断言 NightBlack 处更新)

**Interfaces:**
- Produces: `theme-parity.test.mjs` 导出零接口(纯 test 文件);内部 helper `parseKotlinReaderThemes(src)`、`parseSchemes(src)`、`parseCssThemes(src)`、`parsePrimaryText(src)`(Task 2 复用其中的 Kotlin 解析风格,直接在同文件追加 test)
- Produces: `Theme.kt` 新增 `private val DayBrightColorScheme`;`ColorTokens.kt` 新增 `PrimaryText.DayBright`、`OutlineSoft.DayBright`

- [ ] **Step 1: 写守护测试(预期 chrome 部分红)**

创建 `server/internal/web/theme-parity.test.mjs`,完整内容:

```js
// 跨端主题对称守护（spec 2026-09-28-ui-polish-alignment §3/§5）：
// 同时解析 Web 源与 Android 源，断言逐字对称。任一端漂移 → 本文件红。
// 解析失败（源文件格式变化）→ fail 并给出可操作信息，绝不静默 skip。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import * as readerPrefs from './readerPrefs.js';

const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8');
const READER_SETTINGS_KT = '../../../android/app/src/main/java/com/juziss/localmediahub/data/ReaderSettings.kt';
const THEME_KT = '../../../android/app/src/main/java/com/juziss/localmediahub/ui/theme/Theme.kt';
const COLOR_TOKENS_KT = '../../../android/app/src/main/java/com/juziss/localmediahub/ui/theme/ColorTokens.kt';
const THEMES_CSS = './css/themes.css';

const hexOf = (kotlinColor) => '#' + kotlinColor.toUpperCase(); // '0xFFxxxxxx' → '#XXXXXX'

/** 解析 ReaderSettings.kt 的 ReaderTheme enum：NAME(bg = Color(0x..), ...) */
function parseKotlinReaderThemes(src) {
    const themes = {};
    const re = /^ {4}([A-Z_]+)\($([\s\S]*?)^ {4}\),?$/gm;
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
```

- [ ] **Step 2: 跑测试确认按预期红**

```bash
cd server/internal/web && node --test theme-parity.test.mjs
```
Expected: reader hex test **PASS**(当前已对称);chrome token test **FAIL**(Light.primary=#26282E ≠ #0A0A0A 等十余项);PrimaryText test **FAIL**(NightBlack #E7E9EE ≠ #A3CDAF)。失败信息应逐字段列出差异——这就是本 task 的改动清单。

- [ ] **Step 3: 修改 `Theme.kt`**

按 spec §2.2/§2.3(全部为 Web 现值):
- `LightColorScheme`:`primary = Color(0xFF0A0A0A)`(原 #26282E),新增 `error = Color(0xFFDC2626)`、`onError = Color.White`
- 新增 `DayBrightColorScheme`(插在 LightColorScheme 之后):
```kotlin
private val DayBrightColorScheme = lightColorScheme(
    primary = Color(0xFF0A0A0A),
    secondary = Color(0xFF3D6B4F),
    tertiary = Color(0xFF2F5640),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF7F7F4),
    primaryContainer = Color(0xFFE9E9E4),
    secondaryContainer = Color(0xFFE4EDE6),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF52575E),
    outline = Color(0xFFE7E7E2),
    outlineVariant = Color(0xFFE7E7E2),
    error = Color(0xFFDC2626),
    onError = Color.White,
)
```
- `EyeCareColorScheme`:`primary = Color(0xFF26211A)`、`secondary = Color(0xFF4A6B52)`、`tertiary = Color(0xFF3C5843)`,新增 `error = Color(0xFFC0392B)`、`onError = Color.White`
- `EyeCareGreenColorScheme`:`primary = Color(0xFF17211A)`、`secondary = Color(0xFF33593C)`、`tertiary = Color(0xFF2A4831)`,新增 `error = Color(0xFFC0392B)`、`onError = Color.White`
- `ParchmentColorScheme`:`primary = Color(0xFF241D14)`、`secondary = Color(0xFF4E6B44)`、`tertiary = Color(0xFF3F5837)`,新增 `error = Color(0xFFC0392B)`、`onError = Color.White`
- `DarkColorScheme`:新增 `error = Color(0xFFF87171)`、`onError = Color(0xFF0F172A)`
- `NightBlackColorScheme`:`secondary = Color(0xFF8FBF9F)`(原 #9BA1AC)、`tertiary = Color(0xFFA3CDAF)`(原 #74787F),新增 `error = Color(0xFFF87171)`、`onError = Color(0xFF0F172A)`
- `LocalMediaHubTheme` 的 when 分支:`"DAY" -> Triple(LightColorScheme, OutlineSoft.Light, PrimaryText.Light)` 与 `"DAY_BRIGHT" -> Triple(DayBrightColorScheme, OutlineSoft.DayBright, PrimaryText.DayBright)`(拆开原合并分支 `"DAY", "DAY_BRIGHT" ->`)

- [ ] **Step 4: 修改 `ColorTokens.kt`**

- `object PrimaryText` 内新增 `val DayBright: Color = Color(0xFF2F5640)  // = day --accent-text`
- `PrimaryText.NightBlack`:`Color(0xFFE7E9EE)` → `Color(0xFFA3CDAF)  // = night_black --accent-text`
- `object OutlineSoft` 内新增 `val DayBright: Color = Color(0xFFE7E7E2)  // = day_bright --border-subtle`

- [ ] **Step 5: 更新 Android 单测断言**

`ThemeColorSchemeTest.kt`:
- `day_theme_uses_ink_primary_and_paper_bg`:`primary` 断言改 `Color(0xFF0A0A0A)`
- `eye_care_theme_keeps_own_primary_but_gets_outline_soft`:重命名为 `eye_care_theme_uses_ink_primary_and_moss_accent`,`primary` 断言改 `Color(0xFF26211A)`,追加 `assertEquals(Color(0xFF4A6B52), schemes.single().secondary)`
- 新增测试(照抄现有 style):
```kotlin
@Test
fun day_bright_theme_uses_white_surfaces() {
    val s = schemeFor("DAY_BRIGHT")
    assertEquals(Color(0xFF0A0A0A), s.primary)
    assertEquals(Color(0xFFFFFFFF), s.background)
    assertEquals(Color(0xFFE7E7E2), s.outline)
}

@Test
fun error_colors_follow_web_tokens() {
    assertEquals(Color(0xFFDC2626), schemeFor("DAY").error)
    assertEquals(Color(0xFFC0392B), schemeFor("EYE_CARE").error)
    assertEquals(Color(0xFFF87171), schemeFor("NIGHT").error)
}
```
`PrimaryTextTokenTest.kt`:NightBlack 断言值改 `Color(0xFFA3CDAF)`(执行时打开文件核对既有断言写法)。

- [ ] **Step 6: 双端测试转绿**

```bash
cd server/internal/web && node --test
cd ../../android && ./gradlew testDebugUnitTest
```
Expected: 两端全绿。

- [ ] **Step 7: Commit**

```bash
git add server/internal/web/theme-parity.test.mjs android/app/src/main/java/com/juziss/localmediahub/ui/theme/Theme.kt android/app/src/main/java/com/juziss/localmediahub/ui/theme/ColorTokens.kt android/app/src/test/java/com/juziss/localmediahub/ui/theme/
git commit -m "feat(android): align color schemes with web ink tokens and add parity guard tests"
```

---

### Task 2: THEME_LABELS 圆点化 + label 对称守护

**Files:**
- Modify: `server/internal/web/readerPrefs.js:47-56`(THEME_LABELS)
- Modify: `server/internal/web/theme-parity.test.mjs`(追加 label test)
- Test: `server/internal/web/readerPrefs.test.mjs`(若既有断言检查旧文案则同步更新)

**Interfaces:**
- Consumes: Task 1 的 `parseKotlinReaderThemes` 同构解析思路(label 字段用 `label = "..."` 正则,与 hex 分开提取)
- Produces: `readerPrefs.THEME_LABELS` 新文案(消费方 `settings.js:63,84` 自动跟随,无需改动)

- [ ] **Step 1: 追加失败测试**

在 `theme-parity.test.mjs` 末尾追加:

```js
test('theme labels: web THEME_LABELS / THEME_OPTIONS === android ReaderTheme.label', () => {
    const src = read(READER_SETTINGS_KT);
    const labels = {};
    let m;
    const re = /^ {4}([A-Z_]+)\($([\s\S]*?)^ {4}\),?$/gm;
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
```

- [ ] **Step 2: 跑测试确认红**

```bash
cd server/internal/web && node --test theme-parity.test.mjs
```
Expected: FAIL —— THEME_LABELS 仍是短版(如 `DAY: 日间 ≠ 日间·纸白`)。

- [ ] **Step 3: 改 `readerPrefs.js` THEME_LABELS**

```js
export const THEME_LABELS = Object.freeze({
    DAY: '日间·纸白',
    DAY_BRIGHT: '日间·亮白',
    EYE_CARE: '护眼·米黄',
    EYE_CARE_GREEN: '护眼·豆沙绿',
    PARCHMENT: '羊皮纸',
    NIGHT: '夜间·深空',
    NIGHT_BLACK: '夜间·纯黑',
    AUTO: '跟随系统',
});
```
(注释与 `Object.freeze` 保持不变;`reader-settings.js` 的 `THEME_OPTIONS` 已是此文案,无需改动。)

- [ ] **Step 4: 全量转绿**

```bash
cd server/internal/web && node --test
```
Expected: 全绿(含 `readerPrefs.test.mjs` 既有断言——若它断言旧文案,更新为圆点文案)。

- [ ] **Step 5: Commit**

```bash
git add server/internal/web/readerPrefs.js server/internal/web/theme-parity.test.mjs server/internal/web/readerPrefs.test.mjs
git commit -m "feat(web): unify theme labels with android dot style"
```

---

### Task 3: inline style 守护 + XSS-SAFE 复核

**Files:**
- Modify: `server/internal/web/theme-parity.test.mjs`(追加 inline style 扫描 test)

**Interfaces:** 无(纯测试增量,行为零改动)

- [ ] **Step 1: 追加测试(预期直接绿——存量已迁移完毕)**

在 `theme-parity.test.mjs` 追加(顶部 import 补 `readdirSync`):

```js
import { readdirSync } from 'node:fs';

test('web sources contain no inline style attributes (CSP style-src self)', () => {
    const skip = (f) => f.endsWith('.test.mjs') || f.includes('vendor/') || f.includes('_snapshot-helpers');
    const files = readdirSync(new URL('./', import.meta.url), { recursive: true, encoding: 'utf8' })
        .filter((f) => /\.(js|html)$/.test(f) && !skip(f));
    assert.ok(files.length > 20, `scan found suspiciously few files (${files.length}) — check directory`);
    for (const f of files) {
        const src = read('./' + f);
        assert.ok(!/style="/.test(src), `${f} contains inline style=" — move to a CSS class (CSP)`);
    }
});
```

- [ ] **Step 2: 跑测试**

```bash
cd server/internal/web && node --test theme-parity.test.mjs
```
Expected: PASS。若 FAIL——存量确有漏网 inline style,把该处迁移为 CSS 类(加到对应 `css/` 模块,不改行为),直至 PASS。

- [ ] **Step 3: xsscheck 复核**

```bash
cd tools/xsscheck && go run . ../../server/internal/web
```
Expected: 通过(无未注释 sink)。

- [ ] **Step 4: Commit**

```bash
git add server/internal/web/theme-parity.test.mjs
git commit -m "test(web): inline-style guard and xss-safe audit"
```
(若 Step 2 迁移了漏网 inline style,被改文件一并 `git add`。)

---

### Task 4: Web 关键控件 aria 补齐

**Files:**
- Modify: `server/internal/web/index.html`(lightbox 导航按钮)
- Modify: `server/internal/web/browserView.js`(card-action-btn 模板)
- Modify: `server/internal/web/textReader.js`(autoscroll ± 按钮)
- Modify: `server/internal/web/bookmarksView.js`(delBtn)
- Modify: `server/internal/web/readerScrubber.js`(progressbar 语义)
- Modify: `server/internal/web/reader-settings.js`(dialog aria-labelledby)
- Modify: `server/internal/web/router.js`(侧栏 aria-current)
- Test: `server/internal/web/readerScrubber.test.mjs`、`server/internal/web/reader-settings.test.mjs`、`server/internal/web/textReader.test.mjs`(均为既有文件,追加断言)

**Interfaces:** 无新接口;`readerScrubber.js` 的 root 元素新增 `role`/`aria-*` 属性,`update()` 内部追加 aria-valuenow 同步(不改变既有返回 API)

- [ ] **Step 1: 写失败测试(jsdom 级,三处)**

`readerScrubber.test.mjs` 追加(照抄该文件既有 render 调用方式):
```js
test('scrubber exposes progressbar semantics', () => {
    // renderScrubber 后：
    assert.equal(root.getAttribute('role'), 'progressbar');
    assert.equal(root.getAttribute('aria-label'), '阅读进度');
    assert.equal(root.getAttribute('aria-valuemin'), '0');
    assert.equal(root.getAttribute('aria-valuemax'), '100');
    assert.match(root.getAttribute('aria-valuenow') || '', /^\d+$/);
});
```
`reader-settings.test.mjs` 追加:
```js
test('settings dialog is labelled', () => {
    const dialog = document.getElementById('reader-settings-dialog');
    const labelledBy = dialog.getAttribute('aria-labelledby');
    assert.ok(labelledBy, 'dialog needs aria-labelledby');
    assert.ok(document.getElementById(labelledBy), 'aria-labelledby must point at the header');
});
```
`textReader.test.mjs` 追加(渲染后):
```js
test('autoscroll buttons have aria-labels', () => {
    assert.equal(document.getElementById('autoscroll-panel-minus')?.getAttribute('aria-label'), '减速');
    assert.equal(document.getElementById('autoscroll-panel-plus')?.getAttribute('aria-label'), '加速');
});
```

- [ ] **Step 2: 跑测试确认三处 FAIL**

```bash
cd server/internal/web && node --test readerScrubber.test.mjs reader-settings.test.mjs textReader.test.mjs
```
Expected: 三个新断言 FAIL,其余 PASS。

- [ ] **Step 3: 实现(全部为标记/属性增量,零行为改动)**

1. `index.html:364,374`:`btn-image-prev` 加 `aria-label="上一张"`,`btn-image-next` 加 `aria-label="下一张"`
2. `browserView.js:297-401` 七个 `card-action-btn` 模板:各补 `aria-label` 同其 title 值(`删除文件夹`/`收藏`/`阅读状态`/`删除文件`)
3. `textReader.js:97,99`:`autoscroll-panel-minus` 补 `aria-label="减速"`、`autoscroll-panel-plus` 补 `aria-label="加速"`
4. `bookmarksView.js:113` delBtn:createElement 后若设了 title 则同步 `delBtn.setAttribute('aria-label', ...)`,未设则新增「删除书签」(执行时打开核对)
5. `readerScrubber.js`:模板 root 加 `role="progressbar" aria-label="阅读进度" aria-valuemin="0" aria-valuemax="100" aria-valuenow="0"`;`update()` 里在既有宽度/位置计算处追加 `root.setAttribute('aria-valuenow', String(Math.round(percent * 100)))`(percent 为既有 0..1 值,执行时按实际变量名对接)
6. `reader-settings.js:57-59`:`<h3 id="reader-settings-title">阅读设置</h3>`,dialog 创建后 `dialog.setAttribute('aria-labelledby', 'reader-settings-title')`(native `<dialog>` 已隐式 role=dialog)
7. `router.js` 活动菜单项:在设置 active class 的同一处,对活动项 `setAttribute('aria-current', 'page')`、其余 `removeAttribute('aria-current')`(菜单项选择器执行时按 `index.html` sidebar 结构核对)
8. `videoPlayer.js`:执行时审计其动态生成的 icon-only 控件(静态 `index.html` 的 `video-control-btn` 已有 aria-label),有缺则按同模式补

模板字符串改动的 sink 均已有 `// XSS-SAFE:` 注释(纯字面量),新增 aria 属性不引入用户数据,注释前提不变。

- [ ] **Step 4: 全量验证**

```bash
cd server/internal/web && node --test
cd ../tools/xsscheck && go run . ../../server/internal/web
```
Expected: 全绿;xsscheck 通过。

- [ ] **Step 5: Commit**

```bash
git add server/internal/web/index.html server/internal/web/browserView.js server/internal/web/textReader.js server/internal/web/bookmarksView.js server/internal/web/readerScrubber.js server/internal/web/reader-settings.js server/internal/web/router.js server/internal/web/videoPlayer.js server/internal/web/readerScrubber.test.mjs server/internal/web/reader-settings.test.mjs server/internal/web/textReader.test.mjs
git commit -m "feat(web): aria labels for key controls"
```

---

### Task 5: 文档同步

**Files:**
- Modify: `AGENTS.md`

**Interfaces:** 无

- [ ] **Step 1: 更新 AGENTS.md**

- 「Web 管理界面 → 样式层」末尾追加一句:`theme-parity.test.mjs 守护双端主题 token 对称(reader hex / chrome token / label / 无 inline style),改色值或标签需双端同步`
- 「Android → 构建」前的 theme 相关描述处(`ui/theme/`)追加:`色值与 web themes.css 对称,由 server/internal/web/theme-parity.test.mjs 守护(node --test)`
- 不改动其它章节。

- [ ] **Step 2: 验收全跑一遍**

```bash
cd server/internal/web && node --test
cd ../../tools/xsscheck && go run . ../../server/internal/web
cd ../../android && ./gradlew testDebugUnitTest assembleDebug
```
Expected: 三项全绿。

- [ ] **Step 3: Commit**

```bash
git add AGENTS.md
git commit -m "docs: sync handbook for ui polish alignment"
```

---

## Self-Review 记录

- Spec 覆盖:§2 chrome 收敛→Task 1;§3 reader hex 守护→Task 1;§4 标签→Task 2;§5 守护形态(含 inline style 第 4 条)→Task 1+3;§6 aria 四类→Task 4(icon-only/dialog/progressbar/nav 全覆盖);§7 提交切分→五个 commit 与 spec §7 一致;§8 验收→Task 5 Step 2 + 最终 review。
- 占位符:Task 4 第 4/7/8 项含「执行时打开核对」——这是审计型改动的现场确认指令,不是缺内容;每项都给出了确定的选择规则与默认值。
- 类型一致性:`parseKotlinReaderThemes` 等 helper 名与 Task 1/2 引用一致;`DayBrightColorScheme`/`PrimaryText.DayBright`/`OutlineSoft.DayBright` 命名全程一致。
