package io.zer0.muse.ui.common.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseCornerRadius

/**
 * iOS 风格分组容器 — 一组共用圆角容器,行间用细分割线分隔。
 *
 * v0.34: 自包含实现 — 渲染为 Surface 卡片(surfaceVariant 0.5f 背景 + 20dp 圆角),
 * 与 [io.zer0.muse.ui.common.surface.CardGroup] 视觉一致。保留此兼容封装供现有二级页
 * (BackupSection / ThemeSection / AboutSection 等)继续使用;一级设置页与新页面
 * 直接用 CardGroup + DSL item。
 *
 * 对标 iOS SwiftUI `Form` / `List(in:)` 的分组视觉。
 *
 * 用法:
 * ```
 * SettingsGroup {
 *     SettingsItemRow(icon, "标题", "副标题") { onClick() }
 *     SettingsGroupDivider()
 *     SettingsSwitchRow(icon, "开关", checked) { onChange(it) }
 * }
 * ```
 *
 * @param modifier Modifier(默认 fillMaxWidth)
 * @param content 分组内容(由调用方插入 [SettingsItemRow] / [SettingsSwitchRow] / [SettingsGroupDivider])
 */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val isLight = colorScheme.surface.luminance() > 0.5f
    // iOS 分组卡片: light=white@96%, dark=white@10%; 12dp 圆角 + 0.6dp 描边
    val cardColor = if (isLight) {
        Color.White.copy(alpha = 0.96f)
    } else {
        Color.White.copy(alpha = 0.10f)
    }
    val borderColor = colorScheme.outlineVariant.copy(alpha = if (isLight) 0.08f else 0.06f)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(MuseCornerRadius.CARD.dp),
        color = cardColor,
        border = androidx.compose.foundation.BorderStroke(0.6.dp, borderColor),
    ) {
        Column {
            content()
        }
    }
}
