# Image Preview Vertical Scrollbar Design

**Date:** 2026-04-08
**Status:** Approved
**Scope:** ImagePreviewScreen optimization

## Problem

When browsing images in a folder, all images are stitched vertically in a LazyColumn. When zoomed in, there's no way to quickly gauge or jump to a position within the image list. Users need a scrollbar to see where they are and jump to any position.

## Solution

Add a custom vertical scrollbar on the right side of the image list. The scrollbar shows current position and supports drag-to-seek.

## Design

### Component: `VerticalScrollbar`

A new Composable in `ui/component/VerticalScrollbar.kt`.

**Layout integration in ImagePreviewScreen:**
```
Box {
    Row {
        LazyColumn (weight=1f, fill remaining width)
        VerticalScrollbar (fixed 24dp width, right edge)
    }
    TopAppBar (overlay, existing)
}
```

### Progress Calculation

Derived from `LazyListState`:
- `progress = (firstVisibleItemIndex + firstVisibleItemScrollOffset / itemHeight) / totalItems`
- `thumbTop = progress * trackHeight`
- `thumbHeight = max(32dp, trackHeight * (visibleItems / totalItems))`

### Drag Interaction

1. Touch down on thumb → enter drag mode, consume events
2. Drag moves → calculate new progress → `listState.scrollToItem(index)`
3. Touch up → exit drag mode
4. Drag events do NOT propagate to LazyColumn (consumed by scrollbar)

### Visual Style

| Element | Normal | Dragging |
|---------|--------|----------|
| Track | Rounded rect, 24dp wide, `White 0.15` | Same |
| Thumb | Rounded rect, 6dp wide, `White 0.5`, height proportional | 10dp wide, `White 0.85` |
| Position | Right-aligned, 4dp from edge | Same |

### Gesture Conflict Avoidance

- Scrollbar occupies its own 24dp column to the right of LazyColumn
- Image zoom gestures are inside LazyColumn items (separate touch target)
- No overlap: zoom gestures are on image items, drag is on scrollbar column

## Files

| File | Change |
|------|--------|
| `ui/component/VerticalScrollbar.kt` | New — scrollbar composable with drag support |
| `ui/screen/ImagePreviewScreen.kt` | Modify — wrap LazyColumn in Row + add scrollbar |

## Acceptance Criteria

1. Scrollbar appears on right side, thumb reflects current scroll position
2. Dragging thumb scrolls the image list proportionally
3. Scrollbar does not interfere with image zoom gestures
4. TopAppBar page counter `"3 / 42"` still works
