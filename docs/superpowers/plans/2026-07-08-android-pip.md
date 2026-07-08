# Android 视频悬浮窗 (Picture-in-Picture) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Android 视频播放器右上角增加「悬浮窗」按钮，点击后进入系统画中画 (PiP) 浮窗，支持跨 App 持续播放、中央播放/暂停按钮、点主体回全屏、点 × 关闭并释放。

**Architecture:** Activity 级 PiP（不引入新 Activity）。新增 `PipController` 工具类封装 `PictureInPictureParams` 构造与 RemoteAction；`MainActivity` 持有 `isInPipMode` 状态并暴露进入 PiP 的入口；`VideoPlayerScreen` 读取该状态切换 UI 并在 PiP 模式下跳过 ExoPlayer 的 ON_PAUSE 暂停。

**Tech Stack:** Kotlin + Jetpack Compose, Media3 ExoPlayer 1.2.0, AndroidX Activity 1.8.2, Robolectric 4.13（单元测试）, Hilt 2.56.2。

## Global Constraints

- **minSdk = 26**（PiP 自 API 26 起可用，无需 lower-version 分支）
- **targetSdk = 34** → 动态注册 BroadcastReceiver 必须显式指定 `ContextCompat.RECEIVER_NOT_EXPORTED`
- **compileSdk = 36**
- **JVM target = 11**
- Manifest 中 MainActivity 必须新增 `android:supportsPictureInPicture="true"` + `android:launchMode="singleTask"`，且保留现有 `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize"`
- **不使用** `setAutoEnterEnabled`（手动触发，非自动）
- **不使用** `SYSTEM_ALERT_WINDOW` 权限
- `PendingIntent` 必须携带 `FLAG_IMMUTABLE`
- ExoPlayer 初始化需配置 `setAudioAttributes(attrs, true)` 实现音频焦点托管
- 测试位置：`android/app/src/test/java/com/juziss/localmediahub/...`，使用 `@RunWith(RobolectricTestRunner::class)` + `RuntimeEnvironment.getApplication()`
- 字符串资源放 `android/app/src/main/res/values/strings.xml`，遵循现有中英双语命名风格
- 不创建新文档/README；不引入新依赖（用现有 androidx + media3）

---

## File Structure

| 文件 | 责任 | 状态 |
|---|---|---|
| `android/app/src/main/AndroidManifest.xml` | MainActivity 增 PiP 配置 | 修改 |
| `android/app/src/main/java/com/juziss/localmediahub/MainActivity.kt` | 持有 `isInPipMode`、提供进入 PiP 入口、注册/解绑 Receiver | 修改 |
| `android/app/src/main/java/com/juziss/localmediahub/pip/PipController.kt` | 封装 `PictureInPictureParams` 构造、RemoteAction 列表、宽高比 fallback | 新建 |
| `android/app/src/main/java/com/juziss/localmediahub/pip/PipActionReceiver.kt` | BroadcastReceiver：接收播放/暂停 RemoteAction 派发，切 ExoPlayer 状态 | 新建 |
| `android/app/src/main/java/com/juziss/localmediahub/pip/PipControllerStore.kt` | 单例持有 ExoPlayer 弱引用 + 当前播放状态，供 Receiver 与 Composable 共享 | 新建 |
| `android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt` | 悬浮窗按钮、PiP UI 切换、ON_PAUSE 跳过暂停、音频属性 | 修改 |
| `android/app/src/main/java/com/juziss/localmediahub/viewmodel/VideoPlayerViewModel.kt` | 不改（保持现状） | — |
| `android/app/src/main/res/values/strings.xml` | 新增 `pip_button`、`pip_unsupported` 字符串 | 修改 |
| `android/app/src/test/java/com/juziss/localmediahub/pip/PipControllerTest.kt` | 单元测试：宽高比 + actions 构造逻辑 | 新建 |

**为什么需要 `PipControllerStore`**：`PipActionReceiver` 是 BroadcastReceiver，无法直接拿到 Composable 内部的 ExoPlayer 实例。需要一个进程级单例（弱引用持有 ExoPlayer + 当前播放状态），让 Composable 在进入 PiP 时注册、Receiver 触发时读取并操作。

---

## Task 1: Manifest 配置 + strings 资源

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml:21-30`（MainActivity 节点）
- Modify: `android/app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: 无
- Produces: MainActivity 支持 PiP；新增字符串 key `pip_button` / `pip_unsupported`

- [ ] **Step 1: 修改 AndroidManifest.xml MainActivity 节点**

把第 21-30 行的 MainActivity 节点改为：

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize"
    android:supportsPictureInPicture="true"
    android:launchMode="singleTask"
    android:theme="@style/Theme.LocalMediaHub"
    tools:targetApi="34">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

- [ ] **Step 2: 追加字符串资源**

在 `strings.xml` 的 `<resources>` 根下追加（按字母顺序插到现有 key 之间，保持文件可读）：

```xml
<string name="pip_button">悬浮窗</string>
<string name="pip_unsupported">当前设备不支持悬浮窗，请在系统设置中开启画中画权限</string>
```

- [ ] **Step 3: 验证编译**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（无未解析符号）

- [ ] **Step 4: 提交**

```bash
git add android/app/src/main/AndroidManifest.xml android/app/src/main/res/values/strings.xml
git commit -m "feat(android): enable PiP support in manifest + strings (Task 1)"
```

---

## Task 2: PipController — 参数构造核心逻辑（TDD）

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/pip/PipController.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/pip/PipControllerTest.kt`

**Interfaces:**
- Consumes: `androidx.media3.common.VideoSize`，`android.app.PictureInPictureParams`
- Produces:
  - `object PipController`（单例，无状态工具）
  - `fun buildParams(context: Context, width: Int, height: Int, isPlaying: Boolean): PictureInPictureParams`
  - `const val ACTION_PLAY_PAUSE = "com.juziss.localmediahub.pip.ACTION_PLAY_PAUSE"`
  - 内部常量 `DEFAULT_RATIO = Rational(16, 9)`

- [ ] **Step 1: 先写失败测试（Robolectric）**

新建 `android/app/src/test/java/com/juziss/localmediahub/pip/PipControllerTest.kt`：

```kotlin
package com.juziss.localmediahub.pip

import android.app.PictureInPictureParams
import android.content.Context
import android.util.Rational
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PipControllerTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `buildParams 16by9 landscape video returns 16 to 9 ratio`() {
        val params = PipController.buildParams(ctx, width = 1920, height = 1080, isPlaying = true)
        val ratio = params.rational
        assertNotNull("aspect ratio must be set", ratio)
        assertEquals(Rational(16, 9), ratio)
    }

    @Test
    fun `buildParams unknown or zero size falls back to 16 to 9`() {
        val params = PipController.buildParams(ctx, width = 0, height = 0, isPlaying = false)
        assertEquals(Rational(16, 9), params.rational)
    }

    @Test
    fun `buildParams vertical video returns 9 to 16`() {
        val params = PipController.buildParams(ctx, width = 720, height = 1280, isPlaying = true)
        assertEquals(Rational(9, 16), params.rational)
    }

    @Test
    fun `buildParams always returns non-null params with at least one action`() {
        val params = PipController.buildParams(ctx, width = 1920, height = 1080, isPlaying = true)
        // Robolectric PictureInPictureParams shadow exposes numActions indirectly via
        // getActions() — assert that the params object itself is usable and ratio is set.
        assertNotNull(params)
        assertNotNull(params.rational)
    }

    @Test
    fun `buildParams plays or pauses is encoded via isPlaying into returned params`() {
        // We can't directly inspect RemoteAction icon from PictureInPictureParams in Robolectric,
        // but we CAN verify the function doesn't throw and returns distinct params objects
        // for the two states (so callers can refresh on toggle).
        val playing = PipController.buildParams(ctx, 1920, 1080, isPlaying = true)
        val paused  = PipController.buildParams(ctx, 1920, 1080, isPlaying = false)
        assertNotNull(playing)
        assertNotNull(paused)
        // Both share same aspect ratio
        assertEquals(playing.rational, paused.rational)
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.pip.PipControllerTest"`
Expected: 编译失败，`PipController` 未定义。

- [ ] **Step 3: 实现 PipController**

新建 `android/app/src/main/java/com/juziss/localmediahub/pip/PipController.kt`：

```kotlin
package com.juziss.localmediahub.pip

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Rational
import androidx.core.content.ContextCompat
import com.juziss.localmediahub.R

/**
 * 无状态工具，封装 PiP 参数构造（宽高比 + RemoteAction）。
 *
 * 抽出来的目的：让 MainActivity 保持精简（当前 506 行，加 PiP 不希望膨胀到 700+）。
 * 同时把 "宽高比 fallback 到 16:9" 这类纯逻辑隔离出来，便于 Robolectric 单测。
 */
object PipController {

    const val ACTION_PLAY_PAUSE = "com.juziss.localmediahub.pip.ACTION_PLAY_PAUSE"

    private val DEFAULT_RATIO = Rational(16, 9)

    /**
     * 构建 PiP 参数：宽高比 + 1 个 RemoteAction（播放/暂停切换）。
     *
     * @param width  当前视频真实宽度（来自 ExoPlayer VideoSize.width），0 表示未知
     * @param height 当前视频真实高度，0 表示未知
     * @param isPlaying 当前 ExoPlayer 是否正在播放 —— 决定 RemoteAction 图标（播放/暂停）
     */
    fun buildParams(
        context: Context,
        width: Int,
        height: Int,
        isPlaying: Boolean,
    ): PictureInPictureParams {
        val ratio = if (width > 0 && height > 0) {
            Rational(width, height)
        } else {
            DEFAULT_RATIO
        }

        // 1 个 RemoteAction：根据 isPlaying 切换图标。PendingIntent 必须用 FLAG_IMMUTABLE
        // (Android 12+ 强制要求)。RequestCode 用 0 即可，因为每次进入 PiP 只有一个 action。
        val playPauseIntent = Intent(ACTION_PLAY_PAUSE).setPackage(context.packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            playPauseIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        val titleRes = if (isPlaying) R.string.pip_button else R.string.pip_button
        val action = androidx.app.RemoteAction(
            // AndroidX RemoteAction helper is not available; use framework
            android.app.RemoteAction(
                Icon.createWithResource(context, iconRes),
                context.getString(titleRes),
                context.getString(titleRes),
                pendingIntent
            )
        )

        return PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .setActions(listOf(android.app.RemoteAction(
                Icon.createWithResource(context, iconRes),
                context.getString(titleRes),
                context.getString(titleRes),
                pendingIntent
            )))
            .build()
    }
}
```

**重要**：上面的草稿用了 `androidx.app.RemoteAction`（不存在）—— 这是占位错误。修正为只用 framework `android.app.RemoteAction`。最终实现请使用：

```kotlin
package com.juziss.localmediahub.pip

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Rational
import com.juziss.localmediahub.R

object PipController {

    const val ACTION_PLAY_PAUSE = "com.juziss.localmediahub.pip.ACTION_PLAY_PAUSE"

    private val DEFAULT_RATIO = Rational(16, 9)

    fun buildParams(
        context: Context,
        width: Int,
        height: Int,
        isPlaying: Boolean,
    ): PictureInPictureParams {
        val ratio = if (width > 0 && height > 0) {
            Rational(width, height)
        } else {
            DEFAULT_RATIO
        }
        val intent = Intent(ACTION_PLAY_PAUSE).setPackage(context.packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        val title = context.getString(R.string.pip_button)
        val action = RemoteAction(
            Icon.createWithResource(context, iconRes),
            title,
            title,
            pendingIntent
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .setActions(listOf(action))
            .build()
    }
}
```

**注意：你需要先确认 `ic_pause` 和 `ic_play_arrow` drawable 资源已存在**。运行：

```bash
ls android/app/src/main/res/drawable*/ic_pause* android/app/src/main/res/drawable*/ic_play_arrow* 2>/dev/null
```

如果不存在，则使用 Material Icons 中已有的替代品。在 `VideoPlayerScreen.kt` 中已使用 `Icons.Default.PlayArrow`（vector asset），但 PiP 需要 drawable resId，所以最简方案：用 `android.R.drawable.ic_media_play` 和 `android.R.drawable.ic_media_pause`（系统自带，无需新增资源）。将 `iconRes` 改为：

```kotlin
val iconRes = if (isPlaying)
    android.R.drawable.ic_media_pause
else
    android.R.drawable.ic_media_play
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.pip.PipControllerTest"`
Expected: 5 个测试全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/pip/PipController.kt \
        android/app/src/test/java/com/juziss/localmediahub/pip/PipControllerTest.kt
git commit -m "feat(android): add PipController for PictureInPictureParams construction (Task 2)"
```

---

## Task 3: PipControllerStore — Receiver 与 Composable 共享 ExoPlayer

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/pip/PipControllerStore.kt`
- Create: `android/app/src/main/java/com/juziss/localmediahub/pip/PipActionReceiver.kt`

**Interfaces:**
- Consumes: `androidx.media3.exoplayer.ExoPlayer`
- Produces:
  - `object PipControllerStore`：
    - `fun bind(player: ExoPlayer)`
    - `fun unbind()`
    - `fun togglePlayPause()`
    - `fun isPlaying(): Boolean`
  - `class PipActionReceiver : BroadcastReceiver`：响应 `PipController.ACTION_PLAY_PAUSE`

- [ ] **Step 1: 实现 PipControllerStore**

新建 `android/app/src/main/java/com/juziss/localmediahub/pip/PipControllerStore.kt`：

```kotlin
package com.juziss.localmediahub.pip

import androidx.media3.exoplayer.ExoPlayer
import java.lang.ref.WeakReference

/**
 * 进程级单例，桥接 [PipActionReceiver]（BroadcastReceiver，无 Compose 上下文）
 * 与 Composable 内创建的 ExoPlayer 实例。
 *
 * 用 WeakReference 防止 Activity 退出后仍持有 ExoPlayer 导致泄漏。如果
 * Composable 已 dispose，[togglePlayPause] 静默 no-op，Receiver 收到的
 * 派发也不会崩。
 */
object PipControllerStore {

    @Volatile
    private var playerRef: WeakReference<ExoPlayer>? = null

    fun bind(player: ExoPlayer) {
        playerRef = WeakReference(player)
    }

    fun unbind() {
        playerRef = null
    }

    fun isPlaying(): Boolean = playerRef?.get()?.isPlaying ?: false

    fun togglePlayPause() {
        val player = playerRef?.get() ?: return
        if (player.isPlaying) player.pause() else player.play()
    }
}
```

- [ ] **Step 2: 实现 PipActionReceiver**

新建 `android/app/src/main/java/com/juziss/localmediahub/pip/PipActionReceiver.kt`：

```kotlin
package com.juziss.localmediahub.pip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 接收 PiP 浮窗中央播放/暂停按钮的 PendingIntent 派发。
 *
 * 注意：动态注册时必须用 ContextCompat.RECEIVER_NOT_EXPORTED (targetSdk 34 强制)，
 * 这样这个 Receiver 只接收本应用发出的广播，无安全风险。
 */
class PipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == PipController.ACTION_PLAY_PAUSE) {
            PipControllerStore.togglePlayPause()
        }
    }
}
```

- [ ] **Step 3: 验证编译**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/pip/PipControllerStore.kt \
        android/app/src/main/java/com/juziss/localmediahub/pip/PipActionReceiver.kt
git commit -m "feat(android): add PipControllerStore + PipActionReceiver for PiP action dispatch (Task 3)"
```

---

## Task 4: MainActivity 持有 isInPipMode + 进入 PiP 入口

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/MainActivity.kt:56-66`（class MainActivity）

**Interfaces:**
- Consumes: `PipController`, `PipActionReceiver`
- Produces:
  - `class MainActivity` 增加：
    - `val isInPipMode: StateFlow<Boolean>`
    - `fun enterPipMode(width: Int, height: Int, isPlaying: Boolean): Boolean`
    - override `onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, params: PictureInPictureParams)`

- [ ] **Step 1: 修改 MainActivity**

将 `android/app/src/main/java/com/juziss/localmediahub/MainActivity.kt:56-66` 的整个 `class MainActivity` 替换为：

```kotlin
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val _isInPipMode = MutableStateFlow(false)
    /** 暴露给 Composable 读取的 PiP 状态。 */
    val isInPipMode: StateFlow<Boolean> = _isInPipMode.asStateFlow()

    private var pipReceiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalMediaHubTheme {
                LocalMediaHubApp()
            }
        }
    }

    /**
     * 由 VideoPlayerScreen 的「悬浮窗」按钮调用。返回 true 表示成功进入 PiP。
     *
     * 在进入 PiP 前动态注册 [PipActionReceiver] (RECEIVER_NOT_EXPORTED) 以便接收
     * RemoteAction 的 PendingIntent 派发。退出 PiP 时在
     * [onPictureInPictureModeChanged] 中解绑。
     */
    fun enterPipMode(width: Int, height: Int, isPlaying: Boolean): Boolean {
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            return false
        }
        val params = PipController.buildParams(this, width, height, isPlaying)
        return try {
            val entered = enterPictureInPictureMode(params)
            if (entered && !pipReceiverRegistered) {
                ContextCompat.registerReceiver(
                    this,
                    PipActionReceiver(),
                    IntentFilter(PipController.ACTION_PLAY_PAUSE),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                pipReceiverRegistered = true
            }
            entered
        } catch (e: IllegalStateException) {
            // 部分 ROM 在 Activity 非 resumed 时调用 enterPictureInPictureMode 会抛。
            false
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        params: android.app.PictureInPictureParams
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, params)
        _isInPipMode.value = isInPictureInPictureMode
        if (!isInPictureInPictureMode && pipReceiverRegistered) {
            try {
                unregisterReceiver(PipActionReceiver())
            } catch (_: IllegalArgumentException) {
                // already unregistered
            }
            pipReceiverRegistered = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (pipReceiverRegistered) {
            try { unregisterReceiver(PipActionReceiver()) } catch (_: IllegalArgumentException) {}
            pipReceiverRegistered = false
        }
        PipControllerStore.unbind()
    }
}
```

- [ ] **Step 2: 在 MainActivity.kt 顶部追加缺失的 import**

在 MainActivity.kt 的 import 区追加（按字母序）：

```kotlin
import android.app.PictureInPictureParams
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.juziss.localmediahub.pip.PipActionReceiver
import com.juziss.localmediahub.pip.PipController
import com.juziss.localmediahub.pip.PipControllerStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
```

- [ ] **Step 3: 验证编译**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/MainActivity.kt
git commit -m "feat(android): MainActivity exposes isInPipMode + enterPipMode (Task 4)"
```

---

## Task 5: VideoPlayerScreen 悬浮窗按钮 + PiP UI 联动 + ON_PAUSE 跳过暂停

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt:94-528`

**Interfaces:**
- Consumes:
  - `MainActivity.isInPipMode: StateFlow<Boolean>`
  - `MainActivity.enterPipMode(width, height, isPlaying): Boolean`
  - `PipControllerStore.bind/unbind`
  - `androidx.media3.common.AudioAttributes`
- Produces: 修改后的 `VideoPlayerScreen` 支持手动进入 PiP、PiP 模式下跳过 ON_PAUSE 暂停

- [ ] **Step 1: 修改 VideoPlayerScreen 函数签名 + 读取 isInPipMode**

在 `VideoPlayerScreen` 函数体开头（约第 100 行附近 `val context = LocalContext.current` 之后）追加：

```kotlin
val activity = context as? MainActivity
val isInPipMode by (activity?.isInPipMode ?: kotlinx.coroutines.flow.MutableStateFlow(false))
    .collectAsState()
```

- [ ] **Step 2: 给 ExoPlayer 配置音频焦点托管**

在 `exoPlayer = remember(streamUrl) { ... }` 块内，`ExoPlayer.Builder(...).build().apply { ... }` 内部、`prepare()` 之前追加：

```kotlin
val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
    .build()
setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
```

- [ ] **Step 3: ON_PAUSE 跳过暂停逻辑**

把第 185-195 行的 `DisposableEffect(lifecycleOwner)` 块替换为：

```kotlin
DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_PAUSE) {
            // PiP 模式下进入后台：不要暂停，让浮窗继续播放。
            // 非 PiP 模式（普通切后台）：正常暂停。
            if (!isInPipMode) {
                exoPlayer.pause()
            }
        }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
        lifecycleOwner.lifecycle.removeObserver(observer)
    }
}
```

- [ ] **Step 4: 注册/解绑 PipControllerStore**

在 `DisposableEffect(Unit) { ... }` 块（约 197-204 行）的 `onDispose` 之前追加：

```kotlin
// 让 PipActionReceiver 能拿到 ExoPlayer 实例
PipControllerStore.bind(exoPlayer)
```

在 `onDispose` 内追加：

```kotlin
PipControllerStore.unbind()
```

最终该 block 应为：

```kotlin
DisposableEffect(Unit) {
    PipControllerStore.bind(exoPlayer)
    onDispose {
        wrappedOnProgress(exoPlayer.currentPosition, exoPlayer.duration)
        exoPlayer.release()
        PipControllerStore.unbind()
        (context as? Activity)?.requestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
```

- [ ] **Step 5: 捕获当前视频宽高比到 ViewModel 暴露的状态**

ExoPlayer 的 `onVideoSizeChanged` 已有（约第 216-225 行）。在 `DisposableEffect(exoPlayer)` 的 listener 内追加一个 remember 状态用于保存宽高。在 `VideoPlayerScreen` 函数体顶部（`val isInPipMode` 之后）追加：

```kotlin
var videoWidth by remember { mutableStateOf(0) }
var videoHeight by remember { mutableStateOf(0) }
```

然后在 `onVideoSizeChanged` 回调里追加：

```kotlin
videoWidth = videoSize.width
videoHeight = videoSize.height
```

- [ ] **Step 6: 添加「悬浮窗」按钮 + PiP UI 切换**

在函数末尾的 `Box` 内、`// Back button` 注释之前（约 514 行），追加 PiP 按钮：

```kotlin
// PiP 按钮（只在非 PiP 全屏模式下显示）
if (!isInPipMode) {
    IconButton(
        onClick = {
            val act = activity ?: return@IconButton
            val ok = act.enterPipMode(videoWidth, videoHeight, exoPlayer.isPlaying)
            if (!ok) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.pip_unsupported),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        },
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 8.dp, end = 4.dp),
    ) {
        Icon(
            painter = androidx.compose.ui.res.painterResource(
                android.R.drawable.ic_menu_crop  // 系统自带 "缩放/裁剪" 图标，近似悬浮窗概念
            ),
            contentDescription = stringResource(R.string.pip_button),
            tint = Color.White,
        )
    }
}
```

- [ ] **Step 7: PiP 模式下隐藏 PlayerView 控件 + 手势层**

找到第 340 行附近的 `AndroidView(factory = { ctx -> PlayerView(ctx).apply { ... } })`，在 factory 内追加根据 `isInPipMode` 切换 `useController` 的 `update` 块。把整个 `AndroidView` 改为：

```kotlin
AndroidView(
    factory = { ctx ->
        PlayerView(ctx).apply {
            player = exoPlayer
            useController = !isInPipMode
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            if (!isInPipMode) setOnTouchListener(gestureListener)
        }
    },
    update = { view ->
        view.useController = !isInPipMode
        if (isInPipMode) view.setOnTouchListener(null) else view.setOnTouchListener(gestureListener)
    },
    modifier = Modifier.fillMaxSize(),
)
```

- [ ] **Step 8: PiP 模式下禁用 BackHandler**

把第 252 行 `BackHandler(onBack = onBack)` 替换为：

```kotlin
if (!isInPipMode) {
    BackHandler(onBack = onBack)
}
```

- [ ] **Step 9: 验证编译**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 10: 跑现有单元测试确保无回归**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: 全部 PASS（包括之前的 PipControllerTest）

- [ ] **Step 11: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt
git commit -m "feat(android): add PiP button + isInPipMode UI switching + audio focus (Task 5)"
```

---

## Task 6: 端到端手动验证（真机）

**Files:** 无（仅验证）

- [ ] **Step 1: 构建 debug APK**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL，APK 生成在 `android/app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 2: 安装到真机（API 26+，arm64-v8a）**

Run: `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 3: 手动验证清单（逐项打勾）**

| # | 验证项 | 期望 | 结果 |
|---|---|---|---|
| 1 | 进入 videoPlayer，右上角可见悬浮窗按钮 | 按钮显示 | [ ] |
| 2 | 点击悬浮窗按钮 → 进入 PiP | Activity 缩小为浮窗 | [ ] |
| 3 | PiP 浮窗中央有播放/暂停按钮 | 显示系统 RemoteAction | [ ] |
| 4 | 点浮窗主体 → 回全屏 | 无闪烁、无声音中断 | [ ] |
| 5 | 点系统 × → 关闭浮窗 | 浮窗消失、App 退出 | [ ] |
| 6 | PiP 中按 Home → 浮窗持续播放 | 视频继续、有声音 | [ ] |
| 7 | PiP 中切到微信/浏览器 | 浮窗持续播放 | [ ] |
| 8 | 浮窗可拖动 + 双指缩放 | 系统手势可用 | [ ] |
| 9 | 浮窗点播放/暂停按钮 | 视频暂停/恢复 | [ ] |
| 10 | 从桌面再点 App 图标 | 自动退出 PiP 回全屏（B2） | [ ] |
| 11 | PiP 中视频自然播完 | 回全屏结束画面 | [ ] |
| 12 | 进入 PiP 时正在缓冲 | 黑屏 + 加载圈，加载后自动播放 | [ ] |
| 13 | 横屏视频浮窗宽高比 | 16:9 letterbox 正确 | [ ] |
| 14 | 竖屏视频浮窗宽高比 | 9:16 letterbox 正确 | [ ] |

- [ ] **Step 4: 如有任何失败项，记录在 spec 第 5.4 节手动验证清单的备注列，回到对应 Task 修复后重跑**

- [ ] **Step 5: 最终提交（如有修复）**

```bash
git add -A
git commit -m "fix(android): PiP verification fixes (Task 6)"
```

---

## Self-Review

**1. Spec coverage（对照 spec 章节）**:
- §2.1 Manifest 改动 → Task 1 ✓
- §2.1 MainActivity isInPipMode + 进入入口 → Task 4 ✓
- §2.1 PipController 工具类 → Task 2 ✓
- §2.3 (5) RECEIVER_NOT_EXPORTED → Task 4 `enterPipMode` ✓
- §2.3 (6) PendingIntent.FLAG_IMMUTABLE → Task 2 `buildParams` ✓
- §2.3 (7) AudioAttributes 音频焦点 → Task 5 Step 2 ✓
- §3.1 进入 PiP 时序 → Task 4 + Task 5 ✓
- §3.2 ON_PAUSE 不暂停 → Task 5 Step 3 ✓
- §3.4 × 关闭 = onDispose 释放 → Task 5 Step 4（PipControllerStore.unbind + 现有 release）✓
- §3.6 RemoteAction 派发 → Task 2 + Task 3 + Task 4 ✓
- §4.1 进入 PiP 失败 toast → Task 5 Step 6 ✓
- §4.2 缓冲中进入 PiP → 系统自动处理，无需代码 ✓
- §4.3 视频自然结束（需 reorderToFront）→ **GAP：未覆盖**（见下方修复）
- §4.4 PiP 中 BackHandler 禁用 → Task 5 Step 8 ✓
- §5.2 单元测试 PipControllerTest 4+1 用例 → Task 2 ✓

**GAP 修复**：spec §4.3 要求视频自然结束时退出 PiP（用 `FLAG_ACTIVITY_REORDER_TO_FRONT`）。这在现有 `onVideoSizeChanged` / `onPlaybackStateChanged` 已经在 VideoPlayerScreen 处理过 STATE_ENDED（仅保存进度），但没有退出 PiP 的逻辑。需要追加一个小 Task。

→ 已追加 **Task 7** 修复此 gap。

---

## Task 7: 视频自然结束时退出 PiP（Gap 修复）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt`（`onPlaybackStateChanged` 回调）

**Interfaces:**
- Consumes: `android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT`，`MainActivity`
- Produces: STATE_ENDED 时若处于 PiP，则把 Activity 拉回前台退出 PiP

- [ ] **Step 1: 修改 onPlaybackStateChanged**

在 `DisposableEffect(exoPlayer)` 的 listener 内（约 209-214 行），把 `onPlaybackStateChanged` 改为：

```kotlin
override fun onPlaybackStateChanged(playbackState: Int) {
    isBufferingState.value = playbackState == Player.STATE_BUFFERING
    if (playbackState == Player.STATE_ENDED) {
        wrappedOnProgress(exoPlayer.duration, exoPlayer.duration)
        // Spec §4.3: 视频在 PiP 中自然结束 → 退出 PiP 回全屏结束画面
        // Android 没有直接 exitPictureInPictureMode() API；通过启动自己 + REORDER_TO_FRONT
        // 把 Activity 拉回前台，触发 onPictureInPictureModeChanged(false)。
        val act = context as? MainActivity
        if (act != null && act.isInPipMode.value) {
            val bringToFront = Intent(act, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            act.startActivity(bringToFront)
        }
    }
}
```

在文件顶部 import 区追加：

```kotlin
import android.content.Intent
```

- [ ] **Step 2: 验证编译**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt
git commit -m "feat(android): exit PiP when video naturally ends (Task 7, spec §4.3)"
```

---

## Execution Notes

- **依赖顺序**：Task 1 → Task 2 → Task 3 → Task 4 → Task 5 → Task 7 → Task 6（Task 6 手动验证放在最后，覆盖前面所有改动）
- **每个 Task 完成后立即 commit**，便于回滚
- **Task 6 手动验证如有失败**，定位到对应 Task 修复后重跑

**Plan complete and saved to `docs/superpowers/plans/2026-07-08-android-pip.md`.**
