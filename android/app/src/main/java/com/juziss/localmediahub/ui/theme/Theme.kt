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

// Ink Editorial（spec 2026-09-27 §3.1）：primary = 墨黑主操作（暗色反转），
// secondary = 苔绿（进度/焦点/在读），背景 = 暖灰纸面 / 深 slate。
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFE7E9EE),
    secondary = Color(0xFF8FBF9F),
    tertiary = Color(0xFFA3CDAF),
    background = Color(0xFF0C0D10),
    surface = Color(0xFF15161A),
    surfaceVariant = Color(0xFF1F2128),
    primaryContainer = Color(0xFF26282C),
    secondaryContainer = Color(0xFF1C2A20),
    onPrimary = Color(0xFF0F172A),
    onSecondary = Color(0xFF0F172A),
    onTertiary = Color(0xFF0F172A),
    onBackground = Color(0xFFE7E9EE),
    onSurface = Color(0xFFE7E9EE),
    onSurfaceVariant = Color(0xFF9BA1AC),
    outline = Color(0xFF22242B),
    outlineVariant = Color(0xFF22242B),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF26282E),
    secondary = Color(0xFF3D6B4F),
    tertiary = Color(0xFF2F5640),
    background = Color(0xFFF7F7F5),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F1EE),
    primaryContainer = Color(0xFFE9E9E4),
    secondaryContainer = Color(0xFFE4EDE6),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF52575E),
    outline = Color(0xFFE5E4DF),
    outlineVariant = Color(0xFFE5E4DF),
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

private val EyeCareGreenColorScheme = lightColorScheme(
    primary = Color(0xFF33593C),
    secondary = Color(0xFF2A4831),
    tertiary = Color(0xFF48584A),
    background = Color(0xFFDDE6DA),
    surface = Color(0xFFEDF2EA),
    surfaceVariant = Color(0xFFD2DECF),
    primaryContainer = Color(0xFFE4EDE6),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF1E2A20),
    onSurface = Color(0xFF1E2A20),
    onSurfaceVariant = Color(0xFF3E4C40),
    outline = Color(0xFFC6D4C2),
)

private val EyeCareColorScheme = lightColorScheme(
    primary = Color(0xFF4A6B52),
    secondary = Color(0xFF3C5843),
    tertiary = Color(0xFF6B5E48),
    background = Color(0xFFF5F1E6),
    surface = Color(0xFFFBF8F0),
    surfaceVariant = Color(0xFFEFE9DA),
    primaryContainer = Color(0xFFEAE3D2),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF3B3428),
    onSurface = Color(0xFF3B3428),
    onSurfaceVariant = Color(0xFF5E5442),
    outline = Color(0xFFE2D9C4),
)

private val ParchmentColorScheme = lightColorScheme(
    primary = Color(0xFF4E6B44),
    secondary = Color(0xFF3F5837),
    tertiary = Color(0xFF75654F),
    background = Color(0xFFEFE8D5),
    surface = Color(0xFFF7F1E2),
    surfaceVariant = Color(0xFFE7DEC7),
    primaryContainer = Color(0xFFE4DAC3),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF3D3327),
    onSurface = Color(0xFF3D3327),
    onSurfaceVariant = Color(0xFF5C5040),
    outline = Color(0xFFDCD0B5),
)

private val NightBlackColorScheme = darkColorScheme(
    primary = Color(0xFFE7E9EE),
    secondary = Color(0xFF9BA1AC),
    tertiary = Color(0xFF74787F),
    background = Color.Black,
    surface = Color(0xFF101114),
    surfaceVariant = Color(0xFF1A1C22),
    primaryContainer = Color(0xFF22242B),
    onPrimary = Color(0xFF0F172A),
    onSecondary = Color(0xFF0F172A),
    onBackground = Color(0xFFE7E9EE),
    onSurface = Color(0xFFE7E9EE),
    onSurfaceVariant = Color(0xFF9BA1AC),
    outline = Color(0xFF202228),
)

@Composable
fun LocalMediaHubTheme(
    themeKey: String = "AUTO",
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val upper = themeKey.uppercase()
    val (colorScheme, outlineSoft, primaryText) = when (upper) {
        "EYE_CARE_GREEN" -> Triple(EyeCareGreenColorScheme, OutlineSoft.EyeCareGreen, PrimaryText.EyeCareGreen)
        "EYE_CARE" -> Triple(EyeCareColorScheme, OutlineSoft.EyeCare, PrimaryText.EyeCare)
        "PARCHMENT" -> Triple(ParchmentColorScheme, OutlineSoft.Parchment, PrimaryText.Parchment)
        "NIGHT_BLACK" -> Triple(NightBlackColorScheme, OutlineSoft.NightBlack, PrimaryText.NightBlack)
        "NIGHT" -> Triple(DarkColorScheme, OutlineSoft.Dark, PrimaryText.Dark)
        "DAY", "DAY_BRIGHT" -> Triple(LightColorScheme, OutlineSoft.Light, PrimaryText.Light)
        else -> Triple(
            if (darkTheme) DarkColorScheme else LightColorScheme,
            if (darkTheme) OutlineSoft.Dark else OutlineSoft.Light,
            if (darkTheme) PrimaryText.Dark else PrimaryText.Light,
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
    ) {
        ProvideOutlineSoft(outlineSoft) {
            ProvidePrimaryText(primaryText) {
                // See NoRippleIndication.kt — overrides Material 1.3.1's legacy PlatformRipple
                // (which only implements Indication, not IndicationNodeFactory) so that
                // foundation 1.11.x's clickable doesn't crash on release R8 builds.
                ProvideNoRippleIndication(content)
            }
        }
    }
}
