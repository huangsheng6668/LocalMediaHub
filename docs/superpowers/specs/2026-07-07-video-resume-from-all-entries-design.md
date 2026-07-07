# 视频从任意入口恢复播放进度

**日期**:2026-07-07
**状态**:设计阶段
**作者**:huangsheng6668 + Claude

## 背景与问题

项目已有播放进度保存机制(`RecentActivityStore.savePlaybackProgress` +
`VideoPlayerScreen.onProgress` 周期性上报 + 退出/播放结束时上报)。
Home 屏幕的"继续观看"卡片能正确读出进度并续播。

但是,**其它打开视频的入口写死了 `currentVideoStartPositionMs = 0L`**,
不会去查 DataStore 里是否已有进度。所以同一视频从浏览界面、收藏、下载、
最近打开等入口点进去时,即使有保存的进度也会从头播放。

用户期望:不管从哪个入口打开同一个视频,都能从上次退出的位置继续。

## 目标

1. 所有打开视频的入口都自动检查并应用已保存的播放进度
2. 当某个视频被判定为"已看完"(进度 ≥ 95%)时,弹窗让用户选择
   "继续 / 从头开始"(默认聚焦"继续")

## 非目标

- 修改"继续观看"卡片本身的行为(已经工作正常)
- 跨服务器 / 跨账号同步进度
- 修改图片预览的任何行为
- 新增服务端 API

## 影响范围

### 受影响的入口(`MainActivity.kt`)

| 入口 | 当前位置 | 当前进度处理 |
|------|----------|--------------|
| Browse → `onVideoClick` | `MainActivity.kt:222` | 写死 0L |
| Browse → `onFavoriteVideoClick` | `MainActivity.kt:248` | 写死 0L |
| Home → `onFavoriteClick` (video 分支) | `MainActivity.kt:176` | 写死 0L |
| Home → `onOpenRecentMedia` (video 分支,在 `openRecentMedia` 函数) | `MainActivity.kt:379` | 写死 0L |
| Downloads → `onVideoClick` | `MainActivity.kt:352` | 写死 0L |
| Home → `onDownloadClick` (video 分支) | `MainActivity.kt:201` | 写死 0L |
| Home → `onContinueWatching` | `MainActivity.kt:139` | **已正确,不动** |

### 受影响的数据层

- `RecentActivityStore.kt`:`shouldKeepPlaybackProgress` 行为调整 + 新增单条查询 API
- `HomeViewModel.kt`:`continueWatching` 需要过滤掉 ≥95% 的条目

## 设计

### 1. 数据存储层调整(`RecentActivityStore.kt`)

**当前 `shouldKeepPlaybackProgress`**(`RecentActivityStore.kt:62-68`):

```kotlin
internal fun shouldKeepPlaybackProgress(
    positionMs: Long,
    durationMs: Long,
): Boolean {
    if (positionMs < 10_000L || durationMs <= 0L) return false
    return positionMs < (durationMs * 0.95).toLong()
}
```

≥95% 的进度会被**丢弃**。Q4 = c 要求看完后弹窗,所以这些条目必须保留下来。

**调整后**:拆成两个语义清晰的小函数

```kotlin
internal fun isValidProgress(positionMs: Long, durationMs: Long): Boolean {
    if (positionMs < 10_000L || durationMs <= 0L) return false
    return true
}

internal fun isCompleted(positionMs: Long, durationMs: Long): Boolean {
    if (durationMs <= 0L) return false
    return positionMs >= (durationMs * 0.95).toLong()
}
```

`savePlaybackProgress` 改为:只要 `isValidProgress` 为真就保存(不再因 ≥95% 而丢弃)。
原有的"看完丢弃"行为消失,但这正是 Q4 = c 需要的——存储里要能查到"已看完"的记录,
入口才能据此弹窗。

**新增单条查询 API**:

```kotlin
suspend fun getPlaybackProgress(
    file: MediaFile,
    isSystemBrowse: Boolean,
): PlaybackProgressEntry?
```

按 `(file.relativePath, isSystemBrowse)` 在 DataStore 的当前快照中匹配返回。
这是同步读取当前值,而不是收集 Flow——因为入口处只需要"现在有没有进度"这一帧。

### 2. Home 屏幕过滤已看完条目

`HomeViewModel` 当前如何暴露 `continueWatching` 需要确认(下一步在实现时核实),
但设计上:"继续观看"卡片**不应显示进度 ≥95% 的条目**——否则会变成一个永远从头播的卡片。
过滤逻辑复用 `isCompleted`。

### 3. 统一的"打开视频"辅助函数(`MainActivity.kt`)

把 6 个入口里重复的"`currentVideoXxx = ...` + `navController.navigate("videoPlayer")`"
抽成一个 suspend 辅助函数(顶层私有函数,挂在 `MainActivity.kt` 文件里):

```kotlin
private suspend fun openVideoWithResume(
    file: MediaFile,
    isSystemBrowse: Boolean,
    streamUrl: String,
    store: RecentActivityStore,
    onReady: (file: MediaFile, url: String, positionMs: Long, isSystemBrowse: Boolean) -> Unit,
    onCompletedDialog: (file: MediaFile, positionMs: Long, durationMs: Long, resumeAction: () -> Unit) -> Unit,
    navigateToVideoPlayer: () -> Unit,
)
```

行为:

1. 调 `store.getPlaybackProgress(file, isSystemBrowse)`
2. 分三种情况:
   - `null`(没记录 / 播放不到 10 秒)→ `onReady(file, url, 0L, isSystemBrowse)` + `navigateToVideoPlayer()`
   - 有记录且 `!isCompleted` → `onReady(file, url, progress.positionMs, isSystemBrowse)` + `navigateToVideoPlayer()`
   - 有记录且 `isCompleted` → 调 `onCompletedDialog(...)`,把以下两种选择交给 UI:
     - 选"继续" → 同上一种情况(position = progress.positionMs)
     - 选"从头" → 调 `store.clearPlaybackProgress(file, isSystemBrowse)` 后 `onReady(file, url, 0L, isSystemBrowse)` + navigate

`onReady` 回调就是把 `MainActivity` 里的 `currentVideoFile = ...` 等四个 setter 收拢成一行。

### 4. 对话框 Composable

新增 `ResumePlaybackDialog`(放在 `ui/component/` 下,具体文件待实现时决定):

- 内容:`上次看到 ${formatTimestamp(positionMs)},继续?`
- 两个按钮:`[从头开始]` `[继续]`(默认聚焦"继续")
- 时间戳格式:超过 60 分钟显示 `H:MM:SS`,否则 `MM:SS`

对话框状态由一个 `mutableStateOf<ResumeRequest?>`(`sealed class` 或 `data class`)
挂在 `LocalMediaHubApp` 顶层。`onCompletedDialog` 回调把请求塞进去,UI 层渲染对话框,
用户点按钮后调对应分支并清空状态。

### 5. 数据流总览

```
用户点击视频(任意入口)
  → openVideoWithResume(file, isSystemBrowse, streamUrl, ...)
  → store.getPlaybackProgress(file, isSystemBrowse)
       ├─ null / < 10s
       │     → onReady(pos = 0) → navigate("videoPlayer")
       ├─ 10s ≤ pos < 95%
       │     → onReady(pos = progress.positionMs) → navigate("videoPlayer")
       └─ pos ≥ 95% (已看完)
             → 显示 ResumePlaybackDialog
                  ├─ 点"继续"  → onReady(pos = progress.positionMs) → navigate
                  └─ 点"从头" → clearPlaybackProgress + onReady(pos = 0) → navigate
```

播放过程中 VideoPlayerScreen 的 `onProgress` 继续周期性保存(已有逻辑,不动)。
退出播放器时(`DisposableEffect` 的 `onDispose` `VideoPlayerScreen.kt:191-198`)
也会把最终位置上报,这条路径已经正常工作。

## 测试

### 单元测试(扩展 `RecentActivityStoreTest.kt`)

- `getPlaybackProgress(file, isSystemBrowse)`:
  - 命中:`relativePath + isSystemBrowse` 完全匹配返回正确条目
  - 不命中:`relativePath` 匹配但 `isSystemBrowse` 不一致 → 返回 null
  - 空存储 → 返回 null
- `isValidProgress` / `isCompleted` 边界:
  - `isValidProgress`:positionMs < 10_000 → false;durationMs ≤ 0 → false
  - `isCompleted`:positionMs == 0.95 * durationMs → true;刚好少 1ms → false
- `savePlaybackProgress` 行为变化:
  - 保存 ≥95% 的进度不再被丢弃(新行为)
  - 保存 < 10 秒的进度仍然被丢弃(不变)
- Home `continueWatching` 过滤:≥95% 的条目不出现

### 手动验证

- 从浏览界面点视频 → 看 ≥30 秒 → 退出 → 再点同一视频 → 应从上次位置恢复
- 从收藏点视频 → 看 ≥30 秒、看到 ≥95% → 退出 → 再点同一视频 → 弹"继续 / 从头"
- 点"继续" → 从 95% 位置恢复;点"从头" → 从 0 开始且 DataStore 中该条目被清除
- 看完后 Home 不显示该视频的"继续观看"卡片
- 下载列表里的本地视频点进去,seekTo 也能恢复(非 transcode 路径)

## 风险与边界情况

- **Transcode 流**:带 `transcode=true` 的 URL,`VideoPlayerScreen.kt:156-161`
  已经在 `initialPositionMs > 0` 时通过 URL `start` 参数处理,本设计不需要额外逻辑。
- **本地下载视频**(`file://` URL):ExoPlayer 直接 `seekTo` 即可,
  `VideoPlayerScreen.kt:171-173` 已正确分支(非 transcode 走 `seekTo`),无需改动。
- **同一视频被多个入口并发触发**:理论上单用户操作不会发生;DataStore 写入是顺序的。
- **`relativePath` 作为唯一性 key**:与现有"继续观看"卡片相同,沿用现有约束。
- **首次进入新服务器**:`relativePath` 是相对路径,跨服务器可能重复——但这是现有
  机制已经接受的行为,本设计不引入新风险。

## 开放问题

无。所有关键决定已在 brainstorming 阶段敲定:
- Q3 = E(所有入口都续播)
- Q4 = c(看完弹窗)
- D1 = b(简洁文案"上次看到 MM:SS,继续?")
- D2 = "继续"作为默认聚焦按钮
