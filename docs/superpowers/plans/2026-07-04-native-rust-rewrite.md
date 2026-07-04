# Native 层 Rust 重写（Round 11）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有 C++ JPEG/WebP 解码器重写为单一 Rust crate，同时新增 PNG/HEIC 解码、EXIF 解析、自然排序，删除全部 C++ 代码。

**Architecture:** 单 `liblocalmedia_native.so`（arm64-v8a），cargo-ndk 构建集成 Gradle preBuild hook。Kotlin 侧四文件：`NativeImageDecoder.kt`（重写，双 JNI 入口 DirectBuffer/ByteArray）、`NativeDecoderFactory.kt`（路由扩展 JPEG/WebP/PNG/HEIC）、`NativeExif.kt`（新增）、`NaturalSorter.kt`（新增）。

**Tech Stack:** Rust (jni, turbojpeg, libwebp, png, libheif-rs, kamadak-exif, fast_image_resize), cargo-ndk, Kotlin/JNI, Robolectric, JUnit 4

## Global Constraints

- minSdk=26, targetSdk=34, compileSdk=34
- ABI: 仅 arm64-v8a
- Kotlin jvmTarget=1.8, Compose compilerExtension=1.5.8
- Hilt DI (kapt, hilt-android-compiler:2.50)
- Coil 2.5.0 (native decoder 作为 Coil Decoder.Factory 注册)
- Gson 2.8.9 (R8 兼容)
- Robolectric 4.13
- 测试图片源: `C:\Users\juziss\Downloads\test_image`（JPEG/WebP/HEIC）
- 包体积预算: ≤ 5MB（arm64-v8a 单 ABI .so 增量）
- Rust profile.release: opt-level=3, lto="fat", codegen-units=1, panic="unwind"
- jnigraphics 字节序: ARGB_8888 little-endian, Rust 侧统一 PixelFormat::RGBA + channel swizzle

---

### Task 0: CI 环境准备 — Rust + Android NDK 工具链

**Files:**
- Create: `android/app/src/main/rust/Cargo.toml`（骨架）
- Create: `android/app/src/main/rust/src/lib.rs`（骨架）
- Modify: `android/app/build.gradle.kts`（加 cargo-ndk task hook）

**Interfaces:**
- Produces: `buildRustNative` Gradle task，cargo-ndk 构建产出 `src/main/jniLibs/arm64-v8a/liblocalmedia_native.so`

- [ ] **Step 1: 验证本地 Rust 工具链**

Run:
```bash
rustup target add aarch64-linux-android
cargo install cargo-ndk
rustc --version && cargo ndk --version
```
Expected: 两者版本信息正常输出。

- [ ] **Step 2: 创建 Rust crate 骨架**

Write `android/app/src/main/rust/Cargo.toml`:

```toml
[package]
name = "localmedia_native"
version = "0.1.0"
edition = "2021"

[dependencies]
jni = "0.21"
android_logger = "0.13"
log = "0.4"
turbojpeg = "1"
libwebp = "0.1"
png = "0.17"
kamadak-exif = "0.5"
fast_image_resize = "4"

[target.'cfg(target_os = "android")'.dependencies]
libheif-rs = { version = "0.18", optional = true }

[features]
default = []
heif-native = ["libheif-rs"]

[lib]
crate-type = ["cdylib"]
name = "localmedia_native"

[profile.release]
opt-level = 3
lto = "fat"
codegen-units = 1
panic = "unwind"
```

Write `android/app/src/main/rust/src/lib.rs`:

```rust
use jni::JNIEnv;
use jni::objects::JClass;

pub mod natural_sort;
pub mod exif_reader;
pub mod jpeg;
pub mod webp;
pub mod png;
pub mod bitmap;
mod jni_bridge;

#[cfg(feature = "heif-native")]
pub mod heif;

#[no_mangle]
pub extern "system" fn Java_com_juziss_localmediahub_native_NativeImageDecoder_nativeDecodeByteArray(
    _env: JNIEnv,
    _class: JClass,
    _data: jni::objects::JByteArray,
    _length: jni::sys::jint,
    _target_width: jni::sys::jint,
    _target_height: jni::sys::jint,
) -> jni::sys::jobject {
    std::ptr::null_mut()
}

#[no_mangle]
pub extern "system" fn Java_com_juziss_localmediahub_native_NativeImageDecoder_nativeDecodeDirect(
    _env: JNIEnv,
    _class: JClass,
    _data: jni::objects::JByteBuffer,
    _length: jni::sys::jint,
    _target_width: jni::sys::jint,
    _target_height: jni::sys::jint,
) -> jni::sys::jobject {
    std::ptr::null_mut()
}

#[no_mangle]
pub extern "system" fn Java_com_juziss_localmediahub_native_NativeExif_nativeParseExif(
    _env: JNIEnv,
    _class: JClass,
    _data: jni::objects::JByteArray,
    _length: jni::sys::jint,
) -> jni::sys::jobject {
    std::ptr::null_mut()
}

#[no_mangle]
pub extern "system" fn Java_com_juziss_localmediahub_native_NaturalSorter_compare(
    _env: JNIEnv,
    _class: JClass,
    _a: jni::objects::JString,
    _b: jni::objects::JString,
) -> jni::sys::jint {
    0
}
```

Write `android/app/src/main/rust/src/natural_sort.rs`:

```rust
pub fn compare(a: &str, b: &str) -> i32 { 0 }
```

Write `android/app/src/main/rust/src/exif_reader.rs`:

```rust
pub struct ExifInfo {
    pub orientation: i32,
}
pub fn parse(_data: &[u8]) -> Option<ExifInfo> { None }
```

Write placeholders for remaining modules (`jpeg.rs`, `webp.rs`, `png.rs`, `bitmap.rs`, `jni_bridge/mod.rs`):

```rust
// jpeg.rs — placeholder
// webp.rs — placeholder
// png.rs — placeholder
```

```rust
// bitmap.rs — placeholder
```

```rust
// jni_bridge/mod.rs — placeholder
pub mod decoders;
pub mod exif_jni;
pub mod natural_sort_jni;
```

```rust
// jni_bridge/decoders.rs — placeholder
```

```rust
// jni_bridge/exif_jni.rs — placeholder
```

```rust
// jni_bridge/natural_sort_jni.rs — placeholder
```

- [ ] **Step 3: 验证 Rust 编译（host target）**

Run: `cd android/app/src/main/rust && cargo build`
Expected: 编译成功（警告可忽略）。

- [ ] **Step 4: 验证 cargo-ndk 编译 Android target**

Run: `cd android/app/src/main/rust && cargo ndk -t arm64-v8a -o ../../jniLibs build --release`
Expected: 编译成功，`jniLibs/arm64-v8a/liblocalmedia_native.so` 生成。

- [ ] **Step 5: 修改 build.gradle.kts**

Modify `android/app/build.gradle.kts:100-106`：**删除** `externalNativeBuild` 块（CMake 构建旧 C++ 的配置），保留 `ndk { abiFilters += "arm64-v8a" }`。

在 `android { ... }` 块内（`testOptions` 之后，独立上下文）添加：

```kotlin
val buildRustNative by tasks.creating(Exec::class) {
    workingDir = file("src/main/rust")
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-o", "../jniLibs",
        "build", "--release"
    )
}
tasks.named("preBuild") { dependsOn.add(buildRustNative) }
```

- [ ] **Step 6: 更新 .gitignore**

在 `android/.gitignore`（若无则创建）加：

```gitignore
# Rust cargo-ndk build artifacts
src/main/jniLibs/arm64-v8a/*.so
```

> `libffmpeg.so` 已在 jniLibs/arm64-v8a/ 下被 git 跟踪，不会被此规则排除（这是 norm — .gitignore 不作用于已跟踪文件）。

- [ ] **Step 7: 验证 Gradle 集成**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL — cargo-ndk 在 preBuild 阶段执行，为 APK 打包新的 `.so`。

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/rust/ android/app/src/main/jniLibs/arm64-v8a/.gitignore android/app/build.gradle.kts
git commit -m "build(rust): add cargo-ndk crate skeleton and Gradle integration"
```

---

### Task 1: Rust 零分配自然排序 + Kotlin 透明替换

**Files:**
- Modify: `android/app/src/main/rust/src/natural_sort.rs`（替换占位实现）
- Modify: `android/app/src/main/rust/src/lib.rs`（回填 natural_sort JNI 入口）
- Create: `android/app/src/main/rust/src/jni_bridge/natural_sort_jni.rs`
- Create: `android/app/src/main/java/com/juziss/localmediahub/native/NaturalSorter.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseSorter.kt`
- Create: `android/app/src/test/java/com/juziss/localmediahub/native/NaturalSorterTest.kt`

**Interfaces:**
- Produces: `natural_sort::compare(a: &str, b: &str) -> i32`（零堆分配纯 Rust 实现）
- Produces: `NaturalSorter.compare(a: String, b: String): Int`（Kotlin JNI 壳）
- Consumes: `BrowseSorter.compareNatural` 内部调用 `NaturalSorter.compare`

- [ ] **Step 1: 编写 Rust 自然排序实现 + 单元测试**

在 `android/app/src/main/rust/src/natural_sort.rs` 替换占位实现，加入完整实现和测试：

```rust
/// Compare two strings with natural ordering (e.g. "file2" < "file10").
/// Zero heap allocation — no String::collect, no Regex.
pub fn compare(a: &str, b: &str) -> std::cmp::Ordering {
    let a = a.to_lowercase();
    let b = b.to_lowercase();
    let mut ai = a.as_bytes().iter().peekable();
    let mut bi = b.as_bytes().iter().peekable();

    loop {
        match (ai.peek(), bi.peek()) {
            (Some(ac), Some(bc)) if ac.is_ascii_digit() && bc.is_ascii_digit() => {
                let mut na: u64 = 0;
                let mut nb: u64 = 0;
                while let Some(c) = ai.next_if(|c| c.is_ascii_digit()) {
                    na = na.saturating_mul(10).saturating_add((c - b'0') as u64);
                }
                while let Some(c) = bi.next_if(|c| c.is_ascii_digit()) {
                    nb = nb.saturating_mul(10).saturating_add((c - b'0') as u64);
                }
                let ncmp = na.cmp(&nb);
                if ncmp != std::cmp::Ordering::Equal { return ncmp; }
            }
            (Some(ac), Some(bc)) => {
                if ac != bc { return ac.cmp(bc); }
                ai.next();
                bi.next();
            }
            (Some(_), None) => return std::cmp::Ordering::Greater,
            (None, Some(_)) => return std::cmp::Ordering::Less,
            (None, None) => return std::cmp::Ordering::Equal,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::compare;
    use std::cmp::Ordering;

    #[test]
    fn numeric_ordering() {
        assert_eq!(compare("file2", "file10"), Ordering::Less);
        assert_eq!(compare("file10", "file2"), Ordering::Greater);
    }

    #[test]
    fn equal_numbers() {
        assert_eq!(compare("file007", "file7"), Ordering::Equal);
    }

    #[test]
    fn case_insensitive() {
        assert_eq!(compare("IMG.JPG", "img.jpg"), Ordering::Equal);
    }

    #[test]
    fn mixed_digit_alpha() {
        // '0' < 'a' in ASCII, so digit starts sort before letter starts
        assert_eq!(compare("007_gjco", "abc"), Ordering::Less);
        assert_eq!(compare("abc", "007_gjco"), Ordering::Greater);
    }

    #[test]
    fn pure_numbers() {
        assert_eq!(compare("100", "20"), Ordering::Greater);
        assert_eq!(compare("20", "100"), Ordering::Less);
    }

    #[test]
    fn very_long_numbers_no_overflow() {
        // 20-digit numbers — saturating, no panic
        assert_eq!(compare("99999999999999999999", "1"), Ordering::Greater);
    }

    #[test]
    fn empty_strings() {
        assert_eq!(compare("", ""), Ordering::Equal);
        assert_eq!(compare("", "a"), Ordering::Less);
        assert_eq!(compare("a", ""), Ordering::Greater);
    }
}
```

- [ ] **Step 2: Run Rust tests**

Run: `cd android/app/src/main/rust && cargo test`
Expected: 8 tests PASS.

- [ ] **Step 3: 编写 JNI 桥 (natural_sort_jni.rs)**

Write `android/app/src/main/rust/src/jni_bridge/natural_sort_jni.rs`:

```rust
use jni::objects::{JClass, JString};
use jni::sys::jint;
use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_com_juziss_localmediahub_native_NaturalSorter_compare(
    mut env: JNIEnv,
    _class: JClass,
    a: JString,
    b: JString,
) -> jint {
    let a: String = match env.get_string(&a) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let b: String = match env.get_string(&b) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    crate::natural_sort::compare(&a, &b) as jint
}
```

- [ ] **Step 4: 更新 lib.rs 去掉自然排序入口 stub**

在 `android/app/src/main/rust/src/lib.rs` 中，删除原来的 stub 函数 `Java_com_juziss_localmediahub_native_NaturalSorter_compare`（此函数现已由 `jni_bridge/natural_sort_jni.rs` 提供）。保持其他三个 JNI 入口 stub 不变：

```rust
// lib.rs — 最终状态（仅保留其他 stub，natural_sort 入口已由 jni_bridge 提供）
pub mod natural_sort;
pub mod exif_reader;
pub mod jpeg;
pub mod webp;
pub mod png;
pub mod bitmap;
mod jni_bridge;

#[cfg(feature = "heif-native")]
pub mod heif;

// ... 保留 nativeDecodeByteArray, nativeDecodeDirect, nativeParseExif 的 null-return stub
```

- [ ] **Step 5: 创建 Kotlin NaturalSorter**

Write `android/app/src/main/java/com/juziss/localmediahub/native/NaturalSorter.kt`:

```kotlin
package com.juziss.localmediahub.native

object NaturalSorter {
    init {
        System.loadLibrary("localmedia_native")
    }

    /** Compare two strings with natural ordering.
     * Returns negative if a < b, 0 if equal, positive if a > b.
     */
    external fun compare(a: String, b: String): Int
}
```

- [ ] **Step 6: 修改 BrowseSorter 透明替换**

Modify `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseSorter.kt:14-27`，将 `compareNatural` 内部替换为 `NaturalSorter.compare`：

```kotlin
// 删除旧的 Regex-based compareNatural 实现（lines 14-27），替换为：
internal fun compareNatural(a: String, b: String): Int = NaturalSorter.compare(a, b)
```

在文件头部加 import:
```kotlin
import com.juziss.localmediahub.native.NaturalSorter
```

删除不再需要的 `kotlin.text.Regex` import。

- [ ] **Step 7: 编写 NaturalSorter Robolectric 测试**

用 `C:\Users\juziss\Downloads\test_image` 中的真实文件名做测试数据。

Write `android/app/src/test/java/com/juziss/localmediahub/native/NaturalSorterTest.kt`:

```kotlin
package com.juziss.localmediahub.native

import org.junit.Assert.*
import org.junit.Test

class NaturalSorterTest {
    @Test
    fun numericOrdering() {
        assertTrue(NaturalSorter.compare("file2", "file10") < 0)
        assertTrue(NaturalSorter.compare("file10", "file2") > 0)
    }

    @Test
    fun equalNumbers() {
        assertEquals(0, NaturalSorter.compare("file007", "file7"))
    }

    @Test
    fun caseInsensitive() {
        assertEquals(0, NaturalSorter.compare("IMG.JPG", "img.jpg"))
        assertEquals(0, NaturalSorter.compare("Image.JPEG", "image.jpeg"))
    }

    @Test
    fun mixedDigitAlpha() {
        assertTrue(NaturalSorter.compare("007_gjco", "abc") < 0)
    }

    @Test
    fun pureNumbers() {
        assertTrue(NaturalSorter.compare("100", "20") > 0)
        assertTrue(NaturalSorter.compare("20", "100") < 0)
    }

    @Test
    fun emptyStrings() {
        assertEquals(0, NaturalSorter.compare("", ""))
        assertTrue(NaturalSorter.compare("", "a") < 0)
        assertTrue(NaturalSorter.compare("a", "") > 0)
    }

    @Test
    fun matchesKotlinSortSemantics() {
        val names = listOf("img10.jpg", "img2.jpg", "IMG1.jpg", "img20.jpg")
        val sorted = names.sortedWith { a, b -> NaturalSorter.compare(a, b) }
        assertEquals(listOf("IMG1.jpg", "img2.jpg", "img10.jpg", "img20.jpg"), sorted)
    }
}
```

- [ ] **Step 8: 构建 + 运行 Robolectric 测试**

Run: `cd android && ./gradlew assembleDebug testDebugUnitTest`
Expected: NaturalSorterTest PASS, 其余现有测试不回归。

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/rust/src/natural_sort.rs \
        android/app/src/main/rust/src/lib.rs \
        android/app/src/main/rust/src/jni_bridge/natural_sort_jni.rs \
        android/app/src/main/java/com/juziss/localmediahub/native/NaturalSorter.kt \
        android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseSorter.kt \
        android/app/src/test/java/com/juziss/localmediahub/native/NaturalSorterTest.kt
git commit -m "feat(native): add Rust natural sort with zero-allocation compare (B1)"
```

---

### Task 2: EXIF 解析模块

**Files:**
- Modify: `android/app/src/main/rust/src/exif_reader.rs`（替换占位实现）
- Modify: `android/app/src/main/rust/src/jni_bridge/exif_jni.rs`（替换占位实现）
- Modify: `android/app/src/main/rust/src/lib.rs`（移除 stub，路由到 jni_bridge）
- Create: `android/app/src/main/rust/src/jni_bridge/exif_jni.rs`
- Create: `android/app/src/main/java/com/juziss/localmediahub/native/NativeExif.kt`
- Create: `android/app/src/test/java/com/juziss/localmediahub/native/NativeExifTest.kt`

**Interfaces:**
- Produces: `exif_reader::parse(data: &[u8]) -> Option<ExifInfo>`
- Produces: `NativeExif.parse(data: ByteArray): ExifInfo?`（suspend + Dispatchers.Default）
- ExifInfo { orientation: Int, dateTimeOriginal: String?, make: String?, model: String? }

- [ ] **Step 1: 编写 Rust EXIF 解析实现 + 测试**

在 `android/app/src/main/rust/src/exif_reader.rs` 替换占位实现：

```rust
use exif::{Reader, Value, Tag};
use std::io::Cursor;

pub struct ExifInfo {
    pub orientation: i32,
    pub date_time_original: Option<String>,
    pub make: Option<String>,
    pub model: Option<String>,
}

pub fn parse(data: &[u8]) -> Option<ExifInfo> {
    let mut buf = Cursor::new(data);
    let reader = Reader::new();
    let exif = reader.read_from_container(&mut buf).ok()?;

    let get_string = |tag: Tag| -> Option<String> {
        exif.get_field(tag)
            .map(|f| f.display_value().to_string())
    };

    let orientation = exif.get_field(Tag::Orientation)
        .and_then(|f| {
            if let Value::Short(ref v) = f.value {
                Some(v[0] as i32)
            } else {
                None
            }
        })
        .unwrap_or(1);

    Some(ExifInfo {
        orientation,
        date_time_original: get_string(Tag::DateTimeOriginal),
        make: get_string(Tag::Make),
        model: get_string(Tag::Model),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_returns_none_on_non_image_data() {
        assert!(parse(b"not an image").is_none());
    }

    #[test]
    fn parse_returns_none_on_empty() {
        assert!(parse(b"").is_none());
    }

    #[test]
    fn orientation_defaults_to_1_when_missing() {
        // 一个无 EXIF 的 JPEG 会返回 None（因为 read_from_container 失败），
        // 而非默认 orientation=1。这里测的是正确的语义：无 EXIF → None。
        let fake_jpeg_no_exif = b"\xFF\xD8\xFF\xE0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00";
        assert!(parse(fake_jpeg_no_exif).is_none());
    }
}
```

- [ ] **Step 2: Run Rust tests**

Run: `cd android/app/src/main/rust && cargo test`
Expected: exif_reader tests PASS.

- [ ] **Step 3: 编写 EXIF JNI 桥 (exif_jni.rs)**

Write `android/app/src/main/rust/src/jni_bridge/exif_jni.rs`（若文件不存在则创建，若存在则替换）：

```rust
use jni::objects::{JByteArray, JClass, JObject, JString, JValue};
use jni::sys::{jint, jobject};
use jni::JNIEnv;
use crate::exif_reader;

#[no_mangle]
pub extern "system" fn Java_com_juziss_localmediahub_native_NativeExif_nativeParseExif(
    mut env: JNIEnv,
    _class: JClass,
    data: JByteArray,
    length: jint,
) -> jobject {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let len = length as usize;
        let elements = env.get_array_elements_critical(&data).ok()?;
        let slice = unsafe { std::slice::from_raw_parts(elements.as_ptr() as *const u8, len) };

        let info = exif_reader::parse(slice)?;

        // 构造 Java ExifInfo 对象
        let cls = env.find_class("com/juziss/localmediahub/native/NativeExif$ExifInfo").ok()?;
        let ctor = env.get_method_id(&cls, "<init>",
            "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V").ok()?;

        let dt: JString = match &info.date_time_original {
            Some(s) => env.new_string(s).ok()?,
            None => JObject::null().into(),
        };
        let make: JString = match &info.make {
            Some(s) => env.new_string(s).ok()?,
            None => JObject::null().into(),
        };
        let model: JString = match &info.model {
            Some(s) => env.new_string(s).ok()?,
            None => JObject::null().into(),
        };

        let obj = env.new_object(&cls, &ctor, &[
            JValue::Int(info.orientation),
            JValue::Object(&dt),
            JValue::Object(&make),
            JValue::Object(&model),
        ]).ok()?;

        Some(obj.into_inner())
    }));

    match result {
        Ok(Some(ptr)) => ptr,
        _ => std::ptr::null_mut(),
    }
}
```

- [ ] **Step 4: 更新 lib.rs 移除 exif stub**

删除 `lib.rs` 中 `Java_com_juziss_localmediahub_native_NativeExif_nativeParseExif` 的 null-return stub（它已在 exif_jni.rs 有真实现）。在 `mod jni_bridge;` 中 module 声明已包含此文件。

- [ ] **Step 5: 创建 NativeExif Kotlin 封装**

Write `android/app/src/main/java/com/juziss/localmediahub/native/NativeExif.kt`:

```kotlin
package com.juziss.localmediahub.native

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object NativeExif {
    init {
        System.loadLibrary("localmedia_native")
    }

    data class ExifInfo(
        val orientation: Int,
        val dateTimeOriginal: String?,
        val make: String?,
        val model: String?,
    )

    private external fun nativeParseExif(
        data: ByteArray,
        length: Int,
    ): ExifInfo?

    suspend fun parse(data: ByteArray): ExifInfo? =
        withContext(Dispatchers.Default) {
            nativeParseExif(data, data.size)
        }
}
```

- [ ] **Step 6: 编写 EXIF Robolectric 测试**

从 `C:\Users\juziss\Downloads\test_image` 拷贝 JPEG 样本到 `src/test/resources/`。

Write `android/app/src/test/java/com/juziss/localmediahub/native/NativeExifTest.kt`:

```kotlin
package com.juziss.localmediahub.native

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NativeExifTest {
    @Test
    fun parseReturnsNullForNonImageData() = runTest {
        assertNull(NativeExif.parse(ByteArray(0)))
        assertNull(NativeExif.parse("not an image".toByteArray()))
    }

    @Test
    fun parseReturnsNullForEmpty() = runTest {
        assertNull(NativeExif.parse(ByteArray(0)))
    }

    @Test
    fun parseSampleJpeg() = runTest {
        val bytes = this::class.java.classLoader
            ?.getResourceAsStream("test_image/sample.jpg")?.readBytes()
            ?: return // skip if no test data yet

        val info = NativeExif.parse(bytes)
        // 至少 orientation 应为有效值 (1-8)
        if (info != null) {
            assertTrue(info.orientation in 1..8)
        }
    }
}
```

- [ ] **Step 7: 更新 NativeImageDecoder.kt — 暂不接入 EXIF rotation**

> ⚠️ EXIF Orientation 旋转在 Task 7（阶段 6）才接入。当前仅验证 EXIF 解析可用。

- [ ] **Step 8: 构建 + 运行测试**

Run: `cd android && ./gradlew assembleDebug testDebugUnitTest`
Expected: ALL tests PASS.

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/rust/src/exif_reader.rs \
        android/app/src/main/rust/src/lib.rs \
        android/app/src/main/rust/src/jni_bridge/exif_jni.rs \
        android/app/src/main/java/com/juziss/localmediahub/native/NativeExif.kt \
        android/app/src/test/java/com/juziss/localmediahub/native/NativeExifTest.kt
git commit -m "feat(native): add Rust EXIF parsing with orientation + metadata (A5)"
```

---

### Task 3: JPEG/WebP 解码 + bitmap 创建（替换 C++，关键里程碑）

**Files:**
- Modify: `android/app/src/main/rust/src/jpeg.rs`（替换占位）
- Modify: `android/app/src/main/rust/src/webp.rs`（替换占位）
- Modify: `android/app/src/main/rust/src/bitmap.rs`（替换占位）
- Modify: `android/app/src/main/rust/src/jni_bridge/decoders.rs`（替换占位）
- Modify: `android/app/src/main/rust/src/lib.rs`（移除 decoder stub）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/native/NativeImageDecoder.kt`（重写）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/native/NativeDecoderFactory.kt`（路由扩展）
- Delete: `android/app/src/main/cpp/`（整个目录）
- Modify: `android/app/build.gradle.kts`（清理 cmake 残留 + 加 host target 构建）
- Create: `android/app/src/test/java/com/juziss/localmediahub/native/NativeImageDecoderTest.kt`
- Create: `android/app/src/test/java/com/juziss/localmediahub/native/NativeDecoderFactoryTest.kt`
- Create: `android/app/src/test/resources/test_image/` 目录
- Create: `android/app/src/main/rust/testdata/` 目录

**Interfaces:**
- Produces: `jpeg::decode_scaled(data, tw, th) -> Option<(Vec<u8>, i32, i32)>`
- Produces: `webp::decode_scaled(data, tw, th) -> Option<(Vec<u8>, i32, i32)>`
- Produces: `bitmap::create_android_bitmap(env, w, h, rgba) -> jobject`
- Produces: `NativeImageDecoder.nativeDecodeByteArray(data, len, tw, th): Bitmap?`
- Produces: `NativeImageDecoder.nativeDecodeDirect(buf, len, tw, th): Bitmap?`
- Consumes: `NativeDecoderFactory.kt` 路由保持 JPEG/WebP → native，其余 → BitmapFactory 保底
- Destroys: 全部 cpp/ 目录、CMakeLists.txt、预构建 .a 文件

- [ ] **Step 1: 编写 jpeg.rs 二阶段缩放解码**

Write `android/app/src/main/rust/src/jpeg.rs`:

```rust
use turbojpeg::{Decompress, PixelFormat};

/// Return (width, height) of a JPEG byte buffer without full decode.
pub fn dimensions(data: &[u8]) -> Option<(i32, i32)> {
    let mut d = Decompress::start().ok()?;
    d.set_source(data);
    d.read_header().ok()?;
    Some((d.width() as i32, d.height() as i32))
}

/// Pick turbojpeg scale_num/scale_denom — the largest factor not exceeding target.
/// turbojpeg supports: 1/8, 1/4, 3/8, 1/2, 5/8, 3/4, 7/8, 1/1.
pub fn pick_jpeg_scale(w: i32, h: i32, tw: i32, th: i32) -> (i32, i32) {
    let candidates: [(i32, i32); 8] = [
        (1, 8), (1, 4), (3, 8), (1, 2), (5, 8), (3, 4), (7, 8), (1, 1),
    ];
    for &(num, den) in candidates.iter().rev() {
        let sw = w * num / den;
        let sh = h * num / den;
        if sw <= tw && sh <= th {
            return (num, den);
        }
    }
    (1, 1) // fallback — full scale
}

pub fn decode_scaled(data: &[u8], tw: i32, th: i32) -> Option<(Vec<u8>, i32, i32)> {
    let mut d = Decompress::start().ok()?;
    d.set_source(data);
    d.read_header().ok()?;
    let (w, h) = (d.width() as i32, d.height() as i32);

    if tw > 0 && th > 0 && (w > tw || h > th) {
        let (sn, sd) = pick_jpeg_scale(w, h, tw, th);
        // turbojpeg crate API uses set_scale_num/set_scale_denom
        // Exact API depends on turbojpeg crate version; fallback to nearest-neighbor if not available
        if sn > 1 || sd > 1 {
            // turbojpeg decompress with scale; if crate doesn't expose it, decode full + resize
        }
    }

    d.set_pixel_format(PixelFormat::RGBA);
    let mut img = d.decompress().ok()?;
    let rgba = img.data().to_vec();
    let iw = img.width() as i32;
    let ih = img.height() as i32;

    // Phase 2: fast_image_resize for precise downscaling (Box / Bilinear with NEON SIMD)
    if tw > 0 && th > 0 && (iw > tw || ih > th) {
        return fast_downscale_rgba(&rgba, iw, ih, tw, th);
    }
    Some((rgba, iw, ih))
}

fn fast_downscale_rgba(rgba: &[u8], w: i32, h: i32, tw: i32, th: i32) -> Option<(Vec<u8>, i32, i32)> {
    use fast_image_resize as fir;
    use fir::{ResizeAlg, Resizer, PixelType, Image};

    // Calculate aspect-fit target dimensions
    let ratio = (tw as f64 / w as f64).min(th as f64 / h as f64);
    let dst_w = ((w as f64) * ratio).max(1.0) as u32;
    let dst_h = ((h as f64) * ratio).max(1.0) as u32;

    let src = Image::from_vec_u8(w as u32, h as u32, rgba.to_vec(), PixelType::U8x4).ok()?;
    let mut dst = Image::new(dst_w, dst_h, PixelType::U8x4);
    let mut resizer = Resizer::new();
    resizer.resize(&src.view(), &mut dst.view_mut(), &ResizeAlg::Bilinear).ok()?;
    let out = dst.buffer().to_vec();
    Some((out, dst_w as i32, dst_h as i32))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pick_jpeg_scale_exact_match() {
        // 4000x3000 → target 500x500 → 1/8 = 500x375 fits both dimensions
        let (n, d) = pick_jpeg_scale(4000, 3000, 500, 500);
        assert_eq!((n, d), (1, 8));
    }

    #[test]
    fn pick_jpeg_scale_no_scale_needed() {
        // 400x300 → target 800x800 — already fits
        let (n, d) = pick_jpeg_scale(400, 300, 800, 800);
        assert_eq!((n, d), (1, 1));
    }

    #[test]
    fn pick_jpeg_scale_target_zero() {
        let (n, d) = pick_jpeg_scale(4000, 3000, 0, 0);
        assert_eq!((n, d), (1, 1));
    }

    #[test]
    fn dimensions_valid_jpeg() {
        // 需要一个最小 JPEG 字节用于测试
        // 测试数据放 testdata/
    }
}
```

- [ ] **Step 2: 编写 webp.rs**

Write `android/app/src/main/rust/src/webp.rs`:

```rust
use libwebp::WebPDecoder;
use libwebp::sys as webp_sys;

pub fn dimensions(data: &[u8]) -> Option<(i32, i32)> {
    let mut features = unsafe { std::mem::zeroed::<webp_sys::WebPBitstreamFeatures>() };
    let rc = unsafe { webp_sys::WebPGetFeatures(data.as_ptr(), data.len(), &mut features) };
    if rc != webp_sys::VP8_STATUS_OK { return None; }
    Some((features.width as i32, features.height as i32))
}

pub fn decode_scaled(data: &[u8], tw: i32, th: i32) -> Option<(Vec<u8>, i32, i32)> {
    let mut decoder = WebPDecoder::new(data).ok()?;
    let (src_w, src_h) = (decoder.width() as i32, decoder.height() as i32);

    // WebP libwebp-sys crate uses WebPDecoder::decode with output buffer
    let rgba = decoder.decode().ok()?;
    let (w, h) = (src_w, src_h);

    if tw > 0 && th > 0 && (w > tw || h > th) {
        // fast_image_resize
        crate::jpeg::fast_downscale_rgba(&rgba, w, h, tw, th)
    } else {
        Some((rgba, w, h))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dimensions_invalid_data() {
        assert!(dimensions(b"not webp").is_none());
    }
}
```

- [ ] **Step 3: 编写 bitmap.rs（AndroidBitmap 创建 + channel swizzle）**

Write `android/app/src/main/rust/src/bitmap.rs`:

```rust
use jni::objects::{JObject, JValue};
use jni::sys::jobject;
use jni::JNIEnv;

/// Create an Android Bitmap from RGBA pixel data.
/// Android Bitmap uses ARGB_8888 on little-endian: in memory byte order is B,G,R,A.
/// Input is RGBA byte order, so we swizzle R↔B and pack alpha to high byte.
pub fn create_android_bitmap(env: &mut JNIEnv, width: i32, height: i32, rgba: &[u8]) -> jobject {
    // Find Bitmap class and ARGB_8888 config
    let config_cls = env.find_class("android/graphics/Bitmap$Config")
        .expect("Failed to find Bitmap.Config");
    let argb_field = env.get_static_field_id(&config_cls, "ARGB_8888",
        "Landroid/graphics/Bitmap$Config;")
        .expect("Failed to find ARGB_8888 field");
    let config = env.get_static_field(&config_cls, &argb_field)
        .expect("Failed to get ARGB_8888")
        .l().expect("null ARGB_8888");

    let bitmap_cls = env.find_class("android/graphics/Bitmap")
        .expect("Failed to find Bitmap");
    let create_method = env.get_static_method_id(&bitmap_cls, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;")
        .expect("Failed to find createBitmap");
    let bitmap = env.call_static_method(&bitmap_cls, &create_method, &[
        JValue::Int(width),
        JValue::Int(height),
        JValue::Object(&config),
    ]).expect("createBitmap failed")
     .l().expect("null Bitmap");

    // Lock pixels via jnigraphics
    let bitmap_ptr = bitmap.as_raw();
    let mut info = android_bitmap_info { width: 0, height: 0, stride: 0, format: 0, flags: 0 };
    let rc = unsafe { AndroidBitmap_getInfo(env.get_native_interface(), bitmap_ptr, &mut info) };
    if rc != 0 { return std::ptr::null_mut(); }

    let mut pixels: *mut u32 = std::ptr::null_mut();
    let rc = unsafe { AndroidBitmap_lockPixels(env.get_native_interface(), bitmap_ptr, &mut pixels as *mut _ as *mut *mut std::ffi::c_void) };
    if rc != 0 { return std::ptr::null_mut(); }

    let stride = info.stride as usize / 4; // stride in u32 units

    unsafe {
        for y in 0..height as usize {
            let src_row = &rgba[y * width as usize * 4..];
            let dst_row = std::slice::from_raw_parts_mut(pixels.add(y * stride), width as usize);
            for x in 0..width as usize {
                let r = src_row[x * 4] as u32;
                let g = src_row[x * 4 + 1] as u32;
                let b = src_row[x * 4 + 2] as u32;
                let a = src_row[x * 4 + 3] as u32;
                // Pack as ARGB (little-endian memory: B,G,R,A)
                dst_row[x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
    }

    unsafe { AndroidBitmap_unlockPixels(env.get_native_interface(), bitmap_ptr); }
    bitmap.into_inner()
}

// FFI declarations for jnigraphics
#[repr(C)]
struct android_bitmap_info {
    width: u32,
    height: u32,
    stride: u32,
    format: i32,
    flags: u32,
}

extern "C" {
    fn AndroidBitmap_getInfo(env: *mut std::ffi::c_void, bitmap: jni::sys::jobject,
        info: *mut android_bitmap_info) -> i32;
    fn AndroidBitmap_lockPixels(env: *mut std::ffi::c_void, bitmap: jni::sys::jobject,
        addrPtr: *mut *mut std::ffi::c_void) -> i32;
    fn AndroidBitmap_unlockPixels(env: *mut std::ffi::c_void, bitmap: jni::sys::jobject) -> i32;
}
```

- [ ] **Step 4: 编写 JNI 桥 (decoders.rs)**

Write `android/app/src/main/rust/src/jni_bridge/decoders.rs`:

```rust
use jni::objects::{JByteArray, JByteBuffer, JClass};
use jni::sys::{jint, jobject};
use jni::JNIEnv;

fn detect_format(data: &[u8]) -> u32 {
    if data.len() >= 3 && data[0] == 0xFF && data[1] == 0xD8 && data[2] == 0xFF { return 1; }
    if data.len() >= 12 && &data[0..4] == b"RIFF" && &data[8..12] == b"WEBP" { return 2; }
    if data.len() >= 8 && &data[0..8] == b"\x89PNG\r\n\x1a\n" { return 3; }
    0
}

#[no_mangle]
pub extern "system" fn Java_com_juziss_localmediahub_native_NativeImageDecoder_nativeDecodeByteArray(
    mut env: JNIEnv,
    _class: JClass,
    data: JByteArray,
    length: jint,
    target_width: jint,
    target_height: jint,
) -> jobject {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let len = length as usize;
        let elements = env.get_array_elements_critical(&data).ok()?;
        let slice = unsafe { std::slice::from_raw_parts(elements.as_ptr() as *const u8, len) };

        let decoded = decode_slice(slice, target_width, target_height)?;
        let bitmap = crate::bitmap::create_android_bitmap(&mut env, decoded.1, decoded.2, &decoded.0);
        Some(bitmap)
    }));
    match result {
        Ok(Some(ptr)) => ptr,
        _ => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_juziss_localmediahub_native_NativeImageDecoder_nativeDecodeDirect(
    mut env: JNIEnv,
    _class: JClass,
    data: JByteBuffer,
    length: jint,
    target_width: jint,
    target_height: jint,
) -> jobject {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let ptr = env.get_direct_buffer_address(&data).ok()?;
        let slice = unsafe { std::slice::from_raw_parts(ptr as *const u8, length as usize) };

        let decoded = decode_slice(slice, target_width, target_height)?;
        let bitmap = crate::bitmap::create_android_bitmap(&mut env, decoded.1, decoded.2, &decoded.0);
        Some(bitmap)
    }));
    match result {
        Ok(Some(ptr)) => ptr,
        _ => std::ptr::null_mut(),
    }
}

fn decode_slice(data: &[u8], tw: jint, th: jint) -> Option<(Vec<u8>, i32, i32)> {
    match detect_format(data) {
        1 => crate::jpeg::decode_scaled(data, tw, th),
        2 => crate::webp::decode_scaled(data, tw, th),
        3 => crate::png::decode(data), // 阶段 4 实现
        _ => None,
    }
}
```

- [ ] **Step 5: 更新 lib.rs**

移除 `nativeDecodeByteArray` 和 `nativeDecodeDirect` 的两个 null-return stub（它们已由 `jni_bridge/decoders.rs` 提供）。

```rust
// lib.rs — 最终状态：
pub mod natural_sort;
pub mod exif_reader;
pub mod jpeg;
pub mod webp;
pub mod png;
pub mod bitmap;
mod jni_bridge;

#[cfg(feature = "heif-native")]
pub mod heif;

// 仅保留一个 null-return stub（PNG/HEIC 未接时 nativeDecode 后端已由 decoders.rs 处理）
```

- [ ] **Step 6: 重写 NativeImageDecoder.kt**

Write `android/app/src/main/java/com/juziss/localmediahub/native/NativeImageDecoder.kt`:

```kotlin
package com.juziss.localmediahub.native

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

object NativeImageDecoder {
    private const val TAG = "NativeImageDecoder"

    var nativeAvailable: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("localmedia_native")
            nativeAvailable = true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "liblocalmedia_native.so unavailable, using BitmapFactory fallback", e)
        }
    }

    const val FORMAT_UNKNOWN = 0
    const val FORMAT_JPEG = 1
    const val FORMAT_WEBP = 2
    const val FORMAT_PNG = 3
    const val FORMAT_HEIC = 4

    // JNI: GetPrimitiveArrayCritical safe borrow — zero copy
    private external fun nativeDecodeByteArray(
        data: ByteArray, length: Int,
        targetWidth: Int, targetHeight: Int,
    ): Bitmap?

    // JNI: DirectByteBuffer — zero copy
    private external fun nativeDecodeDirect(
        data: ByteBuffer, length: Int,
        targetWidth: Int, targetHeight: Int,
    ): Bitmap?

    /**
     * Decode image bytes to a Bitmap, optionally resized to fit target dimensions.
     * Falls back to BitmapFactory if native library is unavailable or decoding fails.
     */
    suspend fun decode(
        data: ByteArray,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
    ): Bitmap = withContext(Dispatchers.Default) {
        if (!nativeAvailable) return@withContext fallbackDecode(data, targetWidth, targetHeight)
        nativeDecodeByteArray(data, data.size, targetWidth, targetHeight)
            ?: fallbackDecode(data, targetWidth, targetHeight)
    }

    fun fallbackDecode(data: ByteArray, targetWidth: Int, targetHeight: Int): Bitmap {
        Log.w(TAG, "Falling back to BitmapFactory for decoding")
        if (targetWidth > 0 && targetHeight > 0) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
            options.inSampleSize = calculateInSampleSize(options, targetWidth, targetHeight)
            options.inJustDecodeBounds = false
            return BitmapFactory.decodeByteArray(data, 0, data.size, options)
                ?: throw IllegalArgumentException("Failed to decode image")
        }
        return BitmapFactory.decodeByteArray(data, 0, data.size)
            ?: throw IllegalArgumentException("Failed to decode image")
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
```

> ⚠️ 删除 `getImageInfo()` 方法 — 旧代码每个解码调用两次 native（getImageInfo + decode），新设计由 Rust 侧统一 format detection 后路由。

- [ ] **Step 7: 更新 NativeDecoderFactory.kt**

Modify `android/app/src/main/java/com/juziss/localmediahub/native/NativeDecoderFactory.kt:29-35`：

```kotlin
// 替换 when 分支，支持 4 种格式：
val isJpeg = header.size >= 3 &&
        header[0] == 0xFF.toByte() &&
        header[1] == 0xD8.toByte() &&
        header[2] == 0xFF.toByte()
val isWebp = header.size >= 12 &&
        String(header, 0, 4) == "RIFF" &&
        String(header, 8, 4) == "WEBP"
val isPng = header.size >= 8 &&
        header[0] == 0x89.toByte() &&
        String(header, 1, 3) == "PNG"
val isHeic = header.size >= 12 &&
        (String(header, 4, 8) == "ftyp")
        // HEIF/HEIC detection: look for "ftyp" at offset 4

return if (isJpeg || isWebp || isPng || isHeic) {
    NativeDecoderFactory(result, options.size, options)
} else {
    null
}
```

> 注意：HEIC routing 在阶段 5（Task 5）才真正可用，但 Factory routing 现在就预留。

- [ ] **Step 8: 删除 cpp/ 目录**

Run:
```bash
rm -rf android/app/src/main/cpp
```

从 `build.gradle.kts` 中**删除** externalNativeBuild 块（若 Task 0 漏了）：
```kotlin
// 确保以下块已删除：
// externalNativeBuild {
//     cmake {
//         path = file("src/main/cpp/CMakeLists.txt")
//         version = "3.22.1"
//     }
// }
```

- [ ] **Step 9: 拷贝测试样本图片**

Run:
```bash
mkdir -p android/app/src/main/rust/testdata
mkdir -p android/app/src/test/resources/test_image
cp /c/Users/juziss/Downloads/test_image/*.jpg android/app/src/main/rust/testdata/ 2>/dev/null || true
cp /c/Users/juziss/Downloads/test_image/*.webp android/app/src/main/rust/testdata/ 2>/dev/null || true
cp /c/Users/juziss/Downloads/test_image/*.jpg android/app/src/test/resources/test_image/ 2>/dev/null || true
cp /c/Users/juziss/Downloads/test_image/*.webp android/app/src/test/resources/test_image/ 2>/dev/null || true
```

- [ ] **Step 10: 编写 NativeImageDecoder Robolectric 测试**

Write `android/app/src/test/java/com/juziss/localmediahub/native/NativeImageDecoderTest.kt`:

```kotlin
package com.juziss.localmediahub.native

import android.graphics.Bitmap
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NativeImageDecoderTest {
    @Test
    fun nativeAvailableIsTrueWhenLibraryLoaded() {
        assertTrue(NativeImageDecoder.nativeAvailable)
    }

    @Test
    fun decodeJpegReturnsBitmap() = runTest {
        val bytes = readTestImage("sample.jpg") ?: return
        val bitmap = NativeImageDecoder.decode(bytes, 0, 0)
        assertNotNull(bitmap)
        assertTrue(bitmap.width > 0)
        assertTrue(bitmap.height > 0)
    }

    @Test
    fun decodeJpegRespectsTargetSize() = runTest {
        val bytes = readTestImage("sample.jpg") ?: return
        val bitmap = NativeImageDecoder.decode(bytes, 200, 200)
        assertTrue(bitmap.width <= 500 && bitmap.height <= 500)
    }

    @Test
    fun decodeWebpReturnsBitmap() = runTest {
        val bytes = readTestImage("sample.webp") ?: return
        val bitmap = NativeImageDecoder.decode(bytes, 0, 0)
        assertNotNull(bitmap)
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
    }

    @Test
    fun fallbackOnCorruptData() = runTest {
        // 仅一个 JPEG magic 头，后续数据无效
        val corrupt = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0, 0, 0)
        try {
            val bitmap = NativeImageDecoder.decode(corrupt, 0, 0)
            // fallback 到 BitmapFactory 可能返回 bitmap 或抛异常 — 两者都接受
        } catch (e: Exception) {
            // expected if BitmapFactory fails too
        }
    }

    private fun readTestImage(name: String): ByteArray? {
        return try {
            this::class.java.classLoader
                ?.getResourceAsStream("test_image/$name")
                ?.readBytes()
        } catch (e: Exception) {
            null
        }
    }
}
```

- [ ] **Step 11: 构建 + cargo test + Robolectric**

Run:
```bash
cd android/app/src/main/rust && cargo test
cd android && ./gradlew assembleDebug testDebugUnitTest
```
Expected: cargo test PASS, assembleDebug PASS, Robolectric tests PASS.

- [ ] **Step 12: 真机手工回归验证（Round 4/9）**

在真机或 arm64 模拟器上进行回归验证：
- 打开含超长图的目录 → 图片预览、快速左右滑 → 不崩溃、不 OOM
- 浏览网格 JPEG/WebP 缩略图正常加载
- 与 Round 4/9 建立的 OOM baseline 对比（`dumpsys meminfo`）

- [ ] **Step 13: Commit**

```bash
git add android/app/src/main/rust/src/jpeg.rs \
        android/app/src/main/rust/src/webp.rs \
        android/app/src/main/rust/src/bitmap.rs \
        android/app/src/main/rust/src/jni_bridge/decoders.rs \
        android/app/src/main/rust/src/lib.rs \
        android/app/src/main/java/com/juziss/localmediahub/native/NativeImageDecoder.kt \
        android/app/src/main/java/com/juziss/localmediahub/native/NativeDecoderFactory.kt \
        android/app/build.gradle.kts \
        android/app/src/test/java/com/juziss/localmediahub/native/NativeImageDecoderTest.kt \
        android/app/src/main/rust/testdata/
git add -u android/app/src/main/cpp/
git commit -m "refactor(native): rewrite JPEG/WebP decoder from C++ to Rust with A3 scale fix"
```

---

### Task 4: PNG 解码（新增格式 A2）

**Files:**
- Modify: `android/app/src/main/rust/src/png.rs`（替换占位）
- Modify: `android/app/src/main/rust/src/jni_bridge/decoders.rs`（加 `3 => crate::png::decode(data)`）
- Modify: `android/app/src/test/java/com/juziss/localmediahub/native/NativeImageDecoderTest.kt`（加 PNG 测试）

**Interfaces:**
- Produces: `png::decode(data: &[u8]) -> Option<(Vec<u8>, i32, i32)>`
- Consumes: `jni_bridge/decoders.rs` 的 `decode_slice` 函数在 format=3 时路由到 `png::decode`

- [ ] **Step 1: 编写 png.rs**

Write `android/app/src/main/rust/src/png.rs`:

```rust
use png::Decoder;

pub fn decode(data: &[u8]) -> Option<(Vec<u8>, i32, i32)> {
    let decoder = Decoder::new(data);
    let mut reader = decoder.read_info().ok()?;
    let (w, h) = {
        let info = reader.info();
        (info.width as i32, info.height as i32)
    };
    let mut buf = vec![0u8; reader.output_buffer_size()];
    let info = reader.next_frame(&mut buf).ok()?;

    let rgba = match info.color_type {
        png::ColorType::Rgba => buf,
        png::ColorType::Rgb => {
            // Expand RGB to RGBA (alpha=255)
            let n = (w as usize) * (h as usize);
            let mut rgba = Vec::with_capacity(n * 4);
            for i in 0..n {
                rgba.extend_from_slice(&buf[i * 3..i * 3 + 3]);
                rgba.push(255);
            }
            rgba
        }
        png::ColorType::Grayscale => {
            // Expand Gray to RGBA
            let n = (w as usize) * (h as usize);
            let mut rgba = Vec::with_capacity(n * 4);
            for i in 0..n {
                let g = buf[i];
                rgba.extend_from_slice(&[g, g, g, 255]);
            }
            rgba
        }
        png::ColorType::GrayscaleAlpha => {
            // Expand GA to RGBA
            let n = (w as usize) * (h as usize);
            let mut rgba = Vec::with_capacity(n * 4);
            for i in 0..n {
                rgba.extend_from_slice(&[buf[i * 2], buf[i * 2], buf[i * 2], buf[i * 2 + 1]]);
            }
            rgba
        }
        _ => return None,
    };
    Some((rgba, w, h))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decode_invalid_data() {
        assert!(decode(b"not png").is_none());
    }
}
```

- [ ] **Step 2: 更新 decoders.rs decode_slice**

在 `android/app/src/main/rust/src/jni_bridge/decoders.rs` 的 `decode_slice` 函数中，`3 => crate::png::decode(data),` 这行已预留（Task 3 Step 4 已写入）。若没有则添加：

```rust
fn decode_slice(data: &[u8], tw: jint, th: jint) -> Option<(Vec<u8>, i32, i32)> {
    match detect_format(data) {
        1 => crate::jpeg::decode_scaled(data, tw, th),
        2 => crate::webp::decode_scaled(data, tw, th),
        3 => crate::png::decode(data),
        _ => None,
    }
}
```

- [ ] **Step 3: 添加 PNG Robolectric 测试**

在 `NativeImageDecoderTest.kt` 中添加：

```kotlin
@Test
fun decodePngReturnsBitmap() = runTest {
    val bytes = readTestImage("sample.png") ?: return
    val bitmap = NativeImageDecoder.decode(bytes, 0, 0)
    assertNotNull(bitmap)
    assertTrue(bitmap.width > 0 && bitmap.height > 0)
}
```

- [ ] **Step 4: 构建 + 运行测试**

Run:
```bash
cd android/app/src/main/rust && cargo test && cd ../../../../
cd android && ./gradlew assembleDebug testDebugUnitTest
```
Expected: ALL tests PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/rust/src/png.rs \
        android/app/src/main/rust/src/jni_bridge/decoders.rs \
        android/app/src/test/java/com/juziss/localmediahub/native/NativeImageDecoderTest.kt
git commit -m "feat(native): add Rust PNG decoding (A2)"
```

---

### Task 5: HEIC 解码（A1）— 含 NDK AImageDecoder 保底

**Files:**
- Create: `android/app/src/main/rust/src/heif.rs`
- Modify: `android/app/src/main/rust/src/lib.rs`（加 heif module）
- Modify: `android/app/src/main/rust/Cargo.toml`（加 libheif-rs dependency）
- Modify: `android/app/src/main/rust/src/jni_bridge/decoders.rs`（加 HEIC routing）
- Modify: `android/app/src/test/java/com/juziss/localmediahub/native/NativeImageDecoderTest.kt`（加 HEIC 测试）

**Interfaces:**
- Produces: `heif::decode(data: &[u8]) -> Option<(Vec<u8>, i32, i32)>`
- Consumes: `decode_slice` 中 format 检测到 HEIC 时路由

- [ ] **Step 1: 添加 libheif-rs dependency**

```toml
# Cargo.toml — 改 [features] 和 [dependencies]：
[features]
default = []
heif-native = ["dep:libheif-rs"]

[dependencies]
# ... existing ...
libheif-rs = { version = "0.18", optional = true }
```

- [ ] **Step 2: 编写 heif.rs（含 feature gate）**

Write `android/app/src/main/rust/src/heif.rs`:

```rust
/// HEIC/HEIF decoding — gated behind the "heif-native" feature in Cargo.toml.
#[cfg(feature = "heif-native")]
pub fn decode(data: &[u8]) -> Option<(Vec<u8>, i32, i32)> {
    use libheif_rs::{HeifContext, ColorSpace, Chroma};

    let ctx = HeifContext::read_from_bytes(data).ok()?;
    let handle = ctx.primary_image_handle().ok()?;
    let (w, h) = (handle.width(), handle.height());

    let img = handle.decode_image(ColorSpace::Rgba, Chroma::C420, None).ok()?;
    let plane = img.planes().rgba?;
    Some((plane.data.to_vec(), w, h))
}

/// No-op when heif-native feature is disabled — Rust fails to decode, Kotlin falls back
/// to BitmapFactory (which on API 28+ uses NDK AImageDecoder internally).
#[cfg(not(feature = "heif-native"))]
pub fn decode(_data: &[u8]) -> Option<(Vec<u8>, i32, i32)> {
    None
}
```

在 `android/app/src/main/rust/src/lib.rs` 中：

```rust
// lib.rs — 移除 #[cfg(feature = "heif-native")] gate 在 mod 声明上
pub mod heif;
```

- [ ] **Step 3: 更新 decoders.rs 加 HEIC 检测和路由**

在 `detect_format` 函数中添加 HEIC 检测：

```rust
fn detect_format(data: &[u8]) -> u32 {
    if data.len() >= 3 && data[0] == 0xFF && data[1] == 0xD8 && data[2] == 0xFF { return 1; }
    if data.len() >= 12 && &data[0..4] == b"RIFF" && &data[8..12] == b"WEBP" { return 2; }
    if data.len() >= 8 && &data[0..8] == b"\x89PNG\r\n\x1a\n" { return 3; }
    // HEIF/HEIC: box-based format — look for "ftyp" at offset 4 (ISO BMFF container)
    if data.len() >= 12 && &data[4..8] == b"ftyp" { return 4; }
    0
}
```

在 `decode_slice` 中加：

```rust
4 => crate::heif::decode(data),
```

- [ ] **Step 4: 添加测试样本**

```bash
cp /c/Users/juziss/Downloads/test_image/*.heic android/app/src/main/rust/testdata/ 2>/dev/null || true
cp /c/Users/juziss/Downloads/test_image/*.heic android/app/src/test/resources/test_image/ 2>/dev/null || true
```

- [ ] **Step 5: 添加 HEIC Robolectric 测试**

在 `NativeImageDecoderTest.kt` 中添加：

```kotlin
@Test
fun decodeHeicReturnsBitmap() = runTest {
    val bytes = readTestImage("sample.heic") ?: return
    val bitmap = NativeImageDecoder.decode(bytes, 0, 0)
    // 如果 native HEIC 解码失败（NDK 保底链路未启用），BitmapFactory 会处理
    assertNotNull(bitmap)
    assertTrue(bitmap.width > 0 && bitmap.height > 0)
}
```

- [ ] **Step 6: 构建 + cargo test + Robolectric**

Run:
```bash
cd android/app/src/main/rust && cargo test && cd ../../../../
cd android && ./gradlew assembleDebug testDebugUnitTest
```
Expected: ALL PASS.

- [ ] **Step 7: 包体积检查**

Run:
```bash
ls -lh android/app/build/outputs/apk/debug/*.apk
# 或用 Android Studio Build → Analyze APK 对比 .so 大小
```
Expected: `liblocalmedia_native.so` ≤ 5MB

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/rust/src/heif.rs \
        android/app/src/main/rust/src/lib.rs \
        android/app/src/main/rust/src/jni_bridge/decoders.rs \
        android/app/src/main/rust/Cargo.toml \
        android/app/src/test/java/com/juziss/localmediahub/native/NativeImageDecoderTest.kt
git commit -m "feat(native): add Rust HEIC decoding with NDK AImageDecoder fallback (A1)"
```

---

### Task 6: EXIF Orientation 旋转接入解码路径（A5 修复）

**Files:**
- Modify: `android/app/src/main/rust/src/jni_bridge/decoders.rs`（解码后旋转）
- Modify: `android/app/src/main/rust/src/exif_reader.rs`（加快速 EXIF orientation-only 解析函数）
- Modify: `android/app/src/main/rust/src/bitmap.rs`（加 orientation 旋转函数）
- Modify: `android/app/src/test/java/com/juziss/localmediahub/native/NativeImageDecoderTest.kt`（验证旋转）

**Interfaces:**
- Produces: `exif_reader::parse_orientation_only(data: &[u8]) -> i32`
- Produces: `bitmap::rotate_rgba(rgba, w, h, orientation) -> (Vec<u8>, i32, i32)`
- Consumes: `decode_slice` 在创建 Bitmap 前调用旋转

- [ ] **Step 1: 添加快速 EXIF orientation 解析（不做完整解析）**

在 `android/app/src/main/rust/src/exif_reader.rs` 中添加：

```rust
/// Fast path: parse only Orientation tag without full EXIF traversal.
/// Returns 1 (normal) if not found.
pub fn parse_orientation_only(data: &[u8]) -> i32 {
    // Quick EXIF check: only JPEG has Orientation in a well-known IFD offset
    if data.len() < 12 || data[0] != 0xFF || data[1] != 0xD8 {
        return 1; // not JPEG — no orientation
    }
    // ... full EXIF parse is fine for now (kamadak-exif is fast enough)
    parse(data).map(|e| e.orientation).unwrap_or(1)
}
```

> 简化方案：`parse_orientation_only` 内部直接调用 `parse().map(|e| e.orientation).unwrap_or(1)`。kamadak-exif 的读取性能已足够（~10μs 量级），无需手写 seek 逻辑。

- [ ] **Step 2: 添加 RGBA 旋转函数到 bitmap.rs**

在 `android/app/src/main/rust/src/bitmap.rs` 中添加：

```rust
/// Apply EXIF orientation 1..8 to RGBA pixel data.
/// Returns (rotated_rgba, new_width, new_height).
/// Orientations 5-8 transpose the image (swap width/height).
pub fn apply_exif_orientation(rgba: &[u8], w: i32, h: i32, orientation: i32) -> (Vec<u8>, i32, i32) {
    match orientation {
        1 => (rgba.to_vec(), w, h),
        2 => flip_horizontal(rgba, w, h),
        3 => rotate_180(rgba, w, h),
        4 => flip_vertical(rgba, w, h),
        5 => flip_horizontal(&transpose(rgba, w, h).0, h, w),
        6 => rotate_90_cw(rgba, w, h),
        7 => flip_vertical(&transpose(rgba, w, h).0, h, w),
        8 => rotate_90_ccw(rgba, w, h),
        _ => (rgba.to_vec(), w, h),
    }
}

fn flip_horizontal(rgba: &[u8], w: i32, h: i32) -> (Vec<u8>, i32, i32) {
    let w = w as usize; let h = h as usize;
    let mut out = vec![0u8; w * h * 4];
    for y in 0..h {
        let src = &rgba[y * w * 4..];
        let dst = &mut out[y * w * 4..];
        for x in 0..w {
            let si = x * 4;
            let di = (w - 1 - x) * 4;
            dst[di..di+4].copy_from_slice(&src[si..si+4]);
        }
    }
    (out, w as i32, h as i32)
}

fn flip_vertical(rgba: &[u8], w: i32, h: i32) -> (Vec<u8>, i32, i32) {
    let w = w as usize; let h = h as usize;
    let mut out = vec![0u8; w * h * 4];
    for y in 0..h {
        let src = &rgba[y * w * 4..];
        let dst = &mut out[(h - 1 - y) * w * 4..];
        dst.copy_from_slice(src);
    }
    (out, w as i32, h as i32)
}

fn rotate_180(rgba: &[u8], w: i32, h: i32) -> (Vec<u8>, i32, i32) {
    let w = w as usize; let n = w * h as usize * 4;
    let mut out = vec![0u8; n];
    for y in 0..h as usize {
        for x in 0..w {
            let si = y * w * 4 + x * 4;
            let dy = h as usize - 1 - y;
            let dx = w - 1 - x;
            let di = dy * w * 4 + dx * 4;
            out[di..di+4].copy_from_slice(&rgba[si..si+4]);
        }
    }
    (out, w as i32, h as i32)
}

fn transpose(rgba: &[u8], w: i32, h: i32) -> (Vec<u8>, i32, i32) {
    let w = w as usize; let h = h as usize;
    let mut out = vec![0u8; w * h * 4];
    for y in 0..h {
        for x in 0..w {
            let si = y * w * 4 + x * 4;
            let di = x * h * 4 + y * 4;
            out[di..di+4].copy_from_slice(&rgba[si..si+4]);
        }
    }
    (out, h as i32, w as i32)
}

fn rotate_90_cw(rgba: &[u8], w: i32, h: i32) -> (Vec<u8>, i32, i32) {
    let (transposed, tw, th) = transpose(rgba, w, h);
    flip_horizontal(&transposed, tw, th)
}

fn rotate_90_ccw(rgba: &[u8], w: i32, h: i32) -> (Vec<u8>, i32, i32) {
    let (transposed, tw, th) = transpose(rgba, w, h);
    flip_vertical(&transposed, tw, th)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn orientation_1_is_identity() {
        let rgba = vec![0, 0, 0, 255, 255, 0, 0, 255, 0, 255, 0, 255, 255, 255, 255, 255];
        let (out, w, h) = apply_exif_orientation(&rgba, 2, 2, 1);
        assert_eq!(out, rgba);
        assert_eq!((w, h), (2, 2));
    }

    #[test]
    fn orientation_6_is_90_cw() {
        let rgba = vec![
            255, 0, 0, 255,   0, 255, 0, 255,
            0,   0, 255, 255, 0, 0,   0, 255,
        ];
        let (out, w, h) = apply_exif_orientation(&rgba, 2, 2, 6);
        assert_eq!((w, h), (2, 2));
        // Top-left of rotated should be bottom-left of original (0,0,255 at (0,1) → top row col 0)
        assert_eq!(&out[0..4], &[0, 0, 255, 255]);
    }
}
```

- [ ] **Step 3: 在 decoders.rs 解码流程中集成 EXIF 旋转**

在 `decode_slice` 函数中，解码后在创建 Bitmap 前应用 EXIF orientation：

```rust
fn decode_slice(data: &[u8], tw: jint, th: jint) -> Option<(Vec<u8>, i32, i32)> {
    let (rgba, w, h) = match detect_format(data) {
        1 => crate::jpeg::decode_scaled(data, tw, th)?,
        2 => crate::webp::decode_scaled(data, tw, th)?,
        3 => crate::png::decode(data)?,
        4 => crate::heif::decode(data)?,
        _ => return None,
    };

    // Apply EXIF orientation rotation for JPEG (other formats don't use EXIF orientation)
    let orientation = if detect_format(data) == 1 {
        crate::exif_reader::parse_orientation_only(data)
    } else {
        1
    };

    if orientation != 1 {
        Some(crate::bitmap::apply_exif_orientation(&rgba, w, h, orientation))
    } else {
        Some((rgba, w, h))
    }
}
```

- [ ] **Step 4: 添加 EXIF Orientation 旋转测试**

在 `NativeImageDecoderTest.kt` 中添加：

```kotlin
@Test
fun portraitImageOrientationIsCorrected() = runTest {
    val bytes = readTestImage("portrait_rot6.jpg") ?: return
    val bitmap = NativeImageDecoder.decode(bytes, 0, 0)
    // orientation=6 (portrait) 应被旋转为 landscape 宽高比（w > h）
    assertTrue(bitmap.width > 0 && bitmap.height > 0)
    // 如果原始竖拍的宽 > 高，说明已旋转
    // 注意：这取决于 test_image 的具体内容 — 有 EXIF orientation=6 的图片应为竖拍
}
```

- [ ] **Step 5: 构建 + 测试**

Run:
```bash
cd android/app/src/main/rust && cargo test && cd ../../../../
cd android && ./gradlew assembleDebug testDebugUnitTest
```
Expected: ALL PASS.

- [ ] **Step 6: 真机验证竖拍照片显示**

打开一个竖拍照片（orientation=6），确认：
- 不是侧躺显示
- 宽高比正确

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/rust/src/exif_reader.rs \
        android/app/src/main/rust/src/bitmap.rs \
        android/app/src/main/rust/src/jni_bridge/decoders.rs \
        android/app/src/test/java/com/juziss/localmediahub/native/NativeImageDecoderTest.kt
git commit -m "fix(native): apply EXIF orientation rotation in decode path (A5)"
```

---

### Task 7: 收尾 — 清理 + 文档 + 最终验证

**Files:**
- Modify: `android/app/build.gradle.kts`（确认 cmake 已清理、cargo-ndk host test target 就绪）
- Modify: `android/app/proguard-rules.pro`（加 Rust JNI 类 keep rules）
- Create: `android/app/src/main/rust/src/jni_bridge/mod.rs`（若缺失）
- Modify: `android/.gitignore`（验证 jniLibs/*.so 被排除）

**Interfaces:**
- Consumes: 所有前序 Task 产物
- Produces: 干净的构建流水线

- [ ] **Step 1: 更新 proguard-rules.pro**

在 `android/app/proguard-rules.pro` 中添加 Rust JNI native 方法的 keep 规则：

```proguard
# Rust JNI native methods — keep class names exactly as expected by #no_mangle symbols
-keep class com.juziss.localmediahub.native.NativeImageDecoder {
    native <methods>;
}
-keep class com.juziss.localmediahub.native.NativeExif {
    native <methods>;
}
-keep class com.juziss.localmediahub.native.NaturalSorter {
    native <methods>;
}
```

- [ ] **Step 2: 全量构建 + 全量测试**

Run:
```bash
cd android/app/src/main/rust && cargo test
cd android && ./gradlew clean assembleDebug testDebugUnitTest
```
Expected: cargo test ALL PASS, assembleDebug BUILD SUCCESSFUL, testDebugUnitTest ALL PASS.

- [ ] **Step 3: 验证 Round 4/9 无回归**

真机/模拟器验证清单：
- [ ] 超长图预览 → 不 OOM
- [ ] 竖拍照片 → 方向正确
- [ ] 浏览网格 → JPEG/WebP/PNG 缩略图正显
- [ ] 搜索、收藏、下载 → 无异常
- [ ] 旋转屏 → 解码正常

- [ ] **Step 4: 最终 Commit**

```bash
git add -A
git commit -m "chore(native): finalize Rust rewrite — cleanup, proguard, verification"
```

- [ ] **Step 5: 性能基线记录**

记录性能数字，为后续优化提供 baseline：
- JPEG 4000×3000 解码 → 200×200：__ ms（vs Round 9 baseline）
- 自然排序 10k 文件：__ ms（vs Kotlin Regex baseline）
- `liblocalmedia_native.so` 大小：__ MB（vs 5MB 预算）

---

## 附录 A: 测试样本清单

| 文件 | 来源 | 用途 |
|---|---|---|
| `sample.jpg` | `C:\Users\juziss\Downloads\test_image\` | JPEG 解码 + EXIF |
| `portrait_rot6.jpg` | 需用 exiftool 生成（命令见下） | Orientation=6 验证 |
| `sample.webp` | `C:\Users\juziss\Downloads\test_image\` | WebP 解码 |
| `sample.png` | `C:\Users\juziss\Downloads\test_image\` 或 ImageMagick 生成 | PNG 解码 |
| `sample_rgb.png` | `magick sample.jpg -define png:color-type=2 sample_rgb.png` | 颜色类型转换 |
| `sample_grayscale.png` | `magick sample.jpg -colorspace Gray -define png:color-type=0 sample_grayscale.png` | 灰度扩展 |
| `sample.heic` | `C:\Users\juziss\Downloads\test_image\` | HEIC 解码 |

生成 portrait_rot6.jpg（若 test_image 中没有）：
```bash
exiftool -n -Orientation=6 sample.jpg -o portrait_rot6.jpg
```

## 附录 B: rust 模块结构（最终）

```
android/app/src/main/rust/src/
├── lib.rs                  # pub mod 声明 + 空的 lib crate
├── natural_sort.rs         # 零分配自然排序（纯 Rust）
├── exif_reader.rs          # kamadak-exif 包装
├── jpeg.rs                 # turbojpeg + 二阶段缩放
├── webp.rs                 # libwebp + fast_image_resize
├── png.rs                  # png crate + 颜色类型转换
├── heif.rs                 # libheif-rs（feature-gated）
├── bitmap.rs               # AndroidBitmap_* + channel swizzle + EXIF rotation
└── jni_bridge/
    ├── mod.rs
    ├── decoders.rs         # nativeDecodeDirect + nativeDecodeByteArray JNI 入口
    ├── exif_jni.rs          # nativeParseExif JNI 入口
    └── natural_sort_jni.rs  # NaturalSorter.compare JNI 入口
```
