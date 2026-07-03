# BrowseViewModel Decoupling Implementation Plan (Round 10)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove `viewModel: BrowseViewModel` from `BrowseContent`, `BrowseStateContent`, and `TagMenuDialog`; introduce a `BrowseContentState` data class; add the first Robolectric Compose UI tests proving they're independently testable — with zero UI behavior change.

**Architecture:** `BrowseScreen` becomes the single state-collection + wiring point (it newly collects `restoreScrollTo`, builds `BrowseContentState`, computes `fileTags`, and provides all callbacks). The three composables become pure (data + callbacks + pure fns); `BrowseStateContent` forwards `state` + scroll/thumbnail callbacks to `BrowseContent`. `BrowseViewModel`'s behavior and public API are unchanged.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Robolectric 4.13 + `ui-test-junit4` for JVM Compose tests. Build: `./gradlew`.

**Spec:** `docs/superpowers/specs/2026-07-04-browse-decouple-viewmodel-design.md`

## Global Constraints

(Each task's requirements implicitly include these. Values copied verbatim from the spec.)

- **Branch / sync:** `master`, NO worktree, auto-sync to GitHub `master` (user-consented, rounds 7–9). Each task = one commit, message suffix `(round 10 task N)`.
- **UI behavior-preserving.** Same rendering, scroll save/restore, sort-to-top, tag toggle, error retry. `BrowseViewModel`'s behavior and public API MUST NOT change. Do not touch `BrowseSearchView` / `BrowseFavoritesView` (already viewModel-free).
- **Verification per task:** `cd android && ./gradlew testDebugUnitTest assembleDebug` → `BUILD SUCCESSFUL`, existing unit tests pass, AND any new Robolectric Compose tests added by that task pass.
- **TDD applies this round** (real behavior tests). For each composable test: write the test (references the new signature → fails to compile against the old = RED), implement the decoupling, run (GREEN), commit.
- **`Tag.id` is `String`.** Tag/untag callbacks are `(String) -> Unit` (the tag id).
- **New state class:** `data class BrowseContentState(folderSort: SortOrder, fileSort: SortOrder, currentPath: String, restoreScrollTo: String?)` in package `com.juziss.localmediahub.ui.component.browse` (file `ui/component/browse/BrowseContentState.kt`).
- **Test runner:** Robolectric JVM. Tests live in `android/app/src/test/java/com/juziss/localmediahub/ui/browse/`. Annotations: `@RunWith(RobolectricTestRunner::class)` + `@GraphicsMode(GraphicsMode.Mode.NATIVE_ROBOLECTRIC)` + `createAndroidComposeRule<ComponentActivity>()`. Fallback: if a specific test won't run on Robolectric, move just that test to `androidTest` (instrumented).
- **New test dep:** add `testImplementation("androidx.compose.ui:ui-test-junit4")` to `android/app/build.gradle.kts` (Robolectric 4.13 and `androidx.activity:activity-compose:1.8.2` already present; `ui-test-manifest` already in `debugImplementation`).
- **Type locations:** `MediaFile`/`Folder`/`Tag`/`BrowseResult` → `com.juziss.localmediahub.data`; `BrowseViewModel`/`BrowseState`/`SortOrder` → `com.juziss.localmediahub.viewmodel`.

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `android/app/build.gradle.kts` | Modify | Add `testImplementation("androidx.compose.ui:ui-test-junit4")` |
| `android/app/src/test/java/com/juziss/localmediahub/ui/browse/ComposeSmokeTest.kt` | Create | Validate Robolectric+Compose harness |
| `android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseContentState.kt` | Create | The grid's reactive state |
| `android/app/src/main/java/com/juziss/localmediahub/ui/component/BrowseContent.kt` | Modify | Drop viewModel; read `state` + callbacks |
| `android/app/src/test/java/com/juziss/localmediahub/ui/browse/BrowseContentTest.kt` | Create | Renders without VM + scroll-save callback |
| `android/app/src/main/java/com/juziss/localmediahub/ui/component/TagComponents.kt` | Modify | `TagMenuDialog` drops viewModel |
| `android/app/src/test/java/com/juziss/localmediahub/ui/browse/TagMenuDialogTest.kt` | Create | Toggle callbacks fire with correct id |
| `android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseStateContent.kt` | Modify | Drop viewModel; take `state` + callbacks |
| `android/app/src/test/java/com/juziss/localmediahub/ui/browse/BrowseStateContentTest.kt` | Create | Error state retry |
| `android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt` | Modify | Build `contentState`; compute `fileTags`; wire all callbacks |

`BrowseSearchView` / `BrowseFavoritesView` are NOT touched (already viewModel-free from round 9).

---

### Task 1: Robolectric + Compose test infrastructure

**Files:**
- Modify: `android/app/build.gradle.kts` (testImplementation block, ~line 170–177)
- Create: `android/app/src/test/java/com/juziss/localmediahub/ui/browse/ComposeSmokeTest.kt`

**Interfaces:** none (infra task).

- [ ] **Step 1: Add the test dependency**

In `android/app/build.gradle.kts`, in the `dependencies { … }` block, add this line immediately after the existing `testImplementation("org.robolectric:robolectric:4.13")` line:

```kotlin
    testImplementation("androidx.compose.ui:ui-test-junit4")
```

- [ ] **Step 2: Write the smoke test**

Create `android/app/src/test/java/com/juziss/localmediahub/ui/browse/ComposeSmokeTest.kt`:

```kotlin
package com.juziss.localmediahub.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE_ROBOLECTRIC)
class ComposeSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun renders_text() {
        composeRule.setContent { Text("round10-smoke") }
        composeRule.onNodeWithText("round10-smoke").assertIsDisplayed()
    }
}
```

- [ ] **Step 3: Run it**

Run: `cd android && ./gradlew testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `ComposeSmokeTest.renders_text` PASSES. This validates the Robolectric + Compose harness. **If it fails** with a setup error (e.g. missing `ui-test-manifest`, SDK config, or application class), add `testImplementation("androidx.compose.ui:ui-test-manifest")` and/or an `@Config` annotation per the error, re-run until green. Do not proceed to Task 2 until the smoke test passes.

- [ ] **Step 4: Commit**

```bash
git add android/app/build.gradle.kts android/app/src/test/java/com/juziss/localmediahub/ui/browse/ComposeSmokeTest.kt
git commit -m "test(android): add Robolectric Compose test harness (round 10 task 1)"
```

---

### Task 2: `BrowseContentState` + decouple `BrowseContent`

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseContentState.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/BrowseContent.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseStateContent.kt` (transitional: update its internal `BrowseContent` calls)
- Create: `android/app/src/test/java/com/juziss/localmediahub/ui/browse/BrowseContentTest.kt`

**Interfaces:**
- Produces: `data class BrowseContentState(folderSort: SortOrder, fileSort: SortOrder, currentPath: String, restoreScrollTo: String?)` and the new `BrowseContent` signature (see Step 4). Later tasks consume both.
- Consumes: nothing from earlier tasks.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/juziss/localmediahub/ui/browse/BrowseContentTest.kt`:

```kotlin
package com.juziss.localmediahub.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertExists
import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.ui.component.BrowseContent
import com.juziss.localmediahub.ui.component.browse.BrowseContentState
import com.juziss.localmediahub.viewmodel.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE_ROBOLECTRIC)
class BrowseContentTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun file(name: String, type: String = "video") = MediaFile(
        name = name, path = "/$name", relativePath = name,
        size = 1L, modifiedTime = "", mediaType = type,
        extension = if (type == "video") "mp4" else "jpg",
    )

    @Test
    fun renders_folders_and_files_without_view_model() {
        var saved: Pair<String, Int>? = null
        composeRule.setContent {
            BrowseContent(
                folders = listOf(Folder(name = "Films", path = "/Films", relativePath = "Films")),
                files = listOf(file("v.mp4")),
                onFolderClick = {}, onVideoClick = {}, onImageClick = {},
                onToggleFavorite = {}, isFavorite = { false },
                state = BrowseContentState(SortOrder.NAME_ASC, SortOrder.NAME_ASC, "/Films", null),
                onSaveScrollPosition = { p, i -> saved = p to i },
                onConsumeRestoreScroll = {},
                getScrollPosition = { 0 },
                getThumbnailUrl = { "" },
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Films").assertExists()
        assertNotNull("save-scroll LaunchedEffect should fire on initial composition", saved)
        assertEquals("/Films" to 0, saved)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*BrowseContentTest"`
Expected: COMPILE FAILURE — `BrowseContent` has no `state` / `onSaveScrollPosition` / … params and `BrowseContentState` is unresolved. (RED — the new signature does not exist yet.)

- [ ] **Step 3: Create `BrowseContentState`**

Create `android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseContentState.kt`:

```kotlin
package com.juziss.localmediahub.ui.component.browse

import com.juziss.localmediahub.viewmodel.SortOrder

/**
 * Reactive state the browse content grid needs (sort order + navigation path
 * + scroll-restore target). Built once in BrowseScreen from collected flows
 * and passed down; BrowseContent no longer reads BrowseViewModel directly.
 */
data class BrowseContentState(
    val folderSort: SortOrder,
    val fileSort: SortOrder,
    val currentPath: String,
    val restoreScrollTo: String?,
)
```

- [ ] **Step 4: Decouple `BrowseContent` — signature + imports**

In `android/app/src/main/java/com/juziss/localmediahub/ui/component/BrowseContent.kt`:

Remove the import:
```kotlin
import com.juziss.localmediahub.viewmodel.BrowseViewModel
```
Add the import:
```kotlin
import com.juziss.localmediahub.ui.component.browse.BrowseContentState
```

Replace the signature tail (the last two params):
```kotlin
    onFileLongClick: (MediaFile) -> Unit = {},
    onFolderLongClick: (Folder) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: BrowseViewModel,
) {
```
with:
```kotlin
    onFileLongClick: (MediaFile) -> Unit = {},
    onFolderLongClick: (Folder) -> Unit = {},
    state: BrowseContentState,
    onSaveScrollPosition: (path: String, index: Int) -> Unit,
    onConsumeRestoreScroll: () -> Unit,
    getScrollPosition: (path: String) -> Int,
    getThumbnailUrl: (file: MediaFile) -> String,
    modifier: Modifier = Modifier,
) {
```

- [ ] **Step 5: Replace the four `collectAsState` reads with state-field reads**

Replace:
```kotlin
    val folderSortOrder by viewModel.folderSortOrder.collectAsState()
    val fileSortOrder by viewModel.fileSortOrder.collectAsState()
    val gridState = rememberLazyGridState()
    val staggeredState = rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()
    val restorePath by viewModel.restoreScrollTo.collectAsState()
    val currentPath by viewModel.currentPath.collectAsState()
```
with:
```kotlin
    val folderSortOrder = state.folderSort
    val fileSortOrder = state.fileSort
    val gridState = rememberLazyGridState()
    val staggeredState = rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()
    val restorePath = state.restoreScrollTo
    val currentPath = state.currentPath
```

(The body references `folderSortOrder` / `fileSortOrder` / `restorePath` / `currentPath` unchanged — they are now plain `val`s reading from `state`.)

- [ ] **Step 6: Replace the five `viewModel.*` call sites**

In the save-scroll `LaunchedEffect`:
```kotlin
        viewModel.saveScrollPosition(currentPath, index)
```
→
```kotlin
        onSaveScrollPosition(currentPath, index)
```

In the restore-scroll `LaunchedEffect`:
```kotlin
            val savedIndex = viewModel.getScrollPosition(restorePath!!)
```
→
```kotlin
            val savedIndex = getScrollPosition(restorePath!!)
```
and
```kotlin
            viewModel.consumeRestoreScroll()
```
→
```kotlin
            onConsumeRestoreScroll()
```

In the `WaterfallImageGrid` call:
```kotlin
                getThumbnailUrl = viewModel::getThumbnailUrl,
```
→
```kotlin
                getThumbnailUrl = getThumbnailUrl,
```

In the `VideoCard` and `ImageCard` calls, replace BOTH occurrences of:
```kotlin
                            thumbnailUrl = viewModel.getThumbnailUrl(file),
```
→
```kotlin
                            thumbnailUrl = getThumbnailUrl(file),
```

- [ ] **Step 7: Transitional update — `BrowseStateContent`'s internal `BrowseContent` calls**

`BrowseStateContent` (in `ui/component/browse/BrowseStateContent.kt`) is the only caller of `BrowseContent`. In Task 2 it still keeps its `viewModel` param (Task 4 removes it), but its three internal `BrowseContent(…)` calls (in the `SystemBrowsed`, `Browsed`, and `TagCollection` branches) must pass the new params. To DRY this, add a local at the top of `BrowseStateContent`'s body (right after its opening `{`, before the `when (browseState)`):

```kotlin
    val contentState = BrowseContentState(
        folderSort = viewModel.folderSortOrder.value,
        fileSort = viewModel.fileSortOrder.value,
        currentPath = currentPath,
        restoreScrollTo = viewModel.restoreScrollTo.value,
    )
```

Then in each of the three `BrowseContent(…)` calls, replace the trailing argument:
```kotlin
                        viewModel = viewModel,
```
with:
```kotlin
                        state = contentState,
                        onSaveScrollPosition = viewModel::saveScrollPosition,
                        onConsumeRestoreScroll = viewModel::consumeRestoreScroll,
                        getScrollPosition = viewModel::getScrollPosition,
                        getThumbnailUrl = viewModel::getThumbnailUrl,
```

(`StateFlow.value` reads the current value synchronously — behavior-identical to the previous `collectAsState` for this transitional step.)

- [ ] **Step 8: Run the test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*BrowseContentTest"`
Expected: PASS.

- [ ] **Step 9: Full gate**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: `BUILD SUCCESSFUL`; all existing tests + the new `BrowseContentTest` pass.

- [ ] **Step 10: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseContentState.kt \
        android/app/src/main/java/com/juziss/localmediahub/ui/component/BrowseContent.kt \
        android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseStateContent.kt \
        android/app/src/test/java/com/juziss/localmediahub/ui/browse/BrowseContentTest.kt
git commit -m "refactor(android): decouple BrowseContent from BrowseViewModel (round 10 task 2)"
```

---

### Task 3: Decouple `TagMenuDialog`

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/TagComponents.kt` (`TagMenuDialog`, ~line 67)
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt` (the `TagMenuDialog(…)` call site)
- Create: `android/app/src/test/java/com/juziss/localmediahub/ui/browse/TagMenuDialogTest.kt`

**Interfaces:**
- Produces: the new `TagMenuDialog(file, tags, fileTags, onTagFile: (String)->Unit, onUntagFile: (String)->Unit, onDismiss)` signature.
- Consumes: nothing from earlier tasks.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/juziss/localmediahub/ui/browse/TagMenuDialogTest.kt`:

```kotlin
package com.juziss.localmediahub.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.Tag
import com.juziss.localmediahub.ui.component.TagMenuDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE_ROBOLECTRIC)
class TagMenuDialogTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val file = MediaFile(
        name = "movie.mp4", path = "/movie.mp4", relativePath = "movie.mp4",
        size = 1L, modifiedTime = "", mediaType = "video", extension = "mp4",
    )

    @Test
    fun renders_all_tag_names() {
        composeRule.setContent {
            TagMenuDialog(
                file = file,
                tags = listOf(Tag("1", "Music"), Tag("2", "Work")),
                fileTags = emptyList(),
                onTagFile = {}, onUntagFile = {}, onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Music").assertExists()
        composeRule.onNodeWithText("Work").assertExists()
    }

    @Test
    fun click_unapplied_applies_and_click_applied_removes() {
        val applied = mutableListOf<String>()
        val removed = mutableListOf<String>()
        composeRule.setContent {
            TagMenuDialog(
                file = file,
                tags = listOf(Tag("1", "Music"), Tag("2", "Work")),
                fileTags = listOf(Tag("2", "Work")), // "Work" is applied
                onTagFile = { applied.add(it) },
                onUntagFile = { removed.add(it) },
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Music").performClick()   // unapplied -> onTagFile("1")
        assertEquals(listOf("1"), applied)
        composeRule.onNodeWithText("Work").performClick()    // applied -> onUntagFile("2")
        assertEquals(listOf("2"), removed)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*TagMenuDialogTest"`
Expected: COMPILE FAILURE — `TagMenuDialog` has no `fileTags` / `onTagFile` / `onUntagFile` params (RED).

- [ ] **Step 3: Decouple `TagMenuDialog`**

In `android/app/src/main/java/com/juziss/localmediahub/ui/component/TagComponents.kt`, replace the signature + the `fileTags` computation:

```kotlin
@Composable
internal fun TagMenuDialog(
    file: MediaFile,
    tags: List<Tag>,
    viewModel: BrowseViewModel,
    onDismiss: () -> Unit,
) {
    val fileTags = viewModel.getTagsForFile(file.relativePath)
```
with:

```kotlin
@Composable
internal fun TagMenuDialog(
    file: MediaFile,
    tags: List<Tag>,
    fileTags: List<Tag>,
    onTagFile: (tagId: String) -> Unit,
    onUntagFile: (tagId: String) -> Unit,
    onDismiss: () -> Unit,
) {
```

Then replace the clickable-row block:
```kotlin
                                .clickable {
                                    if (isApplied) {
                                        viewModel.untagFile(tag.id, file.relativePath)
                                    } else {
                                        viewModel.tagFile(tag.id, file.relativePath)
                                    }
                                }
```
→
```kotlin
                                .clickable {
                                    if (isApplied) onUntagFile(tag.id) else onTagFile(tag.id)
                                }
```
And the `Checkbox` `onCheckedChange`:
```kotlin
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        viewModel.tagFile(tag.id, file.relativePath)
                                    } else {
                                        viewModel.untagFile(tag.id, file.relativePath)
                                    }
                                },
```
→
```kotlin
                                onCheckedChange = { checked ->
                                    if (checked) onTagFile(tag.id) else onUntagFile(tag.id)
                                },
```

Remove the now-unused `import com.juziss.localmediahub.viewmodel.BrowseViewModel` from `TagComponents.kt`.

- [ ] **Step 4: Update the `BrowseScreen` call site**

In `android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt`:
```kotlin
        val taggedFile = showTagMenuForFile
        if (taggedFile != null) {
            TagMenuDialog(
                file = taggedFile,
                tags = tags,
                viewModel = viewModel,
                onDismiss = { showTagMenuForFile = null },
            )
        }
```
→
```kotlin
        val taggedFile = showTagMenuForFile
        if (taggedFile != null) {
            TagMenuDialog(
                file = taggedFile,
                tags = tags,
                fileTags = viewModel.getTagsForFile(taggedFile.relativePath),
                onTagFile = { id -> viewModel.tagFile(id, taggedFile.relativePath) },
                onUntagFile = { id -> viewModel.untagFile(id, taggedFile.relativePath) },
                onDismiss = { showTagMenuForFile = null },
            )
        }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*TagMenuDialogTest"`
Expected: PASS (both tests).

- [ ] **Step 6: Full gate**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: `BUILD SUCCESSFUL`; all tests pass.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/TagComponents.kt \
        android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt \
        android/app/src/test/java/com/juziss/localmediahub/ui/browse/TagMenuDialogTest.kt
git commit -m "refactor(android): decouple TagMenuDialog from BrowseViewModel (round 10 task 3)"
```

---

### Task 4: Decouple `BrowseStateContent` + wire `BrowseScreen`

`BrowseStateContent` uses `viewModel` for: Error retry (`loadSystemDrives`/`loadRoots`), navigation (`browseFolder`, `browseSystemPath`), the tag filter (`setActiveTagFilter`), file-tag filtering (`filterFilesByTag`), and forwarding scroll/thumbnail to `BrowseContent`. Each becomes a callback.

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseStateContent.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt`
- Create: `android/app/src/test/java/com/juziss/localmediahub/ui/browse/BrowseStateContentTest.kt`

**Interfaces:**
- Produces: the fully-decoupled `BrowseStateContent` (no viewModel).
- Consumes: `BrowseContentState` + the new `BrowseContent` signature (Task 2).

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/juziss/localmediahub/ui/browse/BrowseStateContentTest.kt`:

```kotlin
package com.juziss.localmediahub.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.juziss.localmediahub.R
import com.juziss.localmediahub.ui.component.browse.BrowseContentState
import com.juziss.localmediahub.ui.component.browse.BrowseStateContent
import com.juziss.localmediahub.viewmodel.BrowseState
import com.juziss.localmediahub.viewmodel.SortOrder
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE_ROBOLECTRIC)
class BrowseStateContentTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun error_state_retry_button_invokes_onRetry() {
        var retried = false
        composeRule.setContent {
            BrowseStateContent(
                browseState = BrowseState.Error("boom"),
                state = BrowseContentState(SortOrder.NAME_ASC, SortOrder.NAME_ASC, "", null),
                isSystemBrowse = false,
                tags = emptyList(),
                activeTagFilter = null,
                onVideoClick = {}, onImageClick = {},
                onToggleFavorite = {}, isFavorite = { false },
                onFileLongClick = {}, onFolderLongClick = {},
                onRetry = { retried = true },
                onBrowseFolder = { _, _ -> },
                onBrowseSystemPath = { _, _ -> },
                onActiveTagFilterChange = {},
                filterFilesByTag = { it },
                onSaveScrollPosition = { _, _ -> },
                onConsumeRestoreScroll = {},
                getScrollPosition = { 0 },
                getThumbnailUrl = { "" },
            )
        }
        val retry = composeRule.activity.getString(R.string.browse_retry)
        composeRule.onNodeWithText(retry).performClick()
        assertTrue(retried)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*BrowseStateContentTest"`
Expected: COMPILE FAILURE — `BrowseStateContent` has no `onRetry` / `state` / navigation params and still requires `viewModel` (RED).

- [ ] **Step 3: Decouple `BrowseStateContent` — replace the signature**

In `android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseStateContent.kt`, replace the signature (drop `viewModel`; drop the standalone `currentPath` param — it now comes from `state.currentPath`; add the navigation/retry/forwarded callbacks):

```kotlin
@Composable
internal fun BrowseStateContent(
    browseState: BrowseState,
    currentPath: String,
    isSystemBrowse: Boolean,
    tags: List<Tag>,
    activeTagFilter: Tag?,
    onVideoClick: (MediaFile) -> Unit,
    onImageClick: (MediaFile, List<MediaFile>) -> Unit,
    onToggleFavorite: (MediaFile) -> Unit,
    isFavorite: (String) -> Boolean,
    onFileLongClick: (MediaFile) -> Unit,
    onFolderLongClick: (Folder) -> Unit,
    viewModel: BrowseViewModel,
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
) {
```
with:

```kotlin
@Composable
internal fun BrowseStateContent(
    browseState: BrowseState,
    state: BrowseContentState,
    isSystemBrowse: Boolean,
    tags: List<Tag>,
    activeTagFilter: Tag?,
    onVideoClick: (MediaFile) -> Unit,
    onImageClick: (MediaFile, List<MediaFile>) -> Unit,
    onToggleFavorite: (MediaFile) -> Unit,
    isFavorite: (String) -> Boolean,
    onFileLongClick: (MediaFile) -> Unit,
    onFolderLongClick: (Folder) -> Unit,
    onRetry: () -> Unit,
    onBrowseFolder: (path: String, name: String) -> Unit,
    onBrowseSystemPath: (path: String, name: String) -> Unit,
    onActiveTagFilterChange: (Tag?) -> Unit,
    filterFilesByTag: (List<MediaFile>) -> List<MediaFile>,
    onSaveScrollPosition: (String, Int) -> Unit,
    onConsumeRestoreScroll: () -> Unit,
    getScrollPosition: (String) -> Int,
    getThumbnailUrl: (MediaFile) -> String,
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
) {
    val currentPath = state.currentPath
```

- [ ] **Step 4: Update the body's `viewModel.*` references**

Apply these replacements in the body:

1. **Remove the transitional `contentState` local** added in Task 2 Step 7 (the `val contentState = BrowseContentState(viewModel.folderSortOrder.value, …)` block) — `state` is now the param.

2. **Error branch** — replace the retry action:
```kotlin
                onAction = {
                    if (isSystemBrowse) viewModel.loadSystemDrives() else viewModel.loadRoots()
                },
```
→
```kotlin
                onAction = onRetry,
```

3. **Navigation** — replace each `viewModel.browseFolder(...)` with `onBrowseFolder(...)`, and each `viewModel.browseSystemPath(...)` with `onBrowseSystemPath(...)`:

RootFolders branch:
```kotlin
                            val path = if (folder.relativePath.isEmpty()) folder.name else folder.relativePath
                            viewModel.browseFolder(path, folder.name)
```
→
```kotlin
                            val path = if (folder.relativePath.isEmpty()) folder.name else folder.relativePath
                            onBrowseFolder(path, folder.name)
```

SystemDrives branch:
```kotlin
                            viewModel.browseSystemPath(drivePath, drivePath)
```
→
```kotlin
                            onBrowseSystemPath(drivePath, drivePath)
```

SystemBrowsed branch:
```kotlin
                        viewModel.browseSystemPath(folder.path, folder.name)
```
→
```kotlin
                        onBrowseSystemPath(folder.path, folder.name)
```

Browsed branch:
```kotlin
                            val path = if (folder.relativePath.isEmpty()) folder.name else folder.relativePath
                            viewModel.browseFolder(path, folder.name)
```
→
```kotlin
                            val path = if (folder.relativePath.isEmpty()) folder.name else folder.relativePath
                            onBrowseFolder(path, folder.name)
```

4. **TagFilterBar** (Browsed branch) — replace `viewModel.setActiveTagFilter(...)`:
```kotlin
                            onTagClick = { tag ->
                                viewModel.setActiveTagFilter(
                                    if (activeTagFilter?.id == tag.id) null else tag
                                )
                            },
```
→
```kotlin
                            onTagClick = { tag ->
                                onActiveTagFilterChange(
                                    if (activeTagFilter?.id == tag.id) null else tag
                                )
                            },
```

5. **File-tag filter** — both `filteredFiles = viewModel.filterFilesByTag(result.files)` lines (SystemBrowsed + Browsed branches) become:
```kotlin
            val filteredFiles = filterFilesByTag(result.files)
```

6. **The three `BrowseContent(…)` calls** — the trailing args set transitionally in Task 2 (using `contentState` and `viewModel::` refs) now use the params directly. In each call, replace:
```kotlin
                        state = contentState,
                        onSaveScrollPosition = viewModel::saveScrollPosition,
                        onConsumeRestoreScroll = viewModel::consumeRestoreScroll,
                        getScrollPosition = viewModel::getScrollPosition,
                        getThumbnailUrl = viewModel::getThumbnailUrl,
```
with:
```kotlin
                        state = state,
                        onSaveScrollPosition = onSaveScrollPosition,
                        onConsumeRestoreScroll = onConsumeRestoreScroll,
                        getScrollPosition = getScrollPosition,
                        getThumbnailUrl = getThumbnailUrl,
```

7. Remove the now-unused `import com.juziss.localmediahub.viewmodel.BrowseViewModel`.

- [ ] **Step 5: Wire `BrowseScreen`**

In `android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt`:

Add the import:
```kotlin
import com.juziss.localmediahub.ui.component.browse.BrowseContentState
```

Add the new state collection + `contentState`, next to the other `collectAsState` lines (alongside `folderSort`/`fileSort`):
```kotlin
    val restoreScrollTo by viewModel.restoreScrollTo.collectAsState()
    val contentState = BrowseContentState(folderSort, fileSort, currentPath, restoreScrollTo)
```

Replace the existing `BrowseStateContent(…)` call (which currently passes `viewModel = viewModel`, `currentPath = currentPath`, `innerPadding = innerPadding`) with:

```kotlin
        BrowseStateContent(
            browseState = browseState,
            state = contentState,
            isSystemBrowse = isSystemBrowse,
            tags = tags,
            activeTagFilter = activeTagFilter,
            onVideoClick = onVideoClick,
            onImageClick = onImageClick,
            onToggleFavorite = onToggleFavoriteCb,
            isFavorite = isFavoriteCb,
            onFileLongClick = onFileLongClickCb,
            onFolderLongClick = { folder -> itemForActions = folder },
            onRetry = { if (isSystemBrowse) viewModel.loadSystemDrives() else viewModel.loadRoots() },
            onBrowseFolder = viewModel::browseFolder,
            onBrowseSystemPath = viewModel::browseSystemPath,
            onActiveTagFilterChange = viewModel::setActiveTagFilter,
            filterFilesByTag = viewModel::filterFilesByTag,
            onSaveScrollPosition = viewModel::saveScrollPosition,
            onConsumeRestoreScroll = viewModel::consumeRestoreScroll,
            getScrollPosition = viewModel::getScrollPosition,
            getThumbnailUrl = viewModel::getThumbnailUrl,
            innerPadding = innerPadding,
        )
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*BrowseStateContentTest"`
Expected: PASS.

- [ ] **Step 7: Full gate**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: `BUILD SUCCESSFUL`; all existing tests + the three new test classes (`BrowseContentTest`, `TagMenuDialogTest`, `BrowseStateContentTest`) pass.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseStateContent.kt \
        android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt \
        android/app/src/test/java/com/juziss/localmediahub/ui/browse/BrowseStateContentTest.kt
git commit -m "refactor(android): decouple BrowseStateContent + wire BrowseScreen (round 10 task 4)"
```

---

### Task 5: Final whole-branch review

**Files:** none modified (review-only).

- [ ] **Step 1: Whole-branch diff review**

Run: `git diff a6f2a85..HEAD -- android/app/`
Confirm:
1. **No `viewModel` survives below `BrowseScreen`** — run `grep -rn "viewModel" android/app/src/main/java/com/juziss/localmediahub/ui/component/BrowseContent.kt android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseStateContent.kt android/app/src/main/java/com/juziss/localmediahub/ui/component/TagComponents.kt` and confirm it returns nothing.
2. **Behavior preserved** — `BrowseContent`'s 3 `LaunchedEffect`s read `state.*`/callbacks with identical keys/bodies; `TagMenuDialog` toggle semantics intact; `BrowseStateContent` Error retry / navigation / tag-filter wiring intact.
3. **No public API change to `BrowseViewModel`.**

- [ ] **Step 2: Build + all tests**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: `BUILD SUCCESSFUL`; all tests pass.

- [ ] **Step 3: Hand off to user for manual device smoke**

Report: all tasks compile and pass (existing + 4 new test classes). Request manual smoke: scroll-position memory on back-nav, sort-order change scrolls to top, long-press → tag dialog checkbox state + toggle, error retry. Any regression ⇒ `superpowers:systematic-debugging`.

- [ ] **Step 4: No commit** (review-only).

---

## Self-Review

**1. Spec coverage:**
- §3.1 `BrowseContentState` → Task 2 Step 3. ✓
- §3.2 `BrowseContent` decoupled → Task 2. ✓
- §3.2 `BrowseStateContent` decoupled (incl. `onRetry`) → Task 4. ✓
- §3.2 `TagMenuDialog` decoupled → Task 3. ✓
- §3.3 `BrowseScreen` wiring (collect `restoreScrollTo`, build `contentState`, compute `fileTags`, all callbacks) → Task 3 Step 4 (`fileTags`) + Task 4 Step 5 (the rest). ✓
- §5 Robolectric Compose tests (3 test classes + smoke) → Tasks 1–4. ✓
- §6 verification (`testDebugUnitTest assembleDebug` incl. new tests) → every task's gate. ✓
- §7 task split → Tasks 1–5 match. ✓
- §2 non-goals (no BrowseViewModel change; don't touch Search/Favorites views) → Global Constraints enforce; Task 5 Step 1.1 verifies. ✓

**2. Placeholder scan:** No TBD/TODO. No "describe without showing" steps; every code change shows the exact old→new. ✓

**3. Type consistency:**
- `BrowseContentState(folderSort: SortOrder, fileSort: SortOrder, currentPath: String, restoreScrollTo: String?)` — Task 2 Step 3 defines; Tasks 2/4 + all tests use identical field names/types. ✓
- `BrowseContent` new params (`onSaveScrollPosition: (String, Int) -> Unit`, `onConsumeRestoreScroll: () -> Unit`, `getScrollPosition: (String) -> Int`, `getThumbnailUrl: (MediaFile) -> String`) — match the ViewModel methods they replace (`saveScrollPosition(path, index: Int)`, `consumeRestoreScroll()`, `getScrollPosition(path): Int`, `getThumbnailUrl(file): String`). ✓
- `TagMenuDialog` callbacks `(String) -> Unit` — matches `Tag.id: String` and `tagFile(tagId: String, …)` / `untagFile(tagId: String, …)`. ✓
- `BrowseStateContent` params (Task 4 Step 3 signature) match the `BrowseScreen` wiring (Task 4 Step 5) one-for-one, including `onBrowseFolder` / `onBrowseSystemPath` / `onActiveTagFilterChange`. The `BrowseStateContentTest` (Step 1) supplies all of them as no-ops. ✓
- Tests construct `MediaFile`/`Folder`/`Tag`/`BrowseState.Error` with the exact field/constructor shapes from `Models.kt` and `BrowseViewModel.kt:726-734`. ✓

**4. One residual risk flagged for the implementer:** Task 2 Step 7's transitional `BrowseStateContent` reads `viewModel.folderSortOrder.value` / `viewModel.restoreScrollTo.value` (StateFlow `.value`). This is synchronous and behavior-identical to `collectAsState` for the snapshot used inside that composition. It is removed in Task 4 Step 4.1. If the implementer finds the transitional read awkward, they may merge Tasks 2 and 4 — but the split lets `BrowseContent` + its test land and verify before the larger `BrowseStateContent` decoupling.

No further issues. Plan is complete.
