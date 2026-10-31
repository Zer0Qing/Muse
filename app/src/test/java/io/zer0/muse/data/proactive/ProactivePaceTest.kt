package io.zer0.muse.data.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ProactivePace] 档位反推的单元测试。
 *
 * 重点验证：默认配置必须落在 STANDARD，且每一档的 (interval, probability)
 * 都能原样反推回自身（保证 UI 选档后不会显示成「自定义」）。
 *
 * 每日条数不在本枚举里，由 [ProactiveIntensity.cap] 决定，因此档位反推只看
 * 间隔与概率两个维度。
 */
class ProactivePaceTest {

    @Test
    fun `default config maps to standard`() {
        // ProactiveMessageConfig 默认值：240 分钟 / 100%
        assertEquals(ProactivePace.STANDARD, ProactivePace.from(240, 100))
    }

    @Test
    fun `every pace round trips`() {
        ProactivePace.values().forEach { pace ->
            assertEquals(
                pace,
                ProactivePace.from(pace.intervalMinutes, pace.sendProbability),
            )
        }
    }

    @Test
    fun `custom values return null`() {
        assertNull(ProactivePace.from(200, 77))
        assertNull(ProactivePace.from(240, 50))
    }
}
