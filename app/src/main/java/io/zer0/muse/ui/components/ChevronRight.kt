package io.zer0.muse.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Muse UI Kit — 灰色右箭头 [ChevronRight]。
 *
 * 设计稿对齐: iOS Settings 风格的 chevron 右箭头,
 * 表示"可点击进入详情页"。使用线性图标。
 *
 * 用法:
 * ```
 * MuseListItem(
 *     headlineContent = { Text("外观") },
 *     trailingContent = { ChevronRight() },
 * )
 * ```
 *
 * @param modifier 修饰符
 */
@Composable
fun ChevronRight(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Outlined.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        modifier = modifier.size(20.dp),
    )
}
