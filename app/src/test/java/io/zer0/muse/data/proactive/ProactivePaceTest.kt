package io.zer0.muse.data.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ProactivePace] 档位反推的单元测试。
 *
 * 重点验证：默认配置必须落在 STANDARD，且每一档的 (interval, probability, dailyLimit)
 * 都能原样反推回自身（保证 UI 选档后不会显示成「自定义」）。
 */
class ProactivePaceTest {

    @Test
    fun `default config maps to standard`() {
        // ProactiveMessageConfig 默认值：240 / 100 / 3
        assertEquals(ProactivePace.STANDARD, ProactivePace.from(240, 100, 3))
    }

    @Test
    fun `every pace round trips`() {
        ProactivePace.values().forEach { pace ->
            assertEquals(
                pace,
                ProactivePace.from(pace.intervalMinutes, pace.sendProbability, pace.maxDailyMessages),
            )
        }
    }

    @Test
    fun `custom values return null`() {
        assertNull(ProactivePace.from(200, 77, 5))
        assertNull(ProactivePace.from(240, 100, 2))
    }
}
