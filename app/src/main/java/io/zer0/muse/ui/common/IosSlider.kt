package io.zer0.muse.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MusePaddings

/**
 * v1.40: iOS 风格滑块 — 替代 Material 默认 [androidx.compose.material3.Slider]。
 *
 * 视觉差异(对比 Material Slider):
 *  - 轨道更细(4dp vs Material 16dp),更符合 iOS Settings 风格
 *  - 拇指更小(20dp vs Material 24dp)+ 白色描边
 *  - 已填充部分用 primary,未填充用 surfaceVariant
 *  - 拖动时拇指带平滑缩放动画
 *
 * 用法与 Material Slider 完全兼容:
 * ```
 * IosSlider(
 *     value = temperature,
 *     onValueChange = { v -> update { it.copy(temperature = v) } },
 *     valueRange = 0f..2f,
 * )
 * ```
 *
 * @param value 当前值
 * @param onValueChange 值变化回调(拖动中持续触发)
 * @param valueRange 取值范围(默认 0f..1f)
 * @param modifier Modifier
 * @param steps 离散步数(0 = 连续,>0 = 离散 N 步,与 Material Slider 一致)
 * @param showValueLabel 是否在右侧显示当前值文本(默认 true)
 * @param valueFormatter 值格式化函数(默认保留 2 位小数)
 * @param onValueChangeFinished 拖动结束回调(松手或取消时触发,默认空,向后兼容)。
 *   P0-2: 用于支持 RagSettingsPage 等"松手时写 DataStore"的持久化优化场景。
 */
@Composable
fun IosSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    showValueLabel: Boolean = true,
    valueFormatter: (Float) -> String = { "%.2f".format(it) },
    onValueChangeFinished: () -> Unit = {},
) {
    val start = valueRange.start
    val end = valueRange.endInclusive
    val range = end - start
    val rawFraction = if (range > 0) ((value - start) / range).coerceIn(0f, 1f) else 0f
    // H-SL1: 对齐 Material3 Slider steps 语义 — steps=N 表示 start/end 之间有 N 个中间停点,
    // 共 N+2 个取值,离散分度 = N+1(而非旧的 N),旧实现少一个取值。
    val fraction = snapFraction(rawFraction, steps)
    val thumbSizePx = with(LocalDensity.current) { SliderThumbSize.toPx() }
    val trackHeightPx = with(LocalDensity.current) { SliderTrackHeight.toPx() }

    // 拖动时拇指放大动画。L-SL4: isDragging 用 Boolean 语义(旧实现误用 Float 0f/1f 当布尔)。
    var isDragging by remember { mutableStateOf(false) }
    val thumbScale by animateFloatAsState(
        targetValue = if (isDragging) 1.1f else 1f,
        animationSpec = tween(150),
        label = "thumbScale",
    )
    val trackColorVal = trackColor()
    val filledColorVal = filledColor()
    // v1.48 (h20): thumb 在 primary 色背景上,用 onPrimary 替代裸 Color.White
    val thumbColorVal = thumbColor()
    // stringResource 需在 @Composable 直接调用位置提取,不能在 semantics{} 内使用。
    val sliderCd = stringResource(R.string.common_slider_cd, valueFormatter(value))

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .pointerInput(valueRange) {
                    val trackWidthPx = size.width.toFloat() - thumbSizePx
                    val usableWidth = trackWidthPx.coerceAtLeast(0f)
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            val x = (offset.x - thumbSizePx / 2).coerceIn(0f, usableWidth)
                            val newFraction = if (usableWidth > 0) x / usableWidth else 0f
                            val stepped = snapFraction(newFraction, steps)
                            onValueChange((start + stepped * range).coerceIn(start, end))
                        },
                        onDragEnd = {
                            isDragging = false
                            onValueChangeFinished()
                        },
                        onDragCancel = {
                            isDragging = false
                            onValueChangeFinished()
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val currentFraction = if (usableWidth > 0) {
                                ((change.position.x - thumbSizePx / 2) / usableWidth).coerceIn(0f, 1f)
                            } else 0f
                            val stepped = snapFraction(currentFraction, steps)
                            onValueChange((start + stepped * range).coerceIn(start, end))
                        },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    // M-SL2: progressSemantics 注册可调节语义动作 + 自动 stateDescription,
                    // TalkBack 可用音量键逐步调节滑块值(accessibility custom action)。
                    .progressSemantics(value, valueRange, steps)
                    .semantics {
                        contentDescription = sliderCd
                    },
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val centerY = canvasHeight / 2
                val trackStart = thumbSizePx / 2
                val trackEnd = canvasWidth - thumbSizePx / 2
                val trackWidth = (trackEnd - trackStart).coerceAtLeast(0f)
                val thumbCenter = trackStart + fraction * trackWidth

                // 未填充轨道(灰色)
                drawRoundRect(
                    color = trackColorVal,
                    topLeft = Offset(trackStart, centerY - trackHeightPx / 2),
                    size = Size(trackWidth, trackHeightPx),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeightPx / 2, trackHeightPx / 2),
                )
                // 已填充轨道(primary)
                val filledWidth = (thumbCenter - trackStart).coerceAtLeast(0f)
                if (filledWidth > 0) {
                    drawRoundRect(
                        color = filledColorVal,
                        topLeft = Offset(trackStart, centerY - trackHeightPx / 2),
                        size = Size(filledWidth, trackHeightPx),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeightPx / 2, trackHeightPx / 2),
                    )
                }
                // 拇指(白色 + 阴影)
                val thumbRadius = (thumbSizePx / 2) * thumbScale
                drawCircle(
                    color = thumbColorVal,  // v1.48 (h20): onPrimary 替代裸 Color.White
                    radius = thumbRadius,
                    center = Offset(thumbCenter, centerY),
                )
                drawCircle(
                    color = filledColorVal.copy(alpha = 0.15f),
                    radius = thumbRadius * 0.7f,
                    center = Offset(thumbCenter, centerY),
                )
            }
        }
        if (showValueLabel) {
            Spacer(Modifier.width(MusePaddings.itemGap))
            Text(
                text = valueFormatter(value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.width(48.dp),
            )
        }
    }
}

// L-SL3: 滑块专属尺寸抽为局部令牌,避免裸 22.dp / 6.dp 散落。
/** 滑块拇指视觉尺寸。 */
private val SliderThumbSize = 22.dp
/** 滑块轨道高度。 */
private val SliderTrackHeight = 6.dp

/**
 * H-SL1: 将连续 fraction 按 Material3 Slider steps 语义离散化。
 * steps=N 表示 start/end 之间有 N 个中间停点(共 N+2 个取值),
 * 离散分度 = N+1,返回 0..1 之间的吸附 fraction。
 */
private fun snapFraction(fraction: Float, steps: Int): Float {
    if (steps <= 0) return fraction.coerceIn(0f, 1f)
    val divisor = steps + 1
    return (kotlin.math.round(fraction * divisor) / divisor).coerceIn(0f, 1f)
}

@Composable
private fun trackColor(): Color = MaterialTheme.colorScheme.surfaceVariant

@Composable
private fun filledColor(): Color = MaterialTheme.colorScheme.primary

// v1.48 (h20): 拇指颜色 — thumb 绘制在 primary 色填充轨道上,用 onPrimary 保证对比度
@Composable
private fun thumbColor(): Color = MaterialTheme.colorScheme.onPrimary
