package io.zer0.muse.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * B6-02: 情绪皮肤专用调色板。
 *
 * 集中管理 mood skin 特效与全屏 overlay 的色值，避免 UI 文件中散落裸色。
 * 这些颜色是情绪皮肤协议的一部分，不属于 Material 语义色，因此放在 theme 域内单独维护。
 */
object MoodSkinColors {

    /** glow 特效高亮色。 */
    val glow = Color(0xFFFFB74D)

    /** whisper 特效弱化色。 */
    val whisper = Color(0xFF9E9E9E)

    /** red 特效强调色。 */
    val red = Color(0xFFE53935)

    /** shake 特效色。 */
    val shake = Color(0xFF8E24AA)

    /** blur 特效色。 */
    val blur = Color(0xFFBDBDBD)

    /** glitch 特效色。 */
    val glitch = Color(0xFF00ACC1)

    /** 默认全屏渐变。 */
    val defaultOverlay = listOf(
        Color(0x33333333),
        Color(0x22111111),
        Color(0x11000000),
    )

    /** 按 moodSkin 名称取全屏渐变。 */
    val overlays: Map<String, List<Color>> = mapOf(
        "rage" to listOf(Color(0x66111111), Color(0x55B71C1C), Color(0x22111111)),
        "rage2" to listOf(Color(0x66FF3D00), Color(0x33B71C1C), Color(0x22111111)),
        "desire" to listOf(Color(0x66B71C1C), Color(0x33FF6F91), Color(0x22111111)),
        "vuoto" to listOf(Color(0x66616A6B), Color(0x334A4A4A), Color(0x22111111)),
        "moonlight" to listOf(Color(0x660B1D4D), Color(0x33E1E9FF), Color(0x22111111)),
    )
}
