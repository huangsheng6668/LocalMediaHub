# Android Quick Scroll Navigation (Top/Bottom) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide a floating action button (FAB) navigation in LocalMediaHub's Android client that smoothly scrolls to top and bottom in both the media browser list and the text reader, with smart visibility logic.

**Architecture:** A reusable Composable `ScrollFabGroup` with a pure mathematical helper `calculateScrollFabVisibility`, integrated into `BrowseContent.kt` for grid/staggered views and `TextReaderScreen.kt` for reader content, linked with reader chrome visibility and offset from `ReaderScrollbar`.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Coroutines, JUnit 4.

## Global Constraints

- Android Material 3 design system compliance (`primaryContainer`, `onPrimaryContainer`, `40.dp` FAB).
- DefensiveCompose: no ripple crash risks (standard `SmallFloatingActionButton` or Compose M3 FAB without broken Foundation 1.11 clickable).
- Zero regressions in existing `ReaderScrollbar` and reading modes (chapter/scroll).
- Pure logic 100% covered by JVM unit tests (`testDebugUnitTest`).

---

### Task 1: Core Component `ScrollFabGroup` & Unit Tests

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/ui/component/ScrollFabGroup.kt`
- Create: `android/app/src/test/java/com/juziss/localmediahub/ui/component/ScrollFabVisibilityTest.kt`

**Interfaces:**
- Produces:
  - `data class ScrollFabVisibility(val canScrollToTop: Boolean, val canScrollToBottom: Boolean)`
  - `fun calculateScrollFabVisibility(firstVisibleIndex: Int, firstVisibleOffset: Int, lastVisibleIndex: Int, totalItems: Int, visibleCount: Int, offsetThreshold: Int = 100): ScrollFabVisibility`
  - `@Composable fun ScrollFabGroup(...)`

- [ ] **Step 1: Write unit tests in `ScrollFabVisibilityTest.kt`**

```kotlin
package com.juziss.localmediahub.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollFabVisibilityTest {

    @Test
    fun `empty or single item list shows neither top nor bottom`() {
        val res0 = calculateScrollFabVisibility(
            firstVisibleIndex = 0,
            firstVisibleOffset = 0,
            lastVisibleIndex = 0,
            totalItems = 0,
            visibleCount = 0,
        )
        assertFalse(res0.canScrollToTop)
        assertFalse(res0.canScrollToBottom)

        val res1 = calculateScrollFabVisibility(
            firstVisibleIndex = 0,
            firstVisibleOffset = 0,
            lastVisibleIndex = 0,
            totalItems = 1,
            visibleCount = 1,
        )
        assertFalse(res1.canScrollToTop)
        assertFalse(res1.canScrollToBottom)
    }

    @Test
    fun `content fully visible on screen shows neither top nor bottom`() {
        val res = calculateScrollFabVisibility(
            firstVisibleIndex = 0,
            firstVisibleOffset = 0,
            lastVisibleIndex = 4,
            totalItems = 5,
            visibleCount = 5,
        )
        assertFalse(res.canScrollToTop)
        assertFalse(res.canScrollToBottom)
    }

    @Test
    fun `at top of long list shows only bottom button`() {
        val res = calculateScrollFabVisibility(
            firstVisibleIndex = 0,
            firstVisibleOffset = 50,
            lastVisibleIndex = 8,
            totalItems = 50,
            visibleCount = 9,
            offsetThreshold = 100,
        )
        assertFalse(res.canScrollToTop)
        assertTrue(res.canScrollToBottom)
    }

    @Test
    fun `in middle of long list shows both top and bottom buttons`() {
        val res = calculateScrollFabVisibility(
            firstVisibleIndex = 15,
            firstVisibleOffset = 0,
            lastVisibleIndex = 23,
            totalItems = 50,
            visibleCount = 9,
            offsetThreshold = 100,
        )
        assertTrue(res.canScrollToTop)
        assertTrue(res.canScrollToBottom)
    }

    @Test
    fun `at bottom of long list shows only top button`() {
        val res = calculateScrollFabVisibility(
            firstVisibleIndex = 42,
            firstVisibleOffset = 150,
            lastVisibleIndex = 49,
            totalItems = 50,
            visibleCount = 8,
            offsetThreshold = 100,
        )
        assertTrue(res.canScrollToTop)
        assertFalse(res.canScrollToBottom)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android; ./gradlew testDebugUnitTest --tests com.juziss.localmediahub.ui.component.ScrollFabVisibilityTest`
Expected: FAIL (unresolved reference `calculateScrollFabVisibility`)

- [ ] **Step 3: Implement `ScrollFabGroup.kt`**

Create `android/app/src/main/java/com/juziss/localmediahub/ui/component/ScrollFabGroup.kt`:
```kotlin
package com.juziss.localmediahub.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juziss.localmediahub.R

data class ScrollFabVisibility(
    val canScrollToTop: Boolean,
    val canScrollToBottom: Boolean,
)

/**
 * Pure calculation function for determining whether the scroll-to-top and
 * scroll-to-bottom FABs should be visible.
 */
fun calculateScrollFabVisibility(
    firstVisibleIndex: Int,
    firstVisibleOffset: Int,
    lastVisibleIndex: Int,
    totalItems: Int,
    visibleCount: Int,
    offsetThreshold: Int = 100,
): ScrollFabVisibility {
    if (totalItems <= 1 || visibleCount >= totalItems) {
        return ScrollFabVisibility(canScrollToTop = false, canScrollToBottom = false)
    }
    val atTop = firstVisibleIndex == 0 && firstVisibleOffset <= offsetThreshold
    val atBottom = lastVisibleIndex >= totalItems - 1
    return ScrollFabVisibility(
        canScrollToTop = !atTop,
        canScrollToBottom = !atBottom,
    )
}

/**
 * Floating Action Button pair for quick scroll to top and bottom.
 * Supports smooth fade and scale enter/exit animations for each button independently.
 */
@Composable
fun ScrollFabGroup(
    canScrollToTop: Boolean,
    canScrollToBottom: Boolean,
    onScrollToTop: () -> Unit,
    onScrollToBottom: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    if (!canScrollToTop && !canScrollToBottom) return

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(
            visible = canScrollToTop,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
        ) {
            FloatingActionButton(
                onClick = onScrollToTop,
                modifier = Modifier.size(40.dp),
                containerColor = containerColor,
                contentColor = contentColor,
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.content_to_top),
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = canScrollToBottom,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
        ) {
            FloatingActionButton(
                onClick = onScrollToBottom,
                modifier = Modifier.size(40.dp),
                containerColor = containerColor,
                contentColor = contentColor,
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.content_to_bottom),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android; ./gradlew testDebugUnitTest --tests com.juziss.localmediahub.ui.component.ScrollFabVisibilityTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/ScrollFabGroup.kt android/app/src/test/java/com/juziss/localmediahub/ui/component/ScrollFabVisibilityTest.kt
git commit -m "feat(android): add ScrollFabGroup component and visibility unit tests"
```

---

### Task 2: Upgrade `BrowseContent.kt` with Smart Visibility FAB

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/BrowseContent.kt:425-472`

**Interfaces:**
- Consumes: `ScrollFabGroup`, `calculateScrollFabVisibility`

- [ ] **Step 1: Replace hardcoded FABs in `BrowseContent.kt` with `ScrollFabGroup`**

In `BrowseContent.kt`:
Calculate visibility using `derivedStateOf`:
```kotlin
        val totalItems = if (useStaggeredGrid) images.size else folders.size + files.size
        val fabVisibility by remember(useStaggeredGrid, totalItems) {
            derivedStateOf {
                if (useStaggeredGrid) {
                    val info = staggeredState.layoutInfo
                    val visibleItems = info.visibleItemInfo
                    val firstIndex = staggeredState.firstVisibleItemIndex
                    val firstOffset = staggeredState.firstVisibleItemScrollOffset
                    val lastIndex = visibleItems.lastOrNull()?.index ?: 0
                    calculateScrollFabVisibility(firstIndex, firstOffset, lastIndex, totalItems, visibleItems.size)
                } else {
                    val info = gridState.layoutInfo
                    val visibleItems = info.visibleItemsInfo
                    val firstIndex = gridState.firstVisibleItemIndex
                    val firstOffset = gridState.firstVisibleItemScrollOffset
                    val lastIndex = visibleItems.lastOrNull()?.index ?: 0
                    calculateScrollFabVisibility(firstIndex, firstOffset, lastIndex, totalItems, visibleItems.size)
                }
            }
        }
```
And replace the FAB column:
```kotlin
        ScrollFabGroup(
            canScrollToTop = fabVisibility.canScrollToTop,
            canScrollToBottom = fabVisibility.canScrollToBottom,
            onScrollToTop = {
                scope.launch {
                    if (useStaggeredGrid) staggeredState.animateScrollToItem(0)
                    else gridState.animateScrollToItem(0)
                }
            },
            onScrollToBottom = {
                scope.launch {
                    val lastIndex = (totalItems - 1).coerceAtLeast(0)
                    if (useStaggeredGrid) staggeredState.animateScrollToItem(lastIndex)
                    else gridState.animateScrollToItem(lastIndex)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp),
        )
```

- [ ] **Step 2: Run tests to verify**

Run: `cd android; ./gradlew testDebugUnitTest`
Expected: ALL PASS

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/BrowseContent.kt
git commit -m "feat(browse): upgrade floating scroll buttons to smart ScrollFabGroup"
```

---

### Task 3: Integrate `ScrollFabGroup` into `TextReaderScreen.kt`

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt`

**Interfaces:**
- Consumes: `ScrollFabGroup`, `calculateScrollFabVisibility`, `isChromeVisible`

- [ ] **Step 1: Integrate `ScrollFabGroup` in `TextReaderScreen.kt`**

Import `com.juziss.localmediahub.ui.component.ScrollFabGroup` and `com.juziss.localmediahub.ui.component.calculateScrollFabVisibility`.
In the reading content container (`Box(Modifier.fillMaxSize(), ...)`):
Compute:
```kotlin
        val readerFabVisibility by remember(listState) {
            derivedStateOf {
                val info = listState.layoutInfo
                val visibleItems = info.visibleItemsInfo
                val totalItems = info.totalItemsCount
                val firstIndex = listState.firstVisibleItemIndex
                val firstOffset = listState.firstVisibleItemScrollOffset
                val lastIndex = visibleItems.lastOrNull()?.index ?: 0
                calculateScrollFabVisibility(firstIndex, firstOffset, lastIndex, totalItems, visibleItems.size)
            }
        }
```
Render FAB group with chrome visibility linkage and `ReaderScrollbar` clearance:
```kotlin
        // 快速置顶/置底悬浮按钮组：与上下工具栏联动，全屏沉浸阅读时自动淡出防遮挡正文
        AnimatedVisibility(
            visible = isChromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 36.dp, bottom = 80.dp),
        ) {
            ScrollFabGroup(
                canScrollToTop = readerFabVisibility.canScrollToTop,
                canScrollToBottom = readerFabVisibility.canScrollToBottom,
                onScrollToTop = {
                    scope.launch { listState.animateScrollToItem(0) }
                },
                onScrollToBottom = {
                    val total = listState.layoutInfo.totalItemsCount
                    if (total > 0) {
                        scope.launch { listState.animateScrollToItem(total - 1) }
                    }
                },
            )
        }
```

- [ ] **Step 2: Run unit tests and assembleDebug**

Run: `cd android; ./gradlew testDebugUnitTest`
Expected: ALL PASS
Run: `cd android; ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt
git commit -m "feat(reader): add smart ScrollFabGroup with chrome auto-hide linkage"
```
