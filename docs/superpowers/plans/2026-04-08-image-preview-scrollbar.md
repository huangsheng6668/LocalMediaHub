# Image Preview Scrollbar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a vertical scrollbar to the image preview screen that shows current position and supports drag-to-seek.

**Architecture:** Custom Composable `VerticalScrollbar` attached alongside the existing `LazyColumn` in `ImagePreviewScreen`. Progress is derived from `LazyListState`. Drag interaction uses `Modifier.pointerInput` within a dedicated 24dp column, separated from the image zoom gestures.

**Tech Stack:** Jetpack Compose, Kotlin, LazyListState

---

## File Structure

### New Files
| File | Responsibility |
|------|---------------|
| `android/app/src/main/java/com/juziss/localmediahub/ui/component/VerticalScrollbar.kt` | Scrollbar composable with progress display and drag-to-seek |

### Modified Files
| File | Change |
|------|--------|
| `android/app/src/main/java/com/juziss/localmediahub/ui/screen/ImagePreviewScreen.kt` | Wrap LazyColumn in Row + add VerticalScrollbar |

---

## Task 1: Create VerticalScrollbar Composable

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/ui/component/VerticalScrollbar.kt`

- [ ] **Step 1: Create VerticalScrollbar.kt**

```kotlin
package com.juziss.localmediahub.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * A vertical scrollbar that tracks [LazyListState] position and supports drag-to-seek.
 * Must be placed alongside the LazyColumn in a Row — not overlapping it.
 *
 * @param listState The LazyListState of the associated LazyColumn.
 * @param itemCount Total number of items in the LazyColumn.
 * @param modifier   Modifier for sizing (typically fillMaxHeight + fixed width).
 */
@Composable
fun VerticalScrollbar(
    listState: LazyListState,
    itemCount: Int,
    modifier: Modifier = Modifier,
) {
    if (itemCount <= 0) return

    // ---- Progress calculation ----
    val firstVisibleItem = listState.firstVisibleItemIndex
    val firstVisibleOffset = listState.firstVisibleItemScrollOffset

    // Approximate total scroll range: assume each item is roughly the same height.
    // We track a normalized progress [0..1].
    val progress by remember {
        derivedStateOf {
            if (itemCount <= 1) 0f
            else (firstVisibleItem.toFloat() + firstVisibleOffset.coerceAtLeast(0) / 1000f) / (itemCount - 1).coerceAtLeast(1)
        }
    }

    val visibleItemCount = listState.layoutInfo.visibleItemsInfo.size
    val thumbRatio = (visibleItemCount.toFloat() / itemCount.coerceAtLeast(1)).coerceIn(0.05f, 1f)

    var isDragging by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier) {
        val trackHeight = maxHeight
        val thumbHeight = (trackHeight * thumbRatio).coerceAtLeast(32.dp)
        val thumbTop = progress * (trackHeight - thumbHeight)

        // ---- Track ----
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(24.dp)
                .padding(horizontal = 9.dp)
                .background(
                    Color.White.copy(alpha = 0.15f),
                    RoundedCornerShape(2.dp)
                )
        )

        // ---- Thumb ----
        val thumbWidth = if (isDragging) 10.dp else 6.dp
        val thumbAlpha = if (isDragging) 0.85f else 0.5f

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(y = thumbTop)
                .fillMaxHeight()
                .height(thumbHeight)
                .width(24.dp)
                .padding(
                    start = if (isDragging) 6.dp else 8.dp,
                    end = if (isDragging) 8.dp else 10.dp
                )
                .background(
                    Color.White.copy(alpha = thumbAlpha),
                    RoundedCornerShape(3.dp)
                )
                .pointerInput(itemCount) {
                    var dragStartY = 0f
                    var dragStartProgress = 0f

                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Press -> {
                                    val change = event.changes.first()
                                    dragStartY = change.position.y
                                    dragStartProgress = progress
                                    isDragging = true
                                    change.consume()
                                }
                                PointerEventType.Move -> {
                                    if (isDragging) {
                                        val change = event.changes.first()
                                        val dy = change.position.y - dragStartY
                                        val trackPx = this.size.height.toFloat()
                                        if (trackPx > 0f) {
                                            val newProgress = (dragStartProgress + dy / trackPx)
                                                .coerceIn(0f, 1f)
                                            val targetIndex = (newProgress * (itemCount - 1)).toInt()
                                                .coerceIn(0, itemCount - 1)
                                            listState.scrollToItem(targetIndex)
                                        }
                                        change.consume()
                                    }
                                }
                                PointerEventType.Release -> {
                                    isDragging = false
                                }
                            }
                        }
                    }
                }
        )
    }
}
```

- [ ] **Step 2: Verify file compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/VerticalScrollbar.kt
git commit -m "feat: add VerticalScrollbar composable with drag-to-seek"
```

---

## Task 2: Integrate scrollbar into ImagePreviewScreen

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/ImagePreviewScreen.kt`

- [ ] **Step 1: Update ImagePreviewScreen layout**

Replace the existing `Box` content in `ImagePreviewScreen` (lines 57–101) with:

```kotlin
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Main content: LazyColumn + scrollbar side by side
        Row(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(imageList, key = { _, file -> file.relativePath }) { _, file ->
                    ZoomableImageItem(
                        file = file,
                        imageUrl = getOriginalUrl(file),
                    )
                }
            }

            VerticalScrollbar(
                listState = listState,
                itemCount = imageList.size,
                modifier = Modifier.fillMaxHeight(),
            )
        }

        // Top bar
        TopAppBar(
            title = {
                Text(
                    "${visibleIndex + 1} / ${imageList.size}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Black.copy(alpha = 0.7f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding(),
        )
    }
```

Add the import at the top of the file:

```kotlin
import com.juziss.localmediahub.ui.component.VerticalScrollbar
```

- [ ] **Step 2: Build and verify**

Run: `cd android && ./gradlew :app:assembleRelease`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/screen/ImagePreviewScreen.kt
git commit -m "feat: integrate vertical scrollbar into image preview screen"
```

---

## Dependency Graph

```
Task 1 (VerticalScrollbar composable) ──── Task 2 (integrate into ImagePreviewScreen)
```

Task 2 depends on Task 1. Total: 2 tasks.
