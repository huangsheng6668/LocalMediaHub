# Android 视频悬浮窗（Picture-in-Picture）设计

**日期**: 2026-07-08
**作者**: huangsheng6668 + Claude
**目标平台**: Android（minSdk 26, targetSdk 34, compileSdk 36）

## 1. 背景与目标

用户希望在观看视频的同时切换到其他 App 浏览不同内容。需要实现一个视频悬浮窗，让视频在屏幕角落持续播放，用户可以同时操作其他应用。

### 选定方案

**Activity 级 PiP（方案 A）**：基于 Android 原生 `PictureInPictureMode`，把 `MainActivity` 缩小为系统浮窗，不引入新 Activity、不需要 `SYSTEM_ALERT_WINDOW` 权限、不需要自绘 overlay。

### 用户决策摘要

| 维度 | 选择 | 决策原因 |
|---|---|---|
| 触发方式 | **手动按钮**（播放器右上角） | 用户主动控制，避免无意义浮窗 |
| 返回行为 | **点主体回全屏 / 点 × 关闭并释放** | 符合主流视频 App 习惯，无歧义 |
| 浮窗控件 | **视频画面 + 中央播放/暂停按钮**（系统提供 × 关闭） | 切到别的 App 后能快速暂停，不需切回全屏 |
| 再开 App | **B2 - 从桌面再点图标自动退出 PiP 回全屏播放** | 零闪烁，语义直观，依赖 Android 原生默认行为 |

### 非目标（YAGNI）

- 不做跨页面常驻浮窗（浮窗只在 `videoPlayer` 目的地存在）
- 不做自绘 `SYSTEM_ALERT_WINDOW` 浮窗
- 不做独立 PiP Activity（方案 B 被否决）
- 不实现 Android 12 `setAutoEnterEnabled` 自动进入（与手动触发需求冲突）
- 不做浮窗内的进度条 / 音量 / 亮度控件（极简浮窗）

## 2. 架构与组件

### 2.1 涉及改动的文件

| 文件 | 改动 |
|---|---|
| `AndroidManifest.xml` | MainActivity 增加 `android:supportsPictureInPicture="true"` 和 `android:launchMode="singleTask"`（确保桌面再点图标时复用实例并自动退出 PiP）；确保已声明 `android:configChanges` 包含 `orientation|screenSize|screenLayout|smallestScreenSize`（防止 Activity 在 PiP 切换时重建） |
| `MainActivity.kt` | 增加 PiP 进入/退出包装、`onPictureInPictureModeChanged` 回调、`isInPipMode` 状态持有、动态注册/解绑 PiP 播放控制 BroadcastReceiver |
| `VideoPlayerScreen.kt` | 右上角新增「悬浮窗」按钮；监听 PiP 状态切换 UI（全屏控件 vs PiP 极简）；调整 `LifecycleEventObserver` 逻辑，在 PiP 模式下 `onPause` 时不暂停 ExoPlayer |
| `VideoPlayerViewModel.kt` | 暴露 `isInPipMode` 状态流；保留当前视频宽高比给 PiP 参数构造 |
| **新增** `PipController.kt`（~90 行） | 封装 `PictureInPictureParams.Builder` 与 RemoteAction 的构造，解耦广播接收器注册逻辑，让 MainActivity 保持精简 |

### 2.2 运行时组件分工

```
┌─────────────────────────────────────────────────────────────┐
│  MainActivity (ComponentActivity)                           │
│  ├─ supportsPictureInPicture = true, launchMode = singleTask│
│  ├─ 持有 isInPipMode: StateFlow<Boolean>                    │
│  ├─ enterPipMode()  ← 由 VideoPlayerScreen 的按钮调用       │
│  ├─ onPictureInPictureModeChanged(isInPip, params)          │
│  │    → 更新 isInPipMode、动态注册/解绑 PipActionReceiver   │
│  │      (注意 targetSdk 34 的 RECEIVER_NOT_EXPORTED 安全要求)│
│  └─ 仅分发 PiP 模式切换状态，不直接侵入 ExoPlayer 的实例管理 │
└─────────────────────────────────────────────────────────────┘
              ▲                              ▲
              │ 调用 enterPipMode()          │ 读取 isInPipMode
              │                              │ 切换 UI
┌─────────────┴──────────┐      ┌────────────┴──────────────┐
│ VideoPlayerScreen      │      │ PipController (工具类)    │
│ ├─ PlayerView (ExoPlayer)│      │ ├─ buildParams()         │
│ ├─ 右上角悬浮窗按钮     │      │ │   → 16:9 + actions      │
│ └─ PiP 时隐藏自定义控件 │      │ └─ PipActionReceiver     │
│   (PlayerView 的        │      │     → 切换 Play/Pause    │
│    useController=false) │      │                           │
└────────────────────────┘      └───────────────────────────┘
```

### 2.3 关键设计决策

1. **PiP 状态单一来源**：`MainActivity` 持有 `isInPipMode: StateFlow<Boolean>`，`VideoPlayerScreen` 通过 CompositionLocal 或 ViewModel 读取，避免多处状态不同步。

2. **不引入新 Activity**：方案 A 的精髓，PiP 就是 MainActivity 自己缩小。`videoPlayer` 目的地依然在 NavHost 里，浮窗里看到的就是这个目的地的内容。

3. **RemoteAction 用系统机制**：浮窗中央的播放/暂停按钮通过 `PictureInPictureParams.Builder().setActions(...)` 注册，点击触发 `BroadcastReceiver` → 调用 ExoPlayer 的 `play()` / `pause()`。Android 官方做法，不依赖自绘 overlay。

4. **PipController 单独成类**：把 PiP 参数构造和 Action Receiver 逻辑从 MainActivity 抽出来，MainActivity 保持精简（当前 506 行，加 PiP 不希望膨胀到 700+）。

5. **动态 Receiver 安全合规 (targetSdk 34)**：针对 Android 14 (API 34) 的新规，动态注册 `PipActionReceiver` 时必须显式指定 `ContextCompat.RECEIVER_NOT_EXPORTED`，防止系统抛出安全异常（SecurityException）导致崩溃。

6. **PendingIntent 不可变性 (Android 12+)**：注册 `RemoteAction` 时使用的 `PendingIntent` 必须显式携带 `PendingIntent.FLAG_IMMUTABLE` 标志，以满足 Android 12+ 的安全性约束。

7. **ExoPlayer 自动音频焦点管理**：在 `VideoPlayerScreen` 中初始化 ExoPlayer 时，为其配置 `setAudioAttributes(audioAttributes, true)`。开启自动音频焦点管理，确保视频在浮窗（后台）播放时，如果被其他媒体应用夺走音频焦点，能自动暂停并响应，提升后台表现。

## 3. 数据流与时序

### 3.1 进入 PiP（用户点悬浮窗按钮）

```
用户点击右上角「悬浮窗」按钮
        │
        ▼
VideoPlayerScreen.onClickPipButton()
        │  通过 LocalContext 拿到 Activity
        ▼
MainActivity.enterPipMode()
        │
        ├─ 1. 从 VideoPlayerViewModel 读取当前视频宽高比
        │     (videoSize.width / videoSize.height, 默认 16:9)
        ├─ 2. PipController.buildParams(ratio, isPlaying)
        │     → PictureInPictureParams.Builder()
        │         .setAspectRatio(Rational(width, height))
        │         .setActions([playPauseAction])
        │         .build()
        ├─ 3. activity.enterPictureInPictureMode(params)
        ▼
系统缩小窗口 → 触发 onPictureInPictureModeChanged(true)
        │
        ▼
MainActivity 更新 isInPipMode = true
        │
        ▼
VideoPlayerScreen 观察到 isInPipMode=true
        │
        ├─ PlayerView.useController = false
        ├─ 隐藏手势层 (PlayerGestureDetector)
        └─ 显示黑色背景填满浮窗 (letterbox)
```

### 3.2 PiP 中切到别的 App

```
用户按 Home 键 → VideoPlayerScreen 绑定的 LifecycleObserver 监听到 ON_PAUSE
        │
        ├─ 检查 activity.isInPictureInPictureMode == true ?
        │     YES → 拦截 onPause 行为，不调用 exoPlayer.pause()
        │           视频继续播放 ✓
        │     NO  → 正常调用 exoPlayer.pause() 暂停播放（普通切后台）
        ▼
用户在微信/浏览器/任意 App 操作
        │
        ▼
浮窗持续播放（ExoPlayer 不释放、不暂停）
        │
        ▼
（用户可以拖动浮窗、双指缩放 — 系统提供，无需我们处理）
```

### 3.3 点浮窗主体 → 回全屏

```
用户点击浮窗画面主体
        │
        ▼
系统自动：把 MainActivity 带到前台 + 退出 PiP
        │  （Android 原生行为，点 PiP 窗口非关闭区域即触发）
        ▼
onPictureInPictureModeChanged(false)
        │
        ▼
MainActivity 更新 isInPipMode = false
        │
        ▼
VideoPlayerScreen 恢复全屏 UI
        ├─ PlayerView.useController = true
        └─ 恢复手势层、进度条
        │
        ▼
（视频未暂停，无缝继续全屏播放）
```

### 3.4 点系统 × 关闭按钮（或滑走关闭）→ 关闭浮窗

```
用户点击 PiP 浮窗右上角的系统 × （或向屏幕边缘滑走关闭）
        │
        ▼
系统关闭并销毁 Activity (MainActivity)
        │
        ├─ Compose 自动触发 VideoPlayerScreen 的 onDispose 块
        │     → 自动调用 exoPlayer.release() 释放播放器资源
        │     → 自动调用 wrappedOnProgress(...) 将当前播放进度保存至本地数据库
        ▼
应用完全退出/关闭（符合 Single-Activity 架构下画中画关闭的 Android 标准行为）
```

**3.3 与 3.4 的区分**：两者触发的生命周期和销毁机制完全不同。点主体退出 PiP 时 Activity 保持存活并返回前台，仅触发 `onPictureInPictureModeChanged(false)`，UI 状态重组为全屏播放；而点击 × 关闭（或滑走）会使系统直接 finish 并 destroy 该 Activity，进而通过 Compose `onDispose` 释放资源并落库进度，不需要我们在 `onPictureInPictureModeChanged` 里手动做判断或调用 `popBackStack`。

### 3.5 PiP 激活时，用户从桌面/通知再点 App 图标（B2 决策）

```
用户点击 App 图标
        │
        ▼
Launcher 启动 MainActivity 的 launch Intent
        │
        ▼
由于已配置 launchMode="singleTask"：
Android 系统自动复用现有的 MainActivity 实例并将其拉回前台
        │
        ▼
onPictureInPictureModeChanged(false) → isInPipMode = false
        │
        ▼
VideoPlayerScreen 恢复全屏 UI，视频无缝继续播放
```

**B2 的本质**：依赖 `launchMode="singleTask"` 结合 Android 原生默认行为实现。如果在 Manifest 中缺失 `launchMode="singleTask"`，Launcher 会启动一个新的 `MainActivity` 实例，导致画中画浮窗（旧实例）与前台全屏（新实例）共存的严重 Bug。

### 3.6 PiP 中点 RemoteAction 播放/暂停按钮

```
用户点击浮窗中央播放/暂停按钮
        │
        ▼
系统派发 PendingIntent (携带 FLAG_IMMUTABLE) → PipActionReceiver.onReceive()
        │
        ├─ 从应用层/ExoPlayer 读取当前播放状态
        ├─ 执行播放状态切换：isPlaying ? exoPlayer.pause() : exoPlayer.play()
        └─ 更新并构建新的 RemoteAction (切换 play ↔ pause 图标)
        │
        ▼
通过 setPictureInPictureParamsAsync() 刷新 params 包含的 actions
        │
        ▼
浮窗中央的按钮图标立即更新
```

## 4. 错误处理与边界情况

### 4.1 进入 PiP 失败

**触发**：少数国产 ROM（小米/华为/OPPO）可能禁用了 PiP，或用户在系统设置里关了「悬浮窗」权限；`enterPictureInPictureMode()` 返回 `false`。

**处理**：
- `enterPipMode()` 包装方法捕获返回值
- 失败时 `Toast` 提示「当前设备不支持悬浮窗，请在系统设置中开启画中画权限」
- 播放器状态不变（不暂停、不退出），用户继续全屏观看

### 4.2 进入 PiP 时视频尚未开始播放 / 正在缓冲

**触发**：用户在视频还在 loading 时就点了悬浮窗按钮。

**处理**：
- 不阻止进入 PiP，画面显示黑屏 + 加载圈（`PlayerView` 默认行为）
- RemoteAction 图标显示「暂停」状态（表示意图是播放，但实际还在加载）
- 视频加载完成后自动开始播放

### 4.3 PiP 中视频播放结束（自然结束）

**触发**：视频在浮窗里播完了。

**处理**：
- **退出 PiP**：由于 Android 框架层没有直接提供 `exitPictureInPictureMode()` 的 API，需要通过再次启动 `MainActivity` 并携带 `Intent.FLAG_ACTIVITY_REORDER_TO_FRONT` 标志来将 Activity 重新拉回前台全屏，以退出画中画状态并停留在播放结束画面。
- 触发现有的 `onProgress(positionMs, durationMs)` 回调保存进度（已有逻辑）。

### 4.4 PiP 模式下的返回键防御

**触发**：PiP 模式下理论上不能在 App 内导航（浮窗里没有导航控件）。但需防御性处理。

**处理**：
- `isInPipMode == true` 时，`onBack` 回调和系统返回键**被禁用**（`BackHandler { /* 拦截 */ }`）
- 避免用户意外退出播放器导致 ExoPlayer 释放但 PiP 窗口还在的诡异状态

### 4.5 进程被系统杀死后恢复

**触发**：App 在 PiP 中长时间挂起，系统回收进程，用户再点浮窗。

**处理**：
- `MainActivity` 已有 `rememberSaveable` 处理进程死亡恢复（`currentVideoFile`、`currentVideoUrl` 等都是 Saveable）
- 恢复后 `videoPlayer` 目的地会重建，ExoPlayer 重新初始化，从 `currentVideoStartPositionMs` 续播
- **PiP 状态不 Saveable**：进程恢复后默认处于全屏模式（PiP 窗口已不存在），这是合理的降级

### 4.6 横屏视频 vs 竖屏视频的宽高比

**触发**：不同视频宽高比不同（16:9 / 21:9 / 4:3 / 竖屏）。

**处理**：
- 从 `VideoPlayerViewModel.videoSize`（ExoPlayer 已有）读取真实宽高
- `PipController.buildParams(ratio)` 用 `setAspectRatio(Rational(width, height))`
- **fallback**：若 `videoSize == VideoSize.UNKNOWN`（视频还在加载），用 16:9 默认值
- Android 系统会自动 letterbox 不匹配的浮窗，不需要我们处理

### 4.7 不使用 Android 12+ 的 `setAutoEnterEnabled`

**触发**：Android 12 (API 31) 引入 `setAutoEnterEnabled(true)`，可以让 Activity 在用户按 Home 键时**自动**进入 PiP（无需手动调用）。

**处理**：
- **不用** `setAutoEnterEnabled` —— 用户选的是「手动按钮触发」，自动进入会冲突
- 保持纯手动调用 `enterPictureInPictureMode(params)`，所有 Android 版本行为一致

### 4.8 ExoPlayer 在 PiP 生命周期中的释放时机

| 场景 | 处理 |
|---|---|
| 进入 PiP + 切换到别的 App | 不释放，不暂停 |
| 点 × 关闭浮窗（或滑走关闭） | 自动触发 Compose 的 `onDispose` 回调，在此处调用 `exoPlayer.release()` 释放播放器且保存进度，Activity 被销毁 |
| 点主体回全屏 | 不释放（继续全屏播放） |
| 系统因内存压力杀进程 | ExoPlayer 随进程销毁，无需特殊处理 |

### 4.9 音频焦点 (Audio Focus) 自动挂起与恢复

**触发**：在画中画后台播放状态下，用户使用其他 App 播放音频/视频，或突然接听电话。

**处理**：
- 在 `VideoPlayerScreen` 中初始化 ExoPlayer 时，通过 `.setAudioAttributes(audioAttributes, true)` 将音频焦点托管给系统。
- 焦点丢失时，ExoPlayer 自动触发 `pause()`。
- 焦点重新获得时，如果属于短暂丢失，自动触发 `play()` 继续播放。

## 5. 测试策略

### 5.1 测试分层

| 层 | 工具 | 覆盖范围 |
|---|---|---|
| 单元测试 | JUnit4 + Robolectric 4.13（已在依赖） | `PipController.buildParams()` 参数构造逻辑 |
| 仪器测试（instrumented） | AndroidJUnit + Espresso | PiP 进入/退出、RemoteAction 派发 |
| 手动验证 | 真机 | ROM 差异、闪烁、跨 App 持续播放 |

### 5.2 单元测试（CI 可跑）

`PipControllerTest.kt`（新增）：

1. **`buildParams_16by9_returnsCorrectAspectRatio`**
   - 输入 `width=1920, height=1080`
   - 断言 `params.pictureInPictureParams.aspectRatio` == `Rational(16, 9)`

2. **`buildParams_unknownSize_defaultsTo16by9`**
   - 输入 `videoSize = VideoSize.UNKNOWN`
   - 断言 fallback 到 16:9

3. **`buildParams_verticalVideo_returns9by16`**
   - 输入 `width=720, height=1280`（竖屏视频）
   - 断言 aspectRatio == `Rational(9, 16)`

4. **`buildParams_includesPlayPauseAction`**
   - 断言返回的 params 包含恰好 1 个 action
   - 断言 action 的图标/icon type 对应当前播放状态

> Robolectric 已经在依赖里（`testImplementation("org.robolectric:robolectric:4.13")`），`PictureInPictureParams` 在 Robolectric 阴影对象下可构造和断言。

### 5.3 仪器测试（需真机/模拟器，本地跑）

`MainActivityPipTest.kt`（新增，`androidTest/`）：

5. **`enterPipMode_buttonClicked_entersPip`**
   - 启动 MainActivity → 导航到 videoPlayer → 点击悬浮窗按钮
   - 断言 `Activity.isInPictureInPictureMode` == true

6. **`pipMode_playerView_useControllerDisabled`**
   - 进入 PiP 后断言 `PlayerView.useController` == false

7. **`pipAction_pauseButton_togglesPlayPause`**
   - 进入 PiP → 触发 PipActionReceiver 的暂停 action
   - 断言 ExoPlayer `isPlaying` == false

8. **`exitPip_tapBody_returnsFullscreen`**
   - 进入 PiP → 模拟点击浮窗主体
   - 断言 `isInPictureInPictureMode` == false，PlayerView 控件恢复

> 仪器测试需要 API 26+ 模拟器。项目目前没有 CI 配置文件，仪器测试只在本地执行。

### 5.4 手动验证清单

无法自动化、必须真机验证的点：

- [ ] **小米/华为/OPPO ROM**：进入 PiP 是否成功（4.1）
- [ ] **跨 App 持续播放**：PiP 中切到微信/浏览器，视频是否继续播放、有声音
- [ ] **浮窗拖动 + 缩放**：双指捏合可缩放
- [ ] **× 关闭按钮**：点击后浮窗消失、ExoPlayer 释放
- [ ] **点主体回全屏**：无声音中断、无进度跳跃
- [ ] **从桌面再点 App 图标**（B2）：自动退出 PiP 回到全屏播放，无闪烁
- [ ] **横屏 / 竖屏视频**：浮窗宽高比正确 letterbox
- [ ] **PiP 中视频自然结束**：退出 PiP 回全屏结束画面

### 5.5 测试覆盖度权衡

- **不写**：时序 3.5（B2 行为依赖 Android 默认）和时序 3.3（点主体回全屏）的仪器测试 —— 这些是 Android 原生行为，测试它等于测试系统框架。改为放在手动验证清单里。
- **重点测**：`PipController` 的参数构造逻辑（单元测试 1-4），这是我们自己写的、容易出错的纯逻辑。
- **仪器测**：进入/退出 PiP、RemoteAction 派发（5-8），这些涉及 Activity 生命周期和 BroadcastReceiver，需要真实 Android 环境。

## 6. 实现顺序建议（供 writing-plans 参考）

1. Manifest 改动（supportsPictureInPicture, launchMode）+ MainActivity 基础 PiP 进入（不带 RemoteAction）→ 真机验证最小可用
2. `PipController` 抽出 + 单元测试
3. `VideoPlayerScreen` 悬浮窗按钮 + isInPipMode 状态联动
4. `VideoPlayerScreen` 里的 `LifecycleObserver` 逻辑调整（PiP 模式下不暂停，非 PiP 模式正常暂停）→ 真机验证核心场景
5. RemoteAction（播放/暂停）+ PipActionReceiver（带有安全合规的 Export 标志和 Immutable PendingIntent）
6. × 关闭时的销毁释放与进度自动落库验证
7. 边界情况（失败 toast、播放结束触发 launchActivity 回前台全屏、返回键防御、自动音频焦点管理）
8. 仪器测试补全
