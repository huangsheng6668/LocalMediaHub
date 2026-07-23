# Android Video Player Gesture HUD & PiP Integration Design Spec

**Date**: 2026-07-23  
**Status**: Approved  
**Target**: `android/app/src/main/java/com/juziss/localmediahub/`

---

## 1. Overview & Goals

Enhance the Android Compose Video Player (`VideoPlayerScreen.kt`, `VideoPlayerActivity.kt`) with modern gesture HUD feedback, double-tap seek ripples, and seamless Picture-in-Picture (PiP) system remote actions.

### Key Goals:
1. **Modern Gesture HUD**: Replace plain text overlays with glassmorphic pill-shaped indicators for volume and brightness, equipped with smooth animations and an auto-dismiss timer.
2. **Double-Tap Seek Animations**: Implement left/right double-tap gesture detectors with ripple animations and `±10s` (or cumulative `±20s`, `±30s`) visual overlays.
3. **Seamless PiP System Controls**: Add Android system PiP remote actions (`Play/Pause`, `Rewind 10s`, `Forward 10s`) via BroadcastReceiver, and clean up UI visibility transitions on PiP state changes.

---

## 2. Architecture & Component Boundaries

### 2.1 File Map

- **[NEW]** `android/app/src/main/java/com/juziss/localmediahub/ui/component/PlayerGestureHud.kt`
  - `VolumeBrightnessPillHud`: Glassmorphic pill indicator showing icon + percentage bar with auto-dismiss.
  - `DoubleTapSeekRippleOverlay`: Animated ripple ring and text indicator (`+10s` / `-10s`) for double-tap seeking.
- **[MODIFY]** `android/app/src/main/java/com/juziss/localmediahub/ui/component/PlayerGestureDetector.kt`
  - Enhance tap and drag detection to report double-tap seek side (Left vs Right) and cumulative tap count.
- **[MODIFY]** `android/app/src/main/java/com/juziss/localmediahub/pip/PipController.kt`
  - Add Android 8.0+ `RemoteAction` action builders (Play/Pause, Replay 10, Forward 10).
  - Manage PiP broadcast intent filters and callbacks.
- **[MODIFY]** `android/app/src/main/java/com/juziss/localmediahub/VideoPlayerActivity.kt`
  - Register/unregister BroadcastReceiver for PiP remote action intents.
  - Update `PictureInPictureParams` dynamically when player state changes (playing vs paused).
- **[MODIFY]** `android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt`
  - Integrate new `PlayerGestureHud` and double-tap overlay.
  - Bind ExoPlayer seek/pause handlers to PiP and gesture events.

---

## 3. Detailed Specifications

### 3.1 Gesture HUD (`PlayerGestureHud.kt`)

1. **Volume & Brightness Pill (`VolumeBrightnessPillHud`)**:
   - Location: Top-center of the player screen.
   - Design: Dark semi-transparent background with rounded corners (`CircleShape` / `RoundedCornerShape(24.dp)`), blurred backdrop feel.
   - Elements: Icon (`VolumeUp`, `VolumeMute`, `BrightnessHigh`, etc.), progress bar (horizontal bar showing 0–100%), percentage text.
   - Behavior: Appears immediately on drag; hides 1.5 seconds after drag completes via `LaunchedEffect` timer.

2. **Double-Tap Seek Overlay (`DoubleTapSeekRippleOverlay`)**:
   - Location: Left 35% (rewind) or Right 35% (forward) screen area.
   - Behavior:
     - Detects rapid taps (within 300ms).
     - Increments seek amount by 10s per tap (e.g. 1st double tap = 10s, 3rd tap = 20s, 4th tap = 30s).
     - Renders expanding circle ripple with fading scale and alpha animation.
     - Performs ExoPlayer `seekTo(position ± seekAmount)` on tap completion.

### 3.2 PiP Remote Actions & State Sync

1. **Remote Actions Integration (`PipController.kt` / `VideoPlayerActivity.kt`)**:
   - Action IDs:
     - `com.juziss.localmediahub.ACTION_PIP_PLAY_PAUSE`
     - `com.juziss.localmediahub.ACTION_PIP_REWIND`
     - `com.juziss.localmediahub.ACTION_PIP_FORWARD`
   - Actions in PiP Window:
     - **Replay 10s**: Icon `ic_replay_10` / `android.R.drawable.ic_media_rew`
     - **Play / Pause**: Toggle icon based on `exoPlayer.isPlaying`
     - **Forward 10s**: Icon `ic_forward_10` / `android.R.drawable.ic_media_ff`
   - Dynamic Update: Call `setPictureInPictureParams` whenever ExoPlayer playback state changes while in background/PiP.

2. **UI Cleanup in PiP Mode**:
   - When `isInPipMode` is `true`, all controls, overlays, and gesture detectors are disabled/hidden.
   - When `isInPipMode` becomes `false` (user returns to full screen), normal controls and gesture HUD are restored cleanly.

---

## 4. Verification & Test Plan

### 4.1 Automated Tests
- Command: `cd android && ./gradlew testDebugUnitTest`
- Scope: Run unit tests for ViewModel state updates and helper calculations.

### 4.2 Build Verification
- Command: `cd android && ./gradlew assembleDebug`
- Scope: Verify Kotlin compilation, Compose layout stability, and resource bundling.

### 4.3 Manual Verification
- Launch video player on device/emulator.
- Test volume/brightness vertical drag on left/right screen halves and confirm HUD auto-dismiss.
- Test double-tap on left/right edges to verify seek ripple animation and position jump.
- Press Home button to enter PiP mode, test PiP action buttons (play, pause, fast forward, rewind), and restore to full screen.
