package com.juziss.localmediahub.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7AD4D2),
    secondary = Color(0xFFFFBC6D),
    tertiary = Color(0xFFC8D78E),
    background = Color(0xFF0D1718),
    surface = Color(0xFF142022),
    surfaceVariant = Color(0xFF213235),
    primaryContainer = Color(0xFF1C3A3F),
    secondaryContainer = Color(0xFF473017),
    onPrimary = Color(0xFF062A2C),
    onSecondary = Color(0xFF381B00),
    onTertiary = Color.White,
    onBackground = Color(0xFFE9F1EF),
    onSurface = Color(0xFFE9F1EF),
    onSurfaceVariant = Color(0xFFADC4C1),
    outline = Color(0xFF67817F),
    outlineVariant = Color(0xFF314749),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF135F65),
    secondary = Color(0xFFB96D1D),
    tertiary = Color(0xFF647A33),
    background = Color(0xFFF6F1E8),
    surface = Color(0xFFFFFBF7),
    surfaceVariant = Color(0xFFE6E3DA),
    primaryContainer = Color(0xFFDDF1F1),
    secondaryContainer = Color(0xFFFFE4C9),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1A1F1C),
    onSurface = Color(0xFF1A1F1C),
    onSurfaceVariant = Color(0xFF5A6462),
    outline = Color(0xFF7A8683),
    outlineVariant = Color(0xFFC7D0CC),
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 31.sp,
        lineHeight = 37.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 25.sp,
        lineHeight = 31.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

@Composable
fun LocalMediaHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> LightColorScheme
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
