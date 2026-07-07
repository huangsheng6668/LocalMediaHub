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

### 3. 统一的"打开视频"校验与逻辑控制 (`MainActivity.kt`)

为了避免传递过多繁琐的回调，利用 Kotlin 的 `sealed class` 优雅地表达"直接播放"与"弹窗确认"两种业务分支：

```kotlin
sealed class VideoOpenAction {
    data class PlayDirectly(val positionMs: Long) : VideoOpenAction()
    data class ShowCompletedDialog(val positionMs: Long, val durationMs: Long) : VideoOpenAction()
}
```

在 `MainActivity.kt` 中实现私有 `suspend` 辅助函数，自动把视频点击加入最近打开历史，并查询/判定应当执行的动作：

```kotlin
private suspend fun checkPlaybackProgress(
    file: MediaFile,
    isSystemBrowse: Boolean,
    store: RecentActivityStore,
): VideoOpenAction {
    // 自动保存至最近浏览列表，避免各入口重复编写 addRecentMedia 逻辑
    store.addRecentMedia(file, isSystemBrowse)
    
    val progress = store.getPlaybackProgress(file, isSystemBrowse)
    return if (progress != null && isValidProgress(progress.positionMs, progress.durationMs)) {
        if (isCompleted(progress.positionMs, progress.durationMs)) {
            VideoOpenAction.ShowCompletedDialog(progress.positionMs, progress.durationMs)
        } else {
            VideoOpenAction.PlayDirectly(progress.positionMs)
        }
    } else {
        VideoOpenAction.PlayDirectly(0L)
    }
}
```

在 `LocalMediaHubApp` 顶层，使用局部 lambda `playVideo` 收拢 setter 赋值和导航跳转：

```kotlin
val playVideo = { file: MediaFile, url: String, positionMs: Long, isSys: Boolean ->
    currentVideoFile = file
    currentVideoUrl = url
    currentVideoStartPositionMs = positionMs
    currentVideoUsesSystemUrl = isSys
    navController.navigate("videoPlayer")
}
```

各个点击入口（共 6 个受影响入口）直接调用 `checkPlaybackProgress` 配合 `when` 进行分支处理。以 `BrowseScreen` 的 `onVideoClick` 为例：

```kotlin
onVideoClick = { file ->
    appScope.launch {
        val isSystemBrowse = browseViewModel.isSystemBrowseMode()
        val streamUrl = browseViewModel.getVideoStreamUrl(file)
        when (val action = checkPlaybackProgress(file, isSystemBrowse, recentActivityStore)) {
            is VideoOpenAction.PlayDirectly -> {
                playVideo(file, streamUrl, action.positionMs, isSystemBrowse)
            }
            is VideoOpenAction.ShowCompletedDialog -> {
                resumeRequest = ResumePlaybackRequest(
                    file = file,
                    isSystemBrowse = isSystemBrowse,
                    streamUrl = streamUrl,
                    positionMs = action.positionMs,
                    durationMs = action.durationMs
                )
            }
        }
    }
}
```

### 4. 确认弹窗与状态维护 (`ResumePlaybackDialog`)

在 `ui/component/` 新增 `ResumePlaybackDialog`，其状态由挂在 `LocalMediaHubApp` 顶层的 `mutableStateOf<ResumePlaybackRequest?>` 维护：

```kotlin
data class ResumePlaybackRequest(
    val file: MediaFile,
    val isSystemBrowse: Boolean,
    val streamUrl: String,
    val positionMs: Long,
    val durationMs: Long,
)
```

**交互分支与状态清理规则**：
- **点击“继续播放”**：调用 `playVideo(req.file, req.streamUrl, req.positionMs, req.isSystemBrowse)` 并设置 `resumeRequest = null`。
- **点击“从头开始”**：在 `appScope` 中启动协程异步调用 `recentActivityStore.clearPlaybackProgress(req.file, req.isSystemBrowse)`，随后调用 `playVideo(req.file, req.streamUrl, 0L, req.isSystemBrowse)`，并将 `resumeRequest = null`。
- **取消 / 点击外部 / 物理返回键**：直接设置 `resumeRequest = null`（页面保持原样，取消任何界面跳转）。

**规范与优化**：
- **时间格式化复用**：将 `HomeComponents.kt` 中私有的 `formatTime` 提取至公共工具类 `com.juziss.localmediahub.util.TimeUtil` 中，并在 `ResumePlaybackDialog` 和 `HomeComponents` 中实现复用，避免代码冗余。
- **硬编码消除与汉化**：弹窗的所有文字必须采用 `strings.xml` 资源，并遵循中文规范：
  ```xml
  <string name="resume_dialog_title">继续播放</string>
  <string name="resume_dialog_message">上次看到 %1$s，是否从该进度继续播放？</string>
  <string name="resume_dialog_btn_restart">从头开始</string>
  <string name="resume_dialog_btn_resume">继续播放</string>
  ```
- **默认聚焦优化（分段策略）**：本弹窗**仅在视频已播放完毕（进度 ≥95%）时**展示。为了同时覆盖"几乎看完想重看"和"接近看完但被打断、仍想继续"两种场景，默认焦点按进度阈值分段：
  - **95% ≤ progress < 98%**：默认聚焦 **"继续播放"**。这种情况通常是网络中断、手机断电、误退出等"非真正看完"场景，用户大概率想接着看。
  - **progress ≥ 98%**：默认聚焦 **"从头开始"**。这种情况视频已实质看完，再次打开大概率是为了重看；同时如果默认聚焦"继续"会瞬间跳转至片尾然后黑屏/退出，体验差。

  阈值常量 `COMPLETED_FOCUS_THRESHOLD = 0.98` 与 `isCompleted` 用的 `0.95` 一并定义在 `RecentActivityStore.kt` 顶层（或 `TimeUtil.kt` 邻近的常量区），避免散落 magic number。这同样修正了原 Brainstorm 阶段的 D2 决定，以更细粒度地匹配真实用户意图。

### 5. 数据流总览

```
用户点击视频 (6 个不同入口)
  → 调用 checkPlaybackProgress(file, isSystemBrowse, store)
  → 添加最近打开历史 (store.addRecentMedia) 并查询进度 (store.getPlaybackProgress)
       ├─ 无进度 / 进度记录失效 (< 10s)
       │     → 返回 PlayDirectly(pos = 0L) → 调 playVideo() → 导航至播放器
       ├─ 未看完 (10s <= progress < 95%)
       │     → 返回 PlayDirectly(pos = positionMs) → 调 playVideo() → 导航至播放器
       └─ 已看完 (progress >= 95%)
             → 返回 ShowCompletedDialog → 赋值给顶层状态 resumeRequest
             → 渲染展现 ResumePlaybackDialog
                  ├─ 点击“继续” → 调 playVideo(pos = positionMs) 并清空 resumeRequest
                  ├─ 点击“从头” → 异步清除进度并调 playVideo(pos = 0L) 并清空 resumeRequest
                  └─ 点击外部/返回键 → 清空 resumeRequest (留在当前页面)
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

## 开放问题与修订建议

所有关键决策已根据用户体验（UX）与工程最佳实践进行了细化与修正：
- **Q3 (入口支持)**: 所有 6 个打开入口均集成该进度校验机制。
- **Q4 (触发条件)**: 仅在视频进度 ≥95% 时触发选择弹窗；<95% 进度时系统自动无缝续播。
- **D1 (文案设计)**: 采用规范的汉化文案 `"上次看到 MM:SS，是否从该进度继续播放？"`。
- **D2 (聚焦按钮)**: **修正为按进度分段策略**。`95% ≤ progress < 98%` 默认聚焦"继续播放"(覆盖断电/断网等非真正看完的场景),`progress ≥ 98%` 默认聚焦"从头开始"(实质看完,避免瞬间跳转片尾黑屏)。阈值常量统一在 `RecentActivityStore.kt` 顶层定义。
- **公共抽取**: `formatTime` 转移到 `TimeUtil.kt`，实现两个 Composable 模块之间的最佳代码复用。
