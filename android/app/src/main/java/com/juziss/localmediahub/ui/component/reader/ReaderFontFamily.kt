package com.juziss.localmediahub.ui.component.reader

import androidx.compose.ui.text.font.FontFamily

enum class ReaderFontFamily(val label: String) {
    SYSTEM("无衬线"),
    SERIF("宋体"),
    KAITI("楷体（部分设备显示为宋体）");

    fun toFontFamily(): FontFamily = when (this) {
        SYSTEM -> FontFamily.Default
        SERIF  -> FontFamily.Serif
        KAITI  -> FontFamily.Serif
    }
}
