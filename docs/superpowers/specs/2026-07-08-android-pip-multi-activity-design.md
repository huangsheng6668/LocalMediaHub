# Android 视频悬浮窗 (Picture-in-Picture) 多 Activity 重构设计

**日期**: 2026-07-08
**作者**: huangsheng6668 + Claude
**目标平台**: Android（minSdk 26, targetSdk 34, compileSdk 36）
**前置**: 取代 `2026-07-08-android-pip-design.md` 的单 Activity 方案（该方案在 overlay 架构尝试中失败）

## 1. 背景与目标

### 原始需求

用户希望观看视频时切换到其他 App 或在 App 内浏览图片，视频以悬浮窗形式持续播放。

### 单 Activity 方案的失败

最初的设计（`2026-07-08-android-pip-design.md`）采用单 Activity 架构，VideoPlayerScreen 作为 NavHost 目的地 + PiP。实测遇到根本性技术限制：

- **PiP 浮窗捕获 Activity 的可见视图树**。视频画面（ExoPlayer 的 SurfaceView）必须在视图树里、且有非零可见区域，PiP 才能捕获。
- 单 Activity 下，overlay 全屏会盖住 NavHost（用户看不到首页）；overlay `size=0` 隐藏则 PiP 也捕获不到。
- 一轮 overlay 重构（VideoPlayerScreen 作为顶层 overlay）失败：PiP 浮窗显示首页而非视频。

### 多 Activity 方案（本设计）

将视频播放独立为 `VideoPlayerActivity`，与承载浏览/图片的 `MainActivity` 分离。这是 Android PiP 的标准设计意图 —— 每个 Activity 是独立的窗口实体，进入 PiP 后在独立 task 栈存活，与其他 Activity 生命周期完全解耦。

### 用户决策摘要

| 维度 | 选择 | 决策原因 |
|---|---|---|
| 进度回传 | **VideoPlayerActivity 直接注入 RecentActivityStore** | Hilt `@Singleton`，两个 Activity 共享同一实例，无需 Intent 回传 |
| Intent 数据传递 | **4 个参数全部传**（streamUrl + position + MediaFile + isSystemBrowse） | MediaFile 已 `@Parcelize`，零成本传递 |
| 浮窗交互 | **播放/暂停 RemoteAction + 点主体回全屏** | 主流视频 App 标准行为，迁移后自然保留 |
| 再开 App 行为 | **多 Activity 自然行为**（MainActivity 显示 + PiP 浮窗持续） | 系统天然支持，无需任何 hack 代码 |
| overlay 代码处理 | **回退到 NavHost 基础 + 保留 PiP 工具类** | 基于已知能工作的代码改，复用已验证的工具类 |

### 非目标（YAGNI）

- 不做跨 Activity 的 ViewModel 共享（RecentActivityStore 单例已足够）
- 不做自定义 Activity 过渡动画（用系统默认）
- 不实现 `setAutoEnterEnabled`（手动触发，与需求冲突）
- 不做 VideoPlayerScreen 的进程死亡状态恢复（合理降级）

## 2. 架构与组件

### 2.1 文件结构

| 文件 | 责任 | 状态 |
|---|---|---|
| `MainActivity.kt` | 图片浏览/导航宿主，**删除所有 PiP 代码**，回到接近原始状态 | 修改（瘦身） |
| `VideoPlayerActivity.kt` | **新建**。视频播放独立 Activity，承载 ExoPlayer + 所有 PiP 逻辑 | 新建 |
| `VideoPlayerScreen.kt` | Composable，**几乎不变**。宿主从 NavHost 目的地变成 VideoPlayerActivity 的 setContent | 微调 |
| `pip/PipController.kt` | PiP 参数构造工具 | 不变（复用） |
| `pip/PipControllerStore.kt` | ExoPlayer 弱引用桥接 | 不变（复用） |
| `pip/PipActionReceiver.kt` | RemoteAction 接收器 | 不变（复用） |
| `AndroidManifest.xml` | MainActivity 恢复 `singleTop`；VideoPlayerActivity 新增声明 | 修改 |

### 2.2 运行时组件分工

```
┌─────────────────────────────────────────────────────────────┐
│  MainActivity (ComponentActivity, @AndroidEntryPoint)       │
│  ├─ launchMode = "singleTop"（恢复，不再是 singleTask）     │
│  ├─ 默认 taskAffinity = "com.juziss.localmediahub"          │
│  ├─ NavHost: connection / home / browse / imagePreview / downloads │
│  └─ 点视频 → startActivity(Intent → VideoPlayerActivity)   │
└─────────────────────────────────────────────────────────────┘
                            │ startActivity
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  VideoPlayerActivity (ComponentActivity, @AndroidEntryPoint)│
│  ├─ launchMode = "singleTask"                                │
│  ├─ supportsPictureInPicture = true                          │
│  ├─ taskAffinity = "com.juziss.localmediahub.video"（独立） │
│  ├─ @Inject RecentActivityStore（保存进度）                  │
│  ├─ 持有 isInPipMode: StateFlow<Boolean>                    │
│  ├─ enterPipMode(width, height, isPlaying, sourceRectHint)  │
│  ├─ onPictureInPictureModeChanged(isInPip)                  │
│  │    → 更新 isInPipMode、注册/解绑 PipActionReceiver       │
│  ├─ onNewIntent(newParams) → 更新 ExoPlayer mediaItem        │
│  └─ setContent { VideoPlayerScreen(...) }                    │
└─────────────────────────────────────────────────────────────┘
              ▲                              ▲
              │ enterPipMode()              │ 读取 isInPipMode
              │                              │ 切换 UI
┌─────────────┴──────────┐      ┌────────────┴──────────────┐
│ VideoPlayerScreen      │      │ PipController (复用)      │
│ ├─ PlayerView (ExoPlayer)│      │ ├─ buildParams()         │
│ ├─ 右上角悬浮窗按钮     │      │ │   → 16:9 + actions      │
│ ├─ onProgress 回调      │      │ └─ (无状态)              │
│ │   → RecentActivityStore │      └───────────────────────────┘
│ └─ PiP 时隐藏自定义控件 │
│   (useController=false) │      ┌───────────────────────────┐
└────────────────────────┘      │ PipControllerStore (复用)│
                                 │ ├─ bind/unbind ExoPlayer │
                                 │ └─ togglePlayPause()     │
                                 └───────────────────────────┘
```

### 2.3 关键架构决策

1. **独立 taskAffinity**：VideoPlayerActivity 声明 `android:taskAffinity="com.juziss.localmediahub.video"`，使其进入 PiP 时系统把它放在独立 task 栈，与 MainActivity 完全解耦。这是多 Activity PiP 方案的关键配置。

2. **数据传递**：MainActivity 通过 `Intent.putExtra(...)` 传递 4 个参数到 VideoPlayerActivity：
   - `streamUrl: String`
   - `initialPositionMs: Long`
   - `isSystemBrowse: Boolean`
   - `mediaFile: MediaFile`（`@Parcelize`，可直接 putExtra）

3. **进度保存与 UI 刷新**：VideoPlayerActivity 用 `@AndroidEntryPoint` 注入 `RecentActivityStore`（Hilt `@Singleton`），在 `onPause` / `onStop` / ExoPlayer 进度回调时直接调 `savePlaybackProgress`，**不需要回传给 MainActivity**。同时，由于 `RecentActivityStore` 为全局单例且底层采用 Flow 暴露数据，当 `VideoPlayerActivity` 销毁、用户回到 `MainActivity` 时，`MainActivity` 的 Compose UI 会自动响应 Flow 更新，实现播放进度与“继续播放”列表的无感、实时刷新。

4. **PiP 状态归属**：所有 PiP 相关代码（`isInPipMode`、`enterPipMode`、`PipActionReceiver` 注册、`onPictureInPictureModeChanged`）从 MainActivity **整体迁移**到 VideoPlayerActivity。MainActivity 不再有任何 PiP 代码。

5. **VideoPlayerScreen 的角色**：保持为纯 Composable，接收参数 + 回调。宿主从 NavHost 目的地变成 VideoPlayerActivity 的 setContent。PiP 状态通过 Activity 暴露的 StateFlow 传给 Composable。

## 3. 数据流与时序

### 3.1 打开视频（MainActivity 启动 VideoPlayerActivity）

```
用户在 HomeScreen/BrowseScreen 点视频
        │
        ▼
MainActivity.playVideo(file, url, positionMs, isSys)
        │
        ├─ 不再设置 currentVideoFile 等状态
        ├─ 不再 navigate("videoPlayer")
        ├─ 构造 Intent(this, VideoPlayerActivity::class.java)
        │     .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)  ← 显式加入以确保跨 Task 启动在所有定制 ROM 上表现一致
        │     .putExtra("streamUrl", url)
        │     .putExtra("initialPositionMs", positionMs)
        │     .putExtra("isSystemBrowse", isSys)
        │     .putExtra("mediaFile", file)     ← MediaFile @Parcelize
        ├─ startActivity(intent)
        ▼
VideoPlayerActivity.onCreate(savedInstanceState)
        │
        ├─ @AndroidEntryPoint → Hilt 注入 RecentActivityStore
        ├─ 从 intent 读取 4 个参数
        ├─ setContent { VideoPlayerScreen(streamUrl, initialPositionMs, onProgress, onBack) }
        │     其中 onProgress 直接调 recentActivityStore.savePlaybackProgress(...)
        │     onBack 调 finish()
        ▼
用户全屏观看视频
```

### 3.2 进入 PiP

```
用户点 VideoPlayerScreen 右上角悬浮窗按钮
        │
        ▼
VideoPlayerScreen.onClickPipButton()
        │
        ├─ 通过 LocalContext 拿到 Activity（现在是 VideoPlayerActivity）
        ├─ 调 videoPlayerActivity.enterPipMode(width, height, isPlaying, sourceRectHint)
        ▼
VideoPlayerActivity.enterPipMode(...)   ← 从 MainActivity 迁移来的代码
        │
        ├─ PipController.buildParams(...)
        ├─ registerReceiver(PipActionReceiver, RECEIVER_NOT_EXPORTED)
        ├─ enterPictureInPictureMode(params)
        ▼
系统把 VideoPlayerActivity 缩小为 PiP 浮窗
        │
        ▼
onPictureInPictureModeChanged(true) → isInPipMode = true
        │
        ▼
VideoPlayerScreen 观察到 isInPipMode=true
        ├─ PlayerView.useController = false
        └─ 隐藏手势层
```

### 3.3 PiP 中用户切到桌面，再点 App 图标（核心场景）

```
用户按 Home 键 → VideoPlayerActivity 进入 PiP（如果还没进）
        │
        ▼
用户在桌面点 App 图标
        │
        ▼
Launcher 发送 MAIN/LAUNCHER intent
        │
        ▼
系统路由到 MainActivity（有 LAUNCHER intent-filter 的 Activity）
        │
        ├─ MainActivity 已存在（singleTop）→ onNewIntent 或 onResume
        ├─ MainActivity 显示 home/browse 页
        │
        ▼
VideoPlayerActivity 在独立 task 中保持 PiP 状态
        │
        ├─ 浮窗持续显示视频画面 ✓
        ├─ ExoPlayer 继续播放 ✓
        └─ 不受 MainActivity 任何影响 ✓
        │
        ▼
用户在 MainActivity 自由浏览图片，视频浮窗一直在角落播放
```

**这是多 Activity 方案的核心优势**：不需要任何 `onNewIntent` hack、不需要 `reenterPip`、不需要 `pendingReenterPip` 标志。系统天然支持。

### 3.4 点浮窗主体 → 回全屏

```
用户点 PiP 浮窗主体
        │
        ▼
系统：VideoPlayerActivity 退出 PiP，回到全屏
        │
        ▼
onPictureInPictureModeChanged(false) → isInPipMode = false
        │
        ├─ 解绑 PipActionReceiver
        └─ VideoPlayerScreen 恢复全屏 UI
        │
        ▼
视频无缝继续全屏播放
```

### 3.5 点系统 × 关闭浮窗

```
用户点 PiP 浮窗右上角的 × （或上滑）
        │
        ▼
系统 finish VideoPlayerActivity
        │
        ▼
VideoPlayerActivity.onDestroy
        │
        ├─ PipControllerStore.unbind()
        ├─ RecentActivityStore 进度已通过 ExoPlayer listener 持续保存
        └─ Activity 销毁，浮窗消失
        │
        ▼
用户回到 MainActivity（之前一直在后台，现在到前台）
```

### 3.6 PiP 中视频自然结束

```
ExoPlayer STATE_ENDED
        │
        ▼
VideoPlayerScreen 的 onPlaybackStateChanged 检测到
        │
        ├─ 保存进度（进度保存为 100% 并清除临时播放断点）
        ├─ 如果 isInPipMode：直接调用 finish() 销毁 Activity 自动关闭悬浮窗
        │     → 避免突然弹回全屏打扰用户在其他 App 的操作（符合 Bilibili / YouTube 等主流 App 的无打扰设计）
        └─ 如果在全屏模式下：显示播放结束画面或退出播放
        │
        ▼
Activity 正常销毁，悬浮窗自动消失，体验平滑
```

### 3.7 MainActivity 与 VideoPlayerActivity 的生命周期独立性

| 用户操作 | MainActivity | VideoPlayerActivity |
|---|---|---|
| 全屏看视频 | onStop（被 VideoPlayerActivity 覆盖） | onResume |
| 进入 PiP | onStop（仍在后台） | onPause（PiP 模式，不停止播放） |
| 切到桌面 | onStop | onPause（PiP 保持） |
| 点 App 图标 | onResume（前台显示） | onPause（PiP 保持，浮窗显示） |
| 点浮窗主体 | onStop（被 VideoPlayerActivity 覆盖） | onResume（全屏） |
| 点 × 关闭浮窗 | onResume（前台显示） | onDestroy |

**关键**：两个 Activity 的生命周期完全独立，互不影响。这是多 Activity 方案的根本优势。

## 4. 错误处理与边界情况

### 4.1 进入 PiP 失败（设备不支持 / ROM 禁用）

**触发**：少数国产 ROM 或用户关闭了系统画中画权限；`enterPictureInPictureMode()` 返回 false。

**处理**：
- `VideoPlayerActivity.enterPipMode()` 捕获返回值
- 失败时 `Toast` 提示「当前设备不支持悬浮窗，请在系统设置中开启画中画权限」
- VideoPlayerActivity 保持全屏播放，不退出

### 4.2 进入 PiP 时视频正在缓冲

**触发**：用户在视频 loading 时点悬浮窗按钮。

**处理**：
- 不阻止进入 PiP，画面显示黑屏 + 加载圈（PlayerView 默认行为）
- RemoteAction 图标显示「暂停」状态
- 视频加载完成后自动开始播放

### 4.3 PiP 中视频自然结束

**触发**：视频在浮窗里播完了。

**处理**：
- VideoPlayerScreen 的 `onPlaybackStateChanged` 检测 STATE_ENDED
- 若 `isInPipMode`：启动 `Intent(this, VideoPlayerActivity::class.java).addFlags(REORDER_TO_FRONT)` 把自己拉回前台全屏
- 触发 `onPictureInPictureModeChanged(false)`
- 保存进度（已有逻辑）

### 4.4 PiP 中按返回键

**触发**：PiP 模式下理论上不能在 App 内导航，但需防御。

**处理**：
- `isInPipMode == true` 时，`BackHandler` 被禁用（VideoPlayerScreen 已有此逻辑）
- 避免意外 finish VideoPlayerActivity 导致浮窗消失但视频未保存进度

### 4.5 进程被系统杀死后恢复

**触发**：App 在 PiP 中长时间挂起，系统回收进程。

**处理**：
- VideoPlayerActivity 不做 `rememberSaveable` 持久化
- 进程恢复后 VideoPlayerActivity 会被系统重建，但 intent extras 仍在，会用初始 position 重新播放
- **PiP 状态不恢复**：进程恢复后默认全屏模式（PiP 窗口已不存在），合理降级
- MainActivity 的 `rememberSaveable` 逻辑不受影响（图片浏览状态保留）

### 4.6 横屏 / 竖屏视频宽高比

**处理**：
- VideoPlayerScreen 从 ExoPlayer `videoSize` 读取真实宽高
- `PipController.buildParams(ratio)` 用 `setAspectRatio(Rational(width, height))`
- fallback：`videoSize == UNKNOWN` 时用 16:9
- sourceRectHint 从 PlayerView 屏幕坐标计算（已有逻辑）

### 4.7 不使用 setAutoEnterEnabled

**处理**：
- 保持手动按钮触发（用户需求决策）
- 所有 Android 版本行为一致

### 4.8 VideoPlayerActivity 重复启动（onNewIntent）

**触发**：用户在 PiP 浮窗活跃时，从 MainActivity 再次点同一个/不同视频。

**处理**：
- VideoPlayerActivity `launchMode="singleTask"` → 系统复用现有实例
- 通过 `onNewIntent` 接收新参数，首先调用 `setIntent(intent)` 更新 Activity 的 Intent 实例
- 解析新参数，更新 ExoPlayer 的 mediaItem 并 `seekTo` 新位置播放
- **系统行为与交互**：由于从前台的 MainActivity 再次启动已处于 PiP 状态（后台独立 Task）的 `singleTask` Activity，系统会自动将该 Activity 唤醒至前台（即自动退出 PiP 模式，恢复全屏），并加载新视频。这符合用户在主界面点击视频时期望“全屏观看”的直觉。

### 4.9 taskAffinity 配置

**处理**：
- VideoPlayerActivity 声明独立 `taskAffinity="com.juziss.localmediahub.video"`
- MainActivity 保持默认 taskAffinity（即包名 `com.juziss.localmediahub`）
- 两者在不同 task 栈，PiP 互不影响
- MainActivity 恢复 `launchMode="singleTop"`（不再是 singleTask，避免 task 复用冲突）
- **启动 Flag 强化**：在 `MainActivity` 中启动 `VideoPlayerActivity` 的 Intent 中，显式添加 `Intent.FLAG_ACTIVITY_NEW_TASK`。双重保障（Manifest 声明 + 启动 Flag）确保系统百分之百在独立 Task 中拉起播放器，绕过部分国产 ROM 强行合并 Task 的兼容性问题。

### 4.10 RecentActivityStore 并发写入

**触发**：VideoPlayerActivity 在 onPause / 进度回调 / onDestroy 多个时机保存进度。

**处理**：
- RecentActivityStore 内部用 DataStore（线程安全，序列化写入）
- 多次调用 `savePlaybackProgress` 不会冲突，最后一次写入生效
- 与现有 VideoPlayerScreen 行为一致

### 4.11 ExoPlayer 播放与暂停生命周期控制

为了防止在 PiP 模式下切到后台被暂停，同时保证在普通全屏模式下切出或锁屏时能正确释放资源，VideoPlayerActivity 的生命周期控制设计如下：

- **onPause**:
  - 若 `isInPictureInPictureMode == true`（进入 PiP 模式）：**不暂停**播放，继续在小窗渲染和播放。
  - 若 `isInPictureInPictureMode == false`（普通全屏下切出）：调用 `exoPlayer.pause()` 暂停播放。
- **onStop**:
  - 无论是否在 PiP 模式下，一旦 Activity 处于完全不可见状态（如设备锁屏、或者 PiP 窗被完全覆盖），必须调用 `exoPlayer.pause()` 暂停播放，以节省系统资源和电池。
- **onDestroy**:
  - 调用 `PipControllerStore.unbind()`，且 Composable 的 `onDispose` 会触发 `exoPlayer.release()` 彻底释放播放器资源。

| 场景 | 处理 |
|---|---|
| 全屏 + 切到别的 App | onPause / onStop → 暂停播放 |
| 进入 PiP + 切到别的 App | 保持 onPause (isInPip=true) → 继续播放 |
| PiP 状态下锁屏 | onStop → 暂停播放 |
| 点 × 关闭浮窗 | VideoPlayerActivity onDestroy → 释放 ExoPlayer |
| 点主体回全屏 | onResume → 继续播放（无缝切换） |
| 系统杀进程 | 随进程销毁 |

### 4.12 MainActivity 启动 VideoPlayerActivity 时的过渡

**处理**：
- 用默认 Activity 过渡动画（系统提供的 slide/fade）
- 不自定义过渡（YAGNI）
- VideoPlayerActivity 用 `enableEdgeToEdge`（与 MainActivity 一致）

## 5. 测试策略

### 5.1 测试分层

| 层 | 工具 | 覆盖范围 |
|---|---|---|
| 单元测试 | JUnit4 + Robolectric 4.13（已在依赖） | PipController.buildParams 参数构造（已有，复用） |
| 单元测试（新增） | JUnit4 + Robolectric | Intent 构造逻辑（VideoPlayerActivity 启动参数） |
| 仪器测试（instrumented） | AndroidJUnit + Espresso | PiP 进入/退出、RemoteAction 派发、跨 Activity 行为 |
| 手动验证 | 真机 | ROM 差异、taskAffinity、跨 App 持续播放、生命周期独立性 |

### 5.2 单元测试（CI 可跑）

**已有的 PipControllerTest（复用）**：5 个测试覆盖宽高比 + actions 构造，零改动。

**新增 IntentBuilderTest**（验证启动 VideoPlayerActivity 的 Intent 构造）：

1. **`buildVideoPlayerIntent_includesAllParams`**
   - 输入：MediaFile + streamUrl + position + isSystemBrowse
   - 断言 Intent 有 4 个 extras，类型正确，MediaFile 反序列化后 equals 原对象

2. **`buildVideoPlayerIntent_targetsCorrectActivity`**
   - 断言 `intent.component` == `ComponentName(ctx, VideoPlayerActivity::class.java)`

### 5.3 仪器测试（需真机/模拟器，本地跑）

**VideoPlayerActivityPipTest**（新增，`androidTest/`）：

3. **`startVideoPlayerActivity_intentExtras_passedToExoPlayer`**
   - 启动 VideoPlayerActivity 带 streamUrl + position
   - 断言 ExoPlayer.currentPosition 接近 initialPositionMs
   - 断言 ExoPlayer.currentMediaItem.mediaUrl == streamUrl

4. **`enterPipMode_buttonClicked_entersPip`**
   - 启动 VideoPlayerActivity → 点悬浮窗按钮
   - 断言 `VideoPlayerActivity.isInPictureInPictureMode` == true

5. **`pipAction_pauseButton_togglesPlayPause`**
   - 进入 PiP → 触发 PipActionReceiver 的暂停 action
   - 断言 ExoPlayer.isPlaying == false

6. **`tapPipBody_returnsToFullscreen`**
   - 进入 PiP → 模拟点击浮窗主体
   - 断言 isInPictureInPictureMode == false

7. **`closePip_finishesVideoActivity`**
   - 进入 PiP → 模拟点 ×
   - 断言 VideoPlayerActivity 被 finish

8. **`mainActivityResumes_pipKeepsPlaying`（核心场景）**
   - 启动 VideoPlayerActivity → 进入 PiP
   - 启动 MainActivity（模拟从桌面点图标）
   - 断言 VideoPlayerActivity 仍在 PiP
   - 断言 MainActivity 在前台
   - 断言 ExoPlayer 仍在播放

### 5.4 手动验证清单

无法自动化、必须真机验证的点：

- [ ] **进入视频**：从 MainActivity 点视频 → VideoPlayerActivity 全屏播放
- [ ] **返回 MainActivity**：点返回 → 回到 MainActivity 之前的页面
- [ ] **悬浮窗按钮**：右上角按钮可见，点击进入 PiP
- [ ] **浮窗显示视频画面**：PiP 浮窗显示视频，不是空白或首页
- [ ] **跨 App 持续播放**：PiP 中切到微信/浏览器，视频继续播放、有声音
- [ ] **浮窗拖动 + 缩放**：双指捏合可缩放
- [ ] **× 关闭按钮**：点击后浮窗消失、ExoPlayer 释放
- [ ] **点主体回全屏**：无声音中断、无进度跳跃
- [ ] **核心场景**：PiP 中按 Home → 点 App 图标 → MainActivity 显示 + PiP 浮窗持续显示视频
- [ ] **从 MainActivity 再次点视频**：浮窗切换到新视频（边界 4.8）
- [ ] **横屏 / 竖屏视频**：浮窗宽高比正确 letterbox
- [ ] **PiP 中视频自然结束**：退出 PiP 回全屏结束画面
- [ ] **进入 PiP 失败**（小米/华为/OPPO ROM）：Toast 提示，保持全屏
- [ ] **taskAffinity 验证**：PiP 浮窗和 MainActivity 互不影响（核心架构验证）

### 5.5 测试覆盖度权衡

- **重点测**：Intent 构造逻辑（单元测试 1-2）、跨 Activity PiP 行为（仪器测试 8）
- **仪器测**：VideoPlayerActivity 进入/退出 PiP、RemoteAction 派发、跨 Activity 独立性
- **不写**：Android 原生 PiP 系统行为（点 × 关闭、点主体回全屏的系统手势）—— 放手动验证清单
- **保留**：PipControllerTest（5 个测试，零改动复用）

## 6. 实现顺序建议（供 writing-plans 参考）

1. **回退 overlay 改动**：git revert MainActivity / VideoPlayerScreen 的 overlay 相关改动，回到「VideoPlayerScreen 是 NavHost 目的地 + 基础 PiP」状态。保留 PipController / PipControllerStore / PipActionReceiver。
2. **新建 VideoPlayerActivity**：从 MainActivity 迁移所有 PiP 代码（isInPipMode / enterPipMode / Receiver / onPictureInPictureModeChanged / onNewIntent）+ 注入 RecentActivityStore + setContent 承载 VideoPlayerScreen。
3. **修改 MainActivity**：删除所有 PiP 代码，`playVideo` 改成 `startActivity(Intent → VideoPlayerActivity)`。
4. **修改 VideoPlayerScreen**：宿主从 NavHost 目的地变成 VideoPlayerActivity，`activity as? MainActivity` 改成 `activity as? VideoPlayerActivity`，onBack 改成 `finish()`。
5. **修改 Manifest**：MainActivity 恢复 `singleTop`，新增 VideoPlayerActivity 声明（singleTask + supportsPictureInPicture + 独立 taskAffinity）。
6. **新增 IntentBuilderTest** 单元测试。
7. **手动验证清单**：在真机上验证 14 项。
