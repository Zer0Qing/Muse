package io.zer0.muse.ui.common.surface

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.tiny

/**
 * B1-05: 通用列表骨架屏。
 *
 * 用单条 InfiniteTransition 驱动的扫光 Brush 为会话卡片、头像行、记忆条目
 * 提供与聊天流一致的低成本 shimmer 视觉。
 */
@Composable
fun MuseShimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "muse_shimmer")
    val translate by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "muse_shimmer_translate",
    )
    return Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        start = Offset(translate * -300f, 0f),
        end = Offset(translate * 300f, 0f),
    )
}

@Composable
fun SessionCardSkeleton(modifier: Modifier = Modifier) {
    val brush = MuseShimmerBrush()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(MuseShapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .padding(MusePaddings.cardInner),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(brush),
                )
                Column(
                    modifier = Modifier.padding(start = MusePaddings.contentGap),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.55f)
                            .height(14.dp)
                            .clip(MuseShapes.tiny)
                            .background(brush),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.35f)
                            .height(10.dp)
                            .clip(MuseShapes.tiny)
                            .background(brush),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(MuseShapes.tiny)
                    .background(brush),
            )
        }
    }
}

@Composable
fun AvatarRowSkeleton(modifier: Modifier = Modifier) {
    val brush = MuseShimmerBrush()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MuseShapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .padding(MusePaddings.itemGap),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(MuseIconSizes.iconLarge)
                .clip(CircleShape)
                .background(brush),
        )
        Column(
            modifier = Modifier.padding(start = MusePaddings.contentGap),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.45f)
                    .height(14.dp)
                    .clip(MuseShapes.tiny)
                    .background(brush),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.3f)
                    .height(10.dp)
                    .clip(MuseShapes.tiny)
                    .background(brush),
            )
        }
    }
}

@Composable
fun FactItemSkeleton(modifier: Modifier = Modifier) {
    val brush = MuseShimmerBrush()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MuseShapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .padding(MusePaddings.cardInner),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(14.dp)
                .clip(MuseShapes.tiny)
                .background(brush),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(MuseShapes.tiny)
                .background(brush),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(12.dp)
                .clip(MuseShapes.tiny)
                .background(brush),
        )
    }
}

private object MuseIconSizes {
    val iconLarge: androidx.compose.ui.unit.Dp = 40.dp
}
