// P4-5: material3 桥接层单测 — DynamicScheme.toColorScheme() 此前零覆盖。
// 用 material-color-utilities 构造明/暗 DynamicScheme,断言映射正确。
package io.zer0.material3

import androidx.compose.material3.ColorScheme
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

    /**
     * 36 栏**逐条**比对。名单来自 `javap` 读到的 `ColorScheme` 1.4.0-alpha04 真实 getter 集合，
     * 不是照抄实现里写了什么 —— 照抄的话，"实现漏一栏"这种错永远测不出来
     * （原来那条叫 `all fields map from dynamic scheme` 的用例其实只查了 15/36）。
     *
     * 为什么写成"栏名 → (期望, 实际)"而不是两份平行列表：两份手写的清单一旦错位，
     * 断言就在比不相干的两栏，看着全绿其实什么都没测。
     */
    private fun assertEveryRoleMapped(s: DynamicScheme, cs: ColorScheme) {
        val pairs = listOf(
            "primary" to (Color(s.primary) to cs.primary),
            "onPrimary" to (Color(s.onPrimary) to cs.onPrimary),
            "primaryContainer" to (Color(s.primaryContainer) to cs.primaryContainer),
            "onPrimaryContainer" to (Color(s.onPrimaryContainer) to cs.onPrimaryContainer),
            "inversePrimary" to (Color(s.inversePrimary) to cs.inversePrimary),
            "secondary" to (Color(s.secondary) to cs.secondary),
            "onSecondary" to (Color(s.onSecondary) to cs.onSecondary),
            "secondaryContainer" to (Color(s.secondaryContainer) to cs.secondaryContainer),
            "onSecondaryContainer" to (Color(s.onSecondaryContainer) to cs.onSecondaryContainer),
            "tertiary" to (Color(s.tertiary) to cs.tertiary),
            "onTertiary" to (Color(s.onTertiary) to cs.onTertiary),
            "tertiaryContainer" to (Color(s.tertiaryContainer) to cs.tertiaryContainer),
            "onTertiaryContainer" to (Color(s.onTertiaryContainer) to cs.onTertiaryContainer),
            "background" to (Color(s.background) to cs.background),
            "onBackground" to (Color(s.onBackground) to cs.onBackground),
            "surface" to (Color(s.surface) to cs.surface),
            "onSurface" to (Color(s.onSurface) to cs.onSurface),
            "surfaceVariant" to (Color(s.surfaceVariant) to cs.surfaceVariant),
            "onSurfaceVariant" to (Color(s.onSurfaceVariant) to cs.onSurfaceVariant),
            "surfaceTint" to (Color(s.surfaceTint) to cs.surfaceTint),
            "inverseSurface" to (Color(s.inverseSurface) to cs.inverseSurface),
            "inverseOnSurface" to (Color(s.inverseOnSurface) to cs.inverseOnSurface),
            "error" to (Color(s.error) to cs.error),
            "onError" to (Color(s.onError) to cs.onError),
            "errorContainer" to (Color(s.errorContainer) to cs.errorContainer),
            "onErrorContainer" to (Color(s.onErrorContainer) to cs.onErrorContainer),
            "outline" to (Color(s.outline) to cs.outline),
            "outlineVariant" to (Color(s.outlineVariant) to cs.outlineVariant),
            "scrim" to (Color(s.scrim) to cs.scrim),
            "surfaceBright" to (Color(s.surfaceBright) to cs.surfaceBright),
            "surfaceDim" to (Color(s.surfaceDim) to cs.surfaceDim),
            "surfaceContainer" to (Color(s.surfaceContainer) to cs.surfaceContainer),
            "surfaceContainerHigh" to (Color(s.surfaceContainerHigh) to cs.surfaceContainerHigh),
            "surfaceContainerHighest" to (
                Color(s.surfaceContainerHighest) to cs.surfaceContainerHighest
            ),
            "surfaceContainerLow" to (Color(s.surfaceContainerLow) to cs.surfaceContainerLow),
            "surfaceContainerLowest" to (Color(s.surfaceContainerLowest) to cs.surfaceContainerLowest),
        )

        assertEquals("这一版 ColorScheme 一共 36 栏（javap 实测），清单要跟得上", 36, pairs.size)
        pairs.forEach { (name, pair) ->
            assertEquals("$name 映射的不是 DynamicScheme 的同名栏", pair.first, pair.second)
        }
    }

    @Test
    fun `暗色方案 36 栏逐条映射`() {
        val s = scheme(isDark = true)

        assertEveryRoleMapped(s, s.toColorScheme())
    }

    @Test
    fun `亮色方案 36 栏逐条映射`() {
        // 实现里有 buildDark / buildLight **两套**构造器：漏一栏可能只发生在其中一套，
        // 所以两边各测一次，不能只测暗色就以为都覆盖了。
        val s = scheme(isDark = false)

        assertEveryRoleMapped(s, s.toColorScheme())
    }

    @Test
    fun `dark scheme surfaces differ from light scheme`() {
        val dark = scheme(isDark = true).toColorScheme()
        val light = scheme(isDark = false).toColorScheme()
        // 明暗方案在背景与容器梯度上必须不同(否则映射失去意义)
        assertNotEquals(dark.background, light.background)
        assertNotEquals(dark.surfaceContainer, light.surfaceContainer)
        assertNotEquals(dark.surface, light.surface)
    }

    @Test
    fun `dark and light schemes differ on background`() {
        val dark = scheme(isDark = true).toColorScheme()
        val light = scheme(isDark = false).toColorScheme()
        assertNotEquals(dark.background, light.background)
    }
}
