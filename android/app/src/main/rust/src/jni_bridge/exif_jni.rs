//! JNI entry point for `NativeExif.nativeParseExif`.
//!
//! Lives in a submodule so the top-level `lib.rs` stays focused on the
//! decoder stubs. The function name encodes the Java/Kotlin class
//! `com.juziss.localmediahub.native.NativeExif` and the private external
//! method `nativeParseExif`, per the JNI symbol resolution convention.
//! The Kotlin side exposes a public suspend `parse` wrapper that routes to
//! this symbol via `Dispatchers.Default` when the native library is loaded.
//!
//! On any JNI-side error (bad class lookup, OOM, malformed byte array) the
//! bridge returns `null` so callers see "no EXIF" rather than a JVM crash.
//! The underlying [`crate::exif_reader::parse`] is infallible w.r.t. panics
//! on arbitrary input — it returns `None` for any non-EXIF container — but
//! we still wrap the whole body in `catch_unwind` to satisfy the JNI
//! safety contract established in Task 1 (`natural_sort_jni`):
//!
//!   > Every JNI entry point must defend against Rust panics, because an
//!     uncaught unwind across the FFI boundary is Undefined Behaviour in
//!     JNI 0.21 and will abort the JVM process on Android.

use jni::objects::{JByteArray, JClass, JObject, JString, JValue};
use jni::sys::{jint, jobject};
use jni::JNIEnv;

/// JNI symbol for `NativeExif.nativeParseExif`.
///
/// Reads `length` bytes from `data` (a `JByteArray`), parses EXIF metadata
/// via [`crate::exif_reader::parse`], and constructs a
/// `com.juziss.localmediahub.native.NativeExif$ExifInfo` instance with the
/// four-arg constructor `(int, String, String, String)`. Returns `null`
/// when the input has no EXIF or any JNI step fails.
#[no_mangle]
pub extern "system" fn Java_com_juziss_localmediahub_native_NativeExif_nativeParseExif(
    mut env: JNIEnv,
    _class: JClass,
    data: JByteArray,
    _length: jint,
) -> jobject {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        // `convert_byte_array` copies the JVM-managed byte[] into a Rust
        // `Vec<u8>`. For a typical EXIF segment (<64 KiB) this is a single
        // short-lived allocation that is freed at the end of this scope,
        // and it is dramatically simpler than the unsafe critical-array
        // primitive (which would require pinning the array and managing
        // `ReleaseMode` ourselves). The `length` argument from the Kotlin
        // caller is intentionally ignored — the JByteArray already knows
        // its own length, and trusting `length` would let a malicious
        // caller read out-of-bounds heap memory.
        let bytes = env.convert_byte_array(&data).ok()?;

        let info = crate::exif_reader::parse(&bytes)?;

        // Look up the Kotlin `ExifInfo` data class. The constructor signature
        // matches `NativeExif.ExifInfo(orientation: Int, dateTimeOriginal:
        // String?, make: String?, model: String?)`.
        let cls = env
            .find_class("com/juziss/localmediahub/native/NativeExif$ExifInfo")
            .ok()?;

        // Build JStrings for the optional fields. `JObject::null().into()`
        // materialises a `JString` wrapping null, which JNI marshals to
        // Kotlin `String? = null` for the absent case.
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

        // `new_object` accepts the class descriptor and the constructor
        // signature as a string; jni-rs performs the method-id lookup
        // internally and validates arg count/types against the signature.
        let obj = env
            .new_object(
                &cls,
                "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                &[
                    JValue::Int(info.orientation),
                    JValue::Object(&dt),
                    JValue::Object(&make),
                    JValue::Object(&model),
                ],
            )
            .ok()?;

        // `into_raw` consumes the local reference and yields the raw
        // `jobject` handle. Returning it as the function's `jobject` return
        // value hands ownership to the JVM caller, which is responsible for
        // managing the local ref (Kotlin will promote it to a strong global
        // ref via the `ExifInfo?` return value).
        Some(obj.into_raw())
    }));

    match result {
        Ok(Some(ptr)) => ptr,
        _ => std::ptr::null_mut(),
    }
}
