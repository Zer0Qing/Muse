// P4-5: material3 桥接层单测 — DynamicScheme.toColorScheme() 此前零覆盖。
// 用 material-color-utilities 构造明/暗 DynamicScheme,断言映射正确。
package io.zer0.material3

import androidx.compose.ui.graphics.Color
import dynamiccolor.DynamicScheme
import dynamiccolor.Variant
import hct.Hct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import palettes.TonalPalette

class ColorSchemeBridgeTest {

    private fun scheme(isDark: Boolean): DynamicScheme = DynamicScheme(
        sourceColorHct = Hct.fromInt(0xFF6750A4u.toInt()),
        variant = Variant.VIBRANT,
        isDark = isDark,
        contrastLevel = 0.0,
        primaryPalette = TonalPalette.fromHueAndChroma(262.0, 36.0),
        secondaryPalette = TonalPalette.fromHueAndChroma(291.0, 32.0),
        tertiaryPalette = TonalPalette.fromHueAndChroma(31.0, 71.0),
        neutralPalette = TonalPalette.fromHueAndChroma(266.0, 6.0),
        neutralVariantPalette = TonalPalette.fromHueAndChroma(267.0, 8.0),
        errorPalette = TonalPalette.fromHueAndChroma(0.0, 0.0),
    )

    @Test
    fun `dark scheme surfaces differ from light scheme`() {
        val dark = scheme(isDark = true).toColorScheme()
        val light = scheme(isDark = false).toColorScheme()
        // 明暗方案在背景与容器渐变上必须不同(否则映射失去意义)
        assertNotEquals(dark.background, light.background)
        assertNotEquals(dark.surfaceContainer, light.surfaceContainer)
        assertNotEquals(dark.surface, light.surface)
    }

    @Test
    fun `all fields map from dynamic scheme`() {
        val s = scheme(isDark = true)
        val cs = s.toColorScheme()
        assertEquals(Color(s.primary), cs.primary)
        assertEquals(Color(s.onPrimary), cs.onPrimary)
        assertEquals(Color(s.primaryContainer), cs.primaryContainer)
        assertEquals(Color(s.secondary), cs.secondary)
        assertEquals(Color(s.tertiary), cs.tertiary)
        assertEquals(Color(s.background), cs.background)
        assertEquals(Color(s.onBackground), cs.onBackground)
        assertEquals(Color(s.surface), cs.surface)
        assertEquals(Color(s.onSurface), cs.onSurface)
        assertEquals(Color(s.error), cs.error)
        assertEquals(Color(s.outline), cs.outline)
        assertEquals(Color(s.surfaceTint), cs.surfaceTint)
        assertEquals(Color(s.surfaceBright), cs.surfaceBright)
        assertEquals(Color(s.surfaceDim), cs.surfaceDim)
        assertEquals(Color(s.surfaceContainer), cs.surfaceContainer)
    }

    @Test
    fun `dark and light schemes differ on background`() {
        val dark = scheme(isDark = true).toColorScheme()
        val light = scheme(isDark = false).toColorScheme()
        assertNotEquals(dark.background, light.background)
    }
}
