package io.zer0.muse.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MusePaddings

/**
 * Muse UI Kit — 空状态占位组件 [EmptyState]。
 *
 * 设计稿对齐:
 *  - 居中布局: 图标(灰色线性) + 标题 + 副标题 + 可选操作按钮
 *  - 大量留白(上下 32dp)
 *  - 图标 48dp,灰色(onSurfaceVariant @ 40%)
 *
 * 用法:
 * ```
 * EmptyState(
 *     icon = Icons.Outlined.SearchOff,
 *     title = "没有找到结果",
 *     subtitle = "试试其他关键词",
 * )
 * ```
 *
 * @param icon 线性图标
 * @param title 主标题
 * @param modifier 修饰符
 * @param subtitle 副标题(可选)
 * @param actionLabel 操作按钮文字(可选,如"重试")
 * @param onAction 操作按钮回调
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.emptyStateGap, horizontal = MusePaddings.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(48.dp),
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onAction) {
                Text(
                    text = actionLabel,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
