package com.mekari.project.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
        assertEquals(BrandMagenta, LightColors.primary)
        assertEquals(BrandMagenta, LightColors.surfaceTint)
    }
}
