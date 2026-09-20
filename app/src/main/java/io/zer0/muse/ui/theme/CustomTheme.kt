package io.zer0.muse.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import dynamiccolor.ColorSpecs
import dynamiccolor.DynamicScheme
import dynamiccolor.Variant
import hct.Hct
import io.zer0.material3.toColorScheme
import kotlinx.serialization.Serializable
import palettes.TonalPalette
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * v1.97 gap7: 用户自定义主题 — 基于种子色生成完整 ColorScheme。
 *
 * 用户在设置页选择 1~3 个种子色(primary 必填,secondary/tertiary 可选),
 * 调用 [generateColorScheme] 时通过 Material Color Utilities 的 HCT 色彩空间
 * 与 Variant.TONAL_SPOT 算法(Android 12+ 壁纸动态色同款)自动派生完整配色。
 *
 * 持久化:序列化为 JSON 存入 DataStore Preferences(见 SettingsRepository.customThemesFlow)。
 * 与 [PresetTheme] 的区别:PresetTheme 是手工调校的固定 ColorScheme;CustomTheme 由算法生成,
 * 用户只需选种子色,无需理解 ColorScheme 的 50+ 字段。
 *
 * 实现说明:既有实现 CustomTheme.kt(Apache 2.0)。
 *
 * @param id 唯一标识(自动生成 UUID)
 * @param name 用户可见名称(允许空串,UI 显示时回退"未命名")
 * @param primaryColorArgb 主种子色 ARGB(默认月桂绿,与 muse 品牌色一致)
 * @param secondaryColorArgb 次种子色,null 表示由 primary 自动派生
 * @param tertiaryColorArgb 三级种子色,null 表示由 primary 自动派生
 */
@Serializable
@OptIn(ExperimentalUuidApi::class)
data class CustomTheme(
    val id: String = Uuid.random().toString(),
    val name: String = "",
    val primaryColorArgb: Long = DEFAULT_PRIMARY_ARGB,
    val secondaryColorArgb: Long? = null,
    val tertiaryColorArgb: Long? = null,
) {
    /**
     * 基于种子色生成 [dark] 或浅色模式的完整 [ColorScheme]。
     *
     * 算法链:
     *  1. 主种子色 → [Hct.fromInt] 得到 HCT 色彩空间表示
     *  2. 用 [ColorSpecs] 按 spec 版本派生 6 个 TonalPalette
     *     (primary/secondary/tertiary/neutral/neutralVariant/error)
     *  3. 若 secondary/tertiary 显式指定,用 [TonalPalette.fromInt] 覆盖对应 palette
     *  4. 组装 [DynamicScheme] → 调用 [toColorScheme] 扩展转换为 Compose ColorScheme
     *
     * 使用 Variant.TONAL_SPOT — 与 Android 12+ 壁纸动态色使用的同一 variant,
     * 保证生成结果与系统级动态色风格一致。contrastLevel=0.0 表示标准对比度
     * (设计规范基线,-1 为最低对比度,1 为最高对比度)。
     *
     * @param dark true 生成深色 ColorScheme,false 生成浅色
     * @return 完整的 Compose Material3 ColorScheme(含 surfaceContainer 梯度与 fixed 色)
     */
    fun generateColorScheme(dark: Boolean): ColorScheme {
        val sourceHct = Hct.fromInt(primaryColorArgb.toInt())
        val specVersion = DynamicScheme.DEFAULT_SPEC_VERSION
        val platform = DynamicScheme.DEFAULT_PLATFORM
        val contrastLevel = 0.0
        val colorSpec = ColorSpecs.get(specVersion)

        val primaryPalette = colorSpec.getPrimaryPalette(
            Variant.TONAL_SPOT, sourceHct, dark, platform, contrastLevel,
        )
        // secondary/tertiary 未指定时,由 primary 自动派生(默认行为,与系统动态色一致)
        val secondaryPalette = if (secondaryColorArgb != null) {
            TonalPalette.fromInt(secondaryColorArgb.toInt())
        } else {
            colorSpec.getSecondaryPalette(
                Variant.TONAL_SPOT, sourceHct, dark, platform, contrastLevel,
            )
        }
        val tertiaryPalette = if (tertiaryColorArgb != null) {
            TonalPalette.fromInt(tertiaryColorArgb.toInt())
        } else {
            colorSpec.getTertiaryPalette(
                Variant.TONAL_SPOT, sourceHct, dark, platform, contrastLevel,
            )
        }

        val scheme = DynamicScheme(
            sourceHct,
            Variant.TONAL_SPOT,
            dark,
            contrastLevel,
            platform,
            specVersion,
            primaryPalette,
            secondaryPalette,
            tertiaryPalette,
            colorSpec.getNeutralPalette(
                Variant.TONAL_SPOT, sourceHct, dark, platform, contrastLevel,
            ),
            colorSpec.getNeutralVariantPalette(
                Variant.TONAL_SPOT, sourceHct, dark, platform, contrastLevel,
            ),
            colorSpec.getErrorPalette(
                Variant.TONAL_SPOT, sourceHct, dark, platform, contrastLevel,
            ),
        )
        return whitenNeutrals(scheme.toColorScheme(), dark)
    }

    /**
     * v1.0.93: 把算法生成的中性色往白端拉一档。
     *
     * Material You 的 neutral 调板偏灰偏浊(浅色下 surfaceVariant 约 tone 90 → #E1E3E1，
     * surfaceContainerHigh 约 #E4E4E4)，在手调预设里看着像脏了一度。
     *
     * 这里只动 surface 家族与 outlineVariant：这些色与文字色不是成对的，只改底色不会破坏对比度，
     * 反而把前景文字对比度抬高了。inverseSurface / 主色 / 状态色 / 所有 on* 颜色一律不动。
     */
    private fun whitenNeutrals(base: ColorScheme, dark: Boolean): ColorScheme = if (dark) {
        base.copy(
            surfaceDim = liftTowardWhite(base.surfaceDim, 0.02f),
            surfaceBright = liftTowardWhite(base.surfaceBright, 0.05f),
            surfaceContainerLowest = liftTowardWhite(base.surfaceContainerLowest, 0.02f),
            surfaceContainerLow = liftTowardWhite(base.surfaceContainerLow, 0.04f),
            surfaceContainer = liftTowardWhite(base.surfaceContainer, 0.06f),
            surfaceContainerHigh = liftTowardWhite(base.surfaceContainerHigh, 0.08f),
            surfaceContainerHighest = liftTowardWhite(base.surfaceContainerHighest, 0.10f),
            surfaceVariant = liftTowardWhite(base.surfaceVariant, 0.08f),
            outlineVariant = liftTowardWhite(base.outlineVariant, 0.08f),
        )
    } else {
        base.copy(
            surfaceContainerLowest = liftTowardWhite(base.surfaceContainerLowest, 0.20f),
            surfaceContainerLow = liftTowardWhite(base.surfaceContainerLow, 0.35f),
            surfaceContainer = liftTowardWhite(base.surfaceContainer, 0.45f),
            surfaceContainerHigh = liftTowardWhite(base.surfaceContainerHigh, 0.50f),
            surfaceContainerHighest = liftTowardWhite(base.surfaceContainerHighest, 0.55f),
            surfaceVariant = liftTowardWhite(base.surfaceVariant, 0.55f),
            surfaceDim = liftTowardWhite(base.surfaceDim, 0.35f),
            outlineVariant = liftTowardWhite(base.outlineVariant, 0.45f),
        )
    }

    /** 把颜色向纯白拉 [amount](0..1)，只改亮度不做色相偏移。 */
    private fun liftTowardWhite(color: Color, amount: Float): Color = Color(
        red = color.red + (1f - color.red) * amount,
        green = color.green + (1f - color.green) * amount,
        blue = color.blue + (1f - color.blue) * amount,
        alpha = color.alpha,
    )

    companion object {
        /**
         * 默认主种子色 — 月桂绿(与 muse 品牌色一致)。
         * v1.0.74 fix (前端审计 3.10): 原值 0xFF2D8C5F 是旧版 LaurelGreen,
         * Color.kt v1.0.21 已改为 #2A7A55,此处同步对齐避免自定义主题默认色漂移。
         *
         * 用 ARGB Long 表示(高 8 位 alpha=0xFF),避免 Int 负数表示带来的混淆。
         */
        const val DEFAULT_PRIMARY_ARGB: Long = 0xFF2A7A55L
    }
}
