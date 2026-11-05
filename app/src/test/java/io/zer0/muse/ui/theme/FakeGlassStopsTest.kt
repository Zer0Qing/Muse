package io.zer0.muse.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 假玻璃色阶的明暗分向（回归测试）。
 *
 * 背景：2026-11-06 用户截图指出深色模式下菜单与底部面板是一块"很奇怪的气泡底"。
 * 根因是旧实现无条件 `lerp(base, White, 0.4~0.7)` —— 深色底被提成中灰到浅灰。
 * 这里把"暗色必须保持深色"钉成断言；旧实现会直接红。
 *
 * 用 luminance 作代理：它是"这块面在深色页面上会不会跳出来"的客观量度，
 * 比"我觉得好看"可复现。
 */
class FakeGlassStopsTest {

    private fun cfg(strength: Float = 0.5f, style: GlassStyle = GlassStyle.FROST) =
        LiquidGlassConfig(style = style, strength = strength, mode = LiquidGlassConfig.MODE_FROST)

    private val darkBase = Color(0xFF0F0D13) // M3 暗色 surfaceContainerLowest
    private val darkSurface = Color(0xFF141218)
    private val lightBase = Color(0xFFF7F2FA)

    @Test
    fun `暗色下任何一档都必须仍是深色`() {
        for (strength in listOf(0.2f, 0.5f, 1f)) {
            for (base in listOf(darkBase, darkSurface)) {
                val stops = fakeGlassStops(base, cfg(strength))
                assertEquals("色阶必须是三段", 3, stops.size)
                stops.forEach { (pos, c) ->
                    // 0.25 是宽松上限：旧实现会到 0.4~0.7，新实现顶部约 0.02~0.05
                    assertTrue(
                        "暗色 base=$base strength=$strength 在 $pos 处亮度 ${c.luminance()} 已不算深色",
                        c.luminance() < 0.25f,
                    )
                }
            }
        }
    }

    @Test
    fun `暗色底部要比顶部更沉（体积感方向不能反）`() {
        val stops = fakeGlassStops(darkBase, cfg())
        assertTrue(
            "暗色玻璃应当顶部略亮、底部略暗；反了就是'上薄下厚'的塑料片",
            stops.first().second.luminance() > stops.last().second.luminance(),
        )
    }

    @Test
    fun `亮色仍是向白提亮的老行为`() {
        val stops = fakeGlassStops(lightBase, cfg())
        // 只断言真正的事：亮色下每一档都比基色更白（乳白玻璃）。
        // 上一版我写了"顶部要比底部亮"，那是没核实旧行为就臆造的方向 ——
        // 旧实现底部 white-alpha(0.705) 本来就大于顶部(0.435)，即底部更白。测试当场红，说明我错。
        stops.forEach { (pos, c) ->
            assertTrue(
                "亮色下 $pos 处应当比基色更亮（旧行为是向白提亮）",
                c.luminance() >= lightBase.luminance() - 0.001f,
            )
        }
        assertEquals("亮色仍要三段", 3, stops.size)
    }

    @Test
    fun `强度越高暗色压得越深`() {
        val weak = fakeGlassStops(darkBase, cfg(0.1f)).last().second.luminance()
        val strong = fakeGlassStops(darkBase, cfg(1f)).last().second.luminance()
        assertTrue("强度滑条在暗色下也要有效果（低=$weak 高=$strong）", strong <= weak)
    }

    @Test
    fun `顶部的环境光不许超过基色太多`() {
        val top = fakeGlassStops(darkBase, cfg(1f)).first().second.luminance()
        val base = darkBase.luminance()
        assertTrue("顶边只是'一丝'光，不是一层白雾", top - base < 0.08f)
    }
}
