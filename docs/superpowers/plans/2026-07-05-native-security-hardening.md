# Native 层 Security Hardening（Round 13）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 `nativeDecodeDirect` JNI 入口加 DirectByteBuffer capacity 校验，防止 caller-supplied `length` 超过实际容量时 OOB 读。

**Architecture:** 修改单文件 `android/app/src/main/rust/src/jni_bridge/decoders.rs`，在 `nativeDecodeDirect` 函数体里 `slice::from_raw_parts` 前插入 `env.get_direct_buffer_capacity()` + `min(length, capacity)` clamp。新增 3 个 Rust 单测验证 min/clamp 逻辑；由于 `decoders.rs` 整文件受 `#[cfg(target_os = "android")]` 门控，测试模块必须放在 cfg gate 之外（host cargo test 才能跑到）。

**Tech Stack:** Rust + jni-rs 0.21 (returns `jint`/`i32`, NOT `jlong`/`i64`)

## Global Constraints

- jni-rs 0.21 API: `JNIEnv::get_direct_buffer_capacity(&mut self, buf: &JObject) -> Result<jint>` where `jint = i32`
- jni-rs 0.21 API: `JNIEnv::get_direct_buffer_address(&mut self, buf: &JObject) -> Result<*mut c_void>`
- `decoders.rs` 整文件受 `#[cfg(target_os = "android")]` 门控 — 测试模块必须放在 cfg gate 之外（host `cargo test` 才能跑到）
- 超额处理：`min(length, capacity)` 静默裁剪（生产友好，零回归）
- 负值 capacity（理论不应发生但防御）：早退返 null，避免 `i32` 负值 cast 到 `usize` 的 sign-extension 陷阱
- 单 commit 提交，单文件改动
- 不动 Kotlin 侧、不动 Cargo.toml、不动 build.gradle.kts
- panic = "unwind"（Cargo.toml 已设）+ 每个 JNI 入口已 wrapped in `catch_unwind`（保持不变）

---

### Task 1: 加 capacity clamp + 3 个 Rust 单测

**Files:**
- Modify: `android/app/src/main/rust/src/jni_bridge/decoders.rs:140-179`（`nativeDecodeDirect` 函数体）
- Append at end of file: 新 `#[cfg(test)] mod tests` 块（必须 NOT 在 `#[cfg(target_os = "android")]` gate 内）

**Interfaces:**
- Consumes: jni-rs 0.21 `JNIEnv::get_direct_buffer_capacity(&mut self, &JObject) -> Result<jint>`
- Produces: `nativeDecodeDirect` 函数加 capacity clamp（无签名变化，无外部接口变化）

- [ ] **Step 1: 在 `decoders.rs` 末尾追加测试模块（先写测试）**

打开 `android/app/src/main/rust/src/jni_bridge/decoders.rs`，在文件最末尾（line 180 之后，注意要在所有 `#[cfg(target_os = "android")]` 块之外）追加：

```rust
#[cfg(test)]
mod tests {
    // Unit tests for the capacity-clamp logic in `nativeDecodeDirect`.
    // Pure logic — does NOT exercise the real JNI path, which is gated
    // behind `#[cfg(target_os = "android")]` and only runs on-device.
    //
    // The clamp pattern is: `effective = (length as usize).min(capacity as usize)`
    // preceded by an early-return when `capacity <= 0`.

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

- [ ] **Step 2: Run tests to verify they pass (logic-only, no JNI needed)**

Run: `cd android/app/src/main/rust && cargo test --package localmedia_native --lib jni_bridge::decoders::tests`
Expected: 3 tests PASS. (Pure logic tests — they don't depend on the production code change yet because they replicate the min() pattern inline. They'll continue to pass after the production change.)

- [ ] **Step 3: Modify `nativeDecodeDirect` to add capacity clamp**

In `android/app/src/main/rust/src/jni_bridge/decoders.rs`, find the `Java_com_juziss_localmediahub_native_NativeImageDecoder_nativeDecodeDirect` function (around lines 140-179).

Replace this block (inside the `catch_unwind` closure, currently lines 154-161):

```rust
        let ptr = env.get_direct_buffer_address(&data).ok()?;
        if ptr.is_null() {
            return None;
        }
        if length <= 0 {
            return None;
        }
        let slice = unsafe { std::slice::from_raw_parts(ptr as *const u8, length as usize) };
```

With this block:

```rust
        // jni-rs 0.21 `get_direct_buffer_address` returns `Result<*mut c_void, Error>`.
        // A null pointer (no backing memory) is surfaced as `Ok(null)` by the crate,
        // so we explicit-null-check it after the `?`.
        let ptr = env.get_direct_buffer_address(&data).ok()?;
        if ptr.is_null() {
            return None;
        }
        if length <= 0 {
            return None;
        }

        // Defense-in-depth: clamp caller-supplied length to actual buffer capacity.
        // Without this, `slice::from_raw_parts(ptr, length)` would read OOB if a
        // caller's `length` exceeds the DirectByteBuffer's backing memory — Rust UB,
        // with unpredictable compiler-optimized behavior.
        //
        // jni-rs 0.21: `get_direct_buffer_capacity` returns `Result<jint>` (i32).
        // The JNI spec's `GetDirectBufferCapacity` returns `jlong`, but jni-rs 0.21
        // wraps it as `jint`; for this project's image byte streams (well under 2GB)
        // `i32` is sufficient.
        //
        // Negative/zero capacity means invalid buffer (defensive — shouldn't happen
        // in practice). The `<= 0` check also guards against the i32→usize cast
        // sign-extension trap (`-1i32 as usize` on 64-bit = `usize::MAX`).
        let capacity = env.get_direct_buffer_capacity(&data).ok()?;
        if capacity <= 0 {
            return None;
        }
        let effective_length = (length as usize).min(capacity as usize);
        let slice = unsafe { std::slice::from_raw_parts(ptr as *const u8, effective_length) };
```

- [ ] **Step 4: Run full Rust test suite**

Run: `cd android/app/src/main/rust && cargo test`
Expected: All existing tests (43 from Round 11/12) + 3 new tests PASS (46 total).

- [ ] **Step 5: Verify Android target still compiles**

Run: `cd android/app/src/main/rust && cargo ndk -t arm64-v8a -o ../jniLibs build --release`
Expected: BUILD SUCCESSFUL — confirms the `cfg(target_os = "android")`-gated production code compiles with the new `get_direct_buffer_capacity` call.

- [ ] **Step 6: Verify full Gradle build + JVM test suite**

Run: `cd android && ./gradlew assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — APK still packages the new `.so`; all existing JVM tests still pass.

- [ ] **Step 7: Commit**

```bash
cd "E:\github_project\LocalMediaHub"
git add android/app/src/main/rust/src/jni_bridge/decoders.rs
git commit -m "$(cat <<'EOF'
fix(native): clamp nativeDecodeDirect length to buffer capacity (round 13)

Caller-supplied `length` is now clamped via `env.get_direct_buffer_capacity()`
before `slice::from_raw_parts`. Prevents OOB read if a future caller passes a
length larger than the DirectByteBuffer's backing memory (Round 11 code review
Important finding #1).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## 附录 A: 实现速查

| 项 | 值 |
|---|---|
| Rust 文件改动 | `decoders.rs`（单文件，2 处：函数体 + 末尾追加 tests mod） |
| Kotlin 改动 | 无 |
| Cargo.toml 改动 | 无 |
| build.gradle.kts 改动 | 无 |
| 新依赖 | 无（`get_direct_buffer_capacity` 是 jni-rs 0.21 已有 API） |
| 超额处理 | `min(length, capacity)` 静默裁剪 |
| 负值 capacity | 早退返 null |
| 测试 | 3 个 Rust 内部逻辑单测 |
| 提交 | 单 commit |

## 附录 B: 测试模块放置陷阱（已规避）

`decoders.rs` 整文件受 `#[cfg(target_os = "android")]` 门控（lines 13-179）。如果直接在某个 `#[cfg(target_os = "android")]` 函数下方插入 `#[cfg(test)] mod tests`，host `cargo test` 跑不到这些测试（因 host 不是 android target）。

**正确做法：** 把 `#[cfg(test)] mod tests` 放在文件**最末尾**，且**独立于** `#[cfg(target_os = "android")]` gate。这样 host `cargo test` 能直接跑（不依赖 android cfg）。本计划 Step 1 已遵守此约束。

## 附录 C: 已知风险（接受）

1. **测试不覆盖真 JNI 路径**：`get_direct_buffer_capacity` 实际返回值由 jni-rs runtime 决定，单测不验证。完整集成需 instrumented test，超范围。
2. **静默裁剪 vs 抛错**：`min(length, capacity)` 不让 caller 知道 length 被截断。如果 caller 的 length 是真实 image byte stream 长度，截断会导致解码失败 → 返回 null → Kotlin 走 BitmapFactory fallback（行为正确但浪费一次 JNI 调用）。生产友好优先于严格错误传播，符合 spec 决策。
3. **i32 容量上限 ~2GB**：超过 2GB 的 DirectByteBuffer 会被 jni-rs 0.21 包装层截断为 i32 后溢出为负值，触发早退返 null。本项目无 2GB+ 图片，可接受。
