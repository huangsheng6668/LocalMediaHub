package com.juziss.localmediahub.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollFabVisibilityTest {

    @Test
    fun `empty or single item list shows neither top nor bottom`() {
        val res0 = calculateScrollFabVisibility(
            firstVisibleIndex = 0,
            firstVisibleOffset = 0,
            lastVisibleIndex = 0,
            totalItems = 0,
            visibleCount = 0,
        )
        assertFalse(res0.canScrollToTop)
        assertFalse(res0.canScrollToBottom)

        val res1 = calculateScrollFabVisibility(
            firstVisibleIndex = 0,
            firstVisibleOffset = 0,
            lastVisibleIndex = 0,
            totalItems = 1,
            visibleCount = 1,
        )
        assertFalse(res1.canScrollToTop)
        assertFalse(res1.canScrollToBottom)
    }

    @Test
    fun `content fully visible on screen shows neither top nor bottom`() {
        val res = calculateScrollFabVisibility(
            firstVisibleIndex = 0,
            firstVisibleOffset = 0,
            lastVisibleIndex = 4,
            totalItems = 5,
            visibleCount = 5,
        )
        assertFalse(res.canScrollToTop)
        assertFalse(res.canScrollToBottom)
    }

    @Test
    fun `at top of long list shows only bottom button`() {
        val res = calculateScrollFabVisibility(
            firstVisibleIndex = 0,
            firstVisibleOffset = 50,
            lastVisibleIndex = 8,
            totalItems = 50,
            visibleCount = 9,
            offsetThreshold = 100,
        )
        assertFalse(res.canScrollToTop)
        assertTrue(res.canScrollToBottom)
    }

    @Test
    fun `in middle of long list shows both top and bottom buttons`() {
        val res = calculateScrollFabVisibility(
            firstVisibleIndex = 15,
            firstVisibleOffset = 0,
            lastVisibleIndex = 23,
            totalItems = 50,
            visibleCount = 9,
            offsetThreshold = 100,
        )
        assertTrue(res.canScrollToTop)
        assertTrue(res.canScrollToBottom)
    }

    @Test
    fun `at bottom of long list shows only top button`() {
        val res = calculateScrollFabVisibility(
            firstVisibleIndex = 42,
            firstVisibleOffset = 150,
            lastVisibleIndex = 49,
            totalItems = 50,
            visibleCount = 8,
            offsetThreshold = 100,
        )
        assertTrue(res.canScrollToTop)
        assertFalse(res.canScrollToBottom)
    }
}
