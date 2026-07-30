package io.zer0.muse.ui.common.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * 折叠分区 — iOS Settings 高级感核心组件。
 *
 * 设计:
 *  - 标题行(图标 + 标题 + 副标题 + 旋转箭头)
 *  - 点击标题行展开/折叠内容区
 *  - 箭头旋转动画:tween 250ms,展开时 180°(向上)
 *  - 内容区 expandVertically + fadeIn,带柔和过渡
 *  - 标题行用 surfaceVariant 圆角容器,展开时容器包裹内容
 *
 * 用法:
 * ```
 * CollapsibleSection(
 *     title = "模型高级",
 *     subtitle = "temperature / topP / maxTokens",
 *     icon = Icons.Outlined.Tune,
 * ) {
 *     // 内容
 * }
 * ```
 *
 * @param title 分区标题
 * @param subtitle 副标题(可选,灰色小字)
 * @param icon 左侧图标(可选)
 * @param defaultExpanded 默认是否展开(默认 false)
 * @param content 分区内容
 */
@Composable
fun CollapsibleSection(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    defaultExpanded: Boolean = false,
    // M-SC3: 增加 key 参数 — 当同一页面存在多个 title 相同的分区时,
    // rememberSaveable(title) 会共享状态导致展开/折叠互相串扰,key 用于区分实例。
    key: Any? = null,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title, key) { mutableStateOf(defaultExpanded) }
    // 箭头旋转动画(180° → 0°)
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(250),
        label = "section_arrow",
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MuseShapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // 标题行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    // M-SC2: 裸 16/14dp 替换为 MusePaddings.cardInner 令牌(与 SettingsItemRow 一致)。
                    .padding(MusePaddings.cardInner),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .size(MuseIconSizes.icon)
                        .rotate(rotation),
                )
            }
            // 内容区(带过渡动画)
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(250)) + fadeIn(tween(MuseAnimation.TACTILE_MS)),
                exit = shrinkVertically(tween(250)) + fadeOut(tween(MuseAnimation.TACTILE_MS)),
            ) {
                Column {
                    if (subtitle != null) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = 0.5.dp,
                        )
                    }
                    content()
                }
            }
        }
    }
}
