# Theme & Design Tokens — LocalMediaHub

Dual-frontend system: **Web SPA** (vanilla ES modules + layered CSS, no build step) and **Android** (Jetpack Compose Material3). Chrome themes and reader themes are two INDEPENDENT axes: the app chrome theme (7 presets) styles the shell/dashboard/browser; the reader theme (9 options incl. AUTO/CUSTOM) takes over the whole reader subtree.

## Part 1 — Compact token summary

### Web chrome themes (`[data-theme]` on `<html>`, 7 presets)

| Token | day (default) | day_bright | eye_care | eye_care_green | parchment | night | night_black |
|---|---|---|---|---|---|---|---|
| --surface-app | #FAFAFA | #FFFFFF | #F7F1E3 | #EAF0E6 | #F3EBD9 | #141517 | #000000 |
| --surface-card | #FFFFFF | #FFFFFF | #FCF8EE | #F4F8F1 | #FAF4E6 | #1D1F23 | #101114 |
| --surface-sidebar | #F4F4F5 | #F8FAFC | #EFE7D2 | #DFE8DA | #EBE1C8 | #101113 | #0A0B0D |
| --text-primary | #17181C | #0F172A | #3F3A2F | #253326 | #443C2C | #E8E9ED | #E8E9ED |
| --text-secondary | #52525B | #475569 | #6B6353 | #4A5B4C | #6E6450 | #A6A8B0 | #A6A8B0 |
| --text-muted | #8A8A93 | #94A3B8 | #8F866F | #6E7F70 | #948A74 | #7C7E87 | #7C7E87 |
| --accent | #5E6AD2 | #2563EB | #A0713C | #4F7D5D | #9C6B2F | #7B87E8 | #7B87E8 |
| --accent-hover | #4E59C9 | #1D4ED8 | #8A5F30 | #40684C | #83571F | #8C97EE | #8C97EE |
| --accent-soft | rgba(94,106,210,.12) | rgba(37,99,235,.12) | rgba(160,113,60,.14) | rgba(79,125,93,.14) | rgba(156,107,47,.14) | rgba(123,135,232,.16) | rgba(123,135,232,.18) |
| --accent-text | #4348B8 | #1E40AF | #7C5527 | #3A5C46 | #77531F | #A3ACEF | #A3ACEF |
| --border-soft / --border-subtle | #EDEDF0 / #E4E4E7 | #EDF2F7 / #E2E8F0 | #EAE1CD / #E3D9C2 | #DAE3D6 / #D3DFD0 | #E7DCC4 / #E0D4B8 | #24262C / #2A2C33 | #22242A / #26282E |
| --error / --secondary | #DC2626 / #16A34A | same | #C0392B / #4F7D3A | #C0392B / #16A34A | #C0392B / #4F7D3A | #F87171 / #4ADE80 | same as night |

Shared: `--error`, `--secondary`, `--text-on-accent: #FFFFFF`. Shadows (light): `--shadow-sm: 0 1px 2px rgba(23,24,28,.05)`, `--shadow-md: 0 4px 12px rgba(23,24,28,.08), 0 2px 4px rgba(23,24,28,.05)`. Dark themes use heavier black shadows.

### Radius / spacing / fonts (web)

- `--radius-sm: 6px; --radius-md: 10px; --radius-lg: 14px`
- `--space-1: 4px … --space-6: 32px` (4/8/12/16/24/32)
- `--font-sans: system-ui, -apple-system, 'Segoe UI', Roboto, 'PingFang SC', 'Microsoft YaHei', sans-serif + emoji fallback`
- Reader fonts (self-hosted woff2): `"LXGW WenKai"` (楷体), `"Noto Serif SC"` (宋体); families SYSTEM/SERIF/KAITI/HEITI/MONO
- Focus ring: `outline: 2px solid var(--accent); offset 2px`; inputs use border+`box-shadow: 0 0 0 3px var(--accent-soft)`
- Breakpoint: 768px (responsive.css last in cascade); sidebar collapses to hamburger drawer

### Web READER themes (`--reader-*` vars, set by JS on `<html>`; 9 options)

| Theme | bg | fg | chromeBg | chromeFg | muted | border |
|---|---|---|---|---|---|---|
| DAY (default) | #FAF8F3 | #2B2B2B | #F2EFE7 | #3D3D3D | #7A7A78 | #E5E2D8 |
| DAY_BRIGHT | #FFFFFF | #212121 | #F5F5F5 | #333333 | #7A7A7A | #E0E0E0 |
| EYE_CARE | #F4ECD8 | #5B4636 | #EDE3CC | #6B5644 | #9C8870 | #D8CBAF |
| EYE_CARE_GREEN | #B9C7B6 | #1F2E20 | #ACBCAB | #1A271B | #4D5E4F | #9BB098 |
| PARCHMENT | #EFE6D2 | #3D3327 | #E5D9BF | #4D4034 | #8C7E66 | #D3C7AB |
| NIGHT | #1A1A1F | #C9C9CE | #232328 | #B0B0B5 | #84848A | #2D2D33 |
| NIGHT_BLACK | #000000 | #BFBFBF | #0A0A0A | #A8A8A8 | #787878 | #1C1C1C |
| AUTO | resolves to DAY/NIGHT by `prefers-color-scheme` |
| CUSTOM | user 3-color (bg/fg/muted); chrome/border derived from them |

Reader typography defaults: fontSize 16px (12–28), lineHeight 1.8 (1.3–2.5), contentWidth 720px (600–1400), letterSpacing 0em (0–1), firstLineIndent on (2em), serif chapter titles (+6px, Noto Serif SC), optional drop-cap 3.2em on first eligible paragraph.

### Android app chrome (Compose Material3, `ui/theme/Theme.kt`)

Light: primary #B96D1D (amber-brown), secondary #3E7A7E (teal), tertiary #647A33 (olive), background #F4EEE2 (warm paper), surface #FBF6EC, onBackground #2A2218, outline #D4CCBA — a **warm paper/amber** palette (deliberately different from web's cool indigo chrome).
Dark: primary #E8915A, background #141210, surface #1E1A17, onBackground #EDE6DA.
Extra schemes: EYE_CARE (#F5EBDC bg, #8C6239 primary), EYE_CARE_GREEN (#B9C7B6 bg, #2C5030), PARCHMENT (#F4ECD8, #6B4C2A), NIGHT_BLACK (pure black, #E0E0E0).
Extra CompositionLocals: `OutlineSoft` (paper card hairline) and `PrimaryText` (AA-contrast primary for small text).

### Android reader themes (`data/ReaderSettings.kt` enum)

Same 6+AUTO+CUSTOM presets with the SAME hex values as web reader themes (cross-platform parity by spec). Rendered via `ReaderThemeScope` which copies bg/fg/chrome/muted into a local MaterialTheme colorScheme. Android-only extra: `bgImageUri` background image under a 70% bg scrim.

### Type scale (Android Typography)

headlineLarge 31/37 Bold, headlineSmall 25/31 SemiBold, titleLarge 22/28 SemiBold, titleMedium 17/23 SemiBold, bodyLarge 16/23, bodyMedium 14/21, labelLarge 14/20 SemiBold, labelMedium 12/16 Medium. All SansSerif; reader chapter titles use `FontFamily.Serif`.

## Part 2 — Raw source dumps

### css/themes.css (full)

```css
:root,
[data-theme="day"] {
    color-scheme: light;
    --surface-app:     #FAFAFA;
    --surface-card:    #FFFFFF;
    --surface-sidebar: #F4F4F5;
    --surface-hover:   rgba(94, 106, 210, 0.08);
    --text-primary:    #17181C;
    --text-secondary:  #52525B;
    --text-muted:      #8A8A93;
    --text-on-accent:  #FFFFFF;
    --accent:          #5E6AD2;
    --accent-hover:    #4E59C9;
    --accent-soft:     rgba(94, 106, 210, 0.12);
    --accent-text:     #4348B8;
    --border-soft:     #EDEDF0;
    --border-subtle:   #E4E4E7;
    --shadow-sm:       0 1px 2px rgba(23, 24, 28, 0.05);
    --shadow-md:       0 4px 12px rgba(23, 24, 28, 0.08), 0 2px 4px rgba(23, 24, 28, 0.05);
    --radius-sm: 6px;  --radius-md: 10px; --radius-lg: 14px;
    --space-1: 4px; --space-2: 8px; --space-3: 12px; --space-4: 16px;
    --space-5: 24px; --space-6: 32px;
    --error: #DC2626;  --secondary: #16A34A;
    --font-sans: system-ui, -apple-system, 'Segoe UI', Roboto, 'PingFang SC', 'Microsoft YaHei', sans-serif, "Apple Color Emoji", "Segoe UI Emoji", "Segoe UI Symbol", "Noto Color Emoji";
}

[data-theme="day_bright"] {
    color-scheme: light;
    --surface-app: #FFFFFF; --surface-card: #FFFFFF; --surface-sidebar: #F8FAFC;
    --surface-hover: rgba(37, 99, 235, 0.08);
    --text-primary: #0F172A; --text-secondary: #475569; --text-muted: #94A3B8;
    --text-on-accent: #FFFFFF;
    --accent: #2563EB; --accent-hover: #1D4ED8;
    --accent-soft: rgba(37, 99, 235, 0.12); --accent-text: #1E40AF;
    --border-soft: #EDF2F7; --border-subtle: #E2E8F0;
    --error: #DC2626; --secondary: #16A34A;
}

[data-theme="eye_care"] {
    color-scheme: light;
    --surface-app: #F7F1E3; --surface-card: #FCF8EE; --surface-sidebar: #EFE7D2;
    --surface-hover: rgba(160, 113, 60, 0.10);
    --text-primary: #3F3A2F; --text-secondary: #6B6353; --text-muted: #8F866F;
    --text-on-accent: #FFFFFF;
    --accent: #A0713C; --accent-hover: #8A5F30;
    --accent-soft: rgba(160, 113, 60, 0.14); --accent-text: #7C5527;
    --border-soft: #EAE1CD; --border-subtle: #E3D9C2;
    --error: #C0392B; --secondary: #4F7D3A;
}

[data-theme="eye_care_green"] {
    color-scheme: light;
    --surface-app: #EAF0E6; --surface-card: #F4F8F1; --surface-sidebar: #DFE8DA;
    --surface-hover: rgba(79, 125, 93, 0.10);
    --text-primary: #253326; --text-secondary: #4A5B4C; --text-muted: #6E7F70;
    --text-on-accent: #FFFFFF;
    --accent: #4F7D5D; --accent-hover: #40684C;
    --accent-soft: rgba(79, 125, 93, 0.14); --accent-text: #3A5C46;
    --border-soft: #DAE3D6; --border-subtle: #D3DFD0;
    --error: #C0392B; --secondary: #16A34A;
}

[data-theme="parchment"] {
    color-scheme: light;
    --surface-app: #F3EBD9; --surface-card: #FAF4E6; --surface-sidebar: #EBE1C8;
    --surface-hover: rgba(156, 107, 47, 0.10);
    --text-primary: #443C2C; --text-secondary: #6E6450; --text-muted: #948A74;
    --text-on-accent: #FFFFFF;
    --accent: #9C6B2F; --accent-hover: #83571F;
    --accent-soft: rgba(156, 107, 47, 0.14); --accent-text: #77531F;
    --border-soft: #E7DCC4; --border-subtle: #E0D4B8;
    --error: #C0392B; --secondary: #4F7D3A;
}

[data-theme="night"] {
    color-scheme: dark;
    --surface-app: #141517; --surface-card: #1D1F23; --surface-sidebar: #101113;
    --surface-hover: rgba(123, 135, 232, 0.12);
    --text-primary: #E8E9ED; --text-secondary: #A6A8B0; --text-muted: #7C7E87;
    --text-on-accent: #FFFFFF;
    --accent: #7B87E8; --accent-hover: #8C97EE;
    --accent-soft: rgba(123, 135, 232, 0.16); --accent-text: #A3ACEF;
    --border-soft: #24262C; --border-subtle: #2A2C33;
    --shadow-sm: 0 1px 2px rgba(0, 0, 0, 0.4);
    --shadow-md: 0 4px 12px rgba(0, 0, 0, 0.5), 0 2px 4px rgba(0, 0, 0, 0.4);
    --error: #F87171; --secondary: #4ADE80;
}

[data-theme="night_black"] {
    color-scheme: dark;
    --surface-app: #000000; --surface-card: #101114; --surface-sidebar: #0A0B0D;
    --surface-hover: rgba(123, 135, 232, 0.14);
    --text-primary: #E8E9ED; --text-secondary: #A6A8B0; --text-muted: #7C7E87;
    --text-on-accent: #FFFFFF;
    --accent: #7B87E8; --accent-hover: #8C97EE;
    --accent-soft: rgba(123, 135, 232, 0.18); --accent-text: #A3ACEF;
    --border-soft: #22242A; --border-subtle: #26282E;
    --shadow-sm: 0 1px 2px rgba(0, 0, 0, 0.6);
    --shadow-md: 0 4px 12px rgba(0, 0, 0, 0.7), 0 2px 4px rgba(0, 0, 0, 0.5);
    --error: #F87171; --secondary: #4ADE80;
}

/* body[data-reader-theme] overrides: reader subtree surface/text/border
   tokens are taken over by the reader theme (--reader-chrome-bg etc.) */
body[data-reader-theme][data-active-tab="read"] .view-container,
body[data-reader-theme] .text-reader,
body[data-reader-theme] .text-reader__drawer,
body[data-reader-theme] dialog#reader-settings-dialog,
body[data-reader-theme] .text-reader__autoscroll-panel {
    --surface-card: var(--reader-chrome-bg);
    --surface-hover: rgba(0, 0, 0, 0.08);
    --text-primary: var(--reader-fg);
    --text-secondary: var(--reader-fg);
    --text-muted: var(--reader-muted);
    --border-subtle: var(--reader-border);
    --border-soft: var(--reader-border);
}
```

### Reader theme presets (readerPrefs.js THEME_PRESETS)

```js
export const THEME_PRESETS = {
    DAY:            { bg: '#FAF8F3', fg: '#2B2B2B', chromeBg: '#F2EFE7', chromeFg: '#3D3D3D', muted: '#7A7A78', border: '#E5E2D8' },
    DAY_BRIGHT:     { bg: '#FFFFFF', fg: '#212121', chromeBg: '#F5F5F5', chromeFg: '#333333', muted: '#7A7A7A', border: '#E0E0E0' },
    EYE_CARE:       { bg: '#F4ECD8', fg: '#5B4636', chromeBg: '#EDE3CC', chromeFg: '#6B5644', muted: '#9C8870', border: '#D8CBAF' },
    EYE_CARE_GREEN: { bg: '#B9C7B6', fg: '#1F2E20', chromeBg: '#ACBCAB', chromeFg: '#1A271B', muted: '#4D5E4F', border: '#9BB098' },
    PARCHMENT:      { bg: '#EFE6D2', fg: '#3D3327', chromeBg: '#E5D9BF', chromeFg: '#4D4034', muted: '#8C7E66', border: '#D3C7AB' },
    NIGHT:          { bg: '#1A1A1F', fg: '#C9C9CE', chromeBg: '#232328', chromeFg: '#B0B0B5', muted: '#84848A', border: '#2D2D33' },
    NIGHT_BLACK:    { bg: '#000000', fg: '#BFBFBF', chromeBg: '#0A0A0A', chromeFg: '#A8A8A8', muted: '#787878', border: '#1C1C1C' },
    AUTO:           null,
};

export const FONT_FAMILIES = {
    SYSTEM: `-apple-system, "PingFang SC", "Microsoft YaHei", "Helvetica Neue", sans-serif, …emoji`,
    SERIF:  `"Noto Serif SC", "Songti SC", "SimSun", serif, …`,
    KAITI:  `"LXGW WenKai", "Kaiti SC", "STKaiti", cursive, …`,
    HEITI:  `"Heiti SC", "Microsoft YaHei", "PingFang SC", sans-serif, …`,
    MONO:   `"Cascadia Mono", Consolas, "Courier New", monospace, …`,
};

export const DEFAULT_SETTINGS = {
    fontFamily: 'SYSTEM', fontSize: 16, lineHeight: 1.8, contentWidth: 720,
    firstLineIndent: true, paragraphSpacing: false, theme: 'DAY',
    immersiveMode: false, autoScrollSpeed: 5, readingMode: 'chapter',
    letterSpacing: 0, customBg: null, customFg: null, customMuted: null,
    pageTurnStyle: 'NONE',
};
```

### Android Theme.kt color schemes (full values)

```kotlin
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFE8915A), secondary = Color(0xFF6FB8BC), tertiary = Color(0xFFC8D78E),
    background = Color(0xFF141210), surface = Color(0xFF1E1A17), surfaceVariant = Color(0xFF2A2420),
    primaryContainer = Color(0xFF3A2516), secondaryContainer = Color(0xFF1A3335),
    onPrimary = Color(0xFF2A1408), onSecondary = Color(0xFF042022), onTertiary = Color.White,
    onBackground = Color(0xFFEDE6DA), onSurface = Color(0xFFEDE6DA),
    onSurfaceVariant = Color(0xFFB3A793), outline = Color(0xFF3A3229), outlineVariant = Color(0xFF3A3229),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFB96D1D), secondary = Color(0xFF3E7A7E), tertiary = Color(0xFF647A33),
    background = Color(0xFFF4EEE2), surface = Color(0xFFFBF6EC), surfaceVariant = Color(0xFFEDE6D6),
    primaryContainer = Color(0xFFFBEBD8), secondaryContainer = Color(0xFFD6EFF0),
    onPrimary = Color.White, onSecondary = Color.White, onTertiary = Color.White,
    onBackground = Color(0xFF2A2218), onSurface = Color(0xFF2A2218),
    onSurfaceVariant = Color(0xFF6B5E48), outline = Color(0xFFD4CCBA), outlineVariant = Color(0xFFD4CCBA),
)

// EyeCareGreen: primary #2C5030, bg #B9C7B6, surface #ACBCAB
// EyeCare:      primary #8C6239, bg #F5EBDC, surface #EBDCC8
// Parchment:    primary #6B4C2A, bg #F4ECD8, surface #E8DFC9
// NightBlack:   primary #E0E0E0, bg #000000, surface #121212

object OutlineSoft {
    val Light = Color(0xFFE2D9C6); val Dark = Color(0xFF332B24)
    val EyeCare = Color(0xFFD9C8B2); val EyeCareGreen = Color(0xFF9BB098)
    val Parchment = Color(0xFFD6CBAE); val NightBlack = Color(0xFF222222)
}
object PrimaryText {
    val Light = Color(0xFF965410); val Dark = Color(0xFFF2A878)
    val EyeCare = Color(0xFF6B4A2A); val EyeCareGreen = Color(0xFF1F3A23)
    val Parchment = Color(0xFF4A3520); val NightBlack = Color(0xFFE0E0E0)
}
```

### base.css (global reset excerpt)

```css
@font-face { font-family: "LXGW WenKai"; src: url("/fonts/LXGWWenKai-Regular.woff2") format("woff2"); font-display: swap; }
@font-face { font-family: "Noto Serif SC"; src: url("/fonts/NotoSerifSC-Regular.woff2") format("woff2"); font-display: swap; }

* { margin: 0; padding: 0; box-sizing: border-box; -webkit-font-smoothing: antialiased; }
body { background-color: var(--surface-app); color: var(--text-primary); font-family: var(--font-sans); overflow: hidden; height: 100vh; }
input, textarea, select { background-color: var(--surface-card); color: var(--text-primary); border: 1px solid var(--border-subtle); border-radius: var(--radius-md); padding: 8px 12px; font: inherit; }
input:focus, textarea:focus, select:focus { outline: none; border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.num-tabular { font-variant-numeric: tabular-nums; }
```
