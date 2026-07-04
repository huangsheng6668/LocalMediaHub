//! JNI entry point for `NaturalSorter.nativeCompare`.
//!
//! Lives in a submodule so the top-level `lib.rs` stays focused on the
//! decoder stubs. The function name encodes the Java/Kotlin class
//! `com.juziss.localmediahub.native.NaturalSorter` and the private external
//! method `nativeCompare`, per the JNI symbol resolution convention. The
//! Kotlin side exposes a public `compare` wrapper that routes to this
//! symbol when the native library is loaded.
//!
//! On any JNI-side error (e.g. null JString) the bridge returns `0` so that
//! callers see "equal" rather than a JVM crash. The underlying
//! `natural_sort::compare` itself is infallible.

use jni::objects::{JClass, JString};
use jni::sys::jint;
use jni::JNIEnv;

/// JNI symbol for `NaturalSorter.nativeCompare`.
///
/// Named `..._nativeCompare` rather than `..._compare` because the public
/// Kotlin API is `compare`, which would otherwise clash with the private
/// `external` declaration that needs an identical name to satisfy JNI
/// symbol resolution. The Kotlin wrapper (`NaturalSorter.compare`) falls
/// back to a pure-Kotlin implementation when the `.so` is unavailable
/// (host JVM test environment), so the public method name is preserved.
#[no_mangle]
pub extern "system" fn Java_com_juziss_localmediahub_native_NaturalSorter_nativeCompare(
    mut env: JNIEnv,
    _class: JClass,
    a: JString,
    b: JString,
) -> jint {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let a: String = match env.get_string(&a) {
            Ok(s) => s.into(),
            Err(_) => return 0,
        };
        let b: String = match env.get_string(&b) {
            Ok(s) => s.into(),
            Err(_) => return 0,
        };
        crate::natural_sort::compare(&a, &b) as jint
    }));
    match result {
        Ok(ordering) => ordering,
        Err(_) => 0,
    }
}
