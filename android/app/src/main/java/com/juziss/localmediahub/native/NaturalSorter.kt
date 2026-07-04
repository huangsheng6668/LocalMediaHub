package com.juziss.localmediahub.native

/**
 * Natural-order string comparison backed by a Rust implementation living in
 * `liblocalmedia_native.so`.
 *
 * Production: every call goes through JNI into
 * `natural_sort::compare` in `android/app/src/main/rust/src/natural_sort.rs`,
 * which performs a single byte-stream scan with no regex and no per-call
 * `List<String>` allocation.
 *
 * Test host (Robolectric / plain JVM): the `.so` is only cross-compiled for
 * `arm64-v8a` and is not loadable on the host JVM, so the loader catches
 * `UnsatisfiedLinkError` once and falls back to a pure-Kotlin implementation
 * with identical semantics. This keeps `BrowseSorterTest` green in the local
 * test runner without weakening production behaviour.
 *
 * The fallback deliberately mirrors the original Kotlin `compareNatural`
 * semantics (Regex-tokenised, case-insensitive) so callers observe the same
 * ordering regardless of which path executes.
 */
object NaturalSorter {

    @Volatile
    private var nativeAvailable: Boolean = false

    init {
        try {
            System.loadLibrary("localmedia_native")
            nativeAvailable = true
        } catch (_: UnsatisfiedLinkError) {
            // Host JVM (unit tests) or device without the .so packaged —
            // callers transparently fall back to [compareFallback].
            nativeAvailable = false
        }
    }

    /**
     * Compare two strings with natural ordering.
     *
     * Returns negative if `a < b`, zero if equal, positive if `a > b`.
     * Contract matches `kotlin.Comparator` usage so the result can be passed
     * directly to `sortedWith` and friends.
     */
    fun compare(a: String, b: String): Int =
        if (nativeAvailable) nativeCompare(a, b) else compareFallback(a, b)

    private external fun nativeCompare(a: String, b: String): Int

    /**
     * Pure-Kotlin fallback used when the native library cannot be loaded.
     *
     * This is byte-for-byte the previous `BrowseSorter.compareNatural`
     * implementation, retained here so unit tests that cannot load the
     * arm64 `.so` observe identical ordering. In production on-device this
     * path is never taken.
     */
    @Suppress("Regex", "UnusedMember")
    private fun compareFallback(a: String, b: String): Int {
        val regex = Regex("\\d+|\\D+")
        val tokensA = regex.findAll(a.lowercase()).map { it.value }.toList()
        val tokensB = regex.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(tokensA.size, tokensB.size)) {
            val ta = tokensA[i]
            val tb = tokensB[i]
            val numA = ta.toIntOrNull()
            val numB = tb.toIntOrNull()
            val cmp = if (numA != null && numB != null) numA.compareTo(numB) else ta.compareTo(tb)
            if (cmp != 0) return cmp
        }
        return tokensA.size.compareTo(tokensB.size)
    }
}
