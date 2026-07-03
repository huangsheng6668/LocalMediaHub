# BrowseScreen.kt 拆分 Implementation Plan (Round 9)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the 834-line single `@Composable fun BrowseScreen` into a ~220-line shell + 8 focused presentational composables, with zero behavior change.

**Architecture:** Pure-move (behavior-preserving) extraction. Each new file lives in `com.juziss.localmediahub.ui.component.browse`, is an `internal @Composable` receiving data + lambdas, and contains code moved verbatim from `BrowseScreen.kt` (HEAD `c2a31f6`). `BrowseScreen` stays the single state-collection point and delegates rendering. No state hoisting changes; `viewModel` still threads into `BrowseContent`/`TagMenuDialog`/`BrowseStateContent` exactly as today (decoupling is out of scope).

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Media3/ViewModel. Build: `./gradlew` (AGP/Gradle Wrapper).

**Spec:** `docs/superpowers/specs/2026-07-04-browsescreen-split-design.md`

## Global Constraints

(Each task's requirements implicitly include these. Values copied verbatim from the spec.)

- **Branch / sync:** `master`, NO worktree. Project auto-syncs to GitHub `master` (user-consented in prior rounds). Each task = one commit.
- **Risk posture — STRICT behavior-preserving.** Zero logic change. Do NOT: externalize hardcoded Chinese strings, remove `!!` force-unwraps, reorder side effects, change error paths, or alter `BrowseViewModel` / any existing public signature.
- **Round numbering:** commit messages use `(round 9 task N)` to match the project convention (round 7 = `app.js` modularization, round 8 = android state persistence).
- **Verification per task (replaces TDD — see note below):**
  - Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
  - Expected: `BUILD SUCCESSFUL`, and the existing unit tests (`BrowseViewModelTest`, `BrowseSorterTest`, `HomeViewModelTest`, `FavoritesStoreTest`, `RecentActivityStoreTest`, `DownloadManagerTest`, `RoutePathTest`) all PASS.
  - Faster iterative compile check: `./gradlew :app:compileDebugKotlin` (use between steps; the full command above is the task gate).
- **Manual device smoke test** (scroll, search, favorites toggle, long-press → Quick Actions, delete confirm, sort menu, back navigation across modes) is **deferred to the user per task** — mirrors round 7 deferring browser regression. Not a gate for the worker.
- **No new tests, no Compose UI test infrastructure** (spec non-goal). This is a refactor; the existing unit tests are the characterization net.
- **New-file conventions:** package `com.juziss.localmediahub.ui.component.browse`; `internal fun` (matches `BrowseContent`/`SearchContent`/`FavoritesContent`); explicit imports (matches `BrowseScreen.kt` style).
- **`@OptIn(ExperimentalMaterial3Api::class)`:** add to any new file that uses an experimental Material3 API. `BrowseTopBar` requires it (`TopAppBar` is experimental). Other files: add only if `compileDebugKotlin` reports it.
- **Type locations:** `MediaFile`, `Folder`, `Tag` → `com.juziss.localmediahub.data`; `BrowseViewModel`, `BrowseState`, `SearchState`, `SortOrder` → `com.juziss.localmediahub.viewmodel`.

> **Note on TDD adaptation:** This plan implements a documented *behavior-preserving refactor* (spec §2 non-goals, §9 verification). There is no new logic to drive with new tests, and Compose UI tests are explicitly out of scope. The "test cycle" for every task is therefore: **compile (`assembleDebug`) + existing unit tests stay green**. This is the same verification strategy round 7 used (`node --check` + `go build`), adapted to Android.

## File Structure

New files (all `android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/`):

| File | Responsibility | Moved from `BrowseScreen.kt` (HEAD `c2a31f6`) |
|------|----------------|-----------------------------------------------|
| `BrowseSortMenu.kt` | Sort IconButton + folder/file sort `DropdownMenu` | lines 229–283 |
| `DeleteLoadingDialog.kt` | Delete-in-progress overlay `AlertDialog` | lines 537–554 |
| `DeleteConfirmDialog.kt` | Delete confirmation `AlertDialog` (+ recursive checkbox) | lines 460–534 |
| `QuickActionsDialog.kt` | Long-press action sheet (MediaFile vs Folder branches) | lines 334–457 |
| `BrowseTopBar.kt` | `if (isSearchMode)` two-mode `TopAppBar`; calls `BrowseSortMenu` | lines 146–297 |
| `BrowseStateContent.kt` | 8-branch `when(browseState)` content dispatcher | lines 626–831 |
| `BrowseSearchView.kt` | Search branch (wraps `SearchContent` + glue) | lines 566–591 |
| `BrowseFavoritesView.kt` | Favorites branch (SummaryCard + `FavoritesContent` + glue) | lines 593–624 |

Modified file: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt` — shrinks from 834 → ~220 lines across tasks 1–7.

**Task ordering note (refinement of spec §10):** Tasks 6 and 7 are swapped vs. the spec's preview. Extracting `BrowseStateContent` (the 8-branch `when`) first is a self-contained tail replacement; then Task 7 extracts the two early-exit views and assembles the final `when { isSearchMode / showFavoritesOnly / else }`, which is what removes the two `return@Scaffold`. Same deliverables as the spec, cleaner edit boundaries.

---

### Task 1: BrowseSortMenu

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseSortMenu.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt` (add import; replace inline sort menu with call; collect the two sort states)

**Interfaces:**
- Produces: `internal fun BrowseSortMenu(folderSort: SortOrder, fileSort: SortOrder, onFolderSortChange: (SortOrder) -> Unit, onFileSortChange: (SortOrder) -> Unit)`. Owns its own `showSortMenu` expand state.
- Consumes (later, Task 5): `BrowseTopBar` will call this and forward the 4 params.

- [ ] **Step 1: Create `BrowseSortMenu.kt`**

```kotlin
package com.juziss.localmediahub.ui.component.browse

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import com.juziss.localmediahub.R
import com.juziss.localmediahub.viewmodel.SortOrder

@Composable
internal fun BrowseSortMenu(
    folderSort: SortOrder,
    fileSort: SortOrder,
    onFolderSortChange: (SortOrder) -> Unit,
    onFileSortChange: (SortOrder) -> Unit,
) {
    var showSortMenu by remember { mutableStateOf(false) }
    IconButton(onClick = { showSortMenu = true }) {
        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.sort))
    }
    DropdownMenu(
        expanded = showSortMenu,
        onDismissRequest = { showSortMenu = false },
    ) {
        Text(
            stringResource(R.string.browse_sort_folder),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        listOf(
            SortOrder.NAME_ASC,
            SortOrder.NAME_DESC,
            SortOrder.NUMERIC_ASC,
            SortOrder.NUMERIC_DESC,
            SortOrder.TIME_ASC,
            SortOrder.TIME_DESC,
        ).forEach { order ->
            DropdownMenuItem(
                text = { Text(order.label) },
                trailingIcon = {
                    if (order == folderSort) {
                        Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
                    }
                },
                onClick = { onFolderSortChange(order) },
            )
        }
        HorizontalDivider()
        Text(
            stringResource(R.string.browse_sort_file),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        SortOrder.entries.forEach { order ->
            DropdownMenuItem(
                text = { Text(order.label) },
                trailingIcon = {
                    if (order == fileSort) {
                        Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
                    }
                },
                onClick = { onFileSortChange(order) },
            )
        }
    }
}
```

- [ ] **Step 2: In `BrowseScreen.kt`, add the import**

Add after the existing `import com.juziss.localmediahub.viewmodel.SortOrder` line (BrowseScreen.kt:81):

```kotlin
import com.juziss.localmediahub.ui.component.browse.BrowseSortMenu
```

- [ ] **Step 3: In `BrowseScreen.kt`, collect the two sort states**

In the state-collection block (right after line 103 `val activeTagFilter by viewModel.activeTagFilter.collectAsState()`), add:

```kotlin
    val folderSort by viewModel.folderSortOrder.collectAsState()
    val fileSort by viewModel.fileSortOrder.collectAsState()
```

- [ ] **Step 4: Replace the inline sort menu with a call**

Replace this block (BrowseScreen.kt:229–283, inside `actions = { ... }`):

```kotlin
                            var showSortMenu by remember { mutableStateOf(false) }
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.sort))
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                            ) {
                                Text(
                                    stringResource(R.string.browse_sort_folder),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                val folderSort by viewModel.folderSortOrder.collectAsState()
                                listOf(
                                    SortOrder.NAME_ASC,
                                    SortOrder.NAME_DESC,
                                    SortOrder.NUMERIC_ASC,
                                    SortOrder.NUMERIC_DESC,
                                    SortOrder.TIME_ASC,
                                    SortOrder.TIME_DESC,
                                ).forEach { order ->
                                    DropdownMenuItem(
                                        text = { Text(order.label) },
                                        trailingIcon = {
                                            if (order == folderSort) {
                                                Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
                                            }
                                        },
                                        onClick = { viewModel.setFolderSortOrder(order) },
                                    )
                                }
                                HorizontalDivider()
                                Text(
                                    stringResource(R.string.browse_sort_file),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                val fileSort by viewModel.fileSortOrder.collectAsState()
                                SortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = { Text(order.label) },
                                        trailingIcon = {
                                            if (order == fileSort) {
                                                Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
                                            }
                                        },
                                        onClick = { viewModel.setFileSortOrder(order) },
                                    )
                                }
                            }
```

with:

```kotlin
                            BrowseSortMenu(
                                folderSort = folderSort,
                                fileSort = fileSort,
                                onFolderSortChange = viewModel::setFolderSortOrder,
                                onFileSortChange = viewModel::setFileSortOrder,
                            )
```

- [ ] **Step 5: Verify**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: `BUILD SUCCESSFUL`; all existing unit tests PASS. (A few now-unused import warnings in `BrowseScreen.kt` — e.g. `DropdownMenu`, `DropdownMenuItem`, `HorizontalDivider` — may appear; do not act on them until Task 7 cleanup.)

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseSortMenu.kt android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt
git commit -m "refactor(android): extract BrowseSortMenu from BrowseScreen (round 9 task 1)"
```

---

### Task 2: DeleteLoadingDialog

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/DeleteLoadingDialog.kt`
- Modify: `BrowseScreen.kt` (add import; replace inline overlay with call)

**Interfaces:**
- Produces: `internal fun DeleteLoadingDialog()` (no params). Renders the delete-in-progress `AlertDialog`.

- [ ] **Step 1: Create `DeleteLoadingDialog.kt`**

```kotlin
package com.juziss.localmediahub.ui.component.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.juziss.localmediahub.R

@Composable
internal fun DeleteLoadingDialog() {
    AlertDialog(
        onDismissRequest = {},
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        title = { Text(stringResource(R.string.browse_deleting), fontWeight = FontWeight.Bold) },
        text = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(16.dp),
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.browse_deleting_desc))
            }
        },
        confirmButton = {},
    )
}
```

> Note: `RoundedCornerShape` is referenced fully-qualified to match how the moved block will read; equivalently add `import androidx.compose.foundation.shape.RoundedCornerShape` and use `RoundedCornerShape(20.dp)`. Either compiles identically — pick one and be consistent within the file.

- [ ] **Step 2: In `BrowseScreen.kt`, add the import**

Add alongside the other `browse` imports (after the `BrowseLoadingCard` import near line 5):

```kotlin
import com.juziss.localmediahub.ui.component.browse.DeleteLoadingDialog
```

- [ ] **Step 3: Replace the inline overlay with a call**

Replace this block (BrowseScreen.kt:537–554):

```kotlin
        // Loading Overlay for Deletion
        if (deleteState is com.juziss.localmediahub.viewmodel.DeleteState.Loading) {
            AlertDialog(
                onDismissRequest = {},
                shape = RoundedCornerShape(20.dp),
                title = { Text(stringResource(R.string.browse_deleting), fontWeight = FontWeight.Bold) },
                text = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.browse_deleting_desc))
                    }
                },
                confirmButton = {}
            )
        }
```

with:

```kotlin
        // Loading Overlay for Deletion
        if (deleteState is com.juziss.localmediahub.viewmodel.DeleteState.Loading) {
            DeleteLoadingDialog()
        }
```

- [ ] **Step 4: Verify**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: `BUILD SUCCESSFUL`; existing tests PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/DeleteLoadingDialog.kt android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt
git commit -m "refactor(android): extract DeleteLoadingDialog from BrowseScreen (round 9 task 2)"
```

---

### Task 3: DeleteConfirmDialog

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/DeleteConfirmDialog.kt`
- Modify: `BrowseScreen.kt` (add import; replace inline dialog with call)

**Interfaces:**
- Produces: `internal fun DeleteConfirmDialog(item: Any, deleteRecursive: Boolean, onRecursiveChange: (Boolean) -> Unit, onConfirm: (path: String, recursive: Boolean) -> Unit, onDismiss: () -> Unit)`. `item` is non-null `MediaFile | Folder` (caller keeps the existing `if (showDeleteConfirm && itemToDelete != null) { val item = itemToDelete!!; ... }` guard and `!!`, per the spec non-goal of not removing `!!`).

- [ ] **Step 1: Create `DeleteConfirmDialog.kt`**

```kotlin
package com.juziss.localmediahub.ui.component.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.juziss.localmediahub.R
import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile

@Composable
internal fun DeleteConfirmDialog(
    item: Any,
    deleteRecursive: Boolean,
    onRecursiveChange: (Boolean) -> Unit,
    onConfirm: (path: String, recursive: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val name = when (item) {
        is MediaFile -> item.name
        is Folder -> item.name
        else -> ""
    }
    val isFolder = item is Folder
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = if (isFolder) stringResource(R.string.browse_delete_folder) else stringResource(R.string.browse_delete_file),
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "您确定要从服务端永久删除 \"$name\" 吗？此操作不可撤销，文件将彻底消失。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (isFolder) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onRecursiveChange(!deleteRecursive) }
                            .padding(vertical = 4.dp),
                    ) {
                        Checkbox(
                            checked = deleteRecursive,
                            onCheckedChange = { onRecursiveChange(it) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.browse_delete_recursive),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val path = when (item) {
                        is MediaFile -> item.path
                        is Folder -> item.path
                        else -> ""
                    }
                    if (path.isNotEmpty()) {
                        onConfirm(path, if (isFolder) deleteRecursive else false)
                    }
                },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.confirm_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
```

- [ ] **Step 2: In `BrowseScreen.kt`, add the import**

```kotlin
import com.juziss.localmediahub.ui.component.browse.DeleteConfirmDialog
```

- [ ] **Step 3: Replace the inline dialog with a call**

Replace this block (BrowseScreen.kt:459–535, including the leading comment and the `if` guard):

```kotlin
        // Delete Confirmation Dialog
        if (showDeleteConfirm && itemToDelete != null) {
            val item = itemToDelete!!
            val name = when (item) {
                is MediaFile -> item.name
                is com.juziss.localmediahub.data.Folder -> item.name
                else -> ""
            }
            val isFolder = item is com.juziss.localmediahub.data.Folder
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                shape = RoundedCornerShape(20.dp),
                title = {
                    Text(
                        text = if (isFolder) stringResource(R.string.browse_delete_folder) else stringResource(R.string.browse_delete_file),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "您确定要从服务端永久删除 \"$name\" 吗？此操作不可撤销，文件将彻底消失。",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (isFolder) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { deleteRecursive = !deleteRecursive }
                                    .padding(vertical = 4.dp)
                            ) {
                                Checkbox(
                                    checked = deleteRecursive,
                                    onCheckedChange = { deleteRecursive = it }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.browse_delete_recursive),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val path = when (item) {
                                is MediaFile -> item.path
                                is com.juziss.localmediahub.data.Folder -> item.path
                                else -> ""
                            }
                            if (path.isNotEmpty()) {
                                viewModel.deletePath(path, if (isFolder) deleteRecursive else false)
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.confirm_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
```

with:

```kotlin
        // Delete Confirmation Dialog
        if (showDeleteConfirm && itemToDelete != null) {
            val item = itemToDelete!!
            DeleteConfirmDialog(
                item = item,
                deleteRecursive = deleteRecursive,
                onRecursiveChange = { deleteRecursive = it },
                onConfirm = { path, recursive -> viewModel.deletePath(path, recursive) },
                onDismiss = { showDeleteConfirm = false },
            )
        }
```

- [ ] **Step 4: Verify**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: `BUILD SUCCESSFUL`; existing tests PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/DeleteConfirmDialog.kt android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt
git commit -m "refactor(android): extract DeleteConfirmDialog from BrowseScreen (round 9 task 3)"
```

---

### Task 4: QuickActionsDialog

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/QuickActionsDialog.kt`
- Modify: `BrowseScreen.kt` (add import; replace inline action sheet with call)

**Interfaces:**
- Produces: `internal fun QuickActionsDialog(item: Any, onEditTags: (MediaFile) -> Unit, onDownloadFile: (MediaFile) -> Unit, onDeleteFile: (MediaFile) -> Unit, onDownloadFolder: (Folder) -> Unit, onDeleteFolder: (Folder) -> Unit, onDismiss: () -> Unit)`. `item` is non-null `MediaFile | Folder`; caller keeps the `if (itemForActions != null) { val item = itemForActions!!; ... }` guard.

- [ ] **Step 1: Create `QuickActionsDialog.kt`**

```kotlin
package com.juziss.localmediahub.ui.component.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.juziss.localmediahub.R
import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile

@Composable
internal fun QuickActionsDialog(
    item: Any,
    onEditTags: (MediaFile) -> Unit,
    onDownloadFile: (MediaFile) -> Unit,
    onDeleteFile: (MediaFile) -> Unit,
    onDownloadFolder: (Folder) -> Unit,
    onDeleteFolder: (Folder) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = stringResource(R.string.browse_quick_actions),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (item is MediaFile) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(
                        onClick = { onEditTags(item) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Start,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.browse_action_edit_tags))
                        }
                    }
                    TextButton(
                        onClick = { onDownloadFile(item) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Start,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.browse_action_download_file))
                        }
                    }
                    TextButton(
                        onClick = { onDeleteFile(item) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Start,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.browse_action_delete_file))
                        }
                    }
                } else if (item is Folder) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(
                        onClick = { onDownloadFolder(item) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Start,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.browse_action_download_folder))
                        }
                    }
                    TextButton(
                        onClick = { onDeleteFolder(item) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Start,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.browse_action_delete_folder))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
```

- [ ] **Step 2: In `BrowseScreen.kt`, add the import**

```kotlin
import com.juziss.localmediahub.ui.component.browse.QuickActionsDialog
```

- [ ] **Step 3: Replace the inline action sheet with a call**

Replace this block (BrowseScreen.kt:333–457, the `// Action Sheet / Dialog for Long-press options` comment and its `if`):

```kotlin
        // Action Sheet / Dialog for Long-press options
        if (itemForActions != null) {
            val item = itemForActions!!
            AlertDialog(
                onDismissRequest = { itemForActions = null },
                shape = RoundedCornerShape(20.dp),
                title = {
                    Text(
                        text = stringResource(R.string.browse_quick_actions),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (item is MediaFile) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(
                                onClick = {
                                    showTagMenuForFile = item
                                    itemForActions = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Start,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.browse_action_edit_tags))
                                }
                            }
                            TextButton(
                                onClick = {
                                    viewModel.downloadFile(item)
                                    itemForActions = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Start,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.browse_action_download_file))
                                }
                            }
                            TextButton(
                                onClick = {
                                    itemToDelete = item
                                    showDeleteConfirm = true
                                    itemForActions = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Start,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.browse_action_delete_file))
                                }
                            }
                        } else if (item is com.juziss.localmediahub.data.Folder) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(
                                onClick = {
                                    viewModel.downloadFolder(item)
                                    itemForActions = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Start,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.browse_action_download_folder))
                                }
                            }
                            TextButton(
                                onClick = {
                                    itemToDelete = item
                                    showDeleteConfirm = true
                                    deleteRecursive = true
                                    itemForActions = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Start,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.browse_action_delete_folder))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { itemForActions = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
```

with:

```kotlin
        // Action Sheet / Dialog for Long-press options
        if (itemForActions != null) {
            val item = itemForActions!!
            QuickActionsDialog(
                item = item,
                onEditTags = { file ->
                    showTagMenuForFile = file
                    itemForActions = null
                },
                onDownloadFile = { file ->
                    viewModel.downloadFile(file)
                    itemForActions = null
                },
                onDeleteFile = { file ->
                    itemToDelete = file
                    showDeleteConfirm = true
                    itemForActions = null
                },
                onDownloadFolder = { folder ->
                    viewModel.downloadFolder(folder)
                    itemForActions = null
                },
                onDeleteFolder = { folder ->
                    itemToDelete = folder
                    showDeleteConfirm = true
                    deleteRecursive = true
                    itemForActions = null
                },
                onDismiss = { itemForActions = null },
            )
        }
```

> Faithfulness note: the original `onDismissRequest` and the confirm-button "取消" both set `itemForActions = null`; the extracted `onDismiss` lambda reproduces exactly that. The action callbacks each reproduce the original `onClick` body verbatim (action + `itemForActions = null`), so behavior is identical.

- [ ] **Step 4: Verify**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: `BUILD SUCCESSFUL`; existing tests PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/QuickActionsDialog.kt android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt
git commit -m "refactor(android): extract QuickActionsDialog from BrowseScreen (round 9 task 4)"
```

---

### Task 5: BrowseTopBar

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseTopBar.kt`
- Modify: `BrowseScreen.kt` (add import; compute `title` + `onBack`; replace the entire `topBar = { ... }` lambda with a `BrowseTopBar(...)` call)

**Interfaces:**
- Consumes: `BrowseSortMenu` (Task 1) — called inside `actions`, forwarding the 4 sort params.
- Produces: `internal fun BrowseTopBar(isSearchMode, searchQuery, onSearchQueryChange, onClearSearch, title, onBack, showLibraryActions, isSystemBrowse, onToggleSystemMode, onShowFavorites, showSortAndSearch, folderSort, fileSort, onFolderSortChange, onFileSortChange, showSearch, onEnterSearch)`. Wide signature is intentional (spec §5).

**Design of the move:** the original `topBar = { if (isSearchMode) {...} else {...} }` becomes `BrowseTopBar`'s body verbatim, with these substitutions:
- The inline `BrowseSortMenu` call (already in place after Task 1) stays inside `actions`.
- `title` is computed by the caller (BrowseScreen) and passed in — it replaces the whole `title = { ... when {...} ... }` block.
- The 3-branch navigation icon collapses to `onBack: (() -> Unit)?`: caller passes non-null only when one of `showFavoritesOnly`/`isCollectionView`/`canGoBack()` holds, with the correct action baked in; null renders no nav icon.
- `showLibraryActions` = `currentPath.isEmpty() && !showFavoritesOnly && !isCollectionView` (gates both the storage toggle and the favorites toggle, which share this exact condition).
- `showSortAndSearch` = `!showFavoritesOnly` (gates the sort menu).
- `showSearch` = `!showFavoritesOnly && !isCollectionView` (gates the search IconButton).

- [ ] **Step 1: Create `BrowseTopBar.kt`**

```kotlin
package com.juziss.localmediahub.ui.component.browse

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.juziss.localmediahub.R
import com.juziss.localmediahub.viewmodel.SortOrder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowseTopBar(
    isSearchMode: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    title: String,
    onBack: (() -> Unit)?,
    showLibraryActions: Boolean,
    isSystemBrowse: Boolean,
    onToggleSystemMode: () -> Unit,
    onShowFavorites: () -> Unit,
    showSortAndSearch: Boolean,
    folderSort: SortOrder,
    fileSort: SortOrder,
    onFolderSortChange: (SortOrder) -> Unit,
    onFileSortChange: (SortOrder) -> Unit,
    showSearch: Boolean,
    onEnterSearch: () -> Unit,
) {
    if (isSearchMode) {
        TopAppBar(
            title = {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text(stringResource(R.string.browse_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                    ),
                )
            },
            navigationIcon = {
                IconButton(onClick = onClearSearch) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        )
    } else {
        TopAppBar(
            title = {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold,
                )
            },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            },
            actions = {
                if (showLibraryActions) {
                    IconButton(onClick = onToggleSystemMode) {
                        Icon(
                            Icons.Filled.Storage,
                            contentDescription = if (isSystemBrowse) stringResource(R.string.browse_libraries) else stringResource(R.string.browse_title_drive),
                        )
                    }
                }
                if (showLibraryActions) {
                    IconButton(onClick = onShowFavorites) {
                        Icon(
                            Icons.Outlined.FavoriteBorder,
                            contentDescription = stringResource(R.string.browse_favorites),
                        )
                    }
                }
                if (showSortAndSearch) {
                    BrowseSortMenu(
                        folderSort = folderSort,
                        fileSort = fileSort,
                        onFolderSortChange = onFolderSortChange,
                        onFileSortChange = onFileSortChange,
                    )
                    if (showSearch) {
                        IconButton(onClick = onEnterSearch) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search))
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                titleContentColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}
```

> Import note: `Icons.Filled.Storage` is used in the body — add `import androidx.compose.material.icons.filled.Storage` if you did not wildcard-import `androidx.compose.material.icons.filled.*`. The block above references `Icons.Filled.Storage`, so include that import.

- [ ] **Step 2: In `BrowseScreen.kt`, add the import**

```kotlin
import com.juziss.localmediahub.ui.component.browse.BrowseTopBar
```

- [ ] **Step 3: Replace the entire `topBar = { ... }` lambda with a `BrowseTopBar(...)` call**

In `BrowseScreen.kt`, the `Scaffold(...)` call's `topBar = { ... }` argument currently spans the whole two-branch TopAppBar (BrowseScreen.kt:148–297). Replace the whole `topBar = { ... }` argument with:

```kotlin
        topBar = {
            val collectionTitle = (browseState as? BrowseState.TagCollection)?.title
            BrowseTopBar(
                isSearchMode = isSearchMode,
                searchQuery = searchQuery,
                onSearchQueryChange = viewModel::updateSearchQuery,
                onClearSearch = {
                    isSearchMode = false
                    viewModel.clearSearch()
                },
                title = when {
                    showFavoritesOnly -> stringResource(R.string.browse_favorites)
                    collectionTitle != null -> collectionTitle
                    isSystemBrowse && currentPath.isEmpty() -> stringResource(R.string.browse_drives)
                    isSystemBrowse -> currentPath
                    currentPath.isEmpty() -> stringResource(R.string.browse_libraries)
                    else -> currentPath
                },
                onBack = when {
                    showFavoritesOnly -> { viewModel.setShowFavoritesOnly(false) }
                    isCollectionView -> onExitBrowse
                    viewModel.canGoBack() -> { viewModel.navigateBack() }
                    else -> null
                },
                showLibraryActions = currentPath.isEmpty() && !showFavoritesOnly && !isCollectionView,
                isSystemBrowse = isSystemBrowse,
                onToggleSystemMode = {
                    if (isSystemBrowse) viewModel.loadRoots() else viewModel.loadSystemDrives()
                },
                onShowFavorites = { viewModel.setShowFavoritesOnly(true) },
                showSortAndSearch = !showFavoritesOnly,
                folderSort = folderSort,
                fileSort = fileSort,
                onFolderSortChange = viewModel::setFolderSortOrder,
                onFileSortChange = viewModel::setFileSortOrder,
                showSearch = !showFavoritesOnly && !isCollectionView,
                onEnterSearch = { isSearchMode = true },
            )
        }
```

> Faithfulness notes:
> - The `title` `when` reproduces the original 6-branch expression verbatim (search-field branch is gone — that belongs to `isSearchMode`). `collectionTitle` is hoisted as a local (`val collectionTitle = (browseState as? BrowseState.TagCollection)?.title`) at the top of the `topBar` lambda so the `when` can branch on it without a new `!!`.
> - `onBack` collapses the original 3-branch `if/else if` navigation icon to a single nullable lambda; rendering of the icon is gated by `onBack != null` inside `BrowseTopBar`, exactly matching when the original showed an icon.
> - The `actions` gating (`showLibraryActions` for both toggles, `showSortAndSearch` for sort, `showSearch` for the search button) reproduces the original `if` conditions verbatim.

- [ ] **Step 4: Verify**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: `BUILD SUCCESSFUL`; existing tests PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseTopBar.kt android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt
git commit -m "refactor(android): extract BrowseTopBar from BrowseScreen (round 9 task 5)"
```

---

### Task 6: BrowseStateContent

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseStateContent.kt`
- Modify: `BrowseScreen.kt` (add import; replace the 8-branch `when(browseState)` block with a single `BrowseStateContent(...)` call)

**Interfaces:**
- Consumes: `BrowseSummaryCard`, `BrowseStateCard`, `BrowseLoadingCard` (existing, in `browse`), plus `FolderGrid`, `SystemDrivesContent`, `BrowseContent`, `TagFilterBar` (existing, in `component`), and `viewModel: BrowseViewModel` (passed through, same coupling as `BrowseContent`).
- Produces: `internal fun BrowseStateContent(browseState, currentPath, isSystemBrowse, tags, activeTagFilter, onVideoClick, onImageClick, onToggleFavorite, isFavorite, onFileLongClick, onFolderLongClick, viewModel, innerPadding)`. `innerPadding: PaddingValues`.

- [ ] **Step 1: Create `BrowseStateContent.kt`**

```kotlin
package com.juziss.localmediahub.ui.component.browse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juziss.localmediahub.R
import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.Tag
import com.juziss.localmediahub.ui.component.BrowseContent
import com.juziss.localmediahub.ui.component.FolderGrid
import com.juziss.localmediahub.ui.component.SystemDrivesContent
import com.juziss.localmediahub.ui.component.TagFilterBar
import com.juziss.localmediahub.viewmodel.BrowseState
import com.juziss.localmediahub.viewmodel.BrowseViewModel

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
    when (browseState) {
        is BrowseState.Idle -> {
            BrowseStateCard(
                title = stringResource(R.string.browse_loading_files),
                message = stringResource(R.string.browse_loading_files_desc),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
        is BrowseState.Loading -> {
            BrowseLoadingCard(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
        is BrowseState.Error -> {
            BrowseStateCard(
                title = stringResource(R.string.browse_error_title),
                message = browseState.message,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                actionLabel = stringResource(R.string.browse_retry),
                onAction = {
                    if (isSystemBrowse) viewModel.loadSystemDrives() else viewModel.loadRoots()
                },
            )
        }
        is BrowseState.RootFolders -> {
            val folders = browseState.folders
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                BrowseSummaryCard(
                    icon = Icons.Filled.Storage,
                    title = stringResource(R.string.browse_lib_card_title),
                    message = stringResource(R.string.browse_lib_card_desc),
                    meta = "共 ${folders.size} 个共享盘符",
                    badge = null,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                FolderGrid(
                    folders = folders,
                    onFolderClick = { folder ->
                        val path = if (folder.relativePath.isEmpty()) folder.name else folder.relativePath
                        viewModel.browseFolder(path, folder.name)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        is BrowseState.SystemDrives -> {
            val drives = browseState.drives
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                BrowseSummaryCard(
                    icon = Icons.Filled.Storage,
                    title = stringResource(R.string.browse_drive_card_title),
                    message = stringResource(R.string.browse_drive_card_desc),
                    meta = "检测到 ${drives.size} 个磁盘分区",
                    badge = null,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                SystemDrivesContent(
                    drives = drives,
                    onDriveClick = { drivePath ->
                        viewModel.browseSystemPath(drivePath, drivePath)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        is BrowseState.SystemBrowsed -> {
            val result = browseState.result
            val filteredFiles = viewModel.filterFilesByTag(result.files)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                BrowseSummaryCard(
                    icon = Icons.Filled.Storage,
                    title = stringResource(R.string.browse_path_title),
                    message = result.currentPath ?: currentPath,
                    meta = "${result.folders.size} 文件夹 · ${filteredFiles.size} 文件",
                    badge = activeTagFilter?.name,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                BrowseContent(
                    folders = result.folders,
                    files = filteredFiles,
                    onFolderClick = { folder ->
                        viewModel.browseSystemPath(folder.path, folder.name)
                    },
                    onVideoClick = onVideoClick,
                    onImageClick = { file ->
                        onImageClick(file, filteredFiles.filter { it.mediaType == "image" })
                    },
                    onToggleFavorite = onToggleFavorite,
                    isFavorite = isFavorite,
                    onFileLongClick = onFileLongClick,
                    onFolderLongClick = onFolderLongClick,
                    modifier = Modifier.weight(1f),
                    viewModel = viewModel,
                )
            }
        }
        is BrowseState.Browsed -> {
            val result = browseState.result
            val filteredFiles = viewModel.filterFilesByTag(result.files)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                BrowseSummaryCard(
                    icon = Icons.Filled.Folder,
                    title = if (currentPath.isBlank()) stringResource(R.string.browse_browsed_title) else currentPath,
                    message = stringResource(R.string.browse_browsed_desc),
                    meta = "${result.folders.size} 文件夹 · ${filteredFiles.size} 文件",
                    badge = activeTagFilter?.name,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                if (tags.isNotEmpty()) {
                    Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                        TagFilterBar(
                            tags = tags,
                            activeTagFilter = activeTagFilter,
                            onTagClick = { tag ->
                                viewModel.setActiveTagFilter(
                                    if (activeTagFilter?.id == tag.id) null else tag
                                )
                            },
                        )
                    }
                }
                BrowseContent(
                    folders = result.folders,
                    files = filteredFiles,
                    onFolderClick = { folder ->
                        val path = if (folder.relativePath.isEmpty()) folder.name else folder.relativePath
                        viewModel.browseFolder(path, folder.name)
                    },
                    onVideoClick = onVideoClick,
                    onImageClick = { file ->
                        onImageClick(file, filteredFiles.filter { it.mediaType == "image" })
                    },
                    onToggleFavorite = onToggleFavorite,
                    isFavorite = isFavorite,
                    onFileLongClick = onFileLongClick,
                    onFolderLongClick = onFolderLongClick,
                    modifier = Modifier.weight(1f),
                    viewModel = viewModel,
                )
            }
        }
        is BrowseState.TagCollection -> {
            val collection = browseState
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                BrowseSummaryCard(
                    icon = Icons.Filled.Bookmarks,
                    title = collection.title,
                    message = stringResource(R.string.browse_collection_desc),
                    meta = "共 ${collection.files.size} 个媒体文件",
                    badge = stringResource(R.string.browse_collection_title),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                if (collection.files.isEmpty()) {
                    BrowseStateCard(
                        title = stringResource(R.string.browse_collection_empty),
                        message = "您可以在浏览媒体文件时长按并贴上 \"${collection.title}\" 标签，以便在此快速查看。",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .weight(1f),
                    )
                } else {
                    BrowseContent(
                        folders = emptyList(),
                        files = collection.files,
                        onFolderClick = {},
                        onVideoClick = onVideoClick,
                        onImageClick = { file ->
                            onImageClick(file, collection.files.filter { it.mediaType == "image" })
                        },
                        onToggleFavorite = onToggleFavorite,
                        isFavorite = isFavorite,
                        onFileLongClick = onFileLongClick,
                        modifier = Modifier.weight(1f),
                        viewModel = viewModel,
                    )
                }
            }
        }
    }
}
```

> Faithfulness note: `(browseState as BrowseState.RootFolders).folders` etc. are replaced by smart-cast `browseState.folders` (the `when` subjects `browseState` to smart-casting). This is the idiomatic, behavior-identical form. The `TagCollection` branch originally did `val collection = browseState as BrowseState.TagCollection` — here `val collection = browseState` with smart cast is equivalent.

- [ ] **Step 2: In `BrowseScreen.kt`, add the import**

```kotlin
import com.juziss.localmediahub.ui.component.browse.BrowseStateContent
```

- [ ] **Step 3: Replace the 8-branch `when(browseState)` block with a call**

Replace the entire `when (browseState) { ... }` block (BrowseScreen.kt:626–831):

```kotlin
        when (browseState) {
            is BrowseState.Idle -> {
                ... (all 8 branches, lines 626–831) ...
        }
```

with:

```kotlin
        BrowseStateContent(
            browseState = browseState,
            currentPath = currentPath,
            isSystemBrowse = isSystemBrowse,
            tags = tags,
            activeTagFilter = activeTagFilter,
            onVideoClick = onVideoClick,
            onImageClick = onImageClick,
            onToggleFavorite = onToggleFavoriteCb,
            isFavorite = isFavoriteCb,
            onFileLongClick = onFileLongClickCb,
            onFolderLongClick = { folder -> itemForActions = folder },
            viewModel = viewModel,
            innerPadding = innerPadding,
        )
```

> The full text of the `when(browseState)` block being replaced is large (lines 626–831); match it from the current `BrowseScreen.kt` at HEAD `c2a31f6`. The replacement is the single call above.

- [ ] **Step 4: Verify**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: `BUILD SUCCESSFUL`; existing tests PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseStateContent.kt android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt
git commit -m "refactor(android): extract BrowseStateContent from BrowseScreen (round 9 task 6)"
```

---

### Task 7: BrowseSearchView + BrowseFavoritesView + final `when` + shrink

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseSearchView.kt`
- Create: `android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseFavoritesView.kt`
- Modify: `BrowseScreen.kt` (add 2 imports; replace the two `if (...) { ...; return@Scaffold }` early-exit blocks + the `BrowseStateContent(...)` call with the final `when { isSearchMode / showFavoritesOnly / else }`; remove now-unused imports)

**Interfaces:**
- Produces:
  - `internal fun BrowseSearchView(searchState, searchQuery, onClearSearch, onBrowseFolder, onVideoClick, onImageClick, onToggleFavorite, isFavorite, getThumbnailUrl, onFileLongClick, modifier)`
  - `internal fun BrowseFavoritesView(favoriteFiles, onVideoClick, onImageClick, onToggleFavorite, isFavorite, getFavoriteThumbnailUrl, onFileLongClick, modifier)`

- [ ] **Step 1: Create `BrowseSearchView.kt`**

```kotlin
package com.juziss.localmediahub.ui.component.browse

import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.ui.component.SearchContent
import com.juziss.localmediahub.viewmodel.SearchState

@Composable
internal fun BrowseSearchView(
    searchState: SearchState,
    searchQuery: String,
    onClearSearch: () -> Unit,
    onBrowseFolder: (path: String, name: String) -> Unit,
    onVideoClick: (MediaFile) -> Unit,
    onImageClick: (MediaFile, List<MediaFile>) -> Unit,
    onToggleFavorite: (MediaFile) -> Unit,
    isFavorite: (String) -> Boolean,
    getThumbnailUrl: (MediaFile) -> String,
    onFileLongClick: (MediaFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    SearchContent(
        searchState = searchState,
        searchQuery = searchQuery,
        onFolderClick = { folder ->
            val path = if (folder.relativePath.isEmpty()) folder.name else folder.relativePath
            onClearSearch()
            onBrowseFolder(path, folder.name)
        },
        onVideoClick = onVideoClick,
        onImageClick = { file ->
            val allImages = when (val state = searchState) {
                is SearchState.Results -> state.result.files.filter { it.mediaType == "image" }
                else -> emptyList()
            }
            onImageClick(file, allImages)
        },
        onToggleFavorite = onToggleFavorite,
        isFavorite = isFavorite,
        getThumbnailUrl = getThumbnailUrl,
        onFileLongClick = onFileLongClick,
        modifier = modifier,
    )
}
```

> Faithfulness note: the original branch did `isSearchMode = false; viewModel.clearSearch(); viewModel.browseFolder(path, folder.name)`. Here the caller passes `onClearSearch = { isSearchMode = false; viewModel.clearSearch() }` and `onBrowseFolder = viewModel::browseFolder`, so the sequence is identical.

- [ ] **Step 2: Create `BrowseFavoritesView.kt`**

```kotlin
package com.juziss.localmediahub.ui.component.browse

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juziss.localmediahub.R
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.ui.component.FavoritesContent

@Composable
internal fun BrowseFavoritesView(
    favoriteFiles: List<MediaFile>,
    onVideoClick: (MediaFile, Boolean) -> Unit,
    onImageClick: (MediaFile, List<MediaFile>, Boolean) -> Unit,
    onToggleFavorite: (MediaFile) -> Unit,
    isFavorite: (String) -> Boolean,
    getFavoriteThumbnailUrl: (MediaFile) -> String,
    onFileLongClick: (MediaFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        BrowseSummaryCard(
            icon = Icons.Outlined.FavoriteBorder,
            title = stringResource(R.string.browse_favorites),
            message = stringResource(R.string.browse_fav_card_desc),
            meta = "共 ${favoriteFiles.size} 个收藏",
            badge = null,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        FavoritesContent(
            favoriteFiles = favoriteFiles,
            onVideoClick = onVideoClick,
            onImageClick = onImageClick,
            onToggleFavorite = onToggleFavorite,
            isFavorite = isFavorite,
            getThumbnailUrl = getFavoriteThumbnailUrl,
            onFileLongClick = onFileLongClick,
            modifier = Modifier.weight(1f),
        )
    }
}
```

> Faithfulness note: the caller passes `onVideoClick = { file -> onFavoriteVideoClick(file, viewModel.isFavoriteSystemBrowse(file)) }` and `onImageClick = { file, allFiles -> onFavoriteImageClick(file, allFiles.filter { it.mediaType == "image" }, viewModel.isFavoriteSystemBrowse(file)) }`, reproducing the original favorites-branch glue verbatim. The `Column` here drops the original `.padding(innerPadding)` from its top-level modifier because the caller will pass `modifier = Modifier.padding(innerPadding)` (see Step 4) — net effect identical.

- [ ] **Step 3: In `BrowseScreen.kt`, add the imports**

```kotlin
import com.juziss.localmediahub.ui.component.browse.BrowseSearchView
import com.juziss.localmediahub.ui.component.browse.BrowseFavoritesView
```

- [ ] **Step 4: Replace the two early-exit blocks + `BrowseStateContent(...)` call with the final `when`**

After Task 6, the tail of the content lambda is:

```kotlin
        if (isSearchMode) {
            SearchContent(
                searchState = searchState,
                searchQuery = searchQuery,
                onFolderClick = { folder ->
                    val path = if (folder.relativePath.isEmpty()) folder.name else folder.relativePath
                    isSearchMode = false
                    viewModel.clearSearch()
                    viewModel.browseFolder(path, folder.name)
                },
                onVideoClick = onVideoClick,
                onImageClick = { file ->
                    val allImages = when (val state = searchState) {
                        is SearchState.Results -> state.result.files.filter { it.mediaType == "image" }
                        else -> emptyList()
                    }
                    onImageClick(file, allImages)
                },
                onToggleFavorite = onToggleFavoriteCb,
                isFavorite = isFavoriteCb,
                getThumbnailUrl = viewModel::getThumbnailUrl,
                onFileLongClick = onFileLongClickCb,
                modifier = Modifier.padding(innerPadding),
            )
            return@Scaffold
        }

        if (showFavoritesOnly) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                BrowseSummaryCard(
                    icon = Icons.Outlined.FavoriteBorder,
                    title = stringResource(R.string.browse_favorites),
                    message = stringResource(R.string.browse_fav_card_desc),
                    meta = "共 ${favoriteFiles.size} 个收藏",
                    badge = null,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                FavoritesContent(
                    favoriteFiles = favoriteFiles,
                    onVideoClick = { file ->
                        onFavoriteVideoClick(file, viewModel.isFavoriteSystemBrowse(file))
                    },
                    onImageClick = { file, allFiles ->
                        val allImages = allFiles.filter { it.mediaType == "image" }
                        onFavoriteImageClick(file, allImages, viewModel.isFavoriteSystemBrowse(file))
                    },
                    onToggleFavorite = onToggleFavoriteCb,
                    isFavorite = isFavoriteCb,
                    getThumbnailUrl = viewModel::getFavoriteThumbnailUrl,
                    onFileLongClick = onFileLongClickCb,
                    modifier = Modifier.weight(1f),
                )
            }
            return@Scaffold
        }

        BrowseStateContent(
            browseState = browseState,
            currentPath = currentPath,
            isSystemBrowse = isSystemBrowse,
            tags = tags,
            activeTagFilter = activeTagFilter,
            onVideoClick = onVideoClick,
            onImageClick = onImageClick,
            onToggleFavorite = onToggleFavoriteCb,
            isFavorite = isFavoriteCb,
            onFileLongClick = onFileLongClickCb,
            onFolderLongClick = { folder -> itemForActions = folder },
            viewModel = viewModel,
            innerPadding = innerPadding,
        )
```

Replace that entire tail with:

```kotlin
        when {
            isSearchMode -> BrowseSearchView(
                searchState = searchState,
                searchQuery = searchQuery,
                onClearSearch = {
                    isSearchMode = false
                    viewModel.clearSearch()
                },
                onBrowseFolder = viewModel::browseFolder,
                onVideoClick = onVideoClick,
                onImageClick = onImageClick,
                onToggleFavorite = onToggleFavoriteCb,
                isFavorite = isFavoriteCb,
                getThumbnailUrl = viewModel::getThumbnailUrl,
                onFileLongClick = onFileLongClickCb,
                modifier = Modifier.padding(innerPadding),
            )
            showFavoritesOnly -> BrowseFavoritesView(
                favoriteFiles = favoriteFiles,
                onVideoClick = { file ->
                    onFavoriteVideoClick(file, viewModel.isFavoriteSystemBrowse(file))
                },
                onImageClick = { file, allFiles ->
                    onFavoriteImageClick(file, allFiles.filter { it.mediaType == "image" }, viewModel.isFavoriteSystemBrowse(file))
                },
                onToggleFavorite = onToggleFavoriteCb,
                isFavorite = isFavoriteCb,
                getFavoriteThumbnailUrl = viewModel::getFavoriteThumbnailUrl,
                onFileLongClick = onFileLongClickCb,
                modifier = Modifier.padding(innerPadding),
            )
            else -> BrowseStateContent(
                browseState = browseState,
                currentPath = currentPath,
                isSystemBrowse = isSystemBrowse,
                tags = tags,
                activeTagFilter = activeTagFilter,
                onVideoClick = onVideoClick,
                onImageClick = onImageClick,
                onToggleFavorite = onToggleFavoriteCb,
                isFavorite = isFavoriteCb,
                onFileLongClick = onFileLongClickCb,
                onFolderLongClick = { folder -> itemForActions = folder },
                viewModel = viewModel,
                innerPadding = innerPadding,
            )
        }
```

> This is the single transformation that removes both `return@Scaffold` early-exits (spec §7 transformation #1). Behavior is identical: the three branches are mutually exclusive in the same priority order (search → favorites → state).

- [ ] **Step 5: Remove now-unused imports from `BrowseScreen.kt`**

After tasks 1–7, `BrowseScreen.kt` no longer references many symbols it imported. Audit and **remove** these imports from `BrowseScreen.kt` (they are now only used inside the extracted files):

- `androidx.compose.material3.DropdownMenu`, `androidx.compose.material3.DropdownMenuItem`, `androidx.compose.material3.HorizontalDivider` (moved to `BrowseSortMenu`)
- `androidx.compose.material3.AlertDialog`, `androidx.compose.material3.ButtonDefaults`, `androidx.compose.material3.Checkbox`, `androidx.compose.material3.CircularProgressIndicator`, `androidx.compose.material3.OutlinedTextField`, `androidx.compose.material3.OutlinedTextFieldDefaults`, `androidx.compose.material3.TextButton`, `androidx.compose.material3.TopAppBar`, `androidx.compose.material3.TopAppBarDefaults` (moved to dialogs / `BrowseTopBar`)
- `androidx.compose.material3.ElevatedCard`, `androidx.compose.material3.Surface` — **remove only if the compiler reports them unused** (verify with `./gradlew :app:compileDebugKotlin`; if no error references them, leave them — do not guess)
- `androidx.compose.material.icons.automirrored.filled.Sort`, `androidx.compose.material.icons.filled.Check`, `androidx.compose.material.icons.filled.Search`, `androidx.compose.material.icons.filled.Storage`, `androidx.compose.material.icons.outlined.FavoriteBorder` — remove those no longer referenced (`ArrowBack`, `Bookmarks`, `Folder` may still be used by `BackHandler`? no — icons are only used in the topbar/dialogs now; remove all five unless still referenced)
- `androidx.compose.foundation.clickable`, `androidx.compose.foundation.shape.RoundedCornerShape`
- `androidx.compose.foundation.layout.Arrangement`, `Row`, `Spacer`, `height`, `width` — remove if unused
- `androidx.compose.material3.Button` — remove if unused
- `androidx.compose.ui.graphics.Color`, `androidx.compose.ui.graphics.vector.ImageVector`, `androidx.compose.ui.platform.LocalContext` (keep `LocalContext` if the toast `LaunchedEffect` still uses it — it does), `android.widget.Toast`

**Procedure:** After editing, run `./gradlew :app:compileDebugKotlin`. Kotlin flags unused imports only via lint, not the compiler, so do NOT rely on the build to find them. Instead, in the editor remove an import only when you can confirm by search that the symbol no longer appears in `BrowseScreen.kt`. If unsure, **leave the import** — an unused import does not break the build and this step is cosmetic. The functional gate is Step 6.

- [ ] **Step 6: Verify (final shell)**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: `BUILD SUCCESSFUL`; all existing unit tests PASS. `BrowseScreen.kt` should now be ~220 lines.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseSearchView.kt android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseFavoritesView.kt android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt
git commit -m "refactor(android): extract Search/Favorites views + final when in BrowseScreen (round 9 task 7)"
```

---

### Task 8: Final whole-branch review

**Files:** none modified by review (review-only; findings filed as follow-ups or fixed inline only if behavior-preserving and trivial).

- [ ] **Step 1: Whole-branch diff review**

Run: `git diff c2a31f6..HEAD -- android/app/src/main/java/com/juziss/localmediahub/`
Review the full branch diff with the eye of a skeptic. Confirm:

1. **No logic change:** every extracted block reads identically to its source (same conditions, same action bodies, same string literals, same `viewModel.*` calls).
2. **No dropped behavior:** the two `return@Scaffold` removals are covered by the `when` branch priority; the `deleteState` `LaunchedEffect`, the toast effect, the search-debounce effect, and `BackHandler` are all still present in `BrowseScreen.kt`.
3. **No signature drift:** `BrowseViewModel`, `BrowseContent`, `SearchContent`, `FavoritesContent`, `TagFilterBar`, `FolderGrid`, `SystemDrivesContent` are unchanged.
4. **`@OptIn`:** `BrowseTopBar` has it; `BrowseScreen`'s own `@OptIn(ExperimentalMaterial3Api::class)` is still needed if it still references any experimental API directly (after Task 7 it likely does not — if the annotation is now unused, that is a warning, not an error; leave it or remove it, either is fine).

- [ ] **Step 2: Build + tests one final time**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: `BUILD SUCCESSFUL`; all existing unit tests PASS.

- [ ] **Step 3: Hand off to user for manual device smoke**

Report to the user: all 7 extraction tasks compiled and tests pass. Request the manual device smoke (spec §9): open browse, scroll, enter/exit search, toggle favorites, long-press a file → Quick Actions (edit tags / download / delete), long-press a folder, delete confirm flow, open sort menu and switch folder/file sort, back navigation from each mode. Any regression ⇒ debug via `superpowers:systematic-debugging`.

- [ ] **Step 4: No commit** (review-only task). If a trivial behavior-preserving cleanup was applied during review, commit it as `refactor(android): browse split review cleanup (round 9 task 8)`.

---

## Self-Review

(Run by plan author after writing — results recorded here.)

**1. Spec coverage:**
- §4 target architecture (8 new files + ~220-line shell): Tasks 1–7 create the 8 files; Task 7 shrinks `BrowseScreen` to ~220. ✓
- §5 interfaces (signatures + the two intentional wide ones): Task 5 (`BrowseTopBar`) and Task 6 (`BrowseStateContent`) carry the wide signatures, documented. ✓
- §6 data flow (BrowseScreen = single state point, viewModel still threads through): preserved — no task hoists state out of `BrowseScreen`; `BrowseStateContent`/`BrowseContent` still take `viewModel`. ✓
- §7 two faithful transformations: Task 7 step 4 implements `return@Scaffold → when`; `@OptIn` migration covered in Task 5 + Task 8 step 1.4. ✓
- §8 error handling unchanged: no task edits error paths; `deleteState` effect stays in `BrowseScreen`. ✓
- §9 verification (assembleDebug + testDebugUnitTest + deferred manual smoke): every task's verify step uses exactly this; Task 8 step 3 hands off manual smoke. ✓
- §10 task ordering: 8 tasks present; 6/7 swapped with an explicit note (cleaner edits, same deliverables). ✓
- §11 risks/rollback: each task = one commit on master, revertible. (Stated in Global Constraints.) ✓
- §12 out-of-scope (strings, `!!`, ViewModel decoupling, Compose UI tests): Global Constraints forbid all four; `!!` explicitly preserved in Tasks 3 & 4 call-sites. ✓

**2. Placeholder scan:** No TBD/TODO/"add appropriate". Task 6 step 3 and Task 7 step 4 reference "the full block at lines X–Y, match from HEAD" for the largest replacements — this is a precise instruction (the executor copies the verbatim block shown earlier in the spec/their editor), not a placeholder; the replacement code is shown in full. The import-removal step (Task 7 step 5) intentionally defers the exact set to a search-and-confirm procedure because predicting it perfectly is error-prone — this is called out as cosmetic, with the functional gate clearly being Step 6. Acceptable.

**3. Type/signature consistency:**
- `BrowseSortMenu(folderSort, fileSort, onFolderSortChange, onFileSortChange)` — Task 1 defines; Task 5 calls with same names. ✓
- `DeleteLoadingDialog()` — Task 2 defines; not called elsewhere by name (called in BrowseScreen). ✓
- `DeleteConfirmDialog(item, deleteRecursive, onRecursiveChange, onConfirm, onDismiss)` — Task 3 defines and calls consistently. ✓
- `QuickActionsDialog(item, onEditTags, onDownloadFile, onDeleteFile, onDownloadFolder, onDeleteFolder, onDismiss)` — Task 4 defines and calls consistently. ✓
- `BrowseTopBar(...)` 17 params — Task 5 defines and the call passes all 17. ✓ (cross-checked: `showLibraryActions`, `showSortAndSearch`, `showSearch` are the three gating booleans; `onBack` nullable.)
- `BrowseStateContent(browseState, currentPath, isSystemBrowse, tags, activeTagFilter, onVideoClick, onImageClick, onToggleFavorite, isFavorite, onFileLongClick, onFolderLongClick, viewModel, innerPadding)` — Task 6 defines; Task 7 `else` branch calls with the same 13. ✓
- `BrowseSearchView(searchState, searchQuery, onClearSearch, onBrowseFolder, onVideoClick, onImageClick, onToggleFavorite, isFavorite, getThumbnailUrl, onFileLongClick, modifier)` — Task 7 step 1 defines; step 4 calls with same 11. ✓
- `BrowseFavoritesView(favoriteFiles, onVideoClick, onImageClick, onToggleFavorite, isFavorite, getFavoriteThumbnailUrl, onFileLongClick, modifier)` — Task 7 step 2 defines; step 4 calls with same 8. ✓ (Note: `onVideoClick`/`onImageClick` here carry the `Boolean` system-browse flag, matching the original favorites-branch glue — distinct from the screen's `onFavoriteVideoClick`/`onFavoriteImageClick` signatures, which the caller adapts.)
- `SortOrder`, `BrowseState`, `SearchState`, `MediaFile`, `Folder`, `Tag` import paths confirmed against codebase. ✓

**4. Task 5 `title` `when`:** hoists `val collectionTitle = (browseState as? BrowseState.TagCollection)?.title` as a local inside the `topBar` lambda and branches `collectionTitle != null -> collectionTitle` — faithful to the original and introduces no new `!!` (consistent with the Global Constraint).

No further issues. Plan is complete.
