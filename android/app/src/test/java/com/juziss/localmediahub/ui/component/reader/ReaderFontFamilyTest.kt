package com.juziss.localmediahub.ui.component.reader

import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReaderFontFamilyTest {

    /** 回退方案：全量字体 ~25MB 超出 spec 上限（4-13MB），KAITI 回退到系统宋体。 */
    @Test
    fun kaiti_fallback_maps_to_serif() {
        assertEquals(FontFamily.Serif, ReaderFontFamily.KAITI.toFontFamily())
    }

    @Test
    fun mono_maps_to_monospace() {
        assertEquals(FontFamily.Monospace, ReaderFontFamily.MONO.toFontFamily())
    }
}
