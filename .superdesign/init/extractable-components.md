# Extractable Components — LocalMediaHub

"Menu" of components worth extracting as Superdesign DraftComponents. Web components are CSS-class + HTML markup (convert directly to Petite-Vue HTML); Android entries map to HTML equivalents for design purposes only.

## Layout components

### WebSidebar
- Source: `server/internal/web/index.html` (`.sidebar`) + `css/layout.css`
- Category: layout
- Description: 260px left nav — LMH monogram + brand + version, 4 menu items (icon+label), server-status pill footer
- Extractable props: `activeItem` (string: dashboard|browser|bookmarks|settings), `serverOnline` (boolean, default true)
- Hardcoded: SVG icons, 中文 labels, colors from CSS vars

### WebMainHeader
- Source: `index.html` (`.main-header`) + layout.css/components.css
- Category: layout
- Description: 56px top bar — hamburger (mobile), page title, theme toggle (sun/moon), primary "立即扫描媒体" button
- Extractable props: `pageTitle` (string), `showScanButton` (boolean)
- Hardcoded: SVGs, button labels

### ReaderChromeBar (web reader header/footer pair)
- Source: `textReader.js` skeleton + `css/views/reader.css`
- Category: layout
- Description: reader top bar (← back, chapter—book title, Aa + autoscroll square icon buttons) and footer (上一章 | progress scrubber | 下一章 | 目录); themed by reader chrome tokens
- Extractable props: `title` (string), `progressLabel` (string), `showChapterButtons` (boolean — scroll mode hides prev/next), `bookmarksCount` (number)
- Hardcoded: 36px bordered square buttons, ❖ semantics, CSS var theming

### ReaderTocDrawer
- Source: `toc.js` + reader.css
- Category: layout
- Description: right slide-in 280px drawer — 目录/书签 tabs + chapter list rows (active = accent-soft + 3px inset bar)
- Extractable props: `chapters` (list), `activeChapterIndex` (number), `bookmarksCount` (number), `open` (boolean)
- Hardcoded: tab labels, row styling

### AndroidReaderScaffold
- Source: `ui/screen/TextReaderScreen.kt` (Scaffold block)
- Category: layout
- Description: Material3 equivalent of ReaderChromeBar — TopAppBar (←/title/Aa/▶/☰) + LinearProgressIndicator + BottomAppBar (progress text + prev/next TextButtons) + right ReaderScrollbar + FAB group
- Extractable props: `title`, `progressPercent`, `isScrollMode`, `chromeVisible`
- Hardcoded: Material3 componentry, reader theme colors

## Basic components

### MediaCard (web browser grid card)
- Source: `browserView.js` + `css/views/browser.css`
- Category: basic
- Description: thumbnail/folder card with name, meta (size/time), type icon, favorite star, reading-status badge, tag chips
- Extractable props: `title`, `metaText`, `mediaType` (folder|text|video|image), `isFavorite`, `readingStatus` (unread|reading|finished|null), `tagChips`
- Hardcoded: card radius/borders, icon SVGs

### StatCard (dashboard)
- Source: `index.html` `.stat-card` + `css/views/dashboard.css`
- Category: basic
- Description: icon tile + label + tabular number value
- Extractable props: `label`, `value`, `iconType` (text|video|image)

### WidgetCard
- Source: components.css `.widget-card`
- Category: basic
- Description: bordered rounded surface card with 15px/600 heading slot + body
- Extractable props: `heading`

### Toast
- Source: `toast.js` + components.css `.toast`
- Category: basic
- Description: bottom-right slide-in notification, 4px colored left border (accent/info, green/success, red/error)
- Extractable props: `message`, `type` (info|success|error)

### ReaderSettingsDialog (web)
- Source: `reader-settings.js` + reader.css `dialog#reader-settings-dialog`
- Category: basic
- Description: 480px settings modal — 5 groups (外观: font radios + 3-col theme swatch grid; 字号与行距: 4 sliders; 段落: 2 toggles; 行为: mode/page-turn radios + immersive toggle + speed slider; 自定义颜色: 3 color rows)
- Extractable props: `open`, `activeTheme`, `fontFamily`
- Hardcoded: all labels/options, slider ranges

### ReaderChapterTitle / ChapterEnd ornament
- Source: reader.css `__chapter-title`, `__chapter-end`
- Category: basic
- Description: centered serif chapter heading with 40px underline ornament; ❖ end-of-chapter tap target
- Extractable props: `title` (string)
