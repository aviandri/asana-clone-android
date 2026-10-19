package com.mekari.project.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** QA A3: no role may fall back to M3's baseline purple (the FAB, the nav indicator, chips, sheets). */
class ThemeTest {
    private fun roles(scheme: ColorScheme) = mapOf(
        "primary" to scheme.primary,
        "primaryContainer" to scheme.primaryContainer,
        "onPrimaryContainer" to scheme.onPrimaryContainer,
        "secondary" to scheme.secondary,
        "secondaryContainer" to scheme.secondaryContainer,
        "onSecondaryContainer" to scheme.onSecondaryContainer,
        "tertiary" to scheme.tertiary,
        "tertiaryContainer" to scheme.tertiaryContainer,
        "inversePrimary" to scheme.inversePrimary,
        "surface" to scheme.surface,
        "surfaceVariant" to scheme.surfaceVariant,
        "surfaceTint" to scheme.surfaceTint,
        "surfaceContainer" to scheme.surfaceContainer,
        "surfaceContainerLow" to scheme.surfaceContainerLow,
        "surfaceContainerHigh" to scheme.surfaceContainerHigh,
        "surfaceContainerHighest" to scheme.surfaceContainerHighest,
        "outline" to scheme.outline
    )

    private fun assertNoBaseline(name: String, brand: ColorScheme, baseline: ColorScheme) {
        val baselineRoles = roles(baseline)
        val same = roles(brand).filter { (role, color) -> baselineRoles[role] == color }.keys
        assertTrue("$name roles still on the M3 baseline: $same", same.isEmpty())
    }

    @Test
    fun everyAccentAndContainerRoleIsBranded() {
        assertNoBaseline("light", LightColors, lightColorScheme())
        assertNoBaseline("dark", DarkColors, darkColorScheme())
        assertEquals(BrandMagenta, LightColors.surfaceTint)
    }

    /** Primary is a text colour too: WCAG AA (4.5:1) on every surface it's written on, and for its label. */
    @Test
    fun primaryTextMeetsAaContrast() {
        for ((name, scheme) in listOf("light" to LightColors, "dark" to DarkColors)) {
            val pairs = mapOf(
                "onPrimary on primary (filled buttons)" to (scheme.onPrimary to scheme.primary),
                "primary on surface (headers, text buttons)" to (scheme.primary to scheme.surface),
                "primary on surfaceContainer (bars)" to (scheme.primary to scheme.surfaceContainer),
                "primary on surfaceContainerHigh (dialogs)" to (scheme.primary to scheme.surfaceContainerHigh),
                "primary on surfaceContainerHighest" to (scheme.primary to scheme.surfaceContainerHighest),
                "onPrimaryContainer on primaryContainer (FAB)" to
                    (scheme.onPrimaryContainer to scheme.primaryContainer),
                "onSecondaryContainer on secondaryContainer (nav)" to
                    (scheme.onSecondaryContainer to scheme.secondaryContainer)
            )
            for ((what, pair) in pairs) {
                val ratio = contrast(pair.first, pair.second)
                assertTrue("$name: $what is ${"%.2f".format(ratio)}:1", ratio >= 4.5)
            }
        }
    }

    private fun contrast(a: Color, b: Color): Float {
        val (dark, light) = listOf(a.luminance(), b.luminance()).sorted()
        return (light + 0.05f) / (dark + 0.05f)
    }
}
