# Pages — dependency trees — LocalMediaHub

Candidate `--context-file` sets per page (apply PAYLOAD BUDGET: reader page set ≈ index.html + reader.css + textReader.js + readerPrefs.js + toc.js + reader-settings.js ≈ 3.2k lines → trim textReader.js to render-relevant ranges if payload 400s).

## Web `#/read` — Novel Reader (PRIMARY design target)

Entry: `server/internal/web/textReader.js` `renderTextReader()` → renders into `#view-reader`
Shell: `index.html` (`.main-content`/`.view-container`, header hidden in read mode)

```
textReader.js (992, orchestrator: skeleton render, chapter load, scroll buffering, gestures, immersive)
├─ api.js (getBookInfo, getBookChapter)
├─ toast.js (showToast)
├─ readerPrefs.js (221 — settings + THEME_PRESETS + FONT_FAMILIES + bookmarks, localStorage)
├─ bus.js (48 — EVT events)
├─ reader-state.js (36 — shared state singleton)
├─ progress.js (78 — updateProgressUI, chapter detection)
├─ toc.js (134 — TOC/bookmarks tabs + drawer list)
├─ bookmarks.js (53 — bookmarks panel rows)
├─ autoscroll.js (71 — floating panel controller)
├─ reader-settings.js (288 — settings dialog markup + bindings)
├─ readerScrubber.js (110 — footer progress scrubber)
├─ pageTurn.js (411 — page-turn animation layer, curl keyframes)
├─ library.js (reportState/fetchState — progress sync)
├─ readingTimer.js (28 — read-seconds accumulation)
└─ css/views/reader.css (906 — ALL reader styles)
   + css/themes.css (chrome + data-reader-theme overrides)
   + css/base.css (fonts, reset)
```

Rendered DOM (from textReader.js skeleton + submodules):

```
<div class="text-reader">                        ← reader theme bg/fg
  <div class="text-reader__progress-track"><div class="text-reader__progress-bar"/></div>
  <header class="text-reader__header">           ← chromeBg, bottom border
    <button class="text-reader__back">←</button>
    <span class="text-reader__title">章节名 — 书名</span>
    <div class="text-reader__header-actions">
      <button class="text-reader__icon-btn">Aa</button>      (settings)
      <button class="text-reader__icon-btn">▶/‖</button>     (autoscroll)
    </div>
  </header>
  <div class="text-reader__content">             ← the page: centered ≤720px column
    <section class="text-reader__chapter-section">
      <h2 class="text-reader__chapter-title">第N章 标题</h2>
      <p class="text-reader__p indent-on gap-on [--dropcap]">正文段落…<button class="text-reader__para-bookmark">+</button></p>
      …
      <div class="text-reader__chapter-end">❖</div>
    </section>
  </div>
  <footer class="text-reader__footer">           ← chromeBg, top border
    <button class="text-reader__prev">上一章</button>
    <span class="text-reader__progress">→ scrubber (track+thumb+label)</span>
    <button class="text-reader__next">下一章</button>
    <button class="text-reader__toc">目录</button>
  </footer>
</div>
<div class="text-reader__drawer">tabs(目录|书签) + list</div>
<div class="text-reader__autoscroll-panel">play − 速度:5 +</div>
<dialog id="reader-settings-dialog">5 settings groups</dialog>
```

## Web `#/dashboard`

Entry: `dashboard.js` (101) → `#view-dashboard` (static markup in index.html: stats-grid, dashboard-widgets, dashboard-bookshelf)
Dependencies: `app.js`, `api.js`, `state.js`, `dom.js`, `toast.js`, `bookshelf.js` (section render), `library.js` (reading badges), `css/views/dashboard.css`

## Web `#/browser`

Entry: `browserView.js` (563) → `#view-browser` (toolbar markup in index.html)
Dependencies: `api.js`, `state.js`, `dom.js`, `toast.js`, `library.js` (status filter matrix + badges), `lightbox.js`/`videoPlayer.js` (open handlers), `delete.js`, `scrollMemory.js`, `css/views/browser.css` (471)

## Web `#/bookshelf`

Entry: `bookshelf.js` (164) → shared `#view-reader`
Dependencies: `readerPrefs.js`, `library.js`, `css/views/bookshelf.css` (47)

## Android TextReaderScreen (PRIMARY design target)

Entry: `TextReaderActivity` → `ui/screen/TextReaderScreen.kt` (1468)

```
TextReaderScreen.kt
├─ ui/component/reader/ReaderThemeWrapper.kt (137 — ReaderThemeScope color override)
├─ ui/component/reader/ReaderSettingsSheet.kt (544 — bottom-sheet settings)
├─ ui/component/reader/ReaderScrollbar.kt (139 — right-edge seek rail)
├─ ui/component/reader/PageTurnController.kt (64) + PageTurnSimulator.kt (72)
├─ ui/component/ScrollFabGroup.kt (112 — used via calculateScrollFabVisibility)
├─ data/ReaderSettings.kt (ReaderTheme enum, ReadingMode, PageTurnStyle, ReaderListLayout)
├─ viewmodel/TextReaderViewModel.kt (book/blocks/settings/bookmarks/chrome state)
├─ viewmodel/BleSettingsViewModel.kt (BLE badge + retry)
└─ ui/theme/Theme.kt + ColorTokens.kt (app Material3 beneath reader override)
   internal composables: ChapterModeContent, ScrollModeContent, BlockItem,
   ParagraphItem (long-press bookmark/copy menu), BookmarkRow, StaticChapterOverlay
```

## Android HomeScreen

Entry: `ui/screen/HomeScreen.kt` (410)
Dependencies: `ui/component/home/HomeComponents.kt` (907 — Hero/Library/ContinueWatching/RecentMedia/Favorite/Downloaded/Bookshelf/SectionHeader cards), `ui/component/BleChannelSection.kt`, `viewmodel/HomeViewModel`, `data/MediaFile`, `data/RecentActivityStore`

## Android BrowseScreen

Entry: `ui/screen/BrowseScreen.kt` (567)
Dependencies: `ui/component/browse/*` (TopBar, SortMenu, SearchView, FilterChipsRow, StateContent, QuickActionsDialog, DeleteConfirm/Loading), `ui/component/TagComponents.kt`, `ui/component/BrowseContent.kt`, `GridContainers.kt`, `MediaItems.kt`, `viewmodel/BrowseViewModel` (+ delegates)

## Android BookshelfScreen

Entry: `ui/screen/BookshelfScreen.kt` (192)
Dependencies: `viewmodel/BookshelfViewModel`, `TextReaderActivity` (launch), `R`
