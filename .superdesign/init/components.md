# Shared UI Components — LocalMediaHub

Web primitives are CSS classes (no component framework). Android primitives are Compose composables (structural reference for HTML design mapping). Full source of the small web JS utilities + the shared CSS layer below.

## Web — shared CSS primitives (`css/components.css`, full)

```css
.btn {
    display: inline-flex; align-items: center; gap: var(--space-2);
    padding: 8px 14px; font-size: 13px; font-weight: 500; font-family: var(--font-sans);
    border-radius: var(--radius-sm); border: 1px solid var(--border-subtle);
    background: var(--surface-card); color: var(--text-primary); cursor: pointer;
    transition: background-color .15s ease, border-color .15s ease, color .15s ease, box-shadow .15s ease;
}
.btn:hover { background: var(--surface-hover); border-color: var(--accent); }
.btn:active { transform: translateY(0.5px); }
.btn:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.btn-primary { background: var(--accent); border-color: var(--accent); color: var(--text-on-accent); }
.btn-primary:hover { background: var(--accent-hover); border-color: var(--accent-hover); }
.btn-danger { background: var(--error); color: var(--text-on-accent); border-color: transparent; }

/* forms */
input[type="text"], input[type="password"], select, textarea {
    font-family: var(--font-sans); font-size: 13px; color: var(--text-primary);
    background: var(--surface-card); border: 1px solid var(--border-subtle);
    border-radius: var(--radius-sm); padding: 8px 10px;
}
input:focus-visible { outline: none; border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }

/* cards & info lists */
.widget-card {
    background: var(--surface-card); border: 1px solid var(--border-subtle);
    border-radius: var(--radius-lg); padding: var(--space-5); box-shadow: var(--shadow-sm);
}
.widget-card h2 { font-size: 15px; font-weight: 600; color: var(--text-primary); margin: 0 0 var(--space-4); }
.info-item { display: flex; justify-content: space-between; align-items: baseline; gap: var(--space-4); padding: 10px 0; border-bottom: 1px solid var(--border-soft); }
.info-label { font-size: 13px; color: var(--text-secondary); }
.info-value { font-size: 13px; color: var(--text-primary); text-align: right; }

/* modal framework */
.overlay-modal { position: fixed; inset: 0; z-index: 100; display: flex; align-items: center; justify-content: center; opacity: 0; pointer-events: none; transition: opacity 0.3s ease; }
.overlay-modal.active { opacity: 1; pointer-events: auto; }
.modal-backdrop { position: absolute; inset: 0; background-color: rgba(0, 0, 0, 0.35); }
.modal-wrapper {
    background-color: var(--surface-card); border: 1px solid var(--border-subtle);
    border-radius: var(--radius-lg); width: 90%; max-width: 800px;
    box-shadow: var(--shadow-md); position: relative; z-index: 101; overflow: hidden;
    transform: scale(0.95); transition: transform 0.3s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.overlay-modal.active .modal-wrapper { transform: scale(1); }
.modal-header { height: 56px; border-bottom: 1px solid var(--border-subtle); padding: 0 20px; display: flex; align-items: center; justify-content: space-between; }
.modal-title { font-weight: 600; font-size: 15px; }
.modal-body { padding: 24px; }

/* empty state */
.empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 64px 24px; text-align: center; }
.empty-state__icon { display: grid; place-items: center; width: 64px; height: 64px; border-radius: 50%; background: var(--surface-hover); color: var(--text-muted); margin-bottom: 16px; }
.empty-state__title { margin: 0 0 8px; font-size: 16px; font-weight: 600; }
.empty-state__desc { margin: 0 0 20px; font-size: 14px; color: var(--text-secondary); max-width: 380px; line-height: 1.5; }

/* toast */
.toast-container { position: fixed; bottom: 24px; right: 24px; display: flex; flex-direction: column; gap: 8px; z-index: 200; }
.toast {
    background-color: var(--surface-card); color: var(--text-primary);
    border: 1px solid var(--border-subtle); border-left: 4px solid var(--accent);
    border-radius: var(--radius-md); padding: 12px 20px; font-size: 13px; font-weight: 500;
    box-shadow: var(--shadow-md); min-width: 220px;
    animation: slideIn 0.3s cubic-bezier(0.175, 0.885, 0.32, 1.275);
}
.toast.success { border-left-color: var(--secondary); }
.toast.error { border-left-color: var(--error); }

/* quick-scroll FAB */
.scroll-fab-group { position: fixed; right: 28px; bottom: 28px; display: flex; flex-direction: column; gap: 8px; z-index: 40; pointer-events: none; }
.scroll-fab-btn {
    width: 38px; height: 38px; border-radius: 50%; background-color: var(--surface-card);
    border: 1px solid var(--border-subtle); color: var(--text-secondary);
    display: inline-flex; align-items: center; justify-content: center; cursor: pointer;
    box-shadow: var(--shadow-md); opacity: 0; visibility: hidden; transform: scale(0.85);
    backdrop-filter: blur(8px); transition: opacity .15s ease, transform .15s ease, …;
}
.scroll-fab-btn--visible { opacity: 0.85; visibility: visible; pointer-events: auto; transform: scale(1); }
```

## Web — reader chrome component classes (`css/views/reader.css` — the design target surface)

Full file is passed as context on reader design tasks; key class inventory:

- `.text-reader` — flex column, max-width 820px centered, reader-bg/fg; immersive: fixed inset-0
- `.text-reader__progress-track/-bar` — fixed 3px top progress line
- `.text-reader__header` — back btn (36px bordered square, "←") + title (15px/600 ellipsis) + header-actions (Aa + autoscroll icon buttons, 36px bordered)
- `.text-reader__content` — the reading surface: padding `32px max(24px, (100% - 720px)/2)`, reader font/size/line-height CSS vars, paragraphs `.text-reader__p` (`.indent-on` 2em / `.gap-on` 2.4em), `__chapter-title` (serif +6px centered + 40px underline), `__p--dropcap` (3.2em serif first-letter), `__chapter-end` "❖", `__image`, `__chapter-divider` (dashed), hover `__para-bookmark` "+" button
- `.text-reader__footer` — prev/next buttons + scrubber: `.text-reader__scrubber` (4px track, 14px accent thumb →18px on hover, floating label "第 x / y 章 · 本章 z%")
- `.text-reader__drawer` — right slide-in 280px panel: tabs (目录 / 书签 (n)) + `.text-reader__drawer-item` rows (active = accent-soft bg + 3px inset accent bar)
- `dialog#reader-settings-dialog` — 480px modal, header + scrollable body with 5 groups (外观/字号与行距/段落/行为/自定义颜色), theme swatch grid 3-col (28px circles), slider rows, toggle rows
- `.text-reader__autoscroll-panel` — floating bottom-center pill: play/pause + −/速度: 5/+

## Web — small shared JS modules

```js
// toast.js (full)
export function showToast(message, type = 'info') {
    const container = document.getElementById('toast-container');
    if (!container) return;
    const t = document.createElement('div');
    t.className = `toast ${type}`;
    t.textContent = message;
    container.appendChild(t);
    setTimeout(() => { t.style.opacity = '0'; t.style.transition = 'opacity 0.3s'; }, 3000);
    setTimeout(() => t.remove(), 3300);
}

// utils.js (full)
export function escapeHtml(s) {
    return String(s ?? '').replace(/[&<>"']/g, (c) => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
    }[c]));
}
export function formatBytes(bytes) { /* 1024-based B/KB/MB/GB with 1 decimal */ }

// dom.js — $(sel, root) / $$(sel, root) querySelector helpers + el() creator
```

## Android — shared Compose primitives (structure reference)

- `ui/component/GridContainers.kt` — responsive media grid (`MediaCardGrid`), phone/tablet adaptive columns
- `ui/component/MediaItems.kt` — `MediaCardItem` (thumbnail + name + meta + favorite/tag overlays), `FolderCardItem`, long-press menus
- `ui/component/TagComponents.kt` — tag chips + tag menu dialog
- `ui/component/ScrollFabGroup.kt` — ↑/↓ quick-scroll FAB pair with `calculateScrollFabVisibility`
- `ui/component/VerticalScrollbar.kt` / `reader/ReaderScrollbar.kt` — draggable thin progress rail (right edge)
- `ui/component/home/HomeComponents.kt` — Hero / Library / ContinueWatching / RecentMedia / FavoritePreview / DownloadedPreview / BookshelfCard / SectionHeader cards
- `ui/component/browse/*` — TopBar, SortMenu, SearchView, FilterChipsRow, QuickActionsDialog, DeleteConfirmDialog
- Reader-specific: `ReaderSettingsSheet` (ModalBottomSheet mirroring web settings dialog + BLE section), `ReaderThemeWrapper/Scope`, `PageTurnController/Simulator`, `ReaderFontFamily`
