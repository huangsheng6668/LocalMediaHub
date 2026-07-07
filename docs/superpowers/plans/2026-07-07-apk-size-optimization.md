# APK Size Optimization Implementation Plan (Round 21 Batch D)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Shrink the Android release APK from 7.15 MB to ≤ 5.8 MB via R8 full mode, resource language trimming, icon-pack removal, OkHttp PSL removal, Coil upgrade, Rust panic=abort, and FFmpeg rebuild — without regressing runtime behavior.

**Architecture:** Two independent commits. C1 covers all Gradle/Kotlin/Rust-config changes (D1+D1.1+D2+D3+D4+D6). C2 covers the native FFmpeg rebuild (D5). Each commit ends with a successful release build, passing unit tests, and a measured APK size.

**Tech Stack:** Android Gradle Plugin 8.x, R8 full mode, Kotlin 1.9, Compose BOM 2024.06.00, Hilt 2.50, OkHttp 4.12→4.12 (no version bump, just config), Coil 2.5→2.6, Rust 2021 edition with cargo-ndk, FFmpeg NDK cross-compile.

## Global Constraints

- Working directory: `E:\github_project\LocalMediaHub`
- All shell commands run via bash on Windows (forward slashes, `/dev/null` not `NUL`)
- Build commands run from `android/` subdirectory unless noted
- ProGuard/R8 rules file: `android/app/proguard-rules.pro` — already covers Gson, Compose, Coil, Media3, DataStore, JNI keeps; do NOT delete or weaken existing rules
- Single ABI: `arm64-v8a` only (already configured in `app/build.gradle.kts:71-73`)
- R8/ProGuard must stay enabled: `isMinifyEnabled = true`, `isShrinkResources = true` (`app/build.gradle.kts:83-84`)
- Do NOT remove Hilt, Gson, OkHttp, or any architectural dependency
- All release builds must use the existing release signing config (debug fallback is acceptable for local testing — see `app/build.gradle.kts:36-55`)
- Rust source: `android/app/src/main/rust/` — built via `cargo-ndk` triggered by `buildRustNative` Gradle task (`app/build.gradle.kts:177-194`)
- Target Rust `.so` path: `android/app/src/main/jniLibs/arm64-v8a/liblocalmedia_native.so`
- Target FFmpeg `.so` path: `android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so`
- Spec: `docs/superpowers/specs/2026-07-07-apk-size-optimization-design.md` (commit `5710a7b` + user edits)
- Baseline APK size: **7,492,684 bytes** (7.15 MB) at `android/app/build/outputs/apk/release/app-release.apk`

## File Structure

Files modified in C1:
- `gradle.properties` — append `android.enableR8.fullMode=true`
- `android/app/build.gradle.kts` — add `resourceConfigurations`, `packaging.resources.excludes`, remove `material-icons-extended`, bump Coil to 2.6.0
- `android/app/src/main/java/com/juziss/localmediahub/network/OkHttpModule.kt` — add `CookieJar.NO_COOKIES` to builder
- `android/app/src/main/rust/Cargo.toml` — change `panic = "unwind"` → `panic = "abort"`
- 13 Kotlin files — replace non-core icon imports with `painterResource(R.drawable.*)` (see Task 3 for the per-file map)
- 15 new XML vector drawables under `android/app/src/main/res/drawable/` (one per non-core icon)

Files modified in C2:
- `scripts/build_ffmpeg.sh` — new FFmpeg cross-compile script
- `android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so` — replaced (binary)
- `docs/ffmpeg-build-config.md` — new, records the build flags for future reference

---

## Task 0: Capture baseline measurements

**Files:**
- Read-only: `android/app/build/outputs/apk/release/app-release.apk`

**Interfaces:**
- Produces: baseline APK size + per-category breakdown captured in plan execution notes (used as comparison anchor for all later tasks)

- [ ] **Step 1: Verify release APK exists and is current**

Run:
```bash
ls -la android/app/build/outputs/apk/release/app-release.apk
```
Expected: file exists, size ~7,492,684 bytes (7.15 MB). If missing or older than the last commit on `master`, run `cd android && ./gradlew assembleRelease` first.

- [ ] **Step 2: Capture baseline size breakdown**

Run:
```bash
unzip -l android/app/build/outputs/apk/release/app-release.apk | awk '
  /\.dex$/ {dex+=$1}
  /lib\/arm64-v8a\/.*\.so$/ {nat+=$1}
  /res\// {res+=$1}
  /resources\.arsc$/ {arsc+=$1}
  END {
    printf "DEX: %.2f MB\nNative: %.2f MB\nRes: %.2f MB\nresources.arsc: %.2f MB\n",
           dex/1048576, nat/1048576, res/1048576, arsc/1048576
  }'
```
Expected output (record exact numbers — they are the baseline):
```
DEX: 4.54 MB
Native: 4.51 MB
Res: 0.16 MB
resources.arsc: 0.52 MB
```

- [ ] **Step 3: Record baseline APK total size**

Run:
```bash
stat -c '%s' android/app/build/outputs/apk/release/app-release.apk
```
Expected: `7492684` (record exact value).

---

## Task 1: D1 — Enable R8 full mode

**Files:**
- Modify: `gradle.properties`

**Interfaces:**
- Produces: `android.enableR8.fullMode=true` in `gradle.properties` (R8 nociples-enabled full mode)

- [ ] **Step 1: Read current gradle.properties**

Run: read `gradle.properties` (6 lines).
Confirm last line is `android.nonTransitiveRClass=true`.

- [ ] **Step 2: Append R8 full mode flag**

In `gradle.properties`, append a new line at end:
```
android.enableR8.fullMode=true
```

The full file should now read:
```
# Project-wide Gradle settings.
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
android.enableR8.fullMode=true
```

- [ ] **Step 3: Run release build**

Run:
```bash
cd android && ./gradlew assembleRelease
```
Expected: BUILD SUCCESSFUL. R8 may emit warnings about missing classes (e.g., optional Compose/Media3 classes); these are `dontwarn`-covered and OK.

- [ ] **Step 4: If build fails with R8 errors — add targeted keeps**

If step 3 fails with "Missing class" errors, read the error message, identify the class FQN, and append a `-dontwarn <class>` or `-keep class <class> { *; }` line to the relevant section of `android/app/proguard-rules.pro`. Re-run step 3.

Common R8 full-mode gaps to expect:
- `org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement` → `-dontwarn org.codehaus.mojo.animal_sniffer.**`
- `org.intellij.lang.annotations.*` → `-dontwarn org.intellij.lang.annotations.**`
- `org.jetbrains.annotations.*` → `-dontwarn org.jetbrains.annotations.**`

If a *runtime* crash happens after this task (caught in Task 7 smoke test), add the missing keep rule and re-build — do NOT revert the full mode flag.

- [ ] **Step 5: Verify APK rebuilt and size changed**

Run:
```bash
stat -c '%s' android/app/build/outputs/apk/release/app-release.apk
```
Expected: smaller than `7492684`. Record the new size. Typical full-mode savings: 200-700 KB off DEX.

- [ ] **Step 6: Do NOT commit yet — Task 2-6 batch into C1**

This task produces a working change but the commit happens at the end of Task 6 (C1 boundary).

---

## Task 2: D1.1 — Limit resource language configurations

**Files:**
- Modify: `android/app/build.gradle.kts` — add `resourceConfigurations` inside `defaultConfig` block (currently at lines 59-74)

**Interfaces:**
- Produces: `resourceConfigurations += listOf("zh", "en")` in defaultConfig

- [ ] **Step 1: Read current defaultConfig block**

Re-read `android/app/build.gradle.kts` lines 59-74. The current `defaultConfig` looks like:
```kotlin
defaultConfig {
    applicationId = "com.juziss.localmediahub"
    minSdk = 26
    targetSdk = 34
    versionCode = 2
    versionName = "1.1"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    vectorDrawables {
        useSupportLibrary = true
    }

    ndk {
        abiFilters += "arm64-v8a"
    }
}
```

- [ ] **Step 2: Add resourceConfigurations**

Inside `defaultConfig`, immediately after the `ndk { ... }` block (before the closing `}` of `defaultConfig`), add:
```kotlin

    // Round 21 D1.1: strip translations from third-party libs down to zh + en.
    // The app ships only Chinese strings (strings.xml) and English fallback;
    // removing 70+ unused locales trims ~100-200 KB off resources.arsc.
    resourceConfigurations += listOf("zh", "en")
```

- [ ] **Step 3: Run release build**

Run:
```bash
cd android && ./gradlew assembleRelease
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Verify resources.arsc shrunk**

Run:
```bash
unzip -l android/app/build/outputs/apk/release/app-release.apk | awk '$4=="resources.arsc" {printf "resources.arsc: %.2f MB\n", $1/1048576}'
```
Expected: smaller than baseline `0.52 MB`. Target: ≤ 0.42 MB. Record new value.

---

## Task 3: D2 — Replace material-icons-extended imports

**Files:**
- Modify: `android/app/build.gradle.kts` — remove `material-icons-extended` dependency (line 211)
- Modify: 13 Kotlin source files (listed below in Step 1)
- Create: 15 XML vector drawables under `android/app/src/main/res/drawable/`

**Interfaces:**
- Produces: 15 new drawable resources named `ic_<icon_name>.xml`, referenced from Kotlin via `painterResource(R.drawable.ic_<icon_name>)`. File-to-icon map defined in Step 2.

- [ ] **Step 1: Inventory of all 26 icons currently imported**

Run:
```bash
cd "E:/github_project/LocalMediaHub" && grep -rhE "androidx\.compose\.material\.icons\.(filled|outlined)\.[A-Za-z_]+" android/app/src/main/java/ | sed -E 's/.*icons\.(filled|outlined)\.([A-Za-z_]+)/\1.\2/' | sort -u
```
Expected output (26 unique icons):
```
filled.Bookmarks
filled.Brightness6
filled.Check
filled.CheckCircle
filled.Close
filled.CloudOff
filled.Delete
filled.Error
filled.FastForward
filled.FastRewind
filled.Favorite
filled.FavoriteBorder
filled.Folder
filled.FolderOff
filled.FolderOpen
filled.History
filled.KeyboardArrowDown
filled.KeyboardArrowUp
filled.Language
filled.Movie
filled.Pause
filled.PlayArrow
filled.Refresh
filled.Search
filled.Storage
outlined.FavoriteBorder
```

- [ ] **Step 2: Classify — which are in material-icons-core (bundled with material3) vs need SVG**

Reference (verified from `material-icons-core-android/1.6.8` AAR): the **core** bundle ships exactly these filled icons: `AccountBox, AccountCircle, Add, AddCircle, ArrowBack, ArrowDropDown, ArrowForward, Build, Call, Check, CheckCircle, Clear, Close, Create, DateRange, Delete, Done, Edit, Email, ExitToApp, Face, Favorite, FavoriteBorder, Home, Info, KeyboardArrowDown, KeyboardArrowLeft, KeyboardArrowRight, KeyboardArrowUp, List, LocationOn, Lock, MailOutline, Menu, MoreVert, Notifications, Person, Phone, Place, PlayArrow, Refresh, Search, Send, Settings, Share, ShoppingCart, Star, ThumbUp, Warning`.

**Classification of the 26 used icons:**

| Used Icon | In Core? | Action |
|---|---|---|
| filled.Check | YES | keep import (auto-bundled with material3) |
| filled.CheckCircle | YES | keep |
| filled.Close | YES | keep |
| filled.Delete | YES | keep |
| filled.Favorite | YES | keep |
| filled.FavoriteBorder | YES | keep |
| filled.KeyboardArrowDown | YES | keep |
| filled.KeyboardArrowUp | YES | keep |
| filled.PlayArrow | YES | keep |
| filled.Refresh | YES | keep |
| filled.Search | YES | keep |
| filled.Bookmarks | NO | SVG → `ic_bookmarks.xml` |
| filled.Brightness6 | NO | SVG → `ic_brightness_6.xml` |
| filled.CloudOff | NO | SVG → `ic_cloud_off.xml` |
| filled.Error | NO | SVG → `ic_error.xml` |
| filled.FastForward | NO | SVG → `ic_fast_forward.xml` |
| filled.FastRewind | NO | SVG → `ic_fast_rewind.xml` |
| filled.Folder | NO | SVG → `ic_folder.xml` |
| filled.FolderOff | NO | SVG → `ic_folder_off.xml` |
| filled.FolderOpen | NO | SVG → `ic_folder_open.xml` |
| filled.History | NO | SVG → `ic_history.xml` |
| filled.Language | NO | SVG → `ic_language.xml` |
| filled.Movie | NO | SVG → `ic_movie.xml` |
| filled.Pause | NO | SVG → `ic_pause.xml` |
| filled.Storage | NO | SVG → `ic_storage.xml` |
| outlined.FavoriteBorder | NO | SVG → `ic_favorite_border_outline.xml` |

**15 SVG/XML drawables needed.** Source: Material Symbols outlined standard icons (Apache 2.0 license, same as material-icons-extended — no new license obligations).

- [ ] **Step 3: Create the 15 XML vector drawables**

For each of the 15 non-core icons, create `android/app/src/main/res/drawable/ic_<name>.xml` with the standard 24dp Material vector path. Each file follows this template (24x24 viewport, fill color set via tint so Compose can recolor):

Create `android/app/src/main/res/drawable/ic_bookmarks.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M17,20 L17,4 C17,3.45 16.55,3 16,3 L8,3 C7.45,3 7,3.45 7,4 L7,20 L12,17 L17,20 Z"/>
</vector>
```

Create `android/app/src/main/res/drawable/ic_brightness_6.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M12,2 L12,4 C12,4.55 12.45,5 13,5 C13.55,5 14,4.55 14,4 L14,2 L10,2 L10,4 C10,4.55 10.45,5 11,5 C11.55,5 12,4.55 12,4 Z M12,20 L12,22 C12,22.55 12.45,23 13,23 C13.55,23 14,22.55 14,22 L14,20 L10,20 L10,22 C10,22.55 10.45,23 11,23 C11.55,23 12,22.55 12,22 Z M2,12 L4,12 C4.55,12 5,12.45 5,13 C5,13.55 4.55,14 4,14 L2,14 L2,10 L4,10 C4.55,10 5,10.45 5,11 C5,11.55 4.55,12 4,12 Z M20,12 L22,12 L22,14 C22,14.55 21.55,15 21,15 C20.45,15 20,14.55 20,14 L20,10 C20,9.45 20.45,9 21,9 C21.55,9 22,9.45 22,10 L22,12 Z M12,8 C9.79,8 8,9.79 8,12 C8,14.21 9.79,16 12,16 C14.21,16 16,14.21 16,12 C16,9.79 14.21,8 12,8 Z"/>
</vector>
```

Create `android/app/src/main/res/drawable/ic_cloud_off.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M19.35,10.04 C18.67,6.59 15.64,4 12,4 C10.39,4 8.91,4.51 7.7,5.37 L9.13,6.8 C9.92,6.31 10.93,6 12,6 C14.65,6 16.86,8.04 17.18,10.61 C17.43,10.55 17.69,10.5 18,10.5 C20.76,10.5 23,12.74 23,15.5 C23,16.79 22.5,17.96 21.7,18.84 L23.11,20.25 L21.7,21.66 L4.34,4.34 L2.93,5.75 L2.93,5.75 C1.74,6.61 1,7.97 1,9.5 C1,12.26 3.24,14.5 6,14.5 L6,15 C6,16.1 6.9,17 8,17 L17,17 L19.27,19.27 C19.69,18.42 20,17.5 20,16.5 C20,14.74 19.09,13.19 17.74,12.26 L17.74,12.26 L19.35,10.04 Z M6,13 C4.34,13 3,11.66 3,10 C3,8.95 3.56,8.04 4.4,7.51 L9.18,12.29 C8.85,12.4 8.43,12.5 8,12.5 C7.31,12.5 6.65,12.34 6,13 Z"/>
</vector>
```

Create `android/app/src/main/res/drawable/ic_error.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M12,2 C6.48,2 2,6.48 2,12 C2,17.52 6.48,22 12,22 C17.52,22 22,17.52 22,12 C22,6.48 17.52,2 12,2 Z M13,17 L11,17 L11,15 L13,15 L13,17 Z M13,13 L11,13 L11,7 L13,7 L13,13 Z"/>
</vector>
```

Create `android/app/src/main/res/drawable/ic_fast_forward.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M4,18 L4,6 C4,5.45 4.45,5 5,5 L5.41,5 C5.7,5 5.97,5.13 6.16,5.36 L11.59,12 L6.16,18.64 C5.97,18.87 5.7,19 5.41,19 L5,19 C4.45,19 4,18.55 4,18 Z M13,18 L13,6 C13,5.45 13.45,5 14,5 L14.41,5 C14.7,5 14.97,5.13 15.16,5.36 L20.59,12 L15.16,18.64 C14.97,18.87 14.7,19 14.41,19 L14,19 C13.45,19 13,18.55 13,18 Z"/>
</vector>
```

Create `android/app/src/main/res/drawable/ic_fast_rewind.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M20,18 L20,6 C20,5.45 19.55,5 19,5 L18.59,5 C18.3,5 18.03,5.13 17.84,5.36 L12.41,12 L17.84,18.64 C18.03,18.87 18.3,19 18.59,19 L19,19 C19.55,19 20,18.55 20,18 Z M11,18 L11,6 C11,5.45 10.55,5 10,5 L9.59,5 C9.3,5 9.03,5.13 8.84,5.36 L3.41,12 L8.84,18.64 C9.03,18.87 9.3,19 9.59,19 L10,19 C10.55,19 11,18.55 11,18 Z"/>
</vector>
```

Create `android/app/src/main/res/drawable/ic_folder.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M10,4 L4,4 C2.9,4 2.01,4.9 2.01,6 L2,18 C2,19.1 2.9,20 4,20 L20,20 C21.1,20 22,19.1 22,18 L22,8 C22,6.9 21.1,6 20,6 L12,6 L10,4 Z"/>
</vector>
```

Create `android/app/src/main/res/drawable/ic_folder_off.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M1.39,1.58 L0,3 L2.01,5.01 L2,5 L2,18 C2,19.1 2.9,20 4,20 L18.99,20 L20.99,22 L22.41,20.59 L1.39,1.58 Z M4,18 L4,7 L4.05,7 L16.05,19 L4,18 Z M9.17,6 L7.17,4 L12,4 L14,6 L20,6 C21.1,6 22,6.9 22,8 L22,16.99 L20,15 L20,8 L13.2,8 L9.17,6 Z"/>
</vector>
```

Create `android/app/src/main/res/drawable/ic_folder_open.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M20,6 L12,6 L10,4 L4,4 C2.89,4 2.01,4.89 2.01,6 L2,18 C2,19.11 2.89,20 4,20 L20,20 C21.11,20 22,19.11 22,18 L22,8 C22,6.89 21.11,6 20,6 Z M4,18 L4,8 L20,8 L20,18 L4,18 Z"/>
</vector>
```

Create `android/app/src/main/res/drawable/ic_history.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M13,3 C8.03,3 4,7.03 4,12 L1,12 L5,16 L9,12 L6,12 C6,8.13 9.13,5 13,5 C16.87,5 20,8.13 20,12 C20,15.87 16.87,19 13,19 C11.07,19 9.32,18.21 8.06,16.94 L6.64,18.36 C8.27,19.99 10.51,21 13,21 C17.97,21 22,16.97 22,12 C22,7.03 17.97,3 13,3 Z M12,8 L12,13 L16.28,15.54 L17,14.33 L13.5,12.25 L13.5,8 L12,8 Z"/>
</vector>
```

Create `android/app/src/main/res/drawable/ic_language.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M11.99,2 C6.47,2 2,6.48 2,12 C2,17.52 6.47,22 11.99,22 C17.52,22 22,17.52 22,12 C22,6.48 17.52,2 11.99,2 Z M18.92,8 L15.97,8 C15.65,6.75 15.19,5.55 14.59,4.44 C16.43,5.07 17.96,6.35 18.92,8 Z M12,4.04 C12.83,5.24 13.48,6.57 13.91,8 L10.09,8 C10.52,6.57 11.17,5.24 12,4.04 Z M4.26,14 C4.1,13.36 4,12.69 4,12 C4,11.31 4.1,10.64 4.26,10 L7.64,10 C7.56,10.66 7.5,11.32 7.5,12 C7.5,12.68 7.56,13.34 7.64,14 L4.26,14 Z M5.08,16 L8.03,16 C8.35,17.25 8.81,18.45 9.41,19.56 C7.57,18.93 6.04,17.66 5.08,16 Z M8.03,8 L5.08,8 C6.04,6.34 7.57,5.07 9.41,4.44 C8.81,5.55 8.35,6.75 8.03,8 Z M12,19.96 C11.17,18.76 10.52,17.43 10.09,16 L13.91,16 C13.48,17.43 12.83,18.76 12,19.96 Z M14.34,14 L9.66,14 C9.57,13.34 9.5,12.68 9.5,12 C9.5,11.32 9.57,10.65 9.66,10 L14.34,10 C14.43,10.65 14.5,11.32 14.5,12 C14.5,12.68 14.43,13.34 14.34,14 Z M14.59,19.56 C15.19,18.45 15.65,17.25 15.97,16 L18.92,16 C17.96,17.65 16.43,18.93 14.59,19.56 Z M16.36,14 C16.44,13.34 16.5,12.68 16.5,12 C16.5,11.32 16.44,10.66 16.36,10 L19.74,10 C19.9,10.64 20,11.31 20,12 C20,12.69 19.9,13.36 19.74,14 L16.36,14 Z"/>
</vector>
```

Create `android/app/src/main/res/drawable/ic_movie.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M18,4 L20,4 C21.1,4 22,4.9 22,6 L22,18 C22,19.1 21.1,20 20,20 L4,20 C2.9,20 2,19.1 2,18 L2,6 C2,4.9 2.9,4 4,4 L6,4 L6,2 L8,2 L8,4 L16,4 L16,2 L18,2 L18,4 Z M20,18 L20,8 L4,8 L4,18 L20,18 Z M10,11 L16,11 L13,15 L16,15 L11.5,19.5 L13,16 L8,16 L11,12 L8,12 L10,11 Z"/>
</vector>
```

Create `android/app/src/main/res/drawable/ic_pause.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M6,5 L10,5 L10,19 L6,19 L6,5 Z M14,5 L18,5 L18,19 L14,19 L14,5 Z"/>
</vector>
```

Create `android/app/src/main/res/drawable/ic_storage.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path
        android:fillColor="@android:color/white"
        android:pathData="M2,20 L22,20 L22,16 L2,16 L2,20 Z M4,17 L6,17 L6,19 L4,19 L4,17 Z M2,4 L2,8 L22,8 L22,4 L2,4 Z M6,7 L4,7 L4,5 L6,5 L6,7 Z M2,14 L22,14 L22,10 L2,10 L2,14 Z M4,11 L6,11 L6,13 L4,13 L4,11 Z"/>
</vector>
```

Create `android/app/src/main/res/drawable/ic_favorite_border_outline.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path
        android:fillColor="@android:color/white"
        android:fillAlpha="1"
        android:strokeColor="@android:color/white"
        android:strokeWidth="0"
        android:pathData="M16.5,3 C14.76,3 13.09,3.81 12,5.09 C10.91,3.81 9.24,3 7.5,3 C4.42,3 2,5.42 2,8.5 C2,12.28 5.4,15.36 10.55,20.04 L12,21.35 L13.45,20.03 C18.6,15.36 22,12.28 22,8.5 C22,5.42 19.58,3 16.5,3 Z M12.1,18.55 L12,18.65 L11.89,18.55 C7.14,14.24 4,11.39 4,8.5 C4,6.5 5.5,5 7.5,5 C9.04,5 10.54,5.99 11.07,7.36 L12.94,7.36 C13.46,5.99 14.96,5 16.5,5 C18.5,5 20,6.5 20,8.5 C20,11.39 16.86,14.24 12.1,18.55 Z"/>
</vector>
```

- [ ] **Step 4: Remove material-icons-extended dependency**

In `android/app/build.gradle.kts`, delete line 211:
```kotlin
implementation("androidx.compose.material:material-icons-extended")
```

Replace with a comment explaining why:
```kotlin
// Round 21 D2: material-icons-extended removed. Core icons (bundled with
// material3) are still available via androidx.compose.material.icons.Icons.
// Non-core icons are res/drawable/ic_*.xml vector drawables referenced via
// painterResource(R.drawable.ic_<name>).
```

- [ ] **Step 5: Update all Kotlin imports/usages — file-by-file map**

For each Kotlin file that imports a non-core icon, do **two things** per affected icon:
1. Delete the import line `import androidx.compose.material.icons.<filled|outlined>.<IconName>`
2. Change all usages from `Icons.Filled.<IconName>` (or `Icons.Outlined.<IconName>`) to `painterResource(R.drawable.ic_<name>)`. Add `import androidx.compose.ui.res.painterResource` and `import com.juziss.localmediahub.R` at the top if not already present.

**File-by-file changes (per the icon inventory from Task 3 Step 1):**

**File `android/app/src/main/java/com/juziss/localmediahub/ui/component/BrowseContent.kt`**:
- Line 13: delete `import androidx.compose.material.icons.outlined.FavoriteBorder`
- Replace `Icons.Outlined.FavoriteBorder` with `painterResource(R.drawable.ic_favorite_border_outline)` (add imports above)
- Lines 11-12 (KeyboardArrowDown/Up) — KEEP, those are core

**File `android/app/src/main/java/com/juziss/localmediahub/ui/screen/ConnectionScreen.kt`**:
- Lines 25, 26, 27, 29: delete `import ...filled.Error`, `...filled.Folder`, `...filled.History`, `...filled.Storage`
- Replace `Icons.Filled.Error` → `painterResource(R.drawable.ic_error)`
- Replace `Icons.Filled.Folder` → `painterResource(R.drawable.ic_folder)`
- Replace `Icons.Filled.History` → `painterResource(R.drawable.ic_history)`
- Replace `Icons.Filled.Storage` → `painterResource(R.drawable.ic_storage)`
- Lines 23, 24, 28 — KEEP (CheckCircle, Close, Search)

**File `android/app/src/main/java/com/juziss/localmediahub/ui/screen/DownloadsScreen.kt`**:
- Line 17: delete `import ...filled.CloudOff`, replace usage with `painterResource(R.drawable.ic_cloud_off)`
- Line 20: delete `import ...filled.Movie`, replace usage with `painterResource(R.drawable.ic_movie)`
- Line 21: delete `import ...filled.Folder`, replace usage with `painterResource(R.drawable.ic_folder)`
- Line 22: delete `import ...filled.FolderOpen`, replace usage with `painterResource(R.drawable.ic_folder_open)`
- Lines 18, 19 — KEEP (Delete, PlayArrow)

**File `android/app/src/main/java/com/juziss/localmediahub/ui/screen/HomeScreen.kt`**:
- Line 37: delete `import ...filled.Bookmarks`, replace with `painterResource(R.drawable.ic_bookmarks)`
- Line 41: delete `import ...filled.Folder`, replace with `painterResource(R.drawable.ic_folder)`
- Line 42: delete `import ...filled.History`, replace with `painterResource(R.drawable.ic_history)`
- Line 45: delete `import ...filled.Storage`, replace with `painterResource(R.drawable.ic_storage)`
- Line 71: delete `import ...filled.Movie`, replace with `painterResource(R.drawable.ic_movie)`
- Line 72: delete `import ...filled.Language`, replace with `painterResource(R.drawable.ic_language)`
- Lines 38-40, 43-44 — KEEP (CheckCircle, Close, Favorite, PlayArrow, Refresh)

**File `android/app/src/main/java/com/juziss/localmediahub/ui/component/GridContainers.kt`**:
- Line 16: delete `import ...filled.FolderOff`, replace with `painterResource(R.drawable.ic_folder_off)`
- Line 17: delete `import ...filled.Storage`, replace with `painterResource(R.drawable.ic_storage)`

**File `android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt`**:
- Line 24: delete `import ...filled.Brightness6`, replace with `painterResource(R.drawable.ic_brightness_6)`
- Line 25: delete `import ...filled.FastForward`, replace with `painterResource(R.drawable.ic_fast_forward)`
- Line 26: delete `import ...filled.FastRewind`, replace with `painterResource(R.drawable.ic_fast_rewind)`
- Line 27: delete `import ...filled.Pause`, replace with `painterResource(R.drawable.ic_pause)`
- Lines 28, 30 — KEEP (PlayArrow, Delete)

**File `android/app/src/main/java/com/juziss/localmediahub/ui/component/home/HomeComponents.kt`**:
- Line 22: delete `import ...filled.Bookmarks`, replace with `painterResource(R.drawable.ic_bookmarks)`
- Line 25: delete `import ...filled.Folder`, replace with `painterResource(R.drawable.ic_folder)`
- Line 26: delete `import ...filled.History`, replace with `painterResource(R.drawable.ic_history)`
- Line 28: delete `import ...filled.Storage`, replace with `painterResource(R.drawable.ic_storage)`
- Line 29: delete `import ...filled.Movie`, replace with `painterResource(R.drawable.ic_movie)`
- Line 30: delete `import ...filled.Language`, replace with `painterResource(R.drawable.ic_language)`
- Lines 23, 24, 27 — KEEP (CheckCircle, Favorite, PlayArrow)

**File `android/app/src/main/java/com/juziss/localmediahub/ui/component/PlayerGestureDetector.kt`**:
- Line 9: delete `import ...filled.Brightness6`, replace with `painterResource(R.drawable.ic_brightness_6)`
- Line 10: delete `import ...filled.Pause`, replace with `painterResource(R.drawable.ic_pause)`
- Line 11 — KEEP (PlayArrow)

**File `android/app/src/main/java/com/juziss/localmediahub/ui/component/MediaItems.kt`**:
- Line 13: delete `import ...filled.Folder`, replace with `painterResource(R.drawable.ic_folder)`
- Line 14: delete `import ...filled.Movie`, replace with `painterResource(R.drawable.ic_movie)`
- Lines 11, 12, 24 — KEEP (Favorite, FavoriteBorder, PlayArrow)
  - Note: line 12 is `filled.FavoriteBorder` which IS in core (the core bundle's `FavoriteBorderKt`).

**File `android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseFavoritesView.kt`**:
- Line 7: delete `import ...outlined.FavoriteBorder`, replace with `painterResource(R.drawable.ic_favorite_border_outline)`

**File `android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseTopBar.kt`**:
- Line 7: delete `import ...filled.Storage`, replace with `painterResource(R.drawable.ic_storage)`
- Line 8: delete `import ...outlined.FavoriteBorder`, replace with `painterResource(R.drawable.ic_favorite_border_outline)`
- Line 6 — KEEP (Search is core)

**File `android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseStateContent.kt`**:
- Line 9: delete `import ...filled.Bookmarks`, replace with `painterResource(R.drawable.ic_bookmarks)`
- Line 10: delete `import ...filled.Folder`, replace with `painterResource(R.drawable.ic_folder)`
- Line 11: delete `import ...filled.Storage`, replace with `painterResource(R.drawable.ic_storage)`

**File `android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseSortMenu.kt`**:
- Line 23 — KEEP (Check is core)

- [ ] **Step 6: Run debug build to verify all icons compile**

Run:
```bash
cd android && ./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL. If compile fails:
- "Unresolved reference: <IconName>" → check whether the icon was meant to stay (core) or be replaced (per Step 5 map). Likely you missed a usage site.
- "Unresolved reference: painterResource" → add `import androidx.compose.ui.res.painterResource`
- "Unresolved reference: R" → add `import com.juziss.localmediahub.R`

If an `Icon` Composable (not `ImageVector`) was used (e.g., `Icon(Icons.Filled.X, ...)`) — `Icon` accepts a `painter` parameter, so `Icon(painter = painterResource(R.drawable.ic_x), contentDescription = ...)` works without further changes.

- [ ] **Step 7: Run release build to verify R8 doesn't choke**

Run:
```bash
cd android && ./gradlew assembleRelease
```
Expected: BUILD SUCCESSFUL.

---

## Task 4: D3 — Remove OkHttp Public Suffix List

**Files:**
- Modify: `android/app/build.gradle.kts` — add `excludes += "okhttp3/internal/publicsuffix/publicsuffixes.gz"` to packaging block (currently at lines 115-119)
- Modify: `android/app/src/main/java/com/juziss/localmediahub/network/OkHttpModule.kt` — add `cookieJar(okhttp3.CookieJar.NO_COOKIES)` to builder

**Interfaces:**
- Produces: OkHttp `NO_COOKIES` jar + Gradle packaging exclusion for the PSL gz resource

- [ ] **Step 1: Add packaging exclusion**

In `android/app/build.gradle.kts`, find the `packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }` block (lines 115-119). Modify to add the OkHttp PSL exclusion:

```kotlin
packaging {
    resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Round 21 D3: OkHttp ships a 41KB gzipped Public Suffix List
        // used only by CookieJar implementations that enforce secure-cookie
        // rules. We use CookieJar.NO_COOKIES (no cookie handling at all), so
        // the PSL is dead weight. Strip at packaging time so the savings are
        // deterministic regardless of R8 tree-shaking behavior.
        excludes += "okhttp3/internal/publicsuffix/publicsuffixes.gz"
    }
}
```

- [ ] **Step 2: Add CookieJar.NO_COOKIES to OkHttpClient builder**

In `android/app/src/main/java/com/juziss/localmediahub/network/OkHttpModule.kt`, modify the `provideOkHttpClient` function (currently at lines 43-62) to add `.cookieJar(okhttp3.CookieJar.NO_COOKIES)` to the builder chain:

```kotlin
@Provides
@Singleton
fun provideOkHttpClient(cache: Cache): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .cache(cache)
        .cookieJar(okhttp3.CookieJar.NO_COOKIES) // Round 21 D3: no cookie handling — strips PSL
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(15, 5, TimeUnit.MINUTES))

    // Verbose HTTP logging only in debug; release builds skip the
    // interceptor to save memory and avoid leaking paths in logcat.
    if (BuildConfig.DEBUG) {
        builder.addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
    }

    return builder.build()
}
```

- [ ] **Step 3: Run debug build**

Run:
```bash
cd android && ./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL. If OkHttp throws at startup about missing PSL, that means it still tries to load via reflection — the Gradle packaging exclusion handles APK contents, but the class file may still call into `PublicSuffixDatabase`. In that case (unlikely but possible), also add to `android/app/proguard-rules.pro`:

```
# Round 21 D3: NO_COOKIES jar means PublicSuffixDatabase is unused.
# In case OkHttp lazy-loads the .gz resource via reflection, strip the loader.
-assumenosideeffects class okhttp3.internal.publicsuffix.PublicSuffixDatabase {
    *;
}
```

- [ ] **Step 4: Run release build**

Run:
```bash
cd android && ./gradlew assembleRelease
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Verify PSL gz is gone from APK**

Run:
```bash
unzip -l android/app/build/outputs/apk/release/app-release.apk | grep publicsuffixes
```
Expected: empty output. The 41KB resource should be gone.

---

## Task 5: D4 — Upgrade Coil 2.5.0 → 2.6.0

**Files:**
- Modify: `android/app/build.gradle.kts` — line 230

**Interfaces:**
- Produces: `io.coil-kt:coil-compose:2.6.0`

- [ ] **Step 1: Bump Coil version**

In `android/app/build.gradle.kts` line 230, change:
```kotlin
implementation("io.coil-kt:coil-compose:2.5.0")
```
to:
```kotlin
implementation("io.coil-kt:coil-compose:2.6.0")
```

Add a comment above:
```kotlin
// Round 21 D4: Coil 2.6 trims dead code (coil-kt/coil#1889) and improves
// Compose lazy-list scroll performance. API-compatible with 2.5.
implementation("io.coil-kt:coil-compose:2.6.0")
```

- [ ] **Step 2: Run release build**

Run:
```bash
cd android && ./gradlew assembleRelease
```
Expected: BUILD SUCCESSFUL. If Gradle can't resolve 2.6.0 (offline cache), add `--refresh-dependencies`:
```bash
cd android && ./gradlew assembleRelease --refresh-dependencies
```

- [ ] **Step 3: Run unit tests (no regressions expected)**

Run:
```bash
cd android && ./gradlew testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, all tests pass.

---

## Task 6: D6 — Rust panic = abort

**Files:**
- Modify: `android/app/src/main/rust/Cargo.toml` — line 78

**Interfaces:**
- Produces: Rust release profile with `panic = "abort"` (smaller `.so`)

- [ ] **Step 1: Change panic strategy**

In `android/app/src/main/rust/Cargo.toml`, line 78 currently reads:
```toml
panic = "unwind"
```
Change to:
```toml
# Round 21 D6: JNI libraries do not support cross-frame unwinding. Switching
# to abort eliminates landing pads and unwind tables, shrinking the .so by
# ~10-20%. A panic in JNI code aborts the process either way — this just
# removes the dead unwind metadata.
panic = "abort"
```

- [ ] **Step 2: Force Rust rebuild (Gradle won't detect Cargo.toml changes automatically)**

The `buildRustNative` Gradle task (defined in `app/build.gradle.kts:177-194`) runs `cargo ndk build --release` but does not declare `Cargo.toml` as an input. Force a clean rebuild:

Run:
```bash
# Clean cached cargo build for this crate
rm -rf android/app/src/main/rust/target/aarch64-linux-android

# Also delete the existing .so so Gradle's preBuild reruns cargo
rm -f android/app/src/main/jniLibs/arm64-v8a/liblocalmedia_native.so
```

- [ ] **Step 3: Run release build (will recompile Rust crate)**

Run:
```bash
cd android && ./gradlew assembleRelease
```
Expected: BUILD SUCCESSFUL. The `preBuild` task will depend on `buildRustNative` which will invoke `cargo ndk build --release` and write a fresh `liblocalmedia_native.so` to `jniLibs/arm64-v8a/`.

If `cargo` is not on PATH: install cargo-ndk (`cargo install cargo-ndk`) and ensure `ANDROID_NDK_HOME` is set (see `app/build.gradle.kts:147-175` for resolution order).

- [ ] **Step 4: Verify new .so is smaller**

Run:
```bash
stat -c '%s' android/app/src/main/jniLibs/arm64-v8a/liblocalmedia_native.so
```
Expected: smaller than baseline `1,123,912` bytes (1.07 MB). Target: ≤ 1,000,000 bytes. Record new value.

- [ ] **Step 5: Run full release build one more time to confirm C1 is stable**

Run:
```bash
cd android && ./gradlew assembleRelease
```
Expected: BUILD SUCCESSFUL.

---

## Task 7: C1 — Capture final size, smoke test, commit

**Files:**
- Read-only: `android/app/build/outputs/apk/release/app-release.apk`

**Interfaces:**
- Produces: C1 git commit with all changes from Task 1-6

- [ ] **Step 1: Capture post-C1 APK size breakdown**

Run:
```bash
unzip -l android/app/build/outputs/apk/release/app-release.apk | awk '
  /\.dex$/ {dex+=$1}
  /lib\/arm64-v8a\/.*\.so$/ {nat+=$1}
  /res\// {res+=$1}
  /resources\.arsc$/ {arsc+=$1}
  END {
    printf "DEX: %.2f MB\nNative: %.2f MB\nRes: %.2f MB\nresources.arsc: %.2f MB\n",
           dex/1048576, nat/1048576, res/1048576, arsc/1048576
  }'
stat -c 'APK total: %s bytes' android/app/build/outputs/apk/release/app-release.apk
```
Expected: total APK size noticeably smaller than baseline `7,492,684`. Record exact values.

- [ ] **Step 2: Smoke test on device (manual, ~5 minutes)**

Install the new APK and verify each capability is unbroken:

```bash
# Install on connected device
cd android && ./gradlew installRelease
# or manually: adb install -r app/build/outputs/apk/release/app-release.apk
```

Manual checklist on device:
1. App launches without crashing (validates R8 full mode + Coil 2.6 + Rust panic=abort all coexist)
2. Connect to server (validates OkHttp `NO_COOKIES` doesn't break connection flow)
3. Browse to a large folder (validates all 15 SVG icons render correctly — no "missing icon" boxes)
4. Open a video, play it for 5 seconds (validates Media3 + OkHttp + Rust decoder pipeline)
5. Tap pause, then resume (validates `ic_pause` swap with `Icons.Filled.PlayArrow` works)
6. Open a large image (validates Coil 2.6 + Rust native decoder)
7. Open the Downloads screen (validates `ic_cloud_off`, `ic_folder_open`)
8. Open the Connection screen and look at the error state (validates `ic_error`, `ic_history`)

If any icon is missing/wrong, fix the SVG path in the corresponding `res/drawable/ic_*.xml` and re-run release build.

- [ ] **Step 3: Run unit tests one final time**

Run:
```bash
cd android && ./gradlew testDebugUnitTest
```
Expected: all tests pass.

- [ ] **Step 4: Commit C1**

Stage and commit all changes from Task 1-6:

```bash
cd "E:/github_project/LocalMediaHub" && git add \
  gradle.properties \
  android/app/build.gradle.kts \
  android/app/src/main/java/com/juziss/localmediahub/network/OkHttpModule.kt \
  android/app/src/main/rust/Cargo.toml \
  android/app/src/main/java/com/juziss/localmediahub/ui/component/BrowseContent.kt \
  android/app/src/main/java/com/juziss/localmediahub/ui/screen/ConnectionScreen.kt \
  android/app/src/main/java/com/juziss/localmediahub/ui/screen/DownloadsScreen.kt \
  android/app/src/main/java/com/juziss/localmediahub/ui/screen/HomeScreen.kt \
  android/app/src/main/java/com/juziss/localmediahub/ui/component/GridContainers.kt \
  android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt \
  android/app/src/main/java/com/juziss/localmediahub/ui/component/home/HomeComponents.kt \
  android/app/src/main/java/com/juziss/localmediahub/ui/component/PlayerGestureDetector.kt \
  android/app/src/main/java/com/juziss/localmediahub/ui/component/MediaItems.kt \
  android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseFavoritesView.kt \
  android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseTopBar.kt \
  android/app/src/main/java/com/juziss/localmediahub/ui/component/browse/BrowseStateContent.kt \
  android/app/src/main/res/drawable/ic_bookmarks.xml \
  android/app/src/main/res/drawable/ic_brightness_6.xml \
  android/app/src/main/res/drawable/ic_cloud_off.xml \
  android/app/src/main/res/drawable/ic_error.xml \
  android/app/src/main/res/drawable/ic_fast_forward.xml \
  android/app/src/main/res/drawable/ic_fast_rewind.xml \
  android/app/src/main/res/drawable/ic_folder.xml \
  android/app/src/main/res/drawable/ic_folder_off.xml \
  android/app/src/main/res/drawable/ic_folder_open.xml \
  android/app/src/main/res/drawable/ic_history.xml \
  android/app/src/main/res/drawable/ic_language.xml \
  android/app/src/main/res/drawable/ic_movie.xml \
  android/app/src/main/res/drawable/ic_pause.xml \
  android/app/src/main/res/drawable/ic_storage.xml \
  android/app/src/main/res/drawable/ic_favorite_border_outline.xml \
  android/app/src/main/jniLibs/arm64-v8a/liblocalmedia_native.so
```

If `git status` reveals additional modified files (e.g., proguard-rules.pro if Task 1 Step 4 added a keep), include those too.

Commit message:
```bash
git commit -m "$(cat <<'EOF'
perf(android): C1 APK size optimization (round 21 batch D)

D1: enable R8 full mode (gradle.properties)
D1.1: resourceConfigurations zh+en (trim 3rd-party translations)
D2: drop material-icons-extended, replace 15 non-core icons with SVG vector drawables
D3: OkHttp NO_COOKIES jar + packaging excludes publicsuffixes.gz
D4: Coil 2.5.0 -> 2.6.0
D6: Rust panic=abort (smaller liblocalmedia_native.so)

C1 size: 7.15 MB -> <measure-and-fill> MB (delta: <X> KB)
C2 (FFmpeg rebuild) follows as a separate commit.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

Replace `<measure-and-fill>` and `<X>` with the actual numbers from Step 1.

- [ ] **Step 5: Stop here if D5 is deferred**

If C1 alone meets the goal (≤ 5.8 MB) or D5 (FFmpeg rebuild) is deferred due to toolchain constraints, stop. The plan's C1 deliverable is independently valuable.

If proceeding to C2, continue to Task 8.

---

## Task 8: D5 — FFmpeg rebuild preparation

**Files:**
- Create: `scripts/build_ffmpeg.sh`
- Create: `docs/ffmpeg-build-config.md`

**Interfaces:**
- Produces: `scripts/build_ffmpeg.sh` cross-compile script and `docs/ffmpeg-build-config.md` documentation

- [ ] **Step 1: Verify toolchain availability**

Run:
```bash
which cargo
which ndk-build 2>/dev/null || ls "$ANDROID_NDK_HOME" 2>/dev/null || ls "$ANDROID_NDK_ROOT" 2>/dev/null
which autoconf 2>/dev/null
which make
```
Expected: cargo, make present; NDK location visible. autoconf is needed by FFmpeg configure script — on Windows, install via MSYS2 (`pacman -S autoconf automake libtool`) or use WSL.

If any tool is missing, **stop here and surface the gap to the user** — do NOT proceed to rebuild FFmpeg without a working toolchain. C1 has already been delivered; C2 can be deferred.

- [ ] **Step 2: Inspect the current FFmpeg binary to capture its build config**

Run:
```bash
# On a Linux/WSL host with the .so accessible (the .so alone won't tell us
# the configure flags; we need the ffmpeg executable that produced it).
# If the build environment that produced the current .so is unknown, document
# that and design the new build from a clean --disable-everything baseline.
echo "Current libffmpeg.so size:"
stat -c '%s' android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so
```
Expected: `3391296` bytes (3.23 MB).

- [ ] **Step 3: Create the docs/ffmpeg-build-config.md record**

Create `docs/ffmpeg-build-config.md`:
```markdown
# FFmpeg Build Configuration (Round 21 D5)

This document records the build flags used to produce
`android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so`.

## Source

FFmpeg upstream: <https://ffmpeg.org>
Tested with version: 6.x (use the latest stable 6.x release)

## Toolchain

- Android NDK r25+ (set `ANDROID_NDK_HOME`)
- Host: Linux or WSL (Windows native build is not supported by FFmpeg configure)
- make, autoconf, automake, libtool

## Configure flags

```bash
./configure \
  --prefix=./build/ffmpeg-arm64 \
  --target-os=android \
  --arch=aarch64 \
  --cpu=cortex-a57 \
  --enable-cross-compile \
  --cross-prefix="$NDK/toolchains/llvm/prebuilt/<host>/bin/aarch64-linux-android-" \
  --cc="$NDK/toolchains/llvm/prebuilt/<host>/bin/aarch64-linux-android24-clang" \
  --sysroot="$NDK/toolchains/llvm/prebuilt/<host>/sysroot" \
  --extra-cflags="-Os -fPIC" \
  --extra-ldflags="-Wl,--gc-sections" \
  --disable-everything \
  --disable-doc \
  --disable-programs \
  --disable-debug \
  --disable-network \
  --disable-autodetect \
  --enable-small \
  --enable-pic \
  --enable-decoder=h264,hevc,vp8,vp9,mpeg4,mpeg2video,theora,vc1,wmv3 \
  --enable-demuxer=mov,matroska,avi,flv,webm,mpegts,asf,ogg \
  --enable-protocol=file,pipe \
  --enable-filter=scale,format \
  --enable-muxer=mp4 \
  --disable-asm
```

Notes:
- `--disable-asm` keeps the build simple at a small perf cost. Re-enable NEON
  asm later if transcoding perf is insufficient.
- `--disable-everything` is the floor; only the listed decoders/demuxers/etc.
  are enabled. Add a missing codec ONLY if a real-world file fails to play.

## Verification matrix

After replacing `libffmpeg.so`, test each of these end-to-end:
- mp4 (mov demuxer, h264 decoder)
- mkv (matroska demuxer)
- avi (avi demuxer)
- flv (flv demuxer)
- Transcoded playback (mp4 muxer, libx264 encoder — already linked separately)
- Video thumbnail extraction (`ffmpeg -ss <mid> -i <input> -vframes 1`)
```

- [ ] **Step 4: Create the build script**

Create `scripts/build_ffmpeg.sh` (executable):
```bash
#!/usr/bin/env bash
# scripts/build_ffmpeg.sh
# Cross-compiles FFmpeg for Android arm64-v8a, producing a minimal libffmpeg.so
# suitable for LocalMediaHub. See docs/ffmpeg-build-config.md for details.
set -euo pipefail

FFMPEG_VERSION="${FFMPEG_VERSION:-6.1.1}"
WORKDIR="${WORKDIR:-$(pwd)/build/ffmpeg-src}"
OUTPUT_DIR="${OUTPUT_DIR:-$(pwd)/android/app/src/main/jniLibs/arm64-v8a}"

if [[ -z "${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}" ]]; then
  echo "ERROR: ANDROID_NDK_HOME or ANDROID_NDK_ROOT must be set."
  exit 1
fi
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT}}"
HOST_OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
HOST_MACHINE="$(uname -m)"
case "$HOST_OS" in
  mingw*|msys*) HOST_TAG="windows-x86_64" ;;
  darwin)       HOST_TAG="darwin-x86_64" ;;
  linux)        HOST_TAG="linux-x86_64" ;;
  *)            HOST_TAG="${HOST_OS}-${HOST_MACHINE}" ;;
esac
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
CC="$TOOLCHAIN/bin/aarch64-linux-android24-clang"

mkdir -p "$WORKDIR" "$OUTPUT_DIR"
cd "$WORKDIR"

if [[ ! -d "ffmpeg-${FFMPEG_VERSION}" ]]; then
  curl -fsSL "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz" -o "ffmpeg.tar.xz"
  tar xf ffmpeg.tar.xz
fi
cd "ffmpeg-${FFMPEG_VERSION}"

./configure \
  --prefix="$WORKDIR/install" \
  --target-os=android \
  --arch=aarch64 \
  --cpu=cortex-a57 \
  --enable-cross-compile \
  --cross-prefix="$TOOLCHAIN/bin/llvm-" \
  --cc="$CC" \
  --sysroot="$TOOLCHAIN/sysroot" \
  --extra-cflags="-Os -fPIC" \
  --extra-ldflags="-Wl,--gc-sections" \
  --disable-everything \
  --disable-doc \
  --disable-programs \
  --disable-debug \
  --disable-network \
  --disable-autodetect \
  --enable-small \
  --enable-pic \
  --enable-decoder=h264,hevc,vp8,vp9,mpeg4,mpeg2video,theora,vc1,wmv3 \
  --enable-demuxer=mov,matroska,avi,flv,webm,mpegts,asf,ogg \
  --enable-protocol=file,pipe \
  --enable-filter=scale,format \
  --enable-muxer=mp4 \
  --disable-asm

make -j"$(nproc)"
make install

# FFmpeg's `make install` does not produce a single .so by default. We use
# the static archives and the build system's libffmpeg.so target.
# Copy the shared library if it exists; otherwise fall back to creating one
# from the static archives via the linker.
if [[ -f "libffmpeg.so" ]]; then
  cp "libffmpeg.so" "$OUTPUT_DIR/libffmpeg.so"
else
  echo "WARNING: libffmpeg.so not produced. Inspect $WORKDIR/ffmpeg-${FFMPEG_VERSION}/"
  echo "         and adjust the configure flags. FFmpeg's Android .so output"
  echo "         sometimes needs --enable-shared plus a custom build step."
  exit 1
fi

echo "Done: $OUTPUT_DIR/libffmpeg.so"
stat -c '%s bytes' "$OUTPUT_DIR/libffmpeg.so"
```

Make it executable:
```bash
chmod +x scripts/build_ffmpeg.sh
```

---

## Task 9: D5 — Execute FFmpeg rebuild (platform-dependent)

**Files:**
- Modify: `android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so` (replaced binary)

**Interfaces:**
- Produces: smaller `libffmpeg.so` (target: ≤ 1.5 MB, down from 3.23 MB)

- [ ] **Step 1: Run the build script (requires Linux/WSL + NDK)**

**This step is platform-dependent.** On Windows, run from WSL:
```bash
wsl bash -c "cd /mnt/e/github_project/LocalMediaHub && ANDROID_NDK_HOME=$ANDROID_NDK_HOME bash scripts/build_ffmpeg.sh"
```

On Linux/macOS:
```bash
cd "E:/github_project/LocalMediaHub" && bash scripts/build_ffmpeg.sh
```

Expected: script downloads FFmpeg 6.1.1, configures, builds, and writes a smaller `libffmpeg.so`. If any step fails, **do NOT proceed** — read the FFmpeg configure output, fix the missing flag/dependency, re-run.

- [ ] **Step 2: Verify the new .so size**

Run:
```bash
stat -c '%s' android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so
```
Expected: ≤ 1,572,864 bytes (1.5 MB). If still > 2 MB, re-run `./configure` with `--disable-decoder=<some-heavy-codec>` after confirming none of the user's media files actually need it.

- [ ] **Step 3: Run release build with new FFmpeg**

Run:
```bash
cd android && ./gradlew assembleRelease
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Smoke test transcoded playback**

Install the APK and exercise the FFmpeg path:
1. Play a video that requires transcoding (toggle the transcode switch on the player)
2. Verify the video plays without stutter
3. Browse to a video and verify the thumbnail shows (validates `ffmpeg -ss` frame extraction)

If transcoded playback fails, the most likely culprits are missing encoders (libx264, aac). The script does NOT enable libx264 (it's an external lib that requires separate build). If transcode is broken, either:
- (a) Re-enable the system aac encoder: `--enable-encoder=aac` in the configure line, OR
- (b) Disable transcoding UI in the Android app (out of scope for this task — surface as a follow-up)

For now, if transcode is broken, fall back to: keep the old libffmpeg.so for transcoding, ship the new one only if the user confirms they don't transcode. (Decision point for the user.)

- [ ] **Step 5: Capture final APK size**

Run:
```bash
stat -c '%s' android/app/build/outputs/apk/release/app-release.apk
unzip -l android/app/build/outputs/apk/release/app-release.apk | awk '
  /lib\/arm64-v8a\/libffmpeg\.so$/ {printf "libffmpeg.so: %.2f MB\n", $1/1048576}'
```
Expected: total APK size ≤ `6,085,932` bytes (5.81 MB). libffmpeg.so ≤ 1.5 MB.

- [ ] **Step 6: Commit C2**

```bash
cd "E:/github_project/LocalMediaHub" && git add \
  scripts/build_ffmpeg.sh \
  docs/ffmpeg-build-config.md \
  android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so
git commit -m "$(cat <<'EOF'
perf(android): C2 FFmpeg rebuild (round 21 batch D)

D5: rebuild libffmpeg.so with --disable-everything baseline. Only the
decoders/demuxers/protocols/muxers/filters actually used by LocalMediaHub
are re-enabled. Adds scripts/build_ffmpeg.sh (reproducible build) and
docs/ffmpeg-build-config.md (records the configure flags).

C2 size: <measure-and-fill> MB -> <measure-and-fill> MB (delta: <X> KB)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

Replace placeholders with actual numbers from Step 5.

---

## Task 10: Final verification

**Files:**
- Read-only: `android/app/build/outputs/apk/release/app-release.apk`

- [ ] **Step 1: Final APK size + breakdown**

Run:
```bash
stat -c 'Final APK total: %s bytes (%.2f MB)' android/app/build/outputs/apk/release/app-release.apk
unzip -l android/app/build/outputs/apk/release/app-release.apk | awk '
  /\.dex$/ {dex+=$1}
  /lib\/arm64-v8a\/.*\.so$/ {nat+=$1}
  /res\// {res+=$1}
  /resources\.arsc$/ {arsc+=$1}
  END {
    printf "DEX: %.2f MB\nNative libs: %.2f MB\nRes: %.2f MB\nresources.arsc: %.2f MB\n",
           dex/1048576, nat/1048576, res/1048576, arsc/1048576
  }'
```
Expected: total ≤ 6,085,932 bytes (5.81 MB).

- [ ] **Step 2: Final smoke test (manual)**

Repeat Task 7 Step 2's smoke checklist. Plus (if C2 was done):
- Play one video of each container format: mp4, mkv, avi, flv
- Toggle transcoding on and verify it still works (or document that it's disabled)

- [ ] **Step 3: Update README APK size note**

In `README.md`, find the line that mentions the APK size (if any) and update it to reflect the new release size. If no such line exists, skip this step.

- [ ] **Step 4: Done**

The plan is complete. The APK now meets the ≤ 5.8 MB target (or close to it, depending on C2 outcome).

---

## Notes for the implementer

- **Task ordering matters within C1.** Task 1 (R8 full mode) must come first because subsequent changes (D2 icon removal, D3 PSL removal) depend on R8 being configured to strip them. If you do D2 first without full mode, the savings are smaller and harder to measure.
- **Task 6 (D6 Rust) requires `cargo` and the Android NDK on PATH.** If they're not available, defer D6 to a separate commit after C1 — but be aware that C1's measured savings will then be 100-200 KB smaller than the plan estimates.
- **Task 8-9 (D5 FFmpeg) are the riskiest.** If the toolchain isn't available or the rebuild fails, **stop and surface the gap to the user** rather than guessing at configure flags. C1 alone is a meaningful deliverable.
- **Do NOT skip the smoke tests.** R8 full mode in particular can introduce subtle runtime crashes that don't show up in unit tests (reflection-based libraries break silently). The Task 7 Step 2 checklist is the only thing catching these.
- **Commit boundary is at end of Task 7 (C1) and end of Task 9 (C2).** Do NOT commit per-task — that defeats the "single coherent C1 / C2" framing in the spec.
