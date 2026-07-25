package io.zer0.muse.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseCornerRadius
import kotlinx.coroutines.delay

/**
 * 堆叠式顶部通知系统 — Kelivo 风格。
 *
 * 最多 3 个同时显示, 从顶部滑入 (300ms easeOutCubic),
 * 堆叠效果: 偏移 8dp / 缩放递减 3% / 透明度递减 20%。
 *
 * 类型色:
 *  - success: #34C759 (绿色)
 *  - error: #FF3B30 (红色)
 *  - warning: #FF9500 (橙色)
 *  - info: primary (主题色)
 *
 * 用法:
 * ```
 * // 在 Scaffold 中放置:
 * MuseSnackbarHost()
 *
 * // 触发通知:
 * MuseSnackbar.show("保存成功", MuseSnackbar.Type.SUCCESS)
 * MuseSnackbar.show("网络错误", MuseSnackbar.Type.ERROR)
 * ```
 */
object MuseSnackbar {

    /** 通知类型。 */
    enum class Type { SUCCESS, ERROR, WARNING, INFO }

    /** 通知数据。 */
    data class SnackbarData(
        val id: Long = System.nanoTime(),
        val text: String,
        val type: Type = Type.INFO,
        val durationMillis: Long = 3000L,
    )

    private val _messages = mutableStateListOf<SnackbarData>()
    val messages: List<SnackbarData> get() = _messages

    /** 显示通知 (最多 3 个同时, 超出则移除最早的)。 */
    fun show(text: String, type: Type = Type.INFO, durationMillis: Long = 3000L) {
        val data = SnackbarData(text = text, type = type, durationMillis = durationMillis)
        _messages.add(data)
        if (_messages.size > 3) {
            _messages.removeAt(0)
        }
    }

    /** 移除指定通知。 */
    fun dismiss(id: Long) {
        _messages.removeAll { it.id == id }
    }

    /** 类型对应颜色。 */
    fun typeColor(type: Type): Color = when (type) {
        Type.SUCCESS -> Color(0xFF34C759)
        Type.ERROR -> Color(0xFFFF3B30)
        Type.WARNING -> Color(0xFFFF9500)
        Type.INFO -> Color(0xFF007AFF)
    }
}

/**
 * MuseSnackbar 宿主 Composable — 放置在 Scaffold 顶层。
 *
 * 渲染 [MuseSnackbar.messages] 中的通知列表, 每条自动在 [durationMillis] 后消失。
 */
@Composable
fun MuseSnackbarHost(modifier: Modifier = Modifier) {
    val messages = MuseSnackbar.messages

    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        messages.forEachIndexed { index, data ->
            val stackOffset = (messages.size - 1 - index) // 0 = 最顶层
            val yOffset = stackOffset * 8
            val scaleFactor = 1f - (stackOffset * 0.03f)
            val alphaFactor = 1f - (stackOffset * 0.2f)

            AnimatedVisibility(
                visible = true,
                enter = slideInVertically(
                    animationSpec = tween(MuseAnimation.SLOW_MS - 20, easing = MuseAnimation.EaseOutCubic),
                    initialOffsetY = { -it },
                ) + fadeIn(animationSpec = tween(MuseAnimation.NORMAL_MS)),
                exit = slideOutVertically(
                    animationSpec = tween(MuseAnimation.NORMAL_MS, easing = MuseAnimation.EaseInCubic),
                    targetOffsetY = { -it },
                ) + fadeOut(animationSpec = tween(MuseAnimation.NORMAL_MS)),
            ) {
                Surface(
                    modifier = Modifier
                        .offset { IntOffset(0, yOffset * 4) } // dp 转 px 的近似值
                        .scale(scaleFactor),
                    shape = RoundedCornerShape(MuseCornerRadius.BUTTON.dp),
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f * alphaFactor),
                    shadowElevation = 4.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = data.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                        )
                    }
                }
            }

            // 自动消失
            LaunchedEffect(data.id) {
                delay(data.durationMillis)
                MuseSnackbar.dismiss(data.id)
            }
        }
    }
}
