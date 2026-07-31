package com.juziss.localmediahub.ui.component.reader

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.juziss.localmediahub.R

enum class ReaderFontFamily(val label: String) {
    SYSTEM("无衬线"),
    SERIF("宋体"),
    KAITI("楷体·文楷"),
    MONO("等宽");

    fun toFontFamily(): FontFamily = when (this) {
        SYSTEM -> FontFamily.Default
        SERIF  -> FontFamily.Serif
        KAITI  -> FontFamily(Font(R.font.lxgw_wenkai))
        MONO   -> FontFamily.Monospace
    }
}
