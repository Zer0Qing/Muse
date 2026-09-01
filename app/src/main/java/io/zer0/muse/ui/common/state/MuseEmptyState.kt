package io.zer0.muse.ui.common.state

import io.zer0.muse.ui.theme.MuseMotion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill

/**
 * 统一空状态组件,所有列表页空态复用。
 *
 * v1.0.20 MANUS 风格升级:
 *  - 图标从冷灰裸图标改为暖色圆形背景容器(primary 8% 底 + primary 50% 图标)
 *  - 营造柔和的暖调质感,与 MANUS 风格一致
 *  - 保持参数签名不变,20 处调用方零改动
 *
 * v1.0.23 入场微动效:
 *  - 渐入 + 上滑(20px)过渡,提升状态页精致度
 *  - 使用 [MuseAnimation.NORMAL_MS](240ms)+ [MuseAnimation.EaseOutCubic] 曲线,
 *    与卡片按压、面板切换节奏一致
 *  - 首帧延迟 1 帧(LaunchedEffect 触发),确保 AnimatedVisibility 正确执行 enter 转场
 *
 * @param icon 顶部图标(默认收件箱,空数据语义)
 * @param title 标题文案
 * @param subtitle 副标题文案(可选,灰色说明)
 * @param actionText 操作按钮文案(可选,提供后与 [onAction] 一同显示)
 * @param onAction 操作按钮回调(可选)
 * @param modifier 修饰符
 */
@Composable
fun MuseEmptyState(
    icon: ImageVector = Icons.Outlined.Inbox,
    title: String,
    subtitle: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // v1.0.23: 首次组合时触发 enter 转场(AnimatedVisibility 初始 visible=true 不触发动画,
    // 必须从 false → true 跳变才会播放 enter)。
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(
            animationSpec = MuseMotion.tween(MuseAnimation.NORMAL_MS, easing = MuseAnimation.EaseOutCubic),
        ) + slideInVertically(
            animationSpec = MuseMotion.tween(MuseAnimation.NORMAL_MS, easing = MuseAnimation.EaseOutCubic),
            // v1.0.74 fix (前端审计 7): 裸 20px 改高度一半(密度无关)
            initialOffsetY = { it / 2 },
        ),
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(MusePaddings.screen * 2),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            // 小图标（不再用大圆圈包裹）
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(MuseIconSizes.iconLarge),  // 32dp
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),  // 灰色
            )
            // 标题
            if (title.isNotBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 副标题
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }
            // 操作按钮（如果有）
            if (actionText != null && onAction != null) {
                Button(
                    onClick = onAction,
                    shape = MuseShapes.pill,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(actionText)
                }
            }
        }
    }
}
