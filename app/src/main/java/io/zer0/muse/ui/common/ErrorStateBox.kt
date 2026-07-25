package io.zer0.muse.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill

/**
 * 统一错误状态组件,带重试按钮。
 *
 * v1.0.20 MANUS 风格升级(复活僵尸组件):
 *  - 从 Row 错误条改为 Column 居中状态页,与 [EmptyState] 视觉对称
 *  - 圆形 error 色背景容器(error 8% 底 + error 60% 图标)
 *  - 暖调质感替代冷红 errorContainer,降低视觉攻击性
 *  - 居中布局更适合全屏错误态,替换各页面裸 Text 错误提示
 *
 * v1.0.23 入场微动效:
 *  - 与 [EmptyState] 对称的渐入 + 上滑(20px)过渡
 *  - 使用 [MuseAnimation.NORMAL_MS](240ms)+ [MuseAnimation.EaseOutCubic] 曲线
 *
 * @param message 错误文案
 * @param onRetry 重试回调(可选,提供后显示"重试"按钮)
 * @param onDismiss 关闭回调(可选,提供后显示"关闭"按钮)
 * @param modifier 修饰符
 */
@Composable
fun ErrorStateBox(
    message: String,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // v1.0.23: 首次组合触发 enter 转场(AnimatedVisibility 初始 visible=true 不播放动画)。
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
                .padding(MusePaddings.screen * 2),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            // v1.0.20: MANUS 风格 — 圆形错误色背景容器
            Box(
                modifier = Modifier
                    .size(MuseIconSizes.iconEmpty + MusePaddings.screen * 2)
                    .background(
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(MuseIconSizes.iconEmpty),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (onRetry != null || onDismiss != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    if (onRetry != null) {
                        FilledTonalButton(
                            onClick = onRetry,
                            shape = MuseShapes.pill,
                        ) {
                            Text(stringResource(R.string.common_retry))
                        }
                    }
                    if (onDismiss != null) {
                        FilledTonalButton(
                            onClick = onDismiss,
                            shape = MuseShapes.pill,
                        ) {
                            Text(stringResource(R.string.common_close))
                        }
                    }
                }
            }
        }
    }
}
