package io.zer0.muse.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 消息气泡共享样式(决策 D1:单聊向群聊靠齐)。
 *
 * 单聊 [io.zer0.muse.ui.MessageBubble] 与群聊 GroupChatMessageBubble 共用本组默认值,
 * 消除两处外观漂移(CHAT-01/CHAT-02):
 *  - 用户气泡: surfaceVariant 底 + 右下 6dp 小尾巴,文字 onSurface/bodyMedium
 *  - 助手气泡: surfaceVariant@60% 浅色卡片 + 左下 6dp 小尾巴,文字 onSurface/bodyMedium
 *  - 宽度: 气泡最大 70% 屏宽(与群聊 maxBubbleWidth 口径一致)
 *
 * 皮肤(BubbleSkin)存在时仍只覆盖外壳(颜色/圆角/内边距/宽度),默认值统一随本处。
 */
object MuseBubbleStyles {
    /** 气泡最大宽度占屏比例(70%,对齐群聊 maxBubbleWidth)。 */
    const val MAX_WIDTH_FRACTION = 0.70f

    /**
     * 用户气泡默认底色(无皮肤)。
     *
     * v2.0: 从 surfaceVariant(与背景几乎同色,用户反馈"气泡看不见")改为主色浅染,
     * 亮/暗主题下都能和背景拉开对比;描边由 [userBorderColor] 给边缘。
     */
    @Composable
    fun userSurfaceColor(): Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)

    /** 用户气泡默认描边(无皮肤)— 主色 24%,让气泡边界清晰。 */
    @Composable
    fun userBorderColor(): Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)

    /**
     * 助手气泡默认底色(无皮肤)— 浅色卡片。
     *
     * v2.0: 用 surfaceVariant 向 onSurface 混入 6% —— 纯 surfaceVariant 在部分主题
     * (如单色主题 #F6F6F8 vs 背景 #FBFBFC)与背景几乎同色,用户反馈"气泡看不见";
     * 混色后亮/暗主题下都有稳定的可见度。
     */
    @Composable
    fun assistantSurfaceColor(): Color = androidx.compose.ui.graphics.lerp(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.onSurface,
        0.06f,
    )

    /** 助手气泡默认描边(无皮肤)— outlineVariant,给浅色卡片一个清晰边界。 */
    @Composable
    fun assistantBorderColor(): Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.9f)

    /** 用户气泡默认文字色(无皮肤)。 */
    @Composable
    fun userContentColor(): Color = MaterialTheme.colorScheme.onSurface

    /** 气泡最大宽度(70% 屏宽,与群聊 maxBubbleWidth 一致)。 */
    @Composable
    fun maxBubbleWidth(): Dp =
        with(LocalConfiguration.current) { (screenWidthDp * MAX_WIDTH_FRACTION).dp }

    /** 用户气泡形状:主体圆角 [radiusDp](null 用 20dp 默认)+ 右下 6dp 尾巴。 */
    fun userBubbleShape(radiusDp: Float? = null): RoundedCornerShape {
        val r = (radiusDp ?: MuseCornerRadius.CARD.toFloat()).dp
        return RoundedCornerShape(r, r, MuseCornerRadius.BUBBLE_TAIL.dp, r)
    }

    /** 助手气泡形状:主体圆角 [radiusDp](null 用 20dp 默认)+ 左下 6dp 尾巴。 */
    fun assistantBubbleShape(radiusDp: Float? = null): RoundedCornerShape {
        val r = (radiusDp ?: MuseCornerRadius.CARD.toFloat()).dp
        return RoundedCornerShape(r, r, r, MuseCornerRadius.BUBBLE_TAIL.dp)
    }
}
