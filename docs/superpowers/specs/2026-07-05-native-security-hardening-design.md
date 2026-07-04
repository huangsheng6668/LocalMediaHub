# Native 层 Security Hardening 设计（Round 13）

- **日期**: 2026-07-05
- **范围**: Rust JNI bridge `nativeDecodeDirect` 长度校验（单文件改动）
- **策略**: A — `min(length, capacity)` 静默裁剪
- **状态**: 已审核修订
- **前置**: Round 11（Rust 重写落地）；Round 11 final code review 标记 2 个 Important follow-ups，本轮修第 1 个
- **审核修订**: 2026-07-05 — 修正 `get_direct_buffer_capacity` 返回类型（`jint`/`i32`，非 `jlong`/`i64`），同步更新代码示例、测试与决策表

---

## 1. 背景与动机

Round 11 final code review（commit `e8d535b..37c08ac`，opus 审核）标记了 2 个 Important 项：

1. **`nativeDecodeDirect` 长度未校验** — `decoders.rs:154-161` 用 `slice::from_raw_parts(ptr, length as usize)`，直接信任 Kotlin 传入的 `length` 参数（`jint`/`i32`）。若 caller-supplied `length` > DirectByteBuffer 实际容量 → OOB 读（Rust 安全契约违规，潜在 undefined behavior）。
2. Native 库加载失败可观测性缺失 — Kotlin 侧 `System.loadLibrary` 失败时仅 `Log.w`，生产环境静默回退。

Round 13 解决第 1 项；第 2 项移到后续轮次（用户明确选最小范围）。

### 1.1 当前风险

- **生产路径不触发**：`NativeImageDecoder.decode()` 走 `nativeDecodeByteArray`，不调 direct 路径。
- **已导出 JNI 符号**：未来一旦有调用方接入（如 Coil `SourceResult` backed by `DirectByteBuffer`），即触发风险。
- **编译器基于 UB 的优化不可预测**：Rust `slice::from_raw_parts` 在 length 超 capacity 时是 UB；编译器可激进优化，行为不保证。

### 1.2 范围明确

- ✅ `nativeDecodeDirect` 加 capacity 校验
- ❌ Kotlin 侧可观测性（后续轮次）
- ❌ `nativeDecodeByteArray` 路径（已通过 `JArray::len()` 内置安全）
- ❌ Round 11 的 6 个 Minor 项

---

## 2. 目标与非目标

### 目标
1. **`nativeDecodeDirect` 加 capacity 校验**：用 `env.get_direct_buffer_capacity()` 取真实容量（返回 `jint`/`i32`），`effective_length = min(length, capacity)`。
2. **早退无效 buffer**：capacity ≤ 0 时返回 null（防御性）。
3. **加 Rust 单测**：验证 `min` 逻辑（不调真 JNI）。
4. **保持现有行为**：所有现有测试不回归。

### 非目标
- ❌ Native 加载失败可观测性（Kotlin 侧）
- ❌ `nativeDecodeByteArray` 路径校验
- ❌ 任何 Kotlin 侧改动
- ❌ 6 个 Round 11 Minor 项（stale doc、CMYK、`v[0]` 防御等）
- ❌ `bitmap.rs` 的 `AndroidBitmap_*` FFI 二次校验

---

## 3. 架构与文件组织

### 3.1 改动文件清单

| 文件 | 改动类型 | 说明 |
|---|---|---|
| `android/app/src/main/rust/src/jni_bridge/decoders.rs` | 改 | `nativeDecodeDirect` 函数体（约 lines 149-174）加 capacity 校验 |

无新增文件，无 Kotlin 改动，无 Cargo.toml 改动。

### 3.2 修复实现

**当前代码**（lines 149-178，含函数签名）：

```rust
let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
    let ptr = env.get_direct_buffer_address(&data).ok()?;
    if ptr.is_null() {
        return None;
    }
    if length <= 0 {
        return None;
    }
    let slice = unsafe { std::slice::from_raw_parts(ptr as *const u8, length as usize) };
    let decoded = decode_slice(slice, target_width, target_height)?;
    // ... 其余 bitmap 创建逻辑
}));
```

**修复后代码**：

```rust
let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
    // jni-rs 0.21 `get_direct_buffer_address` returns Result<*mut u8, Error>;
    // null pointer (no backing memory) is Ok(null), so explicit-null-check.
    let ptr = env.get_direct_buffer_address(&data).ok()?;
    if ptr.is_null() {
        return None;
    }
    if length <= 0 {
        return None;
    }

    // Defense-in-depth: clamp caller-supplied length to actual buffer capacity.
    // Without this, slice::from_raw_parts(ptr, length) would read OOB if a
    // caller's `length` exceeds the DirectByteBuffer's backing memory — Rust
    // UB, with unpredictable compiler-optimized behavior.
    //
    // jni-rs 0.21: get_direct_buffer_capacity returns Result<jint> (i32).
    // JNI spec: GetDirectBufferCapacity returns jlong, but jni-rs 0.21 wraps
    // it as jint; negative/zero means invalid buffer.
    let capacity = env.get_direct_buffer_capacity(&data).ok()?;
    if capacity <= 0 {
        return None;
    }
    let effective_length = (length as usize).min(capacity as usize);
    let slice = unsafe { std::slice::from_raw_parts(ptr as *const u8, effective_length) };

    let decoded = decode_slice(slice, target_width, target_height)?;
    // ... 其余 bitmap 创建逻辑（不变）
}));
```

### 3.3 jni-rs 0.21 API 验证

`jni-rs 0.21` 提供：
- `JNIEnv::get_direct_buffer_address(&mut self, buf: &JObject) -> Result<*mut c_void>`
  - 现有代码用 `&JByteBuffer` 传参（自动 `Deref` 到 `&JObject`），返回值做 `ptr as *const u8` 转换
- `JNIEnv::get_direct_buffer_capacity(&mut self, buf: &JObject) -> Result<jint>` （`jint = i32`）
  - 同样 `&JByteBuffer` 可直接传参

> ⚠️ **审核修正**：原文档误将 capacity 返回类型标注为 `jlong`/`i64`，实际 jni-rs 0.21 返回 `jint`/`i32`。这并非 JNI 规范差异（JNI spec 的 `GetDirectBufferCapacity` 确实返回 `jlong`），而是 jni-rs 0.21 的包装层选择了 `jint`。对于本项目的 DirectByteBuffer（图片字节流），容量远小于 2GB，`i32` 足够覆盖。

返回类型 `i32`（不是 `usize`）— 负值或 0 表示无效 buffer（理论不应发生但防御）。Cast 到 `usize` 前先做 `<= 0` 检查，避免负值 cast 陷阱（`-1i32 as usize` 在 64 位平台 = `usize::MAX`，因 sign-extension）。

---

## 4. 测试

### 4.1 Rust 单测

加到 `decoders.rs::tests`（若不存在 `#[cfg(test)] mod tests` 则新建）：

```rust
#[cfg(test)]
mod tests {
    #[test]
    fn clamp_length_to_capacity_when_length_exceeds() {
        // Mirrors the runtime min() logic without calling real JNI.
        // jni-rs 0.21: capacity is jint (i32), length param is also jint.
        let caller_length: usize = 1000;
        let capacity: i32 = 500;
        assert!(capacity > 0);
        let effective = caller_length.min(capacity as usize);
        assert_eq!(effective, 500);
    }

    #[test]
    fn keep_length_when_within_capacity() {
        let caller_length: usize = 300;
        let capacity: i32 = 500;
        let effective = caller_length.min(capacity as usize);
        assert_eq!(effective, 300);
    }

    #[test]
    fn reject_non_positive_capacity() {
        // Mimics the early-return path: capacity <= 0 → null return
        let capacity: i32 = 0;
        assert!(!(capacity > 0));
        let capacity: i32 = -1;
        assert!(!(capacity > 0));
    }
}
```

> **测试范围限制：** 真正的 JNI 集成测试需要 mock `JNIEnv` 或在 instrumented test 中跑（host JVM 无法 mock DirectByteBuffer 的 native pointer）。本计划仅做 Rust 内部逻辑测试，覆盖 `min()` 早退逻辑。完整集成验证靠 `cargo test` 全过 + `assembleDebug` 全过 + 真机手工回归。
>
> **注意：** 由于 `decoders.rs` 当前没有 `#[cfg(test)] mod tests`（整个模块被 `#[cfg(target_os = "android")]` 门控），测试模块需要独立于 android gate 之外，或作为独立的 pure-logic 测试文件放置。建议在 `decoders.rs` 末尾新建不带 `cfg(target_os)` 门控的 `#[cfg(test)] mod tests` 块。

### 4.2 回归

- `cd android/app/src/main/rust && cargo test` — 现有 43 个测试 + 3 个新测试全过
- `cd android && ./gradlew assembleDebug testDebugUnitTest` — 全部既有测试不回归

### 4.3 真机手工回归

- 浏览网格 → JPEG/WebP/PNG 缩略图正常加载（不变）
- HEIC 图片通过 Android AImageDecoder fallback 正常显示（不变）
- 由于 `nativeDecodeDirect` 当前无生产 caller，**真机行为应与 Round 12 一致**

---

## 5. 实现顺序与提交策略

单一提交（一个文件、一次 commit）：

```bash
git add android/app/src/main/rust/src/jni_bridge/decoders.rs
git commit -m "fix(native): clamp nativeDecodeDirect length to buffer capacity (round 13)"
```

提交前：`cargo test` + `./gradlew assembleDebug` 必须全过。

---

## 6. 已决策点

| 决策 | 选择 | 理由 |
|---|---|---|
| 范围 | 仅 `nativeDecodeDirect` OOB 修复 | 用户明确选最小范围 |
| 超额处理 | `min(length, capacity)` 静默裁剪 | 生产友好、零回归 |
| API | `env.get_direct_buffer_capacity()` (jni-rs 0.21, 返回 `jint`/`i32`) | 已存在、零新依赖 |
| 负值 capacity | 早退返 null | 防御性，避免 `i32` 负值 cast 到 `usize` 陷阱 |
| 测试 | Rust 内部逻辑单测（3 个） | 真 JNI 集成需 instrumented test，超范围 |
| 提交 | 单 commit | 单文件、单函数改动 |

---

## 7. 非目标（再次明确）

- ❌ Native 加载失败可观测性（Kotlin 侧）— 移到后续轮次
- ❌ `nativeDecodeByteArray` 路径校验
- ❌ 任何 Kotlin 改动
- ❌ 6 个 Round 11 Minor 项（stale doc、CMYK 等）
- ❌ `bitmap.rs` FFI 二次校验
- ❌ Cargo.toml / build.gradle.kts 改动

---

## 8. 后续轮次（不在本 spec，仅备忘）

- **Native 加载失败可观测性**：BuildConfig.DEBUG 检测 + Release fast-fail（用户已选择"仅修 Rust 侧"路径，本项移到下一轮）
- **Round 11 6 个 Minor 项**：stale doc（jpeg.rs/png.rs/Cargo.toml typos）、重复 magic-byte sniff、CMYK 公式、`v[0]` 防御、`png.rs` buffer 零初始化
- **Coil v3 升级**：原生"按访问时间淘汰"，解决 Round 12 mtime 限制
- **可注入 Logger**：解决 Round 12 `isReturnDefaultValues = true` 全局 flag 的 fidelity 倒退

---

## 9. 审核修订记录

| 日期 | 审核人 | 修订内容 |
|---|---|---|
| 2026-07-05 | Code Review | **[关键]** 修正 `get_direct_buffer_capacity` 返回类型：原文档标注为 `jlong`/`i64`，实际 jni-rs 0.21 返回 `jint`/`i32`。同步修正修复代码示例（变量名 `capacity_i64` → `capacity`，类型 `i64` → `i32`）、单测用例类型、3.3 节 API 签名说明、决策表。此错误若不修正将导致编译失败或类型隐式转换行为不符预期。 |
| 2026-07-05 | Code Review | 补充 `get_direct_buffer_address` 实际返回 `Result<*mut c_void>` 而非 `Result<*mut u8>`（现有代码通过 `as *const u8` 转换，功能正确但签名描述需准确）。 |
| 2026-07-05 | Code Review | 补充说明 `decoders.rs` 当前无 `#[cfg(test)]` 模块，且整文件受 `#[cfg(target_os = "android")]` 门控，测试模块的放置位置需注意。 |
| 2026-07-05 | Code Review | 修正 `&self` → `&mut self`：jni-rs 0.21 的 `JNIEnv` 方法接收 `&mut self`。 |
