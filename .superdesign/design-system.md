# LocalMediaHub Design System

> **Two-part document.** Part A = CURRENT implementation (ground truth for pixel-perfect reproductions — use for "Current X" drafts). Part B = NEW target visual language (redesign direction confirmed by user 2026-09-27 — use for variation/branch drafts).

## Product context

LocalMediaHub is a PC ↔ Android LAN media streaming system: a Go server scans and streams media (video / images / novels: txt + epub), an Android client browses and plays, and the server embeds a Web manager SPA. Core surfaces: dashboard, media browser (folders + cards), novel reader (the product's deepest feature), bookshelf, bookmarks, settings, video player, image lightbox.

**Primary persona:** a single power user reading long Chinese web novels (txt/epub) on desktop and Android, watching videos, browsing image folders — over LAN, often at night.

**JTBD:** "When I read a 2000-chapter novel on my LAN, I want a distraction-free, typographically excellent reader that remembers exactly where I was, syncs to my phone, and never tires my eyes — and I want to jump back in from a bookshelf in one tap."

---

# PART A — Current implementation (reproduction ground truth)

## Two independent theming axes

1. **App chrome theme** — 7 presets driving the shell. Web: modern-neutral (indigo `#5E6AD2` accent, `#FAFAFA` app, `#F4F4F5` sidebar). Android: warm paper/amber Material3 (`#B96D1D` primary, `#F4EEE2` background).
2. **Reader theme** — 9 options (7 presets + AUTO + CUSTOM) fully taking over the reader subtree, independent of chrome theme. Hexes identical on web and Android:

| Theme | bg / fg | chromeBg / chromeFg | muted / border |
|---|---|---|---|
| DAY | #FAF8F3 / #2B2B2B | #F2EFE7 / #3D3D3D | #7A7A78 / #E5E2D8 |
| DAY_BRIGHT | #FFFFFF / #212121 | #F5F5F5 / #333333 | #7A7A7A / #E0E0E0 |
| EYE_CARE | #F4ECD8 / #5B4636 | #EDE3CC / #6B5644 | #9C8870 / #D8CBAF |
| EYE_CARE_GREEN | #B9C7B6 / #1F2E20 | #ACBCAB / #1A271B | #4D5E4F / #9BB098 |
| PARCHMENT | #EFE6D2 / #3D3327 | #E5D9BF / #4D4034 | #8C7E66 / #D3C7AB |
| NIGHT | #1A1A1F / #C9C9CE | #232328 / #B0B0B5 | #84848A / #2D2D33 |
| NIGHT_BLACK | #000000 / #BFBFBF | #0A0A0A / #A8A8A8 | #787878 / #1C1C1C |

## Current typography / shape / motion (web)

- UI sans stack (system-ui / PingFang SC / Microsoft YaHei); 13px controls, 14–15px labels, 15–18px headings 600.
- Reader: SYSTEM/SERIF(Noto Serif SC)/KAITI(LXGW WenKai)/HEITI/MONO; size 12–28 (16), line-height 1.3–2.5 (1.8), width 600–1400 (720), indent 2em, chapter title serif +6px centered + 40px underline, optional 3.2em drop-cap, "❖" chapter end.
- Radius 6/10/14; space 4/8/12/16/24/32; sidebar 260px; header 56px; breakpoint 768px.
- Motion: 120ms chapter enter, 0.15s hovers, 0.2s drawer, 0.25s immersive, 280/400ms page turns.

## Current reader chrome layout (what a reproduction must match)

Top bar: bordered 36px "←" square + "章节名 — 书名" (15px/600) + right-aligned "Aa" and "▶" 36px bordered squares, on chromeBg with bottom border. Content: centered ≤720px column on bg, serif centered chapter title + hairline ornament, indented paragraphs, hover "+" bookmark per paragraph, "❖" end. Footer: bordered small buttons 上一章/下一章/目录 + 4px scrubber with accent thumb and floating label "第 x / y 章 · 本章 z%". Right slide-in 280px TOC drawer (目录/书签 tabs). 3px fixed top progress line. Floating autoscroll pill. 480px settings dialog (5 groups, 3-col swatch grid).

---

# PART B — NEW visual language (user-approved 2026-09-27; IMPLEMENTED 2026-09-27, commits 6e4d180..343a2b1)

Seeded by the **Serene / Minimalist Editorial** style (superdesign slug `serene-find-my-dream`), adapted from a landing-page hero language into a **product UI language for a reading-first media hub**.

## B1. Typography

- **Display/brand/chapter voice:** elegant high-contrast serif (web: `"Instrument Serif", "Noto Serif SC", serif`; weights 400 only — elegance via size, not bold). Used for: brand wordmark, page titles, dashboard stat numerals, chapter titles, bookshelf book titles, empty-state headlines.
- **UI/body voice:** clean grotesque sans (`Inter`-class + PingFang SC / Microsoft YaHei for Chinese). 400 copy, 500 UI labels, 600 reserved for active states.
- Reader body remains user-controllable (family/size/line-height/width/indent) — defaults retuned: 17px, line-height 1.9.
- Scale: 12 / 13 / 14 / 16 / 18 / 22 / 28 / 40 (display). Reader chapter title: serif 400, +8px over body, generous tracking, no underline ornament — use whitespace instead.

## B2. Color — "Slate Ink & Paper" palette (chrome)

Calm, low-chroma slate neutrals with ink-black primary actions; accent used sparingly.

- App background: `#F7F7F5` (light) / `#0C0D10` (dark) — near-neutral warm-gray, not pure white/black
- Surface/card: `#FFFFFF` / `#15161A`; sidebar: `#F1F1EE` / `#0F1013`
- Text primary: `#0F172A` (deep slate) / `#E7E9EE`; secondary HSL(215,25%,32%) / `#9BA1AC`; muted HSL(215,16%,55%)
- **Primary action: ink black `#0A0A0A` bg + white text** (buttons, active nav, scrubber thumb) — replaces the indigo/amber accent as the interactive anchor; hover scale(1.02)
- Accent (sparingly — focus rings, live/progress, reading-status): **moss/forest green `#3D6B4F`** (light) / `#8FBF9F` (dark), soft wash rgba(61,107,79,.10)
- Hairlines: `#E5E4DF` / `#22242B`; radius refined: 8/12/16px; pill 9999px for CTAs and filters
- The 7-theme chrome architecture is KEPT (day/day_bright/eye_care/eye_care_green/parchment/night/night_black) but re-tuned to this family: neutral slates replace indigo; eye-care variants keep their warm papers with moss accents.

## B3. Reader themes (retuned, same 9-option architecture)

Reading surfaces stay paper-warm for eye comfort but harmonize with slate ink:

- DAY: bg `#F6F4EE` paper, fg `#26282E` ink; chrome `#EDEBE3` / `#3A3C44`; muted `#83858C`; border `#E0DDD2`
- SEPIA (was EYE_CARE): bg `#F2EAD8`, fg `#4A4034`, chrome `#E9DFC9`, muted `#9A8C74`, border `#D9CDb2`
- MOSS (was EYE_CARE_GREEN): bg `#C6D2C4`, fg `#1E2A20`, chrome `#B9C7B6`, muted `#48584A`, border `#A3B39F`
- NIGHT: bg `#111318`, fg `#C6CAD2`, chrome `#191C22`, muted `#7E838D`, border `#252832`
- NIGHT_BLACK: bg `#000`, fg `#B9BDC6`, chrome `#0A0B0D`, muted `#74787F`, border `#1B1D24`
- (+ DAY_BRIGHT pure white / PARCHMENT kept close to current; AUTO + CUSTOM 3-color unchanged in behavior)

## B4. Layout & composition principles

- **Reader = the product's hero.** More whitespace, quieter chrome: floating/ghost chrome bars (hairline border, translucent backdrop-blur over content) instead of solid boxed bars; chrome recedes, typography leads.
- Sidebar navigation slimmer (220–240px), quieter (no active pill fill — active = ink text + 2px ink left bar); editorial wordmark in serif.
- Dashboard: stat numerals in serif display (40px), fewer boxes — open composition on app background, cards only where grouping is semantic.
- Browser: media cards on open grid, hover = hairline → ink border + scale(1.01); filter chips become pills.
- Bookshelf: book spines/covers as a shelf composition, serif titles, reading progress as thin moss underline.
- Buttons: primary = ink pill; secondary = hairline pill; icon buttons = 36px ghost circles (no borders at rest, hairline on hover).

## B5. Motion

- Entrance: fade-rise (opacity 0→1, translateY 24px→0, 0.8s ease-out), 200ms stagger for grouped content (dashboard cards, bookshelf grid).
- Hover: buttons scale(1.02–1.03) 200–300ms ease-in-out.
- Reader chapter change: 160ms fade-rise; immersive chrome: 0.3s slide + fade; page turns unchanged (280/400ms).
- prefers-reduced-motion still kills everything.

## B6. Constraints (unchanged, hard)

- Zero inline styles (CSP); CSS classes / Compose tokens only. No-build vanilla JS web; Compose Material3 android.
- Chinese-first UI copy. Emoji icons banned in web chrome — inline SVG, stroke currentColor.
- Both theming axes and the 9-option reader theme architecture must survive (values may be retuned as in B3).
- Cross-platform parity: same theme names/structure on web and Android.

## Specific project requirements

- Reader must keep: chapter/scroll modes, tap zones ±20%, page-turn styles, immersive mode + top progress line, TOC/bookmarks drawer, per-paragraph bookmark, autoscroll panel, settings dialog (5 groups), progress scrubber semantics.
- Designs must show BOTH a light (DAY) and a dark (NIGHT) reader state when exploring themes.
