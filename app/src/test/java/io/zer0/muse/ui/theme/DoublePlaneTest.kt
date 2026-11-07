package io.zer0.muse.ui.theme

import androidx.compose.ui.graphics.luminance
import io.zer0.muse.ui.theme.presets.WarmPaperTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「双平面」层级不变量（回归测试）。
 *
 * 设计目标：page 底色（background）与卡面（surface）靠明度差分层，不靠描边/阴影。
 * 归一化 [toWhiterNeutrals] 在运行时把底色压深、卡面提亮；预设原始值（底=卡）经它之后必须拉开。
 *
 * 背景：深色分支此前对 background 与 surface 用同一系数压黑，预设里底=卡（都 #000），
 * 压完仍相等 —— 深色根本没有层级。这里把"卡面必须亮于底色"钉成断言；旧实现深色会直接红。
 *
 * 用 luminance 作代理：它是"这块卡会不会从页面上浮起来"的客观量度，比"我觉得好看"可复现。
 * 浅色 gap 阈值对齐已定的 8%/35% 校准（旧 4.5%/0 约 0.095，会被 0.10 拦下）；
 * 深色只钉方向与非零间隔，具体幅度待深色样张/实机校准。
 */
class DoublePlaneTest {

    @Test
    fun `浅色卡面必须明显亮于页面底色`() {
        val raw = WarmPaperTheme.lightScheme
        val plane = raw.toWhiterNeutrals(darkTheme = false)
        val gap = plane.surface.luminance() - plane.background.luminance()
        assertTrue(
            "浅色双平面失效：卡面 luminance=${plane.surface.luminance()} 底=${plane.background.luminance()} gap=$gap",
            gap >= 0.10f,
        )
    }

    @Test
    fun `深色卡面必须亮于页面底色而非同系数压黑`() {
        val raw = WarmPaperTheme.darkScheme
        val plane = raw.toWhiterNeutrals(darkTheme = true)
        val gap = plane.surface.luminance() - plane.background.luminance()
        assertTrue(
            "深色双平面失效（底=卡？）：卡面 luminance=${plane.surface.luminance()} 底=${plane.background.luminance()} gap=$gap",
            gap > 0.004f,
        )
    }

    @Test
    fun `归一化只动底层面色不碰前景与品牌色`() {
        listOf(false, true).forEach { dark ->
            val raw = if (dark) WarmPaperTheme.darkScheme else WarmPaperTheme.lightScheme
            val plane = raw.toWhiterNeutrals(darkTheme = dark)
            assertEquals("onSurface 不该被归一化改动", raw.onSurface, plane.onSurface)
            assertEquals("onBackground 不该被归一化改动", raw.onBackground, plane.onBackground)
            assertEquals("onSurfaceVariant 不该被归一化改动", raw.onSurfaceVariant, plane.onSurfaceVariant)
            assertEquals("primary 不该被归一化改动", raw.primary, plane.primary)
            assertEquals("onPrimary 不该被归一化改动", raw.onPrimary, plane.onPrimary)
            assertEquals("error 不该被归一化改动", raw.error, plane.error)
        }
    }
}
