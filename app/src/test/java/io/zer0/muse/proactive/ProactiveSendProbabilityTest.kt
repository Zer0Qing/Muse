package io.zer0.muse.proactive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 发送概率闸。
 *
 * 概率是用户在设置页能看到的数字，所以两端必须守恒：0 永不发送，100 与没有这道闸
 * 完全一致。中间值用注入的掷骰结果验证两条分支 —— 去断言「跑一百次大约发五十次」
 * 只会得到一条偶发失败的测试，而真正要保证的是「掷骰结果怎么映射成发不发」。
 */
class ProactiveSendProbabilityTest {

    @Test
    fun `概率为 0 时永不发送`() {
        (0 until ProactiveSendProbability.ROLL_BOUND).forEach { roll ->
            assertFalse("roll=$roll", ProactiveSendProbability.allows(0, roll))
        }
    }

    @Test
    fun `概率为 100 时全发`() {
        (0 until ProactiveSendProbability.ROLL_BOUND).forEach { roll ->
            assertTrue("roll=$roll", ProactiveSendProbability.allows(100, roll))
        }
    }

    @Test
    fun `概率为 50 时按掷骰结果二分`() {
        assertTrue(ProactiveSendProbability.allows(50, 0))
        assertTrue(ProactiveSendProbability.allows(50, 49))
        assertFalse(ProactiveSendProbability.allows(50, 50))
        assertFalse(ProactiveSendProbability.allows(50, 99))
    }

    @Test
    fun `脏值按边界处理`() {
        assertTrue(ProactiveSendProbability.allows(150, 99))
        assertFalse(ProactiveSendProbability.allows(-10, 0))
        assertTrue(ProactiveSendProbability.allows(1, 0))
        assertFalse(ProactiveSendProbability.allows(1, 1))
    }

    @Test
    fun `巡检在生成之前真的读了配置里的概率`() {
        // 纯函数测完还得盯住接线：概率曾经是「界面在写、调度不读」的字段之一，
        // 这里做源码级断言，防止以后把这道闸留在函数里却没接到巡检上。
        val source = source("ProactiveCycleRunner.kt")
        assertTrue(
            "巡检必须用配置里的 sendProbability 掷骰",
            source.contains("ProactiveSendProbability.allows(ready.config.sendProbability"),
        )
        assertTrue("随机源必须可注入", source.contains("rollPercent"))
    }

    private fun source(name: String): String {
        val candidates = listOf(
            File("src/main/java/io/zer0/muse/proactive/$name"),
            File("app/src/main/java/io/zer0/muse/proactive/$name"),
        )
        val file = candidates.firstOrNull { it.exists() } ?: error("找不到 $name")
        return file.readText()
    }
}
