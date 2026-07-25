package io.zer0.muse.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import io.zer0.muse.ui.theme.MuseAnimation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseShapes

/**
 * iOS 风格过滤卡 — 设计稿首页全部/置顶/归档三卡。
 *
 * 选中时:primary 背景 + 白图标 + 白文字
 * 未选中时:surfaceVariant 背景 + secondary 图标/文字
 *
 * 用法:
 * ```
 * IosFilterCard(
 *     icon = Icons.Default.List,
 *     label = "All",
 *     isActive = currentFilter == Filter.ALL,
 *     onClick = { currentFilter = Filter.ALL },
 * )
 * ```
 *
 * @param icon 顶部图标
 * @param label 底部标签
 * @param isActive 是否选中
 * @param onClick 点击回调
 * @param modifier Modifier
 */
@Composable
fun IosFilterCard(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        animationSpec = tween(MuseAnimation.TACTILE_MS),
        label = "filter_card_bg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(MuseAnimation.TACTILE_MS),
        label = "filter_card_content",
    )

    Surface(
        shape = MuseShapes.medium, // 14dp — 介于 medium(12) 和 semiLarge(16) 之间,取 medium
        color = bgColor,
        onClick = onClick,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = contentColor,
            )
        }
    }
}
