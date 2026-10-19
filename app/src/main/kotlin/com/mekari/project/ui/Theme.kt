package com.mekari.project.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Brand magenta; dynamic colour stays off so the brand colours are used (SPEC-ANDROID §2). */
val BrandMagenta = Color(0xFFE0257F)
private val BrandMagentaDark = Color(0xFFB0136B)
private val BrandMagentaLight = Color(0xFFFF5FA2)

/**
 * Every role is mapped, not just primary: M3 falls back to its baseline purple for the rest, which put a
 * purple FAB and a grey nav indicator on the device (QA A3). Containers are the brand's tonal steps, the
 * tertiary is the petals' purple and the neutrals carry a faint magenta tint.
 */
internal val LightColors: ColorScheme = lightColorScheme(
    primary = BrandMagenta,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E6),
    onPrimaryContainer = Color(0xFF3E0021),
    inversePrimary = Color(0xFFFFB0CC),
    secondary = BrandMagentaDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E2),
    onSecondaryContainer = Color(0xFF3B0624),
    tertiary = Color(0xFF6B3FD4),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEADDFF),
    onTertiaryContainer = Color(0xFF25005A),
    background = Color(0xFFFFF8F8),
    onBackground = Color(0xFF22191C),
    surface = Color(0xFFFFF8F8),
    onSurface = Color(0xFF22191C),
    surfaceVariant = Color(0xFFF3DDE3),
    onSurfaceVariant = Color(0xFF524348),
    surfaceTint = BrandMagenta,
    inverseSurface = Color(0xFF382E31),
    inverseOnSurface = Color(0xFFFFECF0),
    outline = Color(0xFF847378),
    outlineVariant = Color(0xFFD6C1C7),
    surfaceBright = Color(0xFFFFF8F8),
    surfaceDim = Color(0xFFE6D6DA),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFF0F3),
    surfaceContainer = Color(0xFFFBEAEE),
    surfaceContainerHigh = Color(0xFFF5E4E8),
    surfaceContainerHighest = Color(0xFFEFDEE2)
)

internal val DarkColors: ColorScheme = darkColorScheme(
    primary = BrandMagentaLight,
    onPrimary = Color(0xFF3E0021),
    primaryContainer = Color(0xFF8E0050),
    onPrimaryContainer = Color(0xFFFFD9E6),
    inversePrimary = BrandMagentaDark,
    secondary = Color(0xFFFFB0CC),
    onSecondary = Color(0xFF5B1138),
    secondaryContainer = Color(0xFF6E1E47),
    onSecondaryContainer = Color(0xFFFFD9E6),
    tertiary = Color(0xFFD0BCFF),
    onTertiary = Color(0xFF3B1C78),
    tertiaryContainer = Color(0xFF53359F),
    onTertiaryContainer = Color(0xFFEADDFF),
    background = Color(0xFF191114),
    onBackground = Color(0xFFEFDFE2),
    surface = Color(0xFF191114),
    onSurface = Color(0xFFEFDFE2),
    surfaceVariant = Color(0xFF524348),
    onSurfaceVariant = Color(0xFFD6C1C7),
    surfaceTint = BrandMagentaLight,
    inverseSurface = Color(0xFFEFDFE2),
    inverseOnSurface = Color(0xFF382E31),
    outline = Color(0xFF9F8C91),
    outlineVariant = Color(0xFF524348),
    surfaceBright = Color(0xFF41373A),
    surfaceDim = Color(0xFF191114),
    surfaceContainerLowest = Color(0xFF140C0F),
    surfaceContainerLow = Color(0xFF22191C),
    surfaceContainer = Color(0xFF261D20),
    surfaceContainerHigh = Color(0xFF31272A),
    surfaceContainerHighest = Color(0xFF3C3235)
)

@Composable
fun MekariProjectTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
