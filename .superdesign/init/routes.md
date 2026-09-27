# Routes — LocalMediaHub

## Web (hash router, `server/internal/web/router.js`)

No framework router — `handleRoute()` reads `window.location.hash` and toggles `.view-section.active` + sidebar `.menu-item.active` + `body[data-active-tab]` (drives view-scoped CSS overrides). All view sections are pre-declared in index.html.

| Route | View section | Renderer | Sidebar entry | Notes |
|---|---|---|---|---|
| `#/dashboard` (default) | `#view-dashboard` | `dashboard.js` render | 仪表盘 | stats-grid (texts/videos/images) + recent-media widget + server-info widget + bookshelf section |
| `#/browser` | `#view-browser` | `browserView.js` | 媒体共享库 | `?path=…&sys=1` deep-link restore; breadcrumbs + filter chips + sort + search; folder/media card grid |
| `#/bookmarks` | `#view-bookmarks` | `bookmarksView.js` | 书签管理 | reading-bookmark manager list |
| `#/settings` | `#view-settings` | `settings.js` | 系统设置 | global theme grid + scan roots + system info cards |
| `#/read?path=…&chapter=&para=` | `#view-reader` | `textReader.js` renderTextReader | — (off-menu) | novel reader; hides main-header; `data-active-tab="read"` |
| `#/bookshelf` | `#view-reader` (shared) | `bookshelf.js` | — (off-menu) | book card grid from localStorage progress |

Overlays (not routed): `#modal-video-player`, `#modal-image-preview`, `#auth-modal`, toast container.

`body[data-active-tab]` values: `dashboard | browser | bookmarks | settings | read | bookshelf`. The `read` value triggers reader-specific layout overrides (hide header, zero padding).

## Android (Navigation Compose, `MainActivity.kt` NavHost)

| Route | Screen | Entry |
|---|---|---|
| `connection` | ConnectionScreen | first-run / server config + BLE settings |
| `home` | HomeScreen | hero + library + continue-watching + recent + favorites + downloads + bookshelf preview |
| `browse` | BrowseScreen | folder/media browsing with filters/sort/search/tags |
| `imagePreview` | ImagePreviewScreen | from browse |
| `bookshelf` | BookshelfScreen | "查看全部" from home bookshelf card |
| `downloads` | DownloadsScreen | from home/downloads entry |
| (separate Activity) | **TextReaderActivity → TextReaderScreen** | any text file tap; full-screen reader |
| (separate Activity) | VideoPlayerActivity / VideoPlayerScreen | video tap; supports PiP |
