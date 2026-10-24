package io.zer0.muse.ui.common.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/**
 * 发送时那段「文字从输入框升起来、收成气泡」的飞行。
 *
 * 只在会话的第一条消息触发：后续每条都飞会变成噪音，而第一句恰好是「对话开始」
 * 这件事本身，值得一个动作。
 *
 * 关键实现取舍：幽灵层画在一个独立的窗口里（Popup），坐标系用 window bounds。
 * 这样它不受 Scaffold 的内容区裁剪，也不必为了叠一层而改造屏幕根布局。
 *
 * 飞行末段（最后 15%）幽灵层淡出，与列表里已经就位的真实气泡重合。
 * 不做实时重排文字：宽度收窄时真文字会不停跳行，反而比交叉淡变更乱。
 */

/** 一次发送飞行的几何与内容。 */
internal data class SendFlight(
    val text: String,
    val from: Rect,
    val to: Rect,
)

/** 飞行总时长。 */
private const val FLIGHT_MS = 480

/**
 * 起步快、尾巴长的缓动。落地不回弹：回弹会让这个动作显得轻浮。
 */
private val FlightEasing = CubicBezierEasing(0.22f, 0.68f, 0f, 1f)

/** 气泡落点的最大宽度占比（与聊天气泡的最大宽度保持一致的手感）。 */
private const val TARGET_WIDTH_RATIO = 0.72f

/**
 * 由输入框与消息列表的窗口矩形推算落点。
 *
 * 落点取列表底部往上一行：新消息发出后总是贴在列表底部，幽灵层落在这里，
 * 正好压在真实气泡上。
 */
internal fun computeSendFlight(text: String, input: Rect, list: Rect): SendFlight? {
    val valid = text.isNotBlank() && input.width > 0f && input.height > 0f && list.width > 0f
    if (!valid) return null
    val horizontalInset = list.width * 0.08f
    val width = (list.width * TARGET_WIDTH_RATIO).coerceAtMost(list.width - horizontalInset * 2f)
    val height = input.height
    val right = list.right - horizontalInset
    val top = (list.bottom - height - height * 0.35f).coerceAtLeast(list.top)
    val to = Rect(left = right - width, top = top, right = right, bottom = top + height)
    return SendFlight(text = text, from = input, to = to)
}

/**
 * 幽灵层。整个窗口大小的一层，靠偏移把文字放在该在的地方。
 *
 * @param flight 为 null 时不渲染；渲染完毕回调 [onFinished] 让调用方清空状态
 */
@Composable
internal fun SendFlightLayer(flight: SendFlight?, onFinished: () -> Unit) {
    if (flight == null) return
    val progress = remember(flight) { Animatable(0f) }
    LaunchedEffect(flight) {
        progress.animateTo(targetValue = 1f, animationSpec = tween(FLIGHT_MS, easing = FlightEasing))
        onFinished()
    }
    val density = LocalDensity.current
    val p = progress.value
    val x = lerpFloat(flight.from.left, flight.to.left, p)
    val y = lerpFloat(flight.from.top, flight.to.top, p)
    val width = lerpFloat(flight.from.width, flight.to.width, p)
    val height = lerpFloat(flight.from.height, flight.to.height, p)
    // 前 30% 只是离地，背景仍透明：像把文字从输入框里"拿起来"。
    val backgroundAlpha = ((p - 0.30f) / 0.55f).coerceIn(0f, 1f)
    // 最后 15% 淡出，交由真实气泡接管。
    val ghostAlpha = if (p > 0.85f) ((1f - p) / 0.15f).coerceIn(0f, 1f) else 1f
    val cornerRadius = lerpFloat(10f, 18f, p)
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .width(with(density) { width.toDp() })
                .height(with(density) { height.toDp() })
                .graphicsLayer { alpha = ghostAlpha }
                .clip(RoundedCornerShape(cornerRadius.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = backgroundAlpha))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = flight.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (useOnPrimaryText(backgroundAlpha)) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun lerpFloat(start: Float, end: Float, fraction: Float): Float = start + (end - start) * fraction

/** 文本颜色在背景渐显时切换，这里给出便于观察的中间态判阈。 */
private const val TEXT_COLOR_FLIP_ALPHA = 0.5f

/** 背景显到一半以上就改用浅色文字。 */
internal fun useOnPrimaryText(backgroundAlpha: Float): Boolean = backgroundAlpha > TEXT_COLOR_FLIP_ALPHA

/**
 * 把幽灵层挂到一个独立的窗口上，位置钉在窗口原点。
 *
 * 用窗口而不是普通叠层的原因：它不受 Scaffold 内容区裁剪，也不必为了叠一层
 * 而改造屏幕根布局；坐标统一用 window bounds，与 onGloballyPositioned 的量法一致。
 */
@Composable
internal fun SendFlightWindow(flight: SendFlight?, onFinished: () -> Unit) {
    if (flight == null) return
    val provider = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset = IntOffset.Zero
        }
    }
    Popup(
        popupPositionProvider = provider,
        properties = PopupProperties(focusable = false),
    ) {
        SendFlightLayer(flight = flight, onFinished = onFinished)
    }
}
