# Native 层遗留 follow-ups 批量打包（Round 14）

- **日期**: 2026-07-05
- **范围**: Rust + Kotlin 8 项遗留 follow-ups（不含 Logger 注入重构）
- **策略**: 5 个 commit 分组（doc/typo → helper 提取 → Rust 防御 → 加载可观测性 → buffer 优化）
- **状态**: 待评审
- **前置**: Round 11（Rust 重写）；Round 12（缓存）；Round 13（security hardening #1）

---

## 1. 背景与动机

Round 11/12/13 三轮 final code review 累计标记了 9 项遗留 follow-ups（1 Important + 6 Minor + 1 Other + 1 加载可观测性）。Round 14 批量打包 8 项（不含 Logger 注入重构），一次性清账。

### 1.1 全部 8 项清单

| # | 来源 | 严重度 | 项目 |
|---|---|---|---|
| 1 | Round 11 Important #2 | Important | Native 加载失败可观测性（Kotlin 侧） |
| 2 | Round 11 Minor | Minor | `jpeg.rs:86` stale doc |
| 3 | Round 11 Minor | Minor | `Cargo.toml` typo（"DEVIVATION"、"theturbojpeg"） |
| 4 | Round 11 Minor | Minor | `NativeDecoderFactory.kt` 重复 magic-byte sniff |
| 5 | Round 11 Minor | Minor | `exif_reader.rs:71` `v[0]` 无空检查 |
| 6 | Round 11 Minor | Minor | `png.rs:14-15` doc 提"set_format"实际是"set_transformations" |
| 7 | Round 11 Minor | Minor | `proguard-rules.pro` 部分 keep 被 catch-all 覆盖 |
| 8 | Round 11 Other | Minor | `jpeg.rs::cmyk_to_rgba` 简化的 CMYK 公式 |

**显式排除：**
- ❌ Logger 注入重构（Round 12 Important）— 涉及面广，单独一轮
- ❌ HEIC 真实现、IDCT-scale、AVIF/JXL、JavaVM 缓存 — 见 Round 13 spec §8 后续备忘
- ❌ 任何新功能

### 1.2 PNG buffer 零初始化优化（新增）

Round 11 final review 提到 `png.rs` 零初始化保守可接受。Round 14 顺带处理：用 `MaybeUninit` 省一次零填充。

---

## 2. 目标与非目标

### 目标
1. **C1（doc/typo）**：`jpeg.rs` stale doc + `Cargo.toml` typo + `png.rs` doc
2. **C2（helper 提取）**：`NativeDecoderFactory` magic-byte 提取 + `proguard-rules.pro` 简化
3. **C3（Rust 防御）**：`v[0]` 空检查 + CMYK 公式修正
4. **C4（加载可观测性）**：3 个 Native object init 加 BuildConfig.DEBUG + release fast-fail
5. **C5（buffer 优化）**：`png.rs` 用 `MaybeUninit` 移除零初始化

### 非目标
- ❌ Logger 注入重构
- ❌ HEIC、IDCT-scale、AVIF/JXL
- ❌ 任何架构变化
- ❌ 任何新功能

---

## 3. 架构与文件组织

### 3.1 文件改动矩阵（5 个 commit）

| Commit | 文件 | 改动类型 |
|---|---|---|
| C1 | `android/app/src/main/rust/src/jpeg.rs` | 改 doc 注释 |
| C1 | `android/app/src/main/rust/src/png.rs` | 改 doc 注释 |
| C1 | `android/app/src/main/rust/Cargo.toml` | 改 typo |
| C2 | `android/app/src/main/java/com/juziss/localmediahub/native/NativeDecoderFactory.kt` | 提取 helper |
| C2 | `android/app/proguard-rules.pro` | 简化 keep 规则 |
| C3 | `android/app/src/main/rust/src/exif_reader.rs` | `v[0]` → `v.first().copied()` |
| C3 | `android/app/src/main/rust/src/jpeg.rs` | CMYK 公式修正 |
| C4 | `android/app/src/main/java/com/juziss/localmediahub/native/NativeImageDecoder.kt` | init 加 BuildConfig.DEBUG 检测 |
| C4 | `android/app/src/main/java/com/juziss/localmediahub/native/NativeExif.kt` | 同上 |
| C4 | `android/app/src/main/java/com/juziss/localmediahub/native/NaturalSorter.kt` | 同上 |
| C5 | `android/app/src/main/rust/src/png.rs` | `MaybeUninit` 优化 |

无新增文件，无 Cargo.toml dep 改动，无 build.gradle.kts 改动。

### 3.2 关键约束

- jni-rs 0.21 API（保持不变）
- BuildConfig.DEBUG 在 debug build type = true，release = false
- Robolectric 测试运行在 debug variant（`BuildConfig.DEBUG = true`），所以加载失败时仅 Log.w，不抛 IllegalStateException → **不影响现有 Robolectric 测试**
- CMYK 修复基于 Adobe 反转约定，无样本验证（接受文档化限制）
- PNG MaybeUninit 优化依赖 `png` crate 0.17 的 `next_frame` 完全覆盖 buffer 的契约（已验证）

---

## 4. 实现细节

### 4.1 Commit 1: doc/typo 修复

**`jpeg.rs` 修正 stale doc**（line 85-87，`fast_downscale_rgba` 函数文档）：

```rust
// 当前（line 86）：
/// `webp::decode_scaled`, and (in Task 4) `png::decode`. Returns the resized
// 改为：
/// `webp::decode_scaled`, and `png::decode_scaled`. Returns the resized
```

> 注：实际的 stale doc 在 `fast_downscale_rgba` 函数注释中（line 85-87），不是 JNI dispatch table。它说 `png::decode` 但实际调用方是 `png::decode_scaled`（png.rs:94），且 "(in Task 4)" 前瞻注释已过时。

**`png.rs` 修正 doc**（line 12）：

```rust
// 当前（line 12）：
//! are truncated to 8 bit via the `set_format` call below (the thumbnail
// 改为：
//! are truncated to 8 bit via the `set_transformations` call below (the thumbnail
```

> 注：代码 line 36 已使用 `decoder.set_transformations(png::Transformations::STRIP_16)`，仅 doc 注释未同步更新。

**`Cargo.toml` typos**：

- "DEVIVATION FROM PLAN" → "DEVIATION FROM PLAN"
- "theturbojpeg crate" → "the turbojpeg crate"

### 4.2 Commit 2: helper 提取 + proguard 简化

**`NativeDecoderFactory.kt`**：提取 `companion object` 静态 helper，删除实例方法 `nativeHandlesFormat`：

```kotlin
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
        /** True iff [bytes] begins with a magic signature that the Rust
         *  `nativeDecodeByteArray` knows how to handle (JPEG/WebP/PNG/HEIC).
         *  Cheap byte-level sniff — no JNI calls. */
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
            val isHeic = bytes.size >= 12 &&
                String(bytes, 4, 4) == "ftyp"
            return isJpeg || isWebp || isPng || isHeic
        }
    }
}
```

**`proguard-rules.pro` 简化**：删除 3 个 per-class keep（被 catch-all 覆盖）：

```proguard
# 删除：
# -keep class com.juziss.localmediahub.native.NativeImageDecoder { native <methods>; }
# -keep class com.juziss.localmediahub.native.NativeExif { native <methods>; }
# -keep class com.juziss.localmediahub.native.NaturalSorter { native <methods>; }

# 保留：
-keep class com.juziss.localmediahub.native.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
```

### 4.3 Commit 3: Rust 防御性修复

**`exif_reader.rs:72` `v[0]` 空检查**：

```rust
// 当前（lines 68-77）：
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

// 改为：
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

> 注：`get_field` 需要两个参数 `(Tag, In)` — 实际代码使用 `In::PRIMARY`。

**`jpeg.rs::cmyk_to_rgba` 公式修正**（Adobe 反转 CMYK 约定，lines 158-176）：

当前代码已将 C/M/Y 反转（`255 - chunk[n]`），但 K 通道未反转（`chunk[3] as i32` 直接使用），
导致公式 `(255-C) * K / 255` 在 K=0（白色纸张）时错误地将 RGB 全部置零。

```rust
// 当前（错误，lines 163-169）：
//   let c = 255 - chunk[0] as i32;
//   ...（CMY 已反转）
//   let k = chunk[3] as i32; // K channel already 0..255  ← 未反转
//   let r = (c * k / 255).clamp(0, 255) as u8;  ← K=0 时 R=0（错误）

// 改为（Adobe 标准，保持原函数签名 fn cmyk_to_rgba(cmyk: &[u8]) -> Vec<u8>）：
fn cmyk_to_rgba(cmyk: &[u8]) -> Vec<u8> {
    // Adobe-style inverted CMYK: all four channels are inverted.
    // Formula: rgb = (255 - channel) * (255 - k) / 255
    let mut out = Vec::with_capacity(cmyk.len() / 4 * 4);
    for chunk in cmyk.chunks_exact(4) {
        let c = chunk[0] as u32;  // already Adobe-inverted
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

> ⚠️ **签名保持一致**：实际函数签名是 `fn cmyk_to_rgba(cmyk: &[u8]) -> Vec<u8>`（接受 slice，返回 Vec），不是 4 个独立参数 + `[u8; 4]` 返回值。修复必须保持原签名和 iterator 模式。

> ⚠️ **CMYK 验证限制**：项目测试库无 CMYK JPEG 样本。此修复基于 Adobe 文档标准公式，host 单测无法验证。在 commit message + spec §8 显式记录此限制。

### 4.4 Commit 4: 加载可观测性（BuildConfig.DEBUG fast-fail）

**`NativeImageDecoder.kt`、`NativeExif.kt`、`NaturalSorter.kt`** 三个 object 的 init 块统一改为：

```kotlin
init {
    try {
        System.loadLibrary("localmedia_native")
        nativeAvailable = true
    } catch (e: UnsatisfiedLinkError) {
        if (BuildConfig.DEBUG) {
            // Debug build (incl. Robolectric unit tests on host JVM):
            // native lib absent is expected; fall back gracefully.
            Log.w(TAG, "liblocalmedia_native.so unavailable, using fallback", e)
        } else {
            // Release build: missing .so means the build pipeline broke or
            // R8 stripped the symbol. Silent fallback would hide a critical
            // regression. Crash loudly so it surfaces in Crashlytics / Play
            // Console / user reports.
            throw IllegalStateException(
                "liblocalmedia_native.so failed to load — production builds must include the native library",
                e,
            )
        }
    }
}
```

**新增 import**：`import com.juziss.localmediahub.BuildConfig`

> ⚠️ **关键约束**：Robolectric 单测在 host JVM 跑，BuildConfig.DEBUG = true（debug variant），所以测试时仍走 Log.w + fallback 路径。release 才 fast-fail。**不影响现有 57 个 JVM 测试**。

### 4.5 Commit 5: PNG buffer 零初始化优化

**`png.rs` 当前**（line 47-48）：

```rust
let mut buf = vec![0u8; reader.output_buffer_size()];
let frame_info = reader.next_frame(&mut buf).ok()?;
```

**改为 `unsafe set_len`**（更安全的惯用写法，避免 `transmute`）：

```rust
let buf_size = reader.output_buffer_size();
// SAFETY: `next_frame` fully writes `buf_size` bytes per png 0.17 contract.
// Skipping zero-fill saves one memset pass on large images.
let mut buf: Vec<u8> = Vec::with_capacity(buf_size);
unsafe { buf.set_len(buf_size); }
let frame_info = reader.next_frame(&mut buf).ok()?;
```

> ⚠️ **为何不用 `MaybeUninit` + `transmute`**：`std::mem::transmute::<Vec<MaybeUninit<u8>>, Vec<u8>>()` 依赖 `Vec` 内部布局兼容性，Rust 标准库**不保证** `Vec<MaybeUninit<T>>` 和 `Vec<T>` 具有相同 layout（虽然实际上是一样的）。直接在 `Vec<u8>` 上 `set_len` 是标准做法（与 `Read::read_to_end` 内部实现一致），且 SAFETY 论证更简洁：只需证明 `next_frame` 完全写入 buffer。

> ⚠️ **依赖契约**：`png` crate 0.17 的 `next_frame` 必须完全写入 buf。文档明确这一点，但缺乏 formal proof。Round 11 final review 标注"保守接受零初始化"作为 Minor，Round 14 选择激进优化，加 SAFETY 注释解释依赖。

---

## 5. 测试

### 5.1 测试矩阵

| Commit | 现有测试 | 新测试 | 验证 |
|---|---|---|---|
| C1 doc/typo | cargo test, JVM test 全过 | 无 | doc/typo 不影响行为 |
| C2 helper 提取 | Gradle assembleDebug 通过 | ⚠️ 可选：新增 `NativeDecoderFactoryTest`（当前不存在） | helper 行为等价；建议补充 `nativeHandles()` 单元测试 |
| C3 Rust 防御 | `cargo test` 43+ | 无（CMYK 无样本） | `v.first().copied()` 行为等价；CMYK 文档化限制 |
| C4 加载可观测性 | 57 JVM tests 全过 | 可选：mock BuildConfig.DEBUG | Robolectric 跑 debug variant，仍走 fallback |
| C5 buffer 优化 | `cargo test` PNG 解码测试 | 无 | `decode_real_png_rgb` + `decode_scaled_real_png_downscaled` 验证 |

### 5.2 真机/模拟器手工回归

- 浏览网格 → JPEG/WebP/PNG 缩略图正常加载（C1/C2/C3/C5 不影响行为）
- 竖拍照片 → EXIF 旋转正确（C3 不影响）
- HEIC 图片 → fallback 正常（C4 不影响 HEIC 路径）
- **release APK 验证**：构建 release APK + 启动 app → 不应 crash（因为 .so 正确打包）；若 R8 误删 .so 应该 crash + 触发 fast-fail

### 5.3 CMYK 验证限制

`jpeg.rs::cmyk_to_rgba` 公式修正**无样本验证**。项目测试库无 CMYK JPEG（CMYK JPEG 是印前印刷行业格式，移动端极罕见）。修复基于 Adobe Photoshop 标准 CMYK 反转约定：

- `c=255`（纯青色）→ `r=0`
- `k=255`（纯黑）→ `r=g=b=0`
- `c=m=y=k=0`（白色）→ `r=g=b=255`

公式 `rgb = (255 - channel) * (255 - k) / 255` 满足上述边界条件。

---

## 6. 实现顺序与提交策略

5 个 commit，每个独立可提交：

1. **C1 doc/typo**：3 个文件 doc/typo 修复（最简、最低风险，先做）
2. **C2 helper 提取**：NativeDecoderFactory + proguard（Kotlin 侧）
3. **C3 Rust 防御**：exif_reader + jpeg CMYK（Rust 侧）
4. **C4 加载可观测性**：3 个 Native object init（Kotlin 侧，触及面最广，最后做）
5. **C5 buffer 优化**：png.rs MaybeUninit（Rust 侧 micro-optimization，最后做）

每个 commit 之间：
- `cd android/app/src/main/rust && cargo test`（C1/C3/C5 影响 Rust）
- `cd android && ./gradlew assembleDebug :app:testDebugUnitTest`（C2/C4 影响 Kotlin；C1/C3/C5 也需验证 Gradle build）

---

## 7. 已决策点

| 决策 | 选择 | 理由 |
|---|---|---|
| 范围 | 8 项 follow-ups（不含 Logger 重构） | 用户明确选最大打包 |
| 加载可观测性 | BuildConfig.DEBUG + release fast-fail | 用户明确选 |
| 提交粒度 | 5 个 commit | 用户明确选 |
| CMYK 公式 | Adobe 反转：`(255 - channel) * (255 - k) / 255` | Adobe 标准约定 |
| Buffer 优化 | 直接 `unsafe set_len`，无 cfg 开关 | YAGNI，不做双路径；避免 `transmute` |
| Helper 提取 | `companion object nativeHandles`，删除实例方法 | 单一来源 |
| proguard 简化 | 删除 per-class keep | catch-all 已覆盖 |

---

## 8. 已知限制（接受）

1. **CMYK 修复无样本验证**：移动端 CMYK JPEG 极罕见；修复基于 Adobe 文档标准公式。如未来发现实际样本可补充测试。
2. **PNG `unsafe set_len` 依赖 png crate 契约**：`next_frame` 必须完全写入 buffer。png 0.17 文档明确，但缺乏 formal proof。SAFETY 注释解释依赖。
3. **加载可观测性 fast-fail 仅 release**：debug build（含 Robolectric）仍 fallback。这意味着开发者本地测试可能漏掉 release-only 的 .so 配置问题。CI 需加 release smoke test 弥补（不在本 spec 范围）。
4. **v[0] 防御是防御性**：kamadak-exif 0.5 在 Tag::Orientation 上始终返回非空 vec，本修复是上游 API 变化的兜底。
5. **`NativeDecoderFactoryTest` 不存在**：spec 原始版本声称该测试已存在，但实际项目中未找到。C2 helper 提取后应考虑补充 `nativeHandles()` 的单元测试。

---

## 9. 非目标（再次明确）

- ❌ Logger 注入重构（移到后续轮次）
- ❌ HEIC 真实现
- ❌ IDCT-scale、AVIF/JXL、Animated WebP
- ❌ JNI JavaVM 缓存
- ❌ Cargo.toml dep 改动、build.gradle.kts 改动
- ❌ 任何新功能

---

## 10. 后续轮次（不在本 spec，仅备忘）

- **Logger 注入重构**：解决 Round 12 `isReturnDefaultValues = true` 全局 flag 的 fidelity 倒退
- **HEIC 真实现**：libheif-rs 或纯 Rust HEIC crate
- **Native instrumented test 基础设施**：CI 覆盖真 JNI 路径
- **JPEG IDCT-scale**：评估切回 turbojpeg crate 或上游 PR
- **Coil v3 升级**：原生"按访问时间淘汰"，解决 Round 12 mtime 限制
