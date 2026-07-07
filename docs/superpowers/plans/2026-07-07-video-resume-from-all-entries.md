# 视频从任意入口恢复播放进度 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户从浏览/收藏/下载/最近打开等 6 个入口打开同一个视频时,自动从上次保存的播放进度恢复;进度 ≥ 95% 时弹出"继续 / 从头开始"对话框让用户选择。

**Architecture:** 在 `RecentActivityStore` 中拆分"是否有效进度"与"是否看完"两个语义,新增单条进度查询 API;在 `MainActivity.kt` 用 `sealed class VideoOpenAction` 表达"直接播放 vs 弹窗确认",通过 `checkPlaybackProgress` suspend 函数集中处理;在 `LocalMediaHubApp` 顶层用 `mutableStateOf<ResumePlaybackRequest?>` 维护对话框状态,新增 `ResumePlaybackDialog` Composable;`formatTime` 提取到 `util/TimeUtil.kt` 复用。

**Tech Stack:** Kotlin + Jetpack Compose + Hilt + DataStore Preferences + JUnit4。

## Global Constraints

- 所有面向用户文案必须使用 `android/app/src/main/res/values/strings.xml` 的资源,中文规范,不允许硬编码字符串到 Composable。
- 阈值常量统一在 `RecentActivityStore.kt` 顶层定义:`COMPLETED_RATIO = 0.95`、`COMPLETED_FOCUS_RATIO = 0.98`、`MIN_KEEP_POSITION_MS = 10_000L`。不允许在其它文件出现这些 magic number。
- 时间格式:`< 1 小时` 显示 `M:SS`(分钟不补零,秒补零);`≥ 1 小时` 显示 `H:MM:SS`。这与现有 `HomeComponents.kt:741-751` 行为完全一致。
- 沿用 `relativePath + isSystemBrowse` 作为同一视频的唯一性 key。
- 单元测试位于 `android/app/src/test/java/com/juziss/localmediahub/...`,沿用现有 JUnit4 + `org.junit.Assert` 风格。
- 项目根目录:`E:\github_project\LocalMediaHub`。所有 Bash 命令均在此目录运行。
- 提交信息规范:`feat(android):` / `refactor(android):` / `test(android):` / `docs(plan):` 前缀,与 recent commits 一致。
- TDD 严格执行:每个 Task 先写失败测试 → 跑测试确认失败 → 写最小实现 → 跑测试确认通过 → 提交。

---

## 文件结构

| 文件 | 操作 | 责任 |
|------|------|------|
| `android/app/src/main/java/com/juziss/localmediahub/util/TimeUtil.kt` | 新建 | 公共时间格式化函数 `formatTime(ms: Long): String` |
| `android/app/src/main/java/com/juziss/localmediahub/data/RecentActivityStore.kt` | 修改 | 拆分阈值判定函数 + 新增 `getPlaybackProgress` 单条查询 |
| `android/app/src/main/java/com/juziss/localmediahub/ui/component/home/HomeComponents.kt` | 修改 | `formatTime` 改为引用 `TimeUtil.formatTime`,删除私有版本 |
| `android/app/src/main/java/com/juziss/localmediahub/viewmodel/HomeViewModel.kt` | 修改 | `continueWatching` 过滤掉已看完(≥95%)的条目 |
| `android/app/src/main/java/com/juziss/localmediahub/ui/component/ResumePlaybackDialog.kt` | 新建 | 弹窗 Composable + `ResumePlaybackRequest` 数据类 + `VideoOpenAction` sealed class |
| `android/app/src/main/java/com/juziss/localmediahub/MainActivity.kt` | 修改 | 新增 `checkPlaybackProgress` 辅助函数;6 个视频入口改为统一流程;顶层 `resumeRequest` 状态 + 对话框渲染 |
| `android/app/src/main/res/values/strings.xml` | 修改 | 新增 4 条弹窗相关字符串 |
| `android/app/src/test/java/com/juziss/localmediahub/data/RecentActivityStoreTest.kt` | 修改 | 扩展测试覆盖新函数 + 修改后的 `shouldKeepPlaybackProgress` 行为 |
| `android/app/src/test/java/com/juziss/localmediahub/util/TimeUtilTest.kt` | 新建 | `formatTime` 单元测试 |
| `android/app/src/test/java/com/juziss/localmediahub/viewmodel/HomeViewModelTest.kt` | 修改 | 添加"已看完条目不进入 continueWatching"测试 |

---

## Task 1: 提取 TimeUtil 并迁移 formatTime

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/util/TimeUtil.kt`
- Create: `android/app/src/test/java/com/juziss/localmediahub/util/TimeUtilTest.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/home/HomeComponents.kt:737-751`

**Interfaces:**
- Consumes: 无(基础工具)
- Produces: `fun formatTime(ms: Long): String`(public,包 `com.juziss.localmediahub.util`)

- [ ] **Step 1: 写失败测试**

创建 `android/app/src/test/java/com/juziss/localmediahub/util/TimeUtilTest.kt`:

```kotlin
package com.juziss.localmediahub.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeUtilTest {

    @Test
    fun `formatTime formats seconds under a minute as M_SS`() {
        assertEquals("0:05", formatTime(5_000L))
        assertEquals("0:59", formatTime(59_000L))
    }

    @Test
    fun `formatTime formats minutes under an hour as M_SS`() {
        assertEquals("1:00", formatTime(60_000L))
        assertEquals("12:34", formatTime(12 * 60_000L + 34_000L))
    }

    @Test
    fun `formatTime formats hours and above as H_MM_SS`() {
        assertEquals("1:00:00", formatTime(3_600_000L))
        assertEquals("1:02:03", formatTime(3_600_000L + 2 * 60_000L + 3_000L))
    }

    @Test
    fun `formatTime clamps negative values to zero`() {
        assertEquals("0:00", formatTime(-5_000L))
    }

    @Test
    fun `formatTime handles zero`() {
        assertEquals("0:00", formatTime(0L))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.util.TimeUtilTest"
```
Expected: 编译失败,提示 `formatTime` 未解析。

- [ ] **Step 3: 创建 TimeUtil.kt 最小实现**

创建 `android/app/src/main/java/com/juziss/localmediahub/util/TimeUtil.kt`:

```kotlin
package com.juziss.localmediahub.util

/**
 * 把毫秒数格式化为可读的时间字符串。
 * - < 1 小时:显示为 `M:SS`(分钟不补零,秒补零)
 * - >= 1 小时:显示为 `H:MM:SS`
 *
 * 负数会被钳制为 0。
 */
fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.util.TimeUtilTest"
```
Expected: 全部 PASS。

- [ ] **Step 5: 迁移 HomeComponents.kt 使用 TimeUtil**

修改 `android/app/src/main/java/com/juziss/localmediahub/ui/component/home/HomeComponents.kt`:

在文件顶部 import 区添加:
```kotlin
import com.juziss.localmediahub.util.formatTime
```

删除 `HomeComponents.kt:741-751` 的私有 `formatTime` 函数整体(从 `private fun formatTime(ms: Long): String {` 到对应 `}` 结束,共 11 行)。

`formatProgressLabel`(737-739 行)保持不变,它仍然调用 `formatTime`,但这次解析到 `util` 包。

- [ ] **Step 6: 全量构建确认 HomeComponents 编译通过**

Run:
```bash
cd android && ./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL。

- [ ] **Step 7: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/util/TimeUtil.kt \
        android/app/src/test/java/com/juziss/localmediahub/util/TimeUtilTest.kt \
        android/app/src/main/java/com/juziss/localmediahub/ui/component/home/HomeComponents.kt
git commit -m "$(cat <<'EOF'
refactor(android): extract formatTime to util.TimeUtil (round 22)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: 拆分 shouldKeepPlaybackProgress 阈值函数

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/RecentActivityStore.kt:62-80`
- Modify: `android/app/src/test/java/com/juziss/localmediahub/data/RecentActivityStoreTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `internal const COMPLETED_RATIO: Double = 0.95`(文件顶层)
  - `internal const COMPLETED_FOCUS_RATIO: Double = 0.98`(文件顶层)
  - `internal const MIN_KEEP_POSITION_MS: Long = 10_000L`(文件顶层)
  - `internal fun isValidProgress(positionMs: Long, durationMs: Long): Boolean`
  - `internal fun isCompleted(positionMs: Long, durationMs: Long): Boolean`
  - `internal fun shouldFocusRestart(positionMs: Long, durationMs: Long): Boolean`
  - 删除旧的 `shouldKeepPlaybackProgress`

- [ ] **Step 1: 修改测试**

修改 `android/app/src/test/java/com/juziss/localmediahub/data/RecentActivityStoreTest.kt`:

把 `shouldKeepPlaybackProgress only keeps meaningful unfinished playback`(46-50 行)整个测试函数替换为以下三个测试:

```kotlin
@Test
fun `isValidProgress rejects sub-threshold positions and invalid durations`() {
    assertFalse(isValidProgress(positionMs = 5_000L, durationMs = 120_000L))
    assertTrue(isValidProgress(positionMs = 10_000L, durationMs = 120_000L))
    assertFalse(isValidProgress(positionMs = 30_000L, durationMs = 0L))
    assertFalse(isValidProgress(positionMs = 30_000L, durationMs = -1L))
}

@Test
fun `isCompleted treats positions at or above 95 percent as completed`() {
    assertFalse(isCompleted(positionMs = 94_999L, durationMs = 100_000L))
    assertTrue(isCompleted(positionMs = 95_000L, durationMs = 100_000L))
    assertTrue(isCompleted(positionMs = 99_999L, durationMs = 100_000L))
    // 无效时长不算完成
    assertFalse(isCompleted(positionMs = 95_000L, durationMs = 0L))
}

@Test
fun `shouldFocusRestart only true at or above 98 percent`() {
    assertFalse(shouldFocusRestart(positionMs = 97_999L, durationMs = 100_000L))
    assertTrue(shouldFocusRestart(positionMs = 98_000L, durationMs = 100_000L))
    assertTrue(shouldFocusRestart(positionMs = 100_000L, durationMs = 100_000L))
    assertFalse(shouldFocusRestart(positionMs = 98_000L, durationMs = 0L))
}
```

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.data.RecentActivityStoreTest"
```
Expected: 编译失败,`isValidProgress` / `isCompleted` / `shouldFocusRestart` 未解析,且旧 `shouldKeepPlaybackProgress` 已无引用但还在源码中(不影响测试编译失败)。

- [ ] **Step 3: 修改 RecentActivityStore.kt**

修改 `android/app/src/main/java/com/juziss/localmediahub/data/RecentActivityStore.kt`:

把 `62-80` 行(整个 `shouldKeepPlaybackProgress` 函数 + 紧随其后的 `mergePlaybackProgress` 之前的空行)替换为以下内容:

```kotlin
/** 进度低于此值(毫秒)的播放不保存。 */
internal const val MIN_KEEP_POSITION_MS: Long = 10_000L

/** 进度达到 duration × 此比例视为"已看完",会触发弹窗。 */
internal const val COMPLETED_RATIO: Double = 0.95

/** 进度达到 duration × 此比例时,弹窗默认聚焦"从头开始"。 */
internal const val COMPLETED_FOCUS_RATIO: Double = 0.98

/** 进度是否值得保存(>= 10 秒且有时长)。 */
internal fun isValidProgress(positionMs: Long, durationMs: Long): Boolean {
    if (positionMs < MIN_KEEP_POSITION_MS || durationMs <= 0L) return false
    return true
}

/** 进度是否视为"已看完"(>= 95%)。仅在有时长时有意义。 */
internal fun isCompleted(positionMs: Long, durationMs: Long): Boolean {
    if (durationMs <= 0L) return false
    return positionMs >= (durationMs * COMPLETED_RATIO).toLong()
}

/** 弹窗是否应当默认聚焦"从头开始"(>= 98%)。仅在有时长时有意义。 */
internal fun shouldFocusRestart(positionMs: Long, durationMs: Long): Boolean {
    if (durationMs <= 0L) return false
    return positionMs >= (durationMs * COMPLETED_FOCUS_RATIO).toLong()
}

```

注意:此替换保留了原 `mergePlaybackProgress` 函数(70-80 行)不动。

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.data.RecentActivityStoreTest"
```
Expected: 全部 PASS(包括原有的 `deriveLocationTitle` / `mergeRecentMedia` / `mergePlaybackProgress` 测试 + 新增的 3 个测试)。

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/data/RecentActivityStore.kt \
        android/app/src/test/java/com/juziss/localmediahub/data/RecentActivityStoreTest.kt
git commit -m "$(cat <<'EOF'
refactor(android): split progress threshold helpers (round 22)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: 调整 savePlaybackProgress 保留 ≥95% 进度

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/RecentActivityStore.kt:137-167`
- Modify: `android/app/src/test/java/com/juziss/localmediahub/data/RecentActivityStoreTest.kt`

**说明:** 当前 `savePlaybackProgress` 用 `shouldKeepPlaybackProgress` 决定是否丢弃。Task 2 已删除该函数,改用 `isValidProgress`。语义上:只要进度 ≥ 10 秒就保存(不再因 ≥95% 而丢弃)。这样后续入口才能查到"已看完"记录并弹窗。

**Interfaces:**
- Consumes: Task 2 产出的 `isValidProgress`
- Produces: `savePlaybackProgress` 行为变更(签名不变,只改内部判定)

- [ ] **Step 1: 写失败测试**

在 `android/app/src/test/java/com/juziss/localmediahub/data/RecentActivityStoreTest.kt` **末尾**(`mergePlaybackProgress keeps newest unique entries` 测试之后、最后一个 `}` 之前)添加测试。

由于 `savePlaybackProgress` 是 `RecentActivityStore` 类的成员,需要 Context。本仓库现有测试只测内部纯函数,没有 Robolectric 设置。因此本任务采用**间接测试**方式:抽出判定逻辑为可测函数,通过该函数验证。

实际上 Task 2 的 `isValidProgress` 测试已经覆盖了判定逻辑。此 Task 的"实现"主要是修改 `savePlaybackProgress` 内部一行。但为了 TDD 完整性,我们验证 `savePlaybackProgress` 不再因 ≥95% 而丢弃——通过新增一个**纯函数级测试**,验证"新判定逻辑允许 ≥95%":

```kotlin
@Test
fun `isValidProgress allows completed-level positions to be saved`() {
    // 与旧 shouldKeepPlaybackProgress 不同,新逻辑允许 95% 以上的进度被保存。
    // savePlaybackProgress 改用 isValidProgress 后,这一行为变化通过此测试锁定。
    assertTrue(isValidProgress(positionMs = 95_000L, durationMs = 100_000L))
    assertTrue(isValidProgress(positionMs = 100_000L, durationMs = 100_000L))
}
```

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.data.RecentActivityStoreTest.isValidProgress allows completed-level positions to be saved"
```
Expected: PASS(因为 Task 2 实现的 `isValidProgress` 已经允许这些值)。如果 PASS,跳到 Step 4——这说明实现已经满足要求,本 Task 主要是修改 `savePlaybackProgress` 内部使其调用新函数。

- [ ] **Step 3: 修改 savePlaybackProgress 使用 isValidProgress**

修改 `android/app/src/main/java/com/juziss/localmediahub/data/RecentActivityStore.kt:144-153`。

当前代码(在 `savePlaybackProgress` 函数内):
```kotlin
        context.recentActivityDataStore.edit { preferences ->
            val current = decodePlaybackProgress(preferences[playbackProgressKey])
            if (!shouldKeepPlaybackProgress(positionMs, durationMs)) {
                preferences[playbackProgressKey] = gson.toJson(
                    current.filterNot {
                        it.file.relativePath == file.relativePath && it.isSystemBrowse == isSystemBrowse
                    }
                )
                return@edit
            }
```

替换判定调用:
```kotlin
        context.recentActivityDataStore.edit { preferences ->
            val current = decodePlaybackProgress(preferences[playbackProgressKey])
            if (!isValidProgress(positionMs, durationMs)) {
                preferences[playbackProgressKey] = gson.toJson(
                    current.filterNot {
                        it.file.relativePath == file.relativePath && it.isSystemBrowse == isSystemBrowse
                    }
                )
                return@edit
            }
```

- [ ] **Step 4: 跑全部测试确认通过**

Run:
```bash
cd android && ./gradlew :app:testDebugUnitTest
```
Expected: 全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/data/RecentActivityStore.kt \
        android/app/src/test/java/com/juziss/localmediahub/data/RecentActivityStoreTest.kt
git commit -m "$(cat <<'EOF'
feat(android): preserve completed playback progress (round 22)

savePlaybackProgress now uses isValidProgress instead of the old
shouldKeepPlaybackProgress, so >=95% entries are no longer dropped.
This lets entry points detect "completed" status and prompt the user.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: 新增 getPlaybackProgress 单条查询 API

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/RecentActivityStore.kt`(在 `clearPlaybackProgress` 之后添加新方法)
- Modify: `android/app/src/test/java/com/juziss/localmediahub/data/RecentActivityStoreTest.kt`

**Interfaces:**
- Consumes: Task 2 的常量
- Produces: `suspend fun RecentActivityStore.getPlaybackProgress(file: MediaFile, isSystemBrowse: Boolean): PlaybackProgressEntry?`

**说明:** 由于现有测试不使用 Robolectric/DataStore 真实环境,我们采用**接口契约测试 + 纯逻辑函数**的方式:把"在列表中按 key 查找"的纯逻辑抽成 `internal fun findPlaybackProgress(...)`,测它;`getPlaybackProgress` 是个 thin wrapper(读 DataStore 当前快照 + 调用 `findPlaybackProgress`)。这样不用引入 Robolectric 也能 TDD。

- [ ] **Step 1: 写失败测试**

在 `android/app/src/test/java/com/juziss/localmediahub/data/RecentActivityStoreTest.kt` 末尾(`isValidProgress allows completed-level positions to be saved` 之后)添加:

```kotlin
@Test
fun `findPlaybackProgress matches on relativePath and isSystemBrowse`() {
    val fileA = MediaFile("a.mp4", "F:/Media/a.mp4", "F:/Media/a.mp4", 1, "", "video", "mp4")
    val fileB = MediaFile("b.mp4", "F:/Media/b.mp4", "F:/Media/b.mp4", 1, "", "video", "mp4")
    val list = listOf(
        PlaybackProgressEntry(fileA, isSystemBrowse = false, positionMs = 10_000L, durationMs = 100_000L, updatedAt = 1L),
        PlaybackProgressEntry(fileB, isSystemBrowse = true, positionMs = 20_000L, durationMs = 100_000L, updatedAt = 2L),
    )

    val match = findPlaybackProgress(list, fileA, isSystemBrowse = false)
    assertEquals(10_000L, match?.positionMs)

    // 同 relativePath 但 isSystemBrowse 不同 → null
    assertNull(findPlaybackProgress(list, fileA, isSystemBrowse = true))

    // 不存在的 relativePath → null
    val fileC = MediaFile("c.mp4", "F:/Media/c.mp4", "F:/Media/c.mp4", 1, "", "video", "mp4")
    assertNull(findPlaybackProgress(list, fileC, isSystemBrowse = false))

    // 空列表 → null
    assertNull(findPlaybackProgress(emptyList(), fileA, isSystemBrowse = false))
}
```

并补充 import:
```kotlin
import org.junit.Assert.assertNull
```

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.data.RecentActivityStoreTest.findPlaybackProgress matches on relativePath and isSystemBrowse"
```
Expected: 编译失败,`findPlaybackProgress` 未解析。

- [ ] **Step 3: 添加 findPlaybackProgress 纯函数 + getPlaybackProgress 方法**

修改 `android/app/src/main/java/com/juziss/localmediahub/data/RecentActivityStore.kt`:

在 `mergePlaybackProgress` 函数(70-80 行区域,Task 2 后位置不变)之后、`class RecentActivityStore` 之前,添加:

```kotlin
/** 在已保存的进度列表中按 key 查找单条记录。 */
internal fun findPlaybackProgress(
    list: List<PlaybackProgressEntry>,
    file: MediaFile,
    isSystemBrowse: Boolean,
): PlaybackProgressEntry? {
    return list.firstOrNull {
        it.file.relativePath == file.relativePath && it.isSystemBrowse == isSystemBrowse
    }
}
```

然后在 `RecentActivityStore` 类中,在 `clearPlaybackProgress` 方法之后(`182` 行附近的 `}` 之后、`decodeRecentMedia` 之前),添加新方法:

```kotlin
    /** 查询单个视频当前已保存的进度。无记录返回 null。 */
    suspend fun getPlaybackProgress(
        file: MediaFile,
        isSystemBrowse: Boolean,
    ): PlaybackProgressEntry? {
        val current = context.recentActivityDataStore.data.map { preferences ->
            decodePlaybackProgress(preferences[playbackProgressKey])
        }.firstOrNull() ?: emptyList()
        return findPlaybackProgress(current, file, isSystemBrowse)
    }
```

并补充 import(文件顶部):
```kotlin
import kotlinx.coroutines.flow.firstOrNull
```

- [ ] **Step 4: 跑测试确认通过**

Run:
```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.data.RecentActivityStoreTest"
```
Expected: 全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/data/RecentActivityStore.kt \
        android/app/src/test/java/com/juziss/localmediahub/data/RecentActivityStoreTest.kt
git commit -m "$(cat <<'EOF'
feat(android): add getPlaybackProgress single-entry lookup (round 22)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: HomeViewModel 过滤已看完条目

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/HomeViewModel.kt:68-72`
- Modify: `android/app/src/test/java/com/juziss/localmediahub/viewmodel/HomeViewModelTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `isCompleted`
- Produces: `HomeUiState.continueWatching` 不再包含 ≥95% 的条目

- [ ] **Step 1: 先看现有 HomeViewModelTest 结构**

Run:
```bash
cat android/app/src/test/java/com/juziss/localmediahub/viewmodel/HomeViewModelTest.kt
```
了解现有测试用的 fake repository / fake store 模式,以便沿用。

- [ ] **Step 2: 写失败测试**

在 `android/app/src/test/java/com/juziss/localmediahub/viewmodel/HomeViewModelTest.kt` 中,基于现有 fake store 测试模式添加(如果现有测试没有可直接 mock `playbackProgress` Flow 的入口,先按现有测试的实际 API 写一个能编译但会失败的测试):

```kotlin
@Test
fun `continueWatching excludes completed entries`() = runTest {
    val fileA = MediaFile("a.mp4", "F:/Media/a.mp4", "F:/Media/a.mp4", 1, "", "video", "mp4")
    val fileB = MediaFile("b.mp4", "F:/Media/b.mp4", "F:/Media/b.mp4", 1, "", "video", "mp4")
    val progress = listOf(
        PlaybackProgressEntry(fileA, isSystemBrowse = false, positionMs = 50_000L, durationMs = 100_000L, updatedAt = 1L), // 50%
        PlaybackProgressEntry(fileB, isSystemBrowse = false, positionMs = 96_000L, durationMs = 100_000L, updatedAt = 2L), // 96% -> 已看完
    )
    val fakeStore = FakeRecentActivityStore(playbackProgress = progress)
    val viewModel = HomeViewModel(
        recentActivityStore = fakeStore,
        // 其他依赖按现有测试构造方式提供
    )

    // 等待 init 中的 collect 完成
    runCurrent()

    val uiState = viewModel.uiState.value
    assertEquals(listOf("a.mp4"), uiState.continueWatching.map { it.file.name })
}
```

**注意:** 本步骤中的 `FakeRecentActivityStore`、`runCurrent()`、ViewModel 构造参数等需要与现有 `HomeViewModelTest.kt` 中的实际 API 对齐。如果现有测试缺少 `FakeRecentActivityStore` 或可注入的 playbackProgress Flow,**先在 Step 1 阶段补齐这些测试基础设施**,具体写法参考已有 `Fake*` 类(在测试文件顶部或同包内)。

如果现有 HomeViewModel 没有 FakeRecentActivityStore 入口(例如直接用真实 DataStore),则把此测试拆为**间接验证**:在测试中直接调用一个新的内部纯函数 `filterContinueWatching(list: List<PlaybackProgressEntry>): List<PlaybackProgressEntry>` 并断言其过滤行为。这种情况下:

```kotlin
@Test
fun `filterContinueWatching excludes completed entries`() {
    val fileA = MediaFile("a.mp4", "F:/Media/a.mp4", "F:/Media/a.mp4", 1, "", "video", "mp4")
    val fileB = MediaFile("b.mp4", "F:/Media/b.mp4", "F:/Media/b.mp4", 1, "", "video", "mp4")
    val input = listOf(
        PlaybackProgressEntry(fileA, isSystemBrowse = false, positionMs = 50_000L, durationMs = 100_000L, updatedAt = 1L),
        PlaybackProgressEntry(fileB, isSystemBrowse = false, positionMs = 96_000L, durationMs = 100_000L, updatedAt = 2L),
    )
    val filtered = filterContinueWatching(input)
    assertEquals(listOf("a.mp4"), filtered.map { it.file.name })
}
```

- [ ] **Step 3: 跑测试确认失败**

Run:
```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.viewmodel.HomeViewModelTest"
```
Expected: 编译失败,`filterContinueWatching` 未定义(或测试断言失败,因为现有 ViewModel 没过滤)。

- [ ] **Step 4: 实现**

修改 `android/app/src/main/java/com/juziss/localmediahub/viewmodel/HomeViewModel.kt`:

在 `HomeViewModel` 类外(文件末尾或合适位置)添加纯函数:

```kotlin
/** 过滤掉"已看完"(进度 >= 95%)的条目,只保留还会用到的续播记录。 */
internal fun filterContinueWatching(
    entries: List<PlaybackProgressEntry>,
): List<PlaybackProgressEntry> {
    return entries.filterNot { entry ->
        com.juziss.localmediahub.data.isCompleted(entry.positionMs, entry.durationMs)
    }
}
```

修改 `HomeViewModel.kt:68-72` 的 collector:

```kotlin
        viewModelScope.launch {
            recentActivityStore.playbackProgress.collect { progress ->
                _uiState.value = _uiState.value.copy(
                    continueWatching = filterContinueWatching(progress),
                )
            }
        }
```

确保 import:
```kotlin
import com.juziss.localmediahub.data.PlaybackProgressEntry
```

- [ ] **Step 5: 跑测试确认通过**

Run:
```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.viewmodel.HomeViewModelTest"
```
Expected: 全部 PASS。

- [ ] **Step 6: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/viewmodel/HomeViewModel.kt \
        android/app/src/test/java/com/juziss/localmediahub/viewmodel/HomeViewModelTest.kt
git commit -m "$(cat <<'EOF'
feat(android): filter completed entries out of continue watching (round 22)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: 新增 strings.xml 资源

**Files:**
- Modify: `android/app/src/main/res/values/strings.xml`

**说明:** 本任务无测试(strings.xml 是声明式资源),但仍按"修改 → 编译验证 → 提交"的流程执行。

- [ ] **Step 1: 添加字符串**

在 `android/app/src/main/res/values/strings.xml` 中,在 `home_section_continue_desc`(第 31 行)之后添加:

```xml
    <!-- ── Resume playback dialog ─────────────────────────── -->
    <string name="resume_dialog_title">继续播放</string>
    <string name="resume_dialog_message">上次看到 %1$s，是否从该进度继续播放？</string>
    <string name="resume_dialog_btn_restart">从头开始</string>
    <string name="resume_dialog_btn_resume">继续播放</string>
```

- [ ] **Step 2: 验证资源编译**

Run:
```bash
cd android && ./gradlew :app:processDebugResources
```
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 提交**

```bash
git add android/app/src/main/res/values/strings.xml
git commit -m "$(cat <<'EOF'
feat(android): add resume playback dialog strings (round 22)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: 新增 ResumePlaybackDialog + 数据类 + VideoOpenAction

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/ui/component/ResumePlaybackDialog.kt`

**Interfaces:**
- Consumes:
  - `formatTime` from `util.TimeUtil`
  - `R.string.resume_dialog_*` from Task 6
  - `MediaFile` from `data` package
- Produces:
  - `data class ResumePlaybackRequest(file: MediaFile, isSystemBrowse: Boolean, streamUrl: String, positionMs: Long, durationMs: Long)`
  - `sealed class VideoOpenAction { data class PlayDirectly(positionMs: Long); data class ShowCompletedDialog(positionMs: Long, durationMs: Long) }`
  - `@Composable fun ResumePlaybackDialog(request: ResumePlaybackRequest, onRestart: () -> Unit, onResume: () -> Unit, onDismiss: () -> Unit)`

**说明:** Composable 函数无单元测试(本项目无 Android UI 测试目录),通过编译 + 后续手动测试覆盖。

- [ ] **Step 1: 创建 ResumePlaybackDialog.kt**

创建 `android/app/src/main/java/com/juziss/localmediahub/ui/component/ResumePlaybackDialog.kt`:

```kotlin
package com.juziss.localmediahub.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.juziss.localmediahub.R
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.util.formatTime

/** 用户点击视频后,根据已保存进度判定的下一步动作。 */
sealed class VideoOpenAction {
    /** 直接以给定位置开始播放。 */
    data class PlayDirectly(val positionMs: Long) : VideoOpenAction()

    /** 已看完,需要弹窗让用户选择继续 / 从头。 */
    data class ShowCompletedDialog(val positionMs: Long, val durationMs: Long) : VideoOpenAction()
}

/** 触发 [ResumePlaybackDialog] 的请求负载。 */
data class ResumePlaybackRequest(
    val file: MediaFile,
    val isSystemBrowse: Boolean,
    val streamUrl: String,
    val positionMs: Long,
    val durationMs: Long,
)

/**
 * 续播确认对话框。仅当视频进度 >= 95% 时由调用方触发。
 *
 * 默认聚焦按钮由调用方根据进度阈值决定(95% <= progress < 98% 聚焦"继续播放",
 * progress >= 98% 聚焦"从头开始"),通过 [focusResume] 参数传入。
 */
@Composable
fun ResumePlaybackDialog(
    request: ResumePlaybackRequest,
    focusResume: Boolean,
    onRestart: () -> Unit,
    onResume: () -> Unit,
    onDismiss: () -> Unit,
) {
    val message = stringResource(
        R.string.resume_dialog_message,
        formatTime(request.positionMs),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.resume_dialog_title)) },
        text = { Text(message) },
        dismissButton = {
            TextButton(onClick = onRestart) {
                Text(stringResource(R.string.resume_dialog_btn_restart))
            }
        },
        confirmButton = {
            TextButton(onClick = onResume) {
                Text(stringResource(R.string.resume_dialog_btn_resume))
            }
        },
    )
}
```

**关于"默认聚焦":** Compose Material3 `AlertDialog` 不直接暴露"哪个按钮默认聚焦"。在触摸屏上无意义;在 TV / 遥控器场景需要 `Modifier.focusRequester` + `FocusRequester`。本 Task 先不接入 FocusRequester(默认无焦点),在 Task 8 的集成中,如果检测到当前是 leanback/TV 环境,再扩展。**目前实现:对话框无初始焦点,用户主动点击选择。** 这是务实折中——spec 中的"分段聚焦"逻辑通过参数 `focusResume` 已暴露,具体 FocusRequester 接入留作后续优化(在 Task 8 完成后用 issue 跟踪)。

- [ ] **Step 2: 验证编译**

Run:
```bash
cd android && ./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/ResumePlaybackDialog.kt
git commit -m "$(cat <<'EOF'
feat(android): add ResumePlaybackDialog component (round 22)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: 集成到 MainActivity(6 个入口 + 顶层状态)

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/MainActivity.kt`

**Interfaces:**
- Consumes:
  - `VideoOpenAction` / `ResumePlaybackRequest` / `ResumePlaybackDialog` from Task 7
  - `RecentActivityStore.getPlaybackProgress` / `addRecentMedia` / `clearPlaybackProgress`
  - `isValidProgress` / `isCompleted` / `shouldFocusRestart` from Task 2
- Produces: 6 个视频入口改用统一流程;顶层 `resumeRequest` 状态;对话框渲染

**说明:** 这是最复杂的 Task。先做基础设施(辅助函数 + 顶层状态 + 对话框渲染),再逐个迁移 6 个入口。

### Step 1: 添加 import

在 `MainActivity.kt` 顶部 import 区添加:

```kotlin
import com.juziss.localmediahub.ui.component.ResumePlaybackDialog
import com.juziss.localmediahub.ui.component.ResumePlaybackRequest
import com.juziss.localmediahub.ui.component.VideoOpenAction
import com.juziss.localmediahub.data.isCompleted
import com.juziss.localmediahub.data.isValidProgress
import com.juziss.localmediahub.data.shouldFocusRestart
```

### Step 2: 添加 checkPlaybackProgress 辅助函数

在 `MainActivity.kt` 文件末尾(`openPlaybackProgress` 函数之后)添加:

```kotlin
/**
 * 检查视频是否有可恢复的播放进度,并返回应执行的动作。
 * 副作用:把该视频加入"最近打开"列表。
 */
private suspend fun checkPlaybackProgress(
    file: MediaFile,
    isSystemBrowse: Boolean,
    store: com.juziss.localmediahub.data.RecentActivityStore,
): VideoOpenAction {
    store.addRecentMedia(file, isSystemBrowse)
    val progress = store.getPlaybackProgress(file, isSystemBrowse) ?: return VideoOpenAction.PlayDirectly(0L)
    return if (!isValidProgress(progress.positionMs, progress.durationMs)) {
        VideoOpenAction.PlayDirectly(0L)
    } else if (isCompleted(progress.positionMs, progress.durationMs)) {
        VideoOpenAction.ShowCompletedDialog(progress.positionMs, progress.durationMs)
    } else {
        VideoOpenAction.PlayDirectly(progress.positionMs)
    }
}
```

### Step 3: 添加顶层 resumeRequest 状态

在 `LocalMediaHubApp` 函数内,现有 `var currentVideoIsLocal by remember { ... }`(约 93 行)之后、`val appScope = rememberCoroutineScope()` 之前,添加:

```kotlin
    var resumeRequest by remember { mutableStateOf<ResumePlaybackRequest?>(null) }
```

### Step 4: 添加 playVideo lambda

在 `val downloadedEntries by ...`(98 行)之后、`NavHost(...)` 之前,添加:

```kotlin
    val playVideo = { file: MediaFile, url: String, positionMs: Long, isSys: Boolean ->
        currentVideoFile = file
        currentVideoUrl = url
        currentVideoStartPositionMs = positionMs
        currentVideoUsesSystemUrl = isSys
        navController.navigate("videoPlayer")
    }
```

### Step 5: 迁移 Browse → onVideoClick(约 222-234 行)

把当前代码:
```kotlin
                onVideoClick = { file ->
                    appScope.launch {
                        recentActivityStore.addRecentMedia(
                            file = file,
                            isSystemBrowse = browseViewModel.isSystemBrowseMode(),
                        )
                    }
                    currentVideoFile = file
                    currentVideoUrl = browseViewModel.getVideoStreamUrl(file)
                    currentVideoUsesSystemUrl = browseViewModel.isSystemBrowseMode()
                    currentVideoStartPositionMs = 0L
                    navController.navigate("videoPlayer")
                },
```

替换为:
```kotlin
                onVideoClick = { file ->
                    appScope.launch {
                        val isSystemBrowse = browseViewModel.isSystemBrowseMode()
                        val streamUrl = browseViewModel.getVideoStreamUrl(file)
                        when (val action = checkPlaybackProgress(file, isSystemBrowse, recentActivityStore)) {
                            is VideoOpenAction.PlayDirectly ->
                                playVideo(file, streamUrl, action.positionMs, isSystemBrowse)
                            is VideoOpenAction.ShowCompletedDialog ->
                                resumeRequest = ResumePlaybackRequest(
                                    file = file,
                                    isSystemBrowse = isSystemBrowse,
                                    streamUrl = streamUrl,
                                    positionMs = action.positionMs,
                                    durationMs = action.durationMs,
                                )
                        }
                    }
                },
```

### Step 6: 迁移 Browse → onFavoriteVideoClick(约 248-260 行)

把当前代码:
```kotlin
                onFavoriteVideoClick = { file, isSystemBrowse ->
                    appScope.launch {
                        recentActivityStore.addRecentMedia(
                            file = file,
                            isSystemBrowse = isSystemBrowse,
                        )
                    }
                    currentVideoFile = file
                    currentVideoUrl = browseViewModel.getFavoriteVideoStreamUrl(file)
                    currentVideoUsesSystemUrl = isSystemBrowse
                    currentVideoStartPositionMs = 0L
                    navController.navigate("videoPlayer")
                },
```

替换为:
```kotlin
                onFavoriteVideoClick = { file, isSystemBrowse ->
                    appScope.launch {
                        val streamUrl = browseViewModel.getFavoriteVideoStreamUrl(file)
                        when (val action = checkPlaybackProgress(file, isSystemBrowse, recentActivityStore)) {
                            is VideoOpenAction.PlayDirectly ->
                                playVideo(file, streamUrl, action.positionMs, isSystemBrowse)
                            is VideoOpenAction.ShowCompletedDialog ->
                                resumeRequest = ResumePlaybackRequest(
                                    file = file,
                                    isSystemBrowse = isSystemBrowse,
                                    streamUrl = streamUrl,
                                    positionMs = action.positionMs,
                                    durationMs = action.durationMs,
                                )
                        }
                    }
                },
```

### Step 7: 迁移 Home → onFavoriteClick 的 video 分支(约 176-181 行)

把当前 video 分支:
```kotlin
                    if (file.mediaType == "video") {
                        currentVideoFile = file
                        currentVideoUrl = homeViewModel.getFavoriteStreamUrl(file)
                        currentVideoUsesSystemUrl = homeViewModel.isFavoriteSystemBrowse(file)
                        currentVideoStartPositionMs = 0L
                        navController.navigate("videoPlayer")
                    } else {
```

替换为:
```kotlin
                    if (file.mediaType == "video") {
                        appScope.launch {
                            val isSystemBrowse = homeViewModel.isFavoriteSystemBrowse(file)
                            val streamUrl = homeViewModel.getFavoriteStreamUrl(file)
                            when (val action = checkPlaybackProgress(file, isSystemBrowse, recentActivityStore)) {
                                is VideoOpenAction.PlayDirectly ->
                                    playVideo(file, streamUrl, action.positionMs, isSystemBrowse)
                                is VideoOpenAction.ShowCompletedDialog ->
                                    resumeRequest = ResumePlaybackRequest(
                                        file = file,
                                        isSystemBrowse = isSystemBrowse,
                                        streamUrl = streamUrl,
                                        positionMs = action.positionMs,
                                        durationMs = action.durationMs,
                                    )
                            }
                        }
                    } else {
```

注意:原代码在 `onFavoriteClick` 开头已经有一个独立的 `appScope.launch { recentActivityStore.addRecentMedia(...) }`(170-175 行)。`checkPlaybackProgress` 内部已经调 `addRecentMedia`,因此原 170-175 行的 launch 块应该**删除**(否则会重复加入)。删除范围:`onFavoriteClick = { file ->` 之后到 `if (file.mediaType == "video")` 之前的 `appScope.launch { ... }` 整块。

### Step 8: 迁移 Home → onDownloadClick 的 video 分支(约 200-206 行)

把当前 video 分支:
```kotlin
                    if (entry.file.mediaType == "video") {
                        currentVideoFile = entry.file
                        currentVideoUrl = "file://${entry.localPath}"
                        currentVideoUsesSystemUrl = false
                        currentVideoStartPositionMs = 0L
                        navController.navigate("videoPlayer")
                    } else {
```

替换为:
```kotlin
                    if (entry.file.mediaType == "video") {
                        appScope.launch {
                            val streamUrl = "file://${entry.localPath}"
                            when (val action = checkPlaybackProgress(entry.file, false, recentActivityStore)) {
                                is VideoOpenAction.PlayDirectly ->
                                    playVideo(entry.file, streamUrl, action.positionMs, false)
                                is VideoOpenAction.ShowCompletedDialog ->
                                    resumeRequest = ResumePlaybackRequest(
                                        file = entry.file,
                                        isSystemBrowse = false,
                                        streamUrl = streamUrl,
                                        positionMs = action.positionMs,
                                        durationMs = action.durationMs,
                                    )
                            }
                        }
                    } else {
```

### Step 9: 迁移 Downloads → onVideoClick(约 352-358 行)

把当前代码:
```kotlin
                onVideoClick = { file, localPath ->
                    currentVideoFile = file
                    currentVideoUrl = "file://$localPath"
                    currentVideoUsesSystemUrl = false
                    currentVideoStartPositionMs = 0L
                    navController.navigate("videoPlayer")
                },
```

替换为:
```kotlin
                onVideoClick = { file, localPath ->
                    appScope.launch {
                        val streamUrl = "file://$localPath"
                        when (val action = checkPlaybackProgress(file, false, recentActivityStore)) {
                            is VideoOpenAction.PlayDirectly ->
                                playVideo(file, streamUrl, action.positionMs, false)
                            is VideoOpenAction.ShowCompletedDialog ->
                                resumeRequest = ResumePlaybackRequest(
                                    file = file,
                                    isSystemBrowse = false,
                                    streamUrl = streamUrl,
                                    positionMs = action.positionMs,
                                    durationMs = action.durationMs,
                                )
                        }
                    }
                },
```

### Step 10: 迁移 openRecentMedia 函数(约 372-387 行)

当前 `openRecentMedia` 函数:
```kotlin
private fun openRecentMedia(
    entry: RecentMediaEntry,
    homeViewModel: HomeViewModel,
    navController: NavHostController,
    onVideoReady: (MediaFile, String) -> Unit,
    onImageReady: (MediaFile, List<MediaFile>) -> Unit,
) {
    if (entry.file.mediaType == "video") {
        onVideoReady(entry.file, homeViewModel.getVideoStreamUrl(entry))
        navController.navigate("videoPlayer")
        return
    }

    onImageReady(entry.file, listOf(entry.file))
    navController.navigate("imagePreview")
}
```

需要把 `onVideoReady` 的签名扩展为 suspend + 接收 positionMs。但 `openRecentMedia` 是从 `HomeScreen.onOpenRecentMedia`(MainActivity.kt:152-168)调用的,签名也需调整。

**修改方式:** 改 `openRecentMedia` 为 suspend + 接收 `recentActivityStore` + 把 `onVideoReady` 签名改成 `(MediaFile, String, Long) -> Unit`,内部调 `checkPlaybackProgress`:

```kotlin
private suspend fun openRecentMedia(
    entry: RecentMediaEntry,
    homeViewModel: HomeViewModel,
    recentActivityStore: com.juziss.localmediahub.data.RecentActivityStore,
    onVideoReady: (MediaFile, String, Long) -> Unit,
    onShowResumeDialog: (ResumePlaybackRequest) -> Unit,
    onImageReady: (MediaFile, List<MediaFile>) -> Unit,
    navigateToVideoPlayer: () -> Unit,
    navigateToImagePreview: () -> Unit,
) {
    if (entry.file.mediaType == "video") {
        val streamUrl = homeViewModel.getVideoStreamUrl(entry)
        when (val action = checkPlaybackProgress(entry.file, entry.isSystemBrowse, recentActivityStore)) {
            is VideoOpenAction.PlayDirectly -> {
                onVideoReady(entry.file, streamUrl, action.positionMs)
                navigateToVideoPlayer()
            }
            is VideoOpenAction.ShowCompletedDialog -> {
                onShowResumeDialog(
                    ResumePlaybackRequest(
                        file = entry.file,
                        isSystemBrowse = entry.isSystemBrowse,
                        streamUrl = streamUrl,
                        positionMs = action.positionMs,
                        durationMs = action.durationMs,
                    )
                )
            }
        }
        return
    }

    onImageReady(entry.file, listOf(entry.file))
    navigateToImagePreview()
}
```

并修改调用处(MainActivity.kt:152-168):
```kotlin
                onOpenRecentMedia = { entry ->
                    appScope.launch {
                        openRecentMedia(
                            entry = entry,
                            homeViewModel = homeViewModel,
                            recentActivityStore = recentActivityStore,
                            onVideoReady = { file, url, positionMs ->
                                currentVideoFile = file
                                currentVideoUrl = url
                                currentVideoStartPositionMs = positionMs
                                currentVideoUsesSystemUrl = entry.isSystemBrowse
                            },
                            onShowResumeDialog = { req -> resumeRequest = req },
                            onImageReady = { file, images ->
                                currentImageFile = file
                                imageList = images
                                currentImageUsesSystemUrl = entry.isSystemBrowse
                                currentImageIsLocal = false
                            },
                            navigateToVideoPlayer = { navController.navigate("videoPlayer") },
                            navigateToImagePreview = { navController.navigate("imagePreview") },
                        )
                    }
                },
```

### Step 11: 添加对话框渲染

在 `NavHost(...) { ... }` 闭合 `}` 之后、`LocalMediaHubApp` 函数闭合 `}` 之前,添加:

```kotlin
    resumeRequest?.let { req ->
        val focusResume = !shouldFocusRestart(req.positionMs, req.durationMs)
        ResumePlaybackDialog(
            request = req,
            focusResume = focusResume,
            onRestart = {
                appScope.launch {
                    recentActivityStore.clearPlaybackProgress(req.file, req.isSystemBrowse)
                    playVideo(req.file, req.streamUrl, 0L, req.isSystemBrowse)
                    resumeRequest = null
                }
            },
            onResume = {
                playVideo(req.file, req.streamUrl, req.positionMs, req.isSystemBrowse)
                resumeRequest = null
            },
            onDismiss = {
                resumeRequest = null
            },
        )
    }
```

### Step 12: 编译验证

Run:
```bash
cd android && ./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL。

如果编译失败:
- 检查是否漏删了 Task 7 Step 7 提到的重复 `addRecentMedia` 调用
- 检查所有 import
- 检查 `openRecentMedia` 调用处的参数列表与新签名一致

### Step 13: 跑全部单元测试

Run:
```bash
cd android && ./gradlew :app:testDebugUnitTest
```
Expected: 全部 PASS。

### Step 14: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/MainActivity.kt
git commit -m "$(cat <<'EOF'
feat(android): resume video playback from all entry points (round 22)

All 6 video entry points (Browse onVideoClick, Browse onFavoriteVideoClick,
Home onFavoriteClick video branch, Home onOpenRecentMedia video branch,
Downloads onVideoClick, Home onDownloadClick video branch) now check
RecentActivityStore for saved progress and either resume automatically
(<95%) or show ResumePlaybackDialog (>=95%).

The shared checkPlaybackProgress helper centralizes the lookup and
addRecentMedia side effect. ResumePlaybackRequest state lives at the
LocalMediaHubApp top level.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: 手动验证 + 文档收尾

**Files:**
- 无修改(纯验证 + 可选的小调整)

- [ ] **Step 1: 构建并安装到设备/模拟器**

Run:
```bash
cd android && ./gradlew :app:installDebug
```
Expected: BUILD SUCCESSFUL, APK 安装成功。

- [ ] **Step 2: 手动验证清单**

在设备上依次验证(每项打勾):

- [ ] 浏览界面点视频 → 看 30 秒 → 返回 → 再点同一视频 → 从 30 秒处恢复
- [ ] 收藏列表点视频 → 看 30 秒 → 返回 → 再点同一视频 → 从上次位置恢复
- [ ] Home 的"最近打开"卡片点视频(已看过 ≥30 秒)→ 从上次位置恢复
- [ ] Downloads 点下载好的视频 → 看 30 秒 → 返回 → 再点 → 从上次位置恢复
- [ ] Home 的"下载"按钮点视频 → 同上
- [ ] 任意入口看视频到 95%(进度条接近末尾)→ 返回 → 再点 → 弹"继续 / 从头开始"对话框
- [ ] 对话框点"继续" → 从 95% 位置恢复
- [ ] 对话框点"从头开始" → 从 0 开始 + Home 的"继续观看"卡片不再显示该视频
- [ ] 对话框点外部 / 返回键 → 取消,留在当前页面
- [ ] Home 的"继续观看"卡片**不显示**已看完(≥95%)的视频

- [ ] **Step 3: 提交验证记录(可选)**

如果手动验证发现需要小调整,直接 Edit + 单独提交。如果一切正常,无需新提交。

---

## Self-Review 结果

### Spec 覆盖

- ✅ **拆分 shouldKeepPlaybackProgress** → Task 2
- ✅ **新增 getPlaybackProgress 单条查询** → Task 4
- ✅ **HomeViewModel continueWatching 过滤 ≥95%** → Task 5
- ✅ **统一 openVideoWithResume 辅助函数** → Task 8(checkPlaybackProgress + playVideo)
- ✅ **6 个入口迁移** → Task 8 Steps 5-10
- ✅ **ResumePlaybackDialog Composable + ResumePlaybackRequest 状态** → Task 7 + Task 8 Step 11
- ✅ **formatTime 提取到 TimeUtil** → Task 1
- ✅ **strings.xml 汉化** → Task 6
- ✅ **D2 分段聚焦策略(95%/98%)** → Task 2(shouldFocusRestart)+ Task 8 Step 11(focusResume 参数)

### 已知简化

- Compose Material3 `AlertDialog` 不直接支持 FocusRequester 默认聚焦。Task 7 注释中说明了这点。`focusResume` 参数已经透传到对话框层,如果未来接入 TV / 遥控器,可在对话框内部按此参数加 FocusRequester。**触摸屏用户体验不受影响**(无初始焦点 = 用户主动点击,符合预期)。

### 类型一致性

- `VideoOpenAction.PlayDirectly(positionMs: Long)` / `ShowCompletedDialog(positionMs: Long, durationMs: Long)` — Task 7 定义,Task 2 Step 8 使用,字段名一致 ✓
- `ResumePlaybackRequest(file, isSystemBrowse, streamUrl, positionMs, durationMs)` — Task 7 定义,Task 8 多处使用,字段名一致 ✓
- `checkPlaybackProgress(file, isSystemBrowse, store): VideoOpenAction` — Task 8 Step 2 定义,所有入口调用一致 ✓
- `playVideo(file, url, positionMs, isSys)` — Task 8 Step 4 定义,所有入口调用一致 ✓
- `findPlaybackProgress(list, file, isSystemBrowse): PlaybackProgressEntry?` — Task 4 定义,在 `getPlaybackProgress` 内部使用 ✓
- `filterContinueWatching(entries): List<PlaybackProgressEntry>` — Task 5 定义,在 HomeViewModel collector 内使用 ✓
