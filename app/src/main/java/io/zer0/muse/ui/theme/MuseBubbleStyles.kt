package io.zer0.muse.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
     * 当前是否「黑白（mono）」主题。
     *
     * 灰阶主题下不能用「主色 + alpha」那套：mono 的主色在暗色模式是近白（#E9E9ED），
     * 20% 叠到近黑背景上会变成一层发灰发浊的乳白，看起来像脏了的玻璃。
     * 黑白主题一律走**实色灰阶**，不做渐变。
     */
    @Composable
    fun isMonoBubbleTheme(): Boolean = LocalMuseThemeId.current == "mono"

    /** 当前配色是否深色（用背景亮度判断，不依赖 isSystemInDarkTheme，避免跟随系统时误判）。 */
    @Composable
    private fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

    /**
     * 用户气泡默认底色(无皮肤)。
     *
     * 2.6.24: 原先用 `primary@20%` 半透明叠色 —— 压在壁纸/渐变上像「糊了一层」，
     * 与助手卡的实色观感割裂。改为实色 [MaterialTheme.colorScheme.primaryContainer]：
     * 与心情块同一族的主题跟随色，但不再透出背景。
     */
    @Composable
    fun userSurfaceColor(): Color = if (isMonoBubbleTheme()) {
        // 黑白主题：扁平灰阶。暗色用比背景亮两档的中灰，浅色用比背景深一档的浅灰。
        if (isDarkScheme()) Color(0xFF2E2E34) else Color(0xFFECECF0)
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }

    /** 用户气泡默认描边(无皮肤)— 主色 18%,与心情块描边同口径。 */
    @Composable
    fun userBorderColor(): Color = if (isMonoBubbleTheme()) {
        if (isDarkScheme()) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.10f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    }

    /**
     * 助手气泡默认底色(无皮肤)— 浅色卡片。
     *
     * v2.0: 用 surfaceVariant 向 onSurface 混入 6% —— 纯 surfaceVariant 在部分主题
     * (如单色主题 #F6F6F8 vs 背景 #FBFBFC)与背景几乎同色,用户反馈"气泡看不见";
     * 混色后亮/暗主题下都有稳定的可见度。
     */
    @Composable
    fun assistantSurfaceColor(): Color = if (isMonoBubbleTheme()) {
        if (isDarkScheme()) Color(0xFF1C1C21) else Color(0xFFF4F4F6)
    } else {
        androidx.compose.ui.graphics.lerp(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurface,
            0.06f,
        )
    }

    /** 助手气泡默认描边(无皮肤)— outlineVariant,给浅色卡片一个清晰边界。 */
    @Composable
    fun assistantBorderColor(): Color = if (isMonoBubbleTheme()) {
        if (isDarkScheme()) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.07f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.9f)
    }

    /** 用户气泡默认文字色(无皮肤)。 */
    @Composable
    fun userContentColor(): Color = if (isMonoBubbleTheme()) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    /** 气泡最大宽度(70% 屏宽,与群聊 maxBubbleWidth 一致)。 */
    @Composable
    fun maxBubbleWidth(): Dp = with(LocalConfiguration.current) { (screenWidthDp * MAX_WIDTH_FRACTION).dp }

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
