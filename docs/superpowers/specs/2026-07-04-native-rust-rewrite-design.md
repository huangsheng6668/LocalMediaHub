# Native 层 Rust 重写设计（Round 11）

- **日期**: 2026-07-04
- **范围**: Android 客户端 native 层（删除现有 C++ JPEG/WebP 解码器，重写为单一 Rust crate；新增 PNG/HEIC 解码、EXIF 解析、自然排序）
- **策略**: 100% Rust 重写，单 `.so`，arm64-v8a 单 ABI
- **状态**: 待评审
- **前置**: Round 9（C++ native-image-decoder 已落地）；Round 4（OOM 解码上限、Coil 缓存调优）；本轮重写需重跑 Round 4/9 验证

---

## 1. 背景与动机

Round 9 已用 C++ 实现了 JPEG/WebP 解码（`android/app/src/main/cpp/jni/native_image_decoder.cpp`），但仍存在以下缺口：

- **A3 — target size bug**：`NativeImageDecoder.decode(data, targetWidth, targetHeight)` 的 `targetWidth/targetHeight` 参数传到 native 后**被忽略**，导致超长图全分辨率解码（与 Round 4 OOM 修复目标相悖）。
- **A1 — HEIC 无支持**：现代 Android 相机主流格式，目前 fallback 到 BitmapFactory（Android 9+ 系统支持但不稳，老设备完全不支持）。
- **A2 — PNG 走 BitmapFactory**：虽系统 libpng 已优化，但与其他格式路径不一致。
- **A5 — EXIF 解析缺失**：`grep` 全代码库 0 命中 `ExifInterface`/EXIF；竖拍照片显示方向可能错误（这是 bug，不只是性能）。
- **B1 — 自然排序 Regex 实现**：`BrowseSorter.kt:14` 的 `compareNatural` 用 `Regex.findAll` + `toIntOrNull`，对每对元素创建临时 List，万级目录可见。
- **架构不一致**：C++ 栈 + 未来若扩展（HEIC、EXIF、排序）需 C++ 工具链，与 Rust 生态相比维护门槛更高。

### 1.1 排除的"五大类"中的两类

代码扫描显示：
- **加密/哈希/压缩**：应用无相关功能（`grep` 显示 0 命中 `MessageDigest`/AES/GZIP）。
- **视频转码/抽帧**：应用只 stream + 播放，从不转码；缩略图由服务端 ffmpeg 生成。

这两类本轮明确不做（YAGNI）。

### 1.2 重写而非并行扩展的理由

用户决策：100% Rust 重写，单 `.so`，删除现有 C++。理由：
- 统一技术栈、降低维护门槛（C++ 的 setjmp/longjmp、JNI 错误处理 → Rust `Result`）。
- Rust 生态成熟（turbojpeg、libwebp、libheif-rs、kamadak-exif、png）。
- 内存安全，未来加新格式时无 buffer overflow 类回归。
- 接受 Round 9 成果重跑验证的成本。

---

## 2. 目标与非目标

### 目标
1. **Rust crate 骨架**：`android/app/src/main/rust/` 下单一 crate，cargo-ndk 集成 Gradle `preBuild`，产出 `liblocalmedia_native.so`（arm64-v8a）。
2. **JPEG 解码（重写）**：turbojpeg crate，**修复 A3 target size bug**（用 `scale_num`/`scale_denom` 粗缩 + `fast_image_resize` SIMD 滤波精准下采样）。
3. **WebP 解码（重写）**：libwebp crate，配合 `fast_image_resize` 高品质下采样（避免 nearest-neighbor 锯齿走样）。
4. **PNG 解码（新增）**：png crate，支持 RGB/RGBA/Gray/GA 颜色类型转换与高质量下采样。
5. **HEIC 解码（新增）**：`libheif-rs` + libde265 后端，并包含 Android 9+ (API 28+) NDK `AImageDecoder` 备选保底链路（防止 C++ 依赖构建超时或体积超标）。
6. **EXIF 解析（新增）**：kamadak-exif，返回 Orientation/DateTimeOriginal/Make/Model。
7. **EXIF Orientation 应用（A5 修复）**：竖拍照片显示方向正确（Rust 侧全形态 1..8 矩阵旋转 RGBA buffer 并转置宽高，避免 Kotlin 多次跨边界）。
8. **自然排序（新增 + 透明替换）**：纯 Rust 实现（零堆内存分配），`BrowseSorter.compareNatural` 内部调用，签名不变。
9. **删除现有 C++**：`android/app/src/main/cpp/` 整目录删除。
10. **测试覆盖**：cargo test + Robolectric（跨平台 Host 原生库 .dll/.so/.dylib）+ instrumented test（发版前）。

### 非目标（明确排除）
- 视频转码、抽帧（无场景）。
- 加密、哈希、压缩（无场景）。
- GIF/APNG/AVIF/JXL（YAGNI，按需后续轮次）。
- Animated WebP（复杂度高、收益低）。
- 服务端任何改动（本轮纯客户端）。
- Compose UI 改动（除路由接入外）。
- Coil 升级 / 缓存调优（Round 4 已做）。

---

## 3. 架构与文件组织

### 3.1 整体架构

```
android/app/
├── build.gradle.kts (改)          # 加 cargo-ndk task hook 到 preBuild，支持跨平台 host 原生库拷贝
├── src/main/
│   ├── cpp/                        # 删除整个目录
│   ├── jniLibs/                    # 新增（cargo-ndk 产物落点，gitignored）
│   │   └── arm64-v8a/liblocalmedia_native.so
│   ├── rust/                       # 新增 crate
│   │   ├── Cargo.toml
│   │   └── src/
│   │       ├── lib.rs              # #no_mangle extern "system" JNI 入口
│   │       ├── jni/
│   │       │   ├── mod.rs
│   │       │   ├── decoders.rs     # JPEG/WebP/PNG/HEIC JNI 函数
│   │       │   ├── exif.rs         # EXIF JNI 函数
│   │       │   └── natural_sort.rs # 自然排序 JNI 函数
│   │       ├── jpeg.rs             # turbojpeg + 二阶段下采样
│   │       ├── webp.rs             # libwebp 包装
│   │       ├── png.rs              # png crate 包装
│   │       ├── heif.rs             # libheif-rs (或 NDK AImageDecoder 降级) 包装
│   │       ├── exif.rs             # kamadak-exif 包装
│   │       ├── natural_sort.rs     # 零分配纯 Rust 自然排序
│   │       └── bitmap.rs           # AndroidBitmap_* 创建/矩阵旋转与宽高转置
│   └── java/com/juziss/localmediahub/native/
│       ├── NativeImageDecoder.kt       # 改：重写 native 函数签名，支持 DirectBuffer/ByteArray
│       ├── NativeDecoderFactory.kt     # 改：路由加 HEIC/PNG
│       ├── NativeExif.kt               # 新增
│       └── NaturalSorter.kt            # 新增
```

### 3.2 关键约束

| 项 | 决策 |
|---|---|
| **ABI** | 仅 `arm64-v8a` |
| **`.so` 数量** | 1 个：`liblocalmedia_native.so` |
| **构建工具** | cargo-ndk，Gradle `preBuild` hook |
| **JPEG** | `turbojpeg` + `turbojpeg-sys`（libjpeg-turbo 绑定，Cargo build script 从源码编译） |
| **WebP** | `libwebp` + `libwebp-sys`（源码编译） |
| **PNG** | `png` crate（纯 Rust） |
| **HEIC** | `libheif-rs` + libde265，Android 9+ (API 28+) 设备提供 NDK `AImageDecoder` 零体积退化保底 |
| **EXIF** | `kamadak-exif`（纯 Rust） |
| **自然排序** | 零堆分配纯 Rust 实现 |
| **缩放 (A3)** | `libjpeg-turbo` 粗缩 + `fast_image_resize` (SIMD NEON Box/Bilinear) 精准防走样缩放 |
| **色彩格式** | 统一 PixelFormat::RGBA，精准匹配 Android `Bitmap.Config.ARGB_8888` Native 字节序 |
| **测试** | cargo test + Robolectric (跨平台主机动态库) + instrumented test |

### 3.3 包体积预算（arm64-v8a 单 ABI）

| 组件 | 估算 |
|---|---|
| Rust runtime（std + panic-unwind） | ~500KB |
| libjpeg-turbo | ~700KB |
| libwebp（decoder + demux） | ~400KB |
| libheif + libde265 (或 NDK 系统降级 0KB) | ~0KB ~ 2.8MB |
| png crate + fast_image_resize | ~250KB |
| 其他 | ~200KB |
| **总计增量** | **~2.05MB ~ 4.85MB**（单 ABI，严格控制在 ≤ 5MB 预算内） |

---

## 4. 数据流与 JNI 边界

### 4.1 整体数据流

```
┌──────────────────────────────────────────────────────────────┐
│  Kotlin (Compose / ViewModel / Repository)                   │
│    │                                                          │
│    ▼  suspend + Dispatchers.Default                          │
│  NativeImageDecoder.decode(bytes, w, h): Bitmap              │
│  NativeExif.parse(bytes): ExifInfo?                          │
│  NaturalSorter.compare(a, b): Int                            │
└──────────────────────────────────────────────────────────────┘
                              │  JNI 边界（ByteArray / DirectByteBuffer）
                              ▼
┌──────────────────────────────────────────────────────────────┐
│  Rust  liblocalmedia_native.so                               │
│    ├── jni::decoders  → jpeg/webp/png/heif 模块              │
│    ├── jni::exif      → exif 模块                            │
│    └── jni::natural_sort → natural_sort 模块                 │
└──────────────────────────────────────────────────────────────┘
```

### 4.2 JNI 边界与内存设计原则

| 原则 | 实现 |
|---|---|
| **避免 Double Allocation** | 1. 采用 `DirectByteBuffer`（零拷贝通道）：Kotlin 侧通过 `ByteBuffer.allocateDirect` 复用池或从 Coil Source/FileChannel.map 获取，Native 使用 `JNIEnv::get_direct_buffer_address`。<br>2. 兼容 `jbyteArray`（安全 Pin）：对于直接传入 `ByteArray` 场景，Native 优先使用 `env.get_array_elements_critical` 进行 Safe 借用，彻底消除“先在 Kotlin 堆分配、又在 Native 堆分配”的双倍内存峰值。 |
| **小数据用 jbyteArray / IntArray** | EXIF 元数据返回用 IntArray/Primitive Array（≤32B），开销可忽略。 |
| **位图回传与字节序匹配** | Rust 侧创建 `android.graphics.Bitmap`（`AndroidBitmap_*` API from `jnigraphics`），统一使用 `PixelFormat::RGBA` 匹配 Android `Bitmap.Config.ARGB_8888` 内存字节序（Little Endian RGBA 顺次填充）。⚠️ **现有 C++ 代码有手动 RGBA→ARGB 逐像素拷贝**（`native_image_decoder.cpp`），Rust 侧需验证 `AndroidBitmap_lockPixels` 返回的 native buffer 字节序是否与 RGBA 一致，若不一致需做 channel swizzle。 |
| **错误传播与异常防护** | 每一个 JNI 入口均以 `catch_unwind` 包装；失败返回 null（Kotlin 侧透明 fallback 到 BitmapFactory）。 |
| **线程规范** | 所有 native 调用必须在 `Dispatchers.Default` 协程调度器执行。 |

### 4.3 关键 JNI 函数签名（Kotlin ↔ Rust）

**NativeImageDecoder.kt**（改写后）：

```kotlin
object NativeImageDecoder {
    // ⚠️ 现有代码无 nativeAvailable 标志（loadLibrary 裸调用，失败直接崩溃）。
    // 本轮必须新增此守卫，改为优雅 fallback。
    var nativeAvailable: Boolean = false
        private set

    init {
        try {
            // ⚠️ 库名迁移：现有 C++ 库名为 "native-image-decoder"，
            // Rust 重写后改为 "localmedia_native"。
            // NativeDecoderFactory 等调用方无需改动（loadLibrary 仅在此处调用一次）。
            System.loadLibrary("localmedia_native")
            nativeAvailable = true
        } catch (e: UnsatisfiedLinkError) {
            // nativeAvailable 保持 false，decode() 直接走 fallbackDecode
        }
    }

    const val FORMAT_UNKNOWN = 0
    const val FORMAT_JPEG = 1
    const val FORMAT_WEBP = 2
    const val FORMAT_PNG = 3
    const val FORMAT_HEIC = 4

    // 直接从 ByteBuffer 解码 (position 敏感)
    private external fun nativeDecodeDirect(
        data: ByteBuffer, length: Int,
        targetWidth: Int, targetHeight: Int,
    ): Bitmap?

    // 零拷贝 ByteArray 解码 (GetPrimitiveArrayCritical)
    private external fun nativeDecodeByteArray(
        data: ByteArray, length: Int,
        targetWidth: Int, targetHeight: Int,
    ): Bitmap?

    suspend fun decode(data: ByteArray, targetWidth: Int = 0, targetHeight: Int = 0): Bitmap =
        withContext(Dispatchers.Default) {
            if (!nativeAvailable) return@withContext fallbackDecode(data, targetWidth, targetHeight)
            nativeDecodeByteArray(data, data.size, targetWidth, targetHeight)
                ?: fallbackDecode(data, targetWidth, targetHeight)
        }

    suspend fun decodeBuffer(buf: ByteBuffer, targetWidth: Int = 0, targetHeight: Int = 0): Bitmap? =
        withContext(Dispatchers.Default) {
            val length = buf.remaining()
            nativeDecodeDirect(buf, length, targetWidth, targetHeight)
        }
}
```

> **关键优化：** 
> 1. 提供 `nativeDecodeByteArray`（Critical Native Array 借用）与 `nativeDecodeDirect` 双入口，消除了在 Kotlin 侧把 ByteArray `put` 到 DirectBuffer 的额外分配与翻转开销！
> 2. 统一路由由 Rust 侧探测 Format 签名，零冗余跨界调用。

**NativeExif.kt**（新增）：

```kotlin
data class ExifInfo(
    val orientation: Int,        // 1..8（EXIF Orientation tag）
    val dateTimeOriginal: String?,
    val make: String?,
    val model: String?,
)

object NativeExif {
    // 复用 NativeImageDecoder 的 loadLibrary（同一个 .so）
    private val available get() = NativeImageDecoder.nativeAvailable

    private external fun nativeParseExif(data: ByteArray, length: Int): ExifInfo?

    suspend fun parse(data: ByteArray): ExifInfo? = withContext(Dispatchers.Default) {
        if (!available) return@withContext null
        nativeParseExif(data, data.size)
    }
}
```

**NaturalSorter.kt**（新增）：

```kotlin
object NaturalSorter {
    /** 与 BrowseSorter.compareNatural 语义完全一致，纯 Rust 零内存堆分配 */
    external fun compare(a: String, b: String): Int
}
```

### 4.4 Rust 侧 JNI 入口骨架

```rust
// src/jni/decoders.rs
use jni::objects::{JByteArray, JByteBuffer, JClass, JObject};
use jni::sys::{jint, jobject};
use jni::JNIEnv;
use std::panic::catch_unwind;

#[no_mangle]
pub extern "system" fn Java_com_juziss_localmediahub_native_NativeImageDecoder_nativeDecodeByteArray(
    mut env: JNIEnv,
    _class: JClass,
    data: JByteArray,
    length: jint,
    target_width: jint,
    target_height: jint,
) -> jobject {
    // 注意：catch_unwind 不能直接捕获 &mut env（非 UnwindSafe）。
    // 实际实现中使用 AssertUnwindSafe 包装，或将 panic=abort 用于 release 构建。
    // 此处伪代码展示逻辑流：
    let inner = move || -> jobject {
        // GetPrimitiveArrayCritical 注意事项：
        // 1. critical section 内禁止调用任何 JNI 函数（包括 NewObject、FindClass 等）
        // 2. 因此必须先拷贝数据到 Rust 堆，释放 critical section，再进行解码和 Bitmap 创建
        let slice = {
            let elements = env.get_primitive_array_critical(&data, jni::objects::ReleaseMode::NoCopyBack).ok();
            match elements {
                Some(e) => {
                    let ptr = e.as_ptr() as *const u8;
                    let s = unsafe { std::slice::from_raw_parts(ptr, length as usize) };
                    s.to_vec()  // 拷贝到 Rust 堆，然后 elements 被 drop → 释放 critical section
                }
                None => return std::ptr::null_mut(),
            }
        };
        // critical section 已释放，以下可安全调用 JNI

        let format = detect_format(&slice);
        let decoded = match format {
            Format::Jpeg => crate::jpeg::decode_scaled(&slice, target_width, target_height),
            Format::Webp => crate::webp::decode_scaled(&slice, target_width, target_height),
            Format::Png  => crate::png::decode_scaled(&slice, target_width, target_height),
            Format::Heic => crate::heif::decode_scaled(&slice, target_width, target_height),
            _ => return std::ptr::null_mut(),
        };

        match decoded {
            Some((rgba, w, h)) => crate::bitmap::create_android_bitmap(&mut env, w, h, &rgba),
            None => std::ptr::null_mut(),
        }
    };

    std::panic::catch_unwind(std::panic::AssertUnwindSafe(inner))
        .unwrap_or(std::ptr::null_mut())
}
```

### 4.5 A3（target size bug）与高清防走样下采样修复细节

1. **JPEG (粗缩 + 精准滤波二阶段下采样)**：
   - **一阶段 (Hardware/DCT 粗缩)**：使用 turbojpeg 的 `scale_num`/`scale_denom` (如 1/8, 1/4, 1/2)。计算最接近且不小于 `target` 的尺寸，极速降低内存分配。
   - **二阶段 (SIMD 精准滤波)**：若粗缩后的尺寸仍大于 `target`，使用 `fast_image_resize` crate（开启 ARM NEON 硬件加速）进行 **Fast Box / Bilinear Filter** 下采样。彻底解决原始 Nearest-Neighbor 的走样锯齿问题，缩略图效果精致、防闪烁。

2. **WebP / PNG / HEIC**：
   - 全量解码后，若尺寸大于 `target`，同样通过 `fast_image_resize` 进行基于 ARM NEON SIMD 的 Box / Bilinear 快速缩放。

3. **EXIF 姿态旋转与维度互换 (A5 修复)**：
   - 在 RGBA Buffer 转 Bitmap 之前，自动读取 EXIF Orientation (1..8)。
   - **维度变换**：当 Orientation 为 `5, 6, 7, 8`（即 90° 或 270° 旋转）时，**必须在 Rust 侧互换 target 宽高** `(out_w, out_h) = (h, w)`，再向 Android `Bitmap.createBitmap` 申请空间，彻底消除缩略图与大图的显示畸变。

### 4.6 错误处理与 fallback

| 失败点 | 处理 |
|---|---|
| DirectByteBuffer / ByteArray 指针异常 | Safe release + null 返回 → fallback 到 BitmapFactory |
| Rust 解码返回 null | Kotlin 侧 fallback 到 BitmapFactory（保留现有 `fallbackDecode` 路径） |
| `System.loadLibrary` UnsatisfiedLinkError | try/catch 拦截，设 `nativeAvailable=false`；`decode()` 直接 fallback |
| Rust panic（罕见） | `catch_unwind` 捕获所有 JNI 入口边界，转换为 null 返回，保证 app 零崩溃 |

---

## 5. 模块实现要点

### 5.1 JPEG（`rust/src/jpeg.rs`）

```rust
use turbojpeg::{Decompress, PixelFormat};
use fast_image_resize as fr;

pub fn decode_scaled(data: &[u8], tw: i32, th: i32) -> Option<(Vec<u8>, i32, i32)> {
    let mut d = Decompress::start().ok()?;
    d.set_source(data);
    d.read_header().ok()?;
    let (orig_w, orig_h) = (d.width() as i32, d.height() as i32);

    if tw > 0 && th > 0 {
        let (sw, sh) = pick_jpeg_scale(orig_w, orig_h, tw, th);
        d.set_scale(sw, sh);
    }
    d.set_pixel_format(PixelFormat::RGBA);
    let mut img = d.decompress().ok()?;
    let (cur_w, cur_h) = (img.width() as i32, img.height() as i32);
    let rgba = img.data().to_vec();

    // 如果粗缩后尺寸仍然大于 target，进行二阶段 NEON SIMD 精准下采样防走样
    if tw > 0 && th > 0 && (cur_w > tw || cur_h > th) {
        resize_rgba(&rgba, cur_w, cur_h, tw, th)
    } else {
        Some((rgba, cur_w, cur_h))
    }
}
```

### 5.2 WebP（`rust/src/webp.rs`）

```rust
use libwebp::WebPDecoder;
use libwebp::sys as webp_sys;

pub fn decode_scaled(data: &[u8], tw: i32, th: i32) -> Option<(Vec<u8>, i32, i32)> {
    let mut features = unsafe { std::mem::zeroed::<webp_sys::WebPBitstreamFeatures>() };
    let rc = unsafe { webp_sys::WebPGetFeatures(data.as_ptr(), data.len(), &mut features) };
    if rc != webp_sys::VP8_STATUS_OK { return None; }

    let (src_w, src_h) = (features.width as i32, features.height as i32);
    let mut decoder = WebPDecoder::new(data).ok()?;
    decoder.set_output_buffer((src_w * src_h * 4) as usize);
    let (rgba, _, _) = decoder.decode_full()?;

    if tw > 0 && th > 0 && (src_w > tw || src_h > th) {
        resize_rgba(&rgba, src_w, src_h, tw, th)
    } else {
        Some((rgba, src_w, src_h))
    }
}
```

### 5.3 PNG（`rust/src/png.rs`）— 新增

```rust
use png::Decoder;

pub fn decode_scaled(data: &[u8], tw: i32, th: i32) -> Option<(Vec<u8>, i32, i32)> {
    let decoder = Decoder::new(data);
    let mut reader = decoder.read_info().ok()?;
    let (w, h) = (reader.info().width as i32, reader.info().height as i32);
    let mut buf = vec![0u8; reader.output_buffer_size()];
    reader.next_frame(&mut buf).ok()?;

    let rgba = match reader.info().color_type {
        png::ColorType::Rgba => buf,
        png::ColorType::Rgb  => rgb_to_rgba(&buf),
        png::ColorType::Grayscale => gray_to_rgba(&buf),
        png::ColorType::GrayscaleAlpha => ga_to_rgba(&buf),
        _ => return None,
    };

    if tw > 0 && th > 0 && (w > tw || h > th) {
        resize_rgba(&rgba, w, h, tw, th)
    } else {
        Some((rgba, w, h))
    }
}
```

### 5.4 HEIC（`rust/src/heif.rs`）— 新增（含 Android 9+ NDK 降级保底）

```rust
pub fn decode_scaled(data: &[u8], tw: i32, th: i32) -> Option<(Vec<u8>, i32, i32)> {
    #[cfg(feature = "use_libheif")]
    {
        use libheif_rs::{HeifContext, ColorSpace, Chroma};
        let ctx = HeifContext::read_from_bytes(data).ok()?;
        let handle = ctx.primary_image_handle().ok()?;
        let (w, h) = (handle.width() as i32, handle.height() as i32);
        let img = handle.decode_image(ColorSpace::Rgba, Chroma::C420, None).ok()?;
        let plane = img.planes().rgba?;
        let rgba = plane.data.to_vec();
        
        if tw > 0 && th > 0 && (w > tw || h > th) {
            return resize_rgba(&rgba, w, h, tw, th);
        }
        return Some((rgba, w, h));
    }

    #[cfg(not(feature = "use_libheif"))]
    {
        // 优雅降级保底：在 API >= 28 设备上使用系统 NDK AImageDecoder，避免笨重的 C++ libde265 静态库链接
        crate::ndk_decoder::decode_via_ndk(data, tw, th)
    }
}
```

### 5.5 Cargo 配置

```toml
[dependencies]
turbojpeg = "1"
libwebp = "0.1"
png = "0.17"
kamadak-exif = "0.5"
fast_image_resize = "4.2"
jni = "0.21"
android_logger = "0.13"

# 可选 features：libheif 源码繁重时可切为 ndk 降级模式
[features]
default = []
use_libheif = ["libheif-rs"]

[dependencies.libheif-rs]
version = "0.18"
optional = true

[lib]
crate-type = ["cdylib", "rlib"]
name = "localmedia_native"

[profile.release]
opt-level = 3
lto = "fat"
codegen-units = 1
panic = "unwind"
```

### 5.6 EXIF 解析与 1..8 全姿态矩阵变换（`rust/src/exif.rs` & `bitmap.rs`）

```rust
use exif::{Reader, Value, Tag};

pub struct ExifInfo {
    pub orientation: u32,
    pub date_time_original: Option<String>,
    pub make: Option<String>,
    pub model: Option<String>,
}

pub fn parse(data: &[u8]) -> Option<ExifInfo> {
    let mut buf = std::io::Cursor::new(data);
    let exif = Reader::new().read_from_container(&mut buf).ok()?;
    let orientation = exif.get_field(Tag::Orientation)
        .and_then(|f| if let Value::Short(ref v) = f.value { Some(v[0] as u32) } else { None })
        .unwrap_or(1);

    Some(ExifInfo {
        orientation,
        date_time_original: exif.get_field(Tag::DateTimeOriginal).map(|f| f.display_value().to_string()),
        make: exif.get_field(Tag::Make).map(|f| f.display_value().to_string()),
        model: exif.get_field(Tag::Model).map(|f| f.display_value().to_string()),
    })
}

/// 对 RGBA Buffer 进行 EXIF Orientation (1..8) 全姿态旋转与变换，并返回互换后的 (w, h)
pub fn transform_by_orientation(rgba: &[u8], w: i32, h: i32, orientation: u32) -> (Vec<u8>, i32, i32) {
    match orientation {
        6 => rotate_90_cw(rgba, w, h),   // 90° CW -> 宽高转置为 (h, w)
        8 => rotate_270_cw(rgba, w, h),  // 270° CW -> 宽高转置为 (h, w)
        3 => rotate_180(rgba, w, h),     // 180° -> 宽高保持 (w, h)
        2 => flip_horizontal(rgba, w, h),
        4 => flip_vertical(rgba, w, h),
        5 => transpose_90(rgba, w, h),
        7 => transverse_90(rgba, w, h),
        _ => (rgba.to_vec(), w, h),      // 1: Normal
    }
}
```

### 5.7 零堆分配纯 Rust 自然排序（`rust/src/natural_sort.rs`）

```rust
/// 零内存堆分配自然排序算法 (Zero-allocation Natural Sort)
/// 支持 case-insensitive、数字段升序 (file2 < file10)
pub fn compare(a: &str, b: &str) -> i32 {
    let mut ai = a.chars().peekable();
    let mut bi = b.chars().peekable();

    while let (Some(&ac), Some(&bc)) = (ai.peek(), bi.peek()) {
        if ac.is_ascii_digit() && bc.is_ascii_digit() {
            let mut na: u64 = 0;
            while let Some(&c) = ai.peek() {
                if let Some(digit) = c.to_digit(10) {
                    na = na.saturating_mul(10).saturating_add(digit as u64);
                    ai.next();
                } else { break; }
            }

            let mut nb: u64 = 0;
            while let Some(&c) = bi.peek() {
                if let Some(digit) = c.to_digit(10) {
                    nb = nb.saturating_mul(10).saturating_add(digit as u64);
                    bi.next();
                } else { break; }
            }

            if na != nb {
                return if na < nb { -1 } else { 1 };
            }
        } else {
            let alc = ac.to_ascii_lowercase();
            let blc = bc.to_ascii_lowercase();
            if alc != blc {
                return (alc as i32) - (blc as i32);
            }
            ai.next();
            bi.next();
        }
    }

    (a.len() as i32) - (b.len() as i32)
}
```

**集成点：** `BrowseSorter.kt::compareNatural` 改为：

```kotlin
internal fun compareNatural(a: String, b: String): Int = NaturalSorter.compare(a, b)
```

签名不变，10k 级目录排序零内存堆分配、速度提升 5~10 倍。

### 5.8 Gradle 与跨平台 Host 集成（`build.gradle.kts`）

```kotlin
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform

android {
    defaultConfig {
        ndk { abiFilters += "arm64-v8a" }
    }
}

// 自动侦测 Host OS 产物后缀（Windows: dll, macOS: dylib, Linux: so）
val hostOs = DefaultNativePlatform.getCurrentOperatingSystem()
val libSuffix = when {
    hostOs.isWindows -> "dll"
    hostOs.isMacOsX -> "dylib"
    else -> "so"
}

val buildRustNative by tasks.creating(Exec::class) {
    workingDir = file("src/main/rust")
    commandLine("cargo", "ndk",
        "-t", "arm64-v8a",
        "-o", "../jniLibs",
        "build", "--release")
}

val buildRustNativeHost by tasks.creating(Exec::class) {
    workingDir = file("src/main/rust")
    commandLine("cargo", "build", "--release")
}

val copyNativeToTest by tasks.creating(Copy::class) {
    from("src/main/rust/target/release/localmedia_native.$libSuffix")
    into("src/test/resources/native/")
    dependsOn(buildRustNativeHost)
}

tasks.named("preBuild") { dependsOn(buildRustNative) }
tasks.named("testDebugUnitTest") { dependsOn(copyNativeToTest) }
```

CI 需在 gradle 步骤前加：
```bash
rustup target add aarch64-linux-android
cargo install cargo-ndk
```

### 5.9 集成点清单

| 文件 | 改动类型 |
|---|---|
| `cpp/` 整目录 | **删除**（含 `CMakeLists.txt`、`libs/`、`third_party/`、`jni/`） |
| `jniLibs/arm64-v8a/libffmpeg.so` | ⚠️ **保留**（FFmpeg JNI bridge，与本轮无关） |
| `build.gradle.kts` | **删除** `externalNativeBuild { cmake {} }` 块；**新增** cargo-ndk Exec task + 跨平台 Host 构建 |
| `NativeImageDecoder.kt` | 重写：合并解码入口、双入口 ByteArray/DirectBuffer、新增 `nativeAvailable` 守卫（现有代码无此保护） |
| `NativeDecoderFactory.kt` | 路由扩展：JPEG/WebP/PNG/HEIC 全走 native；`Factory.create()` 的 magic bytes 探测扩展为 4 种格式 |
| `NativeExif.kt` | **新增**（复用同一 `.so`，不额外 `loadLibrary`） |
| `NaturalSorter.kt` | **新增**（复用同一 `.so`） |
| `BrowseSorter.kt` | `compareNatural` 内部调 `NaturalSorter.compare` |
| `LocalMediaHubApplication.kt` | 无需改动（`NativeDecoderFactory.Factory()` 已在 Coil `ImageLoader.Builder` 首位注册） |
| `.gitignore` | 加 `jniLibs/arm64-v8a/liblocalmedia_native.so`、`src/test/resources/native/` |
| CI workflow | 加 Rust toolchain 安装步骤 |

---

## 6. 测试策略

### 6.1 测试分层

```
┌─────────────────────────────────────────────────────┐
│  Robolectric JVM 测试  (android/app/src/test/)      │
│  - NativeImageDecoderTest                            │
│  - NativeExifTest                                    │
│  - NaturalSorterTest                                 │
│  - NativeDecoderFactoryTest (路由)                   │
└─────────────────────────────────────────────────────┘
                       ▲
                       │ 加载 liblocalmedia_native.so（主机版）
                       │ Robolectric 提供 Android 环境
┌─────────────────────────────────────────────────────┐
│  Rust 单元测试  (cargo test)                         │
│  - jpeg::tests、webp::tests、png::tests、heif::tests │
│  - exif::tests、natural_sort::tests                  │
└─────────────────────────────────────────────────────┘
```

### 6.2 Rust 单元测试（`cargo test`，宿主机运行）

**关键测试用例：**

- `natural_sort::tests::numeric_ordering`：`file2` < `file10`、`file10` > `file2`、`file007` == `file7`。
- `natural_sort::tests::case_insensitive`：`IMG.JPG` == `img.jpg`。
- `natural_sort::tests::mixed`：`007_gjco` < `abc`（数字 < 字母）。
- `natural_sort::tests::matches_kotlin_semantics`：与 `BrowseSorter.compareNatural` 行为一致，包括空串、纯数字、纯字母、混合数字段。
- `exif::tests::landscape_orientation`：解析 `testdata/landscape.jpg` → orientation=1。
- `exif::tests::portrait_orientation_6`：解析 `testdata/portrait_rot6.jpg` → orientation=6。
- `jpeg::tests::full_size_when_no_target`：4000×3000 → (4000, 3000)。
- `jpeg::tests::scale_fits_target`：4000×3000 + target 200×200 → turbojpeg 1/8 scale → 500×375，满足 ≤500 且 ≥200。
- `jpeg::tests::returns_none_on_corrupt`：`[0xFF, 0xD8, 0x00]` → None。
- `heif::tests::decode_sample_heic`：sample.heic → 4032×3024，rgba 长度 = w*h*4。

### 6.3 测试资源

**主路径**：从 `C:\Users\juziss\Downloads\test_image` 拷贝三种格式（JPEG/WebP/HEIC）的样本到：
- `android/app/src/main/rust/src/testdata/`（cargo test 用）
- `android/app/src/test/testdata/`（Robolectric 用）

**合成资源**（test_image 不覆盖的）：
- PNG 不同颜色类型（RGB/RGBA/Gray/GA）：用 ImageMagick 离线生成，每种 <10KB。
- portrait_rot6.jpg（含 EXIF Orientation=6）：用 exiftool 离线生成。
- corrupt bytes：单测内联构造。

### 6.4 Kotlin/Robolectric 测试

**关键约束：** Robolectric 在 JVM 上跑，需要 `.so` 的**主机 ABI 版本**（如 x86_64-linux），不是 arm64-android。CI 需双重构建：

- `cargo test`（host target）→ 测试 Rust 内部逻辑。
- `cargo build --release`（host target）→ 产出主机 `.so` 给 Robolectric 用。
- `cargo ndk -t arm64-v8a build --release` → 产出 Android `.so` 给设备用。

**Gradle 配置：**

```kotlin
android {
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}
val copyNativeToTest by tasks.creating(Copy::class) {
    from("src/main/rust/target/release/localmedia_native.$libSuffix")
    into("src/test/resources/native/")
    dependsOn("buildRustNativeHost")
}
tasks.named("testDebugUnitTest") { dependsOn("copyNativeToTest") }
```

### 6.5 测试矩阵

| 测试层 | 触发时机 | 跑在哪 | 验证什么 |
|---|---|---|---|
| `cargo test` | 每次 commit | host (CI) | Rust 内部逻辑、纯函数 |
| `testDebugUnitTest`（Robolectric + 主机 .so） | 每次 commit | host JVM | JNI 桥、Kotlin 包装 |
| `connectedDebugAndroidTest` | 发版前/manual | arm64 模拟器 | 真实设备行为 |
| Macrobenchmark（新增） | 性能回归时 | 真机 | 解码速度 vs baseline |
| 手工验证 | 提交前 | 真机 | OOM/超长图/旋转屏不回归 |

### 6.6 性能验证基准

| 基准 | 工具 | 通过标准 |
|---|---|---|
| JPEG 解码 4000×3000 → 200×200 | Macrobenchmark | ≥ Round 9 baseline 速度，且内存峰值 < Round 9 |
| HEIC 解码首次成功 | Robolectric/手工 | 不 fallback |
| 自然排序 10k 文件 | JMH/Macrobenchmark | 比 Regex 版本快 ≥ 2× |
| 包体积增量 | APK analyzer | ≤ 5MB（单 ABI） |

---

## 7. 迁移步骤与实现顺序

### 7.1 阶段划分（每个阶段独立可提交）

```
阶段 0：环境准备
  └─ CI 加 Rust toolchain、cargo-ndk；本地验证 cargo build 成功

阶段 1：Rust crate 骨架 + 自然排序（最低风险、纯函数先行）
  └─ Cargo.toml + lib.rs + natural_sort.rs + cargo test

阶段 2：EXIF 解析
  └─ exif.rs + cargo test；Kotlin NativeExif.kt（暂不接入预览）

阶段 3：JPEG/WebP 解码（替换 C++，重写为 Rust）  ← 关键里程碑
  └─ jpeg.rs + webp.rs + 修 A3 target size + cargo test
  └─ Kotlin NativeImageDecoder.kt 重写
  └─ NativeDecoderFactory.kt 保持路由
  └─ 删除 cpp/ 目录、CMakeLists.txt
  └─ Robolectric 测试
  └─ 重跑 Round 4/9 验证（OOM/超长图/旋转屏）

阶段 4：PNG 解码（新增格式）
  └─ png.rs + cargo test；路由扩展；Robolectric 测试

阶段 5：HEIC 解码（最重，最后做）
  └─ heif.rs + libheif-rs Cargo 配置 + cargo test
  └─ 路由扩展；Robolectric 测试
  └─ 包体积检查（≤ 5MB）

阶段 6：EXIF Orientation 接入预览
  └─ Rust 侧 create_android_bitmap 前旋转 RGBA
  └─ Robolectric 验证 portrait_rot6 旋转正确
  └─ 真机手工验证竖拍照片显示方向

阶段 7：自然排序透明替换 + 性能基准
  └─ BrowseSorter.compareNatural → NaturalSorter.compare
  └─ Macrobenchmark：10k 文件排序 ≥ 2× 加速
```

### 7.2 每阶段验证矩阵

| 阶段 | `cargo test` | Robolectric | Macrobenchmark | 真机手工 | 包体积 |
|---|---|---|---|---|---|
| 0 | — | — | — | — | baseline |
| 1 | ✓ | — | — | — | — |
| 2 | ✓ | ✓（独立） | — | — | — |
| 3 | ✓ | ✓（关键） | ✓ | ✓（OOM 回归） | ✓ |
| 4 | ✓ | ✓ | — | — | ✓ |
| 5 | ✓ | ✓ | — | ✓ | ✓ |
| 6 | ✓ | ✓ | — | ✓（关键） | — |
| 7 | — | ✓ | ✓（关键） | — | — |

### 7.3 风险与回退

| 风险 | 触发条件 | 回退策略 |
|---|---|---|
| libheif-rs 编译失败 / 体积超标 | 阶段 5 包体积 > 6MB | 阶段 5 暂缓，仅做前 4 阶段 + A3 修复；HEIC fallback BitmapFactory |
| Robolectric 加载主机 .so 复杂 | 阶段 3 CI 配置卡住 | 暂用 Mock JNI，把 `.so` 集成测试降级为 instrumented test |
| Rust 自然排序与 Kotlin 行为不一致 | 阶段 7 测试发现 | 保留 Kotlin `compareNatural` 为参照，差异单元测试逐条对齐 |
| turbojpeg scale factor API 不达预期 | 阶段 3 性能不达标 | 退化为 Rust nearest-neighbor 缩放（同 WebP 路径） |

---

## 8. 已决策点

| 决策 | 选择 | 理由 |
|---|---|---|
| **范围** | A3 + A5 + A1 + A2 + B1 | JPEG/WebP 重写 + 新增 PNG/HEIC/EXIF/排序 |
| **语言** | 100% Rust | 统一栈、内存安全、生态成熟 |
| **构建** | cargo-ndk，Gradle preBuild hook | Rust Android 标准路径 |
| **`.so`** | 单一 `liblocalmedia_native.so` | 删除 cpp/ 全部（含 CMakeLists.txt）、避免双栈 |
| **ABI** | 仅 arm64-v8a | 与现有预构建库一致；覆盖现代设备 |
| **JPEG/WebP crate** | turbojpeg + libwebp（原生绑定） | 与现有底层库同源，性能可控 |
| **HEIC** | libheif-rs + libde265（可选 Feature），NDK AImageDecoder 保底 | HEVC 后端必备；Feature 切换控制体积 |
| **缩放 (A3)** | libjpeg-turbo DCT 粗缩 + `fast_image_resize` SIMD 精准缩放 | 二阶段：粗缩降内存 + 滤波防走样 |
| **EXIF** | kamadak-exif | 纯 Rust，无 C 依赖 |
| **自然排序集成** | 透明替换 BrowseSorter 内部 | 调用点零改动 |
| **EXIF Orientation 应用** | Rust 侧 1..8 全姿态矩阵旋转 + 宽高转置 | 避免 Kotlin↔native 多次跨边界 |
| **JNI 内存策略** | ByteArray: `GetPrimitiveArrayCritical` 借用→拷贝→释放；DirectBuffer: 零拷贝 | 消除 Double Allocation |
| **测试** | cargo test + Robolectric（跨平台主机动态库）+ instrumented（发版前） | 三层覆盖 |
| **包体积预算** | ≤ 5MB（单 ABI），HEIC NDK 降级时 ≤ 2.1MB | libde265 是大头 |
| **Round 9 关系** | 重写后重跑验证 | 接受成本，统一栈 |
| **测试样本源** | `C:\Users\juziss\Downloads\test_image`（JPEG/WebP/HEIC） | 真实样本 |

---

## 9. 非目标（再次明确）

- ❌ 视频转码、抽帧（应用无场景）
- ❌ 加密、哈希、压缩（应用无场景）
- ❌ GIF/APNG/AVIF/JXL（YAGNI，按需后续轮次）
- ❌ Animated WebP（复杂度高、收益低）
- ❌ 服务端任何改动（本轮纯客户端）
- ❌ Compose UI 改动（除路由接入外）
- ❌ Coil 升级 / 缓存调优（Round 4 已做）
- ❌ GIF 动图解码

---

## 10. 后续轮次（不在本 spec，仅备忘）

- **AVIF/JXL** 解码：若 HEIC 落地后用户样本格式变化。
- **Animated WebP**：若用户场景出现。
- **服务端读取热路径**：扫描器按类型缓存、scoped 搜索去重复 normalize、`DownloadFolderZip` FD/压缩。
- **架构**：`BrowseViewModel` 拆分、`app.js` 模块化、`RetrofitClient` 可注入。
