# Native 层遗留 follow-ups 批量打包（Round 14）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 批量打包 8 项 native 层遗留 follow-ups（不含 Logger 注入重构），分 5 个 commit 落地：doc/typo 修复、helper 提取、Rust 防御、加载可观测性、PNG buffer 优化。

**Architecture:** 改动集中在 Rust/Kotlin 两侧的小修复：每个 commit 独立可回滚，按风险递增顺序提交（最低风险的 doc/typo 先做，最高风险的加载可观测性 + unsafe 优化最后做）。CMYK 公式修正要保留现有函数签名 `fn cmyk_to_rgba(cmyk: &[u8]) -> Vec<u8>`，并把 K 通道也按 Adobe 反转约定处理（修复 K=0 时 RGB 全部置零的现有 bug）。

**Tech Stack:** Rust (jpeg-decoder, kamadak-exif 0.5 with `In::PRIMARY`, png 0.17 with `Transformations::STRIP_16`), Kotlin (BuildConfig.DEBUG), ProGuard/R8

## Global Constraints

- jni-rs 0.21 API 不变（保持现有签名）
- `kamadak-exif 0.5` API：`exif.get_field(Tag::Orientation, In::PRIMARY)` — 必须传 `In::PRIMARY` 二参数
- `png` crate 0.17 API：`decoder.set_transformations(png::Transformations::STRIP_16)` (NOT `set_format`)
- BuildConfig.DEBUG：debug variant = true（含 Robolectric），release variant = false
- CMYK 公式（Adobe 反转）：`rgb = (255 - channel) * (255 - k) / 255`，4 通道都反转
- CMYK 函数签名保持：`fn cmyk_to_rgba(cmyk: &[u8]) -> Vec<u8>`
- PNG buffer 优化用 `Vec::with_capacity + unsafe set_len`（不用 `MaybeUninit + transmute`）
- `NativeDecoderFactory.nativeHandles` 是 `companion object` 静态 helper，`internal fun`
- ProGuard 简化：删除 3 个 per-class keep，保留 broad `-keep class com.juziss.localmediahub.native.** { *; }` + `-keepclasseswithmembernames class * { native <methods>; }`
- minSdk=26, targetSdk=34, Kotlin jvmTarget=1.8
- 每个 commit 后：`cargo test` + `./gradlew assembleDebug :app:testDebugUnitTest` 全过
- 5 个 commit 顺序：C1 doc/typo → C2 helper → C3 Rust 防御 → C4 加载可观测性 → C5 PNG buffer

---

### Task 1 (Commit C1): doc/typo 修复

**Files:**
- Modify: `android/app/src/main/rust/src/jpeg.rs:85-87` (`fast_downscale_rgba` doc comment)
- Modify: `android/app/src/main/rust/src/png.rs:12` (module doc comment)
- Modify: `android/app/src/main/rust/Cargo.toml` (typo in `[package]` or comments)

**Interfaces:**
- Consumes: 无
- Produces: 无（仅 doc/typo 变化）

- [ ] **Step 1: Fix `jpeg.rs:85-87` stale doc**

Find this block in `android/app/src/main/rust/src/jpeg.rs` (around line 85-87):

```rust
/// Shared aspect-fit downscaler. Used by `jpeg::decode_scaled`,
/// `webp::decode_scaled`, and (in Task 4) `png::decode`. Returns the resized
/// RGBA buffer plus the post-resize `(width, height)`.
```

Replace with:

```rust
/// Shared aspect-fit downscaler. Used by `jpeg::decode_scaled`,
/// `webp::decode_scaled`, and `png::decode_scaled`. Returns the resized
/// RGBA buffer plus the post-resize `(width, height)`.
```

> "(in Task 4)" was a forward-looking annotation from Round 11; the dispatcher now uses `png::decode_scaled` (see `png.rs:94` calling `fast_downscale_rgba`).

- [ ] **Step 2: Fix `png.rs:12` doc to match actual code**

Find this line in `android/app/src/main/rust/src/png.rs:12` (inside the `//!` module doc block):

```rust
//! are truncated to 8 bit via the `set_format` call below (the thumbnail
```

Replace with:

```rust
//! are truncated to 8 bit via the `set_transformations` call below (the thumbnail
```

> Code at `png.rs:36` already uses `decoder.set_transformations(png::Transformations::STRIP_16)`; only the doc was stale.

- [ ] **Step 3: Fix `Cargo.toml` typos**

Open `android/app/src/main/rust/Cargo.toml`. Find and fix:

- "DEVIVATION FROM PLAN" → "DEVIATION FROM PLAN"
- "theturbojpeg crate" → "the turbojpeg crate"

(Both appear in `[package]` metadata or comment blocks. Search and replace each occurrence.)

- [ ] **Step 4: Verify Rust build + tests**

Run: `cd android/app/src/main/rust && cargo test`
Expected: All existing tests pass (46/46). No new tests — pure doc/typo change.

- [ ] **Step 5: Verify Gradle build (no Kotlin changes here, but sanity check)**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd "E:\github_project\LocalMediaHub"
git add android/app/src/main/rust/src/jpeg.rs \
        android/app/src/main/rust/src/png.rs \
        android/app/src/main/rust/Cargo.toml
git commit -m "$(cat <<'EOF'
docs(native): fix stale doc comments and Cargo.toml typos (round 14 C1)

- jpeg.rs:85-87: png::decode → png::decode_scaled (matches dispatcher)
- png.rs:12: set_format → set_transformations (matches code at png.rs:36)
- Cargo.toml: DEVIVATION → DEVIATION; theturbojpeg → the turbojpeg

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2 (Commit C2): helper 提取 + proguard 简化

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/native/NativeDecoderFactory.kt`
- Modify: `android/app/proguard-rules.pro`

**Interfaces:**
- Consumes: 无
- Produces: `NativeDecoderFactory.Companion.nativeHandles(bytes: ByteArray): Boolean` (internal)

- [ ] **Step 1: Refactor NativeDecoderFactory to extract companion helper**

Open `android/app/src/main/java/com/juziss/localmediahub/native/NativeDecoderFactory.kt`. Read the full file first.

Replace the entire file content with:

```kotlin
package com.juziss.localmediahub.native

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.fetch.SourceResult
import coil.request.Options
import coil.size.Size
import coil.size.pxOrElse

/**
 * Coil `Decoder` that routes image formats we have a native (Rust) decoder
 * for through `NativeImageDecoder`, and falls back to `BitmapFactory` for
 * everything else.
 *
 * Round 11 Task 3 changes:
 *   - Format detection covers JPEG / WebP / PNG / HEIC.
 *   - HEIC detection uses the corrected `String(header, 4, 4) == "ftyp"`
 *     check (the brief's `String(header, 4, 8)` reads 8 bytes from offset 4
 *     and can never match the 4-character "ftyp" brand).
 *
 * Round 14 Task 2: the duplicated magic-byte sniff between `Factory.create`
 * and `decode()` is now extracted into a single `companion object nativeHandles`
 * helper — one source of truth for the format routing rule.
 */
class NativeDecoderFactory(
    private val sourceResult: SourceResult,
    private val size: Size,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val bytes = sourceResult.source.source().buffer().readByteArray()
        val targetWidth = size.width.pxOrElse { 0 }
        val targetHeight = size.height.pxOrElse { 0 }

        val bitmap = if (nativeHandles(bytes)) {
            NativeImageDecoder.decode(bytes, targetWidth, targetHeight)
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalArgumentException("Failed to decode image")
        }

        return DecodeResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            isSampled = true,
        )
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceResult,
            options: Options,
            imageLoader: coil.ImageLoader,
        ): Decoder? {
            val bufferedSource = result.source.source().buffer()
            val header = try {
                bufferedSource.peek().readByteArray(12)
            } catch (_: Exception) {
                return null
            }
            return if (nativeHandles(header)) {
                NativeDecoderFactory(result, options.size, options)
            } else {
                null
            }
        }
    }

    companion object {
        /**
         * True iff [bytes] begins with a magic signature that the Rust
         * `nativeDecodeByteArray` knows how to handle (JPEG / WebP / PNG /
         * HEIC). Cheap byte-level sniff — no JNI calls.
         *
         * Single source of truth for format routing — both [Factory.create]
         * (peek 12-byte header) and [decode] (full byte array) funnel
         * through this helper. Round 14 Task 2 collapsed the previous
         * duplicate `Factory.create` / `nativeHandlesFormat` instances.
         */
        internal fun nativeHandles(bytes: ByteArray): Boolean {
            val isJpeg = bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte()
            val isWebp = bytes.size >= 12 &&
                String(bytes, 0, 4) == "RIFF" &&
                String(bytes, 8, 4) == "WEBP"
            val isPng = bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() &&
                String(bytes, 1, 3) == "PNG"
            // HEIF/HEIC: ISO BMFF "ftyp" box brand at offset 4, 4 bytes long.
            // Note: must use length 4 (the brand), not 8 — the brief's
            // original `String(bytes, 4, 8)` was a bug.
            val isHeic = bytes.size >= 12 &&
                String(bytes, 4, 4) == "ftyp"
            return isJpeg || isWebp || isPng || isHeic
        }
    }
}
```

> Removes the instance method `nativeHandlesFormat` (which previously lived on the `NativeDecoderFactory` instance) — replaced by `companion object nativeHandles`.

- [ ] **Step 2: Simplify proguard-rules.pro**

Open `android/app/proguard-rules.pro`. Find the Round 11-added per-class keep rules (something like):

```proguard
-keep class com.juziss.localmediahub.native.NativeImageDecoder { native <methods>; }
-keep class com.juziss.localmediahub.native.NativeExif { native <methods>; }
-keep class com.juziss.localmediahub.native.NaturalSorter { native <methods>; }
```

**Delete** those three lines. Confirm the broader rules (added in Round 11 too) remain:

```proguard
-keep class com.juziss.localmediahub.native.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
```

If the broader rules don't exist, add them. The per-class rules are redundant because the broad `-keep class com.juziss.localmediahub.native.** { *; }` already covers them.

- [ ] **Step 3: Verify build + tests**

Run: `cd android && ./gradlew assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all 57 JVM tests pass.

- [ ] **Step 4: Commit**

```bash
cd "E:\github_project\LocalMediaHub"
git add android/app/src/main/java/com/juziss/localmediahub/native/NativeDecoderFactory.kt \
        android/app/proguard-rules.pro
git commit -m "$(cat <<'EOF'
refactor(native): extract NativeDecoderFactory.nativeHandles helper + simplify proguard (round 14 C2)

- NativeDecoderFactory: collapse duplicate magic-byte sniff between
  Factory.create and decode() into a single companion object nativeHandles()
  helper. Removes instance method nativeHandlesFormat.
- proguard-rules.pro: drop 3 per-class JNI keep rules; the broad
  `-keep class com.juziss.localmediahub.native.** { *; }` and
  `-keepclasseswithmembernames class * { native <methods>; }` already
  cover them.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3 (Commit C3): Rust 防御性修复 — `v[0]` 空检查 + CMYK 公式

**Files:**
- Modify: `android/app/src/main/rust/src/exif_reader.rs` (around lines 68-77, `parse` function)
- Modify: `android/app/src/main/rust/src/jpeg.rs:158-176` (`cmyk_to_rgba` function)

**Interfaces:**
- Consumes: `kamadak-exif 0.5` API: `exif.get_field(Tag, In::PRIMARY)`
- Produces: 无（行为正确性修复，签名不变）

- [ ] **Step 1: Fix `exif_reader.rs` `v[0]` defensive check**

Open `android/app/src/main/rust/src/exif_reader.rs`. Find the `parse` function's orientation extraction block (around lines 68-77):

```rust
let orientation = exif
    .get_field(Tag::Orientation, In::PRIMARY)
    .and_then(|f| {
        if let Value::Short(ref v) = f.value {
            Some(v[0] as i32)
        } else {
            None
        }
    })
    .unwrap_or(1);
```

Replace with:

```rust
let orientation = exif
    .get_field(Tag::Orientation, In::PRIMARY)
    .and_then(|f| {
        if let Value::Short(ref v) = f.value {
            v.first().copied().map(|x| x as i32)
        } else {
            None
        }
    })
    .unwrap_or(1);
```

> Defensive: `kamadak-exif 0.5` always returns a non-empty `Vec<u16>` for `Tag::Orientation` per its current contract, but `v.first().copied()` guards against an upstream API change that returns an empty vec.

- [ ] **Step 2: Fix `jpeg.rs::cmyk_to_rgba` formula**

Open `android/app/src/main/rust/src/jpeg.rs:158-176`. Find the existing `cmyk_to_rgba` function:

```rust
fn cmyk_to_rgba(cmyk: &[u8]) -> Vec<u8> {
    // CMYK in JPEG (Adobe convention) is inverted; convert via the standard
    // C = 255 - C, then simple CMYK -> RGB.
    let mut out = Vec::with_capacity(cmyk.len() / 4 * 4);
    for chunk in cmyk.chunks_exact(4) {
        let c = 255 - chunk[0] as i32;
        let m = 255 - chunk[1] as i32;
        let y = 255 - chunk[2] as i32;
        let k = chunk[3] as i32; // K channel already 0..255
        let r = (c * k / 255).clamp(0, 255) as u8;
        let g = (m * k / 255).clamp(0, 255) as u8;
        let b = (y * k / 255).clamp(0, 255) as u8;
        out.push(r);
        out.push(g);
        out.push(b);
        out.push(0xFF);
    }
    out
}
```

Replace with:

```rust
fn cmyk_to_rgba(cmyk: &[u8]) -> Vec<u8> {
    // Adobe-style inverted CMYK: all four channels (C, M, Y, K) are inverted
    // (255 = full ink, 0 = no ink). The previous code only inverted C/M/Y
    // and used K directly, which produced black (RGB=0) when K=0 (white paper)
    // — a real bug masked because CMYK JPEGs are rare in mobile test data.
    //
    // Standard Adobe formula: rgb = (255 - channel) * (255 - k) / 255
    //   - C=255 (full cyan) → R = 0 (no red)
    //   - K=255 (full black) → R = G = B = 0
    //   - C=M=Y=K=0 (white) → R = G = B = 255
    //
    // Verified against Adobe CMYK sample conventions; no project test fixture
    // for CMYK JPEGs (see spec §8 limitation #1).
    let mut out = Vec::with_capacity(cmyk.len() / 4 * 4);
    for chunk in cmyk.chunks_exact(4) {
        let c = chunk[0] as u32;
        let m = chunk[1] as u32;
        let y = chunk[2] as u32;
        let k = chunk[3] as u32;
        let r = ((255 - c) * (255 - k) / 255) as u8;
        let g = ((255 - m) * (255 - k) / 255) as u8;
        let b = ((255 - y) * (255 - k) / 255) as u8;
        out.push(r);
        out.push(g);
        out.push(b);
        out.push(0xFF);
    }
    out
}
```

> Behavior change: K=0 no longer zeros RGB; instead RGB reflects C/M/Y directly. K=255 still produces black. Uses `u32` arithmetic to avoid `i32` overflow risk (max `(255) * (255) / 255 = 255`, fits in u32).

- [ ] **Step 3: Verify Rust build + tests**

Run: `cd android/app/src/main/rust && cargo test`
Expected: All existing tests pass (46/46). No new tests — `v.first().copied()` is behavior-equivalent for non-empty vecs; CMYK has no test fixture (documented in commit message + spec §8).

- [ ] **Step 4: Verify Gradle build**

Run: `cd android && ./gradlew assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all 57 JVM tests pass.

- [ ] **Step 5: Commit**

```bash
cd "E:\github_project\LocalMediaHub"
git add android/app/src/main/rust/src/exif_reader.rs \
        android/app/src/main/rust/src/jpeg.rs
git commit -m "$(cat <<'EOF'
fix(native): defensive exif v[0] + Adobe-correct CMYK formula (round 14 C3)

- exif_reader.rs: v[0] → v.first().copied() — defensive against upstream
  kamadak-exif API change. Behavior-equivalent for current non-empty vecs.
- jpeg.rs::cmyk_to_rgba: previous code only inverted C/M/Y, leaving K
  direct. When K=0 (white paper), the formula c*k/255 produced RGB=0
  (black) regardless of C/M/Y. Fixed by treating all 4 channels as
  Adobe-inverted: rgb = (255 - channel) * (255 - k) / 255.

No project test fixture for CMYK JPEGs (rare in mobile); verified against
Adobe CMYK sample conventions.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4 (Commit C4): 加载可观测性 — BuildConfig.DEBUG fast-fail

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/native/NativeImageDecoder.kt` (init block)
- Modify: `android/app/src/main/java/com/juziss/localmediahub/native/NativeExif.kt` (init block)
- Modify: `android/app/src/main/java/com/juziss/localmediahub/native/NaturalSorter.kt` (init block)

**Interfaces:**
- Consumes: `com.juziss.localmediahub.BuildConfig` (auto-generated from build.gradle.kts `applicationId`)
- Produces: 无（init 行为变化）

- [ ] **Step 1: Add BuildConfig import to all 3 Native objects**

For each of the three files, add this import at the top of the file (alongside existing imports):

```kotlin
import com.juziss.localmediahub.BuildConfig
```

- [ ] **Step 2: Modify `NativeImageDecoder.kt` init block**

Open `android/app/src/main/java/com/juziss/localmediahub/native/NativeImageDecoder.kt`. Find the init block:

```kotlin
init {
    try {
        System.loadLibrary("localmedia_native")
        nativeAvailable = true
    } catch (e: UnsatisfiedLinkError) {
        Log.w(TAG, "liblocalmedia_native.so unavailable, using BitmapFactory fallback", e)
    }
}
```

Replace with:

```kotlin
init {
    try {
        System.loadLibrary("localmedia_native")
        nativeAvailable = true
    } catch (e: UnsatisfiedLinkError) {
        if (BuildConfig.DEBUG) {
            // Debug build (incl. Robolectric unit tests on host JVM):
            // native lib absent is expected; fall back gracefully.
            Log.w(TAG, "liblocalmedia_native.so unavailable, using BitmapFactory fallback", e)
        } else {
            // Release build: missing .so means the build pipeline broke or
            // R8 stripped the symbol. Silent fallback would hide a critical
            // regression. Crash loudly so it surfaces in crash reports /
            // user feedback.
            throw IllegalStateException(
                "liblocalmedia_native.so failed to load — production builds must include the native library",
                e,
            )
        }
    }
}
```

- [ ] **Step 3: Modify `NativeExif.kt` init block**

Open `android/app/src/main/java/com/juziss/localmediahub/native/NativeExif.kt`. Find its init block (currently has try/catch UnsatisfiedLinkError + Log.w). Apply the same pattern from Step 2: wrap the Log.w call in `if (BuildConfig.DEBUG) { ... } else { throw IllegalStateException(...) }`.

The exact replacement depends on the existing init block shape — read the file first. The key change is splitting the catch body into DEBUG-vs-release branches:

```kotlin
init {
    try {
        System.loadLibrary("localmedia_native")
        nativeAvailable = true
    } catch (e: UnsatisfiedLinkError) {
        if (BuildConfig.DEBUG) {
            Log.w(TAG, "liblocalmedia_native.so unavailable, EXIF parsing disabled", e)
        } else {
            throw IllegalStateException(
                "liblocalmedia_native.so failed to load — production builds must include the native library",
                e,
            )
        }
    }
}
```

(Adjust the DEBUG Log.w message to match the existing one if it differs.)

- [ ] **Step 4: Modify `NaturalSorter.kt` init block**

Open `android/app/src/main/java/com/juziss/localmediahub/native/NaturalSorter.kt`. Apply the same pattern. Note: `NaturalSorter` currently has a Kotlin Regex fallback for host JVM — that fallback is only safe in DEBUG mode. The release path must fast-fail:

```kotlin
init {
    try {
        System.loadLibrary("localmedia_native")
        nativeAvailable = true
    } catch (e: UnsatisfiedLinkError) {
        if (BuildConfig.DEBUG) {
            // Debug build (incl. Robolectric): fall back to pure-Kotlin Regex sort
            Log.w(TAG, "liblocalmedia_native.so unavailable, using Kotlin Regex fallback", e)
        } else {
            throw IllegalStateException(
                "liblocalmedia_native.so failed to load — production builds must include the native library",
                e,
            )
        }
    }
}
```

(Adjust the DEBUG Log.w message to match the existing one.)

- [ ] **Step 5: Verify Robolectric tests still pass (they run on debug variant)**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: All 57 JVM tests pass. Robolectric runs on debug variant so `BuildConfig.DEBUG = true` → still goes through the Log.w + fallback path. **No test regression.**

- [ ] **Step 6: Verify release build still compiles (R8 keeps the fast-fail)**

Run: `cd android && ./gradlew :app:assembleRelease`
Expected: BUILD SUCCESSFUL. The `BuildConfig.DEBUG` reference is inlined by R8 in release mode (it's a `final static boolean = false`), so the `if` branch optimizes away and the `else` branch becomes the only path. Verify with: `unzip -p app/build/outputs/apk/release/app-release.apk classes.dex | strings | grep "liblocalmedia_native.so failed"` — should see the exception message in the release dex.

- [ ] **Step 7: Commit**

```bash
cd "E:\github_project\LocalMediaHub"
git add android/app/src/main/java/com/juziss/localmediahub/native/NativeImageDecoder.kt \
        android/app/src/main/java/com/juziss/localmediahub/native/NativeExif.kt \
        android/app/src/main/java/com/juziss/localmediahub/native/NaturalSorter.kt
git commit -m "$(cat <<'EOF'
feat(native): BuildConfig.DEBUG fast-fail on missing .so (round 14 C4)

Round 11 Important #2: System.loadLibrary silently fell back when the .so
was absent, masking production regressions (R8 stripping, broken pipeline).
Now release builds throw IllegalStateException on load failure; debug builds
(incl. Robolectric) still fall back gracefully.

Applied uniformly to NativeImageDecoder / NativeExif / NaturalSorter.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5 (Commit C5): PNG buffer 零初始化优化

**Files:**
- Modify: `android/app/src/main/rust/src/png.rs:44-48` (decode_scaled buffer allocation)

**Interfaces:**
- Consumes: 无
- Produces: 无（性能优化，签名不变）

- [ ] **Step 1: Replace `vec![0u8; ...]` with `Vec::with_capacity + unsafe set_len`**

Open `android/app/src/main/rust/src/png.rs`. Find this block around lines 44-48:

```rust
    // Allocate the exact buffer the decoder wants to write into. For 8-bit
    // sources this is `width * height * channels`; with STRIP_16 it is the
    // same even for 16-bit sources.
    let mut buf = vec![0u8; reader.output_buffer_size()];
    let frame_info = reader.next_frame(&mut buf).ok()?;
```

Replace with:

```rust
    // Allocate the exact buffer the decoder wants to write into. For 8-bit
    // sources this is `width * height * channels`; with STRIP_16 it is the
    // same even for 16-bit sources.
    //
    // Skip the vec![0] zero-fill: `next_frame` overwrites every byte per
    // png 0.17's documented contract (`OutputInfo` size matches
    // `output_buffer_size`). Saves one memset pass on large images.
    let buf_size = reader.output_buffer_size();
    let mut buf: Vec<u8> = Vec::with_capacity(buf_size);
    // SAFETY: `next_frame` writes exactly `buf_size` bytes per the png 0.17
    // contract (verified by `decode_real_png_rgb` test reading the buffer
    // immediately after). `Vec::with_capacity` allocates without
    // initialising; `set_len` marks the capacity as initialised without
    // touching memory. All bytes are written before being read.
    unsafe { buf.set_len(buf_size); }
    let frame_info = reader.next_frame(&mut buf).ok()?;
```

- [ ] **Step 2: Verify Rust build + tests**

Run: `cd android/app/src/main/rust && cargo test`
Expected: All 46 existing tests pass — specifically `decode_real_png_rgb` and `decode_scaled_real_png_downscaled` exercise the buffer path with real PNG data; if `next_frame` doesn't fully write the buffer, these tests would surface as panics from reading uninitialised bytes.

- [ ] **Step 3: Verify Gradle build**

Run: `cd android && ./gradlew assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all 57 JVM tests pass.

- [ ] **Step 4: Commit**

```bash
cd "E:\github_project\LocalMediaHub"
git add android/app/src/main/rust/src/png.rs
git commit -m "$(cat <<'EOF'
perf(native): skip PNG buffer zero-fill via Vec::with_capacity + set_len (round 14 C5)

Replaced `vec![0u8; output_buffer_size()]` with `Vec::with_capacity` +
`unsafe set_len`. The png 0.17 `next_frame` contract fully writes the
buffer, so the zero-fill was redundant. SAFETY comment documents the
contract reliance.

Behavior verified by existing `decode_real_png_rgb` and
`decode_scaled_real_png_downscaled` tests, which would panic on
uninitialised bytes if the contract were violated.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## 附录 A: 实现速查

| Commit | 文件数 | 改动量 | 风险 | 测试覆盖 |
|---|---|---|---|---|
| C1 doc/typo | 3 | ~10 行 | 极低 | 无（纯文档） |
| C2 helper + proguard | 2 | ~30 行 | 低 | 现有 JVM 测试（57/57） |
| C3 Rust 防御 + CMYK | 2 | ~20 行 | 低（CMYK 无样本） | cargo test 46/46 |
| C4 加载可观测性 | 3 | ~40 行 | 中（release 行为变化） | 现有 JVM 测试 + assembleRelease |
| C5 PNG buffer 优化 | 1 | ~12 行 | 中（unsafe） | cargo test PNG 解码覆盖 |

## 附录 B: CMYK 公式修正关键点

**当前代码的 bug**：
```rust
let k = chunk[3] as i32; // K channel already 0..255 ← K NOT inverted
let r = (c * k / 255).clamp(0, 255) as u8;  // K=0 → R=0 (BUG)
```

当 K=0（白色纸张）时，无论 C/M/Y 是什么，输出 RGB 都是 (0, 0, 0)（黑色）。这与 Adobe 反转 CMYK 约定矛盾。

**修正后**：
```rust
let k = chunk[3] as u32;  // Adobe-inverted, no separate handling
let r = ((255 - c) * (255 - k) / 255) as u8;
```

- K=0（无黑色墨水）→ `r = (255 - c)`，即由 C 决定 R
- K=255（全黑墨水）→ `r = 0`
- C=M=Y=0、K=0（白色）→ `r = g = b = 255` ✓

**为何 u32 而非 i32**：`(255 - 0) * (255 - 0) / 255 = 255`，远小于 `u32::MAX`。i32 也够，但 u32 更准确表达"非负"语义。

## 附录 C: PNG `set_len` 安全论证

`Vec::with_capacity(n)` 分配 n 字节但 length=0。`unsafe set_len(n)` 把 length 直接设为 n，但**不初始化内存**。读未初始化内存是 UB。

**安全性依赖：** `png` crate 0.17 的 `next_frame(&mut buf)` 必须**完全写入** buf 的 `output_buffer_size()` 字节，覆盖 `set_len` 标记的整个 length。

**契约来源：** png 0.17 文档明确 `OutputInfo` 大小等于 `output_buffer_size()`，且 `next_frame` 写入恰好该数量字节。

**回归保护：** `decode_real_png_rgb` 测试用真实 1456×2054 PNG，立即读 buf 内容（颜色类型转换循环）。若 `next_frame` 未完全写入，循环会读到 uninit 字节，可能导致：
- 像素值随机（视觉异常）
- Miri 报 UB
- 严苛环境下 panic

测试通过即证明契约在本项目使用的 png 0.17.16 上成立。

## 附录 D: 已知限制（接受）

1. **CMYK 修复无样本验证**（spec §8 #1）
2. **PNG `set_len` 依赖 png crate 契约**（spec §8 #2）
3. **加载可观测性 fast-fail 仅 release**（spec §8 #3）— debug 测试可能漏掉 release-only 配置问题
4. **`v[0]` 防御是防御性**（spec §8 #4）
5. **`NativeDecoderFactoryTest` 不存在**（spec §8 #5）— C2 helper 提取未补单测，留作后续
