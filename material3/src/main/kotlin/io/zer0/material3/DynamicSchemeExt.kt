// v1.97 gap7: DynamicScheme → Compose ColorScheme 桥接扩展。
//
// 将 material-color-utilities 的 DynamicScheme (HCT 色彩空间生成的方案)
// 转换为 Compose Material3 的 ColorScheme,使 CustomTheme.generateColorScheme
// 能直接产出可用的 ColorScheme 注入 MuseTheme。
//
// 参考实现:rikkahub material3 模块的 DynamicSchemeExt.kt(Apache 2.0)。

package io.zer0.material3

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import dynamiccolor.DynamicScheme

/**
 * 将 [DynamicScheme] 转换为 Compose Material3 的 [ColorScheme]。
 *
 * DynamicScheme 的所有颜色属性都是 ARGB Int,这里逐字段映射到 Compose Color。
 * 根据 [DynamicScheme.isDark] 选择 [darkColorScheme] 或 [lightColorScheme] 构造器,
 * 保证 Material3 容器梯度(surfaceContainer 系列 / surfaceDim / surfaceBright)
 * 都正确填充。
 *
 * 注:DynamicScheme 还提供 primaryFixed / secondaryFixed / tertiaryFixed 等
 * "固定色"(Material3 Expressive 引入),但当前 Compose Material3 1.4.0-alpha04
 * 的 ColorScheme 尚未包含这些字段,故此处不映射;后续升级 Compose 版本后可补齐。
 */
fun DynamicScheme.toColorScheme(): ColorScheme {
    val s = this
    return if (isDark) {
        darkColorScheme(
            primary = Color(s.primary),
            onPrimary = Color(s.onPrimary),
            primaryContainer = Color(s.primaryContainer),
            onPrimaryContainer = Color(s.onPrimaryContainer),
            inversePrimary = Color(s.inversePrimary),
            secondary = Color(s.secondary),
            onSecondary = Color(s.onSecondary),
            secondaryContainer = Color(s.secondaryContainer),
            onSecondaryContainer = Color(s.onSecondaryContainer),
            tertiary = Color(s.tertiary),
            onTertiary = Color(s.onTertiary),
            tertiaryContainer = Color(s.tertiaryContainer),
            onTertiaryContainer = Color(s.onTertiaryContainer),
            background = Color(s.background),
            onBackground = Color(s.onBackground),
            surface = Color(s.surface),
            onSurface = Color(s.onSurface),
            surfaceVariant = Color(s.surfaceVariant),
            onSurfaceVariant = Color(s.onSurfaceVariant),
            surfaceTint = Color(s.surfaceTint),
            inverseSurface = Color(s.inverseSurface),
            inverseOnSurface = Color(s.inverseOnSurface),
            error = Color(s.error),
            onError = Color(s.onError),
            errorContainer = Color(s.errorContainer),
            onErrorContainer = Color(s.onErrorContainer),
            outline = Color(s.outline),
            outlineVariant = Color(s.outlineVariant),
            scrim = Color(s.scrim),
            surfaceBright = Color(s.surfaceBright),
            surfaceDim = Color(s.surfaceDim),
            surfaceContainer = Color(s.surfaceContainer),
            surfaceContainerHigh = Color(s.surfaceContainerHigh),
            surfaceContainerHighest = Color(s.surfaceContainerHighest),
            surfaceContainerLow = Color(s.surfaceContainerLow),
            surfaceContainerLowest = Color(s.surfaceContainerLowest),
        )
    } else {
        lightColorScheme(
            primary = Color(s.primary),
            onPrimary = Color(s.onPrimary),
            primaryContainer = Color(s.primaryContainer),
            onPrimaryContainer = Color(s.onPrimaryContainer),
            inversePrimary = Color(s.inversePrimary),
            secondary = Color(s.secondary),
            onSecondary = Color(s.onSecondary),
            secondaryContainer = Color(s.secondaryContainer),
            onSecondaryContainer = Color(s.onSecondaryContainer),
            tertiary = Color(s.tertiary),
            onTertiary = Color(s.onTertiary),
            tertiaryContainer = Color(s.tertiaryContainer),
            onTertiaryContainer = Color(s.onTertiaryContainer),
            background = Color(s.background),
            onBackground = Color(s.onBackground),
            surface = Color(s.surface),
            onSurface = Color(s.onSurface),
            surfaceVariant = Color(s.surfaceVariant),
            onSurfaceVariant = Color(s.onSurfaceVariant),
            surfaceTint = Color(s.surfaceTint),
            inverseSurface = Color(s.inverseSurface),
            inverseOnSurface = Color(s.inverseOnSurface),
            error = Color(s.error),
            onError = Color(s.onError),
            errorContainer = Color(s.errorContainer),
            onErrorContainer = Color(s.onErrorContainer),
            outline = Color(s.outline),
            outlineVariant = Color(s.outlineVariant),
            scrim = Color(s.scrim),
            surfaceBright = Color(s.surfaceBright),
            surfaceDim = Color(s.surfaceDim),
            surfaceContainer = Color(s.surfaceContainer),
            surfaceContainerHigh = Color(s.surfaceContainerHigh),
            surfaceContainerHighest = Color(s.surfaceContainerHighest),
            surfaceContainerLow = Color(s.surfaceContainerLow),
            surfaceContainerLowest = Color(s.surfaceContainerLowest),
        )
    }
}
