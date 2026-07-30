package io.zer0.muse.ui.common.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.common.form.MuseSegmentedControl
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings

/**
 * 带分段控件的设置项行 — 左侧图标 + 标题 + 副标题,下方嵌入 [MuseSegmentedControl]。
 *
 * 用于聊天风格、语气、主题模式等需要在分组卡片内做单选的场景,
 * 统一分段控件与卡片其他行的视觉间距。
 *
 * @param icon 左侧图标
 * @param title 主标题
 * @param subtitle 副标题
 * @param options 选项文本列表
 * @param selectedIndex 当前选中索引
 * @param onSelectedChange 选中变更回调
 */
@Composable
fun SettingsSegmentedRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    options: List<String>,
    selectedIndex: Int,
    onSelectedChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(MuseIconSizes.iconMedium),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            MuseSegmentedControl(
                options = options,
                selectedIndex = selectedIndex,
                onSelectedChange = onSelectedChange,
                modifier = Modifier.padding(top = MusePaddings.auxGap),
            )
        }
    }
}
