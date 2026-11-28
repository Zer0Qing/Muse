package io.zer0.muse.ui.common.media

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.zer0.muse.R
import io.zer0.muse.ui.chat.SmartImage
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.MuseDialogWindowEffect
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 可缩放图片 — 双指缩放、双击切换、视口边界内平移；未放大时不拦截翻页手势。
 */
@Composable
internal fun ZoomableImage(
    model: Any?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    onSingleTap: (() -> Unit)? = null,
) {
    var scale by rememberSaveable { mutableFloatStateOf(1f) }
    var offsetX by rememberSaveable { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable { mutableFloatStateOf(0f) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    val doubleTapSpec = MuseMotion.tween<Float>(
        durationMillis = MuseAnimation.SLOW_MS,
        easing = FastOutSlowInEasing,
    )

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        val pointerCount = event.changes.count { it.pressed }
                        val isPinching = pointerCount >= 2 && zoomChange != 1f
                        if (isPinching || scale > 1.01f) {
                            val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                            if (newScale > 1.01f) {
                                val maxX = ((newScale - 1f) * boxSize.width) / 2f
                                val maxY = ((newScale - 1f) * boxSize.height) / 2f
                                scale = newScale
                                offsetX = (offsetX + panChange.x).coerceIn(-maxX, maxX)
                                offsetY = (offsetY + panChange.y).coerceIn(-maxY, maxY)
                            } else {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            }
                            event.changes.forEach { change ->
                                if (change.positionChanged()) change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(onSingleTap) {
                detectTapGestures(
                    onTap = { onSingleTap?.invoke() },
                    onDoubleTap = {
                        scope.launch {
                            val targetScale = if (scale > 1.05f) 1f else 2.5f
                            val animatable = Animatable(scale)
                            animatable.animateTo(
                                targetValue = targetScale,
                                animationSpec = doubleTapSpec,
                            ) {
                                scale = value
                            }
                            if (abs(targetScale - 1f) < 0.05f) {
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
 * 全屏媒体查看器 — 统一服务聊天大图、产物图片、工作区与创作工坊作品库。
 */
@Composable
internal fun FullScreenMediaViewer(
    images: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onSaveImage: ((imageUri: String) -> Unit)? = null,
    onShareImage: ((imageUri: String) -> Unit)? = null,
    headerExtra: (@Composable (currentIndex: Int) -> Unit)? = null,
    bottomContent: (@Composable (currentIndex: Int) -> Unit)? = null,
) {
    if (images.isEmpty()) return
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, images.lastIndex),
    ) { images.size }
    var showChrome by rememberSaveable { mutableStateOf(true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        MuseDialogWindowEffect(forceFullScreen = true)
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
                    onSingleTap = { showChrome = !showChrome },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (showChrome) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = MusePaddings.itemGap, vertical = MuseSpacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                    ) {
                        headerExtra?.invoke(pagerState.currentPage)
                            ?: Spacer(modifier = Modifier.width(MuseIconSizes.touchTarget))
                    }
                    if (images.size > 1) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        ) {
                            Text(
                                text = "${pagerState.currentPage + 1} / ${images.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(
                                    horizontal = MusePaddings.itemGap,
                                    vertical = MusePaddings.labelVerticalGap,
                                ),
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = MuseIcons.x,
                            contentDescription = stringResource(R.string.common_close),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                val currentImage = images.getOrNull(pagerState.currentPage)
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = MusePaddings.itemGap, vertical = MuseSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                ) {
                    if (onShareImage != null || onSaveImage != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (onShareImage != null && currentImage != null) {
                                IconButton(
                                    onClick = { onShareImage(currentImage) },
                                    modifier = Modifier
                                        .size(MuseIconSizes.touchTarget)
                                        .background(
                                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                            shape = CircleShape,
                                        ),
                                ) {
                                    Icon(
                                        imageVector = MuseIcons.share,
                                        contentDescription = stringResource(R.string.common_share),
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                                    )
                                }
                            }
                            if (onSaveImage != null && currentImage != null) {
                                IconButton(
                                    onClick = { onSaveImage(currentImage) },
                                    modifier = Modifier
                                        .size(MuseIconSizes.touchTarget)
                                        .background(
                                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                            shape = CircleShape,
                                        ),
                                ) {
                                    Icon(
                                        imageVector = MuseIcons.download,
                                        contentDescription = stringResource(R.string.chat_save_image_cd),
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                                    )
                                }
                            }
                        }
                    }
                    bottomContent?.invoke(pagerState.currentPage)
                }
            }
        }
    }
}
