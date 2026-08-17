package com.juziss.localmediahub.util

import com.juziss.localmediahub.data.encodePathSegments
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Task 12 (L-6): URL path-segment encoding for relative media paths.
 *
 * [encodePathSegments] must percent-encode every special character inside each
 * `/`-separated segment (space → %20, # → %23, …) while keeping the `/`
 * separators intact, so folder routes like `/api/v1/folders/<route>/browse`
 * keep addressing the same directory on the server.
 */
class PathEncodingTest {

    @Test
    fun encodesEachSegmentButKeepsSlashes() {
        assertEquals("a%20b/c%23d/e.mp4", encodePathSegments("a b/c#d/e.mp4"))
        assertEquals("e.mp4", encodePathSegments("e.mp4"))
        assertEquals("", encodePathSegments(""))
    }
}
