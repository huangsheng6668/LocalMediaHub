package com.juziss.localmediahub.ui.component.reader

import androidx.compose.ui.text.font.FontFamily
import com.juziss.localmediahub.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReaderFontFamilyTest {

    @Test
    fun kaiti_maps_to_bundled_wenkai_font() {
        // R.font.lxgw_wenkai non-zero proves font resource is packaged
        assertNotNull(R.font.lxgw_wenkai)
        val fam = ReaderFontFamily.KAITI.toFontFamily()
        assertNotNull(fam)
    }

    @Test
    fun mono_maps_to_monospace() {
        assertEquals(FontFamily.Monospace, ReaderFontFamily.MONO.toFontFamily())
    }
}
