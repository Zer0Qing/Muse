package io.zer0.muse.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * iOS 风格入口大卡片 — 设计稿首页定时任务/知识库两个大入口。
 *
 * surfaceVariant 半透明背景 + 20dp 圆角(MuseShapes.extraLarge)。
 * 内含图标 + 标题 + 副标题 + 右侧箭头。
 *
 * 用法:
 * ```
 * EntryCard(
 *     icon = Icons.Default.Schedule,
 *     title = "Scheduled Tasks",
 *     subtitle = "3 active tasks",
 *     onClick = { navToTasks() },
 * )
 * ```
 *
 * @param icon 左侧图标
 * @param title 主标题
 * @param subtitle 副标题(如描述/计数)
 * @param onClick 点击回调
 * @param modifier Modifier
 */
@Composable
fun EntryCard(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MuseShapes.extraLarge, // 20dp — 设计稿大卡片圆角
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.inputPadding),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧图标（黑白线条风格）
            IosSettingsIcon(
                icon = icon,
            )
            // 标题 + 副标题
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 右侧箭头
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
