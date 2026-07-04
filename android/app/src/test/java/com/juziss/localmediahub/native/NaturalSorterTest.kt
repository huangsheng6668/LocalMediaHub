package com.juziss.localmediahub.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NaturalSorter].
 *
 * On the host JVM the arm64-only `liblocalmedia_native.so` cannot be loaded,
 * so these tests exercise the Kotlin fallback path inside [NaturalSorter].
 * The fallback mirrors the original `BrowseSorter.compareNatural` Regex-based
 * semantics, so the assertions below double as a regression guard for that
 * behaviour. On-device (instrumentation tests, not yet wired up) the same
 * assertions validate the Rust JNI path because [NaturalSorter.compare]
 * routes to `nativeCompare` when `System.loadLibrary` succeeds.
 */
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
        // '0' < 'a' in ASCII, so a digit-starting run sorts before a letter.
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
