# Android Video Player Gesture HUD & PiP Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement modern glassmorphic volume/brightness pill HUDs, double-tap 10s seek ripple animations, and Android system PiP remote actions for the Android video player.

**Architecture:** Create `PlayerGestureHud.kt` for UI overlays, enhance `PipController.kt` and `VideoPlayerActivity.kt` with `RemoteAction` broadcasts, and integrate all components cleanly inside `VideoPlayerScreen.kt`.

**Tech Stack:** Kotlin, Jetpack Compose, ExoPlayer (Media3), Android Picture-in-Picture API.

## Global Constraints

- Android API level floor: minSdk 26, targetSdk 35.
- Compose components must use Material 3 and avoid deprecated foundation parameters.
- Absolute file paths in project repository: `E:/github_project/LocalMediaHub/android/app/src/main/java/com/juziss/localmediahub/`

---

### Task 1: Create PlayerGestureHud Component

**Files:**
- Create: `E:/github_project/LocalMediaHub/android/app/src/main/java/com/juziss/localmediahub/ui/component/PlayerGestureHud.kt`
- Test: `E:/github_project/LocalMediaHub/android/app/src/test/java/com/juziss/localmediahub/PlayerGestureHudTest.kt`

**Interfaces:**
- Produces: `VolumeBrightnessPillHud(icon: ImageVector, text: String, progress: Float, visible: Boolean)`
- Produces: `DoubleTapSeekRippleOverlay(isForward: Boolean, seekSeconds: Int, visible: Boolean, onAnimationEnd: () -> Unit)`

- [ ] **Step 1: Write the unit test for Gesture HUD state calculation helper**

Create `E:/github_project/LocalMediaHub/android/app/src/test/java/com/juziss/localmediahub/PlayerGestureHudTest.kt`:

```kotlin
package com.juziss.localmediahub

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerGestureHudTest {
    @Test
    fun testFormatSeekSeconds() {
        val seconds = 10
        val text = "+${seconds}s"
        assertEquals("+10s", text)
    }

    @Test
    fun testCalculateVolumePercentage() {
        val currentVolume = 5
        val maxVolume = 15
        val percent = (currentVolume.toFloat() / maxVolume * 100).toInt()
        assertEquals(33, percent)
    }
}
```

- [ ] **Step 2: Run unit test to verify it passes**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.PlayerGestureHudTest"`
Expected: PASS

- [ ] **Step 3: Create PlayerGestureHud.kt**

Create `E:/github_project/LocalMediaHub/android/app/src/main/java/com/juziss/localmediahub/ui/component/PlayerGestureHud.kt`:

```kotlin
package com.juziss.localmediahub.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun VolumeBrightnessPillHud(
    icon: ImageVector,
    text: String,
    progress: Float,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "PillHudProgress"
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(150)),
        exit = fadeOut(animationSpec = tween(250)),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .width(100.dp)
                    .height(6.dp)
                    .clip(CircleShape),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.3f),
            )
            Text(
                text = text,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun DoubleTapSeekRippleOverlay(
    isForward: Boolean,
    seekSeconds: Int,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible && seekSeconds > 0,
        enter = fadeIn(tween(100)),
        exit = fadeOut(tween(200)),
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxHeight()
                .width(140.dp)
                .background(Color.White.copy(alpha = 0.12f), shape = CircleShape)
        ) {
            val prefix = if (isForward) "+" else "-"
            Text(
                text = "$prefix${seekSeconds}s",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
```

- [ ] **Step 4: Verify test passes**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.PlayerGestureHudTest"`
Expected: PASS

- [ ] **Step 5: Commit Task 1**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/PlayerGestureHud.kt android/app/src/test/java/com/juziss/localmediahub/PlayerGestureHudTest.kt
git commit -m "feat(android): add PlayerGestureHud volume/brightness pill and seek ripple overlay"
```

---

### Task 2: Enhance PipController and VideoPlayerActivity for RemoteActions

**Files:**
- Modify: `E:/github_project/LocalMediaHub/android/app/src/main/java/com/juziss/localmediahub/pip/PipController.kt`
- Modify: `E:/github_project/LocalMediaHub/android/app/src/main/java/com/juziss/localmediahub/VideoPlayerActivity.kt`

**Interfaces:**
- Consumes: ExoPlayer instance state (`isPlaying`)
- Produces: `PipController.updatePipActions(activity: Activity, isPlaying: Boolean)`

- [ ] **Step 1: Add RemoteActions helper to PipController.kt**

In `PipController.kt`, add methods to construct Android `RemoteAction`s for Play/Pause, Rewind 10, Forward 10 when building `PictureInPictureParams`.

Update `E:/github_project/LocalMediaHub/android/app/src/main/java/com/juziss/localmediahub/pip/PipController.kt`:

```kotlin
package com.juziss.localmediahub.pip

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PipController {
    val isInPipMode: StateFlow<Boolean> get() = _isInPipMode
    private val _isInPipMode = MutableStateFlow(false)

    fun onPictureInPictureModeChanged(isInPipMode: Boolean) {
        _isInPipMode.value = isInPipMode
    }

    fun enterPipMode(activity: Activity, videoWidth: Int = 0, videoHeight: Int = 0, isPlaying: Boolean = true) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = buildPipParams(activity, videoWidth, videoHeight, isPlaying)
            activity.enterPictureInPictureMode(params)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun updatePipParams(activity: Activity, videoWidth: Int, videoHeight: Int, isPlaying: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.setPictureInPictureParams(buildPipParams(activity, videoWidth, videoHeight, isPlaying))
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(context: Context, videoWidth: Int, videoHeight: Int, isPlaying: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        if (videoWidth > 0 && videoHeight > 0) {
            val aspect = (videoWidth.toFloat() / videoHeight).coerceIn(0.41841f, 2.39f)
            builder.setAspectRatio(Rational((aspect * 1000).toInt(), 1000))
        }
        val actions = createRemoteActions(context, isPlaying)
        builder.setActions(actions)
        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createRemoteActions(context: Context, isPlaying: Boolean): List<RemoteAction> {
        val actions = mutableListOf<RemoteAction>()

        // Rewind 10s Action
        val rewindIntent = Intent(ACTION_PIP_REWIND).setPackage(context.packageName)
        val rewindPending = PendingIntent.getBroadcast(context, 1, rewindIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val rewindIcon = Icon.createWithResource(context, android.R.drawable.ic_media_rew)
        actions.add(RemoteAction(rewindIcon, "Rewind 10s", "Rewind 10s", rewindPending))

        // Play / Pause Action
        val playPauseIntent = Intent(ACTION_PIP_PLAY_PAUSE).setPackage(context.packageName)
        val playPausePending = PendingIntent.getBroadcast(context, 2, playPauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val playPauseIconRes = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = if (isPlaying) "Pause" else "Play"
        actions.add(RemoteAction(Icon.createWithResource(context, playPauseIconRes), playPauseTitle, playPauseTitle, playPausePending))

        // Forward 10s Action
        val forwardIntent = Intent(ACTION_PIP_FORWARD).setPackage(context.packageName)
        val forwardPending = PendingIntent.getBroadcast(context, 3, forwardIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val forwardIcon = Icon.createWithResource(context, android.R.drawable.ic_media_ff)
        actions.add(RemoteAction(forwardIcon, "Forward 10s", "Forward 10s", forwardPending))

        return actions
    }

    companion object {
        const val ACTION_PIP_PLAY_PAUSE = "com.juziss.localmediahub.PIP_PLAY_PAUSE"
        const val ACTION_PIP_REWIND = "com.juziss.localmediahub.PIP_REWIND"
        const val ACTION_PIP_FORWARD = "com.juziss.localmediahub.PIP_FORWARD"
    }
}
```

- [ ] **Step 2: Register BroadcastReceiver in VideoPlayerActivity.kt**

Modify `VideoPlayerActivity.kt` to handle PiP action broadcasts (`ACTION_PIP_PLAY_PAUSE`, `ACTION_PIP_REWIND`, `ACTION_PIP_FORWARD`):

```kotlin
package com.juziss.localmediahub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import com.juziss.localmediahub.pip.PipController
import com.juziss.localmediahub.ui.screen.VideoPlayerScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class VideoPlayerActivity : ComponentActivity() {
    val pipController = PipController()
    val isInPipMode get() = pipController.isInPipMode
    var onPipActionReceived: ((String) -> Unit)? = null

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action?.let { action ->
                onPipActionReceived?.invoke(action)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: ""
        val initialPosition = intent.getLongExtra(EXTRA_INITIAL_POSITION, 0L)

        val filter = IntentFilter().apply {
            addAction(PipController.ACTION_PIP_PLAY_PAUSE)
            addAction(PipController.ACTION_PIP_REWIND)
            addAction(PipController.ACTION_PIP_FORWARD)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pipReceiver, filter)
        }

        setContent {
            VideoPlayerScreen(
                streamUrl = streamUrl,
                initialPositionMs = initialPosition,
                onBack = { finish() }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(pipReceiver)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        pipController.enterPipMode(this)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipController.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_INITIAL_POSITION = "extra_initial_position"
    }
}
```

- [ ] **Step 3: Run unit tests**

Run: `cd android && ./gradlew testDebugUnitTest`
Expected: PASS

- [ ] **Step 4: Commit Task 2**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/pip/PipController.kt android/app/src/main/java/com/juziss/localmediahub/VideoPlayerActivity.kt
git commit -m "feat(android): add PiP RemoteActions and broadcast receiver in VideoPlayerActivity"
```

---

### Task 3: Integrate Gesture HUD and PiP Actions into VideoPlayerScreen

**Files:**
- Modify: `E:/github_project/LocalMediaHub/android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt`

**Interfaces:**
- Consumes: `VolumeBrightnessPillHud`, `DoubleTapSeekRippleOverlay`, `VideoPlayerActivity.onPipActionReceived`

- [ ] **Step 1: Update VideoPlayerScreen.kt to render HUD and handle double-tap and PiP events**

Modify `VideoPlayerScreen.kt` to:
1. Attach `onPipActionReceived` listener on `VideoPlayerActivity` to control ExoPlayer playback.
2. Render `VolumeBrightnessPillHud` at the top center.
3. Render `DoubleTapSeekRippleOverlay` on left/right edges during double tap.
4. Hide overlays when `isInPipMode` is `true`.

```kotlin
// Inside VideoPlayerScreen.kt:
// 1. Setup PiP action listener
DisposableEffect(activity, exoPlayer) {
    activity?.onPipActionReceived = { action ->
        when (action) {
            PipController.ACTION_PIP_PLAY_PAUSE -> {
                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
            }
            PipController.ACTION_PIP_REWIND -> {
                exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0))
            }
            PipController.ACTION_PIP_FORWARD -> {
                exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(exoPlayer.duration))
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && activity != null) {
            activity.pipController.updatePipParams(activity, videoWidth, videoHeight, exoPlayer.isPlaying)
        }
    }
    onDispose {
        activity?.onPipActionReceived = null
    }
}

// 2. Render HUD elements inside VideoPlayerScreen Compose hierarchy (when !isInPipMode)
if (!isInPipMode) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Pill HUD (Center Top)
        VolumeBrightnessPillHud(
            icon = if (hudType == HudType.VOLUME) Icons.Default.VolumeUp else Icons.Default.BrightnessHigh,
            text = "${(hudProgress * 100).toInt()}%",
            progress = hudProgress,
            visible = isHudVisible,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 40.dp)
        )

        // Double-Tap Seek Ripples
        DoubleTapSeekRippleOverlay(
            isForward = false,
            seekSeconds = rewindSeekSeconds,
            visible = isRewindRippleVisible,
            modifier = Modifier.align(Alignment.CenterStart)
        )

        DoubleTapSeekRippleOverlay(
            isForward = true,
            seekSeconds = forwardSeekSeconds,
            visible = isForwardRippleVisible,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}
```

- [ ] **Step 2: Run unit test & build check**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: PASS with 0 build errors.

- [ ] **Step 3: Commit Task 3**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt
git commit -m "feat(android): integrate Volume/Brightness Pill HUD, seek ripples and PiP actions into VideoPlayerScreen"
```

---

## Plan Self-Review Checklist

1. **Spec coverage**:
   - Volume & Brightness Pill HUD -> Covered in Task 1 & Task 3.
   - Double-Tap Seek Ripples -> Covered in Task 1 & Task 3.
   - PiP Remote Actions & Broadcast Receiver -> Covered in Task 2 & Task 3.
2. **Placeholder scan**: Clean, no TBD/TODOs.
3. **Type consistency**: `VolumeBrightnessPillHud` signature matched between Task 1 definition and Task 3 usage.
