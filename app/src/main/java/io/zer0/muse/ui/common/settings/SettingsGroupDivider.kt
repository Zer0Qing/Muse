package io.zer0.muse.ui.common.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.common.surface.museDividerStartIndent

/**
 * 分组内分隔线 — 用在 [SettingsGroup] 的两个 item 之间。
 *
 * 缩进跟随内容左边线:有 leading 图标时缩到图标右侧文字列,无图标卡落到行内边距。
 * 起点规则与 [io.zer0.muse.ui.common.surface.MuseDivider] 收敛到同一 [museDividerStartIndent]。
 *
 * @param hasLeading 该组是否带 leading 图标(默认 true,对齐旧行为)
 */
@Composable
fun SettingsGroupDivider(hasLeading: Boolean = true) {
    HorizontalDivider(
        modifier = Modifier.padding(start = museDividerStartIndent(hasLeading)),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.08f),
        thickness = 0.6.dp,
    )
}
