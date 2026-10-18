package com.mekari.project.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Brand magenta; dynamic colour stays off so the brand colours are used (SPEC-ANDROID §2). */
val BrandMagenta = Color(0xFFE0257F)
private val BrandMagentaDark = Color(0xFFB0136B)
private val BrandMagentaLight = Color(0xFFFF5FA2)

private val LightColors = lightColorScheme(primary = BrandMagenta, secondary = BrandMagentaDark)
private val DarkColors = darkColorScheme(primary = BrandMagentaLight, secondary = BrandMagenta)

@Composable
fun MekariProjectTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
