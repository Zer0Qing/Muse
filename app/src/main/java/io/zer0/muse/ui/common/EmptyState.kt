package io.zer0.muse.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.unit.dp
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
fun EmptyState(
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
            animationSpec = tween(MuseAnimation.NORMAL_MS, easing = MuseAnimation.EaseOutCubic),
        ) + slideInVertically(
            animationSpec = tween(MuseAnimation.NORMAL_MS, easing = MuseAnimation.EaseOutCubic),
            initialOffsetY = { 20 },
        ),
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                // L-ES3: 空状态用 2x screen 边距(32dp),比常规 16dp 更大留白,
                // 强化"无内容"的呼吸感,对标 iOS 空态视觉。
                .padding(MusePaddings.screen * 2),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            // v1.0.20: MANUS 风格 — 圆形暖色背景容器包裹图标
            // 图标尺寸 iconEmpty(64dp),容器尺寸 iconEmpty + screen*2(96dp),留出呼吸空间
            Box(
                modifier = Modifier
                    .size(MuseIconSizes.iconEmpty + MusePaddings.screen * 2)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(MuseIconSizes.iconEmpty),
                    // v1.0.20: 从冷灰 outline 改为暖色 primary,营造 MANUS 暖调质感
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }
            if (actionText != null && onAction != null) {
                FilledTonalButton(
                    onClick = onAction,
                    shape = MuseShapes.pill,
                ) {
                    Text(actionText)
                }
            }
        }
    }
}
