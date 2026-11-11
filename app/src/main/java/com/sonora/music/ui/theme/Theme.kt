package com.sonora.music.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Accent = Color(0xFFC6F432)          // electric lime
val Background = Color(0xFF09090B)

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF0B0F00),
    primaryContainer = Color(0xFF2A3510),
    onPrimaryContainer = Accent,
    secondary = Color(0xFF9D8CFF),
    onSecondary = Color(0xFF120B33),
    tertiary = Color(0xFFFF7A9C),
    background = Background,
    onBackground = Color(0xFFF4F4F5),
    surface = Background,
    onSurface = Color(0xFFF4F4F5),
    surfaceVariant = Color(0xFF1C1C21),
    onSurfaceVariant = Color(0xFFA1A1AA),
    surfaceContainerLowest = Color(0xFF050506),
    surfaceContainerLow = Color(0xFF101013),
    surfaceContainer = Color(0xFF141418),
    surfaceContainerHigh = Color(0xFF1C1C21),
    surfaceContainerHighest = Color(0xFF26262C),
    outline = Color(0xFF3F3F46),
    outlineVariant = Color(0xFF27272A),
    error = Color(0xFFFF6B6B),
)

private val Base = Typography()
private val SonoraTypography = Base.copy(
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
fun SonoraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = SonoraTypography, content = content)
}
