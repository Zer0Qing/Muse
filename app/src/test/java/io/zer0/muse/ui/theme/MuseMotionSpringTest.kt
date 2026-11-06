package io.zer0.muse.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.SpringSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹簧工厂的降级与参数契约测试（纯函数接缝，无需 Robolectric）。
 *
 * 钉死三件事：
 *  1. 三档参数与 [MuseAnimation] 常量一一对应（档位写错=全站手感漂移）；
 *  2. 动画缩放 <= 0 时弹簧退化为 snap——「系统关闭动画」必须对所有动效种类生效，
 *     不只是 tween；弹簧自身没有时长概念，缩放值只能以这种方式参与。
 *  3. 非法缩放（NaN/负数）按 1x 处理，与 tween 的 sanitize 口径一致。
 */
class MuseMotionSpringTest {

    @Test
    fun `tiers map to their declared physics parameters`() {
        assertTierParams(MuseSpringTier.RESPONSIVE, MuseAnimation.RESPONSIVE_DAMPING, MuseAnimation.RESPONSIVE_STIFFNESS)
        assertTierParams(MuseSpringTier.BOUNCY, MuseAnimation.BOUNCY_DAMPING, MuseAnimation.BOUNCY_STIFFNESS)
        assertTierParams(MuseSpringTier.STEADY, MuseAnimation.STEADY_DAMPING, MuseAnimation.STEADY_STIFFNESS)
    }

    @Test
    fun `reduced motion snaps springs to immediate completion`() {
        val spec = MuseMotion.springForScale<Float>(MuseSpringTier.BOUNCY, null, 0f)
        assertTrue(
            "动画缩放为 0 时必须是 snap，弹簧继续播放会无视「关闭动画」的系统设置",
            spec is SnapSpec<*>,
        )
    }

    @Test
    fun `invalid scales fall back like tween`() {
        val nan = MuseMotion.springForScale<Float>(MuseSpringTier.RESPONSIVE, null, Float.NaN)
        assertTrue("NaN（读不到设置）应按 1x 出弹簧，不能把动效整体关掉", nan is SpringSpec<*>)
        val negative = MuseMotion.springForScale<Float>(MuseSpringTier.RESPONSIVE, null, -3f)
        assertTrue(
            "负缩放与 tween 同口径被 coerceAtLeast(0) 归零，等价「关闭动画」→ snap（保守降级）",
            negative is SnapSpec<*>,
        )
    }

    private fun assertTierParams(tier: MuseSpringTier, damping: Float, stiffness: Float) {
        val spec: FiniteAnimationSpec<Float> = MuseMotion.springForScale(tier, null, 1f)
        assertTrue("档位 $tier 在正常缩放下必须是 SpringSpec", spec is SpringSpec<Float>)
        spec as SpringSpec<Float>
        assertEquals(damping, spec.dampingRatio, 0.0001f)
        assertEquals(stiffness, spec.stiffness, 0.0001f)
    }
}
