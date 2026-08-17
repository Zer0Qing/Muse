package io.zer0.muse.data

import kotlinx.serialization.Serializable

/**
 * E3: 聊天动态渐变背景配置。
 *
 * 双色线性渐变(左上→右下);Long 存 ARGB 色值(如 0xFF1A2980),
 * UI 层用 [Long.toInt] 转 [androidx.compose.ui.graphics.Color]。
 * null 表示不使用渐变(仅默认背景色)。
 */
@Serializable
data class ChatGradient(
    val startColorArgb: Long,
    val endColorArgb: Long,
)
