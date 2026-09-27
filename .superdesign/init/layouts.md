# Layouts — LocalMediaHub

## Web app shell (renders on EVERY web route)

Single-page app, one static `index.html`. Layout = CSS Grid `260px sidebar + 1fr main`. Hash routing toggles `.view-section.active` — all views live in the DOM permanently.

**Structure (from `server/internal/web/index.html`):**

```html
<body>
  <div class="app-container">
    <!-- Sidebar Navigation -->
    <aside class="sidebar">
      <div class="sidebar-brand">
        <span class="brand-monogram">LMH</span>
        <span class="brand-name">LocalMediaHub</span>
        <span class="brand-version">v0.2.0</span>
      </div>
      <nav class="sidebar-menu">
        <a href="#/dashboard" class="menu-item active" id="menu-dashboard">
          <span class="menu-icon"><!-- inline SVG: 4-rect dashboard glyph, 18x18, stroke=currentColor, sw=2 --></span>
          仪表盘
        </a>
        <a href="#/browser" class="menu-item" id="menu-browser">
          <span class="menu-icon"><!-- inline SVG: folder glyph --></span>
          媒体共享库
        </a>
        <a href="#/bookmarks" class="menu-item" id="menu-bookmarks">
          <span class="menu-icon"><!-- inline SVG: bookmark glyph --></span>
          书签管理
        </a>
        <a href="#/settings" class="menu-item" id="menu-settings">
          <span class="menu-icon"><!-- inline SVG: settings gear glyph --></span>
          系统设置
        </a>
      </nav>
      <div class="sidebar-footer">
        <div class="server-status">
          <span class="status-indicator online"></span>
          <span class="status-text">服务正常运行</span>
        </div>
      </div>
    </aside>

    <div class="sidebar-backdrop" id="sidebar-backdrop" hidden></div>

    <main class="main-content">
      <header class="main-header">
        <button class="hamburger-btn" id="btn-hamburger" aria-label="切换菜单">
          <span></span><span></span><span></span>
        </button>
        <h1 id="page-title">仪表盘</h1>
        <div class="header-actions">
          <button class="btn btn-theme-toggle" id="btn-theme-toggle" aria-label="切换主题">
            <!-- sun/moon inline SVG pair, hidden-toggle pattern -->
          </button>
          <button class="btn btn-primary btn-rescan" id="btn-trigger-scan">
            <span><!-- refresh SVG 16x16 --></span> 立即扫描媒体
          </button>
        </div>
      </header>

      <div class="view-container">
        <section id="view-dashboard" class="view-section active"><!-- stats-grid + dashboard-widgets + dashboard-bookshelf --></section>
        <section id="view-browser" class="view-section"><!-- browser-toolbar (breadcrumbs + filter chips + sort + search) + browser-grid --></section>
        <section id="view-bookmarks" class="view-section"><!-- bookmarks-manager-container --></section>
        <section id="view-settings" class="view-section"><!-- settings-card stack --></section>
        <section id="view-reader" class="view-section"><!-- textReader.js or bookshelf.js render target (#/read, #/bookshelf) --></section>
      </div>

      <div class="scroll-fab-group" id="scroll-fab-group">
        <button class="scroll-fab-btn" id="btn-scroll-top"><!-- chevron-up SVG 18x18 --></button>
        <button class="scroll-fab-btn" id="btn-scroll-bottom"><!-- chevron-down SVG --></button>
      </div>
    </main>
  </div>

  <!-- overlays: #modal-video-player (glass modal + custom video controls),
       #modal-image-preview (lightbox), #auth-modal (token input), #toast-container -->
</body>
```

**Reader-mode shell override:** on `#/read`, `body[data-active-tab="read"]` HIDES `.main-header` and zeroes `.view-container` padding — the reader fills the viewport edge-to-edge next to the sidebar; on immersive mode (`body[data-reader-immersive="on"]`) the reader goes `position: fixed; inset: 0; z-index: 9999` covering even the sidebar.

### layout.css (full)

```css
.app-container { display: grid; grid-template-columns: 260px 1fr; height: 100vh; width: 100vw; }

.sidebar {
    background-color: var(--surface-sidebar);
    border-right: 1px solid var(--border-subtle);
    display: flex; flex-direction: column;
    padding: var(--space-5) var(--space-4);
    justify-content: space-between;
}
.sidebar-brand { display: flex; align-items: center; gap: var(--space-3); padding: var(--space-2) var(--space-3) var(--space-5); border-bottom: 1px solid var(--border-subtle); }
.brand-monogram { display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; background: var(--accent); color: var(--text-on-accent); font-weight: 700; font-size: 12px; letter-spacing: 0.5px; border-radius: var(--radius-sm); }
.brand-name { font-size: 16px; font-weight: 600; color: var(--text-primary); letter-spacing: -0.3px; }
.brand-version { font-size: 11px; background: var(--surface-card); color: var(--text-muted); padding: 2px 6px; border-radius: var(--radius-sm); border: 1px solid var(--border-subtle); font-weight: 500; }

.sidebar-menu { display: flex; flex-direction: column; gap: var(--space-1); margin-top: var(--space-5); flex-grow: 1; }
.menu-item { position: relative; display: flex; align-items: center; gap: var(--space-3); padding: var(--space-3) var(--space-4); color: var(--text-secondary); text-decoration: none; font-weight: 500; font-size: 14px; border-radius: var(--radius-md); transition: all .15s ease; }
.menu-item:hover { color: var(--text-primary); background-color: var(--surface-hover); }
.menu-item.active { color: var(--accent-text); background-color: var(--accent-soft); }
.menu-item.active::before { content: ""; position: absolute; left: 0; top: 8px; bottom: 8px; width: 3px; border-radius: 0 2px 2px 0; background: var(--accent); }
.menu-icon { font-size: 16px; display: inline-flex; width: 20px; height: 20px; align-items: center; justify-content: center; }

.sidebar-footer { padding-top: var(--space-4); border-top: 1px solid var(--border-subtle); }
.server-status { display: inline-flex; align-items: center; gap: var(--space-2); padding: 6px 12px; font-size: 13px; color: var(--text-secondary); background: var(--surface-card); border: 1px solid var(--border-subtle); border-radius: 999px; }
.status-indicator { width: 8px; height: 8px; border-radius: 50%; }
.status-indicator.online { background-color: #4F8A6B; box-shadow: 0 0 0 3px rgba(79, 138, 107, 0.2); }

.main-content { display: flex; flex-direction: column; height: 100vh; overflow: hidden; background: var(--surface-app); }
.main-header { height: 56px; border-bottom: 1px solid var(--border-subtle); padding: 0 var(--space-6); display: flex; align-items: center; justify-content: space-between; background-color: var(--surface-card); z-index: 10; }
.main-header h1 { font-size: 18px; font-weight: 600; color: var(--text-primary); letter-spacing: -0.3px; }
.view-container { flex-grow: 1; overflow-y: auto; padding: 32px; }
.view-section { display: none; animation: fadeIn 0.4s ease; }
.view-section.active { display: block; }
@keyframes fadeIn { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: translateY(0); } }

.hamburger-btn { display: none; background: transparent; border: 0; cursor: pointer; padding: 8px; flex-direction: column; gap: 4px; color: var(--text-primary); }
.hamburger-btn span { display: block; width: 22px; height: 2px; background: currentColor; border-radius: 1px; transition: transform 0.2s ease, opacity 0.2s ease; }
.hamburger-btn[aria-expanded="true"] span:nth-child(1) { transform: translateY(6px) rotate(45deg); }
.hamburger-btn[aria-expanded="true"] span:nth-child(2) { opacity: 0; }
.hamburger-btn[aria-expanded="true"] span:nth-child(3) { transform: translateY(-6px) rotate(-45deg); }
.sidebar-backdrop { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.5); z-index: 50; cursor: pointer; }
```

(≤768px: sidebar becomes an off-canvas drawer behind `.sidebar-backdrop`, hamburger shows — see responsive.css.)

## Android app shell

`MainActivity.kt` — single Activity + NavHost (routes: `connection`, `home`, `browse`, `imagePreview`, `bookshelf`, `downloads`). Reader and video player are SEPARATE activities: `TextReaderActivity` (hosts `TextReaderScreen` full-bleed, no app chrome of its own) and `VideoPlayerActivity` (PiP-capable). App-level theme `LocalMediaHubTheme(themeKey)` wraps the NavHost with the warm-paper Material3 schemes.

**TextReaderScreen layout skeleton (the design target):**

```
ReaderThemeScope(theme, bgImage?, customColors?)   // reader colors override Material3
└─ ModalNavigationDrawer(drawerState)             // TOC drawer, slides from left
   ├─ drawerContent: ModalDrawerSheet
   │  ├─ PrimaryTabRow [目录 | 书签 (n)]
   │  └─ LazyColumn of NavigationDrawerItem (chapter titles, selected highlighted)
   └─ content: Scaffold(containerColor = reader bg)
      ├─ topBar: TopAppBar (visible = chromeVisible)
      │  ├─ nav icon: ← back
      │  ├─ title: current chapter title
      │  └─ actions: [Aa settings] [▶/‖ autoscroll] [☰ TOC]
      ├─ bottomBar: Column
      │  ├─ LinearProgressIndicator (3dp, full width)
      │  └─ BottomAppBar
      │     ├─ chapter mode: "第 x / y 章 · 本章 z%" + spacer + [上一章] [下一章] TextButtons
      │     └─ scroll mode: "全书 z% · 第 x / y 章" text only
      └─ content Box (tap zones: left 20% prev / right 20% next / middle toggles chrome)
         ├─ ChapterModeContent | ScrollModeContent (LazyColumn, width = contentDp ≤ 720dp)
         │  ├─ item 0: centered serif chapter title (fontSize+6sp) + 40dp divider
         │  ├─ paragraphs: indent 2em, long-press menu [添加书签 / 复制段落]
         │  └─ chapter end "❖" (tap → next chapter); scroll mode separator "— — —"
         ├─ (chapter mode) StaticChapterOverlay + PageTurnSimulator for turn animations
         ├─ ReaderScrollbar (right edge, thumb = chapter/book progress, drag-to-seek)
         ├─ ScrollFabGroup (bottom-end, ↑/↓ quick scroll, hides with chrome)
         ├─ BLE degraded badge (top-center pill, auto-hide 3s)
         └─ immersive: 2dp translucent progress line at bottom
```
