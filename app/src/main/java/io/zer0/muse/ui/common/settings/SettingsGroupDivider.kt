package io.zer0.muse.ui.common.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 分组内分隔线 — 用在 [SettingsGroup] 的两个 item 之间。
 *
 * 留 16dp 左缩进(对标 iOS 分组分隔线不到边缘的视觉)。
 */
@Composable
fun SettingsGroupDivider() {
    HorizontalDivider(
        // iOS 分隔线: 左缩进 54dp (图标槽 36dp + 间距 12dp + 余量)
        modifier = Modifier.padding(start = 54.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.08f),
        thickness = 0.6.dp,
    )
}
