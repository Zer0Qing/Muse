@file:Suppress("FunctionNaming", "MatchingDeclarationName")

package io.zer0.muse.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import compose.icons.TablerIcons
import compose.icons.tablericons.Check
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion

/** 右上角浮动菜单中的独立操作项。 */
internal data class MuseFloatingActionItem(
    val key: String,
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
    val tint: Color? = null,
    val checked: Boolean? = null,
    val onClick: () -> Unit,
)

/**
 * 无底部遮罩的右对齐浮动菜单。
 *
 * v1.0.80:
 *  - 外层一个统一底色容器(surfaceContainerHigh + 阴影)
 *  - 每个菜单项是独立的“岛”(surface 色胶囊),与底板颜色不同
 *  - checked 项用 primaryContainer 强调
 *  - 主动消息等开关项整行点按 + 勾选图标,不再放大 Switch
 */
@Composable
internal fun MuseFloatingActionMenu(
    items: List<MuseFloatingActionItem>,
    onDismiss: () -> Unit,
    offset: IntOffset? = null,
    belowAnchorDp: Dp = 56.dp,
) {
    val density = LocalDensity.current
    val reducedMotion = MuseMotion.isReducedMotion()
    val staggerDelay = if (reducedMotion) 0 else MuseAnimation.STAGGER_STEP_MS / 4
    val fadeDuration = if (reducedMotion) 0 else MuseAnimation.FAST_NORMAL_MS
    val moveDuration = if (reducedMotion) 0 else MuseAnimation.FAST_NORMAL_MS
    val resolvedOffset = offset ?: with(density) {
        IntOffset(0, belowAnchorDp.roundToPx())
    }
    Popup(
        onDismissRequest = onDismiss,
        alignment = Alignment.TopEnd,
        offset = resolvedOffset,
    ) {
        // 统一底板:主题色容器(surfaceContainerHigh),多个独立岛浮在其上
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
            tonalElevation = 0.dp,
            modifier = Modifier.padding(8.dp),
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items.forEachIndexed { index, item ->
                    key(item.key) {
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(MuseMotion.tween(fadeDuration, delayMillis = index * staggerDelay)) +
                                scaleIn(
                                    animationSpec = MuseMotion.tween(moveDuration, delayMillis = index * staggerDelay),
                                    initialScale = 0.92f,
                                    transformOrigin = TransformOrigin(1f, 0f),
                                ) +
                                slideInHorizontally(
                                    animationSpec = MuseMotion.tween(moveDuration, delayMillis = index * staggerDelay),
                                    initialOffsetX = { it / 4 },
                                ),
                        ) {
                            val foreground = item.tint ?: if (item.enabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                            }
                            // v1.0.80: checked 项不再变色/加深底色,仅在右侧显示勾选图标
                            // (用户反馈主动消息项变蓝+加深看着奇怪,且下方都是 5 字标签位置够)
                            val islandColor = when {
                                !item.enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                else -> MaterialTheme.colorScheme.surface
                            }
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = islandColor,
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable(enabled = item.enabled, onClick = item.onClick),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = item.label,
                                        tint = foreground,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = item.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = foreground,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    item.checked?.let { checked ->
                                        if (checked) {
                                            Icon(
                                                imageVector = TablerIcons.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
