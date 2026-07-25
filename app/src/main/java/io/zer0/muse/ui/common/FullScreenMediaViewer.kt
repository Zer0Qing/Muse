package io.zer0.muse.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.zer0.muse.R
import io.zer0.muse.ui.SmartImage
import io.zer0.muse.ui.theme.MuseIconSizes
import kotlinx.coroutines.launch

/**
 * v1.0.15: 可缩放图片 — 支持双指缩放、双击缩放、缩放后拖拽平移。
 *
 * 从 MessageBubble.kt v1.60-B 实现抽取为共享组件,供单聊与群聊全屏媒体查看器复用。
 * - 缩放范围 1x-5x(detectTransformGestures)
 * - 双击在 1x ↔ 2.5x 间平滑切换(300ms Animatable 过渡)
 * - scale/offset 用 rememberSaveable,旋屏后保持缩放状态
 */
@Composable
internal fun ZoomableImage(
    model: Any?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    var scale by rememberSaveable { mutableStateOf(1f) }
    var offsetX by rememberSaveable { mutableStateOf(0f) }
    var offsetY by rememberSaveable { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    Box(
        modifier = modifier
            .clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    if (newScale > 1f) {
                        scale = newScale
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        scope.launch {
                            val targetScale = if (scale > 1f) 1f else 2.5f
                            val animatable = Animatable(scale)
                            animatable.animateTo(
                                targetValue = targetScale,
                                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                            ) {
                                scale = value
                            }
                            if (targetScale == 1f) {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    },
                )
            }
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY,
            ),
        contentAlignment = Alignment.Center,
    ) {
        SmartImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * v1.0.15: 全屏媒体查看器 — HorizontalPager 左右切换 + 双指/双击缩放。
 *
 * 从 MessageBubble.kt v1.60-B 抽取为共享组件,统一单聊与群聊的图片查看交互。
 * - 遮罩 colorScheme.scrim(0.95 alpha)
 * - 顶部:页码指示器(多图时)+ 关闭按钮
 * - dismissOnClickOutside = false,避免缩放手势误触关闭
 *
 * @param images 图片 URL/data URI 列表
 * @param initialIndex 初始展示页索引
 * @param onDismiss 关闭回调
 */
@Composable
internal fun FullScreenMediaViewer(
    images: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    if (images.isEmpty()) return
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, images.lastIndex),
    ) { images.size }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.95f)),
            contentAlignment = Alignment.Center,
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                ZoomableImage(
                    model = images[page],
                    contentDescription = stringResource(R.string.chat_image_page_cd, page + 1, images.size),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 顶部:关闭按钮 + 页码指示器
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 36.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左侧占位(宽度匹配关闭按钮触摸目标),使页码居中、关闭按钮靠右
                Spacer(modifier = Modifier.width(MuseIconSizes.touchTarget))
                if (images.size > 1) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    ) {
                        Text(
                            text = "${pagerState.currentPage + 1} / ${images.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
