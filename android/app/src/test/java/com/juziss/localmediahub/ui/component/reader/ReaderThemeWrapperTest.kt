package com.juziss.localmediahub.ui.component.reader

import androidx.compose.ui.graphics.Color
import com.juziss.localmediahub.data.ReaderSettings
import com.juziss.localmediahub.data.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReaderThemeWrapperTest {

    @Test
    fun auto_resolves_to_day_or_night() {
        assertEquals(ReaderTheme.DAY.bg, resolveReaderColors(ReaderTheme.AUTO, isDark = false, custom = null).bg)
        assertEquals(ReaderTheme.NIGHT.bg, resolveReaderColors(ReaderTheme.AUTO, isDark = true, custom = null).bg)
    }

    @Test
    fun custom_uses_three_colors_and_derives_chrome() {
        val custom = CustomReaderColors(Color(0xFF112233), Color(0xFF445566), Color(0xFF778899))
        val r = resolveReaderColors(ReaderTheme.CUSTOM, isDark = false, custom = custom)
        assertEquals(Color(0xFF112233), r.bg)
        assertEquals(Color(0xFF445566), r.fg)
        assertEquals(Color(0xFF778899), r.muted)
        assertEquals(Color(0xFF112233), r.chromeBg)  // chromeBg = bg
        assertEquals(Color(0xFF445566), r.chromeFg)  // chromeFg = fg
        assertEquals(Color(0xFF778899), r.border)    // border = muted
    }

    @Test
    fun custom_null_fields_fall_back_by_system_mode() {
        val custom = CustomReaderColors(null, null, null)
        val day = resolveReaderColors(ReaderTheme.CUSTOM, isDark = false, custom = custom)
        assertEquals(ReaderTheme.DAY.bg, day.bg)
        assertEquals(ReaderTheme.DAY.fg, day.fg)
        assertEquals(ReaderTheme.DAY.muted, day.muted)
        val night = resolveReaderColors(ReaderTheme.CUSTOM, isDark = true, custom = custom)
        assertEquals(ReaderTheme.NIGHT.bg, night.bg)
    }

    @Test
    fun concrete_theme_uses_preset_colors() {
        val r = resolveReaderColors(ReaderTheme.EYE_CARE, isDark = false, custom = null)
        assertEquals(ReaderTheme.EYE_CARE.bg, r.bg)
        assertEquals(ReaderTheme.EYE_CARE.muted, r.muted)
    }

    @Test
    fun hex_parsing_accepts_rrggbb_and_rejects_garbage() {
        assertEquals(Color(0xFFABCDEF), "#ABCDEF".toComposeColorOrNull())
        assertEquals(Color(0xFF1A2B3C), "#1a2b3c".toComposeColorOrNull())
        assertNull("red".toComposeColorOrNull())
        assertNull("#12345".toComposeColorOrNull())
        assertNull((null as String?).toComposeColorOrNull())
    }

    @Test
    fun settings_without_custom_colors_produce_empty_custom() {
        val c = ReaderSettings().toCustomReaderColors()
        assertNull(c.bg)
        assertNull(c.fg)
        assertNull(c.muted)
    }
}
