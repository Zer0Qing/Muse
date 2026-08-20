@file:Suppress("FunctionNaming", "MatchingDeclarationName")

package io.zer0.muse.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup

/** 右上角浮动菜单中的独立操作项。 */
internal data class MuseFloatingActionItem(
    val key: String,
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
    val tint: Color? = null,
    val onClick: () -> Unit,
)

/**
 * 无底部遮罩的右对齐浮动菜单,每个按钮独立带入场动画。
 */
@Composable
internal fun MuseFloatingActionMenu(
    items: List<MuseFloatingActionItem>,
    onDismiss: () -> Unit,
    offset: IntOffset? = null,
    belowAnchorDp: Dp = 56.dp,
) {
    val density = LocalDensity.current
    val resolvedOffset = offset ?: with(density) {
        IntOffset(0, belowAnchorDp.roundToPx())
    }
    Popup(
        onDismissRequest = onDismiss,
        alignment = Alignment.TopEnd,
        offset = resolvedOffset,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEachIndexed { index, item ->
                key(item.key) {
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(tween(120, delayMillis = index * 35)) +
                            scaleIn(
                                animationSpec = tween(160, delayMillis = index * 35),
                                initialScale = 0.86f,
                                transformOrigin = TransformOrigin(1f, 0f),
                            ) +
                            slideInHorizontally(
                                animationSpec = tween(160, delayMillis = index * 35),
                                initialOffsetX = { it / 3 },
                            ),
                    ) {
                        val foreground = item.tint ?: if (item.enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        }
                        val shape = RoundedCornerShape(18.dp)
                        // 禁用态使用不透明容器,避免半透明 Surface 与 Popup 背景叠加时
                        // 在内容行中露出一条白色高亮带。
                        val containerColor = if (item.enabled) {
                            MaterialTheme.colorScheme.surface
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                        Surface(
                            shape = shape,
                            color = containerColor,
                            shadowElevation = if (item.enabled) 6.dp else 3.dp,
                            tonalElevation = 0.dp,
                            modifier = Modifier
                                .align(Alignment.End)
                                .clip(shape)
                                .clickable(enabled = item.enabled, onClick = item.onClick),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
